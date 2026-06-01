# OmniFlow Tools

Use this reference to choose the canonical tool path.

## Preferred Tools

1. Use `oob_run_log_list`, `oob_run_log_get`, and `oob_run_log_convert` for
   local RunLog discovery and RunLog-to-Function conversion.
2. Use `oob_function_list`, `oob_function_get`, `oob_function_register`,
   `oob_function_delete`, and `oob_function_clear` for Function lifecycle.
3. Use `update_function` for all Function modifications, including enhancement,
   repair, RunLog evidence analysis, checker metadata, and structural patches.
4. Use `oob_function_guard_check` before risky or user-visible replay when the
   Function source or target context is uncertain.
5. Use `oob_function_run` for replay. Use `start_step_index` to resume after a
   known good prefix.

When recall returns a Function with `inputSchema`, treat it like any other
agent tool: fill required arguments from the user goal, run guard checks, then
call `oob_function_run`. A parameterized Function should not be ignored just
because it needs arguments; the agent is responsible for filling them or asking
the user if the goal does not contain enough information.

## Legacy Direct MCP Names

`omniflow.recall`, `omniflow.call_function`, `omniflow.call_tool`,
`omniflow.ingest_run_log`, and `omniflow.explore_replay` may exist in external
MCP clients or older agentkit flows.

Inside the OOB app, prefer the `oob_*` tools above. Use legacy `omniflow.*`
tools only when those are the tools actually exposed and the `oob_*` tools are
not available. Legacy `omniflow.call_function` is a compatibility alias for the
same `oob_function_run` execution path; do not design a second Function replay
flow around it.

## Tool Missing Rule

If a required tool is not exposed, say which capability is missing. Do not
substitute unrelated live GUI tools unless the task has explicitly moved into
agent fallback or first-run live collection.
