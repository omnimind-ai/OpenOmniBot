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
- External MCP clients should treat the active Project as a Toolbox. `tools/list`
  returns fixed OOB MCP tools plus active Project dynamic tools in
  `<toolbox_id>.<api_slug>` form, such as `quick_note.capture_ingest`.
  Dynamic tools dispatch to the same Project API executor with
  `caller=mcp_toolbox`; external clients no longer need to know the internal
  `workbench_api_call(projectId, apiId)` shape.
- Project creation/import progress remains a Workbench control API: `workbench_project_progress_get`.
- OSS/GitHub source ingest remains a Workbench control API: `workbench_project_ingest_oss`. URL-only ingest records `requiresFetch=true`; local downloaded source paths are copied and analyzed.
- Generated frontend contracts live in `frontend/page_spec.json`.
- Backend Tool Contracts live in `backend/api_spec.json` and include MCP
  `toolName`, `apiVersion`, schemas, capabilities, side effects, data/log files,
  and examples.
- Source ingest contracts live in `source/manifest.json`.
- UI clicks and AI calls share the same native executor path and append to `logs/api_calls.jsonl`.
- MCP read-only context is exposed through `resources/list` and
  `resources/read` for Project manifest, active Project, Toolbox, progress, API
  logs, and source manifest. MCP prompt templates are exposed through
  `prompts/list` and `prompts/get`.
- Authenticated backend E2E can call `POST /mcp/workbench/call` with the local MCP/Dashboard bearer token. This debug transport calls the same native `WorkbenchProjectStore` methods but is not part of MCP tool discovery and never enters Project API Registry. `workbench_project_open` on this route switches to the Android main thread before navigating the native OOB UI through `TaskCompletionNavigator`, so the test can prove the actual Flutter Display is visible.
- The same authenticated debug route has local-only model-provider setup helpers: `debug_model_provider_configure` and `debug_model_provider_get`. They are for device E2E setup, write the normal provider/profile stores, sync Agent AI config, and return only `apiKeyConfigured` rather than the raw key.

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
`/workbench/quick_capture?projectId=oob-workbench-vlm-quick-note` on
`emulator-5554`. The proof must include app-data files under
`workspace/projects/oob-workbench-vlm-quick-note/` and a live screenshot of
`随手记 Inbox · NOTE`. The 2026-05-10 final smoke showed `3 active / 1 archived`,
`OOB native UI`, `4 APIs`, and Project API writes through both `mcp_dashboard`
and the native Flutter UI. Receipt/invoice text now lands in the current
frontend-supported `summary` bucket instead of being misclassified as `todo`.

The same run also validated the model-provider setup path enough for `vlm_task`
to reach DashScope (`scene.vlm.operation.primary` bound to `dashboard-e2e`).
It did not prove full VLM-to-Agent Project creation: VLM could click OOB Home,
but Flutter text entry exposed no focused editable accessibility node, and
in-process `input text/keyevent` is denied `INJECT_EVENTS` on this emulator.
Do not mark the VLM/toolvox Project-creation path complete until a real in-app
runner or reliable text-entry path submits the prompt and the resulting
`workspace/projects/<project-id>/project.json` exists.

The approved MCP runner for the real in-app Agent path is `agent_run`. It creates
or reuses an OOB conversation, calls the same `AgentRunService.startConversationRun`
path used by WebChat/Home, and returns the accepted `taskId` plus
`conversationId`. It does not expose Workbench control APIs; the Agent must still
call internal Workbench tools itself, and success still requires runtime-file
verification under `workspace/projects/<project-id>/`.

## Startup Boundary

Flutter first frame must not block on non-critical scheduler/workspace path work.
The app startup path only awaits storage and background service state with short
timeouts before allowing the first frame; scheduled-task and workspace-path
initialization continue asynchronously. This keeps Workbench Project pages
reachable during backend E2E on a fresh debug install.

## Backend Runtime Boundary

OOB Workbench is now the runtime container for backend assets as well as UI:

- Project creation writes `logs/project_progress.jsonl` and exposes the latest row as `lastProgress` in the Project payload.
- `project.json` and `backend/api_spec.json` record the derived Toolbox manifest
  so the active Project can be mounted through MCP dynamic tools.
- `backend/api_spec.json` records executor metadata, control API names, persistence paths, source refs, Tool Contract fields, and examples so a Project API can later move from native-backed execution to Bridge/Alpine without changing internal `workbench_api_call` or external MCP Toolbox tool names.
- `workbench_project_ingest_oss` imports local source snapshots under `source/repos/<source-id>/`, skips dependency/build directories, detects package files such as `package.json`, `pyproject.toml`, `pubspec.yaml`, and Gradle files, and stores entrypoint hints in `source/manifest.json`.
- GitHub URL-only ingest is deliberately metadata-only. It is not a network fetch; after terminal/tool fetch downloads the repo, call the same API with `sourcePath`.
- Workbench control APIs still do not appear in `workbench_api_list`; only Project business APIs are exposed to the generated frontend.
- Workbench control APIs still do not appear in active Project Toolbox dynamic tools; only Project business APIs are mounted.
- `/mcp/workbench/call` is the deterministic Dashboard-token test transport for Project backend runtime operations when VLM/model-provider setup is not the test subject.
- `/mcp/call_tool` with `name=agent_run` is the deterministic MCP runner for the normal Agent/tool path when Flutter Home text entry is not the test subject; `/mcp/call_tool` with an active dynamic Toolbox tool name is the external MCP business API path.
