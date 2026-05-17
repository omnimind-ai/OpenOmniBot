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
- `open_app`
- `press_home`
- `press_back`
- `hot_key`
- `wait`

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
- `workbench_api_list`
- `oob_run_log_list`
- `oob_run_log_get`
- `oob_run_log_convert`

## Known Failure Modes

- Empty function from VLM-only RunLog.
- Wrapper card skipped but parameter bindings still point to old indexes.
- AI normalization turns `browser_use` back into `executor=tool`.
- Direct UI replay tries to execute a tool step without router.
- Agent prompt has stale args after materialization.
- Workspace command save uses different rules from Flutter conversion.

## Required Test Cases

- VLM-only card creates one agent step.
- VLM wrapper plus click keeps only the click as `omniflow`.
- `browser_use` and `web_search` become `executor=agent` with reason `data_flow_tool_requires_live_context`.
- AI-normalized output cannot override data-flow executor policy.
- Mixed replay returns `needs_agent` at the first non-local step.
- Command save distillation follows the same executor policy.
