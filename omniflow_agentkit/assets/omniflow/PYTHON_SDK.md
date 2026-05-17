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
if client.has_direct_omniflow():
    functions = client.list_functions()
    guard = client.guard_check("open_settings_demo", {})
    if guard.get("decision") == "allow":
        result = client.run_function("open_settings_demo", {})
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
python -m omniflow_agentkit openai-smoke "Inspect OmniFlow readiness" --repo /tmp/mobilegpt
```

`openai-smoke` uses `OPENAI_API_KEY` when present. It does not print or inspect
the key.
