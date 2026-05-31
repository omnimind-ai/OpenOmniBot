# OOB Function Backend

This document records the backend ownership rules for OOB reusable Functions.
It is intentionally about core logic only; Flutter cards and display behavior
belong in UI documentation.

## Ownership

`OobFunctionRepository` is the single owner for Function storage:

- register, list, get, delete, and clear Functions
- keep workspace JSON and `OobReusableFunctionStore` in sync
- prefer the local registry when both registry and workspace copies exist,
  because runtime updates are committed there first and workspace JSON is the
  portable mirror
- update UDEG Function references
- bind registered Functions back to source RunLogs
- clear Function-as-tool exposure when the last Function is removed

`OobRunLogReplayService` owns RunLog conversion:

- read finished `InternalRunLogRecord` entries
- compile records through `RunLogReusableFunctionCompiler`
- apply explicit name/id/description overrides
- mirror source RunLogs into the workspace
- delegate all Function persistence to `OobFunctionRepository`

`OobOmniFlowToolkitService` owns the agent/MCP tool facade:

- parse public tool arguments
- expose recall, run, guard, register, update, delete, and clear
- route all Function storage operations through `OobFunctionRepository`
- package RunLog evidence for `update_function`
- apply agent-provided Function patches

`AssistsCoreManager` owns method-channel wiring only:

- call `OobFunctionRepository` for Function register/list/get/delete and direct
  UI run lookup
- call `OobRunLogReplayService` only for RunLog conversion and recent RunLog
  auto-registration
- never implement Function persistence or indexing rules inline

Do not add new Function CRUD paths directly into `AssistsCoreManager`,
`OobOmniFlowToolkitService`, or `OobRunLogReplayService`. Add them to
`OobFunctionRepository`, then call the repository from the facade that needs the
operation.

## Current Shape

```text
Agent/MCP tool surface
  -> OobOmniFlowToolkitService
      -> OobFunctionRepository       # storage/index/source bindings
      -> OobRunLogReplayService      # RunLog -> Function conversion
      -> OobFunctionToolHandler      # execution entry
      -> OobUdegNodeStore            # page/node recall index

Flutter method channel
  -> AssistsCoreManager
      -> OobFunctionRepository       # Function CRUD and direct run lookup
      -> OobRunLogReplayService      # conversion and auto-register only
```

`OobRunLogReplayService` still exposes compatibility methods such as
`registerFunctionSpec`, `listFunctionSpecs`, and `getFunctionSpec`. These are
thin delegates kept for legacy compatibility. Production code should depend on
`OobFunctionRepository` when it needs storage and on `OobRunLogReplayService`
only when it needs RunLog conversion.

## What Not To Merge

Keep these pieces separate:

- `OobFunctionRepository`: persistent Function records and index synchronization
- `RunLogReusableFunctionCompiler`: offline conversion rules from cards to steps
- `OobFunctionToolHandler` and `OmniflowStepExecutor`: runtime execution
- `OobOmniFlowToolkitService.updateFunction`: agent-facing patch application
- builtin skill prompts: agent instructions, not executable policy

Merging these would make it harder to tell whether a change affects storage,
conversion, execution, or agent patching.

## Cleanup Direction

The next useful cleanup is runner extraction:

```text
OobFunctionRunner
  -> guard/check start step
  -> run deterministic prefix
  -> return needs_agent fallback context
  -> record run stats
```

Do this only after repository ownership is stable. Do not introduce a large
service graph just to move methods around; extract only when a class owns a
clear runtime contract.

## Verification

After backend Function changes, run focused tests:

```bash
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.AgentToolRegistryOobFunctionTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentSystemPromptTest' \
  --tests 'cn.com.omnimind.bot.mcp.McpToolDefinitionsTest' \
  --tests 'cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompilerTest' \
  --tests 'cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandlerOmniFlowExecutionTest' \
  --tests 'cn.com.omnimind.bot.agent.tool.handlers.WorkbenchToolHandlerOobFunctionToolsTest' \
  --tests 'cn.com.omnimind.bot.runlog.InternalRunLogStoreTest'
```
