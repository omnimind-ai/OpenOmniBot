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
MAX_STEPS="${OOB_E2E_MAX_STEPS:-10}"
TIMEOUT_SECONDS="${OOB_E2E_TIMEOUT_SECONDS:-240}"
CONFIGURE_PROVIDER=1
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
  --skip-configure-provider  Do not call scripts/configure-oob-model-provider.sh.
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
    --skip-configure-provider)
      CONFIGURE_PROVIDER=0
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

echo "== OOB VLM runlog E2E demo =="
echo "device=$DEVICE_SERIAL"
echo "package=$PACKAGE_NAME"
echo "model=$MODEL_ID"
echo "profile_id=$PROFILE_ID"
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
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$PACKAGE_NAME/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
"${ADB[@]}" shell settings put secure accessibility_enabled 1
"${ADB[@]}" shell am start -a android.settings.SETTINGS >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$VLM_RESULT_FILE" "$FUNCTION_RESULT_FILE" 2>/dev/null || true
sleep 1

echo "== Phase 1: raw VLM -> runlog -> registered function =="
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG \
  -n "$VLM_RECEIVER" \
  --es goal "$GOAL" \
  --ez prelaunch false \
  --ei maxSteps "$MAX_STEPS" \
  --ez register true \
  --es profileId "$PROFILE_ID" \
  --es modelId "$MODEL_ID" >/dev/null

vlm_result="$(wait_for_result_file "$VLM_RESULT_FILE" "VLM runlog")"
printf '%s\n' "$vlm_result"
vlm_success="$(printf '%s' "$vlm_result" | json_get_success)"
function_id="$(printf '%s' "$vlm_result" | json_get_function_id)"

if [[ "$vlm_success" != "true" || -z "$function_id" ]]; then
  echo "VLM phase did not produce a registered reusable function." >&2
  echo "success=$vlm_success function_id=${function_id:-<empty>}" >&2
  exit 1
fi

echo "registered_function_id=$function_id"

echo "== Phase 2: registered function replay =="
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$FUNCTION_RESULT_FILE" 2>/dev/null || true
"${ADB[@]}" shell am start -a android.settings.SETTINGS >/dev/null
sleep 1
"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.RUN_OOB_FUNCTION \
  -n "$FUNCTION_RECEIVER" \
  --es function_id "$function_id" \
  --es goal "$GOAL" >/dev/null

function_result="$(wait_for_result_file "$FUNCTION_RESULT_FILE" "function replay")"
printf '%s\n' "$function_result"
function_success="$(printf '%s' "$function_result" | json_get_success)"

if [[ "$function_success" != "true" ]]; then
  echo "Function replay failed." >&2
  exit 1
fi

echo "== E2E demo passed =="
