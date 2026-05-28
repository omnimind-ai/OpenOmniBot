#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5556}"
PACKAGE_NAME="${PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACTION="cn.com.omnimind.bot.debug.CONVERT_RUNLOG_AND_RUN_FUNCTION"
RECEIVER_CLASS="cn.com.omnimind.bot.debug.DebugRunLogFunctionReplayReceiver"
RUN_ID=""
RUN_LOG_PATH=""
FUNCTION_ID=""
NAME=""
DESCRIPTION=""
OUTPUT_PATH=""
RUN_AFTER_CONVERT=0
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-60}"

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-convert-runlog.sh --run-id <id> [--device SERIAL] [--output-path PATH]
  scripts/oob-convert-runlog.sh --run-log-path runtime/runlogs/foo.run_log.json [--device SERIAL]

Converts an OOB InternalRunLog into a native reusable Function through Kotlin.
The script only sends adb broadcasts; RunLog conversion and registration happen
inside OOB's OobRunLogReplayService/OobOmniFlowToolkitService.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device|--target)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --run-id|--run_id)
      RUN_ID="$2"
      shift 2
      ;;
    --run-log-path|--runlog-path)
      RUN_LOG_PATH="$2"
      shift 2
      ;;
    --function-id)
      FUNCTION_ID="$2"
      shift 2
      ;;
    --name)
      NAME="$2"
      shift 2
      ;;
    --description)
      DESCRIPTION="$2"
      shift 2
      ;;
    --output-path)
      OUTPUT_PATH="$2"
      shift 2
      ;;
    --run)
      RUN_AFTER_CONVERT=1
      shift
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
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

if [[ -z "${RUN_ID// }" && -z "${RUN_LOG_PATH// }" ]]; then
  echo "Either --run-id or --run-log-path is required" >&2
  usage >&2
  exit 2
fi
if [[ -n "${RUN_LOG_PATH// }" && ! -f "$RUN_LOG_PATH" ]]; then
  echo "RunLog path not found: $RUN_LOG_PATH" >&2
  exit 2
fi

mkdir -p runtime/functions
if [[ -z "${OUTPUT_PATH// }" ]]; then
  stamp="$(date +%Y%m%d-%H%M%S)"
  OUTPUT_PATH="runtime/functions/${stamp}.function.json"
fi
mkdir -p "$(dirname "$OUTPUT_PATH")"

ADB=(adb -s "$DEVICE_SERIAL")
RECEIVER="$PACKAGE_NAME/$RECEIVER_CLASS"
RESULT_FILE="files/debug-runlog-function-replay-result.json"
RESULT_TMP="$(mktemp -t oob-convert-result.XXXXXX.json)"
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

BROADCAST_ARGS=(
  shell am broadcast
  -a "$ACTION"
  -n "$RECEIVER"
  --ez run "$([[ "$RUN_AFTER_CONVERT" -eq 1 ]] && echo true || echo false)"
)
if [[ -n "${RUN_ID// }" ]]; then
  BROADCAST_ARGS+=(--es runId "$RUN_ID")
fi
if [[ -n "${RUN_LOG_PATH// }" ]]; then
  RUN_LOG_B64="$(python3 - "$RUN_LOG_PATH" <<'PY'
import base64
import sys
from pathlib import Path
print(base64.b64encode(Path(sys.argv[1]).read_bytes()).decode("ascii"))
PY
)"
  BROADCAST_ARGS+=(--es runLogBase64 "$RUN_LOG_B64")
fi
if [[ -n "${FUNCTION_ID// }" ]]; then
  BROADCAST_ARGS+=(--es functionId "$FUNCTION_ID")
fi
if [[ -n "${NAME// }" ]]; then
  BROADCAST_ARGS+=(--es nameBase64 "$(base64_text "$NAME")")
fi
if [[ -n "${DESCRIPTION// }" ]]; then
  BROADCAST_ARGS+=(--es descriptionBase64 "$(base64_text "$DESCRIPTION")")
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
  echo "Timed out waiting for convert result on $DEVICE_SERIAL" >&2
  exit 1
fi

python3 - "$RESULT_TMP" "$OUTPUT_PATH" "$DEVICE_SERIAL" <<'PY'
import json
import sys
from pathlib import Path

result_path, output_path, target = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(result_path, encoding="utf-8"))
spec = data.get("function_spec") or (data.get("convert") or {}).get("function_spec")
if isinstance(spec, dict) and spec:
    Path(output_path).write_text(
        json.dumps(spec, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
else:
    output_path = ""
summary = {
    "success": data.get("success") is True,
    "target": target,
    "run_id": data.get("run_id"),
    "function_id": data.get("function_id") or (data.get("convert") or {}).get("function_id"),
    "function_path": output_path or None,
    "convert": data.get("convert"),
    "replay": data.get("replay"),
    "error_message": data.get("error_message") or (data.get("convert") or {}).get("error_message"),
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
sys.exit(0 if spec else 1)
PY
