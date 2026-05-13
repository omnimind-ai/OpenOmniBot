# OOB Project Distillation Guide

This guide turns external inspiration into an OOB-native Workbench Project. Use it after `SKILL.md` triggers.

## Table of Contents

1. Source Intake
2. Capability Distillation
3. Project Soul Template
4. Data Model Template
5. API Design Template
6. HTML/UI Design Template
7. AI Backend Workflow Mapping
8. Compliance and IP Guardrails
9. Readiness Checklist
10. Output Template

---

## 1. Source Intake

Classify the source first.

| Source | Minimum evidence to inspect | Notes |
|---|---|---|
| GitHub repo | README, docs, examples, screenshots, package metadata, license, core folders | Prefer summarizing architecture and feature intent over copying code |
| Vibe app share link | User-provided text, screenshots, demo video notes, visible claims | If the link cannot be accessed, ask user to paste text/screenshots |
| Technical document | Concepts, entities, APIs, workflows, examples, constraints | Convert technical capability into user-facing workflow |
| Screenshots | Visible UI hierarchy, states, affordances, labels, navigation | Extract interaction logic and layout principles, not exact visuals |
| Pasted notes | Goals, repeated pain points, feature list, desired audience | Ask one clarifying question only if the target user or primary workflow is unclear |

For GitHub projects, check the license before using code. If the license is unclear or incompatible, use ideas only and rewrite from scratch.

Do not over-collect. Stop when the core capability, entity model, and main workflow are clear enough for an OOB v1.

---

## 2. Capability Distillation

Write the source in four layers:

### Product Job

One sentence:

```text
This helps <user> do <job> under <context> so they can <outcome>.
```

### Capability Inventory

| Capability | Source evidence | OOB equivalent | Keep? |
|---|---|---|---|
| Example: Analyze receipt photo | README screenshot + OCR feature | `image_picker` → `vlm_task` → `entry.create` | yes |
| Example: Team sharing | Pricing page | Not local-first; export only | no for v1 |

Keep capabilities that become stronger inside OOB:
- local personal data
- AI interpretation
- screenshot/photo analysis
- recurring reminders
- lightweight dashboard
- project-specific memory and rules
- workflows spanning phone apps, calendar, files, browser, or notifications

Drop capabilities that are not OOB-native:
- multi-user SaaS permissions
- social feeds
- cloud sync as a core assumption
- branded marketplaces
- heavy realtime collaboration
- proprietary integrations without user credentials

### Interaction Flow

Represent the distilled flow as:

```text
Entry → inspect/current state → primary action → AI/tool processing → persisted item → feedback/notification → review/edit
```

Example:

```text
Open spending dashboard → tap Import screenshot → choose image → VLM parses records → user reviews parsed rows → entries saved → monthly stats update
```

### Design DNA

Capture reusable UI/UX principles without copying visuals:

- information density
- navigation style
- primary action placement
- empty/loading/error states
- list/card/table choice
- chart type
- tone of labels
- mobile ergonomics

Bad:

```text
Copy their purple gradient card and logo.
```

Good:

```text
Use a compact daily summary header, one persistent primary action, and grouped cards by date.
```

---

## 3. Project Soul Template

Generate a complete `PROJECT_SOUL.md` draft for every distilled Project.

````markdown
# <Project Name> — Project Soul

## 创建意图

<Explain the user's source material and what OOB is preserving. State that this
Project distills capability and workflow rather than copying the original product.>

## 来源洞察

| Source | What was learned | How it maps to OOB |
|---|---|---|
| <repo/doc/link/screenshot> | <capability / interaction / UI insight> | <Project field/API/UI rule> |

## 业务规则

- <Domain rule inferred from source or user intent>
- <Rule about what requires user confirmation>
- <Rule about AI interpretation quality or fallback>

If a rule is inferred, mark it:

```text
- 日期默认今天（从领域常识推断，非用户明确说明）
```

## 字段约束

| Field | Type | Required | Valid values / constraints | Default |
|---|---|---|---|---|
| title | string | yes | User-visible item label | — |

## 显示偏好

- First viewport: <summary + primary action + latest/highest priority content>
- Layout: <list/cards/chart/timeline>
- Tone: <utilitarian/editorial/playful/etc.>
- Avoid: <specific visual or interaction anti-patterns>

## AI 能力边界

- <What AI can infer from screenshots/docs/photos>
- <What AI must ask the user to confirm>
- <What should be handled by deterministic native/script tools>

## 禁止行为

- 不复制来源项目的品牌、logo、图标、插画、专有文案或付费内容
- 不生成模拟数据作为真实记录
- 不在用户未确认时删除、归档或批量修改已有记录
- 不把 Project ID、工具数量、executor、workspace、data/log 路径等控制面信息写到可见界面
- <Domain-specific prohibition>

## 更新历史

- <YYYY-MM-DD>: 从 <source> 蒸馏创建初版 Project Soul
````

Use the device date when writing update history.

---

## 4. Data Model Template

Start from the source's real entities, then simplify for OOB v1.

```markdown
## Entity

Primary entity: `<EntityName>`

Why this entity:
<One sentence.>

## Fields

| Field | Type | Required | Source evidence | OOB notes |
|---|---|---|---|---|
| title | string | yes | visible item name | display label |
| status | enum | yes | workflow state | active/archived/done |

## Derived Views

| View | Inputs | Computed by |
|---|---|---|
| Today summary | items.date, items.amount | HTML or script |
| Monthly report | all active items | script if heavy |
```

Prefer 4-7 fields. More fields make first-run usage heavy.

Field rules:
- Use `number` for quantities and scores.
- Use `date` or `datetime`, not free text, for time.
- Use `enum` only when labels are stable and helpful.
- Store source provenance only when it helps the user inspect AI output.
- Do not store raw screenshots by default; store extracted facts and optional source filename/path if user supplied it.

---

## 5. API Design Template

Design APIs before HTML.

```markdown
## API Set

| API ID | Executor | Inputs | Outputs | UI trigger | Notes |
|---|---|---|---|---|---|
| item.create | native.collection.create | title, ... | item | Add button | deterministic |
| item.archive | native.collection.archive | item_id | item | card menu | confirm destructive action |
| item.analyze | agent | image/doc/url | items | Analyze button | AI workflow |
```

Executor selection:

| Need | Executor |
|---|---|
| Add/update/archive one record | `native.collection.*` |
| List current records | HTML `getProject()` or `native.collection.list` only if needed |
| Computation, export, batch transform | `script` / `workspace_python_script` |
| Camera/gallery image analysis | `agent`: `image_picker` → `vlm_task` |
| Web/doc research | `agent`: `web_search` / `browser_use` / file tools |
| Phone app interaction | separate reviewed flow using Android accessibility tools |
| Recurring behavior | `schedule_task_create` or alarm/calendar tools inside agent task |

Keep v1 small:
- 2-4 APIs
- one primary action
- one secondary AI action if the source value depends on AI
- archive/delete only if the user needs cleanup

---

## 6. HTML/UI Design Template

Default output is static HTML/CSS/native JS under `frontend/html/`.

Do not generate React by default. If React is justified, it must compile to static files that the Project WebView can load from `frontend/html/`. The WebView must never depend on a dev server.

### Required UI Contract

- First viewport shows useful content, not a landing hero.
- Top area: project title, one summary stat, one primary action.
- Main area: real `project.items`, normalized from `item.fields`.
- Status area: loading, empty, success, and error states.
- Use `data-oob-id` on important elements for future annotation and edits.
- Use local relative links only for multi-page HTML: `detail.html?id=<id>`.
- Never open external URLs inside the Project WebView.

### Mobile Constraints

Target phone portrait WebView around 360-430dp wide.

- No desktop sidebar-first layouts.
- No wide tables unless wrapped in explicit horizontal scroll.
- No oversized hero, decorative gradient blobs, or marketing page layout.
- Keep controls reachable with thumb-friendly touch targets.
- Text must fit containers; avoid long single-line labels.
- Primary action should be visible without scrolling.

### State Contract

HTML can keep transient UI state only:
- current filter
- input text
- expanded panel
- loading indicator

Persistent business state lives only in:
- `project.items`
- Project Tools
- scripts/agent workflows

Never make a local JS array the source of truth.

---

## 7. AI Backend Workflow Mapping

Map every AI-heavy source feature into an OOB tool chain.

| Source ability | OOB chain | Project write-back |
|---|---|---|
| Analyze image/document | `image_picker` or file input → `vlm_task` | `workbench_api_call(...create/update...)` |
| Research topic | `web_search`/`browser_use` → summarize | create finding/report items |
| Generate plan/report | LLM agent task + optional script | write item fields or export file |
| Import data from another app | user-reviewed Android observation/accessibility flow | write normalized records |
| Daily reminder/summary | `schedule_task_create` → agent task | notification + optional summary item |
| Calendar sync | `calendar_event_create/list` | record event IDs in item fields when useful |

Agent prompt pattern:

```text
1. Read PROJECT_SOUL.md and PROJECT_CONTEXT.md.
2. Perform the source-specific AI/tool step.
3. Extract structured facts using the Project field schema.
4. Ask for confirmation before destructive or ambiguous writes.
5. Call workbench_api_call(projectId=inputs.projectId, apiId=<id>, inputs={...}) to persist.
6. Send notification only when useful and user-visible.
```

---

## 8. Compliance and IP Guardrails

Always include a "what not to copy" decision.

Do not copy:
- brand name, logo, icons, mascot, illustrations
- exact color palette when strongly brand-identifying
- exact copy or onboarding text
- proprietary datasets
- non-compatible licensed code
- hidden API calls or private endpoints
- paid features/content beyond what the user has rights to use

Allowed:
- general workflow patterns
- abstract data model
- user-visible feature categories
- interaction principles
- original implementation written for OOB
- public API integration when terms allow it

For GitHub:
- If license is permissive, code can inspire implementation, but prefer rewriting to OOB architecture.
- If no license, treat source as read-only inspiration.
- If GPL/AGPL, avoid incorporating code into the app unless the user explicitly accepts obligations.

Privacy:
- Store distilled summaries, not raw source screenshots, unless the user asks.
- Do not infer or save sensitive personal data that is not needed for the Project.
- Ask for confirmation before saving data extracted from personal screenshots/docs.

---

## 9. Readiness Checklist

Before proposing or creating a Project:

- [ ] Source type and evidence are identified.
- [ ] Core job-to-be-done is one sentence.
- [ ] At least one capability is worth keeping in OOB.
- [ ] Brand/IP elements are excluded.
- [ ] `PROJECT_SOUL.md` draft exists.
- [ ] Primary entity and 4-7 fields are defined.
- [ ] API IDs are stable and minimal.
- [ ] HTML first viewport is useful on phone.
- [ ] AI-heavy workflows are mapped to OOB tools.
- [ ] Limitations and open questions are explicit.

Before implementation:

- [ ] Use `oob-project-designer` mechanics.
- [ ] Create with HTML files by default.
- [ ] Add `PROJECT_SOUL.md` immediately.
- [ ] Do not create React dev-server dependency.
- [ ] Do not include source product assets.

---

## 10. Output Template

Use this for analysis-only replies.

````markdown
## Source Summary

<What the source is and what it does.>

## Distilled Capability

<What OOB should preserve.>

## Keep / Drop

| Keep | Why |
|---|---|
|  |  |

| Drop | Why |
|---|---|
|  |  |

## OOB Project Concept

- Name:
- Primary user:
- Primary entity:
- Primary action:
- First screen:

## PROJECT_SOUL.md Draft

```markdown
...
```

## Data Model

| Field | Type | Required | Notes |
|---|---|---|---|

## APIs

| API ID | Executor | Inputs | UI Trigger |
|---|---|---|---|

## HTML UI / Interaction

<First viewport, controls, states, local pages.>

## AI Backend Workflows

| Workflow | Tools | Write-back |
|---|---|---|

## Risks / Questions

- <Risk or question>
````

For build requests, implement first, then summarize only the high-signal parts.
