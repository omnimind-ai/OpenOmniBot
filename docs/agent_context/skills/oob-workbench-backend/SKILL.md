# OOB Workbench Backend Runtime Skill

Status: Draft
Last Updated: 2026-05-10

## When To Use

Use this skill when the task touches OOB Workbench backend/runtime behavior:

- creating, activating, exporting, deleting, or querying Projects;
- adding Workbench control tools;
- importing GitHub/OSS/Android assets into a Project;
- changing Project API registry behavior;
- changing executor metadata, progress logs, Project payload shape, or `backend/api_spec.json`;
- validating the claim that OOB is an on-device AI workbench backend/runtime, not just a generated UI.

Do not use this skill for Flutter visual polish, canvas annotation UI, floating overlay design, or unrelated OOB features.

## Required Reading

Read in this order:

1. `AGENTS.md`
2. `docs/reference/OOB_INTEGRATION.md`
3. `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`
4. `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md`
5. `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchRuntime.kt`
6. `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/WorkbenchToolHandler.kt`
7. `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt`
8. `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
9. `app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt`
10. `app/src/test/java/cn/com/omnimind/bot/workbench/WorkbenchProjectStoreTest.kt`

## Core Model

OOB Workbench has three backend/runtime layers:

- Control plane: `workbench_project_*` tools and MethodChannel calls. These create/manage/import/export/hot-update Projects and must not appear in Project API Registry.
- Project API plane: `workbench_api_list` and `workbench_api_call`. These expose only business APIs owned by a Project, such as `todo.add`, `customer.create`, or `capture.ingest`.
- Runtime container: `/workspace/projects/<project-id>/` with `project.json`, `frontend/page_spec.json`, `backend/api_spec.json`, `data/`, `logs/`, `source/`, `android/`, and export packages.

The key product claim is: OOB puts the backend on the phone. A Project should carry backend abilities, source assets, data, API logs, progress, and executor contracts as persistent runtime state.

## Design Claims To Preserve

This skill is about the first and third pillars of "端侧 AI 工作台":

- Backend: OOB native backend workbench, sandbox/workspace, VLM/tool composition entrypoints, Alpine execution replacement point, and Project API execution.
- Runtime: Project as the persistent instance container that binds backend capabilities, frontend contract, user data, API calls, logs, imported source, and export metadata.

Do not spend this pass on frontend visual polish. The backend claim is valid only if Project creation/import/calls/logs are real runtime files and APIs, not mocked UI state.

## Current Control APIs

- `workbench_project_create`: create a Project from `todo_log_demo`, `schema_app`, or `quick_capture_inbox`.
- `workbench_project_activate`: make one Project the active Agent toolbox.
- `workbench_project_active_get`: read the active Project manifest.
- `workbench_project_progress_get`: read `logs/project_progress.jsonl` or latest progress summary.
- `workbench_project_ingest_oss`: register/copy OSS/GitHub source into `source/manifest.json`.
- `workbench_project_ingest_android`: copy APK/Android source into `android/manifest.json`.
- `workbench_project_hot_update`: control-plane project mutation/audit entry.
- `workbench_project_export`: export the runtime package.

These are not business APIs. Never add them to `api_registry.json` or generated frontend business API panels.

## Current Project Files

Every created Project should be able to carry these files:

```text
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

When debugging, first inspect `project.json`, `backend/api_spec.json`, and `logs/project_progress.jsonl`; they summarize most runtime state without requiring UI.

## OSS/GitHub Ingest Rules

- Use `workbench_project_ingest_oss` only after the target Project exists.
- If the caller passes only `sourceUrl`, record metadata with `requiresFetch=true`; do not claim the repo was fetched.
- If the caller passes `sourcePath`, copy that local source snapshot under `source/repos/<source-id>/`.
- Skip generated/dependency directories: `.git`, `.gradle`, `.idea`, `.dart_tool`, `.venv`, `__pycache__`, `build`, `dist`, `node_modules`, `target`, `.next`, `.turbo`.
- Detect package/build files: `package.json`, `pyproject.toml`, `requirements.txt`, `setup.py`, `Pipfile`, `pubspec.yaml`, Gradle files, `Cargo.toml`, `go.mod`, `pom.xml`.
- Store detected stack, package files, entrypoint hints, source URL/ref/path, size, and file count in `source/manifest.json`.
- Append audit rows to `logs/oss_ingest.jsonl` and progress rows to `logs/project_progress.jsonl`.

## Project Progress Rules

- Creation should append staged rows ending in `project_create_completed`.
- Android ingest should append rows ending in `android_ingest_completed`.
- OSS local ingest should append rows ending in `oss_ingest_completed` with `status=completed`.
- OSS URL-only ingest should append `oss_fetch_required` and end with `status=waiting`.
- Query through `workbench_project_progress_get`; do not parse log files from the AI layer unless debugging.

## Multi-Project Functional Scenario

Use this as the minimum backend functional scenario before claiming the runtime is ready:

1. Create `oob-workbench-customer-tracker` with `templateId=schema_app`, `entityName=Customer`.
2. Activate it.
3. Call `customer.create` twice through `workbench_api_call`.
4. Create `oob-workbench-expense-tracker` with `templateId=schema_app`, `entityName=Expense`.
5. Activate it.
6. Call `expense.create` twice through `workbench_api_call`.
7. Query `workbench_project_progress_get` for both Projects and confirm `project_create_completed`.
8. Query `workbench_api_list` for both Projects and confirm only business APIs appear.
9. Ingest one local source directory into either Project and confirm `source/manifest.json`.

Never satisfy this scenario by hand-writing registry, data, source, or log files.

## Executor Boundary

Current state:

- `native_template`: stable native todo executor.
- `native_schema_collection`: stable native generic collection executor.
- `workspace_python_script`: stable Project API contract, currently native-backed for reliability.

Important: `workspace_python_script` is intentionally shaped so a later BridgeServer/Alpine Python executor can replace the native-backed implementation without changing `workbench_api_call`, API ids, Display bindings, data paths, or logs.

## Minimal Verification

Focused backend verification:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*Workbench*' \
  -Ptarget=lib/main_standard.dart
```

Tool definition verification:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*AgentToolDefinitions*' \
  -Ptarget=lib/main_standard.dart
```

Device verification, only when needed:

```bash
adb devices
# Use emulator-5554 only.
```

Do not use `emulator-5556` for this task.

## Toolvox / VLM Validation Notes

`toolvox` is not a symbol in this OOB source tree as of 2026-05-10. The exposed local MCP tools are `vlm_task`, `task_status`, `task_reply`, `task_wait_unlock`, and `file_transfer`; Workbench controls are internal Agent tools, not MCP tools.

For a real toolvox-style run:

1. Ensure the app is fully onboarded and has a working model provider.
2. Use `emulator-5554` only.
3. Start from OOB Home or an approved runner.
4. Send a deliberate Project creation prompt.
5. Verify the app writes `workspace/projects/registry.json`.
6. Verify the resulting Project files through `adb -s emulator-5554 shell run-as cn.com.omnimind.bot.debug ...`.

If the device is not configured with a model provider, record the run as blocked. Do not claim `vlm_task` created Projects unless `workspace/projects/<project-id>/project.json` exists.

## Handoff Checklist

- Keep Workbench control APIs out of `workbench_api_list`.
- Update `docs/reference/OOB_INTEGRATION.md` when API/runtime contracts change.
- Update `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md` when runtime file shape, progress stages, source ingest, or executor boundary changes.
- Update `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md` when Agent behavior or tool workflow changes.
- Update `project_iterations/VERSION_LOG.md` and `project_iterations/PART_ITERATION_LOG.md` before handoff.
- Avoid frontend polish until backend runtime verification is complete.
