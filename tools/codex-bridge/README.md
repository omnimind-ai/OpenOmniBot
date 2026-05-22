# Omnibot Codex Bridge

Self-host this small bridge on a Windows, macOS, or Linux PC that already has the OpenAI Codex CLI installed and logged in. Omnibot connects to the bridge over WebSocket, and the bridge starts `codex app-server` locally on the PC.

The bridge also exposes authenticated HTTP helpers used by Omnibot:

- `GET /health`: check bridge and Codex CLI availability.
- `GET /fs/list?path=/abs/path`: list remote directories for the in-app cwd picker.
- `GET /fs/read?path=/abs/path`: read a remote file for preview/editing.
- `POST /fs/write`: write UTF-8 text back to a remote file.
- `POST /fs/delete`: delete a remote file or directory.
- `POST /fs/move`: rename or move a remote file or directory.

Codex sessions are read through the proxied `codex app-server` protocol, so the app uses the same session list flow for local and remote Codex.

## Run

Recommended one-shot startup:

```bash
npx @thuocean/codex-bridge --cwd "/Users/you/code/project" --token auto
```

Windows PowerShell example:

```powershell
npx @thuocean/codex-bridge --cwd "C:\Users\you\code\project" --token auto
```

Or install it globally:

```bash
npm install -g @thuocean/codex-bridge
codex-bridge "/Users/you/code/project" --token auto
```

When the bridge starts, it prints a terminal QR code. In Omnibot, tap Settings -> 服务与环境 -> Codex -> 扫码连接 to fill the remote Bridge URL, cwd, and token automatically.

For local development from this repository:

```bash
cd tools/codex-bridge
npm install
npm start -- --cwd "/Users/you/code/project" --token auto
```

If you do not use the QR code, set these values in Omnibot under Settings -> 服务与环境 -> Codex:

- Bridge URL: the printed `Quick connect bridge URL`, usually `ws://<pc-lan-ip>:17321/codex`
- Remote cwd: the project path passed with `--cwd`
- Bridge Token: the printed `Bridge token`; with `--token auto` it is generated for this run

For WAN access, put this behind Tailscale, WireGuard, a trusted reverse proxy with TLS, or another private network path. Do not expose the bridge directly to the public internet.

If the printed IP is not reachable from your phone, override the advertised address:

```bash
npx @thuocean/codex-bridge --cwd "/Users/you/code/project" --token auto --public-host 192.168.1.20
```

## CLI Options

- `--cwd <path>` or positional `project-dir`: Codex working directory, default current directory
- `--token <value|auto>`: bearer token; `auto` generates a random token for this run
- `--no-token`: disable token auth for trusted private networks
- `--host <host>`: listen host, default `0.0.0.0`
- `--port <port>`: listen port, default `17321`
- `--public-host <host>`: advertised host/IP used in the QR code
- `--codex-bin <path>`: Codex executable, default `codex`
- `--codex-home <path>`: optional `CODEX_HOME` override

## Environment

- `OMNIBOT_BRIDGE_HOST`: listen host, default `0.0.0.0`
- `OMNIBOT_BRIDGE_PUBLIC_HOST`: optional advertised host/IP used in the QR code
- `OMNIBOT_BRIDGE_PORT`: listen port, default `17321`
- `OMNIBOT_BRIDGE_TOKEN`: optional bearer token; set to `auto` to generate one
- `OMNIBOT_BRIDGE_CWD`: default project directory
- `OMNIBOT_BRIDGE_MAX_READ_BYTES`: max file preview payload, default 12 MiB
- `CODEX_BIN`: Codex executable, default `codex`
- `CODEX_HOME`: optional Codex config directory override

## Troubleshooting

If Omnibot can reach the bridge but reports that remote Codex is unavailable, open the printed health check URL:

```bash
curl -H "Authorization: Bearer <token>" http://<pc-lan-ip>:17321/health
```

`ready: false` usually means the PC cannot run `codex --version`. Install/login the OpenAI Codex CLI on the PC, make sure `codex` is on `PATH`, or start the bridge with an explicit executable:

```bash
npx @thuocean/codex-bridge --cwd "/Users/you/code/project" --token auto --codex-bin /absolute/path/to/codex
```

On Windows, npm usually installs command shims as `.cmd` files. If the health check still says `ready: false`, run this in PowerShell:

```powershell
where.exe codex
codex --version
```

Then pass the `.cmd` path printed by `where.exe`:

```powershell
npx @thuocean/codex-bridge --cwd "C:\Users\you\code\project" --token auto --codex-bin "C:\Users\you\AppData\Roaming\npm\codex.cmd"
```
