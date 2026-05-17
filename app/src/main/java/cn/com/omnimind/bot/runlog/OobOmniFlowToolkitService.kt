package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * OOB-native implementation of the public OmniFlow agent toolkit surface.
 *
 * The service deliberately keeps the first version fixed and local: Functions
 * are registered in OOB stores, recall is deterministic, and execution runs
 * through the existing OOB replay dispatcher. External OmniFlow can replace this
 * class later behind the same `recall -> call_function -> ingest_run_log`
 * contract.
 */
class OobOmniFlowToolkitService(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    )
) {
    private val replayService = OobRunLogReplayService(context, workspaceFunctionStore)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun recall(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val goal = firstNonBlank(request["goal"], request["query"], request["task"])
        val currentPackage = firstNonBlank(request["current_package"], request["currentPackage"])
        val currentNodeId = firstNonBlank(request["current_node_id"], request["currentNodeId"])
        val k = intArg(request["k"], defaultValue = 8).coerceIn(1, 50)
        val specs = replayService.listFunctionSpecs(limit = 500)
        val ranked = specs.mapNotNull { spec ->
            rankFunction(
                spec = spec,
                goal = goal,
                currentPackage = currentPackage,
            )
        }.sortedWith(
            compareByDescending<RankedFunction> { it.score }
                .thenBy { it.functionId }
        ).take(k)

        val candidates = ranked.map { rankedFunction ->
            candidateMap(rankedFunction.spec, rankedFunction.score, rankedFunction.reason)
        }
        val directHit = ranked.firstOrNull { it.score >= DIRECT_HIT_SCORE && isNoArgumentFunction(it.spec) }
        val decision = when {
            directHit != null -> "hit"
            candidates.isNotEmpty() -> "recall"
            else -> "miss"
        }

        return linkedMapOf<String, Any?>(
            "success" to true,
            "decision" to decision,
            "hit" to directHit?.let {
                linkedMapOf(
                    "function_id" to it.functionId,
                    "inputSchema" to inputSchema(it.spec),
                    "score" to it.score,
                    "reason" to it.reason
                )
            },
            "candidates" to if (directHit == null) candidates else emptyList<Map<String, Any?>>(),
            "count" to candidates.size,
            "reason" to if (candidates.isEmpty()) "no_registered_function_match" else "oob_fixed_recall",
            "current_package" to currentPackage.takeIf { it.isNotEmpty() },
            "current_node_id" to currentNodeId.takeIf { it.isNotEmpty() },
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    suspend fun callFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val goal = firstNonBlank(request["goal"])
        val callArguments = mapArg(request["arguments"])
        if (functionId.isEmpty()) {
            return canonicalCallError(
                functionId = functionId,
                error = "call_function requires function_id",
                fallbackReason = "invalid_request"
            )
        }

        val guard = guardCheck(
            linkedMapOf(
                "functionId" to functionId,
                "arguments" to callArguments
            )
        )
        val decision = guard["decision"]?.toString().orEmpty()
        if (decision == "block") {
            return canonicalCallError(
                functionId = functionId,
                error = guard["reason"]?.toString() ?: "Function blocked by guard",
                fallbackReason = "guard_blocked",
                guard = guard
            )
        }
        if (decision == "needs_confirmation") {
            return canonicalCallError(
                functionId = functionId,
                error = guard["reason"]?.toString() ?: "Function requires confirmation",
                fallbackReason = "confirmation_required",
                guard = guard
            )
        }

        val runPayload = executeFunction(
            functionId = functionId,
            arguments = callArguments,
            allowAgentFallback = true
        )
        val success = runPayload["success"] == true && runPayload["model_required"] != true
        val fallback = !success || decision == "needs_agent"
        val fallbackReason = when {
            runPayload["error_code"] != null -> runPayload["error_code"]?.toString()
            decision == "needs_agent" -> "agent_fallback_required"
            fallback -> runPayload["error_message"]?.toString()?.ifBlank { "execution_failed" }
            else -> ""
        }.orEmpty()

        return linkedMapOf<String, Any?>(
            "success" to success,
            "fallback" to fallback,
            "error" to if (success) null else runPayload["error_message"],
            "run_id" to "omniflow_run_${System.currentTimeMillis()}",
            "function_id" to functionId,
            "goal" to goal.takeIf { it.isNotEmpty() },
            "actions_executed" to (runPayload["success_step_count"] ?: 0),
            "step_results" to runPayload["step_results"],
            "timing" to runPayload["timing"],
            "control" to linkedMapOf(
                "postcondition" to if (success) "passed" else "not_verified",
                "fallback_reason" to fallbackReason,
                "guard_decision" to decision,
                "runner" to runPayload["runner"]
            ),
            "oob_result" to runPayload,
            "guard" to guard,
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    fun ingestRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["run_id"], request["runId"])
        val rawRunLog = mapArg(request["run_log"]).ifEmpty { mapArg(request["runLog"]) }
        val result = if (runId.isNotEmpty()) {
            replayService.convertRunLog(runId = runId, register = true)
        } else if (rawRunLog.isNotEmpty()) {
            ingestInlineRunLog(rawRunLog)
        } else {
            linkedMapOf(
                "success" to false,
                "error_code" to "RUN_LOG_EMPTY",
                "error_message" to "ingest_run_log requires run_id or run_log"
            )
        }
        val success = result["success"] == true
        return linkedMapOf<String, Any?>(
            "accepted" to success,
            "success" to success,
            "function_id" to result["function_id"],
            "created_function_id" to result["created_function_id"],
            "status" to when {
                !success -> "rejected"
                result["already_exists"] == true -> "updated"
                else -> "created"
            },
            "reason" to (result["error_message"] ?: ""),
            "result" to result,
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> =
        replayService.listFunctions(limit = intArg(args?.get("limit"), defaultValue = 100))

    fun getFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("functionId"), args?.get("function_id"))
        val spec = replayService.getFunctionSpec(functionId)
        return spec ?: errorPayload(
            code = "OOB_FUNCTION_NOT_FOUND",
            message = "OOB reusable function not found: $functionId",
            functionId = functionId
        )
    }

    fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionSpec = mapArg(request["functionSpec"])
            .ifEmpty { mapArg(request["function_spec"]) }
            .ifEmpty { if (request.containsKey("function_id")) request else emptyMap() }
        if (functionSpec.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_SPEC_EMPTY",
                message = "functionSpec is required"
            )
        }
        return replayService.registerFunctionSpec(functionSpec)
    }

    fun guardCheck(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val spec = replayService.getFunctionSpec(functionId)
            ?: return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId,
                decision = "block",
                riskLevel = "high"
            )
        val arguments = mapArg(request["arguments"])
        val missing = OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
        if (missing.isNotEmpty()) {
            return errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId,
                decision = "block",
                riskLevel = "high"
            ) + linkedMapOf("missing_required_arguments" to missing)
        }

        val materialized = OobReusableFunctionStore.materialize(spec, arguments)
        val stepDecisions = materializedSteps(materialized).map(::guardStep)
        val decision = aggregateDecision(stepDecisions)
        val riskLevel = aggregateRisk(stepDecisions)
        val reason = when (decision) {
            "allow" -> "All steps are deterministic local replay or registered tool calls."
            "needs_agent" -> "At least one step needs live Agent planning."
            "needs_confirmation" -> "At least one step can affect device state outside local UI replay."
            "block" -> "At least one step is blocked by OOB safety policy."
            else -> "Guard decision unavailable."
        }
        return linkedMapOf<String, Any?>(
            "success" to true,
            "function_id" to functionId,
            "decision" to decision,
            "risk_level" to riskLevel,
            "reason" to reason,
            "requires_confirmation" to (decision == "needs_confirmation"),
            "requires_root" to stepDecisions.any { it["requires_root"] == true },
            "step_decisions" to stepDecisions,
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val arguments = mapArg(request["arguments"])
        val dryRun = boolArg(request["dryRun"]) || boolArg(request["dry_run"])
        val continueWithAgent = boolArg(request["continueWithAgent"]) ||
            boolArg(request["continue_with_agent"])
        val confirmed = boolArg(request["confirmed"]) || boolArg(request["userConfirmed"])
        val executionMode = firstNonBlank(request["executionMode"], request["execution_mode"])
            .ifBlank { "foreground" }

        val guard = guardCheck(
            linkedMapOf("functionId" to functionId, "arguments" to arguments)
        )
        val decision = guard["decision"]?.toString().orEmpty()
        if (dryRun) {
            return guard + linkedMapOf(
                "dry_run" to true,
                "execution_mode" to executionMode,
                "run_skipped" to true
            )
        }
        if (decision == "block") {
            return guard + linkedMapOf(
                "success" to false,
                "guard_decision" to decision,
                "run_skipped" to true
            )
        }
        if (decision == "needs_confirmation" && !confirmed) {
            return guard + linkedMapOf(
                "success" to false,
                "guard_decision" to decision,
                "needs_confirmation" to true,
                "run_skipped" to true
            )
        }

        val runPayload = executeFunction(
            functionId = functionId,
            arguments = arguments,
            allowAgentFallback = continueWithAgent || decision == "needs_agent"
        )
        return linkedMapOf<String, Any?>(
            "success" to (runPayload["success"] == true),
            "function_id" to functionId,
            "runner" to runPayload["runner"],
            "guard_decision" to decision,
            "risk_level" to guard["risk_level"],
            "execution_mode" to executionMode,
            "step_results" to runPayload["step_results"],
            "timing" to runPayload["timing"],
            "needs_agent" to (runPayload["model_required"] == true),
            "needs_confirmation" to false,
            "error_message" to runPayload["error_message"],
            "audit_run_id" to "omniflow_run_${System.currentTimeMillis()}",
            "guard" to guard,
            "result" to runPayload
        )
    }

    fun listRunLogs(args: Map<String, Any?>?): Map<String, Any?> {
        val limit = intArg(args?.get("limit"), defaultValue = 50).coerceIn(1, 200)
        return InternalRunLogStore.listRuns(context, limit = limit)
    }

    fun getRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val runId = firstNonBlank(args?.get("runId"), args?.get("run_id"))
        if (runId.isEmpty()) {
            return errorPayload(code = "RUN_LOG_ID_EMPTY", message = "runId is required")
        }
        return InternalRunLogStore.timelinePayload(context, runId)
    }

    fun convertRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["runId"], request["run_id"])
        return replayService.convertRunLog(
            runId = runId,
            register = boolArg(request["register"]),
            functionIdOverride = firstNonBlank(request["functionId"], request["function_id"])
                .takeIf { it.isNotEmpty() },
            nameOverride = firstNonBlank(request["name"]).takeIf { it.isNotEmpty() },
            descriptionOverride = firstNonBlank(request["description"]).takeIf { it.isNotEmpty() }
        )
    }

    private fun ingestInlineRunLog(runLog: Map<String, Any?>): Map<String, Any?> {
        val runId = firstNonBlank(runLog["run_id"], runLog["runId"])
            .ifBlank { "inline_${System.currentTimeMillis()}" }
        val resultMap = mapArg(runLog["result"])
        val success = boolArg(runLog["success"]) || boolArg(resultMap["success"])
        val cards = listArg(runLog["cards"]).ifEmpty {
            listArg(runLog["steps"])
        }.mapNotNull { it as? Map<*, *> }.map(::stringMap)
        val record = InternalRunLogRecord(
            runId = runId,
            goal = firstNonBlank(runLog["goal"], runLog["task"]),
            source = firstNonBlank(runLog["source"]).ifBlank { "external_agent" },
            toolName = firstNonBlank(runLog["tool_name"], runLog["toolName"]),
            operationDescription = firstNonBlank(
                runLog["operation_description"],
                runLog["operationDescription"],
                runLog["goal"],
            ),
            startedAtMs = longArg(runLog["started_at_ms"], System.currentTimeMillis()),
            finishedAtMs = longArg(runLog["finished_at_ms"], System.currentTimeMillis()),
            success = success,
            doneReason = firstNonBlank(resultMap["done_reason"], runLog["done_reason"]),
            errorMessage = firstNonBlank(resultMap["error"], runLog["error_message"]),
            cards = cards,
        )
        if (record.success != true) {
            return errorPayload(
                code = "RUN_LOG_NOT_SUCCESSFUL",
                message = "Only successful RunLogs can be ingested",
                functionId = ""
            )
        }
        val spec = RunLogReusableFunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps"
            )
        workspaceFunctionStore.mirrorRunLog(record)
        return replayService.registerFunctionSpec(spec) + linkedMapOf("run_id" to record.runId)
    }

    private suspend fun executeFunction(
        functionId: String,
        arguments: Map<String, Any?>,
        allowAgentFallback: Boolean,
    ): Map<String, Any?> = withContext(Dispatchers.Default) {
        val spec = replayService.getFunctionSpec(functionId)
            ?: return@withContext errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId
            )
        val missing = OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
        if (missing.isNotEmpty()) {
            return@withContext errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId
            ) + linkedMapOf("missing_required_arguments" to missing)
        }
        val materialized = OobReusableFunctionStore.materialize(spec, arguments)
        val runner = OobFunctionToolHandler(
            context = context,
            helper = SharedHelper(context, json)
        ).apply {
            this.workspaceFunctionStore = workspaceFunctionStore
        }
        runCatching {
            runner.runMaterializedFunction(
                functionId = functionId,
                spec = spec,
                materializedSpec = materialized,
                allowAgentFallback = allowAgentFallback,
                allowToolDelegationWithoutRouter = false
            )
        }.getOrElse { error ->
            errorPayload(
                code = "OOB_FUNCTION_RUN_FAILED",
                message = error.message.orEmpty(),
                functionId = functionId
            )
        }
    }

    private fun rankFunction(
        spec: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): RankedFunction? {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        if (functionId.isEmpty()) return null
        val name = spec["name"]?.toString()?.trim().orEmpty()
        val description = spec["description"]?.toString()?.trim().orEmpty()
        val source = mapArg(spec["source"])
        val corpus = listOf(
            functionId,
            name,
            description,
            source["goal"]?.toString().orEmpty(),
            source["tool_name"]?.toString().orEmpty(),
        ).joinToString(" ").trim()
        if (goal.isBlank()) {
            return RankedFunction(spec, functionId, 0.25, "empty_goal_recent_registered")
        }

        val normalizedGoal = normalizeText(goal)
        val normalizedId = normalizeText(functionId)
        val normalizedName = normalizeText(name)
        val normalizedDescription = normalizeText(description)
        var score = when {
            normalizedGoal == normalizedId -> 1.0
            normalizedGoal == normalizedName && normalizedName.isNotEmpty() -> 0.99
            normalizedGoal == normalizedDescription && normalizedDescription.isNotEmpty() -> 0.96
            normalizedId.contains(normalizedGoal) || normalizedGoal.contains(normalizedId) -> 0.92
            normalizedName.contains(normalizedGoal) || normalizedGoal.contains(normalizedName) -> 0.90
            else -> tokenOverlapScore(goal, corpus)
        }
        var reason = when {
            score >= 0.97 -> "exact_match"
            score >= 0.85 -> "text_match"
            else -> "token_overlap"
        }
        if (currentPackage.isNotEmpty() && packageScopeMatches(spec, currentPackage)) {
            score = (score + 0.05).coerceAtMost(1.0)
            reason = "${reason}_package_scope"
        }
        if (score < MIN_RECALL_SCORE) return null
        return RankedFunction(spec, functionId, roundScore(score), reason)
    }

    private fun tokenOverlapScore(goal: String, corpus: String): Double {
        val goalTokens = tokenize(goal)
        if (goalTokens.isEmpty()) return 0.0
        val corpusTokens = tokenize(corpus).toSet()
        val overlap = goalTokens.count { it in corpusTokens }
        return if (overlap == 0) 0.0 else 0.30 + 0.55 * (overlap.toDouble() / goalTokens.size)
    }

    private fun packageScopeMatches(spec: Map<String, Any?>, currentPackage: String): Boolean {
        val constraints = mapArg(spec["constraints"])
        val source = mapArg(spec["source"])
        val candidates = listOf(
            constraints["package_name"],
            constraints["packageName"],
            source["package_name"],
            source["packageName"],
        ).map { it?.toString()?.trim().orEmpty() }
        return candidates.any { it.isNotEmpty() && it == currentPackage }
    }

    private fun candidateMap(
        spec: Map<String, Any?>,
        score: Double,
        reason: String,
    ): Map<String, Any?> {
        val execution = mapArg(spec["execution"])
        return linkedMapOf(
            "function_id" to spec["function_id"],
            "description" to (spec["description"] ?: spec["name"] ?: spec["function_id"]),
            "name" to spec["name"],
            "inputSchema" to inputSchema(spec),
            "score" to score,
            "reason" to reason,
            "step_count" to (execution["step_count"] ?: listArg(execution["steps"]).size),
            "requires_agent_fallback" to execution["requires_agent_fallback"],
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local"
        )
    }

    private fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(spec["inputSchema"]).ifEmpty { mapArg(spec["input_schema"]) }
        if (explicit.isNotEmpty()) return explicit
        val parameters = listArg(spec["parameters"])
        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        parameters.forEach { raw ->
            val parameter = (raw as? Map<*, *>)?.let(::stringMap) ?: return@forEach
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val type = parameter["type"]?.toString()?.trim()?.ifEmpty { "string" } ?: "string"
            val property = linkedMapOf<String, Any?>("type" to jsonSchemaType(type))
            parameter["description"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
                property["description"] = it
            }
            properties[name] = property
            if (boolArg(parameter["required"])) required += name
        }
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }

    private fun isNoArgumentFunction(spec: Map<String, Any?>): Boolean {
        val schema = inputSchema(spec)
        val required = listArg(schema["required"])
        val properties = mapArg(schema["properties"])
        return required.isEmpty() && properties.isEmpty()
    }

    private fun guardStep(step: Map<String, Any?>): Map<String, Any?> {
        val stepId = firstNonBlank(step["id"], step["step_id"])
        val tool = firstNonBlank(step["tool"], step["omniflow_action"], step["callable_tool"], step["type"])
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(tool)
        val action = RunLogReplayPolicy.omniflowActionForToolName(normalizedTool) ?: normalizedTool
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val decision: String
        val risk: String
        val reason: String
        val requiresRoot: Boolean
        when {
            isBlockedAction(action, step) -> {
                decision = "block"
                risk = "high"
                reason = "$action is blocked by OOB policy"
                requiresRoot = true
            }
            isConfirmationAction(action, step) -> {
                decision = "needs_confirmation"
                risk = "high"
                reason = "$action requires user confirmation"
                requiresRoot = action.contains("root") || action.contains("shizuku")
            }
            OmniflowStepExecutor.isOmniflowStep(step) -> {
                decision = "allow"
                risk = "low"
                reason = "$action is a deterministic local replay action"
                requiresRoot = false
            }
            executor == "agent" || RunLogReplayPolicy.isAgentTool(action) -> {
                decision = "needs_agent"
                risk = "medium"
                reason = "$action requires live Agent planning"
                requiresRoot = false
            }
            else -> {
                decision = "needs_agent"
                risk = "medium"
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
            decisions.contains("block") -> "block"
            decisions.contains("needs_confirmation") -> "needs_confirmation"
            decisions.contains("needs_agent") -> "needs_agent"
            else -> "allow"
        }
    }

    private fun aggregateRisk(stepDecisions: List<Map<String, Any?>>): String {
        val risks = stepDecisions.map { it["risk_level"]?.toString().orEmpty() }
        return when {
            risks.contains("high") -> "high"
            risks.contains("medium") -> "medium"
            else -> "low"
        }
    }

    private fun isBlockedAction(action: String, step: Map<String, Any?>): Boolean {
        val text = step.toString().lowercase()
        return BLOCKED_ACTION_TOKENS.any { action.contains(it) || text.contains(it) }
    }

    private fun isConfirmationAction(action: String, step: Map<String, Any?>): Boolean {
        val text = step.toString().lowercase()
        return CONFIRMATION_ACTION_TOKENS.any { action.contains(it) || text.contains(it) }
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val rawSteps = listArg(mapArg(spec["execution"])["steps"])
        return rawSteps.mapNotNull { rawStep ->
            (rawStep as? Map<*, *>)?.let(::stringMap)
        }
    }

    private fun canonicalCallError(
        functionId: String,
        error: String,
        fallbackReason: String,
        guard: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "fallback" to true,
        "error" to error,
        "run_id" to null,
        "function_id" to functionId,
        "actions_executed" to 0,
        "control" to linkedMapOf(
            "postcondition" to "not_started",
            "fallback_reason" to fallbackReason
        ),
        "guard" to guard,
        "source" to "oob_native_omniflow_toolkit"
    )

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

    private fun normalizeText(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun tokenize(value: String): List<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(value.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toList()

    private fun roundScore(value: Double): Double =
        ((value.coerceIn(0.0, 1.0) * 1000.0).roundToInt() / 1000.0)

    private fun jsonSchemaType(type: String): String =
        when (type.lowercase()) {
            "int", "integer" -> "integer"
            "number", "float", "double" -> "number"
            "bool", "boolean" -> "boolean"
            "array", "object" -> type.lowercase()
            else -> "string"
        }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun mapArg(value: Any?): Map<String, Any?> {
        return when (value) {
            is Map<*, *> -> stringMap(value)
            else -> emptyMap()
        }
    }

    private fun stringMap(value: Map<*, *>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) put(key.toString(), item)
            }
        }

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }

    private fun intArg(value: Any?, defaultValue: Int): Int =
        when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: defaultValue
            else -> defaultValue
        }

    private fun longArg(value: Any?, defaultValue: Long): Long =
        when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: defaultValue
            else -> defaultValue
        }

    private fun boolArg(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true) ||
                value.trim() == "1"
            is Number -> value.toInt() != 0
            else -> false
        }

    private data class RankedFunction(
        val spec: Map<String, Any?>,
        val functionId: String,
        val score: Double,
        val reason: String,
    )

    companion object {
        private const val MIN_RECALL_SCORE = 0.30
        private const val DIRECT_HIT_SCORE = 0.97
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
    }
}
