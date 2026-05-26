---
name: vlm-android-gui
description: Use for OOB VLM Android GUI automation, AndroidWorld phone tasks, vlm_task, OmniFlow replay, reusable command generation, and RunLog validation.
---

# VLM Android GUI Skill

## Step Guidance Essentials

- AndroidWorld first-step policy lives here; choose the simplest action that changes one variable, then verify.
- M3A/Mobilerun-style per-step loop: observe one current screenshot,
  Accessibility tree / indexed UI state, short history, and previous tool
  result; choose one action, then use after-action feedback to correct the next
  step. Marked screenshots are optional fallback evidence, not the default.
- Mobilerun-style structured loop is a reference pattern, not a runtime
  replacement: inject current device state, indexed page evidence, screenshot,
  and the previous tool result; require exactly one executable tool call; then
  feed structured action results back into the next turn.
- Protocol correction retries for the same unchanged screen may omit the
  screenshot when indexed Accessibility evidence is present; do not treat that
  as a new observation turn.
- OOB indexed page evidence: choose by visible label/role; include `element_index`
  or `scrollable_index` when available and emit 0-1000 normalized centers as fallback.
- Pass `packageName` when known; derive unknown packages from installed apps.
- Permission/onboarding: choose safe Continue/Allow/OK, not Deny/Delete/Pay.
- Focused editable input: use `type`; otherwise click intended edit/search field first.
- Visible but not focused editable input: use `input_text(target_description, content, x, y)` so the field is grounded before typing.
- Slider/seekbar: 0-1000 normalized; `Display brightness`: do not click; max x1=70,y1=110,x2=990,y2=110; min x1=990,y1=110,x2=10,y2=110.
- Numeric keypad targets: click visible digit buttons; do not `type`.
- After each action, read `screen_changed`, `appeared_texts`,
  `disappeared_texts`, `after_visible_texts`, and `after_focused_editable` from
  the tool result before choosing the next action. If an action does not change
  the expected variable, re-ground instead of repeating it.
- Validate after at least two visible UI states before `finished`.
- Multi-target goals: keep ordered checklist; finish only after named targets verified.

## Overview

Use this skill when the user wants OOB to operate an Android screen, run a
VLM task, validate an AndroidWorld-style scenario, replay a stored reusable
command, or debug why a phone task did not execute.

This skill is for OOB's executable phone runtime. Open-source model skills such
as LLaVA, BLIP-2, or CLIP are useful references for vision-language modeling,
but they do not replace OOB's `vlm_task`, accessibility actions, RunLog, or
OmniFlow replay path.

Mobilerun/Droidrun is also only a design reference for OOB, not an executable
dependency. Its FastAgent uses a Python host, Portal Android app, indexed
Accessibility tree, optional screenshot, XML-style tool calls, and structured
tool results. OOB must keep the native Kotlin VLM loop and its own
Accessibility, RunLog, reusable command registration, recall, and replay path;
borrow the structured observation/result discipline without calling Portal,
installing Mobilerun runtime, or delegating actions to Python.

## Mobilerun Reference Flow

Record Mobilerun as a process reference only. The goal is to capture useful
workflow shape, then reimplement the matching OOB behavior in native Kotlin:

1. Fetch a fresh device state every turn: Accessibility tree, phone state,
   screen bounds, and screenshot.
2. Format the tree into indexed UI evidence with stable element indexes.
3. Build one LLM turn from the goal, current device state, optional screenshot,
   short memory/history, and the previous tool result.
4. Require one structured tool block per model response. Multiple concrete
   invokes are allowed only when they are clearly sequential on the same stable
   screen; OOB should normally keep one native action per turn.
5. Execute actions through a small registry: indexed click, coordinate click,
   type, swipe, open app, back/home/enter, wait only as internal settling, and
   explicit completion.
6. Feed structured action results back into the next turn instead of relying on
   free-form chat history.
7. Persist trajectory artifacts for inspection: UI state, screenshot when
   enabled, tool call, tool result, success/failure, and token usage.

Borrow these advantages in OOB:

- Keep the VLM prompt grounded in indexed Accessibility evidence plus one
  current screenshot.
- Do not resend unchanged pixels for a tool-call protocol correction when the
  text Accessibility evidence is sufficient.
- Make tool result schemas explicit and stable, especially after-action page
  changes and failure reasons.
- Keep the action surface small and deterministic.
- Track short memory/history for facts that must survive navigation.
- Treat state fetch robustness as part of the agent loop: retry transient page
  read failures, then recover OOB Accessibility state before asking the model
  to act on stale evidence.
- Separate method/reference runners from the production runtime.

OOB mapping for the borrowed flow:

- Mobilerun `StateProvider` shape -> OOB `readCurrentPackage`,
  `readCurrentPage`, screenshot capture, and post-action observation.
- Mobilerun indexed tree formatter -> OOB indexed Accessibility evidence.
- Mobilerun tool registry -> OOB native `VLMToolDefinitions` and
  `DeviceOperator` actions.
- Mobilerun structured results -> OOB structured tool result and RunLog card
  post-action fields.
- Mobilerun trajectory artifacts -> OOB RunLog, reusable command registration,
  replay, UDEG node recall, token usage, and timing diagnostics.

Do not borrow these parts as dependencies:

- Portal app installation, TCP/content-provider protocol, or Python driver.
- Mobilerun prompt templates as runtime prompts.
- Mobilerun macro replay format.
- A host-side agent loop that replaces OOB Kotlin `vlm_task`, RunLog, reusable
  command registration, UDEG recall, or model-free replay.
- Mobilerun CLI/MCP, package import, or runtime installation in OOB validation.

## Activation

Activate when the user asks for any of these:

- `vlm_task`, VLM task, 小万视觉执行, screen automation, phone automation, or
  AndroidWorld validation.
- Click, scroll, type, open app, or verify content on the current Android screen.
- A long phone task that must keep acting until a visible stop condition is met.
- Convert a successful VLM RunLog to a reusable command.
- Run a stored reusable command through `call_tool` or inspect why replay failed.
- Compare live VLM behavior with OmniFlow replay behavior.
- Register, list, inspect, delete, or clear OOB reusable Functions/复用指令.

Do not activate for ordinary image Q&A when the user only uploaded a picture and
does not ask to operate the phone screen.

## Execution Mode

Use exactly one primary mode for a step sequence:

- **VLM**: live model-planned screen operation through `vlm_task`.
- **OmniFlow**: deterministic replay of an existing reusable command through
  `call_tool` with `function_id`.
- **Human takeover**: manual user actions recorded during a paused VLM task.

If VLM creates a successful RunLog and the user wants reuse, convert that RunLog
to a reusable command after the run. If OmniFlow replay needs live perception,
return to VLM as an explicit fallback instead of mixing labels inside one replay.

RunLog source labels must describe how the step actually executed, not whether
the action is convertible:

- Agent/VLM: online `vlm_task` or `compile_kind=vlm_step`; VLM token fields are
  online generation cost.
- Human: `source=human_takeover` or `compile_kind=manual_recording`.
- OmniFlow Replay: direct reusable-command replay with
  `source/run_source=omniflow_replay` or `runner=oob_omniflow_replay`.

Concrete actions such as `click`, `input_text`, and `swipe` in an online VLM
RunLog are only OmniFlow-compatible; do not label them as offline replay unless
the replay runner metadata is present.

## Direct VLM Task

Use `vlm_task` for live Android GUI work:

```json
{
  "goal": "From the Settings home screen, scroll until About phone is visible, open it, verify the About phone page is visible, then finish.",
  "packageName": "com.android.settings",
  "startFromCurrent": false,
  "maxSteps": 12,
  "needSummary": true
}
```

Guidelines:

- Default recall policy: keep `allowOmniFlowFunctionAutoExecute=false`. Recalled
  Functions are optional candidates and UDEG node decision context for the live
  VLM agent; do not auto-run a Function unless the user explicitly asks for a
  strict direct Function execution path.
- For bare online validation, set `disableOmniFlowRecall=true`.
- Include the app, starting point, target page, and visible finish condition.
- Set `startFromCurrent=true` only when the user explicitly wants the current
  page or the target package is already foreground.
- Pass `packageName` when the target app is known and the task should start from
  that app.
- Use `maxSteps` high enough for long tasks; prefer 8 to 20 for AndroidWorld
  validation instead of tiny click-only smoke tests.
- Require visible verification before `finished`.
- When the prompt includes `OOB indexed page evidence`, use it as grounded page
  evidence: match the pending target by visible label/role, copy its normalized
  center as action coordinates, include `element_index` for `#N` rows or
  `scrollable_index` for `S<N>` rows, and keep `target_description` tied to that
  row's label. Treat the screenshot as visual confirmation and the indexed tree
  as the coordinate source.
- The per-turn `Relevant installed apps` section is intentionally a compact
  focused list, not the full package inventory. Use an exact package from that
  list or the request `packageName` when opening an app; if the needed app is
  absent, observe or ask for clarification instead of guessing a hidden package.
- The per-turn prompt keeps stable tool/coordinate rules in the system prompt
  and sends only a compact reminder plus live page evidence. Do not compensate
  by adding long repeated protocol text to user goals; put validation criteria
  in the goal and rely on the tool schema/system prompt for the action contract.
- If the desired target is not present in the indexed element list and is not
  visually visible, scroll a listed scrollable region once, then re-observe. Do
  not tap the first unrelated row.
- For scrollable regions, use `scrollable_index` plus `direction` (`down` to
  reveal lower content, `up` to reveal previous content) and provide the listed
  0-1000 swipe coordinates as fallback.
- If an editable element is focused, use `type(content)` directly. If no input
  is focused, first click the intended editable/search field by indexed center,
  then type on the next step after focus is confirmed.
- Before each action, reduce the decision to input, desired output, and the one
  UI variable that should change. After the tool result, compare the new visible
  state with that expected change and correct only that variable.
- For goals with several named targets such as "open A, verify A, go back, open
  B, verify B", keep an ordered checklist. Do not skip directly to B, and do not
  call `finished` until A and B were both visited or verified in order.
- Do not change destructive or privacy-sensitive settings without confirmation.

## Real Device Startup

Before judging live VLM or Function behavior, normalize the runtime with the
canonical one-click entrypoint:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh
```

Use this instead of hand-editing Accessibility settings. The default profile is
`oob-5556`: it builds the standard debug APK, installs it on `emulator-5556`,
stops known UiAutomation conflicts, clears and rebinds OOB Accessibility,
launches OOB, forwards `127.0.0.1:28999` to device port `8899`, checks stale
emulator time, and probes MCP when a token is provided. Pass `--skip-build` to
reuse the last APK, or `--skip-install` when only rebinding/restarting the
runtime.

To inspect startup failure meanings without touching a device:

```bash
scripts/oob-start.sh --errors
```

When the AndroidWorld emulator pair itself must be restarted, use the OmniFlow
launcher first:

```bash
bash /Users/wuzewen/Projects/Omni/OmniFlow/scripts/start_androidworld_avds.sh
```

This starts `AndroidWorldAvd` on `emulator-5554` and `SmallPhone` on
`emulator-5556`. It intentionally kills existing emulators on those ports, so
use it only for restart, not while a validation run is active. It does not make
OOB ready by itself; after boot, always run:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554 --skip-build
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5556 --skip-build
```

The launcher uses snapshots by default, so stale emulator clocks can come back.
Let `oob-start` do its clock fix/check after every AVD restart, or launch with
`ANDROIDWORLD_USE_SNAPSHOT=0` when a clean no-snapshot restart is required.

For `emulator-5554`, keep AndroidWorld/Mobilerun state intact:

```bash
OOB_MCP_TOKEN=<token> scripts/oob-start.sh --profile 5554
```

The 5554 profile uses host port `28998`, preserves existing Accessibility
services, and does not stop Mobilerun/AndroidWorld processes.

Startup error summary:

- `startup_error=device_unavailable`: adb cannot reach the selected device.
  Start the emulator/device, confirm `adb devices -l`, or pass
  `--device <serial>`.
- `startup_error=build_failed`: Gradle failed before OOB runtime startup.
  Fix the compile/build error, or use `--skip-build` only when a valid APK
  already exists.
- `startup_error=apk_missing`: the selected APK path does not exist. Build
  first or pass an explicit APK path.
- `startup_error=apk_install_failed`: adb install failed. Check device state,
  storage, install compatibility, and the APK path.
- `startup_error=ui_automation_present`: another runner owns UiAutomation.
  Stop Mobilerun/Appium/AndroidWorld ownership or reboot the emulator before
  blaming VLM logic.
- `startup_error=enabled_but_not_bound`: Android lists OOB Accessibility as
  enabled but not bound. Rerun the one-click script so the secure setting is
  rewritten from a clean state.
- `startup_error=accessibility_not_bound`: OOB Accessibility did not bind
  inside the wait budget. Rerun with `--wait-seconds 30` or inspect
  `dumpsys accessibility`.
- `startup_error=mcp_auth_failed`: the token belongs to a different OOB
  instance/device. Copy the token from the target emulator and rerun with
  `OOB_MCP_TOKEN`.
- `startup_error=mcp_unreachable`: the app process, adb forward, or MCP server
  is not reachable.
- `startup_error=mcp_http_<status>` or
  `startup_error=mcp_probe_unexpected_payload`: MCP answered but did not return
  a usable tool list. Inspect OOB logs before testing VLM quality.
- `startup_error=app_not_running`: OOB launched then exited or did not start.
  Reinstall and inspect logcat for a startup crash.
- `startup_error=device_clock_stale`: the emulator clock is before the minimum
  TLS-safe year or too far from host UTC. Online VLM calls can fail with
  `Unacceptable certificate` / `CertificateNotYetValidException`; rerun with
  `--fix-device-clock`, check for an external runner resetting time, or sync
  the device clock.

The long-term startup runbook is
`docs/agent_context/OOB_STARTUP_RUNBOOK.md`.

If the first immediate VLM call still returns "please enable accessibility",
wait a few seconds or rerun the script; that means Android reported the service
as bound before the in-process bridge became available.

For `emulator-5554`, do not stop Mobilerun/AndroidWorld services unless the
validation explicitly targets OOB on that device.

`emulator-5556` defaults to clean OOB rebinding. Non-5556 devices default to
preserving existing Accessibility services so AndroidWorld/Mobilerun setup is
not removed unless `--clean-accessibility` is explicitly passed.

For emulator serials, startup fixes stale/skewed device clocks against host
epoch by default, checks both `date` and `dumpsys alarm nowRTC`, compares epoch
skew, and re-checks after app launch. It does not rewrite a clock that already
matches host epoch. This is required for online VLM because 5554 can otherwise
enter the model request with a 2023 clock and fail TLS before the model reasons.
Use `--fix-device-clock` to force a rewrite, or `--no-fix-device-clock` only
when you explicitly want stale clock to become a startup error.

The 5554 preserve path intentionally removes only OOB's Accessibility component
from `enabled_accessibility_services`, waits briefly, then appends it back. This
refreshes OOB when it appears in `Crashed services` while keeping Mobilerun and
the AndroidWorld accessibility forwarder enabled. If a live VLM RunLog shows
blank `before.package_name`, blank `after.package_name`, and repeated
`open_app`/`press_back`, inspect `dumpsys accessibility` before changing the
prompt: the model is likely acting without XML/page observations.

For real validation, record both the tool result and the actual device state:

```bash
adb -s emulator-5556 shell dumpsys activity activities | rg 'topResumedActivity|ResumedActivity' -m 3
adb -s emulator-5556 shell uiautomator dump /sdcard/oob_verify.xml >/dev/null
adb -s emulator-5556 shell cat /sdcard/oob_verify.xml | rg 'Display|Brightness|target visible text'
```

Treat the task as verified only when the VLM/Function result and the live
foreground package/page agree.

## First-Step AndroidWorld Rules

Keep AndroidWorld first-step behavior in this skill guidance. Do not encode
task-suite-specific prompt policy in the core VLM first-step optimizer.

For the AndroidWorld/M3A alignment method, see
`references/androidworld-m3a-method.md`. This is a method record only: it does
not claim benchmark results and does not require running AndroidWorld episodes.

The live adapter should remain a thin verification shell. AndroidWorld may
initialize tasks and compute reward, but OOB owns the online VLM loop, RunLog
collection, convert, replay, and recall. Use `scripts/androidworld_oob_eval.py`
with `--run-live` only for explicit validation, and use the same simple task set
for online VLM, replay, and recall-repeat phases.

## OmniFlow UDEG Node Skill Decision Context

OOB may inject an `OmniFlow UDEG node skill-like decision context` section into
the VLM dynamic context. Treat it as decision context reached through:
`page match -> UDEG node -> node skill-like decision context -> VLM/tool
decision`.

- Use the page-matched UDEG node's skill-like information to choose the next
  VLM/tool decision on the live screen.
- Consider only reusable commands attached to that UDEG node as outgoing reusable
  transitions.
- In default online VLM mode, those reusable commands are candidates only. The
  native VLM loop still has screen tools only (`click`, `scroll`, `input_text`,
  etc.); direct replay must be a separate explicit agent/user-selected Function
  run.
- Do not call `finished` just because recall returned `hit` or a prior reusable
  command finished. Finish only after the current visible page satisfies the
  user's requested end state.
- If the current screen does not match the recalled step, re-ground on the
  current screenshot/XML and continue normally.
- For form tasks, keep each field's intended value tied to the visible field
  label. If a label row or spinner must be changed, click the label/spinner
  control before typing; never type a label value into the currently focused
  phone/email/name field.
- If a replay-like action appears unsafe or ambiguous, choose a bounded live VLM
  action such as `press_back`, `scroll`, or a specific visible `click`, rather
  than blindly following historical coordinates.

## Function Management

Use real MCP/control-plane tools for reusable Function management. In normal
agent conversation these tools are exposed directly as agent workbench tools;
external clients can call the same names through MCP:

- `oob_function_list` to list local reusable Functions.
- `oob_function_get` to inspect one Function spec.
- `oob_function_register` to register/update a reusable instruction. Prefer
  the simple shape (`functionId`, `name`, `description`, `steps`,
  optional `sourcePage`) during conversation; use full `functionSpec` only when
  converting/importing an existing structured artifact. If `sourcePage` is
  omitted, OOB tries to capture the current Accessibility XML/package as the
  UDEG page anchor, so registration from the current screen can naturally become
  page-match recall context.
- `oob_function_guard_check` to check arguments, guard policy, and whether the
  Function can replay locally before any execution.
- `oob_function_delete` to delete one Function and remove UDEG node references.
- `oob_function_clear` with `confirm=true` to clear all Functions and detach
  all UDEG node Function/segment references.
- `oob_function_run` or `omniflow.call_tool` with `function_id` to explicitly
  replay a Function after user/agent selection.

When validating direct Function replay, inspect `step_results`, not only the
top-level `success`. A successful `open_app` replay should include an
`open_app_package` postcondition with `package_matched=true` and a nonblank
`current_package`. If the visible foreground activity is correct but
`current_package` is blank, treat that as a native replay/package-observation
bug, not a VLM reasoning failure.

`oob_command_save/list/delete/clear` are user-friendly aliases for saving and
maintaining RunLog-derived reusable instructions. Prefer `oob_function_*` when
the agent is doing explicit Function registration, inspection, guard checks, or
execution.

Do not treat registration as permission to auto-execute. In online VLM tasks,
registered Functions are candidate actions only. Use direct Function replay only
when the caller has explicitly selected the Function or set the advanced
auto-execute flag.

Do not assume a newly registered Function becomes a model-callable tool. OOB keeps
that exposure off by default; the agent should inspect/list/guard candidates and
call `oob_function_run` explicitly when it chooses one. The optional
`oobFunctionAsToolEnabled` setting is an advanced UI setting and is not toggled by
registration or RunLog conversion.

Recommended agent workflow:

1. Run `vlm_task` for the live task. Keep
   `allowOmniFlowFunctionAutoExecute=false` unless the user explicitly asks for
   strict direct replay.
2. Inspect the RunLog with `oob_run_log_get`; check `duration_ms`,
   `started_at`, `finished_at`, `step_count`, and `token_usage`.
3. Convert a successful reusable RunLog with `oob_run_log_convert`, or register
   a simple hand-authored reusable instruction with `oob_function_register`.
4. Present the Function as a candidate reusable instruction. Execute only after
   selection via `oob_function_run`.
5. Use `oob_function_delete` to remove temporary validation Functions and
   detach their UDEG node references.

When submitting a conversation through `agent_run` only to create, inspect,
convert, or explicitly run reusable instructions, pass
`toolProfile="function_management"`. That profile exposes only the Function,
RunLog, app lookup, and VLM task tools needed for this workflow, including
`oob_function_list/get/register/guard_check/run/delete/clear`. This avoids
sending the full general Agent tool catalog to the model. For even tighter
validation, pass `allowedTools` with the exact tool names needed for that turn.
Do not use the focused profile for unrelated general Agent tasks. Use
`oob_function_clear(confirm=true)` only when the user explicitly asks to clear
all reusable instructions; otherwise delete temporary validation Functions one
by one.

The device validation path for this workflow must exercise the real agent tool
chain, not only `OobOmniFlowToolkitService` directly. Use:

```bash
scripts/oob-agent-function-management-validation.sh --device emulator-5556
```

This sends a debug broadcast to the installed app and verifies
`AgentToolRegistry -> AgentToolRouter -> WorkbenchToolHandler` can expose the
focused profile, register a simple Function, list it, guard-check it, run it on
the foreground device, and report the real post-run package. For 5554, normalize
startup first with the shared profile, then pass `--device emulator-5554`.
The script prints a compact summary by default; use `--raw-json` only when the
full app-side payload is needed for debugging.

To validate that an online Agent conversation can register and run a Function
by calling the tools itself, configure the provider and run:

```bash
bash scripts/configure-oob-model-provider.sh --device emulator-5554 --profile-id profile-dashscope --model qwen-vl-max-latest
scripts/oob-agent-conversation-function-validation.sh --device emulator-5554 --profile-id profile-dashscope --model qwen-vl-max-latest
```

This validation starts a real in-app Agent run through `AgentRunService` with
`toolProfile=function_management` and the exact Function tool allowlist. The
model must call the tools; the receiver then checks that the Function exists
and can replay. If it fails with `validation_error=adb_unavailable` and the adb
body says `Operation not permitted` or `cannot connect to daemon`, the broadcast
never reached OOB because the current shell could not start adb. Restart adb
from an approved device context or rerun after the daemon is alive before
debugging prompts/tool schemas.

On emulator devices this validation keeps a host-side clock guard alive during
the online Agent run. Keep it enabled on shared 5554; AndroidWorld can reset the
device time back to 2023 after startup, and a later model round would otherwise
fail with a certificate error.

For online VLM plus RunLog conversion and deterministic replay, use:

```bash
bash scripts/demo-vlm-runlog-e2e.sh --device emulator-5554 --startup-profile 5554 --goal '<non-smoke Android task>'
```

This runs provider config, live VLM, RunLog collection, conversion, and Function
replay through the installed OOB app. On 5554 it uses `oob-start` preserve mode
so AndroidWorld/Mobilerun Accessibility services stay enabled. The default
output is intentionally compact: success, run id, Function id, token totals,
card/step counts, and replay duration. Use `--raw-json` only for debugging.
The script also keeps the emulator clock guarded during the online VLM phase.

MCP `agent_run` uses `userMessage` as the prompt field; do not send `message`.
Example wrapper:

```json
{
  "name": "agent_run",
  "arguments": {
    "userMessage": "Register the reusable instruction with oob_function_register, then report functionId and success.",
    "toolProfile": "function_management"
  }
}
```

Simple registration example:

```json
{
  "functionId": "open_android_settings",
  "name": "Open Android Settings",
  "description": "Launch Android Settings from any app.",
  "packageName": "com.android.settings",
  "steps": [
    {
      "action": "open_app",
      "packageName": "com.android.settings"
    },
    {
      "action": "finished",
      "content": "Settings opened"
    }
  ]
}
```

For screen-local Functions, call `oob_function_register` while the source page is
visible and omit `sourcePage` unless you already have a captured XML. The stored
Function should then attach to the current UDEG node and appear later as an
optional candidate when the same page is matched.

For page-scoped recall, include:

```json
{
  "sourcePage": {
    "xml": "<hierarchy>...</hierarchy>",
    "packageName": "com.android.settings"
  }
}
```

Supported simple step actions are `open_app`, `click`, `long_press`,
`input_text`, `swipe`, `press_back`, `press_home`, `press_key`, `finished`, and
`call_tool`. For deterministic replay steps, provide concrete arguments:
`packageName` for `open_app`, `x/y` or source-page-backed coordinates for
`click`, `direction` or `x1/y1/x2/y2` for `swipe`, `content` for `input_text`,
and `key` for `press_key`. When a step calls another reusable instruction, use
`action: "call_tool"` plus `functionId` and optional `arguments`.

For real-device validation, also verify the current foreground package/page
outside the tool response. A `FINISHED` response alone is not enough if the
device has already left the requested target app.

Token control:

- Each online VLM step sends the current screenshot plus compact indexed page
  evidence. This is necessary for correctness; do not remove current screenshot
  evidence by default.
- History should stay compact: carry prior action/result/post-action visible
  text, not the full previous prompt or old Accessibility XML.
- Prefer goals with explicit target app, start page, and visible finish
  condition. Ambiguous goals increase rounds and token cost.
- RunLog token fields are diagnostic and should be used for testing/reporting,
  not shown as normal user-facing UI details.

- If the target app is known, pass `packageName` in `vlm_task` instead of asking
  the model to guess the package.
- If the target app is not known, derive it from the installed application list
  before calling `vlm_task`; do not invent common Android package names.
- If the current page is a permission, onboarding, or account prompt that blocks
  the task, the first action should unblock the flow with a safe continuation
  control such as Allow, While using the app, OK, Got it, Continue, Skip, or Not
  now. Do not choose Deny, Delete, Sign in, Pay, or other destructive/private
  controls unless the user explicitly requested it.
- If an editable field is already focused and the task asks to search, type, or
  enter specific text, the first action should be `type`; do not click the same
  input field again.
- If the desired editable field is visible but a different field is focused,
  prefer `input_text` over typing into the stale focus.
- For list pages, if the requested target is not visible, use a
  deliberate vertical scroll within the list and then re-check visible text. Do
  not tap the first unrelated row.
- If a searchable list still does not expose the requested target after bounded
  scanning, use a visible search affordance instead of continuing a scroll loop.
  Search for the pending target label, then click the matching result.
- For sliders, seekbars, and system panels such as brightness or volume, never
  repeat the same `click` on the slider. Use the `scroll` action as a horizontal
  drag to the actual endpoint: to set max, set `x1` near the left/current thumb
  and `x2` at 90-95% of screen or slider width at the slider y. On a 720px wide
  AndroidWorld phone, action arguments are 0-1000 normalized coordinates: if
  XML only shows `Display brightness`, the valid next action is
  `scroll(x1=70,y1=110,x2=990,y2=110)`, which executes at the far-right safe
  edge, not `click`. To set min, use a leftward drag from the far right to the
  far left, `scroll(x1=990,y1=110,x2=10,y2=110)`. Do not describe a min action
  as "scroll left" while emitting rightward coordinates such as
  `x1=50,x2=702`; `x1` must be greater than `x2` for minimum brightness. Do
  not stop around the middle such as executed `x2=490`, and do not settle for
  executed `x2=680` when the task asks for maximum brightness.
- For nested Settings pages such as Apps > Default apps, if the task lists
  multiple rows (for example Browser app before Phone app), click the first
  pending named row even if a later row is also visible.
- For on-screen numeric keypads such as Clock timers, enter values by clicking
  the visible digit buttons in order. Use `type` only when the focused node is an
  editable text field; a keypad made of clickable digit buttons is not an
  editable text field. For a Clock timer value like 0h 1m 30s, click `1`, then
  `3`, then `0`, and stop without pressing Start.
- If the last action did not change visible text, selected state, or system
  value, do not repeat it more than once. Re-ground on the current screenshot/XML
  and choose a different action such as swipe, back, or a specific visible
  control.
- Ignore OOB overlay controls such as 接管, 继续执行, 小万, and OmniBot when
  choosing the first phone action.

## `call_tool` Dispatch

Prefer `call_tool` when the user explicitly asks to call a tool or when a stored
reusable command should call another reusable command or tool.

Run a live VLM task through `call_tool`:

```json
{
  "tool_name": "vlm_task",
  "arguments": {
    "goal": "Open Settings, find About phone, verify the page title is visible, then finish.",
    "packageName": "com.android.settings",
    "maxSteps": 12,
    "needSummary": true
  }
}
```

Replay a stored reusable command through `call_tool`:

```json
{
  "function_id": "oob_cmd_vlm_task_example",
  "arguments": {}
}
```

Rules:

- Use `function_id` for existing reusable commands.
- Use `tool_name` plus `arguments` for direct tools such as `vlm_task`.
- Nested reusable command calls are valid only through
  `call_tool(function_id=...)`.
- Treat nested `call_function` / `call_tool(function_id=...)` as a real tool
  call. It must produce its own visible tool card with
  `toolName=call_function`, `toolType=oob_function`, readable "复用指令" text,
  `argsJson.function_id`, and final success/error status. Do not hide nested
  reusable-command execution only inside a parent result JSON.
- If `call_tool` returns `fallback=true` or `needs_agent`, switch to a bounded
  VLM task and report the fallback reason.

## Reusable Command Segment Validation

When validating a reusable segment, do not only check registration or recall.
Run a parent reusable command whose step is `call_tool(function_id=...)`, and
verify that the result contains:

- parent step `executor=omniflow_function`
- `nested_function_id` equal to the expected segment id
- one streamed `tool_started` and one `tool_completed` card for the
  `call_function` step
- nested `step_results` with concrete model-free actions such as `open_app`
- the same child reusable command succeeds from at least two different current
  pages

## Offline Flow UI Contract

The user-facing flow is `RunLog -> reusable command registration -> local
execution`. Keep this contract visible and separate from runtime tests:

- A RunLog detail surface should expose direct RunLog replay and reusable command
  registration as adjacent actions.
- A reusable command library surface should show that a command is registered,
  which RunLog(s) it came from, the step count, parameter count, and a local
  execution action.
- Reusable command execution results should keep diagnostic timing internal. Persist
  `duration_ms`, `started_at_ms`, `finished_at_ms`, and phase timings in RunLog
  and test artifacts, but do not expose these fields in user-facing UI.
- Offline replay cards should carry `run_source=omniflow_replay` and
  `runner=oob_omniflow_replay`. User UI may show a compact "离线重放 /
  OmniFlow Replay" tag, but must not show VLM token cost unless the replay
  explicitly fell back to VLM.
- `call_function` cards should appear in the same agent RunLog as other tool
  cards. Users should see a compact reusable-command card and status; detailed
  nested `step_results` stay inside the card detail / raw result surfaces.
- Do not show internal route-building jargon to users. Keep legacy
  route-building field names only as compatibility keys.

## Validation Plan Separation

Keep user experience validation and actual phone execution validation separate:

- UX/widget validation: verify labels, buttons, disabled states, source RunLog
  badges, and reusable command execution result sheets with mocked channel
  payloads.
  Timing telemetry should be parsed and asserted in tests, not shown to users.
  These tests must not start emulators, call VLM, or depend on AndroidWorld.
- Runtime/unit validation: verify RunLog collection, reusable command
  generation, nested reusable command calls, replay timing propagation, UDEG node
  recall, segment recall, and no timing leakage into VLM prompts.
- Device validation: run bounded tasks on emulator-5554 or emulator-5556 only
  when explicitly requested. Record run id, package, goal, step count, success,
  `duration_ms`, token usage, replay result, and whether recall hit a UDEG node
  or reusable command segment.
- AndroidWorld method validation: by default export or inspect the method only.
  Do not claim benchmark success unless a live runner initialized the task,
  OOB executed the task through the native VLM loop, and AndroidWorld evaluated
  the reward.

## No Wait Actions

Never emit or preserve `wait`, `sleep`, delay, pause, or idle as a VLM or
reusable command action step. OOB handles page settling through its internal stability
algorithm. A valid action sequence should contain concrete actions such as:

- `click`
- `scroll`
- `type`
- `press_back`
- `press_home`
- `open_app`
- `finished`

If the page is not stable, let the runtime settle internally and then choose the
next concrete action.

## AndroidWorld Validation Pattern

A useful AndroidWorld-style test should be harder than a one-click smoke test:

1. Start from a known app or current screen.
2. Navigate across at least two visible UI states.
3. Use scrolling or search only when needed.
4. Verify a concrete final page or row by visible text.
5. Finish only after the visible verification succeeds.

Safe example goals:

- Settings: from home, scroll to About phone, open it, verify the page title or
  device information is visible, then finish.
- Settings: open Apps, enter Default apps, verify Browser app or Phone app is
  visible, then finish.
- Clock: open Alarms, verify the alarms list or empty state is visible, then
  finish without creating a new alarm.
- Contacts: search for an existing visible contact, open the result, verify the
  detail page is visible, then finish without editing or deleting anything.
- Chrome: open a page or search query only when network access is available;
  verify the page title, search result text, or address field state before
  finishing.

Avoid goals that toggle settings, send messages, buy items, delete data, or
grant permissions unless the user explicitly confirms.

Validation prompts should be written so success is observable from the current
screen. Prefer final checks based on page title, row label, tab label, empty
state, or stable visible text across at least two UI states. Do not mark success
only because one click or one scroll was executed.

## UDEG Recall Context

OOB recall follows OmniFlow's UDEG path:

1. Encode the live accessibility page into a local `PageVectorSet`.
2. Page-match that vector to a UDEG node.
3. Read the UDEG node's skill-like decision context.
4. Consider the reusable commands attached to that node as outgoing reusable
   transitions.

The decision path is exactly: `page match -> UDEG node -> node skill-like
decision context -> VLM/tool decision`. UDEG node data is skill-like decision
context, not memory text and not a flat reusable command list. Do not skip the
node by scanning the reusable command store directly.

Do not treat recall as a flat text search over all reusable commands. A recalled
reusable command is trusted only when the current page has been localized to its
UDEG node and the command description/boundary fits the user goal. If the node
skill is present but no command clearly fits, continue with normal live VLM
actions.

Direct local execution is disabled by default. It is allowed only when the caller
explicitly selects a Function (`oob_function_run` or `call_tool(function_id=...)`)
or sets the advanced `allowOmniFlowFunctionAutoExecute=true` flag, and the
Function has a strong page match, strongly matches the goal, needs no arguments,
and passes guard checks. Parameterized or weakly matched commands remain decision
context for the VLM/tool layer.

Model-tool exposure for saved Functions is also disabled by default. Registering,
converting, or syncing Functions updates the Function store and UDEG node
attachments only; it does not add each Function id to the model tool list. Use the
management tools plus explicit `oob_function_run` unless the user intentionally
enables Function-as-tool exposure.

`omniflow.recall` defaults to an agent-compact payload. It should contain the
decision, node id/package, optional Function or segment candidates, compact
step summaries, and decision policy. It should not include raw timing, full node
skill body, page vectors, or skill artifacts unless a test/debug caller sets
`include_debug=true`.

## RunLog and Reusable Command Handling

After a successful VLM run:

1. Check the RunLog contains concrete actions and a terminal `finished` marker.
2. Generate a reusable command only when the task is reusable and not
   perception-only.
3. For VLM-only logs with no concrete action, do not create an empty reusable
   command.
4. If generated, report the reusable command id, guard decision, replay status,
   and run id.

For replay:

1. Run `call_tool(function_id=..., arguments=...)`.
2. Respect guard decisions: `allow`, `needs_agent`, `needs_confirmation`,
   `block`.
3. Report whether local replay ran and whether model/VLM fallback was needed.

During RunLog conversion, preserve real human/device actions and drop only
general startup-bridge noise. A transient startup bridge is an early automatic
click where the source page is a compact prompt/overlay-like page, the clicked
target text is absent from the source page, the post-action page matches the
next concrete step's source page, and the next page contains the target. This
keeps reusable commands from replaying stale first-launch prompts while still
preserving manual takeover cards (`compile_kind=manual_recording` or
`source=human_takeover`). Inspect
`transient_startup_bridge_dropped_count` in the converted Function source when
debugging replay, but do not show that internal field in user-facing UI.

If direct replay fails an `open_app` postcondition with
`current_package=""`, verify the live foreground activity and UIAutomator XML
before blaming the Function. A foreground target app plus blank replay package
means the native runner did not obtain a valid Accessibility snapshot in its
postcondition read; rerun on the latest APK and inspect `step_results`.

## Output Requirements

When the task finishes, report:

- mode: `VLM` or `OmniFlow`
- run id, if available
- reusable command id, if created or replayed
- guard decision, if replayed
- number of concrete actions executed
- final visible result or failure reason
