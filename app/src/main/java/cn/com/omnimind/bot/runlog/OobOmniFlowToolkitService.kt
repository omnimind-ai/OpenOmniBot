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
    private val explorer = OobOmniFlowExplorer(context)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

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
                        "segment_candidates" to 0,
                        "segment_scanned_functions" to 0,
                        "segment_text_candidates" to 0,
                        "segment_boundaries" to 0,
                        "segment_boundary_page_hits" to 0,
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
        val segmentMatches = timing.measure("segment_match_ms") {
            segmentMatches(
                recalledFunctions = ranked,
                nodeMatches = decisionNodeMatches,
                goal = goal,
                currentXml = currentXml,
                currentPackage = currentPackage,
                topK = k,
            )
        }
        val segmentCandidates = segmentMatches.matches.map { it.toMap() }
        val segmentHit = segmentMatches.matches.takeIf { it.size == 1 }?.firstOrNull {
            it.score >= DIRECT_HIT_SCORE &&
                it.pageScore >= DIRECT_HIT_SCORE &&
                it.textScore >= DIRECT_HIT_SCORE &&
                it.noArgumentFunction
        }.takeIf { allowDirectExecutionDecision }
        val directHit = ranked.takeIf { it.size == 1 }?.firstOrNull {
            it.score >= DIRECT_HIT_SCORE &&
                it.pageScore >= DIRECT_HIT_SCORE &&
                it.textScore >= DIRECT_HIT_SCORE &&
                isNoArgumentFunction(it.spec)
        }.takeIf { allowDirectExecutionDecision }
        val decision = when {
            directHit != null -> "hit"
            segmentHit != null -> "segment_hit"
            segmentCandidates.isNotEmpty() -> "segment_recall"
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
            "segment_hit" to segmentHit?.toMap(),
            "candidates" to if (directHit == null) candidates else emptyList<Map<String, Any?>>(),
            "segment_candidates" to if (segmentHit == null) segmentCandidates else emptyList<Map<String, Any?>>(),
            "capability_candidates" to nodeCapabilityRanking.capabilities,
            "node_capabilities" to nodeCapabilityRanking.capabilities,
            "node_function_capabilities" to nodeCapabilityRanking.functionCapabilities,
            "node_segment_capabilities" to nodeCapabilityRanking.segmentCapabilities,
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
            "segment_count" to segmentCandidates.size,
            "reason" to when {
                directHit != null -> "udeg_page_match_direct_function_hit"
                segmentHit != null -> "page_vector_segment_function_hit"
                nodeCandidates.isEmpty() && segmentCandidates.isEmpty() -> "no_udeg_node_or_segment_page_match"
                nodeCandidates.isEmpty() -> "no_udeg_node_for_segment_recall"
                nodeCapabilityRanking.capabilities.isEmpty() -> "udeg_node_match_without_attached_capability"
                candidates.isEmpty() && segmentCandidates.isNotEmpty() -> "udeg_node_segment_recall"
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
                    "node_segment_capabilities" to nodeCapabilityRanking.segmentCapabilities.size,
                    "segment_candidates" to segmentCandidates.size,
                    "segment_scanned_functions" to segmentMatches.scannedFunctionCount,
                    "segment_text_candidates" to segmentMatches.textEligibleFunctionCount,
                    "segment_boundaries" to segmentMatches.boundaryCount,
                    "segment_boundary_page_hits" to segmentMatches.boundaryPageHitCount,
                )
            ),
            "source" to "oob_native_udeg_page_match"
        )
        return compactRecallPayload(payload, includeDebug)
    }

    suspend fun callFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val goal = firstNonBlank(request["goal"])
        val callArguments = mapArg(request["arguments"])
        val startStepIndex = intArg(
            request["start_step_index"],
            request["startStepIndex"],
            request["segment_start_step_index"],
            request["segmentStartStepIndex"],
            defaultValue = 0
        ).coerceAtLeast(0)
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
                "arguments" to callArguments,
                "start_step_index" to startStepIndex,
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
            startStepIndex = startStepIndex,
            allowAgentFallback = true
        )
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
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "segment_start_step_index" to startStepIndex.takeIf { it > 0 },
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

    fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("functionId"), args?.get("function_id"))
        return replayService.deleteFunction(functionId)
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
        return replayService.clearFunctions()
    }

    fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionSpec = functionSpecForRegistration(request)
        if (functionSpec.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_SPEC_EMPTY",
                message = "functionSpec or steps are required"
            )
        }
        val mode = if (hasExplicitFunctionSpec(request)) "function_spec" else "simple"
        return replayService.registerFunctionSpec(functionSpec) + linkedMapOf(
            "registration_input_mode" to mode,
            "simple_schema_supported" to true,
        )
    }

    private fun functionSpecForRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = explicitFunctionSpec(request)
        if (explicit.isNotEmpty()) return explicit
        val steps = simpleRegistrationSteps(request)
        if (steps.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis().toString()
        val rawFunctionId = firstNonBlank(
            request["functionId"],
            request["function_id"],
            request["id"],
        )
        val name = firstNonBlank(request["name"], request["title"], rawFunctionId)
            .ifBlank { "OOB reusable function" }
        val description = firstNonBlank(
            request["description"],
            request["goal"],
            request["summary"],
            name,
        )
        val functionId = rawFunctionId.ifBlank {
            simpleFunctionIdFrom(name = name, description = description, now = now)
        }
        val sourceContext = sourceContextFromRegistration(request)
        val sourcePackageName = firstNonBlank(
            mapArg(sourceContext["src_ctx"])["package_name"],
            mapArg(sourceContext["src_ctx"])["packageName"],
        )
        val packageName = firstNonBlank(
            request["packageName"],
            request["package_name"],
            request["current_package"],
            request["currentPackage"],
            mapArg(request["sourcePage"])["package_name"],
            mapArg(request["sourcePage"])["packageName"],
            mapArg(request["source_page"])["package_name"],
            mapArg(request["source_page"])["packageName"],
            sourcePackageName,
        )
        val normalizedSteps = steps.mapIndexed { index, raw ->
            normalizeSimpleRegisteredStep(
                raw = raw,
                index = index,
                inheritedSourceContext = sourceContext.takeIf { index == 0 }.orEmpty(),
            )
        }
        val capabilities = simpleExecutionCapabilities(normalizedSteps)
        return linkedMapOf<String, Any?>(
            "schema_version" to "oob.reusable_function.v1",
            "function_id" to functionId,
            "name" to name,
            "description" to description,
            "parameters" to listArg(request["parameters"]).mapNotNull { raw ->
                mapArg(raw).takeIf { it.isNotEmpty() }
            },
            "constraints" to linkedMapOf(
                "package_name" to packageName.takeIf { it.isNotBlank() },
            ).filterValues { it != null },
            "source" to linkedMapOf(
                "kind" to "agent_registered_function",
                "goal" to firstNonBlank(request["goal"], description),
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "registered_via" to "oob_function_register.simple",
                "source_context_mode" to firstNonBlank(
                    mapArg(sourceContext["_oob_meta"])["mode"],
                    "none"
                ).takeIf { sourceContext.isNotEmpty() },
                "registered_at" to now,
            ).filterValues { it != null },
            "execution" to linkedMapOf(
                "kind" to "tool_sequence",
                "runner" to "oob_tool_sequence",
                "entrypoint" to "execute",
                "capabilities" to capabilities,
                "fallback_runner" to "oob.agent.run",
                "steps" to normalizedSteps,
                "step_count" to normalizedSteps.size,
                "omniflow_step_count" to capabilities["omniflow_step_count"],
                "agent_step_count" to capabilities["agent_step_count"],
                "requires_agent_fallback" to capabilities["requires_agent_fallback"],
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to now,
                "updated_at" to now,
                "runner" to "oob_agent_reusable_function",
                "storage" to "workspace",
                "registration_input_mode" to "simple",
            ),
        )
    }

    private fun hasExplicitFunctionSpec(request: Map<String, Any?>): Boolean =
        explicitFunctionSpec(request).isNotEmpty()

    private fun explicitFunctionSpec(request: Map<String, Any?>): Map<String, Any?> =
        mapArg(request["functionSpec"])
            .ifEmpty { mapArg(request["function_spec"]) }
            .ifEmpty {
                if (request.containsKey("function_id") &&
                    mapArg(request["execution"]).isNotEmpty()
                ) {
                    request
                } else {
                    emptyMap()
                }
            }

    private fun simpleRegistrationSteps(request: Map<String, Any?>): List<Map<String, Any?>> =
        listArg(request["steps"])
            .ifEmpty { listArg(request["execution_steps"]) }
            .ifEmpty { listArg(request["executionSteps"]) }
            .mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }

    private fun normalizeSimpleRegisteredStep(
        raw: Map<String, Any?>,
        index: Int,
        inheritedSourceContext: Map<String, Any?>,
    ): Map<String, Any?> {
        val rawTool = firstNonBlank(
            raw["action"],
            raw["tool"],
            raw["tool_name"],
            raw["toolName"],
            raw["omniflow_action"],
            raw["local_action"],
            raw["type"],
        ).ifBlank {
            if (firstNonBlank(raw["function_id"], raw["functionId"]).isNotBlank()) {
                "call_tool"
            } else {
                "finished"
            }
        }
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(rawTool)
        val action = RunLogReplayPolicy.omniflowActionForToolName(rawTool)
        val sourceContext = mapArg(raw["source_context"])
            .ifEmpty { mapArg(raw["sourceContext"]) }
            .ifEmpty { inheritedSourceContext }
        val title = firstNonBlank(raw["title"], raw["summary"], raw["description"])
            .ifBlank { simpleStepTitle(action ?: normalizedTool, raw, index) }
        val stepArgs = normalizeSimpleStepArgs(raw, action ?: normalizedTool)

        val step = linkedMapOf<String, Any?>(
            "id" to firstNonBlank(raw["id"], raw["step_id"], "step_${index + 1}"),
            "index" to index,
            "title" to title,
        )
        when {
            action != null -> {
                step["kind"] = "omniflow_action"
                step["executor"] = "omniflow"
                step["omniflow_action"] = action
                step["local_action"] = action
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = action
                step["callable_tool"] = action
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) {
                    step["source_context"] = sourceContext
                    if (RunLogReplayPolicy.isCoordinateAction(action)) {
                        step["coordinate_hook"] = "omniflow"
                    }
                }
            }
            RunLogReplayPolicy.isOmniflowGraphTool(normalizedTool) -> {
                step["kind"] = "omniflow_graph"
                step["executor"] = "omniflow"
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = normalizedTool
                step["callable_tool"] = normalizedTool
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
            RunLogReplayPolicy.isOmniflowFunctionTool(normalizedTool) ||
                RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool) ||
                firstNonBlank(raw["function_id"], raw["functionId"]).isNotBlank() -> {
                step["kind"] = "omniflow_function"
                step["executor"] = "omniflow"
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = "call_tool"
                step["callable_tool"] = "call_tool"
                step["source_tool"] = normalizedTool.takeIf { it != "call_tool" }
                step["args"] = canonicalSimpleCallToolArgs(raw, stepArgs)
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
            else -> {
                step["kind"] = "tool_call"
                step["executor"] = "tool"
                step["scriptable"] = true
                step["tool"] = normalizedTool
                step["callable_tool"] = normalizedTool
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
        }
        return step.filterValues { it != null }
    }

    private fun normalizeSimpleStepArgs(
        raw: Map<String, Any?>,
        action: String,
    ): Map<String, Any?> {
        val args = linkedMapOf<String, Any?>()
        args.putAll(mapArg(raw["args"]))
        args.putAll(mapArg(raw["arguments"]).filterKeys { it !in args })
        putIfPresent(args, "package_name", raw["package_name"], raw["packageName"])
        putIfPresent(args, "target_description", raw["target_description"], raw["targetDescription"], raw["label"])
        putIfPresent(args, "text", raw["text"])
        putIfPresent(args, "content", raw["content"], raw["value"])
        putIfPresent(args, "key", raw["key"], raw["hotkey"], raw["hot_key"])
        putIfPresent(args, "direction", raw["direction"], raw["scroll_direction"], raw["scrollDirection"])
        putIfPresent(args, "x", raw["x"], raw["center_x"], raw["centerX"])
        putIfPresent(args, "y", raw["y"], raw["center_y"], raw["centerY"])
        putIfPresent(args, "x1", raw["x1"])
        putIfPresent(args, "y1", raw["y1"])
        putIfPresent(args, "x2", raw["x2"])
        putIfPresent(args, "y2", raw["y2"])
        putIfPresent(args, "distance", raw["distance"], raw["distance_px"], raw["distancePx"])
        putIfPresent(args, "duration_ms", raw["duration_ms"], raw["durationMs"])
        putIfPresent(args, "reset_task", raw["reset_task"], raw["resetTask"])
        putIfPresent(args, "launch_mode", raw["launch_mode"], raw["launchMode"])
        putIfPresent(args, "function_id", raw["function_id"], raw["functionId"])
        val nestedArguments = mapArg(raw["function_arguments"])
            .ifEmpty { mapArg(raw["functionArguments"]) }
            .ifEmpty { mapArg(raw["input"]) }
        if (nestedArguments.isNotEmpty() && !args.containsKey("arguments")) {
            args["arguments"] = nestedArguments
        }
        if ((action == "input_text" || action == "type") &&
            firstNonBlank(args["content"], args["text"], args["value"]).isBlank()
        ) {
            putIfPresent(args, "content", raw["input_text"], raw["inputText"])
        }
        if (action == "open_app" && args["reset_task"] == null) {
            args["reset_task"] = true
            args["launch_mode"] = firstNonBlank(args["launch_mode"], "fresh_task")
        }
        if (action == "finished" && args.isEmpty()) {
            args["content"] = firstNonBlank(raw["content"], raw["summary"], "Done")
        }
        return args.filterValues { it != null }
    }

    private fun canonicalSimpleCallToolArgs(
        raw: Map<String, Any?>,
        normalizedArgs: Map<String, Any?>,
    ): Map<String, Any?> {
        val functionId = firstNonBlank(
            normalizedArgs["function_id"],
            raw["function_id"],
            raw["functionId"],
            raw["oob_function_id"],
            raw["oobFunctionId"],
        )
        val targetTool = firstNonBlank(
            raw["tool_name"],
            raw["toolName"],
            raw["target_tool"],
            raw["targetTool"],
            normalizedArgs["tool_name"],
            normalizedArgs["toolName"],
        )
        val nestedArguments = mapArg(normalizedArgs["arguments"])
            .ifEmpty { mapArg(raw["arguments"]) }
            .ifEmpty { mapArg(raw["args"]) }
        return linkedMapOf<String, Any?>().apply {
            putAll(normalizedArgs)
            if (functionId.isNotBlank()) put("function_id", functionId)
            if (targetTool.isNotBlank()) put("tool_name", targetTool)
            if (nestedArguments.isNotEmpty()) put("arguments", nestedArguments)
        }.filterValues { it != null }
    }

    private fun sourceContextFromRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(request["source_context"])
            .ifEmpty { mapArg(request["sourceContext"]) }
        if (explicit.isNotEmpty()) return explicit
        val sourcePage = mapArg(request["sourcePage"])
            .ifEmpty { mapArg(request["source_page"]) }
            .ifEmpty { mapArg(request["currentPage"]) }
            .ifEmpty { mapArg(request["current_page"]) }
        val pageXmlFromRequest = firstNonBlank(
            sourcePage["page"],
            sourcePage["xml"],
            sourcePage["observation_xml"],
            sourcePage["observationXml"],
            request["current_xml"],
            request["currentXml"],
            request["source_xml"],
            request["sourceXml"],
            request["xml"],
        )
        val requestPackageName = firstNonBlank(
            sourcePage["package_name"],
            sourcePage["packageName"],
            request["package_name"],
            request["packageName"],
            request["current_package"],
            request["currentPackage"],
        )
        val requestActivityName = firstNonBlank(
            sourcePage["activity_name"],
            sourcePage["activityName"],
            request["activity_name"],
            request["activityName"],
        )
        val autoCaptureDisabled = boolArg(request["disable_current_page_capture"]) ||
            boolArg(request["disableCurrentPageCapture"]) ||
            boolArg(request["no_current_page_capture"]) ||
            boolArg(request["noCurrentPageCapture"])
        val autoCaptureAllowed = !autoCaptureDisabled
        val capturedPage = if (pageXmlFromRequest.isBlank() && autoCaptureAllowed) {
            currentPageSourceContext()
        } else {
            emptyMap()
        }
        val capturedSrcCtx = mapArg(capturedPage["src_ctx"])
        val pageXml = firstNonBlank(pageXmlFromRequest, capturedSrcCtx["page"])
        if (pageXml.isBlank()) return emptyMap()
        val packageName = firstNonBlank(
            requestPackageName,
            capturedSrcCtx["package_name"],
            capturedSrcCtx["packageName"],
        )
        val activityName = firstNonBlank(
            requestActivityName,
            capturedSrcCtx["activity_name"],
            capturedSrcCtx["activityName"],
        )
        val mode = if (pageXmlFromRequest.isBlank()) "current_page_capture" else "explicit_request"
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to pageXml,
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "activity_name" to activityName.takeIf { it.isNotBlank() },
                "require_unique_action_signature" to false,
            ).filterValues { it != null },
            "_oob_meta" to linkedMapOf(
                "mode" to mode,
                "captured_current_page" to (mode == "current_page_capture"),
            ),
        )
    }

    private fun currentPageSourceContext(): Map<String, Any?> {
        val pageXml = runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        if (pageXml.isBlank()) return emptyMap()
        val packageName = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        val activityName = runCatching {
            OmniflowActionRuntime.backend.currentActivityName()?.trim().orEmpty()
        }.getOrDefault("")
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to pageXml,
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "activity_name" to activityName.takeIf { it.isNotBlank() },
                "require_unique_action_signature" to false,
            ).filterValues { it != null }
        )
    }

    private fun simpleStepTitle(action: String, raw: Map<String, Any?>, index: Int): String {
        val target = firstNonBlank(
            raw["target_description"],
            raw["targetDescription"],
            raw["label"],
            raw["text"],
            raw["content"],
            raw["value"],
        )
        return when {
            target.isNotBlank() -> "$action: $target"
            else -> "$action step ${index + 1}"
        }
    }

    private fun simpleFunctionIdFrom(name: String, description: String, now: String): String {
        val seed = "$name $description"
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "registered_function" }
        return "oob_fn_${seed}_${now.takeLast(6)}"
    }

    private fun simpleExecutionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "scriptable_step_count" to steps.count { it["scriptable"] == true },
            "model_free_step_count" to steps.count { it["model_free"] == true },
            "omniflow_step_count" to steps.count { it["executor"] == "omniflow" },
            "agent_step_count" to steps.count { it["executor"] == "agent" },
            "requires_agent_fallback" to steps.any { it["executor"] == "agent" },
        )

    private fun putIfPresent(
        target: MutableMap<String, Any?>,
        key: String,
        vararg values: Any?,
    ) {
        if (target.containsKey(key)) return
        values.firstOrNull { value ->
            value != null && value.toString().trim().isNotEmpty()
        }?.let { target[key] = it }
    }

    fun guardCheck(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val startStepIndex = intArg(
            request["start_step_index"],
            request["startStepIndex"],
            request["segment_start_step_index"],
            request["segmentStartStepIndex"],
            defaultValue = 0
        ).coerceAtLeast(0)
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
        val materializedForGuard = sliceMaterializedSpec(
            materializedSpec = materialized,
            startStepIndex = startStepIndex,
        ) ?: return errorPayload(
            code = "OOB_FUNCTION_SEGMENT_EMPTY",
            message = "start_step_index $startStepIndex is outside function steps",
            functionId = functionId,
            decision = "block",
            riskLevel = "high"
        ) + linkedMapOf("segment_start_step_index" to startStepIndex)
        val stepDecisions = materializedSteps(materializedForGuard).map(::guardStep)
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
            "segment_start_step_index" to startStepIndex.takeIf { it > 0 },
            "segment" to if (startStepIndex > 0) {
                segmentExecutionMeta(materialized, startStepIndex)
            } else {
                null
            },
            "step_decisions" to stepDecisions,
            "source" to "oob_native_omniflow_toolkit"
        ).filterValues { it != null }
    }

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val arguments = mapArg(request["arguments"])
        val dryRun = boolArg(request["dryRun"]) || boolArg(request["dry_run"])
        val continueWithAgent = boolArg(request["continueWithAgent"]) ||
            boolArg(request["continue_with_agent"])
        val confirmed = boolArg(request["confirmed"]) || boolArg(request["userConfirmed"])
        val startStepIndex = intArg(
            request["start_step_index"],
            request["startStepIndex"],
            request["segment_start_step_index"],
            request["segmentStartStepIndex"],
            defaultValue = 0
        ).coerceAtLeast(0)
        val executionMode = firstNonBlank(request["executionMode"], request["execution_mode"])
            .ifBlank { "foreground" }

        val guard = guardCheck(
            linkedMapOf(
                "functionId" to functionId,
                "arguments" to arguments,
                "start_step_index" to startStepIndex,
            )
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
            startStepIndex = startStepIndex,
            allowAgentFallback = continueWithAgent || decision == "needs_agent"
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
        return linkedMapOf<String, Any?>(
            "success" to (runPayload["success"] == true),
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "segment_start_step_index" to startStepIndex.takeIf { it > 0 },
            "runner" to runPayload["runner"],
            "guard_decision" to decision,
            "risk_level" to guard["risk_level"],
            "execution_mode" to executionMode,
            "step_results" to runPayload["step_results"],
            "timing" to runPayload["timing"],
            "needs_agent" to (runPayload["model_required"] == true),
            "needs_confirmation" to false,
            "error_message" to runPayload["error_message"],
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
        startStepIndex: Int = 0,
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
        val materializedForRun = sliceMaterializedSpec(
            materializedSpec = materialized,
            startStepIndex = startStepIndex,
        ) ?: return@withContext errorPayload(
            code = "OOB_FUNCTION_SEGMENT_EMPTY",
            message = "start_step_index $startStepIndex is outside function steps",
            functionId = functionId
        ) + linkedMapOf("segment_start_step_index" to startStepIndex)
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
                materializedSpec = materializedForRun,
                allowAgentFallback = allowAgentFallback,
                allowToolDelegationWithoutRouter = false
            ).let { payload ->
                if (startStepIndex <= 0) {
                    payload
                } else {
                    linkedMapOf<String, Any?>().apply {
                        putAll(payload)
                        put("segment_start_step_index", startStepIndex)
                        put("segment", segmentExecutionMeta(materialized, startStepIndex))
                    }
                }
            }
        }.getOrElse { error ->
            errorPayload(
                code = "OOB_FUNCTION_RUN_FAILED",
                message = error.message.orEmpty(),
                functionId = functionId
            )
        }
    }

    private fun segmentMatches(
        recalledFunctions: List<RankedFunction>,
        nodeMatches: List<OobUdegNodeStore.RecallMatch>,
        goal: String,
        currentXml: String,
        currentPackage: String,
        topK: Int,
    ): SegmentRecallResult {
        val query = OobPageVectorSet.encode(currentXml, currentPackage)
            ?: return emptySegmentRecallResult()
        val queryPackage = query.packageName
        val bySegmentKey = linkedMapOf<String, SegmentMatch>()
        val nodeRankedByFunctionId = recalledFunctions.associateBy { it.functionId }
        val specsByFunctionId = linkedMapOf<String, Map<String, Any?>>()
        recalledFunctions.forEach { recalledFunction ->
            if (recalledFunction.functionId.isNotEmpty()) {
                specsByFunctionId[recalledFunction.functionId] = recalledFunction.spec
            }
        }
        val nodeSegmentBoundaries = nodeSegmentBoundaries(
            recalledFunctions = recalledFunctions,
            nodeMatches = nodeMatches,
        )

        var scannedFunctionCount = 0
        var textEligibleFunctionCount = 0
        var boundaryCount = 0
        var boundaryPageHitCount = 0
        specsByFunctionId.forEach { (functionId, spec) ->
            scannedFunctionCount += 1
            val recalledFunction = nodeRankedByFunctionId[functionId]
                ?: rankFunction(spec, goal, currentPackage)
                ?: return@forEach
            if (recalledFunction.textScore < MIN_RECALL_SCORE) return@forEach
            textEligibleFunctionCount += 1
            val steps = materializedSteps(spec)
            if (steps.size < 2) return@forEach

            for ((index, step) in steps.withIndex()) {
                for (boundary in segmentBoundaryContexts(step, index)) {
                    if (boundary.startStepIndex <= 0 || boundary.startStepIndex >= steps.size) {
                        continue
                    }
                    boundaryCount += 1
                    val boundaryVector = OobPageVectorSet.encode(
                        xml = boundary.pageXml,
                        packageName = boundary.packageName.ifBlank { queryPackage },
                    ) ?: continue
                    val rawPageScore = OobPageVectorSet.cosine(query.vector, boundaryVector.vector)
                    val packageMultiplier = segmentPackageMatchMultiplier(
                        queryPackage = queryPackage,
                        boundaryPackage = boundaryVector.packageName,
                    )
                    val pageScore = (rawPageScore * packageMultiplier).toDouble()
                    if (pageScore < OobUdegNodeStore.MIN_PAGE_MATCH_SCORE.toDouble()) continue
                    boundaryPageHitCount += 1
                    val combinedScore = (
                        PAGE_MATCH_WEIGHT * pageScore +
                            GOAL_MATCH_WEIGHT * recalledFunction.textScore
                        ).coerceIn(0.0, 1.0)
                    val remainingSummaries = stepSummaries(spec).drop(boundary.startStepIndex)
                    if (remainingSummaries.isEmpty()) continue
                    val match = SegmentMatch(
                        spec = spec,
                        functionId = functionId,
                        score = roundScore(combinedScore),
                        reason = segmentMatchReason(
                            recalledFunction = recalledFunction,
                            boundary = boundary.boundary,
                            packageMultiplier = packageMultiplier,
                            rawPageScore = rawPageScore,
                        ),
                        textScore = recalledFunction.textScore,
                        pageScore = pageScore,
                        node = recalledFunction.node,
                        recallScope = recalledFunction.recallScope,
                        matchedStepIndex = boundary.matchedStepIndex,
                        matchedBoundary = boundary.boundary,
                        startStepIndex = boundary.startStepIndex,
                        remainingStepCount = remainingSummaries.size,
                        stepSummaries = remainingSummaries,
                        noArgumentFunction = isNoArgumentFunction(spec),
                    )
                    val key = "${match.functionId}:${match.startStepIndex}"
                    val existing = bySegmentKey[key]
                    if (shouldReplaceSegmentMatch(existing, match)) {
                        bySegmentKey[key] = match
                    }
                }
            }
        }
        nodeSegmentBoundaries.forEach { nodeSegment ->
            scannedFunctionCount += 1
            val functionId = nodeSegment.functionId
            val spec = specsByFunctionId[functionId]
                ?: replayService.getFunctionSpec(functionId)
                ?: return@forEach
            val rankedFromNode = nodeRankedByFunctionId[functionId]
            val recalledFunction = rankedFromNode
                ?: rankNodeSegmentFunction(
                    spec = spec,
                    nodeSegment = nodeSegment,
                    goal = goal,
                    currentPackage = currentPackage,
                )
                ?: return@forEach
            if (recalledFunction.textScore < MIN_RECALL_SCORE) return@forEach
            textEligibleFunctionCount += 1
            val steps = materializedSteps(spec)
            if (nodeSegment.startStepIndex <= 0 || nodeSegment.startStepIndex >= steps.size) {
                return@forEach
            }
            boundaryCount += 1
            if (nodeSegment.pageScore < OobUdegNodeStore.MIN_PAGE_MATCH_SCORE.toDouble()) {
                return@forEach
            }
            boundaryPageHitCount += 1
            val combinedScore = (
                PAGE_MATCH_WEIGHT * nodeSegment.pageScore +
                    GOAL_MATCH_WEIGHT * recalledFunction.textScore
            ).coerceIn(0.0, 1.0)
            val remainingSummaries = stepSummaries(spec).drop(nodeSegment.startStepIndex)
            if (remainingSummaries.isEmpty()) return@forEach
            val match = SegmentMatch(
                spec = spec,
                functionId = functionId,
                score = roundScore(combinedScore),
                reason = segmentMatchReason(
                    recalledFunction = recalledFunction,
                    boundary = nodeSegment.matchedBoundary,
                    packageMultiplier = 1.0f,
                    rawPageScore = nodeSegment.pageScore.toFloat(),
                ),
                textScore = recalledFunction.textScore,
                pageScore = nodeSegment.pageScore,
                node = nodeSegment.node,
                recallScope = "udeg_node_segment",
                matchedStepIndex = nodeSegment.matchedStepIndex,
                matchedBoundary = nodeSegment.matchedBoundary,
                startStepIndex = nodeSegment.startStepIndex,
                remainingStepCount = remainingSummaries.size,
                stepSummaries = remainingSummaries,
                noArgumentFunction = isNoArgumentFunction(spec),
            )
            val key = "${match.functionId}:${match.startStepIndex}"
            val existing = bySegmentKey[key]
            if (shouldReplaceSegmentMatch(existing, match)) {
                bySegmentKey[key] = match
            }
        }
        val matches = bySegmentKey.values
            .sortedWith(
                compareByDescending<SegmentMatch> { it.score }
                    .thenByDescending { it.pageScore }
                    .thenBy { it.functionId }
                    .thenBy { it.startStepIndex }
            )
            .take(topK.coerceIn(1, 50))
        return SegmentRecallResult(
            matches = matches,
            scannedFunctionCount = scannedFunctionCount,
            textEligibleFunctionCount = textEligibleFunctionCount,
            boundaryCount = boundaryCount,
            boundaryPageHitCount = boundaryPageHitCount,
        )
    }

    private fun shouldReplaceSegmentMatch(
        existing: SegmentMatch?,
        candidate: SegmentMatch,
    ): Boolean {
        if (existing == null) return true
        val candidateIsNodeSegment = candidate.recallScope == "udeg_node_segment"
        val existingIsNodeSegment = existing.recallScope == "udeg_node_segment"
        if (candidateIsNodeSegment != existingIsNodeSegment) {
            return candidateIsNodeSegment
        }
        if (candidate.score != existing.score) {
            return candidate.score > existing.score
        }
        if (candidate.pageScore != existing.pageScore) {
            return candidate.pageScore > existing.pageScore
        }
        if (candidate.remainingStepCount != existing.remainingStepCount) {
            return candidate.remainingStepCount < existing.remainingStepCount
        }
        return candidate.matchedBoundary < existing.matchedBoundary
    }

    private fun emptySegmentRecallResult(): SegmentRecallResult =
        SegmentRecallResult(
            matches = emptyList(),
            scannedFunctionCount = 0,
            textEligibleFunctionCount = 0,
            boundaryCount = 0,
            boundaryPageHitCount = 0,
        )

    private fun rankNodeCapabilities(
        nodeMatches: List<OobUdegNodeStore.RecallMatch>,
        goal: String,
        currentPackage: String,
        topK: Int,
    ): NodeCapabilityRanking {
        val rankedFunctions = mutableListOf<RankedFunction>()
        val functionCapabilities = mutableListOf<Map<String, Any?>>()
        val segmentCapabilities = mutableListOf<Map<String, Any?>>()
        nodeMatches.forEach { nodeMatch ->
            val node = nodeMatch.toMap()
            val pageScore = nodeMatch.pageSimilarity.toDouble()
            OobUdegNodeStore.functionSummaries(nodeMatch.node).forEach { functionSummary ->
                val functionId = firstNonBlank(functionSummary["function_id"])
                if (functionId.isBlank()) return@forEach
                val spec = replayService.getFunctionSpec(functionId) ?: return@forEach
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
            OobUdegNodeStore.segmentSummaries(nodeMatch.node).forEach { segment ->
                val functionId = firstNonBlank(segment["function_id"])
                if (functionId.isBlank()) return@forEach
                val spec = replayService.getFunctionSpec(functionId) ?: return@forEach
                val textScore = scoreNodeSegmentText(
                    spec = spec,
                    segment = segment,
                    goal = goal,
                    currentPackage = currentPackage,
                )
                val combinedScore = (
                    PAGE_MATCH_WEIGHT * pageScore +
                        GOAL_MATCH_WEIGHT * textScore.score
                    ).coerceIn(0.0, 1.0)
                segmentCapabilities += nodeCapabilityMap(
                    node = node,
                    functionId = functionId,
                    capabilityType = "function_segment",
                    score = roundScore(combinedScore),
                    textScore = textScore.score,
                    pageScore = pageScore,
                    reason = "udeg_${nodeMatch.reason};udeg_node_attached_segment;${textScore.reason}",
                    spec = spec,
                    segment = segment,
                )
            }
        }
        val limit = topK.coerceIn(1, 50)
        val sortedFunctionCapabilities = functionCapabilities.sortedWith(capabilityComparator())
        val sortedSegmentCapabilities = segmentCapabilities.sortedWith(capabilityComparator())
        return NodeCapabilityRanking(
            functions = rankedFunctions
                .sortedWith(
                    compareByDescending<RankedFunction> { it.score }
                        .thenBy { it.functionId }
                )
                .take(limit),
            capabilities = (sortedFunctionCapabilities + sortedSegmentCapabilities)
                .sortedWith(capabilityComparator())
                .take(limit),
            functionCapabilities = sortedFunctionCapabilities.take(limit),
            segmentCapabilities = sortedSegmentCapabilities.take(limit),
        )
    }

    private fun capabilityComparator(): Comparator<Map<String, Any?>> =
        compareByDescending<Map<String, Any?>> { numberDouble(it["score"]) }
            .thenByDescending { numberDouble(it["page_similarity"]) }
            .thenBy { it["function_id"]?.toString().orEmpty() }
            .thenBy { intArg(it["start_step_index"], defaultValue = 0) }

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
        segment: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val startStepIndex = intArg(
            segment["start_step_index"],
            segment["startStepIndex"],
            defaultValue = 0,
        )
        val functionSteps = stepSummaries(spec)
        val segmentSteps = listArg(segment["step_summaries"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }
        val effectiveSteps = if (capabilityType == "function_segment") {
            segmentSteps.ifEmpty { functionSteps.drop(startStepIndex) }
        } else {
            functionSteps
        }
        val call = linkedMapOf<String, Any?>(
            "tool" to "call_tool",
            "function_id" to functionId,
            "arguments" to emptyMap<String, Any?>(),
        ).apply {
            if (capabilityType == "function_segment") {
                put("start_step_index", startStepIndex)
            }
        }
        return linkedMapOf(
            "capability_type" to capabilityType,
            "recall_scope" to if (capabilityType == "function_segment") "udeg_node_segment" else "udeg_node",
            "function_id" to functionId,
            "name" to firstNonBlank(segment["name"], nodeCapability["name"], spec["name"], functionId),
            "description" to firstNonBlank(
                segment["description"],
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
            "start_step_index" to startStepIndex.takeIf { capabilityType == "function_segment" },
            "remaining_step_count" to effectiveSteps.size,
            "matched_boundary" to segment["matched_boundary"],
            "matched_step_index" to segment["matched_step_index"],
            "execution_scope" to if (capabilityType == "function_segment") "function_suffix" else "function",
            "call" to call,
            "step_summaries" to effectiveSteps,
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "oob_udeg_node_capability",
        ).filterValues { it != null }
    }

    private fun segmentPackageMatchMultiplier(queryPackage: String, boundaryPackage: String): Float {
        if (queryPackage.isBlank() || boundaryPackage.isBlank()) return 1.0f
        if (queryPackage == boundaryPackage) return 1.0f
        return SEGMENT_PACKAGE_MISMATCH_MULTIPLIER
    }

    private fun segmentMatchReason(
        recalledFunction: RankedFunction,
        boundary: String,
        packageMultiplier: Float,
        rawPageScore: Float,
    ): String {
        val suffix = if (packageMultiplier >= 1.0f) {
            ""
        } else {
            ";package_soft_mismatch;raw_page_similarity=${roundScore(rawPageScore.toDouble())}"
        }
        val prefix = when (recalledFunction.recallScope) {
            "udeg_node" -> "udeg_node_function"
            "udeg_node_segment" -> "udeg_node_segment"
            else -> "function_boundary_page_match"
        }
        return "${prefix}_${recalledFunction.reason};segment_$boundary$suffix"
    }

    private fun nodeSegmentBoundaries(
        recalledFunctions: List<RankedFunction>,
        nodeMatches: List<OobUdegNodeStore.RecallMatch>,
    ): List<NodeSegmentBoundary> {
        val byKey = linkedMapOf<String, NodeSegmentBoundary>()
        recalledFunctions.forEach { rankedFunction ->
            addNodeSegmentBoundaries(
                output = byKey,
                node = rankedFunction.node,
                pageScore = rankedFunction.pageScore,
            )
        }
        nodeMatches.forEach { nodeMatch ->
            addNodeSegmentBoundaries(
                output = byKey,
                node = nodeMatch.toMap(),
                pageScore = nodeMatch.pageSimilarity.toDouble(),
            )
        }
        return byKey.values.toList()
    }

    private fun addNodeSegmentBoundaries(
        output: MutableMap<String, NodeSegmentBoundary>,
        node: Map<String, Any?>,
        pageScore: Double,
    ) {
        val segments = OobUdegNodeStore.segmentSummaries(node)
        segments.forEach { segment ->
            val functionId = firstNonBlank(segment["function_id"])
            val startStepIndex = intArg(
                segment["start_step_index"],
                segment["startStepIndex"],
                defaultValue = 0,
            )
            if (functionId.isBlank() || startStepIndex <= 0) return@forEach
            val boundary = NodeSegmentBoundary(
                functionId = functionId,
                startStepIndex = startStepIndex,
                matchedStepIndex = intArg(
                    segment["matched_step_index"],
                    segment["matchedStepIndex"],
                    defaultValue = (startStepIndex - 1).coerceAtLeast(0),
                ),
                matchedBoundary = firstNonBlank(segment["matched_boundary"], segment["matchedBoundary"])
                    .ifBlank { "node_segment" },
                pageScore = pageScore,
                node = node,
                segment = segment,
            )
            val key = "${boundary.functionId}:${boundary.startStepIndex}"
            val existing = output[key]
            if (existing == null || boundary.pageScore > existing.pageScore) {
                output[key] = boundary
            }
        }
    }

    private fun segmentBoundaryContexts(
        step: Map<String, Any?>,
        stepIndex: Int,
    ): List<SegmentBoundaryContext> {
        val sourceContext = mapArg(step["source_context"])
            .ifEmpty { mapArg(mapArg(step["args"])["source_context"]) }
        if (sourceContext.isEmpty()) return emptyList()
        val output = mutableListOf<SegmentBoundaryContext>()
        val srcCtx = mapArg(sourceContext["src_ctx"])
        val srcPage = firstNonBlank(
            srcCtx["page"],
            srcCtx["xml"],
            srcCtx["observation_xml"],
            srcCtx["observationXml"],
        )
        if (srcPage.isNotBlank()) {
            output += SegmentBoundaryContext(
                boundary = "src_ctx",
                pageXml = srcPage,
                packageName = firstNonBlank(srcCtx["package_name"], srcCtx["packageName"]),
                matchedStepIndex = stepIndex,
                startStepIndex = stepIndex,
            )
        }
        val dstCtx = mapArg(sourceContext["dst_ctx"])
        val dstPage = firstNonBlank(
            dstCtx["page"],
            dstCtx["xml"],
            dstCtx["observation_xml"],
            dstCtx["observationXml"],
        )
        if (dstPage.isNotBlank()) {
            output += SegmentBoundaryContext(
                boundary = "dst_ctx",
                pageXml = dstPage,
                packageName = firstNonBlank(dstCtx["package_name"], dstCtx["packageName"]),
                matchedStepIndex = stepIndex,
                startStepIndex = stepIndex + 1,
            )
        }
        return output
    }

    private fun sliceMaterializedSpec(
        materializedSpec: Map<String, Any?>,
        startStepIndex: Int,
    ): Map<String, Any?>? {
        if (startStepIndex <= 0) return materializedSpec
        val execution = mapArg(materializedSpec["execution"])
        val steps = materializedSteps(materializedSpec)
        if (startStepIndex >= steps.size) return null
        val slicedSteps = steps.drop(startStepIndex).mapIndexed { offset, step ->
            linkedMapOf<String, Any?>().apply {
                putAll(step)
                putIfAbsent("original_index", step["index"] ?: (startStepIndex + offset))
                put("index", offset)
            }
        }
        val slicedExecution = linkedMapOf<String, Any?>().apply {
            putAll(execution)
            put("steps", slicedSteps)
            put("step_count", slicedSteps.size)
            put("omniflow_step_count", slicedSteps.count { it["executor"] == "omniflow" })
            put("agent_step_count", slicedSteps.count { it["executor"] == "agent" })
            put("requires_agent_fallback", slicedSteps.any { it["executor"] == "agent" })
            val capabilities = mapArg(execution["capabilities"])
            if (capabilities.isNotEmpty()) {
                put(
                    "capabilities",
                    linkedMapOf<String, Any?>().apply {
                        putAll(capabilities)
                        put("scriptable_step_count", slicedSteps.count { it["scriptable"] == true })
                        put("model_free_step_count", slicedSteps.count { it["model_free"] == true })
                        put("omniflow_step_count", slicedSteps.count { it["executor"] == "omniflow" })
                        put("agent_step_count", slicedSteps.count { it["executor"] == "agent" })
                        put("requires_agent_fallback", slicedSteps.any { it["executor"] == "agent" })
                    }
                )
            }
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(materializedSpec)
            put("execution", slicedExecution)
            put("segment_execution", segmentExecutionMeta(materializedSpec, startStepIndex))
        }
    }

    private fun segmentExecutionMeta(
        materializedSpec: Map<String, Any?>,
        startStepIndex: Int,
    ): Map<String, Any?> {
        val originalStepCount = materializedSteps(materializedSpec).size
        val remainingStepCount = (originalStepCount - startStepIndex).coerceAtLeast(0)
        return linkedMapOf(
            "execution_scope" to "function_suffix",
            "start_step_index" to startStepIndex,
            "original_step_count" to originalStepCount,
            "remaining_step_count" to remainingStepCount,
        )
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
            put("segment_hit", compactRecallCandidate(payload["segment_hit"]))
            put(
                "candidates",
                listArg(payload["candidates"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "segment_candidates",
                listArg(payload["segment_candidates"]).mapNotNull { compactRecallCandidate(it) }
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
                "node_segment_capabilities",
                listArg(payload["node_segment_capabilities"]).mapNotNull { compactRecallCandidate(it) }
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
            put("segment_count", payload["segment_count"])
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
            "matched_boundary" to candidate["matched_boundary"],
            "matched_step_index" to candidate["matched_step_index"],
            "start_step_index" to candidate["start_step_index"],
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
            "segment_count" to node["segment_count"],
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
            "attached_segment_count" to listArg(context["attached_segments"]).size,
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
            "segment_count" to context["segment_count"],
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

    private fun SegmentMatch.toMap(): Map<String, Any?> =
        linkedMapOf(
            "capability_type" to "function_segment",
            "function_id" to functionId,
            "description" to (spec["description"] ?: spec["name"] ?: functionId),
            "name" to spec["name"],
            "inputSchema" to inputSchema(spec),
            "score" to score,
            "reason" to reason,
            "text_score" to roundScore(textScore),
            "page_similarity" to roundScore(pageScore),
            "strict_direct_hit" to (
                score >= DIRECT_HIT_SCORE &&
                    pageScore >= DIRECT_HIT_SCORE &&
                    textScore >= DIRECT_HIT_SCORE
                ),
            "udeg_node" to node.takeIf { it.isNotEmpty() },
            "node_skill_context" to node["node_skill_context"],
            "recall_scope" to recallScope,
            "matched_boundary" to matchedBoundary,
            "matched_step_index" to matchedStepIndex,
            "start_step_index" to startStepIndex,
            "remaining_step_count" to remainingStepCount,
            "requires_arguments" to !noArgumentFunction,
            "execution_scope" to "function_suffix",
            "call" to linkedMapOf(
                "tool" to "call_tool",
                "function_id" to functionId,
                "arguments" to emptyMap<String, Any?>(),
                "start_step_index" to startStepIndex,
            ),
            "step_summaries" to stepSummaries,
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "oob_page_vector_segment_match",
        )

    private fun rankFunction(
        spec: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): RankedFunction? {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        if (functionId.isEmpty()) return null
        val scored = scoreFunctionText(spec, goal, currentPackage)
        if (scored.score < MIN_RECALL_SCORE) return null
        return RankedFunction(
            spec = spec,
            functionId = functionId,
            score = roundScore(scored.score),
            reason = "page_vector_boundary_candidate;${scored.reason}",
            textScore = scored.score,
            recallScope = "function_boundary_page_match",
        )
    }

    private fun rankNodeSegmentFunction(
        spec: Map<String, Any?>,
        nodeSegment: NodeSegmentBoundary,
        goal: String,
        currentPackage: String,
    ): RankedFunction? {
        val scored = scoreNodeSegmentText(
            spec = spec,
            segment = nodeSegment.segment,
            goal = goal,
            currentPackage = currentPackage,
        )
        if (scored.score < MIN_RECALL_SCORE) return null
        return RankedFunction(
            spec = spec,
            functionId = nodeSegment.functionId,
            score = roundScore(scored.score),
            reason = "udeg_node_attached_segment;${scored.reason}",
            textScore = scored.score,
            pageScore = nodeSegment.pageScore,
            node = nodeSegment.node,
            recallScope = "udeg_node_segment",
        )
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

    private fun scoreNodeSegmentText(
        spec: Map<String, Any?>,
        segment: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): FunctionTextScore {
        val base = scoreFunctionText(spec, goal, currentPackage)
        val segmentCorpus = listOf(
            segment["function_id"],
            segment["name"],
            segment["description"],
            segment["matched_boundary"],
            listArg(segment["step_summaries"]).joinToString(" ") { raw ->
                val step = mapArg(raw)
                listOf(step["title"], step["tool"], step["id"])
                    .joinToString(" ")
            },
        ).joinToString(" ").trim()
        val segmentScore = if (goal.isBlank() || segmentCorpus.isBlank()) {
            0.0
        } else {
            tokenOverlapScore(goal, segmentCorpus)
        }
        return if (segmentScore > base.score) {
            FunctionTextScore(segmentScore, "segment_text_match")
        } else {
            base
        }
    }

    private fun scoreFunctionText(
        spec: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): FunctionTextScore {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
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
        return linkedMapOf<String, Any?>(
            "function_id" to spec["function_id"],
            "description" to (spec["description"] ?: spec["name"] ?: spec["function_id"]),
            "name" to spec["name"],
            "inputSchema" to inputSchema(spec),
            "score" to score,
            "reason" to reason,
            "step_count" to (execution["step_count"] ?: listArg(execution["steps"]).size),
            "requires_agent_fallback" to execution["requires_agent_fallback"],
            "step_summaries" to stepSummaries(spec),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local"
        ).apply { putAll(extras) }
    }

    private fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mapArg(spec["execution"])
        return listArg(execution["steps"]).mapIndexedNotNull { index, rawStep ->
            val step = mapArg(rawStep).takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            linkedMapOf(
                "index" to index,
                "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"]),
                "kind" to step["kind"],
                "tool" to firstNonBlank(
                    step["omniflow_action"],
                    step["local_action"],
                    step["callable_tool"],
                    step["tool"],
                )
            )
        }
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
            action == "finished" -> {
                decision = "allow"
                risk = "low"
                reason = "finished is a terminal marker"
                requiresRoot = false
            }
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
            RunLogReplayPolicy.isOmniflowExecutionTool(action) -> {
                decision = "allow"
                risk = if (RunLogReplayPolicy.isOmniflowFunctionTool(action)) "medium" else "low"
                reason = "$action is handled by OOB native OmniFlow execution"
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

    private fun sanitizedGuardArgs(value: Any?): Any? {
        return when (value) {
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

    private fun longArg(vararg values: Any?, defaultValue: Long): Long {
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

    private data class SegmentBoundaryContext(
        val boundary: String,
        val pageXml: String,
        val packageName: String,
        val matchedStepIndex: Int,
        val startStepIndex: Int,
    )

    private data class NodeSegmentBoundary(
        val functionId: String,
        val startStepIndex: Int,
        val matchedStepIndex: Int,
        val matchedBoundary: String,
        val pageScore: Double,
        val node: Map<String, Any?>,
        val segment: Map<String, Any?>,
    )

    private data class SegmentMatch(
        val spec: Map<String, Any?>,
        val functionId: String,
        val score: Double,
        val reason: String,
        val textScore: Double,
        val pageScore: Double,
        val node: Map<String, Any?>,
        val recallScope: String,
        val matchedStepIndex: Int,
        val matchedBoundary: String,
        val startStepIndex: Int,
        val remainingStepCount: Int,
        val stepSummaries: List<Map<String, Any?>>,
        val noArgumentFunction: Boolean,
    )

    private data class SegmentRecallResult(
        val matches: List<SegmentMatch>,
        val scannedFunctionCount: Int,
        val textEligibleFunctionCount: Int,
        val boundaryCount: Int,
        val boundaryPageHitCount: Int,
    )

    private data class NodeCapabilityRanking(
        val functions: List<RankedFunction>,
        val capabilities: List<Map<String, Any?>>,
        val functionCapabilities: List<Map<String, Any?>>,
        val segmentCapabilities: List<Map<String, Any?>>,
    )

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
                "segment_match_ms",
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
        private const val SEGMENT_PACKAGE_MISMATCH_MULTIPLIER = 0.82f
        private const val MAX_SEGMENT_FUNCTION_SCAN = 500
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
