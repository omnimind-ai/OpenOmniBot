#!/usr/bin/env bash
# Install the develop-flavor debug APK to a connected Android device.
# Usage:
#   bash scripts/install-dev.sh              # build + install to USB device
#   bash scripts/install-dev.sh --skip-build # install already-built APK
#   bash scripts/install-dev.sh --device <serial>
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

APK="$ROOT_DIR/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"
FLUTTER_TARGET="${OOB_FLUTTER_TARGET:-lib/main_standard.dart}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
SKIP_BUILD=0
DEVICE_SERIAL=""

usage() {
  cat <<'EOF'
Usage:
  bash scripts/install-dev.sh [options]

Options:
  --skip-build          Skip Gradle build; install the last built APK directly.
  --device <serial>     Target a specific device (passed to adb -s).
  --apk <path>          Install this APK instead of the default debug APK.
  --flutter-target <p>  Flutter entrypoint for Gradle -Ptarget. Default: lib/main_standard.dart.
  --package <name>      Package to launch after install. Default: cn.com.omnimind.bot.debug.
  --help                Show this help text.

Defaults:
  When --device is omitted, this script prefers the first connected USB device
  over emulators. This is the canonical path for "install to device".
EOF
}

select_default_device() {
  local lines serial selected=""
  lines="$(adb devices -l | awk 'NR>1 && $2=="device" {print $0}')"
  if [[ -z "$lines" ]]; then
    echo "No online Android device found. Connect a device or start an emulator." >&2
    return 1
  fi

  while IFS= read -r line; do
    serial="$(awk '{print $1}' <<<"$line")"
    if [[ "$serial" != emulator-* ]]; then
      selected="$serial"
      break
    fi
    [[ -z "$selected" ]] && selected="$serial"
  done <<<"$lines"

  echo "$selected"
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

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --device)
      [[ $# -lt 2 ]] && { echo "--device requires a serial" >&2; exit 1; }
      DEVICE_SERIAL="$2"; shift ;;
    --device=*) DEVICE_SERIAL="${1#--device=}" ;;
    --apk)
      [[ $# -lt 2 ]] && { echo "--apk requires a path" >&2; exit 1; }
      APK="$2"; shift ;;
    --apk=*) APK="${1#--apk=}" ;;
    --flutter-target)
      [[ $# -lt 2 ]] && { echo "--flutter-target requires a path" >&2; exit 1; }
      FLUTTER_TARGET="$2"; shift ;;
    --flutter-target=*) FLUTTER_TARGET="${1#--flutter-target=}" ;;
    --package)
      [[ $# -lt 2 ]] && { echo "--package requires a package name" >&2; exit 1; }
      PACKAGE_NAME="$2"; shift ;;
    --package=*) PACKAGE_NAME="${1#--package=}" ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
  shift
done

if [[ -z "$DEVICE_SERIAL" ]]; then
  DEVICE_SERIAL="$(select_default_device)"
fi
ADB=(adb -s "$DEVICE_SERIAL")

# ── 1. Check device ────────────────────────────────────────────────────────────
echo "Checking for connected device..."
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "Device is not reachable: $DEVICE_SERIAL" >&2
  echo "Connected devices:" >&2
  adb devices -l >&2 || true
  exit 1
fi
DEVICE_NAME="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo 'unknown')"
echo "  -> $DEVICE_SERIAL ($DEVICE_NAME)"
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
if is_device_locked; then
  cat >&2 <<EOF
Device $DEVICE_SERIAL is locked. Unlock the phone before installing; otherwise
the Android package installer can reject the APK with INSTALL_FAILED_ABORTED.
EOF
  exit 1
fi

# ── 2. Build ───────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo ""
  echo "Building develop debug APK..."
  chmod +x ./gradlew
  ./gradlew assembleDevelopStandardDebug \
    -Ptarget="$FLUTTER_TARGET" \
    --build-cache \
    -q
  echo "Build complete."
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  echo "Run without --skip-build first." >&2
  exit 1
fi

APK_SIZE_MB="$(du -m "$APK" | cut -f1)"
echo ""
echo "Installing APK (~${APK_SIZE_MB} MB) → $DEVICE_NAME"
set +e
INSTALL_OUTPUT="$("${ADB[@]}" install -r -d "$APK" 2>&1)"
INSTALL_STATUS=$?
set -e
printf '%s\n' "$INSTALL_OUTPUT"
if [[ "$INSTALL_STATUS" -ne 0 ]]; then
  if grep -Eq 'INSTALL_FAILED_ABORTED|User rejected permissions' <<<"$INSTALL_OUTPUT"; then
    cat >&2 <<'EOF'

Install was rejected on the device.
Unlock the phone and accept the install / USB debugging permission prompt, then rerun:
  bash scripts/install-dev.sh --skip-build
EOF
  fi
  exit "$INSTALL_STATUS"
fi
echo ""
echo "Done. Launching app..."
"${ADB[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 \
  >/dev/null 2>&1 || true
echo "  -> $PACKAGE_NAME"
