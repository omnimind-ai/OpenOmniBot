# Task Progress

## 2026-05-10

- Loaded OOB repository instructions and confirmed the task should remain OOB-only.
- Confirmed many unrelated frontend/uikit files are dirty; this pass avoids editing them.
- Implemented/confirmed backend runtime changes in `WorkbenchRuntime.kt`:
  - Project creation progress rows.
  - OSS/GitHub source ingest.
  - `source/manifest.json`.
  - `logs/project_progress.jsonl`.
  - `logs/oss_ingest.jsonl`.
  - Project payload `sourceAssets`, `lastProgress`, `progressLogPath`.
  - `backend/api_spec.json` runtime metadata.
- Wired new Workbench controls through:
  - `AgentToolDefinitions.kt`
  - `WorkbenchToolHandler.kt`
  - `AssistsCoreManager.kt`
  - `AssistsCoreChannel.kt`
  - `AgentSystemPrompt.kt`
- Updated OOB Workbench docs and bundled skill.
- Ran backend/tool-definition Gradle gate:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*Workbench*' \
  --tests '*AgentToolDefinitions*' \
  -Ptarget=lib/main_standard.dart
```

Result: `BUILD SUCCESSFUL`.

## Device Attempt

- Used `emulator-5554` only.
- Confirmed `adb devices` also showed `emulator-5556`, but it was not used.
- Confirmed app process `cn.com.omnimind.bot.debug` was running.
- Skipped onboarding into Home.
- Confirmed app workspace existed at app data `workspace/`.
- Confirmed `workspace/projects` did not exist before testing.
- Attempted Home prompt Project creation from the UI.
- No `workspace/projects` directory was created.

## Blocker

The source tree exposes external MCP tools for `vlm_task`, `task_status`, `task_reply`, `task_wait_unlock`, and `file_transfer`; it does not expose Workbench control APIs directly. The string `toolvox` was not found in the source tree.

For a real toolvox/VLM multi-Project validation, the device needs either:

- a configured model provider so Home Agent can call Workbench tools; or
- a dedicated internal runner that can execute Agent tool calls and write Projects through `WorkbenchProjectStore`.

Until one of those exists, do not claim VLM/toolvox Project creation success.

## 5554 Native Quick Capture E2E

- Built `developStandardDebug`, installed it on `emulator-5554`, and did not use `emulator-5556`.
- Fixed startup first-frame blocking by deferring scheduled-task and workspace-path initialization after `allowFirstFrame`.
- Enabled OOB Local Service on 5554 and used the local Dashboard bearer token through `POST /mcp/workbench/call`.
- Created `projectId=oob-workbench-quick-capture` with `templateId=quick_capture_inbox`.
- Activated the Project.
- Called `workbench_api_call` for `capture.ingest` with `sourceApp=Codex Dashboard E2E`.
- Called `workbench_project_open`; the debug route navigated the native UI through `TaskCompletionNavigator`.
- Verified the screen shows `随手记 Inbox · NOTE`, `3 active / 0 archived`, `OOB native UI`, and `4 APIs`.
- Verified runtime files under app data with `adb -s emulator-5554 shell run-as cn.com.omnimind.bot.debug cat ...`:
  - `workspace/projects/oob-workbench-quick-capture/project.json`
  - `workspace/projects/oob-workbench-quick-capture/backend/api_spec.json`
  - `workspace/projects/oob-workbench-quick-capture/data/items.json`
  - `workspace/projects/oob-workbench-quick-capture/logs/project_progress.jsonl`
  - `workspace/projects/oob-workbench-quick-capture/logs/api_calls.jsonl`
- Screenshot proof: `/tmp/oob_quick_capture_project_5554_recheck.png`.

This resolves the deterministic backend/runtime proof. It does not claim direct
`vlm_task`/toolvox Project creation, which remains gated on model-provider or
runner setup.
