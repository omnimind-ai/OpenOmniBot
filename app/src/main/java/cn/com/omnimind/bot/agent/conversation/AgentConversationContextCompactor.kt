package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.koog.KoogAgentLlmClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Runs LLM-driven context compaction against the conversation history. Replaces the original
 * messages in the model's context window with a concise summary so the agent can keep going past
 * the prompt-token threshold.
 *
 * Implementation note: the actual summarization LLM call now goes through Koog's
 * [KoogAgentLlmClient] (OpenAI-compatible `executeStreaming` against the configured baseUrl) —
 * the legacy hand-rolled `HttpController.postLLMStreamRequestWithContextAsFlow` SSE path has been
 * removed. Surrounding orchestration (token threshold lookup, candidate selection, persistence,
 * UI callbacks) is unchanged.
 */
class AgentConversationContextCompactor(
    private val historyRepository: AgentConversationHistoryRepository,
    private val modelScene: String = DEFAULT_AGENT_MODEL_SCENE,
    private val modelOverride: AgentModelOverride? = null,
    private val reasoningEffort: String? = null,
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
) {
    data class CompactionOutcome(
        val compacted: Boolean,
        val summary: String? = null,
        val cutoffEntryDbId: Long? = null,
        val reason: String? = null
    )

    companion object {
        const val DEFAULT_PROMPT_TOKEN_THRESHOLD = 128_000
        const val DEFAULT_AGENT_MODEL_SCENE = "scene.dispatch.model"
        private const val TAG = "AgentConversationContextCompactor"
        private const val COMPACTION_MAX_TOKENS = 4096
        private val EPHEMERAL_CACHE_CONTROL = mapOf("type" to "ephemeral")
        private const val COMPACTION_REQUEST_PROMPT = """
You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window — the agent will rely on it to continue working. Write the summary in the same language the user used in the conversation.

MUST PRESERVE (never omit or shorten):
- All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
- Commands executed and their outcomes (success/failure/output)
- Active tasks: what was requested, what's done, what's still pending
- Key decisions made and their rationale
- Errors encountered and how they were resolved
- Important constraints, rules, or user preferences mentioned
- Any tool calls and their results that affect current state

STRUCTURE:
1. Start with a one-line summary of the overall goal
2. Then a concise narrative of what happened, preserving technical details
3. End with a "Current state" section: what's done, what's pending, any blockers

PRIORITIZE recent context over older history — the agent needs to know what it was doing most recently, not just what was discussed early on.

Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs to continue.
"""
        private const val FINAL_USER_PROMPT =
            "Generate the replacement context summary now."

        internal fun buildCompactionRequestMessages(
            existingSummary: String?,
            messagesToCompact: List<ChatCompletionMessage>
        ): List<Map<String, Any>> {
            val requestMessages = mutableListOf<Map<String, Any>>()
            requestMessages += mapOf(
                "role" to "system",
                "content" to buildTextContentBlocks(
                    text = COMPACTION_REQUEST_PROMPT.trim(),
                    cacheControl = EPHEMERAL_CACHE_CONTROL
                )
            )
            existingSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { summary ->
                requestMessages += toTransportMessage(
                    AgentConversationHistorySupport.buildContextSummaryUserMessage(summary)
                )
            }
            requestMessages += messagesToCompact.map(::toTransportMessage)
            requestMessages += mapOf(
                "role" to "user",
                "content" to FINAL_USER_PROMPT
            )
            return requestMessages
        }

        private fun buildTextContentBlocks(
            text: String,
            cacheControl: Map<String, String>? = null
        ): List<Map<String, Any>> {
            val block = linkedMapOf<String, Any>(
                "type" to "text",
                "text" to text
            )
            if (cacheControl != null) {
                block["cache_control"] = cacheControl
            }
            return listOf(block)
        }

        private fun toTransportMessage(message: ChatCompletionMessage): Map<String, Any> {
            val payload = linkedMapOf<String, Any>(
                "role" to message.role
            )
            val content = message.content?.let(::jsonElementToTransportValue)
            if (content != null) {
                payload["content"] = content
            }
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                payload["tool_calls"] = toolCalls.map(::toolCallToTransportMap)
            }
            message.reasoningContent?.takeIf { it.isNotBlank() }?.let { reasoning ->
                payload["reasoning_content"] = reasoning
            }
            message.toolCallId?.takeIf { it.isNotBlank() }?.let { toolCallId ->
                payload["tool_call_id"] = toolCallId
            }
            message.name?.takeIf { it.isNotBlank() }?.let { name ->
                payload["name"] = name
            }
            return payload
        }

        private fun toolCallToTransportMap(toolCall: AssistantToolCall): Map<String, Any> {
            return linkedMapOf(
                "id" to toolCall.id,
                "type" to toolCall.type,
                "function" to linkedMapOf(
                    "name" to toolCall.function.name,
                    "arguments" to toolCall.function.arguments
                )
            )
        }

        private fun jsonElementToTransportValue(element: JsonElement): Any? {
            return when (element) {
                is JsonPrimitive -> {
                    element.contentOrNull
                        ?: element.booleanOrNull
                        ?: element.toString()
                }

                is JsonArray -> element.mapNotNull(::jsonElementToTransportValue)
                is JsonObject -> element.entries.associate { (key, value) ->
                    key to (jsonElementToTransportValue(value) ?: "")
                }
            }
        }
    }

    suspend fun resolvePromptTokenThreshold(conversationId: Long?): Int {
        if (conversationId == null || conversationId <= 0L) {
            return DEFAULT_PROMPT_TOKEN_THRESHOLD
        }
        val conversation = historyRepository.getConversation(conversationId)
        val storedThreshold = conversation?.promptTokenThreshold ?: DEFAULT_PROMPT_TOKEN_THRESHOLD
        return storedThreshold.coerceAtLeast(1)
    }

    suspend fun compactIfNeeded(
        conversationId: Long?,
        conversationMode: String,
        promptTokens: Int?,
        messages: List<ChatCompletionMessage>,
        promptTokenThresholdOverride: Int? = null,
        callback: AgentCallback? = null
    ): List<ChatCompletionMessage> {
        if (conversationId == null || conversationId <= 0L) {
            return messages
        }
        val normalizedPromptTokens = promptTokens ?: return messages
        val promptTokenThreshold = promptTokenThresholdOverride?.coerceAtLeast(1)
            ?: resolvePromptTokenThreshold(conversationId)
        historyRepository.updatePromptTokenUsage(
            conversationId = conversationId,
            promptTokens = normalizedPromptTokens,
            threshold = promptTokenThreshold
        )
        if (normalizedPromptTokens <= promptTokenThreshold) {
            return messages
        }
        val candidate = historyRepository.getContextCompactionCandidate(
            conversationId = conversationId,
            conversationMode = conversationMode
        ) ?: return messages
        val runtimeWindow = AgentConversationHistorySupport.buildRuntimeCompactionWindow(messages)
            ?: return messages

        callback?.onContextCompactionStateChanged(
            isCompacting = true,
            latestPromptTokens = normalizedPromptTokens,
            promptTokenThreshold = promptTokenThreshold
        )
        try {
            return runCatching {
                val outcome = compactAndPersist(
                    conversationId = conversationId,
                    existingSummary = runtimeWindow.existingSummary
                        ?: candidate.conversation.contextSummary,
                    messagesToCompact = runtimeWindow.messagesToCompact,
                    cutoffEntryDbId = candidate.cutoffEntryDbId
                )
                val summary = outcome.summary.orEmpty()
                if (!outcome.compacted || summary.isBlank()) {
                    OmniLog.w(TAG, "conversation=$conversationId compaction returned blank summary")
                    messages
                } else {
                    AgentConversationHistorySupport.rebuildMessagesWithCompactedSummary(
                        messages = messages,
                        summary = summary
                    )
                }
            }.getOrElse { error ->
                OmniLog.w(
                    TAG,
                    "conversation=$conversationId compaction failed: ${error.message}"
                )
                messages
            }
        } finally {
            callback?.onContextCompactionStateChanged(
                isCompacting = false,
                latestPromptTokens = normalizedPromptTokens,
                promptTokenThreshold = promptTokenThreshold
            )
        }
    }

    suspend fun compactConversationContext(
        conversationId: Long,
        conversationMode: String
    ): CompactionOutcome {
        val candidate = historyRepository.getContextCompactionCandidate(
            conversationId = conversationId,
            conversationMode = conversationMode
        ) ?: return CompactionOutcome(
            compacted = false,
            reason = "no_candidate"
        )
        val messagesToCompact = AgentConversationHistorySupport.buildPromptRelevantMessages(
            candidate.entriesToCompact
        )
        if (messagesToCompact.isEmpty()) {
            return CompactionOutcome(
                compacted = false,
                reason = "no_prompt_messages"
            )
        }
        return compactAndPersist(
            conversationId = conversationId,
            existingSummary = candidate.conversation.contextSummary,
            messagesToCompact = messagesToCompact,
            cutoffEntryDbId = candidate.cutoffEntryDbId
        )
    }

    private suspend fun compactAndPersist(
        conversationId: Long,
        existingSummary: String?,
        messagesToCompact: List<ChatCompletionMessage>,
        cutoffEntryDbId: Long
    ): CompactionOutcome {
        if (messagesToCompact.isEmpty()) {
            return CompactionOutcome(
                compacted = false,
                reason = "no_prompt_messages"
            )
        }
        val requestMessages = buildCompactionRequestMessages(
            existingSummary = existingSummary,
            messagesToCompact = messagesToCompact
        )
        val summary = requestCompactedSummary(requestMessages)
        if (summary.isBlank()) {
            return CompactionOutcome(
                compacted = false,
                reason = "blank_summary"
            )
        }
        historyRepository.updateContextSummary(
            conversationId = conversationId,
            summary = summary,
            cutoffEntryDbId = cutoffEntryDbId
        )
        return CompactionOutcome(
            compacted = true,
            summary = summary,
            cutoffEntryDbId = cutoffEntryDbId
        )
    }

    private suspend fun requestCompactedSummary(
        messages: List<Map<String, Any>>
    ): String {
        val ccMessages = messages.mapNotNull(::mapToChatCompletionMessage)
        if (ccMessages.isEmpty()) {
            OmniLog.w(TAG, "no convertible compaction messages — returning empty summary")
            return ""
        }
        val request = ChatCompletionRequest(
            messages = ccMessages,
            model = modelOverride?.modelId?.takeIf { it.isNotBlank() } ?: modelScene,
            maxCompletionTokens = COMPACTION_MAX_TOKENS,
            stream = true,
            streamOptions = ChatCompletionStreamOptions(includeUsage = false),
            enableThinking = false,
            reasoningEffort = reasoningEffort
        )
        val turn = KoogAgentLlmClient(modelOverride = modelOverride).streamTurn(request)
        return turn.message.contentText().trim()
    }

    private fun mapToChatCompletionMessage(raw: Map<String, Any>): ChatCompletionMessage? {
        val role = raw["role"]?.toString()?.trim().orEmpty()
        if (role.isEmpty()) return null
        val content: JsonElement? = when (val rawContent = raw["content"]) {
            null -> null
            is String -> JsonPrimitive(rawContent)
            is List<*> -> JsonArray(rawContent.mapNotNull { item ->
                when (item) {
                    is Map<*, *> -> anyMapToJsonObject(item)
                    is String -> JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(item)))
                    else -> null
                }
            })
            is Map<*, *> -> anyMapToJsonObject(rawContent)
            else -> JsonPrimitive(rawContent.toString())
        }
        return ChatCompletionMessage(role = role, content = content)
    }

    private fun anyMapToJsonObject(map: Map<*, *>): JsonObject {
        return JsonObject(
            map.entries.mapNotNull { (k, v) ->
                val key = k?.toString() ?: return@mapNotNull null
                key to anyToJsonElement(v)
            }.toMap()
        )
    }

    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonPrimitive(null as String?)
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> anyMapToJsonObject(value)
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }
}
