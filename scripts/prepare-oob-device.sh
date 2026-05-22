#!/usr/bin/env bash
# Prepare a fresh Android device/emulator for OOB development and acceptance.
#
# This script builds and installs the develop-standard debug APK with a default
# OpenAI-compatible model provider baked into BuildConfig, optionally enables
# OOB Accessibility, and launches the app.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

APK="$ROOT_DIR/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACCESSIBILITY_SERVICE="${OOB_ACCESSIBILITY_SERVICE:-com.google.android.accessibility.selecttospeak.SelectToSpeakService}"
DEFAULT_BASE_URL="${OMNIMIND_API_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}"
API_KEY_ENV="${OMNIMIND_API_KEY_ENV:-DASHSCOPE_API_KEY}"
MODEL_ID="${OMNIMIND_MODEL:-${OPENAI_MODEL:-qwen-vl-max-latest}}"
FLUTTER_TARGET="${OOB_FLUTTER_TARGET:-lib/main_standard.dart}"
SKIP_BUILD=0
ENABLE_ACCESSIBILITY=1
LAUNCH_APP=1
DEVICE_SERIAL=""

usage() {
  cat <<'EOF'
Usage:
  DASHSCOPE_API_KEY=<key> bash scripts/prepare-oob-device.sh --device <serial>

Options:
  --device <serial>          Target adb device or emulator serial.
  --skip-build               Install the last built debug APK without rebuilding.
  --base-url <url>           Default model provider base URL.
                             Default: https://dashscope.aliyuncs.com/compatible-mode/v1
  --model <model-id>         Default scene model id.
                             Default: $OMNIMIND_MODEL, $OPENAI_MODEL, or qwen-vl-max-latest
  --flutter-target <path>    Flutter entrypoint passed as -Ptarget.
                             Default: lib/main_standard.dart
  --package <package-name>   Installed package to launch/configure.
                             Default: cn.com.omnimind.bot.debug
  --no-accessibility         Do not try to enable the OOB Accessibility service.
  --no-launch                Do not launch OOB after install.
  --help                     Show this help text.

Environment:
  DASHSCOPE_API_KEY          Required API key by default. The script never prints it.
  OMNIMIND_API_KEY           Backward-compatible fallback API key.
  OMNIMIND_API_KEY_ENV       Optional alternate env var name for the API key.
  OMNIMIND_API_BASE_URL      Optional default base URL.
  OMNIMIND_MODEL             Optional default model id.
  OOB_FLUTTER_TARGET         Optional Flutter entrypoint.
  OOB_PACKAGE_NAME           Optional installed package name override.
  OOB_ACCESSIBILITY_SERVICE  Optional accessibility service class override.

The provider is injected through these Gradle BuildConfig values:
  OMNIBOT_DEFAULT_MODEL_PROVIDER_BASE_URL
  OMNIBOT_DEFAULT_MODEL_PROVIDER_API_KEY
  OMNIBOT_DEFAULT_MODEL_PROVIDER_MODEL_ID
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -lt 2 ]] && { echo "--device requires a serial" >&2; exit 1; }
      DEVICE_SERIAL="$2"
      shift
      ;;
    --device=*)
      DEVICE_SERIAL="${1#--device=}"
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --base-url)
      [[ $# -lt 2 ]] && { echo "--base-url requires a value" >&2; exit 1; }
      DEFAULT_BASE_URL="$2"
      shift
      ;;
    --base-url=*)
      DEFAULT_BASE_URL="${1#--base-url=}"
      ;;
    --model)
      [[ $# -lt 2 ]] && { echo "--model requires a value" >&2; exit 1; }
      MODEL_ID="$2"
      shift
      ;;
    --model=*)
      MODEL_ID="${1#--model=}"
      ;;
    --flutter-target)
      [[ $# -lt 2 ]] && { echo "--flutter-target requires a value" >&2; exit 1; }
      FLUTTER_TARGET="$2"
      shift
      ;;
    --flutter-target=*)
      FLUTTER_TARGET="${1#--flutter-target=}"
      ;;
    --package)
      [[ $# -lt 2 ]] && { echo "--package requires a value" >&2; exit 1; }
      PACKAGE_NAME="$2"
      shift
      ;;
    --package=*)
      PACKAGE_NAME="${1#--package=}"
      ;;
    --no-accessibility)
      ENABLE_ACCESSIBILITY=0
      ;;
    --no-launch)
      LAUNCH_APP=0
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

if [[ -z "$DEVICE_SERIAL" ]]; then
  echo "--device is required. Use adb devices to choose a serial." >&2
  exit 1
fi
API_KEY="${!API_KEY_ENV:-}"
if [[ -z "${API_KEY// }" && "$API_KEY_ENV" != "OMNIMIND_API_KEY" ]]; then
  API_KEY="${OMNIMIND_API_KEY:-}"
fi
if [[ -z "${API_KEY// }" ]]; then
  echo "$API_KEY_ENV is required and must not be empty." >&2
  exit 1
fi
if [[ -z "${DEFAULT_BASE_URL// }" ]]; then
  echo "--base-url must not be empty." >&2
  exit 1
fi
if [[ -z "${MODEL_ID// }" ]]; then
  echo "--model must not be empty." >&2
  exit 1
fi
if [[ -z "${FLUTTER_TARGET// }" ]]; then
  echo "--flutter-target must not be empty." >&2
  exit 1
fi

ADB=(adb -s "$DEVICE_SERIAL")

echo "Checking device: $DEVICE_SERIAL"
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "Device is not available: $DEVICE_SERIAL" >&2
  exit 1
fi
DEVICE_NAME="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
echo "  device=${DEVICE_NAME:-unknown}"
echo "  provider_base_url=$DEFAULT_BASE_URL"
echo "  provider_model=$MODEL_ID"
echo "  provider_key_env=$API_KEY_ENV"
echo "  flutter_target=$FLUTTER_TARGET"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "Building developStandard debug APK with default provider config..."
  chmod +x ./gradlew
  env \
    OMNIBOT_DEFAULT_MODEL_PROVIDER_BASE_URL="$DEFAULT_BASE_URL" \
    OMNIBOT_DEFAULT_MODEL_PROVIDER_API_KEY="$API_KEY" \
    OMNIBOT_DEFAULT_MODEL_PROVIDER_MODEL_ID="$MODEL_ID" \
    ./gradlew assembleDevelopStandardDebug "-Ptarget=$FLUTTER_TARGET" --build-cache -q
else
  echo "Skipping build; using existing APK. Provider config must already be baked into that APK."
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

APK_SIZE_MB="$(du -m "$APK" | cut -f1)"
echo "Installing APK (~${APK_SIZE_MB} MB) on $DEVICE_SERIAL"
if ! INSTALL_OUTPUT="$("${ADB[@]}" install -r "$APK" 2>&1)"; then
  echo "$INSTALL_OUTPUT" >&2
  if grep -q "INSTALL_FAILED_INSUFFICIENT_STORAGE" <<<"$INSTALL_OUTPUT"; then
    REMOTE_APK="/data/local/tmp/oob-develop-standard-debug.apk"
    echo "adb install hit insufficient storage; retrying with push + pm install."
    "${ADB[@]}" push "$APK" "$REMOTE_APK" >/dev/null
    trap '"${ADB[@]}" shell rm -f "$REMOTE_APK" >/dev/null 2>&1 || true' EXIT
    "${ADB[@]}" shell pm install -r "$REMOTE_APK"
    "${ADB[@]}" shell rm -f "$REMOTE_APK" >/dev/null 2>&1 || true
    trap - EXIT
  else
    exit 1
  fi
else
  echo "$INSTALL_OUTPUT"
fi

if [[ "$ENABLE_ACCESSIBILITY" -eq 1 ]]; then
  COMPONENT="${PACKAGE_NAME}/${ACCESSIBILITY_SERVICE}"
  echo "Allowing OOB overlay app-op: $PACKAGE_NAME"
  "${ADB[@]}" shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow || true
  echo "Enabling OOB Accessibility service: $COMPONENT"
  "${ADB[@]}" shell settings put secure enabled_accessibility_services "$COMPONENT" || true
  "${ADB[@]}" shell settings put secure accessibility_enabled 1 || true
fi

if [[ "$LAUNCH_APP" -eq 1 ]]; then
  echo "Launching OOB package: $PACKAGE_NAME"
  "${ADB[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null
fi

echo "prepare_oob_device=ok"
