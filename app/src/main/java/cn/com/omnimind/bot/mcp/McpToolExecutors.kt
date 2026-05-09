package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcome
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import cn.com.omnimind.bot.util.AssistsUtil
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
