# OOB Startup Runbook

Status: Living runbook
Last Updated: 2026-05-26

Use this runbook before evaluating online VLM, RunLog collection, Function
replay, or UDEG recall. Startup must be normalized first; otherwise failures can
look like model or prompt issues while the real cause is Accessibility, MCP, or
device clock state.

## One-Click Entrypoint

The canonical startup script is:

```bash
scripts/oob-start.sh
```

Use this script for daily OOB online VLM, RunLog, Function replay, and UDEG
validation. Older helper scripts must remain compatibility wrappers only; they
should not grow separate startup behavior.

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

Print the startup error summary without touching a device:

```bash
scripts/oob-start.sh --errors
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
| `device_unavailable` | adb cannot reach the selected device. | Start the emulator/device, confirm `adb devices -l`, or pass the correct `--device <serial>`. |
| `build_failed` | Gradle failed before OOB could be installed or launched. | Fix the compile/build error. Use `--skip-build` only when a valid APK already exists. |
| `apk_missing` | The APK path selected by the startup script does not exist. | Build first or pass `--apk <path>` / `--install <apk>` with an existing file. |
| `apk_install_failed` | adb install failed on the selected device. | Check device online state, storage, install compatibility, and the APK path. |
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

For Function management specifically, validate the actual agent tool chain:

```bash
scripts/oob-agent-function-management-validation.sh --device emulator-5556
```

This is stronger than a backend-only toolkit call because it goes through
`AgentToolRegistry`, the focused `function_management` profile,
`AgentToolRouter`, and `WorkbenchToolHandler` before registering/running the
Function on the device. The script prints a compact summary by default; use
`--raw-json` only when debugging the full app result payload.

To validate that a real online Agent conversation can manage Functions itself,
configure the provider and run:

```bash
bash scripts/configure-oob-model-provider.sh --device emulator-5554 --profile-id profile-dashscope --model qwen-vl-max-latest
scripts/oob-agent-conversation-function-validation.sh --device emulator-5554 --profile-id profile-dashscope --model qwen-vl-max-latest
```

This path starts `AgentRunService`, uses the focused `function_management`
profile, lets the model call the Function tools, then verifies the registered
Function can replay on the device. If the script prints
`validation_error=adb_unavailable` with adb daemon text such as
`Operation not permitted` / `cannot connect to daemon`, the broadcast did not
reach the app. Start adb from an approved direct-device context or rerun after
the daemon is already alive; do not count that as a model, prompt, or OOB
runtime failure.

For online VLM plus RunLog conversion and replay, use:

```bash
bash scripts/demo-vlm-runlog-e2e.sh --device emulator-5554 --startup-profile 5554
```

That script configures the model provider, starts through `scripts/oob-start.sh`,
runs a live VLM task in the installed app, validates token usage, converts the
RunLog into a reusable Function, and replays that Function on the same device.
On 5554 it preserves AndroidWorld/Mobilerun Accessibility services; on 5556 pass
`--startup-profile 5556`.

## Implementation Map

- `scripts/oob-start.sh`: canonical user-facing one-click entrypoint and
  startup error summary source.
- `scripts/start-oob-vlm-device.sh`: lower-level runtime normalizer and
  diagnostic implementation.
- `scripts/start-oob-5556.sh`: older dedicated 5556 wrapper, kept for
  compatibility; it delegates to `scripts/oob-start.sh --profile 5556`.
- `scripts/oob-agent-function-management-validation.sh`: real-device agent
  Function management validation entrypoint.
- `scripts/oob-agent-conversation-function-validation.sh`: real online Agent
  conversation Function registration/management validation entrypoint.
- `scripts/demo-vlm-runlog-e2e.sh`: real-device online VLM -> RunLog ->
  Function conversion -> replay validation entrypoint.
- `app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md`: agent-facing
  validation guidance.

## Current Evidence Log

2026-05-26 on `emulator-5554`:

- `scripts/oob-start.sh --profile 5554 --wait-seconds 30 --settle-seconds 1`
  rebuilt, installed, preserved AndroidWorld/Mobilerun Accessibility services,
  rebound OOB, forwarded MCP on `28998`, and returned `ready=1`.
- `scripts/oob-agent-function-management-validation.sh --device emulator-5554 --target-package com.android.settings --wait-seconds 60`
  passed through `AgentToolRegistry -> AgentToolRouter -> WorkbenchToolHandler`;
  register/list/guard/run all succeeded, guard decision was `allow`, foreground
  was `com.android.settings`, and replay run
  `omniflow_run_1779792168364_1` executed 2 steps in about 4.3 s.
- Provider configuration for `profile-dashscope` / `qwen-vl-max-latest`
  succeeded for dispatch, VLM primary, and compactor scenes.
- The agent-conversation Function validation entrypoint is implemented but the
  latest attempt failed before broadcast because the current restricted shell
  could not start the adb daemon. Direct adb device checks still saw
  `emulator-5554`; rerun this validation from an approved direct-device context.
