---
name: vlm-android-gui
description: Use for VLM/Android GUI automation, AndroidWorld-style phone tasks, 小万视觉执行, vlm_task, OmniFlow replay, call_tool, Function conversion, and RunLog validation. Prefer when the user asks for long phone tasks, screen automation, visual grounding, or reusable Android actions.
---

# VLM Android GUI Skill

Use this skill when the user wants OOB to operate an Android screen, run a
VLM task, validate an AndroidWorld-style scenario, replay a stored Function, or
debug why a phone task did not execute.

This skill is for OOB's executable phone runtime. Open-source model skills such
as LLaVA, BLIP-2, or CLIP are useful references for vision-language modeling,
but they do not replace OOB's `vlm_task`, accessibility actions, RunLog, or
OmniFlow replay path.

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
- Do not change destructive or privacy-sensitive settings without confirmation.

## First-Step AndroidWorld Rules

Keep AndroidWorld first-step behavior in this skill guidance. Do not encode
task-suite-specific prompt policy in the core VLM first-step optimizer.

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
- For list pages such as Settings, if the requested target is not visible, use a
  deliberate vertical scroll within the list and then re-check visible text. Do
  not tap the first unrelated row.
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
