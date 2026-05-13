---
name: oob-project-designer
description: Design and create an OOB Workbench Project from a user's natural-language description of a personal tool, tracker, or recurring workflow. Use when the user says "帮我做一个", "我想创建一个", "给我推荐方案", "帮我调研", "build me a", "make a [X] tracker/tool/app", or expresses intent to build a new persistent personal tool from scratch. Also use for: designing the API set before calling workbench_project_create, choosing between native/script/agent executors, or reviewing a Project's frontend-backend contract.
---

# OOB Project Designer

**执行序列（强制，每步都有用户可见输出才能继续）：**

| Step | 阶段 | 必须产出的可见内容 | 关卡 |
|---|---|---|---|
| 1 | 领域调研 | 3-5条调研要点 + OOB设计主张 | 输出后继续 |
| 2 | 方案确认 | 完整方案卡片（功能/交互/亮点） | **等用户明确回复** |
| 3 | 实体蓝图 | Phase 1.8 三层蓝图 | 输出后继续 |
| 4 | API+HTML | 内部生成，不展示原始代码 | 内部步骤 |
| 5 | Review | `✅ 通过` 或 `⚠️ 修复了N项` | 必须通过 |
| 6 | 创建 | `workbench_project_create` | 前5步全部完成 |

**Step 2 是硬关卡：** 用户说"好"/"可以"/"继续"/"就这样"才能进入 Step 3。沉默不是确认。

References: `references/frontend-guide.md`, `references/backend-guide.md`, `references/capability-map.md`, `assets/base.css`.

---

## Prelude — 读懂用户在说什么

**在做任何调研或设计之前，先判断用户给了什么。** 用户的 prompt 是整个创建过程最重要的输入——比调研结果、比 OOB 能力映射都重要。

### 三种输入模式

**模式 A：一句话描述（"帮我做一个记账工具"）**
→ 用户还没想清楚，需要 agent 主导调研和方案
→ 走完整 Phase 0 → 1 → 1.5 流程，agent 提出方案等确认

**模式 B：有想法但不完整（"我想做一个追踪论文的工具，能拍照录入，数据留本地"）**
→ 用户有方向，agent 补充细节
→ **压缩 Phase 0**：调研 1-2 次即可，重点验证用户的想法，不是重新发现需求
→ Phase 1.5 方案要以用户说的为骨架，不要用调研结果覆盖用户意图

**模式 C：详细规格（用户给了功能列表、字段要求、交互说明，或粘贴了参考内容）**
→ 用户已经想清楚了，agent 的职责是**理解 + 补充 + 实现**，不是重新设计
→ **跳过 Phase 0**，直接进入 Phase 1 提取实体和字段
→ Phase 1.5 方案要高度忠实用户规格，只补充用户没说到的 OOB 亮点
→ 如果有不确定的地方，问一个具体问题，不展开讨论

**判断依据：**
- 字数 < 20 字 → 模式 A
- 有具体功能点或限制条件 → 模式 B
- 有字段名 / 有交互描述 / 有参考对象 / 字数 > 100 字 → 模式 C

---

### 讨论模式

用户可能不想直接创建，而是想先讨论：
- "我在考虑做一个 X，你觉得怎么做比较好？"
- "有几种方案，我想先聊一聊"
- "我不确定要不要做成 Project 还是别的形式"

这时 agent 的职责是**陪用户想清楚**，不是推进创建流程。提问、提供选项、分析利弊。当用户明确说"好，那就做这个"时，再进入 Phase 0/1。

**讨论时的原则：**
- 每次只提一个最重要的问题，不问一堆
- 给出自己的建议和理由，不只是列选项
- 不要在用户还没决定时就开始设计 API

---

### 用户原话优先原则

用户说的话是 PROJECT_SOUL.md 的第一手来源。

- 用户说了具体规则（"奶茶算娱乐"、"不记收入"）→ 直接写入 `## 业务规则`
- 用户表达了偏好（"简洁一点"、"我不需要图表"）→ 写入 `## 显示偏好`
- 用户说了限制（"数据不能上云"、"要能离线用"）→ 写入 `## 设计约束`

不要把这些用 agent 自己的话重新解释——**原话更准确，也更容易在后续热更新时核对**。

---

## Phase 0 — Domain Research

**在问用户任何问题之前，先做全方位领域调研。** 分两步：

### 0-A 领域和工具调研（2-3 次 web_search）

```
web_search("best <domain> apps 2025")
web_search("<领域> 工具 推荐 哪个好用")
web_search("<domain> app features comparison")
```

目标：**了解这个领域最好的工具是什么，它们做对了什么**。从调研中提炼：

**首要问题：现有工具的亮点**
- 这个领域公认最好用的工具是哪些？它们的核心体验是什么？
- 哪些交互模式和数据模型已经被用户验证是对的？（不要重新发明）
- 用户为什么喜欢这些工具？（不是为什么不喜欢）

**次要问题：这些工具能不能直接满足用户需求**
- 如果用户的需求已经有非常成熟的工具覆盖，OOB Project 的价值在哪里？
- 是否存在某个工具已经很好，但 OOB 能在它的基础上加一个原生能力层？

**参考问题：现有工具的局限**
- 这类工具普遍依赖云端/账号，而用户数据其实完全可以本地的？
- 录入步骤太多，而 OOB 的相机 + VLM 可以一步完成的？
- 只能手动触发，而 OOB 可以做成定时自动的？

注意：**OOB 的两个根本价值是数据统一管理和自由定制**。用户不缺好工具，缺的是：所有数据在一个地方、AI 能跨域查询、规则完全按自己来。即使某个领域有非常成熟的专业工具，OOB Project 的价值在于：这份数据和用户的其他数据在一起，能被 AI 联动，能随时按用户意图修改——这是任何单一专业工具做不到的。不要建议用户去用其他 App。

### 0-B 封装 OOB 设计主张

OOB Project 的两个根本价值，在设计每个项目时都要体现：

**① 数据统一管理**
用户的所有个人数据（支出、健身、论文、日程…）都在 OOB 里，AI 可以跨项目查询和关联。不需要在多个 App 之间同步数据，不需要记住数据在哪里。这个项目的数据如何和用户的其他数据形成整体？

**② 自由定制**
OOB Project 不是一个固定产品，是用户的画布。字段、规则、界面、业务逻辑都按用户说的来，随时可以通过对话修改。PROJECT_SOUL.md 记录的是用户自己的规则，不是产品预设的规则。这个项目如何让用户感觉"这是我的工具，不是我在用别人的工具"？

在以上两条基础上，叠加 OOB 的原生能力：

| 现有工具已做好的 | OOB 在统一管理 + 自由定制基础上能加什么 |
|---|---|
| 记账工具已有分类、图表 | 拍账单截图自动录入；和健身数据联动（高消耗日多吃） |
| 笔记工具已有标签、搜索 | 无障碍从微信/浏览器一键导入；按用户自定义分类规则整理 |
| 健身工具已有动作库、计划 | 拍照识别动作自动打卡；规则完全按用户习惯定 |
| 论文工具已有检索、收藏 | 搜索结果直接存入；可以跨项目和读书笔记关联 |

只写**真正能做到**的，不强行套用。

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

## Phase 1.5 — 推荐方案（计划模式）

完成调研和意图提取后，**输出推荐方案并停下来等待用户确认，不要直接进入 Phase 2**。

推荐方案格式：

```
## 方案：<项目名>

### 这是什么
<一两句直接说清楚。不要"不是…而是…"的句式。>
例："拍照记录三餐热量，自动分析，数据存本地。"

### 功能
- <功能1>
- <功能2>
- <功能3>
（简洁列表，用户最关心的，v1 能做到的全部列出来）

### 交互
- 主操作：<描述最常用操作路径，例如"首页大按钮 → 拍照/填表 → 自动写入">
- <关键 UX 时刻，例如"列表左滑 → 确认删除"，"完成时震动反馈">
- <重要空/错状态，例如"无记录时显示「还没有记录，点击上方按钮开始」">
（2-3 条，只写与同类工具差异明显的交互；通用操作不用写）

### 亮点
⚡ <亮点1>：<体现「数据统一管理」或「自由定制」或 OOB 原生能力，一句话>
⚡ <亮点2>
（最多 2-3 个。围绕两个核心价值：① 数据都在一起，AI 可以跨项目联动；② 规则完全按你来，随时可改。原生能力（拍照/无障碍/定时）是加分项，不是主角）

### 暂不支持
- <功能X>（原因一句话）

这个方向对吗？有什么要改的？
```

**规则：**
- **功能列表必须完整**——用户最终关心的是能做什么，不要藏在设计描述后面
- **"这是什么"直接说**，禁止"不是…而是…"句式，禁止堆砌定语
- **亮点只写 OOB 独特的**，通用功能不算亮点
- **痛点分析留在内部推理**，不要在方案里大段展示，用户不想看
- 结尾明确提问，等待用户回复后才进入 Phase 2

---

## Phase 1.8 — 实体蓝图（三层统一来源）

Phase 1 提取了实体和字段。**在进入 Phase 2 之前，先从实体显式推导出三层的完整蓝图。** Phase 2 和 Phase 3 都按这个蓝图实现，不另起炉灶——这是三层能打通的唯一保证。

### 蓝图格式

```
实体：<EntityName>
主操作：<用户最常做的一件事，动词>
使用节奏：<快进快出 10 秒 / 每天回顾 / 偶尔查阅>

─── 数据层 ───────────────────────────────────────────
字段（这里定义的名字是三层的共同语言，不得在各层单独命名）：
  <field1>: <type>  约束: <constraint>  默认: <default>
  <field2>: <type>  约束: <constraint>  默认: <default>

─── 工具层 ───────────────────────────────────────────
<entity>.<verb>  →  <executor>  →  <触发场景>
（字段名直接引用数据层，不另起名字）

─── 展示层 ───────────────────────────────────────────
顶部：<用户第一眼最想看到的数字或状态>
主操作：<底部主按钮，文案是主操作的动词>
列表：<每条记录展示哪几个字段，主/副/时间的顺序>
空状态：<无数据时的引导语，说清楚第一步做什么>
成功反馈：<完成主操作后，用户立即看到什么变化>

─── PROJECT_SOUL 约束 ────────────────────────────────
用户原话中提到的规则（直接引用，不改写）：
  - <rule1>
  - <rule2>
这些规则同时约束工具层行为和展示层逻辑。
```

### 示例（热量追踪器）

```
实体：Meal
主操作：记录一餐热量
使用节奏：快进快出，每次 10-15 秒，一天 3-5 次

─── 数据层 ───────────────────────────────────────────
kcal:   number  约束: 正整数  默认: 0
date:   date    约束: YYYY-MM-DD  默认: 今日
foods:  string  约束: 食物描述  默认: ''
note:   string  约束: 可选备注  默认: ''

─── 工具层 ───────────────────────────────────────────
meal.log     →  agent   →  拍照 / 描述一餐，AI 解析写入
meal.create  →  native  →  手动填表录入
meal.archive →  native  →  删除一条记录

─── 展示层 ───────────────────────────────────────────
顶部：今日总热量（sum kcal where date=today）+ 目标进度条
主操作：底部"拍照记录"按钮（→ meal.log）
列表：每条显示 kcal 大 + foods 副 + date 小，倒序
空状态："还没有记录，点击下方拍照或手动填写"
成功反馈：总热量数字立即更新，新记录从列表顶部滑入

─── PROJECT_SOUL 约束 ────────────────────────────────
用户说："奶茶算零食，不算饮料"
用户说："我不想看卡路里目标，只看今日总量"
```

蓝图填完后，Phase 2 直接按工具层实现 JSON，Phase 3 直接按展示层实现 HTML，字段名全程一致。

---

## Phase 2 — API Design

按 Phase 1.8 蓝图的「工具层」实现 API 列表。**字段名从蓝图的「数据层」直接取，不重新命名。** 蓝图里有什么操作就实现什么，不另行发明。

Design APIs **before** writing HTML. HTML is bound to these IDs; changing them later breaks the UI.

### OOB Agent 能力注入（强制）

OOB 最核心的能力是**把用户的输入和任务交给 agent 处理**。任何项目都必须至少有一个 `run: {use: "agent"}` 的 API，让用户能用自然语言驱动操作，而不是退化成纯 CRUD 表单。

**基础要求（所有项目）：**
每个项目至少包含一个 agent API，作为主入口。常见形式：

| 场景 | API 示例 |
|---|---|
| 记录类（食物、支出、运动…） | `<entity>.log` — 用户描述 → agent 解析字段 → 写入 |
| 生成类（PPT、文档、报告…） | `<entity>.generate` — 用户描述需求 → agent 生成内容 |
| 分析类（数据、图片、趋势…） | `<entity>.analyze` — 传入数据 / 图片 → agent 分析返回结论 |
| 搜索/查询类 | `<entity>.search` — 用户问题 → agent 检索返回结果 |

**额外注入（Phase 0-B 匹配时）：**
Phase 0-B 识别到的 OOB 原生能力，也必须体现为独立 API，不能只停留在方案里：

| Phase 0-B 识别的能力 | 必须追加的 API |
|---|---|
| 拍照 / VLM 识别 | `<entity>.import` 或 `<entity>.scan`，prompt 包含 `image_picker` + `vlm_task` |
| 无障碍读取其他 App | `<entity>.sync_from`，prompt 包含 `android_privileged_action` |
| 定时/周期任务 | `<entity>.schedule`，prompt 包含 `schedule_task_create` |
| 网页搜索/抓取 | `<entity>.fetch`，prompt 包含 `web_search` / `browser_use` |
| 通知提醒 | 在相关 agent API 的 prompt 末尾加 `notification_send` |
| 日历同步 | `<entity>.to_calendar`，prompt 包含 `calendar_event_create` |

### Agent API 设计前：任务领域调研（每个 agent API 都要做）

agent API 的 `run.prompt` 写得是否可靠，取决于设计时带没带**这个具体任务的领域知识**。

**在写任何 `run: {use: "agent"}` 的 prompt 之前，先针对这个任务做一次 web_search 调研：**

```
任务是"搜论文" → web_search("academic paper search API 2025 arxiv semantic scholar")
任务是"查价格" → web_search("电商商品价格查询 API 京东 淘宝 2025")
任务是"读财务报表" → web_search("上市公司财报 API 数据来源 2025")
任务是"查天气" → web_search("weather API free 2025 OpenWeather")
```

调研目标：
1. **这个任务有没有公开 API 或稳定 URL 模式？** 如果有，直接用 API 比爬页面可靠得多
2. **数据在哪里、格式是什么？** arxiv 返回 XML，Semantic Scholar 返回 JSON，字段各不同
3. **有什么限制和坑？** 需要登录、有 rate limit、数据有时效性
4. **业界怎么做这个任务？** 有没有更好的方式是自己没想到的

带这些调研结果写出来的 prompt，sub-agent 在执行时有明确的数据来源和格式依据，不会自己造数据：

```
❌ 没调研（上下文空洞）：
"搜索 inputs.topic 相关论文，整理标题和摘要，写入 Project。"
→ sub-agent 不知道去哪搜，用训练数据里的"论文"填充 = 假数据

✓ 调研后（有领域依据）：
"用 Semantic Scholar 公开 API 搜索 inputs.topic 相关论文：
 GET https://api.semanticscholar.org/graph/v1/paper/search?query=<topic>&fields=title,abstract,year,url&limit=10
 取 data 数组，每条提取 title、externalIds.ArXiv（拼成 arxiv.org/abs/ 链接）、abstract 前 300 字、year。
 逐条调 workbench_api_call(apiId='paper.create', inputs={title, url, abstract, year}) 写入。
 完成后 notification_send 通知找到多少篇。"
→ 有真实 API endpoint，字段名确定，sub-agent 不需要猜
```

**调研结果必须体现在 prompt 里的三件事：**

| 要素 | 说明 | 例子 |
|---|---|---|
| 数据来源 | 具体 API endpoint 或 URL 模式，不能只说"搜索" | `api.semanticscholar.org/...` |
| 字段映射 | API 返回字段名 → item.fields 字段名的对应关系 | `externalIds.ArXiv → url` |
| 写回合约 | 调哪个 apiId，inputs 字段名与 schema 完全对齐 | `apiId='paper.create', inputs={title, url, abstract}` |

**调研结果写入项目文件（持久化）：**

调研完成后，把关键发现写入 `PROJECT_CONTEXT.md` 的 `## API 领域知识` 节。这个节在创建项目后由 agent 维护，后续热更新时必须先读它，不重复调研。

```markdown
## API 领域知识

### paper.search — 学术论文搜索
- 数据源：Semantic Scholar 公开 API（无需 key）
  GET https://api.semanticscholar.org/graph/v1/paper/search?query=<q>&fields=title,abstract,year,externalIds&limit=10
- 字段映射：title→title, externalIds.ArXiv→url（拼 arxiv.org/abs/前缀）, abstract→abstract, year→year
- 限制：rate limit ~100 req/5min；abstract 可能为 null，fallback 用 snippet
- 调研日期：2026-05-13

### price.fetch — 商品价格查询
- 数据源：京东商品页 https://item.jd.com/<id>.html，price 在 #jd-price 元素
- 限制：需 UA 模拟浏览器，部分商品需登录后才显示价格
- 调研日期：2026-05-13
```

写入时机：每个 agent API 调研完立即写，不等到项目创建完再补。

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

按 Phase 1.8 蓝图的「展示层」生成 HTML。**`toViewItem()` 的字段名必须和蓝图「数据层」完全一致。** 首屏布局、主操作、列表、空状态、成功反馈直接按蓝图实现，不重新设计。

See `references/frontend-guide.md` for complete HTML/CSS patterns. Summary of requirements here:

### 设计前先想清楚三件事

开始写 HTML 之前，先回答这三个问题：

1. **用户要完成什么目标？** — 设计的全部目的是减少到达这个目标的摩擦，不是展示功能
2. **这个工具应该让用户感觉如何？** — 建立信任、轻松感、成就感、还是平静？不同的感觉对应不同的颜色/节奏/密度
3. **用户第一眼应该看到什么？** — 整个页面只允许有一个最高视觉优先级的元素

这三个问题的答案，决定了后面所有的设计决策。

---

### 设计五步流程

**步骤 1 — 理解场景**
- 用户类型：第一次用 / 日常使用 / 高频重度用户？不同阶段的 UI 复杂度应该不同
- 主操作：整个页面服务于一个核心操作，其他都是辅助
- 行业惯例：参考同类工具的交互模式，不要在约定俗成的地方创新（创新在别的地方）

**步骤 2 — UX 结构**
- 用 F 型阅读顺序排布信息：左上最重要，往右往下递减
- 主操作放拇指热区（底部 1/3）
- 内容直接暴露，不藏在二级菜单里
- 空状态必须有意义：告诉用户"接下来做什么"，不是一片空白

**步骤 3 — 视觉系统（三套规范，必须一致）**

_颜色 — 60/30/10 法则：_
- 60%：中性底色（白 / 浅灰），用于背景和卡片
- 30%：主色深色调，用于导航栏、分割线、次要文字
- 10%：强调色（品牌色），只用于主按钮、关键数值、重要状态
- 红色 / 橙色只在真正重要的时刻出现（错误、超标、警告），不滥用

_间距 — 8px 倍数网格：_
- 所有间距必须是 4 或 8 的倍数：4, 8, 12, 16, 24, 32, 48
- 同组相关元素间距小（8px），不同组间距大（24px），组间距 = 组内距 × 2
- 禁止出现 7px、13px 这类非 4 倍数间距

_字体 — 最多 4 个字号、2 个字重：_
- 大标题 / 数字：20–24px，bold
- 正文：15–16px，regular
- 辅助文字：13px，regular，opacity 0.6
- 标签/单位：12px，regular，opacity 0.5
- 数字显示用等宽字体：`font-variant-numeric: tabular-nums`

**步骤 4 — 情感设计（Peak-End Rule）**

用户对一次使用体验的记忆，由**峰值时刻**和**结束印象**决定，不是平均值。

- 找出用户旅程中的峰值时刻（完成一次记录、达成目标、导入成功）
- 在峰值时刻设计庆祝反馈：`✓ 已记录` + 轻微 scale 动画，不是"操作成功"
- 结束印象（关闭/返回前最后看到的）要留下正向感受：今日汇总、进度提示

**步骤 5 — 细节打磨**
- 所有交互状态都要设计：默认、hover（不适用移动端）、active、disabled、loading、error、empty、success
- 微动画让操作有重量感：按钮按下 `scale(0.96)` + `0.1s`，新增卡片从下滑入
- 阴影用带颜色的柔和阴影，不用纯黑：`box-shadow: 0 2px 8px rgba(0,100,200,0.08)`

---

### 认知设计原则（选用）

从小红书 52 设计原则中，移动工具最相关的几条：

- **Hick's Law**：选项越多，决策越慢。首屏操作按钮不超过 3 个，分类选项不超过 6 个
- **Progressive Disclosure（渐进披露）**：先展示最常用的，高级选项收起来。新手看到的和重度用户看到的可以不同
- **Miller's Law**：人短时记忆最多处理 7±2 个单元。列表一屏内不要超过 7 条信息块
- **Gestalt 接近性**：相关信息放在一起，不相关的留白分隔。用户不会读标签，只会用眼睛看"这些东西是一组"
- **Zeigarnik Effect**：未完成的事情更容易被记住。进度条、"今日还差 200 大卡"这类未完成提示，比"完成 800 大卡"更有驱动力
- **Forgiveness（容错性）**：每个破坏性操作（删除/清空）必须可撤销，或有确认步骤。用户会误操作

---

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

**移动端设计理念：**

OOB WebView 运行在 Android 手机上，没有鼠标。设计时要改变思维方式，不是"把网页缩小"，而是从手持设备的使用场景出发重新思考。

---

#### 核心理念

**内容是主角，界面是背景**
用户打开工具是为了看他的数据，不是欣赏 UI。界面的职责是让数据清晰易读，而不是展示设计感。能去掉的元素都去掉，留下来的都要服务于用户的目标。

**触摸是物理动作，不是点击**
手指按下屏幕是一个有重量感的动作。UI 应该立即响应，给出可感知的反馈——颜色变化、轻微形变、声音。没有响应的 UI 让用户怀疑操作是否成功，然后重复点击，然后出错。每个可点击的元素都要有 `:active` 状态；操作完成要有成功提示，不能只是静默更新。

**拇指是主要输入工具**
手机大多数时候单手持握。底部 1/3 是拇指最自然的触达区域，这里放主操作。顶部是最难够到的地方，不要把重要按钮放在那里。如果用户必须用另一只手来操作，就说明设计出了问题。

**一屏只做一件事**
手机的使用场景是碎片化的——走路、等车、一心二用。每个屏幕要有单一的、明显的主操作。选项太多会让用户停顿犹豫（Hick's Law）。如果一个页面承担了太多职责，就拆开。

**宽容比确认更好**
不要用弹窗确认来阻止用户操作——这会打断心流。更好的方式是让操作可以撤销：删除后短暂显示"撤销"按钮，归档后可以恢复。只有真正不可逆、影响重大的操作才需要确认弹窗。

**空状态和加载状态是设计的一部分**
用户第一次打开、数据加载中、操作失败——这些"边缘状态"出现的频率不低，但经常被当作技术细节草率处理。空状态是用户看到这个工具的第一印象，要设计得有引导性，告诉用户"下一步做什么"。加载中要有可见的反馈，让用户知道系统还活着。

---

#### 实现参考（数字服务于理念）

以下参数是上述理念的工程实现，理解了理念就能理解为什么是这些数字：

- 触控目标 ≥ 48px 高：保证拇指不会误触相邻元素（视觉可以小，用 padding 撑大）
- 主操作在底部：拇指热区，不是顶部导航
- `<input>` 字号 ≥ 16px：低于这个值 iOS 会自动缩放页面，破坏布局
- 间距用 4/8 的倍数：节奏一致，视觉上有秩序感
- 单列布局：手机宽度有限，两列并排让每列都太窄，内容挤压
- Label 始终可见：placeholder 消失后用户忘记要填什么，尤其表单字段多时
- 选项少用 chip 直接展示，多用 bottom sheet：减少点击层级，避免原生 select 的样式失控

技术必需项（每个 HTML 必须有，不是设计选择，是平台要求）：
```html
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
```
```css
* { -webkit-tap-highlight-color: transparent; box-sizing: border-box; }
input, button { -webkit-appearance: none; outline: none; }
```

---

#### 6. 列表与卡片

**选择依据：**
- **列表布局**：信息密度高、需要快速浏览多条时（待办、记录流）
- **卡片布局**：每条内容独立、信息量丰富、需要视觉分隔时（项目、成果）

**列表项：**
```css
.list-item {
  min-height: 56px;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #f0f0f0;
}
```

**卡片：**
```css
.card {
  border-radius: 12px;
  padding: 16px;
  margin: 8px 16px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
}
```

**滑动操作（swipe-to-delete）：**
- 必须同时提供**点击触发**的替代方案（可访问性）
- 滑动露出操作区：红色删除按钮 ≥ 64px 宽
- 用 `touch-action: pan-y` 在列表项上允许纵向滚动不冲突

---

#### 7. 加载与反馈状态

**选择原则：**
- 操作 ≤ 1s：按钮变灰 + 文字改为"处理中…"，不要 spinner
- 操作 1–3s：小 spinner（24px）放在按钮内或 `#status`
- 页面初始加载 / agent 长任务：骨架屏（灰色占位块），不是空白

**骨架屏示例：**
```css
.skeleton {
  background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
  border-radius: 6px;
}
@keyframes shimmer { 0%{background-position:200% 0} 100%{background-position:-200% 0} }
```

**操作成功：**
- 用 `#status` 展示 1–2 秒后自动消失的成功提示（绿色）
- 不要用 `alert()`

---

#### 8. 滚动与手势

```css
.scroll-container {
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;   /* 惯性滚动 */
  overscroll-behavior-y: contain;      /* 防止滚动穿透 */
}
```

- 主滚动区不要嵌套滚动容器（会导致手势冲突）
- 下拉刷新用 agent 回调触发，不要用 `pull-to-refresh` 库（与 WebView 手势冲突）

---

#### 9. 动效

- 反馈动画 ≤ **150ms**，用 `transition: transform/opacity 0.15s ease`
- 页面内切换（tab 切换、展开/折叠）≤ **250ms**
- 禁止复杂 `@keyframes` 动画（耗电 + WebView 卡顿）
- 尊重系统省电：`@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }`

---

#### 10. 禁止事项

- 横向滚动（整页）
- `:hover` 作为唯一交互触发
- 多列并排布局
- `type="number"` 输入框
- 只用 `placeholder` 不用 `label`
- 硬编码屏幕宽度（`width: 390px`）
- 在 WebView 内使用 `<iframe>`

**前后端接口协议（必读）：**

OOB 前端通过 `window.oob` 桥接原生层。整个接口只有三个方法：

```js
window.oob.getProject()          // 读取当前 project（含所有 items）→ Promise<project>
window.oob.callApi(apiId, inputs) // 调用已注册 API → Promise<result>
window.oob.onProjectUpdated(fn)   // 订阅 project 变更（agent 任务完成后触发）
```

---

#### 数据流：三种执行模型完全不同

**模型 1 — native.collection（CRUD，同步）**

```
callApi('entry.create', {title, amount})
  → 立即返回 result
  → result.project.items 已包含更新后的完整列表
  → 直接 render(activeViewItems(result.project))
```

```js
const result = await window.oob.callApi('entry.create', { title, amount });
if (!result.success) { showError(result.errorMessage); return; }
render(activeViewItems(result.project));  // ✓ 直接拿 result.project
```

**模型 2 — agent（AI 任务，异步两通道）**

```
callApi('meal.analyze', {})
  → 立即返回 {status: "pending"}  ← 不含 project！agent 还没跑
  → agent 在后台执行
  → agent 完成后 → onProjectUpdated(project) 触发
  → 在 onProjectUpdated 回调里 render
```

```js
// ① 触发
async function analyzePhoto() {
  showSpinner('分析中…');
  await window.oob.callApi('meal.analyze', {});
  // ❌ 不要在这里读 result.project —— agent 尚未执行
}

// ② 接收结果（页面加载时注册一次）
window.oob.onProjectUpdated((project) => {
  hideSpinner();
  if (project._taskError) { showError(project.errorMessage); return; }
  render(activeViewItems(project));  // ✓ 数据从这里来
});
```

**模型 3 — script（Python 脚本，同步，outputs 在 result.outputs）**

```
callApi('entry.monthly_stats', {month})
  → 立即返回 result
  → result.outputs = { total, by_category, count }  ← 脚本的计算结果
  → result.project.items 不变（脚本不写入 items）
```

```js
const result = await window.oob.callApi('entry.monthly_stats', { month });
if (!result.success) { showError(result.errorMessage); return; }
const { total, by_category } = result.outputs;  // ✓ 脚本结果在 outputs
renderStats(total, by_category);
```

---

#### item 数据结构（必须通过 toViewItem 规范化）

```js
// project.items 里每个 item 的结构：
{
  id:        "uuid",           // 稳定唯一标识
  title:     "显示标题",
  status:    "active",         // "active" | "archived"
  createdAt: "2026-05-13T...",
  fields: {
    // 所有业务字段都在这里 —— 不在 item 顶层
    amount:   150.0,
    category: "餐饮",
    date:     "2026-05-13",
  }
}
```

**规则：永远不要直接读 `item.amount`。只读 `item.fields.amount`，通过 `toViewItem()` 做一次规范化。**

```js
function toViewItem(item) {
  const f = item.fields || {};
  return {
    id:       item.id,
    title:    item.title || '',
    // 业务字段从 fields 里取，并做好类型转换和默认值：
    amount:   Number(f.amount  || 0),
    category: f.category || '其他',
    date:     f.date     || '',
    note:     f.note     || '',
  };
}
```

---

#### callApi result 结构

```js
// 成功：
{ success: true, apiId: "...", outputs: {...}, project: { items: [...] } }

// 失败：
{ success: false, errorCode: "...", errorMessage: "用户可读的错误文字" }

// agent 任务已提交（尚未完成）：
{ status: "pending" }   // ← 没有 project 字段！
```

---

#### 三个必须一起实现，缺一不可

```js
// 1. 页面加载读数据
window.addEventListener('load', async () => {
  const project = await window.oob.getProject();
  render(activeViewItems(project));
});

// 2. agent 任务结果订阅（即使 v1 没有 agent API 也要注册）
window.oob.onProjectUpdated((project) => {
  hideSpinner();
  if (project._taskError) { showError(project.errorMessage); return; }
  render(activeViewItems(project));
});

// 3. 过滤 active 条目并规范化
function activeViewItems(project) {
  return (project.items || [])
    .filter(i => (i.status || 'active') === 'active')
    .map(toViewItem);
}
```

---

#### 常见错误

| 错误 | 正确做法 |
|---|---|
| agent callApi 后直接读 `result.project` | agent 返回 `{status:"pending"}`，在 `onProjectUpdated` 里读 |
| 直接访问 `item.amount` | 只读 `item.fields.amount`，经 `toViewItem` 规范化 |
| 忘记注册 `onProjectUpdated` | 即使没有 agent API，也必须注册 |
| 脚本结果去 `result.project.items` 里找 | 脚本结果在 `result.outputs`，不在 items |
| 页面加载时 render 硬编码数组 | 必须调 `getProject()` 后从 `project.items` 渲染 |

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

## Phase 3.5 — HTML Review（强制，生成后进入 Phase 4 之前）

HTML 生成完后**不要直接调用 `workbench_project_create`**。

```
skills_read(skillId="oob-project-reviewer")
```

按 reviewer skill 的指示执行完整审查，修复所有 FAIL 后才进入 Phase 4。

reviewer skill 是跨场景共享的合规层——创建和热更新都调它，逻辑只维护一份。Phase 3.5 是创建前拦截门，Phase 5 的 `validate_project.py` 是创建后兜底。

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

## 输出层设计（用户在聊天里看到什么）

每个阶段有明确的用户可见输出。Agent 的内部推理不展示给用户；只有以下内容出现在对话里：

| 阶段 | 用户看到的 | 格式 |
|---|---|---|
| Phase 0 | 一行进度提示："正在调研 [领域]..." | 纯文字，1 行 |
| Phase 1.5 | **完整方案卡片**（见 Phase 1.5 格式） | Markdown，结构化 |
| Phase 3.5 | Reviewer 报告摘要：`✅ 通过 / ⚠️ 修复了 N 项` | 1-2 行 |
| Phase 4 | 创建进度："正在创建项目..." | 纯文字 |
| Phase 6 | **完成通知**（见下方格式） | Markdown，简短 |

**Phase 6 完成通知格式：**
```
✅ [项目名] 已创建

[一句话说这个工具做什么]

先试试：[主操作的具体描述，告诉用户第一步按哪里]

项目文档已保存到项目目录，包括设计决策、API 文档和调研记录。
```

不在聊天里展示：调研全文、API JSON 定义、HTML 代码、Python 脚本、validator 输出详情。这些存到项目目录。

---

## 项目目录文档体系（持久化，供任何人阅读）

项目目录不只是运行时文件，也是完整的可读文档包。创建时写入，热更新时维护。

```
{spacePath}/
  PROJECT_SOUL.md       ← 产品定义、业务规则、用户原话（已有）
  PROJECT_CONTEXT.md    ← API 契约、字段 schema、HTML 元素清单（已有）
  DESIGN.md             ← 设计决策记录（新增）
  API_REFERENCE.md      ← 人类可读的 API 文档（新增）
  RESEARCH.md           ← 各 API 的领域调研记录（新增）
  frontend/html/        ← HTML 前端文件
  backend/scripts/      ← Python 脚本
  data/                 ← 运行时数据（runtime 管理，不要直接写）
  logs/                 ← 执行日志（只读）
  exports/              ← 脚本生成的导出文件
```

### DESIGN.md — 设计决策记录

记录"为什么这样设计"，不记录"是什么"（那是 PROJECT_CONTEXT.md 的职责）。

```markdown
# [项目名] — 设计决策

## 领域背景
[Phase 0 调研的核心结论：这个领域的主要痛点是什么，
现有工具在哪里让用户失望]

## OOB 设计主张
[Phase 0-B 的能力映射：这个项目用了 OOB 哪些独特能力，为什么选这些]

## 关键设计决策
- **为什么用 agent 而不是 native.collection 做 [API名]**：[一句话理由]
- **为什么字段 [X] 设计成 [类型] 而不是 [另一种]**：[理由]
- **为什么不做 [某功能]**：[理由，来自 Phase 1.5 的「暂不支持」]

## 用户原始需求
[用户的原话，逐字保留，不改写]

## 更新历史
- [日期]: 项目创建
```

### API_REFERENCE.md — 人类可读的 API 文档

将 api_spec.json 翻译成任何人都能看懂的文档。

```markdown
# [项目名] — API 参考

## entry.create — 手动记录支出
**类型**: 原生 CRUD（即时，无 AI）
**触发**: 用户填写表单后点「保存」

**输入**
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| title | 文字 | 是 | 商户名或简短描述 |
| amount | 数字 | 是 | 金额，人民币元 |
| category | 枚举 | 否 | 餐饮/交通/购物/其他，默认「其他」|

**输出**: 写入成功的记录，前端立即刷新列表

---

## entry.import — 截图批量导入
**类型**: AI 任务（需要几秒，异步）
**触发**: 用户点「导入账单」，选择截图

**做什么**: 调起相册 → 用视觉模型识别账单截图 → 解析每笔金额和商户 → 批量写入 → 发送通知

**输入**: 可选日期（默认今天）
**输出**: 导入的条数，前端收到 onProjectUpdated 后刷新
```

### RESEARCH.md — 领域调研记录

Phase 2 里每个 agent API 做的任务调研，集中保存在这里。

```markdown
# [项目名] — API 领域调研

## entry.import — 账单截图识别
**调研日期**: 2026-05-14
**问题**: 支付宝/微信账单截图有哪些格式，VLM 识别精度如何

**发现**:
- 支付宝账单页：金额在右侧大字，商户在左侧，日期在顶部
- 微信账单：格式更规整，每行一笔
- VLM 识别精度：金额 > 95%，商户名 ~ 85%（繁体/英文商户名容易出错）
- 建议在 prompt 里要求 VLM 返回 confidence，低于 0.8 的条目标记待确认

**结论**: 用 vlm_task，question 要求返回 JSON 数组，含 amount/note/date/category
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

## 这是什么
<直接复制 Phase 1.5 方案里"这是什么"的一两句话。热更新时功能方向不得与此冲突。>

## 功能
<直接复制 Phase 1.5 方案里的功能列表。>

## 亮点
<直接复制 Phase 1.5 方案里的亮点。热更新时要在 UI 和功能上体现这些亮点，不要做出违背它们的改动。>

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
- 用户对方向有重大调整时 → 更新设计理念和亮点，保持与实际产品一致

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

### Write DESIGN.md

```
file_write(path="{spacePath}/DESIGN.md", content=...)
```

记录设计决策的"为什么"，供创建者、协作者和未来的热更新 agent 阅读。从 Phase 0-1.5 的内容提炼，不是对 PROJECT_SOUL.md 的重复。

包含：领域背景（调研发现的核心痛点）、OOB 设计主张（用了哪些原生能力以及理由）、关键设计决策（每个重要选择的一句话理由）、用户原话（逐字保留，不改写）。

### Write API_REFERENCE.md

```
file_write(path="{spacePath}/API_REFERENCE.md", content=...)
```

将 `apis` 列表翻译成人类可读的 API 文档。每个 API 写清楚：类型（原生/AI/脚本）、触发方式、输入字段（含类型和说明）、做了什么、输出到哪里。供不懂代码的用户理解项目能力，也供热更新时快速了解现有 API。

### Write RESEARCH.md

```
file_write(path="{spacePath}/RESEARCH.md", content=...)
```

汇总 Phase 2 里每个 agent API 做的任务调研结果。格式见「项目目录文档体系」一节的模板。热更新时如需修改 agent API 的行为，先读本文件了解原始调研结论。

### 更新 Data Layout（PROJECT_CONTEXT.md）

在 PROJECT_CONTEXT.md 的 Data Layout 表格里补上新增文件：

| Path | Purpose | Managed by |
|---|---|---|
| `DESIGN.md` | 设计决策和理由 | Agent — 重大设计变更时更新 |
| `API_REFERENCE.md` | 人类可读的 API 文档 | Agent — API 增删改时同步更新 |
| `RESEARCH.md` | 各 API 的领域调研记录 | Agent — 调研新 API 时追加 |

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

---

## Hot Update Flow — 更新已有项目

**触发时机：** 用户对现有项目说"加一个字段"、"改一下界面"、"新增功能"、"修改规则"等。

更新不是重新创建——不重跑 Phase 0、不重建蓝图、不重写整个 HTML。**最小化改动，保持三层一致。**

---

### Step 1 — 读状态（必须，不跳过）

```
file_read({spacePath}/PROJECT_SOUL.md)      ← 用户的规则和意图
file_read({spacePath}/PROJECT_CONTEXT.md)   ← 现有 API 契约、字段 schema、oob-id 清单
file_read({spacePath}/RESEARCH.md)          ← 各 API 的领域调研记录（如果有）
file_read({spacePath}/DESIGN.md)            ← 原始设计决策（如果需要了解背景）
```

读完再改。不读直接改 = 不知道字段名是什么、不知道现有规则、极可能破坏三层一致性。

---

### Step 2 — 判断改动类型

改动影响哪一层，决定了需要同时改哪些地方：

| 用户说的 | 影响层 | 必须同步更新 |
|---|---|---|
| "加一个字段 X" | 数据层 | 工具层（inputSchema 加字段）+ 展示层（toViewItem + HTML 显示）+ PROJECT_CONTEXT.md |
| "新增一个 API / 功能" | 工具层 | 展示层（加对应按钮/触发）+ 调研（agent API 需查任务）+ PROJECT_CONTEXT.md |
| "改一下界面 / 样式" | 展示层 | 仅 HTML，工具层不动 |
| "以后 X 规则改成 Y" | 规则层 | PROJECT_SOUL.md + 检查工具层 prompt 和展示层逻辑是否需要联动 |
| "改 API 的行为" | 工具层 | RESEARCH.md（如是 agent API）+ 展示层（如输出字段变了）|

**数据层变化是最重的**——加字段必须同时更新三层，字段名必须全程一致。

---

### Step 3 — 最小化改动

**展示层优先用 htmlPatches，不整体重写：**

```
小改动（样式、文案、加减元素）→ htmlPatches
大改动（> 50% 结构重组）     → htmlFiles 全量重写
```

htmlPatches 示例：
```json
{"htmlPatches": [
  {"path": "index.html", "oldText": "今日热量", "newText": "今日摄入"},
  {"path": "index.html", "oldText": "font-size: 14px", "newText": "font-size: 16px"}
]}
```

**工具层：** 只增减受影响的 API，不重新设计整个 API 集。

**数据层：** 新字段加进 PROJECT_CONTEXT.md 的 schema 表，toViewItem() 补上新字段的默认值。

---

### Step 4 — 检查三层一致性

改完后，用 Phase 1.8 蓝图作对照：

```
如果加了字段 priority: string：
  ✓ API inputSchema 里有 priority: "string?"
  ✓ toViewItem() 里有 priority: f.priority || 'normal'
  ✓ HTML 里有对应显示或输入
  ✓ PROJECT_CONTEXT.md 的 schema 表里有 priority
```

---

### Step 5 — Review + 更新文档

```
skills_read(skillId="oob-project-reviewer")   ← 和创建一样，必须过
workbench_project_update 或 workbench_project_hot_update
```

更新完成后同步文档：
- 加了字段 / 改了 API → 更新 `PROJECT_CONTEXT.md`
- 用户说了新规则 → 追加到 `PROJECT_SOUL.md` 的「业务规则」
- 改了 agent API 行为 → 更新 `RESEARCH.md` 对应条目
- 重大设计变更 → 在 `DESIGN.md` 追加更新历史

---

### 常见更新场景

**场景 A：加字段**
```
用户说："帮我加一个优先级字段"
→ 读 PROJECT_CONTEXT.md 确认现有字段
→ 数据层：priority: string，枚举（高/中/低），默认"中"
→ 工具层：在 <entity>.create 和 update 的 inputSchema 加 priority: "string?"
→ 展示层：toViewItem 加 priority 字段，HTML 加对应显示（chip 或标签）
→ reviewer → workbench_project_update → 更新 PROJECT_CONTEXT.md
```

**场景 B：改界面**
```
用户说："列表改成卡片式，颜色深一点"
→ 读 PROJECT_CONTEXT.md 确认现有 oob-id
→ 仅展示层：htmlPatches 修改 .card 样式和列表渲染逻辑
→ 工具层不动，字段不动
→ reviewer → workbench_project_hot_update
```

**场景 C：新增 AI 功能**
```
用户说："加一个拍照识别功能"
→ 读 RESEARCH.md 看有没有现成调研
→ 如果没有：web_search 调研任务，结果写入 RESEARCH.md
→ 工具层：新增 <entity>.analyze agent API，prompt 带领域锚点
→ 展示层：加触发按钮，注册 onProjectUpdated 处理异步结果
→ reviewer → workbench_project_update
```

**场景 D：用户说了新规则**
```
用户说："以后奶茶算零食，不算饮料"
→ PROJECT_SOUL.md 的「业务规则」追加这条（原话保留）
→ 检查相关 agent API 的 prompt 是否有分类逻辑需要更新
→ 如果有：更新 prompt，workbench_project_update
→ 如果没有：仅更新 PROJECT_SOUL.md
```

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
