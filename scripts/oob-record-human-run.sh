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
ARTIFACT_DIR=""
RAW_EVENT_OUTPUT=""
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-20}"
ENABLE_RAW_TOUCH="${ENABLE_RAW_TOUCH:-0}"
REQUIRE_RAW_TOUCH="${REQUIRE_RAW_TOUCH:-0}"
EXPECTED_CLICKS=""
EXPECTED_SWIPES=""
DEBUG_OVERLAY_GESTURES="${DEBUG_OVERLAY_GESTURES:-0}"

usage() {
  cat <<'EOF'
Usage:
  scripts/oob-record-human-run.sh --description "增加联系人：妈妈 + 妈妈的手机号" [--device SERIAL] [--output-path PATH] [--artifact-dir DIR]

Starts OOB's native Kotlin human recorder. Press Ctrl-C to finish recording.
The script only sends adb broadcasts; recording and RunLog generation happen
inside the Android app through ManualVlmTraceRecorder + InternalRunLogStore.

The recorder records replayable actions only from concrete touch capture:
the product overlay path, debug overlay gestures, or optional raw getevent.
Accessibility events are retained as evidence only and must not become action
backends. OOB debug APK and OOB Accessibility service are required.

For deterministic device validation, pass --debug-overlay-gestures. That uses
the debug receiver to send synthetic overlay gestures through the same
HumanTrajectoryLearningSession.recordOverlayGesture backend used by the product
manual recording overlay, then verifies overlay_touch actions with before XML
and finishes automatically.
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
    --artifact-dir)
      ARTIFACT_DIR="$2"
      shift 2
      ;;
    --raw-event-output)
      RAW_EVENT_OUTPUT="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --enable-raw-touch)
      ENABLE_RAW_TOUCH=1
      shift
      ;;
    --require-raw-touch)
      ENABLE_RAW_TOUCH=1
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
    --debug-overlay-gestures)
      DEBUG_OVERLAY_GESTURES=1
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
GESTURE_FILE="files/debug-human-run-recording-gesture.json"
START_TMP="$(mktemp -t oob-human-start.XXXXXX.json)"
RESULT_TMP="$(mktemp -t oob-human-result.XXXXXX.json)"
GESTURE_TMP="$(mktemp -t oob-human-gesture.XXXXXX.json)"
FINISHED=0
cleanup() {
  rm -f "$START_TMP" "$RESULT_TMP" "$GESTURE_TMP"
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
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true
  "${ADB[@]}" shell am broadcast \
    -a "$ACTION" \
    -n "$RECEIVER" \
    --es op finish >/dev/null
  wait_for_file "$RESULT_FILE" "$RESULT_TMP"
}

send_recording_op() {
  local op="$1"
  shift
  "${ADB[@]}" shell am broadcast \
    -a "$ACTION" \
    -n "$RECEIVER" \
    --es op "$op" \
    "$@" >/dev/null
}

send_overlay_gesture_and_wait() {
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$GESTURE_FILE" >/dev/null 2>&1 || true
  send_recording_op gesture "$@"
  wait_for_file "$GESTURE_FILE" "$GESTURE_TMP"
  python3 - "$GESTURE_TMP" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
if (
    data.get("success") is not True
    or data.get("executed") is not True
    or data.get("recorded") is not True
):
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

run_debug_overlay_gestures() {
  local size width height tap_x tap_y swipe_x swipe_y1 swipe_y2
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
  "${ADB[@]}" shell am start -a android.settings.SETTINGS >/dev/null || true
  sleep 1
  send_recording_op resume
  sleep 1
  send_overlay_gesture_and_wait \
    --es actionName click \
    --es x "$tap_x" \
    --es y "$tap_y" \
    --es durationMs 100 \
    --es displayWidth "$width" \
    --es displayHeight "$height"
  sleep 1
  send_overlay_gesture_and_wait \
    --es actionName swipe \
    --es x1 "$swipe_x" \
    --es y1 "$swipe_y1" \
    --es x2 "$swipe_x" \
    --es y2 "$swipe_y2" \
    --es durationMs 500 \
    --es direction up \
    --es displayWidth "$width" \
    --es displayHeight "$height"
  sleep 1
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
  if [[ -n "${RAW_EVENT_OUTPUT// }" ]]; then
    mkdir -p "$(dirname "$RAW_EVENT_OUTPUT")"
  fi
  local summary_status
  set +e
  python3 - "$RESULT_TMP" "$OUTPUT_PATH" "$DEVICE_SERIAL" "$PACKAGE_NAME" "$REQUIRE_RAW_TOUCH" "$EXPECTED_CLICKS" "$EXPECTED_SWIPES" "$RAW_EVENT_OUTPUT" "$ARTIFACT_DIR" "$DEBUG_OVERLAY_GESTURES" <<'PY'
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

result_path, output_path, target, package_name = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
require_raw = sys.argv[5] == "1"
expected_clicks = int(sys.argv[6]) if sys.argv[6].strip() else None
expected_swipes = int(sys.argv[7]) if sys.argv[7].strip() else None
raw_event_output = sys.argv[8].strip()
artifact_dir_arg = sys.argv[9].strip()
debug_overlay = sys.argv[10] == "1"
data = json.load(open(result_path, encoding="utf-8"))
run_log = data.get("run_log") or {}
if not isinstance(run_log, dict) or not run_log:
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
Path(output_path).write_text(
    json.dumps(run_log, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
output_stem = Path(output_path)
artifact_dir = Path(artifact_dir_arg) if artifact_dir_arg else output_stem.with_suffix("").with_name(output_stem.with_suffix("").name + ".artifacts")
artifact_dir.mkdir(parents=True, exist_ok=True)
(artifact_dir / "xml").mkdir(parents=True, exist_ok=True)
(artifact_dir / "screenshots").mkdir(parents=True, exist_ok=True)
(artifact_dir / "events").mkdir(parents=True, exist_ok=True)
(artifact_dir / "audit").mkdir(parents=True, exist_ok=True)
(artifact_dir / "run_log.json").write_text(
    json.dumps(run_log, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
diagnostics = data.get("diagnostics") or run_log.get("diagnostics") or {}
raw_touch = diagnostics.get("raw_touch") or {}
raw_event_stream = raw_touch.get("event_stream") or {}
manual_recording = diagnostics.get("manual_recording") or {}
window_transitions = diagnostics.get("unattributed_window_transitions") or {}
allowed_backends = {
    "overlay_touch",
    "overlay_touch_text_input",
    "device_getevent",
    "device_getevent_text_input",
}
debug_overlay_backends = {"overlay_touch", "overlay_touch_text_input"}
actions = data.get("actions") or []
if not actions:
    actions = [
        card for card in run_log.get("cards", [])
        if isinstance(card, dict) and (card.get("action_type") or card.get("tool_name"))
    ]

def as_map(value):
    return value if isinstance(value, dict) else {}

def safe_name(value, fallback):
    text = str(value or fallback)
    text = re.sub(r"[^A-Za-z0-9._-]+", "_", text).strip("_")
    return (text or str(fallback))[:96]

def write_ndjson(path, rows):
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )

def collect_cards(run_log):
    cards = run_log.get("cards")
    if isinstance(cards, list):
        return [card for card in cards if isinstance(card, dict)]
    steps = run_log.get("steps")
    if isinstance(steps, list):
        return [step for step in steps if isinstance(step, dict)]
    return []

cards = collect_cards(run_log)

def card_action_name(card):
    return card.get("action_name") or card.get("action_type") or card.get("tool_name") or card.get("toolName") or ""

def card_step_index(card, fallback):
    raw = card.get("step_index")
    try:
        return int(float(raw))
    except Exception:
        return fallback

def observation(card, side):
    return as_map(card.get(side))

def observation_xml(card, side):
    obs = observation(card, side)
    return obs.get("observation_xml") or obs.get("xml") or ""

def screenshot_ref(card, side):
    obs = observation(card, side)
    ref = as_map(obs.get("screenshot"))
    path = obs.get("screenshot_path") or ref.get("screenshot_path") or ref.get("path")
    if not path:
        return {}
    out = dict(ref)
    out.setdefault("path", path)
    out.setdefault("screenshot_path", path)
    return out

def read_device_file(device_path):
    if not device_path:
        return None, "empty_path"
    try:
        result = subprocess.run(
            ["adb", "-s", target, "shell", "run-as", package_name, "cat", device_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=8,
            check=False,
        )
    except Exception as exc:
        return None, f"copy_exception:{exc}"
    if result.returncode != 0 or not result.stdout:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        return None, f"copy_failed:{result.returncode}:{message[:200]}"
    return result.stdout, None

xml_assets = []
a11_events = []
action_rows = []
screenshot_assets = []
warnings = []
seen_screenshot_paths = {}

for fallback_index, card in enumerate(cards, start=1):
    step_index = card_step_index(card, fallback_index)
    action_name = card_action_name(card)
    card_id = card.get("card_id") or card.get("tool_call_id") or f"step_{step_index:03d}"
    step_prefix = f"step_{step_index:03d}_{safe_name(action_name, 'action')}"
    event_context = as_map(card.get("event_context"))
    source_context = as_map(card.get("source_context"))
    before_xml = observation_xml(card, "before")
    after_xml = observation_xml(card, "after")
    recording_backend = as_map(card.get("params")).get("recording_backend")

    for side, xml in (("before", before_xml), ("after", after_xml)):
        if not xml:
            continue
        xml_path = artifact_dir / "xml" / f"{step_prefix}_{side}.xml"
        xml_path.write_text(xml, encoding="utf-8")
        xml_assets.append({
            "step_index": step_index,
            "card_id": card_id,
            "side": side,
            "path": str(xml_path),
            "relative_path": str(xml_path.relative_to(artifact_dir)),
            "bytes": xml_path.stat().st_size,
            "chars": len(xml),
        })

    for side in ("before", "after"):
        ref = screenshot_ref(card, side)
        device_path = ref.get("path") or ref.get("screenshot_path")
        if not device_path:
            continue
        local_path = seen_screenshot_paths.get(device_path)
        if local_path is None:
            suffix = Path(str(device_path)).suffix or ".jpg"
            local = artifact_dir / "screenshots" / f"{step_prefix}_{side}{suffix}"
            content, error = read_device_file(str(device_path))
            if content is not None:
                local.write_bytes(content)
                local_path = str(local)
            else:
                local_path = ""
                warnings.append({
                    "kind": "screenshot_copy_failed",
                    "step_index": step_index,
                    "side": side,
                    "device_path": device_path,
                    "reason": error,
                })
            seen_screenshot_paths[device_path] = local_path
        asset = {
            "step_index": step_index,
            "card_id": card_id,
            "side": side,
            "device_path": device_path,
            "local_path": local_path or None,
            "relative_path": (
                str(Path(local_path).relative_to(artifact_dir))
                if local_path else None
            ),
            "schema_ref": ref,
        }
        if local_path and Path(local_path).exists():
            asset["bytes"] = Path(local_path).stat().st_size
        screenshot_assets.append({k: v for k, v in asset.items() if v is not None})

    if event_context:
        a11_events.append({
            "step_index": step_index,
            "card_id": card_id,
            "action_name": action_name,
            "event_context": event_context,
        })

    action_rows.append({
        "step_index": step_index,
        "card_id": card_id,
        "title": card.get("title") or card.get("summary"),
        "action_name": action_name,
        "status": card.get("status"),
        "success": card.get("success"),
        "duration_ms": card.get("duration_ms"),
        "source": card.get("source"),
        "compile_kind": card.get("compile_kind"),
        "recording_backend": recording_backend,
        "has_before_xml": bool(before_xml),
        "has_after_xml": bool(after_xml),
        "has_before_screenshot": bool(screenshot_ref(card, "before")),
        "has_after_screenshot": bool(screenshot_ref(card, "after")),
        "has_source_context": bool(source_context),
        "event_type": event_context.get("event_type"),
        "event_has_source": event_context.get("event_has_source"),
        "target_resolution": event_context.get("target_resolution") or as_map(card.get("params")).get("target_resolution"),
    })

write_ndjson(artifact_dir / "events" / "a11.ndjson", a11_events)
write_ndjson(artifact_dir / "actions.ndjson", action_rows)
raw_events = raw_event_stream.get("events") or []
write_ndjson(artifact_dir / "events" / "raw_getevent.ndjson", raw_events)
(artifact_dir / "screenshots" / "index.json").write_text(
    json.dumps(screenshot_assets, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)

action_names = [
    (action.get("action_name") or action.get("action_type") or action.get("tool_name"))
    for action in actions
    if isinstance(action, dict)
]
click_count = sum(1 for name in action_names if name == "click")
swipe_count = sum(1 for name in action_names if name == "swipe")
missing_before_xml = [row["step_index"] for row in action_rows if not row["has_before_xml"]]
missing_after_xml = [row["step_index"] for row in action_rows if not row["has_after_xml"]]
missing_before_screenshot = [row["step_index"] for row in action_rows if not row["has_before_screenshot"]]
missing_after_screenshot = [row["step_index"] for row in action_rows if not row["has_after_screenshot"]]
missing_source_context = [row["step_index"] for row in action_rows if not row["has_source_context"]]
backend_counts = {}
for row in action_rows:
    backend = row.get("recording_backend")
    key = str(backend) if backend is not None else "<missing>"
    backend_counts[key] = backend_counts.get(key, 0) + 1
unexpected_backend_steps = [
    row["step_index"] for row in action_rows
    if row.get("recording_backend") not in allowed_backends
]
a11_backend_steps = [
    row["step_index"] for row in action_rows
    if row.get("recording_backend") == "accessibility_event"
]
non_overlay_backend_steps = [
    row["step_index"] for row in action_rows
    if row.get("recording_backend") not in debug_overlay_backends
]
source_null_steps = [
    row["step_index"] for row in action_rows
    if row.get("event_type") and row.get("event_has_source") is False
]
audit = {
    "schema_version": "oob.manual_recording_audit.v2",
    "success": data.get("success") is True,
    "run_id": data.get("run_id") or run_log.get("run_id"),
    "action_count": len(action_rows),
    "click_count": click_count,
    "swipe_count": swipe_count,
    "xml_asset_count": len(xml_assets),
    "screenshot_ref_count": len(screenshot_assets),
    "screenshot_local_copy_count": sum(1 for item in screenshot_assets if item.get("local_path")),
    "a11_event_count": len(a11_events),
    "raw_getevent_line_count": raw_event_stream.get("line_count") or len(raw_events),
    "raw_getevent_retained_line_count": len(raw_events),
    "recording_backend_counts": backend_counts,
    "a11_replay_actions_enabled": manual_recording.get("a11_replay_actions_enabled"),
    "coverage": {
        "before_xml_steps": len(action_rows) - len(missing_before_xml),
        "after_xml_steps": len(action_rows) - len(missing_after_xml),
        "before_screenshot_steps": len(action_rows) - len(missing_before_screenshot),
        "after_screenshot_steps": len(action_rows) - len(missing_after_screenshot),
        "source_context_steps": len(action_rows) - len(missing_source_context),
    },
    "missing": {
        "before_xml_steps": missing_before_xml,
        "after_xml_steps": missing_after_xml,
        "before_screenshot_steps": missing_before_screenshot,
        "after_screenshot_steps": missing_after_screenshot,
        "source_context_steps": missing_source_context,
        "unexpected_backend_steps": unexpected_backend_steps,
        "a11_backend_steps": a11_backend_steps,
        "non_overlay_backend_steps": non_overlay_backend_steps if debug_overlay else [],
    },
    "diagnostics": {
        "recording_completeness": manual_recording.get("completeness"),
        "recording_action_source": manual_recording.get("action_source"),
        "guarantees_no_missing_clicks": manual_recording.get("guarantees_no_missing_clicks"),
        "a11_replay_actions_enabled": manual_recording.get("a11_replay_actions_enabled"),
        "source_null_event_steps": source_null_steps,
        "warnings": warnings,
    },
}
(artifact_dir / "audit" / "recording_audit.json").write_text(
    json.dumps(audit, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)

manifest = {
    "schema_version": "oob.manual_recording_artifact.v1",
    "kind": "oob_manual_recording_bundle",
    "run_id": data.get("run_id") or run_log.get("run_id"),
    "goal": run_log.get("goal") or data.get("description"),
    "description": data.get("description"),
    "device_serial": target,
    "package_name": package_name,
    "created_at_ms": int(time.time() * 1000),
    "artifact_root": str(artifact_dir),
    "paths": {
        "run_log": str(artifact_dir / "run_log.json"),
        "actions_ndjson": str(artifact_dir / "actions.ndjson"),
        "a11_events_ndjson": str(artifact_dir / "events" / "a11.ndjson"),
        "raw_getevent_ndjson": str(artifact_dir / "events" / "raw_getevent.ndjson"),
        "screenshot_index": str(artifact_dir / "screenshots" / "index.json"),
        "audit": str(artifact_dir / "audit" / "recording_audit.json"),
        "legacy_output_path": output_path,
    },
    "artifacts": {
        "action_count": len(action_rows),
        "xml_asset_count": len(xml_assets),
        "screenshot_ref_count": len(screenshot_assets),
        "screenshot_local_copy_count": audit["screenshot_local_copy_count"],
        "a11_event_count": len(a11_events),
        "raw_getevent_retained_line_count": len(raw_events),
    },
    "xml_assets": xml_assets,
    "screenshot_assets": screenshot_assets,
    "audit": audit,
    "privacy": {
        "run_log_embeds_xml": True,
        "run_log_embeds_screenshot_base64": False,
        "screenshots_stored_as_files": True,
        "raw_getevent_stored_as_ndjson": True,
        "storage": "local_artifact_dir",
    },
    "warnings": warnings,
}
(artifact_dir / "manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)

summary = {
    "success": data.get("success") is True,
    "target": target,
    "run_id": data.get("run_id") or run_log.get("run_id"),
    "run_log_path": output_path,
    "artifact_dir": str(artifact_dir),
    "manifest_path": str(artifact_dir / "manifest.json"),
    "audit_path": str(artifact_dir / "audit" / "recording_audit.json"),
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
    "raw_getevent_line_count": raw_event_stream.get("line_count"),
    "raw_getevent_retained_line_count": raw_event_stream.get("retained_line_count"),
    "raw_getevent_dropped_line_count": raw_event_stream.get("dropped_line_count"),
    "raw_getevent_truncated": raw_event_stream.get("truncated"),
    "screenshot_ref_count": audit["screenshot_ref_count"],
    "screenshot_local_copy_count": audit["screenshot_local_copy_count"],
    "missing_before_xml_steps": missing_before_xml,
    "missing_after_xml_steps": missing_after_xml,
    "unexpected_backend_steps": unexpected_backend_steps,
    "a11_backend_steps": a11_backend_steps,
    "non_overlay_backend_steps": non_overlay_backend_steps if debug_overlay else [],
    "recording_backend_counts": backend_counts,
    "missing_before_screenshot_steps": missing_before_screenshot,
    "missing_after_screenshot_steps": missing_after_screenshot,
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
if raw_event_output:
    events = raw_event_stream.get("events") or []
    shutil.copyfile(artifact_dir / "events" / "raw_getevent.ndjson", raw_event_output)
    summary["raw_getevent_event_stream_path"] = raw_event_output
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
if manual_recording.get("a11_replay_actions_enabled") is not False:
    print("A11 replay actions must stay disabled", file=sys.stderr)
    sys.exit(1)
if a11_backend_steps:
    print(f"A11-only action backend recorded at steps {a11_backend_steps}", file=sys.stderr)
    sys.exit(1)
if unexpected_backend_steps:
    print(f"unexpected action backend at steps {unexpected_backend_steps}", file=sys.stderr)
    sys.exit(1)
if missing_before_xml:
    print(f"missing before XML at steps {missing_before_xml}", file=sys.stderr)
    sys.exit(1)
if debug_overlay and non_overlay_backend_steps:
    print(f"debug overlay validation expected overlay_touch backends, got other backends at steps {non_overlay_backend_steps}", file=sys.stderr)
    sys.exit(1)
if debug_overlay and manual_recording.get("action_source") not in ("overlay_touch", "mixed_real_touch"):
    print(f"debug overlay validation expected overlay_touch action source, got {manual_recording.get('action_source')}", file=sys.stderr)
    sys.exit(1)
if debug_overlay and manual_recording.get("guarantees_no_missing_clicks") is not True:
    print("debug overlay validation did not report no-missing-click guarantee", file=sys.stderr)
    sys.exit(1)
if expected_clicks is not None and click_count < expected_clicks:
    print(f"expected at least {expected_clicks} clicks, got {click_count}", file=sys.stderr)
    sys.exit(1)
if expected_swipes is not None and swipe_count < expected_swipes:
    print(f"expected at least {expected_swipes} swipes, got {swipe_count}", file=sys.stderr)
    sys.exit(1)
sys.exit(0 if summary["success"] else 1)
PY
  summary_status=$?
  set -e
  exit "$summary_status"
}

"${ADB[@]}" get-state >/dev/null
require_unlocked_device
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$START_FILE" "$RESULT_FILE" "$STATUS_FILE" "$GESTURE_FILE" >/dev/null 2>&1 || true

"${ADB[@]}" shell am broadcast \
  -a "$ACTION" \
  -n "$RECEIVER" \
  --es op start \
  --es description "$DESCRIPTION" \
  --ez enableRawTouch "$([[ "$ENABLE_RAW_TOUCH" -eq 1 ]] && echo true || echo false)" >/dev/null

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
if [[ "$DEBUG_OVERLAY_GESTURES" -eq 1 ]]; then
  run_debug_overlay_gestures
  finish_and_exit
fi
trap finish_and_exit INT TERM
while true; do
  sleep 1
done
