# Function Management

Use this reference for chat-driven RunLog registration and saved Function
lifecycle.

## Register The Previous RunLog

For requests like "把上一条 runlog 注册了" or "保存刚才的轨迹为复用指令":

1. Call `oob_run_log_list`.
2. Select the newest successful RunLog.
3. If no successful RunLog exists, report that there is nothing to register.
4. Call `oob_run_log_convert` with `register=true` and the selected `run_id`.
5. Report the real `function_id`, step count, and registration status.

Do not call `oob_function_register` for a RunLog unless the user gives a full
Function spec manually. RunLog conversion is the canonical registration path.

## List, Inspect, Delete

- Use `oob_function_list` to show available reusable commands.
- Use `oob_function_get` to inspect a single command before repair, replay, or
  deletion.
- Use `oob_function_delete` only for a specific Function id.
- Use `oob_function_clear` only when the user explicitly asks to clear all saved
  Functions.

## Register A Manual Function

Use `oob_function_register` only when the user or another tool provides a
complete Function spec or a small explicit Function definition. Do not invent
execution steps from a vague natural-language request.

## User-Facing Report

Report the concrete saved Function id, name, source RunLog id if present, and
whether registration updated an existing Function or created a new one.
