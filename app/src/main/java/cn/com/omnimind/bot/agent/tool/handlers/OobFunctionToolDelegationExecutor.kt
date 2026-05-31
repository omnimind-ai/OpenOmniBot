package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import kotlinx.serialization.json.JsonObject

/**
 * Delegates a replay step to a live agent tool router.
 *
 * OobFunctionToolHandler owns replay ordering and fallback policy. This class
 * owns the mechanical bridge from a materialized Function step to a synthetic
 * tool call and back to a stable per-step result payload.
 */
class OobFunctionToolDelegationExecutor(
    private val helper: SharedHelper,
) {
    suspend fun execute(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
        syntheticCallId: String,
        router: AgentToolExecutor,
    ): Map<String, Any?> {
        val stepArgs = remappedStepArgs(step)
        val syntheticCall = AssistantToolCall(
            id = syntheticCallId,
            type = "function",
            function = AssistantToolCallFunction(
                name = callableTool,
                arguments = stepArgs.toString()
            )
        )
        val subDescriptor = AgentToolRegistry.RuntimeToolDescriptor(
            name = callableTool,
            displayName = stepTitle,
            toolType = "oob_function_step"
        )
        return try {
            val subResult = router.execute(
                syntheticCall,
                stepArgs,
                subDescriptor,
                env,
                callback,
                toolHandle
            )
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to callableTool,
                "executor" to "tool",
                "success" to (subResult !is ToolExecutionResult.Error),
                "summary" to when (subResult) {
                    is ToolExecutionResult.ContextResult -> subResult.summaryText
                    is ToolExecutionResult.Error -> subResult.message
                    else -> stepTitle
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to callableTool,
                "executor" to "tool",
                "success" to false,
                "summary" to (e.message ?: "step failed")
            )
        }
    }

    private fun remappedStepArgs(step: Map<String, Any?>): JsonObject {
        val remapResult = OmniflowStepExecutor.remapStepArgs(step)
        val stepArgsMap = remapResult.args
        return when (stepArgsMap) {
            is Map<*, *> -> helper.mapToJsonElement(
                stepArgsMap.entries.associate { (key, value) -> key.toString() to value }
            ) as? JsonObject ?: JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
    }
}
