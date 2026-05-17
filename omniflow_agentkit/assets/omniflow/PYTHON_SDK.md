# OmniFlow Python Agent Kit

This is the directly callable library for external GUI agents and integration
tests.

It is dependency-free and can be used directly from this repository:

```bash
PYTHONPATH=/path/to/OpenOmniBot python -m omniflow_agentkit pack --no-docs
```

Or install it from the repository:

```bash
pip install /path/to/OpenOmniBot
omniflow-agentkit pack --no-docs
```

For offline acceptance, build a wheel first:

```bash
python3 -m pip wheel --no-deps --no-build-isolation -w /tmp/omniflow-dist /path/to/OpenOmniBot
python3 -m venv /tmp/omniflow-venv
/tmp/omniflow-venv/bin/python -m pip install --no-deps /tmp/omniflow-dist/omniflow_agentkit-0.1.0-py3-none-any.whl
```

## Import

```python
from omniflow_agentkit import OmniFlowAgentKit, OmniFlowMcpClient, RepoProbe

kit = OmniFlowAgentKit()
skill_text = kit.skill()
prompt = kit.agent_prompt("Convert the latest successful RunLog into a reusable Function.")
```

## MCP Calls

```python
from omniflow_agentkit import OmniFlowMcpClient

client = OmniFlowMcpClient(
    endpoint="http://127.0.0.1:8765/mcp",
    token="YOUR_OOB_MCP_TOKEN",
)

tools = client.list_tools()
if client.has_canonical_omniflow():
    recalled = client.recall("open Android Settings")
    function_id = recalled["hit"]["function_id"]
    result = client.call_function(function_id, {})
    explored = client.explore_replay(
        "open network settings",
        package_name="com.android.settings",
        max_steps=3,
        stop_text="Network",
        replay=False,
    )
    ingested = client.ingest_run_log("runlog_install_demo")
if client.has_direct_omniflow():
    functions = client.list_functions()
    direct_function_id = function_id if "function_id" in locals() else "settings_click_path_demo"
    guard = client.guard_check(direct_function_id, {})
    direct = client.run_function(direct_function_id, {})
    converted = client.convert_run_log("runlog_install_demo")
    installed = client.run_function(converted["function_id"], {}, execution_mode="background", confirmed=True)
```

## Repo Probe

```python
from omniflow_agentkit import RepoProbe

report = RepoProbe("/path/to/mobilegpt").run()
print(report.summary())
```

The probe tells you how to inject OmniFlow into a third-party project:

- `direct_mcp`: use `OmniFlowMcpClient`.
- `python_skill_plus_mcp`: inject `OmniFlowAgentKit.skill()` into the agent prompt and use MCP when reachable.
- `json_skill_pack`: export `python -m omniflow_agentkit pack` and pass the JSON to a Node agent.
- `gui_bridge`: use the GUI playbook because the project is an app, not an agent runtime.

## CLI

```bash
python -m omniflow_agentkit pack
python -m omniflow_agentkit prompt "Run the safest saved Function"
python -m omniflow_agentkit probe-repo /tmp/mobilegpt
python -m omniflow_agentkit mcp-recall "open Android Settings" --mcp-url http://127.0.0.1:8765/mcp
python -m omniflow_agentkit mcp-call-function settings_click_path_demo --mcp-url http://127.0.0.1:8765/mcp
python -m omniflow_agentkit mcp-ingest-runlog runlog_install_demo --mcp-url http://127.0.0.1:8765/mcp
python -m omniflow_agentkit mcp-explore-replay "open network settings" --mcp-url http://127.0.0.1:8765/mcp --package-name com.android.settings --stop-text Network --no-replay
python -m omniflow_agentkit mcp-list-functions --mcp-url http://127.0.0.1:8765/mcp
python -m omniflow_agentkit mcp-guard-check settings_click_path_demo --mcp-url http://127.0.0.1:8765/mcp
python -m omniflow_agentkit mcp-run-function settings_click_path_demo --mcp-url http://127.0.0.1:8765/mcp
python -m omniflow_agentkit mcp-convert-runlog runlog_install_demo --mcp-url http://127.0.0.1:8765/mcp
python -m omniflow_agentkit mcp-run-function install_sample_apk_demo --mcp-url http://127.0.0.1:8765/mcp --execution-mode background --confirmed
python -m omniflow_agentkit openai-smoke "Inspect OmniFlow readiness" --repo /tmp/mobilegpt
```

`openai-smoke` uses `OPENAI_API_KEY` when present. It does not print or inspect
the key.
