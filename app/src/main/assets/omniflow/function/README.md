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
- route deterministic Function execution through `OobFunctionRunner`
- route Function registration normalization through `OobFunctionSpecBuilder`
- route `update_function` evidence analysis and patches through
  `OobFunctionUpdateService`

`OobFunctionSpecBuilder` owns simple Function spec construction:

- normalize simple register requests into canonical Function specs
- capture current page source context when a simple registration needs it
- normalize inserted steps for `update_function`
- compute execution capability counts from canonical steps

`OobFunctionUpdateService` owns the `update_function` contract:

- package Function + RunLog evidence for agent analysis
- persist agent analysis in Function metadata
- apply safe metadata, checker, target-repair, insert-step, and delete-step
  patches
- keep optional checker candidates as metadata/checker rules instead of
  mandatory execution steps

`OobFunctionRunner` owns runtime execution startup:

- load the Function spec from `OobFunctionRepository`
- validate and materialize runtime arguments
- create the local `OobFunctionToolHandler`
- pass resume/fallback controls into the replay handler
- merge execution timing into the returned payload

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
      -> OobFunctionSpecBuilder      # simple register/insert-step normalization
      -> OobFunctionUpdateService    # update_function evidence and patches
      -> OobFunctionRunner           # load/materialize/execute Functions
          -> OobFunctionToolHandler  # deterministic replay and agent handoff
      -> OobRunLogReplayService      # RunLog -> Function conversion
      -> OobUdegNodeStore            # page/node recall index

Flutter method channel
  -> AssistsCoreManager
      -> OobFunctionRepository       # Function CRUD and direct run lookup
      -> OobRunLogReplayService      # conversion and auto-register only
```

`OobRunLogReplayService` does not expose Function CRUD. New and legacy callers
must use `OobFunctionRepository` for storage and use `OobRunLogReplayService`
only for `convertRunLog` and `autoRegisterRecentRunLogs`.

## What Not To Merge

Keep these pieces separate:

- `OobFunctionRepository`: persistent Function records and index synchronization
- `OobFunctionSpecBuilder`: simple public input -> canonical Function spec
- `OobFunctionUpdateService`: RunLog evidence packaging and Function patching
- `RunLogReusableFunctionCompiler`: offline conversion rules from cards to steps
- `OobFunctionRunner`: Function loading, materialization, and execution timing
- `OobFunctionToolHandler` and `OmniflowStepExecutor`: runtime step execution
- `OobOmniFlowToolkitService`: public tool facade and response shaping
- builtin skill prompts: agent instructions, not executable policy

Merging these would make it harder to tell whether a change affects storage,
conversion, execution, or agent patching.

## Cleanup Direction

`OobOmniFlowToolkitService` should stay a facade. New Function behavior should
land in one of the owned services above before adding more private helper blocks
to the toolkit. Keep `OobFunctionRunner` intentionally small: guard decisions,
public response shaping, and run-stat recording remain in the toolkit unless
they become reusable outside that facade.

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
