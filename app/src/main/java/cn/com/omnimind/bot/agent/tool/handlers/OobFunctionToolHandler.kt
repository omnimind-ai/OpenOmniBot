package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.OmniFlowUiSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobPageVectorSet
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.PendingActionStack
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
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
            isSkippedLegacyStep(step) ||
                OmniflowStepExecutor.isOmniflowStep(step) ||
                isOmniflowExecutionStep(step)
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
        val missing = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
            .missingRequiredArguments(spec, argsMap)
        if (missing.isNotEmpty()) {
            return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName,
                "Missing required arguments: ${missing.joinToString(", ")}"
            )
        }
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
        val callTool = resolveCallToolRequest(argsMap)
        val targetTool = callTool.targetTool
        val targetArgs = callTool.targetArgs
        val functionId = callTool.functionId

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
        allowAgentFallback: Boolean = false,
        allowToolDelegationWithoutRouter: Boolean = false,
        callStack: List<String> = emptyList(),
        resumeFromStep: Int = 0,
        fallbackSessionId: String = "",
        fallbackAttempt: Int = 0,
    ): Map<String, Any?> {
        val runStartedAtMs = System.currentTimeMillis()
        val timing = FunctionRunnerTiming(runStartedAtMs)
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
        val steps = timing.measure("materialized_steps_ms") { materializedSteps(materializedSpec) }
        val normalizedResumeFromStep = resumeFromStep.coerceIn(0, steps.size)
        val activeSteps = if (normalizedResumeFromStep > 0) {
            steps.drop(normalizedResumeFromStep)
        } else {
            steps
        }
        val pendingActionStack = timing.measure("pending_action_stack_build_ms") {
            PendingActionStack.fromSteps(
                steps = activeSteps,
                functionSpec = materializedSpec,
                originalSpec = spec,
                startIndex = normalizedResumeFromStep,
            )
        }
        val preflightFailure = timing.measure("accessibility_preflight_ms") {
            accessibilityPreflightFailure(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                steps = activeSteps,
            )
        }
        preflightFailure?.let {
            return withRunnerTiming(it, timing.finish())
        }

        val frontendSession = startOmniFlowFrontendIfNeeded(
            functionId = normalizedFunctionId.ifBlank { functionId },
            spec = spec,
            stepCount = activeSteps.size,
            toolHandle = toolHandle,
            callStack = callStack,
        )
        var frontendFinished = false
        var frontendFinishMessage = helper.localized("任务已完成")
        try {
        // Global package checker: if the foreground app doesn't match the Function's
        // entry package, launch it before the step loop. Handles the case where the
        // user navigated away, or the Function was compiled without an open_app step.
        ensureEntryPackageForeground(steps)

        // Checker rules from the Function spec (metadata.checker_rules).
        // These are layered on top of the global built-in rules inside the executor.
        val functionCheckerRules = OmniflowCheckerRule.fromSpec(spec)

        val stepResults = mutableListOf<Map<String, Any?>>()
        var delegatedToolUsed = false
        var modelRequired = false
        var failureReason: String? = null

        val stepLoopStartedAt = System.nanoTime()
        timing.recordSinceStart("pre_step_loop_ms", stepLoopStartedAt)
        var skippedBySourceAlignmentCount = 0
        while (!pendingActionStack.isEmpty()) {
            val stepStartedAtMs = System.currentTimeMillis()
            frontendSession?.throwIfStopRequested()
            toolHandle?.throwIfStopRequested()
            val alignmentResult = alignPendingActionStack(pendingActionStack)
            if (alignmentResult.skippedResults.isNotEmpty()) {
                skippedBySourceAlignmentCount += alignmentResult.skippedResults.size
                stepResults += alignmentResult.skippedResults
            }
            alignmentResult.failureResult?.let { failure ->
                stepResults += failure
                failureReason = failure["summary"]?.toString()
                break
            }
            val frame = pendingActionStack.peek() ?: break
            val index = frame.originalIndex
            val step = frame.step
            val stepIndex = index + 1
            val stepId = step["id"]?.toString() ?: "step_$stepIndex"
            val stepTitle = step["title"]?.toString() ?: stepId
            frontendSession?.update("第 $stepIndex/${steps.size} 步 $stepTitle")
            val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
                .ifEmpty { "agent" }
            val callableTool = step["callable_tool"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: step["tool"]?.toString()?.trim().orEmpty()
            val omniflowExecutionTool = omniflowExecutionToolForStep(step, callableTool)
            if (RunLogReplayPolicy.shouldSkipTool(callableTool) ||
                RunLogReplayPolicy.shouldSkipTool(omniflowExecutionTool) ||
                RunLogReplayPolicy.shouldSkipTool(OmniflowStepExecutor.actionNameForStep(step))
            ) {
                stepResults += linkedMapOf<String, Any?>(
                    "step_id" to stepId,
                    "index" to index,
                    "tool" to callableTool.ifEmpty { omniflowExecutionTool },
                    "executor" to "omniflow",
                    "skipped" to true,
                    "success" to true,
                    "summary" to "Skipped legacy non-semantic step",
                    "started_at_ms" to stepStartedAtMs,
                    "finished_at_ms" to stepStartedAtMs,
                    "duration_ms" to 0L
                )
                pendingActionStack.popExecuted()
                continue
            }

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
                        checkerRules = functionCheckerRules,
                    )
                }

                OmniflowStepExecutor.isOmniflowStep(step) -> {
                    try {
                        executeOmniflowStep(step, stepId, stepTitle, functionCheckerRules)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val executionError = e as? OmniflowStepExecutor.ExecutionException
                        val failReason = e.message ?: "omniflow step failed"
                        val recovery = refetchCurrentPageForFailedStep(failReason)
                        val fallbackResult = if (allowAgentFallback) {
                            tryOmniflowVlmFallback(
                                step = step,
                                stepId = stepId,
                                stepTitle = stepTitle,
                                failReason = failReason,
                                recovery = recovery,
                                env = env,
                                callback = callback,
                                toolHandle = toolHandle,
                                parentToolCallId = parentToolCallId ?: toolName
                            )
                        } else {
                            null
                        }
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
                                "prompt" to agentFallbackPrompt(step, stepTitle, recovery),
                                "omniflow_fail_reason" to failReason,
                                "error_code" to executionError?.errorCode,
                                "diagnostics" to executionError?.diagnostics?.takeIf { it.isNotEmpty() },
                                "recovery" to recovery,
                                "summary" to "Omniflow step requires agent fallback: $stepTitle"
                            ).filterValues { it != null }
                        } else {
                            linkedMapOf<String, Any?>(
                                "step_id" to stepId,
                                "tool" to OmniflowStepExecutor.actionNameForStep(step),
                                "executor" to "omniflow",
                                "model_free" to true,
                                "success" to false,
                                "needs_agent" to false,
                                "fallback_available" to false,
                                "error_code" to (executionError?.errorCode ?: "OOB_OMNIFLOW_STEP_FAILED"),
                                "diagnostics" to executionError?.diagnostics?.takeIf { it.isNotEmpty() },
                                "recovery" to recovery,
                                "summary" to failReason
                            ).filterValues { it != null }
                        }
                    }
                }

                executor == "tool" && callableTool.isNotEmpty() && router != null &&
                    env != null -> {
                    delegatedToolUsed = true
                    executeToolStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = callableTool,
                        env = env,
                        callback = callback ?: cn.com.omnimind.bot.agent.NoOpAgentCallback,
                        toolHandle = toolHandle ?: cn.com.omnimind.bot.agent.NoOpAgentRunControl
                            .beginToolExecution(callableTool, "${parentToolCallId ?: toolName}_$stepId"),
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
                        agentTool.isNotEmpty() && router != null && env != null
                    ) {
                        delegatedToolUsed = true
                        executeToolStep(
                            step = step,
                            stepId = stepId,
                            stepTitle = stepTitle,
                            callableTool = agentTool,
                            env = env,
                            callback = callback ?: cn.com.omnimind.bot.agent.NoOpAgentCallback,
                            toolHandle = toolHandle ?: cn.com.omnimind.bot.agent.NoOpAgentRunControl
                                .beginToolExecution(agentTool, "${parentToolCallId ?: toolName}_$stepId"),
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
                if (!timedStepResult.containsKey("recovery")) {
                    timedStepResult["recovery"] = refetchCurrentPageForFailedStep(
                        timedStepResult["summary"]?.toString() ?: "step failed"
                    )
                }
                failureReason = timedStepResult["summary"]?.toString()
                break
            }
            pendingActionStack.popExecuted()
        }
        timing.recordElapsed("step_loop_ms", stepLoopStartedAt)

        val resultBuildStartedAt = System.nanoTime()
        val successCount = stepResults.count { it["success"] != false }
        val allSuccess = stepResults.size == activeSteps.size && stepResults.none { it["success"] == false }
        val failedStepIndex = stepResults.firstOrNull { it["success"] == false }
            ?.get("index")
        val description = spec["description"]?.toString().orEmpty()
        val resultPayload = linkedMapOf<String, Any?>(
            "success" to allSuccess,
            "run_id" to auditRunId,
            "audit_run_id" to auditRunId,
            "function_id" to (spec["function_id"] ?: functionId),
            "description" to description,
            "source" to "omniflow_replay",
            "run_source" to "omniflow_replay",
            "runner" to when {
                modelRequired -> "oob_function_agent_fallback_required"
                delegatedToolUsed -> "oob_function_mixed_runner"
                else -> RunLogReplayPolicy.fixedReplayRunner
            },
            "replay_mode" to if (RunLogReplayPolicy.fixedReplayOnly) "fixed_replay" else "omniflow_loop",
            "step_count" to steps.size,
            "active_step_count" to activeSteps.size,
            "success_step_count" to successCount,
            "completed_step_count" to (normalizedResumeFromStep + successCount).coerceAtMost(steps.size),
            "resume_from_step" to normalizedResumeFromStep,
            "fallback_session_id" to fallbackSessionId.takeIf { it.isNotBlank() },
            "fallback_attempt" to fallbackAttempt.takeIf { it > 0 },
            "model_used" to false,
            "model_required" to modelRequired,
            "delegated_tool_used" to delegatedToolUsed,
            "fallback_available" to (allowAgentFallback && modelRequired),
            "failed_step_index" to failedStepIndex,
            "pending_action_stack" to linkedMapOf(
                "source_alignment_enabled" to pendingActionStack.sourceAlignmentEnabled,
                "skipped_by_source_alignment_count" to skippedBySourceAlignmentCount,
            ),
            "error_code" to stepResults.firstOrNull { it["success"] == false }
                ?.get("error_code"),
            "error_message" to failureReason,
            "step_results" to stepResults
        )
        timing.recordElapsed("result_build_ms", resultBuildStartedAt)
        val runFinishedAtMs = System.currentTimeMillis()
        resultPayload["timing"] = timing.finish(runFinishedAtMs)
        frontendFinishMessage = helper.localized(if (allSuccess) "任务已完成" else "任务执行失败")
        frontendSession?.finish(frontendFinishMessage)
        frontendFinished = true
        return resultPayload
        } catch (e: ManualToolStopCancellationException) {
            frontendFinishMessage = helper.localized("任务已停止")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            frontendFinishMessage = helper.localized("任务已停止")
            throw e
        } catch (e: Exception) {
            frontendFinishMessage = helper.localized("任务执行失败")
            throw e
        } finally {
            if (!frontendFinished) {
                frontendSession?.finish(frontendFinishMessage)
            }
        }
    }

    private fun agentFallbackPrompt(
        step: Map<String, Any?>,
        stepTitle: String,
        recovery: Map<String, Any?> = emptyMap(),
    ): String {
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
        val recoveryText = recoveryPromptSuffix(recovery)
        return "$prompt$argsText$recoveryText"
    }

    private suspend fun refetchCurrentPageForFailedStep(reason: String): Map<String, Any?> =
        runCatching {
            OmniflowStepExecutor.currentPageSnapshotForRecovery(reason)
        }.getOrElse { error ->
            linkedMapOf(
                "refetched_current_page" to false,
                "reason" to reason,
                "error_message" to error.message.orEmpty(),
            )
        }

    private fun alignPendingActionStack(
        stack: PendingActionStack,
    ): StackAlignmentResult {
        if (!stack.sourceAlignmentEnabled) return StackAlignmentResult()
        val top = stack.peek() ?: return StackAlignmentResult()
        val window = stack.windowUntilNextKey()
        val candidates = window.filter { it.hasSourcePage }
        if (candidates.isEmpty()) return StackAlignmentResult()

        val observedAtMs = System.currentTimeMillis()
        val currentXml = runCatching { OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty() }
            .getOrDefault("")
        if (currentXml.isBlank()) return StackAlignmentResult()
        val currentPackage = runCatching { OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty() }
            .getOrDefault("")
        val currentVector = OobPageVectorSet.encode(xml = currentXml, packageName = currentPackage)
            ?: return StackAlignmentResult()

        var bestFrame: PendingActionStack.ActionFrame? = null
        var bestScore = Float.NEGATIVE_INFINITY
        candidates.forEach { frame ->
            val sourceVector = frame.sourceVector ?: return@forEach
            val score = OobPageVectorSet.cosine(currentVector.vector, sourceVector.vector)
            if (score > bestScore) {
                bestScore = score
                bestFrame = frame
            }
        }
        val matched = bestFrame?.takeIf { bestScore >= OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE }
        if (matched == null) {
            if (!top.hasSourcePage) return StackAlignmentResult()
            val failedAtMs = System.currentTimeMillis()
            return StackAlignmentResult(
                failureResult = linkedMapOf<String, Any?>(
                    "step_id" to top.stepId,
                    "index" to top.originalIndex,
                    "tool" to top.tool,
                    "executor" to "omniflow",
                    "model_free" to true,
                    "success" to false,
                    "needs_agent" to false,
                    "fallback_available" to false,
                    "error_code" to "OOB_SOURCE_ALIGNMENT_MISS",
                    "summary" to "Current page does not match the pending function source window",
                    "source_alignment" to linkedMapOf(
                        "matched" to false,
                        "best_score" to bestScore.takeIf { it.isFinite() },
                        "min_score" to OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE,
                        "window" to window.map(::sourceAlignmentFrameSummary),
                        "current" to linkedMapOf(
                            "node_id" to currentVector.nodeId,
                            "package_name" to currentVector.packageName.takeIf { it.isNotBlank() },
                            "signature" to currentVector.signature,
                            "observed_at_ms" to observedAtMs,
                        ).filterValues { it != null },
                    ).filterValues { it != null },
                    "recovery" to linkedMapOf(
                        "refetched_current_page" to true,
                        "reason" to "source_alignment_miss",
                        "navigate_recovery_available" to false,
                        "target_window" to window.map(::sourceAlignmentFrameSummary),
                    ),
                    "started_at_ms" to observedAtMs,
                    "finished_at_ms" to failedAtMs,
                    "duration_ms" to (failedAtMs - observedAtMs).coerceAtLeast(0),
                ).filterValues { it != null }
            )
        }
        if (matched == top) return StackAlignmentResult()

        val skipped = stack.popSkippedUntil(matched)
        val skippedAtMs = System.currentTimeMillis()
        val skippedResults = skipped.map { frame ->
            linkedMapOf<String, Any?>(
                "step_id" to frame.stepId,
                "index" to frame.originalIndex,
                "tool" to frame.tool,
                "executor" to "omniflow",
                "model_free" to true,
                "skipped" to true,
                "skipped_by_source_alignment" to true,
                "success" to true,
                "summary" to "Skipped by source alignment: current page already matches step ${matched.originalIndex + 1}",
                "source_alignment" to linkedMapOf(
                    "matched" to true,
                    "matched_step_index" to matched.originalIndex,
                    "matched_step_id" to matched.stepId,
                    "page_similarity" to bestScore,
                    "min_score" to OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE,
                    "current_node_id" to currentVector.nodeId,
                    "current_package" to currentVector.packageName.takeIf { it.isNotBlank() },
                    "target_frame" to sourceAlignmentFrameSummary(matched),
                ).filterValues { it != null },
                "started_at_ms" to observedAtMs,
                "finished_at_ms" to skippedAtMs,
                "duration_ms" to (skippedAtMs - observedAtMs).coerceAtLeast(0),
            )
        }
        return StackAlignmentResult(skippedResults = skippedResults)
    }

    private fun sourceAlignmentFrameSummary(
        frame: PendingActionStack.ActionFrame,
    ): Map<String, Any?> = linkedMapOf(
        "step_index" to frame.originalIndex,
        "step_id" to frame.stepId,
        "tool" to frame.tool,
        "role" to frame.role,
        "is_key_action" to frame.isKeyAction,
        "source_package" to frame.sourcePackage.takeIf { it.isNotBlank() },
        "source_node_id" to frame.sourceVector?.nodeId,
        "source_signature" to frame.sourceVector?.signature,
    ).filterValues { it != null }

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
        recovery: Map<String, Any?>,
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
                "recovery" to recovery,
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
        val callTool = resolveCallToolRequest(args, step)
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
        if (router != null && env != null) {
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
                        callback = callback ?: cn.com.omnimind.bot.agent.NoOpAgentCallback,
                        toolHandle = toolHandle ?: cn.com.omnimind.bot.agent.NoOpAgentRunControl
                            .beginToolExecution(targetTool, "${parentToolCallId ?: toolName}_$stepId"),
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
        val nestedArguments = nestedFunctionArguments(args)
        val cardToolName = "call_function"
        val cardId = nestedFunctionToolCardId(parentToolCallId, toolName, stepId)
        val cardStartedAtMs = System.currentTimeMillis()

        suspend fun emitStarted() {
            callback?.onToolCardEvent(
                "tool_started",
                functionToolCardPayload(
                    cardId = cardId,
                    toolName = cardToolName,
                    stepTitle = stepTitle,
                    functionId = functionId,
                    callableTool = callableTool,
                    nestedArguments = nestedArguments,
                    status = "running",
                    success = null,
                    summary = reusableCommandRunningSummary(functionId),
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
                functionToolCardPayload(
                    cardId = cardId,
                    toolName = cardToolName,
                    stepTitle = stepTitle,
                    functionId = functionId,
                    callableTool = callableTool,
                    nestedArguments = nestedArguments,
                    status = if (success) "success" else "error",
                    success = success,
                    summary = result["summary"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: reusableCommandFinishedSummary(functionId, success),
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
                tool = callableTool.ifEmpty { "call_function" },
                executor = "omniflow_function",
                summary = "$stepTitle missing function_id",
                errorCode = "OOB_FUNCTION_ID_MISSING",
            ))
        }
        val nestedSpec = getSpec(functionId)
            ?: return completeWithCard(failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_function" },
                executor = "omniflow_function",
                summary = "OOB reusable function not found: $functionId",
                errorCode = "OOB_FUNCTION_NOT_FOUND",
                extras = mapOf("nested_function_id" to functionId),
            ))
        val missing = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
            .missingRequiredArguments(nestedSpec, nestedArguments)
        if (missing.isNotEmpty()) {
            return completeWithCard(failureStepResult(
                stepId = stepId,
                tool = callableTool.ifEmpty { "call_function" },
                executor = "omniflow_function",
                summary = "Missing required arguments: ${missing.joinToString(", ")}",
                errorCode = "OOB_FUNCTION_ARGUMENTS_MISSING",
                extras = mapOf(
                    "nested_function_id" to functionId,
                    "missing_required_arguments" to missing,
                ),
            ))
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
        return completeWithCard(linkedMapOf<String, Any?>(
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
        ).filterValues { it != null })
    }

    private fun nestedFunctionToolCardId(
        parentToolCallId: String?,
        toolName: String,
        stepId: String,
    ): String {
        val base = safeToolCardIdPart(firstNonBlank(parentToolCallId, toolName, "function"))
        val step = safeToolCardIdPart(stepId.ifBlank { "step" })
        return "${base}_${step}_call_function"
    }

    private fun safeToolCardIdPart(raw: String): String {
        val normalized = raw.trim().replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        return normalized.take(96).ifBlank { "function" }
    }

    private fun reusableCommandRunningSummary(functionId: String): String {
        return if (functionId.isNotBlank()) {
            "${helper.localized("正在执行复用指令")}：$functionId"
        } else {
            helper.localized("正在执行复用指令")
        }
    }

    private fun reusableCommandFinishedSummary(functionId: String, success: Boolean): String {
        val base = helper.localized(if (success) "复用指令执行完成" else "复用指令执行失败")
        return if (functionId.isNotBlank()) "$base：$functionId" else base
    }

    private fun functionToolCardPayload(
        cardId: String,
        toolName: String,
        stepTitle: String,
        functionId: String,
        callableTool: String,
        nestedArguments: Map<String, Any?>,
        status: String,
        success: Boolean?,
        summary: String,
        progress: String,
        startedAtMs: Long,
        finishedAtMs: Long?,
        result: Map<String, Any?>?,
    ): Map<String, Any?> {
        val argsPayload = linkedMapOf<String, Any?>(
            "function_id" to functionId.takeIf { it.isNotBlank() },
            "arguments" to nestedArguments.takeIf { it.isNotEmpty() },
            "source_tool" to callableTool.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
        val resultPayload = result?.let {
            linkedMapOf<String, Any?>(
                "function_id" to functionId.takeIf { id -> id.isNotBlank() },
                "source" to "omniflow_replay",
                "run_source" to "omniflow_replay",
                "runner" to it["runner"],
                "nested_run_id" to it["nested_run_id"],
                "nested_step_count" to it["nested_step_count"],
                "nested_success_step_count" to it["nested_success_step_count"],
                "success" to (it["success"] != false),
                "summary" to it["summary"],
                "error_code" to it["error_code"],
            ).filterValues { value -> value != null }
        }
        val argsJson = helper.encodeLocalizedPayload(argsPayload)
        return linkedMapOf<String, Any?>(
            "cardId" to cardId,
            "toolCallId" to cardId,
            "callId" to cardId,
            "toolName" to toolName,
            "displayName" to helper.localized("复用指令"),
            "toolType" to "oob_function",
            "source" to "omniflow_replay",
            "runSource" to "omniflow_replay",
            "run_source" to "omniflow_replay",
            "runner" to (result?.get("runner") ?: RunLogReplayPolicy.fixedReplayRunner),
            "toolTitle" to if (functionId.isNotBlank()) {
                "${helper.localized("复用指令")}：$functionId"
            } else {
                stepTitle
            },
            "summary" to summary,
            "progress" to progress,
            "status" to status,
            "success" to success,
            "args" to argsJson,
            "argsJson" to argsJson,
            "sourceTool" to callableTool,
            "functionId" to functionId,
            "function_id" to functionId,
            "startedAtMs" to startedAtMs,
            "started_at_ms" to startedAtMs,
            "finishedAtMs" to finishedAtMs,
            "finished_at_ms" to finishedAtMs,
            "durationMs" to finishedAtMs?.let { (it - startedAtMs).coerceAtLeast(0) },
            "duration_ms" to finishedAtMs?.let { (it - startedAtMs).coerceAtLeast(0) },
            "resultPreviewJson" to resultPayload?.let { helper.encodeLocalizedPayload(it) }.orEmpty(),
            "rawResultJson" to result?.let { helper.encodeLocalizedPayload(it) }.orEmpty(),
        ).filterValues { it != null }
    }

    private suspend fun executeOmniflowGraphStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
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
                executeOmniflowStep(primitiveStep, pathStepId, pathTitle, checkerRules)
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
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
    ): Map<String, Any?> {
        return OmniflowStepExecutor.execute(step, stepId, stepTitle, checkerRules)
    }

    private suspend fun ensureEntryPackageForeground(steps: List<Map<String, Any?>>) {
        val entryPackage = entryPackageForSteps(steps).takeIf { it.isNotBlank() } ?: return
        val currentPackage = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        if (currentPackage.isBlank() || currentPackage == entryPackage) return
        // Skip if the first step is already an open_app — it will handle launch itself.
        val firstAction = OmniflowStepExecutor.actionNameForStep(steps.first())
        if (firstAction == "open_app") return
        OmniLog.d(TAG, "global open_app: current=$currentPackage expected=$entryPackage")
        val openAppStep = linkedMapOf<String, Any?>(
            "id" to "global_open_app",
            "title" to "open_app: $entryPackage",
            "kind" to "omniflow_action",
            "executor" to "omniflow",
            "omniflow_action" to "open_app",
            "local_action" to "open_app",
            "model_free" to true,
            "tool" to "open_app",
            "callable_tool" to "open_app",
            "args" to linkedMapOf("package_name" to entryPackage, "reset_task" to false),
        )
        runCatching { OmniflowStepExecutor.execute(openAppStep, "global_open_app", "open_app: $entryPackage") }
            .onFailure { OmniLog.w(TAG, "global open_app failed for $entryPackage: ${it.message}") }
    }

    private fun entryPackageForSteps(steps: List<Map<String, Any?>>): String {
        // Prefer an explicit open_app arg.
        for (step in steps) {
            if (OmniflowStepExecutor.actionNameForStep(step) == "open_app") {
                val args = stringMap(step["args"])
                val pkg = firstNonBlank(args["package_name"], args["packageName"])
                if (pkg.isNotBlank()) return pkg
            }
        }
        // Fall back to src_ctx package from the first step that has one.
        for (step in steps) {
            val srcCtx = stringMap(stringMap(step["source_context"])["src_ctx"])
            val pkg = firstNonBlank(srcCtx["package_name"], srcCtx["packageName"])
            if (pkg.isNotBlank() &&
                !pkg.startsWith("cn.com.omnimind") &&
                pkg != "android" &&
                pkg != "com.android.systemui"
            ) return pkg
        }
        return ""
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

    private fun isSkippedLegacyStep(step: Map<String, Any?>): Boolean {
        val names = listOf(
            executionToolName(step),
            OmniflowStepExecutor.actionNameForStep(step),
            step["source_tool"]?.toString().orEmpty(),
        )
        return names.any { name ->
            name.isNotBlank() && RunLogReplayPolicy.shouldSkipTool(name)
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

    private fun resolveCallToolRequest(
        args: Map<String, Any?>,
        step: Map<String, Any?> = emptyMap(),
    ): CallToolRequest {
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
        return CallToolRequest(
            targetTool = targetTool,
            targetArgs = targetArgs,
            functionId = functionId,
        )
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
            "node_resource_id",
            "nodeResourceId",
            "resource_id",
            "resourceId",
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
        return OobFunctionSchemaBuilder.materializedSteps(materializedSpec)
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

    private fun accessibilityPreflightFailure(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        steps: List<Map<String, Any?>>,
    ): Map<String, Any?>? {
        val indexedStep = steps.withIndex().firstOrNull { (_, step) ->
            !isSkippedLegacyStep(step) && OmniflowStepExecutor.requiresAccessibility(step)
        } ?: return null
        if (OmniflowActionRuntime.backend.isReady()) return null

        val step = indexedStep.value
        val stepId = step["id"]?.toString() ?: "step_${indexedStep.index + 1}"
        val action = OmniflowStepExecutor.actionNameForStep(step)
        val message = "请先开启无障碍权限，复用指令才能执行点击、滑动和输入。"
        return failedRunPayload(
            functionId = functionId,
            spec = spec,
            auditRunId = auditRunId,
            startedAtMs = startedAtMs,
            errorCode = "OOB_ACCESSIBILITY_REQUIRED",
            errorMessage = message,
            extras = linkedMapOf(
                "step_count" to steps.size,
                "required_permission" to "accessibility",
                "missing_permissions" to listOf("accessibility"),
                "blocked_step_index" to indexedStep.index,
                "step_results" to listOf(
                    failureStepResult(
                        stepId = stepId,
                        tool = action,
                        executor = "omniflow",
                        summary = message,
                        errorCode = "OOB_ACCESSIBILITY_REQUIRED",
                        extras = linkedMapOf(
                            "index" to indexedStep.index,
                            "required_permission" to "accessibility",
                        )
                    )
                )
            )
        )
    }

    private fun failedRunPayload(
        functionId: String,
        spec: Map<String, Any?>,
        auditRunId: String,
        startedAtMs: Long,
        errorCode: String,
        errorMessage: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val finishedAtMs = System.currentTimeMillis()
        return linkedMapOf<String, Any?>(
            "success" to false,
            "run_id" to auditRunId,
            "audit_run_id" to auditRunId,
            "function_id" to (spec["function_id"] ?: functionId),
            "description" to spec["description"]?.toString().orEmpty(),
            "source" to "omniflow_replay",
            "run_source" to "omniflow_replay",
            "runner" to RunLogReplayPolicy.fixedReplayRunner,
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
                "duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0),
                "runner_duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0)
            ),
            "step_results" to emptyList<Map<String, Any?>>()
        ).apply {
            putAll(extras)
        }
    }

    private fun withRunnerTiming(
        payload: Map<String, Any?>,
        timing: Map<String, Any?>,
    ): Map<String, Any?> {
        val existingTiming = stringMap(payload["timing"])
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            putAll(timing)
            val phaseMs = linkedMapOf<String, Any?>().apply {
                putAll(stringMap(existingTiming["phase_ms"]))
                putAll(stringMap(timing["phase_ms"]))
            }
            if (phaseMs.isNotEmpty()) put("phase_ms", phaseMs)
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
    }

    private class FunctionRunnerTiming(
        private val startedAtMs: Long,
    ) {
        private val startedAtNanos = System.nanoTime()
        private val phases = linkedMapOf<String, Long>()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                recordElapsed(phaseName, phaseStartedAtNanos)
            }
        }

        fun recordElapsed(phaseName: String, phaseStartedAtNanos: Long) {
            phases[phaseName] = elapsedMs(phaseStartedAtNanos)
        }

        fun recordSinceStart(phaseName: String, endedAtNanos: Long = System.nanoTime()) {
            phases[phaseName] = ((endedAtNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        }

        fun finish(finishedAtMs: Long = System.currentTimeMillis()): Map<String, Any?> {
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "materialized_steps_ms",
                "accessibility_preflight_ms",
                "pre_step_loop_ms",
                "step_loop_ms",
                "result_build_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            val durationMs = (finishedAtMs - startedAtMs).coerceAtLeast(0)
            return linkedMapOf(
                "source" to "oob_function_runner",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to durationMs,
                "runner_duration_ms" to durationMs,
                "phase_ms" to completedPhases,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
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

    private data class CallToolRequest(
        val targetTool: String,
        val targetArgs: Map<String, Any?>,
        val functionId: String,
    )

    private data class OmniFlowFrontendSession(
        val runId: String,
        val taskId: String,
        val stopRequested: AtomicBoolean,
        val label: String,
    ) {
        fun throwIfStopRequested() {
            if (stopRequested.get()) {
                throw ManualToolStopCancellationException("OmniFlow execution stopped manually")
            }
        }
    }

    private data class StackAlignmentResult(
        val skippedResults: List<Map<String, Any?>> = emptyList(),
        val failureResult: Map<String, Any?>? = null,
    )

    private suspend fun startOmniFlowFrontendIfNeeded(
        functionId: String,
        spec: Map<String, Any?>,
        stepCount: Int,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        callStack: List<String>,
    ): OmniFlowFrontendSession? {
        if (stepCount <= 0 || callStack.isNotEmpty()) return null
        val runId = toolHandle?.runId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: nextRunId(System.currentTimeMillis())
        val taskId = "${runId}_omniflow_ui"
        val stopRequested = AtomicBoolean(false)
        val label = omniflowFrontendLabel(functionId, spec)
        OmniFlowUiSession.registerRun(
            runId = runId,
            onStopRequested = { stopRequested.set(true) },
            onCompleteRequested = { stopRequested.set(true) }
        )
        OmniFlowUiSession.beginTask(runId, taskId)
        withContext(Dispatchers.Main) {
            runCatching {
                ScreenMaskLoader.loadGoneViewScreenMask()
                DraggableBallInstance.loadBall()
                DraggableBallInstance.setDoing(
                    message = helper.localized("OmniFlow 准备执行"),
                    isShowTakeOver = false,
                    subMessage = helper.localized(label),
                    isShowStop = false,
                    isTouchable = false
                )
            }.onFailure {
                OmniLog.w(TAG, "start OmniFlow frontend failed: ${it.message}")
            }
        }
        return OmniFlowFrontendSession(
            runId = runId,
            taskId = taskId,
            stopRequested = stopRequested,
            label = label,
        )
    }

    private suspend fun OmniFlowFrontendSession.update(progress: String) {
        throwIfStopRequested()
        val message = helper.localized(
            "OmniFlow：${progress.trim().ifBlank { label }.take(48)}"
        )
        withContext(Dispatchers.Main) {
            runCatching {
                ScreenMaskLoader.loadGoneViewScreenMask()
                DraggableBallInstance.setDoing(
                    message = message,
                    isShowTakeOver = false,
                    subMessage = helper.localized("本地执行中"),
                    isShowStop = false,
                    isTouchable = false
                )
            }.onFailure {
                OmniLog.w(TAG, "update OmniFlow frontend failed: ${it.message}")
            }
        }
        throwIfStopRequested()
    }

    private suspend fun OmniFlowFrontendSession.finish(message: String) {
        OmniFlowUiSession.endTask(taskId)
        val end = OmniFlowUiSession.endRun(runId)
        if (!end.wasActive) return
        withContext(NonCancellable + Dispatchers.Main) {
            runCatching {
                ScreenMaskLoader.loadGoneViewScreenMask()
                DraggableBallInstance.finishDoingTask(message)
            }
                .onFailure { OmniLog.w(TAG, "finish OmniFlow frontend failed: ${it.message}") }
        }
    }

    private fun omniflowFrontendLabel(
        functionId: String,
        spec: Map<String, Any?>,
    ): String {
        val name = firstNonBlank(
            spec["name"],
            spec["title"],
            spec["description"],
            functionId,
        )
        return name.replace(Regex("\\s+"), " ").take(32).ifBlank { "复用指令" }
    }

    private companion object {
        const val TAG = "OobFunctionToolHandler"
        const val MAX_OMNIFLOW_CALL_DEPTH = 8
        const val MAX_RECOVERY_PROMPT_XML_CHARS = 6000
        val RUN_SEQUENCE = AtomicLong(0)
    }

    private fun nextRunId(startedAtMs: Long): String =
        "omniflow_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
}
