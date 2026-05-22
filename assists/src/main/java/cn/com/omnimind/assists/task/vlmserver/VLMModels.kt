package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.sse.EventSource

// ==================== UI操作动作 ====================

@Serializable
sealed class UIAction {
    abstract val name: String
}

@Serializable
@SerialName("click")
data class ClickAction(
    override val name: String = "click",
    @SerialName("target_description")
    val targetDescription: String,
    var x: Float,
    var y: Float
) : UIAction()

@Serializable
@SerialName("type")
data class TypeAction(
    override val name: String = "type",
    val content: String
) : UIAction()

@Serializable
@SerialName("scroll")
data class ScrollAction(
    override val name: String = "scroll",
    @SerialName("target_description")
    val targetDescription: String,
    var x1: Float,  // 起始点x
    var y1: Float,  // 起始点y
    var x2: Float,  // 结束点x
    var y2: Float,  // 结束点y
    val duration: Float = 1.5f  // 持续时间（秒），默认1.5秒
) : UIAction()

@Serializable
@SerialName("long_press")
data class LongPressAction(
    override val name: String = "long_press",
    @SerialName("target_description")
    val targetDescription: String,
    var x: Float,
    var y: Float
) : UIAction()

@Serializable
@SerialName("open_app")
data class OpenAppAction(
    override val name: String = "open_app",
    @SerialName("package_name")
    val packageName: String
) : UIAction()

@Serializable
@SerialName("press_home")
data class PressHomeAction(
    override val name: String = "press_home"
) : UIAction()

@Serializable
@SerialName("press_back")
data class PressBackAction(
    override val name: String = "press_back"
) : UIAction()

@Serializable
@SerialName("wait")
data class WaitAction(
    override val name: String = "wait",
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    // 某些模型可能返回秒为单位的duration字段，保留以兼容
    val duration: Long? = null
) : UIAction()

@Serializable
@SerialName("record")
data class RecordAction(
    override val name: String = "record",
    val content: String
) : UIAction()

@Serializable
@SerialName("finished")
data class FinishedAction(
    override val name: String = "finished",
    val content: String = ""
) : UIAction()

@Serializable
@SerialName("require_user_choice")
data class RequireUserChoiceAction(
    override val name: String = "require_user_choice",
    val options: List<String>,
    val prompt: String
) : UIAction()

@Serializable
@SerialName("require_user_confirmation")
data class RequireUserConfirmationAction(
    override val name: String = "require_user_confirmation",
    val prompt: String
) : UIAction()

@Serializable
@SerialName("info")
data class InfoAction(
    override val name: String = "info",
    val value: String
) : UIAction()

@Serializable
@SerialName("feedback")
data class FeedbackAction(
    override val name: String = "feedback",
    val value: String
) : UIAction()

@Serializable
@SerialName("abort")
data class AbortAction(
    override val name: String = "abort",
    val value: String = ""
) : UIAction()

@Serializable
@SerialName("hot_key")
data class HotKeyAction(
    override val name: String = "hot_key",
    val key: String  // "ENTER", "BACK", "HOME"
) : UIAction()


// ==================== 步骤和上下文 ====================

@Serializable
data class UIStep(
    val observation: String,
    val thought: String,
    val action: UIAction,
    val result: String? = null,
    val summary: String = "",  // 添加summary字段用于历史总结
    @SerialName("observation_xml")
    val observationXml: String? = null,
    @SerialName("after_observation_xml")
    val afterObservationXml: String? = null,
    @SerialName("package_name")
    val packageName: String? = null,
    @SerialName("after_package_name")
    val afterPackageName: String? = null,
    @SerialName("started_at_ms")
    val startedAtMs: Long? = null,
    @SerialName("finished_at_ms")
    val finishedAtMs: Long? = null,
    @SerialName("token_usage")
    val tokenUsage: VLMTokenUsage? = null,
    @SerialName("token_usage_attempts")
    val tokenUsageAttempts: List<VLMTokenUsage> = emptyList()
)

@Serializable
data class VLMStep(
    val observation: String,
    val thought: String,
    val action: UIAction,
    val summary: String = "",  // 添加summary字段，与UIStep保持一致
)

@Serializable
data class VLMTokenUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int? = null,
    @SerialName("text_tokens")
    val textTokens: Int? = null,
    @SerialName("cached_tokens")
    val cachedTokens: Int? = null,
    @SerialName("prefill_tokens_per_second")
    val prefillTokensPerSecond: Double? = null,
    @SerialName("decode_tokens_per_second")
    val decodeTokensPerSecond: Double? = null,
    @SerialName("resolved_model")
    val resolvedModel: String? = null,
    val route: String? = null,
    @SerialName("attempt_index")
    val attemptIndex: Int? = null,
    @SerialName("stability_attempt")
    val stabilityAttempt: Int? = null,
    @SerialName("tool_retry_index")
    val toolRetryIndex: Int? = null,
    @SerialName("attempt_count")
    val attemptCount: Int? = null
)

@Serializable
data class UIContext(
    @SerialName("overall_task")
    val overallTask: String,
    @SerialName("current_step_goal")
    val currentStepGoal: String = "",
    @SerialName("step_skill_guidance")
    val stepSkillGuidance: String = "",
    @SerialName("installed_applications")
    val installedApplications: Map<String, String> = emptyMap(),
    @SerialName("target_package_name")
    val targetPackageName: String = "",
    @SerialName("current_package_name")
    val currentPackageName: String = "",
    @SerialName("current_page_summary")
    val currentPageSummary: String = "",
    @SerialName("first_step_guidance")
    val firstStepGuidance: String = "",
    val trace: List<UIStep> = emptyList(),
    @SerialName("key_memory")
    val keyMemory: List<String> = emptyList(),
    @SerialName("max_steps")
    val maxSteps: Int? = null,
    @SerialName("steps_used")
    val stepsUsed: Int = 0,
    @SerialName("steps_remaining")
    val stepsRemaining: Int? = null,
    @SerialName("running_summary")
    val runningSummary: String = "", // 当前任务的运行总结（由Compactor生成）
    @SerialName("current_state")
    val currentState: String = "",   // 当前屏幕状态描述（由Compactor生成）
    @SerialName("next_step_hint")
    val nextStepHint: String = "",   // 建议的下一步操作（由Compactor生成）
    @SerialName("completed_milestones")
    val completedMilestones: List<String> = emptyList(), // 已完成的里程碑（由Compactor生成）
    @SerialName("priority_event")
    val priorityEvent: String? = null,  // High-priority event message (e.g., file received)
    @SerialName("priority_event_type")
    val priorityEventType: String? = null,  // Event type (e.g., "file_received")
    @SerialName("suggest_completion")
    val suggestCompletion: Boolean = false  // Hint that task should complete
    // 注意：screenshot不在这里，会单独传递（对应Python中的exclude=True）
) {
    fun activeGoal(): String = currentStepGoal.ifBlank { overallTask }
}

data class SceneChatCompletionResponse(
    val success: Boolean,
    val code: String,
    val message: String,
    val parser: ModelSceneRegistry.ResponseParser,
    val route: String? = null,
    val content: String = "",
    val reasoning: String = "",
    val finishReason: String? = null,
    val toolCalls: List<AssistantToolCall> = emptyList(),
    val rawResponseBody: String? = null
)

data class SceneChatCompletionStreamHandle(
    val eventSource: EventSource,
    val parser: ModelSceneRegistry.ResponseParser,
    val route: String? = null,
    val resolvedModel: String
)

data class SceneChatCompletionTurn(
    val parser: ModelSceneRegistry.ResponseParser,
    val route: String? = null,
    val resolvedModel: String,
    val turn: ChatCompletionTurn
)

@Serializable
data class StepMetadataPayload(
    val observation: String = "",
    val thought: String = "",
    val summary: String = ""
)

@Serializable
data class VLMThinkingContext(
    val observation: String = "",
    val thought: String = "",
    val summary: String = "",
    val reasoning: String = "",
    val rawContent: String = "",
    val finishReason: String? = null
)

data class VLMToolCallRetryState(
    val retryIndex: Int,
    val thinking: VLMThinkingContext,
    val failureReason: String? = null
)

data class VLMConversationRound(
    val userMessage: ChatCompletionMessage,
    val assistantMessage: ChatCompletionMessage,
    val toolMessage: ChatCompletionMessage
)

class VLMConversationState(
    private val maxCompletedRounds: Int = 4
) {
    private val completedRounds = ArrayDeque<VLMConversationRound>()
    var streamingReasoning: String = ""
        private set

    fun clear() {
        completedRounds.clear()
        streamingReasoning = ""
    }

    fun updateStreamingReasoning(reasoning: String) {
        streamingReasoning = reasoning
    }

    fun appendRound(round: VLMConversationRound) {
        completedRounds.addLast(round)
        while (completedRounds.size > maxCompletedRounds) {
            completedRounds.removeFirst()
        }
    }

    fun historyMessages(): List<ChatCompletionMessage> {
        if (completedRounds.isEmpty()) return emptyList()
        val messages = mutableListOf<ChatCompletionMessage>()
        completedRounds.forEach { round ->
            messages += round.userMessage
            messages += round.assistantMessage
            messages += round.toolMessage
        }
        return messages
    }

    fun roundCount(): Int = completedRounds.size
}

data class VLMRequestEnvelope(
    val request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
    val currentUserText: String
)

@Serializable
data class OperationResult(
    val success: Boolean,
    val message: String,
    val data: JsonElement? = null,
    val providerRunLogJson: String? = null,
    val providerRunLogPath: String? = null,
    val canonicalRunLogPath: String? = null,
)

@Serializable
data class VLMResult(
    val success: Boolean,
    val step: VLMStep? = null,
    val error: String? = null,
    val thinking: VLMThinkingContext? = null,
    val shouldRetryForToolCall: Boolean = false
)
