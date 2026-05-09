package cn.com.omnimind.assists.api.bean

import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import java.util.concurrent.TimeUnit


sealed class TaskParams {
    data class OpenClawConfig(
        val baseUrl: String,
        val token: String? = null,
        val userId: String? = null,
        val sessionKey: String? = null
    )
    data class ChatModelOverride(
        val providerProfileId: String,
        val modelId: String,
        val apiBase: String,
        val apiKey: String,
        val protocolType: String = "openai_compatible"
    )
    //陪伴任务参数
    data class CompanionTaskParams(
        val companionFinishListener:()->Unit
    ) : TaskParams();
    //聊天任务参数
    data class ChatTaskParams(
        val taskId: String,
        val content: List<Map<String, Any>>,
        val onMessagePush: OnMessagePushListener,
        val provider: String? = null,
        val openClawConfig: OpenClawConfig? = null,
        val modelOverride: ChatModelOverride? = null,
        val reasoningEffort: String? = null
    ) : TaskParams();
    //VLM任务参数
    data class VLMOperationTaskParams(
        val goal: String,
        val model: String?,
        val maxSteps: Int?,
        val packageName: String?,
        val onTaskFinishListener: () -> Unit,
        val needSummary: Boolean = false,
        val onMessagePushListener: OnMessagePushListener? = null,
        val skipGoHome: Boolean = false,  // 是否跳过回到主页，从当前页面开始执行
        val stepSkillGuidance: String = ""
    ): TaskParams();

    data class ScheduledTaskParams(
        val taskParams: TaskParams,
        val delay: Long,
        val timeUnit: TimeUnit=TimeUnit.SECONDS,
        val onTaskFinishListener: () -> Unit
    ): TaskParams();
    data class ScheduledVLMOperationTaskParams(
        val name: String,
        val subTitle: String?,
        val extraJson: String?,
        val goal: String,
        val model: String?,
        val maxSteps: Int?,
        val packageName: String?,
        val scheduledTaskID:String,
        val needSummary: Boolean = false,
        val onMessagePushListener: OnMessagePushListener? = null
    ): TaskParams();
}
