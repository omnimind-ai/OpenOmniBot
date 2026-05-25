---
name: vlm-android-gui
description: Use for OOB VLM Android GUI automation, AndroidWorld phone tasks, vlm_task, OmniFlow replay, Function conversion, and RunLog validation.
---

# VLM Android GUI Skill

## Step Guidance Essentials

- AndroidWorld first-step policy lives here; choose the simplest action that changes one variable, then verify.
- M3A-style per-step loop: observe raw screenshot, marked screenshot, UI element/index list, short history, choose one action, then use after-action feedback to correct the next step.
- Mobilerun-style structured loop is a reference pattern, not a runtime
  replacement: inject current device state, indexed page evidence, screenshot,
  and the previous tool result; require exactly one executable tool call; then
  feed structured action results back into the next turn.
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
VLM task, validate an AndroidWorld-style scenario, replay a stored Function, or
debug why a phone task did not execute.

This skill is for OOB's executable phone runtime. Open-source model skills such
as LLaVA, BLIP-2, or CLIP are useful references for vision-language modeling,
but they do not replace OOB's `vlm_task`, accessibility actions, RunLog, or
OmniFlow replay path.

Mobilerun/Droidrun is also only a design reference for OOB, not an executable
dependency. Its FastAgent uses a Python host, Portal Android app, indexed
Accessibility tree, optional screenshot, XML function calls, and structured
function results. OOB must keep the native Kotlin VLM loop and its own
Accessibility, RunLog, Function registration, recall, and replay path; borrow
the structured observation/result discipline without calling Portal, installing
Mobilerun runtime, or delegating actions to Python.

## Mobilerun Reference Flow

Record Mobilerun as a process reference only:

1. Fetch a fresh device state every turn: Accessibility tree, phone state,
   screen bounds, and optional screenshot.
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

- Keep the VLM prompt grounded in indexed UI evidence plus screenshot.
- Make tool result schemas explicit and stable, especially after-action page
  changes and failure reasons.
- Keep the action surface small and deterministic.
- Track short memory/history for facts that must survive navigation.
- Separate method/reference runners from the production runtime.

Do not borrow these parts as dependencies:

- Portal app installation, TCP/content-provider protocol, or Python driver.
- Mobilerun prompt templates as runtime prompts.
- Mobilerun macro replay format.
- A host-side agent loop that replaces OOB Kotlin `vlm_task`, RunLog, Function
  registration, UDEG recall, or model-free replay.

## Activation

Activate when the user asks for any of these:

- `vlm_task`, VLM task, 小万视觉执行, screen automation, phone automation, or
  AndroidWorld validation.
- Click, scroll, type, open app, or verify content on the current Android screen.
- A long phone task that must keep acting until a visible stop condition is met.
- Convert a successful VLM RunLog to a reusable Function.
- Run a stored Function through `call_tool` or inspect why replay failed.
- Compare live VLM behavior with OmniFlow replay behavior.

Do not activate for ordinary image Q&A when the user only uploaded a picture and
does not ask to operate the phone screen.

## Execution Mode

Use exactly one primary mode for a step sequence:

- **VLM**: live model-planned screen operation through `vlm_task`.
- **OmniFlow**: deterministic replay of an existing Function through `call_tool`
  with `function_id`.

If VLM creates a successful RunLog and the user wants reuse, convert that RunLog
to a Function after the run. If OmniFlow replay needs live perception, return to
VLM as an explicit fallback instead of mixing labels inside one replay.

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
  row's label. The marked screenshot uses the same indexes.
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
- Consider only Functions attached to that UDEG node as outgoing reusable
  transitions.
- Do not call `finished` just because recall returned `hit` or a prior Function
  finished. Finish only after the current visible page satisfies the user's
  requested end state.
- If the current screen does not match the recalled step, re-ground on the
  current screenshot/XML and continue normally.
- For form tasks, keep each field's intended value tied to the visible field
  label. If a label row or spinner must be changed, click the label/spinner
  control before typing; never type a label value into the currently focused
  phone/email/name field.
- If a replay-like action appears unsafe or ambiguous, choose a bounded live VLM
  action such as `press_back`, `scroll`, or a specific visible `click`, rather
  than blindly following historical coordinates.

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
Function should call another Function/tool.

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

Replay a stored Function through `call_tool`:

```json
{
  "function_id": "oob_cmd_vlm_task_example",
  "arguments": {}
}
```

Rules:

- Use `function_id` for existing Functions.
- Use `tool_name` plus `arguments` for direct tools such as `vlm_task`.
- Nested Function calls are valid only through `call_tool(function_id=...)`.
- If `call_tool` returns `fallback=true` or `needs_agent`, switch to a bounded
  VLM task and report the fallback reason.

## Function Segment Validation

When validating a reusable segment, do not only check registration or recall.
Run a parent Function whose step is `call_tool(function_id=...)`, and verify that
the result contains:

- parent step `executor=omniflow_function`
- `nested_function_id` equal to the expected segment id
- nested `step_results` with concrete model-free actions such as `open_app`
- the same child Function succeeds from at least two different current pages

## Offline Flow UI Contract

The user-facing flow is `RunLog -> Function registration -> local execution`.
Keep this contract visible and separate from runtime tests:

- A RunLog detail surface should expose direct RunLog replay and Function
  registration as adjacent actions.
- A Function library surface should show that a Function is registered, which
  RunLog(s) it came from, the step count, parameter count, and a local execution
  action.
- Function execution results should keep diagnostic timing internal. Persist
  `duration_ms`, `started_at_ms`, `finished_at_ms`, and phase timings in RunLog
  and test artifacts, but do not expose these fields in user-facing UI.
- Do not show internal route-building jargon to users. Keep legacy
  route-building field names only as compatibility keys.

## Validation Plan Separation

Keep user experience validation and actual phone execution validation separate:

- UX/widget validation: verify labels, buttons, disabled states, source RunLog
  badges, and Function execution result sheets with mocked channel payloads.
  Timing telemetry should be parsed and asserted in tests, not shown to users.
  These tests must not start emulators, call VLM, or depend on AndroidWorld.
- Runtime/unit validation: verify RunLog collection, conversion to Function,
  nested Function calls, replay timing propagation, UDEG node recall, segment
  recall, and no timing leakage into VLM prompts.
- Device validation: run bounded tasks on emulator-5554 or emulator-5556 only
  when explicitly requested. Record run id, package, goal, step count, success,
  `duration_ms`, token usage, replay result, and whether recall hit a UDEG node
  or Function segment.
- AndroidWorld method validation: by default export or inspect the method only.
  Do not claim benchmark success unless a live runner initialized the task,
  OOB executed the task through the native VLM loop, and AndroidWorld evaluated
  the reward.

## No Wait Actions

Never emit or preserve `wait`, `sleep`, delay, pause, or idle as a VLM or
Function action step. OOB handles page settling through its internal stability
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
4. Consider the Functions attached to that node as outgoing reusable
   transitions.

The decision path is exactly: `page match -> UDEG node -> node skill-like
decision context -> VLM/tool decision`. UDEG node data is skill-like decision
context, not memory text and not a flat Function list. Do not skip the node by
scanning the Function store directly.

Do not treat recall as a flat text search over all Functions. A recalled
Function is trusted only when the current page has been localized to its UDEG
node and the Function description/boundary fits the user goal. If the node skill
is present but no Function clearly fits, continue with normal live VLM actions.

Direct local execution is allowed only for a strong page match and a no-argument
Function that strongly matches the goal. Parameterized or weakly matched
Functions should remain decision context for the VLM/tool layer.

## RunLog and Function Handling

After a successful VLM run:

1. Check the RunLog contains concrete actions and a terminal `finished` marker.
2. Convert to a Function only when the task is reusable and not perception-only.
3. For VLM-only logs with no concrete action, do not create an empty Function.
4. If converted, report the Function id, guard decision, replay status, and run
   id.

For replay:

1. Run `call_tool(function_id=..., arguments=...)`.
2. Respect guard decisions: `allow`, `needs_agent`, `needs_confirmation`,
   `block`.
3. Report whether local replay ran and whether model/VLM fallback was needed.

## Output Requirements

When the task finishes, report:

- mode: `VLM` or `OmniFlow`
- run id, if available
- Function id, if created or replayed
- guard decision, if replayed
- number of concrete actions executed
- final visible result or failure reason
