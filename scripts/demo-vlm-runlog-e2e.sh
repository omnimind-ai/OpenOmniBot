#!/usr/bin/env bash
# End-to-end demo for raw VLM execution -> runlog -> registered function -> replay.
# The API key is read by scripts/configure-oob-model-provider.sh and is never printed here.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
PROFILE_ID="${OOB_PROVIDER_PROFILE_ID:-profile-dashscope}"
MODEL_ID="${OMNIMIND_MODEL:-${OPENAI_MODEL:-qwen-vl-max-latest}}"
BASE_URL="${OMNIMIND_API_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}"
API_KEY_ENV="${OMNIMIND_API_KEY_ENV:-DASHSCOPE_API_KEY}"
SKILL_ID="${OOB_E2E_SKILL_ID:-vlm-android-gui}"
MAX_STEPS="${OOB_E2E_MAX_STEPS:-10}"
TIMEOUT_SECONDS="${OOB_E2E_TIMEOUT_SECONDS:-240}"
CONFIGURE_PROVIDER=1
DISABLE_RECALL=1
EXPECT_RECALL=0
GOAL="${OOB_E2E_GOAL:-当前在设置首页。打开蓝牙。如果蓝牙已经开启，就直接完成。不要重复点击同一位置，不要循环返回。}"

usage() {
  cat <<'EOF'
Usage:
  scripts/demo-vlm-runlog-e2e.sh --device emulator-5554
  scripts/demo-vlm-runlog-e2e.sh --device emulator-5554 --goal "打开蓝牙"

Options:
  --device <serial>          Target adb device. Default: ANDROID_SERIAL or emulator-5554.
  --package <package>        App package. Default: cn.com.omnimind.bot.debug.
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
  --expect-recall            Validate direct OmniFlow recall and skip replay. Implies --allow-recall.
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

ADB=(adb -s "$DEVICE_SERIAL")
VLM_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver"
FUNCTION_RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugOobFunctionRunReceiver"
VLM_RESULT_FILE="files/debug-vlm-runlog-result.json"
FUNCTION_RESULT_FILE="files/debug-oob-function-run-result.json"

read_app_file() {
  local path="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$path" 2>/dev/null || true
}

wait_for_result_file() {
  local path="$1"
  local label="$2"
  local start
  start="$(date +%s)"
  while true; do
    local content
    content="$(read_app_file "$path")"
    if [[ -n "$content" ]]; then
      printf '%s' "$content"
      return 0
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
data = json.load(sys.stdin)
convert = data.get("convert") or {}
value = (
    convert.get("function_id")
    or convert.get("created_function_id")
    or data.get("function_id")
    or data.get("created_function_id")
)
print(value or "")
'
}

json_get_success() {
  python3 -c '
import json, sys
data = json.load(sys.stdin)
print("true" if data.get("success") is True else "false")
'
}

json_validate_vlm_token_usage() {
  python3 -c '
import json, sys
data = json.load(sys.stdin)
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
'
}

json_get_direct_recall_completed() {
  python3 -c '
import json, sys
data = json.load(sys.stdin)
print("true" if data.get("direct_recall_completed") is True else "false")
'
}

json_get_execution_route() {
  python3 -c '
import json, sys
data = json.load(sys.stdin)
outcome = data.get("outcome") or {}
print(outcome.get("executionRoute") or outcome.get("execution_route") or "")
'
}

base64_no_wrap() {
  python3 -c 'import base64, sys; print(base64.b64encode(sys.stdin.buffer.read()).decode("ascii"))'
}

echo "== OOB VLM runlog E2E demo =="
echo "device=$DEVICE_SERIAL"
echo "package=$PACKAGE_NAME"
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
"${ADB[@]}" shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow
"${ADB[@]}" shell settings --user 0 delete secure enabled_accessibility_services >/dev/null 2>&1 || true
"${ADB[@]}" shell settings --user 0 put secure accessibility_enabled 0 || true
sleep 1
"${ADB[@]}" shell settings --user 0 put secure enabled_accessibility_services "$PACKAGE_NAME/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
"${ADB[@]}" shell settings put secure accessibility_enabled 1
"${ADB[@]}" shell am start -a android.settings.SETTINGS >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$VLM_RESULT_FILE" "$FUNCTION_RESULT_FILE" 2>/dev/null || true
sleep 1

echo "== Phase 1: raw VLM -> runlog -> registered function =="
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

vlm_result="$(wait_for_result_file "$VLM_RESULT_FILE" "VLM runlog")"
printf '%s\n' "$vlm_result"
vlm_success="$(printf '%s' "$vlm_result" | json_get_success)"
function_id="$(printf '%s' "$vlm_result" | json_get_function_id)"

if [[ "$vlm_success" != "true" || -z "$function_id" ]]; then
  if [[ "$EXPECT_RECALL" -eq 1 ]]; then
    direct_recall="$(printf '%s' "$vlm_result" | json_get_direct_recall_completed)"
    execution_route="$(printf '%s' "$vlm_result" | json_get_execution_route)"
    if [[ "$vlm_success" == "true" && "$direct_recall" == "true" && "$execution_route" == omniflow_recall* ]]; then
      echo "direct_recall_completed=true"
      echo "execution_route=$execution_route"
      echo "== Recall validation passed =="
      exit 0
    fi
  fi
  echo "VLM phase did not produce a registered reusable function." >&2
  echo "success=$vlm_success function_id=${function_id:-<empty>}" >&2
  exit 1
fi

if [[ "$EXPECT_RECALL" -eq 1 ]]; then
  echo "Expected direct recall, but VLM produced a new function: $function_id" >&2
  exit 1
fi

printf '%s' "$vlm_result" | json_validate_vlm_token_usage
echo "registered_function_id=$function_id"

echo "== Phase 2: registered function replay =="
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$FUNCTION_RESULT_FILE" 2>/dev/null || true
"${ADB[@]}" shell am start -a android.settings.SETTINGS >/dev/null
sleep 1
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_OOB_FUNCTION \
  -n "$FUNCTION_RECEIVER" \
  --es function_id "$function_id" \
  --es goalBase64 "$goal_b64" >/dev/null

function_result="$(wait_for_result_file "$FUNCTION_RESULT_FILE" "function replay")"
printf '%s\n' "$function_result"
function_success="$(printf '%s' "$function_result" | json_get_success)"

if [[ "$function_success" != "true" ]]; then
  echo "Function replay failed." >&2
  exit 1
fi

echo "== E2E demo passed =="
