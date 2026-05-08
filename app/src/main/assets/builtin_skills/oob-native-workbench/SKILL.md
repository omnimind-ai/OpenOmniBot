---
name: oob-native-workbench
description: Build OOB-native workbench projects, pages, tools, and data flows. Use when the user asks to create a mobile project, DIY page, frontend/backend binding, or on-device workbench experience inside OOB.
---

# OOB Native Workbench

Use this skill when a task asks to build or modify an OOB-native project workbench, mobile DIY page, backend tool, or page-to-tool data flow.

The goal is to create vibe projects that feel like part of OOB. Do not default to standalone HTML files, random WebViews, project-list dashboards, or isolated generated apps unless the user explicitly asks for export.

## Project vs MCP Tools

An OOB Workbench Project is not an MCP tool list. Treat it as a Skill-driven workflow plus app-like tool bundle that OOB owns end to end:

- MCP tools expose external callable capabilities to the agent. They usually do not define an OOB-native frontend, project state model, preview route, or editing surface.
- A Workbench Project owns business APIs, persistent data, generated frontend pages, workspace files, and the OOB-native Flutter Display route that users interact with.
- Project business APIs may feel like web-style tools, but they are registered in the Project API Registry and are called through `workbench_api_call`, not discovered as MCP tools.
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

## Required Shape

Workbench creation has two separate interfaces:

```text
OOB Workbench Control API -> creates/opens/reads projects
Project API Registry      -> stores only project business APIs
```

Control APIs are built into OOB and must not appear in the Project API panel:

```text
workbench_project_create
workbench_project_list
workbench_project_get
workbench_project_open
workbench_project_export
workbench_project_delete
workbench_project_hot_update
workbench_api_list
workbench_api_call
```

Project APIs are the business actions owned by the created project. For the first demo template they are:

```text
todo.add
todo.finish
```

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
  logs/api_calls.jsonl
  logs/hot_updates.jsonl
```

`registry.json` stores projects. `api_registry.json` stores business APIs only. `api_calls.jsonl` records both AI calls and UI clicks.

The project directory separates editable source specs from runtime state:

- `README.md` explains what the project is, how it is displayed, and where its APIs/data live.
- `frontend/page_spec.json` is the generated frontend contract. It describes the OOB Flutter Display route, visible controls, state bindings, and which Project API each control calls. It is not standalone HTML.
- `backend/api_spec.json` is the backend contract. It declares business API ids, schemas, executor kind, persistence files, frontend binding, and AI usage. In the current demo the real executor is OOB native Kotlin; future projects can replace the executor kind with a workspace script or provider executor while keeping the same `workbench_api_call` path.
- `data/` and `logs/` are runtime state shared by AI and UI.

## Display Rules

1. Build OOB-native Flutter UI first.
2. Use the existing theme palette through `context.omniPalette`.
3. Use OOB localization; user-visible strings must come from `context.l10n.*`.
4. The generated frontend should render as its own OOB-native page, dialog, or bottom sheet. It is not the same thing as the OOB Project control surface.
5. Keep the OOB Project control surface minimal: show Project switching, registered business APIs, API execution counts, the generated frontend entry, and a Workspace entry for editing files. Avoid turning it into a full CRUD project manager for small demos.
6. Prefer compact panels, list tiles, forms, segmented controls, and tool status rows that match existing OOB screens.
7. Only use WebView or HTML when the user explicitly asks for web export or when the content is already web-native.
8. Put file inspection and editing in Workspace instead of exposing long registry/data/log paths in the default Project UI.
9. Keep Workbench mode separate from existing interaction pages. A small Project shortcut may appear in the chat/VLM input area, drawer, or Workspace header, but it should only open `/workbench/projects`; it must not become an MCP/toolbox list or expose Project control APIs as business APIs.
10. Use `/workbench/projects` for the lightweight vibe editing/control surface and Project information, and `/workbench/todo_log?projectId=...` for the generated Todo frontend page.
11. Project pages may expose a Xiaowan floating assistant. A submitted prompt must call `workbench_project_hot_update`, persist `logs/hot_updates.jsonl`, refresh the current native Flutter display, and keep Project business APIs in `workbench_api_list` unchanged.

## Backend Rules

1. Create or register projects only through `workbench_project_create`; do not directly write registry files from the AI layer.
2. Define project backend actions as stable business API ids in the Project API Registry.
3. Call project business APIs only through `workbench_api_call`, whether the caller is AI or the Flutter Project API panel.
4. Keep tool execution behind native service/controller boundaries, not inside widget callbacks.
5. For shell work, prefer the existing terminal tool/runtime.
6. For file changes, prefer existing workspace file tooling.
7. For Android UI work, use the VLM operation path only when the task truly needs phone-screen automation.
8. Opening an existing project should read it first. Do not overwrite existing project config or original attributes just because a page is opened.
9. Deleting a project must use `workbench_project_delete`; do not delete registry files or project directories by hand. Treat delete as an OOB control-plane action, not as a business API.

## Prompt Decomposition Workflow

When a user opens Project generation mode and says something like “I want a simple todolist management system that can add todos and archive todos”, split the work before calling tools:

1. Product surface: define the generated frontend route and the main user view. For the current template this is `/workbench/todo_log?projectId=<id>`, an OOB-native Flutter page, not HTML or WebView.
2. Project identity: choose a stable `projectId`, display name, and template. For this request use `templateId=todo_log_demo`.
3. Backend APIs: map user verbs to Project APIs. “Add todo” maps to `todo.add`; “archive todo” maps to `todo.finish` in the current template because the persistent state uses `status=finished` for archived items.
4. Data flow: specify that both AI and UI write through `workbench_api_call` and persist to `data/todos.json`, while every call appends to `logs/api_calls.jsonl`.
5. Frontend binding: bind the add input/button to `todo.add`, and each open todo archive action to `todo.finish`.
6. Management surface: keep Project switching, delete, export, API execution counts, and Workspace editing in `/workbench/projects`; do not mix those controls into the generated frontend.
7. Existing projects: call `workbench_project_list` to inspect registered projects and `workbench_project_get` before opening or mutating a specific project.
8. Execution: call `workbench_project_create`, then call `workbench_api_call` for demo or requested initial state, and finally call `workbench_project_open`. Do not fake API execution by writing data files directly.

The recommended user flow is: tap the Project shortcut in the chat input area, open Project generation mode, enter a requirement prompt, let the skill decompose it, create the Project through `workbench_project_create`, initialize demo state through `workbench_api_call` when needed, then open the generated frontend from the primary action. If a generated prompt project already exists, choose a new stable suffix such as `oob-workbench-todolist-2` instead of overwriting the existing Project.

The decomposition should be visible in the Project files (`README.md`, `frontend/page_spec.json`, `backend/api_spec.json`) and should stay compatible with future templates that add more APIs.

## Extending Backend Tools

When the user asks to add backend capability to a Workbench Project, treat it as adding a Project API, not as adding an MCP tool. The prompt or implementation plan must specify:

```text
apiId: stable business id, for example note.create
displayName: user-visible API name
inputSchema: keys and expected value types
outputSchema: returned objects or status fields
executorKind: native_template | workspace_script | provider_http | future custom kind
persistence: project files the API reads/writes
frontendBinding: which Flutter Display control calls this API
aiUsage: when the AI layer should call this API
```

Required workflow:

1. Read the current project with `workbench_project_get` or open it through `/workbench/projects`.
2. Decide whether the API belongs to an existing template or requires a new template/executor kind.
3. Register the business API through the Workbench project creation/update path. Do not append to `api_registry.json` by hand.
4. Implement execution behind the native Workbench executor or an approved workspace/provider executor boundary.
5. Bind the generated frontend control to `workbench_api_call(projectId, apiId, inputs)`.
6. Keep AI calls and UI clicks on the same API path and the same persistent data files.
7. Add a focused mock or native test that calls the API and verifies persisted state and `api_calls.jsonl`.

Current demo limitation: `todo_log_demo` only includes `todo.add` and `todo.finish`. If the requested backend tool is outside that template, explicitly state the new API contract first, then extend the native Workbench runtime/template before using it in the generated frontend.

## Minimal Project Workflow

1. Call `workbench_project_list` when the user asks to manage existing projects.
2. Call `workbench_project_get` before opening, exporting, deleting, or extending an existing project.
3. Call `workbench_project_create` with a project id and template/config.
4. Let OOB create `/workspace/projects/registry.json` and the project directory.
5. Let OOB register the project business APIs into `/workspace/projects/api_registry.json`.
6. Render the lightweight Project control surface from `workbench_api_list(projectId)` and project state, including per-API execution counts.
7. Use `workbench_api_call(projectId, apiId, inputs)` from both AI and UI.
8. Render generated frontend output in a separate OOB-native route/page and bind it to the same Project API Registry.
9. Open the project with `workbench_project_open`.
10. Export a distributable project package with `workbench_project_export` when the user asks to register or share the project.
11. Delete a project with `workbench_project_delete` only after explicit user confirmation.
12. Hot update a project with `workbench_project_hot_update` after reading the current Project. Treat hot update as a Workbench control-plane action; it may internally call registered business APIs but must not appear in Project API Registry.
13. Add focused service/runtime tests before broad UI work.

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
project/logs/*
skills/oob-native-workbench/SKILL.md
```

Export is a Workbench control capability, not a Project business API. It must not appear in the Project API panel and must not be listed by `workbench_api_list`.

## First Template Example

`todo_log_demo` is only the first template example. Future prompt-generated projects should keep the same split: OOB control API creates the project, and the project registers its own business APIs.

```text
Project: oob-workbench-todo-log
Template: todo_log_demo
Project APIs:
  - todo.add
  - todo.finish
Display:
  - Project control page at /workbench/projects
  - Project switcher, Project information, and Project APIs from Project API Registry with execution counts
  - Workspace entry for editing project files
  - Generated Todo frontend page at /workbench/todo_log?projectId=...
  - Todo list on the generated frontend
```

Use this template to verify that persistent project state, AI-native API calls, and Flutter Project API panel clicks share the same backend executor and data files.

## Demo Prompt

```text
用 oob-native-workbench 创建一个 OOB 原生 Workbench Project。不要生成 HTML，不要使用 WebView，Flow 暂时不用做。

请调用 OOB 的 workbench_project_create 接口，使用 todo_log_demo 模板创建 projectId=oob-workbench-todo-log。
创建后注册两个 Project API：todo.add 和 todo.finish。
然后调用 todo.add 添加“验证 AI 层原生调用 API”。
调用 todo.add 添加“验证前端 Project API 点击同一 API”。
调用 todo.finish 完成第一条 todo。
最后打开这个 Project 的 OOB 原生生成前端，让我看到 Todo 列表；Project 编辑页里的 Project API 面板应显示 todo.add / todo.finish 的已执行次数。
```

## Verification

Run the focused Flutter test for the demo service:

```bash
cd ui
flutter test test/workbench_todo_log_service_test.dart
```

When a JDK is available, run the focused Android unit test:

```bash
./gradlew :app:testDevelopDebugUnitTest --tests '*Workbench*'
```

For live UI smoke, open `/workbench/projects` and verify the Project API panel shows `todo.add` / `todo.finish` execution counts. Then open `/workbench/todo_log?projectId=...` and verify it only shows the generated Todo frontend, not the Project control API panel or Workspace entry.

For hot-update smoke, open either `/workbench/projects` or `/workbench/todo_log?projectId=...`, tap the Xiaowan floating button, submit a change prompt, and verify the page refreshes from persisted Project state while `workbench_api_list` still returns only `todo.add` / `todo.finish`.

For distribution smoke, export the project from `/workbench/projects` or call `workbench_project_export`, then verify `/workspace/projects/exports/<project-id>-<timestamp>.zip` contains the manifest, registry records, `project/README.md`, `frontend/page_spec.json`, `backend/api_spec.json`, data, logs, and this skill.
