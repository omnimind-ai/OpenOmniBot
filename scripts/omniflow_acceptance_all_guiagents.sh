#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_ROOT="${OMNIFLOW_ACCEPTANCE_ROOT:-/private/tmp/omniflow-acceptance-all}"
PYTHON_BIN="${OMNIFLOW_PYTHON:-python3}"

now_ms() {
  "$PYTHON_BIN" -c 'import time; print(int(time.time() * 1000))'
}

print_all_timing_summary() {
  BASE_ROOT="$BASE_ROOT" "$PYTHON_BIN" - <<'PY'
from pathlib import Path
import os

base = Path(os.environ["BASE_ROOT"])
rows = []
for timing_path in base.glob("*/timing.tsv"):
    project = timing_path.parent.name
    with timing_path.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 2:
                continue
            name, raw_value = parts
            try:
                value = int(raw_value)
            except ValueError:
                continue
            rows.append((project, name, value))

steps = [(project, name, value) for project, name, value in rows if name != "total"]
function_runners = [(project, name, value) for project, name, value in rows if name.endswith("_runner")]
function_steps = [(project, name, value) for project, name, value in rows if "_step_" in name]
direct_function_runners = [
    (project, name, value)
    for project, name, value in function_runners
    if name.startswith("function_run_")
]
direct_function_steps = [
    (project, name, value)
    for project, name, value in function_steps
    if name.startswith("function_run_")
]

def emit(prefix, items):
    if not items:
        return
    project, name, value = max(items, key=lambda item: item[2])
    print(f"{prefix}={project}:{name}")
    print(f"{prefix}_ms={value}")

emit("timing_all_slowest_step", steps)
emit("timing_all_function_slowest_runner", function_runners)
emit("timing_all_function_slowest_step", function_steps)
emit("timing_all_direct_function_slowest_runner", direct_function_runners)
emit("timing_all_direct_function_slowest_step", direct_function_steps)
PY
}

ALL_START_MS="$(now_ms)"

run_project() {
  local project_name="$1"
  local repo_url="$2"
  local expected_mode="$3"
  local reuse_dir="$4"
  local start_ms
  local end_ms

  start_ms="$(now_ms)"

  OMNIFLOW_PROJECT_NAME="$project_name" \
  OMNIFLOW_REPO_URL="$repo_url" \
  OMNIFLOW_EXPECTED_MODE="$expected_mode" \
  OMNIFLOW_REUSE_DIR="$reuse_dir" \
  OMNIFLOW_ACCEPTANCE_ROOT="$BASE_ROOT/$project_name" \
  bash "$ROOT_DIR/scripts/omniflow_acceptance_external_repo.sh"

  end_ms="$(now_ms)"
  printf 'timing_all_%s_ms=%s\n' "${project_name//-/_}" "$((end_ms - start_ms))"
}

run_project \
  "mobilegpt" \
  "https://github.com/hchoi256/mobilegpt.git" \
  "python_skill_plus_mcp" \
  "/private/tmp/omniflow-probe-mobilegpt"

run_project \
  "mobile-use" \
  "https://github.com/MadeAgents/mobile-use.git" \
  "python_skill_plus_mcp" \
  "/private/tmp/omniflow-probe-mobile-use"

run_project \
  "mobile-mcp" \
  "https://github.com/mobile-next/mobile-mcp.git" \
  "direct_mcp" \
  "/private/tmp/omniflow-probe-mobile-mcp"

ALL_END_MS="$(now_ms)"
printf 'timing_all_total_ms=%s\n' "$((ALL_END_MS - ALL_START_MS))"
print_all_timing_summary
echo "omniflow_all_guiagents_acceptance=ok"
