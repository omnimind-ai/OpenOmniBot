#!/usr/bin/env python3
"""Runner for ProjectArtifactBench/PABench cases."""

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
DEFAULT_ADAPTER = "oob_workbench"
CASE_SCHEMA_VERSION = "project_artifact.case.v1"
ADAPTER_BUILDERS = {
    "oob_workbench": (
        REPO_ROOT
        / "app/src/main/assets/builtin_skills/oob-project/scripts/build_project_from_contract.py"
    ),
}
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
    metric_failures: dict[str, list[str]] = {
        "candidate_required_files_present": [],
        "candidate_required_api_ids_present": [],
        "candidate_required_html_bindings_present": [],
        "candidate_required_json_values_match": [],
        "candidate_forbidden_paths_absent": [],
        "candidate_forbidden_substrings_absent": [],
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

    project_json_path = find_project_json(candidate_dir)
    required_api_ids = static.get("required_api_ids", [])
    if required_api_ids:
        if not project_json_path:
            metric_failures["candidate_required_api_ids_present"].append(
                "project.json missing for API checks"
            )
        else:
            project = load_json(project_json_path)
            missing_apis = sorted(set(required_api_ids) - api_ids(project))
            if missing_apis:
                metric_failures["candidate_required_api_ids_present"].append(
                    f"missing API ids: {missing_apis}"
                )

    html = read_candidate_html(candidate_dir)
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
