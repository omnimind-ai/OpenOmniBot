package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

class OobFunctionToolHandler(
    private val context: android.content.Context,
    private val helper: SharedHelper,
) : ToolHandler {
    override val toolNames: Set<String> = setOf("call_tool", "oob_tool_call")

    internal var router: cn.com.omnimind.bot.agent.AgentToolExecutor? = null

    /** Workspace-backed function store; injected by WorkbenchProjectStore on init. */
    var workspaceFunctionStore: cn.com.omnimind.bot.workbench.WorkspaceFunctionStore? = null

    override fun canHandle(toolName: String): Boolean =
        RunLogReplayPolicy.isOmniflowToolCallTool(toolName) || runCatching {
            cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, toolName) != null
        }.getOrDefault(false) || workspaceFunctionStore?.canHandle(toolName) == true

    /** Returns the function spec from SharedPreferences or workspace, whichever has it. */
    private fun getSpec(functionId: String): Map<String, Any?>? =
        runCatching {
            cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, functionId)
        }.getOrNull()
            ?: workspaceFunctionStore?.get(functionId)

    fun canRunFullyWithOmniflow(materializedSpec: Map<String, Any?>): Boolean {
        val steps = materializedSteps(materializedSpec)
        return steps.isNotEmpty() && steps.all { step ->
            OmniflowStepExecutor.isOmniflowStep(step) || isOmniflowExecutionStep(step)
        }
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
        if (RunLogReplayPolicy.isOmniflowToolCallTool(toolName)) {
            return executeModelCallTool(toolCall, args, env, callback, toolHandle)
        }
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

    private suspend fun executeModelCallTool(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle,
    ): cn.com.omnimind.bot.agent.ToolExecutionResult {
        val toolName = toolCall.function.name
        val argsMap = helper.jsonObjectToMap(args)
        val targetTool = callToolTargetTool(argsMap, emptyMap())
        val targetArgs = callToolArguments(argsMap)
        val functionId = firstNonBlank(
            callToolFunctionId(argsMap, emptyMap()),
            if (RunLogReplayPolicy.isOmniflowFunctionTool(targetTool)) {
                callToolFunctionId(targetArgs, emptyMap())
            } else {
                null
            },
            targetTool.takeIf { it.isNotEmpty() && getSpec(it) != null },
        )

        if (functionId.isNotEmpty()) {
            val spec = getSpec(functionId)
                ?: return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                    toolName, "OOB function not found: $functionId"
                )
            val missing = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
                .missingRequiredArguments(spec, targetArgs)
            if (missing.isNotEmpty()) {
                return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                    toolName,
                    "Missing required arguments: ${missing.joinToString(", ")}"
                )
            }
            val materialized = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
                .materialize(spec, targetArgs)
            val runPayload = runMaterializedFunction(
                functionId = functionId,
                spec = spec,
                materializedSpec = materialized,
                callback = callback,
                toolHandle = toolHandle,
                env = env,
                parentToolCallId = toolCall.id,
                toolName = toolName,
            )
            val success = runPayload["success"] == true
            val payload = helper.encodeLocalizedPayload(runPayload)
            val summary = if (success) {
                "OOB Function completed: $functionId"
            } else {
                runPayload["error_message"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "OOB Function failed: $functionId"
            }
            return cn.com.omnimind.bot.agent.ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = summary,
                previewJson = payload,
                rawResultJson = payload,
                success = success
            )
        }

        if (targetTool.isEmpty()) {
            return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "call_tool requires tool_name or function_id"
            )
        }
        if (RunLogReplayPolicy.isOmniflowToolCallTool(targetTool)) {
            return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "Nested call_tool is not allowed"
            )
        }
        val delegatedRouter = router
            ?: return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "Tool router unavailable for $targetTool"
            )
        val targetArgsJson = helper.mapToJsonElement(targetArgs) as? JsonObject
            ?: JsonObject(emptyMap())
        val syntheticCall = cn.com.omnimind.baselib.llm.AssistantToolCall(
            id = "${toolCall.id}_${targetTool}",
            type = "function",
            function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                name = targetTool,
                arguments = targetArgsJson.toString()
            )
        )
        val subDescriptor = cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor(
            name = targetTool,
            displayName = targetTool,
            toolType = "call_tool"
        )
        return delegatedRouter.execute(
            syntheticCall,
            targetArgsJson,
            subDescriptor,
            env,
            callback,
            toolHandle
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
        callStack: List<String> = emptyList(),
    ): Map<String, Any?> {
        val runStartedAtMs = System.currentTimeMillis()
        val normalizedFunctionId = functionId.trim()
        val auditRunId = nextRunId(runStartedAtMs)
        if (normalizedFunctionId.isNotEmpty() && normalizedFunctionId in callStack) {
            return failedRunPayload(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                errorCode = "OOB_FUNCTION_RECURSION",
                errorMessage = "Recursive OOB function call detected: " +
                    (callStack + normalizedFunctionId).joinToString(" -> ")
            )
        }
        if (callStack.size >= MAX_OMNIFLOW_CALL_DEPTH) {
            return failedRunPayload(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                errorCode = "OOB_FUNCTION_MAX_DEPTH",
                errorMessage = "OOB function call depth exceeds $MAX_OMNIFLOW_CALL_DEPTH"
            )
        }
        val activeCallStack = if (normalizedFunctionId.isNotEmpty()) {
            callStack + normalizedFunctionId
        } else {
            callStack
        }
        val steps = materializedSteps(materializedSpec)
        val stepResults = mutableListOf<Map<String, Any?>>()
        var delegatedToolUsed = false
        var modelRequired = false
        var failureReason: String? = null

        for ((index, step) in steps.withIndex()) {
            val stepStartedAtMs = System.currentTimeMillis()
            toolHandle?.throwIfStopRequested()
            val stepIndex = index + 1
            val stepId = step["id"]?.toString() ?: "step_$stepIndex"
            val stepTitle = step["title"]?.toString() ?: stepId
            val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
                .ifEmpty { "agent" }
            val callableTool = step["callable_tool"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: step["tool"]?.toString()?.trim().orEmpty()
            val omniflowExecutionTool = omniflowExecutionToolForStep(step, callableTool)

            if (callback != null) {
                helper.reportToolProgress(
                    callback = callback,
                    toolName = toolName,
                    progress = "$stepIndex/${steps.size} $stepTitle",
                    toolHandle = toolHandle
                )
            }

            val stepResult: Map<String, Any?> = when {
                RunLogReplayPolicy.isOmniflowToolCallTool(omniflowExecutionTool) -> {
                    executeOmniflowToolCallStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = omniflowExecutionTool,
                        callback = callback,
                        toolHandle = toolHandle,
                        env = env,
                        parentToolCallId = parentToolCallId,
                        toolName = toolName,
                        allowAgentFallback = allowAgentFallback,
                        allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                        callStack = activeCallStack,
                    ).also { result ->
                        if (result["needs_agent"] == true) modelRequired = true
                        if (result["delegated_tool_used"] == true) delegatedToolUsed = true
                    }
                }

                RunLogReplayPolicy.isOmniflowFunctionTool(omniflowExecutionTool) -> {
                    executeOmniflowFunctionStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = omniflowExecutionTool,
                        callback = callback,
                        toolHandle = toolHandle,
                        env = env,
                        parentToolCallId = parentToolCallId,
                        toolName = toolName,
                        allowAgentFallback = allowAgentFallback,
                        allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                        callStack = activeCallStack,
                    )
                }

                RunLogReplayPolicy.isOmniflowGraphTool(omniflowExecutionTool) -> {
                    executeOmniflowGraphStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = omniflowExecutionTool,
                    )
                }

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
            val stepFinishedAtMs = System.currentTimeMillis()
            val timedStepResult = LinkedHashMap<String, Any?>().apply {
                putAll(stepResult)
                putIfAbsent("index", index)
                putIfAbsent("started_at_ms", stepStartedAtMs)
                putIfAbsent("finished_at_ms", stepFinishedAtMs)
                putIfAbsent("duration_ms", (stepFinishedAtMs - stepStartedAtMs).coerceAtLeast(0))
            }
            stepResults += timedStepResult
            if (timedStepResult["success"] == false) {
                failureReason = timedStepResult["summary"]?.toString()
                break
            }
        }

        val runFinishedAtMs = System.currentTimeMillis()
        val successCount = stepResults.count { it["success"] != false }
        val allSuccess = stepResults.size == steps.size && stepResults.none { it["success"] == false }
        val description = spec["description"]?.toString().orEmpty()
        return linkedMapOf(
            "success" to allSuccess,
            "run_id" to auditRunId,
            "audit_run_id" to auditRunId,
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
            "error_code" to stepResults.firstOrNull { it["success"] == false }
                ?.get("error_code"),
            "error_message" to failureReason,
            "timing" to linkedMapOf(
                "source" to "oob_function_runner",
                "started_at_ms" to runStartedAtMs,
                "finished_at_ms" to runFinishedAtMs,
                "runner_duration_ms" to (runFinishedAtMs - runStartedAtMs).coerceAtLeast(0)
            ),
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

    private suspend fun executeOmniflowToolCallStep(
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
    ): Map<String, Any?> {
        val args = stepArgs(step)
        val targetTool = callToolTargetTool(args, step)
        val targetArgs = callToolArguments(args)
        val functionId = firstNonBlank(
            callToolFunctionId(args, step),
            if (RunLogReplayPolicy.isOmniflowFunctionTool(targetTool)) {
                callToolFunctionId(targetArgs, emptyMap())
            } else {
                null
            },
            targetTool.takeIf { it.isNotEmpty() && getSpec(it) != null },
        )
        if (functionId.isNotEmpty()) {
            val functionStep = LinkedHashMap<String, Any?>().apply {
                putAll(step)
                put("args", LinkedHashMap<String, Any?>().apply {
                    putAll(args)
                    put("function_id", functionId)
                    put("arguments", targetArgs)
                })
            }
            return executeOmniflowFunctionStep(
                step = functionStep,
                stepId = stepId,
                stepTitle = stepTitle,
                callableTool = callableTool.ifEmpty { "call_tool" },
                callback = callback,
                toolHandle = toolHandle,
                env = env,
                parentToolCallId = parentToolCallId,
                toolName = toolName,
                allowAgentFallback = allowAgentFallback,
                allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
                callStack = callStack,
            )
        }

        if (targetTool.isEmpty()) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_tool" },
                executor = "tool",
                summary = "$stepTitle missing tool_name or function_id",
                errorCode = "OOB_CALL_TOOL_TARGET_MISSING",
            )
        }
        if (RunLogReplayPolicy.isOmniflowToolCallTool(targetTool)) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_tool" },
                executor = "tool",
                summary = "$stepTitle nested call_tool is not allowed",
                errorCode = "OOB_CALL_TOOL_RECURSION",
            )
        }
        if (router != null && env != null && callback != null && toolHandle != null) {
            val delegatedStep = LinkedHashMap<String, Any?>().apply {
                putAll(step)
                put("tool", targetTool)
                put("callable_tool", targetTool)
                put("args", targetArgs)
            }
            return LinkedHashMap<String, Any?>().apply {
                putAll(
                    executeToolStep(
                        step = delegatedStep,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = targetTool,
                        env = env,
                        callback = callback,
                        toolHandle = toolHandle,
                        syntheticCallId = "${parentToolCallId ?: toolName}_$stepId",
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
                "executor" to "agent",
                "blocked_executor" to "tool",
                "prompt" to agentFallbackPrompt(
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
            executor = "tool",
            summary = "Tool router unavailable for $targetTool",
            errorCode = "OOB_CALL_TOOL_ROUTER_UNAVAILABLE",
        )
    }

    private suspend fun executeOmniflowFunctionStep(
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
    ): Map<String, Any?> {
        val args = stepArgs(step)
        val functionId = firstNonBlank(
            args["function_id"],
            args["functionId"],
            args["id"],
            args["name"],
            step["function_id"],
            step["functionId"],
        )
        if (functionId.isEmpty()) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_function" },
                executor = "omniflow_function",
                summary = "$stepTitle missing function_id",
                errorCode = "OOB_FUNCTION_ID_MISSING",
            )
        }
        val nestedSpec = getSpec(functionId)
            ?: return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_function" },
                executor = "omniflow_function",
                summary = "OOB reusable function not found: $functionId",
                errorCode = "OOB_FUNCTION_NOT_FOUND",
                extras = mapOf("nested_function_id" to functionId),
            )
        val nestedArguments = nestedFunctionArguments(args)
        val missing = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
            .missingRequiredArguments(nestedSpec, nestedArguments)
        if (missing.isNotEmpty()) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_function" },
                executor = "omniflow_function",
                summary = "Missing required arguments: ${missing.joinToString(", ")}",
                errorCode = "OOB_FUNCTION_ARGUMENTS_MISSING",
                extras = mapOf(
                    "nested_function_id" to functionId,
                    "missing_required_arguments" to missing,
                ),
            )
        }
        val materialized = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
            .materialize(nestedSpec, nestedArguments)
        val nestedRun = runMaterializedFunction(
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
        val success = nestedRun["success"] == true
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to callableTool.ifEmpty { "call_function" },
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
        ).filterValues { it != null }
    }

    private suspend fun executeOmniflowGraphStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
    ): Map<String, Any?> {
        val path = resolveGraphPath(step, callableTool)
        if (path.isEmpty()) {
            return failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "go_to_node" },
                executor = "omniflow_graph",
                summary = "$stepTitle has no executable UTG path",
                errorCode = "OOB_UTG_PATH_EMPTY",
            )
        }

        val primitiveResults = mutableListOf<Map<String, Any?>>()
        for ((index, primitiveStep) in path.withIndex()) {
            val pathStepId = "${stepId}_path_${index + 1}"
            val pathTitle = primitiveStep["title"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "$stepTitle path ${index + 1}"
            val startedAtMs = System.currentTimeMillis()
            val result = try {
                executeOmniflowStep(primitiveStep, pathStepId, pathTitle)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failureStepResult(
                    stepId = pathStepId,
                    tool = OmniflowStepExecutor.actionNameForStep(primitiveStep),
                    executor = "omniflow",
                    summary = e.message ?: "UTG path action failed",
                    errorCode = "OOB_UTG_ACTION_FAILED",
                )
            }
            val finishedAtMs = System.currentTimeMillis()
            primitiveResults += LinkedHashMap<String, Any?>().apply {
                putAll(result)
                putIfAbsent("index", index)
                putIfAbsent("started_at_ms", startedAtMs)
                putIfAbsent("finished_at_ms", finishedAtMs)
                putIfAbsent("duration_ms", (finishedAtMs - startedAtMs).coerceAtLeast(0))
            }
            if (result["success"] == false) break
        }

        val success = primitiveResults.size == path.size &&
            primitiveResults.none { it["success"] == false }
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to callableTool.ifEmpty { "go_to_node" },
            "executor" to "omniflow_graph",
            "model_free" to true,
            "success" to success,
            "path_length" to path.size,
            "success_path_step_count" to primitiveResults.count { it["success"] != false },
            "step_results" to primitiveResults,
            "summary" to if (success) {
                "$stepTitle completed via local UTG path"
            } else {
                primitiveResults.lastOrNull()?.get("summary")?.toString()
                    ?: "$stepTitle failed in local UTG path"
            },
        )
    }

    private suspend fun executeOmniflowStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
    ): Map<String, Any?> {
        return OmniflowStepExecutor.execute(step, stepId, stepTitle)
    }

    private fun isOmniflowExecutionStep(step: Map<String, Any?>): Boolean {
        val tool = omniflowExecutionToolForStep(step, executionToolName(step))
        return when {
            RunLogReplayPolicy.isOmniflowGraphTool(tool) -> true
            RunLogReplayPolicy.isOmniflowFunctionTool(tool) -> true
            RunLogReplayPolicy.isOmniflowToolCallTool(tool) -> {
                val args = stepArgs(step)
                firstNonBlank(
                    callToolFunctionId(args, step),
                    callToolTargetTool(args, step).takeIf {
                        it.isNotEmpty() && getSpec(it) != null
                    },
                ).isNotEmpty()
            }
            else -> false
        }
    }

    private fun executionToolName(step: Map<String, Any?>): String =
        firstNonBlank(
            step["callable_tool"],
            step["tool"],
            step["omniflow_action"],
            step["local_action"],
            step["type"],
        )

    private fun omniflowExecutionToolForStep(
        step: Map<String, Any?>,
        callableTool: String,
    ): String {
        val agentCall = stringMap(step["agent_call"])
        val agentArgs = stringMap(agentCall["args"])
        val candidates = listOf(
            callableTool,
            step["tool"],
            step["callable_tool"],
            step["omniflow_action"],
            step["local_action"],
            step["type"],
            agentArgs["original_tool"],
            agentCall["original_tool"],
        )
        return candidates.asSequence()
            .map { it?.toString()?.trim().orEmpty() }
            .map { RunLogReplayPolicy.normalizeToolName(it) }
            .firstOrNull { it.isNotEmpty() && RunLogReplayPolicy.isOmniflowExecutionTool(it) }
            .orEmpty()
    }

    private fun stepArgs(step: Map<String, Any?>): Map<String, Any?> {
        val directArgs = stringMap(step["args"])
        val agentCall = stringMap(step["agent_call"])
        val agentArgs = stringMap(agentCall["args"])
        val originalArgs = stringMap(directArgs["original_args"])
            .ifEmpty { stringMap(directArgs["originalArgs"]) }
            .ifEmpty { stringMap(agentArgs["original_args"]) }
            .ifEmpty { stringMap(agentArgs["originalArgs"]) }
        val topLevelArgs = buildMap {
            for (key in EXECUTION_ARG_KEYS) {
                if (step.containsKey(key)) put(key, step[key])
            }
        }
        return when {
            directArgs.hasExecutionArgs() -> directArgs
            originalArgs.isNotEmpty() -> originalArgs
            topLevelArgs.isNotEmpty() -> topLevelArgs
            else -> directArgs
        }
    }

    private fun nestedFunctionArguments(args: Map<String, Any?>): Map<String, Any?> {
        val nested = stringMap(args["arguments"])
            .ifEmpty { stringMap(args["args"]) }
            .ifEmpty { stringMap(args["input"]) }
        if (nested.isNotEmpty()) return nested
        return linkedMapOf<String, Any?>().apply {
            args.forEach { (key, value) ->
                if (key !in FUNCTION_CALL_META_KEYS) put(key, value)
            }
        }
    }

    private fun callToolFunctionId(
        args: Map<String, Any?>,
        step: Map<String, Any?>,
    ): String = firstNonBlank(
        args["function_id"],
        args["functionId"],
        args["oob_function_id"],
        args["oobFunctionId"],
        step["function_id"],
        step["functionId"],
        step["oob_function_id"],
        step["oobFunctionId"],
    )

    private fun callToolTargetTool(
        args: Map<String, Any?>,
        step: Map<String, Any?>,
    ): String = firstNonBlank(
        args["tool_name"],
        args["toolName"],
        args["target_tool"],
        args["targetTool"],
        args["tool"],
        step["tool_name"],
        step["toolName"],
        step["target_tool"],
        step["targetTool"],
    )

    private fun callToolArguments(args: Map<String, Any?>): Map<String, Any?> {
        val nested = stringMap(args["arguments"])
            .ifEmpty { stringMap(args["args"]) }
            .ifEmpty { stringMap(args["input"]) }
        if (nested.isNotEmpty()) return nested
        return linkedMapOf<String, Any?>().apply {
            args.forEach { (key, value) ->
                if (key !in CALL_TOOL_META_KEYS) put(key, value)
            }
        }
    }

    private fun resolveGraphPath(
        step: Map<String, Any?>,
        callableTool: String,
    ): List<Map<String, Any?>> {
        val args = stepArgs(step)
        val directPath = listArg(args["path"]).ifEmpty { listArg(step["path"]) }
        val utg = stringMap(args["utg"])
            .ifEmpty { stringMap(step["utg"]) }
            .ifEmpty { stringMap(args["graph"]) }
            .ifEmpty { stringMap(step["graph"]) }
        val utgPathIds = listArg(utg["path"])
        val utgEdges = listArg(utg["edges"])
            .mapNotNull { stringMap(it).takeIf { edge -> edge.isNotEmpty() } }
        val edges = listArg(args["edges"])
            .ifEmpty { listArg(step["edges"]) }
            .mapNotNull { stringMap(it).takeIf { edge -> edge.isNotEmpty() } }
            .ifEmpty { utgEdges }

        val rawPath = when {
            directPath.isNotEmpty() -> directPath
            edges.isNotEmpty() && RunLogReplayPolicy.normalizeToolName(callableTool) in
                setOf("click_node", "node_click") -> selectClickNodeEdges(edges, args)
            utgPathIds.isNotEmpty() && utgEdges.isNotEmpty() -> {
                val edgeById = utgEdges.associateBy { firstNonBlank(it["edge_id"], it["edgeId"], it["id"]) }
                utgPathIds.mapNotNull { rawId -> edgeById[rawId?.toString().orEmpty()] }
            }
            edges.isNotEmpty() -> selectGoToNodeEdges(edges, args)
            else -> emptyList()
        }
        return rawPath.mapNotNull { raw ->
            val edge = stringMap(raw)
            edgeToOmniflowStep(edge)
        }
    }

    private fun selectClickNodeEdges(
        edges: List<Map<String, Any?>>,
        args: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val edgeId = firstNonBlank(args["edge_id"], args["edgeId"])
        val actionId = firstNonBlank(args["action_id"], args["actionId"])
        val targetNodeId = firstNonBlank(args["node_id"], args["nodeId"], args["target_node_id"], args["targetNodeId"])
        val selected = edges.firstOrNull { edge ->
            (edgeId.isNotEmpty() && firstNonBlank(edge["edge_id"], edge["edgeId"], edge["id"]) == edgeId) ||
                (actionId.isNotEmpty() && firstNonBlank(edge["action_id"], edge["actionId"]) == actionId) ||
                (targetNodeId.isNotEmpty() && firstNonBlank(edge["to_node_id"], edge["toNodeId"], edge["node_id"], edge["nodeId"]) == targetNodeId)
        } ?: edges.firstOrNull()
        return selected?.let { listOf(it) }.orEmpty()
    }

    private fun selectGoToNodeEdges(
        edges: List<Map<String, Any?>>,
        args: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val targetNodeId = firstNonBlank(args["node_id"], args["nodeId"], args["target_node_id"], args["targetNodeId"])
        if (targetNodeId.isEmpty()) return edges
        val targetIndex = edges.indexOfFirst { edge ->
            firstNonBlank(edge["to_node_id"], edge["toNodeId"], edge["node_id"], edge["nodeId"]) == targetNodeId
        }
        return if (targetIndex >= 0) edges.take(targetIndex + 1) else emptyList()
    }

    private fun edgeToOmniflowStep(edge: Map<String, Any?>): Map<String, Any?>? {
        val action = firstNonBlank(
            edge["action"],
            edge["tool"],
            edge["omniflow_action"],
            edge["local_action"],
            edge["type"],
        )
        val localAction = RunLogReplayPolicy.omniflowActionForToolName(action) ?: return null
        val edgeArgs = linkedMapOf<String, Any?>()
        edgeArgs.putAll(stringMap(edge["args"]))
        for (key in listOf(
            "x",
            "y",
            "x1",
            "y1",
            "x2",
            "y2",
            "direction",
            "distance",
            "distance_px",
            "distancePx",
            "duration",
            "duration_ms",
            "durationMs",
            "content",
            "text",
            "value",
            "package_name",
            "packageName",
            "key",
            "hotkey",
            "hot_key",
            "target_description",
            "targetDescription",
        )) {
            if (edgeArgs[key] == null && edge.containsKey(key)) {
                edgeArgs[key] = edge[key]
            }
        }
        return linkedMapOf(
            "title" to firstNonBlank(edge["title"], edge["summary"], edge["target_description"], edge["targetDescription"], localAction),
            "kind" to "omniflow_action",
            "executor" to "omniflow",
            "omniflow_action" to localAction,
            "local_action" to localAction,
            "model_free" to true,
            "scriptable" to true,
            "tool" to localAction,
            "callable_tool" to localAction,
            "args" to edgeArgs,
            "source_context" to stringMap(edge["source_context"]).takeIf { it.isNotEmpty() },
            "coordinate_hook" to edge["coordinate_hook"],
        ).filterValues { it != null }
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

    private fun failureStepResult(
        stepId: String,
        tool: String,
        executor: String,
        summary: String,
        errorCode: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "step_id" to stepId,
        "tool" to tool,
        "executor" to executor,
        "model_free" to true,
        "success" to false,
        "needs_agent" to false,
        "fallback_available" to false,
        "error_code" to errorCode,
        "summary" to summary,
    ).apply {
        putAll(extras)
    }

    private fun failedRunPayload(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        errorCode: String,
        errorMessage: String,
    ): Map<String, Any?> {
        val finishedAtMs = System.currentTimeMillis()
        return linkedMapOf(
            "success" to false,
            "run_id" to auditRunId,
            "audit_run_id" to auditRunId,
            "function_id" to (spec["function_id"] ?: functionId),
            "description" to spec["description"]?.toString().orEmpty(),
            "runner" to "oob_omniflow_replay",
            "step_count" to 0,
            "success_step_count" to 0,
            "model_used" to false,
            "model_required" to false,
            "delegated_tool_used" to false,
            "fallback_available" to false,
            "error_code" to errorCode,
            "error_message" to errorMessage,
            "timing" to linkedMapOf(
                "source" to "oob_function_runner",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "runner_duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0)
            ),
            "step_results" to emptyList<Map<String, Any?>>()
        )
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun stringMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.associate { (key, item) -> key.toString() to item }
    }

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }

    private fun Map<String, Any?>.hasExecutionArgs(): Boolean =
        EXECUTION_ARG_KEYS.any { key -> this[key] != null }

    private val FUNCTION_CALL_META_KEYS = setOf(
        "function_id",
        "functionId",
        "id",
        "name",
        "tool_name",
        "toolName",
        "target_tool",
        "targetTool",
        "oob_function_id",
        "oobFunctionId",
        "goal",
        "tool_title",
        "tool",
        "callable_tool",
        "arguments",
        "args",
        "input",
    )

    private val EXECUTION_ARG_KEYS = setOf(
        "function_id",
        "functionId",
        "id",
        "name",
        "tool_name",
        "toolName",
        "target_tool",
        "targetTool",
        "oob_function_id",
        "oobFunctionId",
        "node_id",
        "nodeId",
        "target_node_id",
        "targetNodeId",
        "edge_id",
        "edgeId",
        "action_id",
        "actionId",
        "path",
        "edges",
        "utg",
        "graph",
        "arguments",
        "args",
        "input",
    )

    private val CALL_TOOL_META_KEYS = setOf(
        "function_id",
        "functionId",
        "oob_function_id",
        "oobFunctionId",
        "tool_name",
        "toolName",
        "target_tool",
        "targetTool",
        "tool",
        "callable_tool",
        "arguments",
        "args",
        "input",
        "goal",
        "tool_title",
    )

    private companion object {
        const val MAX_OMNIFLOW_CALL_DEPTH = 8
        val RUN_SEQUENCE = AtomicLong(0)
    }

    private fun nextRunId(startedAtMs: Long): String =
        "omniflow_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
}
