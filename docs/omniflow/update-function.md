# update_function Contract

`update_function` is the write path for Function enhancement and repair.
Offline enhancement and user corrections should both save through this tool.

## Input

```json
{
  "function_id": "takeout_entry",
  "mode": "repair",
  "instruction": "应该点「外卖」而不是点「美食」",
  "patch": {
    "ops": [
      {
        "op": "replace_target",
        "step_index": 1,
        "from": "美食",
        "to": "外卖"
      }
    ]
  },
  "dryRun": false,
  "allowExecutionChange": true,
  "allowStructuralChange": false
}
```

## Modes

```text
enhance: annotate and clean a captured Function without running it
repair: apply a user correction such as retargeting a click
```

Structural operations such as `insert_step` and `delete_step` require
`allowStructuralChange=true`.

## Output

The result should identify whether the Function changed, whether it was saved,
and which fields were updated.

```json
{
  "success": true,
  "mode": "repair",
  "changed": true,
  "saved": true,
  "changes": [],
  "warnings": []
}
```

## Invariants

- Every saved step is parsed by `OobActionCodec`.
- Every role decision goes through `OobStepRoleClassifier`.
- Checker rules are validated against the runtime whitelist.
- Enhancement is offline and must not execute the Function.
- User natural language can guide a patch, but the saved result must be
  structured and canonical.
