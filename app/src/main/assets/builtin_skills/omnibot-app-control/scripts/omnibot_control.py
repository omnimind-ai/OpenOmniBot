#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

DEFAULT_PACKAGES = ("cn.com.omnimind.bot", "cn.com.omnimind.bot.debug")
DB_NAME = "omnibot_cache_databaseoss"
FLUTTER_PREFS = "FlutterSharedPreferences"
LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
JSON_LIST_PREFIX = LIST_PREFIX + "!"
REDACT_MARKERS = ("token", "api_key", "apikey", "secret", "password", "bearer")


def discover_app_dir(package=None):
    candidates = []
    if package:
        candidates.append(package)
    env_package = os.environ.get("OMNIBOT_PACKAGE", "").strip()
    if env_package and env_package not in candidates:
        candidates.append(env_package)
    candidates.extend(p for p in DEFAULT_PACKAGES if p not in candidates)

    for pkg in candidates:
        for root in (Path("/data/data") / pkg, Path("/data/user/0") / pkg):
            if root.exists():
                return pkg, root
    raise SystemExit(
        "Could not find Omnibot app data. Pass --package cn.com.omnimind.bot or cn.com.omnimind.bot.debug."
    )


def paths(package=None):
    pkg, app_dir = discover_app_dir(package)
    return {
        "package": pkg,
        "appData": str(app_dir),
        "cache": str(app_dir / "cache"),
        "database": str(app_dir / "databases" / DB_NAME),
        "flutterPrefs": str(app_dir / "shared_prefs" / f"{FLUTTER_PREFS}.xml"),
        "workspaceAndroid": str(app_dir / "workspace"),
        "workspaceShell": "/workspace",
        "omnibotInternal": "/workspace/.omnibot",
        "mmkvRoot": str(app_dir / "files" / "mmkv"),
    }


def backup_file(path):
    path = Path(path)
    if not path.exists():
        return None
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = path.with_name(path.name + f".bak.{stamp}")
    shutil.copy2(path, backup)
    return backup


def backup_db(db_path):
    backups = []
    for suffix in ("", "-wal", "-shm"):
        candidate = Path(str(db_path) + suffix)
        if candidate.exists():
            backup = backup_file(candidate)
            if backup:
                backups.append(str(backup))
    return backups


def prefs_path(app_dir, prefs_name):
    return app_dir / "shared_prefs" / f"{prefs_name}.xml"


def normalize_key(key, prefs_name, raw_key=False):
    key = key.strip()
    if prefs_name == FLUTTER_PREFS and not raw_key and not key.startswith("flutter."):
        return "flutter." + key
    return key


def load_prefs(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or path.stat().st_size == 0:
        return ET.ElementTree(ET.Element("map"))
    return ET.parse(path)


def indent_xml(tree):
    try:
        ET.indent(tree, space="    ")
    except AttributeError:
        pass


def write_prefs(tree, path, make_backup=True):
    if make_backup:
        backup_file(path)
    indent_xml(tree)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tree.write(tmp, encoding="utf-8", xml_declaration=True)
    tmp.replace(path)


def find_pref(root, key):
    for child in list(root):
        if child.attrib.get("name") == key:
            return child
    return None


def decode_pref_node(node):
    tag = node.tag
    if tag == "string":
        value = node.text or ""
        if value.startswith(JSON_LIST_PREFIX):
            try:
                return "string-list", json.loads(value[len(JSON_LIST_PREFIX):])
            except Exception:
                return "string", value
        return "string", value
    if tag in ("boolean", "int", "long", "float"):
        raw = node.attrib.get("value", "")
        if tag == "boolean":
            return tag, raw.lower() == "true"
        if tag in ("int", "long"):
            try:
                return tag, int(raw)
            except ValueError:
                return tag, raw
        if tag == "float":
            try:
                return tag, float(raw)
            except ValueError:
                return tag, raw
    if tag == "set":
        return "set", [item.text or "" for item in node.findall("string")]
    return tag, node.attrib


def should_redact(key):
    lowered = key.lower()
    return any(marker in lowered for marker in REDACT_MARKERS)


def format_value(key, value, redact=True):
    if redact and should_redact(key):
        return "***REDACTED***"
    return value


def redact_json_value(key, value):
    if should_redact(key):
        return "***REDACTED***"
    if isinstance(value, dict):
        return {child_key: redact_json_value(child_key, child_value) for child_key, child_value in value.items()}
    if isinstance(value, list):
        return [redact_json_value(key, item) for item in value]
    return value


def parse_value(value_type, raw_value):
    value_type = value_type.lower().replace("_", "-")
    if value_type in ("bool", "boolean"):
        lowered = raw_value.strip().lower()
        if lowered not in ("true", "false", "1", "0", "yes", "no", "on", "off"):
            raise SystemExit("Boolean value must be true/false.")
        return "boolean", lowered in ("true", "1", "yes", "on")
    if value_type == "int":
        return "int", int(raw_value)
    if value_type == "long":
        return "long", int(raw_value)
    if value_type in ("float", "double"):
        return "float", float(raw_value)
    if value_type == "json":
        parsed = json.loads(raw_value)
        return "string", json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))
    if value_type in ("string-list", "list"):
        parsed = json.loads(raw_value)
        if not isinstance(parsed, list):
            raise SystemExit("string-list value must be a JSON array.")
        return "string", JSON_LIST_PREFIX + json.dumps([str(x) for x in parsed], ensure_ascii=False)
    if value_type == "string":
        return "string", raw_value
    raise SystemExit(f"Unsupported preference type: {value_type}")


def set_pref_node(root, key, node_type, value):
    old = find_pref(root, key)
    if old is not None:
        root.remove(old)
    if node_type == "string":
        node = ET.SubElement(root, "string", {"name": key})
        node.text = value
    elif node_type == "boolean":
        ET.SubElement(root, "boolean", {"name": key, "value": "true" if value else "false"})
    elif node_type == "int":
        ET.SubElement(root, "int", {"name": key, "value": str(value)})
    elif node_type == "long":
        ET.SubElement(root, "long", {"name": key, "value": str(value)})
    elif node_type == "float":
        ET.SubElement(root, "float", {"name": key, "value": str(value)})
    else:
        raise SystemExit(f"Unsupported XML node type: {node_type}")


def cmd_paths(args):
    print(json.dumps(paths(args.package), ensure_ascii=False, indent=2))


def cmd_prefs_list(args):
    _, app_dir = discover_app_dir(args.package)
    tree = load_prefs(prefs_path(app_dir, args.prefs))
    rows = []
    for node in tree.getroot():
        key = node.attrib.get("name", "")
        value_type, value = decode_pref_node(node)
        rows.append(
            {
                "key": key,
                "type": value_type,
                "value": format_value(key, value, not args.no_redact),
            }
        )
    print(json.dumps(rows, ensure_ascii=False, indent=2))


def cmd_prefs_get(args):
    _, app_dir = discover_app_dir(args.package)
    key = normalize_key(args.key, args.prefs, args.raw_key)
    tree = load_prefs(prefs_path(app_dir, args.prefs))
    node = find_pref(tree.getroot(), key)
    if node is None:
        raise SystemExit(f"Preference not found: {key}")
    value_type, value = decode_pref_node(node)
    print(
        json.dumps(
            {"key": key, "type": value_type, "value": format_value(key, value, not args.no_redact)},
            ensure_ascii=False,
            indent=2,
        )
    )


def cmd_prefs_set(args):
    _, app_dir = discover_app_dir(args.package)
    path = prefs_path(app_dir, args.prefs)
    key = normalize_key(args.key, args.prefs, args.raw_key)
    node_type, value = parse_value(args.type, args.value)
    tree = load_prefs(path)
    set_pref_node(tree.getroot(), key, node_type, value)
    write_prefs(tree, path)
    print(json.dumps({"updated": key, "prefs": str(path)}, ensure_ascii=False, indent=2))


def cmd_prefs_remove(args):
    _, app_dir = discover_app_dir(args.package)
    path = prefs_path(app_dir, args.prefs)
    key = normalize_key(args.key, args.prefs, args.raw_key)
    tree = load_prefs(path)
    node = find_pref(tree.getroot(), key)
    removed = False
    if node is not None:
        tree.getroot().remove(node)
        removed = True
        write_prefs(tree, path)
    print(json.dumps({"removed": removed, "key": key, "prefs": str(path)}, ensure_ascii=False, indent=2))


def cmd_prefs_json_merge(args):
    _, app_dir = discover_app_dir(args.package)
    path = prefs_path(app_dir, args.prefs)
    key = normalize_key(args.key, args.prefs, args.raw_key)
    patch = json.loads(args.patch)
    if not isinstance(patch, dict):
        raise SystemExit("Patch must be a JSON object.")
    tree = load_prefs(path)
    root = tree.getroot()
    node = find_pref(root, key)
    current = {}
    if node is not None:
        value_type, value = decode_pref_node(node)
        if value_type == "string" and str(value).strip():
            current = json.loads(value)
    if not isinstance(current, dict):
        current = {}
    current.update(patch)
    set_pref_node(root, key, "string", json.dumps(current, ensure_ascii=False, separators=(",", ":")))
    write_prefs(tree, path)
    print(json.dumps({"updated": key, "value": current}, ensure_ascii=False, indent=2))


def connect_db(package=None, readonly=False):
    path_map = paths(package)
    db_path = Path(path_map["database"])
    if not db_path.exists():
        raise SystemExit(f"Database not found: {db_path}")
    if readonly:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    else:
        conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    return conn, db_path


def is_read_sql(sql):
    head = sql.strip().split(None, 1)[0].lower() if sql.strip() else ""
    return head in ("select", "pragma", "with", "explain")


def redact_row(row, redact=True):
    return {key: format_value(key, row[key], redact) for key in row.keys()}


def extract_payload_text(payload_json):
    if not payload_json:
        return ""
    try:
        payload = json.loads(payload_json)
    except (TypeError, json.JSONDecodeError):
        return ""
    if not isinstance(payload, dict):
        return ""
    content = payload.get("content")
    if isinstance(content, dict):
        text = content.get("text")
        if isinstance(text, str):
            return text
    text = payload.get("text")
    return text if isinstance(text, str) else ""


def cmd_db_query(args):
    sql = args.sql.strip()
    if not is_read_sql(sql) and not args.write:
        raise SystemExit("Refusing mutation without --write.")
    conn, db_path = connect_db(args.package, readonly=not args.write)
    if args.write:
        backups = backup_db(db_path)
    else:
        backups = []
    params = json.loads(args.params) if args.params else []
    cur = conn.cursor()
    cur.execute(sql, params)
    if cur.description is not None:
        rows = [redact_row(row, not args.no_redact) for row in cur.fetchall()]
        if args.write:
            conn.commit()
            print(
                json.dumps(
                    {"rows": rows, "changedRows": cur.rowcount, "backups": backups},
                    ensure_ascii=False,
                    indent=2,
                )
            )
        else:
            print(json.dumps(rows, ensure_ascii=False, indent=2))
    else:
        conn.commit()
        print(json.dumps({"changedRows": cur.rowcount, "backups": backups}, ensure_ascii=False, indent=2))
    conn.close()


def cmd_conversation_list(args):
    conn, _ = connect_db(args.package, readonly=True)
    rows = conn.execute(
        """
        SELECT id,title,mode,isArchived,messageCount,promptTokenThreshold,
               datetime(updatedAt/1000,'unixepoch','localtime') AS updatedAtLocal
        FROM conversations
        ORDER BY updatedAt DESC
        LIMIT ?
        """,
        (args.limit,),
    ).fetchall()
    print(json.dumps([dict(row) for row in rows], ensure_ascii=False, indent=2))
    conn.close()


def cmd_chat_search(args):
    conn, _ = connect_db(args.package, readonly=True)
    like_keyword = f"%{args.keyword}%"
    params = [like_keyword, like_keyword]
    conv_filter = ""
    if args.conversation_id is not None:
        conv_filter = "AND conversationId=?"
        params.append(args.conversation_id)
    params.append(args.limit)
    rows = conn.execute(
        f"""
        SELECT id,conversationId,entryType,summary,payloadJson,
               datetime(createdAt/1000,'unixepoch','localtime') AS createdAtLocal
        FROM agent_conversation_entries
        WHERE (summary LIKE ? OR payloadJson LIKE ?) {conv_filter}
        ORDER BY id DESC
        LIMIT ?
        """,
        params,
    ).fetchall()
    results = []
    for row in rows:
        item = {
            "id": row["id"],
            "conversationId": row["conversationId"],
            "entryType": row["entryType"],
            "summary": row["summary"],
            "createdAtLocal": row["createdAtLocal"],
        }
        if args.text:
            item["textPreview"] = extract_payload_text(row["payloadJson"])[: args.text_limit]
        results.append(item)
    print(json.dumps(results, ensure_ascii=False, indent=2))
    conn.close()


def cmd_chat_conversation(args):
    conn, _ = connect_db(args.package, readonly=True)
    conversation = conn.execute(
        """
        SELECT id,title,mode,isArchived,messageCount,summary,lastMessage,
               datetime(createdAt/1000,'unixepoch','localtime') AS createdAtLocal,
               datetime(updatedAt/1000,'unixepoch','localtime') AS updatedAtLocal
        FROM conversations
        WHERE id=?
        """,
        (args.conversation_id,),
    ).fetchone()
    if conversation is None:
        raise SystemExit(f"Conversation not found: {args.conversation_id}")
    rows = conn.execute(
        """
        SELECT id,entryId,entryType,status,summary,payloadJson,
               datetime(createdAt/1000,'unixepoch','localtime') AS createdAtLocal
        FROM agent_conversation_entries
        WHERE conversationId=?
        ORDER BY id
        LIMIT ?
        """,
        (args.conversation_id, args.limit),
    ).fetchall()
    entries = []
    for row in rows:
        item = {
            "id": row["id"],
            "entryId": row["entryId"],
            "entryType": row["entryType"],
            "status": row["status"],
            "summary": row["summary"],
            "createdAtLocal": row["createdAtLocal"],
        }
        if args.text:
            item["text"] = extract_payload_text(row["payloadJson"])
        if args.payload:
            item["payloadJson"] = row["payloadJson"]
        entries.append(item)
    print(
        json.dumps(
            {"conversation": dict(conversation), "entries": entries},
            ensure_ascii=False,
            indent=2,
        )
    )
    conn.close()


def cmd_chat_recent(args):
    conn, _ = connect_db(args.package, readonly=True)
    rows = conn.execute(
        """
        SELECT id,title,mode,isArchived,messageCount,lastMessage,
               datetime(createdAt/1000,'unixepoch','localtime') AS createdAtLocal,
               datetime(updatedAt/1000,'unixepoch','localtime') AS updatedAtLocal
        FROM conversations
        ORDER BY updatedAt DESC
        LIMIT ?
        """,
        (args.limit,),
    ).fetchall()
    print(json.dumps([dict(row) for row in rows], ensure_ascii=False, indent=2))
    conn.close()


def cmd_chat_stats(args):
    conn, _ = connect_db(args.package, readonly=True)
    total_entries = conn.execute("SELECT COUNT(*) FROM agent_conversation_entries").fetchone()[0]
    total_conversations = conn.execute("SELECT COUNT(*) FROM conversations").fetchone()[0]
    entry_types = conn.execute(
        """
        SELECT entryType,COUNT(*) AS count
        FROM agent_conversation_entries
        GROUP BY entryType
        ORDER BY count DESC
        """
    ).fetchall()
    time_range = conn.execute(
        """
        SELECT MIN(datetime(createdAt/1000,'unixepoch','localtime')) AS firstConversationAt,
               MAX(datetime(createdAt/1000,'unixepoch','localtime')) AS lastConversationAt
        FROM conversations
        """
    ).fetchone()
    print(
        json.dumps(
            {
                "totalEntries": total_entries,
                "totalConversations": total_conversations,
                "entryTypes": [dict(row) for row in entry_types],
                "timeRange": dict(time_range),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    conn.close()


def cmd_conversation_update(args):
    conn, db_path = connect_db(args.package)
    updates = []
    params = []
    if args.title is not None:
        updates.append("title=?")
        params.append(args.title)
    if args.archived is not None:
        updates.append("isArchived=?")
        params.append(1 if args.archived.lower() == "true" else 0)
    if args.threshold is not None:
        updates.append("promptTokenThreshold=?")
        params.append(args.threshold)
    if not updates:
        raise SystemExit("Nothing to update.")
    updates.append("updatedAt=?")
    params.append(int(time.time() * 1000))
    params.append(args.conversation_id)
    backups = backup_db(db_path)
    cur = conn.execute(f"UPDATE conversations SET {', '.join(updates)} WHERE id=?", params)
    conn.commit()
    print(json.dumps({"changedRows": cur.rowcount, "backups": backups}, ensure_ascii=False, indent=2))
    conn.close()


def workspace_file(kind):
    mapping = {
        "soul": Path("/workspace/.omnibot/agent/SOUL.md"),
        "chat": Path("/workspace/.omnibot/agent/CHAT.md"),
        "long": Path("/workspace/.omnibot/memory/MEMORY.md"),
    }
    if kind not in mapping:
        raise SystemExit(f"Unknown workspace file kind: {kind}")
    return mapping[kind]


def cmd_workspace_read(args):
    path = workspace_file(args.kind)
    if not path.exists():
        raise SystemExit(f"File not found: {path}")
    sys.stdout.write(path.read_text(encoding="utf-8"))


def cmd_workspace_write(args):
    path = workspace_file(args.kind)
    path.parent.mkdir(parents=True, exist_ok=True)
    backup_file(path)
    content = sys.stdin.read() if args.stdin else args.content
    if content is None:
        raise SystemExit("Provide content or --stdin.")
    path.write_text(content.rstrip() + "\n", encoding="utf-8")
    print(json.dumps({"written": str(path)}, ensure_ascii=False, indent=2))


def cmd_memory_read(args):
    if args.kind == "today":
        date = datetime.now().strftime("%y-%m-%d")
        path = Path("/workspace/.omnibot/memory/short-memories") / f"{date}.md"
    else:
        path = workspace_file("long")
    if not path.exists():
        return
    sys.stdout.write(path.read_text(encoding="utf-8"))


def cmd_memory_append_long(args):
    path = workspace_file("long")
    path.parent.mkdir(parents=True, exist_ok=True)
    backup_file(path)
    line = args.text.strip()
    if not line:
        raise SystemExit("Memory text is empty.")
    with path.open("a", encoding="utf-8") as handle:
        handle.write(("" if line.startswith("- ") else "- ") + line.removeprefix("- ").strip() + "\n")
    print(json.dumps({"appended": str(path)}, ensure_ascii=False, indent=2))


def cmd_memory_append_daily(args):
    date = args.date or datetime.now().strftime("%Y-%m-%d")
    parsed = datetime.strptime(date, "%Y-%m-%d")
    path = Path("/workspace/.omnibot/memory/short-memories") / f"{parsed.strftime('%y-%m-%d')}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text(f"# {date} Daily Memory\n\n", encoding="utf-8")
    backup_file(path)
    now = datetime.now().strftime("%H:%M:%S")
    path.open("a", encoding="utf-8").write(f"- [{now}] {args.text.strip()}\n")
    print(json.dumps({"appended": str(path)}, ensure_ascii=False, indent=2))


def app_control_authority(package):
    return f"{package}.appcontrol"


def app_control_binary():
    return shutil.which("content") or "/system/bin/content"


def cmd_app_control(args):
    pkg, app_dir = discover_app_dir(args.package)
    payload = json.loads(args.payload)
    if not isinstance(payload, dict):
        raise SystemExit("Payload must be a JSON object.")
    response_path = args.response_path or str(Path(app_dir) / "cache" / f"app_control_{int(time.time() * 1000)}.json")
    payload["responsePath"] = response_path
    uri = f"content://{app_control_authority(pkg)}"
    cmd = [
        app_control_binary(),
        "call",
        "--uri",
        uri,
        "--method",
        "control",
        "--arg",
        json.dumps(payload, ensure_ascii=False),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(proc.stderr.strip() or proc.stdout.strip() or f"content call failed with code {proc.returncode}")
    response_file = Path(response_path)
    if not response_file.exists():
        preview = proc.stdout.strip() or proc.stderr.strip()
        raise SystemExit(f"Response file not found: {response_file}\n{preview}")
    result = json.loads(response_file.read_text(encoding="utf-8"))
    if not args.no_redact:
        result = redact_json_value("root", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


def probe_http_endpoint(url, timeout):
    request = urllib.request.Request(url, headers={"User-Agent": "omnibot-control/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(500).decode("utf-8", errors="replace")
            return {
                "url": url,
                "connected": True,
                "status": response.status,
                "reason": response.reason,
                "preview": body,
            }
    except urllib.error.HTTPError as exc:
        body = exc.read(500).decode("utf-8", errors="replace")
        return {
            "url": url,
            "connected": True,
            "status": exc.code,
            "reason": exc.reason,
            "preview": body,
        }
    except Exception as exc:
        return {
            "url": url,
            "connected": False,
            "error": f"{exc.__class__.__name__}: {exc}",
        }


def cmd_local_model_probe(args):
    base_urls = []
    if args.base_url:
        base_urls.append(args.base_url.rstrip("/"))
    else:
        ports = args.port or [9099]
        base_urls.extend(f"http://127.0.0.1:{port}" for port in ports)
    endpoints = args.endpoint or ["/v1/models", "/health", "/"]
    results = []
    for base_url in base_urls:
        for endpoint in endpoints:
            path = endpoint if endpoint.startswith("/") else "/" + endpoint
            result = probe_http_endpoint(base_url + path, args.timeout)
            results.append(result)
            if result.get("connected"):
                break
    print(json.dumps(results, ensure_ascii=False, indent=2))


def build_parser():
    parser = argparse.ArgumentParser(description="Control Omnibot app-local state safely.")
    parser.add_argument("--package", help="Android package name.")
    sub = parser.add_subparsers(required=True)

    p = sub.add_parser("paths")
    p.set_defaults(func=cmd_paths)

    p = sub.add_parser("prefs-list")
    p.add_argument("--prefs", default=FLUTTER_PREFS)
    p.add_argument("--no-redact", action="store_true")
    p.set_defaults(func=cmd_prefs_list)

    p = sub.add_parser("prefs-get")
    p.add_argument("key")
    p.add_argument("--prefs", default=FLUTTER_PREFS)
    p.add_argument("--raw-key", action="store_true")
    p.add_argument("--no-redact", action="store_true")
    p.set_defaults(func=cmd_prefs_get)

    p = sub.add_parser("prefs-set")
    p.add_argument("key")
    p.add_argument("type")
    p.add_argument("value")
    p.add_argument("--prefs", default=FLUTTER_PREFS)
    p.add_argument("--raw-key", action="store_true")
    p.set_defaults(func=cmd_prefs_set)

    p = sub.add_parser("prefs-remove")
    p.add_argument("key")
    p.add_argument("--prefs", default=FLUTTER_PREFS)
    p.add_argument("--raw-key", action="store_true")
    p.set_defaults(func=cmd_prefs_remove)

    p = sub.add_parser("prefs-json-merge")
    p.add_argument("key")
    p.add_argument("patch")
    p.add_argument("--prefs", default=FLUTTER_PREFS)
    p.add_argument("--raw-key", action="store_true")
    p.set_defaults(func=cmd_prefs_json_merge)

    p = sub.add_parser("db-query")
    p.add_argument("sql")
    p.add_argument("--params", default="")
    p.add_argument("--write", action="store_true")
    p.add_argument("--no-redact", action="store_true")
    p.set_defaults(func=cmd_db_query)

    p = sub.add_parser("conversation-list")
    p.add_argument("--limit", type=int, default=20)
    p.set_defaults(func=cmd_conversation_list)

    p = sub.add_parser("chat-search")
    p.add_argument("keyword")
    p.add_argument("--limit", type=int, default=20)
    p.add_argument("--conversation-id", "--conv-id", type=int)
    p.add_argument("--text", action="store_true", help="Include text preview parsed from payloadJson.")
    p.add_argument("--text-limit", type=int, default=200)
    p.set_defaults(func=cmd_chat_search)

    p = sub.add_parser("chat-conversation")
    p.add_argument("conversation_id", type=int)
    p.add_argument("--limit", type=int, default=200)
    p.add_argument("--text", action="store_true", help="Include parsed text from payloadJson.")
    p.add_argument("--payload", action="store_true", help="Include raw payloadJson.")
    p.set_defaults(func=cmd_chat_conversation)

    p = sub.add_parser("chat-recent")
    p.add_argument("--limit", type=int, default=20)
    p.set_defaults(func=cmd_chat_recent)

    p = sub.add_parser("chat-stats")
    p.set_defaults(func=cmd_chat_stats)

    p = sub.add_parser("conversation-update")
    p.add_argument("conversation_id", type=int)
    p.add_argument("--title")
    p.add_argument("--archived", choices=("true", "false"))
    p.add_argument("--threshold", type=int)
    p.set_defaults(func=cmd_conversation_update)

    p = sub.add_parser("workspace-read")
    p.add_argument("kind", choices=("soul", "chat", "long"))
    p.set_defaults(func=cmd_workspace_read)

    p = sub.add_parser("workspace-write")
    p.add_argument("kind", choices=("soul", "chat", "long"))
    p.add_argument("content", nargs="?")
    p.add_argument("--stdin", action="store_true")
    p.set_defaults(func=cmd_workspace_write)

    p = sub.add_parser("memory-read")
    p.add_argument("kind", choices=("long", "today"))
    p.set_defaults(func=cmd_memory_read)

    p = sub.add_parser("memory-append-long")
    p.add_argument("text")
    p.set_defaults(func=cmd_memory_append_long)

    p = sub.add_parser("memory-append-daily")
    p.add_argument("text")
    p.add_argument("--date", help="YYYY-MM-DD")
    p.set_defaults(func=cmd_memory_append_daily)

    p = sub.add_parser("local-model-probe")
    p.add_argument("--base-url", help="Probe a specific base URL, for example http://127.0.0.1:9099.")
    p.add_argument("--port", type=int, action="append", help="Probe a localhost port. Can be repeated.")
    p.add_argument("--endpoint", action="append", help="Endpoint path to probe. Can be repeated.")
    p.add_argument("--timeout", type=float, default=1.0)
    p.set_defaults(func=cmd_local_model_probe)

    p = sub.add_parser("app-control")
    p.add_argument("payload", help="JSON object payload, for example '{\"action\":\"setting.list\"}'.")
    p.add_argument("--response-path", help="Optional response file path. Defaults to app cache.")
    p.add_argument("--no-redact", action="store_true")
    p.set_defaults(func=cmd_app_control)

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
