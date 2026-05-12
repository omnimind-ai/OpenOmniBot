---
name: oob-native-workbench
description: Give AI outputs a visible, interactive, persistent surface inside OOB. Use when an Agent result, workflow state, report, or dataset needs a mobile UI that users can see, interact with, and iterate on in one sentence.
---

# OOB Native Workbench

OOB Workbench is an AI product display layer, not a vibe app generator and not a preset app catalog. Its job is to make an Agent result visible, interactive, persistent, and easy to change.

Optimize this loop:

```
AI produces output -> user sees it -> user says one thing -> AI updates it -> user sees it
```

Keep the infrastructure thin and the loop fast.

## Project Model

A Workbench Project is a persistent container with:

- `projectId`: stable id for the container
- `apis`: Project Tools that both AI and UI can call
- `initialItems`: optional persisted state in `data/items.json`
- `htmlFiles`: optional HTML/CSS/JS display files under `frontend/html/`
- logs for API calls, hot updates, and progress

Do not ask for or invent preset app names. Do not create a replacement Project for a feature update unless the user explicitly asks for a new Project.

## Control APIs

These are OOB control tools. Call them from the Agent. Never register them as Project Tools.

```
workbench_project_create
workbench_project_list
workbench_project_get
workbench_project_open
workbench_project_activate
workbench_project_active_get
workbench_project_deactivate
workbench_project_update
workbench_project_hot_update
workbench_project_export
workbench_project_delete
workbench_project_progress_get
workbench_api_list
workbench_api_call
```

## Creation Contract

Create a Project with `workbench_project_create`:

```json
{
  "projectId": "oob-workbench-research-brief",
  "name": "Research Brief",
  "prompt": "Show the agent's research result with filters and follow-up actions.",
  "entityName": "Finding",
  "initialItems": [
    {"title": "Key risk", "severity": "high"}
  ],
  "apis": [
    {
      "toolId": "finding.create",
      "displayName": "Add finding",
      "description": "Add one finding to the Project state.",
      "inputSchema": {"title": "string", "severity": "string?"},
      "outputSchema": {"item": "object"},
      "run": {"use": "native.collection.create"}
    },
    {
      "toolId": "finding.archive",
      "displayName": "Archive finding",
      "description": "Archive one finding.",
      "inputSchema": {"item_id": "string"},
      "outputSchema": {"item": "object"},
      "run": {"use": "native.collection.archive"}
    }
  ],
  "htmlFiles": [
    {"path": "index.html", "content": "<!doctype html>..."}
  ]
}
```

After creation, usually call:

1. `workbench_project_activate(projectId)`
2. `workbench_project_open(projectId)` when the user should see it immediately
3. `workbench_api_list(projectId)` before invoking Project Tools

## Project Tools

Project Tools are stable business actions behind the display. UI clicks and Agent calls must use the same tools.

Use these run targets by default:

- `native.collection.create`
- `native.collection.archive`
- `native.collection.update`
- `native.collection.list`
- `script` or `workspace_python_script` for Project-owned scripts
- `agent`, `oob.<tool>`, or `mcp.<tool>` for OOB capability composition

Do not expose arbitrary Android, filesystem, shell, or network access to HTML. Native/mobile capability must be wrapped by registered Project Tools.

## HTML Display

HTML is the fastest first-class display path. Use it for reports, charts, rich documents, comparisons, dashboards, custom interaction, and fast local visual edits.

Route:

```
/workbench/html?projectId=<id>
```

Pass files through `htmlFiles` in `workbench_project_create` or `workbench_project_update`. Include at least `index.html`.

**Target runtime: phone portrait Workspace WebView.**

Generated HTML is normally shown inside the right-side OOB Workspace on a real phone. Use the runtime Workbench layout profile injected by the app, especially `viewportWidthDp` and `viewportHeightDp`, instead of hard-coding phone dimensions. The first viewport must be compact and useful: show the title, current state/summary, and the primary controls or top findings within the measured visible height. Do not generate desktop landing heroes, oversized banners, full-screen decorative sections, large card stacks, or wide tables that require horizontal scrolling.

**Viewport and layout profiles:**

- Mobile interaction UI (lists, forms, dashboards): `<meta name="viewport" content="width=device-width, initial-scale=1">`. Use one-column layouts, `width: 100%`, compact spacing, sticky/visible primary actions, and touch targets at least 44px tall. Avoid panels taller than the measured `viewportHeightDp` unless they are scrollable content areas.
- Portrait report/document: also use `<meta name="viewport" content="width=device-width, initial-scale=1">`. Render as a phone-width article sized for vertical reading: executive summary in the first measured viewport, section anchors, short sections, responsive charts sized relative to the measured visible height, stacked comparison rows, and readable 14-16px body text. Convert wide tables into cards, definition lists, or horizontally summarized sections.
- Wide report, slide deck, or landscape comparison: use `<meta name="viewport" content="width=1280">` only when the user explicitly needs a wide fixed canvas. OOB scales it down to fit, so this is not the default for phone reports.

If no viewport tag is present, OOB defaults to `width=device-width`, but generated HTML should declare the intended profile explicitly.

HTML bridge:

```js
// Synchronous actions (CRUD tools) — result returned immediately
const result = await window.oob.callApi('meal.create', { date: '2026-05-12', calories: 450 });

// Read current project state
const project = await window.oob.getProject();
const items = project.items; // array of project records

// Async workflows (agent_task tools: VLM, camera, web, etc.)
// callApi returns {status:"pending"} immediately; result arrives via onProjectUpdated
const pending = await window.oob.callApi('meal.analyze', { date: '2026-05-12' });
// pending = { status: "pending", taskId: "..." }

// Subscribe to project updates — fires when any agent_task writes back to project data
window.oob.onProjectUpdated(function(project) {
  renderItems(project.items);
  hideLoading();
});

// Inspect mode: report selected element for hot-update context
window.oob.selectElement({ elementId: 'submit-btn', label: 'Submit' });
```

### Agent task pattern (for complex workflows)

Use `run: {use: "agent"}` when a button needs to trigger a multi-step AI workflow — take a photo, run VLM, call external API, write results back. These are always asynchronous.

```js
// HTML: show loading, call tool, wait for update event
async function analyzeAndLog() {
  document.getElementById('status').textContent = '分析中...';
  await window.oob.callApi('meal.analyze', { date: today() });
  // result arrives via onProjectUpdated callback above
}
window.oob.onProjectUpdated(function(project) {
  const meals = project.items.filter(i => i.fields.date === today());
  renderMeals(meals);
  document.getElementById('status').textContent = '';
});
```

Corresponding Project Tool definition:
```json
{
  "toolId": "meal.analyze",
  "displayName": "分析今日饮食",
  "description": "用 VLM 识别照片中的食物，计算热量，写入当天记录",
  "run": {
    "use": "agent",
    "prompt": "用 image_picker 选取一张食物照片，用 vlm_task 分析食物种类和热量，然后调用 workbench_api_call(meal.create, {date: inputs.date, calories: <数字>, foods: [<食物列表>]}) 写入项目数据。"
  }
}
```

Rules:

1. Use `window.oob.callApi(apiId, inputs)` for every backend action.
2. Use `window.oob.getProject()` to read current state synchronously after CRUD operations.
3. Use `window.oob.onProjectUpdated(callback)` when a tool uses `run.use: "agent"` — the result arrives asynchronously after the agent writes back to project data.
   - **Do not read `result.project` from `callApi` to get agent_task results** — that snapshot is captured before the agent runs. Read state only inside the `onProjectUpdated` callback.
   - Show a loading state between the `callApi` call and the `onProjectUpdated` callback.
4. Use `data-oob-id` on important elements so inspect/edit can target small changes.
5. Show domain labels to users, not tool ids, Project ids, executor names, paths, logs, or implementation badges.
6. Handle loading, empty, success, and error states inside the HTML.
7. Prefer local assets for production. CDN is acceptable for demos and iteration, especially charts.

## Default Project Display

If no HTML is supplied, OOB can render Project items and actions with the default Flutter Project Display. This is a generic fallback for structured data: lists, forms, buttons, text, and state. It is not a named product concept.

Use `entityName`, `initialItems`, and Project Tools to make this fallback useful. If the output needs a richer layout, add `htmlFiles` instead of adding a preset app flow.

## Flutter Eval Display

`flutterFiles` is a platform feature for hand-written Dart widgets only. **Do not generate `flutterFiles` automatically.** AI-generated Dart code routinely uses `GlobalKey`, `Form`, `showDialog`, and third-party packages that are not bridged in the `flutter_eval` runtime and will silently fail to compile. Use `htmlFiles` instead for all AI-generated display output.

This section documents constraints for human authors who deliberately write `flutterFiles`:

- Entry file defaults to `lib/main.dart`.
- Entry class defaults to `OobProjectWidget`.
- Do not write `void main()`, `runApp(...)`, a `MaterialApp` app entry, or third-party imports. The Host constructs the entry Widget directly.
- Prefer `class OobProjectWidget extends StatelessWidget/StatefulWidget { const OobProjectWidget(dynamic _, {super.key}); ... }`.
- Call Project Tools through `const MethodChannel('cn.com.omnimind.bot/AssistCoreEvent')` with method `workbenchApiCall` and arguments `projectId`, `apiId`, and `inputs`.
- Keep imports within the supported Flutter/runtime subset.
- Preserve existing Project Tool ids in MethodChannel calls during hot update.

**OOB 当前 flutter_eval 安全子集限制：**
- 这不是 Flutter SDK 编译器。只有 `flutter_eval` 已桥接并由 OOB 授权的类、函数和 MethodChannel 可用。
- `GlobalKey` / `GlobalKey<FormState>` / `GlobalKey<ScaffoldState>` 当前未纳入稳定子集 — 改用 `TextEditingController` + `bool` 状态替代表单验证和局部状态访问。
- `Form` / `FormState` / `FormField` 当前未纳入稳定子集 — 改用普通 `TextField` + 手动验证。
- `Navigator` / `MaterialPageRoute` 在 runtime 中只有部分桥接，容易因 Route、泛型回调、上下文和 overlay 生命周期失败；Project Display 内优先用 `setState` 切换页面内容。
- `showDialog(...)` 当前不要用于 Project Display；改用 `setState` 控制内联面板、底部区域或局部展开态。
- 第三方包不会自动可用。只有 OOB 预置、编译进 App、并提供 eval bridge/权限白名单的包才能用。
- `dart:io`、`dart:isolate`、`dart:mirrors` 不属于 Project UI 安全子集。文件、网络、进程、并发和反射能力必须通过 Project Tool / HTML bridge / OOB native API 暴露，不能直接从 eval UI 访问。

## Hot Update

When the user says “change this”, update the same Project.

1. Call `workbench_project_hot_update` with `projectId`, prompt, and `frontendContext` when available. Preserve `frontendContext.workbenchLayout` / `visibleState.workbenchLayout`; it is the App-measured Workspace/WebView size.
2. If it returns `requiresAgentRegeneration=true` and `frontendHtml.sources` exists, edit the smallest affected HTML/CSS/JS file.
3. Call `workbench_project_update` with `htmlFiles`.
4. Keep Project Tool ids stable unless the user asks to change the backend contract.

For selected HTML elements, prefer `frontendContext.selectedElement`, `frontendContext.workbenchLayout`, `data-oob-id`, nearby DOM text, and current `project.frontendHtml.sources`.

## Display Quality Rules

The display is the user's product surface. It should show the AI output or workflow state itself:

- summaries, evidence, records, statuses, forms, filters, and business actions
- mobile-first layout and touch-friendly controls
- concise text that fits its container
- visible empty/error/loading states

Do not show these in the display:

- Project id
- preset app names or renderer internals
- Toolbox / MCP tool names
- API counts
- executor kind
- workspace paths
- data/log paths
- `backend/api_spec.json`
- `frontend/page_spec.json`
- “generated frontend” or “OOB native UI” implementation copy

Those belong in `/workbench/projects`, debug resources, logs, or documentation.

## Common Flow

For a new visible AI output:

1. Decide whether HTML is needed. Use HTML for rich reports/charts/custom UI.
2. Define the minimal Project Tools required by the display.
3. Call `workbench_project_create` with `projectId`, `name`, `prompt`, optional `initialItems`, `apis`, and optional `htmlFiles`.
4. Activate and open the Project.
5. For further changes, update the same Project with `workbench_project_update`.

For a business action on an existing Project:

1. Get active Project if needed.
2. Call `workbench_api_list(projectId)`.
3. Call `workbench_api_call(projectId, apiId, inputs)`.
4. Do not edit data files directly.
