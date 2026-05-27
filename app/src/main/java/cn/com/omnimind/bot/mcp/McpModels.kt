package cn.com.omnimind.bot.mcp

import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalStatus

/**
 * MCP 服务器状态
 */
data class McpServerState(
    val enabled: Boolean,
    val running: Boolean,
    val host: String?,
    val port: Int,
    val token: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "running" to running,
        "host" to host,
        "port" to port,
        "token" to token,
    )
}

/**
 * VLM 任务请求参数
 */
data class VlmTaskRequest(
    val goal: String = "",
    val model: String? = null,
    val maxSteps: Int? = null,
    val waitTimeoutMs: Long? = null,
    val packageName: String? = null,
    val needSummary: Boolean? = null,
    val skipGoHome: Boolean = false,
    val stepSkillGuidance: String = "",
    val disableOmniFlowRecall: Boolean = true,
    val allowOmniFlowFunctionAutoExecute: Boolean = false,
)

/**
 * 任务状态枚举
 */
enum class TaskStatus {
    RUNNING,           // 正在执行
    WAITING_INPUT,     // 等待用户输入（INFO动作触发）
    USER_PAUSED,       // 用户主动暂停
    SCREEN_LOCKED,     // 屏幕锁定/息屏，等待解锁
    FINISHED,          // 任务完成
    ERROR,             // 任务出错
    CANCELLED          // 任务取消
}

/**
 * 任务状态数据
 */
data class TaskState(
    val taskId: String,
    val goal: String,
    var status: TaskStatus,
    val needSummary: Boolean = false,
    var message: String = "",
    var waitingQuestion: String? = null,
    var chatMessages: MutableList<String> = mutableListOf(),
    @Volatile var finishedContent: String? = null,
    @Volatile var summaryText: String? = null,
    @Volatile var feedback: String? = null,
    @Volatile var summaryUnavailable: Boolean = false,
    @Volatile var compileStatus: String = "",
    @Volatile var executionRoute: String = "",
    @Volatile var errorCode: String? = null,
    @Volatile var missingPermissions: List<String> = emptyList(),
    @Volatile var vlmRequest: VlmTaskRequest? = null,
    val startTime: Long = System.currentTimeMillis(),
    @Volatile var stateChanged: Boolean = false
) {
    fun markStateChanged() {
        stateChanged = true
    }
    
    fun resetStateChanged() {
        stateChanged = false
    }
    
    fun toResponseMap(): Map<String, Any?> = mapOf(
        "taskId" to taskId,
        "goal" to goal,
        "status" to status.name,
        "needSummary" to needSummary,
        "message" to message,
        "waitingQuestion" to waitingQuestion,
        "recentMessages" to chatMessages.takeLast(10),
        "finishedContent" to finishedContent,
        "summary" to summaryText,
        "feedback" to feedback,
        "summaryUnavailable" to summaryUnavailable,
        "executionStatus" to compileStatus,
        "executionRoute" to executionRoute,
        "errorCode" to errorCode,
        "missingPermissions" to missingPermissions,
        "elapsedMs" to (System.currentTimeMillis() - startTime)
    )
    
    fun addChatMessage(content: String) {
        synchronized(chatMessages) {
            chatMessages.add(content)
            if (chatMessages.size > 20) {
                chatMessages.removeAt(0)
            }
        }
    }

    fun updateSummary(summary: String) {
        summaryText = summary
    }

    fun applyTerminalResult(result: VlmTaskTerminalResult) {
        status = when (result.status) {
            VlmTaskTerminalStatus.WAITING_INPUT -> TaskStatus.WAITING_INPUT
            VlmTaskTerminalStatus.FINISHED -> TaskStatus.FINISHED
            VlmTaskTerminalStatus.ERROR -> TaskStatus.ERROR
            VlmTaskTerminalStatus.CANCELLED -> TaskStatus.CANCELLED
        }
        message = result.message.ifBlank {
            result.errorMessage
                ?: result.finishedContent
                ?: result.waitingQuestion
                ?: result.feedback
                ?: message
        }
        waitingQuestion = result.waitingQuestion
        finishedContent = result.finishedContent?.takeIf { it.isNotBlank() } ?: finishedContent
        summaryText = result.summaryText?.takeIf { it.isNotBlank() } ?: summaryText
        feedback = result.feedback?.takeIf { it.isNotBlank() } ?: feedback
        summaryUnavailable = summaryUnavailable || result.summaryUnavailable
        markStateChanged()
    }
}
