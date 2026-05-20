package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.langchain4j.RouteResolvedModelFactory
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

open class AgentConversationContextCompactor(
    private val historyRepository: AgentConversationHistoryRepository,
    private val modelScene: String = DEFAULT_AGENT_MODEL_SCENE,
    private val modelOverride: AgentModelOverride? = null,
    private val reasoningEffort: String? = null,
    private val json: Json = Json {
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
    }

    open suspend fun resolvePromptTokenThreshold(conversationId: Long?): Int {
        if (conversationId == null || conversationId <= 0L) {
            return DEFAULT_PROMPT_TOKEN_THRESHOLD
        }
        val conversation = historyRepository.getConversation(conversationId)
        val storedThreshold = conversation?.promptTokenThreshold ?: DEFAULT_PROMPT_TOKEN_THRESHOLD
        return storedThreshold.coerceAtLeast(1)
    }

    open suspend fun compactIfNeeded(
        conversationId: Long?,
        conversationMode: String,
        promptTokens: Int?,
        messages: List<ChatMessage>,
        promptTokenThresholdOverride: Int? = null,
        callback: AgentCallback? = null
    ): List<ChatMessage> {
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

    open suspend fun compactConversationContext(
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
        messagesToCompact: List<ChatMessage>,
        cutoffEntryDbId: Long
    ): CompactionOutcome {
        if (messagesToCompact.isEmpty()) {
            return CompactionOutcome(
                compacted = false,
                reason = "no_prompt_messages"
            )
        }
        val summary = requestCompactedSummary(
            existingSummary = existingSummary,
            messagesToCompact = messagesToCompact
        )
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
        existingSummary: String?,
        messagesToCompact: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val handle = RouteResolvedModelFactory.chatModelFor(
            scene = modelScene,
            modelOverride = modelOverride
        )
        val messages = buildLangChain4jCompactionMessages(
            existingSummary = existingSummary,
            messagesToCompact = messagesToCompact
        )
        val response = runCatching {
            handle.model.chat(
                ChatRequest.builder()
                    .messages(messages)
                    .build()
            )
        }.onFailure { error ->
            OmniLog.w(TAG, "langchain4j compaction call failed: ${error.message}")
        }.getOrThrow()
        response.aiMessage().text().orEmpty().trim()
    }

    /**
     * Build the compaction prompt as LangChain4j typed messages. Mirrors the legacy
     * [buildCompactionRequestMessages] shape (system prompt → optional summary user
     * message → messagesToCompact → final user prompt), but drops the
     * Anthropic-style `cache_control` field — LangChain4j's `SystemMessage` does not
     * carry passthrough fields. The trade-off is documented in the migration plan.
     */
    private fun buildLangChain4jCompactionMessages(
        existingSummary: String?,
        messagesToCompact: List<ChatMessage>
    ): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        out += SystemMessage.from(COMPACTION_REQUEST_PROMPT.trim())
        existingSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { summary ->
            out += AgentConversationHistorySupport.buildContextSummaryUserMessage(summary)
        }
        out += messagesToCompact
        out += UserMessage.from(FINAL_USER_PROMPT)
        return out
    }

}
