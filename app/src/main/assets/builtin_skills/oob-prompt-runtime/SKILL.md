---
name: oob-prompt-runtime
description: Design, implement, or review OOB Prompt Runtime. Use for "系统提示词", "提示词运行时", "提示词组装", "prompt dump", "项目上下文注入", "上下文预算", "初始输入契约", "prompt runtime", static core, dynamic environment, device state, current app, memory, locale, tool instructions, task prompts, or prompt debugging.
---

# OOB Prompt Runtime

Use this skill when changing how an OOB agent builds, inspects, or debugs model prompts.

The goal is not to add another large prompt. The goal is a typed runtime that assembles small, auditable prompt sections from product state.

## Core Rules

1. Keep Prompt Runtime in Kotlin product code. Use this skill as the implementation guide, not as the runtime itself.
2. Replace giant system prompts with ordered `PromptSection` providers.
3. Every section must have a stable id, source, order, role, enabled reason, token budget, cache policy, and sensitivity level.
4. Dynamic sections must be bounded. Device state, current app, memory, skills, and tool instructions should never grow without a budget.
5. Generate tool instructions from the live tool registry. Do not duplicate tool descriptions in handwritten prompts.
6. Prompt dump is mandatory for debugging. It must be redacted by default and safe to share with maintainers.

## Initial Input Contract

Before building any model prompt, normalize runtime state into a strict `PromptRuntimeInput`. Do not start from a loose string plus a giant system prompt.

The initial input must explicitly contain:

- run identity: `runId`, `conversationId`, `conversationMode`, task type, parent task id when present
- user request: raw user text, normalized goal, language, explicit constraints, requested output shape
- model context: model scene, model override, reasoning effort, streaming mode, current date/time/timezone
- workspace context: workspace id, shell root, current cwd, Android path, retention policy
- project context: active Project id/name/spacePath, doc paths, API ids, frontend context, selected element id
- device context: granted services, permission snapshot, Shizuku backend, foreground app, compact screen summary
- memory context: enabled memory scopes, conversation summary, long-term memory, daily memory, retrieval query
- skill context: installed skill index, selected skills, trigger reasons, reference/script/asset paths
- tool context: live tool registry, enabled tools, tool permission class, MCP server names, confirmation rules
- attachments: metadata and safe references only; no raw base64 or unredacted personal file content
- budgets: prompt threshold, reserved completion budget, per-section caps, dump redaction mode

Unset values must be explicit: use `null` plus a reason in metadata. Never let providers guess missing state from previous runs.

## Section Set

Use these section ids unless there is a strong reason to add another:

- `static_core`: agent identity, global behavior, output discipline
- `permission_policy`: Android, file, shell, browser, MCP, calendar, and Shizuku permission rules
- `dynamic_environment`: runtime mode, conversation mode, model scene, workspace identity
- `device_state`: screen lock, network, battery, granted services, package context
- `current_app`: foreground package, activity, app label, relevant UI snapshot summary
- `project_context`: active Workbench project, `PROJECT_SOUL.md`, `PROJECT_CONTEXT.md`, API contract, frontend binding requirements
- `memory_context`: long-term memory, daily memory, task summary, retrieved memory hits
- `locale_context`: user language, app locale, timezone, formatting preferences
- `skill_context`: loaded skills and the specific reason each skill was loaded
- `tool_instructions`: available tools, tool limits, tool result protocol
- `task_mode_prompt`: mode-specific instructions for chat, VLM task, scheduled task, Workbench, or companion task
- `output_contract`: final answer shape, citation/artifact/notification requirements

## Implementation Workflow

1. Locate current prompt assembly points. Search for `systemPrompt`, `buildPrompt`, `toolsForModel`, `contextSummary`, `memory`, and `conversationMode`.
2. Add a small prompt model: `PromptSection`, `PromptBundle`, `PromptRuntimeInput`, `PromptRuntimeBuilder`, and section providers.
3. Move existing prompt text into section providers without changing behavior first.
4. Wire dynamic providers to existing services: conversation history, workspace memory, skill runtime, tool registry, app/device state, and model scene config.
5. Add redacted prompt dumps before model calls. Dumps should be available through developer logs and a user-visible debug action.
6. Add focused tests for ordering, enablement, redaction, token budget trimming, and prompt dump schema.

## Workbench Project Integration

Do not merge this skill into `oob-project-designer` or `oob-native-workbench`.

Use this boundary:

- `oob-project-designer` owns project intent extraction, API design, frontend/backend contracts, `PROJECT_SOUL.md`, and `PROJECT_CONTEXT.md`.
- `oob-native-workbench` owns runtime display contracts, data binding, HTML skeletons, and Workbench tool usage.
- `oob-prompt-runtime` owns how project context becomes bounded prompt sections for `run: {use: "agent"}`, scheduled project tasks, hot updates, and prompt dump debugging.

For any Workbench Project agent task, the project-specific prompt material should enter through `project_context`, not by pasting full project docs into `run.prompt`. The `run.prompt` should stay as the task request: inputs, required tools, expected writes back to `workbench_api_call`, and completion signal.

## Dump Contract

Prompt dumps should be JSON records under a debug-only location such as `.omnibot/debug/prompts/`.

Include:

- `runId`, `conversationId`, `conversationMode`, `modelScene`, `model`, and timestamp
- ordered section metadata: id, source, order, role, enabled, reason, estimated tokens, sensitivity, cache policy
- redacted rendered content for each enabled section
- disabled sections with disabled reason
- tool count and tool names, without leaking secrets from tool configs
- compact token budget summary: prompt estimate, threshold, reserved completion budget

Never include:

- raw credentials, auth headers, cookies, API keys, signing credentials, `.env` values, or keystore paths
- raw screenshots, base64 images, personal files, or full attachment payloads
- unrestricted MCP server secrets or browser session data

## Verification

Before finishing a Prompt Runtime change:

1. Run unit tests for prompt section ordering and dump redaction.
2. Verify at least one real agent call can be dumped and the dump explains why each section was present.
3. Confirm tools still match `AgentToolRegistry` output.
4. Confirm context compaction and memory retrieval still fit inside the prompt budget.
5. Inspect a redacted dump manually before exposing it in UI.

Read `references/prompt-runtime-design.md` for the detailed architecture and migration plan.
