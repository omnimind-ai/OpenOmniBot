# OmniFlow MCP Contract

This is the stable OOB OmniFlow MCP contract for external GUI agents.

External agents should treat the public surface as canonical-only:

```text
omniflow.recall
omniflow.call_function
omniflow.ingest_run_log
```

If these tools are not present, the agent should fall back to
`GUI_AGENT_PLAYBOOK.md` instead of inventing hidden calls.

## Tool Discovery

Always start with:

```text
tools/list
```

Canonical OmniFlow mode is available when the tool list contains all three
tools:

```text
omniflow.recall
omniflow.call_function
omniflow.ingest_run_log
```

Legacy OOB names such as `oob_function_*` and `oob_run_log_*` are not part of
the public MCP or Agent Kit contract. They may still appear inside OOB internal
replay policy data for old RunLog compatibility, but external agents must not
discover, call, document, or depend on them.

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
    {"index": 3, "type": "type", "duration_ms": 15},
    {"index": 4, "type": "click", "duration_ms": 23},
    {"index": 5, "type": "wait", "duration_ms": 500},
    {"index": 6, "type": "click", "duration_ms": 29}
  ],
  "control": {
    "postcondition": "passed",
    "fallback_reason": "",
    "guard_decision": "allow"
  }
}
```

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

## External Agent Flow

1. Call `tools/list`.
2. If the three canonical tools are present, call `omniflow.recall`.
3. Select a Function returned by recall.
4. Call `omniflow.call_function` with explicit arguments.
5. If a successful RunLog should become reusable, call
   `omniflow.ingest_run_log`.
6. If the canonical tools are missing, use the GUI bridge playbook.

## Safety Rules

- Do not execute a recalled Function unless the user task matches the Function
  purpose and required arguments are explicit.
- Treat `fallback=true` as a request for normal agent control, not a success.
- Treat `needs_confirmation` or high-risk install/package actions as requiring
  explicit user confirmation before retrying.
- Never depend on hidden OOB routes or old direct-tool names from external
  projects.

## Resources

The OOB canonical replay contract is tool-first. `resources/list` and
`resources/read` may expose unrelated Workbench resources, but external agents
must not require resource reads for replay.

## Prompts

Prompts are workflow instructions only. They do not execute tools.
