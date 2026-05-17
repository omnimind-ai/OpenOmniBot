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
MCP_URL_FILE="$WORK_ROOT/mock-mcp-url.txt"
FUNCTION_RUN_OUTPUT="$WORK_ROOT/function-run-output.json"
CONVERT_OUTPUT="$WORK_ROOT/runlog-convert-output.json"
BACKGROUND_RUN_OUTPUT="$WORK_ROOT/background-run-output.json"
RECALL_OUTPUT="$WORK_ROOT/canonical-recall-output.json"
CALL_FUNCTION_OUTPUT="$WORK_ROOT/canonical-call-function-output.json"
INGEST_RUNLOG_OUTPUT="$WORK_ROOT/canonical-ingest-runlog-output.json"
MOCK_MCP_PID=""
SAFE_PROJECT="${PROJECT_NAME//-/_}"

mkdir -p "$WORK_ROOT" "$DIST_DIR"

cleanup() {
  if [ -n "$MOCK_MCP_PID" ]; then
    kill "$MOCK_MCP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [ ! -d "$REPO_DIR/.git" ]; then
  if [ -n "$REUSE_DIR" ] && [ -d "$REUSE_DIR/.git" ]; then
    REPO_DIR="$REUSE_DIR"
  else
    git clone --depth 1 "$REPO_URL" "$REPO_DIR"
  fi
fi

python3 -m pip wheel --no-deps --no-build-isolation -w "$DIST_DIR" "$ROOT_DIR"
python3 -m venv "$VENV_DIR"
"$VENV_DIR/bin/python" -m pip install --no-deps --force-reinstall "$DIST_DIR"/omniflow_agentkit-0.1.0-py3-none-any.whl

rm -f "$MCP_URL_FILE" "$FUNCTION_RUN_OUTPUT" "$CONVERT_OUTPUT" "$BACKGROUND_RUN_OUTPUT" \
  "$RECALL_OUTPUT" "$CALL_FUNCTION_OUTPUT" "$INGEST_RUNLOG_OUTPUT"
python3 "$ROOT_DIR/scripts/omniflow_mock_mcp_server.py" --port-file "$MCP_URL_FILE" &
MOCK_MCP_PID="$!"
for _ in {1..50}; do
  if [ -s "$MCP_URL_FILE" ]; then
    break
  fi
  sleep 0.1
done
if [ ! -s "$MCP_URL_FILE" ]; then
  echo "mock_mcp_server=failed_to_start" >&2
  exit 1
fi
MCP_URL="$(sed -n '1p' "$MCP_URL_FILE")"

(
  cd "$REPO_DIR"
  OMNIFLOW_PROJECT_NAME="$PROJECT_NAME" OMNIFLOW_EXPECTED_MODE="$EXPECTED_MODE" "$VENV_DIR/bin/python" - <<'PY'
import os

from omniflow_agentkit import OmniFlowAgentKit, RepoProbe

project_name = os.environ["OMNIFLOW_PROJECT_NAME"]
expected_mode = os.environ.get("OMNIFLOW_EXPECTED_MODE")

kit = OmniFlowAgentKit()
report = RepoProbe(".").run()
prompt = kit.agent_prompt("Run the safest saved Function", report.summary())

assert "GUIAgent OmniFlow Skill" in kit.skill()
assert kit.sample_function()["function_id"] == "open_settings_demo"
if expected_mode:
    assert report.recommended_mode == expected_mode, (report.recommended_mode, expected_mode)
assert "Run the safest saved Function" in prompt

print(f"project={project_name}")
print("python_import=ok")
print("sample_function=ok")
print(f"recommended_mode={report.recommended_mode}")
PY
  "$VENV_DIR/bin/omniflow-agentkit" probe-repo .
  "$VENV_DIR/bin/omniflow-agentkit" prompt "Run the safest saved Function" --repo . >"$WORK_ROOT/omniflow-agentkit-prompt.txt"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-list-functions --mcp-url "$MCP_URL" >"$WORK_ROOT/omniflow-agentkit-functions.json"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-list-runlogs --mcp-url "$MCP_URL" >"$WORK_ROOT/omniflow-agentkit-runlogs.json"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-recall "open Android Settings" --mcp-url "$MCP_URL" >"$RECALL_OUTPUT"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-call-function open_settings_demo --mcp-url "$MCP_URL" >"$CALL_FUNCTION_OUTPUT"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-ingest-runlog runlog_install_demo --mcp-url "$MCP_URL" >"$INGEST_RUNLOG_OUTPUT"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-guard-check open_settings_demo --mcp-url "$MCP_URL" >"$WORK_ROOT/omniflow-agentkit-guard.json"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-run-function open_settings_demo --mcp-url "$MCP_URL" >"$FUNCTION_RUN_OUTPUT"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-convert-runlog runlog_install_demo \
    --mcp-url "$MCP_URL" \
    --register \
    --function-id install_sample_apk_demo \
    --name "Install sample APK" \
    --description "Install a sample APK in the background." >"$CONVERT_OUTPUT"
  "$VENV_DIR/bin/omniflow-agentkit" mcp-run-function install_sample_apk_demo \
    --mcp-url "$MCP_URL" \
    --background >"$BACKGROUND_RUN_OUTPUT"
  RECALL_OUTPUT="$RECALL_OUTPUT" CALL_FUNCTION_OUTPUT="$CALL_FUNCTION_OUTPUT" INGEST_RUNLOG_OUTPUT="$INGEST_RUNLOG_OUTPUT" "$VENV_DIR/bin/python" - <<'PY'
import json
import os

recall = json.loads(open(os.environ["RECALL_OUTPUT"], encoding="utf-8").read())
called = json.loads(open(os.environ["CALL_FUNCTION_OUTPUT"], encoding="utf-8").read())
ingested = json.loads(open(os.environ["INGEST_RUNLOG_OUTPUT"], encoding="utf-8").read())

assert recall["decision"] == "hit"
assert recall["hit"]["function_id"] == "open_settings_demo"
assert called["success"] is True
assert called["function_id"] == "open_settings_demo"
assert ingested["accepted"] is True
assert ingested["function_id"] == "install_sample_apk_demo"

print("canonical_recall=ok")
print(f"canonical_hit_function_id={recall['hit']['function_id']}")
print("canonical_call_function=ok")
print(f"canonical_run_id={called['run_id']}")
print("canonical_ingest_runlog=ok")
print(f"canonical_ingested_function_id={ingested['function_id']}")
PY
  FUNCTION_RUN_OUTPUT="$FUNCTION_RUN_OUTPUT" "$VENV_DIR/bin/python" - <<'PY'
import json
import os

data = json.loads(open(os.environ["FUNCTION_RUN_OUTPUT"], encoding="utf-8").read())
assert data["success"] is True
assert data["function_id"] == "open_settings_demo"
assert data["guard_decision"] == "allow"
assert data["result"]["run_id"] == "mock-run-open-settings-demo"

print("external_function_trigger=ok")
print(f"triggered_function_id={data['function_id']}")
print(f"run_id={data['result']['run_id']}")
PY
  CONVERT_OUTPUT="$CONVERT_OUTPUT" BACKGROUND_RUN_OUTPUT="$BACKGROUND_RUN_OUTPUT" "$VENV_DIR/bin/python" - <<'PY'
import json
import os

converted = json.loads(open(os.environ["CONVERT_OUTPUT"], encoding="utf-8").read())
background = json.loads(open(os.environ["BACKGROUND_RUN_OUTPUT"], encoding="utf-8").read())

assert converted["success"] is True
assert converted["registered"] is True
assert converted["function_id"] == "install_sample_apk_demo"
assert background["success"] is True
assert background["function_id"] == "install_sample_apk_demo"
assert background["execution_mode"] == "background"
assert background["result"]["run_id"] == "mock-bg-install-run"

print("runlog_function_register=ok")
print(f"registered_function_id={converted['function_id']}")
print("background_install_execution=ok")
print(f"background_run_id={background['result']['run_id']}")
PY
)

if [ "${OMNIFLOW_RUN_CODEX:-0}" = "1" ] && command -v codex >/dev/null 2>&1; then
  codex exec \
    --cd "$REPO_DIR" \
    --sandbox danger-full-access \
    --skip-git-repo-check \
    -o "$CODEX_OUTPUT" \
    - <<PROMPT
You are in an external open-source repo, $PROJECT_NAME. Do not modify files.
Run exactly these three commands and no variants:

$VENV_DIR/bin/omniflow-agentkit probe-repo .
$VENV_DIR/bin/omniflow-agentkit mcp-convert-runlog runlog_install_demo --mcp-url "$MCP_URL" --register --function-id install_sample_apk_demo
$VENV_DIR/bin/omniflow-agentkit mcp-run-function install_sample_apk_demo --mcp-url "$MCP_URL" --background

Then summarize whether all commands succeeded, include recommended_mode if present,
and include the registered function_id and background run_id.
PROMPT
  cat "$CODEX_OUTPUT"
  if ! grep -q "mock-bg-install-run" "$CODEX_OUTPUT"; then
    echo "codex_function_trigger=failed" >&2
    exit 1
  fi
  echo "codex_function_trigger=ok"
else
  echo "codex_step=skip_set_OMNIFLOW_RUN_CODEX_1_to_enable"
fi

echo "omniflow_${SAFE_PROJECT}_acceptance=ok"
