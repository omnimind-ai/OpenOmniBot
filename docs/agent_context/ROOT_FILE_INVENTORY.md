# OOB Agent Context Root File Inventory

Status: Draft
Last Updated: 2026-05-10

## Purpose

This inventory gives the next agent the shortest reliable map for the OOB Workbench backend/runtime task. It intentionally excludes unrelated Flutter polish, floating overlay design, and UIKit animation work.

## Backend Runtime Source

- `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchRuntime.kt`
  - Owns Project registry, Project API registry, Project payloads, runtime files, API execution, hot updates, Android ingest, OSS ingest, progress logs, and export packages.
- `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/WorkbenchToolHandler.kt`
  - Maps Agent tool calls to `WorkbenchProjectStore` methods.
- `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt`
  - Declares Workbench control tools and Project API tools for the Agent.
- `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentSystemPrompt.kt`
  - Teaches the Agent when to create Projects, when not to create Projects, and how to handle OSS/progress workflows.
- `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
  - Native manager entry for Flutter MethodChannel calls.
- `app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt`
  - Flutter/native MethodChannel dispatch for Workbench operations.

## Agent Skill / Documentation

- `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md`
  - Bundled skill used by OOB Agent runtime for Project creation and backend/runtime rules.
- `docs/reference/OOB_INTEGRATION.md`
  - Stable integration note for the Flutter/native Workbench boundary.
- `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`
  - Current detailed backend/runtime contract.
- `docs/agent_context/skills/oob-workbench-backend/SKILL.md`
  - Handoff skill for future repository agents.
- `docs/agent_context/INDEX.md`
  - Agent read-order entry.

## Tests

- `app/src/test/java/cn/com/omnimind/bot/workbench/WorkbenchProjectStoreTest.kt`
  - Main Workbench backend/runtime tests.
- `app/src/test/java/cn/com/omnimind/bot/agent/AgentToolDefinitionsMusicTest.kt`
  - Existing Agent tool definition smoke that now also checks Workbench control tool exposure.

## Iteration Records

- `project_iterations/VERSION_LOG.md`
  - One-line delivery record.
- `project_iterations/PART_ITERATION_LOG.md`
  - Board-level completion/verification/status log.
- `project_iterations/20260510-oob-workbench-backend-runtime/`
  - Current plan/progress/result handoff.

## Device Verification Boundary

- Use `emulator-5554` only.
- Do not use `emulator-5556`.
- Before claiming VLM/toolvox success, verify actual files under app data:

```text
workspace/projects/registry.json
workspace/projects/api_registry.json
workspace/projects/<project-id>/project.json
workspace/projects/<project-id>/logs/project_progress.jsonl
```

## Current Known Dirty Areas To Avoid

The working tree may contain unrelated frontend/UIKit changes from other agents. Treat these as user/other-agent edits unless explicitly instructed otherwise:

- `ui/lib/features/home/pages/command_overlay/`
- `ui/lib/features/home/pages/omnibot_workspace/`
- `ui/lib/features/workbench/widgets/`
- `ui/test/workbench_annotation_overlay_test.dart`
- `uikit/src/main/java/cn/com/omnimind/uikit/`

This backend pass should not revert or clean those files.
