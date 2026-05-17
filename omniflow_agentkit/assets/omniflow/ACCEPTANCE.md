# OmniFlow Agent Kit Acceptance

The kit is acceptable when an external GUI agent can complete these checks using
only the shipped docs, skill, and canonical MCP tools.

## Documentation Checks

- The agent can explain what OmniFlow is and when to activate it.
- The agent can identify available access mode by reading `tools/list` and the UI.
- The agent can find the Function schema.
- The agent can find the MCP tool contract.
- The agent can find the guard decision rules.
- The agent can find fallback instructions when canonical MCP tools are absent.

## Canonical MCP Checks

When the host implements the OOB OmniFlow MCP contract:

- `tools/list` exposes exactly the replay activation surface:
  `omniflow.recall`, `omniflow.call_function`, and
  `omniflow.ingest_run_log`.
- `omniflow.recall` returns a direct no-argument hit or ranked candidates with
  `inputSchema`.
- `omniflow.call_function` executes only an explicit agent-selected
  `function_id` and returns structured `success/fallback/control` fields.
- `omniflow.ingest_run_log` registers a successful RunLog as a reusable Function
  or rejects it with a reason.
- The same ingested Function can be replayed through `omniflow.call_function`.
- Legacy OOB direct names are absent from public discovery and from the Python
  Agent Kit CLI.

## GUI Bridge Checks

When canonical MCP tools are absent:

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

1. Recall the sample `settings_click_path_demo` Function with
   `omniflow.recall`.
2. Run it through `omniflow.call_function`.
3. Confirm the result includes 7 step results and 4 `click` steps.
4. Ingest `runlog_install_demo` through `omniflow.ingest_run_log`.
5. Run the returned Function id through `omniflow.call_function`.
6. Report `function_id=settings_click_path_demo`, the run id, runner duration,
   and the slowest click step.

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
- From the external project directory, Python import succeeds.
- From the external project directory, `omniflow-agentkit probe-repo .`
  succeeds.
- From the external project directory, `omniflow-agentkit prompt ... --repo .`
  succeeds.
- From the external project directory, `omniflow-agentkit mcp-recall ...` finds
  `settings_click_path_demo`.
- From the external project directory,
  `omniflow-agentkit mcp-call-function settings_click_path_demo ...` triggers
  that Function and returns a run id, runner timing, 7 steps, and 4 click steps.
- From the external project directory,
  `omniflow-agentkit mcp-ingest-runlog runlog_install_demo ...` registers a
  Function converted from a RunLog.
- From the external project directory,
  `omniflow-agentkit mcp-call-function install_sample_apk_demo ...` runs the
  ingested Function and returns a run id.
- Codex CLI runs only `probe-repo`, `mcp-recall`, `mcp-call-function`,
  `mcp-ingest-runlog`, and the second `mcp-call-function` from the external
  project directory and reports success, including the Function id, run id,
  runner duration, and click count.
- `recommended_mode` for MobileGPT is `python_skill_plus_mcp`.

For the broader check, the same installed wheel and MCP trigger path must also
work from `mobile-use` and `mobile-mcp`. Passing output includes:

```text
omniflow_mobilegpt_acceptance=ok
omniflow_mobile_use_acceptance=ok
omniflow_mobile_mcp_acceptance=ok
omniflow_all_guiagents_acceptance=ok
```

The expected RunLog registration and replay markers are:

```text
canonical_recall=ok
canonical_hit_function_id=settings_click_path_demo
canonical_call_function=ok
canonical_run_id=mock-run-settings-click-path-demo
canonical_click_step_count=4
canonical_ingest_runlog=ok
canonical_ingested_function_id=install_sample_apk_demo
canonical_call_ingested_function=ok
ingested_function_run_id=mock-run-install-sample-apk-demo
```
