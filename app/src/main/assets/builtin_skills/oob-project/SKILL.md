---
name: oob-project
description: OOB Workbench Project 完整生命周期总入口：新建 Project、更新、蒸馏、审查、HTML Display、Project Tool/API 设计、热更新、导出、PROJECT_SOUL/PROJECT_CONTEXT 维护。Use when the user says "帮我做一个", "我想创建一个", "新建 Project", "build me a", "make a [X] tracker/tool/app", "改一下界面", "加个字段", "新增功能", "修复", "project review", "审查 project", "总结成我们的 project", "蒸馏能力", "提取核心设计", "生成 soul", "把这个 app/项目变成 OOB Project", "产品分析", "GitHub 仓库分析", or any intent to create, distill, modify, review, export, or maintain a persistent personal tool.
---

# OOB Project

OOB Project 是 **OOB 内置核心能力**，不是独立插件边界。Project 的创建、更新、蒸馏、Display、Tool/API、审查、导出和项目文档维护都归本 skill。

不要再引用或恢复任何已废弃的 Project skill 边界名。

`oob-prompt-runtime` 保持独立：它只负责把 Project 上下文以 bounded `project_context` prompt section 注入模型，不负责 Project 意图、设计、创建、HTML、API 或 Workbench 工具使用。

## 先读什么

收到 Project 相关请求，先读完本文件，再按任务类型只加载必要 reference。

| 任务 | 必读 reference |
|---|---|
| 新建 Project | `references/lifecycle-guide.md` |
| 更新/热更新 Project | `references/lifecycle-guide.md`；HTML 小改再读 `references/hot-update-guide.md` |
| 蒸馏外部产品、仓库、文档、截图 | `references/distillation-guide.md`，再回到 lifecycle 创建/更新 |
| 审查 Project | `references/review-guide.md` |
| 设计 agent action、backend/script API | `references/backend-guide.md` 和 `references/agent-prompt-templates.md` |
| 设计或修 HTML Display | `references/frontend-guide.md`；需要样例时读 `references/html-patterns.md` |
| 选择 OOB 能力 | `references/capability-map.md` |
| 写/维护 PROJECT_SOUL | `references/project-soul-guide.md` |

## 产品边界

OOB Project 默认是小而精的个人工具，不是“一键 vibe 一个大应用”。

- v1 只解决一个核心闭环：输入/触发 -> 结构化保存 -> 一屏查看/归档。
- 默认 1 个实体、3-5 个字段、0-1 个主 agent action、1 个列表/仪表页。
- 不做登录系统、复杂设置、多页面导航、市场化首页、完整 BI、插件体系。
- 大需求先切成 v1；其余写进「暂不支持 / 后续可加」。

OOB 的根本价值：

- **数据统一管理**：所有数据在一处，AI 可跨项目操作。
- **自由定制**：字段、规则、界面按用户说的来，后续可热更新。

## 新建硬关卡

新建 Project 必须按 `references/lifecycle-guide.md` 的生命周期执行。最小关卡如下：

| Step | 阶段 | 必须产出 | 关卡 |
|---|---|---|---|
| 1 | 领域 + 开源调研 | 产品要点、开源启发、OOB v1 设计主张 | 输出后继续 |
| 2 | 方案确认 | 完整方案卡片 | **等用户明确回复** |
| 3 | 意图提取 | Entity、字段、主操作、OOB 能力需求 | 输出后继续 |
| 4 | ProjectContract | 结构化合约 JSON | 内部步骤 |
| 5 | 审查 | `✅ 通过` 或 `⚠️ 修复了N项` | 必须通过 |
| 6 | 创建 | `workbench_project_create` 或 builder 输出再创建 | 前 5 步全部完成 |

Step 2 是硬关卡。用户说“好 / 可以 / 继续”才能进入 Step 3；沉默不是确认。禁止跳步直接调 `workbench_project_create`。

## 设计规则

### 数据与三层一致

字段名是 Entity Schema、Project Tool、HTML Display 的共同语言。一处字段名不同，数据层、工具层、展示层就会断开。

```
entity schema  -> fields.kcal: number
api inputSchema -> kcal: "number"
toViewItem()   -> kcal: Number(f.kcal || 0)
HTML           -> ${vi.kcal} 大卡
```

修改前先读 `PROJECT_SOUL.md` 和 `PROJECT_CONTEXT.md`。用户规则必须纵向穿透数据分类、工具行为、展示逻辑和字段约束。

### HTML 只展示和触发

默认输入路由是聊天/Agent：

```
用户输入 -> 聊天对话框/Agent -> Agent 解析/拍照/VLM -> callApi 写入 -> HTML 展示
```

HTML 默认负责：

- Agent 触发按钮。
- `project.items` 列表/仪表展示。
- 归档、删除、轻量编辑等已存在数据操作。

HTML 默认不负责多字段录入表单。用户明确要求“界面里直接编辑”时，才加轻量 inline 编辑。

### Runtime contract 不变

不要引入新 public tool，不重写 Workbench 数据格式，不绕过现有 bridge。

- 页面加载：`await window.oob.getProject()`。
- API 调用：`await window.oob.callApi(toolId, inputs)`，`toolId` 必须匹配 `apis[].toolId`。
- 列表数据：只从 `project.items` 读。
- 业务字段：从 `item.fields.*` 读，不直接读 `item.fieldName`。
- `native.collection.*` 调用后读 `result.project`。
- `agent` 调用后等待 `window.oob.onProjectUpdated`。
- `script` 调用后读 `result.outputs`。
- 当前 Project 显示模式由宿主注入：`project.colorScheme` / `frontendContext.colorScheme` 为 `light` 或 `dark`。HTML 用 `html[data-oob-color-scheme="dark"]` / `html[data-oob-color-scheme="light"]` 或 `window.oob.colorScheme()` 区分浅色 / 深色模式，默认跟随当前 App 主题。

完整 HTML 规则见 `references/frontend-guide.md` 和 `references/html-patterns.md`。

## 工具与脚本

优先使用本 skill 的脚本，避免手写易错 JSON。

- `scripts/build_project_from_contract.py`：把 ProjectContract 转成 `workbench_project_create` JSON；可用 `--execute` 在本地 portable workspace 创建纯 CRUD Project。
- `scripts/validate_project.py`：检查 Project runtime 文件、HTML bridge、API 绑定和文档。
- `scripts/validate_html.py`：手写 HTML 前后检查 `getProject()`、`callApi()`、注册 API、硬编码数据等。
- `scripts/apply_field_change.py`：字段增删改时输出三层联动清单。
- `scripts/workbench_runtime.py`：本地 portable mode 下调用 CRUD API。

## 更新规则

更新项目时先读：

```
PROJECT_SOUL.md
PROJECT_CONTEXT.md
```

按影响范围选择路径：

| 改动 | 默认路径 |
|---|---|
| 文案、样式、小块 HTML | `workbench_project_hot_update` / `htmlPatches` |
| 大结构 HTML | `workbench_project_update` / `htmlFiles` |
| API 增删改 | 先 mini 调研，再 `workbench_project_update` |
| 字段增删改 | 先跑 `apply_field_change.py`，再同步数据、API、HTML、文档 |
| 用户规则变化 | 先写 `PROJECT_SOUL.md`，再检查 API prompt 和展示逻辑 |

不要直接编辑 `data/items.json`。不要为了加功能重新创建已有 Project。

## 审查与验证

创建、重大更新、手写 HTML、agent API 变更都必须审查。优先读 `references/review-guide.md`。

最小输出：

```text
Project Review
- Contract: PASS|FAIL
- Data/Tool/Display binding: PASS|FAIL
- Capability fit: PASS|WARN|FAIL
- Runtime behavior: PASS|WARN|FAIL
- Extensibility notes: <新增小万能力时该 Project 如何接入>
结论：✅ 通过 / ⚠️ 修复了 N 项 / ❌ 阻塞
```

激活前至少确认：

- HTML 调用的每个 `callApi(toolId)` 都在 `apis[]` 中存在。
- 页面加载调用 `getProject()` 并从 `project.items` 渲染。
- `onProjectUpdated` 已注册。
- 业务字段通过 `item.fields.*` 访问。
- 无硬编码数据数组。
- `PROJECT_SOUL.md` 和 `PROJECT_CONTEXT.md` 已同步。
- `validate_project.py` 通过，0 FAIL。
- 已调用 `workbench_project_activate` 和 `workbench_project_open`。

## 导出

Workbench export package 必须记录 canonical Project skill：

```json
{
  "source": "oob-project",
  "skills": [
    {
      "skillId": "oob-project",
      "source": "builtin_asset",
      "path": "skills/oob-project/SKILL.md"
    }
  ]
}
```

导出 zip 应包含 `skills/oob-project/SKILL.md`。
