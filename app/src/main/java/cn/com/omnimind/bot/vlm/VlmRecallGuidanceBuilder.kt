package cn.com.omnimind.bot.vlm

import android.content.Context
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
 * candidates. A no-argument direct hit may be executed by the coordinator before
 * starting VLM, with VLM fallback if local replay cannot complete.
 */
object VlmRecallGuidanceBuilder {
    fun build(
        context: Context,
        goal: String,
        targetPackageName: String?,
        currentPackageName: String? = null,
        k: Int = DEFAULT_RECALL_COUNT,
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
                linkedMapOf(
                    "goal" to normalizedGoal,
                    "current_package" to currentPackage,
                    "k" to k,
                )
            )
        }.getOrElse { error ->
            return VlmRecallGuidance(
                decision = "error",
                guidance = "",
                payload = linkedMapOf("success" to false, "error_message" to error.message.orEmpty()),
            )
        }
        val agentPayload = agentSafePayload(payload)
        return VlmRecallGuidance(
            decision = agentPayload["decision"]?.toString()?.trim().orEmpty().ifBlank { "miss" },
            guidance = renderGuidance(agentPayload),
            payload = agentPayload,
            directHitFunctionId = directHitFunctionId(agentPayload),
        )
    }

    internal fun agentSafePayload(payload: Map<String, Any?>): Map<String, Any?> =
        sanitizeMapForAgent(payload)

    internal fun directHitFunctionId(payload: Map<String, Any?>): String? {
        if (payload["success"] != true) return null
        if (payload["decision"]?.toString()?.trim() != "hit") return null
        return mapArg(payload["hit"])["function_id"]
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    internal fun renderGuidance(payload: Map<String, Any?>): String {
        if (payload["success"] != true) return ""
        val decision = payload["decision"]?.toString()?.trim().orEmpty()
        if (decision == "miss" || decision.isBlank()) return ""

        val candidates = candidateList(payload)
            .take(MAX_GUIDANCE_CANDIDATES)
        val segmentCandidates = segmentCandidateList(payload)
            .take(MAX_GUIDANCE_CANDIDATES)
        val nodeCandidates = listArg(payload["node_candidates"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }.take(MAX_GUIDANCE_CANDIDATES)
        if (candidates.isEmpty() && segmentCandidates.isEmpty() && nodeCandidates.isEmpty()) return ""

        return buildString {
            appendLine("OmniFlow UDEG node skill-like decision context:")
            appendLine("path=${OobUdegNodeStore.UDEG_DECISION_PATH}")
            appendLine("decision=$decision")
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
                val body = skill["body"]?.toString()?.trim().orEmpty()
                    .take(MAX_SKILL_BODY_CHARS)
                if (body.isNotBlank()) {
                    appendLine("   node_skill_body:")
                    appendIndented(body, "      ")
                }
            }
            candidates.forEachIndexed { index, candidate ->
                val functionId = candidate["function_id"]?.toString()?.trim().orEmpty()
                val score = candidate["score"]?.toString()?.trim().orEmpty()
                val description = firstNonBlank(candidate["description"], candidate["name"], functionId)
                    .take(MAX_DESCRIPTION_CHARS)
                appendLine("${index + 1}. function_id=$functionId score=$score description=$description")
                renderStepSummaries(candidate).take(MAX_STEP_SUMMARIES).forEach { summary ->
                    appendLine("   step: $summary")
                }
            }
            segmentCandidates.forEachIndexed { index, candidate ->
                val functionId = candidate["function_id"]?.toString()?.trim().orEmpty()
                val score = candidate["score"]?.toString()?.trim().orEmpty()
                val pageSimilarity = candidate["page_similarity"]?.toString()?.trim().orEmpty()
                val startStepIndex = candidate["start_step_index"]?.toString()?.trim().orEmpty()
                val remainingStepCount = candidate["remaining_step_count"]?.toString()?.trim().orEmpty()
                val matchedBoundary = candidate["matched_boundary"]?.toString()?.trim().orEmpty()
                val description = firstNonBlank(candidate["description"], candidate["name"], functionId)
                    .take(MAX_DESCRIPTION_CHARS)
                appendLine(
                    "segment ${index + 1}: function_id=$functionId score=$score " +
                        "page_similarity=$pageSimilarity start_step_index=$startStepIndex " +
                        "remaining_step_count=$remainingStepCount matched_boundary=$matchedBoundary " +
                        "description=$description"
                )
                appendLine("   call: call_tool function_id=$functionId start_step_index=$startStepIndex")
                renderStepSummaries(candidate).take(MAX_STEP_SUMMARIES).forEach { summary ->
                    appendLine("   remaining_step: $summary")
                }
            }
        }.trim()
    }

    private fun candidateList(payload: Map<String, Any?>): List<Map<String, Any?>> {
        val hit = mapArg(payload["hit"])
        if (hit.isNotEmpty()) return listOf(hit)
        return listArg(payload["candidates"]).mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
    }

    private fun segmentCandidateList(payload: Map<String, Any?>): List<Map<String, Any?>> {
        val hit = mapArg(payload["segment_hit"])
        if (hit.isNotEmpty()) return listOf(hit)
        return listArg(payload["segment_candidates"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }
    }

    private fun renderStepSummaries(candidate: Map<String, Any?>): List<String> {
        return listArg(candidate["step_summaries"]).mapIndexedNotNull { index, raw ->
            val step = mapArg(raw)
            if (step.isEmpty()) return@mapIndexedNotNull null
            val tool = firstNonBlank(step["tool"], step["omniflow_action"], step["callable_tool"], step["kind"])
            val title = firstNonBlank(step["title"], tool)
            if (title.isBlank() && tool.isBlank()) return@mapIndexedNotNull null
            "${index + 1}. ${tool.ifBlank { "step" }}: ${title.take(MAX_STEP_TITLE_CHARS)}"
        }
    }

    private fun StringBuilder.appendIndented(text: String, prefix: String) {
        text.lineSequence()
            .take(MAX_SKILL_BODY_LINES)
            .forEach { line -> appendLine(prefix + line) }
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

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun mapArg(value: Any?): Map<String, Any?> {
        return when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }
    }

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
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
    private const val MAX_GUIDANCE_CANDIDATES = 3
    private const val MAX_STEP_SUMMARIES = 5
    private const val MAX_DESCRIPTION_CHARS = 120
    private const val MAX_STEP_TITLE_CHARS = 120
    private const val MAX_SKILL_BODY_CHARS = 900
    private const val MAX_SKILL_BODY_LINES = 18
    private val AGENT_HIDDEN_RECALL_KEYS = setOf(
        "timing",
        "duration_ms",
        "started_at_ms",
        "finished_at_ms",
        "phase_ms",
        "runner_duration_ms",
    )
}
