#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_NAME="${OMNIFLOW_PROJECT_NAME:?Set OMNIFLOW_PROJECT_NAME}"
REPO_URL="${OMNIFLOW_REPO_URL:?Set OMNIFLOW_REPO_URL}"
EXPECTED_MODE="${OMNIFLOW_EXPECTED_MODE:-}"
REUSE_DIR="${OMNIFLOW_REUSE_DIR:-}"
WORK_ROOT="${OMNIFLOW_ACCEPTANCE_ROOT:-/private/tmp/omniflow-acceptance-$PROJECT_NAME}"
REPO_DIR="${OMNIFLOW_REPO_DIR:-$WORK_ROOT/repo}"
VENV_DIR="$WORK_ROOT/venv"
DIST_DIR="$WORK_ROOT/dist"
CODEX_OUTPUT="$WORK_ROOT/codex-output.txt"
RECALL_OUTPUT="$WORK_ROOT/canonical-recall-output.json"
CALL_FUNCTION_OUTPUT="$WORK_ROOT/canonical-call-function-output.json"
CANONICAL_INSTALL_OUTPUT="$WORK_ROOT/canonical-install-function-output.json"
INGEST_RUNLOG_OUTPUT="$WORK_ROOT/canonical-ingest-runlog-output.json"
TIMING_OUTPUT="$WORK_ROOT/timing.tsv"
SAFE_PROJECT="${PROJECT_NAME//-/_}"
CLICK_FUNCTION_ID="${OMNIFLOW_CLICK_FUNCTION_ID:-settings_click_path_demo}"
INSTALL_FUNCTION_ID="${OMNIFLOW_INSTALL_FUNCTION_ID:-install_sample_apk_demo}"
PYTHON_BIN="${OMNIFLOW_PYTHON:-python3}"
MCP_URL="${OMNIFLOW_MCP_URL:-${OOB_MCP_URL:-}}"
MCP_TOKEN="${OMNIFLOW_MCP_TOKEN:-${OOB_MCP_TOKEN:-}}"
FUNCTION_LIST_OUTPUT="$WORK_ROOT/direct-function-list-output.json"
FUNCTION_GET_OUTPUT="$WORK_ROOT/direct-function-get-output.json"
GUARD_CHECK_OUTPUT="$WORK_ROOT/direct-guard-check-output.json"
RUN_FUNCTION_OUTPUT="$WORK_ROOT/direct-run-function-output.json"
RUNLOG_LIST_OUTPUT="$WORK_ROOT/direct-runlog-list-output.json"
RUNLOG_GET_OUTPUT="$WORK_ROOT/direct-runlog-get-output.json"
RUNLOG_CONVERT_OUTPUT="$WORK_ROOT/direct-runlog-convert-output.json"
RUN_FUNCTION_INSTALL_OUTPUT="$WORK_ROOT/direct-run-install-function-output.json"

mkdir -p "$WORK_ROOT" "$DIST_DIR"

if [ -z "$MCP_URL" ]; then
  echo "real_mcp_url=missing_set_OMNIFLOW_MCP_URL_or_OOB_MCP_URL" >&2
  exit 1
fi
export OMNIFLOW_MCP_URL="$MCP_URL"
if [ -n "$MCP_TOKEN" ]; then
  export OMNIFLOW_MCP_TOKEN="$MCP_TOKEN"
fi

now_ms() {
  "$PYTHON_BIN" -c 'import time; print(int(time.time() * 1000))'
}

record_timing() {
  local name="$1"
  local start_ms="$2"
  local end_ms
  local duration_ms
  end_ms="$(now_ms)"
  duration_ms=$((end_ms - start_ms))
  printf 'timing_%s_ms=%s\n' "$name" "$duration_ms"
  printf '%s\t%s\n' "$name" "$duration_ms" >> "$TIMING_OUTPUT"
}

record_function_run_timing() {
  local prefix="$1"
  local json_path="$2"
  PREFIX="$prefix" JSON_PATH="$json_path" "$PYTHON_BIN" - <<'PY' | while IFS=$'\t' read -r name value; do
import json
import os
import re


def as_ms(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return int(round(value))
    if isinstance(value, str):
        try:
            return int(round(float(value)))
        except ValueError:
            return None
    return None


def read_path(root, *path):
    current = root
    for key in path:
        if not isinstance(current, dict) or key not in current:
            return None
        current = current[key]
    return current


def safe_label(value):
    return re.sub(r"[^a-zA-Z0-9_]+", "_", str(value)).strip("_") or "step"


path = os.environ["JSON_PATH"]
prefix = os.environ["PREFIX"]
with open(path, encoding="utf-8") as handle:
    data = json.load(handle)

root = data.get("result") if isinstance(data, dict) and isinstance(data.get("result"), dict) else data
runner_ms = None
for candidate in (
    read_path(data, "result", "timing", "runner_duration_ms"),
    read_path(data, "result", "timing", "duration_ms"),
    read_path(data, "result", "runner_duration_ms"),
    read_path(data, "timing", "runner_duration_ms"),
    read_path(data, "timing", "duration_ms"),
    read_path(data, "runner_duration_ms"),
    read_path(data, "duration_ms"),
    read_path(data, "execution_duration_ms"),
):
    runner_ms = as_ms(candidate)
    if runner_ms is not None:
        break

if runner_ms is not None:
    print(f"{prefix}_runner\t{runner_ms}")

steps = root.get("step_results", []) if isinstance(root, dict) else []
if isinstance(steps, list):
    for fallback_index, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        step_ms = None
        for candidate in (
            step.get("duration_ms"),
            step.get("elapsed_ms"),
            read_path(step, "timing", "duration_ms"),
            read_path(step, "timing", "runner_duration_ms"),
        ):
            step_ms = as_ms(candidate)
            if step_ms is not None:
                break
        if step_ms is None:
            continue
        index = step.get("index", fallback_index)
        label = safe_label(step.get("type") or step.get("action") or step.get("name") or "step")
        print(f"{prefix}_step_{index}_{label}\t{step_ms}")
PY
    if [ -n "$value" ]; then
      printf 'timing_%s_ms=%s\n' "$name" "$value"
      printf '%s\t%s\n' "$name" "$value" >> "$TIMING_OUTPUT"
    fi
  done
}

print_timing_summary() {
  TIMING_OUTPUT="$TIMING_OUTPUT" "$PYTHON_BIN" - <<'PY'
import os

path = os.environ["TIMING_OUTPUT"]
rows = []
with open(path, encoding="utf-8") as handle:
    for line in handle:
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 2:
            continue
        name, raw_value = parts
        try:
            value = int(raw_value)
        except ValueError:
            continue
        rows.append((name, value))

steps = [(name, value) for name, value in rows if name not in {"total"}]
function_runners = [(name, value) for name, value in rows if name.endswith("_runner")]
function_steps = [(name, value) for name, value in rows if "_step_" in name]
canonical_function_runners = [
    (name, value) for name, value in function_runners if name.startswith("canonical_")
]
canonical_function_steps = [
    (name, value) for name, value in function_steps if name.startswith("canonical_")
]
direct_function_runners = [
    (name, value) for name, value in function_runners if name.startswith("function_run_")
]
direct_function_steps = [
    (name, value) for name, value in function_steps if name.startswith("function_run_")
]

def emit(prefix, items):
    if not items:
        return
    name, value = max(items, key=lambda item: item[1])
    print(f"{prefix}={name}")
    print(f"{prefix}_ms={value}")

emit("timing_slowest_step", steps)
emit("timing_function_slowest_runner", function_runners)
emit("timing_function_slowest_step", function_steps)
emit("timing_canonical_function_slowest_runner", canonical_function_runners)
emit("timing_canonical_function_slowest_step", canonical_function_steps)
emit("timing_direct_function_slowest_runner", direct_function_runners)
emit("timing_direct_function_slowest_step", direct_function_steps)
PY
}

TOTAL_START_MS="$(now_ms)"
: > "$TIMING_OUTPUT"

STEP_START_MS="$(now_ms)"
if [ ! -d "$REPO_DIR/.git" ]; then
  if [ -n "$REUSE_DIR" ] && [ -d "$REUSE_DIR/.git" ]; then
    REPO_DIR="$REUSE_DIR"
  else
    git clone --depth 1 "$REPO_URL" "$REPO_DIR"
  fi
fi
record_timing "repo_prepare" "$STEP_START_MS"

STEP_START_MS="$(now_ms)"
rm -rf "$ROOT_DIR/build" "$ROOT_DIR/omniflow_agentkit.egg-info"
"$PYTHON_BIN" -m pip wheel --no-deps --no-build-isolation -w "$DIST_DIR" "$ROOT_DIR"
record_timing "wheel_build" "$STEP_START_MS"

STEP_START_MS="$(now_ms)"
"$PYTHON_BIN" -m venv "$VENV_DIR"
record_timing "venv_create" "$STEP_START_MS"

STEP_START_MS="$(now_ms)"
"$VENV_DIR/bin/python" -m pip install --no-deps --force-reinstall "$DIST_DIR"/omniflow_agentkit-0.1.0-py3-none-any.whl
record_timing "wheel_install" "$STEP_START_MS"

rm -f "$RECALL_OUTPUT" "$CALL_FUNCTION_OUTPUT" \
  "$CANONICAL_INSTALL_OUTPUT" "$INGEST_RUNLOG_OUTPUT" \
  "$FUNCTION_LIST_OUTPUT" "$FUNCTION_GET_OUTPUT" "$GUARD_CHECK_OUTPUT" \
  "$RUN_FUNCTION_OUTPUT" "$RUNLOG_LIST_OUTPUT" "$RUNLOG_GET_OUTPUT" \
  "$RUNLOG_CONVERT_OUTPUT" "$RUN_FUNCTION_INSTALL_OUTPUT"
STEP_START_MS="$(now_ms)"
"$VENV_DIR/bin/omniflow-agentkit" mcp-list-functions --timeout 5 >/dev/null
record_timing "real_mcp_probe" "$STEP_START_MS"

(
  cd "$REPO_DIR"
  STEP_START_MS="$(now_ms)"
  OMNIFLOW_PROJECT_NAME="$PROJECT_NAME" OMNIFLOW_EXPECTED_MODE="$EXPECTED_MODE" "$VENV_DIR/bin/python" - <<'PY'
import os

from omniflow_agentkit import OmniFlowAgentKit, RepoProbe

project_name = os.environ["OMNIFLOW_PROJECT_NAME"]
expected_mode = os.environ.get("OMNIFLOW_EXPECTED_MODE")
click_function_id = os.environ.get("OMNIFLOW_CLICK_FUNCTION_ID", "settings_click_path_demo")

kit = OmniFlowAgentKit()
report = RepoProbe(".").run()
prompt = kit.agent_prompt("Run the safest saved Function", report.summary())

assert "GUIAgent OmniFlow Skill" in kit.skill()
assert kit.sample_function()["function_id"] == click_function_id
if expected_mode:
    assert report.recommended_mode == expected_mode, (report.recommended_mode, expected_mode)
assert "Run the safest saved Function" in prompt

print(f"project={project_name}")
print("python_import=ok")
print("sample_function=ok")
print(f"recommended_mode={report.recommended_mode}")
PY
  record_timing "sdk_import_probe" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" probe-repo .
  record_timing "cli_probe_repo" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" prompt "Run the safest saved Function" --repo . >"$WORK_ROOT/omniflow-agentkit-prompt.txt"
  record_timing "cli_prompt_build" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-recall "open Android Settings and click through the demo path" --mcp-url "$MCP_URL" >"$RECALL_OUTPUT"
  record_timing "mcp_recall" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-call-function "$CLICK_FUNCTION_ID" --mcp-url "$MCP_URL" >"$CALL_FUNCTION_OUTPUT"
  record_timing "mcp_call_function" "$STEP_START_MS"
  record_function_run_timing "canonical_call_function" "$CALL_FUNCTION_OUTPUT"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-ingest-runlog runlog_install_demo --mcp-url "$MCP_URL" >"$INGEST_RUNLOG_OUTPUT"
  record_timing "mcp_ingest_runlog" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-call-function "$INSTALL_FUNCTION_ID" --mcp-url "$MCP_URL" >"$CANONICAL_INSTALL_OUTPUT"
  record_timing "mcp_call_ingested_function" "$STEP_START_MS"
  record_function_run_timing "canonical_ingested_function" "$CANONICAL_INSTALL_OUTPUT"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-list-functions --mcp-url "$MCP_URL" >"$FUNCTION_LIST_OUTPUT"
  record_timing "mcp_list_functions" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-get-function "$CLICK_FUNCTION_ID" --mcp-url "$MCP_URL" >"$FUNCTION_GET_OUTPUT"
  record_timing "mcp_get_function" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-guard-check "$CLICK_FUNCTION_ID" --mcp-url "$MCP_URL" >"$GUARD_CHECK_OUTPUT"
  record_timing "mcp_guard_check" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-run-function "$CLICK_FUNCTION_ID" --mcp-url "$MCP_URL" >"$RUN_FUNCTION_OUTPUT"
  record_timing "mcp_run_function_existing" "$STEP_START_MS"
  record_function_run_timing "function_run_existing" "$RUN_FUNCTION_OUTPUT"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-list-runlogs --mcp-url "$MCP_URL" >"$RUNLOG_LIST_OUTPUT"
  record_timing "mcp_list_runlogs" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-get-runlog runlog_install_demo --mcp-url "$MCP_URL" >"$RUNLOG_GET_OUTPUT"
  record_timing "mcp_get_runlog" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-convert-runlog runlog_install_demo --mcp-url "$MCP_URL" >"$RUNLOG_CONVERT_OUTPUT"
  record_timing "mcp_convert_runlog" "$STEP_START_MS"

  STEP_START_MS="$(now_ms)"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-run-function "$INSTALL_FUNCTION_ID" --mcp-url "$MCP_URL" --execution-mode background --confirmed >"$RUN_FUNCTION_INSTALL_OUTPUT"
  record_timing "mcp_run_installed_function_background" "$STEP_START_MS"
  record_function_run_timing "function_run_install" "$RUN_FUNCTION_INSTALL_OUTPUT"

  STEP_START_MS="$(now_ms)"
  CLICK_FUNCTION_ID="$CLICK_FUNCTION_ID" INSTALL_FUNCTION_ID="$INSTALL_FUNCTION_ID" RECALL_OUTPUT="$RECALL_OUTPUT" CALL_FUNCTION_OUTPUT="$CALL_FUNCTION_OUTPUT" INGEST_RUNLOG_OUTPUT="$INGEST_RUNLOG_OUTPUT" CANONICAL_INSTALL_OUTPUT="$CANONICAL_INSTALL_OUTPUT" FUNCTION_LIST_OUTPUT="$FUNCTION_LIST_OUTPUT" FUNCTION_GET_OUTPUT="$FUNCTION_GET_OUTPUT" GUARD_CHECK_OUTPUT="$GUARD_CHECK_OUTPUT" RUN_FUNCTION_OUTPUT="$RUN_FUNCTION_OUTPUT" RUNLOG_LIST_OUTPUT="$RUNLOG_LIST_OUTPUT" RUNLOG_GET_OUTPUT="$RUNLOG_GET_OUTPUT" RUNLOG_CONVERT_OUTPUT="$RUNLOG_CONVERT_OUTPUT" RUN_FUNCTION_INSTALL_OUTPUT="$RUN_FUNCTION_INSTALL_OUTPUT" "$VENV_DIR/bin/python" - <<'PY'
import json
import os

click_function_id = os.environ["CLICK_FUNCTION_ID"]
install_function_id = os.environ["INSTALL_FUNCTION_ID"]
recall = json.loads(open(os.environ["RECALL_OUTPUT"], encoding="utf-8").read())
called = json.loads(open(os.environ["CALL_FUNCTION_OUTPUT"], encoding="utf-8").read())
ingested = json.loads(open(os.environ["INGEST_RUNLOG_OUTPUT"], encoding="utf-8").read())
installed = json.loads(open(os.environ["CANONICAL_INSTALL_OUTPUT"], encoding="utf-8").read())
function_list = json.loads(open(os.environ["FUNCTION_LIST_OUTPUT"], encoding="utf-8").read())
function_get = json.loads(open(os.environ["FUNCTION_GET_OUTPUT"], encoding="utf-8").read())
guard = json.loads(open(os.environ["GUARD_CHECK_OUTPUT"], encoding="utf-8").read())
direct_run = json.loads(open(os.environ["RUN_FUNCTION_OUTPUT"], encoding="utf-8").read())
runlog_list = json.loads(open(os.environ["RUNLOG_LIST_OUTPUT"], encoding="utf-8").read())
runlog_get = json.loads(open(os.environ["RUNLOG_GET_OUTPUT"], encoding="utf-8").read())
converted = json.loads(open(os.environ["RUNLOG_CONVERT_OUTPUT"], encoding="utf-8").read())
direct_install = json.loads(open(os.environ["RUN_FUNCTION_INSTALL_OUTPUT"], encoding="utf-8").read())

def step_type(step):
    return step.get("type") or step.get("tool") or step.get("action")

assert recall["decision"] == "hit"
assert recall["hit"]["function_id"] == click_function_id
assert called["success"] is True
assert called["function_id"] == click_function_id
assert called.get("run_id")
assert called["actions_executed"] >= 7
assert sum(1 for step in called["step_results"] if step_type(step) == "click") >= 4
assert ingested["accepted"] is True
assert ingested["function_id"] == install_function_id
assert installed["success"] is True
assert installed["function_id"] == install_function_id
assert installed.get("run_id")
assert function_list["count"] >= 1
assert any(item["function_id"] == click_function_id for item in function_list["functions"])
assert function_get["function_id"] == click_function_id
assert function_get["execution"]["step_count"] == 7
assert guard["decision"] == "allow"
assert direct_run["success"] is True
assert direct_run["function_id"] == click_function_id
assert direct_run.get("run_id")
assert len(direct_run["step_results"]) == 7
assert sum(1 for step in direct_run["step_results"] if step_type(step) == "click") == 4
assert runlog_list["count"] >= 1
assert runlog_get["run_id"] == "runlog_install_demo"
assert converted["success"] is True
assert converted["function_id"] == install_function_id
assert direct_install["success"] is True
assert direct_install["function_id"] == install_function_id
assert direct_install.get("run_id")
assert direct_install["execution_mode"] == "background"

print("canonical_recall=ok")
print(f"canonical_hit_function_id={recall['hit']['function_id']}")
print("canonical_call_function=ok")
print(f"canonical_run_id={called['run_id']}")
print(f"canonical_click_step_count={sum(1 for step in called['step_results'] if step_type(step) == 'click')}")
print("canonical_ingest_runlog=ok")
print(f"canonical_ingested_function_id={ingested['function_id']}")
print("canonical_call_ingested_function=ok")
print(f"ingested_function_run_id={installed['run_id']}")
print("direct_function_list=ok")
print("direct_function_get=ok")
print("direct_guard_check=ok")
print("direct_run_function=ok")
print(f"direct_run_id={direct_run['run_id']}")
print(f"direct_step_count={len(direct_run['step_results'])}")
print(f"direct_click_step_count={sum(1 for step in direct_run['step_results'] if step_type(step) == 'click')}")
print("direct_runlog_list=ok")
print("direct_runlog_get=ok")
print("direct_runlog_convert=ok")
print("direct_background_install=ok")
print(f"direct_background_install_run_id={direct_install['run_id']}")
PY
  record_timing "verify_mcp_results" "$STEP_START_MS"
)

if [ "${OMNIFLOW_RUN_CODEX:-0}" = "1" ] && command -v codex >/dev/null 2>&1; then
  STEP_START_MS="$(now_ms)"
  codex exec \
    --cd "$REPO_DIR" \
    --sandbox danger-full-access \
    --skip-git-repo-check \
    -o "$CODEX_OUTPUT" \
    - <<PROMPT
You are in an external open-source repo, $PROJECT_NAME. Do not modify files.
Run exactly these commands and no variants:

$VENV_DIR/bin/omniflow-agentkit probe-repo .
$VENV_DIR/bin/omniflow-agentkit mcp-recall "open Android Settings and click through the demo path" --mcp-url "$MCP_URL"
$VENV_DIR/bin/omniflow-agentkit mcp-call-function "$CLICK_FUNCTION_ID" --mcp-url "$MCP_URL"
$VENV_DIR/bin/omniflow-agentkit mcp-ingest-runlog runlog_install_demo --mcp-url "$MCP_URL"
$VENV_DIR/bin/omniflow-agentkit mcp-list-functions --mcp-url "$MCP_URL"
$VENV_DIR/bin/omniflow-agentkit mcp-guard-check "$CLICK_FUNCTION_ID" --mcp-url "$MCP_URL"
$VENV_DIR/bin/omniflow-agentkit mcp-run-function "$CLICK_FUNCTION_ID" --mcp-url "$MCP_URL"
$VENV_DIR/bin/omniflow-agentkit mcp-convert-runlog runlog_install_demo --mcp-url "$MCP_URL"
$VENV_DIR/bin/omniflow-agentkit mcp-run-function "$INSTALL_FUNCTION_ID" --mcp-url "$MCP_URL" --execution-mode background --confirmed

Then summarize whether all commands succeeded, include recommended_mode if present,
and include the function_id, run_id, runner_duration_ms, and click step count.
PROMPT
  cat "$CODEX_OUTPUT"
  if ! grep -q "function_id" "$CODEX_OUTPUT"; then
    echo "codex_function_trigger=failed" >&2
    exit 1
  fi
  echo "codex_function_trigger=ok"
  record_timing "codex_cli" "$STEP_START_MS"
else
  echo "codex_step=skip_set_OMNIFLOW_RUN_CODEX_1_to_enable"
fi

record_timing "total" "$TOTAL_START_MS"
print_timing_summary
echo "omniflow_${SAFE_PROJECT}_acceptance=ok"
