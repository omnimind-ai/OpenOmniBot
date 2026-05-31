# Replay Fallback And Resume

Use this reference when `oob_function_run` fails or returns agent fallback
context.

## Normal Replay

1. Resolve the Function id.
2. Inspect with `oob_function_get` if the Function is not already known.
3. Fill required runtime parameters from the user request.
4. Run with `oob_function_run`.
5. Report the real run result.

## Fallback To Agent

If `oob_function_run` returns `fallback_context`, do not restart the whole
Function immediately.

1. Read the failed step, failed reason, current screen context, and
   `resume_from_step`.
2. Complete only the failed step using the live phone state or the bounded VLM
   path available to the caller.
3. Call `oob_function_run` again with the provided resume data:

```json
{
  "functionId": "<id>",
  "start_step_index": 4,
  "fallback_session_id": "<session>",
  "fallback_attempt": 1
}
```

4. Continue from the next step when the fallback succeeds.

## Repair Before Retry

If the fallback shows the Function definition is wrong, call `update_function`
before running again. Examples:

- The Function clicked "美食" but should click "外卖".
- The target selector points to stale text.
- A coordinate-only step no longer maps to the intended node.

## Stop Conditions

Stop and report the blocker when:

- The same step fails repeatedly.
- `fallback_available=false`.
- The action is risky and needs confirmation.
- The Function needs structural repair and the user did not authorize it.
