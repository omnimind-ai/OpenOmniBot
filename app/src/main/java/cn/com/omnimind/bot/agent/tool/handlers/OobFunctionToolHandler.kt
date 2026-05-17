package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlinx.serialization.json.JsonObject

class OobFunctionToolHandler(
    private val context: android.content.Context,
    private val helper: SharedHelper,
) : ToolHandler {
    override val toolNames: Set<String> = emptySet()

    internal var router: cn.com.omnimind.bot.agent.AgentToolExecutor? = null

    /** Workspace-backed function store; injected by WorkbenchProjectStore on init. */
    var workspaceFunctionStore: cn.com.omnimind.bot.workbench.WorkspaceFunctionStore? = null

    override fun canHandle(toolName: String): Boolean =
        cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, toolName) != null
            || workspaceFunctionStore?.canHandle(toolName) == true

    /** Returns the function spec from SharedPreferences or workspace, whichever has it. */
    private fun getSpec(functionId: String): Map<String, Any?>? =
        cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, functionId)
            ?: workspaceFunctionStore?.get(functionId)

    fun canRunFullyWithOmniflow(materializedSpec: Map<String, Any?>): Boolean {
        val steps = materializedSteps(materializedSpec)
        return steps.isNotEmpty() && steps.all { OmniflowStepExecutor.isOmniflowStep(it) }
    }

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle
    ): cn.com.omnimind.bot.agent.ToolExecutionResult {
        val toolName = toolCall.function.name
        val spec = getSpec(toolName)
            ?: return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "OOB function not found: $toolName"
            )

        val argsMap = helper.jsonObjectToMap(args)
        val materializedSpec = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.materialize(spec, argsMap)

        val runPayload = runMaterializedFunction(
            functionId = toolName,
            spec = spec,
            materializedSpec = materializedSpec,
            callback = callback,
            toolHandle = toolHandle,
            env = env,
            parentToolCallId = toolCall.id,
            toolName = toolName
        )
        val steps = materializedSteps(materializedSpec)
        val description = spec["description"]?.toString().orEmpty()
        val stepResults = (runPayload["step_results"] as? List<*>) ?: emptyList<Any?>()
        val allSuccess = runPayload["success"] != false
        val summary = buildString {
            append(description.ifBlank { toolName })
            append(" — ")
            append("${runPayload["success_step_count"] ?: stepResults.size}/${steps.size} 步完成")
        }
        val payload = helper.encodeLocalizedPayload(runPayload)
        return cn.com.omnimind.bot.agent.ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = summary,
            previewJson = payload,
            rawResultJson = payload,
            success = allSuccess
        )
    }

    suspend fun runMaterializedFunction(
        functionId: String,
        spec: Map<String, Any?>,
        materializedSpec: Map<String, Any?>,
        callback: cn.com.omnimind.bot.agent.AgentCallback? = null,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle? = null,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment? = null,
        parentToolCallId: String? = null,
        toolName: String = functionId,
        allowAgentFallback: Boolean = true,
        allowToolDelegationWithoutRouter: Boolean = false,
    ): Map<String, Any?> {
        val steps = materializedSteps(materializedSpec)
        val stepResults = mutableListOf<Map<String, Any?>>()
        var delegatedToolUsed = false
        var modelRequired = false
        var failureReason: String? = null

        for ((index, step) in steps.withIndex()) {
            toolHandle?.throwIfStopRequested()
            val stepIndex = index + 1
            val stepId = step["id"]?.toString() ?: "step_$stepIndex"
            val stepTitle = step["title"]?.toString() ?: stepId
            val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
                .ifEmpty { "agent" }
            val callableTool = step["callable_tool"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: step["tool"]?.toString()?.trim().orEmpty()

            if (callback != null) {
                helper.reportToolProgress(
                    callback = callback,
                    toolName = toolName,
                    progress = "$stepIndex/${steps.size} $stepTitle",
                    toolHandle = toolHandle
                )
            }

            val stepResult: Map<String, Any?> = when {
                OmniflowStepExecutor.isOmniflowStep(step) -> {
                    try {
                        executeOmniflowStep(step, stepId, stepTitle)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val fallbackResult = tryOmniflowVlmFallback(
                            step = step,
                            stepId = stepId,
                            stepTitle = stepTitle,
                            failReason = e.message ?: "omniflow step failed",
                            env = env,
                            callback = callback,
                            toolHandle = toolHandle,
                            parentToolCallId = parentToolCallId ?: toolName
                        )
                        if (fallbackResult != null) {
                            delegatedToolUsed = true
                        }
                        fallbackResult ?: if (allowAgentFallback) {
                            modelRequired = true
                            linkedMapOf<String, Any?>(
                                "step_id" to stepId,
                                "tool" to OmniflowStepExecutor.actionNameForStep(step),
                                "executor" to "agent",
                                "blocked_executor" to "omniflow",
                                "model_free" to true,
                                "success" to false,
                                "needs_agent" to true,
                                "fallback_available" to true,
                                "prompt" to agentFallbackPrompt(step, stepTitle),
                                "omniflow_fail_reason" to (e.message ?: "omniflow step failed"),
                                "summary" to "Omniflow step requires agent fallback: $stepTitle"
                            )
                        } else {
                            linkedMapOf<String, Any?>(
                                "step_id" to stepId,
                                "tool" to OmniflowStepExecutor.actionNameForStep(step),
                                "executor" to "omniflow",
                                "model_free" to true,
                                "success" to false,
                                "summary" to (e.message ?: "omniflow step failed")
                            )
                        }
                    }
                }

                executor == "tool" && callableTool.isNotEmpty() && router != null &&
                    env != null && callback != null && toolHandle != null -> {
                    delegatedToolUsed = true
                    executeToolStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = callableTool,
                        env = env,
                        callback = callback,
                        toolHandle = toolHandle,
                        syntheticCallId = "${parentToolCallId ?: toolName}_$stepId"
                    )
                }

                executor == "tool" && callableTool.isNotEmpty() &&
                    !allowToolDelegationWithoutRouter -> {
                    val agentPrompt = agentFallbackPrompt(step, stepTitle)
                    modelRequired = true
                    linkedMapOf(
                        "step_id" to stepId,
                        "tool" to callableTool,
                        "executor" to "agent",
                        "blocked_executor" to "tool",
                        "prompt" to agentPrompt,
                        "success" to false,
                        "needs_agent" to true,
                        "fallback_available" to true,
                        "summary" to "Tool step requires agent runner: $stepTitle"
                    )
                }

                else -> {
                    val agentTool = replayableAgentTool(step, callableTool)
                    if (!requiresAgentPlanning(step) &&
                        agentTool.isNotEmpty() && router != null && env != null &&
                        callback != null && toolHandle != null
                    ) {
                        delegatedToolUsed = true
                        executeToolStep(
                            step = step,
                            stepId = stepId,
                            stepTitle = stepTitle,
                            callableTool = agentTool,
                            env = env,
                            callback = callback,
                            toolHandle = toolHandle,
                            syntheticCallId = "${parentToolCallId ?: toolName}_$stepId"
                        ).also { result ->
                            (result as? LinkedHashMap<String, Any?>)
                                ?.put("executor", "agent_tool")
                        }
                    } else if (allowAgentFallback) {
                        val agentPrompt = agentFallbackPrompt(step, stepTitle)
                        modelRequired = true
                        linkedMapOf(
                            "step_id" to stepId,
                            "tool" to agentTool.ifEmpty { "?" },
                            "executor" to "agent",
                            "prompt" to agentPrompt,
                            "success" to false,
                            "needs_agent" to true,
                            "fallback_available" to true,
                            "summary" to "Agent fallback required: $stepTitle"
                        )
                    } else {
                        linkedMapOf(
                            "step_id" to stepId,
                            "tool" to agentTool.ifEmpty { callableTool.ifEmpty { "?" } },
                            "executor" to executor.ifEmpty { "agent" },
                            "success" to false,
                            "needs_agent" to false,
                            "fallback_available" to false,
                            "summary" to "Agent fallback disabled: $stepTitle"
                        )
                    }
                }
            }
            stepResults += stepResult
            if (stepResult["success"] == false) {
                failureReason = stepResult["summary"]?.toString()
                break
            }
        }

        val successCount = stepResults.count { it["success"] != false }
        val allSuccess = stepResults.size == steps.size && stepResults.none { it["success"] == false }
        val description = spec["description"]?.toString().orEmpty()
        return linkedMapOf(
            "success" to allSuccess,
            "function_id" to (spec["function_id"] ?: functionId),
            "description" to description,
            "runner" to when {
                modelRequired -> "oob_function_agent_fallback_required"
                delegatedToolUsed -> "oob_function_mixed_runner"
                else -> "oob_omniflow_replay"
            },
            "step_count" to steps.size,
            "success_step_count" to successCount,
            "model_used" to false,
            "model_required" to modelRequired,
            "delegated_tool_used" to delegatedToolUsed,
            "fallback_available" to (!allSuccess || modelRequired),
            "error_message" to failureReason,
            "step_results" to stepResults
        )
    }

    private fun agentFallbackPrompt(step: Map<String, Any?>, stepTitle: String): String {
        val prompt = (step["agent_call"] as? Map<*, *>)
            ?.get("args")?.let { (it as? Map<*, *>)?.get("prompt")?.toString() }
            ?: (step["fallback"] as? Map<*, *>)?.get("prompt")?.toString()
            ?: stepTitle
        val args = OmniflowStepExecutor.normalizeArgsMap(step["args"])
        val argsText = if (args.isNotEmpty()) {
            "\n\n当前已物化参数：${helper.mapToJsonElement(args)}"
        } else {
            ""
        }
        return "$prompt$argsText"
    }

    private fun requiresAgentPlanning(step: Map<String, Any?>): Boolean {
        val reason = (step["agent_call"] as? Map<*, *>)?.get("reason")?.toString()
            ?: step["reason"]?.toString()
            ?: ""
        return RunLogReplayPolicy.requiresAgentPlanningReason(reason)
    }

    /**
     * When omniflow coordinate remapping fails, fall back to vlm_task using
     * target_description from the step args. Returns null if fallback is not
     * possible (no router, no target_description, or vlm_task itself fails).
     */
    private suspend fun tryOmniflowVlmFallback(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        failReason: String,
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
        }
        val vlmArgs = helper.mapToJsonElement(
            mapOf("goal" to goal, "startFromCurrent" to true)
        ) as? kotlinx.serialization.json.JsonObject
            ?: return null
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
            val subResult = router!!.execute(
                syntheticCall, vlmArgs, subDescriptor, env, callback, toolHandle
            )
            val succeeded = subResult !is cn.com.omnimind.bot.agent.ToolExecutionResult.Error
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to "vlm_task",
                "executor" to "omniflow_vlm_fallback",
                "success" to succeeded,
                "omniflow_fail_reason" to failReason,
                "summary" to "omniflow remap failed → vlm fallback: $goal"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun replayableAgentTool(step: Map<String, Any?>, callableTool: String): String {
        val agentCall = step["agent_call"] as? Map<*, *>
        val agentArgs = agentCall?.get("args") as? Map<*, *>
        val candidates = listOf(
            agentArgs?.get("original_tool"),
            step["tool"],
            callableTool,
            agentCall?.get("tool")
        )
        return candidates.asSequence()
            .map { it?.toString()?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() && it != "oob.agent.run" }
            .orEmpty()
    }

    private suspend fun executeToolStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle,
        syntheticCallId: String,
    ): Map<String, Any?> {
        val remapResult = OmniflowStepExecutor.remapStepArgs(step)
        val stepArgsMap = remapResult.args
        val stepArgs = when (stepArgsMap) {
            is Map<*, *> -> helper.mapToJsonElement(
                stepArgsMap.entries.associate { (k, v) -> k.toString() to v }
            ) as? JsonObject ?: JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
        val syntheticCall = cn.com.omnimind.baselib.llm.AssistantToolCall(
            id = syntheticCallId,
            type = "function",
            function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                name = callableTool,
                arguments = stepArgs.toString()
            )
        )
        val subDescriptor = cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor(
            name = callableTool,
            displayName = stepTitle,
            toolType = "oob_function_step"
        )
        return try {
            val subResult = router!!.execute(
                syntheticCall, stepArgs, subDescriptor, env, callback, toolHandle
            )
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to callableTool,
                "executor" to "tool",
                "success" to (subResult !is cn.com.omnimind.bot.agent.ToolExecutionResult.Error),
                "summary" to when (subResult) {
                    is cn.com.omnimind.bot.agent.ToolExecutionResult.ContextResult -> subResult.summaryText
                    is cn.com.omnimind.bot.agent.ToolExecutionResult.Error -> subResult.message
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

    private suspend fun executeOmniflowStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
    ): Map<String, Any?> {
        return OmniflowStepExecutor.execute(step, stepId, stepTitle)
    }

    private fun materializedSteps(materializedSpec: Map<String, Any?>): List<Map<String, Any?>> {
        val rawSteps = (materializedSpec["execution"] as? Map<*, *>)?.get("steps") as? List<*>
            ?: return emptyList()
        return rawSteps.mapNotNull { rawStep ->
            (rawStep as? Map<*, *>)?.entries?.associate { (key, value) ->
                key.toString() to value
            }
        }
    }

}
