---
name: oob-project-updater
description: Update an existing OOB Workbench Project. Use when the user wants to change, extend, or fix a project that already exists — add a field, change the UI, add a new API, fix a bug, update a rule, or change behavior. Do NOT use for creating a new project from scratch (use oob-project-designer instead).
---

# OOB Project Updater

**触发：** 改、加、删、修、更新、修复、优化、换、加个字段、改界面、新增功能、以后要…

---

## 核心原则

**更新 ≠ 重新创建。** 最小化改动，保持三层一致。

OOB Project 有三层，全部从同一个实体定义派生：

```
数据层（item.fields.*）
工具层（registered apis）
展示层（index.html）
```

三层用同一套字段名。改了任何一层，相关的其他层必须同步——否则数据写进去读不出来，或者按钮触发了但界面不更新。

PROJECT_SOUL.md 纵向穿透三层。改动不能违背它记录的用户规则。

---

## 更新的优先级顺序（从高到低）

改动有轻重之分。越往上越难改、影响越大、需要更谨慎：

```
1. 数据层变化（加/删/改字段）    ← 最重，影响三层 + 历史数据风险
2. 工具层变化（加/删/改 API）    ← 较重，影响工具 + 展示需同步
3. 规则变化（PROJECT_SOUL 更新） ← 中等，可能触发工具层或展示层联动
4. 展示层变化（HTML 样式/布局）  ← 最轻，通常只动 HTML
```

判断清楚是哪一级的改动，再决定动哪些文件。

---

## 开始之前：读懂现状

**任何改动之前，必须先读这两个文件：**

```
file_read({spacePath}/PROJECT_SOUL.md)      ← 用户的规则和意图
file_read({spacePath}/PROJECT_CONTEXT.md)   ← 现有字段名、API 列表、oob-id
```

不读就改 = 不知道字段名叫什么、不知道已有什么规则、可能写出和现有数据不兼容的代码。

涉及 agent API 改动时额外读：
```
file_read({spacePath}/RESEARCH.md)          ← 之前调研过的领域知识，不重复工作
```

---

## 级别 1：数据层变化（最重）

**触发：** "加一个字段"、"再记一个信息"、"把这个改成那个"

### 加字段（推荐方式）

字段名一旦确定，三层必须用同一个名字：

```
字段名  →  API inputSchema  →  toViewItem()  →  HTML 显示
kcal       kcal: "number?"     kcal: Number(f.kcal||0)  ${item.kcal}大卡
```

同步更新顺序：
1. 在 PROJECT_CONTEXT.md 的 Item Fields Schema 表加新字段行
2. 在相关 API 的 inputSchema 加这个字段（可选用 `"type?"` 保持向后兼容）
3. 在 toViewItem() 加字段映射（带默认值）
4. 在 HTML 加对应表单输入和列表显示

### 字段重命名（有数据风险）

已有数据的 `item.fields` 里存的是旧字段名，重命名后旧数据这个字段变成 undefined。

**唯一安全做法：** 在 toViewItem() 里同时读新旧字段名：
```js
kcal: Number(f.kcal || f.calories || 0),  // calories 是旧名，kcal 是新名
```

不要修改 data/items.json，不要做数据迁移脚本（除非用户明确要求）。

### 删字段

先确认没有 API 的 inputSchema 在使用这个字段，没有 HTML 在读取它，再从 toViewItem() 和 PROJECT_CONTEXT.md 里移除。

---

## 级别 2：工具层变化（较重）

**触发：** "加一个功能"、"改这个操作的行为"、"删掉某个功能"

### 加 API

1. 确认新 API 的 toolId 不和现有的重复
2. 决定 executor（native / agent / script）
3. 如果是 agent API：先查 RESEARCH.md，没有就 web_search 调研，写入 RESEARCH.md
4. 在 HTML 同步加对应触发入口（按钮或操作区）
5. 更新 PROJECT_CONTEXT.md 的 API Contract 表

### 改 API 行为

只改 `run` 内容（prompt / executor），不改 toolId。toolId 改了 = HTML 里所有 `callApi('old.id')` 失效。

### 删 API

**顺序必须对：** 先从 HTML 删掉所有 `callApi('this.id')` 的调用，再删 API 定义。反过来会造成 HTML 调用不存在的 API。

---

## 级别 3：规则变化（中等）

**触发：** "以后 X 改成 Y"、"我不想要这个了"、"换个主题色"

1. 立即把新规则写入 PROJECT_SOUL.md 的对应节，原话保留，注明日期
2. 检查这条规则是否影响已有 API 的分类逻辑或展示逻辑
3. 如果影响：按级别 2 或级别 4 处理对应层

**规则冲突：** 新规则和已有规则冲突时，先告诉用户有冲突，等确认哪条优先，再修改。

---

## 级别 4：展示层变化（最轻）

**触发：** "改一下界面"、"颜色深一点"、"换个布局"、"加个图表"

工具层不动，字段不动。

**优先用 htmlPatches，不整体重写：**

| 场景 | 方式 |
|---|---|
| 改样式 / 文案 / 颜色 | `htmlPatches` |
| 加减元素 / 调整位置 | `htmlPatches` |
| 整体结构重组（> 50% 变化）| `htmlFiles` 全量 |

htmlPatches 锚点优先用 `data-oob-id`，比文本内容稳定：
```json
[{"path": "index.html",
  "oldText": "data-oob-id=\"btn-add\"",
  "newText": "data-oob-id=\"btn-add\" style=\"background:#34C759\""}]
```

加图表时只加 CDN + canvas + 渲染函数，数据从 `project.items` 读，不另建数据源。

---

## 应用与收尾

### Review（必须）

```
skills_read(skillId="oob-project-reviewer")
```

不论大小，所有改动都过 reviewer。

### 应用方式

| 改动范围 | 工具 |
|---|---|
| 仅 HTML 局部 | `workbench_project_hot_update` (htmlPatches) |
| HTML 结构大改 | `workbench_project_update` (htmlFiles) |
| 新增或修改 API | `workbench_project_update` (apis + htmlFiles) |
| 仅 PROJECT_SOUL 规则 | `file_write` 直接写 |

### 更新文档

| 改了什么 | 更新哪里 |
|---|---|
| 字段增删改 | PROJECT_CONTEXT.md → Item Fields Schema |
| API 增删改 | PROJECT_CONTEXT.md → API Contract |
| oob-id 元素变化 | PROJECT_CONTEXT.md → HTML Element Inventory |
| 用户说新规则/偏好 | PROJECT_SOUL.md → 对应节追加 |
| 新 agent API 有调研 | RESEARCH.md → 追加条目 |
| 重大设计变更 | DESIGN.md → 更新历史追加 |

---

## 禁止事项

- 重新调研领域（除非加全新类型的 agent API）
- 重命名已有 toolId（破坏 HTML callApi 引用）
- 先删 API 再删 HTML 调用（应该反过来）
- 直接写 `data/items.json`（runtime 管理，直接写损坏数据）
- 跳过读 PROJECT_SOUL.md（改动可能违背已有规则）
- 跳过 reviewer（reviewer 负责检查三层一致性）
