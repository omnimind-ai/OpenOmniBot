#!/usr/bin/env bash
# Validate Function registration/management through a real in-app Agent
# conversation run, using the focused function_management tool profile.
set -euo pipefail

DEVICE_SERIAL="${OOB_DEVICE:-emulator-5556}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
TARGET_PACKAGE="${OOB_TARGET_PACKAGE:-com.android.settings}"
FUNCTION_ID="${OOB_FUNCTION_ID:-debug_agent_conversation_open_settings}"
PROFILE_ID="${OOB_PROVIDER_PROFILE_ID:-profile-dashscope}"
MODEL_ID="${OMNIMIND_MODEL:-${OPENAI_MODEL:-qwen-vl-max-latest}}"
WAIT_SECONDS="${OOB_VALIDATION_WAIT_SECONDS:-240}"
CLOCK_GUARD="${OOB_CLOCK_GUARD:-auto}"
CLOCK_GUARD_INTERVAL_SECONDS="${OOB_CLOCK_GUARD_INTERVAL_SECONDS:-5}"
USER_MESSAGE=""
RAW_JSON=0

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-agent-conversation-function-validation.sh --device emulator-5554

Options:
  --device <serial>          Target adb serial. Default: $OOB_DEVICE or emulator-5556.
  --package <name>           OOB package name. Default: cn.com.omnimind.bot.debug.
  --target-package <name>    Package opened by the validation Function. Default: com.android.settings.
  --function-id <id>         Function id to register/update.
  --profile-id <id>          Model provider profile id. Default: profile-dashscope.
  --model <id>               Model id. Default: $OMNIMIND_MODEL, $OPENAI_MODEL, or qwen-vl-max-latest.
  --message <text>           Override the default agent conversation prompt.
  --wait-seconds <n>         Poll budget for the result file. Default: 240.
  --raw-json                 Print the full app result JSON instead of a compact summary.
  --help                     Show this help.

Prerequisite:
  Normalize the target device and configure the provider first:
    OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554
    scripts/configure-oob-model-provider.sh --device emulator-5554 --profile-id profile-dashscope

Result file inside the app:
  files/debug-agent-conversation-function-result.json
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -lt 2 ]] && { echo "--device requires a serial" >&2; exit 1; }
      DEVICE_SERIAL="$2"
      shift
      ;;
    --device=*) DEVICE_SERIAL="${1#--device=}" ;;
    --package)
      [[ $# -lt 2 ]] && { echo "--package requires a value" >&2; exit 1; }
      PACKAGE_NAME="$2"
      shift
      ;;
    --package=*) PACKAGE_NAME="${1#--package=}" ;;
    --target-package)
      [[ $# -lt 2 ]] && { echo "--target-package requires a value" >&2; exit 1; }
      TARGET_PACKAGE="$2"
      shift
      ;;
    --target-package=*) TARGET_PACKAGE="${1#--target-package=}" ;;
    --function-id)
      [[ $# -lt 2 ]] && { echo "--function-id requires a value" >&2; exit 1; }
      FUNCTION_ID="$2"
      shift
      ;;
    --function-id=*) FUNCTION_ID="${1#--function-id=}" ;;
    --profile-id)
      [[ $# -lt 2 ]] && { echo "--profile-id requires a value" >&2; exit 1; }
      PROFILE_ID="$2"
      shift
      ;;
    --profile-id=*) PROFILE_ID="${1#--profile-id=}" ;;
    --model)
      [[ $# -lt 2 ]] && { echo "--model requires a value" >&2; exit 1; }
      MODEL_ID="$2"
      shift
      ;;
    --model=*) MODEL_ID="${1#--model=}" ;;
    --message)
      [[ $# -lt 2 ]] && { echo "--message requires text" >&2; exit 1; }
      USER_MESSAGE="$2"
      shift
      ;;
    --message=*) USER_MESSAGE="${1#--message=}" ;;
    --wait-seconds)
      [[ $# -lt 2 ]] && { echo "--wait-seconds requires a value" >&2; exit 1; }
      WAIT_SECONDS="$2"
      shift
      ;;
    --wait-seconds=*) WAIT_SECONDS="${1#--wait-seconds=}" ;;
    --raw-json) RAW_JSON=1 ;;
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
case "$CLOCK_GUARD_INTERVAL_SECONDS" in (*[!0-9]*|"") echo "OOB_CLOCK_GUARD_INTERVAL_SECONDS must be numeric" >&2; exit 1;; esac

ADB=(adb -s "$DEVICE_SERIAL")
ACTION="cn.com.omnimind.bot.debug.RUN_AGENT_CONVERSATION_FUNCTION_VALIDATION"
RESULT_FILE="files/debug-agent-conversation-function-result.json"
LOCAL_RESULT="$(mktemp "${TMPDIR:-/tmp}/oob-agent-conversation-function.XXXXXX.json")"
CLOCK_GUARD_PID=""

cleanup() {
  if [[ -n "${CLOCK_GUARD_PID:-}" ]]; then
    kill "$CLOCK_GUARD_PID" >/dev/null 2>&1 || true
  fi
  rm -f "$LOCAL_RESULT"
}
trap cleanup EXIT

base64_no_wrap() {
  python3 -c 'import base64, sys; print(base64.b64encode(sys.stdin.buffer.read()).decode("ascii"))'
}

sync_device_clock_once() {
  local stamp
  stamp="$(date -u +%m%d%H%M%Y.%S)"
  "${ADB[@]}" shell settings put global auto_time 0 >/dev/null 2>&1 || true
  "${ADB[@]}" shell settings put global auto_time_zone 0 >/dev/null 2>&1 || true
  "${ADB[@]}" shell su 0 date "$stamp" >/dev/null 2>&1 ||
    "${ADB[@]}" shell date "$stamp" >/dev/null 2>&1 ||
    true
}

should_start_clock_guard() {
  if [[ "$CLOCK_GUARD" == "1" || "$CLOCK_GUARD" == "true" ]]; then
    return 0
  fi
  if [[ "$CLOCK_GUARD" == "0" || "$CLOCK_GUARD" == "false" ]]; then
    return 1
  fi
  [[ "$DEVICE_SERIAL" == emulator-* ]]
}

start_clock_guard() {
  should_start_clock_guard || return 0
  echo "[oob-agent-conversation-function] clock_guard=enabled interval=${CLOCK_GUARD_INTERVAL_SECONDS}s"
  sync_device_clock_once
  (
    while true; do
      sleep "$CLOCK_GUARD_INTERVAL_SECONDS"
      sync_device_clock_once
    done
  ) &
  CLOCK_GUARD_PID="$!"
}

echo "[oob-agent-conversation-function] device=${DEVICE_SERIAL}"
echo "[oob-agent-conversation-function] package=${PACKAGE_NAME}"
echo "[oob-agent-conversation-function] target_package=${TARGET_PACKAGE}"
echo "[oob-agent-conversation-function] function_id=${FUNCTION_ID}"
echo "[oob-agent-conversation-function] profile_id=${PROFILE_ID}"
echo "[oob-agent-conversation-function] model=${MODEL_ID}"

if ! adb_state_output="$("${ADB[@]}" get-state 2>&1)"; then
  echo "[oob-agent-conversation-function] validation_error=adb_unavailable" >&2
  echo "Device is not available: $DEVICE_SERIAL" >&2
  printf '%s\n' "$adb_state_output" >&2
  if [[ "$adb_state_output" == *"Operation not permitted"* || "$adb_state_output" == *"cannot connect to daemon"* ]]; then
    echo "Hint: adb daemon startup is blocked in the current shell context. Start adb with an approved direct adb command or run this validation outside the restricted sandbox, then rerun." >&2
  fi
  exit 1
fi

start_clock_guard
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true

BROADCAST_ARGS=(
  -a "$ACTION"
  --es targetPackage "$TARGET_PACKAGE"
  --es functionId "$FUNCTION_ID"
  --es profileId "$PROFILE_ID"
  --es modelId "$MODEL_ID"
  --el waitMs "$((WAIT_SECONDS * 1000))"
  "$PACKAGE_NAME"
)
if [[ -n "${USER_MESSAGE// }" ]]; then
  message_b64="$(printf '%s' "$USER_MESSAGE" | base64_no_wrap)"
  BROADCAST_ARGS=( "${BROADCAST_ARGS[@]:0:${#BROADCAST_ARGS[@]}-1}" --es userMessageBase64 "$message_b64" "$PACKAGE_NAME" )
fi

"${ADB[@]}" shell am broadcast "${BROADCAST_ARGS[@]}" >/dev/null

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
  sleep 2
done

if [[ "$RAW_JSON" -eq 1 ]]; then
  cat "$LOCAL_RESULT"
else
  python3 -c 'import json, sys
data=json.load(open(sys.argv[1], "r", encoding="utf-8"))
summary={
  "success": data.get("success"),
  "source": data.get("source"),
  "agent_path": data.get("agent_path"),
  "conversation_id": data.get("conversation_id"),
  "task_id": data.get("task_id"),
  "function_id": data.get("function_id"),
  "target_package": data.get("target_package"),
  "tool_profile": data.get("tool_profile"),
  "allowed_tools": data.get("allowed_tools"),
  "function_registered": data.get("function_registered"),
  "run_success": data.get("run_success"),
  "run_summary": data.get("run_summary"),
  "message_count": data.get("message_count"),
  "assistant_tail": data.get("assistant_tail"),
  "function": data.get("function"),
  "phase": data.get("phase"),
  "error_type": data.get("error_type"),
  "error_message": data.get("error_message"),
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
' "$LOCAL_RESULT"
fi

success="$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1], "r", encoding="utf-8")).get("success", False)).lower())' "$LOCAL_RESULT")"
if [[ "$success" != "true" ]]; then
  echo "[oob-agent-conversation-function] validation_failed=1" >&2
  exit 1
fi
echo "[oob-agent-conversation-function] validation_ok=1"
