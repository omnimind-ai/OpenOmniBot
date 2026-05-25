#!/usr/bin/env bash
set -euo pipefail

DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5556}"
PACKAGE_NAME="${PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACTION="cn.com.omnimind.bot.debug.RUN_MANUAL_TRACE"
RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugManualTraceReceiver"
STARTED_FILE="files/debug-manual-trace-started.json"
RESULT_FILE="files/debug-manual-trace-result.json"
DURATION_MS="${DURATION_MS:-8000}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-20}"
TMP_FILES=()
cleanup() {
  for path in "${TMP_FILES[@]:-}"; do
    [[ -n "$path" ]] && rm -f "$path"
  done
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --package)
      PACKAGE_NAME="$2"
      RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugManualTraceReceiver"
      shift 2
      ;;
    --duration-ms)
      DURATION_MS="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    -h|--help)
      cat <<USAGE
Usage: scripts/oob-device-manual-trace-validation.sh [--device SERIAL] [--package PACKAGE]

Starts the debug ManualVlmTraceRecorder, performs adb-driven tap and swipe
gestures on Android Settings, and verifies semantic click/swipe actions plus
duration/phase timing in the result payload.
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

ADB=(adb -s "$DEVICE_SERIAL")

read_app_file() {
  local path="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat "$path" 2>/dev/null || true
}

wait_for_file() {
  local path="$1"
  local label="$2"
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    local content
    content="$(read_app_file "$path")"
    if [[ -n "$content" ]]; then
      printf '%s' "$content"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $label: $path" >&2
  return 1
}

find_click_target() {
  local xml_file="$1"
  local width="$2"
  local height="$3"
  python3 - "$xml_file" "$width" "$height" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_file, width, height = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
bounds_re = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
preferred = (
    "network", "internet", "display", "connected", "apps", "battery",
    "notifications", "sound", "storage", "security", "privacy", "system",
)
blocked = ("search", "avatar", "profile", "account")

def parse_bounds(raw):
    match = bounds_re.fullmatch(raw or "")
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        return None
    return left, top, right, bottom

def score(node):
    if node.attrib.get("clickable") != "true":
        return None
    if node.attrib.get("enabled") == "false":
        return None
    bounds = parse_bounds(node.attrib.get("bounds"))
    if not bounds:
        return None
    left, top, right, bottom = bounds
    if bottom < height * 0.12 or top > height * 0.92:
        return None
    if right - left < width * 0.2 or bottom - top < 32:
        return None
    text = " ".join(
        node.attrib.get(name, "")
        for name in ("text", "content-desc", "resource-id", "class")
    ).strip()
    if not text:
        return None
    lowered = text.lower()
    if any(word in lowered for word in blocked):
        return None
    preference = next((idx for idx, word in enumerate(preferred) if word in lowered), len(preferred))
    return preference, top, left, bounds

root = ET.parse(xml_file).getroot()
candidates = []
for node in root.iter("node"):
    item = score(node)
    if item:
        candidates.append(item)

if not candidates:
    print(f"{width // 2} {height // 2}")
else:
    _, _, _, (left, top, right, bottom) = sorted(candidates)[0]
    print(f"{(left + right) // 2} {(top + bottom) // 2}")
PY
}

dump_window_xml() {
  local xml_file="$1"
  local remote="/sdcard/oob_manual_trace_window.xml"
  "${ADB[@]}" shell uiautomator dump "$remote" >/dev/null
  "${ADB[@]}" exec-out cat "$remote" >"$xml_file"
}

size="$("${ADB[@]}" shell wm size | tr -d '\r' | awk -F': ' '/Physical size/ {print $2; exit}')"
width="${size%x*}"
height="${size#*x}"
if [[ -z "$width" || -z "$height" || "$width" == "$height" ]]; then
  width=720
  height=1280
fi

tap_x=$((width / 2))
tap_y=$((height / 2))
swipe_x=$((width / 2))
swipe_y1=$((height * 4 / 5))
swipe_y2=$((height / 3))

"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"${ADB[@]}" shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true
"${ADB[@]}" shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow || true
"${ADB[@]}" shell settings --user 0 delete secure enabled_accessibility_services >/dev/null 2>&1 || true
"${ADB[@]}" shell settings --user 0 put secure accessibility_enabled 0 >/dev/null 2>&1 || true
sleep 0.5
"${ADB[@]}" shell settings --user 0 put secure enabled_accessibility_services "$PACKAGE_NAME/com.google.android.accessibility.selecttospeak.SelectToSpeakService" || true
"${ADB[@]}" shell settings --user 0 put secure accessibility_enabled 1 || true
"${ADB[@]}" shell am force-stop com.android.settings >/dev/null 2>&1 || true
"${ADB[@]}" shell am force-stop com.google.android.settings.intelligence >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -a android.settings.SETTINGS >/dev/null
sleep 1.5

window_xml="$(mktemp -t oob-manual-window.XXXXXX.xml)"
TMP_FILES+=("$window_xml")
dump_window_xml "$window_xml"
read -r tap_x tap_y < <(find_click_target "$window_xml" "$width" "$height")

"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$STARTED_FILE" "$RESULT_FILE" >/dev/null 2>&1 || true

"${ADB[@]}" shell am broadcast \
  -a "$ACTION" \
  -n "$RECEIVER" \
  --el durationMs "$DURATION_MS" \
  --es sessionLabel "debug_manual_trace_$DEVICE_SERIAL" >/dev/null

wait_for_file "$STARTED_FILE" "manual trace start" >/dev/null

"${ADB[@]}" shell input tap "$tap_x" "$tap_y"
sleep 1
"${ADB[@]}" shell input swipe "$swipe_x" "$swipe_y1" "$swipe_x" "$swipe_y2" 500 || true
sleep 1
"${ADB[@]}" shell input swipe "$swipe_x" "$swipe_y1" "$swipe_x" "$swipe_y2" 500 || true

result_tmp="$(mktemp -t oob-manual-trace.XXXXXX.json)"
TMP_FILES+=("$result_tmp")
wait_for_file "$RESULT_FILE" "manual trace result" >"$result_tmp"

python3 - "$result_tmp" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

def fail(message):
    print(message, file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)

if data.get("success") is not True:
    fail("manual trace did not report success=true")

actions = data.get("actions") or []
names = data.get("action_names") or [action.get("action_name") for action in actions]
if "click" not in names:
    fail("manual trace missing click action")
if "swipe" not in names:
    fail("manual trace missing swipe action")
if data.get("token_usage_total") != 0:
    fail("manual trace should have token_usage_total=0")

timing = data.get("timing") or {}
phase_ms = timing.get("phase_ms") or {}
for key in ("wait_accessibility_ms", "start_recorder_ms", "recording_window_ms", "stop_recorder_ms"):
    if key not in phase_ms:
        fail(f"missing phase timing: {key}")

for action in actions:
    if not isinstance(action.get("duration_ms"), int) or action["duration_ms"] < 0:
        fail("action missing non-negative duration_ms")

summary = {
    "success": data.get("success"),
    "action_count": data.get("action_count"),
    "action_names": names,
    "token_usage_total": data.get("token_usage_total"),
    "duration_ms": timing.get("duration_ms"),
    "phase_ms": phase_ms,
    "actions": [
        {
            "action_name": action.get("action_name"),
            "duration_ms": action.get("duration_ms"),
            "package_name": action.get("package_name"),
            "before_xml_present": action.get("before_xml_present"),
            "after_xml_present": action.get("after_xml_present"),
        }
        for action in actions
    ],
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
PY
