# update_function Contract

`update_function` is the write path for Function enhancement and repair.
Offline enhancement and user corrections should both save through this tool.

## Input

```json
{
  "function_id": "takeout_entry",
  "run_id": "omniflow_run_...",
  "mode": "repair",
  "instruction": "应该点「外卖」而不是点「美食」",
  "analysis": {
    "summary": "RunLog shows the saved Function clicked 美食 but the intended stable target is 外卖.",
    "step_findings": [],
    "failure_reason": {
      "code": "wrong_target",
      "message": "The failed click target does not match the intended entry."
    },
    "recommended_patch": {
      "ops": []
    }
  },
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

## RunLog Evidence Mode

`update_function` can also take a local `run_id`.

- `update_function({functionId, run_id})` reads the Function and RunLog, then
  returns `needs_agent_analysis=true`, `analysis_context`, and `agent_prompt`.
  It does not modify or save the Function.
- The agent analyzes that context and calls `update_function` again with
  `analysis` and an optional `patch`.
- `analysis` is saved under Function metadata as RunLog evidence. If `patch`
  is empty, only evidence metadata is saved.

The native tool does not run a rules engine for this mode. The built-in
`oob-function-management` skill owns the analysis prompt and decides what patch
is safe.

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
