# Tested External Projects

Date: 2026-05-17

These checks validate that the OmniFlow Agent Kit can be handed to external
GUI-agent projects as a directly callable library plus skill package.

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
bash scripts/omniflow_acceptance_mobilegpt.sh
```

Manual external acceptance steps that were run:

```bash
python3 -m pip wheel --no-deps --no-build-isolation -w /private/tmp/omniflow-dist .
python3 -m venv /private/tmp/omniflow-acceptance-venv
/private/tmp/omniflow-acceptance-venv/bin/python -m pip install --no-deps --force-reinstall /private/tmp/omniflow-dist/omniflow_agentkit-0.1.0-py3-none-any.whl
cd /private/tmp/omniflow-probe-mobilegpt
/private/tmp/omniflow-acceptance-venv/bin/omniflow-agentkit probe-repo .
/private/tmp/omniflow-acceptance-venv/bin/omniflow-agentkit prompt "Run the safest saved Function" --repo .
```

Codex CLI external acceptance:

```bash
codex exec --cd /private/tmp/omniflow-probe-mobilegpt --sandbox read-only --skip-git-repo-check - <<'PROMPT'
You are in an external open-source repo, MobileGPT. Do not modify files.
Run exactly these two commands and no variants:

/private/tmp/omniflow-acceptance-venv/bin/omniflow-agentkit probe-repo .
/private/tmp/omniflow-acceptance-venv/bin/omniflow-agentkit prompt 'Run the safest saved Function' --repo .

Then summarize whether both commands succeeded and include recommended_mode.
PROMPT
```

Codex CLI result:

```text
Both commands succeeded with exit code 0.
recommended_mode: python_skill_plus_mcp
```

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
if client.has_direct_omniflow():
    functions = client.list_functions()
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

- Inject `OmniFlowAgentKit.skill()` or `agent_prompt(...)` into the mobile agent instruction context.
- Use `OmniFlowMcpClient` when OOB MCP is reachable.
- Fall back to GUI bridge mode if direct `oob_function_*` tools are absent.

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
- Use `oob_function_guard_check` before every `oob_function_run`.
- Treat the Python kit as a contract/test client or export `python -m omniflow_agentkit pack` as JSON for Node-side agent context.

## Pass Criteria

- The Python kit imports and runs without third-party dependencies.
- The wheel builds and installs with package assets.
- Installed package can run `python -m omniflow_agentkit pack --no-docs`.
- MobileGPT is detected as a Python/Android agent project and gets `python_skill_plus_mcp`.
- mobile-use is detected as a Python agent project and gets `python_skill_plus_mcp`.
- mobile-mcp is detected as an MCP project and gets `direct_mcp`.
