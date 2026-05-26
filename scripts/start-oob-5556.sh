#!/usr/bin/env bash
# One-click startup for the dedicated OOB validation emulator.
#
# This is the fixed daily entrypoint for emulator-5556. It builds the standard
# debug APK unless skipped, installs it, cleanly rebinds OOB Accessibility,
# stops known UiAutomation conflicts, launches OOB, forwards MCP, and probes MCP
# when OOB_MCP_TOKEN is provided.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${OOB_DEVICE:-emulator-5556}"
HOST_PORT="${OOB_MCP_HOST_PORT:-28999}"
WAIT_SECONDS="${OOB_START_WAIT_SECONDS:-20}"
SETTLE_SECONDS="${OOB_START_SETTLE_SECONDS:-3}"
FLUTTER_TARGET="${OOB_FLUTTER_TARGET:-lib/main_standard.dart}"
APK="$ROOT_DIR/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"
MCP_TOKEN="${OOB_MCP_TOKEN:-}"
SKIP_BUILD=0
SKIP_INSTALL=0

usage() {
  cat <<'EOF'
Usage:
  OOB_MCP_TOKEN=<token> scripts/start-oob-5556.sh

Preferred stable entrypoint:
  OOB_MCP_TOKEN=<token> scripts/oob-start.sh

Options:
  --device <serial>        Target adb serial. Default: $OOB_DEVICE or emulator-5556.
  --host-port <port>       Local forwarded MCP port. Default: 28999.
  --token <token>          MCP bearer token. Same as OOB_MCP_TOKEN.
  --apk <path>             APK to install. Default: app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk.
  --skip-build             Reuse the existing APK.
  --skip-install           Do not install an APK before startup.
  --flutter-target <path>  Flutter entrypoint passed as -Ptarget. Default: lib/main_standard.dart.
  --wait-seconds <n>       Wait budget for binding/MCP startup. Default: 20.
  --settle-seconds <n>     Extra delay after MCP probe before ready. Default: 3.
  --help                   Show this help.

Startup failures summarized by the underlying script:
  ui_automation_present       Another runner owns UiAutomation; stop it or reboot.
  enabled_but_not_bound       Accessibility is enabled but not bound; clean rebind fixes it.
  accessibility_not_bound     OOB Accessibility did not bind before timeout.
  mcp_auth_failed             Token belongs to a different OOB instance/device.
  mcp_unreachable             adb forward/app process/MCP server is not reachable.
  app_not_running             OOB launched then exited or did not start.
  device_clock_stale          Device clock is too old for model-provider TLS.
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
    --host-port)
      [[ $# -lt 2 ]] && { echo "--host-port requires a port" >&2; exit 1; }
      HOST_PORT="$2"
      shift
      ;;
    --host-port=*)
      HOST_PORT="${1#--host-port=}"
      ;;
    --token)
      [[ $# -lt 2 ]] && { echo "--token requires a value" >&2; exit 1; }
      MCP_TOKEN="$2"
      shift
      ;;
    --token=*)
      MCP_TOKEN="${1#--token=}"
      ;;
    --apk)
      [[ $# -lt 2 ]] && { echo "--apk requires a path" >&2; exit 1; }
      APK="$2"
      shift
      ;;
    --apk=*)
      APK="${1#--apk=}"
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --skip-install)
      SKIP_INSTALL=1
      ;;
    --flutter-target)
      [[ $# -lt 2 ]] && { echo "--flutter-target requires a path" >&2; exit 1; }
      FLUTTER_TARGET="$2"
      shift
      ;;
    --flutter-target=*)
      FLUTTER_TARGET="${1#--flutter-target=}"
      ;;
    --wait-seconds)
      [[ $# -lt 2 ]] && { echo "--wait-seconds requires a value" >&2; exit 1; }
      WAIT_SECONDS="$2"
      shift
      ;;
    --wait-seconds=*)
      WAIT_SECONDS="${1#--wait-seconds=}"
      ;;
    --settle-seconds)
      [[ $# -lt 2 ]] && { echo "--settle-seconds requires a value" >&2; exit 1; }
      SETTLE_SECONDS="$2"
      shift
      ;;
    --settle-seconds=*)
      SETTLE_SECONDS="${1#--settle-seconds=}"
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

case "$HOST_PORT" in (*[!0-9]*|"") echo "--host-port must be numeric" >&2; exit 1;; esac
case "$WAIT_SECONDS" in (*[!0-9]*|"") echo "--wait-seconds must be numeric" >&2; exit 1;; esac
case "$SETTLE_SECONDS" in (*[!0-9]*|"") echo "--settle-seconds must be numeric" >&2; exit 1;; esac

echo "[oob-5556] device=${DEVICE_SERIAL}"
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "[oob-5556] building developStandard debug APK"
  ./gradlew --no-daemon :app:assembleDevelopStandardDebug "-Ptarget=${FLUTTER_TARGET}" --build-cache
fi

START_ARGS=(
  --device "$DEVICE_SERIAL"
  --host-port "$HOST_PORT"
  --wait-seconds "$WAIT_SECONDS"
  --settle-seconds "$SETTLE_SECONDS"
  --clean-accessibility
  --stop-conflicts
)

if [[ "$SKIP_INSTALL" -eq 0 ]]; then
  if [[ ! -f "$APK" ]]; then
    echo "APK not found: $APK" >&2
    echo "Run without --skip-build first or pass --apk <path>." >&2
    exit 1
  fi
  START_ARGS+=(--install "$APK")
fi

if [[ -n "${MCP_TOKEN// }" ]]; then
  START_ARGS+=(--token "$MCP_TOKEN")
fi

scripts/start-oob-vlm-device.sh "${START_ARGS[@]}"
