package cn.com.omnimind.bot.agent

data class AgentStreamEvent(
    val taskId: String,
    val seq: Long,
    val kind: String,
    val createdAt: Long,
    val entryId: String? = null,
    val roundIndex: Int = 0,
    val isFinal: Boolean = false,
    val text: String? = null,
    val thinking: String? = null,
    val stage: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val success: Boolean? = null,
    val outputKind: String? = null,
    val hasUserVisibleOutput: Boolean? = null,
    val latestPromptTokens: Int? = null,
    val promptTokenThreshold: Int? = null,
    val error: String? = null,
    val question: String? = null,
    val missingFields: List<String>? = null,
    val missing: List<String>? = null,
    val extras: Map<String, Any?> = emptyMap()
) {
    fun toPayload(
        conversationId: Long?,
        conversationMode: String
    ): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "taskId" to taskId,
            "conversationId" to conversationId,
            "conversationMode" to conversationMode,
            "seq" to seq,
            "kind" to kind,
            "createdAt" to createdAt
        )
        entryId?.takeIf { it.isNotBlank() }?.let { payload["entryId"] = it }
        if (roundIndex > 0) {
            payload["roundIndex"] = roundIndex
        }
        if (isFinal) {
            payload["isFinal"] = true
        }
        text?.let { payload["text"] = it }
        thinking?.let { payload["thinking"] = it }
        stage?.let { payload["stage"] = it }
        prefillTokensPerSecond?.let { payload["prefillTokensPerSecond"] = it }
        decodeTokensPerSecond?.let { payload["decodeTokensPerSecond"] = it }
        success?.let { payload["success"] = it }
        outputKind?.let { payload["outputKind"] = it }
        hasUserVisibleOutput?.let { payload["hasUserVisibleOutput"] = it }
        latestPromptTokens?.let { payload["latestPromptTokens"] = it }
        promptTokenThreshold?.let { payload["promptTokenThreshold"] = it }
        error?.takeIf { it.isNotBlank() }?.let { payload["error"] = it }
        question?.takeIf { it.isNotBlank() }?.let { payload["question"] = it }
        missingFields?.takeIf { it.isNotEmpty() }?.let { payload["missingFields"] = it }
        missing?.takeIf { it.isNotEmpty() }?.let { payload["missing"] = it }
        payload.putAll(extras)
        return payload
    }
}
