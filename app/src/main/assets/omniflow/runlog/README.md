# OOB RunLog

OmniFlow is the pipeline from RunLog to Function matching, execution, and
agent fallback. There is no separate OmniFlow skill runtime layer in this
contract.

RunLog is a runtime contract, not just a UI feature. Keep these boundaries aligned:

1. Native records tool cards into `InternalRunLogStore`.
2. Flutter displays the timeline and converts cards into a reusable Function.
3. Native stores and materializes Functions through `OobReusableFunctionStore`.
4. `OobFunctionToolHandler` replays deterministic local steps first, then hands live-context steps back to Agent.
5. Workspace command save must follow the same executor policy as Flutter conversion.

Read `references/runlog-contract.md` before changing conversion or replay behavior.

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
- Function registry/materialization: `baselib/src/main/java/cn/com/omnimind/baselib/runlog/OobReusableFunctionStore.kt`
- Native timeline and method channel handlers: `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
- Replay runner: `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
- Native replay policy and compiler: `app/src/main/java/cn/com/omnimind/bot/runlog/`
- Workspace command save: `app/src/main/java/cn/com/omnimind/bot/workbench/WorkspaceFunctionStore.kt`
- Flutter timeline: `ui/lib/features/task/pages/execution_history/run_log_timeline_page.dart`
- Flutter converter: `ui/lib/features/task/run_log/run_log_reusable_function_converter.dart`
- Flutter service bridge: `ui/lib/services/assists_core_service.dart`

## Executor Policy

The executor lists live in `replay_policy.json` and are mirrored by Kotlin and
Dart policy classes with parity tests. Update the JSON and both mirrors
together.

- `executor=omniflow`: deterministic local replay only. Allowed actions are
  the OOB local set plus OmniFlow canonical aliases: `click`, `long_press`,
  `scroll`, `type`, `input_text`, `swipe`, `open_app`, `press_home`,
  `press_back`, `press_key`, `hot_key`, `wait`, and `finished`. OOB-native
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
- Keep parameter bindings aligned with actual `execution.steps` indexes after skipping wrapper cards.
- For agent steps, bind runtime parameters into both `step.args` and `step.agent_call.args.original_args`.
- AI normalization may rename and parameterize, but must not change executor policy. Normalize data-flow tools back to `executor=agent`.
- OmniFlow graph/function tools compile to `kind=omniflow_graph` or
  `kind=omniflow_function`, `executor=omniflow`, and `model_free=true`.

## Replay Rules

Direct UI execution is two phase:

1. Execute deterministic local prefix.
2. If a tool/data-flow/agent step is reached, return `needs_agent=true` and start an Agent task with the remaining function spec.

Agent runtime execution may delegate normal tools through the router, but data-flow/perception-only steps should still be planned by Agent instead of blindly calling the original tool.
OmniFlow function calls are resolved against the local OOB reusable function
stores and execute recursively with a bounded call stack. OmniFlow graph calls
execute explicit `path` entries or UTG edges by lowering them to supported
primitive local actions.

## OmniFlow Compatibility Boundary

Port into OOB:

- Canonical action names and aliases: `input_text`, `swipe`, `press_key`,
  `finished`, plus common aliases such as `tap`, `type_text`, `scroll_*`,
  `launch_app`, and `done`.
- `source_context.page` as an input alias for OOB's
  `source_context.src_ctx.page` coordinate remap shape.
- Function metadata that keeps `source.run_id`, `source_run_ids`, execution
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
./gradlew :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompilerTest
./gradlew :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.runlog.OmniflowStepExecutorTest
./gradlew :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.workbench.OobSkillReplayTest
```

Add tests for new tool classes before changing executor policy.
