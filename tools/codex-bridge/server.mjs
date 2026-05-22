#!/usr/bin/env node

import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFile } from 'node:child_process';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { WebSocketServer } from 'ws';

const require = createRequire(import.meta.url);
const qrcode = require('qrcode-terminal');

function printHelp() {
  console.log(`Omnibot Codex Bridge

Usage:
  omnibot-codex-bridge [project-dir] [options]
  npx @omnibot/codex-bridge --cwd /path/to/project --token auto

Options:
  --cwd <path>            Codex working directory. Defaults to current directory.
  --token <value|auto>    Bearer token. Use "auto" to generate one for this run.
  --no-token              Disable token auth.
  --host <host>           Listen host. Defaults to 0.0.0.0.
  --port <port>           Listen port. Defaults to 17321.
  --public-host <host>    Host/IP printed in the QR code.
  --codex-bin <path>      Codex executable. Defaults to codex.
  --codex-home <path>     Optional CODEX_HOME override.
  -h, --help              Show this help.

Environment variables with the same meaning are also supported:
  OMNIBOT_BRIDGE_CWD, OMNIBOT_BRIDGE_TOKEN, OMNIBOT_BRIDGE_HOST,
  OMNIBOT_BRIDGE_PORT, OMNIBOT_BRIDGE_PUBLIC_HOST, CODEX_BIN, CODEX_HOME`);
}

function readOptionValue(args, index, option) {
  const arg = args[index];
  const equalsIndex = arg.indexOf('=');
  if (equalsIndex >= 0) {
    return { value: arg.slice(equalsIndex + 1), nextIndex: index };
  }
  const value = args[index + 1];
  if (value == null || value.startsWith('-')) {
    throw new Error(`${option} requires a value.`);
  }
  return { value, nextIndex: index + 1 };
}

function parseCliArgs(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === '-h' || arg === '--help') {
      options.help = true;
      continue;
    }
    if (arg === '--no-token') {
      options.token = '';
      continue;
    }
    const optionMap = {
      '--cwd': 'cwd',
      '--token': 'token',
      '--host': 'host',
      '--port': 'port',
      '--public-host': 'publicHost',
      '--codex-bin': 'codexBin',
      '--codex-home': 'codexHome',
    };
    const matchedOption = Object.keys(optionMap).find(
      (option) => arg === option || arg.startsWith(`${option}=`)
    );
    if (matchedOption) {
      const parsed = readOptionValue(args, index, matchedOption);
      options[optionMap[matchedOption]] = parsed.value;
      index = parsed.nextIndex;
      continue;
    }
    if (arg.startsWith('-')) {
      throw new Error(`Unknown option: ${arg}`);
    }
    if (!options.cwd) {
      options.cwd = arg;
      continue;
    }
    throw new Error(`Unexpected argument: ${arg}`);
  }
  return options;
}

function resolveToken(cliToken) {
  const rawToken =
    cliToken === undefined ? process.env.OMNIBOT_BRIDGE_TOKEN || '' : cliToken;
  return rawToken === 'auto' ? crypto.randomBytes(16).toString('hex') : rawToken;
}

function expandHomePath(rawPath) {
  const value = String(rawPath || '').trim();
  if (value === '~') return os.homedir();
  if (value.startsWith('~/') || value.startsWith('~\\')) {
    return path.join(os.homedir(), value.slice(2));
  }
  return value;
}

let cliOptions;
try {
  cliOptions = parseCliArgs(process.argv.slice(2));
} catch (error) {
  console.error(error.message);
  console.error('Run with --help for usage.');
  process.exit(1);
}

if (cliOptions.help) {
  printHelp();
  process.exit(0);
}

const port = Number.parseInt(
  cliOptions.port || process.env.OMNIBOT_BRIDGE_PORT || '17321',
  10
);
if (!Number.isFinite(port) || port <= 0 || port > 65535) {
  console.error(`Invalid bridge port: ${cliOptions.port || process.env.OMNIBOT_BRIDGE_PORT}`);
  process.exit(1);
}
const host = cliOptions.host || process.env.OMNIBOT_BRIDGE_HOST || '0.0.0.0';
const publicHost =
  cliOptions.publicHost || process.env.OMNIBOT_BRIDGE_PUBLIC_HOST || '';
const token = resolveToken(cliOptions.token);
const codexBin = cliOptions.codexBin || process.env.CODEX_BIN || 'codex';
const bridgeCwd = path.resolve(
  expandHomePath(
    cliOptions.cwd || process.env.OMNIBOT_BRIDGE_CWD || process.cwd()
  )
);
const codexHome = expandHomePath(
  cliOptions.codexHome || process.env.CODEX_HOME || ''
);
const homeDir = os.homedir();

function isAuthorized(req, payloadToken = '') {
  if (!token) return true;
  const header = req.headers.authorization || '';
  const bearer = header.startsWith('Bearer ') ? header.slice('Bearer '.length) : '';
  const custom = req.headers['x-omnibot-bridge-token'] || '';
  return token === bearer || token === custom || token === payloadToken;
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

function isWildcardHost(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return !normalized || normalized === '0.0.0.0' || normalized === '::' || normalized === '[::]';
}

function isLoopbackHost(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return normalized === 'localhost' || normalized === '127.0.0.1' || normalized === '::1' || normalized === '[::1]';
}

function isPrivateIpv4(address) {
  const parts = address.split('.').map((part) => Number.parseInt(part, 10));
  if (parts.length !== 4 || parts.some((part) => Number.isNaN(part))) return false;
  const [a, b] = parts;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function isLinkLocalIpv4(address) {
  return address.startsWith('169.254.');
}

function networkInterfacePenalty(name) {
  const normalized = name.toLowerCase();
  if (/^(docker|br-|veth|vmnet|utun|awdl|llw|lo)/.test(normalized)) return 40;
  if (/(virtual|bridge|vmware|vbox|tailscale|zerotier)/.test(normalized)) return 25;
  return 0;
}

function listAdvertisableIpv4Addresses() {
  const entries = [];
  for (const [name, addresses] of Object.entries(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family !== 'IPv4' || address.internal || !address.address) continue;
      if (isLoopbackHost(address.address) || isLinkLocalIpv4(address.address)) continue;
      const privateScore = isPrivateIpv4(address.address) ? 0 : 15;
      entries.push({
        name,
        address: address.address,
        score: privateScore + networkInterfacePenalty(name),
      });
    }
  }
  entries.sort((a, b) => a.score - b.score || a.name.localeCompare(b.name) || a.address.localeCompare(b.address));
  return entries;
}

function advertisedHosts() {
  const explicit = publicHost.trim();
  if (explicit) return [{ address: explicit, name: 'OMNIBOT_BRIDGE_PUBLIC_HOST' }];
  if (!isWildcardHost(host)) {
    return [{ address: host, name: 'OMNIBOT_BRIDGE_HOST' }];
  }
  const addresses = listAdvertisableIpv4Addresses();
  if (addresses.length > 0) return addresses;
  return [{ address: isWildcardHost(host) ? 'localhost' : host, name: 'fallback' }];
}

function hostForUrl(value) {
  const normalized = String(value || '').trim();
  return normalized.includes(':') && !normalized.startsWith('[') ? `[${normalized}]` : normalized;
}

function bridgeWebSocketUrl(advertisedHost) {
  return `ws://${hostForUrl(advertisedHost)}:${port}/codex`;
}

function quickConnectPayload(bridgeUrl) {
  const payload = new URL('omnibot://codex-bridge');
  payload.searchParams.set('bridgeUrl', bridgeUrl);
  payload.searchParams.set('cwd', bridgeCwd);
  if (token) payload.searchParams.set('token', token);
  return payload.toString();
}

function readCodexVersion() {
  return new Promise((resolve) => {
    execFile(codexBin, ['--version'], { cwd: bridgeCwd, timeout: 5000 }, (error, stdout, stderr) => {
      if (error) {
        resolve({ ok: false, error: stderr?.trim() || error.message });
        return;
      }
      resolve({ ok: true, version: stdout.trim() });
    });
  });
}

function resolveDirectoryPath(rawPath = '') {
  const requested = String(rawPath || '').trim();
  if (!requested) return bridgeCwd;
  const expanded =
    requested === '~'
      ? homeDir
      : requested.startsWith('~/')
        ? path.join(homeDir, requested.slice(2))
        : requested;
  return path.isAbsolute(expanded)
    ? expanded
    : path.resolve(bridgeCwd, expanded);
}

function entryType(dirent) {
  if (dirent.isDirectory()) return 'directory';
  if (dirent.isFile()) return 'file';
  if (dirent.isSymbolicLink()) return 'symlink';
  return 'other';
}

async function listDirectory(rawPath = '') {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realPath = await fs.realpath(resolvedPath);
  const stat = await fs.stat(realPath);
  if (!stat.isDirectory()) {
    const error = new Error('path is not a directory');
    error.status = 400;
    throw error;
  }
  const dirents = await fs.readdir(realPath, { withFileTypes: true });
  const entries = dirents
    .map((dirent) => {
      const type = entryType(dirent);
      return {
        name: dirent.name,
        path: path.join(realPath, dirent.name),
        type,
        hidden: dirent.name.startsWith('.'),
      };
    })
    .sort((a, b) => {
      if (a.type === 'directory' && b.type !== 'directory') return -1;
      if (a.type !== 'directory' && b.type === 'directory') return 1;
      return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
    });
  const parent = path.dirname(realPath);
  return {
    ok: true,
    path: realPath,
    parent: parent === realPath ? null : parent,
    cwd: bridgeCwd,
    home: homeDir,
    entries,
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname === '/health') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    const version = await readCodexVersion();
    sendJson(res, version.ok ? 200 : 503, {
      ok: version.ok,
      codexVersion: version.version || null,
      cwd: bridgeCwd,
      authRequired: Boolean(token),
      error: version.error || null,
    });
    return;
  }

  if (url.pathname === '/fs/list') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const payload = await listDirectory(url.searchParams.get('path') || '');
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to list directory',
        path: url.searchParams.get('path') || bridgeCwd,
        cwd: bridgeCwd,
        home: homeDir,
      });
    }
    return;
  }

  sendJson(res, 404, { ok: false, error: 'not_found' });
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname !== '/codex') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', async (ws, req) => {
  let codex = null;
  let initialized = false;

  function send(type, extra = {}) {
    if (ws.readyState === 1) {
      ws.send(JSON.stringify({ type, ...extra }));
    }
  }

  function closeCodex() {
    if (codex && !codex.killed) {
      codex.kill('SIGTERM');
    }
    codex = null;
  }

  ws.on('message', (data) => {
    let message;
    try {
      message = JSON.parse(data.toString('utf8'));
    } catch (error) {
      send('error', { message: `invalid JSON: ${error.message}` });
      return;
    }

    if (!initialized) {
      if (message.type !== 'hello') {
        send('error', { message: 'hello is required before stdin' });
        ws.close(1008, 'hello required');
        return;
      }
      if (!isAuthorized(req, String(message.token || ''))) {
        send('hello', { ok: false, message: 'unauthorized' });
        ws.close(1008, 'unauthorized');
        return;
      }
      initialized = true;
      const cwd = String(message.cwd || bridgeCwd).trim() || bridgeCwd;
      const env = { ...process.env };
      if (codexHome) env.CODEX_HOME = codexHome;
      codex = spawn(codexBin, ['app-server'], {
        cwd,
        env,
        stdio: ['pipe', 'pipe', 'pipe'],
      });
      codex.stdout.setEncoding('utf8');
      codex.stderr.setEncoding('utf8');
      let stdoutBuffer = '';
      let stderrBuffer = '';
      const drainLines = (chunk, previous, type) => {
        const combined = previous + chunk;
        const lines = combined.split(/\r?\n/);
        const tail = lines.pop() || '';
        for (const line of lines) {
          if (line.trim()) send(type, { line });
        }
        return tail;
      };
      codex.stdout.on('data', (chunk) => {
        stdoutBuffer = drainLines(chunk, stdoutBuffer, 'stdout');
      });
      codex.stderr.on('data', (chunk) => {
        stderrBuffer = drainLines(chunk, stderrBuffer, 'stderr');
      });
      codex.on('exit', (code) => {
        if (stdoutBuffer.trim()) send('stdout', { line: stdoutBuffer });
        if (stderrBuffer.trim()) send('stderr', { line: stderrBuffer });
        send('exit', { exitCode: code });
        ws.close(1011, 'codex exited');
      });
      codex.on('error', (error) => {
        send('error', { message: error.message });
        ws.close(1011, 'codex failed');
      });
      send('hello', { ok: true, cwd });
      return;
    }

    if (message.type === 'stdin') {
      if (!codex || !codex.stdin.writable) {
        send('error', { message: 'codex app-server is not running' });
        return;
      }
      codex.stdin.write(`${String(message.line || '')}\n`);
      return;
    }

    if (message.type === 'close') {
      ws.close(1000, 'client close');
    }
  });

  ws.on('close', closeCodex);
  ws.on('error', closeCodex);
});

server.listen(port, host);
await once(server, 'listening');
const advertised = advertisedHosts();
const primaryBridgeUrl = bridgeWebSocketUrl(advertised[0].address);
const payload = quickConnectPayload(primaryBridgeUrl);
console.log(`Omnibot Codex bridge listening on ws://${host}:${port}/codex`);
console.log(`Health check: http://${host}:${port}/health`);
console.log(`Directory browser: http://${host}:${port}/fs/list`);
console.log(`Working directory: ${bridgeCwd}`);
if (token) {
  console.log('Token auth: enabled');
  console.log(`Bridge token: ${token}`);
}
console.log(`Quick connect bridge URL: ${primaryBridgeUrl}`);
if (advertised.length > 1) {
  console.log(
    `Other detected LAN addresses: ${advertised
      .slice(1)
      .map((entry) => `${entry.address} (${entry.name})`)
      .join(', ')}`
  );
}
if (!publicHost.trim() && isWildcardHost(host)) {
  console.log('Set OMNIBOT_BRIDGE_PUBLIC_HOST to override the QR address if this IP is not reachable from your phone.');
}
if (!publicHost.trim() && isLoopbackHost(host)) {
  console.log('OMNIBOT_BRIDGE_HOST is loopback; phones can only connect through adb reverse, a tunnel, or another forwarded network path.');
}
console.log(`Quick connect payload: ${payload}`);
qrcode.generate(payload, { small: true }, (qr) => {
  console.log(qr);
});
