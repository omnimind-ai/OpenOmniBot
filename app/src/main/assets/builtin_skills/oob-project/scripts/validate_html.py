#!/usr/bin/env python3
"""
OOB Project — pre-write HTML validator.

Checks that HTML is bridge-correct before it gets written to a project.
Focus: every button and callApi call will actually work at runtime.

Usage:
    python3 validate_html.py --html <path> --apis '["entity.create","entity.archive"]'

Exit 0 = all FAIL checks pass (WARNs are printed but don't fail).
Exit 1 = at least one FAIL.
"""

import argparse
import json
import re
import sys


def normalize_api_specs(raw_apis):
    specs = []
    for item in raw_apis:
        if isinstance(item, str):
            specs.append({"toolId": item, "run": {}})
        elif isinstance(item, dict):
            tool_id = item.get("toolId") or item.get("apiId")
            if tool_id:
                specs.append(item | {"toolId": tool_id})
    return specs


def run_use(api):
    run = api.get("run", {})
    return run.get("use", "") if isinstance(run, dict) else ""


def is_agent_entry(api):
    use = run_use(api)
    return use == "agent" or use.startswith("agent.") or use.startswith("oob.") or use.startswith("mcp.")


def is_writeback_api(api):
    return run_use(api) in {"native.collection.create", "native.collection.update"}


def is_html_optional_api(api):
    return run_use(api) in {"native.collection.list", "native.collection.get"}


def validate(html: str, registered_apis: list) -> list:
    errors = []
    api_specs = normalize_api_specs(registered_apis)
    registered_api_ids = [api["toolId"] for api in api_specs]

    # ── Required bridge hooks ──────────────────────────────────────────────────
    if "getProject()" not in html:
        errors.append("FAIL [bridge] getProject() missing — page won't load data on open")

    if "onProjectUpdated" not in html:
        errors.append("FAIL [bridge] onProjectUpdated missing — realtime updates won't work")

    # ── callApi id registration check ─────────────────────────────────────────
    called_ids = set(re.findall(r"""callApi\s*\(\s*['"]([^'"]+)['"]""", html))
    for api_id in sorted(called_ids):
        if api_id not in registered_api_ids:
            errors.append(
                f"FAIL [api] callApi('{api_id}') not in registered apis — "
                f"button will silently fail at runtime"
            )

    # Warn about registered apis with no HTML trigger.
    # Agent-backed Projects often keep create/update as writeback-only tools used from
    # agentPrompt via workbench_api_call, not from a visible HTML button.
    has_called_agent_entry = any(
        is_agent_entry(api) and api.get("toolId") in called_ids for api in api_specs
    )
    html_optional_ids = {
        api["toolId"]
        for api in api_specs
        if is_html_optional_api(api) or (has_called_agent_entry and is_writeback_api(api))
    }
    for api_id in sorted(set(registered_api_ids) - called_ids - html_optional_ids):
        errors.append(f"WARN [api] '{api_id}' registered but never called in HTML")

    # ── Wrong result read patterns ────────────────────────────────────────────
    if "result.items" in html:
        errors.append(
            "FAIL [bridge] result.items — use result.project.items (native) "
            "or result.outputs (script)"
        )

    # ── Data binding hygiene ──────────────────────────────────────────────────
    direct_fields = re.findall(
        r"""\bitem\.(?!id\b|title\b|status\b|fields\b|length\b|map\b|filter\b|forEach\b|find\b)"""
        r"""([a-zA-Z_][a-zA-Z0-9_]*)""",
        html,
    )
    if direct_fields:
        errors.append(
            f"WARN [bridge] direct item.field access: {sorted(set(direct_fields))} "
            f"— use toViewItem() / item.fields.*"
        )

    if re.search(r"""(?:const|let|var)\s+\w+\s*=\s*\[[\s\S]{0,200}title\s*:\s*['"]""", html):
        errors.append("WARN [data] hardcoded data array with 'title' — render from project.items")

    # ── Mobile minimum ────────────────────────────────────────────────────────
    if "viewport-fit=cover" not in html:
        errors.append("WARN [mobile] viewport-fit=cover missing")
    if "base.css" not in html and "safe-area-inset-top" not in html:
        errors.append("WARN [mobile] safe-area padding missing")

    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--html", required=True, help="Path to HTML file (use /dev/stdin for pipe)")
    parser.add_argument(
        "--apis",
        default="[]",
        help='JSON array of registered toolIds, e.g. \'["entity.create","entity.archive"]\'',
    )
    args = parser.parse_args()

    try:
        with open(args.html, encoding="utf-8") as f:
            html = f.read()
    except Exception as e:
        print(f"FAIL [io] cannot read HTML file: {e}")
        sys.exit(1)

    try:
        registered = json.loads(args.apis)
    except Exception as e:
        print(f"FAIL [args] --apis is not valid JSON: {e}")
        sys.exit(1)

    errors = validate(html, registered)
    for e in errors:
        print(e)

    fails = sum(1 for e in errors if e.startswith("FAIL"))
    warns = sum(1 for e in errors if e.startswith("WARN"))
    if errors:
        print(f"\nSummary: {fails} FAIL, {warns} WARN")
    else:
        print("PASS — no issues found")

    sys.exit(1 if fails > 0 else 0)


if __name__ == "__main__":
    main()
