# AndroidWorld M3A Alignment Method

This note records the method only. It is not a benchmark result and does not
require running AndroidWorld episodes.

## M3A Reference

AndroidWorld M3A uses a host-side loop:

1. Observe current AndroidWorld state.
2. Build one action prompt from the user goal, short history, current
   screenshot, and UI element list.
3. Ask the VLM for exactly one JSON action.
4. Execute the action through AndroidWorld.
5. Wait for screen stabilization.
6. Ask the VLM to summarize the before/after screenshots and UI element lists.
7. Append the summary to history.
8. Finish only through an explicit status action after the visible state
   satisfies the goal.

The important properties are indexed target grounding, one action per turn,
before/after feedback, concise action history, and explicit completion.

## OOB Mapping

OOB should keep the runtime Kotlin-native:

- Python AndroidWorld code is only a control-plane adapter.
- The adapter defaults to method-only export and must not run AndroidWorld
  episodes unless a caller explicitly opts into live validation with
  `--run-live`.
- The adapter sends the goal, target package, maxSteps, waitTimeoutMs, model,
  and skillId to OOB.
- The OOB APK owns VLM planning, accessibility execution, RunLog collection,
  RunLog conversion, replay, UDEG recall, and token/timing statistics.
- The adapter must not reimplement GUI policy, task heuristics, or replay.

## Online VLM Alignment

OOB mirrors the M3A method through native components:

- `vlm-android-gui` skill guidance supplies task policy.
- Indexed page evidence and the current screenshot provide target grounding.
- `VLMToolDefinitions` forces one native tool call per turn.
- `VLMPostActionObservation` records after-action XML/package evidence into
  the next turn history.
- `waitTimeoutMs` is only the control-plane wait budget; `maxSteps` remains the
  execution bound.

## RunLog And Replay Method

After a successful VLM run:

- Store concrete action cards with `started_at_ms`, `finished_at_ms`,
  `duration_ms`, `token_usage_total`, per-step token usage, and per-call token
  usage.
- Convert replayable cards into an OOB OmniFlow reusable command.
- Replay model-free actions with `OobRunLogReplayService`.
- Treat recorded page similarity as a compatibility gate, not as task reward.

## Recall Method

Recall is not a flat reusable-command-store search:

1. Match the current page to a UDEG node.
2. Read that node's skill-like decision context.
3. Consider reusable commands attached to that node.
4. Rank attached reusable commands.
5. Execute a safe no-argument hit locally, or fall back to bounded VLM.

Timing fields such as `parse_request_ms`, `read_current_package_ms`,
`read_current_page_ms`, `page_match_ms`, and `rank_functions_ms` are
internal diagnostics and must not be injected into the
VLM prompt.

## Validation Without Running AndroidWorld

Use static and unit checks for the method:

- Kotlin tests for VLM request building, indexed grounding, post-action
  observation, RunLog conversion/replay, UDEG recall, and timeout clamping.
- Python `--method-only` export from `scripts/androidworld_oob_eval.py`.
- Running `scripts/androidworld_oob_eval.py` without `--run-live` is also
  method-only and must not import AndroidWorld, start an emulator, or evaluate a
  reward.
- Artifact inspection that verifies RunLog token usage, action durations, and
  recall timing fields are present.

For the current OOB work, Mobilerun/DroidRun is a recorded method reference
only. Do not call its Portal app, Python runtime, or macro replay path for OOB
validation. If an external benchmark runner is needed, it should be
AndroidWorld calling the same OOB control-plane entry while OOB keeps ownership
of Kotlin VLM, RunLog, replay, and recall.

## Live Adapter Verification Shape

When a maintainer explicitly opts into live validation, use the same adapter for
three phases:

1. `online_vlm`: AndroidWorld initializes the task, OOB executes the goal with a
   bounded `maxSteps`, and AndroidWorld polls the task reward after OOB reports.
2. `replay`: convert the successful OOB RunLog to a reusable command, reset the
   same task params, then replay the command without a model call.
3. `recall_repeat`: reset the same task params again and call OOB VLM. A good
   run should hit direct or node-attached Function recall before falling back to live VLM.

Use simple AndroidWorld tasks first, such as opening Settings pages or verifying
Clock/Contacts read-only pages. These are validation gates for the adapter and
runtime plumbing, not final benchmark claims.

## Mobilerun/DroidRun Reference Method

Mobilerun's useful flow is:

1. Fetch current device state through a device service: Accessibility tree,
   phone state, screen bounds, and optional screenshot.
2. Transform the tree into indexed UI evidence.
3. Build one LLM turn from goal, indexed state, screenshot, short memory/history,
   and previous tool result.
4. Parse a structured tool block.
5. Execute through a small action registry: click, coordinate click, type,
   swipe, open app, system button, and explicit complete.
6. Feed structured tool results into the next turn.
7. Save trajectory artifacts for debugging.

OOB should borrow the method, not the runtime:

- Use indexed Accessibility evidence plus screenshot in native `vlm_task`.
- Recreate state-read retry/recovery inside OOB Accessibility/page capture when
  device state is stale or temporarily unavailable.
- Persist structured post-action observations and token/timing artifacts in
  RunLog.
- Keep the action registry small and deterministic.
- Keep failure summaries and after-action evidence available to the next VLM
  turn.
- Do not replace Kotlin online VLM, RunLog collection, reusable command registration,
  UDEG recall, or model-free replay with Mobilerun internals.
- Do not import, install, launch, or invoke Mobilerun CLI/MCP/Portal/Python
  runtime during OOB validation. AndroidWorld and Mobilerun remain method
  references unless a maintainer explicitly asks for an external comparison.
