#!/usr/bin/env bash
# One-click runtime startup for real OOB VLM / OmniFlow device validation.
#
# Startup failures this script is meant to prevent or diagnose:
# - OOB Accessibility is listed under "Enabled services" but "Bound services" is
#   empty after reinstall/rebind. Fix: clear and re-write the secure setting.
# - Mobilerun/UiAutomator2 owns UiAutomation on the OOB test emulator, which can
#   unbind OOB Accessibility and make real clicks fail. Fix: stop known conflict
#   packages on the dedicated OOB device before binding OOB.
# - MCP was not restored after the app was killed or reinstalled. Fix: launch OOB,
#   set adb forward, and optionally probe /mcp/list_tools with OOB_MCP_TOKEN.
# - The wrong MCP token is used for the target device. Fix: copy the token from
#   the OOB instance running on that exact emulator and pass OOB_MCP_TOKEN.
# - OOB launches but the process exits immediately. Fix: reinstall the debug APK
#   and inspect logcat for the app crash before testing VLM logic.
# - adb cannot reach the selected emulator/device. Fix: start the emulator or
#   pass the correct --device serial.
# - APK install fails before runtime normalization. Fix: check the APK path,
#   install compatibility, device storage, and whether the device stayed online.
# - Emulator clock is stale, which makes model-provider TLS fail with
#   CertificateNotYetValidException / "Unacceptable certificate". Fix: sync the
#   device clock before running online VLM.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${OOB_DEVICE:-emulator-5556}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
ACCESSIBILITY_SERVICE="${OOB_ACCESSIBILITY_SERVICE:-com.google.android.accessibility.selecttospeak.SelectToSpeakService}"
ACCESSIBILITY_LABEL="${OOB_ACCESSIBILITY_LABEL:-Omnibot}"
HOST_PORT="${OOB_MCP_HOST_PORT:-28999}"
DEVICE_PORT="${OOB_MCP_DEVICE_PORT:-8899}"
MCP_TOKEN="${OOB_MCP_TOKEN:-}"
WAIT_SECONDS="${OOB_START_WAIT_SECONDS:-12}"
SETTLE_SECONDS="${OOB_START_SETTLE_SECONDS:-3}"
STOP_CONFLICTS="${OOB_STOP_CONFLICTS:-auto}"
PRESERVE_ACCESSIBILITY="${OOB_PRESERVE_ACCESSIBILITY:-auto}"
CHECK_DEVICE_CLOCK="${OOB_CHECK_DEVICE_CLOCK:-1}"
FIX_DEVICE_CLOCK="${OOB_FIX_DEVICE_CLOCK:-auto}"
CLOCK_MIN_YEAR="${OOB_CLOCK_MIN_YEAR:-2025}"
INSTALL_APK=""
LAUNCH_APP=1
ENABLE_ACCESSIBILITY=1

usage() {
  cat <<'EOF'
Usage:
  scripts/start-oob-vlm-device.sh
  OOB_MCP_TOKEN=<token> scripts/start-oob-vlm-device.sh --device emulator-5556

Options:
  --device <serial>        Target adb serial. Default: $OOB_DEVICE or emulator-5556.
  --package <name>         OOB package name. Default: cn.com.omnimind.bot.debug.
  --accessibility-label <s> Label shown in dumpsys for the service. Default: Omnibot.
  --install <apk>          Install an APK before startup checks.
  --host-port <port>       Local forwarded MCP port. Default: 28999.
  --device-port <port>     Device MCP port. Default: 8899.
  --token <token>          MCP bearer token. Same as OOB_MCP_TOKEN.
  --stop-conflicts         Force-stop known UiAutomation conflict packages.
  --no-stop-conflicts      Do not force-stop conflict packages.
  --preserve-accessibility Append OOB to existing enabled services.
  --clean-accessibility    Clear existing services before enabling OOB.
  --no-accessibility       Do not rebind OOB Accessibility.
  --no-launch              Do not launch the OOB app.
  --no-clock-check         Skip stale emulator clock detection.
  --fix-device-clock       Try to sync stale device time with host UTC via su/date.
  --no-fix-device-clock    Report stale device time without trying to change it.
  --wait-seconds <n>       Wait budget for binding/MCP startup. Default: 12.
  --settle-seconds <n>     Extra delay after MCP probe before ready. Default: 3.
  --help                   Show this help.

Notes:
  - Default conflict policy is auto: stop known conflicts on emulator-5556 only.
  - If no token is provided, the script still forwards MCP and prints the probe
    command to run after copying the token from OOB.
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
    --package)
      [[ $# -lt 2 ]] && { echo "--package requires a value" >&2; exit 1; }
      PACKAGE_NAME="$2"
      shift
      ;;
    --package=*)
      PACKAGE_NAME="${1#--package=}"
      ;;
    --accessibility-label)
      [[ $# -lt 2 ]] && { echo "--accessibility-label requires a value" >&2; exit 1; }
      ACCESSIBILITY_LABEL="$2"
      shift
      ;;
    --accessibility-label=*)
      ACCESSIBILITY_LABEL="${1#--accessibility-label=}"
      ;;
    --install)
      [[ $# -lt 2 ]] && { echo "--install requires an APK path" >&2; exit 1; }
      INSTALL_APK="$2"
      shift
      ;;
    --install=*)
      INSTALL_APK="${1#--install=}"
      ;;
    --host-port)
      [[ $# -lt 2 ]] && { echo "--host-port requires a value" >&2; exit 1; }
      HOST_PORT="$2"
      shift
      ;;
    --host-port=*)
      HOST_PORT="${1#--host-port=}"
      ;;
    --device-port)
      [[ $# -lt 2 ]] && { echo "--device-port requires a value" >&2; exit 1; }
      DEVICE_PORT="$2"
      shift
      ;;
    --device-port=*)
      DEVICE_PORT="${1#--device-port=}"
      ;;
    --token)
      [[ $# -lt 2 ]] && { echo "--token requires a value" >&2; exit 1; }
      MCP_TOKEN="$2"
      shift
      ;;
    --token=*)
      MCP_TOKEN="${1#--token=}"
      ;;
    --stop-conflicts)
      STOP_CONFLICTS=1
      ;;
    --no-stop-conflicts)
      STOP_CONFLICTS=0
      ;;
    --preserve-accessibility)
      PRESERVE_ACCESSIBILITY=1
      ;;
    --clean-accessibility)
      PRESERVE_ACCESSIBILITY=0
      ;;
    --no-accessibility)
      ENABLE_ACCESSIBILITY=0
      ;;
    --no-launch)
      LAUNCH_APP=0
      ;;
    --no-clock-check)
      CHECK_DEVICE_CLOCK=0
      ;;
    --fix-device-clock)
      FIX_DEVICE_CLOCK=1
      ;;
    --no-fix-device-clock)
      FIX_DEVICE_CLOCK=0
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
case "$DEVICE_PORT" in (*[!0-9]*|"") echo "--device-port must be numeric" >&2; exit 1;; esac
case "$WAIT_SECONDS" in (*[!0-9]*|"") echo "--wait-seconds must be numeric" >&2; exit 1;; esac
case "$SETTLE_SECONDS" in (*[!0-9]*|"") echo "--settle-seconds must be numeric" >&2; exit 1;; esac
case "$CLOCK_MIN_YEAR" in (*[!0-9]*|"") echo "OOB_CLOCK_MIN_YEAR must be numeric" >&2; exit 1;; esac

ADB=(adb -s "$DEVICE_SERIAL")
COMPONENT="${PACKAGE_NAME}/${ACCESSIBILITY_SERVICE}"

log() {
  printf '[oob-start] %s\n' "$*"
}

startup_error() {
  local code="$1"
  local hint="$2"
  log "startup_error=${code}"
  log "startup_hint=${hint}"
}

adb_shell() {
  "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'
}

require_device() {
  if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    startup_error "device_unavailable" "adb cannot reach ${DEVICE_SERIAL}; start the emulator/device or pass the correct --device serial."
    exit 1
  fi
}

should_stop_conflicts() {
  if [[ "$STOP_CONFLICTS" == "1" || "$STOP_CONFLICTS" == "true" ]]; then
    return 0
  fi
  if [[ "$STOP_CONFLICTS" == "0" || "$STOP_CONFLICTS" == "false" ]]; then
    return 1
  fi
  [[ "$DEVICE_SERIAL" == "emulator-5556" ]]
}

should_preserve_accessibility() {
  if [[ "$PRESERVE_ACCESSIBILITY" == "1" || "$PRESERVE_ACCESSIBILITY" == "true" ]]; then
    return 0
  fi
  if [[ "$PRESERVE_ACCESSIBILITY" == "0" || "$PRESERVE_ACCESSIBILITY" == "false" ]]; then
    return 1
  fi
  [[ "$DEVICE_SERIAL" != "emulator-5556" ]]
}

accessibility_dump() {
  "${ADB[@]}" shell dumpsys accessibility 2>/dev/null | tr -d '\r'
}

accessibility_bound() {
  local dump="$1"
  grep -Fq 'Bound services:{Service' <<<"$dump" &&
    grep -Fq "Service[label=${ACCESSIBILITY_LABEL}" <<<"$dump" &&
    grep -Fq "${PACKAGE_NAME}/${ACCESSIBILITY_SERVICE}" <<<"$dump" &&
    grep -Fq "[${PACKAGE_NAME}]" <<<"$dump" &&
    ! grep -q 'Bound services:{}' <<<"$dump"
}

enabled_accessibility_value_with_oob() {
  local current="$1"
  if [[ "$current" == "null" || -z "$current" ]]; then
    printf '%s' "$COMPONENT"
    return
  fi
  IFS=':' read -r -a services <<<"$current"
  local filtered=()
  local found=0
  for service in "${services[@]}"; do
    [[ -z "$service" || "$service" == "null" ]] && continue
    if [[ "$service" == "$COMPONENT" ]]; then
      found=1
    fi
    filtered+=("$service")
  done
  if [[ "$found" -eq 0 ]]; then
    filtered+=("$COMPONENT")
  fi
  local IFS=':'
  printf '%s' "${filtered[*]}"
}

enabled_accessibility_value_without_oob() {
  local current="$1"
  if [[ "$current" == "null" || -z "$current" ]]; then
    return
  fi
  IFS=':' read -r -a services <<<"$current"
  local filtered=()
  for service in "${services[@]}"; do
    [[ -z "$service" || "$service" == "null" || "$service" == "$COMPONENT" ]] && continue
    filtered+=("$service")
  done
  local IFS=':'
  printf '%s' "${filtered[*]}"
}

has_ui_automation() {
  grep -q 'Ui Automation' <<<"$1"
}

device_clock_year() {
  adb_shell date +%Y | head -n 1 | tr -cd '0-9'
}

try_fix_device_clock() {
  local stamp
  stamp="$(date -u +%m%d%H%M%Y.%S)"
  "${ADB[@]}" shell su 0 date "$stamp" >/dev/null 2>&1 ||
    "${ADB[@]}" shell date "$stamp" >/dev/null 2>&1
}

check_device_clock() {
  [[ "$CHECK_DEVICE_CLOCK" == "0" || "$CHECK_DEVICE_CLOCK" == "false" ]] && return 0
  local year
  year="$(device_clock_year)"
  [[ -z "$year" ]] && return 0
  if (( year >= CLOCK_MIN_YEAR )); then
    log "device_clock_year=${year}"
    return 0
  fi

  log "device_clock_year=${year}"
  if [[ "$FIX_DEVICE_CLOCK" == "1" || "$FIX_DEVICE_CLOCK" == "true" || "$FIX_DEVICE_CLOCK" == "auto" ]]; then
    log "device_clock_fix=attempt"
    if try_fix_device_clock; then
      year="$(device_clock_year)"
      log "device_clock_year_after_fix=${year:-unknown}"
      if [[ -n "$year" ]] && (( year >= CLOCK_MIN_YEAR )); then
        return 0
      fi
    fi
  fi

  startup_error "device_clock_stale" "Device clock is before ${CLOCK_MIN_YEAR}; online VLM TLS can fail with an unacceptable/not-yet-valid certificate. Sync device time or rerun with --fix-device-clock."
  return 1
}

print_accessibility_summary() {
  accessibility_dump |
    grep -Ei 'Bound services|Enabled services|Binding services|Crashed services|Ui Automation|Omnibot|cn\.com\.omnimind|mobilerun|wetest' -A3 || true
}

wait_for_accessibility() {
  local started now dump
  started="$(date +%s)"
  while true; do
    dump="$(accessibility_dump)"
    if accessibility_bound "$dump" && ! has_ui_automation "$dump"; then
      return 0
    fi
    now="$(date +%s)"
    if (( now - started >= WAIT_SECONDS )); then
      log "accessibility_not_ready"
      local emitted_error=0
      if has_ui_automation "$dump"; then
        startup_error "ui_automation_present" "UiAutomation is active on this device; stop Mobilerun/Appium/AndroidWorld ownership or reboot the emulator, then rerun this script."
        emitted_error=1
      fi
      if grep -q 'Bound services:{}' <<<"$dump"; then
        startup_error "enabled_but_not_bound" "Android lists OOB Accessibility as enabled but not bound; rerun with clean rebinding or use the dedicated 5556 one-click script."
        emitted_error=1
      fi
      if [[ "$emitted_error" -eq 0 ]]; then
        startup_error "accessibility_not_bound" "OOB Accessibility did not bind within the wait budget; rerun with a longer --wait-seconds or inspect dumpsys accessibility."
      fi
      print_accessibility_summary
      return 1
    fi
    sleep 1
  done
}

probe_mcp() {
  if [[ -z "${MCP_TOKEN// }" ]]; then
    log "mcp_forwarded=http://127.0.0.1:${HOST_PORT}"
    log "mcp_probe_skipped=no_token"
    local default_probe_command
    default_probe_command="OOB_MCP_TOKEN=<token> $0 --device $DEVICE_SERIAL --no-accessibility --no-launch"
    log "probe_command=${OOB_START_PROBE_COMMAND:-$default_probe_command}"
    return 0
  fi
  local started now response body status count last_error
  started="$(date +%s)"
  while true; do
    response="$(curl --noproxy '*' -sS -m 4 -w $'\n%{http_code}' \
      -H "Authorization: Bearer ${MCP_TOKEN}" \
      -H 'Content-Type: application/json' \
      -d '{}' "http://127.0.0.1:${HOST_PORT}/mcp/list_tools" 2>&1)" || {
        last_error="$response"
        now="$(date +%s)"
        if (( now - started >= WAIT_SECONDS )); then
          startup_error "mcp_unreachable" "The local MCP probe could not reach 127.0.0.1:${HOST_PORT}; verify adb forward and that the OOB app process is alive."
          printf '%s\n' "$last_error" >&2
          return 1
        fi
        sleep 1
        continue
      }
    status="${response##*$'\n'}"
    body="${response%$'\n'$status}"
    if [[ "$status" == "401" || "$status" == "403" ]]; then
      startup_error "mcp_auth_failed" "The MCP token does not match this OOB instance; copy the token from the target emulator and rerun with OOB_MCP_TOKEN or --token."
      printf '%s\n' "$body" >&2
      return 1
    fi
    if [[ "$status" != "200" ]]; then
      now="$(date +%s)"
      if (( now - started >= WAIT_SECONDS )); then
        startup_error "mcp_http_${status}" "The MCP server answered with HTTP ${status}; inspect OOB logs or rerun after launching the app."
        printf '%s\n' "$body" >&2
        return 1
      fi
      sleep 1
      continue
    fi
    count="$(count_mcp_tools "$body")"
    if [[ "$count" != "0" ]]; then
      log "mcp_tools=${count}"
      return 0
    fi
    now="$(date +%s)"
    if (( now - started >= WAIT_SECONDS )); then
      startup_error "mcp_probe_unexpected_payload" "MCP responded but did not return a non-empty tool list; check that the app initialized the MCP server."
      printf '%s\n' "$body" >&2
      return 1
    fi
    sleep 1
  done
}

count_mcp_tools() {
  local response="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r 'if type=="object" and has("tools") then (.tools|length) elif type=="array" then length else 0 end' <<<"$response" 2>/dev/null || echo 0
    return
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys
try:
    payload=json.load(sys.stdin)
    tools=payload.get("tools", payload) if isinstance(payload, dict) else payload
    print(len(tools) if isinstance(tools, list) else 0)
except Exception:
    print(0)
' <<<"$response"
    return
  fi
  grep -o '"name"[[:space:]]*:' <<<"$response" | wc -l | tr -d ' '
}

require_device
log "device=${DEVICE_SERIAL}"
log "package=${PACKAGE_NAME}"
check_device_clock

if [[ -n "$INSTALL_APK" ]]; then
  if [[ ! -f "$INSTALL_APK" ]]; then
    startup_error "apk_missing" "APK not found: ${INSTALL_APK}; build first or pass --install <apk> with an existing file."
    exit 1
  fi
  log "installing_apk=$INSTALL_APK"
  if ! "${ADB[@]}" install -r "$INSTALL_APK"; then
    startup_error "apk_install_failed" "adb install failed on ${DEVICE_SERIAL}; check device state, storage, install compatibility, and APK path."
    exit 1
  fi
fi

if should_stop_conflicts; then
  log "stopping_conflicts=enabled"
  "${ADB[@]}" shell am force-stop com.mobilerun.portal >/dev/null 2>&1 || true
  "${ADB[@]}" shell am force-stop io.appium.uiautomator2.server >/dev/null 2>&1 || true
  "${ADB[@]}" shell am force-stop io.appium.uiautomator2.server.test >/dev/null 2>&1 || true
else
  log "stopping_conflicts=disabled"
fi

if [[ "$ENABLE_ACCESSIBILITY" -eq 1 ]]; then
  log "rebinding_accessibility=${COMPONENT}"
  "${ADB[@]}" shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
  "${ADB[@]}" shell appops set "$PACKAGE_NAME" ACCESS_RESTRICTED_SETTINGS allow >/dev/null 2>&1 || true
  if should_preserve_accessibility; then
    log "accessibility_mode=preserve_existing"
    current_services="$("${ADB[@]}" shell settings --user 0 get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
    preserved_services="$(enabled_accessibility_value_without_oob "$current_services")"
    if [[ -n "$preserved_services" ]]; then
      "${ADB[@]}" shell settings --user 0 put secure enabled_accessibility_services "$preserved_services"
    else
      "${ADB[@]}" shell settings --user 0 delete secure enabled_accessibility_services >/dev/null 2>&1 || true
    fi
    sleep 1
    "${ADB[@]}" shell settings --user 0 put secure enabled_accessibility_services "$(enabled_accessibility_value_with_oob "$preserved_services")"
  else
    log "accessibility_mode=clean_rebind"
    "${ADB[@]}" shell settings --user 0 delete secure enabled_accessibility_services >/dev/null 2>&1 || true
    "${ADB[@]}" shell settings --user 0 put secure accessibility_enabled 0 >/dev/null 2>&1 || true
    sleep 1
    "${ADB[@]}" shell settings --user 0 put secure enabled_accessibility_services "$COMPONENT"
  fi
  "${ADB[@]}" shell settings --user 0 put secure accessibility_enabled 1
fi

if [[ "$LAUNCH_APP" -eq 1 ]]; then
  log "launching=${PACKAGE_NAME}"
  "${ADB[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null
fi

if [[ "$ENABLE_ACCESSIBILITY" -eq 1 ]]; then
  wait_for_accessibility
else
  print_accessibility_summary
fi

log "forward=tcp:${HOST_PORT}->tcp:${DEVICE_PORT}"
"${ADB[@]}" forward "tcp:${HOST_PORT}" "tcp:${DEVICE_PORT}"

if ! adb_shell pidof "$PACKAGE_NAME" >/dev/null; then
  startup_error "app_not_running" "OOB is not running after launch; reinstall the debug APK and inspect logcat for startup crashes."
  exit 1
fi

probe_mcp
if (( SETTLE_SECONDS > 0 )); then
  log "settling=${SETTLE_SECONDS}s"
  sleep "$SETTLE_SECONDS"
fi
log "startup_note=first_vlm_screenshot_may_require_android_screen_capture_approval"
log "ready=1"
