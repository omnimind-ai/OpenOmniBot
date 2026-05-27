# OmnibotApp Desktop

macOS (and forthcoming Windows) port of OmnibotApp. The Flutter UI from `ui/` is reused unchanged; the Kotlin Agent runtime is replaced by a standalone Rust backend that the Flutter app spawns as a child process and talks to over a local WebSocket.

## Directory layout

```
desktop/
├── runner/        Flutter desktop app (host for the UI module + spawn glue for the backend)
│   ├── lib/main.dart          Installs the channel bridge and boots the ui module
│   ├── macos/                 AppDelegate + BackendSupervisor.swift (~50 lines glue)
│   ├── windows/               main.cpp + backend_supervisor.{h,cpp} (CreateProcess + JobObject)
│   ├── pubspec.yaml           path-depends on ../../ui
│   └── tool/                  package_macos.sh + package_windows.ps1
├── backend/       Cargo workspace (eight crates)
│   ├── Cargo.toml
│   └── crates/
│       ├── omnibot-backend/   axum WS server + channel router + handlers
│       ├── omnibot-agent/     orchestrator / memory / stream events / prompt / compactor / subagent / mode_policy / tool_catalog
│       ├── omnibot-llm/       SSE stream accumulator + HTTP LLM client + reasoning policy + provider profile storage
│       ├── omnibot-tools/     ToolHandler trait + handlers (file/terminal/skills/memory/image_gen/mcp/subagent/context)
│       ├── omnibot-mcp/       Remote MCP client (JSON-RPC + SSE auto-detect + session header)
│       ├── omnibot-db/        sqlx + SQLite migrations (3 core tables + ai_request_logs + codex_thread_binding stub)
│       ├── omnibot-storage/   AppPaths + KvStore + WorkspaceStore
│       └── omnibot-common/    AppError + types + tracing init
└── README.md      (this file)
```

The Dart-side bridge code lives in `../ui/lib/desktop/channel_bridge/` so it can share the `ui` package's import graph but stays dead code on Android.

## Toolchain prerequisites

- Flutter `stable` ≥ 3.38 (run `flutter config --enable-macos-desktop --enable-windows-desktop`)
- Xcode ≥ 14 (macOS)
- Visual Studio 2022 Build Tools (Windows)
- Rust 1.95+ via `rustup` (the workspace pins this in `desktop/backend/rust-toolchain.toml`)
- For packaging: `create-dmg` (macOS) and Inno Setup 6 (Windows)

## Quick start (development)

```bash
# 1) Build the Rust backend in debug mode and verify it runs.
cd desktop/backend
cargo run -p omnibot-backend -- --data-dir /tmp/omni-data --bind 127.0.0.1:0
# Expect first stdout line: OMNIBOT_BACKEND_PORT=<port>; Ctrl-C to stop.

# 2) Build the Flutter app (Xcode build phases run cargo automatically).
cd ../runner
flutter run -d macos       # or `flutter build macos --debug`
```

The macOS Xcode project includes two `PBXShellScriptBuildPhase` entries:
- *Build Rust backend* — runs `cargo build [--release] -p omnibot-backend`.
- *Copy Rust backend binary* — copies `target/.../omnibot-backend` into `Contents/Resources/omnibot-backend` so the supervisor finds it at runtime.

On Windows, `windows/CMakeLists.txt` adds an `omnibot_backend_build` `add_custom_target(ALL ...)` and an `install(FILES ...)` rule that drops `omnibot-backend.exe` next to the runner exe.

## Channel bridge protocol

The Dart side replaces the host `BinaryMessenger` with `BridgingBinaryMessenger`, which converts `MethodChannel.invokeMethod` calls into JSON frames over `ws://127.0.0.1:<port>/channel`. EventChannels are multiplexed on the same socket via `event_listen` / `event_data` / `event_cancel` frames. See `ui/lib/desktop/channel_bridge/bridge_protocol.dart` and `omnibot-backend/src/api/envelope.rs` for the schema (kept in sync by hand).

Channels are split three ways:
- **Bridged → backend**: `AssistCoreEvent`, `CacheDataEvent`, `McpServer`, `network`, `CodexAppServer` (stub), `device_info`, `app_state`, `app_update`, `file_save`, `RemoteMcpConfig`.
- **Dart-side stubs**: `overlay`, `SpecialPermissionEvent`, `hide_from_recents`, `mnn_local_models`, `VoicePlayback`, `StorageUsage`, `pdf_preview`, `screen_dialog` — return safe defaults so Android-only code paths don't crash.
- **Local-only**: `ui_router_channel` is Dart-to-Dart, never crosses the bridge.

## Milestone status (initial implementation)

| Milestone | Scope | Status |
|---|---|---|
| **M0** | Channel bridge + axum WS + chat task end-to-end | ✅ Compiles, `.app` bundles the backend, manual smoke verified (`OMNIBOT_BACKEND_PORT=...` printed, WS reachable) |
| **M1** | Agent orchestrator + persistence + conversation/model-provider CRUD | ✅ Code complete (see `omnibot-agent/src/orchestrator.rs` + `assist_core/`) |
| **M2** | Tool handlers + Remote MCP client (1:1 of `RemoteMcpClient.kt`) | ✅ Code complete (file/terminal/skills/memory/image_gen/mcp/subagent/context); browser handler reserved for follow-up |
| **M3** | Context compaction + tool streaming progress | 🟡 Compactor + terminal streaming written; orchestrator hook for compactor lives behind a `compact_if_needed` call site that is currently not auto-invoked — flip it on once `prompt_tokens` plumbing is verified end-to-end |
| **M4** | Windows platform | ✅ Code in place (`windows/runner/backend_supervisor.{h,cpp}` + CMake integration); requires a Windows machine to verify the build |
| **M5** | Packaging | ✅ `tool/package_macos.sh` (universal lipo + codesign + notarize + dmg) and `tool/package_windows.ps1` (release build + Inno Setup) |

## Known follow-ups

- Wire `AgentConversationContextCompactor` into the orchestrator's round loop and recompute `messages` after compaction (M3 finishing touches).
- Replace the M0 placeholder shell in `runner/lib/main.dart` with the full `ui` chat UI once `bootstrapMain` is callable on desktop (drop `mobile_scanner` / `image_picker` on Platform.isDesktop, swap WebView backend).
- Bridge the EventChannels listed in `bridge_websocket_client.dart` against `BridgingBinaryMessenger._handleEventChannel` after testing live subscriptions.
- Code-sign + notarize CI step (currently the packaging script supports both, but credentials are not stored in CI).

## Data locations

| Purpose | macOS | Windows |
|---|---|---|
| Database, KV, workspaces | `~/Library/Application Support/OmnibotApp/` | `%APPDATA%\OmnibotApp\` |
| Logs | `~/Library/Logs/OmnibotApp/` | `%LOCALAPPDATA%\OmnibotApp\logs\` |
| Cache | `~/Library/Caches/OmnibotApp/` | `%LOCALAPPDATA%\OmnibotApp\cache\` |

Override the data dir by passing `--data-dir <path>` to the backend binary directly (useful for tests).

## Troubleshooting

- **Backend doesn't start in the app**: the supervisor pipes stderr to `~/Library/Logs/OmnibotApp/backend-stdout.log` (macOS) and `%LOCALAPPDATA%\OmnibotApp\logs\backend-stdout.log` (Windows). The first line in stdout must read `OMNIBOT_BACKEND_PORT=<n>`; if the binary panics earlier the supervisor will surface it.
- **`cargo build` fails inside Xcode but works in the terminal**: Xcode resets `PATH`. The `Build Rust backend` shell phase prepends `$HOME/.cargo/bin:/opt/homebrew/bin`, so make sure cargo lives there (or update the phase).
- **Bridge calls hang forever**: confirm the WS is listening with `curl -i http://127.0.0.1:<port>/health` (it returns `{"ok": true, ...}`).
