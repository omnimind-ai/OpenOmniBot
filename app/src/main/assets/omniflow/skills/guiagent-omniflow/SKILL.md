# GUIAgent OmniFlow Skill

Use this skill when a user asks to reuse, replay, save, inspect, convert, or run
a previous OOB device execution.

OmniFlow is OOB's reusable execution library. It stores successful execution as
Functions, checks them with guard policy, replays safe deterministic steps, and
falls back to Agent planning when the step requires live context.

## Activation

Activate when the user asks for any of these:

- Repeat a previous phone task.
- Save an execution history item as a reusable action.
- Convert a RunLog to a Function.
- Run a stored Function or command.
- Inspect or debug a Function replay.
- Check whether a stored action is safe.
- Build a reusable action library from OOB history.

## Access Mode Selection

If MCP is available, call `tools/list`.

Use Direct MCP mode if these tools exist:

```text
oob_function_list
oob_function_get
oob_function_guard_check
oob_function_run
oob_run_log_list
oob_run_log_get
oob_run_log_convert
```

If direct tools are absent, use GUI bridge mode through the OOB app:

```text
Execution History / Run Logs -> Run details -> Convert to reusable Function
Function Library / Command Library -> Inspect -> Run
```

If only `agent_run` exists, use Agent bridge mode with a targeted prompt asking
the in-app Agent to use OmniFlow UI/native capabilities.

## Direct MCP Workflow

List and run:

1. `oob_function_list`
2. `oob_function_get(functionId)`
3. `oob_function_guard_check(functionId, arguments)`
4. `oob_function_run(functionId, arguments, dryRun=false, continueWithAgent=false)`

Convert:

1. `oob_run_log_list`
2. `oob_run_log_get(runId)`
3. `oob_run_log_convert(runId, register=false)`
4. Inspect and guard-check the generated Function.
5. Register only if safe and useful.

## GUI Bridge Workflow

Convert: open OOB, go to Run Logs, select a successful run, inspect timeline
cards, convert to reusable Function, inspect details, then save.

Run: open Function Library or Command Library, select the Function, inspect
details, fill arguments, check warnings, then run only when safe.

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
  open_app, press_home, press_back, hot_key, wait.
- Use Agent fallback for browser, web_search, memory, VLM-only, RunLog lookup,
  and Workbench query/list.
- Require confirmation for shell exec, settings write, package force-stop,
  permission grants/revokes, and mobile data writes.
- Block reboot, shutdown, fastboot, block-device writes, filesystem format, and
  protected system partition writes.

## Final Response Requirements

Report Function id, guard decision, local replay status, Agent fallback status,
confirmation status, run/audit id if available, and visible result or failure
reason.
