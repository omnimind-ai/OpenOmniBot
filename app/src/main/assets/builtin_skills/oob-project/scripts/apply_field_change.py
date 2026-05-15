#!/usr/bin/env python3
"""
OOB Project Field Change Helper.

Given a project's current state and a field operation, outputs the exact
changes needed across all three layers (data schema, API inputSchema, HTML).

No agent reasoning required — deterministic output for deterministic input.

Usage:
    # Add a field
    python3 apply_field_change.py \\
        --project-json /workspace/projects/<id>/project.json \\
        --op add \\
        --field '{"name": "category", "type": "string", "required": false}'

    # Remove a field
    python3 apply_field_change.py \\
        --project-json /workspace/projects/<id>/project.json \\
        --op remove --field-name "category"

    # Rename a field
    python3 apply_field_change.py \\
        --project-json /workspace/projects/<id>/project.json \\
        --op rename --field-name "cat" --new-name "category"

Output: JSON with sections for each layer that needs changing.
Exit 0 = success, 1 = error.
"""

import argparse
import json
import sys


TYPE_TO_JSON_SCHEMA = {
    "string": {"type": "string"},
    "number": {"type": "number"},
    "boolean": {"type": "boolean"},
    "date": {"type": "string", "format": "date"},
    "integer": {"type": "integer"},
}

TYPE_TO_INPUT_TAG = {
    "number": '<input id="f-{name}" inputmode="decimal" placeholder="{name}" autocomplete="off">',
    "date": '<input id="f-{name}" type="date">',
    "boolean": '<label><input id="f-{name}" type="checkbox"> {name}</label>',
}

TYPE_TO_VIEW_LINE = {
    "number": "    {name}: Number(f.{name} || 0),",
    "boolean": "    {name}: Boolean(f.{name}),",
}


def input_tag(field: dict) -> str:
    template = TYPE_TO_INPUT_TAG.get(
        field["type"],
        '<input id="f-{name}" placeholder="{name}" autocomplete="off">'
    )
    return template.format(name=field["name"])


def view_line(field: dict) -> str:
    template = TYPE_TO_VIEW_LINE.get(field["type"], "    {name}: f.{name} || '',")
    return template.format(name=field["name"])


def schema_prop(field: dict) -> dict:
    return TYPE_TO_JSON_SCHEMA.get(field.get("type", "string"), {"type": "string"})


def load_project(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"FAIL [io] cannot read project.json: {e}", file=sys.stderr)
        sys.exit(1)


def find_create_apis(project: dict) -> list:
    """APIs that use native.collection.create or native.collection.update."""
    return [
        api for api in project.get("apis", [])
        if api.get("run", {}).get("use") in (
            "native.collection.create", "native.collection.update"
        )
    ]


def op_add(project: dict, field: dict) -> dict:
    fname = field["name"]
    ftype = field.get("type", "string")
    required = field.get("required", False)

    apis_to_update = find_create_apis(project)
    api_changes = []
    for api in apis_to_update:
        api_changes.append({
            "toolId": api.get("toolId") or api.get("apiId"),
            "add_to_inputSchema_properties": {fname: schema_prop(field)},
            "add_to_required": [fname] if required else [],
            "note": f"Add {fname} to inputSchema.properties"
                    + (f" and required[]" if required else ""),
        })

    return {
        "op": "add",
        "field": field,
        "layer_data": {
            "description": f"New field '{fname}' ({ftype}) in item.fields",
            "note": "Runtime stores automatically — no schema file to update",
        },
        "layer_tool": {
            "apis_to_update": api_changes,
        },
        "layer_display": {
            "toViewItem_add_line": view_line(field),
            "html_form_add_input": input_tag(field),
            "html_form_read_line": f"{fname}: document.getElementById('f-{fname}').value.trim(),",
            "html_form_reset_line": f"document.getElementById('f-{fname}').value = '';",
            "html_list_add_span": f'<span class="f-{fname}">${{esc(vi.{fname})}}</span>',
        },
        "PROJECT_CONTEXT_update": {
            "fields_table_add_row": f"| {fname} | {ftype} | {'是' if required else '否'} | |",
        },
    }


def op_remove(project: dict, field_name: str) -> dict:
    apis_to_update = find_create_apis(project)
    api_changes = []
    for api in apis_to_update:
        props = api.get("inputSchema", {}).get("properties", {})
        if field_name in props:
            api_changes.append({
                "toolId": api.get("toolId") or api.get("apiId"),
                "remove_from_inputSchema_properties": field_name,
                "remove_from_required_if_present": field_name,
            })

    return {
        "op": "remove",
        "field_name": field_name,
        "warning": "Removing a field does NOT delete existing data in item.fields — "
                   "old records will still have the field value, just unused.",
        "layer_tool": {
            "apis_to_update": api_changes,
        },
        "layer_display": {
            "toViewItem_remove_line": f"    {field_name}: ...,  ← delete this line",
            "html_form_remove": f'id="f-{field_name}"  ← delete the <input> with this id',
            "html_list_remove": f'class="f-{field_name}"  ← delete the <span> with this class',
        },
        "PROJECT_CONTEXT_update": {
            "fields_table_remove_row": f"| {field_name} | ... |  ← delete this row",
        },
    }


def op_rename(project: dict, old_name: str, new_name: str) -> dict:
    apis_to_update = find_create_apis(project)
    api_changes = []
    for api in apis_to_update:
        props = api.get("inputSchema", {}).get("properties", {})
        if old_name in props:
            api_changes.append({
                "toolId": api.get("toolId") or api.get("apiId"),
                "rename_in_inputSchema_properties": {"from": old_name, "to": new_name},
                "rename_in_required_if_present": {"from": old_name, "to": new_name},
            })

    return {
        "op": "rename",
        "old_name": old_name,
        "new_name": new_name,
        "warning": "Existing items have data under the old key. "
                   "toViewItem() must read BOTH keys during migration.",
        "layer_tool": {
            "apis_to_update": api_changes,
        },
        "layer_display": {
            "toViewItem_migration_line":
                f"    {new_name}: f.{new_name} || f.{old_name} || '',  "
                f"// read new key, fall back to old key",
            "html_form_update": f'id="f-{old_name}"  → id="f-{new_name}"',
            "html_list_update": f'class="f-{old_name}"  → class="f-{new_name}"',
            "html_js_update":
                f"f-{old_name} → f-{new_name} (all getElementById and class refs)",
        },
        "PROJECT_CONTEXT_update": {
            "fields_table_rename_row": f"| {old_name} → {new_name} | ... |",
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-json", required=True,
                        help="Path to the project's project.json file")
    parser.add_argument("--op", required=True, choices=["add", "remove", "rename"],
                        help="Field operation type")
    parser.add_argument("--field",
                        help="Field spec JSON for 'add' op: {name, type, required}")
    parser.add_argument("--field-name",
                        help="Field name for 'remove' or 'rename' op")
    parser.add_argument("--new-name",
                        help="New field name for 'rename' op")
    args = parser.parse_args()

    project = load_project(args.project_json)

    if args.op == "add":
        if not args.field:
            print("FAIL --field is required for op=add", file=sys.stderr)
            sys.exit(1)
        try:
            field = json.loads(args.field)
        except Exception as e:
            print(f"FAIL --field is not valid JSON: {e}", file=sys.stderr)
            sys.exit(1)
        result = op_add(project, field)

    elif args.op == "remove":
        if not args.field_name:
            print("FAIL --field-name is required for op=remove", file=sys.stderr)
            sys.exit(1)
        result = op_remove(project, args.field_name)

    elif args.op == "rename":
        if not args.field_name or not args.new_name:
            print("FAIL --field-name and --new-name are required for op=rename",
                  file=sys.stderr)
            sys.exit(1)
        result = op_rename(project, args.field_name, args.new_name)

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
