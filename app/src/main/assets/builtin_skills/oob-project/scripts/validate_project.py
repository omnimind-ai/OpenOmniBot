#!/usr/bin/env python3
"""
OOB Project Designer — post-generation validator.

Usage:
    python3 validate_project.py --project-path /workspace/projects/<projectId>

Exits 0 when all checks pass, 1 when any FAIL is found.
"""

import argparse
import json
import os
import re
import sys


def load_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        return None


def read_html_files(frontend_html_dir):
    html_contents = {}
    if not os.path.isdir(frontend_html_dir):
        return html_contents
    for fname in os.listdir(frontend_html_dir):
        if fname.endswith(".html"):
            fpath = os.path.join(frontend_html_dir, fname)
            try:
                with open(fpath, encoding="utf-8") as f:
                    html_contents[fname] = f.read()
            except Exception:
                pass
    return html_contents


def extract_call_api_ids(html):
    # Match callApi('toolId', ...) and callApi("toolId", ...)
    return set(re.findall(r"""callApi\s*\(\s*['"]([^'"]+)['"]""", html))


def project_apis(project):
    apis = project.get("apis", [])
    if isinstance(apis, list) and apis:
        return apis
    tools = project.get("tools", [])
    if isinstance(tools, list) and tools:
        return tools
    nested_project = project.get("project", {})
    if isinstance(nested_project, dict):
        nested_tools = nested_project.get("tools", [])
        if isinstance(nested_tools, list) and nested_tools:
            return nested_tools
    toolbox = project.get("toolbox", {})
    if isinstance(toolbox, dict):
        toolbox_tools = toolbox.get("tools", [])
        if isinstance(toolbox_tools, list) and toolbox_tools:
            return toolbox_tools
    return []


def run_use(api):
    run = api.get("run", {})
    return run.get("use", "") if isinstance(run, dict) else ""


def is_agent_entry(api):
    use = run_use(api)
    executor_kind = api.get("executorKind", "")
    return (
        use == "agent" or
        use.startswith("agent.") or
        use.startswith("oob.") or
        use.startswith("mcp.") or
        executor_kind == "agent_task"
    )


def is_writeback_api(api):
    return run_use(api) in {"native.collection.create", "native.collection.update"}


def is_html_optional_api(api):
    return run_use(api) in {"native.collection.list", "native.collection.get"}


def check_has_pattern(html, pattern, label):
    found = bool(re.search(pattern, html))
    return ("PASS" if found else "FAIL", label, "" if found else f"not found: {pattern}")


def run(project_path):
    results = []

    def record(status, check, detail=""):
        results.append({"status": status, "check": check, "detail": detail})

    # ── Load project.json ──────────────────────────────────────────────────────
    project_json_path = os.path.join(project_path, "project.json")
    project = load_json(project_json_path)
    if project is None:
        record("FAIL", "project.json exists", f"not found at {project_json_path}")
        _print_results(results)
        return 1

    record("PASS", "project.json exists")

    registered_tool_ids = set()
    apis = project_apis(project)
    for api in apis:
        tid = api.get("toolId") or api.get("apiId")
        if tid:
            registered_tool_ids.add(tid)

    record(
        "PASS" if registered_tool_ids else "FAIL",
        "at least one API registered",
        "" if registered_tool_ids else "apis list is empty in project.json",
    )

    agent_apis = []
    missing_capabilities = []
    for api in apis:
        run = api.get("run", {})
        use = run.get("use", "") if isinstance(run, dict) else ""
        executor_kind = api.get("executorKind", "")
        if use == "agent" or executor_kind == "agent_task":
            agent_apis.append(api)
            capabilities = run.get("capabilities", []) if isinstance(run, dict) else []
            if not isinstance(capabilities, list) or not any(str(x).strip() for x in capabilities):
                missing_capabilities.append(api.get("toolId") or api.get("apiId") or "<unknown>")

    if agent_apis:
        if len(agent_apis) > 1:
            record("FAIL", "v1 has at most one agent API", f"{len(agent_apis)} agent API(s)")
        else:
            record("PASS", "v1 has at most one agent API", f"{len(agent_apis)} agent API")
    else:
        record("PASS", "agent API optional", "pure CRUD project is acceptable when it completes the core loop")

    if missing_capabilities:
        record("FAIL", "agent APIs declare capabilities", f"missing: {sorted(missing_capabilities)}")
    elif agent_apis:
        record("PASS", "agent APIs declare capabilities")

    field_names = set()
    for api in apis:
        schema = api.get("inputSchema", {})
        properties = schema.get("properties", {}) if isinstance(schema, dict) else {}
        if isinstance(properties, dict):
            field_names.update(str(k) for k in properties.keys() if str(k) != "item_id")
    if len(field_names) > 6:
        record("FAIL", "v1 field scope is small", f"{len(field_names)} input field(s): {sorted(field_names)}")
    elif field_names:
        record("PASS", "v1 field scope is small", f"{len(field_names)} input field(s)")

    # ── Load HTML files ────────────────────────────────────────────────────────
    html_dir = os.path.join(project_path, "frontend", "html")
    html_files = read_html_files(html_dir)

    if not html_files:
        record("FAIL", "HTML files exist", f"no .html files found under {html_dir}")
        _print_results(results)
        return 1

    record("PASS", "HTML files exist", ", ".join(html_files.keys()))

    index_html = html_files.get("index.html", "")
    all_html = "\n".join(html_files.values())

    # ── API binding checks ─────────────────────────────────────────────────────
    called_ids = extract_call_api_ids(all_html)

    unregistered = called_ids - registered_tool_ids
    if unregistered:
        record("FAIL", "all callApi ids are registered", f"unregistered: {sorted(unregistered)}")
    else:
        record("PASS", "all callApi ids are registered")

    has_called_agent_entry = any(
        is_agent_entry(api) and (api.get("toolId") or api.get("apiId")) in called_ids
        for api in apis
    )
    html_optional_ids = {
        api.get("toolId") or api.get("apiId")
        for api in apis
        if is_html_optional_api(api) or (has_called_agent_entry and is_writeback_api(api))
    }
    unused = registered_tool_ids - called_ids - html_optional_ids
    if unused:
        record(
            "WARN",
            "visible APIs used in HTML or agent writeback",
            f"not called in HTML: {sorted(unused)}",
        )
    else:
        record("PASS", "visible APIs used in HTML or agent writeback")

    # ── Required bridge calls ──────────────────────────────────────────────────
    s, label, detail = check_has_pattern(
        all_html, r"window\.oob\.getProject\s*\(", "getProject() called on load"
    )
    record(s, label, detail)

    s, label, detail = check_has_pattern(
        all_html, r"window\.oob\.onProjectUpdated\s*\(", "onProjectUpdated registered"
    )
    record(s, label, detail)

    # ── Skeleton elements ──────────────────────────────────────────────────────
    for selector, check_name in [
        (r'id=["\']status["\']', "#status element"),
        (r'class=["\'][^"\']*empty[^"\']*["\']|\.empty\b', ".empty state class"),
        (r'error-msg|error_msg', ".error-msg or error handling"),
        (r'data-oob-id=', "data-oob-id attributes"),
    ]:
        s, label, detail = check_has_pattern(all_html, selector, check_name)
        record(s, label, detail)

    # ── Data binding hygiene ───────────────────────────────────────────────────
    # Warn if domain fields are accessed directly on item (not via item.fields or a view model)
    # Pattern: item.someField where someField is not id/title/status/fields
    direct_field_accesses = re.findall(
        r"""item\.(?!id\b|title\b|status\b|fields\b|length\b|map\b|filter\b|forEach\b|find\b)([a-zA-Z_][a-zA-Z0-9_]*)""",
        all_html,
    )
    if direct_field_accesses:
        record(
            "WARN",
            "domain fields accessed via item.fields (not item.<field>)",
            f"direct accesses found: {sorted(set(direct_field_accesses))} — wrap via toViewItem()",
        )
    else:
        record("PASS", "domain fields accessed via item.fields (not item.<field>)")

    # ── Hardcoded data check ───────────────────────────────────────────────────
    hardcoded = re.search(
        r"""(?:const|let|var)\s+\w+\s*=\s*\[[\s\S]{0,200}title\s*:\s*['"]""",
        all_html,
    )
    if hardcoded:
        record("WARN", "no hardcoded data arrays", "found inline data array with 'title' — render from project.items")
    else:
        record("PASS", "no hardcoded data arrays")

    # ── Loading state check ────────────────────────────────────────────────────
    s, label, detail = check_has_pattern(
        all_html, r"加载中|处理中|loading|disabled\s*=\s*true|\.disabled|\.loading", "loading state present"
    )
    record(s, label, detail)

    # ── Project documents ──────────────────────────────────────────────────────
    soul_path = os.path.join(project_path, "PROJECT_SOUL.md")
    if os.path.exists(soul_path):
        record("PASS", "PROJECT_SOUL.md exists")
        soul_text = open(soul_path, encoding="utf-8").read()
        has_intent   = "创建意图" in soul_text or "intent" in soul_text.lower()
        has_rules    = "业务规则" in soul_text or "rules" in soul_text.lower()
        has_history  = "更新历史" in soul_text or "history" in soul_text.lower()
        if has_intent and has_rules and has_history:
            record("PASS", "PROJECT_SOUL.md has required sections")
        else:
            missing = [s for s, ok in [("创建意图", has_intent), ("业务规则", has_rules), ("更新历史", has_history)] if not ok]
            record("WARN", "PROJECT_SOUL.md has required sections", f"missing sections: {missing}")
    else:
        record("WARN", "PROJECT_SOUL.md exists", "not found — write it via Phase 4.5")

    ctx_path = os.path.join(project_path, "PROJECT_CONTEXT.md")
    if os.path.exists(ctx_path):
        record("PASS", "PROJECT_CONTEXT.md exists")
        ctx_text = open(ctx_path, encoding="utf-8").read()

        # Check field schema has real types (not all "string |")
        field_rows = re.findall(r"\|\s*(\w+)\s*\|\s*(string|number|boolean|date|enum)", ctx_text)
        all_string = all(t == "string" for _, t in field_rows) if field_rows else False
        if field_rows and not all_string:
            record("PASS", "PROJECT_CONTEXT.md field schema has real types")
        elif not field_rows:
            record("WARN", "PROJECT_CONTEXT.md field schema has real types", "no typed fields found — update schema with real types")
        else:
            record("WARN", "PROJECT_CONTEXT.md field schema has real types", "all fields are 'string' — replace with number/date/enum where appropriate")

        # Check Data Layout section
        if "Data Layout" in ctx_text or "data/items.json" in ctx_text:
            record("PASS", "PROJECT_CONTEXT.md has Data Layout section")
        else:
            record("WARN", "PROJECT_CONTEXT.md has Data Layout section", "missing — append Data Layout table per project-soul-guide.md")
    else:
        record("WARN", "PROJECT_CONTEXT.md exists", "not found — runtime should have auto-generated it")

    _print_results(results)

    fail_count = sum(1 for r in results if r["status"] == "FAIL")
    warn_count = sum(1 for r in results if r["status"] == "WARN")
    print(f"\nSummary: {len(results)} checks — {fail_count} FAIL, {warn_count} WARN")

    return 1 if fail_count > 0 else 0


def _print_results(results):
    for r in results:
        icon = {"PASS": "✓", "FAIL": "✗", "WARN": "△"}.get(r["status"], "?")
        detail = f"  {r['detail']}" if r["detail"] else ""
        print(f"  {icon} [{r['status']}] {r['check']}{detail}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-path", required=True, help="Absolute path to the project directory")
    args = parser.parse_args()
    sys.exit(run(args.project_path))
