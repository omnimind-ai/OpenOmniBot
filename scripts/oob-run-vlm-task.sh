#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5556}"
PACKAGE_NAME="${PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACTION="cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG"
RECEIVER_CLASS="cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver"
TASK=""
OUTPUT_PATH=""
MAX_STEPS="${MAX_STEPS:-6}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"
START_FROM_CURRENT=0
PACKAGE_TO_OPEN="${PACKAGE_TO_OPEN:-com.android.settings}"
REGISTER=0

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-run-vlm-task.sh --task "打开蓝牙" [--device SERIAL] [--output-path PATH]

Runs one OOB native Kotlin VLM task and writes the returned InternalRunLog JSON.
The script only sends adb broadcasts; VLM execution, RunLog collection, recall,
and optional convert are handled inside the Android app.

Useful options:
  --max-steps N            Default: 6
  --start-from-current     Do not prelaunch Settings; run from current page
  --package PACKAGE        App package to prelaunch when not using --start-from-current
  --register               Let the debug receiver convert/register the successful run
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device|--target)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --app-package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --task|--goal|--description)
      TASK="$2"
      shift 2
      ;;
    --output-path)
      OUTPUT_PATH="$2"
      shift 2
      ;;
    --max-steps)
      MAX_STEPS="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --start-from-current)
      START_FROM_CURRENT=1
      shift
      ;;
    --package)
      PACKAGE_TO_OPEN="$2"
      shift 2
      ;;
    --register)
      REGISTER=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "${TASK// }" ]]; then
  echo "--task is required" >&2
  usage >&2
  exit 2
fi

mkdir -p runtime/runlogs
if [[ -z "${OUTPUT_PATH// }" ]]; then
  stamp="$(date +%Y%m%d-%H%M%S)"
  OUTPUT_PATH="runtime/runlogs/${stamp}.vlm.run_log.json"
fi
mkdir -p "$(dirname "$OUTPUT_PATH")"

ADB=(adb -s "$DEVICE_SERIAL")
RECEIVER="$PACKAGE_NAME/$RECEIVER_CLASS"
RESULT_FILE="files/debug-vlm-runlog-result.json"
RESULT_TMP="$(mktemp -t oob-vlm-result.XXXXXX.json)"
trap 'rm -f "$RESULT_TMP"' EXIT

base64_text() {
  python3 - "$1" <<'PY'
import base64
import sys
print(base64.b64encode(sys.argv[1].encode("utf-8")).decode("ascii"))
PY
}

read_app_file() {
  local path="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$path" 2>/dev/null || true
}

"${ADB[@]}" get-state >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true

GOAL_B64="$(base64_text "$TASK")"
BROADCAST_ARGS=(
  shell am broadcast
  -a "$ACTION"
  -n "$RECEIVER"
  --es goalBase64 "$GOAL_B64"
  --ei maxSteps "$MAX_STEPS"
  --ez register "$([[ "$REGISTER" -eq 1 ]] && echo true || echo false)"
)
if [[ "$START_FROM_CURRENT" -eq 1 ]]; then
  BROADCAST_ARGS+=(--ez startFromCurrent true --ez skipGoHome true)
else
  BROADCAST_ARGS+=(--es packageName "$PACKAGE_TO_OPEN")
fi

"${ADB[@]}" "${BROADCAST_ARGS[@]}" >/dev/null

deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  read_app_file "$RESULT_FILE" >"$RESULT_TMP"
  if [[ -s "$RESULT_TMP" ]]; then
    break
  fi
  sleep 1
done

if [[ ! -s "$RESULT_TMP" ]]; then
  echo "Timed out waiting for VLM result on $DEVICE_SERIAL" >&2
  exit 1
fi

python3 - "$RESULT_TMP" "$OUTPUT_PATH" "$DEVICE_SERIAL" <<'PY'
import json
import sys
from pathlib import Path

result_path, output_path, target = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(result_path, encoding="utf-8"))
run_log = data.get("run_log") or {}
if isinstance(run_log, dict) and run_log:
    Path(output_path).write_text(
        json.dumps(run_log, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
else:
    output_path = ""
summary = {
    "success": data.get("success") is True,
    "target": target,
    "run_id": data.get("run_id") or run_log.get("run_id"),
    "run_log_path": output_path or None,
    "runlog_found": data.get("runlog_found"),
    "runlog_success": data.get("runlog_success"),
    "runlog_card_count": data.get("runlog_card_count"),
    "token_usage_total": data.get("token_usage_total"),
    "outcome": data.get("outcome"),
    "convert": data.get("convert"),
    "error_message": data.get("error_message") or (data.get("outcome") or {}).get("errorMessage"),
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
sys.exit(0 if data.get("runlog_found") is True else 1)
PY
