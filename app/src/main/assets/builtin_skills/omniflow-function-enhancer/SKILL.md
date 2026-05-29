---
name: omniflow-function-enhancer
description: Enhance an existing OmniFlow reusable Function created from a RunLog. Use for 增强, RunLog 转 Function 增强, execution-structure enhancement planning, runtime parameters, agent_reuse metadata, background enhancement status, and explicit enhanced unchanged partial failed reporting.
---

# OmniFlow Function Enhancer

Use this skill when the user wants Agent to improve an existing OmniFlow
reusable Function that was created from a RunLog.

This skill is for Function enhancement, not replay execution. The target is the
saved Function spec. The RunLog is evidence and provenance only.

The runtime prompt uses the shared contract in
`references/runtime-contract.md`. Keep that file, the Flutter asset
`ui/assets/execution_history/omniflow_function_enhancer_contract.md`, and the
Dart constant `kOmniFlowFunctionEnhancerContract` in sync.

## Core Rule

Enhancement must make reuse clearer without silently changing execution.

Allowed by default:

- Rewrite `name` and `description` so the Function reads like a reusable
  command.
- Rewrite per-step `title`, `summary`, or `description`.
- Add or improve runtime parameter metadata for existing non-coordinate leaf
  args.
- Add non-executable `agent_reuse` metadata for selection, reuse boundaries,
  key actions, success signal, and future segment review.
- Write an explicit enhancement report into metadata.

Forbidden unless the user explicitly asks for execution-structure enhancement:

- Do not change `function_id`.
- Do not reorder, insert, delete, or split executable steps.
- Do not change tool names, executors, concrete args, validation, fallback, or
  callable tool definitions.
- Do not bind parameters to coordinates, bounds, width, height, screenshots, XML
  nodes, or invented JSON paths.
- Do not register a UDEG node, page memory, or node decision context as a skill.
  UDEG node material is recall evidence, not a user-installable skill.

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

Do not send full screenshots, source XML, raw accessibility trees, or unrelated
RunLog payloads to the label enhancer. Use a compact digest.

## Enhancement Workflow

1. Header pass: produce a concise action-oriented command name and one-sentence
   description.
2. Step pass: produce one title and one useful description for every existing
   step index.
3. Parameter pass: add runtime slots only from allowed candidate bindings.
4. Reuse pass: write `agent_reuse` metadata.
5. Validate the patch against the original Function.
6. Apply only metadata-safe changes.
7. Save the patched Function back to the same `function_id`.
8. Report the final status explicitly.

Run the work as a background UI task when invoked from the Function sheet. The
user should see progress immediately and should not have to infer whether the
click did anything.

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

## Patch Contract

Header patch:

```json
{"name":"创建联系人","description":"在联系人编辑页填写姓名和手机号。"}
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

Read `references/enhancement-rubric.md` when you need more examples or need to
judge whether a patch should be `enhanced`, `unchanged`, `partial`, or `failed`.
