# OOB Project Lifecycle Guide

This guide holds the detailed create/update workflow for `oob-project`. Keep `SKILL.md` as the concise routing entry; load this file when creating or materially changing a Project.

## New Project Flow

### Prelude

Classify the user input before planning:

| Mode | Signal | Handling |
|---|---|---|
| A | One sentence, broad request | Run the full flow. |
| B | Direction exists but incomplete | Keep Phase 0 short and use the user's idea as the backbone. |
| C | Detailed spec, >100 words, or explicit fields | Still do mini product + OSS research, but preserve user-provided fields, rules, and features. |

The user's original wording goes into `PROJECT_SOUL.md` unchanged.

### Phase 0: Product + OSS Research

Research calibrates a small personal tool. It is not a competitor report and must not copy assets, brands, private code, or product identity.

Required:

1. Product/tool research: at least 2 `web_search` calls about strong tools in the domain and what they do well.
2. OSS research: at least 1 GitHub/OSS query such as `web_search("<domain> open source GitHub app")` or `web_search("site:github.com <domain> tracker")`.
3. If search results are only lists, open 1-2 representative pages or READMEs for actual model, UI, workflow, or issue signals.

Output:

- Product/tool findings: 2-3 bullets with source or URL.
- OSS inspiration: 1-2 bullets with project name or URL.
- OOB v1 design position: which findings become fields, primary action, display, or agent action.
- Out of scope: anything useful but too large for v1.

Use this table shape:

| 现有优秀工具已做好的 | 开源项目可借鉴的 | OOB 能在此基础上加什么 |
|---|---|---|
| 记账工具已有分类、图表 | 开源记账项目常用 transaction/category/account | 拍截图自动录入，和其他 Project 数据联动 |

### Phase 1: Intent Extraction

Extract:

| Item | Rule |
|---|---|
| Entity | PascalCase, singular, for example `Meal`, `Expense`, `Paper` |
| Fields | 3-5 core fields, max 6 |
| Primary action | One user-centered verb phrase |
| OOB native needs | Camera, VLM, calendar, notification, web, accessibility, etc. |

Scope gate:

- Entity: default 1.
- Agent action: 0-1; only add when the primary loop needs OOB capability.
- Native CRUD: create/archive required; update/list/get only when needed.
- Page: 1 list/dashboard page.
- Chart: 0-1 lightweight chart; no full BI.

### Phase 1.5: Proposal

After research and intent extraction, show a proposal and wait for explicit confirmation.

```markdown
## 方案：<项目名>

### 这是什么
<一两句直接描述>

### 功能
- <功能1，服务主闭环>
- <功能2，最多 3 条>

### 交互
- 主操作：<用户完成一次主操作的路径>
- <关键 UX 时刻>

### 亮点
⚡ <体现统一管理、自由定制或 OOB 原生能力>

### 暂不支持
- <功能X>（原因）
- <功能Y>（留到 v2）

这个方向对吗？有什么要改的？
```

Do not proceed until the user explicitly says yes, continue, OK, or equivalent.

### Phase 1.8: ProjectContract

The ProjectContract is the single source of truth for data, tools, and display. Generate API and HTML from it; do not invent separate field names later.

Before designing agent actions:

1. Read `capability-map.md`.
2. If a capability is new or unclear, read `review-guide.md` capability rules.
3. For external APIs or domain-specific data sources, research real endpoints, URL patterns, field formats, and limits. Write those facts into `agentPrompt`.
4. Read `agent-prompt-templates.md` before writing agent prompts.

Contract shape:

```json
{
  "projectId": "oob-workbench-<domain>-<name>",
  "name": "<用户可见中文名>",
  "entity": {
    "name": "<PascalCase>",
    "primaryAction": "<主操作动词>"
  },
  "fields": [
    {"name": "<field1>", "type": "number|string|date|boolean", "required": true},
    {"name": "<field2>", "type": "string"}
  ],
  "actions": [
    {
      "id": "<entity>.<verb>",
      "executor": "native.collection.create",
      "displayName": "<用户可见名>",
      "inputs": {"<field>": "number", "<field2>": "string?"}
    },
    {
      "id": "<entity>.archive",
      "executor": "native.collection.archive",
      "inputs": {"item_id": "string"}
    },
    {
      "id": "<entity>.<agentVerb>",
      "executor": "agent",
      "displayName": "<用户可见名>",
      "capabilities": ["image_picker", "vlm_task", "workbench_api_call"],
      "agentPrompt": "<完整任务、字段映射、写回 apiId、完成信号>",
      "inputs": {"<field>": "string?"}
    }
  ],
  "views": {
    "primary": "<顶部第一眼看到的>",
    "list": "<列表字段排列和排序规则>",
    "empty": "<空状态引导语>"
  }
}
```

Constraints:

- `entity.name` must be PascalCase.
- `fields` must be non-empty; default 3-5, max 6.
- Field and action input names must be JS-safe lowerCamel or snake identifiers.
- Field types: `string`, `number`, `boolean`, `date`, `integer`; action inputs may add `?`.
- `actions` must be non-empty.
- Agent action max: 1.
- `action.id` format: `<entity>.<verb>`.
- Agent action must declare `capabilities`; each must exist in `capability-map.md` or be explicitly out of scope.
- Agent action must end by writing back through `workbench_api_call` when it produces Project data.

Executor choice:

| Need | Executor |
|---|---|
| Simple CRUD | `native.collection.*` |
| Camera/VLM/search/calendar/notification/accessibility | `agent` |
| Calculation/export/complex filtering | `script` |

### Phase 3.5: Review

Run capability-aware review before create or major update. Use `review-guide.md` for the full checklist.

Required output:

```text
Project Review
- Contract: PASS|FAIL
- Data/Tool/Display binding: PASS|FAIL
- Capability fit: PASS|WARN|FAIL
- Runtime behavior: PASS|WARN|FAIL
- Extensibility notes: <新增小万能力时该 Project 如何接入>
结论：✅ 通过 / ⚠️ 修复了 N 项 / ❌ 阻塞
```

Fix all FAIL items before creating/updating.

### Phase 4: Create

Preferred path: use the builder.

```bash
python3 {skillDir}/scripts/build_project_from_contract.py \
  --contract '<ProjectContract json>'
```

If the contract is saved as a file:

```bash
python3 {skillDir}/scripts/build_project_from_contract.py \
  --contract-file '<contract.json>'
```

For custom HTML:

```bash
python3 {skillDir}/scripts/build_project_from_contract.py \
  --contract-file '<contract.json>' \
  --custom-html '<index.html>'
```

Then in Android OOB:

1. Read builder stdout JSON.
2. Call `workbench_project_create` with `projectId`, `name`, `entityName`, `apis`, and `htmlFiles`.
3. Write `PROJECT_SOUL.md` and `PROJECT_CONTEXT.md` from builder docs when present.
4. Call `workbench_project_activate(projectId)`.
5. Call `workbench_project_open(projectId)`.

Portable/local mode:

```bash
python3 {skillDir}/scripts/build_project_from_contract.py \
  --contract-file '<contract.json>' \
  --execute \
  --workspace './workspace'
```

Portable `--execute` only supports `native.collection.*`. Agent/script executors return a not-supported status and need manual simulation or Android runtime.

### Phase 4.5: Project Docs

When the builder does not write docs for you, create them immediately.

`PROJECT_SOUL.md`:

```markdown
# <项目名> — Project Soul

## 这是什么
<from proposal>

## 功能
- <function>

## 亮点
⚡ <value>

## 创建意图
<user original words, unchanged>

## 业务规则
- <rule>（用户原话）

## 字段约束
- <field>: <type>, <constraint>, default <default>

## 禁止行为
- 不得生成模拟数据或占位数据
- 不得在未告知用户的情况下修改已有数据

## 更新历史
- <ISO date>: 项目创建
```

`PROJECT_CONTEXT.md`:

```markdown
## API Contract
| Tool ID | Executor | Inputs | Description |

## Item Fields Schema
| Field | Type | Required | Notes |

## HTML Element Inventory (data-oob-id)
| oob-id | Element | Purpose |

## API 领域知识
### <apiId> — <任务名>
- 数据源：<URL/API endpoint>
- 字段映射：<外部字段> -> <item.fields字段>
- 限制：<rate limit/登录/时效>
- 调研日期：<YYYY-MM-DD>
```

Optional docs:

- `DESIGN.md`: design decisions from Phase 0.
- `API_REFERENCE.md`: human-readable API docs.
- `RESEARCH.md`: product/OSS/API research records.

### Phase 5: Validate

```bash
python3 {skillDir}/scripts/validate_project.py --project-path {spacePath}
```

Fix all FAIL items before activation.

## Update Flow

Always read first:

```text
PROJECT_SOUL.md
PROJECT_CONTEXT.md
```

Map the request to the lightest safe path:

| Change | Research | Contract | Tool | Docs |
|---|---|---|---|---|
| Bug fix | No | No | hot update or targeted update | Context if oob-id/API changed |
| Style/text | No | No | `htmlPatches` first | Usually no |
| Add field | Usually no | Yes | `workbench_project_update` | Fields + HTML inventory |
| Add API | Mini product/API research | Yes | `workbench_project_update` | API contract + research |
| Rule change | No unless external data | Maybe | update prompt/display if affected | PROJECT_SOUL first |

Field changes:

```bash
python3 {skillDir}/scripts/apply_field_change.py \
  --project-json {spacePath}/project.json \
  --op add --field '{"name": "category", "type": "string"}'
```

Supported operations: `add`, `remove`, `rename`. Follow the script's three-layer output.

Rules:

- Field rename must keep backward compatibility in `toViewItem()` by reading old and new keys.
- Do not edit `data/items.json` directly.
- Do not change an existing `toolId` unless the old API is removed from HTML and docs.
- For HTML-only changes, prefer `workbench_project_hot_update`.
- Use full `htmlFiles` only when the structure changes substantially.

## Runtime Contract Quick Reference

HTML must:

1. Load with `await window.oob.getProject()`.
2. Register `window.oob.onProjectUpdated(...)` at top script scope.
3. Render from `project.items`; no hardcoded item arrays.
4. Convert records through `toViewItem(item)` and read business fields from `item.fields || {}`.
5. Call APIs with exact registered `toolId`.
6. Escape user content before injecting HTML.
7. Show loading and error states for every visible action.

Executor result handling:

| Executor | After `callApi` |
|---|---|
| `native.collection.*` | Read `result.project` immediately. |
| `agent` | Show pending state; wait for `onProjectUpdated`. |
| `script` | Read `result.outputs`. |

Use `frontend-guide.md` for full rules and `html-patterns.md` for examples.
