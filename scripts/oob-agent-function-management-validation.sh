#!/usr/bin/env bash
# Validate that OOB Function management works through the real Agent tool path:
# AgentToolRegistry -> AgentToolRouter -> WorkbenchToolHandler -> OmniFlow run.
set -euo pipefail

DEVICE_SERIAL="${OOB_DEVICE:-emulator-5556}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
TARGET_PACKAGE="${OOB_TARGET_PACKAGE:-com.android.settings}"
FUNCTION_ID="${OOB_FUNCTION_ID:-debug_agent_function_management_open_settings}"
WAIT_SECONDS="${OOB_VALIDATION_WAIT_SECONDS:-45}"
RUN_FUNCTION=1
DELETE_BEFORE=1
RAW_JSON=0

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-agent-function-management-validation.sh
  scripts/oob-agent-function-management-validation.sh --device emulator-5554 --target-package com.android.settings

Options:
  --device <serial>          Target adb serial. Default: $OOB_DEVICE or emulator-5556.
  --package <name>           OOB package name. Default: cn.com.omnimind.bot.debug.
  --target-package <name>    Package opened by the validation Function. Default: com.android.settings.
  --function-id <id>         Function id to register/update.
  --wait-seconds <n>         Poll budget for the result file. Default: 45.
  --no-run                   Only register/list/guard; do not execute replay.
  --keep-existing            Do not delete the validation Function before registering.
  --raw-json                 Print the full app result JSON instead of a compact summary.
  --help                     Show this help.

Prerequisite:
  Normalize the target device first:
    OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5556
    OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554

Result file inside the app:
  files/debug-agent-function-management-result.json
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -lt 2 ]] && { echo "--device requires a serial" >&2; exit 1; }
      DEVICE_SERIAL="$2"
      shift
      ;;
    --device=*)
      DEVICE_SERIAL="${1#--device=}"
      ;;
    --package)
      [[ $# -lt 2 ]] && { echo "--package requires a value" >&2; exit 1; }
      PACKAGE_NAME="$2"
      shift
      ;;
    --package=*)
      PACKAGE_NAME="${1#--package=}"
      ;;
    --target-package)
      [[ $# -lt 2 ]] && { echo "--target-package requires a value" >&2; exit 1; }
      TARGET_PACKAGE="$2"
      shift
      ;;
    --target-package=*)
      TARGET_PACKAGE="${1#--target-package=}"
      ;;
    --function-id)
      [[ $# -lt 2 ]] && { echo "--function-id requires a value" >&2; exit 1; }
      FUNCTION_ID="$2"
      shift
      ;;
    --function-id=*)
      FUNCTION_ID="${1#--function-id=}"
      ;;
    --wait-seconds)
      [[ $# -lt 2 ]] && { echo "--wait-seconds requires a value" >&2; exit 1; }
      WAIT_SECONDS="$2"
      shift
      ;;
    --wait-seconds=*)
      WAIT_SECONDS="${1#--wait-seconds=}"
      ;;
    --no-run)
      RUN_FUNCTION=0
      ;;
    --keep-existing)
      DELETE_BEFORE=0
      ;;
    --raw-json)
      RAW_JSON=1
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

case "$WAIT_SECONDS" in (*[!0-9]*|"") echo "--wait-seconds must be numeric" >&2; exit 1;; esac

ADB=(adb -s "$DEVICE_SERIAL")
RESULT_FILE="files/debug-agent-function-management-result.json"
ACTION="cn.com.omnimind.bot.debug.RUN_AGENT_FUNCTION_MANAGEMENT_VALIDATION"
LOCAL_RESULT="$(mktemp "${TMPDIR:-/tmp}/oob-agent-function-result.XXXXXX.json")"
trap 'rm -f "$LOCAL_RESULT"' EXIT

echo "[oob-agent-function] device=${DEVICE_SERIAL}"
echo "[oob-agent-function] package=${PACKAGE_NAME}"
echo "[oob-agent-function] target_package=${TARGET_PACKAGE}"
echo "[oob-agent-function] function_id=${FUNCTION_ID}"

if ! adb_state_output="$("${ADB[@]}" get-state 2>&1)"; then
  echo "[oob-agent-function] validation_error=adb_unavailable" >&2
  echo "Device is not available: $DEVICE_SERIAL" >&2
  printf '%s\n' "$adb_state_output" >&2
  if [[ "$adb_state_output" == *"Operation not permitted"* || "$adb_state_output" == *"cannot connect to daemon"* ]]; then
    echo "Hint: adb daemon startup is blocked in the current shell context. Start adb with an approved direct adb command or run this validation outside the restricted sandbox, then rerun." >&2
  fi
  exit 1
fi

"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true

"${ADB[@]}" shell am broadcast \
  -a "$ACTION" \
  --es targetPackage "$TARGET_PACKAGE" \
  --es functionId "$FUNCTION_ID" \
  --ez run "$([[ "$RUN_FUNCTION" -eq 1 ]] && echo true || echo false)" \
  --ez deleteBefore "$([[ "$DELETE_BEFORE" -eq 1 ]] && echo true || echo false)" \
  "$PACKAGE_NAME" >/dev/null

started="$(date +%s)"
while true; do
  if "${ADB[@]}" shell run-as "$PACKAGE_NAME" test -s "$RESULT_FILE" >/dev/null 2>&1; then
    "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$RESULT_FILE" 2>/dev/null | tr -d '\r' > "$LOCAL_RESULT" || true
    if [[ -s "$LOCAL_RESULT" ]]; then
      break
    fi
  fi
  now="$(date +%s)"
  if (( now - started >= WAIT_SECONDS )); then
    echo "Timed out waiting for $RESULT_FILE" >&2
    exit 1
  fi
  sleep 1
done

if command -v jq >/dev/null 2>&1; then
  if [[ "$RAW_JSON" -eq 1 ]]; then
    cat "$LOCAL_RESULT"
  else
    jq '{
      success,
      source,
      agent_path,
      function_id,
      target_package,
      run_requested,
      current_package_before,
      current_package_after,
      foreground_package_matched,
      missing_tools,
      unexpected_tools,
      register_success,
      list_contains_function,
      guard_decision,
      run_success,
      phase,
      error_type,
      error_message,
      record_count: (.records // [] | length),
      records: [
        (.records // [])[] | {
          tool_name,
          success,
          result_type,
          duration_ms,
          summary,
          step_count: .payload.step_count,
          actions_executed: .payload.actions_executed,
          run_id: .payload.run_id,
          timing: .payload.timing
        }
      ]
    }' "$LOCAL_RESULT"
  fi
  success="$(jq -r '.success // false' "$LOCAL_RESULT")"
  foreground="$(jq -r '.current_package_after // ""' "$LOCAL_RESULT")"
else
  if [[ "$RAW_JSON" -eq 1 ]]; then
    cat "$LOCAL_RESULT"
  else
    python3 -c 'import json,sys
payload=json.load(sys.stdin)
records=[]
for record in payload.get("records", []):
    body=record.get("payload") if isinstance(record.get("payload"), dict) else {}
    records.append({
        "tool_name": record.get("tool_name"),
        "success": record.get("success"),
        "result_type": record.get("result_type"),
        "duration_ms": record.get("duration_ms"),
        "summary": record.get("summary"),
        "step_count": body.get("step_count"),
        "actions_executed": body.get("actions_executed"),
        "run_id": body.get("run_id"),
        "timing": body.get("timing"),
    })
summary={key: payload.get(key) for key in [
    "success","source","agent_path","function_id","target_package","run_requested",
    "current_package_before","current_package_after","foreground_package_matched",
    "missing_tools","unexpected_tools","register_success","list_contains_function",
    "guard_decision","run_success"
]}
summary["record_count"]=len(payload.get("records", []))
summary["records"]=records
print(json.dumps(summary, ensure_ascii=False, indent=2))
' < "$LOCAL_RESULT"
  fi
  success="$(python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("success", False)).lower())' < "$LOCAL_RESULT")"
  foreground="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("current_package_after", ""))' < "$LOCAL_RESULT")"
fi

echo "[oob-agent-function] foreground=${foreground:-unknown}"
if [[ "$success" != "true" ]]; then
  echo "[oob-agent-function] validation_failed=1" >&2
  exit 1
fi
echo "[oob-agent-function] validation_ok=1"
