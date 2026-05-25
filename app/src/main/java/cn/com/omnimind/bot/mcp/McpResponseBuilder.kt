package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale

/**
 * MCP 响应构建器
 */
object McpResponseBuilder {
    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun t(zh: String, en: String): String {
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    private fun permissionLabel(permission: String): String {
        return when (permission) {
            "accessibility" -> t("无障碍权限", "Accessibility")
            "overlay" -> t("悬浮窗权限", "Overlay")
            else -> permission
        }
    }

    private fun permissionLine(state: TaskState): String {
        val labels = state.missingPermissions
            .map(::permissionLabel)
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) return ""
        return "${t("需要权限", "Required permissions")}: ${labels.joinToString(t("、", ", "))}"
    }
    
    fun buildFinishedResponse(state: TaskState): Map<String, Any?> {
        val recentActivity = state.chatMessages.takeLast(5).joinToString("\n") { "- $it" }
        val recentActivityList = state.chatMessages.takeLast(5)
        val summary = state.summaryText?.takeIf { it.isNotBlank() }
        val finishedContent = state.finishedContent?.takeIf { it.isNotBlank() }
        val summaryBlock = when {
            summary != null -> "\n\n${t("总结", "Summary")}:\n$summary"
            state.needSummary && state.summaryUnavailable -> "\n\n${t("总结", "Summary")}:\n${t("(不可用)", "(unavailable)")}"
            state.needSummary -> "\n\n${t("总结", "Summary")}:\n${t("(生成中)", "(pending)")}"
            else -> ""
        }
        return mapOf(
            "content" to listOf(mapOf(
                "type" to "text",
                "text" to """${t("✅ 任务已成功完成！", "✅ Task completed successfully!")}

${t("任务 ID", "Task ID")}: ${state.taskId}
${t("目标", "Goal")}: ${state.goal}
${t("状态", "Status")}: FINISHED
${if (state.message.isNotBlank()) "${t("消息", "Message")}: ${state.message}" else ""}
${if (!finishedContent.isNullOrBlank()) "${t("完成内容", "Finished Content")}: $finishedContent" else ""}
$summaryBlock

${t("最近活动", "Recent activity")}:
$recentActivity""".trimIndent()
            )),
            "status" to "FINISHED",
            "finishedContent" to finishedContent,
            "summary" to summary,
            "summaryUnavailable" to state.summaryUnavailable,
            "feedback" to state.feedback,
            "recentActivity" to recentActivityList,
            "executionStatus" to state.compileStatus,
            "executionRoute" to state.executionRoute
        )
    }
    
    fun buildErrorResponse(state: TaskState): Map<String, Any?> {
        val permissionText = permissionLine(state).takeIf { it.isNotBlank() }
        return mapOf(
            "content" to listOf(mapOf(
                "type" to "text",
                "text" to """${t("❌ 任务执行失败。", "❌ Task failed with error.")}

${t("任务 ID", "Task ID")}: ${state.taskId}
${t("目标", "Goal")}: ${state.goal}
${t("错误", "Error")}: ${state.message}
${permissionText.orEmpty()}""".trimIndent()
            )),
            "status" to "ERROR",
            "finishedContent" to state.finishedContent,
            "summary" to state.summaryText,
            "summaryUnavailable" to state.summaryUnavailable,
            "feedback" to state.feedback,
            "recentActivity" to state.chatMessages.takeLast(5),
            "executionStatus" to state.compileStatus,
            "executionRoute" to state.executionRoute,
            "errorCode" to state.errorCode,
            "missingPermissions" to state.missingPermissions,
            "isError" to true
        )
    }
    
    fun buildWaitingInputResponse(state: TaskState): Map<String, Any?> {
        return mapOf(
            "content" to listOf(mapOf(
                "type" to "text",
                "text" to when (currentLocale()) {
                    PromptLocale.ZH_CN -> """
                        ⏸️ 任务已暂停，Agent 需要你的输入！

                        任务 ID: ${state.taskId}
                        目标: ${state.goal}

                        🤖 Agent 正在询问：
                        "${state.waitingQuestion ?: "请提供继续执行所需的输入"}"

                        👉 需要执行的操作：
                        使用 `task_reply` 工具回复：
                        - taskId: "${state.taskId}"
                        - reply: <你对 Agent 问题的回答>

                        示例：
                        - 如果要求验证码，回复验证码
                        - 如果询问播放哪首歌，回复歌名
                        - 如果要求确认操作，回复“确认”或给出明确指令
                    """.trimIndent()
                    PromptLocale.EN_US -> """
                        ⏸️ Task paused - Agent needs your input!

                        Task ID: ${state.taskId}
                        Goal: ${state.goal}

                        🤖 Agent is asking:
                        "${state.waitingQuestion ?: "Please provide input to continue"}"

                        👉 ACTION REQUIRED:
                        Use the `task_reply` tool to respond:
                        - taskId: "${state.taskId}"
                        - reply: <your answer to the agent's question>

                        Example scenarios:
                        - If asked for a verification code, reply with the code
                        - If asked which song to play, reply with the song name
                        - If asked to confirm an action, reply with "confirm" or specific instructions
                    """.trimIndent()
                }
            )),
            "status" to "WAITING_INPUT",
            "waitingQuestion" to state.waitingQuestion,
            "finishedContent" to state.finishedContent,
            "summary" to state.summaryText,
            "summaryUnavailable" to state.summaryUnavailable,
            "feedback" to state.feedback,
            "recentActivity" to state.chatMessages.takeLast(5),
            "executionStatus" to state.compileStatus,
            "executionRoute" to state.executionRoute
        )
    }
    
    fun buildUserPausedResponse(state: TaskState): Map<String, Any?> {
        return mapOf(
            "content" to listOf(mapOf(
                "type" to "text",
                "text" to t(
                    """
                    ⏸️ 任务已由用户暂停。

                    任务 ID: ${state.taskId}
                    目标: ${state.goal}

                    用户已在设备上手动暂停该任务。
                    当用户从设备界面继续执行时，任务会恢复。
                    """.trimIndent(),
                    """
                    ⏸️ Task paused by user.

                    Task ID: ${state.taskId}
                    Goal: ${state.goal}

                    The user has manually paused this task on the device.
                    The task will resume when the user continues it from the device UI.
                    """.trimIndent()
                )
            )),
            "status" to "USER_PAUSED",
            "recentActivity" to state.chatMessages.takeLast(5),
            "executionStatus" to state.compileStatus,
            "executionRoute" to state.executionRoute
        )
    }

    fun buildScreenLockedResponse(state: TaskState, isInitial: Boolean): Map<String, Any?> {
        val actionText = if (isInitial) {
            t(
                """设备当前处于锁屏或熄屏状态。VLM 任务无法启动，直到屏幕被解锁。

👉 需要执行的操作：
请让用户解锁手机，然后使用 `task_wait_unlock` 工具等待解锁并启动任务：
- taskId: "${state.taskId}"
- goal: "${state.goal}" """,
                """The device screen is currently locked or off. The VLM task cannot start until the screen is unlocked.

👉 ACTION REQUIRED:
Please ask the user to unlock their phone, then use `task_wait_unlock` to wait for the unlock and start the task:
- taskId: "${state.taskId}"
- goal: "${state.goal}" """
            )
        } else {
            t(
                """任务执行过程中设备被锁屏，任务已暂停。

👉 需要执行的操作：
请让用户解锁手机，然后使用 `task_wait_unlock` 工具恢复任务：
- taskId: "${state.taskId}" """,
                """The device screen was locked during task execution. The task is paused.

👉 ACTION REQUIRED:
Please ask the user to unlock their phone, then use `task_wait_unlock` to resume:
- taskId: "${state.taskId}" """
            )
        }
        
        return mapOf(
            "content" to listOf(mapOf(
                "type" to "text",
                "text" to """${t("🔒 屏幕已锁定，任务暂停", "🔒 Screen Locked - Task Paused")}

${t("任务 ID", "Task ID")}: ${state.taskId}
${t("目标", "Goal")}: ${state.goal}
${t("状态", "Status")}: SCREEN_LOCKED

$actionText""".trimIndent()
            )),
            "status" to "SCREEN_LOCKED",
            "recentActivity" to state.chatMessages.takeLast(5),
            "executionStatus" to state.compileStatus,
            "executionRoute" to state.executionRoute
        )
    }
    
    fun buildTimeoutResponse(taskId: String, goal: String, state: TaskState?): Map<String, Any?> {
        return mapOf(
            "content" to listOf(mapOf(
                "type" to "text",
                "text" to t(
                    """
                    任务在超时后仍在运行。

                    任务 ID: $taskId
                    目标: $goal
                    当前状态: ${state?.status?.name ?: "UNKNOWN"}

                    任务仍在设备上继续执行。你可以：
                    1. 使用 `task_status` 并传入 taskId="$taskId" 查看当前进度
                    2. 继续等待设备上的任务完成

                    最近活动：
                    ${state?.chatMessages?.takeLast(5)?.joinToString("\n") { "- $it" } ?: "暂无活动"}
                    """.trimIndent(),
                    """
                    Task is still running after timeout.

                    Task ID: $taskId
                    Goal: $goal
                    Current Status: ${state?.status?.name ?: "UNKNOWN"}

                    The task continues running on the device. You can:
                    1. Use `task_status` with taskId="$taskId" to check current progress
                    2. Wait for the task to complete on the device

                    Recent activity:
                    ${state?.chatMessages?.takeLast(5)?.joinToString("\n") { "- $it" } ?: "No activity yet"}
                    """.trimIndent()
                )
            )),
            "status" to "TIMEOUT",
            "finishedContent" to state?.finishedContent,
            "summary" to state?.summaryText,
            "summaryUnavailable" to (state?.summaryUnavailable ?: false),
            "feedback" to state?.feedback,
            "recentActivity" to (state?.chatMessages?.takeLast(5) ?: emptyList<String>()),
            "executionStatus" to state?.compileStatus,
            "executionRoute" to state?.executionRoute
        )
    }
    
    fun buildUnlockTimeoutResponse(taskId: String, goal: String): Map<String, Any?> {
        return mapOf(
            "content" to listOf(mapOf(
                "type" to "text",
                "text" to t(
                    """
                    ⏱️ 等待屏幕解锁超时。

                    任务 ID: $taskId
                    目标: $goal

                    在超时时间内屏幕仍未解锁。
                    请让用户解锁手机后，再次使用 `task_wait_unlock` 重试。
                    """.trimIndent(),
                    """
                    ⏱️ Timeout waiting for screen unlock.

                    Task ID: $taskId
                    Goal: $goal

                    The screen was not unlocked within the timeout period.
                    Please ask the user to unlock the phone and try again with `task_wait_unlock`.
                    """.trimIndent()
                )
            )),
            "status" to "TIMEOUT"
        )
    }
    
    fun buildTaskStatusResponse(state: TaskState): Map<String, Any?> {
        val statusText = buildString {
            appendLine("${t("任务 ID", "Task ID")}: ${state.taskId}")
            appendLine("${t("目标", "Goal")}: ${state.goal}")
            appendLine("${t("状态", "Status")}: ${state.status}")
            if (state.message.isNotBlank()) {
                appendLine("${t("消息", "Message")}: ${state.message}")
            }
            val finishedContent = state.finishedContent?.takeIf { it.isNotBlank() }
            if (finishedContent != null) {
                appendLine("${t("完成内容", "Finished Content")}: $finishedContent")
            }
            val summaryValue = state.summaryText?.takeIf { it.isNotBlank() }
            if (state.needSummary || summaryValue != null) {
                appendLine(
                    "${t("总结", "Summary")}: ${summaryValue ?: if (state.summaryUnavailable) t("不可用", "unavailable") else t("生成中", "pending")}"
                )
            }
            state.feedback?.takeIf { it.isNotBlank() }?.let { feedback ->
                appendLine("${t("反馈", "Feedback")}: $feedback")
            }
            if (state.status == TaskStatus.WAITING_INPUT && state.waitingQuestion != null) {
                appendLine("")
                appendLine(t("⚠️ 等待用户输入", "⚠️ WAITING FOR INPUT"))
                appendLine("${t("问题", "Question")}: ${state.waitingQuestion}")
                appendLine("")
                appendLine(t("请使用 `task_reply` 提供所需信息。", "Use `task_reply` to provide the requested information."))
            }
            if (state.chatMessages.isNotEmpty()) {
                appendLine("")
                appendLine("${t("最近活动", "Recent activity")}:")
                state.chatMessages.takeLast(5).forEach { appendLine("- $it") }
            }
        }
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to statusText)),
            "status" to state.status.name,
            "waitingQuestion" to state.waitingQuestion,
            "finishedContent" to state.finishedContent,
            "summary" to state.summaryText,
            "summaryUnavailable" to state.summaryUnavailable,
            "feedback" to state.feedback,
            "recentActivity" to state.chatMessages.takeLast(5),
            "executionStatus" to state.compileStatus,
            "executionRoute" to state.executionRoute
        )
    }
    
    fun buildErrorText(message: String): Map<String, Any?> {
        return mapOf("content" to listOf(mapOf("type" to "text", "text" to message)), "isError" to true)
    }
    
    fun buildTextResponse(message: String): Map<String, Any?> {
        return mapOf("content" to listOf(mapOf("type" to "text", "text" to message)))
    }
}
