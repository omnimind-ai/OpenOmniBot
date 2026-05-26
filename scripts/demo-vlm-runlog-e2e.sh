#!/usr/bin/env bash
# End-to-end demo for raw VLM execution -> runlog -> registered reusable command -> replay.
# The API key is read by scripts/configure-oob-model-provider.sh and is never printed here.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
TARGET_APP_PACKAGE="${OOB_E2E_TARGET_APP_PACKAGE:-com.android.settings}"
START_INTENT_ACTION="${OOB_E2E_START_INTENT_ACTION:-android.settings.SETTINGS}"
STARTUP_PROFILE="${OOB_START_PROFILE:-}"
PROFILE_ID="${OOB_PROVIDER_PROFILE_ID:-profile-dashscope}"
MODEL_ID="${OMNIMIND_MODEL:-${OPENAI_MODEL:-qwen-vl-max-latest}}"
BASE_URL="${OMNIMIND_API_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}"
API_KEY_ENV="${OMNIMIND_API_KEY_ENV:-DASHSCOPE_API_KEY}"
SKILL_ID="${OOB_E2E_SKILL_ID:-vlm-android-gui}"
MAX_STEPS="${OOB_E2E_MAX_STEPS:-10}"
TIMEOUT_SECONDS="${OOB_E2E_TIMEOUT_SECONDS:-240}"
CLOCK_GUARD="${OOB_CLOCK_GUARD:-auto}"
CLOCK_GUARD_INTERVAL_SECONDS="${OOB_CLOCK_GUARD_INTERVAL_SECONDS:-5}"
CONFIGURE_PROVIDER=1
DISABLE_RECALL=1
EXPECT_RECALL=0
RAW_JSON=0
GOAL="${OOB_E2E_GOAL:-当前在设置首页。打开蓝牙。如果蓝牙已经开启，就直接完成。不要重复点击同一位置，不要循环返回。}"

usage() {
  cat <<'EOF'
Usage:
  scripts/demo-vlm-runlog-e2e.sh --device emulator-5554
  scripts/demo-vlm-runlog-e2e.sh --device emulator-5554 --goal "打开蓝牙"

Options:
  --device <serial>          Target adb device. Default: ANDROID_SERIAL or emulator-5554.
  --package <package>        App package. Default: cn.com.omnimind.bot.debug.
  --target-app-package <pkg> Target app to reset before each phase. Default: com.android.settings.
  --start-intent-action <a>  Intent action used to open the target app. Default: android.settings.SETTINGS.
  --startup-profile <name>   oob-start profile. Default: 5554 for emulator-5554, 5556 for emulator-5556.
  --goal <text>              VLM task goal. Default: Bluetooth settings demo.
  --max-steps <n>            VLM max steps. Default: 10.
  --timeout <seconds>        Poll timeout for each phase. Default: 240.
  --base-url <url>           Provider base URL. Default: DashScope compatible endpoint.
  --api-key-env <name>       Env var for API key. Default: DASHSCOPE_API_KEY.
  --model <model>            Model id. Default: qwen-vl-max-latest.
  --profile-id <id>          Provider profile id. Default: profile-dashscope.
  --skill-id <id>            Builtin skill guidance for VLM. Default: vlm-android-gui.
  --skip-configure-provider  Do not call scripts/configure-oob-model-provider.sh.
  --allow-recall             Allow OmniFlow recall during phase 1. Default disables recall so the demo validates fresh VLM RunLog capture.
  --expect-recall            Validate OmniFlow recall hit and skip replay. Accepts direct or segment recall; implies --allow-recall.
  --raw-json                 Print full app result JSON for both phases.
  --help                     Show this help text.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_SERIAL="$2"
      shift
      ;;
    --device=*)
      DEVICE_SERIAL="${1#--device=}"
      ;;
    --package)
      PACKAGE_NAME="$2"
      shift
      ;;
    --package=*)
      PACKAGE_NAME="${1#--package=}"
      ;;
    --target-app-package)
      TARGET_APP_PACKAGE="$2"
      shift
      ;;
    --target-app-package=*)
      TARGET_APP_PACKAGE="${1#--target-app-package=}"
      ;;
    --start-intent-action)
      START_INTENT_ACTION="$2"
      shift
      ;;
    --start-intent-action=*)
      START_INTENT_ACTION="${1#--start-intent-action=}"
      ;;
    --startup-profile)
      STARTUP_PROFILE="$2"
      shift
      ;;
    --startup-profile=*)
      STARTUP_PROFILE="${1#--startup-profile=}"
      ;;
    --goal)
      GOAL="$2"
      shift
      ;;
    --goal=*)
      GOAL="${1#--goal=}"
      ;;
    --max-steps)
      MAX_STEPS="$2"
      shift
      ;;
    --max-steps=*)
      MAX_STEPS="${1#--max-steps=}"
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift
      ;;
    --timeout=*)
      TIMEOUT_SECONDS="${1#--timeout=}"
      ;;
    --base-url)
      BASE_URL="$2"
      shift
      ;;
    --base-url=*)
      BASE_URL="${1#--base-url=}"
      ;;
    --api-key-env)
      API_KEY_ENV="$2"
      shift
      ;;
    --api-key-env=*)
      API_KEY_ENV="${1#--api-key-env=}"
      ;;
    --model)
      MODEL_ID="$2"
      shift
      ;;
    --model=*)
      MODEL_ID="${1#--model=}"
      ;;
    --profile-id)
      PROFILE_ID="$2"
      shift
      ;;
    --profile-id=*)
      PROFILE_ID="${1#--profile-id=}"
      ;;
    --skill-id)
      SKILL_ID="$2"
      shift
      ;;
    --skill-id=*)
      SKILL_ID="${1#--skill-id=}"
      ;;
    --skip-configure-provider)
      CONFIGURE_PROVIDER=0
      ;;
    --allow-recall)
      DISABLE_RECALL=0
      ;;
    --expect-recall)
      DISABLE_RECALL=0
      EXPECT_RECALL=1
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

case "$MAX_STEPS" in (*[!0-9]*|"") echo "--max-steps must be numeric" >&2; exit 1;; esac
case "$TIMEOUT_SECONDS" in (*[!0-9]*|"") echo "--timeout must be numeric" >&2; exit 1;; esac
case "$CLOCK_GUARD_INTERVAL_SECONDS" in (*[!0-9]*|"") echo "OOB_CLOCK_GUARD_INTERVAL_SECONDS must be numeric" >&2; exit 1;; esac

ADB=(adb -s "$DEVICE_SERIAL")
if [[ -z "${STARTUP_PROFILE// }" ]]; then
  case "$DEVICE_SERIAL" in
    emulator-5556) STARTUP_PROFILE="5556" ;;
    *) STARTUP_PROFILE="5554" ;;
  esac
fi
VLM_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver"
FUNCTION_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugOobFunctionRunReceiver"
VLM_RESULT_FILE="files/debug-vlm-runlog-result.json"
FUNCTION_RESULT_FILE="files/debug-oob-function-run-result.json"
LOCAL_VLM_RESULT="$(mktemp "${TMPDIR:-/tmp}/oob-vlm-result.XXXXXX.json")"
LOCAL_FUNCTION_RESULT="$(mktemp "${TMPDIR:-/tmp}/oob-function-result.XXXXXX.json")"
CLOCK_GUARD_PID=""

cleanup() {
  if [[ -n "${CLOCK_GUARD_PID:-}" ]]; then
    kill "$CLOCK_GUARD_PID" >/dev/null 2>&1 || true
    wait "$CLOCK_GUARD_PID" 2>/dev/null || true
  fi
  rm -f "$LOCAL_VLM_RESULT" "$LOCAL_FUNCTION_RESULT"
}
trap cleanup EXIT

read_app_file() {
  local path="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$path" 2>/dev/null || true
}

wait_for_result_file() {
  local path="$1"
  local label="$2"
  local output_path="$3"
  local start
  start="$(date +%s)"
  while true; do
    if "${ADB[@]}" shell run-as "$PACKAGE_NAME" test -s "$path" >/dev/null 2>&1; then
      read_app_file "$path" | tr -d '\r' > "$output_path"
      if [[ -s "$output_path" ]]; then
        return 0
      fi
    fi
    if (( "$(date +%s)" - start >= TIMEOUT_SECONDS )); then
      echo "Timed out waiting for $label result: $path" >&2
      return 1
    fi
    sleep 2
  done
}

json_get_function_id() {
  python3 -c '
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
convert = data.get("convert") or {}
value = (
    convert.get("function_id")
    or convert.get("created_function_id")
    or data.get("function_id")
    or data.get("created_function_id")
)
print(value or "")
' "$1"
}

json_get_success() {
  python3 -c '
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
print("true" if data.get("success") is True else "false")
' "$1"
}

json_validate_vlm_token_usage() {
  python3 -c '
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
total = data.get("token_usage_total")
if total is None:
    total = (data.get("token_usage") or {}).get("total_tokens")
try:
    total_int = int(total)
except Exception:
    total_int = 0
steps = data.get("token_usage_by_step") or []
calls = data.get("token_usage_by_call") or []
if total_int <= 0:
    raise SystemExit("missing token_usage_total")
if not steps:
    raise SystemExit("missing token_usage_by_step")
if not calls:
    raise SystemExit("missing token_usage_by_call")
reported_calls = (data.get("token_usage") or {}).get("call_count")
if reported_calls is not None and int(reported_calls) != len(calls):
    raise SystemExit(f"token_usage_by_call count mismatch: summary={reported_calls} calls={len(calls)}")
for index, call in enumerate(calls):
    usage = call.get("token_usage") or {}
    if int(usage.get("total_tokens") or 0) <= 0:
        raise SystemExit(f"missing token usage total for VLM call {index}")
print(f"token_usage_total={total_int}")
print(f"token_usage_step_count={len(steps)}")
print(f"token_usage_call_count={len(calls)}")
' "$1"
}

json_get_direct_recall_completed() {
  python3 -c '
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
print("true" if data.get("direct_recall_completed") is True else "false")
' "$1"
}

json_get_execution_route() {
  python3 -c '
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
outcome = data.get("outcome") or {}
print(outcome.get("executionRoute") or outcome.get("execution_route") or "")
' "$1"
}

print_vlm_summary() {
  python3 -c '
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
convert = data.get("convert") or {}
convert_summary = convert.get("summary") if isinstance(convert.get("summary"), dict) else {}
outcome = data.get("outcome") or {}
summary = {
    "success": data.get("success"),
    "run_id": data.get("run_id"),
    "goal": data.get("goal"),
    "model_id": data.get("model_id") or data.get("model"),
    "runlog_card_count": data.get("runlog_card_count"),
    "function_step_count": convert_summary.get("step_count"),
    "omniflow_step_count": convert_summary.get("omniflow_step_count"),
    "direct_recall_completed": data.get("direct_recall_completed"),
    "execution_route": outcome.get("executionRoute") or outcome.get("execution_route"),
    "function_id": convert.get("function_id") or convert.get("created_function_id") or data.get("function_id"),
    "token_usage_total": data.get("token_usage_total") or (data.get("token_usage") or {}).get("total_tokens"),
    "token_usage_call_count": (data.get("token_usage") or {}).get("call_count") or len(data.get("token_usage_by_call") or []),
    "token_usage_step_count": len(data.get("token_usage_by_step") or []),
    "error_message": data.get("error_message") or data.get("error"),
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
' "$1"
}

print_function_summary() {
  python3 -c '
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
payload = data.get("result") if isinstance(data.get("result"), dict) else data
oob_result = data.get("oob_result") if isinstance(data.get("oob_result"), dict) else {}
timing = data.get("timing") if isinstance(data.get("timing"), dict) else {}
summary = {
    "success": data.get("success"),
    "function_id": data.get("function_id") or payload.get("function_id"),
    "run_id": data.get("run_id") or payload.get("run_id"),
    "step_count": data.get("step_count") or payload.get("step_count") or oob_result.get("step_count"),
    "success_step_count": data.get("success_step_count") or payload.get("success_step_count") or oob_result.get("success_step_count"),
    "actions_executed": data.get("actions_executed") or payload.get("actions_executed"),
    "duration_ms": data.get("duration_ms") or payload.get("duration_ms") or timing.get("runner_duration_ms"),
    "model_used": oob_result.get("model_used"),
    "error_message": data.get("error_message") or data.get("error") or payload.get("error_message"),
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
' "$1"
}

base64_no_wrap() {
  python3 -c 'import base64, sys; print(base64.b64encode(sys.stdin.buffer.read()).decode("ascii"))'
}

sync_device_clock_once() {
  local epoch
  epoch="$(date -u +%s)"
  "${ADB[@]}" shell settings put global auto_time 0 >/dev/null 2>&1 || true
  "${ADB[@]}" shell settings put global auto_time_zone 0 >/dev/null 2>&1 || true
  "${ADB[@]}" shell su 0 date "@${epoch}" >/dev/null 2>&1 ||
    "${ADB[@]}" shell date "@${epoch}" >/dev/null 2>&1 ||
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
  echo "clock_guard=enabled interval=${CLOCK_GUARD_INTERVAL_SECONDS}s"
  sync_device_clock_once
  (
    while true; do
      sleep "$CLOCK_GUARD_INTERVAL_SECONDS"
      sync_device_clock_once
    done
  ) &
  CLOCK_GUARD_PID="$!"
}

echo "== OOB VLM runlog E2E demo =="
echo "device=$DEVICE_SERIAL"
echo "package=$PACKAGE_NAME"
echo "target_app_package=$TARGET_APP_PACKAGE"
echo "start_intent_action=$START_INTENT_ACTION"
echo "startup_profile=$STARTUP_PROFILE"
echo "model=$MODEL_ID"
echo "profile_id=$PROFILE_ID"
echo "skill_id=$SKILL_ID"
echo "goal=$GOAL"

if [[ "$CONFIGURE_PROVIDER" -eq 1 ]]; then
  echo "== Configure provider =="
  bash scripts/configure-oob-model-provider.sh \
    --device "$DEVICE_SERIAL" \
    --package "$PACKAGE_NAME" \
    --base-url "$BASE_URL" \
    --api-key-env "$API_KEY_ENV" \
    --model "$MODEL_ID" \
    --profile-id "$PROFILE_ID" \
    --name "DashScope" \
    --no-read-result
fi

echo "== Prepare device =="
scripts/oob-start.sh \
  --profile "$STARTUP_PROFILE" \
  --device "$DEVICE_SERIAL" \
  --skip-build \
  --skip-install \
  --wait-seconds 30 \
  --settle-seconds 0
start_clock_guard
"${ADB[@]}" shell am force-stop "$TARGET_APP_PACKAGE" >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -a "$START_INTENT_ACTION" >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$VLM_RESULT_FILE" "$FUNCTION_RESULT_FILE" 2>/dev/null || true
sleep 1

echo "== Phase 1: raw VLM -> runlog -> registered reusable command =="
goal_b64="$(printf '%s' "$GOAL" | base64_no_wrap)"
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG \
  -n "$VLM_RECEIVER" \
  --es goalBase64 "$goal_b64" \
  --ez prelaunch false \
  --ei maxSteps "$MAX_STEPS" \
  --ez register true \
  --ez disableOmniFlowRecall "$( [[ "$DISABLE_RECALL" -eq 1 ]] && printf true || printf false )" \
  --es profileId "$PROFILE_ID" \
  --es modelId "$MODEL_ID" \
  --es skillId "$SKILL_ID" >/dev/null

wait_for_result_file "$VLM_RESULT_FILE" "VLM runlog" "$LOCAL_VLM_RESULT"
if [[ "$RAW_JSON" -eq 1 ]]; then
  cat "$LOCAL_VLM_RESULT"
else
  print_vlm_summary "$LOCAL_VLM_RESULT"
fi
vlm_success="$(json_get_success "$LOCAL_VLM_RESULT")"
function_id="$(json_get_function_id "$LOCAL_VLM_RESULT")"

if [[ "$EXPECT_RECALL" -eq 1 && "$vlm_success" == "true" ]]; then
  direct_recall="$(json_get_direct_recall_completed "$LOCAL_VLM_RESULT")"
  execution_route="$(json_get_execution_route "$LOCAL_VLM_RESULT")"
  if [[ "$direct_recall" == "true" || "$execution_route" == *omniflow_recall* ]]; then
    echo "direct_recall_completed=$direct_recall"
    echo "execution_route=$execution_route"
    if [[ "$direct_recall" != "true" ]]; then
      json_validate_vlm_token_usage "$LOCAL_VLM_RESULT"
      if [[ -n "$function_id" ]]; then
        echo "registered_function_id=$function_id"
      fi
    fi
    echo "== Recall validation passed =="
    exit 0
  fi
fi

if [[ "$vlm_success" != "true" || -z "$function_id" ]]; then
  if [[ "$EXPECT_RECALL" -eq 1 ]]; then
    direct_recall="$(json_get_direct_recall_completed "$LOCAL_VLM_RESULT")"
    execution_route="$(json_get_execution_route "$LOCAL_VLM_RESULT")"
    if [[ "$vlm_success" == "true" && "$direct_recall" == "true" && "$execution_route" == omniflow_recall* ]]; then
      echo "direct_recall_completed=true"
      echo "execution_route=$execution_route"
      echo "== Recall validation passed =="
      exit 0
    fi
  fi
  echo "VLM phase did not produce a registered reusable command." >&2
  echo "success=$vlm_success function_id=${function_id:-<empty>}" >&2
  exit 1
fi

if [[ "$EXPECT_RECALL" -eq 1 ]]; then
  execution_route="$(json_get_execution_route "$LOCAL_VLM_RESULT")"
  echo "Expected OmniFlow recall hit, but execution_route=${execution_route:-<empty>} and new function=$function_id" >&2
  exit 1
fi

json_validate_vlm_token_usage "$LOCAL_VLM_RESULT"
echo "registered_function_id=$function_id"

echo "== Phase 2: registered reusable command replay =="
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$FUNCTION_RESULT_FILE" 2>/dev/null || true
"${ADB[@]}" shell am force-stop "$TARGET_APP_PACKAGE" >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -a "$START_INTENT_ACTION" >/dev/null
sleep 1
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_OOB_FUNCTION \
  -n "$FUNCTION_RECEIVER" \
  --es function_id "$function_id" \
  --es goalBase64 "$goal_b64" >/dev/null

wait_for_result_file "$FUNCTION_RESULT_FILE" "function replay" "$LOCAL_FUNCTION_RESULT"
if [[ "$RAW_JSON" -eq 1 ]]; then
  cat "$LOCAL_FUNCTION_RESULT"
else
  print_function_summary "$LOCAL_FUNCTION_RESULT"
fi
function_success="$(json_get_success "$LOCAL_FUNCTION_RESULT")"

if [[ "$function_success" != "true" ]]; then
  echo "Reusable command replay failed." >&2
  exit 1
fi

echo "== E2E demo passed =="
