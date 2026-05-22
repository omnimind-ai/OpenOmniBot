package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object VLMTokenUsageMapper {
    fun fromTurn(
        turn: SceneChatCompletionTurn,
        attemptIndex: Int,
        stabilityAttempt: Int,
        toolRetryIndex: Int
    ): VLMTokenUsage? {
        val usage = turn.turn.usage ?: return null
        if (!usage.hasTokenPayload()) return null
        return VLMTokenUsage(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens ?: addIfAny(usage.promptTokens, usage.completionTokens),
            reasoningTokens = intField(usage.completionTokensDetails, "reasoning_tokens"),
            textTokens = intField(usage.completionTokensDetails, "text_tokens"),
            cachedTokens = intField(usage.promptTokensDetails, "cached_tokens"),
            prefillTokensPerSecond = usage.prefillTokensPerSecond,
            decodeTokensPerSecond = usage.decodeTokensPerSecond,
            resolvedModel = turn.resolvedModel.takeIf { it.isNotBlank() },
            route = turn.route?.takeIf { it.isNotBlank() },
            attemptIndex = attemptIndex,
            stabilityAttempt = stabilityAttempt,
            toolRetryIndex = toolRetryIndex,
            attemptCount = 1
        )
    }

    fun aggregate(usages: List<VLMTokenUsage>): VLMTokenUsage? {
        val nonEmpty = usages.filter { it.hasTokenPayload() }
        if (nonEmpty.isEmpty()) return null
        return VLMTokenUsage(
            promptTokens = sumInts(nonEmpty) { it.promptTokens },
            completionTokens = sumInts(nonEmpty) { it.completionTokens },
            totalTokens = sumInts(nonEmpty) { it.totalTokens }
                ?: addIfAny(
                    sumInts(nonEmpty) { it.promptTokens },
                    sumInts(nonEmpty) { it.completionTokens }
                ),
            reasoningTokens = sumInts(nonEmpty) { it.reasoningTokens },
            textTokens = sumInts(nonEmpty) { it.textTokens },
            cachedTokens = sumInts(nonEmpty) { it.cachedTokens },
            resolvedModel = commonString(nonEmpty.mapNotNull { it.resolvedModel }),
            route = commonString(nonEmpty.mapNotNull { it.route }),
            attemptCount = nonEmpty.sumOf { it.attemptCount ?: 1 }
        )
    }

    fun toRunLogMap(usage: VLMTokenUsage): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            putInt("prompt_tokens", usage.promptTokens)
            putInt("completion_tokens", usage.completionTokens)
            putInt("total_tokens", usage.totalTokens)
            putInt("reasoning_tokens", usage.reasoningTokens)
            putInt("text_tokens", usage.textTokens)
            putInt("cached_tokens", usage.cachedTokens)
            putDouble("prefill_tokens_per_second", usage.prefillTokensPerSecond)
            putDouble("decode_tokens_per_second", usage.decodeTokensPerSecond)
            putString("resolved_model", usage.resolvedModel)
            putString("route", usage.route)
            putInt("attempt_index", usage.attemptIndex)
            putInt("stability_attempt", usage.stabilityAttempt)
            putInt("tool_retry_index", usage.toolRetryIndex)
            putInt("attempt_count", usage.attemptCount)
        }
    }

    private fun ChatCompletionUsage.hasTokenPayload(): Boolean {
        return promptTokens != null ||
            completionTokens != null ||
            totalTokens != null ||
            promptTokensDetails != null ||
            completionTokensDetails != null
    }

    private fun VLMTokenUsage.hasTokenPayload(): Boolean {
        return promptTokens != null ||
            completionTokens != null ||
            totalTokens != null ||
            reasoningTokens != null ||
            textTokens != null ||
            cachedTokens != null
    }

    private fun intField(element: JsonElement?, name: String): Int? {
        val obj = element as? JsonObject ?: return null
        return obj[name]?.jsonPrimitive?.intOrNull
            ?: obj[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }

    private inline fun sumInts(
        usages: List<VLMTokenUsage>,
        selector: (VLMTokenUsage) -> Int?
    ): Int? {
        var hasValue = false
        var total = 0
        usages.forEach { usage ->
            val value = selector(usage)
            if (value != null) {
                hasValue = true
                total += value
            }
        }
        return if (hasValue) total else null
    }

    private fun addIfAny(first: Int?, second: Int?): Int? {
        if (first == null && second == null) return null
        return (first ?: 0) + (second ?: 0)
    }

    private fun commonString(values: List<String>): String? {
        if (values.isEmpty()) return null
        val distinct = values.distinct()
        return if (distinct.size == 1) distinct.first() else "multiple"
    }

    private fun MutableMap<String, Any?>.putInt(key: String, value: Int?) {
        if (value != null) put(key, value)
    }

    private fun MutableMap<String, Any?>.putDouble(key: String, value: Double?) {
        if (value != null) put(key, value)
    }

    private fun MutableMap<String, Any?>.putString(key: String, value: String?) {
        if (!value.isNullOrBlank()) put(key, value)
    }
}
