package cn.com.omnimind.baselib.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "token_usage_records",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["conversationId"])
    ]
)
data class TokenUsageRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val isLocal: Boolean,
    val model: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val textTokens: Int = 0,
    val cachedTokens: Int = 0,
    val createdAt: Long = Date().time
)
