package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.omniflow.OobFunctionRunPolicy
import cn.com.omnimind.bot.omniflow.OobFunctionSpecBuilder
import cn.com.omnimind.bot.omniflow.OobFunctionUpdateService
import cn.com.omnimind.bot.omniflow.OobFunctionRunner
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import kotlin.math.roundToInt

/**
 * OOB-native implementation of the public OmniFlow agent toolkit surface.
 *
 * The service deliberately keeps the first version fixed and local: Functions
 * are registered in OOB stores, recall is deterministic, and execution runs
 * through the existing OOB replay dispatcher. External OmniFlow can replace this
 * class later behind the same `recall -> call_tool(function_id) -> ingest_run_log`
 * contract.
 */
class OobOmniFlowToolkitService(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    )
) {
    private val replayService = OobRunLogReplayService(context, workspaceFunctionStore)
    private val functionRepository = OobFunctionRepository(context, workspaceFunctionStore)
    private val functionRunner = OobFunctionRunner(context, workspaceFunctionStore, functionRepository)
    private val functionRunPolicy = OobFunctionRunPolicy(functionRepository)
    private val functionSpecBuilder = OobFunctionSpecBuilder()
    private val functionUpdateService = OobFunctionUpdateService(context, functionRepository, functionSpecBuilder)
    private val explorer = OobOmniFlowExplorer(context)

    fun recall(args: Map<String, Any?>?): Map<String, Any?> {
        val timing = RecallTiming()
        val request = timing.measure("parse_request_ms") { args ?: emptyMap() }
        val goal = firstNonBlank(request["goal"], request["query"], request["task"])
        val includeDebug = boolArg(request["include_debug"]) ||
            boolArg(request["includeDebug"]) ||
            boolArg(request["debug"])
        val currentPackage = timing.measure("read_current_package_ms") {
            firstNonBlank(
                request["current_package"],
                request["currentPackage"],
                runCatching { OmniflowActionRuntime.backend.currentPackageName() }.getOrNull(),
            )
        }
        val currentNodeId = firstNonBlank(request["current_node_id"], request["currentNodeId"])
        val k = intArg(request["k"], defaultValue = 8).coerceIn(1, 50)
        val allowDirectExecutionDecision = boolArg(request["auto_execute"]) ||
            boolArg(request["autoExecute"]) ||
            boolArg(request["allow_direct_hit"]) ||
            boolArg(request["allowDirectHit"]) ||
            firstNonBlank(
                request["decision_mode"],
                request["decisionMode"],
                request["execution_policy"],
                request["executionPolicy"],
            ).lowercase() in setOf("direct", "auto_execute", "auto-execute")
        val currentXml = timing.measure("read_current_page_ms") {
            firstNonBlank(
                request["current_xml"],
                request["currentXml"],
                request["xml"],
                request["page"],
                request["observation_xml"],
                request["observationXml"],
            ).ifBlank {
                runCatching { OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty() }
                    .getOrDefault("")
            }
        }
        if (currentXml.isBlank()) {
            val payload = linkedMapOf<String, Any?>(
                "success" to true,
                "decision" to "miss",
                "decision_path" to OobUdegNodeStore.UDEG_DECISION_PATH,
                "hit" to null,
                "candidates" to emptyList<Map<String, Any?>>(),
                "node_candidates" to emptyList<Map<String, Any?>>(),
                "count" to 0,
                "reason" to "missing_current_page_for_udeg_page_match",
                "current_package" to currentPackage.takeIf { it.isNotEmpty() },
                "current_node_id" to currentNodeId.takeIf { it.isNotEmpty() },
                "timing" to timing.finish(
                    decision = "miss",
                    counts = linkedMapOf(
                        "node_candidates" to 0,
                        "function_candidates" to 0,
                    )
                ),
                "source" to "oob_native_udeg_page_match",
            )
            return compactRecallPayload(payload, includeDebug)
        }

        val nodeStore = OobUdegNodeStore(context)
        val nodeMatches = timing.measure("page_match_ms") {
            nodeStore.recall(
                currentXml = currentXml,
                currentPackage = currentPackage,
                topK = k,
            )
        }
        val nodeCandidates = nodeMatches.map { it.toMap() }
        val decisionNodeMatches = nodeMatches.take(1)
        val nodeCapabilityRanking = timing.measure("rank_functions_ms") {
            rankNodeCapabilities(
                nodeMatches = decisionNodeMatches,
                goal = goal,
                currentPackage = currentPackage,
                topK = k,
            )
        }
        val ranked = nodeCapabilityRanking.functions

        val candidates = ranked.map { rankedFunction ->
            candidateMap(
                spec = rankedFunction.spec,
                score = rankedFunction.score,
                reason = rankedFunction.reason,
                extras = linkedMapOf(
                    "text_score" to roundScore(rankedFunction.textScore),
                    "page_similarity" to roundScore(rankedFunction.pageScore),
                    "udeg_node" to rankedFunction.node,
                    "node_skill_context" to rankedFunction.node["node_skill_context"],
                    "recall_scope" to "udeg_node",
                )
            )
        }
        val directHit = ranked.takeIf { it.size == 1 }?.firstOrNull {
            it.score >= DIRECT_HIT_SCORE &&
                it.pageScore >= DIRECT_HIT_SCORE &&
                it.textScore >= DIRECT_HIT_SCORE &&
                isNoArgumentFunction(it.spec)
        }.takeIf { allowDirectExecutionDecision }
        val decision = when {
            directHit != null -> "hit"
            nodeCandidates.isNotEmpty() -> "recall"
            else -> "miss"
        }

        val payload = linkedMapOf<String, Any?>(
            "success" to true,
            "decision" to decision,
            "decision_path" to OobUdegNodeStore.UDEG_DECISION_PATH,
            "hit" to directHit?.let {
                linkedMapOf(
                    "function_id" to it.functionId,
                    "inputSchema" to inputSchema(it.spec),
                    "score" to it.score,
                    "reason" to it.reason,
                    "text_score" to roundScore(it.textScore),
                    "page_similarity" to roundScore(it.pageScore),
                    "strict_direct_hit" to true,
                    "udeg_node" to it.node,
                    "node_skill_context" to it.node["node_skill_context"],
                    "step_summaries" to stepSummaries(it.spec)
                )
            },
            "candidates" to if (directHit == null) candidates else emptyList<Map<String, Any?>>(),
            "capability_candidates" to nodeCapabilityRanking.capabilities,
            "node_capabilities" to nodeCapabilityRanking.capabilities,
            "node_function_capabilities" to nodeCapabilityRanking.functionCapabilities,
            "node_candidates" to nodeCandidates,
            "current_node" to nodeCandidates.firstOrNull(),
            "node_skill" to (nodeCandidates.firstOrNull()?.get("skill")),
            "node_skill_context" to (nodeCandidates.firstOrNull()?.get("node_skill_context")),
            "decision_context" to (nodeCandidates.firstOrNull()?.get("decision_context")),
            "decision_policy" to linkedMapOf(
                "mode" to if (allowDirectExecutionDecision) "direct_execution_allowed" else "node_skill_context_only",
                "requires_vlm_or_tool_decision" to !allowDirectExecutionDecision,
                "direct_hit_requested" to allowDirectExecutionDecision,
                "direct_hit_min_score" to DIRECT_HIT_SCORE,
                "direct_hit_requires_single_candidate" to true,
                "direct_hit_requires_no_arguments" to true,
            ),
            "count" to candidates.size,
            "reason" to when {
                directHit != null -> "udeg_page_match_direct_function_hit"
                nodeCandidates.isEmpty() -> "no_udeg_node_page_match"
                nodeCapabilityRanking.capabilities.isEmpty() -> "udeg_node_match_without_attached_capability"
                candidates.isEmpty() -> "udeg_node_match_without_attached_function"
                else -> "udeg_node_skill_context_recall"
            },
            "current_package" to currentPackage.takeIf { it.isNotEmpty() },
            "current_node_id" to currentNodeId.takeIf { it.isNotEmpty() },
            "timing" to timing.finish(
                decision = decision,
                counts = linkedMapOf(
                    "node_candidates" to nodeCandidates.size,
                    "decision_node_candidates" to decisionNodeMatches.size,
                    "function_candidates" to ranked.size,
                    "node_capabilities" to nodeCapabilityRanking.capabilities.size,
                    "node_function_capabilities" to nodeCapabilityRanking.functionCapabilities.size,
                )
            ),
            "source" to "oob_native_udeg_page_match"
        )
        return compactRecallPayload(payload, includeDebug)
    }

    suspend fun callFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = FunctionCallTiming()
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

        val guard = callTiming.measure("guard_check_ms") {
            guardCheck(
                linkedMapOf(
                    "functionId" to functionId,
                    "arguments" to callArguments,
                )
            )
        }
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

        var runPayload = callTiming.measureSuspend("execute_function_ms") {
            functionRunner.execute(
                functionId = functionId,
                arguments = callArguments,
                allowAgentFallback = false
            )
        }
        runPayload = attachFunctionCallTiming(runPayload, callTiming)
        val success = runPayload["success"] == true && runPayload["model_required"] != true
        OobReusableFunctionStore.recordRun(
            context = context,
            functionId = functionId,
            success = success,
            runId = runPayload["run_id"]?.toString(),
            runner = runPayload["runner"]?.toString(),
            stepCount = intArg(runPayload["step_count"], defaultValue = 0),
            errorMessage = runPayload["error_message"]?.toString()
        )
        val fallback = false
        val fallbackReason = when {
            runPayload["error_code"] != null -> runPayload["error_code"]?.toString()
            decision == "needs_agent" -> "agent_fallback_required"
            !success -> runPayload["error_message"]?.toString()?.ifBlank { "execution_failed" }
            else -> ""
        }.orEmpty()

        return linkedMapOf<String, Any?>(
            "success" to success,
            "fallback" to fallback,
            "error" to if (success) null else runPayload["error_message"],
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "goal" to goal.takeIf { it.isNotEmpty() },
            "actions_executed" to (runPayload["success_step_count"] ?: 0),
            "step_results" to runPayload["step_results"],
            "timing" to runPayload["timing"],
            "control" to linkedMapOf(
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

    suspend fun explore(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val register = boolArg(request["register"])
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val name = firstNonBlank(request["name"])
        val description = firstNonBlank(request["description"])
        val exploreResult = explorer.explore(request)
        val success = exploreResult["success"] == true
        if (!success) {
            return linkedMapOf(
                "success" to false,
                "phase" to "explore",
                "explore" to exploreResult,
                "error_code" to exploreResult["error_code"],
                "error_message" to exploreResult["error_message"],
                "source" to "oob_native_omniflow_toolkit"
            )
        }
        if (!register) {
            return linkedMapOf(
                "success" to true,
                "phase" to "explore",
                "run_id" to exploreResult["run_id"],
                "utg" to exploreResult["utg"],
                "explore" to exploreResult,
                "registered" to false,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val convertResult = replayService.convertRunLog(
            runId = exploreResult["run_id"]?.toString().orEmpty(),
            register = true,
            functionIdOverride = functionId.takeIf { it.isNotEmpty() },
            nameOverride = name.takeIf { it.isNotEmpty() },
            descriptionOverride = description.takeIf { it.isNotEmpty() },
        )
        val converted = convertResult["success"] == true
        return linkedMapOf(
            "success" to converted,
            "phase" to if (converted) "registered" else "convert",
            "run_id" to exploreResult["run_id"],
            "function_id" to convertResult["function_id"],
            "created_function_id" to convertResult["created_function_id"],
            "registered" to converted,
            "utg" to exploreResult["utg"],
            "explore" to exploreResult,
            "convert" to convertResult,
            "error_code" to convertResult["error_code"],
            "error_message" to convertResult["error_message"],
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    suspend fun exploreAndReplay(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val exploreArgs = linkedMapOf<String, Any?>().apply {
            putAll(request)
            put("register", true)
        }
        val exploreResult = explore(exploreArgs)
        if (exploreResult["success"] != true) {
            return linkedMapOf(
                "success" to false,
                "phase" to (exploreResult["phase"] ?: "explore"),
                "explore" to exploreResult,
                "error_code" to exploreResult["error_code"],
                "error_message" to exploreResult["error_message"],
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val functionId = firstNonBlank(exploreResult["function_id"], exploreResult["created_function_id"])
        val packageName = firstNonBlank(
            request["package_name"],
            request["packageName"],
            request["target_package"],
            request["targetPackage"],
        )
        val shouldReplay = request["replay"] != false &&
            !request["replay"]?.toString().equals("false", ignoreCase = true)
        if (!shouldReplay) {
            return linkedMapOf(
                "success" to true,
                "phase" to "registered",
                "run_id" to exploreResult["run_id"],
                "function_id" to functionId,
                "utg" to exploreResult["utg"],
                "replay_skipped" to true,
                "explore" to exploreResult,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val settleDelayMs = longArg(
            request["settle_delay_ms"],
            request["settleDelayMs"],
            defaultValue = 800L
        ).coerceIn(100L, 5_000L)
        val resetBackSteps = intArg(
            request["reset_back_steps"],
            request["resetBackSteps"],
            defaultValue = 1
        ).coerceIn(0, 8)
        if (boolArg(request["reset_before_replay"]) || boolArg(request["resetBeforeReplay"])) {
            explorer.resetBeforeReplay(
                targetPackageName = packageName,
                backSteps = resetBackSteps,
                settleDelayMs = settleDelayMs,
            )
        }

        val replayResult = callFunction(
            linkedMapOf(
                "function_id" to functionId,
                "arguments" to mapArg(request["arguments"]),
                "goal" to firstNonBlank(request["goal"], request["query"], request["task"])
            )
        )
        val replaySuccess = replayResult["success"] == true
        return linkedMapOf(
            "success" to replaySuccess,
            "phase" to if (replaySuccess) "replayed" else "replay",
            "run_id" to exploreResult["run_id"],
            "function_id" to functionId,
            "explore" to exploreResult,
            "replay" to replayResult,
            "utg" to exploreResult["utg"],
            "error" to replayResult["error"],
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> =
        functionRepository.list(
            limit = intArg(args?.get("limit"), defaultValue = 100),
            offset = intArg(args?.get("offset"), defaultValue = 0)
        )

    fun getFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("functionId"), args?.get("function_id"))
        val spec = functionRepository.get(functionId)
        if (spec == null) {
            return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId
            )
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("success", true)
            put("function", spec)
            put("function_id", firstNonBlank(OobFunctionSchemaBuilder.functionId(spec), functionId))
            put("summary", functionAgentSummary(spec))
            put("response_source", "oob_native_function_store")
        }
    }

    fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("functionId"), args?.get("function_id"))
        return functionRepository.delete(functionId)
    }

    fun clearFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val confirmed = boolArg(request["confirm"]) ||
            boolArg(request["confirmed"]) ||
            firstNonBlank(request["action"]).equals("clear_all", ignoreCase = true)
        if (!confirmed) {
            return errorPayload(
                code = "OOB_FUNCTION_CLEAR_CONFIRMATION_REQUIRED",
                message = "Set confirm=true to clear all registered OOB Functions"
            )
        }
        return functionRepository.clear()
    }

    fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionSpec = functionSpecBuilder.functionSpecForRegistration(request)
        if (functionSpec.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_SPEC_EMPTY",
                message = "functionSpec or steps are required"
            )
        }
        val mode = if (functionSpecBuilder.hasExplicitFunctionSpec(request)) "function_spec" else "simple"
        return functionRepository.register(functionSpec) + linkedMapOf(
            "registration_input_mode" to mode,
            "simple_schema_supported" to true,
        )
    }

    fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> =
        functionUpdateService.updateFunction(args)

    fun guardCheck(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val arguments = mapArg(request["arguments"])
        return functionRunPolicy.guardCheck(functionId = functionId, arguments = arguments)
    }

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = FunctionCallTiming()
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val arguments = mapArg(request["arguments"])
        val dryRun = boolArg(request["dryRun"]) || boolArg(request["dry_run"])
        val confirmed = boolArg(request["confirmed"]) || boolArg(request["userConfirmed"])
        val resumeFromStep = intArg(
            request["resume_from_step"],
            request["resumeFromStep"],
            request["start_step_index"],
            request["startStepIndex"],
            defaultValue = 0
        ).coerceAtLeast(0)
        val fallbackSessionId = firstNonBlank(
            request["fallback_session_id"],
            request["fallbackSessionId"]
        )
        val fallbackAttempt = intArg(
            request["fallback_attempt"],
            request["fallbackAttempt"],
            defaultValue = 0
        ).coerceAtLeast(0)
        val executionMode = firstNonBlank(request["executionMode"], request["execution_mode"])
            .ifBlank { "foreground" }

        val guard = callTiming.measure("guard_check_ms") {
            guardCheck(
                linkedMapOf(
                    "functionId" to functionId,
                    "arguments" to arguments,
                )
            )
        }
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

        var runPayload = callTiming.measureSuspend("execute_function_ms") {
            functionRunner.execute(
                functionId = functionId,
                arguments = arguments,
                allowAgentFallback = true,
                resumeFromStep = resumeFromStep,
                fallbackSessionId = fallbackSessionId,
                fallbackAttempt = fallbackAttempt
            )
        }
        runPayload = attachFunctionCallTiming(runPayload, callTiming)
        val fallbackMetadata = functionRunPolicy.fallbackMetadata(
            functionId = functionId,
            arguments = arguments,
            runPayload = runPayload,
            guard = guard,
            requestedResumeFromStep = resumeFromStep,
            fallbackSessionId = fallbackSessionId,
            fallbackAttempt = fallbackAttempt,
        )
        OobReusableFunctionStore.recordRun(
            context = context,
            functionId = functionId,
            success = runPayload["success"] == true,
            runId = runPayload["run_id"]?.toString(),
            runner = runPayload["runner"]?.toString(),
            stepCount = intArg(runPayload["step_count"], defaultValue = 0),
            errorMessage = runPayload["error_message"]?.toString()
        )
        val stepResults = listArg(runPayload["step_results"])
        val timing = mapArg(runPayload["timing"])
        val startedAtMs = longArg(timing["started_at_ms"])
        val finishedAtMs = longArg(timing["finished_at_ms"])
        val durationMs = longArg(timing["call_duration_ms"], timing["duration_ms"], timing["runner_duration_ms"])
            .takeIf { it > 0L }
            ?: (finishedAtMs - startedAtMs).takeIf { startedAtMs > 0L && finishedAtMs >= startedAtMs }
            ?: stepResults.sumOf { raw ->
                longArg(mapArg(raw)["duration_ms"]).coerceAtLeast(0L)
            }
        val successStepCount = intArg(
            runPayload["success_step_count"],
            defaultValue = stepResults.count { raw -> mapArg(raw)["success"] != false },
        )
        return linkedMapOf<String, Any?>(
            "success" to (runPayload["success"] == true),
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "runner" to runPayload["runner"],
            "step_count" to intArg(runPayload["step_count"], defaultValue = stepResults.size),
            "success_step_count" to successStepCount,
            "actions_executed" to successStepCount,
            "guard_decision" to decision,
            "risk_level" to guard["risk_level"],
            "execution_mode" to executionMode,
            "step_results" to stepResults,
            "started_at_ms" to startedAtMs.takeIf { it > 0L },
            "finished_at_ms" to finishedAtMs.takeIf { it > 0L },
            "duration_ms" to durationMs,
            "runner_duration_ms" to durationMs,
            "timing" to timing,
            "needs_agent" to (runPayload["model_required"] == true || fallbackMetadata.isNotEmpty()),
            "fallback_available" to fallbackMetadata["fallback_available"],
            "fallback_session_id" to fallbackMetadata["fallback_session_id"],
            "resume_from_step" to fallbackMetadata["resume_from_step"],
            "fallback_attempt" to fallbackMetadata["fallback_attempt"],
            "fallback_unavailable_reason" to fallbackMetadata["fallback_unavailable_reason"],
            "fallback_context" to fallbackMetadata["fallback_context"],
            "agent_prompt" to fallbackMetadata["agent_prompt"],
            "needs_confirmation" to false,
            "error_message" to runPayload["error_message"],
            "guard" to guard,
            "result" to runPayload
        ).filterValues { it != null }
    }

    fun listRunLogs(args: Map<String, Any?>?): Map<String, Any?> {
        val limit = intArg(args?.get("limit"), defaultValue = 50).coerceIn(1, 200)
        val offset = intArg(args?.get("offset"), defaultValue = 0).coerceAtLeast(0)
        return InternalRunLogStore.listRuns(context, limit = limit, offset = offset)
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
            register = boolArgOrDefault(request["register"], defaultValue = true),
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
            startedAtMs = longArg(runLog["started_at_ms"], defaultValue = System.currentTimeMillis()),
            finishedAtMs = longArg(runLog["finished_at_ms"], defaultValue = System.currentTimeMillis()),
            success = success,
            doneReason = firstNonBlank(resultMap["done_reason"], runLog["done_reason"]),
            errorMessage = firstNonBlank(resultMap["error"], runLog["error_message"]),
            cards = cards,
        )
        if (record.success != true) {
            return errorPayload(
                code = "RUN_LOG_NOT_SUCCESSFUL",
                message = "RunLog did not finish successfully: ${record.runId}"
            )
        }
        val spec = RunLogReusableFunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps"
            )
        workspaceFunctionStore.mirrorRunLog(record)
        return functionRepository.register(spec) + linkedMapOf(
            "run_id" to record.runId,
            "function_spec" to spec
        )
    }

    private fun rankNodeCapabilities(
        nodeMatches: List<OobUdegNodeStore.RecallMatch>,
        goal: String,
        currentPackage: String,
        topK: Int,
    ): NodeCapabilityRanking {
        val rankedFunctions = mutableListOf<RankedFunction>()
        val functionCapabilities = mutableListOf<Map<String, Any?>>()
        nodeMatches.forEach { nodeMatch ->
            val node = nodeMatch.toMap()
            val pageScore = nodeMatch.pageSimilarity.toDouble()
            OobUdegNodeStore.functionSummaries(nodeMatch.node).forEach { functionSummary ->
                val functionId = firstNonBlank(functionSummary["function_id"])
                if (functionId.isBlank()) return@forEach
                val spec = functionRepository.get(functionId) ?: return@forEach
                val textScore = scoreNodeFunctionText(
                    spec = spec,
                    function = functionSummary,
                    goal = goal,
                    currentPackage = currentPackage,
                )
                val combinedScore = (
                    PAGE_MATCH_WEIGHT * pageScore +
                        GOAL_MATCH_WEIGHT * textScore.score
                    ).coerceIn(0.0, 1.0)
                val rankedFunction = RankedFunction(
                    spec = spec,
                    functionId = functionId,
                    score = roundScore(combinedScore),
                    reason = "udeg_${nodeMatch.reason};${textScore.reason}",
                    textScore = textScore.score,
                    pageScore = pageScore,
                    node = node,
                    recallScope = "udeg_node",
                )
                rankedFunctions += rankedFunction
                functionCapabilities += nodeCapabilityMap(
                    node = node,
                    functionId = functionId,
                    capabilityType = "function",
                    score = rankedFunction.score,
                    textScore = textScore.score,
                    pageScore = pageScore,
                    reason = rankedFunction.reason,
                    spec = spec,
                    nodeCapability = functionSummary,
                )
            }
        }
        val limit = topK.coerceIn(1, 50)
        val sortedFunctionCapabilities = functionCapabilities.sortedWith(capabilityComparator())
        return NodeCapabilityRanking(
            functions = rankedFunctions
                .sortedWith(
                    compareByDescending<RankedFunction> { it.score }
                        .thenBy { it.functionId }
                )
                .take(limit),
            capabilities = sortedFunctionCapabilities.take(limit),
            functionCapabilities = sortedFunctionCapabilities.take(limit),
        )
    }

    private fun capabilityComparator(): Comparator<Map<String, Any?>> =
        compareByDescending<Map<String, Any?>> { numberDouble(it["score"]) }
            .thenByDescending { numberDouble(it["page_similarity"]) }
            .thenBy { it["function_id"]?.toString().orEmpty() }

    private fun nodeCapabilityMap(
        node: Map<String, Any?>,
        functionId: String,
        capabilityType: String,
        score: Double,
        textScore: Double,
        pageScore: Double,
        reason: String,
        spec: Map<String, Any?>,
        nodeCapability: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val functionSteps = stepSummaries(spec)
        val call = linkedMapOf<String, Any?>(
            "tool" to "call_tool",
            "function_id" to functionId,
            "arguments" to emptyMap<String, Any?>(),
        )
        return linkedMapOf(
            "capability_type" to capabilityType,
            "recall_scope" to "udeg_node",
            "function_id" to functionId,
            "name" to firstNonBlank(nodeCapability["name"], spec["name"], functionId),
            "description" to firstNonBlank(
                nodeCapability["description"],
                spec["description"],
                spec["name"],
                functionId
            ),
            "score" to roundScore(score),
            "text_score" to roundScore(textScore),
            "page_similarity" to roundScore(pageScore),
            "strict_direct_hit" to (
                score >= DIRECT_HIT_SCORE &&
                    pageScore >= DIRECT_HIT_SCORE &&
                    textScore >= DIRECT_HIT_SCORE
                ),
            "reason" to reason,
            "node_id" to firstNonBlank(node["node_id"]),
            "udeg_node" to node.takeIf { it.isNotEmpty() },
            "node_skill_context" to node["node_skill_context"],
            "node_capability" to nodeCapability.takeIf { it.isNotEmpty() },
            "inputSchema" to inputSchema(spec),
            "remaining_step_count" to functionSteps.size,
            "execution_scope" to "function",
            "call" to call,
            "step_summaries" to functionSteps,
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "oob_udeg_node_capability",
        ).filterValues { it != null }
    }

    private fun compactRecallPayload(
        payload: Map<String, Any?>,
        includeDebug: Boolean,
    ): Map<String, Any?> {
        if (includeDebug) {
            return linkedMapOf<String, Any?>().apply {
                putAll(payload)
                put("payload_mode", "debug_full")
            }
        }
        return linkedMapOf<String, Any?>().apply {
            put("success", payload["success"])
            put("decision", payload["decision"])
            put("decision_path", payload["decision_path"])
            put("hit", compactRecallCandidate(payload["hit"]))
            put(
                "candidates",
                listArg(payload["candidates"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "capability_candidates",
                listArg(payload["capability_candidates"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "node_capabilities",
                listArg(payload["node_capabilities"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "node_function_capabilities",
                listArg(payload["node_function_capabilities"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "node_candidates",
                listArg(payload["node_candidates"]).mapNotNull { compactRecallNode(it) }
            )
            put("current_node", compactRecallNode(payload["current_node"]))
            put("node_skill_context", compactNodeSkillContext(payload["node_skill_context"]))
            put("decision_context", compactDecisionContext(payload["decision_context"]))
            put("decision_policy", payload["decision_policy"])
            put("count", payload["count"])
            put("reason", payload["reason"])
            put("current_package", payload["current_package"])
            put("current_node_id", payload["current_node_id"])
            put("source", payload["source"])
            put("payload_mode", "agent_compact")
            put("debug_available", true)
        }.filterValues { it != null }
    }

    private fun compactRecallCandidate(value: Any?): Map<String, Any?>? {
        val candidate = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "capability_type" to candidate["capability_type"],
            "function_id" to candidate["function_id"],
            "description" to candidate["description"],
            "name" to candidate["name"],
            "inputSchema" to candidate["inputSchema"],
            "score" to candidate["score"],
            "text_score" to candidate["text_score"],
            "page_similarity" to candidate["page_similarity"],
            "strict_direct_hit" to candidate["strict_direct_hit"],
            "reason" to candidate["reason"],
            "node_id" to firstNonBlank(
                candidate["node_id"],
                mapArg(candidate["udeg_node"])["node_id"],
            ).takeIf { it.isNotBlank() },
            "node_skill_context" to compactNodeSkillContext(candidate["node_skill_context"]),
            "recall_scope" to candidate["recall_scope"],
            "remaining_step_count" to candidate["remaining_step_count"],
            "requires_arguments" to candidate["requires_arguments"],
            "execution_scope" to candidate["execution_scope"],
            "call" to candidate["call"],
            "step_count" to candidate["step_count"],
            "requires_agent_fallback" to candidate["requires_agent_fallback"],
            "step_summaries" to listArg(candidate["step_summaries"]).mapNotNull {
                compactStepSummary(it)
            },
            "function_kind" to candidate["function_kind"],
            "asset_state" to candidate["asset_state"],
            "source" to candidate["source"],
        ).filterValues { it != null }
    }

    private fun compactRecallNode(value: Any?): Map<String, Any?>? {
        val node = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "node_id" to node["node_id"],
            "package_name" to node["package_name"],
            "page_similarity" to node["page_similarity"],
            "reason" to node["reason"],
            "decision_context" to compactDecisionContext(node["decision_context"]),
            "node_skill_context" to compactNodeSkillContext(node["node_skill_context"]),
            "function_ids" to listArg(node["function_ids"]).mapNotNull {
                it?.toString()?.trim()?.takeIf(String::isNotEmpty)
            },
            "source" to node["source"],
        ).filterValues { it != null }
    }

    private fun compactNodeSkillContext(value: Any?): Map<String, Any?>? {
        val context = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        val pageMatch = mapArg(context["page_match"])
        val udegNode = mapArg(context["udeg_node"])
        return linkedMapOf<String, Any?>(
            "schema_version" to context["schema_version"],
            "role" to context["role"],
            "context_kind" to context["context_kind"],
            "decision_path" to context["decision_path"],
            "entry_policy" to context["entry_policy"],
            "page_match" to linkedMapOf(
                "node_id" to pageMatch["node_id"],
                "page_similarity" to pageMatch["page_similarity"],
                "reason" to pageMatch["reason"],
            ).filterValues { it != null }.takeIf { it.isNotEmpty() },
            "udeg_node" to linkedMapOf(
                "node_id" to udegNode["node_id"],
                "package_name" to udegNode["package_name"],
                "activity_name" to udegNode["activity_name"],
            ).filterValues { it != null }.takeIf { it.isNotEmpty() },
            "decision_context" to compactDecisionContext(context["decision_context"]),
            "attached_function_count" to listArg(context["attached_functions"]).size,
        ).filterValues { it != null }
    }

    private fun compactDecisionContext(value: Any?): Map<String, Any?>? {
        val context = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "schema_version" to context["schema_version"],
            "role" to context["role"],
            "entry_policy" to context["entry_policy"],
            "skill_id" to context["skill_id"],
            "decision_path" to context["decision_path"],
            "function_count" to context["function_count"],
            "page_analysis" to compactPageAnalysis(context["page_analysis"]),
        ).filterValues { it != null }
    }

    private fun compactPageAnalysis(value: Any?): Map<String, Any?>? {
        val analysis = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "package_name" to analysis["package_name"],
            "activity_name" to analysis["activity_name"],
            "page_title" to analysis["page_title"],
            "summary" to analysis["summary"],
            "visible_text" to listArg(analysis["visible_text"]).take(8),
            "primary_actions" to listArg(analysis["primary_actions"]).take(8),
        ).filterValues { it != null }
    }

    private fun compactStepSummary(value: Any?): Map<String, Any?>? {
        val step = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "index" to step["index"],
            "id" to step["id"],
            "title" to step["title"],
            "kind" to step["kind"],
            "tool" to step["tool"],
        ).filterValues { it != null }
    }

    private fun scoreNodeFunctionText(
        spec: Map<String, Any?>,
        function: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): FunctionTextScore {
        val base = scoreFunctionText(spec, goal, currentPackage)
        val capabilityCorpus = listOf(
            function["function_id"],
            function["name"],
            function["description"],
            listArg(function["step_summaries"]).joinToString(" ") { raw ->
                val step = mapArg(raw)
                listOf(step["title"], step["tool"], step["id"])
                    .joinToString(" ")
            },
        ).joinToString(" ").trim()
        val capabilityScore = if (goal.isBlank() || capabilityCorpus.isBlank()) {
            0.0
        } else {
            tokenOverlapScore(goal, capabilityCorpus)
        }
        return if (capabilityScore > base.score) {
            FunctionTextScore(capabilityScore, "node_function_capability_match")
        } else {
            base
        }
    }

    private fun scoreFunctionText(
        spec: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): FunctionTextScore {
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
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
            return FunctionTextScore(0.25, "empty_goal")
        }

        val normalizedGoal = normalizeText(goal)
        val normalizedId = normalizeText(functionId)
        val normalizedName = normalizeText(name)
        val normalizedDescription = normalizeText(description)
        val normalizedSourceGoal = normalizeText(source["goal"]?.toString().orEmpty())
        var score = when {
            normalizedGoal == normalizedId -> 1.0
            normalizedGoal == normalizedName && normalizedName.isNotEmpty() -> 0.99
            normalizedGoal == normalizedDescription && normalizedDescription.isNotEmpty() -> 0.96
            normalizedGoal == normalizedSourceGoal && normalizedSourceGoal.isNotEmpty() -> 0.98
            normalizedId.contains(normalizedGoal) || normalizedGoal.contains(normalizedId) -> 0.92
            normalizedName.contains(normalizedGoal) || normalizedGoal.contains(normalizedName) -> 0.90
            normalizedSourceGoal.contains(normalizedGoal) || normalizedGoal.contains(normalizedSourceGoal) -> 0.90
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
        return FunctionTextScore(roundScore(score), reason)
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
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val execution = mapArg(spec["execution"])
        val steps = materializedSteps(spec)
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
        return linkedMapOf<String, Any?>(
            "function_id" to functionId,
            "description" to (spec["description"] ?: spec["name"] ?: functionId),
            "name" to spec["name"],
            "inputSchema" to inputSchema(spec),
            "score" to score,
            "reason" to reason,
            "step_count" to (execution["step_count"] ?: steps.size),
            "requires_agent_fallback" to execution["requires_agent_fallback"],
            "step_summaries" to stepSummaries(spec),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local"
        ).apply { putAll(extras) }
    }

    private fun functionAgentSummary(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = mapArg(spec["execution"])
        val steps = materializedSteps(spec)
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
        return linkedMapOf(
            "function_id" to functionId,
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (execution["step_count"] ?: steps.size),
            "omniflow_step_count" to execution["omniflow_step_count"],
            "agent_step_count" to execution["agent_step_count"],
            "requires_agent_fallback" to execution["requires_agent_fallback"],
            "parameter_names" to OobFunctionSchemaBuilder.parameterNames(spec),
            "step_summaries" to stepSummaries(spec),
            "source" to spec["source"],
            "constraints" to spec["constraints"],
        ).filterValues { it != null }
    }

    private fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.stepSummaries(spec)
    }

    private fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        return OobFunctionSchemaBuilder.inputSchema(spec)
    }

    private fun isNoArgumentFunction(spec: Map<String, Any?>): Boolean {
        val schema = inputSchema(spec)
        val required = listArg(schema["required"])
        val properties = mapArg(schema["properties"])
        return required.isEmpty() && properties.isEmpty()
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.materializedSteps(spec)
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

    private fun numberDouble(value: Any?): Double =
        when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
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

    private fun intArg(vararg values: Any?, defaultValue: Int): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    private fun longArg(vararg values: Any?, defaultValue: Long = 0L): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.trim().toLongOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    private fun boolArg(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true) ||
                value.trim() == "1"
            is Number -> value.toInt() != 0
            else -> false
        }

    private fun boolArgOrDefault(value: Any?, defaultValue: Boolean): Boolean =
        when (value) {
            null -> defaultValue
            is Boolean -> value
            is String -> {
                val text = value.trim().lowercase()
                when (text) {
                    "true", "1", "yes", "y", "on" -> true
                    "false", "0", "no", "n", "off" -> false
                    else -> defaultValue
                }
            }
            is Number -> value.toInt() != 0
            else -> defaultValue
        }

    private data class RankedFunction(
        val spec: Map<String, Any?>,
        val functionId: String,
        val score: Double,
        val reason: String,
        val textScore: Double = score,
        val pageScore: Double = 0.0,
        val node: Map<String, Any?> = emptyMap(),
        val recallScope: String = "udeg_node",
    )

    private data class FunctionTextScore(
        val score: Double,
        val reason: String,
    )

    private data class NodeCapabilityRanking(
        val functions: List<RankedFunction>,
        val capabilities: List<Map<String, Any?>>,
        val functionCapabilities: List<Map<String, Any?>>,
    )

    private fun attachFunctionCallTiming(
        payload: Map<String, Any?>,
        timing: FunctionCallTiming,
    ): Map<String, Any?> {
        val callTiming = timing.finish()
        val existingTiming = mapArg(payload["timing"])
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            put("call_started_at_ms", callTiming["started_at_ms"])
            put("call_finished_at_ms", callTiming["finished_at_ms"])
            put("call_duration_ms", callTiming["duration_ms"])
            put("call_phase_ms", callTiming["phase_ms"])
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
    }

    private class FunctionCallTiming {
        private val startedAtNanos = System.nanoTime()
        val startedAtMs: Long = System.currentTimeMillis()
        private val phases = linkedMapOf<String, Long>()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        suspend fun <T> measureSuspend(phaseName: String, block: suspend () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        fun finish(): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "guard_check_ms",
                "execute_function_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            return linkedMapOf(
                "source" to "oob_function_call",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to completedPhases,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private class RecallTiming {
        private val startedAtNanos = System.nanoTime()
        private val phases = linkedMapOf<String, Long>()
        val startedAtMs: Long = System.currentTimeMillis()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAt = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAt)
            }
        }

        fun finish(
            decision: String,
            counts: Map<String, Any?>,
        ): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "parse_request_ms",
                "read_current_package_ms",
                "read_current_page_ms",
                "page_match_ms",
                "rank_functions_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            return linkedMapOf(
                "source" to "oob_omniflow_recall",
                "decision" to decision,
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to completedPhases,
                "counts" to counts,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    companion object {
        private const val MIN_RECALL_SCORE = 0.30
        private const val DIRECT_HIT_SCORE = 0.999
        private const val PAGE_MATCH_WEIGHT = 0.70
        private const val GOAL_MATCH_WEIGHT = 0.30
    }
}
