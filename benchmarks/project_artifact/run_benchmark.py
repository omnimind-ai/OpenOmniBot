#!/usr/bin/env python3
"""Runner for ProjectArtifactBench/PABench cases."""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


BENCH_ROOT = Path(__file__).resolve().parent
REPO_ROOT = BENCH_ROOT.parents[1]
DEFAULT_CASES_DIR = BENCH_ROOT / "cases"
DEFAULT_ADAPTER = "oob_workbench"
CASE_SCHEMA_VERSION = "project_artifact.case.v1"
ADAPTER_BUILDERS = {
    "oob_workbench": (
        REPO_ROOT
        / "app/src/main/assets/builtin_skills/oob-project/scripts/build_project_from_contract.py"
    ),
}
TEXT_SUFFIXES = {".css", ".html", ".js", ".json", ".md", ".txt"}
OOB_RETIRED_IDS = {
    "oob-project-distiller",
    "oob-project-designer",
    "oob-native-workbench",
}
OOB_ALLOWED_RUN_PREFIXES = (
    "native.collection.",
    "agent",
    "script",
    "oob.",
    "mcp.",
)
SECRET_PATTERNS = [
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "private key block"),
    (
        re.compile(
            r"(?i)\b(api[_-]?key|secret|token|password|passwd|pwd)\b\s*[:=]\s*"
            r"['\"][^'\"]{8,}['\"]"
        ),
        "secret-looking assignment",
    ),
    (re.compile(r"\bOMNI_RELEASE_(STORE|KEY)_(PWD|ALIAS|FILE)\b"), "release signing variable"),
]
REQUIRED_CASE_KEYS = {
    "schema_version",
    "id",
    "title",
    "category",
    "capabilities",
    "instruction",
    "input",
    "oracle",
    "adapter",
}
PLUGIN_METRICS = [
    "adapter_contract_valid",
    "runtime_execution_pass",
    "ui_interaction_pass",
    "export_roundtrip_pass",
    "collateral_damage_free",
]


def make_metric(status: str, failures: list[str] | None = None) -> dict[str, Any]:
    score: int | None
    if status == "pass":
        score = 1
    elif status == "fail":
        score = 0
    else:
        score = None
    return {"status": status, "score": score, "failures": failures or []}


def metric_from_failures(failures: list[str], applicable: bool = True) -> dict[str, Any]:
    if not applicable:
        return make_metric("na")
    return make_metric("fail" if failures else "pass", failures)


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def resolve_case_path(case_file: Path, raw: str | None) -> Path | None:
    if not raw:
        return None
    path = Path(raw)
    if path.is_absolute():
        return path
    return (case_file.parent / path).resolve()


def project_apis(project: dict[str, Any]) -> list[dict[str, Any]]:
    apis = project.get("apis")
    if isinstance(apis, list):
        return [api for api in apis if isinstance(api, dict)]
    tools = project.get("tools")
    if isinstance(tools, list):
        return [tool for tool in tools if isinstance(tool, dict)]
    nested = project.get("project")
    if isinstance(nested, dict):
        nested_tools = nested.get("tools")
        if isinstance(nested_tools, list):
            return [tool for tool in nested_tools if isinstance(tool, dict)]
    return []


def api_id(api: dict[str, Any]) -> str:
    return str(api.get("toolId") or api.get("apiId") or "")


def api_ids(project: dict[str, Any]) -> set[str]:
    ids: set[str] = set()
    for api in project_apis(project):
        raw = api_id(api)
        if raw:
            ids.add(str(raw))
    return ids


def html_payload(output: dict[str, Any]) -> str:
    files = output.get("htmlFiles", [])
    if not isinstance(files, list):
        return ""
    chunks = []
    for item in files:
        if isinstance(item, dict):
            chunks.append(str(item.get("content", "")))
    return "\n".join(chunks)


def read_candidate_html(candidate_dir: Path) -> str:
    html_dir = candidate_dir / "frontend/html"
    chunks = []
    if html_dir.is_dir():
        for path in sorted(html_dir.rglob("*.html")):
            chunks.append(path.read_text(encoding="utf-8", errors="ignore"))
    return "\n".join(chunks)


def read_candidate_html_files(candidate_dir: Path) -> dict[str, str]:
    html_dir = candidate_dir / "frontend/html"
    files = {}
    if html_dir.is_dir():
        for path in sorted(html_dir.rglob("*.html")):
            rel = path.relative_to(candidate_dir).as_posix()
            files[rel] = path.read_text(encoding="utf-8", errors="ignore")
    return files


def find_project_json(candidate_dir: Path) -> Path | None:
    direct = candidate_dir / "project.json"
    if direct.is_file():
        return direct
    matches = sorted(candidate_dir.rglob("project.json"))
    return matches[0] if matches else None


def load_candidate_project(candidate_dir: Path) -> tuple[dict[str, Any] | None, list[str]]:
    project_json_path = find_project_json(candidate_dir)
    if not project_json_path:
        return None, ["project.json missing"]
    try:
        project = load_json(project_json_path)
    except Exception as exc:  # noqa: BLE001
        return None, [f"project.json is not valid JSON: {exc}"]
    if not isinstance(project, dict):
        return None, ["project.json must be a JSON object"]
    return project, []


def json_pointer(data: Any, pointer: str) -> Any:
    if pointer == "":
        return data
    current = data
    for part in pointer.lstrip("/").split("/"):
        key = part.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            current = current[int(key)]
        elif isinstance(current, dict):
            current = current[key]
        else:
            raise KeyError(pointer)
    return current


def text_files(root: Path) -> list[Path]:
    files = []
    for path in root.rglob("*"):
        if path.is_file() and path.suffix.lower() in TEXT_SUFFIXES:
            files.append(path)
    return files


def forbidden_path_hits(root: Path, patterns: list[str]) -> list[str]:
    hits = []
    for path in root.rglob("*"):
        rel = path.relative_to(root).as_posix()
        for pattern in patterns:
            if fnmatch.fnmatch(rel, pattern):
                hits.append(rel)
                break
    return sorted(hits)


def extract_call_api_ids(html: str) -> set[str]:
    return set(re.findall(r"""callApi\s*\(\s*['"]([^'"]+)['"]""", html))


def run_use(api: dict[str, Any]) -> str:
    run = api.get("run", {})
    return str(run.get("use", "")) if isinstance(run, dict) else ""


def schema_properties(schema: Any) -> dict[str, Any]:
    if not isinstance(schema, dict):
        return {}
    props = schema.get("properties", {})
    return props if isinstance(props, dict) else {}


def sample_value_for_schema(name: str, schema: Any) -> Any:
    if not isinstance(schema, dict):
        return f"sample-{name}"
    schema_type = schema.get("type")
    if schema.get("format") == "date":
        return "2026-01-01"
    if schema_type == "number":
        return 1.0
    if schema_type == "integer":
        return 1
    if schema_type == "boolean":
        return True
    if schema_type == "array":
        return []
    if schema_type == "object":
        return {}
    return f"sample-{name}"


def sample_inputs(input_schema: Any, item_id: str | None = None) -> dict[str, Any]:
    props = schema_properties(input_schema)
    inputs = {}
    for name, prop_schema in props.items():
        if name in {"item_id", "itemId", "id"} and item_id:
            inputs[name] = item_id
        else:
            inputs[name] = sample_value_for_schema(name, prop_schema)
    if item_id and not any(name in inputs for name in ("item_id", "itemId", "id")):
        inputs["item_id"] = item_id
    return inputs


def validate_oob_contract(
    case: dict[str, Any],
    candidate_dir: Path,
    project: dict[str, Any] | None,
    project_failures: list[str],
    html: str,
) -> tuple[list[str], bool]:
    static = case.get("oracle", {}).get("static", {})
    applicable = bool(
        project
        or static.get("required_api_ids")
        or "project.json" in static.get("required_files", [])
    )
    if not applicable:
        return [], False

    failures = [f"contract: {failure}" for failure in project_failures]
    if project is None:
        return failures, True

    if not str(project.get("projectId") or project.get("id") or "").strip():
        failures.append("contract: project id is missing")
    if not str(project.get("name") or "").strip():
        failures.append("contract: project name is missing")

    apis = project_apis(project)
    if not apis:
        failures.append("contract: no APIs/tools registered")

    seen = set()
    duplicate_ids = []
    for api in apis:
        current_id = api_id(api)
        if not current_id:
            failures.append("contract: API entry missing toolId/apiId")
            continue
        if current_id in seen:
            duplicate_ids.append(current_id)
        seen.add(current_id)
        if not re.match(r"^[a-z][a-zA-Z0-9]*\.[a-z][a-zA-Z0-9]*$", current_id):
            failures.append(f"contract: API id is not '<entity>.<verb>': {current_id}")

        use = run_use(api)
        if not use:
            failures.append(f"contract: API {current_id} missing run.use")
        elif not use.startswith(OOB_ALLOWED_RUN_PREFIXES):
            failures.append(f"contract: API {current_id} has unsupported run.use={use!r}")

        input_schema = api.get("inputSchema")
        if input_schema is not None and not isinstance(input_schema, dict):
            failures.append(f"contract: API {current_id} inputSchema must be an object")
        elif isinstance(input_schema, dict) and input_schema.get("type", "object") != "object":
            failures.append(f"contract: API {current_id} inputSchema.type must be object")

        output_schema = api.get("outputSchema")
        if output_schema is not None and not isinstance(output_schema, dict):
            failures.append(f"contract: API {current_id} outputSchema must be an object")

        if use == "agent":
            run = api.get("run", {})
            capabilities = run.get("capabilities", []) if isinstance(run, dict) else []
            if not isinstance(capabilities, list) or not any(str(cap).strip() for cap in capabilities):
                failures.append(f"contract: agent API {current_id} must declare capabilities")

    if duplicate_ids:
        failures.append(f"contract: duplicate API ids: {sorted(duplicate_ids)}")

    called_ids = extract_call_api_ids(html)
    unregistered = sorted(called_ids - api_ids(project))
    if unregistered:
        failures.append(f"contract: HTML calls unregistered APIs: {unregistered}")

    docs_required = static.get("required_docs", [])
    for doc_name in docs_required:
        doc_path = candidate_dir / doc_name
        if not doc_path.is_file():
            failures.append(f"contract: required doc missing: {doc_name}")
            continue
        text = doc_path.read_text(encoding="utf-8", errors="ignore")
        if len(text.strip()) < 40:
            failures.append(f"contract: required doc is too small to be useful: {doc_name}")

    return failures, True


def validate_oob_runtime_smoke(
    project: dict[str, Any] | None,
    project_failures: list[str],
) -> tuple[list[str], bool]:
    if project is None:
        return [f"runtime: {failure}" for failure in project_failures], bool(project_failures)

    native_apis = [
        api for api in project_apis(project)
        if run_use(api).startswith("native.collection.")
    ]
    if not native_apis:
        return [], False

    failures = []
    items: list[dict[str, Any]] = []
    next_id = 1

    def ensure_item() -> str:
        nonlocal next_id
        if not items:
            item = {
                "id": f"item-{next_id}",
                "title": "seed item",
                "status": "active",
                "fields": {},
            }
            next_id += 1
            items.insert(0, item)
        return str(items[0]["id"])

    for api in native_apis:
        current_id = api_id(api) or "<unknown>"
        use = run_use(api)
        inputs = sample_inputs(api.get("inputSchema", {}), item_id=ensure_item())
        action = use.removeprefix("native.collection.")
        try:
            if action == "create":
                props = schema_properties(api.get("inputSchema", {}))
                create_inputs = sample_inputs(api.get("inputSchema", {}))
                title = str(create_inputs.get("title") or create_inputs.get("name") or current_id)
                item = {
                    "id": f"item-{next_id}",
                    "title": title,
                    "status": "active",
                    "fields": {
                        key: value
                        for key, value in create_inputs.items()
                        if key not in {"title", "name", "label", "item_id", "itemId", "id"}
                    },
                }
                next_id += 1
                items.insert(0, item)
                if props and not item["title"]:
                    failures.append(f"runtime: {current_id} did not produce a title/name")
            elif action == "update":
                item_id = inputs.get("item_id") or inputs.get("itemId") or inputs.get("id")
                target = next((item for item in items if item["id"] == item_id), None)
                if target is None:
                    failures.append(f"runtime: {current_id} cannot update missing item_id={item_id}")
                    continue
                for key, value in inputs.items():
                    if key in {"item_id", "itemId", "id"}:
                        continue
                    if key == "title":
                        target["title"] = str(value)
                    else:
                        target.setdefault("fields", {})[key] = value
            elif action == "archive":
                item_id = inputs.get("item_id") or inputs.get("itemId") or inputs.get("id")
                target = next((item for item in items if item["id"] == item_id), None)
                if target is None:
                    failures.append(f"runtime: {current_id} cannot archive missing item_id={item_id}")
                    continue
                target["status"] = "archived"
            elif action == "list":
                list(items)
            elif action == "get":
                item_id = inputs.get("item_id") or inputs.get("itemId") or inputs.get("id")
                if item_id and not any(item["id"] == item_id for item in items):
                    failures.append(f"runtime: {current_id} cannot get missing item_id={item_id}")
            else:
                failures.append(f"runtime: unsupported native collection action {action!r} in {current_id}")
        except Exception as exc:  # noqa: BLE001
            failures.append(f"runtime: smoke execution failed for {current_id}: {exc}")

    return failures, True


def validate_oob_ui_smoke(
    candidate_dir: Path,
    project: dict[str, Any] | None,
    html_files: dict[str, str],
) -> tuple[list[str], bool]:
    if not html_files:
        return [], False

    failures = []
    html = "\n".join(html_files.values())
    if not re.search(r"<body\b", html, flags=re.IGNORECASE):
        failures.append("ui: no <body> element found")
    if not re.search(r"<(main|section|div)\b", html, flags=re.IGNORECASE):
        failures.append("ui: no visible layout container found")
    if "data-oob-id=" not in html:
        failures.append("ui: no stable data-oob-id attributes found")
    if "window.oob.getProject" not in html:
        failures.append("ui: window.oob.getProject is not used")
    if "window.oob.onProjectUpdated" not in html:
        failures.append("ui: window.oob.onProjectUpdated is not registered")

    called_ids = extract_call_api_ids(html)
    if project is not None and called_ids:
        missing_buttons = []
        for called_id in called_ids:
            stable_id = called_id.replace(".", "-")
            if stable_id not in html and called_id not in html:
                missing_buttons.append(called_id)
        if missing_buttons:
            failures.append(f"ui: called APIs lack recognizable control ids: {missing_buttons}")

    node = shutil.which("node")
    if node:
        for rel, content in html_files.items():
            for index, match in enumerate(
                re.finditer(r"(<script[^>]*>)([\s\S]*?)</script>", content, re.IGNORECASE)
            ):
                tag = match.group(1)
                script = match.group(2)
                if "type=\"module\"" in tag or "type='module'" in tag or not script.strip():
                    continue
                result = subprocess.run(
                    [node, "-e", f"new Function({json.dumps(script)});"],
                    capture_output=True,
                    text=True,
                    cwd=str(candidate_dir),
                )
                if result.returncode != 0:
                    detail = result.stderr.strip() or result.stdout.strip()
                    failures.append(f"ui: inline script {rel}#{index} has syntax error: {detail}")

    return failures, True


def validate_oob_export_smoke(
    case: dict[str, Any],
    candidate_dir: Path,
) -> tuple[list[str], bool]:
    static = case.get("oracle", {}).get("static", {})
    manifest_path = candidate_dir / "export_manifest.json"
    applicable = (
        case.get("category") in {"export", "migration"}
        or manifest_path.is_file()
        or bool(static.get("required_json_values"))
    )
    if not applicable:
        return [], False
    if not manifest_path.is_file():
        return ["export: export_manifest.json missing"], True

    try:
        manifest = load_json(manifest_path)
    except Exception as exc:  # noqa: BLE001
        return [f"export: export_manifest.json is not valid JSON: {exc}"], True
    if not isinstance(manifest, dict):
        return ["export: export_manifest.json must be an object"], True

    failures = []
    text = json.dumps(manifest, ensure_ascii=False)
    retired = sorted(retired_id for retired_id in OOB_RETIRED_IDS if retired_id in text)
    if retired:
        failures.append(f"export: retired OOB skill ids present: {retired}")

    source = manifest.get("source")
    if source is not None and source != "oob-project":
        failures.append(f"export: expected source 'oob-project', got {source!r}")

    skills = manifest.get("skills")
    if skills is not None:
        if not isinstance(skills, list) or not skills:
            failures.append("export: skills must be a non-empty list when present")
        else:
            first = skills[0] if isinstance(skills[0], dict) else {}
            if first.get("skillId") != "oob-project":
                failures.append(f"export: first skillId must be oob-project, got {first.get('skillId')!r}")
            if first.get("path") != "skills/oob-project/SKILL.md":
                failures.append(f"export: first skill path is not canonical: {first.get('path')!r}")

    entries = manifest.get("entries", [])
    if isinstance(entries, list):
        missing_entries = []
        for entry in entries:
            if not isinstance(entry, str) or entry.startswith("skills/"):
                continue
            if not (candidate_dir / entry).exists():
                missing_entries.append(entry)
        if missing_entries:
            failures.append(f"export: manifest entries missing from candidate: {missing_entries}")

    return failures, True


def validate_collateral_safety(candidate_dir: Path) -> tuple[list[str], bool]:
    failures = []
    for path in candidate_dir.rglob("*"):
        rel = path.relative_to(candidate_dir).as_posix()
        if path.is_symlink():
            try:
                target = path.resolve()
            except OSError:
                target = None
            if target is None or candidate_dir.resolve() not in target.parents:
                failures.append(f"safety: symlink escapes candidate root: {rel}")
        if path.is_file() and path.suffix.lower() in TEXT_SUFFIXES:
            text = path.read_text(encoding="utf-8", errors="ignore")
            for pattern, label in SECRET_PATTERNS:
                if pattern.search(text):
                    failures.append(f"safety: {label} found in {rel}")
                    break
    return failures, True


def validate_case_schema(case_file: Path, case: dict[str, Any]) -> list[str]:
    failures = []
    missing = sorted(REQUIRED_CASE_KEYS - set(case))
    if missing:
        failures.append(f"missing required keys: {missing}")
    if case.get("schema_version") != CASE_SCHEMA_VERSION:
        failures.append(f"schema_version must be {CASE_SCHEMA_VERSION}")
    if case.get("id") != case_file.stem:
        failures.append(f"id must match file name stem {case_file.stem!r}")
    adapter = case.get("adapter")
    if not isinstance(adapter, str) or not adapter.strip():
        failures.append("adapter must be a non-empty string")
    if not isinstance(case.get("capabilities"), list) or not case.get("capabilities"):
        failures.append("capabilities must be a non-empty list")
    if not isinstance(case.get("instruction"), str) or not case.get("instruction", "").strip():
        failures.append("instruction must be a non-empty string")
    if not isinstance(case.get("input"), dict):
        failures.append("input must be an object")
    if not isinstance(case.get("oracle"), dict):
        failures.append("oracle must be an object")
    return failures


def validate_fixture_paths(case_file: Path, case: dict[str, Any]) -> list[str]:
    failures = []
    case_input = case.get("input", {})
    for key in ("contract_fixture", "initial_project_fixture"):
        fixture = resolve_case_path(case_file, case_input.get(key))
        if fixture and not fixture.exists():
            failures.append(f"{key} does not exist: {fixture}")
    return failures


def adapter_for_case(case: dict[str, Any]) -> str:
    raw = case.get("adapter") or case.get("runtime_adapter") or DEFAULT_ADAPTER
    return str(raw)


def run_builder_baseline(
    case_file: Path,
    case: dict[str, Any],
) -> tuple[list[str], dict[str, Any], dict[str, Any]]:
    case_input = case.get("input", {})
    contract_fixture = resolve_case_path(case_file, case_input.get("contract_fixture"))
    adapter = adapter_for_case(case)
    builder = ADAPTER_BUILDERS.get(adapter)
    if not contract_fixture:
        return [], {"status": "not_applicable", "adapter": adapter}, {
            "builder_baseline_valid": make_metric("na"),
        }
    if builder is None:
        return [], {"status": "not_applicable", "adapter": adapter}, {
            "builder_baseline_valid": make_metric("na"),
        }
    if not builder.is_file():
        failures = [f"builder not found for adapter {adapter}: {builder}"]
        return failures, {"status": "missing_builder", "adapter": adapter}, {
            "builder_baseline_valid": make_metric("fail", failures),
        }

    started = time.time()
    result = subprocess.run(
        [sys.executable, str(builder), "--contract-file", str(contract_fixture)],
        capture_output=True,
        text=True,
        cwd=str(REPO_ROOT),
    )
    duration_ms = int((time.time() - started) * 1000)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        failures = [f"builder failed for {contract_fixture.name}: {detail}"]
        return failures, {
            "status": "failed",
            "adapter": adapter,
            "duration_ms": duration_ms,
        }, {
            "builder_baseline_valid": make_metric("fail", failures),
        }

    try:
        output = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        failures = [f"builder output is not JSON: {exc}"]
        return failures, {
            "status": "invalid_json",
            "adapter": adapter,
            "duration_ms": duration_ms,
        }, {
            "builder_baseline_valid": make_metric("fail", failures),
        }

    failures = []
    metric_failures: dict[str, list[str]] = {
        "baseline_project_id_match": [],
        "baseline_required_api_ids_present": [],
        "baseline_required_html_bindings_present": [],
        "baseline_forbidden_substrings_absent": [],
        "baseline_agent_capabilities_declared": [],
        "baseline_required_docs_present": [],
    }
    static = case.get("oracle", {}).get("static", {})
    expected_project_id = static.get("expected_project_id")
    if expected_project_id and output.get("projectId") != expected_project_id:
        metric_failures["baseline_project_id_match"].append(
            f"projectId {output.get('projectId')!r} != {expected_project_id!r}"
        )

    missing_apis = sorted(set(static.get("required_api_ids", [])) - api_ids(output))
    if missing_apis:
        metric_failures["baseline_required_api_ids_present"].append(
            f"missing API ids: {missing_apis}"
        )

    html = html_payload(output)
    missing_html = [
        substring
        for substring in static.get("required_html_substrings", [])
        if substring not in html
    ]
    if missing_html:
        metric_failures["baseline_required_html_bindings_present"].append(
            f"missing HTML substrings: {missing_html}"
        )

    forbidden_substrings = static.get("forbidden_substrings", [])
    forbidden_hits = [substring for substring in forbidden_substrings if substring in html]
    docs = output.get("docs", {})
    if isinstance(docs, dict):
        doc_text = "\n".join(str(value) for value in docs.values())
        forbidden_hits.extend(
            substring
            for substring in forbidden_substrings
            if substring in doc_text and substring not in forbidden_hits
        )
    if forbidden_hits:
        metric_failures["baseline_forbidden_substrings_absent"].append(
            f"contains forbidden substrings: {sorted(forbidden_hits)}"
        )

    if static.get("agent_apis_require_capabilities", False):
        missing_caps = []
        for api in project_apis(output):
            run = api.get("run", {})
            if isinstance(run, dict) and run.get("use") == "agent":
                caps = run.get("capabilities")
                if not isinstance(caps, list) or not any(str(cap).strip() for cap in caps):
                    missing_caps.append(api.get("toolId") or api.get("apiId") or "<unknown>")
        if missing_caps:
            metric_failures["baseline_agent_capabilities_declared"].append(
                f"agent APIs missing capabilities: {missing_caps}"
            )

    required_docs = static.get("required_docs", [])
    if required_docs:
        docs_obj = output.get("docs", {})
        available_docs = set(docs_obj) if isinstance(docs_obj, dict) else set()
        missing_docs = sorted(set(required_docs) - available_docs)
        if missing_docs:
            metric_failures["baseline_required_docs_present"].append(
                f"missing docs: {missing_docs}"
            )

    for items in metric_failures.values():
        failures.extend(items)

    metrics = {
        name: metric_from_failures(items, applicable=bool(applicable))
        for name, items, applicable in [
            (
                "baseline_project_id_match",
                metric_failures["baseline_project_id_match"],
                expected_project_id,
            ),
            (
                "baseline_required_api_ids_present",
                metric_failures["baseline_required_api_ids_present"],
                static.get("required_api_ids"),
            ),
            (
                "baseline_required_html_bindings_present",
                metric_failures["baseline_required_html_bindings_present"],
                static.get("required_html_substrings"),
            ),
            (
                "baseline_forbidden_substrings_absent",
                metric_failures["baseline_forbidden_substrings_absent"],
                static.get("forbidden_substrings"),
            ),
            (
                "baseline_agent_capabilities_declared",
                metric_failures["baseline_agent_capabilities_declared"],
                static.get("agent_apis_require_capabilities", False),
            ),
            (
                "baseline_required_docs_present",
                metric_failures["baseline_required_docs_present"],
                required_docs,
            ),
        ]
    }
    metrics["builder_baseline_valid"] = metric_from_failures(failures)

    return failures, {"status": "ran", "adapter": adapter, "duration_ms": duration_ms}, metrics


def validate_candidate(
    case: dict[str, Any],
    candidate_root: Path,
) -> tuple[list[str], dict[str, Any]]:
    case_id = case["id"]
    candidate_dir = candidate_root / case_id
    if not candidate_dir.is_dir():
        failures = [f"candidate directory missing: {candidate_dir}"]
        return failures, {
            "candidate_available": make_metric("fail", failures),
            "candidate_required_files_present": make_metric("fail", failures),
            "candidate_required_api_ids_present": make_metric("fail", failures),
            "candidate_required_html_bindings_present": make_metric("fail", failures),
            "candidate_required_json_values_match": make_metric("fail", failures),
            "candidate_forbidden_paths_absent": make_metric("fail", failures),
            "candidate_forbidden_substrings_absent": make_metric("fail", failures),
        }

    failures = []
    static = case.get("oracle", {}).get("static", {})
    adapter = adapter_for_case(case)
    project, project_failures = load_candidate_project(candidate_dir)
    html_files = read_candidate_html_files(candidate_dir)
    html = "\n".join(html_files.values())
    metric_failures: dict[str, list[str]] = {
        "candidate_required_files_present": [],
        "candidate_required_api_ids_present": [],
        "candidate_required_html_bindings_present": [],
        "candidate_required_json_values_match": [],
        "candidate_forbidden_paths_absent": [],
        "candidate_forbidden_substrings_absent": [],
        "adapter_contract_valid": [],
        "runtime_execution_pass": [],
        "ui_interaction_pass": [],
        "export_roundtrip_pass": [],
        "collateral_damage_free": [],
    }
    for rel in static.get("required_files", []):
        if not (candidate_dir / rel).is_file():
            metric_failures["candidate_required_files_present"].append(
                f"required file missing: {rel}"
            )

    forbidden_hits = forbidden_path_hits(candidate_dir, static.get("forbidden_path_globs", []))
    if forbidden_hits:
        metric_failures["candidate_forbidden_paths_absent"].append(
            f"forbidden paths present: {forbidden_hits}"
        )

    combined_text = []
    for path in text_files(candidate_dir):
        combined_text.append(path.read_text(encoding="utf-8", errors="ignore"))
    text = "\n".join(combined_text)
    forbidden_substrings = [
        substring for substring in static.get("forbidden_substrings", []) if substring in text
    ]
    if forbidden_substrings:
        metric_failures["candidate_forbidden_substrings_absent"].append(
            f"forbidden substrings present: {sorted(forbidden_substrings)}"
        )

    required_api_ids = static.get("required_api_ids", [])
    if required_api_ids:
        if project is None:
            metric_failures["candidate_required_api_ids_present"].append(
                "project.json missing or invalid for API checks"
            )
        else:
            missing_apis = sorted(set(required_api_ids) - api_ids(project))
            if missing_apis:
                metric_failures["candidate_required_api_ids_present"].append(
                    f"missing API ids: {missing_apis}"
                )

    for substring in static.get("required_html_substrings", []):
        if substring not in html:
            metric_failures["candidate_required_html_bindings_present"].append(
                f"required HTML substring missing: {substring!r}"
            )

    for check in static.get("required_json_values", []):
        rel = check["file"]
        pointer = check["pointer"]
        expected = check["equals"]
        path = candidate_dir / rel
        if not path.is_file():
            metric_failures["candidate_required_json_values_match"].append(
                f"required JSON file missing: {rel}"
            )
            continue
        try:
            actual = json_pointer(load_json(path), pointer)
        except Exception as exc:  # noqa: BLE001
            metric_failures["candidate_required_json_values_match"].append(
                f"cannot read JSON pointer {pointer!r} in {rel}: {exc}"
            )
            continue
        if actual != expected:
            metric_failures["candidate_required_json_values_match"].append(
                f"{rel}{pointer} = {actual!r}, expected {expected!r}"
            )

    contract_applicable = False
    runtime_applicable = False
    ui_applicable = False
    export_applicable = False
    safety_applicable = False
    if adapter == "oob_workbench":
        contract_failures, contract_applicable = validate_oob_contract(
            case,
            candidate_dir,
            project,
            project_failures,
            html,
        )
        metric_failures["adapter_contract_valid"].extend(contract_failures)

        if contract_applicable or project is not None:
            runtime_failures, runtime_applicable = validate_oob_runtime_smoke(
                project,
                project_failures,
            )
        else:
            runtime_failures, runtime_applicable = [], False
        metric_failures["runtime_execution_pass"].extend(runtime_failures)

        ui_failures, ui_applicable = validate_oob_ui_smoke(candidate_dir, project, html_files)
        metric_failures["ui_interaction_pass"].extend(ui_failures)

        export_failures, export_applicable = validate_oob_export_smoke(case, candidate_dir)
        metric_failures["export_roundtrip_pass"].extend(export_failures)

        safety_failures, safety_applicable = validate_collateral_safety(candidate_dir)
        metric_failures["collateral_damage_free"].extend(safety_failures)

    for items in metric_failures.values():
        failures.extend(items)

    metrics = {
        "candidate_available": make_metric("pass"),
        "candidate_required_files_present": metric_from_failures(
            metric_failures["candidate_required_files_present"],
            applicable=bool(static.get("required_files")),
        ),
        "candidate_required_api_ids_present": metric_from_failures(
            metric_failures["candidate_required_api_ids_present"],
            applicable=bool(required_api_ids),
        ),
        "candidate_required_html_bindings_present": metric_from_failures(
            metric_failures["candidate_required_html_bindings_present"],
            applicable=bool(static.get("required_html_substrings")),
        ),
        "candidate_required_json_values_match": metric_from_failures(
            metric_failures["candidate_required_json_values_match"],
            applicable=bool(static.get("required_json_values")),
        ),
        "candidate_forbidden_paths_absent": metric_from_failures(
            metric_failures["candidate_forbidden_paths_absent"],
            applicable=bool(static.get("forbidden_path_globs")),
        ),
        "candidate_forbidden_substrings_absent": metric_from_failures(
            metric_failures["candidate_forbidden_substrings_absent"],
            applicable=bool(static.get("forbidden_substrings")),
        ),
        "adapter_contract_valid": metric_from_failures(
            metric_failures["adapter_contract_valid"],
            applicable=contract_applicable,
        ),
        "runtime_execution_pass": metric_from_failures(
            metric_failures["runtime_execution_pass"],
            applicable=runtime_applicable,
        ),
        "ui_interaction_pass": metric_from_failures(
            metric_failures["ui_interaction_pass"],
            applicable=ui_applicable,
        ),
        "export_roundtrip_pass": metric_from_failures(
            metric_failures["export_roundtrip_pass"],
            applicable=export_applicable,
        ),
        "collateral_damage_free": metric_from_failures(
            metric_failures["collateral_damage_free"],
            applicable=safety_applicable,
        ),
    }

    return failures, metrics


def run_case(case_file: Path, candidate_root: Path | None) -> dict[str, Any]:
    started = time.time()
    case = load_json(case_file)
    metrics: dict[str, Any] = {}

    schema_failures = validate_case_schema(case_file, case)
    metrics["case_schema_valid"] = metric_from_failures(schema_failures)

    fixture_failures = validate_fixture_paths(case_file, case)
    metrics["fixture_paths_valid"] = metric_from_failures(fixture_failures)

    baseline_failures, baseline, baseline_metrics = run_builder_baseline(case_file, case)
    metrics.update(baseline_metrics)

    readiness_failures = schema_failures + fixture_failures + baseline_failures
    candidate_failures: list[str] = []
    if candidate_root:
        candidate_failures, candidate_metrics = validate_candidate(case, candidate_root)
        metrics.update(candidate_metrics)
    else:
        for name in [
            "candidate_available",
            "candidate_required_files_present",
            "candidate_required_api_ids_present",
            "candidate_required_html_bindings_present",
            "candidate_required_json_values_match",
            "candidate_forbidden_paths_absent",
            "candidate_forbidden_substrings_absent",
        ]:
            metrics[name] = make_metric("na")

    for name in PLUGIN_METRICS:
        metrics.setdefault(name, make_metric("na"))

    failures = readiness_failures + candidate_failures
    metrics["case_ready"] = metric_from_failures(readiness_failures)
    metrics["case_resolved"] = (
        metric_from_failures(failures) if candidate_root else make_metric("na")
    )

    return {
        "case_id": case.get("id", case_file.stem),
        "category": case.get("category", ""),
        "adapter": adapter_for_case(case),
        "status": "pass" if not failures else "fail",
        "failures": failures,
        "metrics": metrics,
        "baseline": baseline,
        "duration_ms": int((time.time() - started) * 1000),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases-dir", default=str(DEFAULT_CASES_DIR))
    parser.add_argument("--candidate-root")
    parser.add_argument("--adapter", help="Run only cases for this adapter")
    parser.add_argument("--case", help="Run only one case id")
    parser.add_argument("--jsonl", action="store_true")
    args = parser.parse_args()

    cases_dir = Path(args.cases_dir).resolve()
    candidate_root = Path(args.candidate_root).resolve() if args.candidate_root else None
    case_files = sorted(cases_dir.glob("*.json"))
    if not case_files:
        print(f"No benchmark cases found under {cases_dir}", file=sys.stderr)
        return 1
    if args.adapter:
        selected = []
        for case_file in case_files:
            case = load_json(case_file)
            if adapter_for_case(case) == args.adapter:
                selected.append(case_file)
        case_files = selected
        if not case_files:
            print(f"No benchmark cases found for adapter {args.adapter!r}", file=sys.stderr)
            return 1
    if args.case:
        case_files = [case_file for case_file in case_files if case_file.stem == args.case]
        if not case_files:
            print(f"No benchmark case found with id {args.case!r}", file=sys.stderr)
            return 1

    results = [run_case(case_file, candidate_root) for case_file in case_files]
    if args.jsonl:
        for result in results:
            print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    else:
        for result in results:
            print(f"{result['status'].upper()} {result['case_id']} ({result['category']})")
            for failure in result["failures"]:
                print(f"  - {failure}")
        passed = sum(1 for result in results if result["status"] == "pass")
        noun = "resolved" if candidate_root else "ready"
        print(f"\nSummary: {passed}/{len(results)} cases {noun}")
        metric_totals: dict[str, list[int]] = {}
        for result in results:
            for name, metric in result["metrics"].items():
                score = metric.get("score")
                if score is None:
                    continue
                metric_totals.setdefault(name, []).append(int(score))
        if metric_totals:
            print("\nMetrics:")
            for name in sorted(metric_totals):
                values = metric_totals[name]
                print(f"  {name}: {sum(values)}/{len(values)}")

    return 0 if all(result["status"] == "pass" for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
