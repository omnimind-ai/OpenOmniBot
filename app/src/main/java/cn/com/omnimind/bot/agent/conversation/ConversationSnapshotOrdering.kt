package cn.com.omnimind.bot.agent

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal object ConversationSnapshotOrdering {

    internal data class PreparedMessage(
        val payload: Map<String, Any?>,
        val createdAt: Long,
        val taskAnchor: Long,
        val streamSeq: Long?,
        val phaseRank: Int,
        val sequenceRank: Int,
        val originalIndex: Int
    )

    fun prepareForStorage(messages: List<Map<String, Any?>>): List<PreparedMessage> {
        val fallbackCreatedAt = System.currentTimeMillis()
        return messages.mapIndexed { index, message ->
            val createdAt = resolveCreatedAtMillis(message) ?: fallbackCreatedAt
            PreparedMessage(
                payload = message,
                createdAt = createdAt,
                taskAnchor = resolveTaskAnchorMillis(message) ?: createdAt,
                streamSeq = resolveStreamSeq(message),
                phaseRank = resolvePhaseRank(message),
                sequenceRank = resolveSequenceRank(message),
                originalIndex = index
            )
        }.sortedWith(
            compareBy<PreparedMessage> { it.taskAnchor }
                .thenBy { resolveTurnRank(it.payload) }
                .thenBy { it.streamSeq ?: Long.MAX_VALUE }
                .thenBy { it.createdAt }
                .thenBy { it.phaseRank }
                .thenBy { it.sequenceRank }
                .thenByDescending { it.originalIndex }
        )
    }

    fun sortForDisplay(messages: List<Map<String, Any?>>): List<Map<String, Any?>> {
        return prepareForStorage(messages)
            .asReversed()
            .map { it.payload }
    }

    fun resolveCreatedAtMillis(message: Map<String, Any?>): Long? {
        return resolveDeepThinkingStartTime(message)
            ?: parseCreatedAtMillis(message["createAt"])
            ?: parseIdTimestamp(message["id"])
            ?: parseIdTimestamp(contentValue(message, "id"))
    }

    private fun contentValue(message: Map<String, Any?>, key: String): Any? {
        val content = message["content"] as? Map<*, *> ?: return null
        return content[key]
    }

    private fun resolveDeepThinkingStartTime(message: Map<String, Any?>): Long? {
        val cardData = cardData(message) ?: return null
        if (cardData["type"]?.toString() != "deep_thinking") {
            return null
        }
        return parseCreatedAtMillis(cardData["startTime"])
    }

    private fun cardData(message: Map<String, Any?>): Map<String, Any?>? {
        val content = message["content"] as? Map<*, *> ?: return null
        val cardData = content["cardData"] as? Map<*, *> ?: return null
        return cardData.entries.associate { (key, value) ->
            key.toString() to value
        }
    }

    private fun resolveTaskAnchorMillis(message: Map<String, Any?>): Long? {
        val taskId = resolveTaskId(message)
        return parseIdTimestamp(taskId)
            ?: parseIdTimestamp(message["id"])
            ?: parseIdTimestamp(contentValue(message, "id"))
    }

    private fun resolveTaskId(message: Map<String, Any?>): String? {
        val topLevelId = message["id"]?.toString()?.trim().orEmpty()
        val cardData = cardData(message)
        val cardTaskId = cardData?.get("taskID")?.toString()?.trim().orEmpty()
        if (cardTaskId.isNotEmpty()) {
            return cardTaskId
        }
        val toolTaskId = cardData?.get("taskId")?.toString()?.trim().orEmpty()
        if (toolTaskId.isNotEmpty()) {
            return toolTaskId
        }
        return when {
            topLevelId.endsWith("-user") -> topLevelId.removeSuffix("-user")
            topLevelId.endsWith("-assistant") -> topLevelId.removeSuffix("-assistant")
            topLevelId.endsWith("-clarify") -> topLevelId.removeSuffix("-clarify")
            topLevelId.endsWith("-permission") -> topLevelId.removeSuffix("-permission")
            topLevelId.endsWith("-thinking") -> topLevelId.removeSuffix("-thinking")
            topLevelId.contains("-thinking-") -> topLevelId.substringBefore("-thinking-")
            topLevelId.endsWith("-text") -> topLevelId.removeSuffix("-text")
            topLevelId.contains("-text-") -> topLevelId.substringBefore("-text-")
            topLevelId.contains("-tool-") -> topLevelId.substringBefore("-tool-")
            else -> topLevelId.ifEmpty { null }
        }
    }

    private fun resolveStreamSeq(message: Map<String, Any?>): Long? {
        val streamMeta = streamMeta(message) ?: return null
        return parseCreatedAtMillis(streamMeta["seq"])
    }

    private fun streamMeta(message: Map<String, Any?>): Map<String, Any?>? {
        val raw = message["streamMeta"] as? Map<*, *> ?: return null
        return raw.entries.associate { (key, value) ->
            key.toString() to value
        }
    }

    private fun resolvePhaseRank(message: Map<String, Any?>): Int {
        val type = (message["type"] as? Number)?.toInt()
        val user = (message["user"] as? Number)?.toInt()
        val cardType = cardData(message)?.get("type")?.toString().orEmpty()
        return when {
            type == 1 && user == 1 -> 0
            type == 2 && cardType == "deep_thinking" -> 1
            type == 1 && user == 2 -> 2
            type == 2 -> 3
            else -> 4
        }
    }

    private fun resolveTurnRank(message: Map<String, Any?>): Int {
        val type = (message["type"] as? Number)?.toInt()
        val user = (message["user"] as? Number)?.toInt()
        return if (type == 1 && user == 1) 0 else 1
    }

    private fun resolveSequenceRank(message: Map<String, Any?>): Int {
        val id = message["id"]?.toString()?.trim().orEmpty()
        return when {
            id.contains("-thinking-") -> id.substringAfterLast("-thinking-").toIntOrNull() ?: 1
            id.endsWith("-thinking") -> 1
            id.contains("-text-") -> id.substringAfterLast("-text-").toIntOrNull() ?: 1
            id.endsWith("-text") -> 1
            id.contains("-tool-") -> id.substringAfterLast("-tool-").toIntOrNull() ?: 1
            else -> 0
        }
    }

    private fun parseCreatedAtMillis(raw: Any?): Long? {
        return when (raw) {
            null -> null
            is Number -> raw.toLong()
            is String -> parseCreatedAtString(raw)
            else -> parseCreatedAtString(raw.toString())
        }
    }

    private fun parseCreatedAtString(raw: String): Long? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            return null
        }

        normalized.toLongOrNull()?.let { return it }

        runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()?.let {
            return it
        }
        runCatching { OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }.getOrNull()
            ?.let {
                return it
            }
        runCatching {
            LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()?.let {
            return it
        }

        return null
    }

    private fun parseIdTimestamp(raw: Any?): Long? {
        val normalized = raw?.toString()?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        val prefix = normalized.takeWhile(Char::isDigit)
        return prefix.toLongOrNull()
    }
}
