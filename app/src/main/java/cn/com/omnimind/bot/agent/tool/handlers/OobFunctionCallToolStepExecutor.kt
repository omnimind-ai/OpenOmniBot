package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.NoOpAgentCallback
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Resolves and executes an OmniFlow `call_tool` replay step.
 *
 * Function targets are converted into nested Function steps; ordinary tool
 * targets are delegated through the live tool router or returned to Agent.
 */
class OobFunctionCallToolStepExecutor(
    private val callRequestResolver: OobFunctionCallRequestResolver,
    private val toolDelegationExecutor: OobFunctionToolDelegationExecutor,
    private val agentFallbackController: OobFunctionAgentFallbackController,
    private val runResultBuilder: OobFunctionRunResultBuilder,
) {
    suspend fun execute(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        callback: AgentCallback?,
        toolHandle: AgentToolExecutionHandle?,
        env: AgentExecutionEnvironment?,
        parentToolCallId: String?,
        toolName: String,
        allowAgentFallback: Boolean,
        allowToolDelegationWithoutRouter: Boolean,
        router: AgentToolExecutor?,
        canLoadFunction: (String) -> Boolean,
        executeNestedFunctionStep: suspend (
            functionStep: Map<String, Any?>,
            nestedCallableTool: String,
        ) -> Map<String, Any?>,
    ): Map<String, Any?> {
        val args = callRequestResolver.stepArgs(step)
        val callTool = callRequestResolver.resolve(args, step, canLoadFunction)
        val targetTool = callTool.targetTool
        val targetArgs = callTool.targetArgs
        val functionId = callTool.functionId
        if (functionId.isNotEmpty()) {
            val functionStep = LinkedHashMap<String, Any?>().apply {
                putAll(step)
                put("args", LinkedHashMap<String, Any?>().apply {
                    putAll(args)
                    put("function_id", functionId)
                    put("arguments", targetArgs)
                })
            }
            return executeNestedFunctionStep(
                functionStep,
                callableTool.ifEmpty { "call_tool" }
            )
        }

        if (targetTool.isEmpty()) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_tool" },
                executor = RunLogReplayPolicy.EXECUTOR_TOOL,
                summary = "$stepTitle missing tool_name or function_id",
                errorCode = "OOB_CALL_TOOL_TARGET_MISSING",
            )
        }
        if (RunLogReplayPolicy.isOmniflowToolCallTool(targetTool)) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_tool" },
                executor = RunLogReplayPolicy.EXECUTOR_TOOL,
                summary = "$stepTitle nested call_tool is not allowed",
                errorCode = "OOB_CALL_TOOL_RECURSION",
            )
        }
        if (router != null && env != null) {
            val delegatedStep = LinkedHashMap<String, Any?>().apply {
                putAll(step)
                put("tool", targetTool)
                put("callable_tool", targetTool)
                put("args", targetArgs)
            }
            return LinkedHashMap<String, Any?>().apply {
                putAll(
                    toolDelegationExecutor.execute(
                        step = delegatedStep,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = targetTool,
                        env = env,
                        callback = callback ?: NoOpAgentCallback,
                        toolHandle = toolHandle ?: NoOpAgentRunControl
                            .beginToolExecution(targetTool, "${parentToolCallId ?: toolName}_$stepId"),
                        syntheticCallId = "${parentToolCallId ?: toolName}_$stepId",
                        router = router,
                    )
                )
                put("delegated_from", callableTool.ifEmpty { "call_tool" })
                put("delegated_tool_used", true)
            }
        }
        if (allowAgentFallback && !allowToolDelegationWithoutRouter) {
            return linkedMapOf(
                "step_id" to stepId,
                "tool" to targetTool,
                "executor" to RunLogReplayPolicy.EXECUTOR_AGENT,
                "blocked_executor" to RunLogReplayPolicy.EXECUTOR_TOOL,
                "prompt" to agentFallbackController.prompt(
                    LinkedHashMap<String, Any?>().apply {
                        putAll(step)
                        put("tool", targetTool)
                        put("args", targetArgs)
                    },
                    stepTitle
                ),
                "success" to false,
                "needs_agent" to true,
                "fallback_available" to true,
                "summary" to "call_tool requires agent runner: $stepTitle"
            )
        }
        return failureStepResult(
            stepId = stepId,
            tool = targetTool,
            executor = RunLogReplayPolicy.EXECUTOR_TOOL,
            summary = "Tool router unavailable for $targetTool",
            errorCode = "OOB_CALL_TOOL_ROUTER_UNAVAILABLE",
        )
    }

    private fun failureStepResult(
        stepId: String,
        tool: String,
        executor: String,
        summary: String,
        errorCode: String,
    ): Map<String, Any?> = runResultBuilder.failureStep(
        stepId = stepId,
        tool = tool,
        executor = executor,
        summary = summary,
        errorCode = errorCode,
    )
}
