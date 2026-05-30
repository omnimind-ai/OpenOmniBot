---
name: omniflow-function-enhancer
description: Enhance or repair an existing OmniFlow reusable Function created from a RunLog. Use for 增强, update_function, Function 纠错, "应该点 A 而不是点 B", runtime parameters, agent_reuse metadata, cleanup/noise annotations, background enhancement status, and explicit enhanced unchanged partial failed reporting.
---

# OmniFlow Function Updater

Use this skill when the user wants Agent to improve or correct an existing
OmniFlow reusable Function that was created from a RunLog.

This skill edits the saved Function spec. The RunLog is evidence and provenance
only. Do not execute the Function unless the user separately asks to run it.

The runtime prompt uses the shared contract in
`references/runtime-contract.md`. Keep that file, the Flutter asset
`ui/assets/execution_history/omniflow_function_enhancer_contract.md`, and the
Dart constant `kOmniFlowFunctionEnhancerContract` in sync.

## Core Rule

All saved changes must go through the `update_function` tool. Do not ask the
model to rewrite and register a full Function JSON by hand.

Default mode is `enhance`: make reuse clearer without silently changing
execution.

Allowed in `enhance` mode:

- Rewrite `name` and `description` so the Function reads like a reusable
  command. The description should be compact but detailed enough for later
  Agent selection: what visible operations it performs, where it applies,
  runtime inputs, and success signal when known.
- Rewrite per-step `title`, `summary`, or `description`.
- Add or improve runtime parameter metadata for existing non-coordinate leaf
  args.
- Add non-executable `agent_reuse` metadata for selection, reuse boundaries,
  key actions, success signal, and future segment review.
- Add per-step `cleanup_annotation` metadata for keep, merge candidate,
  drop candidate, or noise.
- Write an explicit enhancement report into metadata.

Forbidden in `enhance` mode:

- Do not change `function_id`.
- Do not reorder, insert, delete, or split executable steps.
- Do not change tool names, executors, concrete args, validation, fallback, or
  callable tool definitions.
- Do not bind parameters to coordinates, bounds, width, height, screenshots, XML
  nodes, or invented JSON paths.
- Do not register a UDEG node, page memory, or node decision context as a
  user-installable global skill. UDEG node material is a page-scoped structured
  skill artifact used as recall evidence after page match.

Use `repair` mode only when the user explicitly says the recorded Function did
the wrong concrete action, for example "应该点「外卖」而不是点「美食」".
Repair mode may update the targeted step's semantic target, selector hints, and
coordinates/bounds when the desired target can be found in source/current XML.
If more than one step matches, call `update_function` with `dryRun=true` or let
the tool return `requires_confirmation=true`; then ask the user which candidate
to save.

## Input Checklist

Before enhancing, inspect the Function spec and extract:

- Function id, source RunLog id, app/package constraints, and current name.
- Step list with index, id, tool/action, executor, title/summary, and compact
  args preview.
- Existing parameters and bindings.
- Candidate binding paths from non-coordinate args only, such as:
  `$.execution.steps[2].args.text`.
- Current `agent_reuse` metadata if present.
- Any existing `metadata.oob_enhancement` report.
- For repair: target action, wrong text, desired text, candidate step index,
  source/current XML availability, and whether the desired target has a unique
  node/bounds.

Do not send full screenshots, source XML, raw accessibility trees, or unrelated
RunLog payloads to the label enhancer. Use a compact digest.

## Enhancement Workflow

1. Read the Function with `oob_function_get`.
2. Header pass: produce a concise action-oriented command name and a compact
   reusable description that states the visible operation sequence, required
   app/page conditions, runtime inputs, and success signal when known.
3. Step pass: produce one title and one useful description for every existing
   step index.
4. Cleanup pass: mark deterministic noise, merge candidates, and drop
   candidates as metadata only.
5. Parameter pass: add runtime slots only from allowed candidate bindings.
6. Reuse pass: write `agent_reuse` metadata.
7. Validate the patch against the original Function.
8. Call `update_function` with `mode="enhance"` and the structured patch.
9. Report the final status explicitly from the tool result.

Run the work as a background UI task when invoked from the Function sheet. The
user should see progress immediately and should not have to infer whether the
click did anything.

## Repair Workflow

Use this when the user gives a correction like "应该点 A 而不是点 B".

1. Read the Function with `oob_function_get`.
2. Parse the correction:
   - `action`: click, input_text, long_press, swipe, etc.
   - `wrong_text`: the target that was wrongly used.
   - `desired_text`: the target the Function should use.
3. Build a structured patch operation:

```json
{
  "ops": [
    {
      "op": "replace_target",
      "action": "click",
      "wrong_text": "美食",
      "desired_text": "外卖"
    }
  ]
}
```

4. Call `update_function` with `mode="repair"` and the patch. The tool is the
   validator and writer.
5. If the tool returns `requires_confirmation=true`, show the candidate step
   indexes/titles and ask the user to choose. Then call `update_function` again
   with `step_index`.
6. If saved, summarize the exact step changed and whether coordinates/bounds
   were updated from XML. Do not claim the Function ran successfully.

For the sentence "应该点「外卖」而不是点「美食」", call:

```json
{
  "function_id": "<id>",
  "instruction": "应该点「外卖」而不是点「美食」",
  "mode": "repair",
  "patch": {
    "ops": [
      {
        "op": "replace_target",
        "action": "click",
        "wrong_text": "美食",
        "desired_text": "外卖"
      }
    ]
  }
}
```

## Structural Workflow

Use this only when the user explicitly asks to add or remove executable
actions, for example "在点击提交前先点搜索" or "删除重复的等待步骤".

Structural updates are never part of default enhancement. They require
`allowStructuralChange=true` and should normally use `mode="repair"` because the
execution sequence changes.

Insert an action:

```json
{
  "function_id": "<id>",
  "mode": "repair",
  "allowStructuralChange": true,
  "patch": {
    "ops": [
      {
        "op": "insert_step",
        "step_index": 2,
        "step": {
          "action": "click",
          "title": "点击外卖入口",
          "target_description": "外卖"
        }
      }
    ]
  }
}
```

Delete an action:

```json
{
  "function_id": "<id>",
  "mode": "repair",
  "allowStructuralChange": true,
  "patch": {
    "ops": [
      {
        "op": "delete_step",
        "step_index": 3,
        "reason": "重复等待，已被前一步覆盖。"
      }
    ]
  }
}
```

Prefer `step_index` from the current Function spec. After insert/delete, report
that downstream indexes may have changed and name the exact inserted or removed
step. Do not invent coordinates for a new action; include coordinates only when
they come from the Function context, XML, or an explicit user correction.

## Status Contract

Every enhancement attempt must end in exactly one visible status:

- `enhanced`: at least one safe, meaningful change was applied and saved.
- `unchanged`: Agent checked the Function and found no safe useful change.
- `partial`: at least one safe change was saved, but one or more sections failed
  or were skipped.
- `failed`: no usable enhancement was produced; keep the Function unchanged and
  allow retry.

Persist status under `metadata.oob_enhancement`:

```json
{
  "schema_version": "oob.function_enhancement.v1",
  "source": "run_log_agent_label_enhancer",
  "status": "enhanced",
  "changed": true,
  "message": "Agent enhancement applied and saved.",
  "updated_at": "2026-05-29T00:00:00.000Z",
  "sections": [
    {"part": "header", "status": "parsed", "accepted": true},
    {"part": "steps", "status": "parsed", "accepted": true},
    {"part": "parameters", "status": "parsed", "accepted": true},
    {"part": "agent_reuse", "status": "parsed", "accepted": true}
  ]
}
```

The UI should show `后台增强中`, then one of `已增强`, `已检查`,
`部分增强`, or `重试增强`.

The tool also writes `metadata.oob_function_update`:

```json
{
  "schema_version": "oob.function_update.v1",
  "tool": "update_function",
  "mode": "repair",
  "status": "updated",
  "changed": true
}
```

## Patch Contract

Header patch:

```json
{"name":"创建联系人","description":"在联系人编辑页填写联系人姓名和手机号，并提交保存表单。当前页面是同类联系人编辑表单时可复用；保存后联系人详情页展示刚写入的信息即为成功。"}
```

Step patch:

```json
{
  "steps": [
    {"index":0,"title":"填写联系人姓名","description":"向姓名输入框写入运行时联系人姓名。"}
  ]
}
```

Parameter patch:

```json
{
  "parameters": [
    {
      "name": "contact_name",
      "type": "string",
      "description": "运行时联系人姓名",
      "default": "妈妈",
      "bindings": ["$.execution.steps[0].args.text"]
    }
  ]
}
```

Reuse patch:

```json
{
  "agent_reuse": {
    "reuse_when": ["当前页面是同一个联系人编辑表单。"],
    "avoid_when": ["目标页面不是联系人编辑页，或字段语义不一致。"],
    "success_signal": "联系人详情页展示刚写入的姓名或手机号。",
    "key_actions": [
      {"step_index":0,"reason":"写入运行时联系人姓名","parameter_names":["contact_name"]}
    ],
    "segments": [
      {
        "name":"填写联系人字段",
        "start_step_index":0,
        "end_step_index":1,
        "description":"可作为未来拆分候选的连续填写片段。",
        "inputs":["contact_name","phone_number"]
      }
    ]
  }
}
```

Cleanup patch:

```json
{
  "steps": [
    {
      "index": 1,
      "cleanup_annotation": {
        "schema_version": "oob.step_cleanup_annotation.v1",
        "decision": "merge_candidate",
        "reason": "重复输入同一字段，可与上一步合并展示。"
      }
    }
  ]
}
```

## Quality Gates

- Name: short, user-facing, verb-object style.
- Description: says when the Function is reusable, not how the internals work.
- Step labels: describe visible intent, not raw coordinates.
- Parameters: semantic names like `contact_name`, `search_query`,
  `message_text`, `target_date`, `target_url`.
- Bindings: copied exactly from candidate bindings and point to existing args.
- `agent_reuse.reuse_when`: concrete page/app preconditions.
- `agent_reuse.avoid_when`: concrete mismatch or risk cases.
- `success_signal`: visible final state, not "tool returned success".
- `key_actions.step_index`: valid existing indexes only.
- `segments`: contiguous inclusive ranges only; metadata only, not registered
  standalone commands.
- Repair must identify a concrete step. Ambiguous repair must return/ask for
  confirmation instead of guessing.
- Insert/delete actions require an explicit user request and
  `allowStructuralChange=true`; never perform them during ordinary enhancement.
- Repair may preserve old coordinates only if the tool reports that no XML
  target was found; tell the user that the semantic target was changed but
  coordinate refresh was not confirmed.

Read `references/enhancement-rubric.md` when you need more examples or need to
judge whether a patch should be `enhanced`, `unchanged`, `partial`, or `failed`.
