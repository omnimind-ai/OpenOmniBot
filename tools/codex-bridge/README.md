# Omnibot Codex Bridge

Self-host this small bridge on a Mac or Linux PC that already has the OpenAI Codex CLI installed and logged in. Omnibot connects to the bridge over WebSocket, and the bridge starts `codex app-server` locally on the PC.

The bridge also exposes authenticated HTTP helpers used by Omnibot:

- `GET /health`: check bridge and Codex CLI availability.
- `GET /fs/list?path=/abs/path`: list remote directories for the in-app cwd picker.

Codex sessions are read through the proxied `codex app-server` protocol, so the app uses the same session list flow for local and remote Codex.

## Run

```bash
cd tools/codex-bridge
npm install
OMNIBOT_BRIDGE_TOKEN="$(openssl rand -hex 16)" \
OMNIBOT_BRIDGE_CWD="/Users/you/code/project" \
npm start
```

Set the same values in Omnibot under Settings -> Scene Model Config -> Codex:

- Bridge URL: `ws://<pc-lan-ip>:17321/codex`
- Remote cwd: the project path on the PC
- Bridge Token: `OMNIBOT_BRIDGE_TOKEN`

For WAN access, put this behind Tailscale, WireGuard, a trusted reverse proxy with TLS, or another private network path. Do not expose the bridge directly to the public internet.

## Environment

- `OMNIBOT_BRIDGE_HOST`: listen host, default `0.0.0.0`
- `OMNIBOT_BRIDGE_PORT`: listen port, default `17321`
- `OMNIBOT_BRIDGE_TOKEN`: optional bearer token
- `OMNIBOT_BRIDGE_CWD`: default project directory
- `CODEX_BIN`: Codex executable, default `codex`
- `CODEX_HOME`: optional Codex config directory override
