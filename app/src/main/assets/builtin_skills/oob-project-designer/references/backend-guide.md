# OOB Project Backend Guide

API executor patterns, Python script structure, and agent task prompt writing for Workbench Projects.

---

## Executor Types

### 1. native.collection (CRUD, no AI)

Best for: create, archive, update, list operations on `project.items`. Instant, reliable, no token cost.

```json
{
  "toolId": "task.create",
  "inputSchema": {"title": "string", "due_date": "string?", "priority": "string?"},
  "outputSchema": {"item": "object"},
  "run": {"use": "native.collection.create"}
}
```

Input schema notes:
- `"string"` = required string
- `"string?"` = optional string
- `"number"` = required number
- `"boolean?"` = optional boolean
- All extra input fields beyond `title` are written to `item.fields`
- `title` is the Workbench item title (required for create, optional for update)

For `native.collection.update`:
```json
{
  "toolId": "task.update",
  "inputSchema": {"item_id": "string", "title": "string?", "priority": "string?", "status_label": "string?"},
  "outputSchema": {"item": "object"},
  "run": {"use": "native.collection.update"}
}
```

For `native.collection.archive`:
```json
{
  "toolId": "task.archive",
  "inputSchema": {"item_id": "string"},
  "outputSchema": {"item": "object"},
  "run": {"use": "native.collection.archive"}
}
```

---

### 2. script (Python in Alpine sandbox)

Best for: statistics, aggregations, data export, file generation, complex filtering, PDF creation.

```json
{
  "toolId": "stats.monthly",
  "displayName": "本月统计",
  "inputSchema": {"month": "string?"},
  "outputSchema": {"total": "number", "by_category": "object", "count": "number"},
  "run": {"use": "script", "path": "backend/scripts/monthly_stats.py"}
}
```

The script receives:
- `stdin`: JSON with `{"projectId": "...", "inputs": {...}, "items": [...]}`
- Must write JSON to stdout: `{"success": true, "outputs": {...}}`

#### Script template

```python
#!/usr/bin/env python3
import json, sys
from datetime import datetime

def run(project_id, inputs, items):
    month = inputs.get("month") or datetime.now().strftime("%Y-%m")

    total = 0.0
    by_category = {}
    count = 0

    for item in items:
        if item.get("status") != "active":
            continue
        fields = item.get("fields", {})
        item_date = str(fields.get("date", ""))
        if not item_date.startswith(month):
            continue
        amount = float(fields.get("amount", 0))
        category = str(fields.get("category", "其他"))
        total += amount
        by_category[category] = by_category.get(category, 0) + amount
        count += 1

    return {"total": round(total, 2), "by_category": by_category, "count": count}

if __name__ == "__main__":
    payload = json.load(sys.stdin)
    result = run(
        payload.get("projectId", ""),
        payload.get("inputs", {}),
        payload.get("items", [])
    )
    print(json.dumps({"success": True, "outputs": result}))
```

#### Writing the script file

After creating the project:
```
workbench_project_update(
  projectId = "oob-workbench-...",
  scriptFiles = [
    {"path": "backend/scripts/monthly_stats.py", "content": "<script content>"}
  ]
)
```

Or write directly:
```
file_write(path="{spacePath}/backend/scripts/monthly_stats.py", content=...)
```

#### Advanced: PDF / file export script

```python
#!/usr/bin/env python3
import json, sys, os

# Use Python stdlib only — no third-party packages unless pre-installed
# For HTML-to-PDF: write an HTML file and note the path in outputs
def run(project_id, inputs, items):
    workspace = os.environ.get("WORKSPACE_PATH", "/workspace")
    export_dir = f"{workspace}/projects/{project_id}/exports"
    os.makedirs(export_dir, exist_ok=True)

    # Generate simple CSV
    lines = ["日期,金额,备注,分类"]
    for item in items:
        if item.get("status") != "active": continue
        f = item.get("fields", {})
        lines.append(f"{f.get('date','')},{f.get('amount',0)},{f.get('note','')},{f.get('category','')}")

    out_path = f"{export_dir}/export.csv"
    with open(out_path, "w", encoding="utf-8") as fp:
        fp.write("\n".join(lines))

    return {"filePath": out_path, "count": len(lines) - 1}

if __name__ == "__main__":
    payload = json.load(sys.stdin)
    result = run(payload.get("projectId",""), payload.get("inputs",{}), payload.get("items",[]))
    print(json.dumps({"success": True, "outputs": result}))
```

---

### 3. agent (multi-step AI workflow)

Best for: anything requiring VLM, camera, web search, calendar, notifications, browser automation, or multi-step reasoning.

The `run.prompt` is executed as a user message in a sub-agent conversation. Write it as ordered steps.

#### Prompt Runtime boundary

Project creation owns the Project API and the task request. Prompt Runtime owns the system-level context around that request.

Keep `run.prompt` small and task-specific:

- name inputs and output variables
- describe required tool sequence
- write results back with `workbench_api_call`
- include notification or completion signal when useful

Do not paste global agent rules, tool catalogs, permission policy, memory, locale, device state, or full Project documents into `run.prompt`. Those belong in Prompt Runtime sections such as `project_context`, `tool_instructions`, `permission_policy`, `memory_context`, and `locale_context`.

If an agent task needs exact project rules, reference the file path (`PROJECT_SOUL.md` or `PROJECT_CONTEXT.md`) and ask the agent to read the relevant section.

#### Strict `run.prompt` template

For any non-trivial agent API, start `run.prompt` with a tight input block before the numbered steps:

```text
目标: <one sentence, exactly what this Project API must accomplish>
输入:
- projectId = inputs.projectId
- <field> = inputs.<field> (<type>, required/optional, default: <value>)
约束:
- 只写入 <apiId> / 不直接改 data/items.json
- 解析失败时不要写入部分脏数据；先向用户说明需要更清晰输入
- 如需精确项目规则，先读取 PROJECT_SOUL.md 的相关小节
输出:
- 成功后必须调用 workbench_api_call(projectId=inputs.projectId, apiId=<writeApi>, inputs={...})
- 可选通知: notification_send(...)
步骤:
1. 用 <tool>(...) ...，拿到 <variable>
2. ...
```

Do not begin complex prompts directly with "1. 用 ...". The first block is the contract that lets Prompt Runtime, prompt dump, and future maintainers understand the task before tools run.

#### Prompt writing rules

1. Start each step with a number + tool name: `1. 用 image_picker(...)`
2. Name the output variable explicitly: `拿到 imagePath`
3. Reference prior step outputs by variable name in later steps
4. Always end with a `workbench_api_call` to write results back
5. Optionally end with `notification_send` for user feedback

#### Complete agent task examples

**Photo → VLM → log (meal tracker):**
```json
{
  "toolId": "meal.analyze",
  "run": {
    "use": "agent",
    "prompt": "1. 用 image_picker(source=gallery) 让用户选一张食物照片，拿到 imagePath。2. 用 vlm_task(imagePath=imagePath, question='识别这张照片中的所有食物，估算总热量（大卡），返回 JSON: {foods:[\"食物名\"], calories:数字, note:\"简短描述\"}') 拿到分析结果。3. 调 workbench_api_call(projectId=inputs.projectId, apiId=meal.log, inputs={title:result.note, date:inputs.date, calories:result.calories, foods:result.foods.join(',')}) 写入记录。4. 调 notification_send(title='热量已记录', body='今日 +'+result.calories+' 大卡') 通知用户。"
  }
}
```

**OCR screenshot → parse → bulk import (expense tracker):**
```json
{
  "toolId": "entry.import_screenshot",
  "run": {
    "use": "agent",
    "prompt": "1. 用 image_picker(source=gallery) 让用户选一张账单截图，拿到 imagePath。2. 用 vlm_task(imagePath=imagePath, question='识别截图中的所有支出记录，返回 JSON 数组，每条含: amount(数字,人民币元), note(商户或备注), date(YYYY-MM-DD,若无则用今日), category(餐饮/交通/购物/娱乐/医疗/其他)') 拿到 records 数组。3. 对 records 中每条记录，调 workbench_api_call(projectId=inputs.projectId, apiId=entry.create, inputs={title:record.note, amount:record.amount, note:record.note, date:record.date, category:record.category})。4. 调 notification_send(title='导入完成', body='共导入 '+records.length+' 笔记录') 通知用户。"
  }
}
```

**Web search → store findings (research tool):**
```json
{
  "toolId": "research.fetch",
  "run": {
    "use": "agent",
    "prompt": "1. 用 web_search(query=inputs.topic, limit=5) 搜索相关资料，拿到 results 数组。2. 对每条结果，调 workbench_api_call(projectId=inputs.projectId, apiId=finding.create, inputs={title:result.title, url:result.url, snippet:result.snippet, topic:inputs.topic}) 逐条存入 Project。3. 调 notification_send(title='搜索完成', body='已找到 '+results.length+' 条资料') 通知用户。"
  }
}
```

**Calendar sync (plan tracker):**
```json
{
  "toolId": "plan.sync_calendar",
  "run": {
    "use": "agent",
    "prompt": "1. 调 workbench_api_call(projectId=inputs.projectId, apiId=plan.list) 获取所有活跃计划，拿到 items。2. 对每个 due_date 不为空的 item，调 calendar_event_create(title=item.title, startDate=item.fields.due_date, startTime='09:00', durationMinutes=30, notes='来自 OOB Project') 创建日历事件。3. 调 notification_send(title='日历已同步', body='已同步 N 个计划') 通知用户。"
  }
}
```

**Recurring scheduled task:**
After creating the project, register a daily summary:
```
schedule_task_create(
  title = "每日账单汇总",
  recurrence = "daily",
  time = "21:00",
  prompt = "调 workbench_api_call(projectId=oob-workbench-expense, apiId=entry.monthly_stats) 获取今日汇总，调 notification_send(title='今日支出', body='今日 共 X 笔，合计 Y 元') 发送通知。"
)
```

---

## API Input/Output Schema

Schema values are descriptive strings. Supported type annotations:

| Type | Meaning |
|---|---|
| `"string"` | Required string |
| `"string?"` | Optional string |
| `"number"` | Required number |
| `"number?"` | Optional number |
| `"boolean"` | Required boolean |
| `"boolean?"` | Optional boolean |
| `"array"` | Array |
| `"object"` | Nested object |
| `"date"` | Date string YYYY-MM-DD |
| `"datetime"` | ISO datetime string |

Example with all variants:
```json
{
  "inputSchema": {
    "title": "string",
    "amount": "number",
    "date": "date",
    "category": "string?",
    "note": "string?",
    "is_recurring": "boolean?"
  },
  "outputSchema": {
    "item": "object",
    "message": "string?"
  }
}
```

---

## Error Handling in Scripts

```python
import json, sys, traceback

def run(project_id, inputs, items):
    # ... implementation
    pass

if __name__ == "__main__":
    try:
        payload = json.load(sys.stdin)
        result = run(payload.get("projectId",""), payload.get("inputs",{}), payload.get("items",[]))
        print(json.dumps({"success": True, "outputs": result}))
    except Exception as e:
        print(json.dumps({
            "success": False,
            "errorCode": "SCRIPT_ERROR",
            "errorMessage": str(e)
        }))
        sys.exit(1)
```

---

## Backend File Layout

```
{spacePath}/
  project.json              ← project registry (managed by runtime)
  PROJECT_CONTEXT.md        ← agent reference (update when APIs/fields change)
  data/
    items.json              ← persistent items (managed by runtime)
  backend/
    api_spec.json           ← API specs (managed by runtime)
    scripts/
      monthly_stats.py      ← Python scripts (write via workbench_project_update)
      export_csv.py
  frontend/
    page_spec.json          ← frontend spec (managed by runtime)
    html/
      base.css
      index.html
      detail.html
  logs/
    api_calls.jsonl         ← execution log (append-only, managed by runtime)
    hot_updates.jsonl
    progress.jsonl
```

---

## PROJECT_CONTEXT.md Format

Every new project gets this file auto-generated. Keep it updated as APIs and fields evolve.

```markdown
# <Project Name> — Project Context

## API Contract

| Tool ID | Executor | Inputs | Description |
|---|---|---|---|
| entry.create | native.collection.create | title, amount, note?, category? | 录入一笔支出 |
| entry.import | agent | date? | 截图 OCR 批量导入 |
| entry.archive | native.collection.archive | item_id | 删除记录 |
| entry.monthly_stats | script | month? | 本月分类统计 |

## Item Fields Schema

| Field | Type | Required | Notes |
|---|---|---|---|
| amount | number | yes | 金额，人民币元 |
| note | string | yes | 商户或备注 |
| category | string | no | 餐饮/交通/购物/娱乐/其他 |
| date | date | yes | YYYY-MM-DD |

## HTML Element Inventory (data-oob-id)

| oob-id | Element | Purpose |
|---|---|---|
| btn-add | button | 主要操作：添加记录 |
| form-panel | div | 录入表单面板 |
| summary | div | 当日/本月汇总数字 |
| item-{id} | div | 每条记录卡片 |
| header | div | 页面标题区 |

## Design Notes

- Mobile-first, single column, max 430px
- Primary intent: quick expense logging with photo import support
- Created: 2026-05-13
```
