import http from 'node:http';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFile } from 'node:child_process';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';

const port = Number.parseInt(process.env.OMNIBOT_BRIDGE_PORT || '17321', 10);
const host = process.env.OMNIBOT_BRIDGE_HOST || '0.0.0.0';
const token = process.env.OMNIBOT_BRIDGE_TOKEN || '';
const codexBin = process.env.CODEX_BIN || 'codex';
const bridgeCwd = process.env.OMNIBOT_BRIDGE_CWD || process.cwd();
const codexHome = process.env.CODEX_HOME || '';
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
console.log(`Omnibot Codex bridge listening on ws://${host}:${port}/codex`);
console.log(`Health check: http://${host}:${port}/health`);
console.log(`Directory browser: http://${host}:${port}/fs/list`);
console.log(`Working directory: ${bridgeCwd}`);
if (token) {
  console.log('Token auth: enabled');
}
