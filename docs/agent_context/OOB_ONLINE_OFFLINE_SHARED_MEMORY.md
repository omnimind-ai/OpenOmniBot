# OOB Online/Offline Shared Memory

Status: Living implementation note
Last Updated: 2026-05-26

This document is the shared memory for OOB's native OmniFlow layer, online VLM
agent, offline replay path, RunLog conversion, UDEG recall, and prompt assembly.
It records what the code does now, not an idealized design.

## Scope

- Online means a live `vlm_task` executed by OOB native Kotlin/Flutter runtime.
- Offline means RunLog collection -> reusable Function compilation -> local
  Function registration -> UDEG page-match recall -> deterministic replay.
- DroidRun/MobileRun and M3A are method references only. OOB does not depend on
  their code in this implementation.
- AndroidWorld is a validation reference. The app does not need a full
  AndroidWorld baseline runner in production.

## Source Map

Online VLM entry and coordination:

- `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/VlmToolHandler.kt`
  - parses `vlm_task`, checks permissions, injects resolved skills.
- `app/src/main/java/cn/com/omnimind/bot/vlm/VlmToolCoordinator.kt`
  - owns task lifecycle, recall guidance, optional explicit Function replay, and
    task start.
- `app/src/main/java/cn/com/omnimind/bot/vlm/VlmRecallGuidanceBuilder.kt`
  - converts OmniFlow recall payload into VLM `stepSkillGuidance`.
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMOperationTask.kt`
  - native task wrapper, RunLog collection, pause/resume, manual takeover.
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMOperationService.kt`
  - VLM loop, screenshot/XML read, prompt request, grounding, action execution.
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMClient.kt`
  - exact ChatCompletion request and message assembly.
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/PromptTemplate.kt`
  - system prompt rendering and dynamic user prompt rendering.
- `baselib/src/main/res/raw/model_scenes_default.json`
  - canonical scene prompt for `scene.vlm.operation.primary`.
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMToolDefinitions.kt`
  - native OpenAI tool schemas.

Offline OmniFlow and replay:

- `app/src/main/java/cn/com/omnimind/bot/runlog/OobOmniFlowToolkitService.kt`
  - `recall`, `callFunction`, `ingestRunLog`, `convertRunLog`.
- `app/src/main/java/cn/com/omnimind/bot/runlog/OobRunLogReplayService.kt`
  - converts successful RunLogs and registers reusable Functions.
- `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReusableFunctionCompiler.kt`
  - compiles RunLog cards into `oob.reusable_function.v1`.
- `app/src/main/java/cn/com/omnimind/bot/runlog/OobUdegNodeStore.kt`
  - page-vector UDEG nodes, node-skill context, Function/segment attachment.
- `app/src/main/java/cn/com/omnimind/bot/runlog/OmniflowStepExecutor.kt`
  - deterministic primitive replay executor.
- `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
  - materialized Function runner and per-step timing.

UI and tests:

- `ui/lib/widgets/execution/` normalizes user-facing RunLog/Function cards.
- `ui/lib/features/task/pages/execution_history/command_library_page.dart`
  shows reusable Functions as "复用指令".
- `ui/lib/features/task/pages/execution_history/function_run_result_sheet.dart`
  hides raw internal timing keys from normal UI.
- Focused tests are listed in `docs/agent_context/INDEX.md`.

## Current Invariants

- The online agent still grounds every action on live screenshot/XML. Recall
  guidance is context, not proof of task completion.
- UDEG recall starts from page match. After a node is hit, the decision context
  is the node skill and its attached Function/segment capabilities. It must not
  globally scan every Function as the primary decision path.
- Default online recall is context-only. Recalled Functions are optional
  candidates for VLM/agent decision, not automatic execution.
- Direct replay is allowed only when the caller explicitly sets
  `allowOmniFlowFunctionAutoExecute=true` or explicitly calls `oob_function_run`
  / `call_tool(function_id=...)`, and the selected Function/segment passes guard.
- Agent conversations expose `oob_function_list/get/register/guard_check/run/delete/clear`
  directly through the workbench tool handler. These are explicit management and
  execution tools; their presence does not change default context-only recall.
- Registering or converting a Function does not enable Function-as-model-tool
  exposure. `oobFunctionAsToolEnabled` is false by default and must be explicitly
  enabled by the user/UI before Function ids are added to the model tool list.
- `oob_function_register` accepts both full `oob.reusable_function.v1` specs and
  a conversation-friendly simple shape: `functionId`, `name`, `description`,
  `steps`, optional `parameters`, `packageName`, and `sourcePage`. The service
  normalizes the simple shape into the same structured Function spec before
  indexing and replay.
- Reusable Function execution that needs click/scroll/input requires OOB
  accessibility backend readiness. If not ready, replay returns
  `OOB_ACCESSIBILITY_REQUIRED`.
- Raw `duration_ms`, `started_at_ms`, `finished_at_ms`, and `phase_ms` are
  internal observability fields. They are recorded in RunLog/results/tests but
  should not be shown as raw debug labels in normal user-facing UI.

## Online Entry Flow

### Agent Tool Path

`VlmToolHandler.execute()` dispatches only `vlm_task`. The handler:

1. Reads `goal`, `packageName/package_name`, `needSummary/need_summary`,
   `startFromCurrent/start_from_current/skipGoHome/skip_go_home`, `maxSteps`,
   `waitTimeoutMs`, `model`, and `disableOmniFlowRecall`.
2. Uses runtime app context to detect target app package from user text and
   goal.
3. Skips VLM when the user only uploaded an image and the text does not imply
   screen automation.
4. Checks prerequisites: accessibility service and overlay permission.
5. Sanitizes args and calls `VlmToolCoordinator.executeNewTask(...)`.
6. Injects skill context as:

```kotlin
stepSkillGuidance = resolvedSkills.joinToString("\n\n") { it.stepGuidance() }
```

The permission check is duplicated defensively in `VlmToolCoordinator`: missing
automation permissions produce an error task with `missingPermissions` and an
automation permission error code.

### Coordinator Path

`VlmToolCoordinator.executeNewTask(...)`:

1. Creates a task id and `McpTaskManager` task state.
2. Checks automation permissions and screen operability.
3. Calls `buildRecallGuidanceAfterOptionalPrelaunch(...)`.
4. Appends recall guidance to `stepSkillGuidance` through
   `VlmTaskRequest.withRecallGuidance(...)`.
5. Calls `tryExecuteRecallHitIfAllowed(...)`. By default this returns null
   because `allowOmniFlowFunctionAutoExecute=false`.
6. Only when the request explicitly allows Function auto-execution and recall
   returned a strict direct no-argument hit, calls
   `OobOmniFlowToolkitService.callFunction(...)` with:

```kotlin
mapOf(
    "function_id" to functionId,
    "goal" to request.goal,
    "arguments" to emptyMap<String, Any?>(),
    "start_step_index" to startStepIndex,
)
```

7. If the explicit direct replay succeeds without fallback, the task is marked
   `FINISHED` and no live VLM loop runs.
8. If explicit direct replay is not allowed, fails, or there is no strict direct
   hit, starts the native VLM
   task through `AssistsUtil.Core.createVLMOperationTask(...)`.

`buildRecallGuidanceAfterOptionalPrelaunch(...)` optionally launches the target
package first when `packageName` is present and `skipGoHome` is false. It then
waits for current package/XML and calls `VlmRecallGuidanceBuilder.build(...)`.
If prelaunch succeeds, the later VLM request uses `skipGoHome = true` so the VLM
continues from the observed app page.

### Flutter/Native Channel Path

The public pause/resume/create path is:

```text
Flutter assists_core_service.dart
  -> MethodChannel methods createVLMOperationTask / pauseVLMTask / resumeVLMTask
  -> AssistsCoreManager
  -> AssistsUtil.Core
  -> AssistsCore
  -> StateMachine / TaskManager
  -> VLMOperationTask
```

The relevant native methods exist in:

- `AssistsCore.pauseVLMTask()` and `AssistsCore.resumeVLMTask()`
- `AssistsUtil.Core.pauseVLMTask()` and `AssistsUtil.Core.resumeVLMTask()`
- `TaskManager.pauseVLMTask()` and `TaskManager.resumeVLMTask()`
- `VLMOperationTask.requestPause()` and `VLMOperationTask.resumeFromPause()`

## Online VLM Task Flow

`VLMOperationTask.start(...)`:

1. Hides keyboard.
2. Determines current package and installed apps.
3. Calls `InternalRunLogStore.beginRun(...)` with:

```kotlin
runId = id
goal = goal
source = "vlm"
toolName = "vlm_task"
operationDescription = goal
```

4. Saves an execution record.
5. Tries `executeOpenAppFastPath(...)` for simple open-app requests.
6. Otherwise calls `vlmOperationService.executeTask(...)`.
7. On completion, writes each execution trace step into RunLog cards and
   finishes the RunLog.

`VLMOperationService.executeTask(...)`:

1. Optionally launches `packageName` unless `skipGoHome = true`.
2. Initializes `UIContext` with goal, installed apps, target package,
   `currentStepGoal`, `stepSkillGuidance`, and max steps.
3. Drains external memory before the loop and before each step. Manual takeover
   summaries enter here.
4. Periodically runs the compactor outside the single-step timeout.
5. Calls `executeSingleStepWithTimeOut(...)` until success/failure/finished or
   max steps.

`executeSingleStep(...)` does the live loop:

1. Pause check.
2. Capture screenshot.
3. Capture current XML.
4. Apply `VLMFirstStepOptimizer.enrichContext(...)`.
5. Apply registered page context provider:
   `VLMPageContextProviderRegistry.enrich(...)`.
6. Apply `VLMIndexedPageContext.enrich(...)` to append compact
   Accessibility-tree/indexed UI state.
7. Use one current screenshot by default. Marked screenshots are opt-in
   fallback evidence and are not generated for the default online request.
   If the model response needs a same-screen tool-call protocol correction and
   indexed Accessibility evidence is available, the retry request can omit the
   unchanged screenshot and use text evidence only.
8. Build request through `VLMClient.buildUIOperationRequest(...)`.
9. Stream one VLM turn through `streamClient.streamTurn(...)`.
10. Aggregate token usage from streamed response usage chunks.
11. Parse native OpenAI `tool_calls` with `VLMClient.parseVLMResponse(...)`.
12. Retry up to two times if the model did not return a valid native tool call.
13. Normalize some `open_app` actions.
14. Apply `VLMActionPostProcessor.correct(...)`.
15. Ground `element_index` / `scrollable_index` with indexed XML evidence.
16. Ground target coordinates against live XML when possible.
17. For coordinate actions, check XML page stability.
18. Execute the action through the action executor.
19. Wait for post-action stable XML.
20. Build post-action observation.
21. Upsert RunLog card and append a conversation round.

## Prompt Assembly

The canonical VLM scene is `scene.vlm.operation.primary`.

Scene registry:

- Prompt source: `baselib/src/main/res/raw/model_scenes_default.json`
- Model: `qwen3-vl-plus`
- Transport: OpenAI-compatible
- Parser: `openai_tool_actions`

`VLMClient.buildUIOperationRequest(...)` is the single assembly point. It:

1. Resolves `sceneId`:
   - If `model` starts with `scene.`, use it.
   - Otherwise use `scene.vlm.operation.primary`.
2. Resolves `modelOverride`:
   - If `model` is a raw model name, pass it as override.
   - If `model` is a scene id, no override.
3. Builds:

```kotlin
val systemPrompt = PromptTemplate.buildSystemPrompt(sceneId)
val currentUserText = PromptTemplate.buildTurnUserPrompt(context, sceneId)
val historyMessages = conversationState.historyMessages()
val messages = buildMessages(
    systemPrompt = systemPrompt,
    historyMessages = historyMessages,
    currentUserText = currentUserText,
    screenshot = screenshot,
    markedScreenshot = markedScreenshot,
    context = context,
    retryState = retryState
)
```

4. Sends:

```kotlin
ChatCompletionRequest(
    model = sceneId,
    modelOverride = modelOverride,
    messages = messages,
    maxCompletionTokens = 2048,
    temperature = 0.2,
    stream = true,
    streamOptions = ChatCompletionStreamOptions(includeUsage = true),
    tools = VLMToolDefinitions.tools(),
    toolChoice = JsonPrimitive("required"),
    parallelToolCalls = false
)
```

### System Prompt

`PromptTemplate.buildSystemPrompt(sceneId)`:

1. Reads runtime profile for `sceneId`.
2. Reads prompt template from `ModelSceneRegistry.getPrompt(sceneId)`.
3. If parser is `OPENAI_TOOL_ACTIONS`, injects:

```json
{"observation":"当前界面的关键状态","thought":"为什么要执行这个工具","summary":"执行完本步后新的历史总结"}
```

or English equivalent, from `VLMToolDefinitions.responseContract(...)`.
4. Renders `{{responseContract}}` and other placeholders with
   `ModelSceneRegistry.renderPrompt(...)`.

The default Chinese system prompt requires:

- The assistant is an Android phone GUI-Agent.
- Every turn must return exactly one native OpenAI `tool_call`.
- `assistant.content`, if present, may only be compact metadata JSON matching
  the response contract.
- Do not put action args, tool name, Markdown, or explanations in content.
- Use `finished` only when truly complete; `info` for user/manual help; `abort`
  when impossible.
- Follow M3A/Mobilerun-style one-change loop: use the current screenshot,
  Accessibility-tree/indexed evidence, previous tool result, and post-action
  feedback.
- Do not emit wait/idle/no-op actions.
- Coordinates are normalized 0-1000 scalars.
- `click`/`long_press` use only `x/y`; `scroll` uses only
  `x1/y1/x2/y2`; `input_text` should bind a visible input target and should
  include `element_index` when indexed evidence has the target.
- Do not type directly unless target input focus is confirmed; otherwise use
  `input_text`.
- Ignore OOB floating controls and task overlays.

### Dynamic User Prompt

`PromptTemplate.buildTurnUserPrompt(context, sceneId)` renders one text block
in this order:

1. Intro: current dynamic context plus screenshot and Accessibility-tree /
   indexed page evidence should determine next action.
2. `Scene: <sceneId>`
3. `Current time: <TimeUtil.getCurrentTimeString()>`
4. `User task: <context.overallTask>`
5. `Current sub-goal: <context.activeGoal()>`
6. `Skill guidance: <context.stepSkillGuidance or None>`
7. Optional urgent event section:
   - urgent event text
   - optional completion suggestion
8. Optional first-step page context:
   - `context.currentPageSummary`
   - `context.firstStepGuidance`
9. `Current state: <context.currentState or Unknown>`
10. `Suggested next step: <context.nextStepHint or None>`
11. `Completed milestones: <context.completedMilestones or None>`
12. `Key memory: <context.keyMemory or None>`
13. `History summary: <runningSummary or last trace summary or no history>`
14. `Installed apps:`
   - one line per installed app as `- <packageName> -> <appName>`
15. Output requirements:
   - choose exactly one tool from tools
   - scalar coordinate fields
   - `assistant.content` only metadata
   - package names must be copied from installed app list
   - do not guess default package names
   - if package cannot be identified, use `info/feedback`
   - M3A-style one-change loop with indexed evidence and previous tool result
   - no wait/idle/no-op

This means skill/recall/UDEG context all enters the LLM through the normal user
text, not as hidden state.

### Current User Message With Images

`VLMClient.buildCurrentUserMessage(...)` creates a single `role=user` message
whose `content` is a JSON array:

1. `{ "type": "text", "text": currentUserText }`
2. If screenshot exists:
   - text item: `Current screenshot.`
   - image item: screenshot base64
3. If marked screenshot fallback is explicitly enabled:
   - text item:
     `Marked screenshot with indexes matching OOB indexed page evidence.`
   - image item: marked screenshot base64

The default policy is screenshot + Accessibility-tree text, so normal VLM
requests contain one image, not raw + marked double images.
For same-screen protocol correction retries, `VLMOperationService` passes
`screenshot = null` when indexed Accessibility evidence is already present; the
retry request still includes the current page text plus the correction prompt,
but does not resend unchanged screenshot bytes.

### History Messages

`conversationState.historyMessages()` is inserted between system prompt and the
current user message. Each completed step appends a `VLMConversationRound`:

- Previous user message: plain `currentUserText` string, without images.
- Assistant message: original assistant `content` and native `toolCalls`.
- Tool message: JSON payload with:
  - `success`
  - `action`
  - `result`
  - optional `observation`
  - optional `summary`
  - post-action fields from `VLMPostActionObservation`:
    `screen_changed`, `package_changed`, `before_package`, `after_package`,
    `after_visible_texts`, `appeared_texts`, `disappeared_texts`,
    `after_focused_editable`, `post_action_observation`.

The previous tool result is treated as real execution feedback and is part of
the next prompt.

### Retry Prompt

When parsing fails because the model omitted or malformed native `tool_calls`,
`buildMessages(...)` appends two extra messages after the current user message:

1. Assistant message containing the previous raw content or reconstructed
   observation/thought/summary.
2. User message from `PromptTemplate.buildToolCallRetryPrompt(...)`.

The retry prompt says the prior tool call was invalid or missing, requires
exactly one native tool call, forbids metadata-only output, forbids putting
action args in `assistant.content`, repeats that `finished` is only for true
completion, and repeats scalar coordinate constraints.

## Online Context Injection

### Resolved Skills

Agent runtime skills are injected by `VlmToolHandler` into
`stepSkillGuidance`. The join delimiter is two newlines.

### OmniFlow Recall Guidance

`VlmRecallGuidanceBuilder.build(...)` calls:

```kotlin
OobOmniFlowToolkitService(context).recall(
    mapOf(
        "goal" to normalizedGoal,
        "current_package" to currentPackage,
        "k" to k,
        "decision_mode" to "context_only",
        "current_xml" to currentXml
    )
)
```

It sanitizes the payload for the agent and renders guidance starting with:

```text
OmniFlow UDEG node skill-like decision context:
path=page match -> UDEG node -> node skill-like decision context -> VLM/tool decision
decision=<decision>
function_execution_policy=optional_candidates_only; do_not_auto_execute=true; require_explicit_agent_selection=true; live_vlm_uses_native_screen_tools_only=true
```

This policy is separate from Function-as-model-tool exposure. Registered Functions
remain in the store and UDEG node attachments, but are not added as individual
model tools unless `oobFunctionAsToolEnabled=true`.

Then it may include:

- decision policy
- node candidates with node id, page similarity, decision path, decision
  context, node skill context, node skill body
- Function candidates with `function_id`, score, description, step summaries
- capability candidates
- segment candidates with `function_id`, page similarity, start step index,
  remaining step count, matched boundary, and remaining step summaries

In context-only mode these are not native VLM tools. The live VLM loop can use
them to choose grounded screen actions (`click`, `scroll`, `input_text`, etc.).
Direct replay requires a separate explicit Function run path.

`VlmTaskRequest.withRecallGuidance(...)` appends this rendered block to existing
`stepSkillGuidance` using two newlines.

### First-Step Optimizer

`VLMFirstStepOptimizer.enrichContext(...)` only applies when:

- `stepIndex == 0`
- `context.trace` is empty

It reads current XML and package, builds a first-screen summary, and adds a
first-step strategy. Example strategies include:

- If target package differs from current package, call `open_app` with the
  target package.
- If user asked to scroll, prefer scroll over clicking the first list item.
- If task text matches visible/actionable labels, click the matching candidate.
- If target app is already foreground, do not repeat `open_app`.
- If XML is unavailable, use screenshot and ask `info` rather than guessing.

For later steps it clears `currentPageSummary` and `firstStepGuidance`.

### UDEG Page Observation Provider

`App.kt` registers `OobVlmPageContextProvider`. During each step,
`VLMPageContextProviderRegistry.enrich(...)` calls it with live XML,
current package, screenshot, goal, and step index.

The provider calls `OobUdegNodeStore.observePage(...)`. It writes or updates a
UDEG page node and injects a compact page-match context into
`currentPageSummary`:

- decision path
- node id
- similarity
- whether this is first seen
- page title
- page role
- visible texts
- actionables
- attached reusable Function ids
- decision hints
- source: live screenshot/XML page match

### Indexed Page Evidence

`VLMIndexedPageContext.enrich(...)` parses live Accessibility XML and appends
evidence to the prompt. The rendered block starts with:

```text
OOB Accessibility tree / indexed page evidence (live Accessibility XML; coordinates are 0-1000 normalized):
```

It lists visible/actionable elements as `#<index>` plus role, label, bounds,
center, and flags. It also lists `Scrollable regions` with scrollable indexes.
The model can return `element_index` or `scrollable_index`; runtime grounding
then overwrites raw coordinates with the matched XML target center or safe
scroll gesture.

## Tool Schema

`VLMToolDefinitions.tools()` sends these OpenAI tools:

- `click(target_description, x, y, element_index?)`
- `input_text(target_description, content, x, y, element_index?)`
- `type(content)`
- `scroll(target_description, x1, y1, x2, y2, duration?, direction?,
  scrollable_index?)`
- `long_press(target_description, x, y, element_index?)`
- `open_app(package_name)`
- `press_home()`
- `press_back()`
- `hot_key(key)` where `key` is `ENTER`, `BACK`, or `HOME`
- `finished(content?)`
- `info(value)`
- `feedback(value)`
- `require_user_choice(options, prompt)`
- `require_user_confirmation(prompt)`

Arguments are normalized before validation:

- point aliases/arrays/objects can be coerced into scalar `x/y`
- range aliases can be coerced into scalar `x1/y1/x2/y2`
- `input_text.text` or `input_text.value` can become `content`

Validation still requires each coordinate field to be a single numeric scalar.

## Online RunLog Schema

VLM step cards come from `VLMOperationTask.buildInternalRunLogCard(...)`.
Important fields:

- `card_id`
- `tool_call_id`
- `header.step_index`
- `header.title`
- `header.tool_name`
- `header.status`
- `header.success`
- optional `header.duration_ms`
- optional `header.token_usage`
- optional `header.token_usage_total`
- `step_index`
- `title`
- `summary`
- `tool_name` and `toolName`
- `tool_type = "vlm"` and `toolType = "vlm"`
- `status`
- `action_type`
- `success`
- `error_message`
- `duration_ms`
- `started_at_ms`
- `finished_at_ms`
- `package_name`
- `token_usage`
- `token_usage_attempts`
- `compile_kind = "vlm_step"`
- `tool_call.id/name/arguments`
- `params`
- `result.message`
- `result.summary`
- post-action observation fields:
  `screen_changed`, `package_changed`, `after_visible_texts`,
  `appeared_texts`, `disappeared_texts`, `after_focused_editable`,
  `observation_summary`
- `before.observation`, `before.observation_xml`, `before.package_name`
- `after.summary`, `after.result`, `after.observation_xml`,
  `after.package_name`

The action duration is currently measured around actual action execution, not
the whole model step. Token usage is aggregated from OpenAI-compatible stream
usage and also retained per attempt.

## Manual Takeover Recording

Manual takeover is integrated with pause/resume:

1. User taps takeover -> `VLMOperationTask.requestPause()`.
2. `VLMOperationService` calls pause check before/inside step execution.
3. `VLMOperationTask.handleUserPause()`:
   - switches task state to paused
   - restores keyboard
   - tells UI: `已接管控制，完成操作后点击继续`
   - starts `ManualVlmTraceRecorder`
   - waits for `resumeFromPause()`
4. User taps continue -> recorder stops.
5. Recorded actions are appended to the same RunLog.
6. Recorder summary is appended as external memory so VLM continues from the
   current screen.

`ManualVlmTraceRecorder` records semantic Accessibility events, not raw touch:

- `TYPE_VIEW_CLICKED` -> `click`
- `TYPE_VIEW_LONG_CLICKED` -> `long_press`
- `TYPE_VIEW_TEXT_CHANGED` -> pending `input_text`
- `TYPE_VIEW_SCROLLED` -> pending `swipe`
- `TYPE_WINDOW_CONTENT_CHANGED` and `TYPE_WINDOW_STATE_CHANGED` update XML
  snapshots

It ignores OOB's own package and labels such as takeover/resume/OOB overlay
controls. It redacts password fields as `[REDACTED]`. It coalesces duplicate
discrete events within 400 ms and coalesces scroll/text changes into a single
action when flushed.

Manual RunLog cards use:

- `tool_type = "manual_recording"`
- `compile_kind = "manual_recording"`
- `source = "human_takeover"`
- action names aligned with replayable names: `click`, `long_press`,
  `input_text`, `swipe`, `press_back`, `press_home`
- before/after XML snapshots when available

## Offline Convert/Register Flow

`OobRunLogReplayService.convertRunLog(...)`:

1. Loads a RunLog by id.
2. Requires `finishedAtMs != null`.
3. Requires `success == true`.
4. Calls `RunLogReusableFunctionCompiler.compile(record)`.
5. Optionally applies function id/name/description overrides.
6. If `register = false`, returns the compiled spec only.
7. If `register = true`:
   - mirrors RunLog into workspace store
   - registers Function spec

`RunLogReusableFunctionCompiler.compile(...)`:

1. Keeps successful replayable cards.
2. Converts cards into OmniFlow replay steps.
3. Injects an initial model-free `open_app` step if the first step is not
   `open_app` and a launchable source package can be inferred.
4. Assigns `id = step_<n>` and zero-based `index`.
5. Infers string parameters from input text steps using deterministic bindings.
6. Emits:

```text
schema_version = oob.reusable_function.v1
execution.kind = tool_sequence
execution.runner = oob_tool_sequence
execution.entrypoint = execute
execution.steps = [...]
execution.step_count = <count>
```

`OobRunLogReplayService.registerFunctionSpec(...)`:

1. Normalizes `function_id`.
2. Calls `OobUdegNodeStore.upsertFunction(functionId, spec)`.
3. Registers into `WorkspaceFunctionStore`.
4. Registers into `OobReusableFunctionStore` SharedPreferences.
5. Enables OOB Function-as-tool feature flag.

`OobOmniFlowToolkitService.registerFunction(...)` is the public management
entrypoint used by MCP and in-app agent tools. It first checks for
`functionSpec/function_spec`. If a full spec is absent, it accepts the simple
conversation shape and builds:

```text
schema_version = oob.reusable_function.v1
source.kind = agent_registered_function
execution.kind = tool_sequence
execution.runner = oob_tool_sequence
execution.steps = normalized simple steps
```

Supported simple step actions are `open_app`, `click`, `long_press`,
`input_text`, `swipe`, `press_back`, `press_home`, `press_key`, `finished`, and
`call_tool`. `sourcePage.xml/packageName` is copied into the first step's source
context so UDEG page-match recall can attach the Function to a node.

## UDEG Node Skill

`OobUdegNodeStore` stores page nodes keyed by `OobPageVectorSet` page vectors.
The intended decision path is:

```text
page match -> UDEG node -> node skill-like decision context -> VLM/tool decision
```

When a Function is registered:

1. `extractSourcePage(functionSpec)` reads source page XML/package from the
   compiled Function.
2. `OobPageVectorSet.encode(xml, packageName)` creates a page vector.
3. The store finds a strong existing node or creates a new node id.
4. The Function summary is attached to that node.
5. Segment summaries are attached for replay boundaries.
6. The node stores:
   - `page_vector_set`
   - `page_analysis`
   - `skill`
   - `decision_context`
   - `functions`
   - `segments`
   - registry metadata

The node skill is structured information, not just free text. It contains:

- `schema_version = oob.udeg.node_skill.v1`
- `kind = udeg_node_skill`
- `activation.type = page_match`
- `activation.min_page_similarity`
- `activation.strong_page_similarity`
- `role = decision_context`
- `decision_path`
- `decision_rules`
- `decision_guidance`
- rendered `body`
- `capabilities`
- `segment_capabilities`
- function and segment counts

The decision context says:

- use the node skill only after page match localizes the current page
- treat attached Functions as outgoing reusable transitions
- keep grounding on live screenshot/XML
- if no Function fits the user goal, continue normal VLM actions

## Offline Recall Flow

`OobOmniFlowToolkitService.recall(args)` records internal timing with
`RecallTiming`. It measures:

- `parse_request_ms`
- `read_current_package_ms`
- `read_current_page_ms`
- `page_match_ms`
- `rank_functions_ms`
- `segment_match_ms`

The recall flow:

1. Parse args and goal (`goal`, `query`, or `task`).
2. Read current package from args or accessibility backend.
3. Read current XML from args or accessibility backend.
4. If XML is blank, return `decision = "miss"` with reason
   `missing_current_page_for_udeg_page_match`.
5. Recall UDEG nodes by page vector similarity.
6. Use the top decision node to rank node capabilities.
7. Rank node-attached Functions by page match plus goal text match.
8. Match segments from recalled Functions and node segment boundaries.
9. Choose decision:
   - `hit` for explicit direct no-argument strong Function hit only when the
     caller requested direct execution mode
   - `segment_hit` for explicit no-argument strong segment hit only when the
     caller requested direct execution mode
   - `segment_recall` when segment candidates exist
   - `recall` when node candidates exist
   - `miss` otherwise
10. Return `payload_mode = agent_compact` by default. The compact payload keeps
    decision policy, node id/package, candidates, segment candidates, compact
    step summaries, and Function call hints. It intentionally omits raw timing,
    full node skill body, page vectors, and skill artifacts.
11. If `include_debug=true`, return `payload_mode = debug_full` with the full
    internal payload, including `timing`, `node_skill`, `skill_artifact`, and
    page-vector metadata. Use this only in tests and diagnostics.

Current thresholds/weights:

- UDEG minimum page match: `0.30`
- UDEG strong page match: `0.87`
- recall minimum text score: `0.30`
- direct hit text/page/combined score: `0.999`
- page match weight: `0.70`
- goal match weight: `0.30`

`timing` returns:

```text
source = oob_omniflow_recall
decision = <decision>
started_at_ms
finished_at_ms
duration_ms
phase_ms = {
  parse_request_ms,
  read_current_package_ms,
  read_current_page_ms,
  page_match_ms,
  rank_functions_ms,
  segment_match_ms
}
counts = {...}
```

This timing is for internal logs/tests. Normal UI should not expose raw
`phase_ms` keys.

## Offline Call Function / Replay Flow

`OobOmniFlowToolkitService.callFunction(args)`:

1. Reads `function_id/functionId`, optional `goal`, optional `arguments`,
   optional `start_step_index` or segment aliases.
2. Runs guard check.
3. Calls `executeFunction(...)`.
4. Records Function run statistics.
5. Returns canonical payload:

```text
success
fallback
error
run_id
audit_run_id
function_id
segment_start_step_index
goal
actions_executed
step_results
timing
control
oob_result
guard
source = oob_native_omniflow_toolkit
```

`executeFunction(...)`:

1. Loads Function spec from replay service.
2. Checks required arguments.
3. Materializes spec with provided args.
4. Slices from `start_step_index` when executing a recalled segment.
5. Runs `OobFunctionToolHandler.runMaterializedFunction(...)`.

`OobFunctionToolHandler.runMaterializedFunction(...)`:

1. Starts run timing.
2. Blocks recursion and excessive nested call depth.
3. Reads materialized steps.
4. Runs accessibility preflight. If any non-skipped step needs accessibility
   and backend is not ready, returns `OOB_ACCESSIBILITY_REQUIRED`.
5. Executes each step:
   - OmniFlow primitive through `OmniflowStepExecutor`
   - nested OmniFlow tool/function/graph tools when present
   - delegated agent tool if router/environment are available
   - otherwise returns agent fallback requirement
6. Records each step result with `started_at_ms`, `finished_at_ms`,
   `duration_ms`.
7. Returns runner timing:

```text
timing.source = oob_function_runner
timing.started_at_ms
timing.finished_at_ms
timing.runner_duration_ms
```

Primitive replay in `OmniflowStepExecutor` supports:

- `click`
- `long_press`
- `scroll` / `swipe`
- `type` / `input_text`
- `open_app`
- `press_home`
- `press_back`
- `hot_key` / `press_key`
- `finished`

For coordinate actions, replay remaps args through `source_context` and current
XML using anchor projection. Remap metadata uses `algorithm = "anchor_projection"`
and records reasons such as `missing_source_context`, `missing_current_xml`, or
`no_anchor_match`.

## Online/Offline Interaction

There are three integration points:

1. Online VLM writes RunLog cards while executing.
2. Offline convert turns a successful RunLog into a reusable Function and
   attaches it to UDEG nodes.
3. Later online VLM calls recall from the current page. Default recall injects
   node-skill/Function/segment context into the prompt. A no-argument strong hit
   is replayed before the VLM loop only on explicit auto-execution requests; a
   user/agent can also run a selected Function through `oob_function_run` or
   `call_tool(function_id=...)`.
4. In an agent conversation, Function management uses the native workbench
   route: `oob_function_register` writes the Function store and UDEG references,
   `oob_function_list/get` inspect candidates, `oob_function_guard_check`
   preflights replay, and `oob_function_delete/clear` remove Function and UDEG
   references. The preferred conversation path is simple registration first,
   full spec import only when the artifact already exists.

This is why the online system must keep collecting high-quality `before` and
`after` XML/package context. Replay and segment recall depend on those fields.

## Token and Timing Observability

Currently available:

- VLM cards record per-action `duration_ms`, `started_at_ms`,
  `finished_at_ms`.
- VLM cards record `token_usage` and `token_usage_attempts`.
- VLM stream logs include token usage per request attempt.
- Recall records structured `duration_ms` and `phase_ms`.
- Function runner records `runner_duration_ms`.
- Function runner step results record per-step `duration_ms`.

Not yet fully structured:

- screenshot capture time per VLM step
- XML capture time per VLM step
- first-step optimizer time
- page provider/UDEG observe time inside VLM step
- indexed page evidence render time
- VLM HTTP stream duration as a RunLog field
- parse/tool-retry time as RunLog fields
- post-action stability wait time as RunLog fields

Some of these exist as logcat `TimeRecord` entries, but they are not yet
standard RunLog fields. If we need detailed speed attribution, add a
`vlm_phase_ms` map to VLM cards rather than exposing it directly in UI.

Recent validation note:

- A previous simple recall/replay open-app-like path completed around
  2.6s-2.9s plus about 1.2s post-run settle.
- A later fresh rerun on `emulator-5554` and `emulator-5556` was blocked because
  OOB accessibility reported unavailable current page XML:
  `blank_effective_package`.
- Do not claim broad AndroidWorld success without a real task run and state
  verification.

## UI Contract

User-facing UI should use understandable labels:

- Function -> `复用指令`
- Function library -> `复用指令库`
- Function details -> `复用指令详情`
- Function result -> `复用指令执行结果`

Cards should show compact user-level tags such as steps, params, and runlogs.
Raw implementation fields like `kind`, `OmniFlow`, `phase_ms`,
`read_current_page_ms`, and `duration_ms` should stay in payload/tests/logs, not
as visible primary labels.

## Validation Checklist

For a complete local validation pass:

1. Unit tests:
   - VLM request prompt/message assembly.
   - tool arg normalization and validation.
   - RunLog compiler concrete action conversion.
   - UDEG page vector recall and node skill context.
   - Function runner timing and permission failure payload.
   - UI hides raw internal timing labels.
2. Device flow on `emulator-5554` or `emulator-5556`:
   - accessibility enabled
   - overlay enabled
   - current XML nonblank
   - run a simple VLM task that is not a smoke test
   - inspect RunLog cards for token usage and before/after XML
   - convert successful RunLog
   - run the reusable Function
   - run recall from the same page and confirm node/segment candidates
3. Manual takeover:
   - start VLM
   - tap takeover
   - perform click and simple scroll
   - tap continue
   - confirm manual cards are in the same RunLog with
     `source = human_takeover`
   - confirm VLM receives the manual summary as external memory

## Known Failure Modes

- Missing accessibility means direct replay cannot click/scroll/input and
  returns `OOB_ACCESSIBILITY_REQUIRED`.
- Missing current XML makes UDEG recall miss with
  `missing_current_page_for_udeg_page_match`.
- Weak VLM prompts can still finish incorrectly; `finished` is not proof of
  app-state success. Always validate final device state for task claims.
- Coordinate replay depends on source/current XML quality. Anchor projection
  can fail if the target page is structurally different or inaccessible.
- Manual recording uses semantic Accessibility events, so it records click,
  long press, text, and simple scroll. It is not raw gesture trajectory capture.
