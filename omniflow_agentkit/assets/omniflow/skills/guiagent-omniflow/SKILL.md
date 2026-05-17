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

Do not activate for one-off chat, static writing, or Workbench Project UI unless
the user is asking to reuse execution behavior.

## Required First Steps

1. Identify access mode.
2. Inspect before executing.
3. Guard-check before running.
4. Ask the user before confirmation-required or live-context continuation.
5. Stop on blocked actions.

## Access Mode Selection

If MCP is available, call `tools/list`.

Use Direct MCP mode if these tools exist:

```text
omniflow.recall
omniflow.call_function
omniflow.ingest_run_log
```

If direct tools are absent, use GUI bridge mode through the OOB app:

```text
Execution History / Run Logs -> Run details -> Convert to reusable Function
Function Library / Command Library -> Inspect -> Run
```

If only `agent_run` exists, use Agent bridge mode with a targeted prompt asking
the in-app Agent to use OmniFlow UI/native capabilities.

## Direct MCP Workflows

### Recall and Run a Function

1. `omniflow.recall(goal, current_package?, current_node_id?, k?)`
2. If `decision=hit` and the hit has no required arguments, call
   `omniflow.call_function(function_id, {})`.
3. If candidates are returned, choose one, fill arguments from `inputSchema`,
   then call `omniflow.call_function(function_id, arguments)`.
4. If recall misses or call_function returns `fallback=true`, continue with the
   host agent's normal planner.

### Write Back a RunLog

1. After a successful non-cache run, call
   `omniflow.ingest_run_log(run_id)` or pass an inline `run_log`.
2. Treat failed, empty, or non-replayable RunLogs as rejected.

## GUI Bridge Workflows

### Convert

Open OOB, go to Run Logs, select a successful run, inspect timeline cards,
convert to reusable Function, inspect the generated spec/details, then save.

### Run

Open Function Library or Command Library, select the Function, inspect details,
fill arguments, check warnings, and run only when safe.

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

When you finish, report:

- Function id.
- Guard decision.
- Whether local replay ran.
- Whether Agent fallback was needed.
- Whether user confirmation was requested.
- Run/audit id if available.
- Visible result or failure reason.

## Reference Docs

Read these files when available:

- `docs/omniflow/README.md`
- `docs/omniflow/MCP_CONTRACT.md`
- `docs/omniflow/FUNCTION_SPEC.md`
- `docs/omniflow/GUI_AGENT_PLAYBOOK.md`
- `docs/omniflow/ACCEPTANCE.md`
