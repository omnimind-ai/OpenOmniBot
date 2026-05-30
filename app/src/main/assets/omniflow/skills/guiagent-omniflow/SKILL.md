# GUIAgent OmniFlow Skill

Use this skill when a user asks to reuse, replay, save, inspect, convert, or run
a previous OOB device execution.

OmniFlow behavior in OOB is skill-first. This skill documents how an agent
should use OOB's reusable-command primitives: store successful executions as
reusable commands, check them with guard policy, replay safe deterministic
steps, and return explicit fallback when a step requires live context. Native
Kotlin/MCP tools are backends that skills call; they are not a separate product
runtime, controller, or component registry.

## Activation

Activate when the user asks for any of these:

- Repeat a previous phone task.
- Save an execution history item as a reusable action.
- Convert a RunLog to a reusable command.
- Run a stored reusable command.
- Inspect or debug reusable command replay.
- Check whether a stored action is safe.
- Build a reusable action library from OOB history.

Do not activate for one-off chat, static writing, or Workbench Project UI unless
the user is asking to reuse execution behavior.

## Required First Steps

1. Identify access mode.
2. Inspect before executing.
3. Guard-check before running.
4. Ask the user before confirmation-required or live-context continuation.
5. Stop on blocked actions.

## Relation to VLM Execution

For online phone execution, `vlm-android-gui` owns the canonical runtime flow:
fresh-page VLM turns, UDEG page-skill injection, RunLog collection, explicit
Function replay boundaries, and fallback policy. This OmniFlow skill owns the
reusable execution material: conversion, registration, enhancement, guard
checks, and explicit replay.

Keep the boundary simple: recall is UDEG page match plus node-attached
capability candidates, not global Function search; registration is
`RunLog -> compile -> Function store -> UDEG node attachment`, not a harness;
enhancement never changes executable replay structure; replay must surface as a
real `call_function` / reusable-command card; if replay needs live perception,
return `fallback=true` / `needs_agent` and let the caller explicitly continue
with bounded VLM.

## Access Mode Selection

If MCP is available, call `tools/list`. Treat direct MCP tools as primitive
backends for this skill, not as a separate OmniFlow workflow owner.

Use Direct MCP mode if these tools exist:

```text
omniflow.recall
omniflow.call_tool
omniflow.ingest_run_log
omniflow.explore_replay
```

If direct tools are absent, use GUI bridge mode through the OOB app:

```text
Execution History / Run Logs -> Run details -> Save as reusable command
Reusable Command Library / Command Library -> Inspect -> Run
```

If only `agent_run` exists, use Agent bridge mode with a targeted prompt asking
the in-app Agent to use OmniFlow UI/native capabilities.

## Direct MCP Workflows

### Recall and Run a Reusable Command

1. `omniflow.recall(goal, current_package?, current_node_id?, current_xml?, k?)`.
   Recall must follow `page match -> UDEG node -> node skill-like decision
   context -> VLM/tool decision`, then use that node's skill-like decision
   context and attached reusable commands. Do not treat recall as a flat search
   over the reusable command store.
2. Treat `decision=recall` or `decision=segment_recall` as context, not
   completion: read `current_node`, `node_skill_context`,
   `decision_context`, and `capability_candidates`, then decide whether a
   node-attached reusable command or segment fits the live goal.
3. If a node-attached capability fits, fill arguments from `inputSchema`, then
   call `omniflow.call_tool({function_id, arguments, start_step_index?})`.
4. Only use `decision=hit` or `decision=segment_hit` as direct execution when
   the host explicitly requested direct recall execution, for example through
   `auto_execute=true`.
5. If recall misses or call_tool returns `fallback=true`, return that state to
   the caller; continue with live planning only after explicit bounded VLM
   selection.

### Write Back a RunLog

1. After a successful non-cache run, call
   `omniflow.ingest_run_log(run_id)` or pass an inline `run_log`.
2. Treat failed, empty, or non-replayable RunLogs as rejected.

### Enhance a Saved RunLog Command

Enhancement improves reuse ability without changing the execution structure.
For detailed Function-enhancement behavior, read the built-in
`omniflow-function-enhancer` skill when available.

Allowed enhancement output:

- Clearer reusable command name and description.
- Per-step title and description.
- Runtime parameter descriptors for existing non-coordinate step args, such as
  contact name, phone number, search term, message text, date, URL, or target
  object name.
- `agent_reuse` metadata: `reuse_when`, `avoid_when`, `success_signal`,
  `key_actions`, and contiguous `segments`.

Hard boundaries:

- Keep `function_id`, tool names, executors, step order, validation, fallback,
  and concrete tool args unchanged.
- Bind parameters only to existing args, for example
  `$.execution.steps[2].args.text`. Do not bind coordinates, bounds, width, or
  height.
- Treat `agent_reuse.segments` as metadata for future selection or split review.
  Do not assume they are already registered standalone commands.
- Before replay, fill fresh argument values through `parameters.bindings`; a
  recording with defaults like "妈妈" and a phone number should be reusable for
  another contact and phone number through those bindings.

Enhancement status must be explicit:

- `enhanced`: safe, meaningful changes were saved to the same Function.
- `unchanged`: Agent checked the Function and found nothing safe to improve.
- `partial`: safe changes were saved, but one or more sections failed or were
  skipped.
- `failed`: no usable enhancement was produced; keep the Function unchanged and
  allow retry.

Persist this result under `metadata.oob_enhancement` and surface it in the GUI.
The GUI action should run as a background UI task: show progress immediately,
then show `已增强`, `已检查`, `部分增强`, or `重试增强` instead of relying on a
bare boolean.

### Explore, Save, and Replay a New Path

Use this only when recall misses and the user wants OOB to discover a reusable
local UI path. Keep the exploration bounded.

1. Call `omniflow.explore_replay(goal, package_name?, max_steps?, stop_text?,
   replay?, reset_before_replay?)`.
2. Prefer `max_steps <= 3` and a concrete `stop_text` when possible.
3. Leave `allow_risky_actions=false` unless the user explicitly confirmed the
   risk.
4. Treat returned `utg.schema_version=oob.omniflow_utg.v1` as a local path
   record, not a full provider-side graph.
5. If `phase=registered`, report the reusable command id and do not claim replay ran.
   If `phase=replayed`, report both explore and replay results.

## GUI Bridge Workflows

### Save From RunLog

Open OOB, go to Run Logs, select a successful run, inspect timeline cards,
save it as a reusable command, inspect the generated spec/details, then save.
If the command is already registered for that RunLog, open the existing command
instead of registering a duplicate. Use Enhance when the user wants better
reuse labels, runtime slots, key actions, or segment metadata.

### Run

Open the reusable command library, select the command, inspect details, fill
arguments, check warnings, and run only when safe.

## Guard Rules

Decisions:

```text
allow
needs_agent
needs_confirmation
block
```

Defaults:

- Allow deterministic local UI actions: click, long_press, scroll, type,
  open_app, press_home, press_back, hot_key.
- Do not emit or preserve wait as a reusable command step. Page settling belongs to the
  local stability backend.
- Use Agent fallback for browser, web_search, memory, VLM-only, RunLog lookup,
  and Workbench query/list.
- Require confirmation for shell exec, settings write, package force-stop,
  permission grants/revokes, and mobile data writes.
- Block reboot, shutdown, fastboot, block-device writes, filesystem format, and
  protected system partition writes.

## Replay Control

Local OmniFlow replay uses pre-action controls only:

- Before dispatch, dismiss obvious blocking overlays and hide the keyboard when
  it covers the recorded target.
- Run action transfer when source/current page anchors are available; otherwise
  execute the recorded action coordinates directly.
- Do not run post-action page/package validation. A deterministic step succeeds
  when the action backend accepts the operation.
- Reusable command replay may return `fallback=true`, `needs_agent`, or
  `model_required=true`; in that case switch to bounded live VLM only when the
  user explicitly requested continued execution.
- Do not preserve `wait` steps as evidence. Page settling belongs to the native
  replay backend.

## Final Response Requirements

When you finish, report:

- Reusable command id.
- Guard decision.
- Whether local replay ran.
- Whether Agent fallback was needed.
- Whether user confirmation was requested.
- Run/audit id if available.
- Visible result or failure reason.

## Reference Docs

Read these files when available:

- `app/src/main/assets/omniflow/runlog/README.md`
- `app/src/main/assets/omniflow/runlog/references/runlog-contract.md`
- `app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md`
- `docs/reference/OOB_VLM_ANDROIDWORLD.md`
