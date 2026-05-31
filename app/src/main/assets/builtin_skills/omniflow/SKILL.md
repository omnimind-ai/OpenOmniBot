---
name: omniflow
description: OmniFlow reusable Android GUI workflow skill. Use when the user wants to reuse, run, register, repair, enhance, analyze, or debug OOB RunLogs and saved Functions, including update_function, oob_function_run, RunLog evidence, replay fallback, checker design, action cleanup, "应该点 A 而不是 B", "保存为复用指令", "增强 function", "从第 N 步继续", and ad/popup optional checkers.
---

# OmniFlow

Use OmniFlow for reusable Android GUI behavior in OOB: converting RunLogs into
Functions, managing saved Functions, enhancing or repairing Functions, replaying
Functions, and learning from replay evidence.

Native Kotlin/MCP code provides storage, replay, UDEG indexing, and tool
backends. The agent behavior belongs in this skill and its references.

## Route The Task

- Function lifecycle or chat management: read `references/function-management.md`.
- Function enhancement, repair, or step labeling: read `references/function-enhancement.md`.
- RunLog success/failure evidence or `run_id`: read `references/runlog-evidence.md`.
- Replay failure, agent fallback, or resume from a step: read `references/replay-fallback.md`.
- Ads, popups, permission nudges, skip/close buttons: read `references/checkers.md`.
- Noisy, duplicate, or unclear actions: read `references/canonical-actions.md`.
- Tool name choice or legacy compatibility: read `references/tools.md`.

Load only the reference needed for the current task. Do not load every reference
by default.

## Core Rules

- Prefer `oob_function_*`, `oob_run_log_*`, and `update_function` for in-app
  OOB Function work.
- Use `oob_function_run` for replay and pass `start_step_index` when resuming.
- Use `update_function` for all saved Function modifications.
- Treat RunLogs as evidence. Do not invent RunLogs, Function ids, screenshots,
  XML, or tool results.
- When a recalled or exposed Function clearly matches the user goal, prefer
  `oob_function_guard_check` followed by `oob_function_run` over live VLM
  clicking. Treat live VLM as fallback when no Function matches, guard fails, or
  replay returns agent fallback.
- Do not run a low-confidence Function. Recall is candidate context until the
  agent decides it matches the current user goal and guard policy.
- Mark transient obstruction handling as optional checkers, not mandatory happy
  path actions.
- Do not add, delete, or reorder executable steps unless the user explicitly
  requests a structural repair.

## Output Discipline

When reporting to the user, use product language: "复用指令", "轨迹",
"执行结果", "已增强", "需要继续处理". Avoid exposing raw MCP/tool plumbing
unless the user asks for implementation details.
