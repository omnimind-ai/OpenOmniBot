# OOB Workbench Backend Runtime

This document describes the current backend runtime for OOB Workbench Projects.

## Runtime Shape

`WorkbenchProjectStore` owns Project persistence under `/workspace/projects`.

Each Project package contains:

- `project.json`: canonical Project payload for UI, MCP resources, export, and debugging.
- `frontend/page_spec.json`: display metadata for the right-side host.
- `frontend/html/`: optional HTML/CSS/JS source for the `html_webview` renderer.
- `frontend/flutter/`: optional limited Dart source for `flutter_eval`.
- `backend/api_spec.json`: Project Tool contract.
- `data/items.json`: generic structured Project state.
- `logs/api_calls.jsonl`: Project Tool call audit log.
- `logs/hot_updates.jsonl`: user iteration log.
- `logs/project_progress.jsonl`: creation, update, and ingest progress.
- `source/manifest.json`: imported source assets.

The registry files are:

- `/workspace/projects/registry.json`
- `/workspace/projects/api_registry.json`
- `/workspace/projects/active_project.json`

## Creation

Project creation is one generic control path:

```text
workbench_project_create
```

Accepted creation inputs include:

- `projectId`
- `name`
- `prompt`
- `entityName`
- `description`
- `initialItems`
- `apis`
- `htmlFiles`
- `flutterFiles`
- `displays`

The runtime writes the Project registry, API registry, source specs, optional frontend sources, initial state, and progress log. Recreating the same Project id returns the existing Project and repairs missing API registry records without replacing user state.

## Project Tools

Project Tools are stable business actions. They are callable from:

- Flutter UI through `workbench_api_call`
- HTML through `window.oob.callApi(apiId, inputs)`
- Agent tools through `workbench_api_call`
- MCP dynamic tools through the active Project Toolbox

Supported run targets:

- `native.collection.create`
- `native.collection.archive`
- `native.collection.update`
- `native.collection.list`
- `script` or `workspace_python_script`
- `agent`
- `oob.<tool>`
- `mcp.<tool>`

The native collection executor mutates `data/items.json`. Workspace script tools run Project-owned scripts. Agent-backed tools start a normal OOB Agent task with Project context.

## Display Payload

Project payloads include:

- `projectId`
- `name`
- `route`
- `spacePath`
- `displays`
- `pageSpec`
- `frontendHtml`
- `frontendFlutter`
- `apiIds`
- `apis`
- `toolbox`
- `items`
- `androidAssets`
- `sourceAssets`
- progress and error summaries

Renderer selection:

- If HTML sources exist, default route is `/workbench/html?projectId=<id>`.
- If Flutter sources exist and HTML is absent, default route is `/workbench/flutter_eval?projectId=<id>`.
- Otherwise, default route is `/workbench/project?projectId=<id>`.

## HTML Runtime

`frontend/html/` files are bounded to the Project directory. Paths must be relative and cannot escape the HTML root. OOB writes `frontend/html/manifest.json` and exposes:

- `entryFile`
- `entryPath`
- `sources`
- `assets`
- `renderer = html_webview`

The right-side Flutter host loads the HTML in WebView and injects the OOB bridge. HTML may call registered Project Tools and read the Project payload. All native capability stays behind Project Tools.

## Hot Update

`workbench_project_hot_update` records the user request and `frontendContext`.

If `frontendHtml.sources` exists, the hot update result asks the Agent to update the relevant HTML/CSS/JS files with `workbench_project_update(htmlFiles=...)`.

If `frontendFlutter.sources` exists, the hot update result asks the Agent to provide full replacement Dart files with `workbench_project_update(flutterFiles=...)`.

If no frontend source exists, the runtime can apply simple structured-data updates through Project Tools or native collection actions.

## Source Ingest

`workbench_project_ingest_oss` records or imports source assets:

- URL-only ingest records metadata and `requiresFetch=true`.
- Local source ingest copies files under `source/repos/<source-id>/`.
- Dependency/build directories are skipped.
- Package files and entrypoints are detected and stored in `source/manifest.json`.

The runtime never performs arbitrary network fetch in native code. Fetching belongs to approved terminal/tool paths.

## MCP

MCP exposes:

- Workbench control tools for Project lifecycle operations.
- Dynamic Project Toolbox tools for the active Project.
- Read-only resources for Project, active Project, Toolbox, progress, logs, and source manifest.
- Prompt entries for common Project creation and inspection workflows.

Dynamic tool names are derived from the active Project id and API id. They dispatch to the same Project Tool executor used by Flutter and Agent calls.

## Export

`workbench_project_export` writes a zip package containing:

- manifest
- registry records
- API records
- full Project directory
- bundled Workbench skill instructions

Exports are audit/debug artifacts and do not alter the live Project.
