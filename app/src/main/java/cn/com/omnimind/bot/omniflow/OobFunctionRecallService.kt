package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.intArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import kotlin.math.roundToInt

/**
 * Owns OOB Function recall policy: current-page matching, UDEG capability
 * ranking, direct-hit decisions, and agent-facing compact payloads.
 */
class OobFunctionRecallService(
    private val context: Context,
    private val functionRepository: OobFunctionRepository,
) {
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
            "tool" to RunLogReplayPolicy.TOOL_CALL_TOOL,
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
            "has_agent_steps" to (
                candidate["has_agent_steps"]
                    ?: candidate["requires_agent_fallback"]
                ),
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
            "has_agent_steps" to (
                execution["has_agent_steps"]
                    ?: execution["requires_agent_fallback"]
                ),
            "step_summaries" to stepSummaries(spec),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local"
        ).apply { putAll(extras) }
    }

    private fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        OobFunctionSchemaBuilder.stepSummaries(spec)

    private fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> =
        OobFunctionSchemaBuilder.inputSchema(spec)

    private fun isNoArgumentFunction(spec: Map<String, Any?>): Boolean {
        val schema = inputSchema(spec)
        val required = listArg(schema["required"])
        val properties = mapArg(schema["properties"])
        return required.isEmpty() && properties.isEmpty()
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> =
        OobFunctionSchemaBuilder.materializedSteps(spec)

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

    private data class RankedFunction(
        val spec: Map<String, Any?>,
        val functionId: String,
        val score: Double,
        val reason: String,
        val textScore: Double = score,
        val pageScore: Double = 0.0,
        val node: Map<String, Any?> = emptyMap(),
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

    private companion object {
        private const val DIRECT_HIT_SCORE = 0.999
        private const val PAGE_MATCH_WEIGHT = 0.70
        private const val GOAL_MATCH_WEIGHT = 0.30
    }
}
