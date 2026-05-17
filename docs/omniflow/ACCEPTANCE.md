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

- `tools/list` exposes `omniflow.recall` and `omniflow.call_function`.
- `omniflow.recall` returns a direct no-argument hit or ranked candidates with
  `inputSchema`.
- `omniflow.call_function` executes only an explicit agent-selected
  `function_id` and returns structured `success/fallback/control` fields.
- `omniflow.ingest_run_log` registers a successful RunLog as a reusable Function
  or rejects it with a reason.
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

## External Project Acceptance

The real acceptance bar is not "works from this repository." It must work from
another open-source project directory after installation.

Required external check:

```bash
bash scripts/omniflow_acceptance_mobilegpt.sh
```

Broader external GUI-agent check:

```bash
bash scripts/omniflow_acceptance_all_guiagents.sh
```

Run the Codex CLI portion explicitly with:

```bash
OMNIFLOW_RUN_CODEX=1 bash scripts/omniflow_acceptance_mobilegpt.sh
```

Codex CLI must be allowed to connect to the local MCP endpoint. In this
acceptance script that means `--sandbox danger-full-access` plus a prompt that
forbids file changes. `read-only` and `workspace-write` sandboxes can block
loopback HTTP and fail before the Function call reaches MCP.

Pass criteria:

- The script clones or reuses MobileGPT under `/private/tmp`.
- It builds an `omniflow-agentkit` wheel.
- It creates a clean venv outside this repo.
- It installs the wheel into that venv.
- From the MobileGPT directory, Python import succeeds.
- From the MobileGPT directory, `omniflow-agentkit probe-repo .` succeeds.
- From the MobileGPT directory, `omniflow-agentkit prompt ... --repo .` succeeds.
- From the MobileGPT directory, `omniflow-agentkit mcp-recall ...` finds
  `open_settings_demo`.
- From the MobileGPT directory, `omniflow-agentkit mcp-call-function open_settings_demo ...`
  triggers that Function and returns a run id.
- From the MobileGPT directory, `omniflow-agentkit mcp-ingest-runlog runlog_install_demo ...`
  registers a Function converted from a RunLog.
- From the MobileGPT directory, `omniflow-agentkit mcp-list-functions ...`
  discovers an existing Function exposed by the host MCP server.
- From the MobileGPT directory, `omniflow-agentkit mcp-guard-check open_settings_demo ...`
  returns `decision=allow`.
- From the MobileGPT directory, `omniflow-agentkit mcp-run-function open_settings_demo ...`
  triggers that existing Function and returns a run id.
- From the MobileGPT directory, `omniflow-agentkit mcp-convert-runlog runlog_install_demo --register ...`
  registers a Function converted from a RunLog.
- From the MobileGPT directory, `omniflow-agentkit mcp-run-function install_sample_apk_demo --background ...`
  enqueues the registered install Function and returns a background run id.
- Codex CLI runs `probe-repo`, `mcp-convert-runlog`, and background
  `mcp-run-function` from the MobileGPT directory and reports success,
  including the registered Function id and background run id.
- `recommended_mode` for MobileGPT is `python_skill_plus_mcp`.

For the broader check, the same installed wheel and MCP trigger path must also
work from `mobile-use` and `mobile-mcp`. Passing output includes:

```text
omniflow_mobilegpt_acceptance=ok
omniflow_mobile_use_acceptance=ok
omniflow_mobile_mcp_acceptance=ok
omniflow_all_guiagents_acceptance=ok
```

The expected RunLog registration and background execution markers are:

```text
runlog_function_register=ok
registered_function_id=install_sample_apk_demo
background_install_execution=ok
background_run_id=mock-bg-install-run
```
