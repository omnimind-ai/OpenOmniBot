---
name: oob-project
description: OOB Workbench Project 完整生命周期——新建、更新、审查。Use when the user says "帮我做一个", "我想创建一个", "新建 Project", "build me a", "make a [X] tracker/tool/app", "改一下界面", "加个字段", "新增功能", "修复", "project review", "审查 project", or any intent to create or modify a persistent personal tool. Also handles: API design, entity blueprint, Bridge Injection, hot update, review, PROJECT_SOUL, document maintenance.
---

# OOB Project

OOB Project 不是“一键 vibe 一个大应用”。默认目标是**小而精的个人工具**：

- v1 只解决一个核心闭环：输入/触发 → 结构化保存 → 一屏查看/归档
- 默认一个实体、3-5 个字段、1 个主 agent 入口、1 个列表页
- 不做登录系统、复杂设置、多页面导航、市场化首页、复杂报表、插件体系
- 大需求先切成 v1；其余放进「暂不支持 / 后续可加」

OOB Workbench 的核心循环：

```
AI 产出 → 用户看见 → 用户说一句话 → AI 更新 → 用户看见
```

## HTML 设计原则

### 输入路由：输入统一走聊天，HTML 只做展示和触发

**HTML 不承担数据录入职责。**

```
用户输入  →  聊天对话框（Agent）  →  Agent 解析/拍照/VLM  →  callApi 写入  →  HTML 展示
                    ↑
              唯一的输入路由
```

HTML 里的元素：
- ✅ **Agent 触发按钮**（主操作，全宽）：用户点击 → 触发 Agent 在聊天里收集输入
- ✅ **数据列表**：展示 project.items
- ✅ **归档/删除按钮**：直接操作已有数据，不需要输入
- ❌ **多字段表单**：不在 HTML 里做，数据录入走 Agent
- ❌ **大量 `<input>` 字段**：避免，统一由 Agent 在对话里收集

**为什么？**
- 用户在手机上打字填表体验差，说话/拍照体验好
- Agent 可以做智能解析（拍张截图 → 自动填10个字段）
- HTML 表单只能做简单字段，Agent 可以做复杂逻辑

**例外：** 用户明确要求"在界面里直接编辑"时，可以加轻量 inline 编辑。

### 视觉完全自由，数据合约必须遵守

- HTML 可以是任何风格：极简列表、卡片、图表、深色主题……完全由用户/AI 决定
- `base.css` 是可选的 OOB 默认风格，不是强制要求
- builder 生成的默认 HTML 仅供"不想手写时"快速启动用

**唯一必须遵守的是数据合约（见「数据合约」节）：**
- 从 `window.oob.getProject()` 读数据，从 `project.items` 取列表
- 用 `window.oob.callApi(toolId, inputs)` 调 API
- 业务字段从 `item.fields.*` 取，不直接读 `item.fieldName`
- 按 executor 类型正确处理返回值

**收到任何 Project 相关请求，先读完本 skill 再行动。**

---

## 架构：三层 + PROJECT_SOUL

```
实体定义（Entity Schema）
    ↓              ↓              ↓
数据层             工具层           展示层
item.fields.*   apis[]          index.html
```

**字段名是三层的共同语言。** 一处字段名不同 = 三层断开：
```
entity schema  →  fields.kcal: number
api inputSchema →  kcal: "number"
toViewItem()   →  kcal: Number(f.kcal || 0)
HTML           →  ${vi.kcal} 大卡
```

**PROJECT_SOUL.md 纵向穿透三层。** 用户的规则（"奶茶算娱乐"）同时约束数据分类、展示逻辑和哪些字段存在。任何修改前必须先读 PROJECT_SOUL.md。

---

## 新建项目

### 强制执行序列

| Step | 阶段 | 必须产出的可见内容 | 关卡 |
|---|---|---|---|
| 1 | 领域 + 开源调研 | 3-5条产品要点 + 2-3条开源启发 + OOB设计主张 | 输出后继续 |
| 2 | 方案确认 | 完整方案卡片（功能/交互/亮点） | **等用户明确回复** |
| 3 | 意图提取 | Entity、核心字段、主操作 | 输出后继续 |
| 4 | ProjectContract | 结构化合约 JSON | 内部步骤 |
| 5 | 审查 | `✅ 通过` 或 `⚠️ 修复了N项` | 必须通过 |
| 6 | 创建 | `workbench_project_create` | 前5步全部完成 |

Step 2 是硬关卡：用户说"好"/"可以"/"继续"才能进入 Step 3。沉默不是确认。
禁止跳步直接调 `workbench_project_create`。

### Prelude：读懂用户输入

**模式 A（一句话）：** 走完整 6 步。
**模式 B（有方向但不完整）：** 压缩 Phase 0，方案以用户想法为骨架。
**模式 C（详细规格，>100字或有字段名）：** 不跳过 Phase 0；做 mini 产品 + OSS 调研校准方案，但忠实保留用户已明确的字段、规则和功能。

用户的原话直接写入 PROJECT_SOUL.md，不改写。

---

### Phase 0 — 领域 + 开源调研

新建 Project 前必须先调研，但调研目的是**校准一个小工具**，不是写竞品报告或复制成熟 App。即使用户给了完整规格，也至少做 mini 产品 + OSS 调研来校准字段、交互、数据模型和 OOB 能力边界；用户明确给出的规格优先，不因调研结果擅自改写。

必须覆盖两条线：

1. **优秀产品/工具调研**：至少 2 次 `web_search`，了解该领域优秀工具做对了什么（不是找痛点），以及 OOB 能在此基础上加什么。
2. **开源项目调研**：至少 1 次 GitHub/OSS 查询，例如 `web_search("<domain> open source GitHub app")` 或 `web_search("site:github.com <domain> tracker")`。需要看 README、截图、数据模型、交互流程或 issue 中的真实用法；不要复制代码、品牌、图标或私有资产。

如果搜索结果只是列表，使用 `browser_use` 打开 1-2 个代表性页面或 GitHub README 做深读。

OOB 的两个根本价值：
- **数据统一管理**：所有数据在一处，AI 可跨项目操作
- **自由定制**：字段/规则/界面完全按用户说的来，随时可改

Phase 0-B 封装 OOB 设计主张，填入对照表：

| 现有优秀工具已做好的 | 开源项目可借鉴的 | OOB 能在此基础上加什么 |
|---|---|---|
| 记账工具已有分类、图表 | 开源记账项目常用 transaction/category/account 三实体 | 拍截图自动录入，和其他数据联动 |
| … | … | … |

Phase 0 输出必须包含：
- 产品/工具发现：2-3 条，带来源或 URL
- 开源启发：1-2 条，带 GitHub/OSS 项目名或 URL
- OOB v1 设计主张：只说明哪些发现会变成 v1 字段、主操作、展示或 agent action；其余放入「暂不支持」

---

### Phase 1 — 意图提取

| 提取项 | 示例 |
|---|---|
| Entity（PascalCase，单数）| `Meal`, `Expense`, `Paper` |
| 核心字段（3-5个）| `date`, `kcal`, `foods` |
| 主操作（动词）| "记录一餐" |
| OOB 原生需求 | 拍照/VLM/日历/通知 |

**Scope Gate — v1 收敛**

进入方案前必须把需求压成 v1：

| 项 | 默认上限 |
|---|---|
| Entity | 1 个 |
| 核心字段 | 3-5 个，最多 6 个 |
| Agent action | 0-1 个；只有主操作确实需要 OOB 能力时才加 |
| Native CRUD | create/archive 必备，update/list/get 按需 |
| 页面 | 1 个列表/仪表页，不做多页面应用 |
| 图表 | 0-1 个轻量统计，不做完整 BI |

超过上限时，先砍到 v1，把剩余写进「暂不支持 / 后续可加」。不要为了展示能力而加功能。

---

### Phase 1.5 — 方案（输出后等用户确认）

```
## 方案：<项目名>

### 这是什么
<直接一两句，禁止"不是…而是…">

### 功能
- <功能1，必须服务主闭环>
- <功能2，最多 3 条>

### 交互
- 主操作：<用户完成一次主操作的路径>
- <关键 UX 时刻>

### 亮点
⚡ <体现「统一管理」或「自由定制」或 OOB 原生能力>

### 暂不支持
- <功能X>（原因）
- <功能Y>（留到 v2）

这个方向对吗？有什么要改的？
```

---

### Phase 1.8 — ProjectContract（三层统一来源）

输出结构化 ProjectContract JSON。后续步骤（API 生成、HTML 生成）从 contract 派生，不另起炉灶。

**Agent API 设计前必须做能力选择 + 任务调研：**

先读取 `references/capability-map.md`，把用户需求映射到 OOB 能力，不要凭记忆假设工具存在。
当需求涉及新工具或不熟悉能力时，同时读取 `references/review-guide.md` 的能力扩展规则。

在 ProjectContract 里为每个 agent action 写 `capabilities`：

```json
{ "id": "entry.import", "executor": "agent",
  "capabilities": ["image_picker", "vlm_task", "workbench_api_call"] }
```

`capabilities` 是审查和后续扩展的依据：小万增加能力时，更新 capability-map + review-guide 后，新 Project 会自动按新能力设计。

然后做任务调研：
```
web_search("<这个任务> API 2025")  ← 找真实的 API endpoint、URL 模式、字段格式
```
调研结果写入 agentPrompt，不凭记忆发明数据来源。

**ProjectContract 格式：**

```json
{
  "projectId": "oob-workbench-<domain>-<name>",
  "name": "<用户可见中文名>",
  "entity": { "name": "<PascalCase>", "primaryAction": "<主操作动词>" },
  "fields": [
    { "name": "<field1>", "type": "number|string|date|boolean", "required": true },
    { "name": "<field2>", "type": "string" }
  ],
  "actions": [
    { "id": "<entity>.<verb>", "executor": "agent",
      "displayName": "<用户可见名>",
      "capabilities": ["<tool-or-capability-id>"],
      "agentPrompt": "<含真实 API endpoint、字段映射、写回合约的完整 prompt>",
      "inputs": { "<field>": "string?" } },
    { "id": "<entity>.create", "executor": "native.collection.create",
      "displayName": "<用户可见名>",
      "inputs": { "<field>": "number", "<field2>": "string?" } },
    { "id": "<entity>.archive", "executor": "native.collection.archive",
      "inputs": { "item_id": "string" } }
  ],
  "views": {
    "primary": "<顶部第一眼看到的>",
    "list": "<列表字段排列和排序规则>",
    "empty": "<空状态引导语>"
  }
}
```

**约束（builder 会强制校验，违反则 exit 1）：**
- `entity.name` 必须 PascalCase
- `fields` 非空，默认 3-5 个，最多 6 个
- 字段名和 action 输入名必须是 JS-safe lowerCamel/snake 标识符；类型只能是 `string|number|boolean|date|integer`（action 输入可加 `?`）
- `actions` 非空；不再强制 agent API，纯 CRUD 小工具是允许的
- agent action 最多 1 个；只有主操作确实需要相机/VLM/搜索/日历/通知/无障碍等 OOB 能力时才加
- `action.id` 必须是 `<entity>.<verb>` 格式
- agent action 必须有完整 `agentPrompt`（含真实 API endpoint / 字段映射 / 写回 apiId）
- agent action 必须声明 `capabilities`；每项必须能在 `capability-map.md` 中找到，或在方案里说明为暂不支持/待接入

**Executor 选择：**
- 需要相机/VLM/搜索/日历/通知/无障碍 → `agent`
- 需要计算/导出/复杂过滤 → `script`
- 简单 CRUD → `native.collection.*`

**API 命名：** `<entity>.<verb>`，例：`meal.log`, `entry.import`, `finding.archive`

**Agent API 是加分项，不是默认项。** 主闭环能用 `native.collection.*` 完成时，保持纯 CRUD；需要 OOB 原生能力时才加一个主 agent API。

**agentPrompt 写法：先读模板，再填参数。**
写 agentPrompt 前调用 `skills_read_reference(skillId="oob-project", refId="agent-prompt-templates")` 读取模板列表，选最近似的一个，把占位符替换为本 project 的具体字段名和 toolId。不从零发明 prompt 结构。

---

### Phase 3.5 — 审查（见「审查清单」节）

执行 capability-aware review。基础清单见下方「审查清单」，能力扩展规则见 `references/review-guide.md`。
所有 FAIL 修完再进入 Phase 4。

必须输出：

```text
Project Review
- Contract: PASS|FAIL
- Data/Tool/Display binding: PASS|FAIL
- Capability fit: PASS|WARN|FAIL
- Runtime behavior: PASS|WARN|FAIL
- Extensibility notes: <新增小万能力时该 Project 如何接入>
结论：✅ 通过 / ⚠️ 修复了 N 项 / ❌ 阻塞
```

---

### Phase 4 — 创建

有三种 HTML 来源，任选其一：

**A. builder 默认 HTML**（快速启动，OOB 风格，不关心视觉时用）：
```
terminal_execute:
  python3 {skillDir}/scripts/build_project_from_contract.py \
    --contract '<Phase 1.8 contract json>'
```

**B. 自定义 HTML**（用户/AI 写了自己的 index.html，任意视觉风格）：
```
terminal_execute:
  python3 {skillDir}/scripts/build_project_from_contract.py \
    --contract '<contract json>' \
    --custom-html '<index.html 路径>'
```

**C. 完全手写 workbench_project_create**（高级，直接组装 JSON）：
- 必须先跑 `validate_html.py --html index.html --apis '[...]'`，通过才能创建

三种方式都会经 validate_html.py 合约校验（A/B 内部自动跑，C 需手动跑）。

- **HTML 视觉风格由你决定**，validate_html.py 只检查数据合约，不检查样式

---

**执行环境判断：**

**在 Android OmniBot 中运行：**
```
# 用 builder 生成 JSON，再调 native 工具
terminal_execute: python3 {skillDir}/scripts/build_project_from_contract.py \
  --contract '<contract json>' [--custom-html '<html路径>']
→ 拿到 stdout JSON

workbench_project_create(<JSON 中的 projectId/name/entityName/apis/htmlFiles>)
file_write({spacePath}/PROJECT_SOUL.md, <JSON 中的 docs["PROJECT_SOUL.md"]>)
file_write({spacePath}/PROJECT_CONTEXT.md, <JSON 中的 docs["PROJECT_CONTEXT.md"]>)
workbench_project_activate(projectId)
workbench_project_open(projectId)
```

**在其他环境（Codex / 本机测试 / 无 Android）：**
```
# --execute 模式直接创建，不需要 native 工具
terminal_execute: python3 {skillDir}/scripts/build_project_from_contract.py \
  --contract '<contract json>' \
  --execute \
  --workspace './workspace'

# 结果在 ./workspace/projects/{projectId}/
# 打开 HTML: ./workspace/projects/{projectId}/frontend/html/index.html
# 后续 API 调用（CRUD）：
python3 {skillDir}/scripts/workbench_runtime.py \
  --workspace './workspace' api call \
  --project-id <projectId> \
  --api-id <toolId> \
  --inputs '<json>'
```

**注意：** `--execute` 模式仅支持 `native.collection.*` executor（CRUD）；`agent`/`script` executor 返回 `{status: "not_supported_in_portable_mode"}`，需要在当前环境手动模拟或跳过。

---

### Phase 4.5 — 初始化项目文档（仅在未使用 builder 时）

创建后立即写入以下文件：

**PROJECT_SOUL.md** — 行为合同，热更新前必读：
```markdown
# <项目名> — Project Soul

## 这是什么
<直接一两句，从 Phase 1.5 方案里复制>

## 功能
- <功能1>

## 亮点
⚡ <亮点1>

## 创建意图
<用户原话，一段，不改写>

## 业务规则
- <规则1>（用户原话）
（如无明确规则：暂无明确规则，待用户使用中逐步补充。）

## 字段约束
- <field>: <type>，<约束>，默认 <default>

## 禁止行为
- 不得生成模拟数据或占位数据
- 不得在未告知用户的情况下修改已有数据

## 更新历史
- <ISO date>: 项目创建
```

**PROJECT_CONTEXT.md**（自动生成，需更新字段类型）：
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
- 字段映射：<外部字段> → <item.fields字段>
- 限制：<rate limit/登录/时效>
- 调研日期：<YYYY-MM-DD>
```

**DESIGN.md** — 设计决策（从 Phase 0 结论提炼）
**API_REFERENCE.md** — 人类可读的 API 文档
**RESEARCH.md** — Project 创建前的产品/开源调研记录，以及各 agent API 的领域调研记录

---

### Phase 5 — Validate

```
terminal_execute: python3 {skillDir}/scripts/validate_project.py --project-path {spacePath}
```

修复所有 FAIL 后激活。

---

## 更新项目

### 更新前必须读

```
file_read({spacePath}/PROJECT_SOUL.md)
file_read({spacePath}/PROJECT_CONTEXT.md)
```

不读就改 = 不知道字段名、规则，必然破坏三层一致性。

### 创建步骤在更新时的对应关系

| 创建步骤 | 修 bug | 改样式 | 加字段 | 加新 API | 改规则 |
|---|---|---|---|---|---|
| Phase 0 领域 + 开源调研 | ❌ | ❌ | ❌ | ✓ mini product + OSS research | ❌ |
| Phase 1.5 方案确认 | ❌ | ❌ | ✓ 影响大时 | ✓ 告知用户 | ❌ |
| Phase 1.8 contract | ❌ | ❌ | ✓ 更新 fields | ✓ 追加 action | ❌ |
| Phase 4 builder | ❌ | ❌ 纯样式 | ✓ 重新跑 builder | ✓ 重新跑 builder | ❌ |
| 审查 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 应用 | hot_update | hot_update | update | update | 仅写文档 |

### 改动类型与影响范围

**Level 1 — 数据层（最重，影响三层 + 历史数据风险）**

字段变更（加/删/改名）先调脚本获取三层变更清单，再执行：
```bash
# 加字段
python3 {skillDir}/scripts/apply_field_change.py \
  --project-json {spacePath}/project.json \
  --op add --field '{"name": "category", "type": "string"}'

# 删字段
python3 {skillDir}/scripts/apply_field_change.py \
  --project-json {spacePath}/project.json \
  --op remove --field-name "category"

# 改名
python3 {skillDir}/scripts/apply_field_change.py \
  --project-json {spacePath}/project.json \
  --op rename --field-name "cat" --new-name "category"
```
脚本输出告诉你每一层需要改什么，按输出执行，不用自己推导联动。

- 字段重命名：toViewItem 必须同时读新旧 key（脚本会给出 migration line）
- 禁止直接写 `data/items.json`

**Level 2 — 工具层（较重）**
- 加 API：先 mini 调研 → 按 executor 模板写 JS → 同步加 HTML 触发入口
- 改 API 行为：只改 `run` 内容，不改 toolId
- 删 API：先从 HTML 删 callApi 调用，再删 API 定义

**Level 3 — 规则层（中等）**
- 立即写入 PROJECT_SOUL.md，原话保留
- 检查是否影响工具层 prompt 或展示层逻辑
- 规则冲突：先告知用户，等确认哪条优先

**Level 4 — 展示层（最轻）**
- 优先 `htmlPatches`（样式/文案/加减元素）
- `htmlFiles` 全量仅在 > 50% 结构变化时使用
- 加图表：CDN + canvas + 渲染函数，数据从 `project.items` 读

### 应用与文档维护

| 改动 | 工具 | 文档更新 |
|---|---|---|
| 局部 HTML | `workbench_project_hot_update (htmlPatches)` | PROJECT_CONTEXT.md oob-id |
| HTML 全量 | `workbench_project_update (htmlFiles)` | — |
| API 增删改 | `workbench_project_update (apis + htmlFiles)` | PROJECT_CONTEXT.md API Contract |
| 字段增删改 | `workbench_project_update` | PROJECT_CONTEXT.md Fields Schema |
| 规则变化 | `file_write PROJECT_SOUL.md` | PROJECT_SOUL.md |
| 新 agent API 调研 | — | RESEARCH.md 追加 |

---

## 审查清单

对照以下清单检查生成或修改后的 HTML，所有 FAIL 修完才能创建/更新。
**对于 builder 生成的默认 HTML，validate_html.py 已自动运行，无需手动审查。**
**以下清单用于强定制 HTML 或手写修改后的 HTML。**

更完整的 review 规则见 `references/review-guide.md`。新增小万能力时不要把长规则堆进这里：更新 capability-map，再在 review-guide 追加对应 rule pack。

### A. 数据绑定（最高优先级）

- [ ] `window.addEventListener('load', async` + 内部 `await window.oob.getProject()`
- [ ] `window.oob.onProjectUpdated` 在 `<script>` 顶层注册（不在函数内部）
- [ ] 无 hardcoded 数据数组（`const items = [{`）
- [ ] `toViewItem()` 存在，字段从 `f.*` 取，没有直接读 `item.fieldName`

### B. Executor-Aware（每个 API 逐条核对）

**native.collection.* API：**
- [ ] callApi 后读 `result.project`，不等 onProjectUpdated
- [ ] inputs 字段名与 inputSchema 一致

**agent API：**
- [ ] callApi 后没有读 `result.project`（等 onProjectUpdated）
- [ ] 有 spinner/disabled 覆盖等待期
- [ ] onProjectUpdated 里检查 `project._taskError`

**script API：**
- [ ] callApi 后从 `result.outputs` 读，不是 `result.project`

**所有 API：**
- [ ] callApi 第一参数是 apis[] 里的 toolId，一字不差

### C. 移动端

- [ ] `<meta name="viewport">` 含 `viewport-fit=cover` 和 `user-scalable=no`
- [ ] CSS 含 `-webkit-tap-highlight-color: transparent`
- [ ] 无固定像素布局容器，无横向滚动
- [ ] 按钮/可点击元素高度 ≥ 48px
- [ ] `<input>/<textarea>` 字号 ≥ 16px

### D. 内容完整性

- [ ] 有 `id="status"` div
- [ ] 有空状态（`.empty` 或等价）
- [ ] 每个 callApi 有 loading + error 处理
- [ ] 用户内容经 `esc()` 转义

### E. Capability-Aware（每个 agent API 逐条核对）

- [ ] agent action 声明了 `capabilities`
- [ ] 每个 capability 在 `references/capability-map.md` 有对应工具、限制或 workaround
- [ ] `agentPrompt` 使用这些能力完成闭环，并最终通过 `workbench_api_call` 写回 Project
- [ ] 需要权限的能力（VLM/无障碍/日历/通知/Shizuku 等）在方案或 UI 中有用户可理解的失败/等待状态
- [ ] 如果某能力当前 OOB 不支持，必须列入「暂不支持」，不要伪造桥接或 HTML API

### Python Linter（强定制 HTML 前运行）

```bash
python3 {skillDir}/scripts/validate_html.py \
  --html index.html \
  --apis '["entity.create","entity.archive"]'
```

---

## 技术参考

### Project Model

```json
{
  "projectId": "oob-workbench-<domain>-<name>",
  "name": "用户可见名称",
  "entityName": "PascalCase",
  "apis": [...],
  "htmlFiles": [
    {"path": "base.css", "content": "..."},
    {"path": "index.html", "content": "..."}
  ]
}
```

### 数据合约（所有 HTML 必须遵守，与视觉无关）

```js
// ① 页面加载：必须 async + await
window.addEventListener('load', async () => {
  const project = await window.oob.getProject();
  render(activeViewItems(project));
});

// ② 实时更新：顶层注册，不在函数内
window.oob.onProjectUpdated(function(project) {
  if (project._taskError) { showError(project.errorMessage); return; }
  render(activeViewItems(project));
});

// ③ 字段适配器：业务字段必须从 item.fields.* 取
function toViewItem(item) {
  const f = item.fields || {};
  return { id: item.id, title: item.title || '', amount: Number(f.amount || 0) };
}

// ④ API 调用：按 executor 类型读结果
// native.collection.*  → result.project（立即）
const r = await window.oob.callApi('entry.create', inputs);
if (!r.success) { showError(r.errorMessage); return; }
render(activeViewItems(r.project));

// agent → 不读 result，等 onProjectUpdated
await window.oob.callApi('entry.analyze', {});
// result 在 onProjectUpdated 里处理

// script → result.outputs
const r = await window.oob.callApi('entry.stats', {});
const { total } = r.outputs;
```

**三条不可违反的规则：**
1. 列表数据只从 `project.items` 取，不 hardcode
2. `callApi` 的第一参数必须是已注册的 `toolId`，一字不差
3. `item.fields.*` 取业务字段，不是 `item.fieldName`

---

### Control APIs（Agent 调用，不注册为 Project Tool）

```
workbench_project_create / list / get / open / activate / deactivate
workbench_project_update / hot_update / export / delete / progress_get
workbench_api_list / workbench_api_call
```

### Item 数据结构

```js
{
  id: "uuid",
  title: "显示标题",
  status: "active",  // "active" | "archived"
  fields: {
    // 所有业务字段在这里，不在顶层
    amount: 150.0,
    date: "2026-05-14",
  }
}
```

### callApi 返回结构

```js
// 成功：
{ success: true, outputs: {...}, project: { items: [...] } }
// 失败：
{ success: false, errorCode: "...", errorMessage: "..." }
// agent 任务已提交：
{ status: "pending" }  // 没有 project 字段
```

### Hot Update — htmlPatches

优先 htmlPatches（~50 token），大结构重组才用 htmlFiles（~5000 token）。

锚点优先用 `data-oob-id`：
```json
[{"path": "index.html", "oldText": "data-oob-id=\"btn-add\"", "newText": "data-oob-id=\"btn-add\" style=\"background:#34C759\""}]
```

### HTML 骨架（builder 自动生成）

builder 生成符合 OOB 风格的 HTML，使用 `base.css` 组件类：

```
.container > .header(.title + .summary) + #status
          + .btn-primary(主操作)
          + .form-panel(隐藏，展开录入)
          + agent-actions(各 agent 触发按钮)
          + #main(卡片列表)
```

**完整骨架和组件规范见 `references/frontend-guide.md`**（权威来源）。

**base.css 核心组件类：**

| Class | 用途 |
|---|---|
| `.container` | 居中列，max-width 430px |
| `.header` `.title` `.summary` | 页头 + 大数字统计 |
| `.card` `.card-row` `.card-title` `.card-meta` | 列表卡片 |
| `.btn-primary` | 全宽蓝色主操作按钮（min-height 44px）|
| `.btn-secondary` | 中性边框按钮 |
| `.btn-archive` | 文字型归档/删除按钮 |
| `.input` `.input-label` `.input-group` | 表单输入项 |
| `.form-panel` `.form-actions` | 表单容器 + 按钮行 |
| `.empty` `.empty-text` `.empty-hint` | 空状态 |
| `.error-msg` `.status-msg` | 错误 / 状态提示 |
| `.tag` `.tag-blue` `.tag-green` 等 | 行内彩色标签 |
| `.stat-grid` `.stat-card` | 2列统计块 |

手写或修改 HTML 时优先用这些 class，不内联重复样式。

### 可用 OOB 工具（agent prompt 里使用）

权威清单在 `references/capability-map.md`。这里不维护完整工具表，避免小万新增能力后主 skill 变旧。

### 激活前质量清单

- [ ] HTML 调用的每个 callApi(apiId) 都在 apis[] 里有对应 toolId
- [ ] 每个用户可见 API 在 HTML 里有触发入口；agent 写回用的 create/update 可只出现在 `agentPrompt` 的 `workbench_api_call`
- [ ] 每个 agent API 的 `capabilities` 已在 capability-map/review-guide 中审查
- [ ] 页面加载调用 getProject() 并从 project.items 渲染
- [ ] onProjectUpdated 已注册
- [ ] 业务字段通过 toViewItem() 的 item.fields.* 访问
- [ ] 无 hardcoded 数据数组
- [ ] PROJECT_SOUL.md 已写入
- [ ] validate_project.py 通过（0 FAIL）
- [ ] workbench_project_activate + workbench_project_open 已调用
