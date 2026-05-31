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

`RunLogReusableFunctionCompiler` owns RunLog card-to-Function assembly:

- filter successful replayable cards before conversion
- coordinate startup bridge cleanup, single-card step compilation, step noise
  cleanup, and parameterization
- assemble top-level reusable Function fields and metadata
- delegate deterministic parameter/action compatibility output to
  `RunLogReusableFunctionParameterizer`

`RunLogReplayStepCompiler` owns single-card replay semantics:

- convert one RunLog card into one canonical execution step or skip it
- decide whether the emitted step uses `executor=omniflow`, `executor=tool`, or
  `executor=agent`
- generate concise step titles from recorded tool/action arguments
- build source-context repair data for coordinate remapping
- keep card action translation out of top-level Function assembly

`RunLogStartupBridgeCleaner` owns startup/launcher bridge cleanup:

- drop transient startup cards that only bridge from launcher to the target app
- normalize injected first `open_app` steps when the RunLog already contains a
  concrete launch action
- keep launch cleanup separate from card action semantics and replay execution

`RunLogCardAccessors` owns RunLog card field extraction:

- coerce card fields, headers, tool calls, args, results, and observations into
  stable Kotlin map values
- centralize JSON-safe conversion helpers used by conversion and cleanup code
- prevent duplicate ad hoc card parsing across compiler services

`RunLogReusableFunctionParameterizer` owns reusable Function parameterization:

- infer deterministic `input_text` runtime parameters from compiled steps
- build the canonical JSON schema exposed through `parameters`
- build the legacy `actions` compatibility list from compiled execution steps
- record parameter binding metadata under `metadata.oob_parameter_bindings`
- keep canonical action compatibility separate from RunLog card filtering

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

- orchestrate Function loading, update mode decisions, dry-run/save behavior,
  and returned tool payloads
- delegate raw patch op and natural-language repair intent normalization to
  `OobFunctionUpdateIntentParser`
- delegate metadata, step label, evidence, checker, parameter, agent reuse, and
  audit patches to `OobFunctionMetadataPatchApplier`
- delegate target-repair, insert-step, delete-step, and execution step
  reindexing to `OobFunctionStructuralPatchApplier`
- delegate Function + RunLog evidence context and agent prompt packaging to
  `OobFunctionRunLogEvidencePackager`

`OobFunctionMetadataPatchApplier` owns non-structural `update_function` patches:

- persist agent RunLog analysis under `metadata.oob_function_evidence`
- apply Function name/description, step title/summary/description, parameters,
  agent reuse hints, metadata, checker rules, and update audit metadata
- delegate checker rule and optional checker candidate normalization to
  `OobFunctionCheckerPatchService`
- never insert/delete/reorder execution steps or retarget a recorded action

`OobFunctionStructuralPatchApplier` owns structural `update_function` patches:

- retarget an existing action when the agent says the Function clicked or used
  the wrong target
- insert and delete execution steps only when structural changes are explicitly
  allowed
- normalize inserted simple steps through `OobFunctionSpecBuilder`
- reindex execution steps and recompute execution capability counts after
  structural edits
- delegate source XML target matching for repair patches to
  `OobFunctionTargetSourceMatcher`
- never persist metadata evidence or save Functions

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

`OobFunctionJson` owns mechanical update payload coercion:

- normalize public tool payload maps/lists into stable Kotlin value shapes
- build mutable JSON-compatible maps and lists for Function patch services
- provide shared scalar coercion helpers used by `update_function` services
- stay policy-free; Function behavior rules belong in the service using the
  coerced values

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

`OobFunctionAccessibilityPreflightGuard` owns replay permission preflight:

- scan active replay steps for deterministic actions that require accessibility
- check whether the accessibility action backend is ready before the step loop
- build the stable permission-blocked failed-run payload
- keep permission preflight outside replay ordering and step execution

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

`OobFunctionStepClassifier` owns replay step-shape classification:

- identify legacy/noise steps that replay should skip
- resolve the canonical OmniFlow execution tool for a step
- decide whether a step is locally executable as graph/function/call_tool
- extract replayable agent tools from recorded agent fallback steps
- keep these routing predicates out of the main replay loop

`OobFunctionToolDelegationExecutor` owns live tool delegation inside replay:

- remap a materialized Function step into delegated tool arguments
- build the synthetic `AssistantToolCall` used by `AgentToolExecutor`
- build the runtime descriptor for delegated replay steps
- map delegated tool results back into a stable per-step payload
- never decide replay order, fallback policy, or whether a step should delegate

`OobFunctionCallToolStepExecutor` owns `call_tool` replay steps:

- resolve `call_tool` target Function/tool names and forwarded arguments
- convert Function targets into nested reusable Function replay handoffs
- delegate ordinary tool targets through `OobFunctionToolDelegationExecutor`
- return agent fallback payloads when a live tool router is required
- keep `call_tool` target policy outside the main replay loop

`OobFunctionNestedFunctionExecutor` owns nested reusable Function execution:

- resolve `function_id` and nested Function arguments from a replay step
- load, validate, and materialize the nested Function before recursive replay
- emit nested Function tool-card start/completion payloads through
  `OobFunctionNestedCallCardPresenter`
- map the nested run result back into the parent step result payload
- never decide parent replay order, recursion limits, or source alignment policy

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
          -> OobFunctionJson # shared value coercion for update services
          -> OobFunctionUpdateIntentParser # patch/instruction -> update ops
          -> OobFunctionMetadataPatchApplier # metadata/evidence/audit patches
              -> OobFunctionCheckerPatchService # checker metadata normalization
          -> OobFunctionStructuralPatchApplier # retarget/insert/delete steps
              -> OobFunctionSpecBuilder # inserted step normalization
              -> OobFunctionTargetSourceMatcher # source XML repair matching
          -> OobFunctionRunLogEvidencePackager # Function + RunLog agent context
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
              -> OobFunctionStepClassifier # replay step-shape routing
              -> OobFunctionToolDelegationExecutor # live tool delegation bridge
              -> OobFunctionCallToolStepExecutor # call_tool step resolution
              -> OobFunctionNestedFunctionExecutor # nested Function execution
              -> OobFunctionRunResultBuilder # run result/timing payloads
              -> OobFunctionNestedCallCardPresenter # nested Function card payloads
              -> OobFunctionEntryPackageGuard # pre-replay app restoration
              -> OobFunctionAccessibilityPreflightGuard # permission preflight
              -> OobFunctionGraphStepRunner # graph/UTG path lowering
      -> OobRunLogReplayService      # RunLog -> Function conversion
          -> RunLogReusableFunctionCompiler # cards -> reusable Function spec
              -> RunLogStartupBridgeCleaner # transient launch bridge cleanup
              -> RunLogReplayStepCompiler # single-card action -> replay step
                  -> RunLogCardAccessors # card field/JSON extraction helpers
              -> RunLogReplayStepNoiseNormalizer # compiled step noise cleanup
              -> RunLogReusableFunctionParameterizer # parameters/actions/bindings

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
- `OobFunctionUpdateService`: update_function orchestration, permission gates,
  dry-run/save behavior, and tool response shaping
- `OobFunctionUpdateIntentParser`: raw patch op and instruction intent
  normalization
- `OobFunctionMetadataPatchApplier`: non-structural metadata, evidence,
  checker, parameter, agent reuse, and audit patching
- `OobFunctionStructuralPatchApplier`: target repair, insert/delete step
  mutation, execution reindexing, and execution capability recomputation
- `OobFunctionRunLogEvidencePackager`: Function + RunLog evidence context and
  agent prompt packaging
- `OobFunctionCheckerPatchService`: checker rule and checker asset metadata
  normalization
- `OobFunctionJson`: mechanical JSON/map/list/scalar coercion shared by
  update_function services; do not hide policy or mutation behavior here
- `OobFunctionTargetSourceMatcher`: source XML parsing and node scoring for
  target-repair patches
- `OobFunctionRecallService`: page/node recall, ranking, direct-hit policy, and
  compact recall payload shaping
- `OobFunctionRunPolicy`: pre-run guard and failed-run agent fallback handoff
- `OobFunctionCallTiming`: Function call timing payload construction
- `RunLogReusableFunctionCompiler`: offline RunLog-to-Function assembly and
  conversion orchestration
- `RunLogReplayStepCompiler`: single-card action semantics, executor selection,
  step titles, and source-context repair
- `RunLogStartupBridgeCleaner`: transient startup/launcher bridge cleanup before
  final step indexing
- `RunLogCardAccessors`: shared RunLog card field, tool-call, observation, and
  JSON-safe extraction helpers
- `RunLogReusableFunctionParameterizer`: deterministic runtime parameter
  inference, canonical JSON schema, legacy action compatibility, and binding
  metadata for compiled Function specs
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
- `OobFunctionStepClassifier`: legacy skip detection, OmniFlow execution-tool
  resolution, local graph/function/call_tool classification, and replayable
  agent-tool extraction
- `OobFunctionToolDelegationExecutor`: mechanical bridge from replay steps to
  live `AgentToolExecutor` calls and back to per-step result payloads
- `OobFunctionCallToolStepExecutor`: `call_tool` target resolution, Function
  target handoff, ordinary tool delegation, and tool-router fallback payloads
- `OobFunctionNestedFunctionExecutor`: nested Function id/argument resolution,
  nested materialization, recursive run handoff, and parent-step result shaping
- `OobFunctionRunResultBuilder`: stable run payload schema, failure step
  records, and runner timing/phase accounting
- `OobFunctionNestedCallCardPresenter`: nested Function tool-card ids,
  summaries, args payloads, and result preview payloads
- `OobFunctionEntryPackageGuard`: pre-replay app/package restoration
- `OobFunctionAccessibilityPreflightGuard`: permission readiness checks and
  permission-blocked failed-run payloads before replay starts
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

When changing `update_function` patch/evidence/checker code, use
`OobFunctionJson` for mechanical payload coercion instead of adding another
private `mapArg`/`firstNonBlank`/`mutableJsonMap` copy. Keep it limited to
shape conversion; new rules should live in the owning update service.

## Verification

After backend Function changes, run focused tests:

```bash
./gradlew --no-daemon :app:compileDevelopStandardDebugKotlin -Pkotlin.incremental=false
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest -Pkotlin.incremental=false \
  --tests 'cn.com.omnimind.bot.agent.AgentToolRegistryOobFunctionTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentSystemPromptTest' \
  --tests 'cn.com.omnimind.bot.mcp.McpToolDefinitionsTest' \
  --tests 'cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompilerTest' \
  --tests 'cn.com.omnimind.bot.runlog.OobOmniFlowLoopAcceptanceTest' \
  --tests 'cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandlerOmniFlowExecutionTest' \
  --tests 'cn.com.omnimind.bot.agent.tool.handlers.WorkbenchToolHandlerOobFunctionToolsTest' \
  --tests 'cn.com.omnimind.bot.runlog.InternalRunLogStoreTest'
```
