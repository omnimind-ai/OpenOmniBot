package cn.com.omnimind.baselib.database

data class AgentConversationEntryRecord(
    val id: Long,
    val conversationId: Long,
    val conversationMode: String,
    val entryId: String,
    val entryType: String,
    val status: String,
    val summary: String,
    val payloadJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val payloadOriginalLength: Int,
    val payloadTruncated: Boolean,
    val summaryOriginalLength: Int,
    val summaryTruncated: Boolean
) {
    fun toEntry(): AgentConversationEntry {
        return AgentConversationEntry(
            id = id,
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            entryType = entryType,
            status = status,
            summary = summary,
            payloadJson = payloadJson,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
