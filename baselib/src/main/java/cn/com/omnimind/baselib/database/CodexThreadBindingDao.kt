package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CodexThreadBindingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: CodexThreadBinding)

    @Query("SELECT * FROM codex_thread_bindings WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getByConversationId(conversationId: Long): CodexThreadBinding?

    @Query("SELECT * FROM codex_thread_bindings WHERE threadId = :threadId LIMIT 1")
    suspend fun getByThreadId(threadId: String): CodexThreadBinding?

    @Query("DELETE FROM codex_thread_bindings WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long): Int
}
