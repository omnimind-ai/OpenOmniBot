#!/usr/bin/env python3
"""
OOB Workbench Runtime — portable Python implementation.

Mirrors the Android native workbench data layer so the oob-project skill
can run outside Android (Codex, local testing, CI).

Disk format is 100% compatible with the Android Kotlin implementation so
projects created here can be copied to an Android device and read natively.

Usage:
  python3 workbench_runtime.py project create --config '<json>' [--workspace PATH]
  python3 workbench_runtime.py project list   [--workspace PATH]
  python3 workbench_runtime.py project get    --project-id ID [--workspace PATH]
  python3 workbench_runtime.py project activate --project-id ID [--workspace PATH]
  python3 workbench_runtime.py api call  --project-id ID --api-id ID --inputs '<json>' [--workspace PATH]
  python3 workbench_runtime.py api list  --project-id ID [--workspace PATH]

All commands write JSON to stdout. Exit 0 = success, 1 = error.
--workspace defaults to ./workspace or $OOB_WORKSPACE env var.

Note: agent/script executors return {status:"not_supported_in_portable_mode"}.
      CRUD (native.collection.*) executors are fully supported.
"""

import argparse
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


# ── Workspace resolution ───────────────────────────────────────────────────────

def resolve_workspace(cli_arg: str | None) -> Path:
    if cli_arg:
        return Path(cli_arg).resolve()
    env = os.environ.get("OOB_WORKSPACE")
    if env:
        return Path(env).resolve()
    return Path("./workspace").resolve()


def projects_root(ws: Path) -> Path:
    return ws / "projects"


def project_dir(ws: Path, project_id: str) -> Path:
    return projects_root(ws) / sanitize_id(project_id)


# ── ID / timestamp utils ───────────────────────────────────────────────────────

def sanitize_id(raw: str) -> str:
    s = raw.strip().lower()
    s = re.sub(r"[^a-z0-9._\-]", "-", s)
    s = s.strip("-._")
    if not s or ".." in s:
        raise ValueError(f"Invalid project id: {raw!r}")
    return s


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + \
           f"{datetime.now(timezone.utc).microsecond // 1000:03d}Z"


def new_item_id(index: int) -> str:
    ts = int(time.time() * 1000)
    return f"item-{ts}-{index + 1}"


# ── JSON file helpers ──────────────────────────────────────────────────────────

def read_json(path: Path, default):
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


# ── Registry helpers ───────────────────────────────────────────────────────────

def read_registry(ws: Path) -> list:
    return read_json(projects_root(ws) / "registry.json", [])


def write_registry(ws: Path, records: list) -> None:
    records_sorted = sorted(records, key=lambda r: r.get("projectId", ""))
    write_json(projects_root(ws) / "registry.json", records_sorted)


def read_api_registry(ws: Path) -> list:
    return read_json(projects_root(ws) / "api_registry.json", [])


def write_api_registry(ws: Path, records: list) -> None:
    records_sorted = sorted(records, key=lambda r: (r.get("projectId", ""), r.get("apiId", "")))
    write_json(projects_root(ws) / "api_registry.json", records_sorted)


def read_items(ws: Path, project_id: str) -> list:
    return read_json(project_dir(ws, project_id) / "data" / "items.json", [])


def write_items(ws: Path, project_id: str, items: list) -> None:
    write_json(project_dir(ws, project_id) / "data" / "items.json", items)


# ── Default API generation (mirrors Android defaultProjectApis) ────────────────

def default_apis(project_id: str, entity_name: str) -> list:
    en = entity_name.lower() if entity_name else "item"
    return [
        {
            "apiId": f"{en}.create", "projectId": project_id, "toolId": f"{en}.create",
            "displayName": f"Add {entity_name}", "description": "",
            "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}}},
            "outputSchema": {"type": "object", "properties": {"item": {"type": "object"}}},
            "executorKind": "native_project_collection",
            "run": {"use": "native.collection.create"},
        },
        {
            "apiId": f"{en}.archive", "projectId": project_id, "toolId": f"{en}.archive",
            "displayName": f"Archive {entity_name}", "description": "",
            "inputSchema": {"type": "object", "properties": {"item_id": {"type": "string"}},
                            "required": ["item_id"]},
            "outputSchema": {"type": "object", "properties": {"item": {"type": "object"}}},
            "executorKind": "native_project_collection",
            "run": {"use": "native.collection.archive"},
        },
    ]


def build_api_records(project_id: str, apis_input: list) -> list:
    records = []
    for a in apis_input:
        tool_id = a.get("toolId") or a.get("apiId", "")
        run = a.get("run", {})
        use = run.get("use", "") if isinstance(run, dict) else ""
        if use.startswith("native.collection."):
            kind = "native_project_collection"
        elif use == "agent":
            kind = "agent_task"
        elif use == "script":
            kind = "workspace_python_script"
        else:
            kind = "native_project_collection"
        records.append({
            "apiId": tool_id,
            "projectId": project_id,
            "toolId": tool_id,
            "displayName": a.get("displayName", tool_id),
            "description": a.get("description", ""),
            "inputSchema": a.get("inputSchema", {}),
            "outputSchema": a.get("outputSchema", {}),
            "executorKind": kind,
            "run": run,
        })
    return records


# ── native.collection.* action inference (mirrors Android projectApiAction) ────

def collection_action(run: dict) -> str:
    use = run.get("use", "") if isinstance(run, dict) else ""
    if use == "native.collection.create":   return "create"
    if use == "native.collection.archive":  return "archive"
    if use == "native.collection.update":   return "update"
    if use == "native.collection.list":     return "list"
    if use == "native.collection.get":      return "get"
    return "custom"


# ── Item CRUD (mirrors Android createProjectItem etc.) ────────────────────────

def item_title(inputs: dict) -> str:
    for key in ("title", "name", "label"):
        v = inputs.get(key, "")
        if v and str(v).strip():
            return str(v).strip()[:160]
    for v in inputs.values():
        if v and str(v).strip():
            return str(v).strip()[:160]
    return ""


def do_create(ws: Path, project_id: str, api_rec: dict, inputs: dict) -> dict:
    items = read_items(ws, project_id)
    title = item_title(inputs)
    fields = {k: v for k, v in inputs.items() if k not in ("title", "name", "label")}
    new_item = {
        "id": new_item_id(len(items)),
        "title": title,
        "status": "active",
        "fields": fields,
        "createdAt": now_iso(),
        "archivedAt": None,
    }
    items.insert(0, new_item)
    write_items(ws, project_id, items)
    return {"success": True, "outputs": {"item": new_item}, "updatedItems": items}


def do_archive(ws: Path, project_id: str, api_rec: dict, inputs: dict) -> dict:
    item_id = inputs.get("item_id") or inputs.get("itemId")
    if not item_id:
        return {"success": False, "errorMessage": "archive requires item_id"}
    items = read_items(ws, project_id)
    found = next((i for i in items if i.get("id") == item_id), None)
    if not found:
        return {"success": False, "errorMessage": f"Item not found: {item_id}"}
    found["status"] = "archived"
    found["archivedAt"] = now_iso()
    write_items(ws, project_id, items)
    return {"success": True, "outputs": {"item": found}, "updatedItems": items}


def do_update(ws: Path, project_id: str, api_rec: dict, inputs: dict) -> dict:
    item_id = inputs.get("item_id") or inputs.get("itemId")
    if not item_id:
        return {"success": False, "errorMessage": "update requires item_id"}
    items = read_items(ws, project_id)
    found = next((i for i in items if i.get("id") == item_id), None)
    if not found:
        return {"success": False, "errorMessage": f"Item not found: {item_id}"}
    rest = {k: v for k, v in inputs.items() if k not in ("item_id", "itemId")}
    if "title" in rest:
        found["title"] = str(rest.pop("title"))[:160]
    found.setdefault("fields", {}).update(rest)
    write_items(ws, project_id, items)
    return {"success": True, "outputs": {"item": found}, "updatedItems": items}


def do_list(ws: Path, project_id: str) -> dict:
    items = read_items(ws, project_id)
    active = [i for i in items if i.get("status", "active") == "active"]
    archived = [i for i in items if i.get("status") == "archived"]
    return {"success": True, "outputs": {"items": items, "activeItems": active, "archivedItems": archived}}


def do_get(ws: Path, project_id: str, inputs: dict) -> dict:
    item_id = inputs.get("item_id") or inputs.get("itemId")
    if not item_id:
        return {"success": False, "errorMessage": "get requires item_id"}
    items = read_items(ws, project_id)
    found = next((i for i in items if i.get("id") == item_id), None)
    if not found:
        return {"success": False, "errorMessage": f"Item not found: {item_id}"}
    return {"success": True, "outputs": {"item": found}}


# ── Build project payload (for project.json and responses) ────────────────────

def build_project_payload(ws: Path, project_id: str, record: dict,
                          api_records: list, items: list) -> dict:
    active = [i for i in items if i.get("status", "active") == "active"]
    tools = [
        {
            "apiId": a.get("apiId"), "toolId": a.get("toolId"),
            "displayName": a.get("displayName", ""), "description": a.get("description", ""),
            "inputSchema": a.get("inputSchema", {}), "outputSchema": a.get("outputSchema", {}),
            "executorKind": a.get("executorKind", ""), "run": a.get("run", {}),
            "executionCount": 0,
        }
        for a in api_records if a.get("projectId") == project_id
    ]
    return {
        "projectId": record["projectId"],
        "name": record.get("name", ""),
        "route": record.get("route", f"/workbench/html?projectId={project_id}"),
        "spacePath": record.get("spacePath", str(project_dir(ws, project_id))),
        "apiIds": record.get("apiIds", []),
        "tools": tools,
        "items": items,
        "activeItems": active,
        "createdAt": record.get("createdAt", now_iso()),
        "updatedAt": record.get("updatedAt", now_iso()),
    }


# ── Command: project create ────────────────────────────────────────────────────

def cmd_project_create(config: dict, ws: Path) -> dict:
    raw_id = config.get("projectId", "")
    if not raw_id:
        return {"success": False, "errorMessage": "projectId is required"}
    project_id = sanitize_id(raw_id)
    ts = now_iso()

    # Determine APIs
    apis_input = config.get("apis") or []
    entity_name = config.get("entityName") or config.get("entity", {}).get("name", "Item")
    if not apis_input:
        api_recs = default_apis(project_id, entity_name)
    else:
        api_recs = build_api_records(project_id, apis_input)
    api_ids = [a["toolId"] for a in api_recs]

    # Registry record
    pdir = project_dir(ws, project_id)
    space_path = str(pdir)
    record = {
        "projectId": project_id,
        "name": config.get("name", project_id),
        "route": f"/workbench/html?projectId={project_id}",
        "spacePath": space_path,
        "apiIds": api_ids,
        "createdAt": ts,
        "updatedAt": ts,
    }

    # Directories
    (pdir / "data").mkdir(parents=True, exist_ok=True)
    (pdir / "logs").mkdir(exist_ok=True)
    (pdir / "frontend" / "html").mkdir(parents=True, exist_ok=True)

    # Initial items
    if not (pdir / "data" / "items.json").exists():
        init_items = []
        for raw_item in (config.get("initialItems") or []):
            if isinstance(raw_item, str):
                init_items.append({
                    "id": new_item_id(len(init_items)),
                    "title": raw_item, "status": "active",
                    "fields": {}, "createdAt": ts, "archivedAt": None,
                })
            elif isinstance(raw_item, dict):
                title = raw_item.pop("title", "") or raw_item.pop("name", "")
                init_items.append({
                    "id": new_item_id(len(init_items)),
                    "title": title, "status": "active",
                    "fields": raw_item, "createdAt": ts, "archivedAt": None,
                })
        write_items(ws, project_id, init_items)

    # HTML files
    html_files = (config.get("htmlFiles") or
                  config.get("frontendHtmlFiles") or [])
    for hf in html_files:
        path_str = hf.get("path", "")
        # Strip frontend/html/ prefix (mirrors cleanFrontendHtmlPath)
        for prefix in ("frontend/html/", "frontend\\html\\", "html/"):
            if path_str.startswith(prefix):
                path_str = path_str[len(prefix):]
                break
        content = hf.get("content", "")
        target = pdir / "frontend" / "html" / path_str
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    # Update registries
    registry = read_registry(ws)
    registry = [r for r in registry if r.get("projectId") != project_id]
    registry.append(record)
    write_registry(ws, registry)

    api_registry = read_api_registry(ws)
    api_registry = [r for r in api_registry if r.get("projectId") != project_id]
    api_registry.extend(api_recs)
    write_api_registry(ws, api_registry)

    # Write project.json
    items = read_items(ws, project_id)
    payload = build_project_payload(ws, project_id, record, api_registry, items)
    write_json(pdir / "project.json", payload)

    return {
        "success": True,
        "projectId": project_id,
        "project": payload,
        "registryPath": str(projects_root(ws) / "registry.json"),
        "apiRegistryPath": str(projects_root(ws) / "api_registry.json"),
    }


# ── Command: project list ──────────────────────────────────────────────────────

def cmd_project_list(ws: Path) -> dict:
    registry = read_registry(ws)
    return {"success": True, "projects": registry}


# ── Command: project get ───────────────────────────────────────────────────────

def cmd_project_get(project_id: str, ws: Path) -> dict:
    project_id = sanitize_id(project_id)
    registry = read_registry(ws)
    record = next((r for r in registry if r.get("projectId") == project_id), None)
    if not record:
        return {"success": False, "errorMessage": f"Project not found: {project_id}"}
    api_registry = read_api_registry(ws)
    items = read_items(ws, project_id)
    payload = build_project_payload(ws, project_id, record, api_registry, items)
    return {"success": True, "project": payload}


# ── Command: project activate ──────────────────────────────────────────────────

def cmd_project_activate(project_id: str, ws: Path) -> dict:
    project_id = sanitize_id(project_id)
    registry = read_registry(ws)
    if not any(r.get("projectId") == project_id for r in registry):
        return {"success": False, "errorMessage": f"Project not found: {project_id}"}
    write_json(projects_root(ws) / "active_project.json",
               {"projectId": project_id, "activatedAt": now_iso()})
    return {"success": True, "projectId": project_id}


# ── Command: api list ──────────────────────────────────────────────────────────

def cmd_api_list(project_id: str, ws: Path) -> dict:
    project_id = sanitize_id(project_id)
    api_registry = read_api_registry(ws)
    apis = [a for a in api_registry if a.get("projectId") == project_id]
    return {"success": True, "apis": apis}


# ── Command: api call ──────────────────────────────────────────────────────────

def cmd_api_call(project_id: str, api_id: str, inputs: dict, ws: Path) -> dict:
    project_id = sanitize_id(project_id)
    api_registry = read_api_registry(ws)
    api_rec = next(
        (a for a in api_registry
         if a.get("projectId") == project_id
         and (a.get("apiId") == api_id or a.get("toolId") == api_id)),
        None
    )
    if not api_rec:
        return {"success": False, "errorMessage": f"API not found: {api_id}"}

    run = api_rec.get("run") or {}
    action = collection_action(run)

    result: dict
    if action == "create":
        result = do_create(ws, project_id, api_rec, inputs)
    elif action == "archive":
        result = do_archive(ws, project_id, api_rec, inputs)
    elif action == "update":
        result = do_update(ws, project_id, api_rec, inputs)
    elif action == "list":
        result = do_list(ws, project_id)
    elif action == "get":
        result = do_get(ws, project_id, inputs)
    else:
        # agent/script — not supported in portable mode
        return {
            "success": True,
            "status": "not_supported_in_portable_mode",
            "message": (f"Executor '{run.get('use', 'unknown')}' requires the OmniBot "
                        f"Android runtime. In portable mode, simulate the result manually."),
        }

    # Attach full project state to result (mirrors Android callProjectCollectionApi)
    if result.get("success") and action not in ("list", "get"):
        registry = read_registry(ws)
        record = next((r for r in registry if r.get("projectId") == project_id), {})
        items = read_items(ws, project_id)
        result["project"] = build_project_payload(ws, project_id, record, api_registry, items)

    return {**result, "projectId": project_id, "apiId": api_id}


# ── CLI ────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(prog="workbench_runtime")
    parser.add_argument("--workspace", default=None,
                        help="Workspace root (default: ./workspace or $OOB_WORKSPACE)")
    sub = parser.add_subparsers(dest="resource", required=True)

    # project subcommands
    proj = sub.add_parser("project")
    proj_sub = proj.add_subparsers(dest="action", required=True)

    p_create = proj_sub.add_parser("create")
    p_create.add_argument("--config", required=True, help="workbench_project_create JSON")

    p_list = proj_sub.add_parser("list")

    p_get = proj_sub.add_parser("get")
    p_get.add_argument("--project-id", required=True)

    p_activate = proj_sub.add_parser("activate")
    p_activate.add_argument("--project-id", required=True)

    # api subcommands
    api = sub.add_parser("api")
    api_sub = api.add_subparsers(dest="action", required=True)

    a_list = api_sub.add_parser("list")
    a_list.add_argument("--project-id", required=True)

    a_call = api_sub.add_parser("call")
    a_call.add_argument("--project-id", required=True)
    a_call.add_argument("--api-id", required=True)
    a_call.add_argument("--inputs", default="{}", help="JSON inputs object")

    args = parser.parse_args()
    ws = resolve_workspace(args.workspace)

    try:
        if args.resource == "project":
            if args.action == "create":
                config = json.loads(args.config)
                result = cmd_project_create(config, ws)
            elif args.action == "list":
                result = cmd_project_list(ws)
            elif args.action == "get":
                result = cmd_project_get(args.project_id, ws)
            elif args.action == "activate":
                result = cmd_project_activate(args.project_id, ws)
            else:
                result = {"success": False, "errorMessage": f"Unknown action: {args.action}"}
        elif args.resource == "api":
            if args.action == "list":
                result = cmd_api_list(args.project_id, ws)
            elif args.action == "call":
                inputs = json.loads(args.inputs)
                result = cmd_api_call(args.project_id, args.api_id, inputs, ws)
            else:
                result = {"success": False, "errorMessage": f"Unknown action: {args.action}"}
        else:
            result = {"success": False, "errorMessage": f"Unknown resource: {args.resource}"}
    except Exception as e:
        result = {"success": False, "errorMessage": str(e)}

    print(json.dumps(result, ensure_ascii=False, indent=2))
    sys.exit(0 if result.get("success") else 1)


if __name__ == "__main__":
    main()
