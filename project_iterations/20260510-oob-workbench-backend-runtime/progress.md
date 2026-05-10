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

## 5554 Provider + VLM Attempt + Quick Note E2E

- Installed the latest `developStandardDebug` APK on `emulator-5554`; did not use `emulator-5556`.
- Added and installed the authenticated `debug_model_provider_configure` / `debug_model_provider_get` route under `POST /mcp/workbench/call`.
- Configured the 5554 device with the local Dashboard/DashScope OpenAI-compatible provider for:
  - `scene.dispatch.model`
  - `scene.vlm.operation.primary`
  - `scene.compactor.context`
  - `scene.compactor.context.chat`
- Verified the route masks the key as `apiKeyConfigured` and does not return the raw key.
- Ran `vlm_task` from OOB Home. The task reached DashScope and generated UI actions.
- VLM could click OOB Home but could not submit the Project prompt because Flutter input did not expose a focused editable accessibility node. The in-app fallback `input text/keyevent` path is denied Android `INJECT_EVENTS`, so this is recorded as a VLM input blocker rather than a Workbench backend failure.
- Fixed `workbench_project_open` in `McpRoutes.kt` to call `TaskCompletionNavigator` on `Dispatchers.Main`; before the fix, the debug route failed with `Can't create handler inside thread ... Looper.prepare()`.
- Created `projectId=oob-workbench-vlm-quick-note` with `templateId=quick_capture_inbox` through the authenticated Workbench debug route.
- Activated the Project and called `capture.ingest` twice.
- Opened the Project on the device and verified the screen shows `随手记 Inbox · NOTE`, `2 active / 0 archived`, `OOB native UI`, and `4 APIs`.
- Fixed quick-capture backend type handling so supported explicit types are preserved, `read_later` maps to `later`, and receipt/invoice/expense text maps to `summary` instead of defaulting to `todo`.
- Rebuilt and reinstalled on `emulator-5554`, used the native Flutter UI to add `Invoice receipt 256 RMB`, archived one malformed smoke entry, and verified the final screen shows `3 active / 1 archived` with the invoice item tagged `Summary`.
- Verified runtime files under app data with `adb -s emulator-5554 shell run-as cn.com.omnimind.bot.debug ...`:
  - `workspace/projects/oob-workbench-vlm-quick-note/project.json`
  - `workspace/projects/oob-workbench-vlm-quick-note/backend/api_spec.json`
  - `workspace/projects/oob-workbench-vlm-quick-note/data/items.json`
  - `workspace/projects/oob-workbench-vlm-quick-note/logs/project_progress.jsonl`
  - `workspace/projects/oob-workbench-vlm-quick-note/logs/api_calls.jsonl`
- Screenshot proof: `/tmp/oob_quick_note_final_clean_5554.png`.

## MCP agent_run Runner

- Added `agent_run` to MCP tool discovery and dispatch.
- Implementation uses the normal OOB Agent runtime path:
  - creates or reuses a conversation through `ConversationDomainService`;
  - starts the Agent through `AgentRunService.startConversationRun`;
  - returns accepted `taskId` and `conversationId`;
  - does not call `WorkbenchProjectStore` directly.
- Updated Workbench docs and skills to mark `agent_run` as the approved toolvox-style runner when the test should not depend on Flutter Home visual text entry.
- Built the APK with:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

Result: `BUILD SUCCESSFUL`.

- Installed the APK to `emulator-5554` only.
- Verified `/mcp/health` on forwarded port `18899` returns `{"status":"ok"}`.
- Blocked on authenticated MCP functional call: the previously used Dashboard/MCP bearer token now returns `401`. Per security policy, did not reverse the token from app-local MMKV/signing material.
- Next action after user provides current Dashboard/MCP bearer token: call `/mcp/call_tool` with `name=agent_run`, prompt OOB to create a quick-capture Project, then verify `workspace/projects/<project-id>/project.json` and visible native OOB display.

## MCP Toolbox v0.1

- Implemented OOB Toolbox over MCP backend surface without touching frontend 0.5.0 work.
- Added `WorkbenchToolboxBuilder.kt`:
  - derives `toolboxId` from Project id, e.g. `oob-workbench-v01-quick-note` -> `quick_note`;
  - derives dynamic MCP tool names, e.g. `quick_note.capture_ingest`;
  - generates Tool Contract fields (`toolName`, `apiVersion`, `capabilities`, `sideEffects`, `dataFiles`, `logFiles`, `examples`);
  - builds active Project Toolbox manifests and MCP tool descriptors.
- Updated `WorkbenchRuntime.kt`:
  - `project.json` and `backend/api_spec.json` include derived `toolbox`;
  - Project API payloads include v0.1 Tool Contract fields;
  - active Project manifests expose Toolbox;
  - `callApi` returns structured `TOOL_NOT_FOUND`, writes failed calls to `logs/api_calls.jsonl`, records `durationMs`, `outputs`, `errorMessage`, and updates `lastError`;
  - added required-field schema validation for missing/null required inputs while preserving executor-level blank-value validation;
  - added `activeMcpTools`, `activeToolbox`, `callMcpTool`, `listMcpResources`, and `readMcpResource`.
- Updated MCP route layer:
  - `initialize` advertises tools/resources/prompts;
  - `tools/list` returns fixed OOB MCP tools plus active Project dynamic tools;
  - `tools/call` dispatches fixed tools first and active Project dynamic tools second;
  - added MCP `resources/list`, `resources/read`, `prompts/list`, `prompts/get`;
  - added fixed OOB MCP controls `oob_project_create`, `oob_project_activate`, `oob_project_open`, `oob_project_progress_get`;
  - kept old `workbench_project_*` and `workbench_api_call` as internal Agent/debug route names, not dynamic Toolbox tools.
- Added built-in MCP prompts:
  - `create_quick_capture_project`
  - `create_schema_project`
  - `inspect_active_toolbox`
  - `fix_project_last_error`
- Updated docs and skills:
  - `docs/reference/OOB_INTEGRATION.md`
  - `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`
  - `docs/agent_context/INDEX.md`
  - `docs/agent_context/ROOT_FILE_INVENTORY.md`
  - `docs/agent_context/skills/oob-workbench-backend/SKILL.md`
  - `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md`
- Added tests:
  - active Project Toolbox dynamic tool derivation;
  - `mcp_toolbox` caller log path;
  - MCP resources for toolbox/progress/api logs;
  - schema missing-input structured error and `lastError`;
  - fixed MCP tools include `agent_run` and `oob_project_*`, but not internal `workbench_*` names.
- Focused test command:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*Workbench*' \
  --tests '*McpToolDefinitions*' \
  -Ptarget=lib/main_standard.dart
```

Result: `BUILD SUCCESSFUL`.

- Full debug APK build command:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

Result: `BUILD SUCCESSFUL`.

- Device functional status: blocked in this pass because `adb devices` returned no attached devices, so `emulator-5554` was not available. `emulator -list-avds` was also not on PATH in this shell. No 5556 device was used.
