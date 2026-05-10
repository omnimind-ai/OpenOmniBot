package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.i18n.AppLocaleManager

/**
 * MCP 工具定义
 */
object McpToolDefinitions {
    private fun brandName(): String = AppLocaleManager.brandName()
    
    val vlmTaskTool = mapOf(
        "name" to "vlm_task",
        "description" to """Execute an autonomous VLM (Visual Language Model) agent task on an Android device.

This tool enables AI-driven device automation by using a visual language model to understand screen content and perform actions. The agent will:
1. Analyze the current screen state using screenshots
2. Reason about the next best action to achieve the goal
3. Execute UI actions (tap, scroll, input text, etc.)
4. Iterate until the goal is achieved or intervention is needed

Use cases:
- Automate repetitive mobile tasks (ordering food, sending messages, etc.)
- Navigate complex app workflows autonomously
- Extract information from mobile applications
- Perform multi-step operations across different apps

IMPORTANT FOR SUMMARY TASKS:
- If the user's goal is to summarize, extract key points, or produce a report (e.g., "总结/汇总/整理/概括/提炼" or "summary/recap"),
  you MUST set needSummary=true to get the summary back in the tool result.
- When needSummary=true, the final response will include a Summary section and a `summary` field.

BEHAVIOR:
- This tool BLOCKS and waits for the task to complete or require input (up to 2 minutes)
- If the agent needs clarification, the response will include the agent's question
- When you receive a WAITING_INPUT response, use 'task_reply' to answer the agent
- After replying, the tool will again wait for completion or next interaction
- Provide clear, specific goals for best results

WORKFLOW:
1. Call vlm_task with your goal
2. If response shows WAITING_INPUT with a question, call task_reply with your answer
3. Repeat step 2 if the agent asks more questions
4. Task completes when you receive a FINISHED status
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf(
                    "type" to "string",
                    "description" to "The task goal in natural language. Be specific and clear. Example: 'Open WeChat and send a message saying Hello to contact John'"
                ),
                "model" to mapOf(
                    "type" to "string",
                    "description" to "Optional: AI model identifier to use for vision reasoning. Leave empty for default."
                ),
                "packageName" to mapOf(
                    "type" to "string",
                    "description" to "Optional: Target app package name (e.g., 'com.tencent.mm' for WeChat). If not specified, the agent will start from the current screen."
                ),
                "needSummary" to mapOf(
                    "type" to "boolean",
                    "description" to "Optional: Set true for summarization/report tasks so the summary is generated and returned in the tool result. Default: false."
                )
            ),
            "required" to listOf("goal")
        )
    )

    val taskStatusTool = mapOf(
        "name" to "task_status",
        "description" to """Query the current status of a VLM task (for long-running tasks that timed out).

This is a backup tool - normally vlm_task and task_reply will wait and return the final status.
Only use this if a previous call timed out but the task is still running.

Returns the task state including:
- status: RUNNING, WAITING_INPUT, USER_PAUSED, FINISHED, ERROR, CANCELLED
- message: Status message or error description
- waitingQuestion: When status is WAITING_INPUT, contains the question the agent is asking
- chatMessages: Recent agent reasoning/action messages
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID returned from vlm_task execution."
                )
            ),
            "required" to listOf("taskId")
        )
    )

    val taskReplyTool = mapOf(
        "name" to "task_reply",
        "description" to """Provide user input to a VLM task that is waiting for input.

WHEN TO USE:
When vlm_task returns with status WAITING_INPUT, the agent is asking a question.
Use this tool to answer the question and the task will continue.

BEHAVIOR:
- This tool BLOCKS and waits for the task to complete or require more input (up to 2 minutes)
- After providing your reply, the agent will resume and this tool returns the next status
- If the agent asks another question, you'll receive another WAITING_INPUT response
- Continue the conversation until the task completes (FINISHED status)

Common scenarios:
- Agent asks for verification code: reply with the code
- Agent asks which song to play: reply with the song name
- Agent asks for confirmation: reply '确认' or specific instructions
- Agent needs manual intervention: reply '已完成操作，继续执行' after completing the action
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID of the waiting task."
                ),
                "reply" to mapOf(
                    "type" to "string",
                    "description" to "The user's reply or input to provide to the agent."
                )
            ),
            "required" to listOf("taskId", "reply")
        )
    )

    val taskWaitUnlockTool = mapOf(
        "name" to "task_wait_unlock",
        "description" to """Wait for the device screen to be unlocked and resume/start a paused VLM task.

WHEN TO USE:
When you receive a SCREEN_LOCKED status, ask the user to unlock their phone,
then call this tool to wait for unlock and automatically resume the task.

BEHAVIOR:
- This tool BLOCKS and waits for the screen to be unlocked (up to 2 minutes)
- Once unlocked, if this is a new task it will start execution
- If this is a paused task, it will resume from where it left off
- Returns the next task status (FINISHED, WAITING_INPUT, etc.)
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID of the screen-locked task."
                )
            ),
            "required" to listOf("taskId")
        )
    )

    val fileTransferTool
        get() = mapOf(
        "name" to "file_transfer",
        "description" to """Retrieve files shared to the ${brandName()} app on the Android device.

WORKFLOW:
1. Use vlm_task to navigate to the file and choose "Open with" or "Share" -> 小万.
2. Call this tool to fetch file metadata and a short-lived download URL.
3. Download the file from the returned URL (valid for about 15 minutes).

ACTIONS:
- latest (default): return the most recently received file
- wait: block until a new file arrives (timeoutMs, default 120000)
- list: list recent received files
- get: fetch a file by fileId
- clear: delete one file (fileId) or all files

NOTES:
- Files are stored temporarily on the device (about 2 hours).
- Download URLs are only reachable on the same LAN.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "latest | wait | list | get | clear. Default: latest."
                ),
                "fileId" to mapOf(
                    "type" to "string",
                    "description" to "Target file ID (required for action=get; optional for action=clear)."
                ),
                "afterFileId" to mapOf(
                    "type" to "string",
                    "description" to "For action=wait, only return a file newer than this ID."
                ),
                "timeoutMs" to mapOf(
                    "type" to "integer",
                    "description" to "For action=wait, max wait time in milliseconds (default 120000)."
                ),
                "limit" to mapOf(
                    "type" to "integer",
                    "description" to "For action=list, max number of items to return."
                )
            )
        )
    )

    val agentRunTool = mapOf(
        "name" to "agent_run",
        "description" to """Submit a prompt into the normal in-app ${brandName()} Agent runtime.

Use this when you need OOB itself to create or modify Workbench Projects, call internal Agent tools, or run a toolvox-style validation without relying on visual typing into the Flutter Home input.

This is not a Workbench debug shortcut:
- It starts the same Agent task path used by WebChat/Home.
- The Agent must call Workbench tools such as workbench_project_create and workbench_api_call by itself.
- Workbench control APIs are still not exposed as MCP tools or Project business APIs.

BEHAVIOR:
- Returns once the Agent run is accepted.
- Use WebChat events, task logs, or Project runtime files to verify completion.
- Do not claim Project creation succeeded until workspace/projects/<project-id>/project.json exists on the device.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "userMessage" to mapOf(
                    "type" to "string",
                    "description" to "The user prompt to submit to the normal OOB Agent runtime."
                ),
                "conversationId" to mapOf(
                    "type" to "integer",
                    "description" to "Optional existing OOB conversation id. If omitted, a new conversation is created."
                ),
                "conversationMode" to mapOf(
                    "type" to "string",
                    "description" to "Optional conversation mode. Defaults to normal."
                ),
                "title" to mapOf(
                    "type" to "string",
                    "description" to "Optional title when creating a new conversation."
                ),
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "Optional stable task id for correlation. If omitted, OOB generates one."
                ),
                "attachments" to mapOf(
                    "type" to "array",
                    "description" to "Optional image/file attachments in the same shape accepted by WebChat."
                ),
                "modelOverride" to mapOf(
                    "type" to "object",
                    "description" to "Optional providerProfileId/modelId override in the same shape accepted by WebChat."
                )
            ),
            "required" to listOf("userMessage")
        )
    )

    val oobProjectCreateTool = mapOf(
        "name" to "oob_project_create",
        "description" to """Create or reuse an OOB Workbench Project.

This is the MCP control entry for Project creation. It writes the normal Workbench runtime files under /workspace/projects/<project-id>/ and registers Project business APIs. It does not add Workbench control APIs to the Project Toolbox.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "projectId" to mapOf("type" to "string", "description" to "Stable Project id. Example: oob-workbench-v01-quick-note"),
                "templateId" to mapOf("type" to "string", "description" to "todo_log_demo | schema_app | quick_capture_inbox"),
                "name" to mapOf("type" to "string", "description" to "Human-readable Project name."),
                "prompt" to mapOf("type" to "string", "description" to "Original creation prompt preserved in Project files."),
                "entityName" to mapOf("type" to "string", "description" to "Schema entity name for schema_app Projects."),
                "apis" to mapOf("type" to "array", "description" to "Optional explicit business API contracts.")
            )
        )
    )

    val oobProjectActivateTool = mapOf(
        "name" to "oob_project_activate",
        "description" to "Activate one OOB Project so its business APIs are mounted as the current MCP Toolbox.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "projectId" to mapOf("type" to "string", "description" to "Project id to activate.")
            ),
            "required" to listOf("projectId")
        )
    )

    val oobProjectOpenTool = mapOf(
        "name" to "oob_project_open",
        "description" to "Open a Project's native OOB Flutter Display route on the device.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "projectId" to mapOf("type" to "string", "description" to "Project id to open.")
            ),
            "required" to listOf("projectId")
        )
    )

    val oobProjectProgressGetTool = mapOf(
        "name" to "oob_project_progress_get",
        "description" to "Read recent Project creation/import progress rows from the Workbench progress log.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "projectId" to mapOf("type" to "string", "description" to "Optional Project id. Defaults to active/latest context when supported."),
                "limit" to mapOf("type" to "integer", "description" to "Maximum rows to return. Defaults to 50.")
            )
        )
    )

    val fixedTools
        get() = listOf(
            vlmTaskTool,
            taskStatusTool,
            taskReplyTool,
            taskWaitUnlockTool,
            fileTransferTool,
            agentRunTool,
            oobProjectCreateTool,
            oobProjectActivateTool,
            oobProjectOpenTool,
            oobProjectProgressGetTool
        )

    val fixedToolNames: Set<String>
        get() = fixedTools.mapNotNull { it["name"]?.toString() }.toSet()

    val allTools
        get() = fixedTools
}
