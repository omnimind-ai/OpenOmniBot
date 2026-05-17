# OmniFlow GUI Agent Playbook

This playbook is for external GUI agents that receive the OmniFlow kit.

The agent is expected to be capable: it can read docs, inspect available MCP
tools, operate Android UI, and ask the user for confirmation.

## First Move

1. Read `skills/guiagent-omniflow/SKILL.md`.
2. If MCP is available, call `tools/list`.
3. Choose the best available mode:
   - Direct MCP mode when `oob_function_*` tools exist.
   - GUI bridge mode when only the OOB app UI is available.
   - Agent bridge mode when only `agent_run` exists.

## Direct MCP Workflow

### List and inspect Functions

```text
tools/call oob_function_list
tools/call oob_function_get(functionId)
tools/call oob_function_guard_check(functionId, arguments)
```

Run only when guard returns `allow`, or when the user explicitly confirms a
`needs_confirmation` result.

### Convert a RunLog

```text
tools/call oob_run_log_list
tools/call oob_run_log_get(runId)
tools/call oob_run_log_convert(runId, register=false)
tools/call oob_function_guard_check(functionId, arguments)
tools/call oob_run_log_convert(runId, register=true)
```

Do not register an obviously unsafe Function. If a Function requires live
planning, register it only if the user wants reusable Agent fallback behavior.

### Run a Function

```text
tools/call oob_function_run(functionId, arguments, dryRun=false, continueWithAgent=false)
```

If the result has `needs_agent=true`, ask the user whether to continue with
Agent fallback. Then rerun with `continueWithAgent=true` or start the fallback
workflow requested by the result.

## GUI Bridge Workflow

Use this when direct MCP Function tools are missing.

### Convert RunLog to Function

1. Open the OOB app.
2. Navigate to Task / Execution History.
3. Open Run Logs.
4. Select the relevant successful run.
5. Inspect the timeline and verify the recorded actions match the user's goal.
6. Choose the UI action that converts the run into a reusable Function.
7. Inspect the generated Function name, description, parameters, and steps.
8. Register/save it only after it looks correct and safe.

### Run a stored Function

1. Open the OOB app.
2. Navigate to Function Library or Command Library.
3. Select the Function by name or description.
4. Inspect details before running.
5. Fill required arguments.
6. Check any displayed guard or warning state.
7. Run only if safe, or after explicit user confirmation for risky actions.
8. Report success/failure and visible result to the user.

### Debug a failed Function

1. Open the Function run detail or latest execution output.
2. Identify the first failed step.
3. If it is coordinate remap failure, prefer Agent/VLM fallback.
4. If it is missing argument failure, ask the user for the missing value.
5. If it is blocked by guard, do not bypass it.
6. If the UI changed, use the latest successful run to create a new Function
   version rather than patching coordinates blindly.

## Agent Bridge Workflow

Use this only when MCP exposes `agent_run` but not direct OmniFlow tools.

Prompt template:

```text
Use OmniFlow inside OOB. Goal: <user goal>.

If a reusable Function already exists, inspect it, run guard, and execute only
the safe local prefix. If live context is required, ask before Agent fallback.
If no Function exists, inspect recent RunLogs, convert the best successful run
into a Function, and register it only after safety inspection.

Return the function_id, guard decision, execution result, and any audit/run id.
```

## Stop Conditions

Stop and ask the user when:

- Guard returns `needs_confirmation`.
- Guard returns `needs_agent` and the next step will operate live UI.
- The Function is about sending messages, payments, purchases, deleting data,
  changing settings, granting permissions, or running shell commands.
- The GUI no longer matches the recorded target.
- The app asks for login, verification code, CAPTCHA, or manual identity check.

Stop permanently when:

- Guard returns `block`.
- The intended action includes reboot, shutdown, fastboot, filesystem format,
  block-device writes, or protected system partition writes.
