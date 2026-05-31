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
- rely on `RunLogReplayStepNoiseNormalizer` for compiled-step noise cleanup
- apply explicit name/id/description overrides
- mirror source RunLogs into the workspace
- delegate all Function persistence to `OobFunctionRepository`

`OobOmniFlowToolkitService` owns the agent/MCP tool facade:

- parse public tool arguments
- expose recall, run, guard, register, update, delete, and clear
- use `OobFunctionCallTiming` for Function call timing payloads
- route all Function storage operations through `OobFunctionRepository`
- route Function recall and direct-hit decisions through
  `OobFunctionRecallService`
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

- persist agent analysis in Function metadata
- apply safe metadata, target-repair, insert-step, and delete-step patches
- delegate raw patch op and natural-language repair intent normalization to
  `OobFunctionUpdateIntentParser`
- delegate Function + RunLog evidence context and agent prompt packaging to
  `OobFunctionRunLogEvidencePackager`
- delegate checker rule and optional checker candidate normalization to
  `OobFunctionCheckerPatchService`
- delegate source XML target matching for repair patches to
  `OobFunctionTargetSourceMatcher`

`OobFunctionRunLogEvidencePackager` owns update evidence packaging:

- build the read-only Function + RunLog analysis context for `update_function`
- generate the built-in agent prompt that tells the agent how to mark required
  actions, optional checkers, noise, duplicate steps, failed actions, and
  success evidence
- keep evidence-analysis prompt contracts outside Function mutation code
- never save Functions or apply patches

`OobFunctionUpdateIntentParser` owns update intent normalization:

- normalize patch fields like `ops`, `operations`, `repairs`, and
  `replace_target` into explicit update operations
- infer simple target-repair operations from user instructions such as
  `应该点「外卖」而不是点「美食」`
- classify replace-target and structural operations for update-mode decisions
- never apply an operation or mutate a Function spec

`OobFunctionCheckerPatchService` owns checker metadata patching:

- normalize agent-provided checker rules from `update_function`
- convert optional cleanup/noise annotations into metadata checker rules
- keep ad, popup, permission, resolver, keyboard, and package mismatch handling
  as conditional checker metadata instead of mandatory execution steps
- deduplicate checker rules and `agent_reuse.checker_assets`

`OobFunctionTargetSourceMatcher` owns target repair source matching:

- extract source XML from a step's recorded `source_context`
- parse source XML safely for target repair only
- score candidate nodes by text, content-desc, resource id, visibility, and
  clickability
- return coordinates, bounds, and selector hints for `replace_target` patches

`OobFunctionRecallService` owns recall policy:

- read current page/package context for Function recall
- ask `OobUdegNodeStore` for page/node matches
- rank attached Function capabilities against the agent goal
- decide whether a no-argument Function is a strict direct hit
- compact recall payloads for normal agent use while preserving debug mode

`OobFunctionRunPolicy` owns run-time policy:

- guard Function steps before execution
- classify block, confirmation, agent-needed, and allow decisions
- build agent fallback context when deterministic replay fails
- generate the resume instruction for `oob_function_run`

`OobFunctionRunner` owns runtime execution startup:

- load the Function spec from `OobFunctionRepository`
- validate and materialize runtime arguments
- create the local `OobFunctionToolHandler`
- pass resume/fallback controls into the replay handler
- merge execution timing into the returned payload

`OobFunctionCallTiming` owns toolkit call timing payloads:

- measure guard and execution phases for Function calls
- merge call-level timing into runner timing without changing run results
- keep timing payload shape outside the public tool facade

`OobFunctionGraphStepRunner` owns local graph/UTG execution inside replay:

- select graph path edges for `go_to_node` and `click_node`
- lower graph edges into primitive OmniFlow local-action steps
- execute the primitive path with the same checker rules as normal replay
- report path-level success, failure, and per-edge step results

`OobFunctionEntryPackageGuard` owns pre-replay app restoration:

- infer the Function entry package from explicit `open_app` steps or source context
- skip restoration when replay already starts with `open_app`
- launch the expected package when the foreground app drifted before replay
- keep package recovery outside the main step loop

`OobFunctionFrontendSessionController` owns transient replay UI state:

- start, update, and finish the local OmniFlow execution overlay
- wire user stop/complete requests into the replay loop
- keep main-thread UI calls outside the deterministic step executor
- skip nested Function calls so only the top-level replay owns the overlay

`OobFunctionSourceAlignmentController` owns source-page alignment during replay:

- compare the current page vector against the pending source window
- skip already-satisfied replay steps when the current page matches a later step
- produce the alignment-miss failure payload when replay is on the wrong page
- keep vector matching and skip/fail policy outside the main step loop

`OobFunctionAgentFallbackController` owns agent-facing fallback context:

- build fallback prompts from the failed step, materialized args, and recovery
  snapshot
- refetch the current page after deterministic replay failures
- run the optional `vlm_task` fallback for remappable UI-action failures
- keep agent recovery text and VLM tool-call shaping outside the replay loop

`OobFunctionCallRequestResolver` owns replay/tool-call argument compatibility:

- extract executable args from current Function steps and older RunLog cards
- resolve `call_tool` targets, nested Function ids, and delegated tool args
- strip Function/call-tool metadata from forwarded argument payloads
- keep recorded argument-shape compatibility outside the runtime replay loop

`OobFunctionRunResultBuilder` owns replay result payloads:

- build stable per-step failure records for guard, delegation, and replay errors
- build failed and completed Function run payloads
- merge runner timing and phase timings into existing failure payloads
- keep output schema and timing accounting outside the runtime replay loop

`OobFunctionNestedCallCardPresenter` owns nested Function tool-card payloads:

- create stable card ids for nested Function calls
- format running/completed summaries for reusable Function cards
- shape UI-facing args/result preview payloads for nested replay
- keep card text and JSON presentation out of nested Function execution

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
          -> OobFunctionUpdateIntentParser # patch/instruction -> update ops
          -> OobFunctionRunLogEvidencePackager # Function + RunLog agent context
          -> OobFunctionCheckerPatchService # checker metadata normalization
          -> OobFunctionTargetSourceMatcher # source XML repair matching
      -> OobFunctionRecallService    # page/node recall and direct-hit policy
          -> OobUdegNodeStore        # page/node recall index
      -> OobFunctionRunPolicy        # guard and fallback handoff
      -> OobFunctionCallTiming       # call-level timing merge
      -> OobFunctionRunner           # load/materialize/execute Functions
          -> OobFunctionToolHandler  # deterministic replay and agent handoff
              -> OobFunctionFrontendSessionController # replay overlay/session
              -> OobFunctionSourceAlignmentController # page-vector skip/fail
              -> OobFunctionAgentFallbackController # recovery prompt/VLM fallback
              -> OobFunctionCallRequestResolver # replay/call_tool args
              -> OobFunctionRunResultBuilder # run result/timing payloads
              -> OobFunctionNestedCallCardPresenter # nested Function card payloads
              -> OobFunctionEntryPackageGuard # pre-replay app restoration
              -> OobFunctionGraphStepRunner # graph/UTG path lowering
      -> OobRunLogReplayService      # RunLog -> Function conversion
          -> RunLogReusableFunctionCompiler # cards -> reusable Function spec
              -> RunLogReplayStepNoiseNormalizer # compiled step noise cleanup

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
- `OobFunctionUpdateService`: Function patching and evidence analysis persistence
- `OobFunctionUpdateIntentParser`: raw patch op and instruction intent
  normalization
- `OobFunctionRunLogEvidencePackager`: Function + RunLog evidence context and
  agent prompt packaging
- `OobFunctionCheckerPatchService`: checker rule and checker asset metadata
  normalization
- `OobFunctionTargetSourceMatcher`: source XML parsing and node scoring for
  target-repair patches
- `OobFunctionRecallService`: page/node recall, ranking, direct-hit policy, and
  compact recall payload shaping
- `OobFunctionRunPolicy`: pre-run guard and failed-run agent fallback handoff
- `OobFunctionCallTiming`: Function call timing payload construction
- `RunLogReusableFunctionCompiler`: offline conversion rules from cards to steps
- `RunLogReplayStepNoiseNormalizer`: repeated input and redundant compiled-step
  cleanup after card-to-step conversion
- `OobFunctionRunner`: Function loading, materialization, and execution timing
- `OobFunctionToolHandler` and `OmniflowStepExecutor`: runtime step execution
- `OobFunctionFrontendSessionController`: top-level replay overlay lifecycle
  and stop signal handling
- `OobFunctionSourceAlignmentController`: current-page/source-page alignment
  policy, replay skip results, and alignment-miss failure payloads
- `OobFunctionAgentFallbackController`: failed-step recovery snapshots,
  fallback prompts, and optional VLM fallback calls
- `OobFunctionCallRequestResolver`: replay step args, `call_tool` target
  resolution, nested Function argument extraction, and metadata stripping
- `OobFunctionRunResultBuilder`: stable run payload schema, failure step
  records, and runner timing/phase accounting
- `OobFunctionNestedCallCardPresenter`: nested Function tool-card ids,
  summaries, args payloads, and result preview payloads
- `OobFunctionEntryPackageGuard`: pre-replay app/package restoration
- `OobFunctionGraphStepRunner`: graph/UTG path selection and primitive action
  lowering inside runtime replay
- `OobOmniFlowToolkitService`: public tool facade and response shaping
- builtin skill prompts: agent instructions, not executable policy

Merging these would make it harder to tell whether a change affects storage,
conversion, execution, or agent patching.

## Cleanup Direction

`OobOmniFlowToolkitService` should stay a facade. New Function behavior should
land in one of the owned services above before adding more private helper blocks
to the toolkit. Keep `OobFunctionRunner` intentionally small: it starts
execution but does not own guard policy, fallback prompts, or patching.

When changing run-time safety or recovery behavior, update
`OobFunctionRunPolicy` first and keep the public response contract stable at the
tool facade. Do not add ad hoc guard, retry, or agent prompt helpers back into
`OobOmniFlowToolkitService`.

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
