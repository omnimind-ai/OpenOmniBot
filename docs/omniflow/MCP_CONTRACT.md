# OmniFlow MCP Contract

This is the stable OOB OmniFlow MCP contract for external GUI agents.

External agents should treat the canonical surface as the normal activation
path:

```text
omniflow.recall
omniflow.call_function
omniflow.ingest_run_log
omniflow.explore_replay
```

OOB also exposes a direct Function/RunLog surface for deterministic replay
audits and explicit tool-driven integration:

```text
oob_function_list
oob_function_get
oob_function_register
oob_function_guard_check
oob_function_run
oob_run_log_list
oob_run_log_get
oob_run_log_convert
```

If these tools are not present, the agent should fall back to
`GUI_AGENT_PLAYBOOK.md` instead of inventing hidden calls.

## Tool Discovery

Always start with:

```text
tools/list
```

Canonical OmniFlow mode is available when the tool list contains:

```text
omniflow.recall
omniflow.call_function
omniflow.ingest_run_log
omniflow.explore_replay
```

Direct OOB Function/RunLog mode is available when the tool list contains:

```text
oob_function_list
oob_function_get
oob_function_guard_check
oob_function_run
oob_run_log_list
oob_run_log_get
oob_run_log_convert
```

## Fixed Tools

### `omniflow.recall`

Finds reusable Function candidates for a goal and current app/page scope.
Recall does not fill missing arguments and does not execute parameterized
Functions.

Input:

```json
{
  "goal": "open Android Settings",
  "current_package": "com.android.settings",
  "current_node_id": "optional-node",
  "k": 8
}
```

Result:

```json
{
  "success": true,
  "decision": "hit",
  "hit": {
    "function_id": "settings_click_path_demo",
    "inputSchema": {"type": "object", "properties": {}, "required": []}
  },
  "candidates": [],
  "reason": "oob_fixed_recall"
}
```

`decision` is one of:

```text
hit
recall
miss
```

### `omniflow.call_function`

Executes one agent-selected Function with explicit arguments.

Input:

```json
{
  "function_id": "settings_click_path_demo",
  "arguments": {},
  "goal": "open Android Settings"
}
```

Result:

```json
{
  "success": true,
  "fallback": false,
  "error": null,
  "function_id": "settings_click_path_demo",
  "run_id": "omniflow_run_...",
  "actions_executed": 7,
  "timing": {"runner_duration_ms": 142},
  "step_results": [
    {"index": 0, "type": "open_app", "duration_ms": 18},
    {"index": 1, "type": "click", "duration_ms": 20},
    {"index": 2, "type": "click", "duration_ms": 26},
    {"index": 3, "type": "input_text", "duration_ms": 15},
    {"index": 4, "type": "click", "duration_ms": 23},
    {"index": 6, "type": "click", "duration_ms": 29}
  ],
  "control": {
    "postcondition": "passed",
    "fallback_reason": "",
    "guard_decision": "allow"
  }
}
```

`run_id` is required on successful execution. `audit_run_id`, when present, is
an alias for internal correlation and must match `run_id`.

If guard policy, remapping, terminal verification, or a live-context step
prevents deterministic replay, the result returns `success=false` or
`fallback=true` with `control.fallback_reason`.

### `omniflow.ingest_run_log`

Converts a successful OOB RunLog into a reusable local Function asset.

Input:

```json
{
  "run_id": "runlog_install_demo",
  "auto_enrich": true
}
```

Inline canonical RunLog payloads are also accepted for simple external
writeback:

```json
{
  "run_log": {
    "run_id": "external_run_1",
    "goal": "open Android Settings",
    "steps": []
  },
  "auto_enrich": true
}
```

Result:

```json
{
  "accepted": true,
  "success": true,
  "function_id": "install_sample_apk_demo",
  "status": "created",
  "reason": ""
}
```

### `omniflow.explore_replay`

Runs the OOB-native lightweight exploration pipeline on the device:

```text
launch app -> observe Accessibility XML -> select safe click/scroll candidates
-> record oob.omniflow_utg.v1 -> convert to Function -> optionally replay
```

Input:

```json
{
  "goal": "open network settings",
  "package_name": "com.android.settings",
  "max_steps": 1,
  "replay": true,
  "reset_before_replay": true
}
```

Result:

```json
{
  "success": true,
  "phase": "replayed",
  "run_id": "omniflow_utg_...",
  "function_id": "oob_fn_...",
  "utg": {
    "schema_version": "oob.omniflow_utg.v1",
    "edge_count": 2,
    "path": ["utg_edge_1", "utg_edge_2"]
  },
  "explore": {"step_count": 2, "done_reason": "target_text_found"},
  "replay": {
    "success": true,
    "run_id": "omniflow_run_...",
    "actions_executed": 2
  }
}
```

This is OOB-native deterministic exploration, not the full provider-side
OmniFlow graph runtime. It is appropriate for safe foreground UI discovery and
recording a reusable local path. Long-term UTG merging, semantic route search,
cross-session recall enrichment, and advanced failure recovery remain
provider/OmniFlow responsibilities.

### Direct Function/RunLog Tools

Use direct tools when the caller needs explicit inspection, guard preflight,
or deterministic replay timing rather than recall-first activation.

`oob_function_list` and `oob_function_get` expose registered Function specs.
`oob_function_guard_check` returns `allow`, `needs_agent`,
`needs_confirmation`, or `block` before execution. `oob_function_run` runs a
Function directly and returns `run_id`, `runner`, `timing`, and
`step_results`. When local replay fails but agent recovery is possible, it also
returns `fallback_context`; after the agent completes the failed step, call
`oob_function_run` again with `resume_from_step`, `fallback_session_id`, and
`fallback_attempt` from that context to continue the remaining steps.

Example direct run result:

```json
{
  "success": true,
  "run_id": "omniflow_run_...",
  "function_id": "settings_click_path_demo",
  "runner": "oob_omniflow_replay",
  "guard_decision": "allow",
  "execution_mode": "foreground",
  "timing": {"runner_duration_ms": 142},
  "step_results": []
}
```

`oob_run_log_list` and `oob_run_log_get` expose recent OOB RunLogs.
`oob_run_log_convert` converts a successful RunLog into a reusable Function
and can register it when requested.
`update_function` is the evidence writeback path for an existing Function:
calling it with `functionId` and `run_id` returns `analysis_context` plus an
`agent_prompt`; calling it again with agent-authored `analysis` and an optional
`patch` saves the evidence and any safe Function updates.

## External Agent Flow

1. Call `tools/list`.
2. If canonical tools are present, call `omniflow.recall`.
3. Select a Function returned by recall.
4. Call `omniflow.call_function` with explicit arguments.
5. If a successful RunLog should become reusable, call
   `omniflow.ingest_run_log`.
6. If recall misses and the user wants OOB to discover a local path, call
   `omniflow.explore_replay` with a bounded `max_steps` and optional
   `stop_text`.
7. Use `oob_function_guard_check` and `oob_function_run` for direct audit or
   explicit deterministic replay flows.
8. If the MCP tools are missing, use the GUI bridge playbook.

## Safety Rules

- Do not execute a recalled Function unless the user task matches the Function
  purpose and required arguments are explicit.
- Treat `fallback=true` as a request for normal agent control, not a success.
- Treat `needs_confirmation` or high-risk install/package actions as requiring
  explicit user confirmation before retrying.
- Keep `allow_risky_actions=false` for `omniflow.explore_replay` unless the
  user explicitly confirmed the risk.
- Never depend on hidden OOB routes outside the tools returned by `tools/list`.

## Resources

The OOB canonical replay contract is tool-first. `resources/list` and
`resources/read` may expose unrelated Workbench resources, but external agents
must not require resource reads for replay.

## Prompts

Prompts are workflow instructions only. They do not execute tools.
