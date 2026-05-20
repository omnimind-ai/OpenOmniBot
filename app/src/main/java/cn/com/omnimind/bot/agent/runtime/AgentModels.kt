@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package cn.com.omnimind.bot.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Agent 相关数据模型
 */

/**
 * Agent 上下文信息
 */
@Serializable
data class AgentContext(
    val installedApps: Map<String, String>,  // appName -> packageName
    val currentPackageName: String?,
    val currentTime: String
)

/**
 * Agent 最终响应
 */
@Serializable
data class AgentFinalResponse(
    val content: String = "",
    val finishReason: String? = null,
    val latestPromptTokens: Int? = null,
    val promptTokenThreshold: Int? = null
)

/**
 * Agent 执行结果
 */
sealed class AgentResult {
    data class Success(
        val response: AgentFinalResponse,
        val executedTools: List<ToolExecutionResult>,
        val outputKind: String = AgentOutputKind.NONE.value,
        val hasUserVisibleOutput: Boolean = false,
        val latestPromptTokens: Int? = null,
        val promptTokenThreshold: Int? = null
    ) : AgentResult()
    
    data class Error(
        val message: String,
        val exception: Exception? = null
    ) : AgentResult()
}

enum class AgentOutputKind(val value: String) {
    CHAT_MESSAGE("chat_message"),
    CLARIFY("clarify"),
    TASK_STARTED("task_started"),
    PERMISSION_REQUIRED("permission_required"),
    TOOL_RESULT("tool_result"),
    NONE("none")
}

/**
 * 工具执行结果
 */
sealed class ToolExecutionResult {
    open val artifacts: List<ArtifactRef> = emptyList()
    open val workspaceId: String? = null
    open val actions: List<ArtifactAction> = emptyList()

    data class VlmTaskStarted(
        val taskId: String,
        val goal: String
    ) : ToolExecutionResult()

    data class ChatMessage(
        val message: String
    ) : ToolExecutionResult()
    
    data class Clarify(
        val question: String,
        val missingFields: List<String>?,
        val dialog: UserDialog? = null
    ) : ToolExecutionResult()
    
    data class Error(
        val toolName: String,
        val message: String
    ) : ToolExecutionResult()

    data class PermissionRequired(
        val missing: List<String>
    ) : ToolExecutionResult()

    data class ScheduleResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val success: Boolean = true,
        val taskId: String? = null,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class McpResult(
        val toolName: String,
        val serverName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class MemoryResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class TerminalResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        val timedOut: Boolean = false,
        val terminalOutput: String = "",
        val terminalSessionId: String? = null,
        val terminalStreamState: String = "completed",
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class Interrupted(
        val toolName: String,
        val summaryText: String = "工具调用已被用户手动停止",
        val previewJson: String = "{}",
        val rawResultJson: String = "{}",
        val interruptedBy: String = "user",
        val interruptionReason: String = "manual_stop",
        val terminalOutput: String = "",
        val terminalSessionId: String? = null,
        val terminalStreamState: String = "interrupted",
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class ContextResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        val imageDataUrl: String? = null,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()
}

/**
 * 结构化用户对话卡片。type 决定 Flutter 渲染哪种交互组件。
 * 仅在用户必须做决策才能继续时使用，不要用于纯信息展示。
 *
 * type:
 *   "confirm"  — 确认/取消（危险操作、不可逆操作）
 *   "choices"  — 多选项（流程分支，2-4 个选项）
 *   "input"    — 单行文本输入（需要用户提供一段文字才能继续）
 */
data class UserDialog(
    val type: String,
    val message: String,
    val title: String? = null,
    val confirmLabel: String? = null,
    val cancelLabel: String? = null,
    val danger: Boolean = false,
    val choices: List<ChoiceOption>? = null,
    val placeholder: String? = null,
    val inputType: String? = null
) {
    fun toPayload(): Map<String, Any?> = buildMap {
        put("type", type)
        put("message", message)
        title?.let { put("title", it) }
        confirmLabel?.let { put("confirmLabel", it) }
        cancelLabel?.let { put("cancelLabel", it) }
        if (danger) put("danger", true)
        choices?.let { put("choices", it.map { c -> c.toPayload() }) }
        placeholder?.let { put("placeholder", it) }
        inputType?.let { put("inputType", it) }
    }
}

data class ChoiceOption(
    val label: String,
    val value: String,
    val hint: String? = null
) {
    fun toPayload(): Map<String, Any?> = buildMap {
        put("label", label)
        put("value", value)
        hint?.let { put("hint", it) }
    }
}

/**
 * Agent 状态
 */
enum class AgentStatus {
    IDLE,
    THINKING,
    EXECUTING_TOOL,
    WAITING_INPUT,
    COMPLETED,
    ERROR
}

/**
 * Agent 回调接口
 */
interface AgentCallback {
    /**
     * Agent 开始思考
     */
    suspend fun onThinkingStart()
    
    /**
     * Agent 思考内容更新
     */
    suspend fun onThinkingUpdate(thinking: String)
    
    /**
     * 模型流里已经出现工具调用，但完整参数尚未结束；用于提前创建稳定工具卡。
     */
    suspend fun onToolCallPreview(
        toolName: String,
        argumentsJson: String,
        toolCallId: String?,
        toolCallIndex: Int
    ) = Unit

    /**
     * 工具调用开始（arguments 已完整，即将执行）。
     * [toolCallId] 是 LLM 在流式输出中分配的稳定 ID，与 onToolCallPreview 的
     * toolCallId 相同，供调用方将 preview 卡片与正式执行关联。
     */
    suspend fun onToolCallStart(toolName: String, toolCallId: String, arguments: JsonObject)
    
    /**
     * 工具调用进度更新
     */
    suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?> = emptyMap()
    )

    /**
     * 外部执行器直接投递的工具卡事件。
     *
     * 用于 VLM/UTG 这类内部子执行器：RunLog 是事实来源，UI 只投影同一张 step card。
     */
    suspend fun onToolCardEvent(
        kind: String,
        payload: Map<String, Any?> = emptyMap()
    ) = Unit
    
    /**
     * 工具调用完成
     */
    suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult)
    
    /**
     * 聊天消息
     */
    suspend fun onChatMessage(message: String)

    /**
     * 聊天消息（支持流式增量）
     */
    suspend fun onChatMessage(message: String, isFinal: Boolean) {
        onChatMessage(message)
    }

    /**
     * 聊天消息（支持流式增量 + 本地推理吞吐）
     */
    suspend fun onChatMessage(
        message: String,
        isFinal: Boolean,
        prefillTokensPerSecond: Double?,
        decodeTokensPerSecond: Double?
    ) {
        onChatMessage(message, isFinal)
    }

    /**
     * 主模型一轮调用结束后的 prompt token 统计更新
     */
    suspend fun onPromptTokenUsageChanged(
        latestPromptTokens: Int,
        promptTokenThreshold: Int?
    ) = Unit

    /**
     * 对话上下文压缩状态变化
     */
    suspend fun onContextCompactionStateChanged(
        isCompacting: Boolean,
        latestPromptTokens: Int?,
        promptTokenThreshold: Int?
    ) = Unit
    
    /**
     * 需要用户输入（追问）。dialog 不为 null 时 Flutter 渲染结构化卡片而非纯文本。
     */
    suspend fun onClarifyRequired(
        question: String,
        missingFields: List<String>?
    ) {
        onClarifyRequired(question, missingFields, null)
    }

    suspend fun onClarifyRequired(
        question: String,
        missingFields: List<String>?,
        dialog: UserDialog? = null
    ) = Unit
    
    /**
     * Agent 执行完成
     */
    suspend fun onComplete(result: AgentResult)
    
    /**
     * Agent 执行错误
     */
    suspend fun onError(error: String)

    /**
     * 执行任务前缺少权限（陪伴模式未开启 或 无障碍权限未授予）
     */
    suspend fun onPermissionRequired(missing: List<String>)

    /**
     * 仅供旧版异步 VLM 任务链路使用；阻塞式统一 Agent 工具不应触发该回调。
     */
    suspend fun onVlmTaskFinished() = Unit

    /**
     * Called once per run, immediately after the skill trigger phase completes.
     * Each entry: skillId, triggerReason, name.
     */
    suspend fun onSkillsResolved(skills: List<Map<String, Any?>>) = Unit
}
