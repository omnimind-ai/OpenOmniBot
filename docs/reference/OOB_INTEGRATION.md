# OOB Workbench Integration

This note defines the current Workbench boundary used by OOB Project creation, display, and iteration.

## Product Role

OOB Workbench is an AI-product display layer. It is not the product being generated. Its job is to make an Agent result, workflow state, report, or dataset visible inside OOB, let the user interact with it, and let the Agent update the same surface quickly.

The target loop is:

```text
AI output -> user sees it -> user says one thing -> AI updates it -> user sees it
```

## Project Contract

A Workbench Project is a persistent container with:

- `projectId`: stable id for the container.
- Project Tools: stable business actions shared by UI clicks, Agent calls, and MCP dynamic tools.
- `initialItems`: optional structured state stored in `data/items.json`.
- `htmlFiles`: optional HTML/CSS/JS files stored under `frontend/html/`.
- `flutterFiles`: optional limited Dart files stored under `frontend/flutter/`.
- Runtime files: `project.json`, `frontend/page_spec.json`, `backend/api_spec.json`, logs, and export metadata.

The creation path is one generic control API:

```text
workbench_project_create(projectId, name, prompt, entityName, initialItems, apis, htmlFiles, flutterFiles)
```

Project operations remain Workbench control APIs:

- `workbench_project_list`
- `workbench_project_get`
- `workbench_project_open`
- `workbench_project_activate`
- `workbench_project_active_get`
- `workbench_project_deactivate`
- `workbench_project_update`
- `workbench_project_hot_update`
- `workbench_project_export`
- `workbench_project_delete`
- `workbench_project_progress_get`
- `workbench_project_ingest_oss`
- `workbench_api_list`
- `workbench_api_call`

Project Tools are not Workbench control APIs. They are business actions owned by a Project.

## Renderer Boundary

The right-side Workspace host dispatches by renderer:

- `html_webview`: loads `/workbench/html?projectId=<id>` and serves `frontend/html/`.
- `oob_project_display`: renders generic structured data and actions through Flutter.
- `flutter_eval`: loads limited Project-owned Dart from `frontend/flutter/`.

HTML is the first-class path for rich reports, charts, comparison views, dashboards, and fast visual iteration. The page can call OOB only through the bridge:

```js
await window.oob.callApi(apiId, inputs)
await window.oob.getProject()
window.oob.selectElement(payload)
```

Native/mobile capability must stay behind registered Project Tools. HTML must not receive arbitrary Android, filesystem, shell, or network bridges.

The default Project Display is a fallback for structured state and common actions: lists, forms, buttons, text, and status. It is a generic renderer, not a named product concept.

`flutter_eval` is supplemental. It is useful only when HTML and the default Project Display are insufficient and the runtime limits are acceptable.

## Display Rules

Generated Displays are application surfaces, not Project summary cards. The first viewport should show the AI output or workflow state itself: records, status, filters, forms, evidence, summaries, and business actions.

Visible Displays must not expose control-plane details such as Project ids, Toolbox names, API counts, executor kinds, Workspace paths, data/log paths, source spec paths, progress rows, or implementation badges. Those details belong in `/workbench/projects`, the info popup, MCP resources, logs, or developer documentation.

The Home Project surface embeds the active Project Display as a child window. Display navigation stays inside the right-side Workbench Project surface so switching pages never replaces the left Home conversation. The Project manager is a secondary control surface for activation, display selection, Workspace access, export, delete, and debug details.

## Iteration Boundary

Feature changes update the same Project:

1. `workbench_project_hot_update` records the user request and current `frontendContext`.
2. If HTML sources exist, the Agent should edit the smallest relevant HTML/CSS/JS file and call `workbench_project_update(htmlFiles=...)`.
3. If Flutter sources exist, the Agent rewrites the full supported Dart file and calls `workbench_project_update(flutterFiles=...)`.
4. If only structured state is involved, the Agent calls existing Project Tools through `workbench_api_call`.

Adding a reusable backend action means extending the Project Tool contract and updating `backend/api_spec.json` through `workbench_project_update`, not creating a replacement Project.

## MCP Boundary

External MCP clients see:

- fixed OOB MCP tools for control operations and Agent entry points
- dynamic Project Toolbox tools for the active Project
- read-only MCP resources for Project manifest, active Project, Toolbox, progress, API logs, and source manifest
- reusable MCP prompts for common Workbench workflows

Dynamic Project tools use `<toolbox_id>.<api_slug>` names and dispatch to the same Project Tool executor with `caller=mcp_toolbox`.

## Source And Runtime Boundary

Project runtime files are owned by `WorkbenchProjectStore`:

- `frontend/html/`: editable WebView source
- `frontend/flutter/`: editable limited Dart source
- `frontend/page_spec.json`: display metadata and generic Project Display state
- `backend/api_spec.json`: Project Tool contract
- `data/items.json`: default structured state
- `logs/api_calls.jsonl`: business action audit log
- `logs/hot_updates.jsonl`: user-driven iteration log
- `logs/project_progress.jsonl`: creation/import/update progress
- `source/manifest.json`: imported source assets

OSS/GitHub URL ingest is metadata-only until source is fetched through an approved terminal/tool path. Local source snapshots are copied under `source/repos/<source-id>/`, analyzed, and recorded in `source/manifest.json`.
