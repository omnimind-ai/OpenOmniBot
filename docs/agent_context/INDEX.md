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
- Deterministic Dashboard-token backend E2E completed on `emulator-5554`: `quick_capture_inbox` Project `oob-workbench-quick-capture` was created, activated, seeded through `capture.ingest`, opened via `workbench_project_open`, and shown as the OOB-native `随手记 Inbox · NOTE` Flutter Display.
- Verified app-data files exist under `workspace/projects/oob-workbench-quick-capture/`, including `project.json`, `backend/api_spec.json`, `data/items.json`, `logs/project_progress.jsonl`, and `logs/api_calls.jsonl`.
- Do not claim `vlm_task`/toolvox direct Project creation success until the VLM path itself writes Project files on `emulator-5554`; the deterministic `/mcp/workbench/call` route is a local authenticated backend E2E transport, not MCP tool discovery.
