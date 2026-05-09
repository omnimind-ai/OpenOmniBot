# OOB Workbench Integration

This note tracks the native Workbench boundary used by OOB Project editing.

## Project Templates

- `todo_log_demo` is only the demo template. It registers `todo.add` and `todo.finish`, persists `data/todos.json`, and renders `/workbench/todo_log`.
- `schema_app` is the first generic Project template. It creates an OOB-native schema display, registers Project APIs such as `<entity>.create` and `<entity>.archive`, persists `data/items.json`, and renders `/workbench/schema_app`.

## Frontend And Backend Contract

- Project creation remains a Workbench control API: `workbench_project_create`.
- Active Project selection remains a Workbench control API:
  `workbench_project_activate`, `workbench_project_active_get`, and
  `workbench_project_deactivate`.
- Project business APIs remain in the Project API Registry and are called through `workbench_api_call`.
- Generated frontend contracts live in `frontend/page_spec.json`.
- Backend API contracts live in `backend/api_spec.json`.
- UI clicks and AI calls share the same native executor path and append to `logs/api_calls.jsonl`.

## Display Boundary

The Home Project surface embeds the active Project display as a child window. Schema Projects use the generic Flutter schema display; `todo_log_demo` remains a regression/demo template. The Project manager remains a secondary control surface, but its landing page is only a compact Project list plus the current active Project. Tap a Project row to open details for activation, displays, Workspace, export, delete, and API execution counts.

The embedded Project toolbar stays fixed to four actions: open the current Project management page, show Project info, open the current Display fullscreen, and refresh the Project payload. Project switching and deeper management stay in `/workbench/projects`.

## Workspace Cache Boundary

Workspace entry restores the last cached Workspace container state first. If the user last viewed file browsing, OOB reopens the cached Workspace directory when it still exists under `/workspace`. If the user last viewed the Project Display container, OOB reopens that Project frontend surface. The top Workbench mark is the explicit toggle between the active Project frontend and Workspace file browsing; direct Workspace entry should not reset the user back to a fixed default page.

## Current E2E Shape

The normal prompt-generated path is `schema_app`: Home input -> Agent calls
`workbench_project_create` -> Agent calls `workbench_project_activate` -> Agent
seeds state through `workbench_api_call` -> OOB opens
`/workbench/schema_app?projectId=<id>`. The generated frontend and the Agent use
the same Project APIs, data files, and `logs/api_calls.jsonl`.
