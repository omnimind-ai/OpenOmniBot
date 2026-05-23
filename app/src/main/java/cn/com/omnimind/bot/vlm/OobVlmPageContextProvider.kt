package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextProvider
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.bot.runlog.OobUdegNodeStore

class OobVlmPageContextProvider(
    context: Context,
) : VLMPageContextProvider {
    private val appContext = context.applicationContext

    override suspend fun enrich(request: VLMPageContextRequest): UIContext {
        val currentXml = request.currentXml?.trim().orEmpty()
        if (currentXml.isBlank()) return request.context
        val observed = OobUdegNodeStore(appContext).observePage(
            OobUdegNodeStore.ObservedPage(
                pageXml = currentXml,
                packageName = request.currentPackageName.orEmpty(),
                screenshotBase64 = request.screenshotBase64,
                goal = request.context.activeGoal().ifBlank { request.context.overallTask },
                stepIndex = request.stepIndex,
            )
        ) ?: return request.context
        val guidance = renderGuidance(observed)
        if (guidance.isBlank()) return request.context

        val pageSummary = listOf(request.context.currentPageSummary, guidance)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
            .take(MAX_CONTEXT_CHARS)
        return request.context.copy(
            currentPageSummary = pageSummary,
            firstStepGuidance = request.context.firstStepGuidance,
        )
    }

    private fun renderGuidance(observed: OobUdegNodeStore.PageObservationResult): String {
        val payload = observed.toMap()
        val nodeId = payload["node_id"]?.toString()?.trim().orEmpty()
        if (nodeId.isBlank()) return ""
        val pageAnalysis = mapArg(payload["page_analysis"])
        val summary = mapArg(pageAnalysis["summary"])
        val visibleTexts = listArg(summary["visible_texts"])
            .take(MAX_ITEMS)
            .joinToString(" / ") { it.toString() }
        val actionables = listArg(summary["actionables"])
            .take(MAX_ITEMS)
            .joinToString(" / ") { it.toString() }
        val hints = listArg(pageAnalysis["decision_hints"])
            .take(MAX_HINTS)
            .joinToString(" ")
        val functionIds = listArg(payload["function_ids"])
            .take(MAX_ITEMS)
            .joinToString(" / ") { it.toString() }

        return buildString {
            append("UDEG page-match context: path=")
            append(OobUdegNodeStore.UDEG_DECISION_PATH)
            append("; node_id=").append(nodeId)
            append("; similarity=").append(observed.pageSimilarity)
            append("; first_seen=").append(observed.firstSeen)
            firstNonBlank(summary["title"]).takeIf { it.isNotBlank() }?.let {
                append("\n页面标题: ").append(it)
            }
            firstNonBlank(summary["page_role"]).takeIf { it.isNotBlank() }?.let {
                append("\n页面类型: ").append(it)
            }
            if (visibleTexts.isNotBlank()) {
                append("\nUDEG可见文本: ").append(visibleTexts)
            }
            if (actionables.isNotBlank()) {
                append("\nUDEG可交互元素: ").append(actionables)
            }
            if (functionIds.isNotBlank()) {
                append("\nUDEG可复用Function: ").append(functionIds)
            }
            if (hints.isNotBlank()) {
                append("\nUDEG决策提示: ").append(hints)
            }
            append("\nUDEG观测来源: live screenshot/XML page match")
        }.take(MAX_GUIDANCE_CHARS)
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

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private companion object {
        private const val MAX_ITEMS = 8
        private const val MAX_HINTS = 3
        private const val MAX_GUIDANCE_CHARS = 1_400
        private const val MAX_CONTEXT_CHARS = 2_400
    }
}
