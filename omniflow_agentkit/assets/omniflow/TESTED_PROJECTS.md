# Tested External Projects

Date: 2026-05-17

These checks validate that the OmniFlow Agent Kit can be handed to external
GUI-agent projects as a directly callable library plus skill package. The public
MCP surface includes the canonical OmniFlow tools:

```text
omniflow.recall
omniflow.call_function
omniflow.ingest_run_log
omniflow.explore_replay
```

The OOB MCP server and Agent Kit also expose direct Function/RunLog tools for
debugging and deterministic replay audits:

```text
oob_function_list/get/register/guard_check/run
oob_run_log_list/get/convert
```

## Test Commands

```bash
python3 -m unittest tests/test_omniflow_agentkit.py
python3 -m omniflow_agentkit pack --no-docs
python3 -m omniflow_agentkit prompt "Convert latest successful RunLog into a reusable Function"
python3 -m pip install --no-deps --no-build-isolation --target /private/tmp/omniflow-agentkit-install-test2 .
PYTHONPATH=/private/tmp/omniflow-agentkit-install-test2 python3 -m omniflow_agentkit pack --no-docs
```

External acceptance command:

```bash
export OMNIFLOW_MCP_URL=http://127.0.0.1:8899/mcp
export OMNIFLOW_MCP_TOKEN=<token-from-oob-settings>
bash scripts/omniflow_acceptance_mobilegpt.sh
bash scripts/omniflow_acceptance_all_guiagents.sh
```

Manual external acceptance steps:

```bash
python3 -m pip wheel --no-deps --no-build-isolation -w /private/tmp/omniflow-acceptance/dist .
python3 -m venv /private/tmp/omniflow-acceptance/venv
/private/tmp/omniflow-acceptance/venv/bin/python -m pip install --no-deps --force-reinstall /private/tmp/omniflow-acceptance/dist/omniflow_agentkit-0.1.0-py3-none-any.whl
cd /private/tmp/omniflow-probe-mobilegpt
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit probe-repo .
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit prompt "Run the safest saved Function" --repo .
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-recall "open Android Settings"
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-call-function settings_click_path_demo
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-ingest-runlog runlog_install_demo
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-explore-replay "open network settings" --package-name com.android.settings --stop-text Network --no-replay
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-call-function install_sample_apk_demo
```

The MCP commands require a real OOB MCP endpoint. Set `OMNIFLOW_MCP_URL` or
`OOB_MCP_URL`, and set `OMNIFLOW_MCP_TOKEN` or `OOB_MCP_TOKEN` when token auth
is enabled in OOB settings.

The broader acceptance script repeats the same install and trigger sequence from
these external repo directories:

```text
/private/tmp/omniflow-probe-mobilegpt
/private/tmp/omniflow-probe-mobile-use
/private/tmp/omniflow-probe-mobile-mcp
```

Codex CLI external acceptance:

```bash
codex exec --cd /private/tmp/omniflow-probe-mobilegpt --sandbox danger-full-access --skip-git-repo-check - <<'PROMPT'
You are in an external open-source repo, MobileGPT. Do not modify files.
Run exactly these commands and no variants:

/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit probe-repo .
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-recall "open Android Settings and click through the demo path"
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-call-function settings_click_path_demo
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-ingest-runlog runlog_install_demo
/private/tmp/omniflow-acceptance/venv/bin/omniflow-agentkit mcp-call-function install_sample_apk_demo

Then summarize whether all commands succeeded, include recommended_mode if present,
and include the function_id, run_id, runner_duration_ms, and click step count.
PROMPT
```

Codex CLI result:

```text
All commands succeeded with exit code 0.
recommended_mode: python_skill_plus_mcp
function_id: settings_click_path_demo
run_id: <real-run-id>
registered function_id: install_sample_apk_demo
replay run_id: <real-run-id>
```

`read-only` and `workspace-write` Codex sandboxes were also checked; both
blocked the loopback MCP call before `omniflow.call_function`. The successful
Codex simulation therefore uses `danger-full-access` while instructing the
agent not to modify files.

OpenAI skill smoke test command:

```bash
python3 -m omniflow_agentkit openai-smoke "Verify OmniFlow skill activation on MobileGPT" --repo /private/tmp/omniflow-probe-mobilegpt
```

Result: skipped because `OPENAI_API_KEY` was not configured in the shell
environment. The library does not inspect local app config or stored secrets.

## Projects

### MobileGPT

Repository: `https://github.com/hchoi256/mobilegpt`

Commit tested:

```text
7945048e6c05849aff1633744cbdf653c58bece2
```

Probe result:

```json
{
  "detected_stack": ["python", "android"],
  "recommended_mode": "python_skill_plus_mcp",
  "integration_entrypoint": "OmniFlowAgentKit.agent_prompt"
}
```

How MobileGPT should use OmniFlow:

```python
from omniflow_agentkit import OmniFlowAgentKit, OmniFlowMcpClient

kit = OmniFlowAgentKit()
system_or_developer_prompt = kit.agent_prompt(
    "Use OmniFlow to inspect reusable phone-task Functions and run the safest available Function."
)

client = OmniFlowMcpClient(endpoint="http://127.0.0.1:8765/mcp", token="...")
if client.has_canonical_omniflow():
    recalled = client.recall("open Android Settings")
    function_id = recalled["hit"]["function_id"]
    result = client.call_function(function_id, {})
```

Acceptance result:

```text
canonical_recall=ok
canonical_hit_function_id=settings_click_path_demo
canonical_call_function=ok
canonical_run_id=<real-run-id>
canonical_click_step_count=4
canonical_ingest_runlog=ok
canonical_ingested_function_id=install_sample_apk_demo
canonical_call_ingested_function=ok
ingested_function_run_id=<real-run-id>
omniflow_mobilegpt_acceptance=ok
```

### mobile-use

Repository: `https://github.com/MadeAgents/mobile-use`

Commit tested:

```text
1c01bef9ce3bf8da71eb5712b869bc6dd3ed02b0
```

Probe result:

```json
{
  "detected_stack": ["python"],
  "recommended_mode": "python_skill_plus_mcp",
  "integration_entrypoint": "OmniFlowAgentKit.agent_prompt"
}
```

How mobile-use should use OmniFlow:

- Inject `OmniFlowAgentKit.skill()` or `agent_prompt(...)` into the mobile
  agent instruction context.
- Use `OmniFlowMcpClient` when OOB MCP is reachable.
- Fall back to GUI bridge mode if canonical `omniflow.*` tools are absent.

Acceptance result:

```text
canonical_recall=ok
canonical_call_function=ok
canonical_run_id=<real-run-id>
canonical_ingest_runlog=ok
canonical_call_ingested_function=ok
ingested_function_run_id=<real-run-id>
omniflow_mobile_use_acceptance=ok
```

### mobile-mcp

Repository: `https://github.com/mobile-next/mobile-mcp`

Commit tested:

```text
8a2947a93cb1b660479aee8e354457623396aa6f
```

Probe result:

```json
{
  "detected_stack": ["node", "mcp"],
  "recommended_mode": "direct_mcp",
  "integration_entrypoint": "OmniFlowMcpClient"
}
```

How mobile-mcp should use OmniFlow:

- Prefer direct MCP mode.
- Discover with `tools/list`.
- Use `omniflow.recall`, `omniflow.call_function`, and
  `omniflow.ingest_run_log` for replay and RunLog registration.
- Treat the Python kit as a contract/test client or export
  `python -m omniflow_agentkit pack` as JSON for Node-side agent context.

Acceptance result:

```text
canonical_recall=ok
canonical_call_function=ok
canonical_run_id=<real-run-id>
canonical_ingest_runlog=ok
canonical_call_ingested_function=ok
ingested_function_run_id=<real-run-id>
omniflow_mobile_mcp_acceptance=ok
```

## Pass Criteria

- The Python kit imports and runs without third-party dependencies.
- The wheel builds and installs with package assets.
- Installed package can run `python -m omniflow_agentkit pack --no-docs`.
- MobileGPT is detected as a Python/Android agent project and gets
  `python_skill_plus_mcp`.
- mobile-use is detected as a Python agent project and gets
  `python_skill_plus_mcp`.
- mobile-mcp is detected as an MCP project and gets `direct_mcp`.
- Each tested external project can recall the existing
  `settings_click_path_demo` Function through MCP.
- Each tested external project can run that Function through
  `omniflow.call_function` and receives a real run id.
- Each tested external project can ingest `runlog_install_demo` into
  `install_sample_apk_demo`.
- Each tested external project can run the ingested Function through
  `omniflow.call_function` and receives a real run id.
