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

Do not use this tool for uploaded image, screenshot, or photo recognition, OCR, explanation, summary, or comparison. Uploaded images are already part of the multimodal conversation; this tool is only for the current Android device screen and real UI automation.

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
- Workbench control tools are still not exposed as Project Tools.

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

    val oobToolCallTool = mapOf(
        "name" to "oob_tool_call",
        "description" to """Call any OOB capability through the normal in-app Agent runtime, or run a saved OOB Function by function_id. Use this as the generic bridge when a Project Tool needs VLM, files, terminal/Alpine, UI automation, memory, schedules, or another OOB tool.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "toolName" to mapOf("type" to "string", "description" to "OOB or MCP tool name, for example vlm_task or file_transfer. Leave empty when function_id is provided."),
                "tool_name" to mapOf("type" to "string", "description" to "Snake-case alias for toolName."),
                "function_id" to mapOf("type" to "string", "description" to "Optional saved OOB Function id. When provided, OOB runs the Function locally."),
                "functionId" to mapOf("type" to "string", "description" to "Camel-case alias for function_id."),
                "arguments" to mapOf("type" to "object", "description" to "Arguments to pass to the requested tool."),
                "goal" to mapOf("type" to "string", "description" to "Optional natural-language goal when the tool requires planning or composition.")
            )
        )
    )

    val omniflowCallToolTool = mapOf(
        "name" to "omniflow.call_tool",
        "description" to """Call one OmniFlow/OOB tool. Pass function_id to run a saved local Function; pass toolName/tool_name plus arguments for VLM, web, terminal, files, memory, schedules, or other OOB tools. This replaces omniflow.call_function for new clients.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "toolName" to mapOf("type" to "string", "description" to "Target OOB/MCP tool name, for example vlm_task, web_search, or terminal_execute."),
                "tool_name" to mapOf("type" to "string", "description" to "Snake-case alias for toolName."),
                "function_id" to mapOf("type" to "string", "description" to "Saved Function id returned by omniflow.recall or Function library."),
                "functionId" to mapOf("type" to "string", "description" to "Camel-case alias for function_id."),
                "arguments" to mapOf("type" to "object", "description" to "Parameter values for the target tool or Function."),
                "goal" to mapOf("type" to "string", "description" to "Optional original task goal for tracing.")
            )
        )
    )

    val omniflowRecallTool = mapOf(
        "name" to "omniflow.recall",
        "description" to """Recall reusable OOB Functions for a goal and current app/page scope. This only returns a direct hit or ranked candidates; parameterized Functions must be selected and filled by the calling agent before `omniflow.call_tool` with function_id.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "Natural-language task goal."),
                "current_package" to mapOf("type" to "string", "description" to "Optional foreground Android package for scope matching."),
                "current_node_id" to mapOf("type" to "string", "description" to "Optional current page/node id for future OmniFlow compatibility."),
                "k" to mapOf("type" to "integer", "description" to "Maximum candidates to return. Default 8.")
            ),
            "required" to listOf("goal")
        )
    )

    val omniflowCallFunctionTool = mapOf(
        "name" to "omniflow.call_function",
        "description" to """Execute one agent-selected OOB Function by function_id with explicit arguments. Returns structured success/fallback/control fields and does not silently invent missing parameters.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_id" to mapOf("type" to "string", "description" to "Function id returned by `omniflow.recall`."),
                "arguments" to mapOf("type" to "object", "description" to "Parameter values matching the Function inputSchema."),
                "goal" to mapOf("type" to "string", "description" to "Optional original task goal for tracing.")
            ),
            "required" to listOf("function_id")
        )
    )

    val omniflowIngestRunLogTool = mapOf(
        "name" to "omniflow.ingest_run_log",
        "description" to """Convert a successful OOB RunLog into a reusable local Function asset. Prefer passing run_id for an existing internal RunLog; inline run_log is accepted for simple external writeback.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "run_id" to mapOf("type" to "string", "description" to "Existing OOB internal RunLog id."),
                "run_log" to mapOf("type" to "object", "description" to "Optional inline canonical run log."),
                "auto_enrich" to mapOf("type" to "boolean", "description" to "Accepted for compatibility; OOB simple mode does deterministic local import.")
            )
        )
    )

    val omniflowExploreReplayTool = mapOf(
        "name" to "omniflow.explore_replay",
        "description" to """Run OOB-native exploratory UI crawling, persist the path as a UTG-backed RunLog, convert it into a reusable Function, then optionally replay that Function through the existing local runner.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "Natural-language objective used to rank safe clickable UI nodes."),
                "package_name" to mapOf("type" to "string", "description" to "Optional Android package to launch before exploration."),
                "max_steps" to mapOf("type" to "integer", "description" to "Maximum exploration clicks. Default 3, capped at 8."),
                "settle_delay_ms" to mapOf("type" to "integer", "description" to "Delay after launch/click before capturing XML. Default 800ms."),
                "stop_text" to mapOf("type" to "string", "description" to "Optional text/content/resource substring that stops exploration once seen in captured XML."),
                "allow_risky_actions" to mapOf("type" to "boolean", "description" to "Allow labels such as delete, pay, submit, or logout. Default false."),
                "function_id" to mapOf("type" to "string", "description" to "Optional stable Function id for the generated path."),
                "replay" to mapOf("type" to "boolean", "description" to "Whether to replay after registration. Default true."),
                "reset_before_replay" to mapOf("type" to "boolean", "description" to "Optionally press Back and relaunch package before replay."),
                "reset_back_steps" to mapOf("type" to "integer", "description" to "Back presses used when reset_before_replay=true. Default 1."),
                "arguments" to mapOf("type" to "object", "description" to "Function arguments for replay; generated UTG functions are usually argument-free.")
            ),
            "required" to listOf("goal")
        )
    )

    val oobFunctionListTool = mapOf(
        "name" to "oob_function_list",
        "description" to "List registered OOB reusable Functions available for direct deterministic replay.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "limit" to mapOf("type" to "integer", "description" to "Maximum number of Functions to return. Default: 100.")
            )
        )
    )

    val oobFunctionGetTool = mapOf(
        "name" to "oob_function_get",
        "description" to "Read one registered OOB reusable Function by id.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "functionId" to mapOf("type" to "string", "description" to "Function id to read.")
            ),
            "required" to listOf("functionId")
        )
    )

    val oobFunctionRegisterTool = mapOf(
        "name" to "oob_function_register",
        "description" to "Register or update one OOB reusable Function spec.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "functionSpec" to mapOf("type" to "object", "description" to "Reusable Function spec object.")
            ),
            "required" to listOf("functionSpec")
        )
    )

    val oobFunctionGuardCheckTool = mapOf(
        "name" to "oob_function_guard_check",
        "description" to "Run preflight guard checks for one OOB reusable Function before replay.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "functionId" to mapOf("type" to "string", "description" to "Function id to check."),
                "arguments" to mapOf("type" to "object", "description" to "Materialization arguments for the Function.")
            ),
            "required" to listOf("functionId")
        )
    )

    val oobFunctionRunTool = mapOf(
        "name" to "oob_function_run",
        "description" to "Run one OOB reusable Function directly after guard preflight; returns runner and per-step timing.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "functionId" to mapOf("type" to "string", "description" to "Function id to run."),
                "arguments" to mapOf("type" to "object", "description" to "Materialization arguments for the Function."),
                "dryRun" to mapOf("type" to "boolean", "description" to "Only return guard decision without executing."),
                "continueWithAgent" to mapOf("type" to "boolean", "description" to "Allow Agent fallback for non-deterministic steps."),
                "executionMode" to mapOf("type" to "string", "description" to "foreground or background. Default: foreground."),
                "confirmed" to mapOf("type" to "boolean", "description" to "Set true only after user confirmation for guarded operations.")
            ),
            "required" to listOf("functionId")
        )
    )

    val oobRunLogListTool = mapOf(
        "name" to "oob_run_log_list",
        "description" to "List recent OOB internal RunLogs that can be inspected or converted to Functions.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "limit" to mapOf("type" to "integer", "description" to "Maximum number of RunLogs to return. Default: 50.")
            )
        )
    )

    val oobRunLogGetTool = mapOf(
        "name" to "oob_run_log_get",
        "description" to "Read one OOB internal RunLog timeline payload by id.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "runId" to mapOf("type" to "string", "description" to "RunLog id to read.")
            ),
            "required" to listOf("runId")
        )
    )

    val oobRunLogConvertTool = mapOf(
        "name" to "oob_run_log_convert",
        "description" to "Convert one successful OOB RunLog into a reusable Function and optionally register it.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "runId" to mapOf("type" to "string", "description" to "RunLog id to convert."),
                "register" to mapOf("type" to "boolean", "description" to "Register the converted Function. Default follows service policy."),
                "functionId" to mapOf("type" to "string", "description" to "Optional Function id override."),
                "name" to mapOf("type" to "string", "description" to "Optional Function name override."),
                "description" to mapOf("type" to "string", "description" to "Optional Function description override.")
            ),
            "required" to listOf("runId")
        )
    )

    val oobProjectCreateTool = mapOf(
        "name" to "oob_project_create",
        "description" to """Create or reuse an OOB Workbench Project.

This is the MCP control entry for Project creation. It writes the normal Workbench runtime files under /workspace/projects/<project-id>/ and registers Project Tools. It does not add Workbench control tools to the Project Toolbox.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "projectId" to mapOf("type" to "string", "description" to "Stable Project id. Example: oob-workbench-v01-research-summary"),
                "name" to mapOf("type" to "string", "description" to "Human-readable Project name."),
                "prompt" to mapOf("type" to "string", "description" to "Original creation prompt preserved in Project files."),
                "entityName" to mapOf("type" to "string", "description" to "Optional Project entity name for the default Project Display."),
                "initialItems" to mapOf("type" to "array", "description" to "Optional initial Project data written to data/items.json."),
                "apis" to mapOf("type" to "array", "description" to "Optional Project Tool contracts. Each item may include apiId or toolId, displayName, description, inputSchema, outputSchema, and run."),
                "htmlFiles" to mapOf("type" to "array", "description" to "Optional HTML/CSS/JS files under frontend/html/. This is the default frontend path. Include index.html for html_webview Displays. Use for reports, interactive UI, charts, dashboards, forms, and rich layouts. Prefer a single page with hash routing; multiple local HTML files may link to each other with relative URLs such as detail.html?id=1#summary, but only as Project-local page replacement with no browser back stack or external navigation. Default to the app-injected Workbench Display layout profile: viewport width=device-width, one column, compact first viewport, targeting the measured viewportWidthDp/viewportHeightDp instead of hard-coded phone dimensions. Portrait reports should use a phone-width article layout with the executive summary in the first measured viewport; use viewport width=1280 only for explicit wide reports or slide decks."),
                "markdownFiles" to mapOf("type" to "array", "description" to "Optional specialized Markdown files under frontend/markdown/. Not the default UI path. Include index.md for markdown Displays only when the user explicitly asks for Markdown, editable documents, plain-text long-form output, or when the current Project is already a Markdown Display."),
                "flutterFiles" to mapOf("type" to "array", "description" to "Optional Flutter files under frontend/flutter/ for the limited flutter_eval renderer. Expose an OobProjectWidget(dynamic _, {super.key}) Widget entry; do not include void main(), runApp(), normal app entry code, or third-party packages.")
            )
        )
    )

    val oobProjectActivateTool = mapOf(
        "name" to "oob_project_activate",
        "description" to "Activate one OOB Project so its Project Tools are mounted as the current MCP Toolbox.",
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
            oobToolCallTool,
            omniflowRecallTool,
            omniflowCallToolTool,
            omniflowIngestRunLogTool,
            omniflowExploreReplayTool,
            oobFunctionListTool,
            oobFunctionGetTool,
            oobFunctionRegisterTool,
            oobFunctionGuardCheckTool,
            oobFunctionRunTool,
            oobRunLogListTool,
            oobRunLogGetTool,
            oobRunLogConvertTool,
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
