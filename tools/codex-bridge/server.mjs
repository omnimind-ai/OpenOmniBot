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
const isWindows = process.platform === 'win32';

function printHelp() {
  console.log(`Omnibot Codex Bridge

Usage:
  codex-bridge [project-dir] [options]
  omnibot-codex-bridge [project-dir] [options]
  npx @thuocean/codex-bridge --cwd /path/to/project --token auto

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
const maxReadBytes = Number.parseInt(
  process.env.OMNIBOT_BRIDGE_MAX_READ_BYTES || `${12 * 1024 * 1024}`,
  10
);
const jsonContentType = 'application/json; charset=utf-8';

function bridgePath() {
  const candidates = [
    process.env.PATH || '',
    process.env.Path || '',
    process.env.path || '',
    process.env.npm_config_prefix || '',
    process.env.NPM_CONFIG_PREFIX || '',
    process.env.PNPM_HOME || '',
    process.env.APPDATA ? path.join(process.env.APPDATA, 'npm') : '',
    process.env.USERPROFILE ? path.join(process.env.USERPROFILE, 'AppData/Roaming/npm') : '',
    process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Programs/nodejs') : '',
    process.env.ProgramFiles ? path.join(process.env.ProgramFiles, 'nodejs') : '',
    process.execPath ? path.dirname(process.execPath) : '',
    '/opt/homebrew/bin',
    '/usr/local/bin',
    '/usr/bin',
    '/bin',
    path.join(homeDir, '.npm-global/bin'),
    path.join(homeDir, '.local/bin'),
    path.join(homeDir, '.cargo/bin'),
    path.join(homeDir, '.bun/bin'),
  ]
    .flatMap((entry) => entry.split(path.delimiter))
    .map((entry) => entry.trim())
    .filter(Boolean);
  return [...new Set(candidates)].join(path.delimiter);
}

function bridgePathEntries() {
  return bridgePath().split(path.delimiter).filter(Boolean);
}

function bridgeEnv() {
  const env = { ...process.env, PATH: bridgePath() };
  if (codexHome) env.CODEX_HOME = codexHome;
  return env;
}

function stripOuterQuotes(value) {
  const normalized = String(value || '').trim();
  if (
    normalized.length >= 2 &&
    ((normalized.startsWith('"') && normalized.endsWith('"')) ||
      (normalized.startsWith("'") && normalized.endsWith("'")))
  ) {
    return normalized.slice(1, -1);
  }
  return normalized;
}

async function fileExists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function resolveCodexCommand() {
  const raw = stripOuterQuotes(codexBin) || 'codex';
  const expanded = expandHomePath(raw);
  const hasPathSeparator = expanded.includes('/') || expanded.includes('\\');
  if (!isWindows || hasPathSeparator || path.extname(expanded)) {
    return expanded;
  }
  const names = [`${expanded}.cmd`, `${expanded}.exe`, `${expanded}.bat`, expanded];
  for (const entry of bridgePathEntries()) {
    for (const name of names) {
      const candidate = path.join(entry, name);
      if (await fileExists(candidate)) return candidate;
    }
  }
  return expanded;
}

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
    'content-type': jsonContentType,
    'content-length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

async function readJsonBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(Buffer.from(chunk));
  }
  const raw = Buffer.concat(chunks).toString('utf8').trim();
  if (!raw) return {};
  return JSON.parse(raw);
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

async function readCodexVersion() {
  const resolvedCodexBin = await resolveCodexCommand();
  return new Promise((resolve) => {
    execFile(
      resolvedCodexBin,
      ['--version'],
      {
        cwd: bridgeCwd,
        env: bridgeEnv(),
        timeout: 5000,
        shell: isWindows,
        windowsHide: true,
      },
      (error, stdout, stderr) => {
        if (error) {
          resolve({
            ok: false,
            error: stderr?.trim() || error.message,
            resolvedCodexBin,
          });
          return;
        }
        resolve({ ok: true, version: stdout.trim(), resolvedCodexBin });
      }
    );
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

function fileNameFromPath(filePath) {
  return path.basename(filePath);
}

function extensionFromPath(filePath) {
  return path.extname(filePath).toLowerCase();
}

function mimeTypeForPath(filePath) {
  const extension = extensionFromPath(filePath);
  const map = {
    '.aac': 'audio/aac',
    '.avi': 'video/x-msvideo',
    '.bmp': 'image/bmp',
    '.css': 'text/css',
    '.csv': 'text/csv',
    '.flac': 'audio/flac',
    '.gif': 'image/gif',
    '.htm': 'text/html',
    '.html': 'text/html',
    '.jpeg': 'image/jpeg',
    '.jpg': 'image/jpeg',
    '.js': 'text/javascript',
    '.json': 'application/json',
    '.jsonl': 'application/jsonl',
    '.m4a': 'audio/mp4',
    '.m4v': 'video/mp4',
    '.md': 'text/markdown',
    '.mov': 'video/quicktime',
    '.mp3': 'audio/mpeg',
    '.mp4': 'video/mp4',
    '.odp': 'application/vnd.oasis.opendocument.presentation',
    '.ods': 'application/vnd.oasis.opendocument.spreadsheet',
    '.odt': 'application/vnd.oasis.opendocument.text',
    '.ogg': 'audio/ogg',
    '.pdf': 'application/pdf',
    '.png': 'image/png',
    '.ppt': 'application/vnd.ms-powerpoint',
    '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    '.py': 'text/x-python',
    '.sh': 'application/x-sh',
    '.svg': 'image/svg+xml',
    '.ts': 'text/typescript',
    '.txt': 'text/plain',
    '.wav': 'audio/wav',
    '.webm': 'video/webm',
    '.webp': 'image/webp',
    '.xls': 'application/vnd.ms-excel',
    '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    '.xml': 'application/xml',
    '.yaml': 'application/yaml',
    '.yml': 'application/yaml',
  };
  return map[extension] || 'application/octet-stream';
}

function previewKindForPath(filePath, mimeType = mimeTypeForPath(filePath)) {
  const extension = extensionFromPath(filePath);
  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType.startsWith('audio/')) return 'audio';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType === 'text/html') return 'html';
  if (mimeType === 'text/markdown') return 'text';
  if (
    mimeType.startsWith('text/') ||
    [
      '.c',
      '.cc',
      '.cpp',
      '.dart',
      '.go',
      '.java',
      '.js',
      '.json',
      '.jsonl',
      '.kt',
      '.kts',
      '.mjs',
      '.py',
      '.rs',
      '.sh',
      '.sql',
      '.swift',
      '.toml',
      '.ts',
      '.tsx',
      '.xml',
      '.yaml',
      '.yml',
    ].includes(extension)
  ) {
    return 'code';
  }
  if (['.doc', '.docx', '.odt', '.rtf'].includes(extension)) return 'office_word';
  if (['.xls', '.xlsx', '.ods'].includes(extension)) return 'office_sheet';
  if (['.ppt', '.pptx', '.odp'].includes(extension)) return 'office_slide';
  return 'file';
}

function isProbablyText(buffer) {
  if (buffer.length === 0) return true;
  const sample = buffer.subarray(0, Math.min(buffer.length, 4096));
  let suspicious = 0;
  for (const byte of sample) {
    if (byte === 0) return false;
    if (byte < 7 || (byte > 14 && byte < 32)) suspicious += 1;
  }
  return suspicious / sample.length < 0.02;
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

async function readFilePayload(rawPath = '') {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realPath = await fs.realpath(resolvedPath);
  const stat = await fs.stat(realPath);
  if (!stat.isFile()) {
    const error = new Error('path is not a file');
    error.status = 400;
    throw error;
  }
  if (Number.isFinite(maxReadBytes) && stat.size > maxReadBytes) {
    const mimeType = mimeTypeForPath(realPath);
    return {
      ok: true,
      path: realPath,
      name: fileNameFromPath(realPath),
      type: 'file',
      size: stat.size,
      mtimeMs: stat.mtimeMs,
      mimeType,
      previewKind: previewKindForPath(realPath, mimeType),
      truncated: true,
      error: `file is larger than OMNIBOT_BRIDGE_MAX_READ_BYTES (${maxReadBytes})`,
    };
  }
  const bytes = await fs.readFile(realPath);
  const mimeType = mimeTypeForPath(realPath);
  let previewKind = previewKindForPath(realPath, mimeType);
  const textLike = previewKind === 'text' || previewKind === 'code' || isProbablyText(bytes);
  if (textLike && previewKind === 'file') previewKind = 'text';
  return {
    ok: true,
    path: realPath,
    name: fileNameFromPath(realPath),
    type: 'file',
    size: stat.size,
    mtimeMs: stat.mtimeMs,
    mimeType,
    previewKind,
    encoding: textLike ? 'utf8' : 'base64',
    content: textLike ? bytes.toString('utf8') : undefined,
    dataBase64: textLike ? undefined : bytes.toString('base64'),
  };
}

async function writeTextFile(rawPath = '', content = '') {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realParent = await fs.realpath(path.dirname(resolvedPath));
  const targetPath = path.join(realParent, path.basename(resolvedPath));
  await fs.writeFile(targetPath, String(content), 'utf8');
  const stat = await fs.stat(targetPath);
  return {
    ok: true,
    path: targetPath,
    name: fileNameFromPath(targetPath),
    type: 'file',
    size: stat.size,
    mtimeMs: stat.mtimeMs,
  };
}

async function deletePath(rawPath = '', recursive = false) {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realPath = await fs.realpath(resolvedPath);
  const stat = await fs.stat(realPath);
  await fs.rm(realPath, { recursive: Boolean(recursive), force: false });
  return {
    ok: true,
    path: realPath,
    type: stat.isDirectory() ? 'directory' : 'file',
  };
}

async function movePath(rawPath = '', rawDestinationPath = '') {
  const sourcePath = await fs.realpath(resolveDirectoryPath(rawPath));
  const destinationResolved = resolveDirectoryPath(rawDestinationPath);
  const destinationParent = await fs.realpath(path.dirname(destinationResolved));
  const destinationPath = path.join(destinationParent, path.basename(destinationResolved));
  await fs.rename(sourcePath, destinationPath);
  const stat = await fs.stat(destinationPath);
  return {
    ok: true,
    path: destinationPath,
    name: fileNameFromPath(destinationPath),
    type: stat.isDirectory() ? 'directory' : 'file',
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
    sendJson(res, 200, {
      ok: version.ok,
      ready: version.ok,
      codexVersion: version.version || null,
      codexBin,
      resolvedCodexBin: version.resolvedCodexBin || codexBin,
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

  if (url.pathname === '/fs/read') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const payload = await readFilePayload(url.searchParams.get('path') || '');
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to read file',
        path: url.searchParams.get('path') || '',
      });
    }
    return;
  }

  if (url.pathname === '/fs/write' && req.method === 'POST') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const payload = await writeTextFile(body.path, body.content);
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to write file',
      });
    }
    return;
  }

  if (url.pathname === '/fs/delete' && req.method === 'POST') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const payload = await deletePath(body.path, body.recursive);
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to delete path',
      });
    }
    return;
  }

  if (url.pathname === '/fs/move' && req.method === 'POST') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const payload = await movePath(body.path, body.destinationPath);
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to move path',
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
      if (isWindows && codex.pid) {
        const killer = spawn('taskkill', ['/pid', String(codex.pid), '/T', '/F'], {
          stdio: 'ignore',
          windowsHide: true,
        });
        killer.on('error', () => {});
      } else {
        codex.kill('SIGTERM');
      }
    }
    codex = null;
  }

  async function handleMessage(data) {
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
      const env = bridgeEnv();
      const resolvedCodexBin = await resolveCodexCommand();
      codex = spawn(resolvedCodexBin, ['app-server'], {
        cwd,
        env,
        shell: isWindows,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
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
      send('hello', { ok: true, cwd, codexBin: resolvedCodexBin });
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
  }

  ws.on('message', (data) => {
    handleMessage(data).catch((error) => {
      send('error', { message: error.message || 'bridge message handling failed' });
      ws.close(1011, 'bridge failed');
    });
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
console.log(`File browser API: http://${host}:${port}/fs/read`);
console.log(`Working directory: ${bridgeCwd}`);
const startupCodexVersion = await readCodexVersion();
if (startupCodexVersion.ok) {
  console.log(`Codex CLI: ${startupCodexVersion.version}`);
} else {
  console.log(`Codex CLI check failed: ${startupCodexVersion.error}`);
  console.log('Install/login Codex CLI or pass --codex-bin /absolute/path/to/codex.');
}
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
