---
name: oob-native-workbench
description: Build OOB-native workbench projects, pages, tools, and data flows. Use when the user asks to create a mobile project, DIY page, frontend/backend binding, or on-device workbench experience inside OOB.
---

# OOB Native Workbench

Use this skill when a task asks to build or modify an OOB-native project workbench, mobile DIY page, backend tool, or page-to-tool data flow.

The goal is to create vibe projects that feel like part of OOB. Do not default to standalone HTML files, random WebViews, project-list dashboards, or isolated generated apps unless the user explicitly asks for export.

## Project, Toolbox, And MCP Tools

An OOB Workbench Project is a persistent app-like instance that OOB owns end to end. Its active business APIs are mounted externally as a Toolbox through MCP:

- MCP Tools are the standard external callable surface. OOB exposes fixed tools such as `vlm_task`, `agent_run`, and `oob_project_*`, plus active Project dynamic tools.
- Toolbox is the product name for the active Project's mounted business tool collection.
- Dynamic Project tools use `<toolbox_id>.<api_slug>`, for example `quick_note.capture_ingest`.
- A Workbench Project owns business APIs, persistent data, generated frontend pages, workspace files, logs, and the OOB-native Flutter Display route that users interact with.
- Internally, Project business APIs are still registered in the Project API Registry and executed through `workbench_api_call`. Externally, MCP dynamic tools dispatch into the same executor with `caller=mcp_toolbox`.
- UI clicks and AI tool calls must hit the same Project API executor so the generated frontend and the agent share one backend and one data flow.
- The core job is to turn vibe coding output into something runnable, visible, persistent, and editable inside OOB: the skill defines the generation process, the Project links frontend/backend/data, Workspace stores editable files, and Flutter Display renders the generated frontend.

## Current OOB Runtime Capabilities

Prefer these existing OOB capabilities before adding new execution layers:

- Flutter-native pages, dialogs, cards, lists, forms, and route entries.
- `/workspace` as the user project space exposed to the built-in Alpine runtime.
- Agent tools for files, terminal execution, browser use, MCP, skills, schedules, memory, and Android VLM operation.
- MethodChannel bridges between Flutter pages and native Kotlin services.
- Chat/tool-call cards for streaming execution status and artifacts.
- Built-in skills under `.omnibot/skills` or bundled assets under `app/src/main/assets/builtin_skills`.

This works especially well in OOB because Android shows the real Flutter result on the same device where the sandbox, Workspace, MethodChannel, skills, and AI tools run. The generated frontend is therefore a visible, tappable, editable OOB-native surface instead of a detached HTML artifact.

Design the Project as a live OOB applet, not as a large package manager page. The Project advantage is that OOB owns the full loop: Prompt/Skill creates the Project, Project registry binds frontend/backend/data, Flutter renders the current Display, AI and UI call the same Project API executor, and Workspace keeps editable source specs. A normal package is mainly a distributable dependency with code and metadata; an OOB Project is a running instance with active Display state, persistent data, API call logs, and an Agent toolbox manifest. It can be exported like a package, but inside OOB it behaves like a small live application.

Use Python package structure only as an analogy for organization: `project.json` is like `pyproject.toml`, Project API and Display declarations are like entry points, `projectId` acts like a namespace, Workspace editing resembles editable install, and Project export resembles a wheel/sdist. Do not copy package semantics blindly; the purpose is to keep vibe coding output visible, callable, editable, and persistent inside the OOB Android sandbox.

## Required Shape

Workbench creation has two separate interfaces:

```text
OOB Workbench Control API -> creates/opens/reads projects
Project API Registry      -> stores only project business APIs
MCP Toolbox               -> mounts active Project business APIs as dynamic MCP tools
```

Control APIs are built into OOB and must not appear in the Project API panel:

```text
workbench_project_create
workbench_project_list
workbench_project_get
workbench_project_open
workbench_project_activate
workbench_project_active_get
workbench_project_deactivate
workbench_project_export
workbench_project_delete
workbench_project_hot_update
workbench_project_ingest_android
workbench_project_ingest_oss
workbench_project_progress_get
workbench_api_list
workbench_api_call
```

Project APIs are the business actions owned by the created project. Todo is only the first demo template:

```text
todo.add
todo.finish
```

The v1 quick-capture demo is available only when the user explicitly asks to create a Project for 随手记 / Inbox / quick capture:

```text
capture.ingest
capture.archive
capture.promote_to_todo
capture.summarize
```

For normal prompt-generated vibe projects, prefer `templateId=schema_app`. A schema Project stores a generated Flutter display contract plus business APIs such as:

```text
note.create
note.archive
expense.create
expense.archive
habit.create
habit.archive
```

The actual ids should match the project entity. If the prompt needs custom backend verbs, pass explicit `apis` to `workbench_project_create` with `apiId/displayName/inputSchema/outputSchema/executorKind`.

Persist generated project assets under the shared workspace:

```text
/workspace/projects/registry.json
/workspace/projects/api_registry.json
/workspace/projects/<project-id>/
  README.md
  project.json
  frontend/page_spec.json
  backend/api_spec.json
  data/todos.json
  data/items.json
  logs/api_calls.jsonl
  logs/link_fetch.jsonl
  logs/script_runs.jsonl
  logs/hot_updates.jsonl
  android/manifest.json
  android/apps/<asset-id>/
  logs/android_ingest.jsonl
  source/manifest.json
  source/repos/<source-id>/
  logs/oss_ingest.jsonl
  logs/project_progress.jsonl
```

`registry.json` stores projects. `api_registry.json` stores business APIs only. `api_calls.jsonl` records AI calls, UI clicks, and MCP Toolbox calls.

The project directory separates editable source specs from runtime state:

- `README.md` explains what the project is, how it is displayed, and where its APIs/data live.
- `frontend/page_spec.json` is the generated frontend contract. It describes the OOB Flutter Display route, renderer, visible controls, state bindings, and which Project API each control calls. It is not standalone HTML.
- `backend/api_spec.json` is the backend Tool Contract. It declares business API ids, MCP `toolName`, `apiVersion`, schemas, executor kind, capabilities, side effects, data/log files, examples, runtime metadata, source refs, persistence files, frontend binding, and AI usage. Todo uses the native todo executor; schema projects use the generic native collection executor; `workspace_python_script` is the stable script boundary for Project APIs that will later run through Bridge/Alpine while keeping the same internal `workbench_api_call` path and external Toolbox tool name.
- `data/` and `logs/` are runtime state shared by AI and UI.
- `android/` stores APK files or Android project source snapshots imported through the Workbench control plane. This is how OOB can "eat" an existing Android app/project into a vibe Project without turning the import itself into a business API.
- `source/` stores OSS/GitHub source snapshots imported through the Workbench control plane. URL-only imports are marked `requiresFetch=true` until terminal/tool fetch places a local repo path for analysis.
- `logs/project_progress.jsonl` is the Project runtime progress stream. Creation, Android ingest, OSS ingest, and future executor preparation append structured progress rows; query it with `workbench_project_progress_get`.

## Home Input Operations

Project operations are driven by Agent understanding from the Home composer. Do not add a second input box, a local command parser, or a Workspace file-controller command path.

Supported Home prompts include:

```text
帮我创建一个随手记的 project，要求可以收文本、链接、摘要和归档
create project: 做一个客户跟进系统，可以新增客户，归档客户
create project: 做一个 todolist，可以增加todo，归档todo
delete project oob-workbench-todo-log
open project oob-workbench-todo-log
export project oob-workbench-todo-log
记一下：明天问张三报价
给当前 todo project 增加一条 todo：验证输入框直接调用 Project API
把当前 Todo 前端的归档按钮改得更明显
```

Routing rules:

1. Creation goes through `workbench_project_create`; never hand-write `registry.json` or `api_registry.json`.
2. Project creation is a deliberate action. Only call `workbench_project_create` when the user explicitly says to create/new/build a Project or workbench. A plain capture command such as `记一下：...`, `总结这个链接`, or `归档这条` must never create a Project by itself.
3. Listing, reading, opening, exporting, and deleting go through `workbench_project_list/get/open/export/delete`.
4. `delete project <explicit projectId>` may call `workbench_project_delete` directly. If the user only says `delete project`, call `workbench_project_list` first; with multiple Projects ask the user to specify, and with only one active/available Project ask for confirmation before deleting.
5. In-app Agent business operations go through `workbench_project_active_get` when context is unclear, then `workbench_api_list(projectId)` followed by `workbench_api_call(projectId, apiId, inputs)`. External MCP clients should use active Toolbox dynamic tools such as `quick_note.capture_ingest`. Do not edit `data/*.json` by hand. If there is no active Project, ask the user to explicitly create or select one first.
6. Frontend/backend edits prefer the active Project from the injected prompt context. If the user names a `projectId`, use that explicit Project.
7. Hot updates from Home input, floating Xiaowan, drawing annotations, or VLM screen input go through `workbench_project_hot_update`.

## Display Rules

1. Build OOB-native Flutter UI first.
2. Use the existing theme palette through `context.omniPalette`.
3. Use OOB localization; user-visible strings must come from `context.l10n.*`.
4. The generated frontend should render as its own OOB-native page, dialog, or bottom sheet. It is not the same thing as the OOB Project control surface.
5. Keep the OOB Project control surface minimal: the `/workbench/projects` landing page should only show the current active Project plus a compact Project list with short summaries. Opening Displays, read-only business API execution counts, imported assets, Workspace, export, and delete belong in the tapped Project detail view. Avoid turning the landing page into a second chat input, tutorial, or full CRUD project manager for small demos.
6. Prefer compact panels, list tiles, forms, segmented controls, and tool status rows that match existing OOB screens.
7. Only use WebView or HTML when the user explicitly asks for web export or when the content is already web-native.
8. Put file inspection and editing in Workspace instead of exposing long registry/data/log paths in the default Project UI.
9. Keep Workbench mode separate from existing interaction pages. Home chat input must not show a Project popup button or active Project chip. The top island keeps the two-way Chat / Workspace slider; Project is a separate top Workbench button using the Workbench tool mark, and toggles the Workspace container between file browsing and the active Project frontend. The top island should stay on the surface switcher by default instead of auto-collapsing into the model name; model/tools are temporary vertical-swipe layers. The drawer shortcut should return to Home and open the Project surface directly.
10. Treat the Home top Workbench button as the primary frontend entry. It should show the active Project's current Flutter Display inside the Workspace container like a browser child window, with exactly four compact actions above it: Project manager, info, fullscreen display, and refresh. Do not put a Work/Project chip inside Workspace content, do not add Project as a third horizontal page in the slider, do not show a large "Workspace / Project" identity block, and do not list all Project internals in this primary surface. Keep `/workbench/projects` as the simplified Project list: current active Project at the top, compact Project rows below, and deeper Displays/API/Workspace/export/delete details only after tapping a Project.
11. Keep the Project Display guide as a compact info popup, not a full tutorial page. It should explain what a Project binds, how Flutter Displays call Project APIs, where data/logs persist, and how to extend backend tools through Workbench registration.
12. Debug is not a Workbench-wide mode. It belongs to the currently displayed Flutter Display: floating Xiaowan, drawing annotations, and VLM input may attach the current frontend context, then call `workbench_project_hot_update`. Do not add a second chat box inside the Workspace Project launcher, Workbench manager, or generated frontend. A hot update still persists `logs/hot_updates.jsonl`, refreshes the current Project payload, and keeps Project business APIs in `workbench_api_list` unchanged.
13. Drawing on top of a generated frontend should be treated as context capture for iteration. The overlay may produce vector strokes and selected regions, but those values must travel in `frontendContext` rather than becoming Project business APIs.
14. Workspace entry restores the last cached Workspace container first: file browsing reopens the last valid directory under `/workspace`, and Project Display mode reopens the active Project frontend. Do not force Workspace entry back to a fixed file root or Project page unless the user explicitly toggles the top Workbench mark.

## Backend Rules

1. Create or register projects only through `workbench_project_create`; do not directly write registry files from the AI layer.
2. Define project backend actions as stable business API ids in the Project API Registry.
3. Call project business APIs only through `workbench_api_call`, whether the caller is AI or a generated Flutter Display.
4. Keep tool execution behind native service/controller boundaries, not inside widget callbacks.
5. For shell work, prefer the existing terminal tool/runtime.
6. For file changes, prefer existing workspace file tooling.
7. For Android UI work, use the VLM operation path only when the task truly needs phone-screen automation.
8. Opening an existing project should read it first. Do not overwrite existing project config or original attributes just because a page is opened.
9. Deleting a project must use `workbench_project_delete`; do not delete registry files or project directories by hand. Treat delete as an OOB control-plane action, not as a business API.

## Prompt Decomposition Workflow

When a user opens Project generation mode and asks for a new vibe project, split the work before calling tools:

1. Product surface: define the generated frontend route and the main user view. For normal projects use `/workbench/schema_app?projectId=<id>`, an OOB-native Flutter schema display, not HTML or WebView. Use `/workbench/todo_log?projectId=<id>` only for the Todo demo.
2. Project identity: choose a stable `projectId`, display name, and template. Use `templateId=schema_app` unless the user explicitly asks for the Todo demo.
3. Backend APIs: map user verbs to Project APIs. “新增客户” can map to `customer.create`; “归档客户” can map to `customer.archive`. If the project needs custom verbs, pass explicit API specs in `apis`.
4. Data flow: specify that both AI and UI write through `workbench_api_call` and persist to Project data files (`data/items.json` for schema projects), while every call appends to `logs/api_calls.jsonl`.
5. Frontend binding: bind generated controls in `frontend/page_spec.json` to the Project API ids.
6. Frontend viewport: expose the generated Display from the Home top Workbench button so the user can tap the Workbench mark beside the Chat / Workspace slider and immediately see the active Project's current frontend in an embedded child window. Keep the `/workbench/projects` landing page to the active Project plus compact Project rows; put Display selection, delete/export, Workspace entry, and API stats in the tapped Project detail view. Do not mix those controls into the generated frontend. The Project info popup should teach this boundary without adding a second creation flow.
7. Existing projects: call `workbench_project_list` to inspect registered projects and `workbench_project_get` before opening or mutating a specific project.
8. Debug context: when the user asks to change the current frontend through floating Xiaowan, drawing overlay, or a VLM prompt, attach a `frontendContext` object to `workbench_project_hot_update`. Include `projectId`, `displayId`, `route`, visible state, selected element/control if any, `selectedRegion`, `drawingPaths`, and screenshot or VLM summary when available.
9. Execution: call `workbench_project_create`, then `workbench_project_activate`, then call `workbench_api_call` for requested initial state, and finally call `workbench_project_open` when the user should see the generated frontend. Do not fake API execution by writing data files directly.

The recommended user flow is: use the Home chat input for the requirement prompt, tap the top Workbench mark to view the active Project Display directly in the Project child window, and tap the same button again to return that container to Workspace file browsing. This skill decomposes the prompt, creates the Project through `workbench_project_create`, activates it through `workbench_project_activate`, initializes state through `workbench_api_call` when needed, and can open the generated frontend with `workbench_project_open`. If a generated prompt project already exists, choose a new stable suffix such as `oob-workbench-customer-tracker-2` instead of overwriting the existing Project. The secondary `/workbench/projects` page is only a compact Project list plus active Project indicator; tapping a row opens details, where activation makes it the current Agent toolbox for future Home-input messages. It does not create a new conversation by itself.

The decomposition should be visible in the Project files (`README.md`, `frontend/page_spec.json`, `backend/api_spec.json`) and should stay compatible with future templates that add more APIs.

## Extending Backend Tools

When the user asks to add backend capability to a Workbench Project, treat it as adding a Project business API. The MCP dynamic tool is derived from that API after the Project is active; do not hand-register a separate one-off MCP tool for a Project business action. The prompt or implementation plan must specify:

```text
apiId: stable business id, for example note.create
displayName: user-visible API name
inputSchema: keys and expected value types
outputSchema: returned objects or status fields
executorKind: native_template | workspace_script | provider_http | future custom kind
persistence: project files the API reads/writes
frontendBinding: which Flutter Display control calls this API
aiUsage: when the AI layer should call this API
toolbox: derived MCP tool name in <toolbox_id>.<api_slug> form
```

Required workflow:

1. Read the current project with `workbench_project_get` or open it through `/workbench/projects`.
2. Decide whether the API belongs to an existing template or requires a new template/executor kind.
3. Register the business API through the Workbench project creation/update path. Do not append to `api_registry.json` by hand and do not add a parallel fixed MCP tool for the same action.
4. Implement execution behind the native Workbench executor or an approved workspace/provider executor boundary.
5. Bind the generated frontend control to `workbench_api_call(projectId, apiId, inputs)`.
6. Keep AI calls and UI clicks on the same API path and the same persistent data files.
7. Add a focused mock or native test that calls the API or its dynamic MCP Toolbox name and verifies persisted state and `api_calls.jsonl`.

Current v1 boundary: `todo_log_demo` remains a regression demo. `schema_app` supports generic create/archive collection projects through `native_schema_collection`. `quick_capture_inbox` is the first daily-use validation template and registers `capture.ingest`, `capture.archive`, `capture.promote_to_todo`, and `capture.summarize` with `executorKind=workspace_python_script`.

For this pass, `workspace_python_script` is a stable Project API contract with a native-backed executor so the v1 path is reliable. OOB also writes editable SDK/script artifacts under `backend/oob_sdk/` and `backend/scripts/` so a later BridgeServer, DSL compiler, or real Python sandbox executor can replace the native implementation without changing `workbench_api_call`, Display bindings, data paths, or API ids. If the requested backend tool is outside the current template actions, explicitly state the new API contract first, pass it as a Project API spec, and extend the native Workbench runtime or an approved executor boundary before using it in the generated frontend.

## Minimal Project Workflow

1. Call `workbench_project_list` when the user asks to manage existing projects.
2. Call `workbench_project_get` before opening, exporting, deleting, or extending an existing project.
3. Call `workbench_project_create` with a project id and template/config.
4. Call `workbench_project_activate(projectId)` so Home input and the Workbench mark use this Project as the current toolbox.
5. Let OOB create `/workspace/projects/registry.json` and the project directory.
6. Let OOB register the project business APIs into `/workspace/projects/api_registry.json`.
7. Render the lightweight Project list from project state: one active Project summary and compact rows for all Projects. Render per-Project details from `workbench_api_list(projectId)` only after the user opens that Project; those API stats stay read-only, and business APIs execute from the generated frontend or AI layer.
8. Use `workbench_api_call(projectId, apiId, inputs)` from both AI and UI.
9. Render generated frontend output in a separate OOB-native route/page and bind it to the same Project API Registry.
10. Open the project with `workbench_project_open`.
11. Export a distributable project package with `workbench_project_export` when the user asks to register or share the project.
12. Delete a project with `workbench_project_delete` only after explicit user confirmation.
13. Hot update a project with `workbench_project_hot_update` after reading the current Project. Pass `frontendContext` when the prompt comes from a generated frontend, floating Xiaowan, or VLM input. Treat hot update as a Workbench control-plane action; it may internally call registered business APIs but must not appear in Project API Registry.
14. Import an Android APK or Android project source with `workbench_project_ingest_android(projectId, sourcePath, sourceKind?)` only after the Project exists. This writes `android/manifest.json`, copies the asset under `android/apps/<asset-id>/`, and appends `logs/android_ingest.jsonl`; it does not install the APK into Android OS and does not appear in `workbench_api_list`.
15. Import a GitHub/OSS repo with `workbench_project_ingest_oss(projectId, sourceUrl?, sourcePath?, sourceKind?, ref?)` only after the Project exists. If only `sourceUrl` is available, OOB records `requiresFetch=true` metadata and does not claim the repo is downloaded. After terminal/tool fetch, call the same API with `sourcePath` to copy the snapshot under `source/repos/<source-id>/`, detect package files/entrypoints, and append `logs/oss_ingest.jsonl`. This is a Workbench control API, not a Project business API.
16. Query Project creation/import progress with `workbench_project_progress_get(projectId?, limit?)` instead of parsing logs manually.
17. Add focused service/runtime tests before broad UI work.

## Toolvox / VLM Validation Boundary

The local MCP surface exposes `vlm_task`, `agent_run`, `task_status`, `task_reply`, `task_wait_unlock`, and `file_transfer`. Workbench control APIs are internal Agent tools; they are not directly exposed as MCP tools and must not be added to Project API Registry.

For toolvox-style validation, use `vlm_task` when the test is visual device operation through OOB Home. Use `agent_run` when the test is the normal OOB Agent/tool path and Flutter Home text entry is not the thing being tested. The in-app Agent then calls:

```text
workbench_project_create
workbench_project_activate
workbench_api_list
workbench_api_call
workbench_project_progress_get
```

Only call a Project creation run successful after `workspace/projects/<project-id>/project.json` and `logs/project_progress.jsonl` exist on the target device. If the device has no model provider configured, record the run as blocked instead of hand-writing files.

For deterministic backend/runtime proof when model-provider setup is not the
test subject, use the authenticated Dashboard debug route
`POST /mcp/workbench/call` with the local MCP/Dashboard bearer token. This route
is not returned by MCP tool discovery and must never be added to the Project API
Registry. It calls the same `WorkbenchProjectStore` methods; `workbench_project_open`
also navigates OOB native UI through `TaskCompletionNavigator` on the Android
main thread, so the device should visibly show the generated Flutter Display
after a successful open.

For local VLM/provider E2E setup, the same debug route supports
`debug_model_provider_configure` and `debug_model_provider_get`. These write the
normal OOB model provider profile and scene bindings, sync Agent AI config, and
must mask the key as `apiKeyConfigured` in responses.

Known successful native proof on `emulator-5554`: create
`projectId=oob-workbench-quick-capture` with `templateId=quick_capture_inbox`,
activate it, seed one item through `workbench_api_call(capture.ingest)`, then
open it. The screen should show `随手记 Inbox · NOTE`, `3 active / 0 archived`,
`OOB native UI`, and `4 APIs`, with runtime files under
`workspace/projects/oob-workbench-quick-capture/`.

Latest native proof on `emulator-5554`: create
`projectId=oob-workbench-vlm-quick-note` with `templateId=quick_capture_inbox`,
activate it, seed two items through `workbench_api_call(capture.ingest)`, then
open it. After the final native UI smoke, the screen should show
`随手记 Inbox · NOTE`, `3 active / 1 archived`, `OOB native UI`, and `4 APIs`,
with a receipt/invoice item classified as `Summary`, and runtime files under
`workspace/projects/oob-workbench-vlm-quick-note/`.

Known VLM blocker: after model provider binding, `vlm_task` can reach the VLM
and click OOB Home, but Project creation through Home input is not proven until
the prompt is actually submitted. On `emulator-5554`, Flutter input exposed no
focused editable accessibility node, and app-process `input text/keyevent` was
denied `INJECT_EVENTS`; record this as a text-entry blocker, not a successful
toolvox Project creation.

`agent_run` submits the prompt into the same `AgentRunService.startConversationRun`
path used by WebChat/Home. It creates or reuses an OOB conversation and returns
an accepted `taskId` plus `conversationId`. It is not allowed to call
`WorkbenchProjectStore` directly; the Agent must invoke Workbench tools through
its normal tool registry. Only mark the run successful when the requested
Project files exist under `workspace/projects/<project-id>/`.

## Distribution Export

Use `workbench_project_export` to package a Project. Do not manually zip the workspace from the AI layer.

The package is written under:

```text
/workspace/projects/exports/<project-id>-<timestamp>.zip
```

The zip includes:

```text
manifest.json
registry/project_record.json
registry/api_records.json
project/README.md
project/project.json
project/frontend/page_spec.json
project/backend/api_spec.json
project/data/*
project/android/*
project/logs/*
skills/oob-native-workbench/SKILL.md
```

Export is a Workbench control capability, not a Project business API. It must not appear in the Project API panel and must not be listed by `workbench_api_list`.

## Generic E2E Example

Todo is not the product scope. Use a schema Project when the user asks for a normal vibe app.

```text
Project: oob-workbench-customer-tracker
Template: schema_app
Entity: Customer
Project APIs:
  - customer.create
  - customer.archive
Display:
  - Home top Workbench button toggling the Workspace container into the active Customer Tracker Display
  - Secondary Project control page at /workbench/projects for switching, exporting, deleting, and API execution counts
  - Generated schema frontend page at /workbench/schema_app?projectId=...
State:
  - data/items.json
  - logs/api_calls.jsonl
```

## Quick Capture E2E Example

Only use this template when the user explicitly asks to create a quick-capture / 随手记 / Inbox Project. A plain business command like `记一下：明天问张三报价` must use the active Project's API and must not create a Project by itself.

```text
Project: oob-workbench-quick-capture
Template: quick_capture_inbox
Project APIs:
  - capture.ingest
  - capture.archive
  - capture.promote_to_todo
  - capture.summarize
Display:
  - Home top Workbench button toggling the Workspace container into `随手记 Inbox · NOTE`
  - Generated quick-capture frontend page at /workbench/quick_capture?projectId=...
State:
  - data/items.json
  - logs/api_calls.jsonl
  - logs/link_fetch.jsonl
  - logs/script_runs.jsonl
Workspace artifacts:
  - backend/api_spec.json
  - backend/oob_sdk/
  - backend/scripts/capture_*.py
```

Recommended prompt:

```text
帮我创建一个随手记的 project，要求可以收文本、链接、摘要和归档。
创建后激活它；如果需要初始化内容，请先 workbench_api_list，再用 capture.ingest 写入示例，最后打开随手记 Inbox 前端。
```

## Legacy Demo Template

`todo_log_demo` is only a legacy test/demo template. It can stay available for regression tests, but normal prompt-generated projects should use `schema_app` and keep the same split: OOB control API creates the project, and the project registers its own business APIs.

```text
Project: oob-workbench-todo-log
Template: todo_log_demo
Project APIs:
  - todo.add
  - todo.finish
Display:
  - Home top Workbench button toggling the Workspace container into the active Todo Display
  - Secondary Project control page at /workbench/projects for Project switcher, Project information, and Project APIs from Project API Registry with execution counts
  - Workspace Work mode for editing project files
  - Generated Todo frontend page at /workbench/todo_log?projectId=...
  - Todo list on the generated frontend
```

Use this template to verify that persistent project state, AI-native API calls, and generated Flutter frontend clicks share the same backend executor and data files.

## E2E Prompt

```text
用 oob-native-workbench 创建一个 OOB 原生 Workbench Project。不要生成 HTML，不要使用 WebView，Flow 暂时不用做。

请调用 OOB 的 workbench_project_create 接口，使用 schema_app 模板创建 projectId=oob-workbench-customer-tracker。
Project 名称是“客户跟进工作台”，entityName=Customer，description=“记录客户跟进、下一步动作和归档状态”。
创建后注册或使用两个 Project API：customer.create 和 customer.archive。
然后调用 workbench_project_activate 激活这个 Project。
调用 customer.create 添加“张三：下周二回访”。
调用 customer.create 添加“李四：发送报价单”。
调用 customer.archive 归档第一条客户记录。
最后打开这个 Project 的 OOB 原生生成前端，让我看到客户跟进列表；Project 管理页里的 Project API 区域只读显示 customer.create / customer.archive 的已执行次数。
```

## Verification

Run the focused Flutter test for the demo service:

```bash
cd ui
flutter test test/workbench_todo_log_service_test.dart
```

When a JDK is available, run the focused Android unit test:

```bash
env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ANDROID_HOME=/Users/wuzewen/Library/Android/sdk \
  ./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests '*Workbench*' \
  --tests '*AgentToolDefinitions*' \
  -Ptarget=lib/main_standard.dart
```

For live UI smoke, open Home and verify the top island only has the Chat / Workspace two-way slider. Tap Workspace and verify it restores the last cached Workspace container and directory. Tap the separate top Workbench mark and verify the active schema Display is hosted inline as a child window with exactly four toolbar actions: Project manager, info, fullscreen display, and refresh. Tap the Workbench mark again and verify it returns to file browsing, then reopen Workspace and verify that cached file browser state is preserved. Home input should not show a Project popup button or active Project chip. If you open the `/workbench/projects` manager from the first toolbar action, verify it opens the current Project and shows switching, Displays, read-only `<entity>.create` / `<entity>.archive` execution counts, Workspace, export, and delete. The generated frontend must only show the business UI, not the Project control API panel or Workspace entry.

For hot-update smoke, activate a Project, open its Display, then submit an edit through Home input, floating Xiaowan, or VLM input with `frontendContext` that names the current `projectId`, `displayId`, route, visible state, and selected control/screenshot summary. Verify the agent uses `workbench_project_hot_update` or the relevant Project APIs. The generated frontend debug banner should expose the context boundary, and `workbench_api_list` must still return only Project business APIs, not Workbench control APIs.

For distribution smoke, export the project from `/workbench/projects` or call `workbench_project_export`, then verify `/workspace/projects/exports/<project-id>-<timestamp>.zip` contains the manifest, registry records, `project/README.md`, `frontend/page_spec.json`, `backend/api_spec.json`, data, logs, and this skill.

For Android ingest smoke, create/open a Project first, then call:

```text
workbench_project_ingest_android(
  projectId=<project-id>,
  sourcePath=/workspace/apps/demo.apk
)
```

or pass an Android project directory with `sourceKind=android_project`. Verify the Project payload contains `androidAssets`, `android/manifest.json` exists, `logs/android_ingest.jsonl` has one row, and `workbench_api_list(projectId)` still returns only business APIs such as `todo.add` and `todo.finish`.
