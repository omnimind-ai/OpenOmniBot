#!/usr/bin/env bash
# Install the develop-flavor debug APK to a connected Android device.
# Usage:
#   bash scripts/install-dev.sh              # build + install
#   bash scripts/install-dev.sh --skip-build # install already-built APK
#   bash scripts/install-dev.sh --device <serial>
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

APK="$ROOT_DIR/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"
SKIP_BUILD=0
DEVICE_SERIAL=""

usage() {
  cat <<'EOF'
Usage:
  bash scripts/install-dev.sh [options]

Options:
  --skip-build          Skip Gradle build; install the last built APK directly.
  --device <serial>     Target a specific device (passed to adb -s).
  --help                Show this help text.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --device)
      [[ $# -lt 2 ]] && { echo "--device requires a serial" >&2; exit 1; }
      DEVICE_SERIAL="$2"; shift ;;
    --device=*) DEVICE_SERIAL="${1#--device=}" ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
  shift
done

ADB="adb"
if [[ -n "$DEVICE_SERIAL" ]]; then
  ADB="adb -s $DEVICE_SERIAL"
fi

# ── 1. Check device ────────────────────────────────────────────────────────────
echo "Checking for connected device..."
if ! $ADB get-state >/dev/null 2>&1; then
  echo "No device found. Connect a device or start an emulator." >&2
  exit 1
fi
DEVICE_NAME="$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo 'unknown')"
echo "  → $DEVICE_NAME"

# ── 2. Build ───────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo ""
  echo "Building develop debug APK..."
  chmod +x ./gradlew
  ./gradlew assembleDevelopStandardDebug \
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
$ADB install -r "$APK"
echo ""
echo "Done. Launching app..."
$ADB shell monkey -p cn.com.omnimind.bot.debug -c android.intent.category.LAUNCHER 1 \
  >/dev/null 2>&1 || true
echo "  → cn.com.omnimind.bot.debug"
