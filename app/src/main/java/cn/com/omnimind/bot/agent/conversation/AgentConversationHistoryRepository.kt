package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class AgentConversationHistoryRepository(
    @Suppress("UNUSED_PARAMETER")
    private val context: Context
) {
    data class ContextCompactionCandidate(
        val conversation: Conversation,
        val entriesToCompact: List<AgentConversationEntry>,
        val cutoffEntryDbId: Long
    )

    data class PromptSeed(
        val historyMessages: List<ChatCompletionMessage>
    )

    companion object {
        const val ENTRY_TYPE_USER_MESSAGE = "user_message"
        const val ENTRY_TYPE_ASSISTANT_MESSAGE = "assistant_message"
        const val ENTRY_TYPE_TOOL_EVENT = "tool_event"
        const val ENTRY_TYPE_UI_CARD = "ui_card"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_INTERRUPTED = "interrupted"

    }

    private val gson = Gson()

    suspend fun upsertUserMessage(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        text: String,
        attachments: List<Map<String, Any?>> = emptyList(),
        createdAt: Long = System.currentTimeMillis()
    ) {
        val payload = buildTextMessagePayload(
            messageId = entryId,
            user = 1,
            text = text,
            attachments = attachments,
            isError = false,
            streamMeta = null,
            createdAt = createdAt
        )
        upsertMessageEntry(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            entryType = ENTRY_TYPE_USER_MESSAGE,
            payload = payload,
            summary = text,
            status = STATUS_SUCCESS,
            createdAt = createdAt
        )
    }

    suspend fun upsertAssistantMessage(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        text: String,
        isError: Boolean = false,
        attachments: List<Map<String, Any?>> = emptyList(),
        streamMeta: Map<String, Any?>? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val payload = buildTextMessagePayload(
            messageId = entryId,
            user = 2,
            text = text,
            attachments = attachments,
            isError = isError,
            streamMeta = streamMeta,
            createdAt = createdAt
        )
        upsertMessageEntry(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            entryType = ENTRY_TYPE_ASSISTANT_MESSAGE,
            payload = payload,
            summary = text,
            status = if (isError) STATUS_ERROR else STATUS_SUCCESS,
            createdAt = createdAt
        )
    }

    suspend fun upsertUiCard(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        cardData: Map<String, Any?>,
        streamMeta: Map<String, Any?>? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val payload = buildCardMessagePayload(
            messageId = entryId,
            cardData = cardData,
            isError = false,
            streamMeta = streamMeta,
            createdAt = createdAt
        )
        upsertMessageEntry(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            entryType = ENTRY_TYPE_UI_CARD,
            payload = payload,
            summary = cardData["summary"]?.toString().orEmpty(),
            status = STATUS_SUCCESS,
            createdAt = createdAt
        )
    }

    suspend fun upsertToolEvent(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        payload: Map<String, Any?>,
        fallbackStatus: String = STATUS_RUNNING,
        fallbackSummary: String = ""
    ) = withContext(Dispatchers.IO) {
        val existing = DatabaseHelper.getAgentConversationEntryByThreadAndId(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId
        )
        val mergedPayload = mergeToolPayload(
            existing = existing?.takeIf { it.entryType == ENTRY_TYPE_TOOL_EVENT }?.let {
                readMap(it.payloadJson)
            }.orEmpty(),
            incoming = payload,
            fallbackStatus = fallbackStatus,
            fallbackSummary = fallbackSummary
        )
        val normalizedStatus = mergedPayload["status"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: fallbackStatus
        val normalizedSummary = mergedPayload["summary"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: fallbackSummary

        upsertEntry(
            AgentConversationEntry(
                id = existing?.id ?: 0,
                conversationId = conversationId,
                conversationMode = conversationMode,
                entryId = entryId,
                entryType = ENTRY_TYPE_TOOL_EVENT,
                status = normalizedStatus,
                summary = normalizedSummary,
                payloadJson = gson.toJson(mergedPayload),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshConversationMetadata(conversationId)
    }

    suspend fun replaceThreadMessagesFromUiSnapshot(
        conversationId: Long,
        conversationMode: String,
        messages: List<Map<String, Any?>>
    ) = withContext(Dispatchers.IO) {
        val existingConversation = DatabaseHelper.getConversationById(conversationId)
        val existingEntries = DatabaseHelper.getAgentConversationEntriesAsc(
            conversationId,
            conversationMode
        )
        val preservedSummary = existingConversation?.contextSummary
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val cutoffEntryId = existingConversation?.contextSummaryCutoffEntryDbId?.let { cutoffDbId ->
            existingEntries.firstOrNull { it.id == cutoffDbId }?.entryId
        }
        var remappedCutoffEntryDbId: Long? = null
        DatabaseHelper.deleteAgentConversationThread(conversationId, conversationMode)
        ConversationSnapshotOrdering.prepareForStorage(messages).forEach { prepared ->
            val message = prepared.payload
            val restoredToolPayload =
                AgentConversationHistorySupport.restoreToolPayloadFromUiMessage(message)
            val entryId = message["id"]?.toString()?.trim().orEmpty()
                .ifEmpty { restoredToolPayload?.get("cardId")?.toString()?.trim().orEmpty() }
                .ifEmpty {
                "entry_${System.currentTimeMillis()}"
            }
            val type = when {
                restoredToolPayload != null -> ENTRY_TYPE_TOOL_EVENT
                (message["type"] as? Number)?.toInt() == 2 -> ENTRY_TYPE_UI_CARD
                (message["user"] as? Number)?.toInt() == 1 -> ENTRY_TYPE_USER_MESSAGE
                else -> ENTRY_TYPE_ASSISTANT_MESSAGE
            }
            val status = when {
                restoredToolPayload != null -> restoredToolPayload["status"]?.toString()?.trim()
                    ?.ifEmpty { null }
                    ?: if (message["isError"] == true) STATUS_ERROR else STATUS_SUCCESS
                message["isError"] == true -> STATUS_ERROR
                else -> STATUS_SUCCESS
            }
            val summary = when {
                restoredToolPayload != null -> restoredToolPayload["summary"]?.toString()?.trim()
                    .orEmpty()
                else -> extractSummaryFromMessagePayload(message)
            }
            val payloadJson = if (restoredToolPayload != null) {
                gson.toJson(restoredToolPayload)
            } else {
                gson.toJson(message)
            }
            val insertedId = DatabaseHelper.upsertAgentConversationEntry(
                AgentConversationEntry(
                    conversationId = conversationId,
                    conversationMode = conversationMode,
                    entryId = entryId,
                    entryType = type,
                    status = status,
                    summary = summary,
                    payloadJson = payloadJson,
                    createdAt = prepared.createdAt,
                    updatedAt = prepared.createdAt
                )
            )
            if (entryId == cutoffEntryId) {
                remappedCutoffEntryDbId = insertedId
            }
        }
        if (preservedSummary != null && remappedCutoffEntryDbId != null) {
            val refreshedConversation = DatabaseHelper.getConversationById(conversationId)
            if (refreshedConversation != null) {
                DatabaseHelper.updateConversation(
                    refreshedConversation.copy(
                        contextSummary = preservedSummary,
                        contextSummaryCutoffEntryDbId = remappedCutoffEntryDbId,
                        contextSummaryUpdatedAt = existingConversation?.contextSummaryUpdatedAt
                            ?: refreshedConversation.contextSummaryUpdatedAt
                    )
                )
            }
        } else {
            resetContextSummary(conversationId)
        }
        refreshConversationMetadata(conversationId)
    }

    suspend fun listConversationMessages(
        conversationId: Long,
        conversationMode: String
    ): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val normalized = normalizeEntriesForDisplay(
            DatabaseHelper.getAgentConversationEntriesDesc(conversationId, conversationMode)
        )
        val messagePayloads = normalized.mapNotNull { entry -> entryToMessagePayload(entry) }
        ConversationSnapshotOrdering.sortForDisplay(messagePayloads)
    }

    suspend fun listConversationMessagesPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): Pair<List<Map<String, Any?>>, Boolean> = withContext(Dispatchers.IO) {
        val totalCount = DatabaseHelper.countAgentConversationThreadEntries(
            conversationId, conversationMode
        )
        val entries = DatabaseHelper.getAgentConversationEntriesDescPaged(
            conversationId, conversationMode, limit, offset
        )
        val normalized = if (offset == 0) {
            normalizeEntriesForDisplay(entries)
        } else {
            entries
        }
        val messagePayloads = normalized.mapNotNull { entry -> entryToMessagePayload(entry) }
        val sorted = ConversationSnapshotOrdering.sortForDisplay(messagePayloads)
        val hasMore = offset + entries.size < totalCount
        Pair(sorted, hasMore)
    }

    suspend fun clearConversationMessages(
        conversationId: Long,
        conversationMode: String
    ) = withContext(Dispatchers.IO) {
        DatabaseHelper.deleteAgentConversationThread(conversationId, conversationMode)
        resetContextSummary(conversationId)
        refreshConversationMetadata(conversationId)
    }

    suspend fun deleteConversation(conversationId: Long) = withContext(Dispatchers.IO) {
        DatabaseHelper.deleteAgentConversationEntries(conversationId)
    }

    suspend fun buildPromptSeed(
        conversationId: Long?,
        conversationMode: String
    ): PromptSeed = withContext(Dispatchers.IO) {
        if (conversationId == null || conversationId <= 0L) {
            return@withContext PromptSeed(emptyList())
        }
        val conversation = DatabaseHelper.getConversationById(conversationId)
        val normalizedEntries = normalizeInterruptedToolEntries(
            DatabaseHelper.getAgentConversationEntriesAsc(conversationId, conversationMode)
        )
        AgentConversationHistorySupport.buildPromptSeedFromEntries(
            entries = normalizedEntries,
            contextSummary = conversation?.contextSummary,
            cutoffEntryDbId = conversation?.contextSummaryCutoffEntryDbId
        )
    }

    suspend fun getContextCompactionCandidate(
        conversationId: Long,
        conversationMode: String
    ): ContextCompactionCandidate? = withContext(Dispatchers.IO) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return@withContext null
        val normalizedEntries = normalizeInterruptedToolEntries(
            DatabaseHelper.getAgentConversationEntriesAsc(conversationId, conversationMode)
        )
        val selection = AgentConversationHistorySupport.selectEntriesToCompact(
            entries = normalizedEntries,
            cutoffEntryDbId = conversation.contextSummaryCutoffEntryDbId
        ) ?: return@withContext null
        ContextCompactionCandidate(
            conversation = conversation,
            entriesToCompact = selection.entriesToCompact,
            cutoffEntryDbId = selection.cutoffEntryDbId
        )
    }

    suspend fun updateContextSummary(
        conversationId: Long,
        summary: String,
        cutoffEntryDbId: Long,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return@withContext
        DatabaseHelper.updateConversation(
            conversation.copy(
                contextSummary = summary.trim(),
                contextSummaryCutoffEntryDbId = cutoffEntryDbId,
                contextSummaryUpdatedAt = updatedAt,
                updatedAt = maxOf(conversation.updatedAt, updatedAt)
            )
        )
    }

    suspend fun updatePromptTokenUsage(
        conversationId: Long,
        promptTokens: Int,
        threshold: Int,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return@withContext
        DatabaseHelper.updateConversation(
            conversation.copy(
                latestPromptTokens = promptTokens.coerceAtLeast(0),
                promptTokenThreshold = threshold.coerceAtLeast(1),
                latestPromptTokensUpdatedAt = updatedAt,
                updatedAt = maxOf(conversation.updatedAt, updatedAt)
            )
        )
    }

    suspend fun getConversation(conversationId: Long): Conversation? = withContext(Dispatchers.IO) {
        DatabaseHelper.getConversationById(conversationId)
    }

    private suspend fun upsertMessageEntry(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        entryType: String,
        payload: Map<String, Any?>,
        summary: String,
        status: String,
        createdAt: Long
    ) = withContext(Dispatchers.IO) {
        val existing = DatabaseHelper.getAgentConversationEntryByThreadAndId(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId
        )
        upsertEntry(
            AgentConversationEntry(
                id = existing?.id ?: 0,
                conversationId = conversationId,
                conversationMode = conversationMode,
                entryId = entryId,
                entryType = entryType,
                status = status,
                summary = summary.trim(),
                payloadJson = gson.toJson(payload),
                createdAt = existing?.createdAt ?: createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshConversationMetadata(conversationId)
    }

    private suspend fun upsertEntry(entry: AgentConversationEntry) {
        DatabaseHelper.upsertAgentConversationEntry(entry)
    }

    private suspend fun refreshConversationMetadata(conversationId: Long) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return
        val lastEntry = DatabaseHelper.getLatestAgentConversationEntry(conversationId)
        val firstEntry = DatabaseHelper.getEarliestAgentConversationEntry(conversationId)
        val lastUpdate = DatabaseHelper.getLatestAgentConversationUpdate(conversationId)
        val messageCount = DatabaseHelper.countAgentConversationEntries(conversationId)
        val updatedConversation = conversation.copy(
            lastMessage = lastEntry?.let(::conversationLastMessageFromEntry)?.takeIf { it.isNotBlank() },
            messageCount = messageCount,
            createdAt = firstEntry?.createdAt ?: conversation.createdAt,
            updatedAt = lastUpdate?.updatedAt ?: conversation.updatedAt
        )
        DatabaseHelper.updateConversation(updatedConversation)
    }

    private suspend fun resetContextSummary(conversationId: Long) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return
        DatabaseHelper.updateConversation(
            conversation.copy(
                contextSummary = null,
                contextSummaryCutoffEntryDbId = null,
                contextSummaryUpdatedAt = 0
            )
        )
    }

    private suspend fun normalizeInterruptedToolEntries(
        entries: List<AgentConversationEntry>
    ): List<AgentConversationEntry> {
        if (entries.isEmpty()) return entries
        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(entries)
        normalized.forEachIndexed { index, updated ->
            if (updated != entries[index]) {
                upsertEntry(updated.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        return normalized
    }

    private suspend fun normalizeEntriesForDisplay(
        entries: List<AgentConversationEntry>
    ): List<AgentConversationEntry> {
        if (entries.isEmpty()) return entries
        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(
            entries = entries,
            finalizeLatestThinkingEntries = true
        )
        normalized.forEachIndexed { index, updated ->
            if (updated != entries[index]) {
                upsertEntry(updated.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        return normalized
    }

    private fun buildTextMessagePayload(
        messageId: String,
        user: Int,
        text: String,
        attachments: List<Map<String, Any?>>,
        isError: Boolean,
        streamMeta: Map<String, Any?>?,
        createdAt: Long
    ): Map<String, Any?> {
        val safeText = AgentTextSanitizer.sanitizeUtf16(text)
        val content = linkedMapOf<String, Any?>(
            "text" to safeText,
            "id" to messageId
        )
        if (attachments.isNotEmpty()) {
            content["attachments"] = attachments
        }
        return linkedMapOf(
            "id" to messageId,
            "type" to 1,
            "user" to user,
            "content" to content,
            "isLoading" to false,
            "isFirst" to false,
            "isError" to isError,
            "isSummarizing" to false,
            "streamMeta" to streamMeta,
            "createAt" to Instant.ofEpochMilli(createdAt).toString()
        ).filterValues { it != null }
    }

    private fun buildCardMessagePayload(
        messageId: String,
        cardData: Map<String, Any?>,
        isError: Boolean,
        streamMeta: Map<String, Any?>?,
        createdAt: Long
    ): Map<String, Any?> {
        return linkedMapOf(
            "id" to messageId,
            "type" to 2,
            "user" to 3,
            "content" to linkedMapOf(
                "cardData" to cardData,
                "id" to messageId
            ),
            "isLoading" to false,
            "isFirst" to false,
            "isError" to isError,
            "isSummarizing" to false,
            "streamMeta" to streamMeta,
            "createAt" to Instant.ofEpochMilli(createdAt).toString()
        ).filterValues { it != null }
    }

    private fun entryToMessagePayload(entry: AgentConversationEntry): Map<String, Any?>? {
        return when (entry.entryType) {
            ENTRY_TYPE_TOOL_EVENT -> buildToolCardMessage(entry)
            ENTRY_TYPE_USER_MESSAGE,
            ENTRY_TYPE_ASSISTANT_MESSAGE,
            ENTRY_TYPE_UI_CARD -> readMap(entry.payloadJson)
            else -> null
        }
    }

    private fun buildToolCardMessage(entry: AgentConversationEntry): Map<String, Any?> {
        val payload = readMap(entry.payloadJson)
        val messageId = entry.entryId
        val cardData = linkedMapOf<String, Any?>(
            "type" to "agent_tool_summary",
            "taskId" to payload["taskId"],
            "cardId" to payload["cardId"]?.toString().orEmpty().ifEmpty { messageId },
            "toolName" to payload["toolName"]?.toString().orEmpty(),
            "displayName" to payload["displayName"]?.toString().orEmpty(),
            "toolTitle" to payload["toolTitle"]?.toString().orEmpty(),
            "toolType" to payload["toolType"]?.toString().orEmpty().ifEmpty { "builtin" },
            "serverName" to payload["serverName"],
            "status" to entry.status,
            "summary" to payload["summary"]?.toString().orEmpty().ifEmpty { entry.summary },
            "progress" to payload["progress"]?.toString().orEmpty(),
            "argsJson" to payload["argsJson"]?.toString().orEmpty(),
            "resultPreviewJson" to payload["resultPreviewJson"]?.toString().orEmpty(),
            "rawResultJson" to payload["rawResultJson"]?.toString().orEmpty(),
            "terminalOutput" to payload["terminalOutput"]?.toString().orEmpty(),
            "terminalOutputDelta" to payload["terminalOutputDelta"]?.toString().orEmpty(),
            "terminalSessionId" to payload["terminalSessionId"],
            "terminalStreamState" to payload["terminalStreamState"]?.toString().orEmpty(),
            "interruptedBy" to payload["interruptedBy"]?.toString(),
            "interruptionReason" to payload["interruptionReason"]?.toString(),
            "timedOut" to (payload["timedOut"] == true),
            "workspaceId" to payload["workspaceId"],
            "artifacts" to toListOfStringAnyMap(payload["artifacts"]),
            "actions" to toListOfStringAnyMap(payload["actions"]),
            "success" to (payload["success"] ?: (entry.status == STATUS_SUCCESS)),
            "showScheduleAction" to (payload["toolType"]?.toString() == "schedule"),
            "showAlarmAction" to (payload["toolType"]?.toString() == "alarm")
        )
        return buildCardMessagePayload(
            messageId = messageId,
            cardData = cardData,
            isError = entry.status == STATUS_ERROR,
            streamMeta = toStringAnyMap(payload["streamMeta"]).takeIf { it.isNotEmpty() },
            createdAt = entry.createdAt
        )
    }

    private fun mergeToolPayload(
        existing: Map<String, Any?>,
        incoming: Map<String, Any?>,
        fallbackStatus: String,
        fallbackSummary: String
    ): Map<String, Any?> {
        return AgentConversationHistorySupport.mergeToolPayload(
            existing = existing,
            incoming = incoming,
            fallbackStatus = fallbackStatus,
            fallbackSummary = fallbackSummary
        )
    }

    private fun conversationLastMessageFromEntry(entry: AgentConversationEntry): String {
        return when (entry.entryType) {
            ENTRY_TYPE_TOOL_EVENT -> entry.summary.ifBlank { "执行了工具调用" }
            ENTRY_TYPE_UI_CARD -> {
                val payload = readMap(entry.payloadJson)
                val content = toStringAnyMap(payload["content"])
                val cardData = toStringAnyMap(content["cardData"])
                cardData["summary"]?.toString()?.trim().orEmpty().ifEmpty {
                    cardData["type"]?.toString()?.trim().orEmpty().ifEmpty { "卡片消息" }
                }
            }
            else -> {
                val payload = readMap(entry.payloadJson)
                val content = toStringAnyMap(payload["content"])
                AgentTextSanitizer.sanitizeUtf16(
                    content["text"]?.toString()?.trim().orEmpty().ifEmpty { entry.summary }
                )
            }
        }
    }

    private fun extractSummaryFromMessagePayload(message: Map<String, Any?>): String {
        val content = toStringAnyMap(message["content"])
        val text = AgentTextSanitizer.sanitizeUtf16(
            content["text"]?.toString()?.trim().orEmpty()
        )
        if (text.isNotEmpty()) return text
        val cardData = toStringAnyMap(content["cardData"])
        return AgentTextSanitizer.sanitizeUtf16(
            cardData["summary"]?.toString()?.trim().orEmpty()
        )
    }

    private fun readMap(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, Any?>>(
                json,
                object : TypeToken<Map<String, Any?>>() {}.type
            )
        }.getOrElse { emptyMap() }
    }

    private fun toStringAnyMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) return emptyMap()
        return value.entries.associate { (key, rawValue) ->
            key.toString() to rawValue
        }
    }

    private fun toListOfStringAnyMap(value: Any?): List<Map<String, Any?>> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { item -> item?.let(::toStringAnyMap).takeIf { !it.isNullOrEmpty() } }
    }

}
