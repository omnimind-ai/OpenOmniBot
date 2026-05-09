# OOB Agent Context Index

Status: Draft
Last Updated: 2026-05-10

## Fixed Read Order For Workbench Backend Tasks

1. `AGENTS.md`
2. `docs/reference/OOB_INTEGRATION.md`
3. `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`
4. `docs/agent_context/ROOT_FILE_INVENTORY.md`
5. `docs/agent_context/skills/oob-workbench-backend/SKILL.md`
6. Target source files:
   - `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchRuntime.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/WorkbenchToolHandler.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt`
   - `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
   - `app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt`
   - `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md`

## Current Workbench Backend Focus

- Backend workbench: Project creation, API registry, executor metadata, source ingest, progress logs.
- Runtime container: Project payload, `project.json`, `backend/api_spec.json`, `source/manifest.json`, data files, logs, export package.
- Out of scope for this pass: frontend polish, page_spec v2 widgets, real BridgeServer socket, raw Python execution, APK install/launch, OSS network fetch inside native code.

## Iteration Logs

- Latest version log: `project_iterations/VERSION_LOG.md`
- Part status log: `project_iterations/PART_ITERATION_LOG.md`
- Current handoff directory: `project_iterations/20260510-oob-workbench-backend-runtime/`

## Current Verification State

- Backend unit/tool-definition gate passed on 2026-05-10.
- Device smoke used `emulator-5554` only.
- UI/VLM Project creation was attempted but did not create `workspace/projects` because the test device had no configured model provider and this source tree has no direct `toolvox` Workbench runner.
- Do not claim toolvox/VLM multi-Project success until actual Project files exist under the app workspace on `emulator-5554`.
