---
name: oob-project-reviewer
description: Review and fix OOB Workbench Project HTML before creation or hot-update. Use after generating or modifying HTML, before calling workbench_project_create or workbench_project_update. Checks frontend-backend contract, mobile standards, data binding rules, and API alignment. Returns a violation report and fixes all FAIL items.
---

# OOB Project Reviewer

**调用时机：** 任何涉及 HTML 生成或修改的场景，在写入项目之前调用本 skill。

- 项目创建：Phase 3（HTML 生成）之后、Phase 4（workbench_project_create）之前
- 热更新：修改 HTML 之后、workbench_project_update 之前

**执行顺序（强制）：** ① Checklist 扫描 → ② 修复所有 FAIL → ③ 可选 Python linter 确认 → ④ 返回 reviewer 报告

---

## Step 1 — Checklist 扫描

把当前 HTML 逐条对照以下清单。每发现一个 FAIL，**立即停下来修正**，修正完再检查下一条。不要把所有 FAIL 攒到最后一起修。

### A. 前后端接口（最高优先级）

- [ ] `window.addEventListener('load', ...)` 里调用了 `window.oob.getProject()`，从 `project.items` 渲染初始数据
- [ ] `window.oob.onProjectUpdated(fn)` 已注册，且在回调里重新渲染列表
- [ ] 所有 `callApi(apiId)` 的 apiId **完全匹配** `apis` 列表里的 toolId，无多余、无遗漏
- [ ] agent API 的 `callApi` 之后**没有**读 `result.project`（agent 返回 `{status:"pending"}`，数据在 `onProjectUpdated`）
- [ ] native.collection API 的结果从 `result.project.items` 渲染，**不是**等 `onProjectUpdated`
- [ ] script API 的计算结果从 `result.outputs` 取，不是 `result.project.items`
- [ ] 没有直接读 `item.amount`、`item.category` 等顶层字段——所有业务字段必须经 `toViewItem()` 从 `item.fields.*` 取
- [ ] `toViewItem()` 函数存在，且为所有业务字段提供默认值和类型转换

### B. 移动端标准

- [ ] `<meta name="viewport">` 包含 `viewport-fit=cover` 和 `user-scalable=no`
- [ ] CSS reset 包含 `-webkit-tap-highlight-color: transparent`
- [ ] 没有固定像素布局容器（`width: 400px`），使用 `%`、`100%` 或 `vw`
- [ ] 所有按钮和可点击元素高度 ≥ 48px（可通过 padding 撑大点击区）
- [ ] 所有 `<input>` / `<textarea>` 的 `font-size` ≥ 16px（防 iOS 自动缩放）
- [ ] 没有 `type="number"` 输入框（用 `inputmode="decimal"` 代替）
- [ ] 主操作按钮在页面底部区域，不在顶部

### C. 内容完整性

- [ ] 没有硬编码数据数组（如 `const items = [{...}, {...}]`）
- [ ] 有空状态（`.empty` 或等价元素），文案有实际引导意义
- [ ] 有 `id="status"` div，用于 loading / success / error 消息
- [ ] 每个 `callApi` 调用都有：loading 状态 + error 处理 + `esc()` 转义用户内容

### D. 设计质量

- [ ] 间距使用 4 或 8 的倍数（4, 8, 12, 16, 24, 32px）
- [ ] 字体层级最多 4 档，正文 ≥ 15px，辅助文字 ≥ 12px
- [ ] 颜色使用 CSS 变量（`var(--accent)` 等），不硬编码十六进制
- [ ] 有 `:active` 反馈状态，没有仅靠 `:hover` 触发的交互

---

## Step 2 — Python Linter（可选，精确检测）

checklist 之后，用 `terminal_execute` 运行以下脚本做自动扫描。把 HTML 内容通过 stdin 传入：

```python
#!/usr/bin/env python3
import re, sys

html = sys.stdin.read()
errors = []

# A. 前后端接口
if 'getProject()' not in html:
    errors.append("FAIL [bridge] window.oob.getProject() not called on load")
if 'onProjectUpdated' not in html:
    errors.append("FAIL [bridge] onProjectUpdated not registered")
if re.search(r'\bitem\.(?!fields\b|id\b|title\b|status\b|createdAt\b)\w+\b', html):
    errors.append("WARN [bridge] possible direct item field access — use item.fields.*")
if 'toViewItem' not in html:
    errors.append("FAIL [bridge] toViewItem() adapter missing")

# B. 移动端
if 'viewport-fit=cover' not in html:
    errors.append("FAIL [mobile] viewport-fit=cover missing")
if '-webkit-tap-highlight-color' not in html:
    errors.append("FAIL [mobile] tap-highlight-color reset missing")
for m in re.finditer(r'(?<!\-)(width|min-width)\s*:\s*(\d+)px', html):
    if int(m.group(2)) > 200:
        errors.append(f"WARN [mobile] fixed width {m.group(2)}px — use % or vw")
for m in re.finditer(r'font-size\s*:\s*(\d+)px', html):
    if int(m.group(1)) < 12:
        errors.append(f"FAIL [mobile] font-size {m.group(1)}px too small (min 12px)")
if 'type="number"' in html or "type='number'" in html:
    errors.append("WARN [mobile] type=number found — use inputmode=decimal instead")

# C. 内容
if re.search(r'const \w+\s*=\s*\[[\s\S]{10,200}title.*?\]', html):
    errors.append("WARN [data] possible hardcoded items array")
if 'id="status"' not in html and "id='status'" not in html:
    errors.append("FAIL [content] #status element missing")
if 'empty' not in html.lower():
    errors.append("WARN [content] no empty state found")
if 'esc(' not in html:
    errors.append("WARN [xss] no esc() function — user content may not be escaped")

fail_count = sum(1 for e in errors if e.startswith('FAIL'))
warn_count = sum(1 for e in errors if e.startswith('WARN'))

for e in errors:
    print(e)
if not errors:
    print("PASS: all checks passed")
else:
    print(f"\nSummary: {fail_count} FAIL, {warn_count} WARN")

sys.exit(1 if fail_count > 0 else 0)
```

FAIL → 必须修正后才能继续。WARN → 确认合理后可继续。

---

## Step 3 — Reviewer 报告

修复完所有 FAIL 后，输出一份简短的 reviewer 报告，格式：

```
✅ Reviewer 通过
修复项：
- [bridge] toViewItem() 已补充
- [mobile] viewport-fit=cover 已添加
剩余 WARN：
- [mobile] width:240px（用于图表容器，已确认合理）
```

如果 0 FAIL 0 WARN，直接输出 `✅ Reviewer 通过，无违规`。

---

## 复杂项目：Sub-agent Reviewer

项目有 ≥ 5 个 API、多页面跳转、或复杂状态管理时，在 Step 1/2 之后额外运行 sub-agent 深度审查：

```
subagent_dispatch(
  goal = "你是 OOB Project 合规审查员。只输出违规列表，每条格式：[类别] 问题描述 → 修复建议。不解释，不重写代码。

审查维度：
1. 前后端数据流：agent/native/script 三种模型的结果读取方式是否正确
2. API 对齐：callApi 的 apiId 是否都在 apis 列表里
3. 移动端：触控目标、字体、布局、安全区
4. XSS：用户内容是否经过 esc() 转义

HTML:\n<生成的 HTML>

apis 列表:\n<Phase 2 的 apis JSON>"
)
```

---

## 给调用方的说明

本 skill 的输出是修复后的 HTML 和 reviewer 报告。调用方（`oob-project-designer`、`oob-native-html-frontend`）：
- 收到报告后继续下一步（创建或更新项目）
- 把 reviewer 报告的摘要写入 `PROJECT_CONTEXT.md` 的 `## 最近 Reviewer 记录` 节
- 不要把 WARN 当 FAIL 处理，不要因为 WARN 阻塞创建
