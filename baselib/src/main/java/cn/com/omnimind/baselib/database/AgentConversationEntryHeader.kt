package cn.com.omnimind.baselib.database

data class AgentConversationEntryHeader(
    val id: Long,
    val conversationId: Long,
    val conversationMode: String,
    val entryId: String,
    val entryType: String,
    val status: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long
)
