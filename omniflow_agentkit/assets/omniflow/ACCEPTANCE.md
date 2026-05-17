# OmniFlow Agent Kit Acceptance

The kit is acceptable when an external GUI agent can complete these checks using
only the shipped docs and skill.

## Documentation Checks

- The agent can explain what OmniFlow is and when to activate it.
- The agent can identify available access modes by reading `tools/list` and the UI.
- The agent can find the Function schema.
- The agent can find the MCP tool contract.
- The agent can find the guard decision rules.
- The agent can find fallback instructions when direct MCP tools are absent.

## Direct MCP Checks

When the host implements the direct MCP contract:

- `oob_function_list` returns stored Function summaries.
- `oob_function_get` returns a full spec.
- `oob_function_guard_check` returns `allow`, `needs_agent`, `needs_confirmation`, or `block`.
- `oob_function_run` does not execute blocked or confirmation-required steps silently.
- `oob_run_log_list` and `oob_run_log_get` expose recent runs as read-only data.
- `oob_run_log_convert(register=false)` previews a Function without writing it.
- `oob_run_log_convert(register=true)` registers a Function through the same repository used by UI and Agent.

## GUI Bridge Checks

When direct MCP tools are absent:

- The agent can open OOB and navigate to Run Logs.
- The agent can inspect a successful run timeline.
- The agent can convert that RunLog into a reusable Function using UI.
- The agent can find the Function in the library.
- The agent can run a low-risk local Function.
- The agent asks before risky or live-context steps.

## Safety Checks

- Local UI replay actions are allowed only after Function inspection.
- Browser/search/memory/VLM-only steps become Agent fallback, not hard replay.
- Shell/settings/package permission actions require confirmation.
- Reboot/shutdown/fastboot/block-device/system partition write actions are blocked.
- The final response includes the Function id and execution or audit result.

## Sample Task

Minimum sample task:

1. Register or find the sample `open_settings_demo` Function.
2. Guard-check it.
3. Run it.
4. Confirm the device opens Android Settings and waits.
5. Report `function_id=open_settings_demo` and the run result.
