package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import kotlinx.serialization.json.JsonObject

/**
 * Builds agent-facing fallback context for replay failures. The replay handler
 * decides when fallback is allowed; this controller owns prompt/recovery
 * shaping and the optional VLM fallback tool call.
 */
class OobFunctionAgentFallbackController(
    private val helper: SharedHelper,
) {
    fun prompt(
        step: Map<String, Any?>,
        stepTitle: String,
        recovery: Map<String, Any?> = emptyMap(),
    ): String {
        val basePrompt = (step["agent_call"] as? Map<*, *>)
            ?.get("args")?.let { (it as? Map<*, *>)?.get("prompt")?.toString() }
            ?: (step["fallback"] as? Map<*, *>)?.get("prompt")?.toString()
            ?: stepTitle
        val args = OmniflowStepExecutor.normalizeArgsMap(step["args"])
        val argsText = if (args.isNotEmpty()) {
            "\n\n当前已物化参数：${helper.mapToJsonElement(args)}"
        } else {
            ""
        }
        return "$basePrompt$argsText${recoveryPromptSuffix(recovery)}"
    }

    suspend fun refetchCurrentPageForFailedStep(reason: String): Map<String, Any?> =
        runCatching {
            OmniflowStepExecutor.currentPageSnapshotForRecovery(reason)
        }.getOrElse { error ->
            linkedMapOf(
                "refetched_current_page" to false,
                "reason" to reason,
                "error_message" to error.message.orEmpty(),
            )
        }

    suspend fun tryVlmFallback(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        failReason: String,
        recovery: Map<String, Any?>,
        router: cn.com.omnimind.bot.agent.AgentToolExecutor?,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        parentToolCallId: String,
    ): Map<String, Any?>? {
        if (router == null || env == null || callback == null || toolHandle == null) return null
        val args = OmniflowStepExecutor.normalizeArgsMap(step["args"])
        val targetDesc = OmniflowStepExecutor.stringArg(
            args,
            "target_description",
            "targetDescription"
        ).orEmpty()
        if (targetDesc.isEmpty()) return null
        val action = OmniflowStepExecutor.actionNameForStep(step)
        val goal = when (action) {
            "click", "long_press" -> "找到并点击「$targetDesc」"
            "scroll" -> "在「$targetDesc」区域滚动"
            else -> "执行 $action 操作：$targetDesc"
        } + recoveryPromptSuffix(recovery)
        val vlmArgs = helper.mapToJsonElement(
            mapOf("goal" to goal, "startFromCurrent" to true)
        ) as? JsonObject ?: return null
        val syntheticCall = cn.com.omnimind.baselib.llm.AssistantToolCall(
            id = "${parentToolCallId}_${stepId}_fallback",
            type = "function",
            function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                name = "vlm_task",
                arguments = vlmArgs.toString()
            )
        )
        val subDescriptor = cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor(
            name = "vlm_task",
            displayName = stepTitle,
            toolType = "omniflow_fallback"
        )
        return try {
            val subResult = router.execute(
                syntheticCall, vlmArgs, subDescriptor, env, callback, toolHandle
            )
            val succeeded = subResult !is cn.com.omnimind.bot.agent.ToolExecutionResult.Error
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to "vlm_task",
                "executor" to "omniflow_vlm_fallback",
                "success" to succeeded,
                "omniflow_fail_reason" to failReason,
                "recovery" to recovery,
                "summary" to "omniflow remap failed → vlm fallback: $goal"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun recoveryPromptSuffix(recovery: Map<String, Any?>): String {
        if (recovery.isEmpty()) return ""
        val packageName = firstNonBlank(recovery["effective_package"], recovery["package_name"])
        val activityName = firstNonBlank(recovery["activity_name"])
        val xml = recovery["observation_xml"]?.toString()?.take(MAX_RECOVERY_PROMPT_XML_CHARS).orEmpty()
        return buildString {
            append("\n\n上一次复用步骤执行失败后，系统已重新获取当前页面。")
            if (packageName.isNotBlank()) append("\n当前包名：$packageName")
            if (activityName.isNotBlank()) append("\n当前 Activity：$activityName")
            if (xml.isNotBlank()) append("\n当前页面 XML（截断）：\n").append(xml)
            append("\n请基于这个最新页面继续，不要沿用失败步骤里的旧坐标或旧页面。")
        }
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private companion object {
        const val MAX_RECOVERY_PROMPT_XML_CHARS = 6000
    }
}
