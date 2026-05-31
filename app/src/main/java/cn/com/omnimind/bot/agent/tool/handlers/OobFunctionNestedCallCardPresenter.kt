package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Shapes tool-card payloads for nested Function calls during replay. Execution
 * remains in the replay handler; this presenter only owns UI-facing card text
 * and JSON payload structure.
 */
class OobFunctionNestedCallCardPresenter(
    private val helper: SharedHelper,
) {
    fun cardId(parentToolCallId: String?, toolName: String, stepId: String): String {
        val base = safeCardIdPart(firstNonBlank(parentToolCallId, toolName, "function"))
        val step = safeCardIdPart(stepId.ifBlank { "step" })
        return "${base}_${step}_call_function"
    }

    fun runningSummary(functionId: String): String {
        return if (functionId.isNotBlank()) {
            "${helper.localized("正在执行复用指令")}：$functionId"
        } else {
            helper.localized("正在执行复用指令")
        }
    }

    fun finishedSummary(functionId: String, success: Boolean): String {
        val base = helper.localized(if (success) "复用指令执行完成" else "复用指令执行失败")
        return if (functionId.isNotBlank()) "$base：$functionId" else base
    }

    fun payload(
        cardId: String,
        toolName: String,
        stepTitle: String,
        functionId: String,
        callableTool: String,
        nestedArguments: Map<String, Any?>,
        status: String,
        success: Boolean?,
        summary: String,
        progress: String,
        startedAtMs: Long,
        finishedAtMs: Long?,
        result: Map<String, Any?>?,
    ): Map<String, Any?> {
        val argsPayload = linkedMapOf<String, Any?>(
            "function_id" to functionId.takeIf { it.isNotBlank() },
            "arguments" to nestedArguments.takeIf { it.isNotEmpty() },
            "source_tool" to callableTool.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
        val resultPayload = result?.let {
            linkedMapOf<String, Any?>(
                "function_id" to functionId.takeIf { id -> id.isNotBlank() },
                "source" to "omniflow_replay",
                "run_source" to "omniflow_replay",
                "runner" to it["runner"],
                "nested_run_id" to it["nested_run_id"],
                "nested_step_count" to it["nested_step_count"],
                "nested_success_step_count" to it["nested_success_step_count"],
                "success" to (it["success"] != false),
                "summary" to it["summary"],
                "error_code" to it["error_code"],
            ).filterValues { value -> value != null }
        }
        val argsJson = helper.encodeLocalizedPayload(argsPayload)
        return linkedMapOf<String, Any?>(
            "cardId" to cardId,
            "toolCallId" to cardId,
            "callId" to cardId,
            "toolName" to toolName,
            "displayName" to helper.localized("复用指令"),
            "toolType" to "oob_function",
            "source" to "omniflow_replay",
            "runSource" to "omniflow_replay",
            "run_source" to "omniflow_replay",
            "runner" to (result?.get("runner") ?: RunLogReplayPolicy.fixedReplayRunner),
            "toolTitle" to if (functionId.isNotBlank()) {
                "${helper.localized("复用指令")}：$functionId"
            } else {
                stepTitle
            },
            "summary" to summary,
            "progress" to progress,
            "status" to status,
            "success" to success,
            "args" to argsJson,
            "argsJson" to argsJson,
            "sourceTool" to callableTool,
            "functionId" to functionId,
            "function_id" to functionId,
            "startedAtMs" to startedAtMs,
            "started_at_ms" to startedAtMs,
            "finishedAtMs" to finishedAtMs,
            "finished_at_ms" to finishedAtMs,
            "durationMs" to finishedAtMs?.let { (it - startedAtMs).coerceAtLeast(0) },
            "duration_ms" to finishedAtMs?.let { (it - startedAtMs).coerceAtLeast(0) },
            "resultPreviewJson" to resultPayload?.let { helper.encodeLocalizedPayload(it) }.orEmpty(),
            "rawResultJson" to result?.let { helper.encodeLocalizedPayload(it) }.orEmpty(),
        ).filterValues { it != null }
    }

    private fun safeCardIdPart(raw: String): String {
        val normalized = raw.trim().replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        return normalized.take(96).ifBlank { "function" }
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }
}
