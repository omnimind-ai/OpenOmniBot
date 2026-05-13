# Prompt Runtime Design

This reference describes the target architecture for OOB Prompt Runtime.

## Why This Exists

OOB agents run inside an Android product with device automation, local memory, skills, MCP, Workbench, browser, terminal, calendar, alarms, and Shizuku actions. A single giant system prompt makes these responsibilities hard to debug and easy to regress.

Prompt Runtime should make every model call answer these questions:

- Which prompt sections were included?
- Why was each section included?
- Which runtime state produced it?
- What was redacted?
- How much prompt budget did it consume?
- Which tools and permissions were visible to the model?

## Data Model

Use a compact typed model. Names can be adjusted to match local style.

```kotlin
enum class PromptRole { SYSTEM, USER_CONTEXT }

enum class PromptSensitivity {
    PUBLIC,
    INTERNAL,
    PERSONAL,
    SECRET
}

enum class PromptCachePolicy {
    NONE,
    EPHEMERAL,
    STABLE
}

data class PromptSection(
    val id: String,
    val source: String,
    val order: Int,
    val role: PromptRole = PromptRole.SYSTEM,
    val content: String,
    val enabled: Boolean = true,
    val reason: String = "",
    val estimatedTokens: Int? = null,
    val budgetTokens: Int? = null,
    val sensitivity: PromptSensitivity = PromptSensitivity.INTERNAL,
    val cachePolicy: PromptCachePolicy = PromptCachePolicy.NONE,
    val metadata: Map<String, Any?> = emptyMap()
)

data class PromptBundle(
    val sections: List<PromptSection>,
    val messages: List<ChatCompletionMessage>,
    val dump: PromptDump
)
```

Section providers should be pure enough to test. They may read product state, but they should return plain data rather than directly mutating the request.

```kotlin
interface PromptSectionProvider {
    val id: String
    suspend fun build(input: PromptRuntimeInput): PromptSection?
}
```

## Initial Input Contract

`PromptRuntimeInput` is the only object section providers should read. Build it once at the start of a run, then pass the same immutable input to all providers.

Recommended shape:

```kotlin
data class PromptRuntimeInput(
    val runId: String,
    val parentTaskId: String? = null,
    val conversationId: Long?,
    val conversationMode: String,
    val taskType: String,
    val userRequest: UserRequestInput,
    val model: ModelInput,
    val workspace: AgentWorkspaceDescriptor,
    val project: ProjectInput? = null,
    val device: DeviceInput,
    val memory: MemoryInput,
    val skills: SkillInput,
    val tools: ToolInput,
    val attachments: List<AttachmentInput>,
    val budget: PromptBudgetInput,
    val debug: PromptDebugInput
)

data class UserRequestInput(
    val rawText: String,
    val normalizedGoal: String,
    val language: String,
    val explicitConstraints: List<String> = emptyList(),
    val requestedOutputShape: String? = null
)

data class ProjectInput(
    val projectId: String,
    val name: String,
    val entityName: String?,
    val spacePath: String,
    val projectSoulPath: String?,
    val projectContextPath: String?,
    val apiIds: List<String>,
    val frontendContext: Map<String, Any?> = emptyMap()
)

data class ToolInput(
    val toolNames: List<String>,
    val permissionClasses: Map<String, String>,
    val mcpServers: List<String>,
    val confirmationRequiredTools: List<String>
)

data class PromptBudgetInput(
    val thresholdTokens: Int,
    val reservedCompletionTokens: Int,
    val sectionCaps: Map<String, Int>
)
```

Rules:

- Every nullable field must be null because the state is genuinely unavailable, not because the builder forgot to fill it.
- Put absence reasons into provider metadata or disabled-section reasons.
- Store references to large resources by path/id and summarize them into bounded sections.
- Do not pass raw screenshots, base64 attachments, credentials, or browser session data into `PromptRuntimeInput`.
- The input builder is responsible for redaction labels and sensitivity classification before any dump is written.

## Section Ordering

Use deterministic ordering. Keep gaps so new sections can be inserted without renumbering.

| Order | Section | Source |
|---:|---|---|
| 100 | `static_core` | product-owned constant prompt |
| 200 | `permission_policy` | local permission and sandbox policy |
| 300 | `dynamic_environment` | active conversation/run/model/workspace state |
| 400 | `device_state` | Android service and capability state |
| 500 | `current_app` | foreground package and UI state summary |
| 550 | `project_context` | active Workbench project, project docs, API contract |
| 600 | `memory_context` | `WorkspaceMemoryService` and conversation summary |
| 700 | `locale_context` | `AppLocaleManager`, timezone, formatting hints |
| 800 | `skill_context` | loaded skills and trigger reasons |
| 900 | `tool_instructions` | `AgentToolRegistry` and runtime descriptors |
| 1000 | `task_mode_prompt` | chat, VLM, scheduled, Workbench, companion mode |
| 1100 | `output_contract` | final response and artifact contract |

## Provider Responsibilities

### Static Core Provider

Contains only stable behavior that applies to every OOB agent run. Keep it short. Do not put tool catalogs, current device state, model-specific details, or user memory here.

### Permission Policy Provider

Summarizes what the agent may do without confirmation, what requires confirmation, and what is blocked. Generate privileged action details from policy code such as Shizuku action policy instead of duplicating a stale list.

### Dynamic Environment Provider

Includes conversation mode, run id, workspace id/path, current model scene, target package, schedule context, Workbench context, and whether the run is foreground or background.

### Device State Provider

Includes only actionable device state: accessibility enabled, overlay enabled, screenshot availability, Shizuku backend, network status, screen lock state, and package install facts that affect tool choice.

### Current App Provider

Includes foreground package/activity and a compact screen summary. Do not dump raw XML trees into the system prompt. Put raw UI data behind tools or short-lived context blocks with explicit budgets.

### Project Context Provider

Used only when the run is attached to a Workbench Project or a Project tool execution.

Includes bounded excerpts from:

- `PROJECT_SOUL.md`: user intent, business rules, display preferences, prohibited behavior
- `PROJECT_CONTEXT.md`: API contract, field schema, data layout, frontend element inventory
- active project metadata: project id, name, entity name, API ids, active display route

Do not paste full project files into every call. Prefer summarized sections with file paths and last modified timestamps. If a task needs exact project docs, expose them through file tools or a targeted project context tool.

For `run: {use: "agent"}` Project APIs, the user-level `run.prompt` should contain only the task-specific workflow. Global rules, tool policy, project docs, permissions, memory, and locale should come from Prompt Runtime sections.

### Memory Context Provider

Combines:

- long-term memory summary
- today's short memory
- conversation context summary
- retrieved memory hits for the current user goal

Separate facts by scope. Do not merge user preferences, project knowledge, and task state into one anonymous paragraph.

### Skill Context Provider

Only include skills that were actually selected for this run. For each skill include id, name, trigger reason, and the loaded instruction body or a bounded excerpt. Do not load every installed skill into every prompt.

### Tool Instructions Provider

Use the live tool catalog. Include concise global tool rules, then rely on tool schemas for arguments. Tool prompt text should explain sequencing, safety, result interpretation, and constraints that schemas cannot express.

### Task Mode Provider

Keep mode-specific behavior out of static core. Examples:

- normal chat
- screen/VLM task
- scheduled task
- Workbench project agent
- companion overlay task
- memory rollup task

### Output Contract Provider

Controls how the final answer is shaped. Keep this late in the bundle so it can override presentation details without redefining agent identity or permissions.

## Prompt Dump Schema

Recommended JSON shape:

```json
{
  "schemaVersion": 1,
  "runId": "agent-run-id",
  "conversationId": 123,
  "conversationMode": "normal",
  "modelScene": "scene.dispatch.model",
  "model": "provider/model",
  "createdAtMillis": 1760000000000,
  "budget": {
    "estimatedPromptTokens": 12000,
    "thresholdTokens": 128000,
    "reservedCompletionTokens": 4096
  },
  "sections": [
    {
      "id": "memory_context",
      "source": "WorkspaceMemoryService",
      "order": 600,
      "role": "SYSTEM",
      "enabled": true,
      "reason": "long-term memory and daily memory available",
      "estimatedTokens": 900,
      "budgetTokens": 1600,
      "sensitivity": "PERSONAL",
      "cachePolicy": "NONE",
      "contentRedacted": "..."
    }
  ],
  "disabledSections": [
    {
      "id": "current_app",
      "reason": "no foreground package available"
    }
  ],
  "tools": {
    "count": 18,
    "names": ["terminal_execute", "file_read"]
  },
  "redactions": [
    {
      "type": "secret",
      "count": 3
    }
  ]
}
```

## Redaction Rules

Redact before persistence, UI display, or log upload.

Always redact:

- API keys, tokens, auth headers, cookies, session ids
- `.env` values, signing properties, keystore names, release credentials
- raw screenshots, base64 media, image OCR content unless summarized
- personal file contents unless the user explicitly requested a dump for that file
- MCP server credentials and browser storage

Prefer replacing sensitive content with markers such as `[REDACTED:api-key]` and include counts in dump metadata.

## Migration Plan

1. Add prompt model and dump model without changing behavior.
2. Wrap existing prompt construction as one `legacy_static_core` section.
3. Split out tool instructions and permission policy first, because these change most often.
4. Split Workbench project docs and API contract into `project_context`.
5. Split memory and context compaction into `memory_context`.
6. Split device and current app state into bounded dynamic sections.
7. Add UI or developer command to view latest redacted prompt dump.
8. Remove the legacy section only after parity tests pass.

## Tests

Minimum tests:

- sections are sorted by order and stable id
- disabled sections appear in dump but not rendered messages
- secret patterns are redacted before dump persistence
- tool names in dump match `AgentToolRegistry`
- memory context respects budget and truncation order
- project context includes active project docs only when a project run is active
- locale context changes language-specific prompt text without changing unrelated sections
- prompt dump remains valid JSON when sections contain quotes, Unicode, or tool schemas

## Anti-Patterns

- appending a new paragraph to a giant `systemPrompt`
- copying full tool schemas into handwritten prompt text
- dumping raw Accessibility XML into every request
- putting user memory in static core
- saving unredacted prompt dumps for convenience
- allowing skills or MCP servers to rewrite prompt policy files at runtime
