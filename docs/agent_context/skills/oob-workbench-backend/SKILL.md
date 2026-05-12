# OOB Workbench Backend Skill

Use this context when changing the Workbench backend runtime.

## Core Principle

Workbench is a display and orchestration layer for AI products. It gives Agent outputs a persistent surface that users can see, operate, and update in one sentence.

Do not add preset app flows. Prefer one generic Project runtime with Project Tools and renderer-specific assets.

## Read First

1. `docs/reference/OOB_INTEGRATION.md`
2. `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`
3. `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchRuntime.kt`
4. `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchToolboxBuilder.kt`
5. `app/src/main/java/cn/com/omnimind/bot/workbench/executor/WorkbenchExecutor.kt`
6. `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt`
7. `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md`

## Control Path

Project lifecycle and iteration go through Workbench control tools:

- `workbench_project_create`
- `workbench_project_list`
- `workbench_project_get`
- `workbench_project_open`
- `workbench_project_activate`
- `workbench_project_active_get`
- `workbench_project_deactivate`
- `workbench_project_update`
- `workbench_project_hot_update`
- `workbench_project_export`
- `workbench_project_delete`
- `workbench_project_progress_get`
- `workbench_project_ingest_oss`
- `workbench_api_list`
- `workbench_api_call`

Never register these control tools as Project Tools.

## Project Creation Shape

Create Projects with:

```json
{
  "projectId": "oob-workbench-research-brief",
  "name": "Research Brief",
  "prompt": "Show the agent result with filters and follow-up actions.",
  "entityName": "Finding",
  "initialItems": [
    {"title": "Key risk", "severity": "high"}
  ],
  "apis": [
    {
      "toolId": "finding.create",
      "displayName": "Add finding",
      "inputSchema": {"title": "string", "severity": "string?"},
      "outputSchema": {"item": "object"},
      "run": {"use": "native.collection.create"}
    }
  ],
  "htmlFiles": [
    {"path": "index.html", "content": "<!doctype html>..."}
  ]
}
```

Use `htmlFiles` for rich reports, charts, dashboards, comparison documents, or custom UI. Use the default Project Display for simple structured lists/forms/actions. Use `flutterFiles` only when the limited runtime is acceptable.

## Project Tools

Project Tools are business actions shared by UI, Agent, and MCP dynamic tools.

Default native collection actions:

- `native.collection.create`
- `native.collection.archive`
- `native.collection.update`
- `native.collection.list`

Other run targets:

- `script`
- `workspace_python_script`
- `agent`
- `oob.<tool>`
- `mcp.<tool>`

Project Tools must be whitelist-backed. Do not expose arbitrary Android, filesystem, shell, or network access directly to frontend code.

## HTML Bridge

HTML source lives under `frontend/html/` and is loaded by `/workbench/html?projectId=<id>`.

Frontend calls:

```js
await window.oob.callApi(apiId, inputs)
await window.oob.getProject()
window.oob.selectElement(payload)
```

Use `data-oob-id` on important elements so inspect/edit mode can target smaller changes.

### HTML Data Contract

The Workbench frontend must render persisted Project data, not generated sample arrays. On load, call `window.oob.getProject()` and render from `project.items`.

Workbench item records are envelopes:

```js
{
  id: "item-id",
  title: "Display title",
  status: "active",
  fields: {
    amount: 12.5,
    type: "expense",
    category: "餐饮",
    date: "2026-05-12",
    note: "午饭"
  }
}
```

Only `id`, `title`, and `status` are top-level item metadata. Business fields produced by Project Tools live under `item.fields`. Generated HTML must not read raw persisted items as `item.amount`, `item.type`, `item.category`, `item.date`, `item.note`, etc. Normalize at the boundary:

```js
function toViewItem(item) {
  const fields = item && item.fields ? item.fields : {};
  return {
    id: item.id,
    title: item.title || fields.title || '',
    status: item.status || 'active',
    amount: Number(fields.amount || 0),
    type: fields.type || 'expense',
    category: fields.category || '',
    date: fields.date || '',
    note: fields.note || ''
  };
}
```

`window.oob.callApi(apiId, inputs)` returns an envelope:

```js
{
  success: true,
  apiId: "expense.list",
  outputs: { items: [] },
  project: { items: [] },
  errorCode: "OPTIONAL_ERROR",
  errorMessage: "Optional error message"
}
```

Generated HTML must read list outputs from `result.outputs.items || result.project.items`, never `result.items`. After create/update/archive, refresh from `result.project.items` or call `getProject()` again.

If a Project Tool references `run.use: "script"` or `run.use: "workspace_python_script"`, the referenced script file must be present in the Project backend, such as `backend/scripts/expense_stats.py`. Missing scripts cause `SCRIPT_NOT_FOUND` and are backend contract failures, not empty frontend data.

## Hot Update

For user iteration:

1. Read the active Project with `workbench_project_get` when needed.
2. Call `workbench_project_hot_update(projectId, prompt, frontendContext)`.
3. If HTML sources exist, edit the smallest affected HTML/CSS/JS file and call `workbench_project_update(htmlFiles=...)`.
4. If Flutter sources exist, write full replacement Dart files and call `workbench_project_update(flutterFiles=...)`.
5. If the change is data-only, call a registered Project Tool.

Do not create a replacement Project for a feature change unless the user explicitly asks for a new Project.

## Files To Keep In Sync

- `WorkbenchRuntime.kt`: Project persistence, payloads, HTML/Flutter source writes, hot update, Project Tool execution.
- `WorkbenchToolboxBuilder.kt`: Project Tool contract and MCP dynamic tool descriptors.
- `AgentToolDefinitions.kt`: Agent-facing control tool schemas.
- `McpToolDefinitions.kt`: MCP-facing control tool schemas.
- `McpPromptDefinitions.kt`: reusable MCP prompt instructions.
- `app/src/main/assets/builtin_skills/oob-native-workbench/SKILL.md`: prompt contract for Agents.
- Flutter Workbench pages and services under `ui/lib/features/workbench/`.

## Verification

For code changes, prefer focused checks only when requested:

- create a generic Project with `initialItems`
- create/update HTML source and verify the route resolves to `/workbench/html`
- call a Project Tool and verify `data/items.json` plus `logs/api_calls.jsonl`
- hot update HTML and verify changed source is returned in `frontendHtml.sources`
- confirm existing Project activation/open/export paths still use control APIs
