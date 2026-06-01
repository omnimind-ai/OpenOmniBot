# OOB Function Backend

This document records the backend ownership rules for OOB reusable Functions.
It is intentionally about core logic only; Flutter cards and display behavior
belong in UI documentation.

## Concept Model

Function code should be unified by concept, not by where a string happens to
appear:

- A Function is a reusable capability. Its durable shape is the Function spec,
  execution steps, parameters, checker metadata, evidence metadata, and recall
  hints.
- A RunLog is evidence. It can create or improve a Function, but raw cards,
  failed attempts, perception wrappers, and cleanup clicks are not themselves
  Function steps until the RunLog compiler accepts them.
- An action is a device operation such as `click`, `input_text`, or `open_app`.
  Action vocabulary belongs to `OobActionCodec`; do not redefine action aliases
  in Function update, recall, or replay services.
- Code that branches on canonical action names must use `OobActionCodec`
  constants. Literal strings are acceptable only in raw JSON fixtures,
  compatibility alias lists, or user-facing prose.
- Code that needs an action family, such as point-target actions, should use
  the sets exposed by `OobActionCodec` instead of rebuilding local
  `click`/`long_press` lists.
- Runtime decisions must be action-driven. Main replay, source alignment, and
  route safety should use `OobActionCodec` predicates or replay policy, not
  offline role labels such as `semantic`, `navigation`, or `noise`.
- An executor is a replay classification, not an action. `omniflow`, `tool`,
  and `agent` belong to `RunLogReplayPolicy`; use them to decide who executes a
  step, not to describe what the step does.
- A tool name is an agent/MCP surface. Function lifecycle tools belong to
  `OobFunctionToolNames`; generic agent tools belong to `AgentToolNames`;
  replay bridge names such as `call_tool` belong to `RunLogReplayPolicy`.
- A checker is conditional environment handling. Ads, popups, permission
  prompts, resolver sheets, and "skip" buttons should be represented as checker
  metadata or evidence, not inserted as mandatory Function path steps.
- Checker rule condition/action/phase vocabulary and alias normalization belong
  to `OmniflowCheckerRule`; patch appliers should delegate to it instead of
  maintaining local `dismiss`/`allow`/`skip` alias tables.

When adding code, first decide which concept it belongs to. If the new code
needs two concepts, wire the existing owners together instead of creating a new
helper with mixed semantics.

## Ownership

`OobFunctionRepository` is the single owner for Function storage:

- register, list, get, delete, and clear Functions
- keep workspace JSON and `OobReusableFunctionStore` in sync
- prefer the local registry when both registry and workspace copies exist,
  because runtime updates are committed there first and workspace JSON is the
  portable mirror
- report workspace/registry sync failures as structured diagnostics; registry
  success remains the authoritative runtime registration result
- update UDEG Function references
- bind registered Functions back to source RunLogs
- clear Function-as-tool exposure when the last Function is removed

`OobRunLogReplayService` owns RunLog conversion:

- read finished `InternalRunLogRecord` entries
- compile records through `RunLogReusableFunctionCompiler`
- rely on `RunLogReplayStepNoiseNormalizer` for compiled-step noise cleanup
- apply explicit name/id/description overrides
- mirror source RunLogs into the workspace as a best-effort portable artifact
- return conversion diagnostics such as card count and compiled step count
- delegate all Function persistence to `OobFunctionRepository`; callers that
  already own a repository should inject it so conversion and tool facades share
  the same storage owner instance

`InternalRunLogStore` owns native RunLog persistence:

- append every durable run mutation to the per-run event log
- save JSON snapshots only as a read-performance/cache artifact, not as the
  source of truth for terminal run status
- rebuild timeline payloads by loading the latest snapshot and replaying later
  events so event-only cards and finish events remain recoverable
- keep `finishRun(saveSnapshot=false)` available for recording modes that must
  avoid overwriting richer event-log evidence with a sparse terminal snapshot
- keep conversion and Function compilation outside the storage layer

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

`McpToolDefinitions` and `McpToolExecutors` own the external MCP adapter:

- expose the public MCP schema for OOB tools, including Function tools
- normalize MCP argument aliases before dispatching into the agent/tool runtime
- directly hand `oob_function_run`-style Function calls to
  `OobOmniFlowToolkitService`
- never implement Function storage, recall, update, guard, or replay policy

`OobFunctionSkillProfile` owns the native Function-management skill profile:

- expose the small static tool set used by `oob-function-management`
- expose dynamic registered Functions as model tools when the feature flag is on
- build compact prompt candidates for agent tool selection
- never execute Functions or mutate Function specs

`OobFunctionToolNames` owns canonical in-app agent tool names for Function and
RunLog lifecycle:

- define `oob_function_*`, `update_function`, and `oob_run_log_*` names used by
  the native skill profile, Workbench tool handler, and MCP OOB Function schema
- keep legacy/external `omniflow.*`, `call_tool`, and `oob_tool_call`
  compatibility names in the MCP adapter instead of mixing them into this set
- keep replay-step executor/tool taxonomy in `RunLogReplayPolicy`
- never own tool descriptions, schemas, execution, recall, update, or replay
  behavior

`AgentToolNames` owns canonical in-app names for generic agent tools:

- define stable names such as `vlm_task`, `browser_use`, `web_search`, and
  `android_privileged_action`
- share those names across agent tool definitions, handlers, MCP adapters,
  agent run-log card construction, and RunLog classifiers
- never own OOB Function lifecycle names or replay-only taxonomy such as
  `call_function`

When adding or migrating a generic agent tool name:

- add the string once in `AgentToolNames`
- use that constant in the tool definition, handler routing, MCP route/schema
  adapter, fallback call sites, and run-log card construction
- update `RunLogReplayPolicy` only when replay classification must recognize
  the tool; do not move Function lifecycle names into `AgentToolNames`
- keep user-facing skill/tool descriptions near the existing tool schema owner,
  not in `AgentToolNames`
- add or update a route/schema test when the tool is exposed through MCP

`AgentToolJson` owns agent-facing JSON projection helpers:

- convert Kotlin maps/lists/scalars into `JsonElement` for tool definitions and
  tool payloads
- support dynamic Function tool definitions, remote MCP tool schemas, and
  runtime tool result payloads
- stay policy-free; schema meaning and Function behavior belong to the caller

`OobFunctionSchemaBuilder` owns model-tool schema projection:

- convert reusable Function specs into JSON-schema shaped tool input contracts
- derive dynamic Function tool ids and parameter names from canonical or legacy
  Function fields
- materialize legacy action specs into canonical execution-step shapes for
  schema/tool compatibility only
- emit canonical local action names through `OobActionCodec` when rebuilding
  execution steps from legacy action specs
- never decide recall ranking, replay policy, or update patches

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

`OmniflowCheckerRule` owns runtime checker rule vocabulary:

- define checker phases, conditions, actions, and global built-in checker rules
- normalize checker condition/action aliases such as `skip_ad`, `click_allow`,
  `always_open`, and `dismiss_keyboard`
- expose the supported condition/action matrix and default phase/action mapping
- keep checker vocabulary out of `update_function` patch appliers and step
  execution services

`OobStepRoleClassifier` owns reusable step role normalization:

- classify explicit `agent_reuse`, cleanup annotations, and default navigation
  roles for offline analysis and UDEG metadata
- expose checker-candidate role alias detection used by
  `OobFunctionCheckerPatchService`
- keep role labels such as `optional_checker`, `runtime_checker`,
  `checker_candidate`, and `ad_checker` out of checker-specific local alias
  tables
- never decide whether a replay step is executable, key/user-facing, or
  route-safe; those runtime decisions belong to `OobActionCodec` and replay
  policy

`OobFunctionStructuralPatchApplier` owns structural `update_function` patches:

- retarget an existing action when the agent says the Function clicked or used
  the wrong target
- insert and delete execution steps only when structural changes are explicitly
  allowed
- canonicalize patch action names through `OobActionCodec` before matching
  recorded steps, so aliases such as `scroll` do not become durable Function
  actions
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
- use `OobFunctionRunLogAnalysisContract` for the agent-facing analysis field
  names, role labels, and failure codes embedded in that prompt
- keep evidence-analysis prompt contracts outside Function mutation code
- never save Functions or apply patches

`OobFunctionUpdateIntentParser` owns update intent normalization:

- normalize patch fields like `ops`, `operations`, `repairs`, and
  `replace_target` into explicit update operations
- infer simple target-repair operations from user instructions such as
  `应该点「外卖」而不是点「美食」`
- emit canonical action names from `OobActionCodec` when inferring simple
  repair operations
- classify replace-target and structural operations for update-mode decisions
- never apply an operation or mutate a Function spec

`OobFunctionCheckerPatchService` owns checker metadata patching:

- normalize agent-provided checker rules from `update_function`
- convert optional cleanup/noise annotations into metadata checker rules
- keep ad, popup, permission, resolver, keyboard, and package mismatch handling
  as conditional checker metadata instead of mandatory execution steps
- deduplicate checker rules and `agent_reuse.checker_assets`

`OobFunctionJson` owns mechanical Function payload coercion:

- normalize public tool payload maps/lists into stable Kotlin value shapes
- build mutable JSON-compatible maps and lists for Function patch/update services
- provide shared scalar coercion helpers used by Function register/update/run/recall
  and replay-handler argument compatibility code
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
- share mechanical Function payload coercion with VLM recall/page-context
  guidance through `OobFunctionJson`

`OobFunctionRunPolicy` owns run-time policy:

- guard Function steps before execution
- classify block, confirmation, agent-needed, and allow decisions
- own the guard decision/risk vocabulary used in guard and fallback payloads
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

`OmniflowActionBackend`, `OmniflowCheckerRule`, and `OmniflowStepExecutor` own
primitive local action execution:

- dispatch canonical local UI actions to the accessibility-backed runtime
- evaluate global, Function-level, and node-level checker rules around each step
- perform source-context coordinate remapping and recovery snapshots
- keep primitive execution separate from Function storage, recall, and update

`OobFunctionGraphStepRunner` owns local graph/UTG execution inside replay:

- select graph path edges for `go_to_node` and `click_node`
- lower graph edges into primitive OmniFlow local-action steps
- execute the primitive path with the same checker rules as normal replay
- report path-level success, failure, and per-edge step results
- delegate stable failure step payload shape to `OobFunctionRunResultBuilder`
- use `RunLogReplayPolicy` for graph replay tool aliases such as `click_node`
  and `node_click`; graph runners and schema builders should not rebuild these
  alias sets locally

`OobFunctionEntryPackageGuard` owns pre-replay app restoration:

- infer the Function entry package from explicit `open_app` steps or source context
- skip restoration when replay already starts with `open_app`
- launch the expected package when the foreground app drifted before replay
- keep package recovery outside the main step loop
- callers that infer an entry package from Function steps should canonicalize
  action aliases through `OobActionCodec`; legacy names such as `launch_app`
  must still be treated as `open_app`

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
- derive fallback operation wording from canonical actions in `OobActionCodec`
  so `click`, `long_press`, and `swipe` keep distinct user-facing semantics
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
  -> McpToolDefinitions / McpToolExecutors # external MCP schema/argument adapter
  -> OobFunctionSkillProfile # Function-management profile and dynamic Function tools
      -> OobFunctionSchemaBuilder # Function spec -> model tool schema
  -> OobOmniFlowToolkitService
      -> OobFunctionRepository       # storage/index/source bindings
      -> OobFunctionSpecBuilder      # simple register/insert-step normalization
          -> OobFunctionJson # shared value coercion for Function payloads
      -> OobFunctionUpdateService    # update_function evidence and patches
          -> OobFunctionJson # shared value coercion for Function payloads
          -> OobFunctionUpdateIntentParser # patch/instruction -> update ops
          -> OobFunctionMetadataPatchApplier # metadata/evidence/audit patches
              -> OobFunctionCheckerPatchService # checker metadata normalization
          -> OobFunctionStructuralPatchApplier # retarget/insert/delete steps
              -> OobFunctionSpecBuilder # inserted step normalization
              -> OobFunctionTargetSourceMatcher # source XML repair matching
          -> OobFunctionRunLogEvidencePackager # Function + RunLog agent context
      -> OobFunctionRecallService    # page/node recall and direct-hit policy
          -> OobFunctionJson # shared value coercion for Function payloads
          -> OobUdegNodeStore        # page/node recall index
      -> VLM recall/page context guidance # render Function candidates for live VLM prompts
          -> OobFunctionJson # shared value coercion for Function payloads
      -> OobFunctionRunPolicy        # guard and fallback handoff
          -> OobFunctionJson # shared value coercion for Function payloads
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
              -> OmniflowStepExecutor # primitive local UI action execution
                  -> OmniflowActionBackend # accessibility-backed action runtime
                  -> OmniflowCheckerRule # global/function/node checker metadata
      -> OobRunLogReplayService      # RunLog -> Function conversion
          -> OobFunctionRepository   # injected storage owner for registration
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
- `OobFunctionRunLogAnalysisContract`: agent-facing analysis JSON field names,
  evidence role labels, and failure code vocabulary used by
  `OobFunctionRunLogEvidencePackager`; this is not runtime replay role policy
- `OobFunctionCheckerPatchService`: checker rule and checker asset metadata
  normalization
- `OobFunctionJson`: mechanical JSON/map/list/scalar coercion shared by Function
  register/update/run/recall services; do not hide policy or mutation behavior here
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
- `OmniflowActionBackend`, `OmniflowCheckerRule`, and
  `OmniflowStepExecutor`: primitive local action dispatch, checker evaluation,
  coordinate remapping, and recovery snapshots
- `McpToolDefinitions` and `McpToolExecutors`: external MCP schema and argument
  alias adapter before dispatch into the Function/tool facade
- `OobFunctionSkillProfile`: native Function-management skill profile,
  dynamic Function tool exposure, and compact prompt candidates
- `AgentToolJson`: agent-facing map/list/scalar to `JsonElement` projection for
  tool definitions and payloads
- `OobFunctionSchemaBuilder`: Function spec projection into model-tool schemas
  and compatibility materialization for that projection
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

When changing Function register/update/run/recall payload handling, use
`OobFunctionJson` for mechanical payload coercion instead of adding another
private `mapArg`/`listArg`/`firstNonBlank`/`intArg`/`longArg`/`boolArg`/
`mutableJsonMap` copy. Runtime replay helpers may also use it for
argument-shape compatibility, but execution policy must remain in the replay
service that owns the decision. Keep it limited to shape conversion; new rules
should live in the owning update service or replay component. Prefer direct
calls or member imports from the owner object over local one-line forwarding
helpers; thin wrappers make ownership harder to audit.

## Documentation Maintenance

When Function or RunLog behavior changes, update the nearest owner document in
the same commit as the code change:

- Tool surface or activation wording: update the built-in skill docs under
  `app/src/main/assets/builtin_skills/omniflow/` and this backend map when the
  native owner changes.
- Function storage, update, recall, run, checker, fallback, or replay ownership:
  update this file.
- RunLog conversion, card filtering, action aliases, executor categories,
  canonical replay tools, or noise cleanup: update
  `app/src/main/assets/omniflow/runlog/README.md`.
- Agent-facing repair/enhancement behavior: update the relevant skill reference
  and keep `update_function` as the only saved Function mutation path.
- Do not document a second owner for the same rule. If a new component must own
  behavior currently listed here, move the ownership bullet instead of copying
  it.

Use canonical OOB Function tools in agent-facing docs:
`oob_function_list`, `oob_function_get`, `oob_function_register`,
`update_function`, `oob_function_guard_check`, `oob_function_run`,
`oob_function_delete`, `oob_function_clear`, `oob_run_log_list`,
`oob_run_log_get`, and `oob_run_log_convert`. Treat `omniflow.*` names as
legacy/external MCP compatibility unless the code path being documented is
specifically that adapter. In Kotlin, route those canonical names through
`OobFunctionToolNames` unless the code is deliberately documenting user-facing
text or legacy replay taxonomy.

## Helper Maintenance Audit

Use these owner rules when removing duplicated helper code:

- Function payload shape helpers belong in `OobFunctionJson`. This includes
  generic map/list/string/int/long/bool coercion and JSON-safe sanitization used
  by register, update, recall, run payloads, timing merge payloads, and Function
  replay argument compatibility. Repository storage, summary, and projection
  code should also call this owner for mechanical coercion instead of growing
  storage-local copies. It must stay policy-free. Call this owner directly
  instead of adding local forwarding helpers with the same names.
- RunLog action/value helpers belong in `OobActionCodec`. This includes action
  aliases, low-level action argument extraction, and generic coercion used while
  converting RunLog cards or building RunLog-derived compatibility payloads.
  Call this owner directly instead of adding local forwarding helpers with the
  same names.
- Replay executor names belong in `RunLogReplayPolicy` constants. Core Function
  run policy, RunLog compilation, and local replay checks should use those
  constants instead of local string literals for `omniflow`, `tool`, or `agent`.
  This applies to generated step specs, result payloads that report the
  executor category, runtime comparisons, and deterministic replay markers such
  as `coordinate_hook`. Replay-engine markers such as `omniflow_utg` belong in
  the same policy object when runtime checks depend on them. Diagnostic labels such as
  `agent_tool`, `omniflow_graph`, `omniflow_function`, or
  `omniflow_vlm_fallback` are not executor categories and should stay local to
  the component that emits them.
- Canonical replay tool names such as `call_tool`, `oob_tool_call`,
  `call_function`, `go_to_node`, `click_node`, `node_click`, and
  `oob.agent.run` also belong in
  `RunLogReplayPolicy` constants when they are used as replay tool taxonomy.
  Compatibility replay types such as `wait` and `external_tool` also belong
  there when Function compilation or schema projection needs to preserve them.
  Replay-only data-flow compatibility names such as `oob_agent_run`,
  `omniflow.recall`, `omniflow.ingest_run_log`, and `workbench_api_list` should
  be named there when RunLog conversion or guard policy classifies them.
  UDEG edge-kind field names and diagnostic counter keys are graph-storage
  vocabulary and should remain with `OobUdegNodeStore`.
- Generic agent tool names such as `vlm_task`, `browser_use`, `web_search`,
  and `android_privileged_action` belong in `AgentToolNames`. Use that owner
  for registration, routing, fallback calls, and run-log card construction.
- RunLog card-field extraction belongs in `RunLogCardAccessors`. Do not add
  another local parser for `tool_call`, card headers, results, observations, or
  card payload JSON.
- Step role aliases belong in `OobStepRoleClassifier`. Checker patching may
  consume those roles, but should not maintain a separate optional-checker role
  alias table.
- Checker condition/action/phase aliases belong in `OmniflowCheckerRule`.
  `OobFunctionCheckerPatchService` may infer checker metadata from optional
  cleanup annotations, but it must delegate explicit checker rule
  normalization there instead of maintaining a second checker alias table.
  When a checker patch references a real local action such as `click` or
  `open_app`, it must canonicalize through `OobActionCodec` before mapping to
  checker-only actions such as dismiss, allow, or reopen-app.
- Function update policy belongs in `OobFunctionUpdateService` and its patch
  appliers. Do not move checker, evidence, audit, retarget, insert, delete, or
  reindex rules into `OobFunctionJson`.
- Runtime replay policy belongs in the replay components under
  `OobFunctionToolHandler`. Do not move skip/fallback/delegation/source
  alignment decisions into mechanical helper objects.
- Agent-facing tool JSON projection belongs in `AgentToolJson`. Use it when
  building tool definitions or serializing generic tool payloads, instead of
  adding another local `mapToJsonElement` copy or a forwarding method on
  `SharedHelper`.

Known helper exceptions that should not be force-merged without a semantic
change:

- `OobFunctionSchemaBuilder.boolArg` is stricter for schema fields and
  intentionally does not accept every runtime truthy alias.
- `RunLogReusableFunctionParameterizer.asMap` preserves legacy map-key behavior
  for compatibility metadata.
- `RunLogCardAccessors.asMap` and `RunLogCardAccessors.firstNonBlank` are the
  RunLog card-field extraction API, not duplicate action codecs.
- `OobFunctionJson.boolArgOrDefault` owns default-aware Function boolean
  coercion for checker/update payloads. Do not add checker-local copies unless
  a patch needs intentionally different semantics.
- `OobUdegNodeStore` should use `OobActionCodec` for generic scalar coercion
  such as graph indexes, timestamps, and booleans. It may keep graph-export
  `mapArg`/`listArg`/`firstNonBlank` helpers local only where those helpers
  sanitize stored UDEG graph values rather than merely coercing Function or
  RunLog payloads.
- `McpToolExecutors.intArg`, `McpToolExecutors.longArg`,
  `McpToolExecutors.boolArg`, and `McpToolExecutors.boolArgOrDefault` read
  multi-key MCP argument aliases and defaults; keep them local unless a shared
  MCP argument adapter with identical semantics exists.
- `McpRoutes.mapArg` and `McpRoutes.listArg` support legacy/debug HTTP route
  request parsing, including nullable maps and comma-separated string lists.
  They are not Function payload helpers.
- `OmniflowStepExecutor.firstNonBlank` and `OobPageVectorSet.firstNonBlank`
  are low-risk local helpers in runtime/vector internals; merge them only when
  touching the surrounding code for another reason.
- `OobReusableFunctionStore` lives in `baselib` and cannot depend on app-layer
  helper owners such as `OobFunctionJson`; keep its storage-compatibility
  coercion local unless the owner is moved to a shared module.
- `VlmToolCoordinator.firstNonBlank`, `VlmRecallGuidanceBuilder.boolArg`,
  `AgentAiCapabilityConfigSync.firstNonBlank`, and
  `AssistsCoreManager.firstNonBlankString` are outside Function payload
  ownership. Do not merge them into Function helpers unless their surrounding
  feature is explicitly migrated to the Function/RunLog backend.

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
