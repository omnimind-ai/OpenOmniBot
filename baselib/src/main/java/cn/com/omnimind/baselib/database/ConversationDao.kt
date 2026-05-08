package cn.com.omnimind.baselib.database

import androidx.room.*

@Dao
interface ConversationDao {

    companion object {
        const val MAX_TITLE_CHARS = 512
        const val MAX_SUMMARY_CHARS = 2048
        const val MAX_LAST_MESSAGE_CHARS = 2048
        const val MAX_CONTEXT_SUMMARY_CHARS = 32 * 1024
        private const val SAFE_CONTEXT_SUMMARY_HEAD_CHARS = 10 * 1024
        private const val SAFE_CONTEXT_SUMMARY_TAIL_CHARS = 20 * 1024

        const val SAFE_CONVERSATION_PROJECTION = """
            id,
            CASE
                WHEN LENGTH(title) > $MAX_TITLE_CHARS THEN substr(title, 1, $MAX_TITLE_CHARS)
                ELSE title
            END AS title,
            mode,
            isArchived,
            CASE
                WHEN summary IS NOT NULL AND LENGTH(summary) > $MAX_SUMMARY_CHARS
                    THEN substr(summary, 1, $MAX_SUMMARY_CHARS)
                ELSE summary
            END AS summary,
            CASE
                WHEN contextSummary IS NOT NULL
                    AND LENGTH(contextSummary) > $MAX_CONTEXT_SUMMARY_CHARS
                    THEN substr(contextSummary, 1, $SAFE_CONTEXT_SUMMARY_HEAD_CHARS) ||
                        '

[Context summary truncated to avoid Android CursorWindow row limits]

' ||
                        substr(contextSummary, -$SAFE_CONTEXT_SUMMARY_TAIL_CHARS)
                ELSE contextSummary
            END AS contextSummary,
            contextSummaryCutoffEntryDbId,
            contextSummaryUpdatedAt,
            status,
            CASE
                WHEN lastMessage IS NOT NULL AND LENGTH(lastMessage) > $MAX_LAST_MESSAGE_CHARS
                    THEN substr(lastMessage, 1, $MAX_LAST_MESSAGE_CHARS)
                ELSE lastMessage
            END AS lastMessage,
            messageCount,
            latestPromptTokens,
            promptTokenThreshold,
            latestPromptTokensUpdatedAt,
            createdAt,
            updatedAt
        """
    }

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Delete
    suspend fun delete(conversation: Conversation)

    @Query(
        """
        SELECT
            $SAFE_CONVERSATION_PROJECTION
        FROM conversations
        WHERE id = :id
        """
    )
    suspend fun getById(id: Long): Conversation?

    @Query(
        """
        SELECT
            $SAFE_CONVERSATION_PROJECTION
        FROM conversations
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getAll(): List<Conversation>

    @Query(
        """
        SELECT
            $SAFE_CONVERSATION_PROJECTION
        FROM conversations
        ORDER BY updatedAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getConversationsByPage(offset: Int, limit: Int): List<Conversation>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAll(): Int

    @Query("UPDATE conversations SET messageCount = messageCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementMessageCount(id: Long, updatedAt: Long)

    @Query(
        """
        SELECT
            $SAFE_CONVERSATION_PROJECTION
        FROM conversations
        WHERE status = :status
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getByStatus(status: Int): List<Conversation>
}
