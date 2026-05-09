package cn.com.omnimind.baselib.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "codex_thread_bindings",
    indices = [
        Index(value = ["threadId"], unique = true),
        Index(value = ["updatedAt"])
    ]
)
data class CodexThreadBinding(
    @PrimaryKey
    val conversationId: Long,
    val threadId: String,
    val cwd: String,
    val createdAt: Long,
    val updatedAt: Long
)
