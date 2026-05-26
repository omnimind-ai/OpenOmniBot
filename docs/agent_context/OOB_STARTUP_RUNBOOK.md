# OOB Startup Runbook

Status: Living runbook
Last Updated: 2026-05-26

Use this runbook before evaluating online VLM, RunLog collection, Function
replay, or UDEG recall. Startup must be normalized first; otherwise failures can
look like model or prompt issues while the real cause is Accessibility, MCP, or
device clock state.

## One-Click Entrypoint

Default dedicated OOB validation device:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh
```

Shared AndroidWorld/Mobilerun-safe device:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554
```

Fast restart without rebuilding:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --skip-build
```

Fast rebind/probe without reinstalling:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --skip-build --skip-install
```

## Profiles

`oob-5556` is the default profile:

- device: `emulator-5556`
- host MCP port: `28999`
- installs the developStandard debug APK
- clean-rebinds OOB Accessibility
- stops known UiAutomation conflicts
- launches OOB and probes MCP
- checks and attempts to fix stale emulator time

`androidworld-5554` is the shared profile:

- device: `emulator-5554`
- host MCP port: `28998`
- installs the same APK unless `--skip-install` is passed
- preserves existing Accessibility services
- does not stop Mobilerun/AndroidWorld processes
- refreshes only OOB Accessibility
- checks and attempts to fix stale emulator time

## Startup Error Summary

The script prints `startup_error=<code>` and `startup_hint=<hint>` when startup
cannot be normalized.

| Code | Meaning | Action |
| --- | --- | --- |
| `ui_automation_present` | Another runner owns UiAutomation, so Accessibility may not receive events. | Stop Mobilerun/Appium/AndroidWorld ownership on the OOB device, or reboot the emulator. On 5554, only do this when the validation explicitly targets OOB. |
| `enabled_but_not_bound` | Android secure settings list OOB Accessibility, but the service did not bind. | Rerun `scripts/oob-start.sh`; use the default 5556 profile for a clean rebind. |
| `accessibility_not_bound` | OOB Accessibility did not bind before timeout. | Rerun with `--wait-seconds 30`; inspect `adb shell dumpsys accessibility`. |
| `mcp_auth_failed` | The MCP token belongs to another app instance/device. | Copy the token from the target emulator and rerun with `OOB_MCP_TOKEN` or `--token`. |
| `mcp_unreachable` | Local port, adb forward, app process, or MCP server is unavailable. | Check that OOB is running, confirm `adb forward`, then rerun the startup script. |
| `mcp_http_<status>` | MCP answered, but not with a successful tool-list response. | Inspect app logs and the HTTP body printed by the script. |
| `mcp_probe_unexpected_payload` | MCP responded without a usable non-empty tool list. | Treat this as MCP initialization failure, not VLM quality failure. |
| `app_not_running` | OOB launched then exited, or did not start. | Reinstall and inspect logcat for startup crashes. |
| `device_clock_stale` | Device year is older than the TLS-safe threshold. | Rerun with `--fix-device-clock` or manually sync emulator time before online VLM. |

## What The Script Guarantees

After `ready=1`, these should be true:

- OOB process is alive.
- OOB Accessibility is bound.
- No UiAutomation conflict is present on the dedicated 5556 profile.
- MCP is forwarded to the selected host port.
- MCP tool list was probed when a token was provided.
- Device clock is at or after the configured minimum year, unless clock fixing
  was skipped.

This does not prove VLM task success. For a real task, still verify:

```bash
adb -s emulator-5556 shell dumpsys activity activities | rg 'topResumedActivity|ResumedActivity' -m 3
adb -s emulator-5556 shell uiautomator dump /sdcard/oob_verify.xml >/dev/null
adb -s emulator-5556 shell cat /sdcard/oob_verify.xml | rg '<expected visible text>'
```

## Implementation Map

- `scripts/oob-start.sh`: stable user-facing one-click entrypoint.
- `scripts/start-oob-vlm-device.sh`: lower-level runtime normalizer and
  diagnostic implementation.
- `scripts/start-oob-5556.sh`: older dedicated 5556 wrapper, kept for
  compatibility.
- `app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md`: agent-facing
  validation guidance.
