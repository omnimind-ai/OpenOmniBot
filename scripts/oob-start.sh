#!/usr/bin/env bash
# Stable one-click startup entrypoint for OOB online VLM / OmniFlow validation.
#
# Default profile:
#   - oob-5556: dedicated OOB validation emulator. Build, install, clean-rebind
#     OOB Accessibility, stop known UiAutomation conflicts, forward MCP, probe
#     MCP when a token is provided.
#
# Shared profile:
#   - androidworld-5554: shared emulator. Build/install OOB, preserve existing
#     Accessibility services, do not stop Mobilerun/AndroidWorld processes, use
#     a separate MCP host port.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

PROFILE="${OOB_START_PROFILE:-oob-5556}"
DEVICE_SERIAL="${OOB_DEVICE:-}"
HOST_PORT="${OOB_MCP_HOST_PORT:-}"
WAIT_SECONDS="${OOB_START_WAIT_SECONDS:-25}"
SETTLE_SECONDS="${OOB_START_SETTLE_SECONDS:-3}"
FLUTTER_TARGET="${OOB_FLUTTER_TARGET:-lib/main_standard.dart}"
APK="$ROOT_DIR/app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk"
MCP_TOKEN="${OOB_MCP_TOKEN:-}"
SKIP_BUILD=0
SKIP_INSTALL=0
CLOCK_ARGS=()

usage() {
  cat <<'EOF'
Usage:
  OOB_MCP_TOKEN=<token> scripts/oob-start.sh
  OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554

Profiles:
  5556, oob-5556, dedicated
      Dedicated OOB validation startup. Default device emulator-5556, host MCP
      port 28999, clean Accessibility rebind, stop known UiAutomation conflicts.

  5554, androidworld-5554, shared
      Shared AndroidWorld/Mobilerun-safe startup. Default device emulator-5554,
      host MCP port 28998, preserve existing Accessibility services, do not stop
      known conflict packages.

Options:
  --profile <name>        Startup profile. Default: $OOB_START_PROFILE or oob-5556.
  --device <serial>       Override adb serial for the selected profile.
  --host-port <port>      Override local forwarded MCP port.
  --token <token>         MCP bearer token. Same as OOB_MCP_TOKEN.
  --apk <path>            APK to install. Default: developStandard debug APK.
  --skip-build            Reuse the existing APK.
  --skip-install          Do not install an APK before startup.
  --flutter-target <path> Flutter entrypoint passed as -Ptarget.
  --wait-seconds <n>      Wait budget for Accessibility/MCP readiness.
  --settle-seconds <n>    Extra delay after MCP probe before ready.
  --fix-device-clock      Force an attempted emulator clock sync.
  --no-fix-device-clock   Report stale device clock without changing it.
  --no-clock-check        Skip stale emulator clock detection.
  --help                  Show this help.

Startup errors emitted by the underlying runtime script:
  ui_automation_present             Another runner owns UiAutomation.
  enabled_but_not_bound             Accessibility enabled but OOB is not bound.
  accessibility_not_bound           OOB Accessibility did not bind in time.
  mcp_auth_failed                   MCP token is for a different OOB instance.
  mcp_unreachable                   adb forward/app process/MCP is unreachable.
  mcp_http_<status>                 MCP responded with an unexpected HTTP code.
  mcp_probe_unexpected_payload      MCP returned no usable tool list.
  app_not_running                   OOB process is not alive after launch.
  device_clock_stale                Device clock is too old for provider TLS.

Fast paths:
  scripts/oob-start.sh --skip-build
  scripts/oob-start.sh --profile 5554 --skip-build --skip-install
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      [[ $# -lt 2 ]] && { echo "--profile requires a value" >&2; exit 1; }
      PROFILE="$2"
      shift
      ;;
    --profile=*)
      PROFILE="${1#--profile=}"
      ;;
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
    --fix-device-clock|--no-fix-device-clock|--no-clock-check)
      CLOCK_ARGS+=("$1")
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

case "$WAIT_SECONDS" in (*[!0-9]*|"") echo "--wait-seconds must be numeric" >&2; exit 1;; esac
case "$SETTLE_SECONDS" in (*[!0-9]*|"") echo "--settle-seconds must be numeric" >&2; exit 1;; esac

PROFILE_CANONICAL=""
ACCESSIBILITY_ARGS=()
CONFLICT_ARGS=()

case "$PROFILE" in
  5556|oob-5556|dedicated)
    PROFILE_CANONICAL="oob-5556"
    DEVICE_SERIAL="${DEVICE_SERIAL:-emulator-5556}"
    HOST_PORT="${HOST_PORT:-28999}"
    ACCESSIBILITY_ARGS=(--clean-accessibility)
    CONFLICT_ARGS=(--stop-conflicts)
    ;;
  5554|androidworld-5554|shared)
    PROFILE_CANONICAL="androidworld-5554"
    DEVICE_SERIAL="${DEVICE_SERIAL:-emulator-5554}"
    HOST_PORT="${HOST_PORT:-28998}"
    ACCESSIBILITY_ARGS=(--preserve-accessibility)
    CONFLICT_ARGS=(--no-stop-conflicts)
    ;;
  *)
    echo "Unknown profile: $PROFILE" >&2
    usage
    exit 1
    ;;
esac

case "$HOST_PORT" in (*[!0-9]*|"") echo "--host-port must be numeric" >&2; exit 1;; esac

echo "[oob-start] profile=${PROFILE_CANONICAL}"
echo "[oob-start] device=${DEVICE_SERIAL}"
echo "[oob-start] host_port=${HOST_PORT}"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "[oob-start] building developStandard debug APK"
  ./gradlew --no-daemon :app:assembleDevelopStandardDebug "-Ptarget=${FLUTTER_TARGET}" --build-cache
fi

START_ARGS=(
  --device "$DEVICE_SERIAL"
  --host-port "$HOST_PORT"
  --wait-seconds "$WAIT_SECONDS"
  --settle-seconds "$SETTLE_SECONDS"
)
START_ARGS+=("${ACCESSIBILITY_ARGS[@]}")
START_ARGS+=("${CONFLICT_ARGS[@]}")
if (( ${#CLOCK_ARGS[@]} > 0 )); then
  START_ARGS+=("${CLOCK_ARGS[@]}")
fi

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

OOB_START_PROBE_COMMAND="OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile ${PROFILE_CANONICAL} --device ${DEVICE_SERIAL} --host-port ${HOST_PORT} --skip-build --skip-install" \
  scripts/start-oob-vlm-device.sh "${START_ARGS[@]}"
