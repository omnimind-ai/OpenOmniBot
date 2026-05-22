package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
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
        return VlmRecallGuidance(
            decision = payload["decision"]?.toString()?.trim().orEmpty().ifBlank { "miss" },
            guidance = renderGuidance(payload),
            payload = payload,
            directHitFunctionId = directHitFunctionId(payload),
        )
    }

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
        val nodeCandidates = listArg(payload["node_candidates"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }.take(MAX_GUIDANCE_CANDIDATES)
        if (candidates.isEmpty() && nodeCandidates.isEmpty()) return ""

        return buildString {
            appendLine("OmniFlow UDEG recall context:")
            appendLine("decision=$decision")
            nodeCandidates.forEachIndexed { index, node ->
                val nodeId = node["node_id"]?.toString()?.trim().orEmpty()
                val score = node["page_similarity"]?.toString()?.trim().orEmpty()
                val skill = mapArg(node["skill"])
                val guidance = skill["decision_guidance"]?.toString()?.trim().orEmpty()
                    .take(MAX_DESCRIPTION_CHARS)
                appendLine("node ${index + 1}: node_id=$nodeId page_similarity=$score")
                if (guidance.isNotBlank()) appendLine("   skill: $guidance")
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
        }.trim()
    }

    private fun candidateList(payload: Map<String, Any?>): List<Map<String, Any?>> {
        val hit = mapArg(payload["hit"])
        if (hit.isNotEmpty()) return listOf(hit)
        return listArg(payload["candidates"]).mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }
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

    private const val DEFAULT_RECALL_COUNT = 3
    private const val MAX_GUIDANCE_CANDIDATES = 3
    private const val MAX_STEP_SUMMARIES = 5
    private const val MAX_DESCRIPTION_CHARS = 120
    private const val MAX_STEP_TITLE_CHARS = 120
}
