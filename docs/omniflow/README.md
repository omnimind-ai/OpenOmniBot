# OmniFlow Agent Kit

Status: v0.1 external agent package

OmniFlow is the reusable execution library for OOB. It turns successful device
work into reusable Functions, checks them with guard policy, replays safe local
steps, and falls back to an Agent when live context is required.

This package is intentionally documentation-first. A capable GUI agent should be
able to read these files, discover the available runtime surface, and start
using OmniFlow without private repo knowledge.

## What To Ship

Ship this directory as the external OmniFlow kit:

- `README.md`: product boundary, modes, and quick start.
- `skills/guiagent-omniflow/SKILL.md`: the primary instruction file for GUI agents.
- `MCP_CONTRACT.md`: direct MCP tool/resource/prompt contract.
- `FUNCTION_SPEC.md`: reusable Function schema and executor rules.
- `canonical-actions.md`: OOB Function action vocabulary and legacy alias migration.
- `update-function.md`: `update_function` patch contract and repair/enhance flow.
- `checkers.md`: runtime checker rule cookbook and whitelist.
- `cleanup-rules.md`: deterministic RunLog cleanup and merge rules.
- `manual-runlog-recording.md`: loss-intolerant manual RunLog recording policy.
- `oob-function-architecture.md`: RunLog/OOB Function/UDEG runtime ownership map.
- `GUI_AGENT_PLAYBOOK.md`: step-by-step execution playbook and fallback paths.
- `ACCEPTANCE.md`: verification checklist for a host app or external agent.
- `PYTHON_SDK.md`: directly callable Python library usage.
- `TESTED_PROJECTS.md`: tested external GUI-agent integrations.
- `samples/`: small Function and MCP call examples.

For App-bundled distribution, mirror the skill under:

```text
app/src/main/assets/omniflow/skills/guiagent-omniflow/SKILL.md
```

## Runtime Boundary

OmniFlow owns:

- RunLog discovery and conversion.
- Function registration, listing, lookup, and deletion.
- Function materialization from call arguments.
- Guard preflight and per-step guard decisions.
- Deterministic local replay.
- Agent fallback prompts for perception, browser, search, memory, and other live-context steps.
- Function run audit logs.

OmniFlow does not require a separate `SKILL.md` runtime wrapper. The Skill is
only an instruction and workflow package. The trusted execution boundary remains
the host app runtime, MCP server, guard policy, and audited runner.

## External Access Modes

### Mode A: Direct MCP

Use this when `tools/list` contains the canonical OmniFlow tools. This is the
preferred stable external interface.

Minimum direct tools:

```text
omniflow.recall
omniflow.call_function
omniflow.ingest_run_log
```

Read `MCP_CONTRACT.md` before calling these tools.

### Mode B: Current GUI Bridge

Use this when direct MCP Function tools are not present yet, but the OOB app UI
is available. The GUI agent should operate the App screens:

```text
Execution History / Run Logs -> Run details -> Convert to reusable Function
Function Library / Command Library -> Inspect -> Run
```

Read `GUI_AGENT_PLAYBOOK.md` for exact visual workflow expectations.

### Mode C: Agent Bridge

Use this only when the MCP server exposes `agent_run` but not direct Function
tools. Submit a targeted prompt to the in-app Agent and ask it to use the
OmniFlow / RunLog / Function UI or native channels. Treat this as a bridge, not
as the long-term API.

## Activation Rules For GUI Agents

Activate OmniFlow when the user asks to:

- Repeat a previous phone task.
- Save a successful run as a reusable action.
- Reuse a task from execution history.
- Inspect, debug, or replay a recorded run.
- Convert RunLog into a reusable Function.
- Run a stored Function or command.
- Check whether a reusable Function is safe to run.

Do not activate OmniFlow for one-off general chat, static document writing, or
Workbench Project display creation unless the user is explicitly asking to reuse
or replay execution behavior.

## Safety Rules

Every execution path must perform guard checks. A GUI agent must not bypass a
guard result by clicking through risky UI silently.

Guard decisions:

```text
allow
needs_agent
needs_confirmation
block
```

Default policy:

- `allow`: canonical local UI actions such as click, input_text, swipe,
  open_app, press_key, and finished. Legacy names are accepted only at ingestion.
- `needs_agent`: browser, web search, memory, VLM-only, runlog lookup, workbench query/list.
- `needs_confirmation`: shell exec, settings writes, package force-stop, permission changes.
- `block`: reboot, shutdown, fastboot, block-device writes, filesystem format, protected system partition writes.

## Quick Start For An External GUI Agent

1. Read `skills/guiagent-omniflow/SKILL.md`.
2. If using MCP, call `tools/list`.
3. If `omniflow.recall` and `omniflow.call_function` exist, use direct MCP mode.
4. If direct tools are missing, open the OOB app and use GUI bridge mode.
5. Always inspect a Function before running it.
6. Always call guard or visually inspect guard state before execution.
7. Run deterministic local steps only when allowed.
8. Ask the user before confirmation-required actions.
9. Stop on blocked actions.
10. Record the Function run result or audit id in the final response.

## Current Code Map

- Native RunLog store: `baselib/src/main/java/cn/com/omnimind/baselib/runlog/InternalRunLogStore.kt`
- Native Function store: `baselib/src/main/java/cn/com/omnimind/baselib/runlog/OobReusableFunctionStore.kt`
- Workspace Function store: `app/src/main/java/cn/com/omnimind/bot/workbench/WorkspaceFunctionStore.kt`
- Native compiler/policy/executor: `app/src/main/java/cn/com/omnimind/bot/runlog/`
- Canonical action parser: `app/src/main/java/cn/com/omnimind/bot/runlog/OobActionCodec.kt`
- Shared step role classifier: `app/src/main/java/cn/com/omnimind/bot/runlog/OobStepRoleClassifier.kt`
- Agent Function runner adapter: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
- Flutter RunLog UI/converter: `ui/lib/features/task/run_log/` and `ui/lib/features/task/pages/execution_history/`
- Existing RunLog contract: `app/src/main/assets/omniflow/runlog/references/runlog-contract.md`
