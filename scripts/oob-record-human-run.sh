#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-}"
PACKAGE_NAME="${PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACTION="cn.com.omnimind.bot.debug.HUMAN_RUN_RECORDING"
RECEIVER_CLASS="cn.com.omnimind.bot.debug.DebugHumanRunRecordingReceiver"
DESCRIPTION=""
OUTPUT_PATH=""
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-20}"
REQUIRE_RAW_TOUCH="${REQUIRE_RAW_TOUCH:-0}"
EXPECTED_CLICKS=""
EXPECTED_SWIPES=""

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-record-human-run.sh --description "增加联系人：妈妈 + 妈妈的手机号" [--device SERIAL] [--output-path PATH]

Starts OOB's native Kotlin human recorder. Press Ctrl-C to finish recording.
The script only sends adb broadcasts; recording and RunLog generation happen
inside the Android app through ManualVlmTraceRecorder + InternalRunLogStore.

The recorder uses Accessibility events as the default action source. Raw
getevent through app/su/Shizuku is optional enrichment unless
--require-raw-touch is passed. It requires the OOB debug APK and OOB
Accessibility service to be enabled.
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
    --description|--task)
      DESCRIPTION="$2"
      shift 2
      ;;
    --output-path)
      OUTPUT_PATH="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --require-raw-touch)
      REQUIRE_RAW_TOUCH=1
      shift
      ;;
    --expected-clicks)
      EXPECTED_CLICKS="$2"
      shift 2
      ;;
    --expected-swipes)
      EXPECTED_SWIPES="$2"
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

if [[ -z "${DESCRIPTION// }" ]]; then
  echo "--description is required" >&2
  usage >&2
  exit 2
fi

mkdir -p runtime/recordings
if [[ -z "${OUTPUT_PATH// }" ]]; then
  stamp="$(date +%Y%m%d-%H%M%S)"
  OUTPUT_PATH="runtime/recordings/${stamp}.human.run_log.json"
fi
mkdir -p "$(dirname "$OUTPUT_PATH")"

if [[ -z "${DEVICE_SERIAL// }" ]]; then
  DEVICE_SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')"
fi
if [[ -z "${DEVICE_SERIAL// }" ]]; then
  echo "No USB device selected. Pass --device SERIAL or set ANDROID_SERIAL." >&2
  exit 2
fi

ADB=(adb -s "$DEVICE_SERIAL")
RECEIVER="$PACKAGE_NAME/$RECEIVER_CLASS"
START_FILE="files/debug-human-run-recording-start.json"
RESULT_FILE="files/debug-human-run-recording-result.json"
STATUS_FILE="files/debug-human-run-recording-status.json"
START_TMP="$(mktemp -t oob-human-start.XXXXXX.json)"
RESULT_TMP="$(mktemp -t oob-human-result.XXXXXX.json)"
FINISHED=0
cleanup() {
  rm -f "$START_TMP" "$RESULT_TMP"
}
trap cleanup EXIT

read_app_file() {
  local path="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$path" 2>/dev/null || true
}

wait_for_file() {
  local path="$1"
  local output="$2"
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    read_app_file "$path" >"$output"
    if [[ -s "$output" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $path on $DEVICE_SERIAL" >&2
  return 1
}

is_device_locked() {
  local trust window
  trust="$("${ADB[@]}" shell dumpsys trust 2>/dev/null | tr -d '\r' || true)"
  if grep -Eq '\(current\).*deviceLocked=1' <<<"$trust"; then
    return 0
  fi
  window="$("${ADB[@]}" shell dumpsys window 2>/dev/null | tr -d '\r' || true)"
  grep -Eq 'isKeyguardShowing=true|mDreamingLockscreen=true|mIsShowing=true' <<<"$window"
}

require_unlocked_device() {
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  if is_device_locked; then
    cat >&2 <<EOF
Device $DEVICE_SERIAL is locked. Unlock the phone before recording; otherwise
install prompts, Accessibility events, and raw touch validation are not reliable.
EOF
    exit 1
  fi
}

finish_recording() {
  "${ADB[@]}" shell am broadcast \
    -a "$ACTION" \
    -n "$RECEIVER" \
    --es op finish >/dev/null
  wait_for_file "$RESULT_FILE" "$RESULT_TMP"
}

finish_and_exit() {
  if [[ "$FINISHED" -eq 1 ]]; then
    exit 130
  fi
  FINISHED=1
  trap - INT TERM
  echo "Stopping OOB human recording..." >&2
  if ! finish_recording; then
    exit 1
  fi
  python3 - "$RESULT_TMP" "$OUTPUT_PATH" "$DEVICE_SERIAL" "$REQUIRE_RAW_TOUCH" "$EXPECTED_CLICKS" "$EXPECTED_SWIPES" <<'PY'
import json
import sys
from pathlib import Path

result_path, output_path, target = sys.argv[1], sys.argv[2], sys.argv[3]
require_raw = sys.argv[4] == "1"
expected_clicks = int(sys.argv[5]) if sys.argv[5].strip() else None
expected_swipes = int(sys.argv[6]) if sys.argv[6].strip() else None
data = json.load(open(result_path, encoding="utf-8"))
run_log = data.get("run_log") or {}
if not isinstance(run_log, dict) or not run_log:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
Path(output_path).write_text(
    json.dumps(run_log, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
diagnostics = data.get("diagnostics") or run_log.get("diagnostics") or {}
raw_touch = diagnostics.get("raw_touch") or {}
manual_recording = diagnostics.get("manual_recording") or {}
window_transitions = diagnostics.get("unattributed_window_transitions") or {}
actions = data.get("actions") or []
if not actions:
    actions = [
        card for card in run_log.get("cards", [])
        if isinstance(card, dict) and (card.get("action_type") or card.get("tool_name"))
    ]
action_names = [
    (action.get("action_name") or action.get("action_type") or action.get("tool_name"))
    for action in actions
    if isinstance(action, dict)
]
click_count = sum(1 for name in action_names if name == "click")
swipe_count = sum(1 for name in action_names if name == "swipe")
summary = {
    "success": data.get("success") is True,
    "target": target,
    "run_id": data.get("run_id") or run_log.get("run_id"),
    "run_log_path": output_path,
    "action_count": data.get("action_count") or run_log.get("step_count"),
    "action_names": action_names,
    "click_count": click_count,
    "swipe_count": swipe_count,
    "duration_ms": run_log.get("duration_ms"),
    "raw_touch_available": raw_touch.get("available"),
    "raw_touch_access_method": raw_touch.get("access_method"),
    "raw_touch_device": raw_touch.get("device_path"),
    "raw_touch_started_gesture_count": raw_touch.get("started_gesture_count"),
    "raw_touch_finished_gesture_count": raw_touch.get("finished_gesture_count"),
    "raw_touch_recorded_gesture_count": raw_touch.get("recorded_gesture_count"),
    "raw_touch_ignored_control_gesture_count": raw_touch.get("ignored_control_gesture_count"),
    "recording_completeness": manual_recording.get("completeness"),
    "recording_action_source": manual_recording.get("action_source"),
    "guarantees_no_missing_clicks": manual_recording.get("guarantees_no_missing_clicks"),
    "unattributed_window_transition_count": (
        manual_recording.get("unattributed_window_transition_count")
        if isinstance(manual_recording, dict)
        else None
    ) or window_transitions.get("count"),
    "recording_warning": manual_recording.get("warning_message"),
    "manual_recording": manual_recording or None,
    "diagnostics": diagnostics or None,
    "token_usage_total": data.get("token_usage_total", 0),
    "error_message": data.get("error_message") or None,
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
if require_raw and raw_touch.get("available") is not True:
    print("raw touch is required but unavailable", file=sys.stderr)
    sys.exit(1)
if require_raw and raw_touch.get("active_at_stop") is not True:
    print("raw touch stream stopped before recording finished", file=sys.stderr)
    sys.exit(1)
if require_raw and manual_recording.get("guarantees_no_missing_clicks") is not True:
    print("recording completeness is not complete_raw_touch", file=sys.stderr)
    sys.exit(1)
if require_raw and (raw_touch.get("recorded_gesture_count") or 0) < click_count + swipe_count:
    print("raw touch diagnostics do not cover recorded click/swipe actions", file=sys.stderr)
    sys.exit(1)
if expected_clicks is not None and click_count < expected_clicks:
    print(f"expected at least {expected_clicks} clicks, got {click_count}", file=sys.stderr)
    sys.exit(1)
if expected_swipes is not None and swipe_count < expected_swipes:
    print(f"expected at least {expected_swipes} swipes, got {swipe_count}", file=sys.stderr)
    sys.exit(1)
sys.exit(0 if summary["success"] else 1)
PY
}

"${ADB[@]}" get-state >/dev/null
require_unlocked_device
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$START_FILE" "$RESULT_FILE" "$STATUS_FILE" >/dev/null 2>&1 || true

"${ADB[@]}" shell am broadcast \
  -a "$ACTION" \
  -n "$RECEIVER" \
  --es op start \
  --es description "$DESCRIPTION" >/dev/null

wait_for_file "$START_FILE" "$START_TMP"
python3 - "$START_TMP" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("success") is not True:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY

echo "Recording human run on $DEVICE_SERIAL; press Ctrl-C to stop." >&2
trap finish_and_exit INT TERM
while true; do
  sleep 1
done
