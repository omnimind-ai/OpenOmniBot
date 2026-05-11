package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcome
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.webchat.AgentRunRequestNormalizer
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP 工具执行器
 */
object McpToolExecutors {
    private const val TAG = "[McpToolExecutors]"
    private fun brandName(): String = AppLocaleManager.brandName()
    
    /**
     * 执行 VLM 任务（阻塞等待完成）
     */
    suspend fun executeVlmTask(
        context: Context,
        args: Map<String, Any?>?,
        scope: CoroutineScope
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val goal = args?.get("goal") as? String
        if (goal.isNullOrBlank()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing goal")
        }

        val needSummaryArg = args?.get("needSummary") as? Boolean
        val shouldSummary = shouldEnableSummary(goal, needSummaryArg)

        val request = VlmTaskRequest(
            goal = goal,
            model = args["model"] as? String,
            packageName = args["packageName"] as? String,
            needSummary = shouldSummary
        )

        try {
            val outcome = VlmToolCoordinator.executeNewTask(
                context = context,
                request = request,
                scope = scope
            )
            return@withContext outcomeToMcpResponse(outcome)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error executing VLM task: ${e.message}")
            return@withContext McpResponseBuilder.buildErrorText("VLM task failed: ${e.message}")
        }
    }
    
    /**
     * 执行任务回复
     */
    suspend fun executeTaskReply(
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val taskId = args?.get("taskId") as? String
        val reply = args?.get("reply") as? String
        
        if (taskId.isNullOrBlank() || reply.isNullOrBlank()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing taskId or reply")
        }
        
        val taskState = McpTaskManager.getTask(taskId)
            ?: return@withContext McpResponseBuilder.buildErrorText("Task not found: $taskId")
        
        if (taskState.status != TaskStatus.WAITING_INPUT) {
            return@withContext McpResponseBuilder.buildErrorText(
                "Task is not waiting for input. Current status: ${taskState.status}"
            )
        }
        
        OmniLog.d(TAG, "Sending reply to task $taskId: $reply")
        
        val success = AssistsUtil.Core.provideUserInputToVLMTask(reply)
        if (!success) {
            return@withContext McpResponseBuilder.buildErrorText("Failed to send reply to task")
        }
        
        // 更新状态并等待下一个状态变更
        taskState.status = TaskStatus.RUNNING
        taskState.waitingQuestion = null
        taskState.message = if (AppLocaleManager.isEnglish()) "Resuming execution" else "继续执行中"
        taskState.addChatMessage("User replied: $reply")
        taskState.markStateChanged()
        
        // 阻塞等待任务完成或再次需要输入
        val outcome = VlmToolCoordinator.waitForTask(taskId, taskState.goal)
        return@withContext outcomeToMcpResponse(outcome)
    }
    
    /**
     * 执行任务状态查询
     */
    fun executeTaskStatus(args: Map<String, Any?>?): Map<String, Any?> {
        val taskId = args?.get("taskId") as? String
        
        if (taskId.isNullOrBlank()) {
            return McpResponseBuilder.buildErrorText("Missing taskId")
        }
        
        val state = McpTaskManager.getTask(taskId)
            ?: return McpResponseBuilder.buildErrorText("Task not found: $taskId")
        
        return McpResponseBuilder.buildTaskStatusResponse(state)
    }
    
    /**
     * 执行等待屏幕解锁
     */
    suspend fun executeTaskWaitUnlock(
        context: Context,
        args: Map<String, Any?>?,
        scope: CoroutineScope
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val taskId = args?.get("taskId") as? String
        
        if (taskId.isNullOrBlank()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing taskId")
        }
        
        val taskState = McpTaskManager.getTask(taskId)
            ?: return@withContext McpResponseBuilder.buildErrorText("Task not found: $taskId")
        
        if (taskState.status != TaskStatus.SCREEN_LOCKED) {
            // 如果已经不是锁屏状态，直接返回当前状态
            return@withContext when (taskState.status) {
                TaskStatus.FINISHED -> McpResponseBuilder.buildFinishedResponse(taskState)
                TaskStatus.ERROR -> McpResponseBuilder.buildErrorResponse(taskState)
                TaskStatus.WAITING_INPUT -> McpResponseBuilder.buildWaitingInputResponse(taskState)
                TaskStatus.RUNNING -> outcomeToMcpResponse(
                    VlmToolCoordinator.waitForTask(taskId, taskState.goal)
                )
                else -> McpResponseBuilder.buildTextResponse("Task status: ${taskState.status}")
            }
        }
        
        OmniLog.d(TAG, "Waiting for screen unlock for task $taskId")

        val outcome = VlmToolCoordinator.resumeAfterUnlock(
            context = context,
            taskId = taskId,
            taskState = taskState,
            scope = scope
        )
        return@withContext if (outcome.status == VlmToolOutcomeStatus.TIMEOUT) {
            McpResponseBuilder.buildUnlockTimeoutResponse(taskId, taskState.goal)
        } else {
            outcomeToMcpResponse(outcome)
        }
    }

    /**
     * 执行文件传输工具
     */
    suspend fun executeFileTransfer(
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val action = (args?.get("action") as? String)?.trim()?.lowercase() ?: "latest"
        val fileId = args?.get("fileId") as? String
        val afterFileId = args?.get("afterFileId") as? String
        val limit = (args?.get("limit") as? Number)?.toInt()
        val timeoutMs = (args?.get("timeoutMs") as? Number)?.toLong()
            ?.coerceIn(1_000L, McpTaskManager.MAX_WAIT_TIME_MS)
            ?: McpTaskManager.MAX_WAIT_TIME_MS

        when (action) {
            "latest" -> {
                val record = McpFileInbox.latest()
                    ?: return@withContext McpResponseBuilder.buildTextResponse(
                        "No files in inbox. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                    )
                return@withContext buildFileTransferResponse(record)
            }
            "get" -> {
                if (fileId.isNullOrBlank()) {
                    return@withContext McpResponseBuilder.buildErrorText("Missing fileId")
                }
                val record = McpFileInbox.getFile(fileId)
                    ?: return@withContext McpResponseBuilder.buildErrorText("File not found: $fileId")
                return@withContext buildFileTransferResponse(record)
            }
            "list" -> {
                val records = McpFileInbox.list(limit)
                if (records.isEmpty()) {
                    return@withContext McpResponseBuilder.buildTextResponse(
                        "No files in inbox. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                    )
                }
                val itemsText = records.joinToString("\n") { record ->
                    "- id=${record.id}, name=${record.fileName}, size=${record.sizeBytes}, receivedAt=${record.createdAt}"
                }
                return@withContext mapOf(
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Received files:\n$itemsText"
                        )
                    ),
                    "files" to records.map { record ->
                        mapOf(
                            "id" to record.id,
                            "name" to record.fileName,
                            "mimeType" to record.mimeType,
                            "sizeBytes" to record.sizeBytes,
                            "receivedAt" to record.createdAt,
                        )
                    }
                )
            }
            "clear" -> {
                val cleared = if (!fileId.isNullOrBlank()) {
                    if (McpFileInbox.removeFile(fileId)) 1 else 0
                } else {
                    McpFileInbox.clearAll()
                }
                return@withContext McpResponseBuilder.buildTextResponse("Cleared $cleared file(s) from inbox.")
            }
            "wait" -> {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val record = McpFileInbox.latest()
                    if (record != null && (afterFileId == null || record.id != afterFileId)) {
                        return@withContext buildFileTransferResponse(record)
                    }
                    kotlinx.coroutines.delay(McpTaskManager.POLL_INTERVAL_MS)
                }
                return@withContext McpResponseBuilder.buildTextResponse(
                    "No file received within timeout. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                )
            }
            else -> {
                return@withContext McpResponseBuilder.buildErrorText("Unknown action: $action")
            }
        }
    }

    /**
     * Starts a normal in-app Agent run through the same service used by WebChat/Home.
     *
     * @param context Android application context used to access conversation storage and the Agent manager.
     * @param args MCP JSON arguments. `userMessage` is the natural-language request; `conversationId`
     * optionally reuses an existing conversation, while omitted or invalid ids create a fresh one.
     * @return MCP-compatible response with the accepted task id and conversation id. Completion must be
     * verified through Agent/WebChat events or the runtime files produced by the requested tools.
     */
    suspend fun executeAgentRun(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val requestArgs = args ?: emptyMap()
        val normalizedPayload = AgentRunRequestNormalizer.normalize(requestArgs)
        if (normalizedPayload.userMessage.isBlank() && normalizedPayload.attachments.isEmpty()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing userMessage")
        }

        val appContext = context.applicationContext
        val conversationService = ConversationDomainService(appContext)
        val requestedMode = requestArgs["conversationMode"]?.toString()?.trim()?.ifEmpty { null }
            ?: "normal"
        val requestedConversationId = when (val raw = requestArgs["conversationId"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }?.takeIf { it > 0L }

        val conversation = if (requestedConversationId != null) {
            conversationService.getConversationPayload(requestedConversationId)
                ?: return@withContext McpResponseBuilder.buildErrorText(
                    "Conversation not found: $requestedConversationId"
                )
        } else {
            conversationService.createConversation(
                title = requestArgs["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "MCP Agent Run",
                mode = requestedMode
            )
        }
        val conversationId = (conversation["id"] as? Number)?.toLong()
            ?: conversation["id"]?.toString()?.toLongOrNull()
            ?: return@withContext McpResponseBuilder.buildErrorText("Conversation id is invalid")

        val runRequest = linkedMapOf<String, Any?>()
        runRequest.putAll(requestArgs)
        runRequest["conversationMode"] = requestedMode
        runRequest["userMessage"] = normalizedPayload.userMessage
        runRequest["attachments"] = normalizedPayload.attachments

        return@withContext runCatching {
            val accepted = AgentRunService(appContext).startConversationRun(
                conversationId = conversationId,
                request = runRequest
            )
            val taskId = accepted["taskId"]?.toString().orEmpty()
            val status = accepted["status"]?.toString().orEmpty().ifEmpty { "accepted" }
            val text = buildString {
                appendLine("Agent run accepted.")
                appendLine("")
                appendLine("Task ID: $taskId")
                appendLine("Conversation ID: $conversationId")
                appendLine("Status: $status")
                appendLine("")
                appendLine("This uses the normal in-app OOB Agent runtime. Verify completion through WebChat events or Project runtime files.")
            }
            linkedMapOf<String, Any?>(
                "content" to listOf(mapOf("type" to "text", "text" to text)),
                "taskId" to taskId,
                "status" to status,
                "conversationId" to conversationId,
                "conversation" to conversation
            )
        }.getOrElse { error ->
            McpResponseBuilder.buildErrorText("Agent run failed: ${error.message}")
        }
    }


    /**
     * Submits a generic OOB tool call request through the normal Agent runtime.
     *
     * @param context Android application context used by [executeAgentRun].
     * @param args MCP arguments containing `toolName`, optional `arguments`, and optional `goal`.
     * @return Accepted Agent task response. Completion is observed through existing Agent channels.
     */
    suspend fun executeOobToolCall(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> {
        val requestArgs = args ?: emptyMap()
        val toolName = requestArgs["toolName"]?.toString()?.trim().orEmpty()
        if (toolName.isEmpty()) {
            return McpResponseBuilder.buildErrorText("Missing toolName")
        }
        val toolArgs = (requestArgs["arguments"] as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            ?: emptyMap<String, Any?>()
        val goal = requestArgs["goal"]?.toString()?.trim().orEmpty()
        val prompt = buildString {
            appendLine("Call this OOB capability through the normal Agent tool chain.")
            appendLine()
            appendLine("Tool: $toolName")
            if (goal.isNotEmpty()) appendLine("Goal: $goal")
            appendLine("Arguments JSON: $toolArgs")
            appendLine()
            appendLine("Use existing OOB tools and permissions. Do not create a new Project unless the user explicitly asked for one.")
        }
        return executeAgentRun(
            context,
            linkedMapOf(
                "userMessage" to prompt,
                "title" to "OOB Tool Call: $toolName"
            )
        )
    }
    
    private fun outcomeToMcpResponse(outcome: VlmToolOutcome): Map<String, Any?> {
        val state = McpTaskManager.getTask(outcome.taskId)
        return when (outcome.status) {
            VlmToolOutcomeStatus.FINISHED -> {
                state?.let(McpResponseBuilder::buildFinishedResponse)
                    ?: McpResponseBuilder.buildTextResponse("Task completed: ${outcome.message}")
            }
            VlmToolOutcomeStatus.WAITING_INPUT -> {
                state?.let(McpResponseBuilder::buildWaitingInputResponse)
                    ?: McpResponseBuilder.buildTextResponse(outcome.message)
            }
            VlmToolOutcomeStatus.SCREEN_LOCKED -> {
                state?.let { McpResponseBuilder.buildScreenLockedResponse(it, isInitial = false) }
                    ?: McpResponseBuilder.buildTextResponse(outcome.message)
            }
            VlmToolOutcomeStatus.ERROR, VlmToolOutcomeStatus.CANCELLED -> {
                state?.let(McpResponseBuilder::buildErrorResponse)
                    ?: McpResponseBuilder.buildErrorText(outcome.errorMessage ?: outcome.message)
            }
            VlmToolOutcomeStatus.TIMEOUT -> {
                McpResponseBuilder.buildTimeoutResponse(outcome.taskId, outcome.goal, state)
            }
        }
    }

    private fun buildFileTransferResponse(record: McpFileRecord): Map<String, Any?> {
        val issued = McpFileInbox.issueDownloadToken(record)
        val state = McpServerManager.currentState()
        val host = state.host ?: McpNetworkUtils.currentLanIp()
        if (host.isNullOrBlank()) {
            return McpResponseBuilder.buildErrorText("LAN IP not available. Please ensure the device is on a LAN-accessible network.")
        }
        val url = "http://$host:${state.port}/mcp/file/${issued.id}?token=${issued.downloadToken}"
        val text = buildString {
            appendLine("File ready for download.")
            appendLine("")
            appendLine("File ID: ${issued.id}")
            appendLine("Name: ${issued.fileName}")
            appendLine("Size: ${issued.sizeBytes} bytes")
            appendLine("MIME: ${issued.mimeType ?: "unknown"}")
            appendLine("ReceivedAt: ${issued.createdAt}")
            appendLine("")
            appendLine("Download URL (valid ~15 minutes):")
            appendLine(url)
        }
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "file" to mapOf(
                "id" to issued.id,
                "name" to issued.fileName,
                "mimeType" to issued.mimeType,
                "sizeBytes" to issued.sizeBytes,
                "receivedAt" to issued.createdAt,
                "downloadUrl" to url,
                "tokenExpiresAt" to issued.tokenExpiresAt,
            )
        )
    }

    private fun shouldEnableSummary(goal: String, needSummaryArg: Boolean?): Boolean {
        return (needSummaryArg == true) || hasSummaryIntent(goal)
    }

    private fun hasSummaryIntent(goal: String): Boolean {
        if (goal.isBlank()) return false
        val keywords = listOf(
            "总结", "汇总", "整理", "要点", "概括", "归纳", "提炼", "总结一下",
            "summary", "summarize", "recap", "tl;dr", "tl;dr."
        )
        return keywords.any { goal.contains(it, ignoreCase = true) }
    }

}
