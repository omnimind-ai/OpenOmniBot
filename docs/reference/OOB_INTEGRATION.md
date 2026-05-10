# OOB Workbench Integration

This note tracks the native Workbench boundary used by OOB Project editing.

## Project Templates

- `todo_log_demo` is only the demo template. It registers `todo.add` and `todo.finish`, persists `data/todos.json`, and renders `/workbench/todo_log`.
- `schema_app` is the first generic Project template. It creates an OOB-native schema display, registers Project APIs such as `<entity>.create` and `<entity>.archive`, persists `data/items.json`, and renders `/workbench/schema_app`.
- `quick_capture_inbox` is the daily-use validation template for 随手记/Inbox Projects. It registers `capture.ingest`, `capture.archive`, `capture.promote_to_todo`, and `capture.summarize`, persists `data/items.json`, writes workspace-script contract artifacts under `backend/`, and renders `/workbench/quick_capture`.

## Frontend And Backend Contract

- Project creation remains a Workbench control API: `workbench_project_create`.
- Active Project selection remains a Workbench control API:
  `workbench_project_activate`, `workbench_project_active_get`, and
  `workbench_project_deactivate`.
- Project business APIs remain in the Project API Registry and are called through `workbench_api_call`.
- Project creation/import progress remains a Workbench control API: `workbench_project_progress_get`.
- OSS/GitHub source ingest remains a Workbench control API: `workbench_project_ingest_oss`. URL-only ingest records `requiresFetch=true`; local downloaded source paths are copied and analyzed.
- Generated frontend contracts live in `frontend/page_spec.json`.
- Backend API contracts live in `backend/api_spec.json`.
- Source ingest contracts live in `source/manifest.json`.
- UI clicks and AI calls share the same native executor path and append to `logs/api_calls.jsonl`.
- Authenticated backend E2E can call `POST /mcp/workbench/call` with the local MCP/Dashboard bearer token. This debug transport calls the same native `WorkbenchProjectStore` methods but is not part of MCP tool discovery and never enters Project API Registry. `workbench_project_open` on this route also navigates the native OOB UI through `TaskCompletionNavigator`, so the test can prove the actual Flutter Display is visible.

## Display Boundary

The Home Project surface embeds the active Project display as a child window. Schema Projects use the generic Flutter schema display; Quick Capture Projects use the native `workbench_quick_capture` display; `todo_log_demo` remains a regression/demo template. The Project manager remains a secondary control surface, but its landing page is only a compact Project list plus the current active Project. Tap a Project row to open details for activation, displays, Workspace, export, delete, and API execution counts.

The embedded Project toolbar stays fixed to four actions: open the current Project management page, show Project info, open the current Display fullscreen, and refresh the Project payload. Project switching and deeper management stay in `/workbench/projects`.

## Workspace Cache Boundary

Workspace entry restores the last cached Workspace container state first. If the user last viewed file browsing, OOB reopens the cached Workspace directory when it still exists under `/workspace`. If the user last viewed the Project Display container, OOB reopens that Project frontend surface. The top Workbench mark is the explicit toggle between the active Project frontend and Workspace file browsing; direct Workspace entry should not reset the user back to a fixed default page.

## Current E2E Shape

The normal prompt-generated path is `schema_app`: Home input -> Agent calls
`workbench_project_create` -> Agent calls `workbench_project_activate` -> Agent
seeds state through `workbench_api_call` -> OOB opens
`/workbench/schema_app?projectId=<id>`. The generated frontend and the Agent use
the same Project APIs, data files, and `logs/api_calls.jsonl`.

The deterministic device proof for the daily-use template is `quick_capture_inbox`:
Dashboard-token call -> `workbench_project_create` -> `workbench_project_activate`
-> `workbench_api_call(capture.ingest)` -> `workbench_project_open` -> native
`/workbench/quick_capture?projectId=oob-workbench-quick-capture` on
`emulator-5554`. The proof must include app-data files under
`workspace/projects/oob-workbench-quick-capture/` and a live screenshot of
`随手记 Inbox · NOTE`.

## Startup Boundary

Flutter first frame must not block on non-critical scheduler/workspace path work.
The app startup path only awaits storage and background service state with short
timeouts before allowing the first frame; scheduled-task and workspace-path
initialization continue asynchronously. This keeps Workbench Project pages
reachable during backend E2E on a fresh debug install.

## Backend Runtime Boundary

OOB Workbench is now the runtime container for backend assets as well as UI:

- Project creation writes `logs/project_progress.jsonl` and exposes the latest row as `lastProgress` in the Project payload.
- `backend/api_spec.json` records executor metadata, control API names, persistence paths, and source refs so a Project API can later move from native-backed execution to Bridge/Alpine without changing `workbench_api_call`.
- `workbench_project_ingest_oss` imports local source snapshots under `source/repos/<source-id>/`, skips dependency/build directories, detects package files such as `package.json`, `pyproject.toml`, `pubspec.yaml`, and Gradle files, and stores entrypoint hints in `source/manifest.json`.
- GitHub URL-only ingest is deliberately metadata-only. It is not a network fetch; after terminal/tool fetch downloads the repo, call the same API with `sourcePath`.
- Workbench control APIs still do not appear in `workbench_api_list`; only Project business APIs are exposed to the generated frontend.
- `/mcp/workbench/call` is the deterministic Dashboard-token test transport for Project backend runtime operations when VLM/model-provider setup is not the test subject.
