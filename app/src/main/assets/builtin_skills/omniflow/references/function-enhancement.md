# Function Enhancement And Repair

Use this reference when improving an existing saved Function.

## Core Rule

All saved changes go through `update_function`. Do not rewrite and re-register a
full Function JSON by hand.

## Enhancement Mode

Default mode is `enhance`: improve reuse clarity without silently changing
execution.

Allowed changes:

- Rewrite `name` and `description` so the Function reads like a reusable
  command. Include visible operations, where it applies, runtime inputs, and
  success signal when known.
- Rewrite per-step `title`, `summary`, or `description`.
- For every executable step, state what the action does and why it exists.
- Add `cleanup_annotation.action_purpose` for durable step purpose labels.
- Add runtime parameter metadata from existing non-coordinate leaf args only.
- Add `agent_reuse` metadata: `reuse_when`, `avoid_when`, `success_signal`, and
  `key_actions`.
- Mark deterministic noise, merge candidates, drop candidates, and optional
  checkers as metadata.

Forbidden in `enhance` mode:

- Do not change `function_id`.
- Do not reorder, insert, delete, or split executable steps.
- Do not change tool names, executors, concrete args, validation, fallback, or
  callable tool definitions.
- Do not bind parameters to coordinates, bounds, width, height, screenshots,
  XML nodes, or invented JSON paths.

## Repair Mode

Use `repair` when the user says the recorded Function did the wrong concrete
action, for example "应该点「外卖」而不是点「美食」".

Patch shape:

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

If more than one step matches, let `update_function` return confirmation
candidates or call it with `dryRun=true`, then ask the user which candidate to
save.

## Structural Repair

Only add or delete executable actions when the user explicitly asks for it.
Use `allowStructuralChange=true`.

Insert:

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

Delete:

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

Do not invent coordinates for a new action. Include coordinates only when they
come from Function context, XML, or an explicit user correction.

## Status Contract

Every enhancement attempt ends in exactly one status:

- `enhanced`: meaningful safe changes were saved.
- `unchanged`: checked and found no safe useful change.
- `partial`: some safe changes were saved, but part of the enhancement failed
  or was skipped.
- `failed`: no usable enhancement was produced.
