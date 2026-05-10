# OOB Workbench Backend Runtime

Status: Draft
Last Updated: 2026-05-10

## Product Boundary

This document records the backend/runtime half of the "端侧 AI 工作台" direction.

The current pass focuses only on:

1. Backend: OOB native backend workbench, Project API execution, source ingest, progress logs, executor metadata.
2. Runtime: Project as a persistent instance container for backend capabilities, UI contract, user data, API calls, logs, source assets, and export.

Frontend visual editing remains out of scope for this pass.

## Runtime Directory Contract

Each Project lives under:

```text
/workspace/projects/<project-id>/
  README.md
  project.json
  frontend/page_spec.json
  backend/api_spec.json
  data/
  logs/api_calls.jsonl
  logs/hot_updates.jsonl
  logs/project_progress.jsonl
  logs/oss_ingest.jsonl
  logs/android_ingest.jsonl
  source/manifest.json
  source/repos/<source-id>/
  android/manifest.json
  android/apps/<asset-id>/
```

`project.json` is the runtime summary. It includes Project identity, displays, schema, business APIs, Android assets, source assets, progress summary, and state counters.

`backend/api_spec.json` is the backend contract. It includes API ids, schemas, executor kind, runtime metadata, control API names, persistence paths, source refs, and frontend binding.

`source/manifest.json` records OSS/GitHub source assets. It is intentionally separate from `backend/api_spec.json` so source ingestion can happen before API binding.

`logs/project_progress.jsonl` is the progress stream for Project creation/import/executor preparation.

## Control Plane Vs Project API Plane

Workbench control APIs manage Project lifecycle and runtime assets:

```text
workbench_project_create
workbench_project_list
workbench_project_get
workbench_project_open
workbench_project_activate
workbench_project_active_get
workbench_project_deactivate
workbench_project_export
workbench_project_delete
workbench_project_hot_update
workbench_project_ingest_android
workbench_project_ingest_oss
workbench_project_progress_get
```

Project business APIs live in Project API Registry and are called with:

```text
workbench_api_list
workbench_api_call
```

Rule: lifecycle/import/progress/export/hot-update controls must not appear in `workbench_api_list`.

## Backend Capability Surface

This pass is deliberately the backend/runtime foundation, not the final visual editor.

The implemented capability surface is:

- Project creation and reuse with append-only progress stages.
- Generic `schema_app` Projects with business APIs such as `<entity>.create` and `<entity>.archive`.
- Quick capture Projects with `workspace_python_script` API ids that already write script/SDK artifacts, while execution remains native-backed for reliability. Supported buckets are `todo`, `summary`, `link`, and `later`; receipt/invoice/expense inputs are mapped to `summary` until the frontend adds a dedicated expense bucket.
- Android asset ingest as Project runtime metadata under `android/`.
- OSS/GitHub source ingest as Project runtime metadata under `source/`.
- Runtime summaries in `project.json`, `backend/api_spec.json`, active Project prompt context, and export packages.

The intentional replacement points are:

- `workspace_python_script` can later be backed by BridgeServer + Alpine Python without changing `workbench_api_call`.
- `source/manifest.json` can later drive automatic API binding for imported OSS code.
- `logs/project_progress.jsonl` can later drive frontend progress UI and toolvox/reporting.

## OSS/GitHub Ingest Flow

URL-only flow:

1. Agent creates or selects a Project.
2. Agent calls `workbench_project_ingest_oss(projectId, sourceUrl, ref?)`.
3. OOB writes `source/manifest.json` with `requiresFetch=true`.
4. OOB appends progress rows ending in `status=waiting`.
5. A later terminal/tool step fetches the repo.
6. Agent calls `workbench_project_ingest_oss(projectId, sourcePath=<downloaded-dir>)`.

Local source flow:

1. Agent creates or selects a Project.
2. Agent calls `workbench_project_ingest_oss(projectId, sourcePath)`.
3. OOB copies source into `source/repos/<source-id>/source`.
4. OOB skips generated/dependency directories.
5. OOB detects package files and entrypoints.
6. OOB writes `source/manifest.json`, `logs/oss_ingest.jsonl`, and progress rows ending in `status=completed`.

Detected package files currently include:

```text
package.json
pyproject.toml
requirements.txt
setup.py
Pipfile
pubspec.yaml
build.gradle
build.gradle.kts
settings.gradle
settings.gradle.kts
Cargo.toml
go.mod
pom.xml
```

## Progress Event Contract

Progress rows are JSONL objects in `logs/project_progress.jsonl`.

Stable fields:

```text
timestamp
projectId
stage
status
message
percent
caller
details
```

Current creation stages:

```text
project_create_started
project_registered | project_reused
project_workspace_ready
project_create_completed
```

Current OSS ingest stages:

```text
oss_ingest_started
oss_fetch_required | oss_source_copied
oss_package_analyzed
oss_ingest_completed
oss_ingest_failed
```

Current Android ingest stages:

```text
android_ingest_started
android_asset_copied
android_ingest_completed
android_ingest_failed
```

Status values are `running`, `waiting`, `completed`, or `failed`.

`workbench_project_progress_get(projectId?, limit?)` is the public read API for these rows. Other layers should only parse files directly during debugging.

## Executor Model

Current executor kinds:

- `native_template`: native todo executor.
- `native_schema_collection`: native generic CRUD-style collection executor.
- `workspace_python_script`: script executor contract, currently native-backed.

`workspace_python_script` already writes editable SDK/script artifacts under `backend/oob_sdk/` and `backend/scripts/`. This is a replacement point for:

- BridgeServer callback into OOB native tools;
- Alpine Python execution;
- VLM/tool composition;
- schedule/memory/file allowlist;
- later Omniflow executable memory reuse.

The replacement must preserve:

- `workbench_api_call(projectId, apiId, inputs)`;
- API ids;
- frontend bindings in `frontend/page_spec.json`;
- data files;
- `logs/api_calls.jsonl`;
- `logs/script_runs.jsonl`.

## Toolvox / VLM Task Boundary

As of this pass, the external MCP tool surface in OOB exposes:

```text
vlm_task
task_status
task_reply
task_wait_unlock
file_transfer
```

It does not expose Workbench control APIs as MCP tools. Therefore a toolvox-style validation that starts outside the app should use `vlm_task` to drive the OOB UI/Agent, and the in-app Agent then calls `workbench_project_create`, `workbench_project_activate`, `workbench_api_call`, and related Workbench tools.

For deterministic backend E2E, OOB also exposes an authenticated Dashboard/debug transport:

```text
POST /mcp/workbench/call
Authorization: Bearer <Dashboard token>
body: { "name": "<workbench tool name>", "arguments": { ... } }
```

This route uses the same MCP/Dashboard bearer token as `/mcp/state`. It calls `WorkbenchProjectStore` directly and is intentionally not listed by `tools/list`, not available as a Project business API, and not written into Project API Registry.

`workbench_project_open` on this route is allowed to navigate the native OOB UI
through `TaskCompletionNavigator`; the route switches to the Android main
thread before navigation. That makes the route useful for proving a Project is
not only written to disk but also visible as an OOB-native Flutter Display on
the target device.

The same route includes local E2E setup helpers:

```text
debug_model_provider_configure
debug_model_provider_get
```

These helpers write OOB's normal model provider profile and scene binding stores,
sync Agent AI config, and dispatch the Agent AI config change event. They return
only masked key state (`apiKeyConfigured`) and are not MCP tools.

## Quick Capture Device E2E Result

Status on 2026-05-10: completed on `emulator-5554` only.

Validated flow:

1. Built and installed `developStandardDebug`.
2. Enabled OOB Local Service and forwarded host port `18899` to device port `8899`.
3. Called `POST /mcp/workbench/call` with the local Dashboard bearer token.
4. Created `projectId=oob-workbench-quick-capture`, `templateId=quick_capture_inbox`.
5. Activated the Project.
6. Seeded one item through `workbench_api_call(capture.ingest)`.
7. Opened the Project through `workbench_project_open`.
8. Verified the device screen shows the OOB-native `随手记 Inbox · NOTE` page with `3 active / 0 archived`, `OOB native UI`, and `4 APIs`.
9. Re-ran the same shape with `projectId=oob-workbench-vlm-quick-note`, then added one receipt item through the native Flutter UI and archived a malformed smoke item. The final device screen showed `3 active / 1 archived`, `OOB native UI`, and `4 APIs`; the top receipt card was classified as `Summary`.

Device files verified through `adb -s emulator-5554 shell run-as cn.com.omnimind.bot.debug`:

```text
workspace/projects/oob-workbench-quick-capture/project.json
workspace/projects/oob-workbench-quick-capture/backend/api_spec.json
workspace/projects/oob-workbench-quick-capture/data/items.json
workspace/projects/oob-workbench-quick-capture/logs/project_progress.jsonl
workspace/projects/oob-workbench-quick-capture/logs/api_calls.jsonl
workspace/projects/oob-workbench-vlm-quick-note/project.json
workspace/projects/oob-workbench-vlm-quick-note/backend/api_spec.json
workspace/projects/oob-workbench-vlm-quick-note/data/items.json
workspace/projects/oob-workbench-vlm-quick-note/logs/project_progress.jsonl
workspace/projects/oob-workbench-vlm-quick-note/logs/api_calls.jsonl
```

## VLM / Toolvox Attempt Result

Status on 2026-05-10: provider configured, full creation path blocked.

What passed:

- `debug_model_provider_configure` bound `scene.vlm.operation.primary`,
  `scene.dispatch.model`, and compactor scenes to a Dashboard/DashScope
  OpenAI-compatible profile.
- `vlm_task` reached the configured model and produced UI actions on
  `emulator-5554`.
- VLM could click OOB Home and repeatedly attempted to type the Project creation
  prompt.

What blocked:

- Flutter Home input did not expose a focused editable accessibility node during
  the VLM run.
- App-process `Runtime.exec("input text/keyevent ...")` is denied Android
  `INJECT_EVENTS`; it cannot be used as a reliable in-app text-entry fallback on
  this emulator.

Do not claim a VLM/toolvox Project creation as successful unless the prompt is
actually submitted through OOB and the resulting Project files exist under
`workspace/projects/<project-id>/`.

The progress log ends with `stage=project_create_completed` and
`status=completed`. `data/items.json` contains the two initial captures plus the
extra `capture.ingest` row written through Project API execution.

## Verification Plan

Backend unit gate:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*Workbench*' \
  -Ptarget=lib/main_standard.dart
```

Agent tool definition gate:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*AgentToolDefinitions*' \
  -Ptarget=lib/main_standard.dart
```

Functional verification after backend gate:

1. Use OOB internal tools to create at least two `schema_app` Projects with different `projectId`s.
2. Activate each Project and seed records with `workbench_api_call`.
3. Query `workbench_project_progress_get` and confirm each Project ended with `project_create_completed`.
4. Ingest one local sample repo with `workbench_project_ingest_oss(sourcePath=...)`.
5. Confirm `workbench_api_list(projectId)` still excludes Workbench control APIs.
6. When model provider setup blocks the VLM path, use `POST /mcp/workbench/call` with the local Dashboard token to run the same backend calls and verify actual files on `emulator-5554`.
7. Device-side checks, if needed, use `emulator-5554` only.

## Verification Status On 2026-05-10

Passed:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*Workbench*' \
  --tests '*AgentToolDefinitions*' \
  -Ptarget=lib/main_standard.dart
```

Result: `BUILD SUCCESSFUL`.

Device smoke on `emulator-5554`:

- Debug APK was installed and app process `cn.com.omnimind.bot.debug` was running.
- Onboarding was skipped into Home and OOB workspace existed at app data `workspace/`.
- The authenticated debug route configured the normal OOB model-provider stores with a Dashboard/DashScope profile and masked the key as `apiKeyConfigured`.
- `vlm_task` reached the configured VLM and generated UI actions, but could not submit the Project prompt because the Flutter Home input did not expose a focused editable accessibility node and in-process `input text/keyevent` was denied `INJECT_EVENTS`.
- Deterministic backend/runtime proof succeeded through `POST /mcp/workbench/call`: `oob-workbench-vlm-quick-note` was created from `quick_capture_inbox`, activated, seeded through `capture.ingest`, opened through `workbench_project_open`, then further exercised through the native Flutter UI.

Remaining functional gap:

- Run a real toolvox/VLM Project-creation validation only after the Home text-entry path is fixed or a dedicated in-app runner can cause the Agent to call Workbench tools without typing into Flutter. Use `emulator-5554` only and require actual Project files before claiming success.
