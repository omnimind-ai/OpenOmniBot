#!/usr/bin/env python3
"""Small runner for OOB Project benchmark cases."""

from __future__ import annotations

import argparse
import fnmatch
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


BENCH_ROOT = Path(__file__).resolve().parent
REPO_ROOT = BENCH_ROOT.parents[1]
DEFAULT_CASES_DIR = BENCH_ROOT / "cases"
BUILDER = (
    REPO_ROOT
    / "app/src/main/assets/builtin_skills/oob-project/scripts/build_project_from_contract.py"
)
TEXT_SUFFIXES = {".css", ".html", ".js", ".json", ".md", ".txt"}
REQUIRED_CASE_KEYS = {
    "schema_version",
    "id",
    "title",
    "category",
    "capabilities",
    "instruction",
    "input",
    "oracle",
}


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


def api_ids(project: dict[str, Any]) -> set[str]:
    ids: set[str] = set()
    for api in project_apis(project):
        raw = api.get("toolId") or api.get("apiId")
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


def find_project_json(candidate_dir: Path) -> Path | None:
    direct = candidate_dir / "project.json"
    if direct.is_file():
        return direct
    matches = sorted(candidate_dir.rglob("project.json"))
    return matches[0] if matches else None


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


def validate_case_schema(case_file: Path, case: dict[str, Any]) -> list[str]:
    failures = []
    missing = sorted(REQUIRED_CASE_KEYS - set(case))
    if missing:
        failures.append(f"missing required keys: {missing}")
    if case.get("schema_version") != "oob.project_benchmark_case.v1":
        failures.append("schema_version must be oob.project_benchmark_case.v1")
    if case.get("id") != case_file.stem:
        failures.append(f"id must match file name stem {case_file.stem!r}")
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


def run_builder_baseline(case_file: Path, case: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    case_input = case.get("input", {})
    contract_fixture = resolve_case_path(case_file, case_input.get("contract_fixture"))
    if not contract_fixture:
        return [], {"status": "not_applicable"}
    if not BUILDER.is_file():
        return [f"builder not found: {BUILDER}"], {"status": "missing_builder"}

    started = time.time()
    result = subprocess.run(
        [sys.executable, str(BUILDER), "--contract-file", str(contract_fixture)],
        capture_output=True,
        text=True,
        cwd=str(REPO_ROOT),
    )
    duration_ms = int((time.time() - started) * 1000)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        return [f"builder failed for {contract_fixture.name}: {detail}"], {
            "status": "failed",
            "duration_ms": duration_ms,
        }

    try:
        output = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        return [f"builder output is not JSON: {exc}"], {
            "status": "invalid_json",
            "duration_ms": duration_ms,
        }

    failures = []
    static = case.get("oracle", {}).get("static", {})
    expected_project_id = static.get("expected_project_id")
    if expected_project_id and output.get("projectId") != expected_project_id:
        failures.append(
            f"baseline projectId {output.get('projectId')!r} != {expected_project_id!r}"
        )

    missing_apis = sorted(set(static.get("required_api_ids", [])) - api_ids(output))
    if missing_apis:
        failures.append(f"baseline missing API ids: {missing_apis}")

    html = html_payload(output)
    missing_html = [
        substring
        for substring in static.get("required_html_substrings", [])
        if substring not in html
    ]
    if missing_html:
        failures.append(f"baseline missing HTML substrings: {missing_html}")

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
        failures.append(f"baseline contains forbidden substrings: {sorted(forbidden_hits)}")

    if static.get("agent_apis_require_capabilities", False):
        missing_caps = []
        for api in project_apis(output):
            run = api.get("run", {})
            if isinstance(run, dict) and run.get("use") == "agent":
                caps = run.get("capabilities")
                if not isinstance(caps, list) or not any(str(cap).strip() for cap in caps):
                    missing_caps.append(api.get("toolId") or api.get("apiId") or "<unknown>")
        if missing_caps:
            failures.append(f"baseline agent APIs missing capabilities: {missing_caps}")

    required_docs = static.get("required_docs", [])
    if required_docs:
        docs_obj = output.get("docs", {})
        available_docs = set(docs_obj) if isinstance(docs_obj, dict) else set()
        missing_docs = sorted(set(required_docs) - available_docs)
        if missing_docs:
            failures.append(f"baseline missing docs: {missing_docs}")

    return failures, {"status": "ran", "duration_ms": duration_ms}


def validate_candidate(case: dict[str, Any], candidate_root: Path) -> list[str]:
    case_id = case["id"]
    candidate_dir = candidate_root / case_id
    if not candidate_dir.is_dir():
        return [f"candidate directory missing: {candidate_dir}"]

    failures = []
    static = case.get("oracle", {}).get("static", {})
    for rel in static.get("required_files", []):
        if not (candidate_dir / rel).is_file():
            failures.append(f"required file missing: {rel}")

    forbidden_hits = forbidden_path_hits(candidate_dir, static.get("forbidden_path_globs", []))
    if forbidden_hits:
        failures.append(f"forbidden paths present: {forbidden_hits}")

    combined_text = []
    for path in text_files(candidate_dir):
        combined_text.append(path.read_text(encoding="utf-8", errors="ignore"))
    text = "\n".join(combined_text)
    forbidden_substrings = [
        substring for substring in static.get("forbidden_substrings", []) if substring in text
    ]
    if forbidden_substrings:
        failures.append(f"forbidden substrings present: {sorted(forbidden_substrings)}")

    project_json_path = find_project_json(candidate_dir)
    required_api_ids = static.get("required_api_ids", [])
    if required_api_ids:
        if not project_json_path:
            failures.append("project.json missing for API checks")
        else:
            project = load_json(project_json_path)
            missing_apis = sorted(set(required_api_ids) - api_ids(project))
            if missing_apis:
                failures.append(f"missing API ids: {missing_apis}")

    html = read_candidate_html(candidate_dir)
    for substring in static.get("required_html_substrings", []):
        if substring not in html:
            failures.append(f"required HTML substring missing: {substring!r}")

    for check in static.get("required_json_values", []):
        rel = check["file"]
        pointer = check["pointer"]
        expected = check["equals"]
        path = candidate_dir / rel
        if not path.is_file():
            failures.append(f"required JSON file missing: {rel}")
            continue
        try:
            actual = json_pointer(load_json(path), pointer)
        except Exception as exc:  # noqa: BLE001
            failures.append(f"cannot read JSON pointer {pointer!r} in {rel}: {exc}")
            continue
        if actual != expected:
            failures.append(f"{rel}{pointer} = {actual!r}, expected {expected!r}")

    return failures


def run_case(case_file: Path, candidate_root: Path | None) -> dict[str, Any]:
    started = time.time()
    case = load_json(case_file)
    failures = []
    failures.extend(validate_case_schema(case_file, case))
    failures.extend(validate_fixture_paths(case_file, case))

    baseline_failures, baseline = run_builder_baseline(case_file, case)
    failures.extend(baseline_failures)

    if candidate_root:
        failures.extend(validate_candidate(case, candidate_root))

    return {
        "case_id": case.get("id", case_file.stem),
        "category": case.get("category", ""),
        "status": "pass" if not failures else "fail",
        "failures": failures,
        "baseline": baseline,
        "duration_ms": int((time.time() - started) * 1000),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases-dir", default=str(DEFAULT_CASES_DIR))
    parser.add_argument("--candidate-root")
    parser.add_argument("--jsonl", action="store_true")
    args = parser.parse_args()

    cases_dir = Path(args.cases_dir).resolve()
    candidate_root = Path(args.candidate_root).resolve() if args.candidate_root else None
    case_files = sorted(cases_dir.glob("*.json"))
    if not case_files:
        print(f"No benchmark cases found under {cases_dir}", file=sys.stderr)
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
        print(f"\nSummary: {passed}/{len(results)} cases passed")

    return 0 if all(result["status"] == "pass" for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
