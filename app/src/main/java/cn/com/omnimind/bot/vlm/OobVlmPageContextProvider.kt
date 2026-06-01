package cn.com.omnimind.bot.vlm

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextProvider
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OobUdegNodeStore

class OobVlmPageContextProvider(
    context: Context,
) : VLMPageContextProvider {
    private val appContext = context.applicationContext

    override suspend fun enrich(request: VLMPageContextRequest): UIContext {
        val snapshot = request.snapshot
        val currentXml = (snapshot?.xml ?: request.currentXml)?.trim().orEmpty()
        if (currentXml.isBlank()) return request.context
        val pageMatchStartedAt = System.currentTimeMillis()
        val observed = OobUdegNodeStore(appContext).observePage(
            OobUdegNodeStore.ObservedPage(
                pageXml = currentXml,
                packageName = snapshot?.packageName ?: request.currentPackageName.orEmpty(),
                screenshotBase64 = snapshot?.screenshotBase64 ?: request.screenshotBase64,
                goal = request.context.activeGoal().ifBlank { request.context.overallTask },
                stepIndex = request.stepIndex,
            )
        ) ?: return request.context
        val pageMatchMs = System.currentTimeMillis() - pageMatchStartedAt
        val guidance = renderGuidance(observed)
        val diagnostics = buildDiagnostics(
            observed = observed,
            guidance = guidance,
            pageMatchMs = pageMatchMs,
            snapshotTimestampMs = snapshot?.capturedAtMs
        )
        val baseContext = request.context.copy(pageDiagnostics = diagnostics)
        if (guidance.isBlank()) return baseContext

        val pageSummary = listOf(baseContext.currentPageSummary, guidance)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
            .take(MAX_CONTEXT_CHARS)
        return baseContext.copy(
            currentPageSummary = pageSummary,
            firstStepGuidance = baseContext.firstStepGuidance,
        )
    }

    private fun renderGuidance(observed: OobUdegNodeStore.PageObservationResult): String {
        if (observed.firstSeen) return ""
        val payload = observed.toMap()
        val nodeId = payload["node_id"]?.toString()?.trim().orEmpty()
        if (nodeId.isBlank()) return ""
        val pageAnalysis = mapArg(payload["page_analysis"])
        val summary = mapArg(pageAnalysis["summary"])
        val nodeSkillContext = mapArg(payload["node_skill_context"])
        val skill = mapArg(nodeSkillContext["skill"])
        val decisionContext = mapArg(nodeSkillContext["decision_context"])
            .ifEmpty { mapArg(payload["decision_context"]) }
        val attachedFunctions = listArg(nodeSkillContext["attached_functions"])
            .mapNotNull { mapArg(it).takeIf(Map<String, Any?>::isNotEmpty) }
        val visibleTexts = listArg(summary["visible_texts"])
            .take(MAX_ITEMS)
            .joinToString(" / ") { it.toString() }
        val actionables = listArg(summary["actionables"])
            .take(MAX_ITEMS)
            .joinToString(" / ") { it.toString() }
        val hints = listArg(pageAnalysis["decision_hints"])
            .take(MAX_HINTS)
            .joinToString(" ")
        val decisionRules = listArg(skill["decision_rules"])
            .take(MAX_HINTS)
            .joinToString(" ") { it.toString() }
        val usageRules = listArg(decisionContext["usage"])
            .take(MAX_HINTS)
            .joinToString(" ") { it.toString() }

        return buildString {
            append("UDEG page skill context (current-turn page match; app-card-like decision context, not executable proof):")
            append("\npath=").append(OobUdegNodeStore.UDEG_DECISION_PATH)
            append("\nnode_id=").append(nodeId)
            append(" page_similarity=").append(observed.pageSimilarity)
            append(" first_seen=").append(observed.firstSeen)
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
            if (hints.isNotBlank()) {
                append("\nUDEG决策提示: ").append(hints)
            }
            if (decisionRules.isNotBlank()) {
                append("\nUDEG节点规则: ").append(decisionRules)
            }
            if (usageRules.isNotBlank()) {
                append("\nUDEG使用方式: ").append(usageRules)
            }
            appendCapabilitySection(
                title = "UDEG attached Functions (optional capabilities)",
                capabilities = attachedFunctions
            )
            append("\n约束: 这是当前页决策上下文；下一步动作仍必须基于本轮 live screenshot/XML/indexed evidence。")
            append("\n约束: attached Function 只是候选能力，不是完成证明；除非外层显式允许且严格命中，否则不要自动重放。")
            append("\nUDEG观测来源: live screenshot/XML page match")
        }.take(MAX_GUIDANCE_CHARS)
    }

    private fun buildDiagnostics(
        observed: OobUdegNodeStore.PageObservationResult,
        guidance: String,
        pageMatchMs: Long,
        snapshotTimestampMs: Long?,
    ): Map<String, String> {
        val payload = observed.toMap()
        val nodeId = payload["node_id"]?.toString()?.trim().orEmpty()
        return linkedMapOf<String, String>().apply {
            if (nodeId.isNotBlank()) put("node_id", nodeId)
            put("page_similarity", observed.pageSimilarity.toString())
            put("page_match_ms", pageMatchMs.coerceAtLeast(0L).toString())
            put("skill_context_chars", guidance.length.toString())
            put("udeg_first_seen", observed.firstSeen.toString())
            put("udeg_context_injected", guidance.isNotBlank().toString())
            snapshotTimestampMs?.let { put("snapshot_timestamp", it.toString()) }
        }
    }

    private fun StringBuilder.appendCapabilitySection(
        title: String,
        capabilities: List<Map<String, Any?>>
    ) {
        val rendered = capabilities
            .take(MAX_CAPABILITIES)
            .mapIndexedNotNull { index, capability ->
                val functionId = firstNonBlank(capability["function_id"])
                if (functionId.isBlank()) return@mapIndexedNotNull null
                val description = firstNonBlank(
                    capability["description"],
                    capability["name"],
                    functionId
                ).take(MAX_DESCRIPTION_CHARS)
                val steps = renderStepSummaries(capability)
                buildString {
                    append(index + 1).append(". function_id=").append(functionId)
                    if (description.isNotBlank()) append(" description=").append(description)
                    if (steps.isNotEmpty()) {
                        append(" steps=").append(steps.joinToString(" | "))
                    }
                }
            }
        if (rendered.isEmpty()) return
        append("\n").append(title).append(":")
        rendered.forEach { append("\n- ").append(it) }
    }

    private fun renderStepSummaries(capability: Map<String, Any?>): List<String> =
        listArg(capability["step_summaries"])
            .mapNotNull { raw ->
                val step = mapArg(raw)
                val tool = firstNonBlank(step["tool"], step["omniflow_action"], step["callable_tool"], step["kind"])
                val title = firstNonBlank(step["title"], tool)
                listOf(tool, title)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                    .take(MAX_STEP_CHARS)
                    .takeIf { it.isNotBlank() }
            }
            .take(MAX_STEPS_PER_CAPABILITY)

    private companion object {
        private const val MAX_ITEMS = 8
        private const val MAX_HINTS = 3
        private const val MAX_CAPABILITIES = 4
        private const val MAX_STEPS_PER_CAPABILITY = 2
        private const val MAX_DESCRIPTION_CHARS = 140
        private const val MAX_STEP_CHARS = 100
        private const val MAX_GUIDANCE_CHARS = 1_400
        private const val MAX_CONTEXT_CHARS = 2_400
    }
}
