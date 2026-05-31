# RunLog Evidence For update_function

Use this reference when a Function should learn from an existing RunLog,
especially after replay failure or after a successful run shows a better path.

## Evidence Flow

1. Resolve the Function id and RunLog id. Prefer a `run_id` returned by
   `oob_function_run`, `oob_run_log_list`, or `oob_run_log_get`.
2. Call `update_function` with `functionId` and `run_id` only.
3. Expect `needs_agent_analysis=true`, `analysis_context`, and `agent_prompt`.
4. Compare `analysis_context.function.steps` with
   `analysis_context.runlog.cards`.
5. Build `analysis` and the smallest safe `patch`.
6. Call `update_function` again with `functionId`, `run_id`, `analysis`, and
   optional `patch`.

## Required Analysis Shape

```json
{
  "summary": "这次 RunLog 说明 Function 为什么成功/失败",
  "step_findings": [
    {
      "function_step_index": 1,
      "runlog_card_index": 3,
      "label": "点击外卖入口",
      "role": "required_action | optional_checker | noise | duplicate | failed_action | success_evidence",
      "reason": "为什么这样判断"
    }
  ],
  "failure_reason": {
    "code": "wrong_target | target_missing | ad_interruption | repeated_input | unstable_coordinate | unknown",
    "message": "具体原因"
  },
  "recommended_patch": {
    "ops": []
  }
}
```

## Evidence Rules

- If unsure, do not change the main path.
- Ads, skip buttons, close popups, coupons, permission nudges, and transient
  interruptions are `optional_checker` evidence, not mandatory replay steps.
- `wait`, pure perception wrappers, failed cards, and repeated input are noise
  unless they explain a concrete failure.
- Successful RunLogs may improve descriptions, step titles, summaries, selector
  hints, success signals, and evidence metadata.
- Failed RunLogs may change a step only when the evidence is explicit.

## Failure Codes

- `wrong_target`: the Function selected the wrong visible target.
- `target_missing`: the expected target was not present.
- `ad_interruption`: an ad, popup, or overlay blocked the happy path.
- `repeated_input`: repeated text or duplicate input caused the issue.
- `unstable_coordinate`: coordinates or bounds no longer match the target.
- `unknown`: evidence is insufficient.
