package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

data class OobNestedFunctionRunRequest(
    val functionId: String,
    val spec: Map<String, Any?>,
    val materializedSpec: Map<String, Any?>,
    val callback: cn.com.omnimind.bot.agent.AgentCallback?,
    val toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
    val env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
    val parentToolCallId: String,
    val toolName: String,
    val allowAgentFallback: Boolean,
    val allowToolDelegationWithoutRouter: Boolean,
    val callStack: List<String>,
)

/**
 * Executes an OmniFlow nested reusable Function step and owns the nested card
 * lifecycle around that local recursive run.
 */
class OobFunctionNestedFunctionExecutor(
    private val callRequestResolver: OobFunctionCallRequestResolver,
    private val nestedCallCardPresenter: OobFunctionNestedCallCardPresenter,
    private val runResultBuilder: OobFunctionRunResultBuilder,
) {
    suspend fun execute(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        parentToolCallId: String?,
        toolName: String,
        allowAgentFallback: Boolean,
        allowToolDelegationWithoutRouter: Boolean,
        callStack: List<String>,
        loadSpec: (String) -> Map<String, Any?>?,
        runNestedFunction: suspend (OobNestedFunctionRunRequest) -> Map<String, Any?>,
    ): Map<String, Any?> {
        val args = callRequestResolver.stepArgs(step)
        val functionId = firstNonBlank(
            args["function_id"],
            args["functionId"],
            args["id"],
            args["name"],
            step["function_id"],
            step["functionId"],
        )
        val nestedArguments = callRequestResolver.nestedFunctionArguments(args)
        val cardToolName = RunLogReplayPolicy.TOOL_CALL_FUNCTION
        val cardId = nestedCallCardPresenter.cardId(parentToolCallId, toolName, stepId)
        val cardStartedAtMs = System.currentTimeMillis()

        suspend fun emitStarted() {
            callback?.onToolCardEvent(
                "tool_started",
                nestedCallCardPresenter.payload(
                    cardId = cardId,
                    toolName = cardToolName,
                    stepTitle = stepTitle,
                    functionId = functionId,
                    callableTool = callableTool,
                    nestedArguments = nestedArguments,
                    status = "running",
                    success = null,
                    summary = nestedCallCardPresenter.runningSummary(functionId),
                    progress = stepTitle,
                    startedAtMs = cardStartedAtMs,
                    finishedAtMs = null,
                    result = null,
                )
            )
        }

        suspend fun completeWithCard(result: Map<String, Any?>): Map<String, Any?> {
            val success = result["success"] != false
            val finishedAtMs = System.currentTimeMillis()
            callback?.onToolCardEvent(
                "tool_completed",
                nestedCallCardPresenter.payload(
                    cardId = cardId,
                    toolName = cardToolName,
                    stepTitle = stepTitle,
                    functionId = functionId,
                    callableTool = callableTool,
                    nestedArguments = nestedArguments,
                    status = if (success) "success" else "error",
                    success = success,
                    summary = result["summary"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: nestedCallCardPresenter.finishedSummary(functionId, success),
                    progress = "",
                    startedAtMs = cardStartedAtMs,
                    finishedAtMs = finishedAtMs,
                    result = result,
                )
            )
            return result
        }

        emitStarted()
        if (functionId.isEmpty()) {
            return completeWithCard(failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_FUNCTION },
                executor = "omniflow_function",
                summary = "$stepTitle missing function_id",
                errorCode = "OOB_FUNCTION_ID_MISSING",
            ))
        }
        val nestedSpec = loadSpec(functionId)
            ?: return completeWithCard(failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_FUNCTION },
                executor = "omniflow_function",
                summary = "OOB reusable function not found: $functionId",
                errorCode = "OOB_FUNCTION_NOT_FOUND",
                extras = mapOf("nested_function_id" to functionId),
            ))
        val missing = OobReusableFunctionStore.missingRequiredArguments(
            nestedSpec,
            nestedArguments
        )
        if (missing.isNotEmpty()) {
            return completeWithCard(failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_FUNCTION },
                executor = "omniflow_function",
                summary = "Missing required arguments: ${missing.joinToString(", ")}",
                errorCode = "OOB_FUNCTION_ARGUMENTS_MISSING",
                extras = mapOf(
                    "nested_function_id" to functionId,
                    "missing_required_arguments" to missing,
                ),
            ))
        }
        val materialized = OobReusableFunctionStore.materialize(nestedSpec, nestedArguments)
        val nestedRun = runNestedFunction(
            OobNestedFunctionRunRequest(
                functionId = functionId,
                spec = nestedSpec,
                materializedSpec = materialized,
                callback = callback,
                toolHandle = toolHandle,
                env = env,
                parentToolCallId = "${parentToolCallId ?: toolName}_$stepId",
                toolName = functionId,
                allowAgentFallback = allowAgentFallback,
                allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                callStack = callStack,
            )
        )
        val success = nestedRun["success"] == true
        return completeWithCard(linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to callableTool.ifEmpty { RunLogReplayPolicy.TOOL_CALL_FUNCTION },
            "executor" to "omniflow_function",
            "model_free" to true,
            "success" to success,
            "nested_function_id" to functionId,
            "nested_run_id" to nestedRun["run_id"],
            "nested_runner" to nestedRun["runner"],
            "nested_step_count" to nestedRun["step_count"],
            "nested_success_step_count" to nestedRun["success_step_count"],
            "nested_model_required" to nestedRun["model_required"],
            "step_results" to nestedRun["step_results"],
            "timing" to nestedRun["timing"],
            "error_code" to nestedRun["error_code"],
            "summary" to if (success) {
                "$stepTitle completed via local OOB Function: $functionId"
            } else {
                nestedRun["error_message"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "$stepTitle failed via local OOB Function: $functionId"
            },
        ).filterValues { it != null })
    }

    private fun failureStepResult(
        stepId: String,
        tool: String,
        executor: String,
        summary: String,
        errorCode: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = runResultBuilder.failureStep(
        stepId = stepId,
        tool = tool,
        executor = executor,
        summary = summary,
        errorCode = errorCode,
        extras = extras,
    )

}
