# OmniFlow MCP Contract

This is the stable direct-MCP contract that external agents should prefer.

If a host app has not implemented these tools yet, the external agent should
fall back to `GUI_AGENT_PLAYBOOK.md` instead of inventing hidden calls.

## Tool Discovery

Always start with:

```text
tools/list
```

Direct OmniFlow mode is available only when the tool list contains:

```text
omniflow.recall
omniflow.call_function
```

Writeback mode additionally uses:

```text
omniflow.ingest_run_log
```

Legacy OOB compatibility mode is available when the tool list contains:

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
  "decision": "hit | recall | miss",
  "hit": {
    "function_id": "open_settings_demo",
    "inputSchema": {"type": "object", "properties": {}, "required": []}
  },
  "candidates": [],
  "reason": "oob_fixed_recall"
}
```

### `omniflow.call_function`

Executes one agent-selected Function with explicit arguments.

Input:

```json
{
  "function_id": "open_settings_demo",
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
  "run_id": "oob_function_run_...",
  "actions_executed": 2,
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

Result:

```json
{
  "accepted": true,
  "function_id": "install_sample_apk_demo",
  "status": "created | updated | rejected",
  "reason": ""
}
```

### `oob_function_list`

Lists stored reusable Functions.

Input:

```json
{
  "limit": 100,
  "includeDynamicToolNames": true
}
```

Result:

```json
{
  "success": true,
  "count": 1,
  "functions": [
    {
      "function_id": "open_settings_demo",
      "name": "Open Settings",
      "description": "Open Android Settings and wait.",
      "step_count": 2,
      "risk_level": "low",
      "requires_confirmation": false,
      "dynamic_tool_name": "oob_function.open_settings_demo"
    }
  ]
}
```

### `oob_function_get`

Reads a full Function spec.

Input:

```json
{
  "functionId": "open_settings_demo"
}
```

### `oob_function_register`

Registers or updates a Function.

Input:

```json
{
  "functionSpec": {
    "schema_version": "oob.reusable_function.v1",
    "function_id": "open_settings_demo",
    "name": "Open Settings",
    "description": "Open Android Settings and wait.",
    "parameters": [],
    "execution": {
      "kind": "tool_sequence",
      "steps": []
    }
  },
  "source": "mcp"
}
```

### `oob_function_guard_check`

Preflights a Function with materialized arguments. This does not execute steps.

Input:

```json
{
  "functionId": "open_settings_demo",
  "arguments": {}
}
```

Result:

```json
{
  "success": true,
  "function_id": "open_settings_demo",
  "decision": "allow",
  "risk_level": "low",
  "reason": "All steps are deterministic local UI actions.",
  "requires_confirmation": false,
  "requires_root": false,
  "step_decisions": [
    {
      "step_id": "step_1",
      "decision": "allow",
      "risk_level": "low",
      "reason": "open_app is a deterministic local action"
    }
  ]
}
```

### `oob_function_run`

Runs a Function through the audited OmniFlow runner.

Input:

```json
{
  "functionId": "open_settings_demo",
  "arguments": {},
  "dryRun": false,
  "continueWithAgent": false,
  "executionMode": "foreground"
}
```

Default behavior:

- Execute only the deterministic local prefix.
- `executionMode=background` may enqueue long-running work such as trusted app
  install, but it still must pass the same guard checks and audit logging.
- Return `needs_agent=true` for live-context or planning steps.
- Return `needs_confirmation=true` for confirmation-required steps.
- Stop immediately on `block`.
- Do not launch Agent fallback unless `continueWithAgent=true`.
- App install, permission, package, settings, and shell actions normally require
  confirmation unless the host has an explicit trusted/pre-approved policy.

Result:

```json
{
  "success": true,
  "function_id": "open_settings_demo",
  "runner": "oob_omniflow_replay",
  "guard_decision": "allow",
  "risk_level": "low",
  "execution_mode": "foreground",
  "step_results": [],
  "needs_agent": false,
  "needs_confirmation": false,
  "error_message": null,
  "audit_run_id": "function_run_..."
}
```

### `oob_run_log_list`

Lists recent internal RunLogs.

Input:

```json
{
  "limit": 50
}
```

### `oob_run_log_get`

Reads one RunLog timeline payload.

Input:

```json
{
  "runId": "..."
}
```

### `oob_run_log_convert`

Converts a RunLog into a reusable Function. Use `register=false` to preview.

Input:

```json
{
  "runId": "...",
  "register": false,
  "functionId": "optional_override",
  "name": "Optional display name",
  "description": "Optional description"
}
```

Result:

```json
{
  "success": true,
  "registered": false,
  "function_id": "oob_cmd_example",
  "function_spec": {}
}
```

## Dynamic Tools

When supported, every safe, registered Function can also appear as:

```text
oob_function.<safe_id>
```

The dynamic tool descriptor should include:

```json
{
  "name": "oob_function.open_settings_demo",
  "description": "Run OmniFlow Function open_settings_demo.",
  "inputSchema": {},
  "functionId": "open_settings_demo",
  "riskLevel": "low",
  "requiresConfirmation": false
}
```

Dynamic tools must dispatch to the same guard and runner as
`oob_function_run`.

## Resources

Read-only resources:

```text
oob://functions
oob://functions/{id}
oob://functions/{id}/guard
oob://function_runs
oob://function_runs/{runId}
oob://run_logs
oob://run_logs/{runId}
```

All resources return JSON text content. They must not expose arbitrary file
paths or filesystem reads.

## Prompts

Prompts are workflow instructions only. They do not execute tools.

```text
convert_runlog_to_function
replay_function_safely
debug_function_replay
inspect_function_guard
```

## Error Shape

Use one consistent failure envelope:

```json
{
  "success": false,
  "error_code": "OOB_FUNCTION_NOT_FOUND",
  "error_message": "OOB reusable function not found: id",
  "function_id": "id",
  "needs_agent": false,
  "needs_confirmation": false
}
```
