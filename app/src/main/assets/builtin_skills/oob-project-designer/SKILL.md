---
name: oob-project-designer
description: Design and create an OOB Workbench Project from a user's natural-language description of a personal tool, tracker, or recurring workflow. Use when the user says "帮我做一个", "我想创建一个", "build me a", "make a [X] tracker/tool/app", or expresses intent to build a new persistent personal tool from scratch. Also use for: designing the API set before calling workbench_project_create, choosing between native/script/agent executors, or reviewing a Project's frontend-backend contract.
---

# OOB Project Designer

This skill covers the **design and orchestration** of Workbench Projects. Technical HTML/CSS/JS patterns live in `references/frontend-guide.md`. API executor details and script patterns live in `references/backend-guide.md`. Full capability boundaries live in `references/capability-map.md`. Standard mobile CSS is in `assets/base.css`.

If `oob-native-workbench` is also loaded, defer to it for runtime contracts (data binding rules, HTML skeleton, hot update). If it is not loaded, use `references/frontend-guide.md` and `references/backend-guide.md` — they are self-sufficient. This skill covers the **design and orchestration** workflow.

If `oob-prompt-runtime` is also loaded, defer to it for Prompt Runtime architecture, prompt sectioning, prompt dump, and redaction rules. This skill should only define Project-specific intent, APIs, documents, and agent task prompts.

---

## Phase 1 — Intent Extraction

Before calling any tool, extract these from the user's message:

| What | Question to answer | Example |
|---|---|---|
| **Entity** | What is the core data object? | "健身记录" → `Workout` |
| **Fields** | What domain fields matter most? (3–5) | `date`, `calories`, `weight` |
| **Primary action** | What will the user do most often? | "Log a meal" |
| **Read view** | What does the user want to see at a glance? | "Today's calorie total vs goal" |
| **OOB native need** | Does any step need camera / VLM / web / calendar / notification? | "Photo → VLM → log" |

If the intent is ambiguous, ask **one** targeted question. Never ask about all five at once.

Entity naming rules:
- English, PascalCase, singular: `Expense`, `Workout`, `Mistake`, `Contact`
- Avoid generic names: `Record`, `Item`, `Data` — these produce useless default tool names
- The entity name appears in default tool display labels and in `entityName` field

---

## Phase 2 — API Design

Design APIs **before** writing HTML. HTML is bound to these IDs; changing them later breaks the UI.

### Executor decision tree

```
Does the action need a photo, screen, external search, calendar, or notification?
  YES → run: {use: "agent"} with a step-by-step prompt
  NO  → Does it need computation, export, or multi-record processing?
          YES → run: {use: "script"} + provide the Python script file
          NO  → run: {use: "native.collection.<action>"}
```

### native.collection operations

| use | Description | Required inputs |
|---|---|---|
| `native.collection.create` | Add a new item | `title: string`, any extra fields |
| `native.collection.update` | Update an existing item's fields | `item_id: string`, fields to update |
| `native.collection.archive` | Archive (soft-delete) an item | `item_id: string` |
| `native.collection.list` | Return all active items | _(none)_ |

### OOB native tools available inside agent tasks

| Tool | What it does | Common use in Projects |
|---|---|---|
| `vlm_task` | Analyze any image with VLM | Read food photos, parse receipts, OCR screenshots |
| `image_picker` | Let user pick from camera or gallery | Always precedes vlm_task when photo is needed |
| `notification_send` | Send a local push notification | Task done, goal reached, daily reminder |
| `calendar_event_create` | Create a calendar event | Sync plans from Project to system calendar |
| `calendar_event_list` | Read upcoming events | Import schedule into Project context |
| `browser_use` | Automate a browser session | Scrape a web source, fill a form |
| `web_search` | Lightweight web search | Fetch prices, news, external data |
| `terminal_execute` | Run Python/shell in Alpine sandbox | Computation, PDF export, data transformation |
| `schedule_task_create` | Schedule recurring agent task | Daily summary, weekly report |
| `alarm_reminder_create` | Set a device alarm | Time-based reminders |
| `android_privileged_action` | Tap/read any app screen via accessibility | Import data from WeChat, Alipay, etc. |
| `memory_upsert_longterm` | Write to long-term workspace memory | Persist user preferences across sessions |
| `file_read` / `file_write` | Read/write files in /workspace | Load exported data, write reports |
| `subagent_dispatch` | Spawn a sub-agent for a complex sub-task | Long-running multi-step background work |

### API count rules

- Start with **2–4 APIs** for v1. Users will ask to add more.
- Do not register an API the HTML never calls.
- Every registered API must have a corresponding action in the HTML.
- `native.collection.list` is rarely needed — HTML reads from `project.items` directly.

### Naming pattern

```
<entity-lowercase>.<verb>

meal.create          entry.import         finding.archive
meal.analyze         entry.stats          contact.remind
workout.log          mistake.add          report.generate
```

### Example API set (expense tracker)

```json
[
  {
    "toolId": "entry.create",
    "displayName": "记录支出",
    "description": "手动录入一笔支出。",
    "inputSchema": {"amount": "number", "note": "string", "category": "string?"},
    "outputSchema": {"item": "object"},
    "run": {"use": "native.collection.create"}
  },
  {
    "toolId": "entry.import",
    "displayName": "截图导入账单",
    "description": "截取支付宝/微信账单截图，VLM 解析后批量写入。",
    "inputSchema": {"date": "string?"},
    "outputSchema": {"count": "number", "items": "array"},
    "run": {
      "use": "agent",
      "prompt": "1. 用 image_picker(source=gallery) 让用户选一张账单截图，拿到 imagePath。2. 用 vlm_task(imagePath=imagePath, question='识别截图中的所有支出记录，返回 JSON 数组，每条含 amount(数字,元)、note(商户名或备注)、date(YYYY-MM-DD)、category(餐饮/交通/购物/其他)') 解析。3. 对每条记录调 workbench_api_call(projectId=inputs.projectId, apiId=entry.create, inputs={amount:<金额>, note:<备注>, category:<分类>})。4. 调 notification_send(title='账单已导入', body='共导入 N 笔') 通知用户。"
    }
  },
  {
    "toolId": "entry.archive",
    "displayName": "删除记录",
    "inputSchema": {"item_id": "string"},
    "outputSchema": {"item": "object"},
    "run": {"use": "native.collection.archive"}
  },
  {
    "toolId": "entry.monthly_stats",
    "displayName": "本月统计",
    "description": "计算本月各分类支出合计，返回统计数据。",
    "inputSchema": {"month": "string?"},
    "outputSchema": {"total": "number", "by_category": "object"},
    "run": {"use": "script", "path": "backend/scripts/monthly_stats.py"}
  }
]
```

---

## Phase 3 — Frontend Design

See `references/frontend-guide.md` for complete HTML/CSS patterns. Summary of requirements here:

**First screen must show:**
- Project title / current state summary (today's total, progress bar, etc.)
- Primary action button (the most common user action)
- List of recent items

**Must include:**
- `#status` div for status/error messages
- `.empty` state when no items
- `data-oob-id` on every interactive element (primary button, item cards, key sections)
- Loading state for every `callApi` call
- Error display on every `callApi` failure

**Data binding — non-negotiable:**
- Page load: `window.oob.getProject()` → render from `project.items`
- Sync CRUD: re-render from `result.project.items` after `callApi`
- Async agent tasks: subscribe `window.oob.onProjectUpdated()`, show spinner until callback
- Domain fields: always via `item.fields.*` after normalization through `toViewItem()`

**Styles:**
- Copy `assets/base.css` from this skill's assets dir into `frontend/html/base.css` and `<link>` it, OR inline it in `<style>`. Never depend on external CSS CDN for the core layout.
- Use CDN only for chart libraries (Chart.js) or icon sets (heroicons).
- Do not use CSS frameworks (Tailwind, Bootstrap) — they produce oversized HTML and break the token budget.

**Style import from other skills:**
A skill can provide CSS or component templates in its `assets/` directory. To use them in a Project:
```
# Agent: copy skill asset into project frontend
file_write(path="{spacePath}/frontend/html/base.css", content=<read from assetsDir/base.css>)
```
Then in HTML: `<link rel="stylesheet" href="base.css">`. This is the only supported mechanism — WebView serves from `frontend/html/` only, not from skill directories.

---

## Phase 4 — Generate

Call `workbench_project_create`:

```json
{
  "projectId": "oob-workbench-<domain>-<name>",
  "name": "<User-visible Chinese title>",
  "prompt": "<One sentence: what this project does, for whom, why>",
  "entityName": "<PascalCase>",
  "initialItems": [],
  "apis": [ /* Phase 2 result */ ],
  "htmlFiles": [
    {"path": "base.css", "content": "<contents of assets/base.css>"},
    {"path": "index.html", "content": "<Phase 3 result, links to base.css>"}
  ]
}
```

`projectId` rules: `oob-workbench-` prefix, lowercase, hyphens only, no spaces. Must be stable — never regenerate a new id for the same project.

If you include a Python script API (`run: {use: "script"}`), also include:
```json
"apis": [...],
"htmlFiles": [...],
// No dedicated field — write the script via workbench_project_update after creation:
// workbench_project_update(projectId, scriptFiles=[{path:"backend/scripts/monthly_stats.py", content:"..."}])
```

---

## Phase 4.5 — Initialize Project Documents

Run immediately after `workbench_project_create` returns `spacePath`, before validation.

### Write PROJECT_SOUL.md

```
file_write(path="{spacePath}/PROJECT_SOUL.md", content=<see format below>)
```

Extract the initial soul from the user's creation intent. Include everything the user expressed about preferences, constraints, or rules — even implicitly. This file is the authoritative behavioral contract for the project. The agent must read it before every hot update and before every agent task execution.

```markdown
# <Project Name> — Project Soul

## 创建意图
<One paragraph: what the user said they wanted, their goal, their context>

## 业务规则
- <Rule extracted from user intent, e.g. "奶茶归类娱乐，不算餐饮">
- <Rule, e.g. "不记录收入，只记录支出">
- <Rule, e.g. "单笔超过500元必须填备注">
(If no rules stated yet, write: "暂无明确规则，待用户使用中逐步补充。")

## 字段约束
- amount: 正数，单位人民币元，不为零
- category: 枚举值（餐饮 / 交通 / 购物 / 娱乐 / 医疗 / 其他），默认"其他"
- date: YYYY-MM-DD，未填时取今日
(List only fields with real constraints; omit unconstrained fields)

## 显示偏好
- 列表排序: 按 date 倒序
- 主色调: 蓝色 (#007AFF)
- 空状态文案: <domain-specific hint>
(If no preferences stated, write: "无特殊偏好，使用 base.css 默认样式。")

## 禁止行为
- 不得生成模拟数据或占位数据
- 不得在未告知用户的情况下修改已有数据
(Add domain-specific prohibitions if the user expressed any)

## 更新历史
- {ISO date}: 项目创建，初始规则从用户意图提取
```

**Update PROJECT_SOUL.md whenever:**
- The user states a new rule: "以后奶茶算娱乐" → append to 业务规则
- The user expresses a display preference: "换成深色主题" → update 显示偏好
- The user says "不要这样做" or "我不喜欢" → append to 禁止行为
- After any hot update that changes behavior → append to 更新历史

---

### Update PROJECT_CONTEXT.md — Field Types and Data Layout

The auto-generated `PROJECT_CONTEXT.md` has weak field schema (all `string`). Overwrite the field table with real types and add the Data Layout section immediately after creation.

Read the existing file, then call `file_edit` to replace the field table and add the layout:

**Field schema — real types:**

```markdown
## Item Fields Schema

| Field    | Type    | Required | Constraints / Notes              |
|----------|---------|----------|----------------------------------|
| title    | string  | yes      | Item display label               |
| amount   | number  | yes      | 正数，单位元                     |
| note     | string  | yes      | 商户名或自定义备注               |
| category | string  | no       | 餐饮/交通/购物/娱乐/医疗/其他   |
| date     | date    | yes      | YYYY-MM-DD，默认今日             |
```

Type vocabulary: `string`, `number`, `boolean`, `date` (YYYY-MM-DD), `datetime` (ISO), `array`, `object`, `enum:<val1>|<val2>`.

**Data Layout section — append after HTML Element Inventory:**

```markdown
## Data Layout

All paths are relative to `{spacePath}`:

| Path | Purpose | Managed by |
|---|---|---|
| `data/items.json` | All persisted items (active + archived) | Runtime — never write directly |
| `PROJECT_CONTEXT.md` | API contract, field schema, element IDs | Agent — update on API/field/HTML changes |
| `PROJECT_SOUL.md` | User rules, constraints, preferences | Agent — update when user expresses rules |
| `backend/scripts/` | Python scripts for `run:{use:"script"}` APIs | Agent — write via `file_write` |
| `frontend/html/` | HTML, CSS, JS display files | Agent — update via `workbench_project_update` |
| `exports/` | Files generated by scripts (CSV, PDF, etc.) | Agent scripts |
| `logs/api_calls.jsonl` | Execution log | Runtime — read-only |
```

See `references/project-soul-guide.md` for full format spec and update protocol.

---

## Phase 5 — Validate

After `workbench_project_create`, run the validation script:

```
terminal_execute:
  python3 {assetsDir}/../scripts/validate_project.py --project-path {spacePath}
```

`spacePath` is in the `createProject` response (`project.spacePath`).

Fix all FAIL items before activating. WARN items are acceptable for v1 but should be noted.

Most common failures:
- `callApi ids unregistered` → HTML calls a toolId not in `apis` list
- `getProject not called on load` → add `window.oob.getProject()` to load handler
- `onProjectUpdated not registered` → required even if no agent tasks in v1
- `direct field access` → `item.amount` should be `item.fields.amount` via `toViewItem()`
- `hardcoded data array` → remove and replace with render from `project.items`

---

## Phase 6 — Activate and Open

```
workbench_project_activate(projectId)
workbench_project_open(projectId)
```

Tell the user in **one sentence** what the project does, then one sentence on the primary action they should try first.

---

## Capability Boundaries (Summary)

Full table in `references/capability-map.md`. Key boundaries:

**CAN do from HTML (via registered Project Tools):**
- All CRUD on `project.items`
- Trigger VLM / photo analysis
- Show local notifications
- Create/read calendar events
- Run Python scripts in Alpine sandbox
- Web search, browser automation
- Schedule recurring tasks
- Read any other app's screen (via android_privileged, accessibility)

**CANNOT do from HTML directly:**
- Call arbitrary Android APIs — must go through registered Project Tools
- Read other Projects' data
- Establish persistent WebSocket connections
- Access hardware sensors (GPS, accelerometer, gyroscope)
- Write outside `/workspace` (no system DB, no other apps' storage)
- Background execution triggered from HTML (use `schedule_task_create` instead)
- Import npm packages — only CDN URLs

**Boundary cases:**
- Persistent timer: use `schedule_task_create`, not `setTimeout` across sessions
- Real-time multi-device sync: not supported — local only
- File export: write to `/workspace/projects/<id>/exports/`, then `workbench_project_export`

---

## Quality Checklist Before Activating

**Frontend / data binding:**
- [ ] Every `callApi(apiId)` in HTML references a registered `toolId` in `apis`
- [ ] Every registered API has a button or trigger in HTML
- [ ] Page load calls `window.oob.getProject()` and renders from `project.items`
- [ ] `window.oob.onProjectUpdated()` is registered
- [ ] Domain fields read from `item.fields.*` via `toViewItem()` adapter
- [ ] No hardcoded data arrays
- [ ] Loading + error + empty states present
- [ ] `data-oob-id` on primary button, item cards, summary section

**Project documents:**
- [ ] `PROJECT_SOUL.md` written with creation intent, rules, field constraints
- [ ] `PROJECT_CONTEXT.md` field schema has real types (not all `string`)
- [ ] `PROJECT_CONTEXT.md` has Data Layout section
- [ ] `PROJECT_CONTEXT.md` HTML Element Inventory is complete

**Validation and activation:**
- [ ] `validate_project.py` passes (0 FAIL)
- [ ] `workbench_project_activate` + `workbench_project_open` called after creation

**Ongoing (after every hot update or user rule change):**
- [ ] Update `PROJECT_SOUL.md` when user states a rule or preference
- [ ] Update `PROJECT_CONTEXT.md` when APIs, fields, or `data-oob-id` elements change
