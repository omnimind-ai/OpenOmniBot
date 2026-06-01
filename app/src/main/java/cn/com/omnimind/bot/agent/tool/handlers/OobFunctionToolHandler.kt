package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.agent.AgentToolJson.mapToJsonElement
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.PendingActionStack
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

class OobFunctionToolHandler(
    private val context: android.content.Context,
    private val helper: SharedHelper,
    private val graphStepRunner: OobFunctionGraphStepRunner = OobFunctionGraphStepRunner(),
    private val entryPackageGuard: OobFunctionEntryPackageGuard = OobFunctionEntryPackageGuard(),
    private val frontendSessionController: OobFunctionFrontendSessionController =
        OobFunctionFrontendSessionController(helper),
    private val sourceAlignmentController: OobFunctionSourceAlignmentController =
        OobFunctionSourceAlignmentController(),
    private val agentFallbackController: OobFunctionAgentFallbackController =
        OobFunctionAgentFallbackController(),
    private val nestedCallCardPresenter: OobFunctionNestedCallCardPresenter =
        OobFunctionNestedCallCardPresenter(helper),
    private val callRequestResolver: OobFunctionCallRequestResolver =
        OobFunctionCallRequestResolver(),
    private val stepClassifier: OobFunctionStepClassifier =
        OobFunctionStepClassifier(callRequestResolver),
    private val runResultBuilder: OobFunctionRunResultBuilder =
        OobFunctionRunResultBuilder(),
    private val toolDelegationExecutor: OobFunctionToolDelegationExecutor =
        OobFunctionToolDelegationExecutor(),
    private val accessibilityPreflightGuard: OobFunctionAccessibilityPreflightGuard =
        OobFunctionAccessibilityPreflightGuard(stepClassifier, runResultBuilder),
    private val nestedFunctionExecutor: OobFunctionNestedFunctionExecutor =
        OobFunctionNestedFunctionExecutor(
            callRequestResolver,
            nestedCallCardPresenter,
            runResultBuilder
        ),
    private val callToolStepExecutor: OobFunctionCallToolStepExecutor =
        OobFunctionCallToolStepExecutor(
            callRequestResolver,
            toolDelegationExecutor,
            agentFallbackController,
            runResultBuilder
        ),
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
            stepClassifier.isSkippedLegacyStep(step) ||
                OmniflowStepExecutor.isOmniflowStep(step) ||
                stepClassifier.isOmniflowExecutionStep(step) { getSpec(it) != null }
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
        val callTool = callRequestResolver.resolve(argsMap) { getSpec(it) != null }
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
        val targetArgsJson = mapToJsonElement(targetArgs) as? JsonObject
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
        val timing = runResultBuilder.timing(runStartedAtMs)
        val normalizedFunctionId = functionId.trim()
        val auditRunId = nextRunId(runStartedAtMs)
        if (normalizedFunctionId.isNotEmpty() && normalizedFunctionId in callStack) {
            return runResultBuilder.failedRun(
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
            return runResultBuilder.failedRun(
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
            accessibilityPreflightGuard.failureIfBlocked(
                functionId = functionId,
                spec = spec,
                auditRunId = auditRunId,
                startedAtMs = runStartedAtMs,
                steps = activeSteps,
            )
        }
        preflightFailure?.let {
            return runResultBuilder.withRunnerTiming(it, timing.finish())
        }

        val frontendSession = frontendSessionController.start(
            functionId = normalizedFunctionId.ifBlank { functionId },
            spec = spec,
            stepCount = activeSteps.size,
            toolHandle = toolHandle,
            callStack = callStack,
            fallbackRunIdProvider = { nextRunId(System.currentTimeMillis()) },
        )
        var frontendFinished = false
        var frontendFinishMessage = helper.localized("任务已完成")
        try {
        entryPackageGuard.ensureForeground(steps)

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
            val alignmentResult = sourceAlignmentController.align(pendingActionStack)
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
            val omniflowExecutionTool = stepClassifier.omniflowExecutionToolForStep(step, callableTool)
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
                    graphStepRunner.execute(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = omniflowExecutionTool,
                        checkerRules = functionCheckerRules,
                    )
                }

                OmniflowStepExecutor.isOmniflowStep(step) -> {
                    try {
                        OmniflowStepExecutor.execute(step, stepId, stepTitle, functionCheckerRules)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val executionError = e as? OmniflowStepExecutor.ExecutionException
                        val failReason = e.message ?: "omniflow step failed"
                        val recovery = agentFallbackController.refetchCurrentPageForFailedStep(failReason)
                        val fallbackResult = if (allowAgentFallback) {
                            agentFallbackController.tryVlmFallback(
                                step = step,
                                stepId = stepId,
                                stepTitle = stepTitle,
                                failReason = failReason,
                                recovery = recovery,
                                router = router,
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
                                "prompt" to agentFallbackController.prompt(step, stepTitle, recovery),
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
                    val routerRef = router
                        ?: error("router became unavailable during tool delegation")
                    delegatedToolUsed = true
                    toolDelegationExecutor.execute(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = callableTool,
                        env = env,
                        callback = callback ?: cn.com.omnimind.bot.agent.NoOpAgentCallback,
                        toolHandle = toolHandle ?: cn.com.omnimind.bot.agent.NoOpAgentRunControl
                            .beginToolExecution(callableTool, "${parentToolCallId ?: toolName}_$stepId"),
                        syntheticCallId = "${parentToolCallId ?: toolName}_$stepId",
                        router = routerRef,
                    )
                }

                executor == "tool" && callableTool.isNotEmpty() &&
                    !allowToolDelegationWithoutRouter -> {
                    val agentPrompt = agentFallbackController.prompt(step, stepTitle)
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
                    val agentTool = stepClassifier.replayableAgentTool(step, callableTool)
                    if (!stepClassifier.requiresAgentPlanning(step) &&
                        agentTool.isNotEmpty() && router != null && env != null
                    ) {
                        val routerRef = router
                            ?: error("router became unavailable during agent tool delegation")
                        delegatedToolUsed = true
                        toolDelegationExecutor.execute(
                            step = step,
                            stepId = stepId,
                            stepTitle = stepTitle,
                            callableTool = agentTool,
                            env = env,
                            callback = callback ?: cn.com.omnimind.bot.agent.NoOpAgentCallback,
                            toolHandle = toolHandle ?: cn.com.omnimind.bot.agent.NoOpAgentRunControl
                                .beginToolExecution(agentTool, "${parentToolCallId ?: toolName}_$stepId"),
                            syntheticCallId = "${parentToolCallId ?: toolName}_$stepId",
                            router = routerRef,
                        ).also { result ->
                            (result as? LinkedHashMap<String, Any?>)
                                ?.put("executor", "agent_tool")
                        }
                    } else if (allowAgentFallback) {
                        val agentPrompt = agentFallbackController.prompt(step, stepTitle)
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
                    timedStepResult["recovery"] = agentFallbackController.refetchCurrentPageForFailedStep(
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
        val resultPayload = runResultBuilder.completedRun(
            functionId = functionId,
            spec = spec,
            auditRunId = auditRunId,
            steps = steps,
            activeSteps = activeSteps,
            stepResults = stepResults,
            normalizedResumeFromStep = normalizedResumeFromStep,
            fallbackSessionId = fallbackSessionId,
            fallbackAttempt = fallbackAttempt,
            modelRequired = modelRequired,
            delegatedToolUsed = delegatedToolUsed,
            allowAgentFallback = allowAgentFallback,
            sourceAlignmentEnabled = pendingActionStack.sourceAlignmentEnabled,
            skippedBySourceAlignmentCount = skippedBySourceAlignmentCount,
            failureReason = failureReason,
        )
        timing.recordElapsed("result_build_ms", resultBuildStartedAt)
        val runFinishedAtMs = System.currentTimeMillis()
        resultPayload["timing"] = timing.finish(runFinishedAtMs)
        val allSuccess = resultPayload["success"] == true
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
    ): Map<String, Any?> = callToolStepExecutor.execute(
        step = step,
        stepId = stepId,
        stepTitle = stepTitle,
        callableTool = callableTool,
        callback = callback,
        toolHandle = toolHandle,
        env = env,
        parentToolCallId = parentToolCallId,
        toolName = toolName,
        allowAgentFallback = allowAgentFallback,
        allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
        router = router,
        canLoadFunction = { getSpec(it) != null },
        executeNestedFunctionStep = { functionStep, nestedCallableTool ->
            executeOmniflowFunctionStep(
                step = functionStep,
                stepId = stepId,
                stepTitle = stepTitle,
                callableTool = nestedCallableTool,
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
    )

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
    ): Map<String, Any?> = nestedFunctionExecutor.execute(
        step = step,
        stepId = stepId,
        stepTitle = stepTitle,
        callableTool = callableTool,
        callback = callback,
        toolHandle = toolHandle,
        env = env,
        parentToolCallId = parentToolCallId,
        toolName = toolName,
        allowAgentFallback = allowAgentFallback,
        allowToolDelegationWithoutRouter = allowToolDelegationWithoutRouter,
        callStack = callStack,
        loadSpec = { getSpec(it) },
        runNestedFunction = { request ->
            runMaterializedFunction(
                functionId = request.functionId,
                spec = request.spec,
                materializedSpec = request.materializedSpec,
                callback = request.callback,
                toolHandle = request.toolHandle,
                env = request.env,
                parentToolCallId = request.parentToolCallId,
                toolName = request.toolName,
                allowAgentFallback = request.allowAgentFallback,
                allowToolDelegationWithoutRouter = request.allowToolDelegationWithoutRouter,
                callStack = request.callStack,
            )
        }
    )

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
    ): Map<String, Any?> = runResultBuilder.failureStep(
        stepId = stepId,
        tool = tool,
        executor = executor,
        summary = summary,
        errorCode = errorCode,
        extras = extras,
    )

    private companion object {
        const val TAG = "OobFunctionToolHandler"
        const val MAX_OMNIFLOW_CALL_DEPTH = 8
        val RUN_SEQUENCE = AtomicLong(0)
    }

    private fun nextRunId(startedAtMs: Long): String =
        "omniflow_run_${startedAtMs}_${RUN_SEQUENCE.incrementAndGet()}"
}
