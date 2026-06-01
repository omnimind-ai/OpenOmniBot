# OOB RunLog

OmniFlow is the pipeline from RunLog to reusable command matching, execution,
and agent fallback. Product-facing behavior is exposed through skills; this
contract only defines the storage, conversion, and replay primitives those
skills call. There is no separate OmniFlow runtime or controller outside the
skill system.

RunLog is a runtime contract, not just a UI feature. Keep these boundaries aligned:

1. Native records tool cards into `InternalRunLogStore`.
2. Flutter displays the timeline and converts cards into a reusable command.
3. Native stores and materializes reusable commands through `OobReusableFunctionStore`.
4. `OobFunctionToolHandler` replays deterministic local steps first, then hands live-context steps back to Agent.
5. Workspace command save must follow the same executor policy as Flutter conversion.

Read `references/runlog-contract.md` before changing conversion or replay behavior.

Home input exposes RunLog entry points through one compact trajectory icon below
the composer. Tapping it opens a transient three-action popup: existing
trajectories, the current/latest trajectory, and record trajectory. Do not
restore a persistent large action panel above the input.

## Storage Model

`InternalRunLogStore` stores every mutation as append-only NDJSON events using
`schema_version=oob.run_log_event.v1`, then writes compact JSON snapshots for
existing timeline APIs. High-frequency running-card updates append events first
and snapshot at terminal boundaries or after a short interval, so live terminal
and browser runs avoid whole-file rewrites on every progress tick.

Readers must treat the JSON snapshot plus later NDJSON events as one logical
record. Do not read only the snapshot when correctness matters.

## Code Map

- Native storage: `baselib/src/main/java/cn/com/omnimind/baselib/runlog/InternalRunLogStore.kt`
- Shared replay policy: `app/src/main/assets/omniflow/runlog/replay_policy.json`
- Reusable Function storage owner: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRepository.kt`
- Function payload/value codec: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionJson.kt`
- Function spec normalization: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionSpecBuilder.kt`
- Function update/evidence service: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionUpdateService.kt`
- Function metadata/evidence patch applier: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionMetadataPatchApplier.kt`
- Function structural patch applier: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionStructuralPatchApplier.kt`
- Function update intent parser: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionUpdateIntentParser.kt`
- Function RunLog evidence packager: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunLogEvidencePackager.kt`
- Function checker metadata normalization: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionCheckerPatchService.kt`
- Function target repair source matcher: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionTargetSourceMatcher.kt`
- Function recall policy: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRecallService.kt`
- Function guard/fallback policy: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunPolicy.kt`
- Function call timing: `app/src/main/java/cn/com/omnimind/bot/runlog/OobFunctionCallTiming.kt`
- MCP Function tool schema: `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt`
- MCP Function/tool call adapter: `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolExecutors.kt`
- SharedPreferences registry/materialization: `baselib/src/main/java/cn/com/omnimind/baselib/runlog/OobReusableFunctionStore.kt`
- Native timeline and method channel handlers: `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
- RunLog replay step noise normalizer: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepNoiseNormalizer.kt`
- RunLog reusable Function compiler: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReusableFunctionCompiler.kt`
- RunLog card-to-step compiler: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepCompiler.kt`
- RunLog card field/JSON accessors: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogCardAccessors.kt`
- RunLog startup/launcher bridge cleaner: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogStartupBridgeCleaner.kt`
- RunLog reusable Function parameterizer: `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReusableFunctionParameterizer.kt`
- RunLog action/value codec: `app/src/main/java/cn/com/omnimind/bot/runlog/OobActionCodec.kt`
- Function execution startup: `app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionRunner.kt`
- Replay step runner: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
- Replay frontend session controller: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionFrontendSessionController.kt`
- Replay source alignment controller: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionSourceAlignmentController.kt`
- Replay agent fallback controller: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionAgentFallbackController.kt`
- Replay call/tool argument resolver: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionCallRequestResolver.kt`
- Replay step classifier: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionStepClassifier.kt`
- Replay tool delegation executor: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolDelegationExecutor.kt`
- `call_tool` step executor: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionCallToolStepExecutor.kt`
- Nested Function executor: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionNestedFunctionExecutor.kt`
- Replay run result builder: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionRunResultBuilder.kt`
- Nested Function card presenter: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionNestedCallCardPresenter.kt`
- Pre-replay entry package guard: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionEntryPackageGuard.kt`
- Pre-replay accessibility guard: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionAccessibilityPreflightGuard.kt`
- Graph/UTG replay path runner: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionGraphStepRunner.kt`
- Native replay policy and reusable command conversion: `app/src/main/java/cn/com/omnimind/bot/runlog/`
- RunLog conversion facade: `app/src/main/java/cn/com/omnimind/bot/runlog/OobRunLogReplayService.kt`
  It only converts and auto-registers RunLogs; Function CRUD belongs in
  `OobFunctionRepository`.
- Agent/MCP Function facade: `app/src/main/java/cn/com/omnimind/bot/runlog/OobOmniFlowToolkitService.kt`
- Function backend ownership: `app/src/main/assets/omniflow/function/README.md`
- Workspace command save: `app/src/main/java/cn/com/omnimind/bot/workbench/WorkspaceFunctionStore.kt`
- Flutter timeline: `ui/lib/features/task/pages/execution_history/run_log_timeline_page.dart`
- Flutter reusable command card: `ui/lib/features/task/pages/execution_history/widgets/reusable_command_card.dart`
- Flutter converter: `ui/lib/features/task/run_log/run_log_reusable_function_converter.dart`
- Flutter service bridge: `ui/lib/services/assists_core_service.dart`

## Executor Policy

The executor lists live in `replay_policy.json` and are mirrored by Kotlin and
Dart policy classes with parity tests. Update the JSON and both mirrors
together.

- `executor=omniflow`: deterministic local replay only. Allowed actions are
  the OOB local set plus OmniFlow canonical aliases: `click`, `long_press`,
  `scroll`, `input_text`, `swipe`, `open_app`, `press_home`,
  `press_back`, `press_key`, `hot_key`, and `finished`. Legacy `wait` cards are
  skipped because page settling is handled internally by the replay backend. OOB-native
  OmniFlow graph/function commands such as `go_to_node`, `click_node`, and
  `call_tool` with `function_id` also use this executor and are dispatched by
  `OobFunctionToolHandler`.
- `executor=tool`: direct tool call only when a live `AgentToolRouter` exists and the tool output is not live data needed by later steps.
- `executor=agent`: live planning or perception. Use for VLM-only cards, `browser_use`, `web_search`, memory lookup, RunLog lookup, and workbench list/query tools.

Do not hard replay `browser_use` or `web_search`; their outputs are live context and can be stale.

## Conversion Rules

- VLM-only logs must not become empty functions. Emit one `executor=agent` step with reason `perception_only_step_without_recorded_actions`.
- If a VLM wrapper card is followed by concrete recorded actions, skip the perception wrapper and keep the recorded `omniflow` steps.
- Failed recorded action cards must not count as concrete replay evidence; keep the
  VLM fallback if the only local action failed.
- `android_privileged_action` cards that wrap a supported local UI action should
  flatten nested `arguments` into the emitted OmniFlow step args.
- Treat legacy `type` as an import alias for `input_text`; do not emit it as a
  final replay tool. Drop adjacent duplicate input-text steps when noisy
  accessibility events report the same final text on the same target.
- Keep compiled step noise cleanup in `RunLogReplayStepNoiseNormalizer`.
  It owns repeated input collapse and redundant click-before-input removal;
  startup launch bridge cleanup belongs in `RunLogStartupBridgeCleaner`, and
  the compiler should only orchestrate card-to-step conversion.
- Keep single-card action semantics in `RunLogReplayStepCompiler`. It owns
  whether a card becomes `executor=omniflow`, `executor=tool`, or
  `executor=agent`, plus step titles and source-context repair.
- Keep RunLog card field extraction and JSON coercion in `RunLogCardAccessors`.
  Do not duplicate `tool_call`/`header`/observation parsing across compiler,
  startup cleanup, or future analysis code.
- Keep generic RunLog action/value coercion in `OobActionCodec`. Tool facades
  such as `OobOmniFlowToolkitService` and small payload helpers such as
  `OobFunctionCallTiming`, schema/parameterization helpers, explorer utilities,
  and cleanup services should call it instead of adding private
  `mapArg`/`listArg`/`firstNonBlank`/`intArg`/`longArg`/`boolArg` copies when
  behavior is equivalent. Prefer direct calls or member imports from
  `OobActionCodec`; do not add one-line local forwarding helpers.
- Keep generic Function payload coercion in `OobFunctionJson`. Function
  register/update/run/recall services, Function replay argument compatibility,
  and Function run-result timing payload merge should use it instead of adding
  local `mapArg`/`listArg`/`firstNonBlank`/`intArg`/`longArg`/`boolArg` copies.
  It is a mechanical JSON/value helper only; action aliases and RunLog-card
  semantics stay in `OobActionCodec` and `RunLogCardAccessors`. Prefer direct
  calls or member imports from `OobFunctionJson`; do not add one-line local
  forwarding helpers.
- Do not force-merge helpers with intentionally different compatibility
  behavior. `OobFunctionSchemaBuilder.boolArg` is stricter for schema fields,
  `RunLogReusableFunctionParameterizer.asMap` preserves its legacy map-key
  behavior, `RunLogCardAccessors` owns card-field extraction helpers rather
  than generic action coercion, and MCP route/executor helpers may keep
  multi-key argument alias/default parsing local when the semantics are
  route-specific.
- Keep deterministic `input_text` parameter inference, canonical JSON schema
  construction, legacy `actions` compatibility, and parameter binding metadata
  in `RunLogReusableFunctionParameterizer`; do not put those rules back into
  the card compiler or runtime replay handler.
- Keep parameter bindings aligned with actual `execution.steps` indexes after skipping wrapper cards.
- For agent steps, bind runtime parameters into both `step.args` and `step.agent_call.args.original_args`.
- AI normalization may rename and parameterize, but must not change executor policy. Normalize data-flow tools back to `executor=agent`.
- Agent enhancement may refine the reusable command name, description, per-step
  descriptions, parameter names/descriptions/bindings, and `agent_reuse`
  metadata. It must not rewrite `execution.steps`, tool names, executors,
  step order, validation, fallback, or concrete tool args. New parameter
  bindings are accepted only when they point to existing non-coordinate leaf
  args, so a recorded "妈妈 + 手机号" flow can become a reusable
  "联系人 + 手机号" command without changing the replay structure.
- The enhancement prompt should send a compact digest with step summaries and
  `candidate_bindings`, plus a valid example output JSON. Do not send the full
  executable spec, source XML, screenshots, or `source_context` to the label
  enhancer.
- `agent_reuse.key_actions` is planning metadata only. It helps later agent
  selection but is not an executable split.
- OmniFlow graph/reusable-command tools convert to `kind=omniflow_graph` or
  `kind=omniflow_function`, `executor=omniflow`, and `model_free=true`.

## Replay Rules

Direct UI execution is two phase:

1. Execute deterministic local prefix.
2. If a tool/data-flow/agent step is reached, return `needs_agent=true` and start an Agent task with the remaining function spec.

Agent runtime execution may delegate normal tools through the router, but data-flow/perception-only steps should still be planned by Agent instead of blindly calling the original tool.
OmniFlow reusable-command calls are resolved against the local OOB reusable
command stores and execute recursively with a bounded call stack. OmniFlow graph calls
execute explicit `path` entries or UTG edges by lowering them to supported
primitive local actions.

Manual human recording shows a compact top control only after the recorder has
started, but action capture is initially paused. The user can navigate to the
target screen first; tapping the same pause/resume control starts capture and
refreshes the current screen baseline before recording the first action. The
manual recording flow does not show the lower cat learning popup; the compact
top control is the only recording control surface. The control can be dragged;
drag movement temporarily pauses capture, does not enter the RunLog, and resumes
by capturing the current screen again so the next recorded step uses a fresh
source context. Manual recording can also be paused
explicitly while transient UI issues are handled. The top control's
`截图` action is a full get-state capture, not a replay action: it hides the
control, persists XML, screenshot, PageVector observation, page analysis,
decision context, node skill, and state manifest through `OobUdegNodeStore`,
then appends a skipped `get_state` evidence card to the active RunLog.
Debug builds may enable per-action marked screenshots for manual recording.
Those screenshots are diagnostic-only private JPEG artifacts with the actual
touch position annotated; normal product recording remains XML/touch based and
does not store screenshots by default.
Registered reusable-command steps can be edited from the command library and
are saved back under the same `function_id`. Manual action cards retain
`event_context` for conversion diagnostics. Replayable manual actions must come
from concrete input backends: `overlay_touch`, `overlay_touch_text_input`,
`device_getevent`, or `device_getevent_text_input`. Accessibility events are
evidence only and must not create replayable actions by themselves. Every
manual action must have a before XML snapshot; if execution succeeds but the
action cannot be recorded with its before XML, the recorder reports failure
instead of writing a partial RunLog step.

RunLog save results, the command library page, and the memory-center embedded
command list share the same reusable-command summary card. Keep the primary run
entry on that card; secondary controls may enhance or schedule the command, but
must not introduce another summary-card layout or duplicate run button. If the
RunLog already maps to a registered reusable command, open that command directly
instead of registering it again. The save-result sheet's default primary action
is `增强`: it asks Agent to improve the reusable command name, description,
per-step descriptions, safe runtime parameter slots, and non-executable
`agent_reuse` metadata, then saves the enhanced spec back to the same
`function_id`. Enhancement never changes executor/tool/args/validation/fallback
or step order. This is a background UI task: the button shows `后台增强中`
while the Agent/update work is pending, then shows an explicit terminal result:
`已增强`, `已检查`, `部分增强`, or `重试增强`. The result is persisted under
`metadata.oob_enhancement` with status `enhanced`, `unchanged`, `partial`, or
`failed`, so reopening the Function does not require guessing whether the prior
click changed anything. A save button should only appear after unsaved manual edits.
Raw JSON and agent prompt details stay under the advanced section by default.

## OmniFlow Compatibility Boundary

Port into OOB:

- Canonical action names and aliases: `input_text`, `swipe`, `press_key`,
  `finished`, plus common aliases such as `tap`, `type_text`, `scroll_*`,
  `launch_app`, and `done`.
- `source_context.page` as an input alias for OOB's
  `source_context.src_ctx.page` coordinate remap shape.
- Reusable command metadata that keeps `source.run_id`, `source_run_ids`, execution
  counts, and local runner state available for future provider import/export.

Keep in OmniFlow/provider for now:

- Provider HTTP/MCP lifecycle, cache gate, recall, cloud push/pull, and retry
  resume semantics.
- SQLite `RunStore`, background enrich, semantic dedup, L1/L2 cache writeback,
  and multi-user registry.
- Cloud/provider graph optimization beyond the local path/edge data embedded in
  OOB function specs.

## Verification

Run focused tests after RunLog changes:

```bash
cd ui
flutter test test/features/task/pages/execution_history/run_log_reusable_function_converter_test.dart
flutter test test/services/agent_stream_reducer_test.dart
dart analyze lib/features/task/run_log/run_log_reusable_function_converter.dart lib/features/task/pages/execution_history/run_log_timeline_page.dart test/features/task/pages/execution_history/run_log_reusable_function_converter_test.dart
```

```bash
./gradlew :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.runlog.InternalRunLogStoreTest
./gradlew :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.runlog.OmniflowStepExecutorTest
./gradlew :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.workbench.OobSkillReplayTest
```

Add tests for new tool classes before changing executor policy.
