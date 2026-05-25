#!/usr/bin/env bash
set -euo pipefail

DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5556}"
PACKAGE_NAME="${PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACTION="cn.com.omnimind.bot.debug.VALIDATE_OOB_FUNCTION_SEGMENT"
RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugOobFunctionSegmentReceiver"
RESULT_FILE="files/debug-oob-function-segment-result.json"
GOAL="${GOAL:-Validate reusable command segment}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-45}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --package)
      PACKAGE_NAME="$2"
      RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugOobFunctionSegmentReceiver"
      shift 2
      ;;
    --goal)
      GOAL="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    -h|--help)
      cat <<USAGE
Usage: scripts/oob-device-segment-validation.sh [--device SERIAL] [--package PACKAGE] [--goal TEXT] [--timeout SECONDS]

Runs the debug-only reusable command segment validation on a connected device.
The validation is an actual execution test: it registers a child command,
registers a parent command, recalls from the current page, executes the parent,
and verifies that Android Settings becomes foreground.
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

adb -s "$DEVICE_SERIAL" shell run-as "$PACKAGE_NAME" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true

adb -s "$DEVICE_SERIAL" shell am broadcast \
  -a "$ACTION" \
  -n "$RECEIVER" \
  --es goal "$GOAL" >/dev/null

deadline=$((SECONDS + TIMEOUT_SECONDS))
result_tmp="$(mktemp -t oob-segment-validation.XXXXXX.json)"
trap 'rm -f "$result_tmp"' EXIT
while (( SECONDS < deadline )); do
  if adb -s "$DEVICE_SERIAL" shell run-as "$PACKAGE_NAME" cat "$RESULT_FILE" >"$result_tmp" 2>/dev/null &&
    [[ -s "$result_tmp" ]]; then
    break
  fi
  sleep 1
done

if [[ ! -s "$result_tmp" ]]; then
  echo "Timed out waiting for $RESULT_FILE on $DEVICE_SERIAL" >&2
  exit 1
fi

python3 - "$result_tmp" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

required_top_phases = {
    "wait_accessibility_ms",
    "observe_before_ms",
    "register_child_ms",
    "register_parent_ms",
    "load_child_ms",
    "recall_ms",
    "parent_run_ms",
    "post_run_settle_ms",
    "observe_after_ms",
}
required_recall_phases = {
    "parse_request_ms",
    "read_current_package_ms",
    "read_current_page_ms",
    "page_match_ms",
    "rank_functions_ms",
    "segment_match_ms",
}

def path(root, *keys):
    value = root
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value

def fail(message):
    print(message, file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)

if data.get("success") is not True:
    fail("segment validation did not report success=true")

timing = data.get("timing")
if not isinstance(timing, dict):
    fail("missing top-level timing")

duration_ms = timing.get("duration_ms")
if not isinstance(duration_ms, int) or duration_ms < 0:
    fail("missing non-negative timing.duration_ms")

phase_ms = timing.get("phase_ms")
if not isinstance(phase_ms, dict):
    fail("missing timing.phase_ms")

missing_top = sorted(required_top_phases - set(phase_ms))
if missing_top:
    fail(f"missing top-level phase timings: {missing_top}")

recall_phase_ms = path(data, "recall", "timing", "phase_ms")
if not isinstance(recall_phase_ms, dict):
    fail("missing recall.timing.phase_ms")

missing_recall = sorted(required_recall_phases - set(recall_phase_ms))
if missing_recall:
    fail(f"missing recall phase timings: {missing_recall}")

runner_duration_ms = path(data, "parent_run", "timing", "runner_duration_ms")
if not isinstance(runner_duration_ms, int) or runner_duration_ms < 0:
    fail("missing parent_run.timing.runner_duration_ms")

summary = {
    "success": data.get("success"),
    "before_package": data.get("before_package"),
    "after_package": data.get("after_package"),
    "duration_ms": duration_ms,
    "phase_ms": phase_ms,
    "recall_decision": path(data, "recall", "decision"),
    "recall_reason": path(data, "recall", "reason"),
    "parent_runner": path(data, "parent_run", "runner"),
    "parent_runner_duration_ms": runner_duration_ms,
    "nested_tools": path(data, "parent_nested_summary", "nested_tools"),
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
PY
