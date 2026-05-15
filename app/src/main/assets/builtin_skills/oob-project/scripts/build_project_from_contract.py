#!/usr/bin/env python3
"""
OOB Project Contract Builder.

Converts a ProjectContract JSON into a canonical workbench_project_create JSON.
Deterministic: same contract → same output every run.

The generated HTML is a reliable functional skeleton, not a polished frontend.
Goal: every button works, every data path is correct.
Visual polish is a future step.

Usage:
    python3 build_project_from_contract.py --contract '<json>'
    python3 build_project_from_contract.py --contract-file contract.json
    python3 build_project_from_contract.py --contract '<json>' --custom-html index.html

On success: prints workbench_project_create JSON to stdout.
On failure: prints error to stderr, exits 1.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import textwrap


# ── Contract validation ────────────────────────────────────────────────────────

def validate_contract(c: dict):
    """
    Required fields: projectId, name, entity.name (PascalCase), fields (non-empty),
                     actions (non-empty), valid action ids, agentPrompt and
                     capabilities for agent actions.
    Optional fields: userIntent (string, written to PROJECT_SOUL.md),
                     soulRules (list of strings, written to PROJECT_SOUL.md),
                     views (primary/list/empty strings).
    """
    errors = []

    entity = c.get("entity", {})
    entity_name = entity.get("name", "")
    if not entity_name:
        errors.append("contract.entity.name is required")
    elif not re.match(r'^[A-Z][a-zA-Z0-9]+$', entity_name):
        errors.append(f"contract.entity.name must be PascalCase, got: {entity_name!r}")

    fields = c.get("fields", [])
    if not fields:
        errors.append("contract.fields must be non-empty")
    elif len(fields) > 6:
        errors.append("contract.fields must stay small for v1 (max 6 fields)")
    for field in fields:
        name = field.get("name", "")
        ftype = field.get("type", "string")
        if not re.match(r'^[a-z][a-zA-Z0-9_]*$', name):
            errors.append(f"field name must be a JS-safe lowerCamel/snake identifier, got: {name!r}")
        if ftype not in TYPE_TO_JSON_SCHEMA:
            errors.append(
                f"field '{name or '<unknown>'}' type must be one of "
                f"{sorted(TYPE_TO_JSON_SCHEMA.keys())}, got: {ftype!r}"
            )

    actions = c.get("actions", [])
    if not actions:
        errors.append("contract.actions must be non-empty")

    agent_action_count = sum(1 for a in actions if a.get("executor") == "agent")
    if agent_action_count > 1:
        errors.append("contract.actions should include at most one agent executor for v1")

    for a in actions:
        aid = a.get("id", "")
        if not re.match(r'^[a-z][a-zA-Z0-9]*\.[a-z][a-zA-Z0-9]*$', aid):
            errors.append(f"action id must be '<entity>.<verb>' format, got: {aid!r}")
        inputs = a.get("inputs", {})
        if isinstance(inputs, dict):
            visible_inputs = [
                k for k in inputs.keys()
                if k not in ("id", "item_id", "itemId", "status")
            ]
            if len(visible_inputs) > 6:
                errors.append(
                    f"action '{aid}' must stay small for v1 (max 6 non-control inputs)"
                )
            for name, type_str in inputs.items():
                if name in ("id", "item_id", "itemId", "status"):
                    continue
                if not re.match(r'^[a-z][a-zA-Z0-9_]*$', str(name)):
                    errors.append(
                        f"action '{aid}' input name must be a JS-safe lowerCamel/snake "
                        f"identifier, got: {name!r}"
                    )
                base_type = str(type_str).rstrip("?")
                if base_type not in TYPE_TO_JSON_SCHEMA:
                    errors.append(
                        f"action '{aid}' input '{name}' type must be one of "
                        f"{sorted(TYPE_TO_JSON_SCHEMA.keys())} plus optional '?', got: {type_str!r}"
                    )
        if a.get("executor") == "agent":
            if not a.get("agentPrompt", "").strip():
                errors.append(f"agent action '{aid}' must have a non-empty agentPrompt")
            capabilities = a.get("capabilities", [])
            if not isinstance(capabilities, list) or not any(str(x).strip() for x in capabilities):
                errors.append(f"agent action '{aid}' must declare non-empty capabilities")

    if not c.get("projectId", "").strip():
        errors.append("contract.projectId is required")

    if not c.get("name", "").strip():
        errors.append("contract.name is required")

    if errors:
        for e in errors:
            print(f"FAIL [contract] {e}", file=sys.stderr)
        sys.exit(1)


# ── API generation ─────────────────────────────────────────────────────────────

EXECUTOR_TO_RUN_USE = {
    "native.collection.create": "native.collection.create",
    "native.collection.archive": "native.collection.archive",
    "native.collection.update": "native.collection.update",
    "native.collection.list": "native.collection.list",
    "native.collection.get": "native.collection.get",
    "agent": "agent",
    "script": "script",
}

TYPE_TO_JSON_SCHEMA = {
    "string": {"type": "string"},
    "number": {"type": "number"},
    "boolean": {"type": "boolean"},
    "date": {"type": "string", "format": "date"},
    "integer": {"type": "integer"},
}


def build_input_schema(inputs: dict) -> dict:
    props = {}
    required = []
    for name, type_str in inputs.items():
        optional = type_str.endswith("?")
        base_type = type_str.rstrip("?")
        props[name] = TYPE_TO_JSON_SCHEMA.get(base_type, {"type": "string"})
        if not optional:
            required.append(name)
    schema = {"type": "object", "properties": props}
    if required:
        schema["required"] = required
    return schema


def build_apis(actions: list) -> list:
    apis = []
    for action in actions:
        executor = action.get("executor", "native.collection.create")
        inputs_raw = action.get("inputs", {})
        input_schema = build_input_schema(inputs_raw)

        run: dict = {"use": EXECUTOR_TO_RUN_USE.get(executor, executor)}
        if executor == "agent":
            run["prompt"] = action.get("agentPrompt", "")
            run["capabilities"] = [
                str(x).strip() for x in action.get("capabilities", []) if str(x).strip()
            ]
        elif executor == "script":
            run["path"] = action.get("scriptPath", "backend/scripts/script.py")

        output_schema: dict
        if executor in ("native.collection.create", "native.collection.update",
                         "native.collection.archive"):
            output_schema = {"type": "object", "properties": {"item": {"type": "object"}}}
        elif executor == "native.collection.list":
            output_schema = {"type": "object", "properties": {"items": {"type": "array"}}}
        elif executor == "native.collection.get":
            output_schema = {"type": "object", "properties": {"item": {"type": "object"}}}
        elif executor == "agent":
            output_schema = {"type": "object", "properties": {"status": {"type": "string"}}}
        else:
            output_schema = {"type": "object"}

        apis.append({
            "toolId": action["id"],
            "displayName": action.get("displayName", action["id"]),
            "description": action.get("description", ""),
            "inputSchema": input_schema,
            "outputSchema": output_schema,
            "run": run,
        })
    return apis


# ── HTML generation — OOB style ────────────────────────────────────────────────
# Input routing principle:
# - If the Project has an agent action, data entry goes through Agent (chat/photo/etc.).
# - If the Project is pure CRUD, HTML may collect a few fields through one compact form.
# Agent reads user input / photos / text, then calls native.collection.create behind the scenes.
# HTML role: display data + trigger the one main action + archive items.
#
# Structure: container > header(title+summary) + status + action entry + list(#main)
# Archive button on each card is the only secondary direct data mutation in HTML.

# Display names for common field types
FIELD_DISPLAY_NAMES = {
    "amount": "金额",
    "date": "日期",
    "note": "备注",
    "title": "标题",
    "name": "名称",
    "category": "分类",
    "count": "数量",
    "score": "评分",
    "duration": "时长",
    "url": "链接",
    "desc": "描述",
    "description": "描述",
    "content": "内容",
    "tag": "标签",
    "type": "类型",
    "priority": "优先级",
    "status": "状态",
}

def _field_label(field: dict) -> str:
    return FIELD_DISPLAY_NAMES.get(field["name"], field["name"])


def _input_group(field: dict) -> str:
    name = field["name"]
    ftype = field.get("type", "string")
    label = _field_label(field)
    required = field.get("required", False)
    req_mark = ' <span style="color:var(--danger)">*</span>' if required else ""

    if ftype == "number":
        inp = (f'<input id="f-{name}" class="input" inputmode="decimal" '
               f'placeholder="0" autocomplete="off">')
    elif ftype == "date":
        inp = f'<input id="f-{name}" class="input" type="date">'
    elif ftype == "boolean":
        inp = (f'<label style="display:flex;align-items:center;gap:8px;'
               f'min-height:44px;cursor:pointer">'
               f'<input id="f-{name}" type="checkbox" style="width:18px;height:18px"> '
               f'{label}</label>')
        return f'<div class="input-group">{inp}</div>'
    else:
        inp = (f'<input id="f-{name}" class="input" type="text" '
               f'placeholder="{label}" autocomplete="off">')

    return (f'<div class="input-group">'
            f'<label class="input-label" for="f-{name}">{label}{req_mark}</label>'
            f'{inp}</div>')


def _field_type_from_input(type_str: str) -> str:
    return str(type_str or "string").rstrip("?")


def _form_fields_for_create(create_action: dict, fields: list) -> list:
    inputs = create_action.get("inputs", {})
    if not isinstance(inputs, dict) or not inputs:
        return fields

    by_name = {f["name"]: f for f in fields if "name" in f}
    form_fields = []
    for name, type_str in inputs.items():
        if name in ("id", "item_id", "itemId", "status"):
            continue
        base = dict(by_name.get(name, {"name": name}))
        base["type"] = base.get("type") or _field_type_from_input(str(type_str))
        base["required"] = bool(base.get("required")) or not str(type_str).endswith("?")
        form_fields.append(base)

    return form_fields or fields


def _js_identifier(name: str) -> str:
    cleaned = re.sub(r"\W+", "_", name)
    if not cleaned or not re.match(r"^[A-Za-z_]", cleaned):
        cleaned = f"f_{cleaned}"
    return cleaned


def _build_to_view_item(fields: list) -> str:
    lines = ["function toViewItem(item) {",
             "  const f = (item && item.fields) ? item.fields : {};",
             "  return {",
             "    id:     item.id,",
             "    title:  item.title || f.title || '',",
             "    status: item.status || 'active',"]
    for field in fields:
        name = field["name"]
        if name == "title":
            continue
        ftype = field.get("type", "string")
        if ftype == "number":
            lines.append(f"    {name}: Number(f.{name} || 0),")
        elif ftype == "boolean":
            lines.append(f"    {name}: Boolean(f.{name}),")
        else:
            lines.append(f"    {name}: f.{name} || '',")
    lines += ["  };", "}"]
    return "\n".join(lines)


def _meta_fields_html(fields: list) -> str:
    """Generate card-meta line showing domain fields (excluding title/name)."""
    display_fields = [f for f in fields if f["name"] not in ("title", "name")]
    if not display_fields:
        return ""
    parts = []
    for f in display_fields:
        label = _field_label(f)
        name = f["name"]
        ftype = f.get("type", "string")
        if ftype == "number":
            parts.append(f'<span>{label}: ${{vi.{name}}}</span>')
        elif ftype == "date":
            parts.append(f'<span>{label}: ${{vi.{name}}}</span>')
        elif ftype == "boolean":
            parts.append(f'<span>{label}: ${{vi.{name} ? "是" : "否"}}</span>')
        else:
            parts.append(f'<span>${{esc(vi.{name})}}</span>')
    return ' <span class="card-meta-sep">·</span> '.join(parts)


def _build_render_item(fields: list, archive_action_id: str | None) -> str:
    meta_html = _meta_fields_html(fields)
    meta_line = (f'+ \'<div class="card-meta">\' + `{meta_html}` + \'</div>\''
                 if meta_html else "")

    archive_btn = ""
    if archive_action_id:
        archive_btn = (
            f"+ '<button class=\"btn-archive\" "
            f"onclick=\"archiveItem(\\''+vi.id+'\\')\">归档</button>'"
        )

    return textwrap.dedent(f"""
        function renderItem(vi) {{
          return '<div class="card" data-oob-id="item-' + vi.id + '">'
            + '<div class="card-row">'
            + '<span class="card-title">' + esc(vi.title) + '</span>'
            {archive_btn}
            + '</div>'
            {meta_line}
            + '</div>';
        }}
    """).strip()


def _build_agent_section(agent_actions: list) -> str:
    """
    Agent actions are the PRIMARY input route — full-width buttons at the top.
    User taps → Agent handles input via conversation/photo → writes data → HTML updates.
    No forms needed here.
    """
    if not agent_actions:
        return ""

    buttons = []
    fns = []
    for a in agent_actions:
        action_id = a["id"]
        fn_name = "trigger_" + action_id.replace(".", "_")
        display = a.get("displayName", action_id)
        btn_id = "btn-" + action_id.replace(".", "-")
        buttons.append(
            f'<button class="btn-primary" id="{btn_id}" data-oob-id="{btn_id}" '
            f'onclick="{fn_name}(this)">{display}</button>'
        )
        fns.append(textwrap.dedent(f"""
            async function {fn_name}(btn) {{
              btn.disabled = true;
              btn.textContent = '处理中…';
              const result = await window.oob.callApi('{action_id}', {{}});
              // callApi returns once the task is SUBMITTED (not completed).
              // Re-enable immediately so user isn't blocked if agent runs long or fails.
              btn.disabled = false;
              btn.textContent = '{a.get("displayName", action_id)}';
              if (result && result.status === 'pending') {{
                showStatus('后台处理中，完成后自动更新…');
              }} else if (result && !result.success) {{
                showError(result.errorMessage || '操作失败');
              }}
              // Final result arrives via onProjectUpdated
            }}
        """).strip())

    buttons_html = "\n  ".join(buttons)
    fns_js = "\n\n".join(fns)
    return textwrap.dedent(f"""
        <div id="action-bar" data-oob-id="action-bar">
          {buttons_html}
        </div>

        <script id="script-agent">
        {fns_js}
        </script>
    """).strip()


def _build_create_form_section(create_action: dict | None, fields: list) -> str:
    """
    Pure CRUD Projects need one direct input path; keep it small and deterministic.
    Agent-backed Projects skip this because the agent owns user input collection.
    """
    if not create_action:
        return ""

    action_id = create_action["id"]
    display = create_action.get("displayName", create_action.get("description", "添加"))
    form_fields = _form_fields_for_create(create_action, fields)
    form_html = "\n    ".join(_input_group(f) for f in form_fields)

    collect_lines = ["function collectCreateInputs() {", "  const inputs = {};"]
    reset_lines = ["function resetCreateInputs() {"]
    required_keys = []

    for field in form_fields:
        name = field["name"]
        var_name = _js_identifier(name)
        ftype = field.get("type", "string")
        if ftype == "boolean":
            collect_lines.append(
                f"  inputs[{json.dumps(name, ensure_ascii=False)}] = "
                f"Boolean(document.getElementById('f-{name}').checked);"
            )
            reset_lines.append(f"  document.getElementById('f-{name}').checked = false;")
        elif ftype == "number":
            collect_lines.append(
                f"  const raw_{var_name} = document.getElementById('f-{name}').value.trim();"
            )
            collect_lines.append(
                f"  if (raw_{var_name}) inputs[{json.dumps(name, ensure_ascii=False)}] = "
                f"Number(raw_{var_name});"
            )
            reset_lines.append(f"  document.getElementById('f-{name}').value = '';")
            if field.get("required"):
                required_keys.append(name)
        else:
            collect_lines.append(
                f"  const raw_{var_name} = document.getElementById('f-{name}').value.trim();"
            )
            collect_lines.append(
                f"  if (raw_{var_name}) inputs[{json.dumps(name, ensure_ascii=False)}] = "
                f"raw_{var_name};"
            )
            reset_lines.append(f"  document.getElementById('f-{name}').value = '';")
            if field.get("required"):
                required_keys.append(name)

    collect_lines.append("  return inputs;")
    collect_lines.append("}")
    reset_lines.append("}")

    required_guard = ""
    if required_keys:
        required_keys_json = json.dumps(required_keys, ensure_ascii=False)
        required_guard = (
            f"if ({required_keys_json}.some(function(k) {{ "
            "return inputs[k] === undefined || inputs[k] === null || inputs[k] === ''; "
            "} })) { showError('请填写必填项'); return; }"
        )
    else:
        required_guard = (
            "if (!Object.keys(inputs).length) "
            "{ showError('请先填写一条记录'); return; }"
        )

    return textwrap.dedent(f"""
        <div id="create-form" class="form-panel" data-oob-id="create-form">
          {form_html}
          <div class="form-actions">
            <button class="btn-primary" id="btn-{action_id.replace(".", "-")}"
              data-oob-id="btn-{action_id.replace(".", "-")}"
              onclick="submitCreate(this)">{display}</button>
          </div>
        </div>

        <script id="script-create-form">
        {"".join(line + chr(10) for line in collect_lines).rstrip()}

        {"".join(line + chr(10) for line in reset_lines).rstrip()}

        async function submitCreate(btn) {{
          showStatus('');
          const inputs = collectCreateInputs();
          {required_guard}
          btn.disabled = true;
          const oldText = btn.textContent;
          btn.textContent = '保存中…';
          try {{
            const result = await window.oob.callApi('{action_id}', inputs);
            if (!result.success) {{ showError(result.errorMessage || '保存失败'); return; }}
            resetCreateInputs();
            if (result.project) render(activeViewItems(result.project));
            showStatus('已保存');
          }} catch (err) {{
            showError(err && err.message ? err.message : '保存失败');
          }} finally {{
            btn.disabled = false;
            btn.textContent = oldText;
          }}
        }}
        </script>
    """).strip()


def _build_archive_fn(archive_action: dict | None) -> str:
    if not archive_action:
        return ""
    action_id = archive_action["id"]
    return textwrap.dedent(f"""
        async function archiveItem(itemId) {{
          const result = await window.oob.callApi('{action_id}', {{item_id: itemId}});
          if (!result.success) {{ showError(result.errorMessage || '操作失败'); return; }}
          if (result.project) render(activeViewItems(result.project));
        }}
    """).strip()


def _build_summary_js(fields: list, views: dict) -> str:
    """Generate JS that computes the summary stat shown in the header."""
    primary = views.get("primary", "")
    # Try to find a numeric field to sum for the summary
    num_fields = [f["name"] for f in fields if f.get("type") == "number"]
    if num_fields and primary:
        fname = num_fields[0]
        return textwrap.dedent(f"""
            function updateSummary(items) {{
              const total = items.reduce(function(s, vi) {{ return s + (vi.{fname} || 0); }}, 0);
              const el = document.getElementById('summary');
              if (el) el.textContent = total.toLocaleString();
            }}
        """).strip()
    return "function updateSummary(items) {}"


def build_html(contract: dict, apis: list) -> str:
    entity = contract.get("entity", {})
    fields = contract.get("fields", [])
    views = contract.get("views", {})
    project_name = contract.get("name", entity.get("name", "Project"))
    empty_text = views.get("empty", "暂无记录，点击上方按钮开始")
    primary_view = views.get("primary", "")

    archive_action = next(
        (a for a in contract.get("actions", [])
         if a.get("executor") == "native.collection.archive"), None
    )
    agent_actions = [a for a in contract.get("actions", []) if a.get("executor") == "agent"]
    create_action = next(
        (a for a in contract.get("actions", [])
         if a.get("executor") == "native.collection.create"), None
    )

    to_view_item_js = _build_to_view_item(fields)
    render_item_js = _build_render_item(
        fields, archive_action["id"] if archive_action else None
    )
    # Agent-backed projects use a conversation button; pure CRUD uses one compact form.
    action_entry_html = (
        _build_agent_section(agent_actions)
        if agent_actions else _build_create_form_section(create_action, fields)
    )
    archive_fn_js = _build_archive_fn(archive_action)
    summary_js = _build_summary_js(fields, views)

    # Summary stat label for header
    summary_label_html = (
        f'<div class="summary-label">{primary_view}</div>' if primary_view else ""
    )
    num_fields = [f for f in fields if f.get("type") == "number"]
    summary_html = (
        f'<div class="summary" id="summary" data-oob-id="summary">--</div>\n'
        f'    {summary_label_html}'
        if num_fields and primary_view else ""
    )

    html = textwrap.dedent(f"""\
        <!doctype html>
        <html lang="zh">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
          <title>{project_name}</title>
          <link rel="stylesheet" href="base.css">
          <style>
            * {{ -webkit-tap-highlight-color: transparent; }}
            input, button, select {{ -webkit-appearance: none; }}
            .card-meta-sep {{ color: var(--border); margin: 0 4px; }}
            #action-bar {{ margin-bottom: 8px; }}
          </style>
        </head>
        <body>
        <div class="container">

          <div class="header" data-oob-id="header">
            <h1 class="title">{project_name}</h1>
            {summary_html}
          </div>

          <div id="status"></div>

          {action_entry_html}

          <div id="main" data-oob-id="main"></div>

        </div>
        <script>
        function esc(s) {{
          return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
        }}
        function showStatus(msg) {{
          document.getElementById('status').innerHTML = msg
            ? '<span class="status-msg">' + msg + '</span>' : '';
        }}
        function showError(msg) {{
          document.getElementById('status').innerHTML =
            '<div class="error-msg">' + esc(msg) + '</div>';
        }}

        {to_view_item_js}

        function activeViewItems(project) {{
          return (project.items || [])
            .filter(function(i) {{ return (i.status || 'active') === 'active'; }})
            .map(toViewItem);
        }}

        {render_item_js}

        {summary_js}

        function render(items) {{
          const el = document.getElementById('main');
          if (!items.length) {{
            el.innerHTML = '<div class="empty"><div class="empty-text">{empty_text}</div></div>';
            return;
          }}
          el.innerHTML = items.map(renderItem).join('');
          updateSummary(items);
        }}

        {archive_fn_js}

        window.addEventListener('load', async function() {{
          showStatus('加载中…');
          const project = await window.oob.getProject();
          render(activeViewItems(project));
          showStatus('');
        }});

        window.oob.onProjectUpdated(function(project) {{
          if (project._taskError) {{ showError(project.errorMessage || '操作失败'); return; }}
          showStatus('');
          render(activeViewItems(project));
        }});
        </script>
        </body>
        </html>
    """)
    return html


# ── Project doc generation ────────────────────────────────────────────────────

def build_project_soul(contract: dict) -> str:
    from datetime import date
    today = date.today().isoformat()
    name = contract.get("name", "")
    entity = contract.get("entity", {})
    primary_action = entity.get("primaryAction", "")
    fields = contract.get("fields", [])
    views = contract.get("views", {})
    user_intent = contract.get("userIntent", "")
    rules = contract.get("soulRules", [])

    field_lines = "\n".join(
        f"- {f['name']}: {f.get('type', 'string')}"
        + (", 必填" if f.get("required") else "")
        for f in fields
    )
    rules_text = "\n".join(f"- {r}" for r in rules) if rules else "暂无明确规则，待用户使用中逐步补充。"
    intent_text = user_intent if user_intent else f"用户创建 {name}，主操作：{primary_action}。"

    return f"""# {name} — Project Soul

## 这是什么
{name}：{primary_action}。

## 功能
- {primary_action}
- 查看已记录的{entity.get('name', '')}列表
- 删除记录

## 创建意图
{intent_text}

## 业务规则
{rules_text}

## 字段约束
{field_lines}

## 显示偏好
- 列表排序：{views.get('list', '按创建时间倒序')}
- 空状态：{views.get('empty', '暂无记录')}

## 禁止行为
- 不得生成模拟数据或占位数据
- 不得在未告知用户的情况下修改已有数据

## 更新历史
- {today}: 项目创建
"""


def build_project_context(contract: dict, apis: list) -> str:
    from datetime import date
    today = date.today().isoformat()
    fields = contract.get("fields", [])
    actions = contract.get("actions", [])

    # API contract table
    api_rows = "\n".join(
        f"| {a['toolId']} | {a['run']['use']} "
        f"| {', '.join(a['run'].get('capabilities', [])) if isinstance(a.get('run'), dict) else ''} "
        f"| {json.dumps(a['inputSchema'].get('properties', {}), ensure_ascii=False)} "
        f"| {a.get('description', '')} |"
        for a in apis
    )
    api_table = (
        "| Tool ID | Executor | Capabilities | Inputs | Description |\n"
        "|---|---|---|---|---|\n"
        + api_rows
    )

    # Field schema table
    field_rows = "\n".join(
        f"| {f['name']} | {f.get('type', 'string')} "
        f"| {'是' if f.get('required') else '否'} | |"
        for f in fields
    )
    field_table = (
        "| Field | Type | Required | Notes |\n"
        "|---|---|---|---|\n"
        + field_rows
    )

    # Agent API research stubs
    agent_stubs = ""
    for a in actions:
        if a.get("executor") == "agent":
            agent_stubs += f"""
### {a['id']} — {a.get('displayName', a['id'])}
- Capabilities: {', '.join(str(x) for x in a.get('capabilities', []))}
- 数据源：（待填写）
- 字段映射：（待填写）
- 调研日期：{today}
"""

    return f"""# {contract.get('name', '')} — Project Context

## API Contract
{api_table}

## Item Fields Schema
{field_table}

## Data Layout
items 存储于 `data/items.json`，envelope 格式：
```json
{{
  "id": "uuid",
  "title": "string",
  "status": "active | archived",
  "fields": {{
    {chr(10).join(f'    "{f["name"]}": {f.get("type", "string")},' for f in fields)}
  }},
  "createdAt": "ISO8601"
}}
```

## HTML Element Inventory (data-oob-id)
| oob-id | Element | Purpose |
|---|---|---|
| item-{{id}} | .item div | 每条记录容器 |

## API 领域知识
{agent_stubs if agent_stubs else "暂无 agent API。"}
"""


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--contract", help="ProjectContract as JSON string")
    group.add_argument("--contract-file", help="Path to contract JSON file")
    parser.add_argument("--custom-html", help="Path to custom index.html (skips default generator)")
    parser.add_argument(
        "--execute", action="store_true",
        help="Directly create the project via workbench_runtime.py instead of printing JSON. "
             "Use outside Android (Codex, local testing)."
    )
    parser.add_argument(
        "--workspace", default=None,
        help="Workspace root for --execute mode (default: ./workspace or $OOB_WORKSPACE)"
    )
    args = parser.parse_args()

    # Load contract
    try:
        if args.contract:
            contract = json.loads(args.contract)
        else:
            with open(args.contract_file, encoding="utf-8") as f:
                contract = json.load(f)
    except Exception as e:
        print(f"FAIL [input] cannot parse contract: {e}", file=sys.stderr)
        sys.exit(1)

    validate_contract(contract)

    apis = build_apis(contract.get("actions", []))
    registered_ids = [a["toolId"] for a in apis]

    # Determine index.html content
    if args.custom_html:
        try:
            with open(args.custom_html, encoding="utf-8") as f:
                index_html = f.read()
        except Exception as e:
            print(f"FAIL [input] cannot read --custom-html: {e}", file=sys.stderr)
            sys.exit(1)
    else:
        index_html = build_html(contract, apis)

    # Validate HTML before emitting
    script_dir = os.path.dirname(os.path.abspath(__file__))
    validator = os.path.join(script_dir, "validate_html.py")
    if os.path.exists(validator):
        import tempfile
        with tempfile.NamedTemporaryFile(mode="w", suffix=".html",
                                         delete=False, encoding="utf-8") as tmp:
            tmp.write(index_html)
            tmp_path = tmp.name
        try:
            result = subprocess.run(
                [sys.executable, validator,
                 "--html", tmp_path,
                 "--apis", json.dumps(apis, ensure_ascii=False)],
                capture_output=True, text=True
            )
            if result.stdout.strip():
                print(result.stdout.strip(), file=sys.stderr)
            if result.returncode != 0:
                print("FAIL [builder] HTML validation failed — see above", file=sys.stderr)
                sys.exit(1)
        finally:
            os.unlink(tmp_path)

    # Look for base.css next to this script's assets dir
    assets_dir = os.path.join(script_dir, "..", "assets")
    base_css_path = os.path.join(assets_dir, "base.css")
    html_files = [{"path": "index.html", "content": index_html}]
    if os.path.exists(base_css_path):
        with open(base_css_path, encoding="utf-8") as f:
            base_css = f.read()
        html_files.insert(0, {"path": "base.css", "content": base_css})

    # Generate project docs from contract (no agent writing needed)
    soul_md = build_project_soul(contract)
    context_md = build_project_context(contract, apis)

    output = {
        "projectId": contract["projectId"],
        "name": contract["name"],
        "entityName": contract["entity"]["name"],
        "apis": apis,
        "htmlFiles": html_files,
        # docs: write these to spacePath after workbench_project_create
        "docs": {
            "PROJECT_SOUL.md": soul_md,
            "PROJECT_CONTEXT.md": context_md,
        },
    }

    if args.execute:
        # Portable mode: delegate to workbench_runtime.py instead of printing JSON
        runtime = os.path.join(script_dir, "workbench_runtime.py")
        if not os.path.exists(runtime):
            print("FAIL [execute] workbench_runtime.py not found in same directory", file=sys.stderr)
            sys.exit(1)

        create_config = {k: v for k, v in output.items() if k != "docs"}
        cmd = [sys.executable, runtime]
        if args.workspace:
            cmd += ["--workspace", args.workspace]
        cmd += ["project", "create", "--config", json.dumps(create_config, ensure_ascii=False)]

        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.stderr.strip():
            print(result.stderr.strip(), file=sys.stderr)
        if result.returncode != 0:
            print("FAIL [execute] workbench_runtime.py failed", file=sys.stderr)
            sys.exit(1)

        create_result = json.loads(result.stdout)
        project_id = create_result.get("projectId", output["projectId"])

        # Write docs to the project space
        import os as _os
        space_path = create_result.get("project", {}).get("spacePath", "")
        if space_path and "docs" in output:
            for fname, content in output["docs"].items():
                doc_path = _os.path.join(space_path, fname)
                with open(doc_path, "w", encoding="utf-8") as f:
                    f.write(content)

        print(json.dumps(create_result, ensure_ascii=False, indent=2))
        if space_path:
            print(f"\nProject created at: {space_path}/", file=sys.stderr)
            print(f"Open HTML: {space_path}/frontend/html/index.html", file=sys.stderr)
    else:
        print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
