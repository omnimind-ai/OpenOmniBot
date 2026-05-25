# RunLog Contract

## Data Shape

RunLog storage has two layers:

- Event log: append-only NDJSON, `schema_version = oob.run_log_event.v1`.
- Snapshot: compact JSON projection used by timeline/list APIs.

The snapshot is a cache, not the only source of truth. Reconstruct a run by
applying events with `event_seq` greater than the snapshot's `eventSeq`.

RunLog cards should preserve:

- `tool_name` / `toolName`
- `tool_call.arguments` or `arguments`
- `result` / `raw_result_json`
- `header.status`, `header.success`, and `duration_ms`
- before-state XML when coordinate remap is possible

Functions use:

- `schema_version = oob.reusable_function.v1`
- `function_id`
- `name`
- `description`
- `parameters`
- `source.kind = run_log`
- `execution.kind = tool_sequence`
- `execution.steps[*].executor`
- `execution.steps[*].args`
- `execution.steps[*].agent_call`
- `parameters[*].bindings`

Do not require a separate skill manifest, `SKILL.md`, `script_reuse`,
`agent_reuse`, or runtime target wrapper for RunLog-derived assets. OmniFlow is
the Function lifecycle itself: convert, index, match, execute, and fallback.

## Executor Classification

The canonical policy artifact is `replay_policy.json`. Kotlin and Dart keep
small mirrored policy classes for runtime speed, and tests must verify parity
with the JSON artifact.

Omniflow:

- `click`
- `long_press`
- `scroll`
- `type`
- `input_text`
- `swipe`
- `open_app`
- `press_home`
- `press_back`
- `press_key`
- `hot_key`
- `finished`

Compatibility aliases from provider/exported OmniFlow assets are normalized
before execution: `tap/click_at/click_element -> click`,
`type_text/set_text/inputtext -> input_text`, `scroll_* -> swipe`,
`presskey/key_event -> press_key`, `openapp/launch_app -> open_app`, and
`finish/done/complete -> finished`. `source_context.page` and OOB's
`source_context.src_ctx.page` are both valid coordinate remap inputs.
Legacy `wait` cards are skipped during conversion because page settling is an
internal backend concern, not a reusable Function step.

Perception-only agent:

- `vlm_task`
- `image_picker`
- `screen_capture`
- `android_privileged_action_screenshot`

Data-flow agent:

- `browser_use`
- `web_search`
- `memory_search`
- `memory_recall`
- `memory_query`
- `oob_agent_run`
- `omniflow.recall`
- `omniflow.ingest_run_log`
- `workbench_api_list`
- `oob_function_list`
- `oob_function_get`
- `oob_function_register`
- `oob_function_guard_check`
- `oob_run_log_list`
- `oob_run_log_get`
- `oob_run_log_convert`

OOB-native OmniFlow execution:

- `go_to_node`
- `click_node`
- `node_click`
- `omniflow.call_tool`
- `call_tool`
- `oob_tool_call`
- `omniflow.call_function`
- `call_function`
- `oob_function_run`

Graph tools and `call_tool` entries with `function_id` convert to
`executor=omniflow`, not `executor=agent`. Graph tools execute embedded
`path`/UTG edge data through the local primitive action executor. `call_tool`
without `function_id` delegates to the live tool router when available. Legacy
`call_function` names are accepted only for compatibility.

## Known Failure Modes

- Empty function from VLM-only RunLog.
- Wrapper card skipped but parameter bindings still point to old indexes.
- AI normalization turns `browser_use` back into `executor=tool`.
- Direct UI replay tries to execute a tool step without router.
- Agent prompt has stale args after materialization.
- Workspace command save uses different rules from Flutter conversion.
- Failed local replay card is treated as concrete replay evidence and suppresses
  the VLM fallback.
- `android_privileged_action.arguments` stays nested and produces a model-free
  click/swipe/type step without executable top-level args.
- Regression where provider/exported Function uses canonical
  `input_text/swipe/press_key` while OOB only handles legacy
  `type/scroll/press_home/press_back`.
- OmniFlow `go_to_node/click_node/call_tool(function_id)` accidentally becomes
  `executor=agent` or `executor=tool` instead of local `executor=omniflow`.

## Required Test Cases

- VLM-only card creates one agent step.
- VLM wrapper plus click keeps only the click as `omniflow`.
- `browser_use` and `web_search` become `executor=agent` with reason `data_flow_tool_requires_live_context`.
- AI-normalized output cannot override data-flow executor policy.
- Mixed replay returns `needs_agent` at the first non-local step.
- Command save distillation follows the same executor policy.
- Provider canonical action names and aliases normalize to deterministic local
  replay actions.
- OmniFlow graph/function commands convert and execute as local
  `executor=omniflow` steps.
- Failed local action does not suppress VLM-only fallback.
- `android_privileged_action` local UI wrappers flatten nested `arguments` into
  executable step args.
