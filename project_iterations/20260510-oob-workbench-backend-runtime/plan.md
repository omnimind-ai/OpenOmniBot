# Task Plan

## Goal

Build the backend/runtime foundation for the OOB "端侧 AI 工作台" direction, focusing on pillar 1 and pillar 3:

- Backend: native Workbench control APIs, Project API execution contracts, source ingest, executor metadata, progress logs.
- Runtime: Project as a persistent instance container that keeps identity, API registry, UI contract, data, logs, imported source, and export metadata.

Frontend polish, page_spec v2 widgets, and floating visual editing are out of scope for this pass.

## Planned Backend Changes

- Extend `WorkbenchRuntime.kt` so Projects write creation/import progress rows.
- Add OSS/GitHub source ingest as a Workbench control API, not a Project business API.
- Persist imported source metadata in `source/manifest.json` and audit rows in `logs/oss_ingest.jsonl`.
- Add `sourceAssets`, `lastProgress`, and `progressLogPath` to Project payloads.
- Add runtime metadata to `backend/api_spec.json`.
- Keep Workbench control APIs out of `workbench_api_list`.
- Wire new control APIs through Agent tool definitions, Workbench tool handler, native manager, and MethodChannel.

## Planned Documentation / Skill Changes

- Update `docs/reference/OOB_INTEGRATION.md`.
- Add `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`.
- Add `docs/agent_context/INDEX.md`.
- Add `docs/agent_context/skills/oob-workbench-backend/SKILL.md`.
- Update bundled `oob-native-workbench` skill.
- Maintain `project_iterations/VERSION_LOG.md` and `project_iterations/PART_ITERATION_LOG.md`.

## Validation Commands

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*Workbench*' \
  --tests '*AgentToolDefinitions*' \
  -Ptarget=lib/main_standard.dart
```

Device validation must use `emulator-5554` only.

## Out Of Scope

- Real BridgeServer socket.
- Real Alpine Python execution for Project APIs.
- Frontend visual polish.
- New landing page behavior.
- APK install/launch.
- OSS network fetch inside native code.
