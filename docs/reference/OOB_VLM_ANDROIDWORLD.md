# OOB VLM AndroidWorld Runtime

This document is the long-term maintenance note for OOB's online Android GUI
VLM path and AndroidWorld-style validation. It describes the OOB-adapted design,
not a 1:1 port of any external agent.

## Scope

This path covers live phone operation through `vlm_task`:

- online VLM action selection
- Accessibility XML and screenshot grounding
- AndroidWorld-style tasks that require multi-step navigation and visible
  verification
- RunLog collection, token usage reporting, conversion, and replay validation

It does not cover generic memory recall, Workbench Project generation, browser
automation, or offline Python harness compatibility.

## Design Boundary

OOB borrows the useful evidence pattern from Android GUI agents but keeps the
runtime native and OOB-specific:

- The VLM still receives the raw screenshot as the primary visual signal.
- OOB builds an indexed page evidence section from live Accessibility XML.
- OOB may attach a marked screenshot whose numeric indexes match that evidence.
- The VLM emits OOB tool calls using normalized `0-1000` coordinates, not
  external index-based action JSON.
- After each executed action, OOB records a compact post-action observation in
  the tool-result history so the next step can compare intent with state change.
- All AndroidWorld prompt policy lives in the `vlm-android-gui` skill, not in a
  benchmark-specific core optimizer.

The invariant is simple: every action should be grounded in current
screenshot/XML evidence, change one intended UI variable, and verify the visible
result before `finished`.

## Runtime Flow

```text
VLMOperationService
  -> capture screenshot
  -> read current Accessibility XML and package
  -> enrich UIContext through page-context providers
  -> append OOB indexed page evidence
  -> optionally render a marked screenshot
  -> VLMClient builds OpenAI tool-call request
  -> VLM returns one OOB GUI action
  -> action executes through DeviceOperator
  -> after XML/package is captured
  -> VLMClient stores post-action observation in conversation history
  -> RunLog records cards, token usage, and timing
```

Key source files:

- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMOperationService.kt`
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMClient.kt`
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMIndexedPageContext.kt`
- `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMPostActionObservation.kt`
- `app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md`
- `app/src/debug/java/cn/com/omnimind/bot/debug/DebugVlmRunLogReceiver.kt`
- `scripts/demo-vlm-runlog-e2e.sh`

## Indexed Page Evidence

`VLMIndexedPageContext` parses live Accessibility XML and produces an OOB-owned
evidence block:

```text
OOB indexed page evidence (live Accessibility XML; coordinates are 0-1000 normalized and match the marked screenshot indexes):
#0 center=(430,473) bounds=(...) flags=click role=TextView label="Network & internet"
Scrollable regions:
S0 vertical_down=(500,890)->(500,220) label="Settings"
```

Each candidate keeps only compact information the model can act on:

- normalized center and bounds
- role from Android class name
- visible label from text, content description, hint, descendant text, resource
  tail, or role
- flags such as `click`, `long`, `edit`, `edit_focused`, `scroll`,
  `checkable`, `checked`, and `selected`
- scrollable region suggestions for deliberate list navigation

Filtering rules are intentionally generic:

- keep visible and enabled nodes
- skip tiny nodes and huge whole-screen containers
- deduplicate by bounds, label, role, and package
- ignore OOB overlay labels such as `接管`, `继续执行`, `小万`, `omnibot`, and
  `oob`

The marked screenshot is optional. If it is present, `VLMClient` sends it after
the raw screenshot with an explicit text label so the model knows which image is
raw and which image carries OOB indexes.

## Post-Action Observation

`VLMPostActionObservation` summarizes the result of the previous executed step
from before/after XML and package names. The history tool result may include:

- `screen_changed`
- `package_changed`
- `before_package`
- `after_package`
- `after_visible_texts`
- `after_focused_editable`
- `post_action_observation`

This is deliberately compact. It is decision evidence for the next action, not a
second model call and not a separate memory store.

## Skill-Owned Policy

`app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md` owns the online VLM
policy used for AndroidWorld-like tasks:

- pass `packageName` when the target app is known
- use `maxSteps` around `8-20` for AndroidWorld or acceptance tests
- use OOB indexed page evidence before guessing coordinates
- type directly only when an editable field is focused; otherwise click the
  intended input first and type on the next step
- scroll a listed scrollable region when the target is absent
- handle sliders with horizontal `scroll`, not repeated `click`
- treat multi-target tasks as ordered checklists
- validate after at least two visible UI states before `finished`

Do not move benchmark prompt rules into `VLMFirstStepOptimizer` unless the rule
is a general safety or execution invariant that applies outside AndroidWorld.

## RunLog, Convert, And Replay

A successful online VLM run should leave a RunLog with concrete action cards and
token usage. The debug E2E receiver converts a finished RunLog into an OmniFlow
Function when the log is reusable.

Expected debug result fields:

- `run_id`
- `runlog_found`
- `runlog_success`
- `runlog_card_count`
- `token_usage`
- `token_usage_total`
- `token_usage_by_step`
- `token_usage_by_call`
- `convert`

Every VLM provider call should report token usage when the provider returns it.
The debug script fails validation if total token usage, per-step usage, or
per-call usage is missing.

## Validation

Unit coverage:

```bash
./gradlew --no-daemon :assists:testDebugUnitTest \
  --tests cn.com.omnimind.assists.task.vlmserver.VLMIndexedPageContextTest \
  --tests cn.com.omnimind.assists.task.vlmserver.VLMPostActionObservationTest \
  --tests cn.com.omnimind.assists.task.vlmserver.ActionTargetGrounderTest \
  --tests cn.com.omnimind.assists.task.vlmserver.VLMActionPostProcessorTest \
  --tests cn.com.omnimind.assists.task.vlmserver.VLMFirstStepOptimizerTest

./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
  --tests cn.com.omnimind.assists.task.vlmserver.VLMClientRequestTest \
  --tests cn.com.omnimind.assists.task.vlmserver.VLMToolDefinitionsTest \
  --tests cn.com.omnimind.bot.mcp.McpToolDefinitionsTest \
  --tests cn.com.omnimind.bot.agent.BuiltinSkillManifestConsistencyTest
```

Build validation:

```bash
./gradlew --no-daemon :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

Device validation:

```bash
scripts/demo-vlm-runlog-e2e.sh \
  --device emulator-5556 \
  --goal "From the Settings home screen, open Apps, enter Default apps, verify Browser app or Phone app is visible, then finish." \
  --max-steps 12
```

The E2E script performs:

1. provider binding setup
2. debug VLM broadcast
3. raw VLM execution
4. RunLog lookup
5. token usage validation
6. RunLog conversion to Function
7. registered Function replay

Use tasks that cross multiple visible states. Do not use one-click smoke tasks
as acceptance evidence.

Recommended AndroidWorld-style task set:

- Settings: scroll to About phone, open it, verify title or device info.
- Settings: Apps -> Default apps, verify Browser app or Phone app.
- Clock: open Alarms, verify list or empty state without creating an alarm.
- Contacts: search or open an existing contact, verify detail page without
  editing.
- Settings panel slider: verify horizontal `scroll` semantics for brightness or
  volume when a safe emulator state is available.

## Known Limitations

- Accessibility XML may omit inaccessible custom views.
- A marked screenshot is not generated in JVM unit tests because Android graphics
  APIs are runtime-dependent.
- Provider token usage is available only when the configured provider returns
  usage metadata.
- Device E2E requires a connected emulator or device and an enabled OOB
  Accessibility service.
- If `adb connect 127.0.0.1:5554` or `adb connect 127.0.0.1:5556` returns
  connection refused, runtime validation is blocked until the emulator is
  started or forwarded.
