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
   - `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md`
   - `ui/lib/features/workbench/`

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
