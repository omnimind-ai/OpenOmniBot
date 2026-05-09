package cn.com.omnimind.bot.webchat

import android.content.Context
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentModelOverride

class ConversationDomainService(
    private val context: Context
) {
    private val historyRepository by lazy {
        AgentConversationHistoryRepository(context)
    }

    suspend fun listConversationPayloads(
        includeArchived: Boolean = true,
        archivedOnly: Boolean = false
    ): List<Map<String, Any?>> {
        val conversations = DatabaseHelper.getAllConversations()
            .filter { conversation ->
                when {
                    archivedOnly -> conversation.isArchived
                    includeArchived -> true
                    else -> !conversation.isArchived
                }
            }
        return conversations.map(::conversationToPayload)
    }

    suspend fun getConversationPayload(conversationId: Long): Map<String, Any?>? {
        return DatabaseHelper.getConversationById(conversationId)?.let(::conversationToPayload)
    }

    suspend fun createConversation(
        title: String,
        mode: String,
        summary: String? = null
    ): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = 0,
            title = title.ifBlank { "新对话" },
            mode = normalizeConversationMode(mode),
            summary = summary,
            status = 0,
            createdAt = now,
            updatedAt = now
        )
        val insertedId = DatabaseHelper.insertConversation(conversation)
        val inserted = requireNotNull(DatabaseHelper.getConversationById(insertedId)) {
            "Conversation was inserted but cannot be loaded back"
        }
        val payload = conversationToPayload(inserted)
        publishConversationEvent("conversation_created", inserted)
        return payload
    }

    suspend fun updateConversationFromPayload(
        conversationMap: Map<String, Any?>
    ): Map<String, Any?> {
        val conversationId = conversationMap.readLong("id")
            ?: throw IllegalArgumentException("conversation.id is invalid")
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val incomingContextSummary = conversationMap["contextSummary"]?.toString()?.trim()
        val updated = existing.copy(
            title = conversationMap["title"]?.toString()?.trim()?.ifEmpty {
                existing.title
            } ?: existing.title,
            mode = normalizeConversationMode(
                conversationMap["mode"]?.toString() ?: existing.mode
            ),
            isArchived = conversationMap.readBoolean("isArchived") ?: existing.isArchived,
            summary = conversationMap["summary"]?.toString(),
            contextSummary = incomingContextSummary
                ?.takeIf { it.isNotEmpty() }
                ?: existing.contextSummary,
            contextSummaryCutoffEntryDbId = conversationMap.readLong("contextSummaryCutoffEntryDbId")
                ?: existing.contextSummaryCutoffEntryDbId,
            contextSummaryUpdatedAt = conversationMap.readLong("contextSummaryUpdatedAt")
                ?.takeIf { it > 0L }
                ?: existing.contextSummaryUpdatedAt,
            status = conversationMap.readInt("status") ?: existing.status,
            lastMessage = conversationMap["lastMessage"]?.toString() ?: existing.lastMessage,
            messageCount = conversationMap.readInt("messageCount") ?: existing.messageCount,
            latestPromptTokens = conversationMap.readInt("latestPromptTokens")
                ?: existing.latestPromptTokens,
            promptTokenThreshold = conversationMap.readInt("promptTokenThreshold")
                ?.coerceAtLeast(1)
                ?: existing.promptTokenThreshold.coerceAtLeast(1),
            latestPromptTokensUpdatedAt = conversationMap.readLong("latestPromptTokensUpdatedAt")
                ?: existing.latestPromptTokensUpdatedAt,
            createdAt = conversationMap.readLong("createdAt") ?: existing.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun updateConversationTitle(
        conversationId: Long,
        newTitle: String
    ): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            title = newTitle.ifBlank { existing.title },
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun updateConversationPromptTokenThreshold(
        conversationId: Long,
        promptTokenThreshold: Int
    ): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            promptTokenThreshold = promptTokenThreshold.coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun setConversationArchived(
        conversationId: Long,
        archived: Boolean
    ): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            isArchived = archived,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun completeConversation(conversationId: Long): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            status = 1,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun deleteConversation(conversationId: Long) {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: return
        historyRepository.deleteConversation(conversationId)
        DatabaseHelper.deleteConversationById(conversationId)
        val payload = conversationToPayload(existing)
        RealtimeHub.publish(
            "conversation_deleted",
            mapOf(
                "conversation" to payload,
                "conversationId" to conversationId,
                "mode" to existing.mode
            )
        )
        FlutterChatSyncBridge.dispatchConversationListChanged(
            reason = "conversation_deleted",
            conversation = payload
        )
        FlutterChatSyncBridge.dispatchConversationMessagesChanged(
            conversationId = conversationId,
            mode = existing.mode,
            reason = "conversation_deleted"
        )
    }

    suspend fun listConversationMessages(
        conversationId: Long,
        conversationMode: String
    ): List<Map<String, Any?>> {
        return historyRepository.listConversationMessages(
            conversationId = conversationId,
            conversationMode = normalizeConversationMode(conversationMode)
        )
    }

    suspend fun listConversationMessagesPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): Map<String, Any?> {
        val (messages, hasMore) = historyRepository.listConversationMessagesPaged(
            conversationId = conversationId,
            conversationMode = normalizeConversationMode(conversationMode),
            limit = limit,
            offset = offset
        )
        return mapOf("messages" to messages, "hasMore" to hasMore)
    }

    suspend fun replaceConversationMessages(
        conversationId: Long,
        conversationMode: String,
        messages: List<Map<String, Any?>>
    ) {
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.replaceThreadMessagesFromUiSnapshot(
            conversationId = conversationId,
            conversationMode = normalizedMode,
            messages = messages
        )
        publishMessagesReplaced(conversationId, normalizedMode)
    }

    suspend fun upsertConversationUiCard(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        cardData: Map<String, Any?>,
        createdAt: Long
    ) {
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.upsertUiCard(
            conversationId = conversationId,
            conversationMode = normalizedMode,
            entryId = entryId,
            cardData = cardData,
            createdAt = createdAt
        )
        publishMessagesReplaced(conversationId, normalizedMode)
    }

    suspend fun compactConversationContext(
        conversationId: Long,
        conversationMode: String,
        modelOverride: AgentModelOverride?,
        reasoningEffort: String? = null
    ): Map<String, Any?> {
        val normalizedMode = normalizeConversationMode(conversationMode)
        val compactor = AgentConversationContextCompactor(
            historyRepository = historyRepository,
            modelScene = AgentConversationContextCompactor.DEFAULT_AGENT_MODEL_SCENE,
            modelOverride = modelOverride,
            reasoningEffort = reasoningEffort
        )
        val outcome = compactor.compactConversationContext(
            conversationId = conversationId,
            conversationMode = normalizedMode
        )
        val updatedConversation = DatabaseHelper.getConversationById(conversationId)
        if (outcome.compacted && updatedConversation != null) {
            publishConversationEvent("conversation_updated", updatedConversation)
        }
        return linkedMapOf(
            "compacted" to outcome.compacted,
            "reason" to outcome.reason,
            "summary" to outcome.summary,
            "conversation" to updatedConversation?.let(::conversationToPayload)
        )
    }

    suspend fun clearConversationMessages(
        conversationId: Long,
        conversationMode: String
    ) {
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.clearConversationMessages(
            conversationId = conversationId,
            conversationMode = normalizedMode
        )
        publishMessagesReplaced(conversationId, normalizedMode)
    }

    fun conversationToPayload(conversation: Conversation): Map<String, Any?> {
        return linkedMapOf(
            "id" to conversation.id,
            "title" to conversation.title,
            "mode" to conversation.mode,
            "isArchived" to conversation.isArchived,
            "summary" to conversation.summary,
            "contextSummary" to conversation.contextSummary,
            "contextSummaryCutoffEntryDbId" to conversation.contextSummaryCutoffEntryDbId,
            "contextSummaryUpdatedAt" to conversation.contextSummaryUpdatedAt,
            "status" to conversation.status,
            "lastMessage" to conversation.lastMessage,
            "messageCount" to conversation.messageCount,
            "latestPromptTokens" to conversation.latestPromptTokens,
            "promptTokenThreshold" to conversation.promptTokenThreshold,
            "latestPromptTokensUpdatedAt" to conversation.latestPromptTokensUpdatedAt,
            "createdAt" to conversation.createdAt,
            "updatedAt" to conversation.updatedAt
        )
    }

    fun normalizeConversationMode(rawMode: String?): String {
        val normalized = rawMode?.trim()?.lowercase().orEmpty()
        return if (normalized.isEmpty()) "normal" else normalized
    }

    private suspend fun publishMessagesReplaced(
        conversationId: Long,
        conversationMode: String
    ) {
        val messages = listConversationMessages(conversationId, conversationMode)
        RealtimeHub.publish(
            "messages_replaced",
            mapOf(
                "conversationId" to conversationId,
                "mode" to conversationMode,
                "messages" to messages
            )
        )
        FlutterChatSyncBridge.dispatchConversationMessagesChanged(
            conversationId = conversationId,
            mode = conversationMode,
            reason = "messages_replaced"
        )
    }

    private fun publishConversationEvent(
        eventName: String,
        conversation: Conversation
    ) {
        val payload = conversationToPayload(conversation)
        RealtimeHub.publish(
            eventName,
            mapOf(
                "conversation" to payload,
                "conversationId" to conversation.id,
                "mode" to conversation.mode
            )
        )
        FlutterChatSyncBridge.dispatchConversationListChanged(
            reason = eventName,
            conversation = payload
        )
    }

    private fun Map<String, Any?>.readLong(key: String): Long? {
        return (this[key] as? Number)?.toLong()
    }

    private fun Map<String, Any?>.readInt(key: String): Int? {
        return (this[key] as? Number)?.toInt()
    }

    private fun Map<String, Any?>.readBoolean(key: String): Boolean? {
        val raw = this[key] ?: return null
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().equals("true", ignoreCase = true)
            else -> null
        }
    }
}
