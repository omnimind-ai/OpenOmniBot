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

## Design Principles

These are non-negotiable. Every Project display must follow them.

**1. The display is the user's work surface, not a demo.**
The HTML shows live, real, persistent data. It is not a prototype, not a preview, not a showcase. Every item rendered came from `project.items`. Every action the user takes writes back to `project.items` via a registered Project Tool.

**2. Every interaction is bound to a registered Project Tool.**
There is no "local state" that diverges from the backend. A user tapping a button triggers `window.oob.callApi(apiId, inputs)` → the result updates `project.items` → the HTML re-renders from the updated state. Client-side JS variables may hold transient UI state (which input is focused, is the form expanded) but never domain data.

**3. The registered API is the contract. HTML is the skin.**
The Project Tool ids (`meal.create`, `finding.archive`, etc.) are stable. The HTML can be regenerated, restyled, or replaced at any time without touching the API definitions. Never embed business logic in the HTML that should be in a Project Tool.

**4. Users do most things through conversation, not the UI.**
The HTML's primary job is to make AI output visible and to let users trigger the next AI step. It is not a full CRUD app. Prioritize: display current state clearly, primary action button prominently, archive/delete discreetly.

**5. Mobile-first, no exceptions.**
Target: phone portrait, 360-430dp wide. Touch targets ≥ 44px tall. One column. No horizontal scroll. No modals. No popovers. Expand/collapse inline.

## Project Model

A Workbench Project is a persistent container with:

- `projectId`: stable id for the container
- `apis`: Project Tools that both AI and UI can call
- `initialItems`: optional persisted state in `data/items.json`
- `htmlFiles`: optional default HTML/CSS/JS display files under `frontend/html/`
- `markdownFiles`: optional specialized Markdown display files under `frontend/markdown/`
- `flutterFiles`: optional limited hand-written flutter_eval display files under `frontend/flutter/`
- logs for API calls, hot updates, and progress

Do not ask for or invent preset app names. Do not create a replacement Project for a feature update unless the user explicitly asks for a new Project.

## Display Renderer Policy

Use HTML by default for visible Project output. It covers reports, charts, rich text, forms, dashboards, comparisons, and normal app-like interaction.

Use Markdown only when the user explicitly asks for Markdown, editable documents, notes, meeting minutes, plain-text long-form output, or when the Project is already a Markdown Display. Markdown is not the default UI path.

Use Flutter Eval only for deliberately hand-written Dart widgets that fit the supported runtime subset.

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

- `native.collection.create` / `archive` / `update` / `list` — simple CRUD, no AI needed
- `script` or `workspace_python_script` — runs Python in workspace, returns JSON
- `agent` — triggers a multi-step AI workflow; use for anything that needs OOB native capabilities

**Available OOB tools for `run: {use: "agent"}` prompts:**

| Tool | 用途 | 典型用法 |
|---|---|---|
| `image_picker` | 从相机/相册选图 | 先 picker 拿路径，再交给 vlm_task |
| `vlm_task` | 视觉理解屏幕/图片 | 分析食物、识别票据、理解文字 |
| `web_search` | 轻量网页搜索 | 查价格、查新闻、查资料 |
| `notification_send` | 发本地通知 | 任务完成提醒 |
| `calendar_event_create` | 创建日历事件 | 把 Project 里的计划同步到日历 |
| `terminal_execute` | 跑 Python 脚本 | 数据处理、统计计算 |
| `browser_use` | 浏览器自动化 | 抓页面、填表单 |

**Multi-step agent_task 示例（拍照记热量）：**
```json
{
  "toolId": "meal.analyze",
  "run": {
    "use": "agent",
    "prompt": "1. 用 image_picker(source=gallery) 让用户选一张食物照片，拿到 path。2. 用 vlm_task 分析照片里的食物种类和总热量（大卡）。3. 调 workbench_api_call(projectId=inputs.projectId, apiId=meal.create, inputs={date:inputs.date, calories:<数字>, foods:[<食物列表>]}) 写入记录。4. 调 notification_send(title='热量已记录', body='今日 +XXX 大卡') 通知用户。"
  }
}
```

**Multi-step agent_task 示例（搜索存档）：**
```json
{
  "toolId": "research.search",
  "run": {
    "use": "agent",
    "prompt": "1. 用 web_search(query=inputs.topic, limit=5) 搜索相关资料。2. 对每条结果调 workbench_api_call(projectId=inputs.projectId, apiId=finding.create, inputs={title:<标题>, url:<链接>, snippet:<摘要>}) 逐条存入 Project。"
  }
}
```

Do not expose arbitrary Android, filesystem, shell, or network access to HTML. Native/mobile capability must be wrapped by registered Project Tools.

## HTML Display

HTML is the default user-facing window into the Project state. The user does most things through a single backend conversation — they tell the AI to add, change, or analyze something — and the HTML must reflect every change in detail. A vague or static display breaks the whole loop.

HTML is the fastest first-class display path. Use it for reports, charts, rich documents, comparisons, dashboards, custom interaction, and fast local visual edits.

Route:

```
/workbench/html?projectId=<id>
```

Pass files through `htmlFiles` in `workbench_project_create` or `workbench_project_update`. Include at least `index.html`.

Default to one `index.html` with in-page state or hash routing. When splitting makes the output easier to maintain, additional local HTML files under the same `frontend/html/` root may link to each other with relative URLs such as `detail.html?id=1#summary`. OOB treats this as Project-local page replacement only: no browser back stack, no external navigation, and no arbitrary file URLs.

**Target runtime: phone portrait Workspace WebView.**

Generated HTML is normally shown inside the right-side OOB Workspace on a real phone. Use the runtime Workbench layout profile injected by the app, especially `viewportWidthDp` and `viewportHeightDp`, instead of hard-coding phone dimensions. The first viewport must be compact and useful: show the title, current state/summary, and the primary controls or top findings within the measured visible height. Do not generate desktop landing heroes, oversized banners, full-screen decorative sections, large card stacks, or wide tables that require horizontal scrolling.

**Viewport and layout profiles:**

- Mobile interaction UI (lists, forms, dashboards): `<meta name="viewport" content="width=device-width, initial-scale=1">`. Use one-column layouts, `width: 100%`, compact spacing, sticky/visible primary actions, and touch targets at least 44px tall. Avoid panels taller than the measured `viewportHeightDp` unless they are scrollable content areas.
- Search, filter, add/edit inputs, and primary actions must remain visible and usable at the measured right-side WebView width. Do not hide search boxes or critical toolbars behind desktop-only breakpoints; on narrow widths, stack or wrap them instead.
- Portrait report/document: also use `<meta name="viewport" content="width=device-width, initial-scale=1">`. Render as a phone-width article sized for vertical reading: executive summary in the first measured viewport, section anchors, short sections, responsive charts sized relative to the measured visible height, stacked comparison rows, and readable 14-16px body text. Convert wide tables into cards, definition lists, or horizontally summarized sections.
- Wide report, slide deck, or landscape comparison: use `<meta name="viewport" content="width=1280">` only when the user explicitly needs a wide fixed canvas. OOB scales it down to fit, so this is not the default for phone reports.

If no viewport tag is present, OOB defaults to `width=device-width`, but generated HTML should declare the intended profile explicitly.

**Data contract: `project.items` is the real persistent state, not mock data.**

Every item written by `callApi` is stored in the Project's `data/items.json` and survives app restarts. The HTML must read and render from `project.items` — never hardcode sample data arrays. On page load, call `window.oob.getProject()` to get the current real state.

Persisted items always use the Workbench item envelope:

```js
{
  id: "item-id",
  title: "User visible title",
  status: "active",        // archived/deleted records may still exist
  fields: {
    // domain fields written by Project Tools live here
    amount: 12.5,
    type: "expense",
    category: "餐饮",
    date: "2026-05-12",
    note: "午饭"
  }
}
```

HTML must treat `id`, `title`, and `status` as Workbench metadata, and all app/domain fields as `item.fields.*`. Do not read raw persisted records as `item.amount`, `item.type`, `item.category`, `item.date`, `item.note`, etc. unless the code first normalizes from `item.fields`.

Use a small view-model adapter at the boundary:

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

function activeViewItems(project) {
  return (project.items || [])
    .filter(item => (item.status || 'active') === 'active')
    .map(toViewItem);
}
```

`window.oob.callApi(apiId, inputs)` returns a result envelope, not a raw list:

```js
{
  success: true,
  apiId: "expense.list",
  outputs: { items: [/* optional API outputs */] },
  project: { items: [/* updated Workbench item envelopes */] },
  errorCode: "OPTIONAL_ERROR",
  errorMessage: "Optional error message"
}
```

For list APIs, use `result.outputs.items || result.project.items`, then normalize each item from `fields`. Never use `result.items`. For create/update/archive, refresh immediately from `result.project.items` if present; otherwise call `window.oob.getProject()` again.

If a Project Tool uses `run.use: "workspace_python_script"` or `run.use: "script"`, the referenced script file must be present under the Project backend, for example `backend/scripts/expense_stats.py`. If the script is missing, the API returns `SCRIPT_NOT_FOUND`; either provide the script or compute simple derived values in HTML from `project.items`.

HTML bridge:

```js
// ── Page load: read real data, never hardcode ──────────────────────────────
window.addEventListener('load', async function() {
  const project = await window.oob.getProject();
  renderItems(activeViewItems(project)); // normalized from item.fields
});

// ── Synchronous CRUD: result.project.items is the updated state ───────────
async function addEntry(amount, note) {
  const result = await window.oob.callApi('entry.create', { amount, note });
  renderItems((result.project.items || []).map(toViewItem)); // re-render immediately
}

// ── Async agent_task: result arrives via onProjectUpdated ─────────────────
// callApi returns {status:"pending"} immediately
await window.oob.callApi('meal.analyze', { date: '2026-05-12' });

window.oob.onProjectUpdated(function(project) {
  if (project._taskError) { showError(project.errorMessage); return; }
  renderItems(activeViewItems(project));
});

// ── Inspect mode ──────────────────────────────────────────────────────────
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
2. **On page load, call `window.oob.getProject()` and render from `project.items`. Never hardcode data arrays — `project.items` is the real persistent state.**
3. Always normalize raw Workbench items before rendering: app fields live under `item.fields`, not on the top-level item object.
4. Treat `callApi` return values as envelopes. Read API outputs from `result.outputs`, updated Project state from `result.project`, and errors from `result.errorCode` / `result.errorMessage`; never read `result.items`.
5. For sync CRUD (`native.collection.create/update/archive`): re-render from `result.project.items` returned by `callApi`. If the envelope has no `project.items`, call `getProject()` and render that state.
6. For list APIs: prefer `result.outputs.items || result.project.items`, then normalize from `fields`.
7. Use `window.oob.getProject()` only when you need full project state outside of a callApi flow.
8. Use `window.oob.onProjectUpdated(callback)` when a tool uses `run.use: "agent"` — the result arrives asynchronously after the agent writes back to project data.
   - **Do not read `result.project` from `callApi` to get agent_task results** — that snapshot is captured before the agent runs. Read state only inside the `onProjectUpdated` callback.
   - Show a loading state between the `callApi` call and the `onProjectUpdated` callback.
   - Check `project._taskError` in the callback: if true, the agent task timed out or failed. Show an error message and hide the loading state.
   ```js
   window.oob.onProjectUpdated(function(project) {
     hideLoading();
     if (project._taskError) { showError(project.errorMessage); return; }
     renderItems(activeViewItems(project));
   });
   ```
9. Before finalizing HTML, scan for raw `project.items` usages that read domain fields directly, such as `item.amount`, `item.type`, `item.category`, `item.date`, or `item.note`. Those are valid only on normalized view models, not on Workbench item envelopes.
10. If an API references `script` or `workspace_python_script`, include the referenced backend script file or remove that API. A missing script is a backend contract failure, not an empty-data state.
11. Use `data-oob-id` on important elements so inspect/edit can target small changes.
12. Show domain labels to users, not tool ids, Project ids, executor names, paths, logs, or implementation badges.
13. Handle loading, empty, success, and error states inside the HTML.
14. Prefer local assets for production. CDN is acceptable for demos and iteration, especially charts.

## Markdown Display

Markdown is an optional specialized display path. Use it only for explicitly requested Markdown, editable documents, notes, meeting minutes, plain-text long-form output, or when hot-updating an existing Markdown Display.

Route:

```
/workbench/markdown?projectId=<id>
```

Pass files through `markdownFiles` in `workbench_project_create` or `workbench_project_update`. Include at least `index.md`.

The right-side Project area supports preview, edit, split live preview, and save for Markdown sources. Do not use Markdown for complex interaction, charts, forms, or app-like controls; use HTML for those.

## Default Project Display

If no HTML or Markdown display source is supplied, OOB can render Project items and actions with the default Flutter Project Display. This is a generic fallback for structured data: lists, forms, buttons, text, and state. It is not a named product concept.

Use `entityName`, `initialItems`, and Project Tools to make this fallback useful. If the output needs a richer layout, add `htmlFiles` instead of adding a preset app flow. Add `markdownFiles` only for explicit Markdown/editable-document output.

## Flutter Eval Display

`flutterFiles` is a platform feature for hand-written Dart widgets only. **Do not generate `flutterFiles` automatically.** AI-generated Dart code routinely uses `GlobalKey`, `Form`, `showDialog`, and third-party packages that are not bridged in the `flutter_eval` runtime and will silently fail to compile. Use `htmlFiles` instead for generated display output unless the user explicitly requested Markdown/editable-document output.

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

## Interaction Design

### Interaction → API Binding

Every user action must be bound to a registered Project Tool. This is the contract:

```
User taps/submits → callApi(registeredToolId, inputs) → re-render from result
```

**Rule: one action = one callApi. Never bypass the API.**

| Interaction | Correct binding | Wrong |
|---|---|---|
| Tap “Add” button | `callApi('entry.create', {amount, note})` | DOM insert + localStorage |
| Tap “Done” on item | `callApi('entry.archive', {item_id})` | `item.style.display='none'` |
| Edit field inline | `callApi('entry.update', {item_id, field, value})` | Update JS variable only |
| Agent workflow button | `callApi('meal.analyze', {date})` → `onProjectUpdated` | Fake loading + hardcoded result |

### Form → API Pattern

Forms collect inputs and fire one `callApi` on submit. No multi-step wizards.

```html
<!-- Input form -->
<div id="add-form" class="card" style="display:none">
  <input id="inp-amount" type="number" placeholder="金额" style="width:100%;padding:10px;border:1px solid #ddd;border-radius:8px;font-size:15px">
  <input id="inp-note" type="text" placeholder="备注" style="width:100%;padding:10px;border:1px solid #ddd;border-radius:8px;font-size:15px;margin-top:8px">
  <div id="form-error" class="error-msg" style="display:none"></div>
  <button class="btn-primary" style="margin-top:12px" onclick="submitAdd()">保存</button>
</div>
```

```js
async function submitAdd() {
  const amount = document.getElementById('inp-amount').value.trim();
  const note = document.getElementById('inp-note').value.trim();
  if (!amount) { showFormError('inp-amount', '金额不能为空'); return; }
  setLoading(true);
  const result = await window.oob.callApi('entry.create', { amount: Number(amount), note });
  setLoading(false);
  if (!result.success) { showFormError(null, result.errorMessage || '保存失败'); return; }
  closeForm();
  renderItems(result.project.items);
}
function showFormError(inputId, msg) {
  const el = document.getElementById('form-error');
  el.textContent = msg; el.style.display = 'block';
  if (inputId) document.getElementById(inputId).focus();
}
```

### List Item Actions

Bind item-level actions to `item.id`. Use inline expand, not modal.

```html
<!-- Item card -->
<div class="card" data-oob-id="item-${item.id}">
  <div style="display:flex;justify-content:space-between;align-items:center">
    <span>${item.fields.amount} 元</span>
    <button onclick="archiveItem('${item.id}')" style="background:none;border:none;color:#999;font-size:13px;padding:8px">归档</button>
  </div>
  <div style="color:#666;font-size:13px">${item.fields.note || ''}</div>
</div>
```

```js
async function archiveItem(itemId) {
  setLoading(true);
  const result = await window.oob.callApi('entry.archive', { item_id: itemId });
  setLoading(false);
  renderItems(result.project.items);
}
```

### State Machine

Every button that calls `callApi` must implement this state machine — no exceptions:

```
idle → [tap] → loading (button disabled, status shows “处理中…”)
            → success: re-render items, clear status
            → error: show error-msg, re-enable button (user can retry)
```

```js
async function callApiAction(apiId, inputs, btn) {
  if (btn) btn.disabled = true;
  showStatus('处理中…');
  try {
    const result = await window.oob.callApi(apiId, inputs);
    if (result.success === false) throw new Error(result.errorMessage || '操作失败');
    showStatus('');
    if (result.project) renderItems(result.project.items);
  } catch (e) {
    showError(e.message);
  } finally {
    if (btn) btn.disabled = false;
  }
}
```

### Agent Task State Machine

Agent tasks are asynchronous. The UI must show a clear pending state:

```
idle → [tap] → pending (show spinner, update status to “分析中…”, disable button)
            → onProjectUpdated fires:
                → _taskError=true: show error, stop spinner, re-enable
                → success: hide spinner, re-render items
            OR → timeout (120s): show “操作超时，请重试”, re-enable
```

Never leave the UI in “loading forever” state. The bridge already enforces 120s timeout.

### Registered Tool IDs Are Stable

When creating a Project, define the minimal set of APIs needed by the HTML. The HTML references these IDs directly in `callApi`. Do not rename tool IDs after creation without updating the HTML simultaneously.

**Checklist before finalizing a Project:**
- [ ] Every `callApi(apiId, ...)` in HTML references a registered `toolId` in `apis`
- [ ] Every registered API has a corresponding button or action in HTML
- [ ] No `apiId` in HTML that is not in the `apis` list
- [ ] Every action has loading + error + success states
- [ ] Page load calls `getProject()` and renders real data

## Hot Update

When the user says “change this”, update the same Project.

1. Call `workbench_project_hot_update` with `projectId`, prompt, and `frontendContext` when available. Preserve `frontendContext.workbenchLayout` / `visibleState.workbenchLayout`; it is the App-measured Workspace/WebView size.
2. If it returns `requiresAgentRegeneration=true`, keep the same current renderer: edit `frontendHtml.sources` and call `workbench_project_update` with `htmlFiles` for HTML; edit `frontendMarkdown.sources` and call with `markdownFiles` for Markdown; edit `frontendFlutter.sources` and call with `flutterFiles` only for Flutter Eval.
3. If the current renderer is unclear and HTML sources exist, default to HTML.
4. Keep Project Tool ids stable unless the user asks to change the backend contract.

For selected HTML elements, prefer `frontendContext.selectedElement`, `frontendContext.workbenchLayout`, `data-oob-id`, nearby DOM text, and current `project.frontendHtml.sources`. For Markdown, prefer the entry file and current `project.frontendMarkdown.sources`.

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

1. Default to HTML for visible output. Use Markdown only for explicit Markdown/editable-document/plain-text long-form output.
2. Define the minimal Project Tools required by the display.
3. Call `workbench_project_create` with `projectId`, `name`, `prompt`, optional `initialItems`, `apis`, and optional `htmlFiles` or `markdownFiles`.
4. Activate and open the Project.
5. For further changes, update the same Project with `workbench_project_update`.

For a business action on an existing Project:

1. Get active Project if needed.
2. Call `workbench_api_list(projectId)`.
3. Call `workbench_api_call(projectId, apiId, inputs)`.
4. Do not edit data files directly.

## HTML Skeleton

Every generated `index.html` must follow this skeleton — do not invent a different structure:

```html
<!doctype html>
<html lang="zh">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title><!-- project name --></title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, sans-serif; font-size: 15px;
           color: #1a1a1a; background: #f5f5f7; padding: 16px; }
    /* layout: one column, max 430px */
    .container { max-width: 430px; margin: 0 auto; }
    /* status bar */
    #status { min-height: 20px; font-size: 13px; color: #666; margin-bottom: 12px; }
    /* loading skeleton */
    .loading { opacity: .4; pointer-events: none; }
    /* empty state */
    .empty { text-align: center; padding: 40px 0; color: #999; }
    /* error state */
    .error-msg { color: #c00; font-size: 13px; padding: 8px 12px;
                 background: #fff0f0; border-radius: 8px; margin-top: 8px; }
    /* primary action button */
    .btn-primary { width: 100%; padding: 14px; border: none; border-radius: 12px;
                   background: #007AFF; color: #fff; font-size: 16px;
                   font-weight: 600; cursor: pointer; margin-bottom: 16px; }
    /* card */
    .card { background: #fff; border-radius: 12px; padding: 14px;
            margin-bottom: 10px; box-shadow: 0 1px 4px rgba(0,0,0,.06); }
  </style>
</head>
<body>
<div class="container">
  <div id="status"></div>
  <div id="main"><!-- content --></div>
</div>
<script>
// ── init ─────────────────────────────────────────────────────────
window.addEventListener('load', async () => {
  showStatus('加载中…');
  const project = await window.oob.getProject();
  renderItems(project.items);
  showStatus('');
});

// ── subscribe to backend updates ─────────────────────────────────
window.oob.onProjectUpdated(function(project) {
  showStatus('');
  if (project._taskError) { showError(project.errorMessage || '操作失败'); return; }
  renderItems(project.items);
});

// ── render ────────────────────────────────────────────────────────
function renderItems(items) {
  const el = document.getElementById('main');
  if (!items || items.length === 0) {
    el.innerHTML = '<div class="empty">暂无数据，点击上方按钮开始记录</div>';
    return;
  }
  el.innerHTML = items.map(item => `
    <div class="card" data-oob-id="item-${item.id}">
      <div>${item.title}</div>
    </div>`).join('');
}

// ── actions ───────────────────────────────────────────────────────
async function callApiAction(apiId, inputs) {
  showStatus('处理中…');
  try {
    const result = await window.oob.callApi(apiId, inputs);
    if (result.project) renderItems(result.project.items);
    showStatus('');
  } catch (e) { showError(e.message); }
}

// ── helpers ───────────────────────────────────────────────────────
function showStatus(msg) { document.getElementById('status').textContent = msg; }
function showError(msg) {
  const el = document.getElementById('status');
  el.innerHTML = `<div class="error-msg">${msg}</div>`;
}
</script>
</body>
</html>
```

**Rules for adapting the skeleton:**
- Replace `renderItems` body with domain-specific card markup
- Add action buttons above `#main`, wired to `callApiAction(apiId, inputs)`
- For `agent_task` tools: call `callApi`, show loading, wait for `onProjectUpdated`
- Keep `#status`, `.empty`, `.error-msg` — they are UX requirements, not optional
- Add CDN chart libraries (Chart.js) only for data visualization, not decorative purposes

## Project Context File (`PROJECT_CONTEXT.md`)

Every new Project automatically gets a `PROJECT_CONTEXT.md` in its workspace (`{spacePath}/PROJECT_CONTEXT.md`). It is the agent's living reference for the project's API contract, item field schema, and known HTML element IDs.

**Read it before every hot update** — `hotUpdateProject` instructions include its path. Use `file_read` to load it explicitly if needed.

**Update it when**:
- You add or remove Project Tools (APIs)
- You change `item.fields` field definitions
- You add new `data-oob-id` elements to the HTML
- The user defines a design constraint or naming convention worth preserving

```markdown
# My Project — Project Context

## API Contract

| Tool ID | Executor | Inputs | Description |
|---|---|---|---|
| meal.create | native.collection.create | date: string, calories: number | 记录一餐 |

## Item Fields Schema

| Field | Type | Notes |
|---|---|---|
| date | string | YYYY-MM-DD |
| calories | number | Total kcal |

## HTML Element Inventory (data-oob-id)

| oob-id | Element | Purpose |
|---|---|---|
| btn-add | button | Primary add action |
| card-{id} | div | Item card |

## Design Notes

- Mobile-first, single column
- Creation intent: ...
```

Treat `PROJECT_CONTEXT.md` as the authoritative reference for field names, API IDs, and element anchors. If the HTML diverges (e.g. after a patch), update the context file to match.

## Hot Update — htmlPatches (Surgical Editing)

When `workbench_project_hot_update` returns `requiresAgentRegeneration=true`, **always prefer `htmlPatches` over full `htmlFiles` rewrite**.

| Approach | Token cost | When to use |
|---|---|---|
| `htmlPatches` | ~50 | CSS/style/text/attribute change, add/remove elements |
| `htmlFiles` (full) | ~5000 | >50% of file restructured |

### Targeting strategy

1. **If `frontendContext.selectedElement.oobId` exists** (e.g. `btn-submit`):
   - Search directly for `data-oob-id="btn-submit"` in the HTML as the `oldText` anchor
   - Do NOT read the full HTML file — use the oobId as a direct key
   - `file_read` only a few lines around the element if you need surrounding context

2. **If no oobId**: use `file_read(lineStart/lineCount)` to read the relevant section only

3. **`frontendContext.annotationDescription`** describes the user's gesture in plain English:
   - `"User drew a circle around the BUTTON labeled '打卡' (data-oob-id: btn-submit)"` → target that button
   - `"User drew an arrow pointing at the SECTION labeled '月度统计'"` → target that section

### htmlPatches examples

```json
// CSS change
{"htmlPatches": [{"path": "index.html", "oldText": "font-size: 14px", "newText": "font-size: 18px"}]}

// Add element (anchor the parent closing tag)
{"htmlPatches": [{"path": "index.html", "oldText": "</div>\n</body>", "newText": "<p>新段落</p>\n</div>\n</body>"}]}

// Remove element
{"htmlPatches": [{"path": "index.html", "oldText": "<div class=\"old-section\">...</div>", "newText": ""}]}

// Multiple changes in one call
{"htmlPatches": [
  {"path": "index.html", "oldText": "font-size: 14px", "newText": "font-size: 18px"},
  {"path": "index.html", "oldText": "color: #999", "newText": "color: #333"}
]}
```

## Control Flow Rules

### When to create vs when to api_call

**Create a Project only when** the user explicitly says "create/new/build a Project" or "make a visible OOB Product".
- Do NOT create a Project for "save this", "add a record", "summarize this link", "archive this".
- For business operations on an existing Project: `workbench_project_active_get` → `workbench_api_list(projectId)` → `workbench_api_call(projectId, apiId, inputs)`.
- If no active Project, ask the user to create or select one first.

### Project management control tools

| User intent | Tool to call |
|---|---|
| List / view projects | `workbench_project_list` |
| Open a project display | `workbench_project_open` |
| Activate / make current | `workbench_project_activate` |
| Deactivate | `workbench_project_deactivate` |
| Delete (with explicit id) | `workbench_project_delete` |
| Delete (id unclear) | `workbench_project_list` first, then confirm |
| Modify frontend/backend | `workbench_project_get` then `workbench_project_hot_update` |
| Check creation progress | `workbench_project_progress_get` |
| Ingest GitHub/OSS source | `workbench_project_ingest_oss` (confirm project exists first) |

After creation: call `workbench_project_activate`, then `workbench_project_open` if user should see it immediately.

### Display rules

The Project Display is the right-side work surface. Never show inside it:
- Project id, API counts, executor kind, Workspace path, data/log paths
- "This is generated" badges, `backend/api_spec.json`, `OOB native UI` labels

These belong only in `/workbench/projects` detail, info popup, logs, or MCP resources.
