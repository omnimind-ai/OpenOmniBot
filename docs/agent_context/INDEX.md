# OOB Agent Context Index

Status: Draft
Last Updated: 2026-05-11

## Fixed Read Order For Workbench Backend Tasks

1. `AGENTS.md`
2. `docs/reference/OOB_INTEGRATION.md`
3. `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`
4. `docs/agent_context/ROOT_FILE_INVENTORY.md`
5. `docs/agent_context/skills/oob-workbench-backend/SKILL.md`
6. Target source files:
   - `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchRuntime.kt`
   - `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchToolboxBuilder.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/WorkbenchToolHandler.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt`
   - `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt`
   - `app/src/main/java/cn/com/omnimind/bot/mcp/McpPromptDefinitions.kt`
   - `app/src/main/assets/builtin_skills/oob-project/SKILL.md`
   - `ui/lib/features/workbench/`

## Fixed Read Order For OOB VLM AndroidWorld Tasks

1. `AGENTS.md`
2. `docs/reference/OOB_VLM_ANDROIDWORLD.md`
3. `docs/agent_context/OOB_ONLINE_OFFLINE_SHARED_MEMORY.md`
4. `docs/agent_context/OOB_DEVICE_VALIDATION_2026-05-25.md`
5. `app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md`
6. Target source files:
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMOperationService.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMClient.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMIndexedPageContext.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMPostActionObservation.kt`
   - `app/src/debug/java/cn/com/omnimind/bot/debug/DebugVlmRunLogReceiver.kt`
   - `app/src/debug/java/cn/com/omnimind/bot/debug/DebugOobFunctionSegmentReceiver.kt`
   - `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
   - `ui/lib/services/assists_core_service.dart`
   - `ui/lib/features/task/pages/execution_history/command_library_page.dart`
   - `ui/lib/features/task/pages/execution_history/function_run_result_sheet.dart`
   - `ui/lib/features/task/pages/execution_history/run_log_timeline_page.dart`
   - `scripts/demo-vlm-runlog-e2e.sh`
7. Focused tests:
   - `app/src/test/java/cn/com/omnimind/bot/manager/AssistsCoreManagerOobReusableFunctionPayloadTest.kt`
   - `app/src/test/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandlerOmniFlowExecutionTest.kt`
   - `ui/test/services/oob_reusable_function_execution_service_test.dart`
   - `ui/test/features/task/pages/execution_history/command_library_page_test.dart`
   - `ui/test/features/task/pages/execution_history/function_run_result_sheet_test.dart`
   - `ui/test/features/task/pages/execution_history/run_log_timeline_page_test.dart`
   - `ui/test/widgets/execution/execution_detail_view_test.dart`

## Current Workbench Focus

- Generic Project container with Project Tools, persistent state, logs, source assets, and export.
- HTML WebView as a first-class renderer for rich reports, dashboards, charts, custom UI, and fast visual iteration.
- Default Project Display as a generic Flutter fallback for structured data and actions.
- `flutter_eval` as a supplemental limited renderer.
- Hot update loop: user context -> Agent edit -> Project source update -> right-side Display refresh.

## Out Of Scope

- Preset app flows.
- Arbitrary native bridges exposed to HTML.
- Native-code network fetch for external repositories.
- Creating replacement Projects for ordinary feature iteration.

## Current Verification State

- Workbench runtime uses one generic Project creation path.
- Project payloads return `frontendHtml`, `frontendFlutter`, `pageSpec`, `tools`, `toolbox`, and `items`.
- HTML sources under `frontend/html/` are bounded, manifest-backed, and loaded through `/workbench/html`.
- Project Tools are exposed through Flutter, Agent, and active MCP Toolbox paths.
- Read-only MCP Resources expose Project, active Project, Toolbox, progress, logs, and source manifest.
