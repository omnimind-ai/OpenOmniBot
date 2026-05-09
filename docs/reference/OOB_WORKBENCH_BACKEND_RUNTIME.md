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
- Quick capture Projects with `workspace_python_script` API ids that already write script/SDK artifacts, while execution remains native-backed for reliability.
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

It does not expose Workbench control APIs directly. Therefore a toolvox-style validation that starts outside the app must use `vlm_task` to drive the OOB UI/Agent, and the in-app Agent must then call `workbench_project_create`, `workbench_project_activate`, `workbench_api_call`, and related Workbench tools.

If a future toolvox runner needs direct Workbench calls, add an explicit debug/test transport rather than overloading MCP `vlm_task`. Keep that transport out of Project API Registry.

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
6. Device-side checks, if needed, use `emulator-5554` only.

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

- Debug APK was already installed and app process `cn.com.omnimind.bot.debug` was running.
- Onboarding was skipped into Home.
- OOB workspace existed at app data `workspace/`.
- `workspace/projects` did not exist before or after the Home prompt attempt.
- Home prompt entry was attempted, but the device was not configured with a model provider and no direct toolvox runner exposing Workbench tools exists in this codebase. No Project was created through the UI/VLM path in this run.

Remaining functional gap:

- Run a real toolvox/VLM validation only after the device has a working model provider or a dedicated in-app runner that can cause the Agent to call Workbench tools. Use `emulator-5554` only.
