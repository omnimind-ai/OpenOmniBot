#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${OOB_MCP_PYTHON:-python3}"

LOCAL_PORT="${OOB_MCP_LOCAL_PORT:-8899}"
DEVICE_PORT="${OOB_MCP_DEVICE_PORT:-8899}"
MCP_URL="${OOB_MCP_URL:-http://127.0.0.1:${LOCAL_PORT}/mcp}"
MCP_TOKEN="${OOB_MCP_TOKEN:-}"
REQUIRE_TOKEN="${OOB_MCP_REQUIRE_TOKEN:-1}"
ADB_FORWARD="${OOB_MCP_ADB_FORWARD:-0}"
ADB_SERIAL="${OOB_MCP_ADB_SERIAL:-}"
OUTPUT_JSON="${OOB_MCP_OUTPUT_JSON:-/private/tmp/oob-omniflow-explore-replay-result.json}"

usage() {
  cat <<'EOF'
Usage:
  OOB_MCP_TOKEN=<token> bash scripts/omniflow_acceptance_oob_mcp_explore_replay.sh

Common environment variables:
  OOB_MCP_URL                  MCP JSON-RPC URL. Default: http://127.0.0.1:8899/mcp
  OOB_MCP_TOKEN                Bearer token copied from OOB settings.
  OOB_MCP_ADB_FORWARD=1        Run adb forward tcp:<local> tcp:<device> first.
  OOB_MCP_ADB_SERIAL=<serial>  Target device when forwarding.
  OOB_MCP_GOAL                 Goal. Default: open network settings
  OOB_MCP_PACKAGE_NAME         Package to launch. Default: com.android.settings
  OOB_MCP_MAX_STEPS            Bounded exploration steps. Default: 1
  OOB_MCP_STOP_TEXT            Stop when text appears. Default: empty
  OOB_MCP_REPLAY=0             Explore and register only; default runs replay too.

Token auth should stay enabled for real OOB MCP acceptance.
EOF
}

case "${1:-}" in
  --help|-h)
    usage
    exit 0
    ;;
esac

if [[ "$ADB_FORWARD" == "1" ]]; then
  ADB=(adb)
  if [[ -n "$ADB_SERIAL" ]]; then
    ADB=(adb -s "$ADB_SERIAL")
  fi
  "${ADB[@]}" forward "tcp:${LOCAL_PORT}" "tcp:${DEVICE_PORT}" >/dev/null
  MCP_URL="${OOB_MCP_URL:-http://127.0.0.1:${LOCAL_PORT}/mcp}"
  echo "adb_forward=ok local_port=${LOCAL_PORT} device_port=${DEVICE_PORT}"
fi

if [[ "$REQUIRE_TOKEN" != "0" && -z "$MCP_TOKEN" ]]; then
  echo "OOB_MCP_TOKEN is required for the real OOB MCP server." >&2
  echo "Open OOB Settings, enable MCP Server, copy the token, then rerun." >&2
  exit 2
fi

export ROOT_DIR
export MCP_URL
export MCP_TOKEN
export OUTPUT_JSON
export OOB_MCP_GOAL="${OOB_MCP_GOAL:-open network settings}"
export OOB_MCP_PACKAGE_NAME="${OOB_MCP_PACKAGE_NAME:-com.android.settings}"
export OOB_MCP_MAX_STEPS="${OOB_MCP_MAX_STEPS:-1}"
export OOB_MCP_SETTLE_DELAY_MS="${OOB_MCP_SETTLE_DELAY_MS:-800}"
export OOB_MCP_STOP_TEXT="${OOB_MCP_STOP_TEXT:-}"
export OOB_MCP_ALLOW_RISKY_ACTIONS="${OOB_MCP_ALLOW_RISKY_ACTIONS:-0}"
export OOB_MCP_FUNCTION_ID="${OOB_MCP_FUNCTION_ID:-}"
export OOB_MCP_REPLAY="${OOB_MCP_REPLAY:-1}"
export OOB_MCP_RESET_BEFORE_REPLAY="${OOB_MCP_RESET_BEFORE_REPLAY:-1}"
export OOB_MCP_RESET_BACK_STEPS="${OOB_MCP_RESET_BACK_STEPS:-1}"
export OOB_MCP_TIMEOUT="${OOB_MCP_TIMEOUT:-60}"

PYTHONPATH="$ROOT_DIR${PYTHONPATH:+:$PYTHONPATH}" "$PYTHON_BIN" - <<'PY'
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

from omniflow_agentkit.mcp import OmniFlowMcpClient


def env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        fail("invalid_env", f"{name} must be an integer, got {raw!r}")


def as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def first_non_blank(*values: Any) -> str:
    for value in values:
        text = "" if value is None else str(value).strip()
        if text:
            return text
    return ""


def number_value(value: Any, default: int = 0) -> int:
    if isinstance(value, bool):
        return default
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        try:
            return int(float(value))
        except ValueError:
            return default
    return default


def fail(code: str, message: str, payload: Any | None = None) -> None:
    print(f"acceptance_error={code}", file=sys.stderr)
    print(message, file=sys.stderr)
    if payload is not None:
        print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
    raise SystemExit(1)


url = os.environ["MCP_URL"]
token = os.environ.get("MCP_TOKEN") or None
timeout = float(os.environ.get("OOB_MCP_TIMEOUT", "60"))
client = OmniFlowMcpClient(endpoint=url, token=token, timeout=timeout)

print(f"mcp_url={url}")
client.initialize()
tools = client.list_tools()
names = {str(tool.get("name", "")).strip() for tool in tools if isinstance(tool, dict)}
required_tools = {
    "omniflow.recall",
    "omniflow.call_function",
    "omniflow.ingest_run_log",
    "omniflow.explore_replay",
    "oob_function_get",
    "oob_function_guard_check",
}
missing = sorted(required_tools - names)
if missing:
    fail("missing_tools", f"MCP tools/list is missing required tools: {missing}", sorted(names))
print(f"tools_list=ok count={len(names)}")

replay = env_bool("OOB_MCP_REPLAY", True)
result = client.explore_replay(
    os.environ["OOB_MCP_GOAL"],
    package_name=os.environ.get("OOB_MCP_PACKAGE_NAME", ""),
    max_steps=env_int("OOB_MCP_MAX_STEPS", 3),
    settle_delay_ms=env_int("OOB_MCP_SETTLE_DELAY_MS", 800),
    stop_text=os.environ.get("OOB_MCP_STOP_TEXT", ""),
    allow_risky_actions=env_bool("OOB_MCP_ALLOW_RISKY_ACTIONS", False),
    function_id=os.environ.get("OOB_MCP_FUNCTION_ID", ""),
    replay=replay,
    reset_before_replay=env_bool("OOB_MCP_RESET_BEFORE_REPLAY", True),
    reset_back_steps=env_int("OOB_MCP_RESET_BACK_STEPS", 1),
)

output_path = Path(os.environ["OUTPUT_JSON"])
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

if result.get("success") is not True:
    fail("explore_replay_failed", "omniflow.explore_replay returned success=false", result)

function_id = first_non_blank(result.get("function_id"), result.get("created_function_id"))
run_id = first_non_blank(result.get("run_id"), as_dict(result.get("explore")).get("run_id"))
if not function_id:
    fail("function_id_empty", "explore_replay did not return a generated function id", result)
if not run_id:
    fail("run_id_empty", "explore_replay did not return a run id", result)

utg = as_dict(result.get("utg")) or as_dict(as_dict(result.get("explore")).get("utg"))
if utg.get("schema_version") != "oob.omniflow_utg.v1":
    fail("utg_schema_mismatch", "UTG schema_version is not oob.omniflow_utg.v1", result)
edge_count = number_value(utg.get("edge_count"), len(as_list(utg.get("edges"))))
if edge_count < 1:
    fail("utg_empty_path", "UTG edge_count must be >= 1 for replayable acceptance", result)

explore = as_dict(result.get("explore"))
step_count = number_value(explore.get("step_count"), number_value(result.get("step_count")))
if step_count < 1:
    fail("explore_step_count_empty", "explore_replay did not report any exploration step", result)

if replay:
    replay_result = as_dict(result.get("replay"))
    if replay_result.get("success") is not True:
        fail("replay_failed", "explore_replay did not replay the generated Function successfully", result)
    replay_steps = as_list(replay_result.get("step_results"))
    actions_executed = number_value(replay_result.get("actions_executed"), len(replay_steps))
    if actions_executed < 1:
        fail("replay_no_actions", "replay result did not execute any action", result)
else:
    if result.get("replay_skipped") is not True:
        fail("replay_skip_missing", "OOB_MCP_REPLAY=0 should return replay_skipped=true", result)

guard = client.guard_check(function_id)
if guard.get("decision") not in {"allow", "needs_agent"}:
    fail("guard_rejected_generated_function", "Generated Function guard did not allow replay", guard)

function_spec = client.get_function(function_id)
execution = as_dict(function_spec.get("execution"))
compiled_steps = number_value(
    execution.get("omniflow_step_count"),
    number_value(execution.get("step_count"), len(as_list(execution.get("steps")))),
)
if compiled_steps < 1:
    fail("compiled_function_empty", "Generated Function does not contain replayable steps", function_spec)

print("explore_replay=ok")
print(f"phase={result.get('phase')}")
print(f"run_id={run_id}")
print(f"function_id={function_id}")
print("utg_schema=oob.omniflow_utg.v1")
print(f"utg_edge_count={edge_count}")
print(f"explore_step_count={step_count}")
print(f"compiled_step_count={compiled_steps}")
if replay:
    print("replay=ok")
else:
    print("replay=skipped")
print(f"result_json={output_path}")
print("oob_mcp_explore_replay_acceptance=ok")
PY
