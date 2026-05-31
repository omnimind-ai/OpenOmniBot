---
name: oob-function-management
description: Manage OOB reusable commands from chat. Use for RunLog registration, "把上一条 runlog 注册了", "保存为复用指令", "转换轨迹", "执行复用指令", update_function, list/get/delete reusable functions, and variable-parameter replay.
---

# OOB Function Management

Use this skill when the user asks from chat to manage OOB RunLogs or reusable
commands. Work only from OOB's real local stores through tools. Do not invent a
RunLog, Function id, or mock payload.

All OOB/OmniFlow behavior is skill-first. This skill is the product-facing
capability for RunLog registration, Function management, and explicit replay.
Native Kotlin provides storage, replay, UDEG indexing, and tool backends only;
the focused `function_management` profile is just this skill's tool budget. Do
not create a separate OmniFlow agent, controller, or component registry for
these workflows.

## Tool Budget

This skill runs under the `function_management` tool profile. The expected tool
set is intentionally small:

- `oob_run_log_list`
- `oob_run_log_convert`
- `oob_function_list`
- `oob_function_get`
- `oob_function_register`
- `update_function`
- `oob_function_guard_check`
- `oob_function_run`
- `oob_function_delete`
- `oob_function_clear`
- `oob_run_log_get`
- any registered OOB Function exposed as its own tool

If a needed tool is not exposed, explain the missing capability instead of
switching to unrelated tools. Treat tool names as implementation details; user
responses should talk about "复用指令", "轨迹", and "执行结果", not MCP/tool
plumbing.

## Workflows

### Register The Previous RunLog

For requests like "把上一条 runlog 注册了" or "保存刚才的轨迹为复用指令":

1. Call `oob_run_log_list` and select the newest successful RunLog.
2. If no successful RunLog exists, report that there is nothing to register.
3. Call `oob_run_log_convert` with `register=true` and the selected `run_id`.
4. Report the real `function_id`, step count if present, and whether the RunLog
   is now bound as registered.

Do not call `oob_function_register` for a RunLog unless the user gives a full
Function spec manually. RunLog conversion is the canonical registration path.

### Enhance Or Repair A Function

For requests like "增强这个复用指令" or "应该点 A 而不是 B":

1. If the Function id is missing, call `oob_function_list` and choose only when
   there is a clear single candidate. Otherwise ask which Function to update.
2. Call `oob_function_get`.
3. Call `update_function`.
4. If `requires_confirmation=true`, show the candidate steps and ask the user.
5. Report exactly what was saved. Do not claim the Function executed.

### Analyze RunLog Evidence For update_function

Use this when a Function should learn from an existing RunLog, especially after
`oob_function_run` failed or after a successful run shows a better path.

1. Resolve the Function id and RunLog id. Prefer the `run_id` returned by
   `oob_function_run`, `oob_run_log_list`, or `oob_run_log_get`.
2. Call `update_function` with `functionId` and `run_id` only. This returns
   `needs_agent_analysis=true`, `analysis_context`, and `agent_prompt`.
3. Compare `analysis_context.function.steps` with `analysis_context.runlog.cards`.
   Mark each useful action as one of:
   `required_action`, `optional_checker`, `noise`, `duplicate`,
   `failed_action`, or `success_evidence`.
4. Identify the concrete success or failure reason. Use one of:
   `wrong_target`, `target_missing`, `ad_interruption`, `repeated_input`,
   `unstable_coordinate`, or `unknown`.
5. Build an `analysis` object in this shape:

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

6. Call `update_function` again with `functionId`, `run_id`, `analysis`, and
   the smallest safe `patch`. If no safe modification is clear, pass an empty
   `recommended_patch.ops`; the evidence analysis is still useful metadata.

Rules:

- If unsure, do not change the main path.
- Ads, skip buttons, close popups, and transient interruptions are
  `optional_checker` evidence, not mandatory replay steps.
- `wait`, pure perception wrappers, failed cards, and repeated input are noise
  unless they explain a concrete failure.
- Successful RunLogs may improve descriptions, step titles/summaries, selector
  hints, and evidence metadata.
- Failed RunLogs may change a step only when the evidence is explicit.

### Execute A Function

For requests like "执行这个复用指令" or "用刚才的指令填写 Eve":

1. Resolve the Function id using explicit user text, `oob_function_list`, or the
   registered dynamic tool name.
2. If runtime parameters are required, map them from the user request.
3. Prefer calling the registered Function tool directly when it is exposed.
   Otherwise call `oob_function_run`.
4. If `oob_function_run` returns `fallback_context`, complete only the failed
   step using the live phone state, then call `oob_function_run` again with the
   provided `resume_from_step`, `fallback_session_id`, and `fallback_attempt`.
   Do not restart from step 0 unless the Function was repaired or the user asks.
5. If the same step repeatedly fails and `fallback_available=false`, stop the
   loop and report the real blocker. If the Function definition is wrong, call
   `update_function` before running it again.
6. Report the tool result.

### Skill Boundary

- Use `vlm-android-gui` for live phone operation and first-run RunLog
  collection.
- Use this skill for chat-driven RunLog registration, command lookup, command
  execution, and deletion.
- Use `omniflow-function-enhancer` when a saved command needs better names,
  descriptions, or runtime parameters.
- Never move these workflows into the generic Agent system prompt. If behavior
  changes, update the owning skill first, then adjust native tool contracts only
  when the skill lacks a backend operation.

### List, View, Delete

- Use `oob_function_list` to show available reusable commands.
- Use `oob_function_get` to inspect one command.
- Use `oob_function_delete` only for a specific Function id.
- Clearing all Functions is not part of the small chat profile; ask the user to
  use the settings/library UI if they truly want bulk deletion.

## Constraints

- Function recall is candidate context, not proof of task completion.
- Do not auto-execute a Function just because it was recalled.
- Do not expose or summarize large raw RunLog JSON unless the user asks for
  details.
- Do not generate fake conversion success. Tool errors are user-visible facts.
- Do not add a separate OmniFlow agent/component registry for these workflows.
  Update the skill and native tool backend contracts instead.
