package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime

data class VlmRecallGuidance(
    val decision: String,
    val guidance: String,
    val payload: Map<String, Any?> = emptyMap(),
    val directHitFunctionId: String? = null,
)

/**
 * Builds online VLM guidance from OOB-native OmniFlow recall.
 *
 * VLM still observes the live screen and emits concrete actions for recall
 * candidates. Recall defaults to UDEG node skill context and optional Function
 * candidates; direct local execution is only used when the caller explicitly
 * enables auto-execution and the recall payload contains a strict hit.
 */
object VlmRecallGuidanceBuilder {
    fun build(
        context: Context,
        goal: String,
        targetPackageName: String?,
        currentPackageName: String? = null,
        currentXml: String? = null,
        k: Int = DEFAULT_RECALL_COUNT,
        allowDirectExecutionDecision: Boolean = false,
    ): VlmRecallGuidance {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isEmpty()) return VlmRecallGuidance(decision = "miss", guidance = "")
        val currentPackage = firstNonBlank(
            currentPackageName,
            runCatching { OmniflowActionRuntime.backend.currentPackageName() }.getOrNull(),
            targetPackageName,
        )
        val payload = runCatching {
            OobOmniFlowToolkitService(context).recall(
                linkedMapOf<String, Any?>(
                "goal" to normalizedGoal,
                "current_package" to currentPackage,
                "k" to k,
                "decision_mode" to if (allowDirectExecutionDecision) {
                    "auto_execute"
                } else {
                    "context_only"
                },
            ).apply {
                    currentXml?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        put("current_xml", it)
                    }
                }
            )
        }.getOrElse { error ->
            return VlmRecallGuidance(
                decision = "error",
                guidance = "",
                payload = linkedMapOf("success" to false, "error_message" to error.message.orEmpty()),
            )
        }
        val agentPayload = agentSafePayload(payload)
        return fromAgentPayload(
            payload = agentPayload,
            allowDirectExecutionDecision = allowDirectExecutionDecision,
        )
    }

    internal fun fromAgentPayload(
        payload: Map<String, Any?>,
        allowDirectExecutionDecision: Boolean = false,
    ): VlmRecallGuidance {
        return VlmRecallGuidance(
            decision = payload["decision"]?.toString()?.trim().orEmpty().ifBlank { "miss" },
            guidance = renderGuidance(payload),
            payload = payload,
            directHitFunctionId = directHitFunctionId(payload).takeIf { allowDirectExecutionDecision },
        )
    }

    internal fun agentSafePayload(payload: Map<String, Any?>): Map<String, Any?> =
        sanitizeMapForAgent(payload)

    internal fun directHitFunctionId(payload: Map<String, Any?>): String? {
        if (payload["success"] != true) return null
        val decision = payload["decision"]?.toString()?.trim().orEmpty()
        if (decision != "hit") return null
        val source = mapArg(payload["hit"])
        if (!hasStrictDirectHitEvidence(source)) return null
        return source["function_id"]
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    internal fun renderGuidance(payload: Map<String, Any?>): String {
        if (payload["success"] != true) return ""
        val decision = payload["decision"]?.toString()?.trim().orEmpty()
        if (decision == "miss" || decision.isBlank()) return ""

        val decisionPolicy = mapArg(payload["decision_policy"])
        val directDecision = isDirectExecutionRequested(decision, decisionPolicy)
        val directCandidatePayload = hasDirectCandidatePayload(decision, payload)
        val nodeCandidates = listArg(payload["node_candidates"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }.take(MAX_GUIDANCE_CANDIDATES)
        val anchoredContext = nodeCandidates.isNotEmpty() || directDecision || directCandidatePayload
        val candidates = if (anchoredContext) {
            candidateList(payload).take(MAX_GUIDANCE_CANDIDATES)
        } else {
            emptyList()
        }
        if (!directDecision && nodeCandidates.isEmpty() && !directCandidatePayload) return ""
        if (candidates.isEmpty() && nodeCandidates.isEmpty()) return ""

        return buildString {
            appendLine("OmniFlow UDEG node skill-like decision context:")
            appendLine("path=${OobUdegNodeStore.UDEG_DECISION_PATH}")
            appendLine("decision=$decision")
            appendLine(functionExecutionPolicyLine(directDecision))
            if (decisionPolicy.isNotEmpty()) {
                val mode = firstNonBlank(decisionPolicy["mode"])
                val requiresDecision = firstNonBlank(decisionPolicy["requires_vlm_or_tool_decision"])
                appendLine("decision_policy: mode=$mode requires_vlm_or_tool_decision=$requiresDecision")
            }
            nodeCandidates.forEachIndexed { index, node ->
                val nodeId = node["node_id"]?.toString()?.trim().orEmpty()
                val score = node["page_similarity"]?.toString()?.trim().orEmpty()
                val nodeSkillContext = mapArg(node["node_skill_context"])
                val skill = mapArg(node["skill"])
                val decisionContext = mapArg(nodeSkillContext["decision_context"])
                    .ifEmpty { mapArg(node["decision_context"]) }
                val decisionPath = firstNonBlank(
                    nodeSkillContext["decision_path"],
                    decisionContext["decision_path"],
                    skill["decision_path"],
                    payload["decision_path"],
                )
                val guidance = skill["decision_guidance"]?.toString()?.trim().orEmpty()
                    .take(MAX_DESCRIPTION_CHARS)
                appendLine("node ${index + 1}: node_id=$nodeId page_similarity=$score")
                if (decisionPath.isNotBlank()) {
                    appendLine("   node_decision_path: $decisionPath")
                }
                renderDecisionContext(decisionContext).takeIf { it.isNotBlank() }?.let {
                    appendLine("   node_decision_context: $it")
                }
                renderNodeSkillContext(nodeSkillContext).takeIf { it.isNotBlank() }?.let {
                    appendLine("   node_skill_context: $it")
                }
                if (guidance.isNotBlank()) {
                    appendLine("   node_skill_decision_context: $guidance")
                }
            }
            candidates.forEachIndexed { index, candidate ->
                val functionId = candidate["function_id"]?.toString()?.trim().orEmpty()
                val score = candidate["score"]?.toString()?.trim().orEmpty()
                val description = firstNonBlank(candidate["description"], candidate["name"], functionId)
                    .take(MAX_DESCRIPTION_CHARS)
                appendLine("${index + 1}. oob_function_run function_id=$functionId score=$score description=$description")
                renderFunctionProfile(candidate).takeIf { it.isNotBlank() }?.let {
                    appendLine("   function_profile: $it")
                }
                renderArgumentPolicy(candidate).takeIf { it.isNotBlank() }?.let {
                    appendLine("   argument_policy: $it")
                }
            }
            val capabilityCandidates = listArg(payload["capability_candidates"])
                .mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
                .take(MAX_GUIDANCE_CANDIDATES)
            capabilityCandidates.forEachIndexed { index, capability ->
                val functionId = capability["function_id"]?.toString()?.trim().orEmpty()
                val type = capability["capability_type"]?.toString()?.trim().orEmpty()
                val scope = capability["recall_scope"]?.toString()?.trim().orEmpty()
                val score = capability["score"]?.toString()?.trim().orEmpty()
                val description = firstNonBlank(capability["description"], capability["name"], functionId)
                    .take(MAX_DESCRIPTION_CHARS)
                appendLine(
                    "capability ${index + 1}: type=$type scope=$scope oob_function_run function_id=$functionId " +
                        "score=$score description=$description"
                )
                renderFunctionProfile(capability).takeIf { it.isNotBlank() }?.let {
                    appendLine("   capability_profile: $it")
                }
                renderArgumentPolicy(capability).takeIf { it.isNotBlank() }?.let {
                    appendLine("   argument_policy: $it")
                }
            }
        }.trim()
    }

    private fun candidateList(payload: Map<String, Any?>): List<Map<String, Any?>> {
        val hit = mapArg(payload["hit"])
        if (hit.isNotEmpty()) return listOf(hit)
        return listArg(payload["candidates"]).mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
    }

    private fun renderFunctionProfile(candidate: Map<String, Any?>): String {
        val profile = mapArg(candidate["function_profile"])
        if (profile.isEmpty()) return ""
        val purpose = firstNonBlank(profile["purpose"]).take(MAX_DESCRIPTION_CHARS)
        val useWhen = firstNonBlank(profile["use_when"]).take(MAX_DESCRIPTION_CHARS)
        val success = firstNonBlank(profile["success_signal"]).take(MAX_DESCRIPTION_CHARS)
        val pkg = firstNonBlank(profile["package_name"])
        return listOf(
            "purpose=$purpose".takeIf { purpose.isNotBlank() },
            "use_when=$useWhen".takeIf { useWhen.isNotBlank() },
            "success_signal=$success".takeIf { success.isNotBlank() },
            "package=$pkg".takeIf { pkg.isNotBlank() },
        ).filterNotNull().joinToString(" ")
    }

    private fun renderArgumentPolicy(candidate: Map<String, Any?>): String {
        val requiresArguments = requiresArguments(candidate)
        val fillPolicy = firstNonBlank(candidate["argument_fill_policy"], candidate["argumentFillPolicy"])
        val schema = mapArg(candidate["inputSchema"])
        val properties = mapArg(schema["properties"]).keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .joinToString(",")
        return listOf(
            "requires_arguments=$requiresArguments",
            "fill_policy=$fillPolicy".takeIf { fillPolicy.isNotBlank() },
            "params=$properties".takeIf { properties.isNotBlank() },
        ).filterNotNull().joinToString(" ")
    }

    private fun renderDecisionContext(decisionContext: Map<String, Any?>): String {
        if (decisionContext.isEmpty()) return ""
        val role = firstNonBlank(decisionContext["role"])
        val entryPolicy = firstNonBlank(decisionContext["entry_policy"])
        val skillId = firstNonBlank(decisionContext["skill_id"])
        return listOf(
            "role=$role".takeIf { role.isNotBlank() },
            "entry_policy=$entryPolicy".takeIf { entryPolicy.isNotBlank() },
            "skill_id=$skillId".takeIf { skillId.isNotBlank() },
        ).filterNotNull().joinToString(" ")
    }

    private fun renderNodeSkillContext(nodeSkillContext: Map<String, Any?>): String {
        if (nodeSkillContext.isEmpty()) return ""
        val role = firstNonBlank(nodeSkillContext["role"])
        val contextKind = firstNonBlank(nodeSkillContext["context_kind"])
        val entryPolicy = firstNonBlank(nodeSkillContext["entry_policy"])
        val pageMatch = mapArg(nodeSkillContext["page_match"])
        val nodeId = firstNonBlank(pageMatch["node_id"], mapArg(nodeSkillContext["udeg_node"])["node_id"])
        val pageSimilarity = firstNonBlank(pageMatch["page_similarity"])
        return listOf(
            "role=$role".takeIf { role.isNotBlank() },
            "context_kind=$contextKind".takeIf { contextKind.isNotBlank() },
            "entry_policy=$entryPolicy".takeIf { entryPolicy.isNotBlank() },
            "node_id=$nodeId".takeIf { nodeId.isNotBlank() },
            "page_similarity=$pageSimilarity".takeIf { pageSimilarity.isNotBlank() },
        ).filterNotNull().joinToString(" ")
    }

    private fun functionExecutionPolicyLine(directDecision: Boolean): String =
        if (directDecision) {
            "function_execution_policy=direct_execution_requested_by_caller; parameterized_hits_may_be_called_by_agent_with_filled_arguments=true"
        } else {
            "function_execution_policy=optional_candidates_only; do_not_auto_execute=true; require_explicit_oob_function_run_selection=true; function_candidates_may_be_called_by_agent_with_filled_arguments=true"
        }

    private fun isDirectExecutionRequested(
        decision: String,
        decisionPolicy: Map<String, Any?>,
    ): Boolean {
        if (decision != "hit") return false
        if (boolArg(decisionPolicy["direct_hit_requested"], decisionPolicy["directHitRequested"])) return true
        val mode = firstNonBlank(
            decisionPolicy["mode"],
            decisionPolicy["execution_policy"],
            decisionPolicy["executionPolicy"],
        ).lowercase()
        return mode in setOf("direct_execution_allowed", "direct", "auto_execute", "auto-execute")
    }

    private fun hasDirectCandidatePayload(
        decision: String,
        payload: Map<String, Any?>,
    ): Boolean {
        return when (decision) {
            "hit" -> mapArg(payload["hit"]).isNotEmpty()
            else -> false
        }
    }

    private fun requiresArguments(payload: Map<String, Any?>): Boolean {
        val raw = payload["requires_arguments"] ?: payload["requiresArguments"]
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().lowercase() in setOf("true", "1", "yes")
            else -> false
        }
    }

    private fun boolArg(vararg values: Any?): Boolean {
        values.forEach { value ->
            when (value) {
                is Boolean -> return value
                is Number -> return value.toInt() != 0
                is String -> {
                    val normalized = value.trim().lowercase()
                    if (normalized in setOf("true", "1", "yes", "y")) return true
                    if (normalized in setOf("false", "0", "no", "n")) return false
                }
            }
        }
        return false
    }

    private fun hasStrictDirectHitEvidence(payload: Map<String, Any?>): Boolean {
        if (payload.isEmpty()) return false
        val score = doubleArg(payload["score"])
        val pageSimilarity = doubleArg(payload["page_similarity"], payload["pageSimilarity"])
        val textScore = doubleArg(payload["text_score"], payload["textScore"])
        if (score < DIRECT_HIT_MIN_SCORE) return false
        if (pageSimilarity < DIRECT_HIT_MIN_PAGE_SCORE) return false
        if (textScore < DIRECT_HIT_MIN_TEXT_SCORE) return false
        if (requiresArguments(payload)) return false
        return true
    }

    private fun doubleArg(vararg values: Any?): Double {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return 0.0
    }

    private fun sanitizeForAgent(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> sanitizeMapForAgent(value)
            is List<*> -> value.map(::sanitizeForAgent)
            is Array<*> -> value.map(::sanitizeForAgent)
            else -> value
        }
    }

    private fun sanitizeMapForAgent(value: Map<*, *>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (rawKey, rawItem) ->
                val key = rawKey?.toString() ?: return@forEach
                if (key in AGENT_HIDDEN_RECALL_KEYS) return@forEach
                put(key, sanitizeForAgent(rawItem))
            }
        }

    private const val DEFAULT_RECALL_COUNT = 3
    private const val MAX_GUIDANCE_CANDIDATES = 2
    private const val MAX_DESCRIPTION_CHARS = 96
    private const val DIRECT_HIT_MIN_SCORE = 0.92
    private const val DIRECT_HIT_MIN_PAGE_SCORE = 0.90
    private const val DIRECT_HIT_MIN_TEXT_SCORE = 0.85
    private val AGENT_HIDDEN_RECALL_KEYS = setOf(
        "timing",
        "duration_ms",
        "started_at_ms",
        "finished_at_ms",
        "phase_ms",
        "runner_duration_ms",
    )
}
