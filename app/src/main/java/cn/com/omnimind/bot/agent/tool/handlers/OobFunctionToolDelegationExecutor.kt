package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
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
    private val runResultBuilder: OobFunctionRunResultBuilder = OobFunctionRunResultBuilder(),
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
            val summary = when (subResult) {
                is ToolExecutionResult.ContextResult -> subResult.summaryText
                is ToolExecutionResult.Error -> subResult.message
                else -> stepTitle
            }
            if (subResult is ToolExecutionResult.Error) {
                runResultBuilder.failureStep(
                    stepId = stepId,
                    tool = callableTool,
                    executor = RunLogReplayPolicy.EXECUTOR_TOOL,
                    summary = summary,
                    errorCode = "OOB_TOOL_DELEGATION_FAILED",
                )
            } else {
                linkedMapOf<String, Any?>(
                    "step_id" to stepId,
                    "tool" to callableTool,
                    "executor" to RunLogReplayPolicy.EXECUTOR_TOOL,
                    "success" to true,
                    "summary" to summary,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runResultBuilder.failureStep(
                stepId = stepId,
                tool = callableTool,
                executor = RunLogReplayPolicy.EXECUTOR_TOOL,
                summary = e.message ?: "step failed",
                errorCode = "OOB_TOOL_DELEGATION_FAILED",
            )
        }
    }

    private fun remappedStepArgs(step: Map<String, Any?>): JsonObject {
        val remapResult = OmniflowStepExecutor.remapStepArgs(step)
        val stepArgsMap = remapResult.args
        return when (stepArgsMap) {
            is Map<*, *> -> mapToJsonElement(
                stepArgsMap.entries.associate { (key, value) -> key.toString() to value }
            ) as? JsonObject ?: JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
    }
}
