# Agent Prompt Templates

当 contract 中的 action 需要 `executor: "agent"` 时，**从以下模板选一个最近似的，填入具体参数，不要从零发明**。

每个模板有固定占位符（`<大写>`），只需替换占位符，不要改动骨架逻辑。

---

## 模板 A：拍照 / 相册 + VLM 解析 → 写入

**适用场景**：用户拍照或从相册选图，AI 解析后把结构化数据写入 Project。

```
1. 调 image_picker(source="camera")（或 source="gallery"，根据场景选一个）拿到 imagePath。
2. 调 vlm_task(imagePath=imagePath, question="<用自然语言描述要从图片里提取什么，比如：识别图中所有支出记录，返回 JSON 数组，每条含 amount(数字,元)、note(商户名/备注)、date(YYYY-MM-DD)>") 拿到解析结果。
3. 对每条结果，调 workbench_api_call(projectId=inputs.projectId, apiId="<entity.create 的 toolId>", inputs={<字段名>: <对应解析字段>, ...})。
4. 调 notification_send(title="<完成提示>", body="共写入 N 条") 通知用户。
```

**填入时必须明确的 3 处：**
- `question`：具体要提取什么字段，每个字段的格式/单位
- `apiId`：对应 native.collection.create 的 toolId（从 contract.actions 里找）
- `inputs`：vlm 结果字段 → item.fields 字段的映射，名字必须与 inputSchema 一致

---

## 模板 B：自然语言描述 → VLM/LLM 解析 → 写入

**适用场景**：用户用文字描述一件事，AI 提取结构化字段后写入。

```
1. 用 inputs.description（或 inputs.note）作为用户输入文本。
2. 调 vlm_task(text=inputs.description, question="<从以下文本提取 JSON：{字段名: 说明}，例如 {amount: 数字金额(元), category: 餐饮/交通/购物/其他, date: YYYY-MM-DD 默认今天}>") 解析字段。
3. 调 workbench_api_call(projectId=inputs.projectId, apiId="<entity.create 的 toolId>", inputs={<字段名>: <解析结果字段>})。
```

**填入时必须明确的 2 处：**
- `question`：要提取的字段列表，每个字段的类型和默认值
- `apiId` + `inputs`：映射关系

---

## 模板 C：网页搜索 + 解析 → 批量写入

**适用场景**：用关键词搜索外部信息，批量写入 Project。搜索前必须调研真实 API。

```
1. 调 web_search(query="<inputs.topic> <附加限定词，例如：site:scholar.google.com OR site:arxiv.org>") 拿到搜索结果列表。
2. 对每条结果，提取：title（标题）、url（链接）、<其他 item.fields 字段>。
3. 调 workbench_api_call(projectId=inputs.projectId, apiId="<entity.create 的 toolId>", inputs={title: <标题>, <字段>: <值>}) 写入。
4. 调 notification_send(title="搜索完成", body="找到 N 条 <entity名>") 通知。
```

**搜索型 API 必做调研（写入 agentPrompt 前）：**
- 先用 `web_search("<这个任务> API 2026")` 找真实 API endpoint
- 把真实 URL pattern 写进 prompt（如 `GET https://api.semanticscholar.org/graph/v1/paper/search?query=...`）
- 用 `browser_use` 如果需要抓非 API 页面

---

## 模板 D：定时/周期任务 → 计算 → 写入

**适用场景**：定时触发，计算汇总数据后写入一条记录（如每日小结、周报）。

```
1. 调 workbench_api_call(projectId=inputs.projectId, apiId="<entity.list 或 native.collection.list 对应 toolId>") 读取现有数据。
2. 对 result.outputs.items 做计算：<说明具体要算什么，比如：sum(item.fields.amount where item.fields.date == today>。
3. 调 workbench_api_call(projectId=inputs.projectId, apiId="<summary.create 的 toolId>", inputs={<汇总字段>: <计算结果>}) 写入汇总记录。
```

---

## 模板 E：日历读写

**适用场景**：读取或创建日历事件，与 Project 数据同步。

```
# 写入日历：
调 calendar_event_create(title="<inputs.title 或 item.title>", startTime="<ISO8601>", endTime="<ISO8601>", notes="<可选备注>")

# 读取日历：
调 calendar_event_list(startTime="<今天 00:00 ISO8601>", endTime="<今天 23:59 ISO8601>") 拿到事件列表。
对每个事件，调 workbench_api_call(...) 写入 Project。
```

---

## 模板 F：Python 脚本统计（script executor，不是 agent）

**适用场景**：复杂聚合计算（月度统计、去重、排序），不需要 AI 思考，用确定性代码完成。

**executor 应设为 `script`，不是 `agent`**，不要用 agentPrompt。

```python
# backend/scripts/<entity>_stats.py
import json, sys
data = json.loads(sys.stdin.read())
items = [i for i in data["items"] if i["status"] == "active"]

# 填入具体计算逻辑
total = sum(float(i["fields"].get("amount", 0)) for i in items)
by_cat = {}
for i in items:
    cat = i["fields"].get("category", "其他")
    by_cat[cat] = by_cat.get(cat, 0) + float(i["fields"].get("amount", 0))

print(json.dumps({"total": total, "by_category": by_cat}))
```

---

## 使用规则

1. **先选模板，再填参数**：不从零写 agentPrompt
2. **vlm_task 的 question 必须具体**：列出每个字段名、类型、单位，不要说"提取信息"
3. **apiId 和 inputs 必须与 contract.actions 一致**：字段名一字不差
4. **网页搜索型必须先做 web_search 调研**：把真实 URL 写进 prompt，不猜 API 结构
5. **能用 script 的不用 agent**：纯计算、聚合、格式化用 script，不用 agent 思考
