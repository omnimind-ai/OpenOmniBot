package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.intArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowStepExecutor
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Owns OOB Function run policy: guard decisions and agent fallback handoff
 * context. It does not execute steps or persist Function records.
 */
class OobFunctionRunPolicy(
    private val functionRepository: OobFunctionRepository,
) {
    fun guardCheck(functionId: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val spec = functionRepository.get(functionId)
            ?: return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId,
                decision = DECISION_BLOCK,
                riskLevel = RISK_HIGH
            )
        val missing = OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
        if (missing.isNotEmpty()) {
            return errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId,
                decision = DECISION_BLOCK,
                riskLevel = RISK_HIGH
            ) + linkedMapOf("missing_required_arguments" to missing)
        }

        val materialized = OobReusableFunctionStore.materialize(spec, arguments)
        val stepDecisions = materializedSteps(materialized).map(::guardStep)
        val decision = aggregateDecision(stepDecisions)
        val riskLevel = aggregateRisk(stepDecisions)
        val reason = when (decision) {
            DECISION_ALLOW -> "All steps are deterministic local replay or registered tool calls."
            DECISION_AGENT_REQUIRED -> "At least one step requires live Agent planning."
            DECISION_NEEDS_CONFIRMATION -> "At least one step can affect device state outside local UI replay."
            DECISION_BLOCK -> "At least one step is blocked by OOB safety policy."
            else -> "Guard decision unavailable."
        }
        return linkedMapOf<String, Any?>(
            "success" to true,
            "function_id" to functionId,
            "decision" to decision,
            "risk_level" to riskLevel,
            "reason" to reason,
            "requires_confirmation" to (decision == DECISION_NEEDS_CONFIRMATION),
            "requires_root" to stepDecisions.any { it["requires_root"] == true },
            "step_decisions" to stepDecisions,
            "source" to "oob_native_omniflow_run_policy"
        ).filterValues { it != null }
    }

    fun fallbackMetadata(
        functionId: String,
        arguments: Map<String, Any?>,
        runPayload: Map<String, Any?>,
        guard: Map<String, Any?>,
        requestedResumeFromStep: Int,
        fallbackSessionId: String,
        fallbackAttempt: Int,
    ): Map<String, Any?> {
        if (runPayload["success"] == true) return emptyMap()
        val stepResults = listArg(runPayload["step_results"])
        val failedStep = stepResults
            .mapNotNull { mapArg(it).takeIf { result -> result["success"] == false } }
            .firstOrNull()
            ?: return emptyMap()
        val failedStepIndex = intArg(
            failedStep["index"],
            runPayload["failed_step_index"],
            runPayload["blocked_step_index"],
            defaultValue = requestedResumeFromStep
        ).coerceAtLeast(0)
        val fallbackEligible = runPayload["model_required"] == true ||
            failedStep["model_required"] == true
        if (!fallbackEligible) return emptyMap()

        val nextAttempt = fallbackAttempt + 1
        val attemptLimitReached = fallbackAttempt >= MAX_FUNCTION_FALLBACK_ATTEMPTS_PER_STEP
        val sessionId = fallbackSessionId.ifBlank {
            "oob_fallback_${functionId.ifBlank { "unknown" }}_${System.currentTimeMillis()}"
        }
        if (attemptLimitReached) {
            return linkedMapOf<String, Any?>(
                "fallback_session_id" to sessionId,
                "resume_from_step" to failedStepIndex,
                "fallback_attempt" to nextAttempt,
                "fallback_unavailable_reason" to "repeated_failure_same_step",
            )
        }
        val remainingSteps = materializedStepSummariesFrom(functionId, arguments, failedStepIndex)
        val recovery = mapArg(failedStep["recovery"])
        val agentPrompt = buildAgentFallbackPrompt(
            functionId = functionId,
            arguments = arguments,
            failedStep = failedStep,
            remainingSteps = remainingSteps,
            resumeFromStep = failedStepIndex,
            sessionId = sessionId,
            nextAttempt = nextAttempt,
            attemptLimitReached = attemptLimitReached,
        )
        val fallbackContext = linkedMapOf<String, Any?>(
            "schema_version" to "oob.function_fallback_context.v1",
            "function_id" to functionId,
            "arguments" to arguments,
            "guard_decision" to guard["decision"],
            "risk_level" to guard["risk_level"],
            "fallback_session_id" to sessionId,
            "resume_from_step" to failedStepIndex,
            "fallback_attempt" to nextAttempt,
            "max_attempts_per_step" to MAX_FUNCTION_FALLBACK_ATTEMPTS_PER_STEP,
            "failed_step" to summarizeStepResult(failedStep),
            "executed_steps" to stepResults.mapNotNull { raw ->
                summarizeStepResult(mapArg(raw)).takeIf { it.isNotEmpty() }
            },
            "remaining_steps" to remainingSteps,
            "recovery" to recovery.takeIf { it.isNotEmpty() },
            "return_instruction" to linkedMapOf(
                "tool" to OobFunctionToolNames.FUNCTION_RUN,
                "args" to linkedMapOf(
                    "function_id" to functionId,
                    "arguments" to arguments,
                    "resume_from_step" to failedStepIndex,
                    "fallback_session_id" to sessionId,
                    "fallback_attempt" to nextAttempt,
                )
            ),
            "agent_rule" to "先在当前页面完成 failed_step；完成后用 return_instruction 从 resume_from_step 继续本地重放。"
        ).filterValues { it != null }

        return linkedMapOf<String, Any?>(
            "fallback_session_id" to sessionId,
            "resume_from_step" to failedStepIndex,
            "fallback_attempt" to nextAttempt,
            "fallback_context" to fallbackContext,
            "agent_prompt" to agentPrompt,
        ).filterValues { it != null }
    }

    private fun guardStep(step: Map<String, Any?>): Map<String, Any?> {
        val stepId = firstNonBlank(step["id"], step["step_id"])
        val tool = firstNonBlank(step["tool"], step["omniflow_action"], step["callable_tool"], step["type"])
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(tool)
        val action = OobActionCodec.canonicalActionForName(normalizedTool) ?: normalizedTool
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val decision: String
        val risk: String
        val reason: String
        val requiresRoot: Boolean
        when {
            RunLogReplayPolicy.shouldSkipTool(tool) || RunLogReplayPolicy.shouldSkipTool(action) -> {
                decision = DECISION_ALLOW
                risk = RISK_LOW
                reason = "$action is an observation-only replay step"
                requiresRoot = false
            }
            action == OobActionCodec.ACTION_FINISHED -> {
                decision = DECISION_ALLOW
                risk = RISK_LOW
                reason = "finished is a terminal marker"
                requiresRoot = false
            }
            isBlockedAction(action, step) -> {
                decision = DECISION_BLOCK
                risk = RISK_HIGH
                reason = "$action is blocked by OOB policy"
                requiresRoot = true
            }
            isConfirmationAction(action, step) -> {
                decision = DECISION_NEEDS_CONFIRMATION
                risk = RISK_HIGH
                reason = "$action requires user confirmation"
                requiresRoot = action.contains("root") || action.contains("shizuku")
            }
            OmniflowStepExecutor.isOmniflowStep(step) -> {
                decision = DECISION_ALLOW
                risk = RISK_LOW
                reason = "$action is a deterministic local replay action"
                requiresRoot = false
            }
            RunLogReplayPolicy.isOmniflowExecutionTool(action) -> {
                decision = DECISION_ALLOW
                risk = if (RunLogReplayPolicy.isOmniflowFunctionTool(action)) RISK_MEDIUM else RISK_LOW
                reason = "$action is handled by OOB native OmniFlow execution"
                requiresRoot = false
            }
            executor == RunLogReplayPolicy.EXECUTOR_AGENT || RunLogReplayPolicy.isAgentTool(action) -> {
                decision = DECISION_AGENT_REQUIRED
                risk = RISK_MEDIUM
                reason = "$action requires live Agent planning"
                requiresRoot = false
            }
            else -> {
                decision = DECISION_AGENT_REQUIRED
                risk = RISK_MEDIUM
                reason = "$action is not a fixed local replay action"
                requiresRoot = false
            }
        }
        return linkedMapOf(
            "step_id" to stepId,
            "tool" to tool,
            "decision" to decision,
            "risk_level" to risk,
            "reason" to reason,
            "requires_root" to requiresRoot
        )
    }

    private fun aggregateDecision(stepDecisions: List<Map<String, Any?>>): String {
        val decisions = stepDecisions.map { it["decision"]?.toString().orEmpty() }
        return when {
            decisions.contains(DECISION_BLOCK) -> DECISION_BLOCK
            decisions.contains(DECISION_NEEDS_CONFIRMATION) -> DECISION_NEEDS_CONFIRMATION
            decisions.contains(DECISION_AGENT_REQUIRED) -> DECISION_AGENT_REQUIRED
            else -> DECISION_ALLOW
        }
    }

    private fun aggregateRisk(stepDecisions: List<Map<String, Any?>>): String {
        val risks = stepDecisions.map { it["risk_level"]?.toString().orEmpty() }
        return when {
            risks.contains(RISK_HIGH) -> RISK_HIGH
            risks.contains(RISK_MEDIUM) -> RISK_MEDIUM
            else -> RISK_LOW
        }
    }

    private fun isBlockedAction(action: String, step: Map<String, Any?>): Boolean {
        val text = guardScanText(step)
        return BLOCKED_ACTION_TOKENS.any { action.contains(it) || text.contains(it) }
    }

    private fun isConfirmationAction(action: String, step: Map<String, Any?>): Boolean {
        val text = guardScanText(step)
        return CONFIRMATION_ACTION_TOKENS.any { action.contains(it) || text.contains(it) }
    }

    private fun guardScanText(step: Map<String, Any?>): String {
        val parts = mutableListOf<String>()
        for (key in GUARD_SCAN_KEYS) {
            val value = step[key] ?: continue
            parts += when (key) {
                "args" -> sanitizedGuardArgs(value).toString()
                else -> value.toString()
            }
        }
        return parts.joinToString(" ").lowercase()
    }

    private fun sanitizedGuardArgs(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (rawKey, rawItem) ->
                    val key = rawKey?.toString() ?: return@forEach
                    if (key in GUARD_CONTEXT_KEYS) return@forEach
                    put(key, sanitizedGuardArgs(rawItem))
                }
            }
            is List<*> -> value.map(::sanitizedGuardArgs)
            is Array<*> -> value.map(::sanitizedGuardArgs)
            else -> value
        }

    private fun summarizeStepResult(step: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "index" to step["index"],
            "step_id" to step["step_id"],
            "title" to step["title"],
            "tool" to step["tool"],
            "executor" to step["executor"],
            "success" to step["success"],
            "model_required" to step["model_required"],
            "error_code" to step["error_code"],
            "summary" to step["summary"],
            "prompt" to step["prompt"],
        ).filterValues { it != null }

    private fun materializedStepSummariesFrom(
        functionId: String,
        arguments: Map<String, Any?>,
        startIndex: Int,
    ): List<Map<String, Any?>> {
        val spec = functionRepository.get(functionId) ?: return emptyList()
        val materialized = runCatching { OobReusableFunctionStore.materialize(spec, arguments) }
            .getOrElse { return emptyList() }
        return materializedSteps(materialized)
            .drop(startIndex.coerceAtLeast(0))
            .mapIndexed { offset, step ->
                val index = startIndex + offset
                linkedMapOf<String, Any?>(
                    "index" to index,
                    "step_id" to firstNonBlank(step["id"], step["step_id"], step["stepId"])
                        .ifBlank { "step_${index + 1}" },
                    "title" to firstNonBlank(step["title"]),
                    "tool" to firstNonBlank(step["tool"], step["callable_tool"], step["omniflow_action"]),
                    "executor" to firstNonBlank(step["executor"]),
                    "action" to firstNonBlank(step["omniflow_action"], step["local_action"], step["action"]),
                    "args" to mapArg(step["args"]).takeIf { it.isNotEmpty() },
                ).filterValues { it != null }
            }
    }

    private fun buildAgentFallbackPrompt(
        functionId: String,
        arguments: Map<String, Any?>,
        failedStep: Map<String, Any?>,
        remainingSteps: List<Map<String, Any?>>,
        resumeFromStep: Int,
        sessionId: String,
        nextAttempt: Int,
        attemptLimitReached: Boolean,
    ): String {
        val title = firstNonBlank(failedStep["title"], failedStep["step_id"], "step_${resumeFromStep + 1}")
        val tool = firstNonBlank(failedStep["tool"])
        val summary = firstNonBlank(failedStep["summary"], failedStep["prompt"])
        val recovery = mapArg(failedStep["recovery"])
        val currentPackage = firstNonBlank(recovery["current_package"], recovery["package_name"])
        val currentActivity = firstNonBlank(recovery["current_activity"], recovery["activity_name"])
        val currentXml = firstNonBlank(recovery["current_xml"], recovery["xml"], recovery["page"])
            .take(4000)
        val remainingText = remainingSteps.take(8).joinToString("\n") { step ->
            "- #${step["index"]}: ${firstNonBlank(step["title"], step["tool"])}"
        }.ifBlank { "- 无可用步骤摘要" }
        val argumentsText = if (arguments.isNotEmpty()) {
            "\n  \"arguments\": $arguments,"
        } else {
            ""
        }
        val retryText = if (attemptLimitReached) {
            "\n注意：同一步已经达到 fallback 尝试上限，不要继续循环调用。"
        } else {
            ""
        }
        return """
            oob_function_run 本地重放失败，需要你接管当前步骤。
            function_id: $functionId
            fallback_session_id: $sessionId
            failed_step_index: $resumeFromStep
            failed_step: $title
            tool: $tool
            reason: $summary
            fallback_attempt: $nextAttempt
            当前包名: $currentPackage
            当前 Activity: $currentActivity
            当前页面 XML（截断）:
            $currentXml

            你需要先在当前页面完成 failed_step 对应的真实操作。完成后调用：
            oob_function_run({
              "function_id": "$functionId",$argumentsText
              "resume_from_step": $resumeFromStep,
              "fallback_session_id": "$sessionId",
              "fallback_attempt": $nextAttempt
            })

            后续本地步骤：
            $remainingText$retryText
        """.trimIndent()
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> =
        OobFunctionSchemaBuilder.materializedSteps(spec)

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = "",
        decision: String? = null,
        riskLevel: String? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId
    ).apply {
        decision?.let { put("decision", it) }
        riskLevel?.let { put("risk_level", it) }
    }

    private companion object {
        private const val MAX_FUNCTION_FALLBACK_ATTEMPTS_PER_STEP = 2
        private const val DECISION_ALLOW = "allow"
        private const val DECISION_AGENT_REQUIRED = "agent_required"
        private const val DECISION_NEEDS_CONFIRMATION = "needs_confirmation"
        private const val DECISION_BLOCK = "block"
        private const val RISK_LOW = "low"
        private const val RISK_MEDIUM = "medium"
        private const val RISK_HIGH = "high"
        private val CONFIRMATION_ACTION_TOKENS = setOf(
            "shell",
            "terminal",
            "settings_write",
            "permission_grant",
            "permission_revoke",
            "package.install",
            "install_apk",
            "force_stop",
            "shizuku",
            "root",
        )
        private val BLOCKED_ACTION_TOKENS = setOf(
            "reboot",
            "shutdown",
            "fastboot",
            "format",
            "block_device",
            "system_partition",
            "dd if=",
            "mkfs",
        )
        private val GUARD_SCAN_KEYS = setOf(
            "title",
            "kind",
            "executor",
            "tool",
            "callable_tool",
            "omniflow_action",
            "local_action",
            "type",
            "args",
        )
        private val GUARD_CONTEXT_KEYS = setOf(
            "source_context",
            "src_ctx",
            "page",
            "xml",
            "observation_xml",
            "screenshot",
            "image",
        )
    }
}
