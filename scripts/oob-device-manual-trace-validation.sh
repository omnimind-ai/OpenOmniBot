#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

DEVICE_SERIAL="${ANDROID_SERIAL:-}"
PACKAGE_NAME="${PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-30}"
OUTPUT_PATH=""
ARTIFACT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --duration-ms)
      # Kept for compatibility with the old A11/raw validation script. The new
      # deterministic validation finishes as soon as debug overlay gestures land.
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="$2"
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
    -h|--help)
      cat <<USAGE
Usage: scripts/oob-device-manual-trace-validation.sh [--device SERIAL] [--package PACKAGE]

Starts the debug human RunLog recorder, sends deterministic overlay-touch click
and swipe gestures through HumanTrajectoryLearningSession.recordOverlayGesture,
and verifies:
  - every recorded action has before XML;
  - action backends are concrete touch backends, not accessibility_event;
  - debug validation actions are overlay_touch;
  - A11 replay actions remain disabled.

This script intentionally selects a USB device by default and never defaults to
emulator-5554/5556. Pass --device explicitly when validating an emulator.
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "${DEVICE_SERIAL// }" ]]; then
  DEVICE_SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')"
fi
if [[ -z "${DEVICE_SERIAL// }" ]]; then
  echo "No USB device selected. Pass --device SERIAL or set ANDROID_SERIAL." >&2
  exit 2
fi

args=(
  --device "$DEVICE_SERIAL"
  --package "$PACKAGE_NAME"
  --description "debug_manual_overlay_touch_validation_$DEVICE_SERIAL"
  --debug-overlay-gestures
  --expected-clicks 1
  --expected-swipes 1
)

if [[ -n "${OUTPUT_PATH// }" ]]; then
  args+=(--output-path "$OUTPUT_PATH")
fi
if [[ -n "${ARTIFACT_DIR// }" ]]; then
  args+=(--artifact-dir "$ARTIFACT_DIR")
fi

TIMEOUT_SECONDS="$TIMEOUT_SECONDS" PACKAGE_NAME="$PACKAGE_NAME" \
  "$ROOT_DIR/scripts/oob-record-human-run.sh" "${args[@]}"
