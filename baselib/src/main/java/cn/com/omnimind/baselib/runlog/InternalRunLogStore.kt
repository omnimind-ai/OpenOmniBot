package cn.com.omnimind.baselib.runlog

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InternalRunLogRecord(
    val runId: String = "",
    val goal: String = "",
    val source: String = "internal_oob",
    val toolName: String = "",
    val operationDescription: String = "",
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null,
    val success: Boolean? = null,
    val doneReason: String = "",
    val errorMessage: String = "",
    val cards: List<Map<String, Any?>> = emptyList()
)

object InternalRunLogStore {
    private const val TAG = "InternalRunLogStore"
    private const val PROVIDER = "internal_oob"
    private const val STORAGE_DIR_NAME = "internal_run_logs"
    private const val MAX_RUN_COUNT = 200

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    @Synchronized
    fun beginRun(
        context: Context,
        runId: String,
        goal: String,
        source: String,
        toolName: String = "",
        operationDescription: String = goal
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = readRunLocked(context, normalizedRunId)
        val record = (existing ?: InternalRunLogRecord(
            runId = normalizedRunId,
            startedAtMs = now
        )).copy(
            goal = goal.ifBlank { existing?.goal.orEmpty() },
            source = source.ifBlank { existing?.source ?: PROVIDER },
            toolName = toolName.ifBlank { existing?.toolName.orEmpty() },
            operationDescription = operationDescription.ifBlank {
                existing?.operationDescription.orEmpty()
            },
            finishedAtMs = null,
            success = null,
            doneReason = "",
            errorMessage = ""
        )
        saveRunLocked(context, record)
        pruneLocked(context)
    }

    @Synchronized
    fun appendCard(context: Context, runId: String, card: Map<String, Any?>) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        saveRunLocked(context, record.copy(cards = record.cards + sanitizeMap(card)))
        pruneLocked(context)
    }

    @Synchronized
    fun appendCards(context: Context, runId: String, cards: List<Map<String, Any?>>) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty() || cards.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        saveRunLocked(context, record.copy(cards = record.cards + cards.map(::sanitizeMap)))
        pruneLocked(context)
    }

    @Synchronized
    fun upsertCard(
        context: Context,
        runId: String,
        cardId: String,
        card: Map<String, Any?>
    ) {
        val normalizedRunId = runId.trim()
        val normalizedCardId = cardId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        val sanitizedCard = sanitizeMap(
            if (normalizedCardId.isEmpty() || card.containsKey("card_id")) {
                card
            } else {
                linkedMapOf<String, Any?>("card_id" to normalizedCardId).apply {
                    putAll(card)
                }
            }
        )
        val updatedCards = record.cards.toMutableList()
        val replaceIndex = if (normalizedCardId.isEmpty()) {
            -1
        } else {
            updatedCards.indexOfFirst { existing ->
                val existingId = existing["card_id"]?.toString()?.trim().orEmpty()
                existingId == normalizedCardId
            }
        }
        if (replaceIndex >= 0) {
            updatedCards[replaceIndex] = sanitizedCard
        } else {
            updatedCards += sanitizedCard
        }
        saveRunLocked(context, record.copy(cards = updatedCards))
        pruneLocked(context)
    }

    @Synchronized
    fun finishRun(
        context: Context,
        runId: String,
        success: Boolean,
        doneReason: String,
        errorMessage: String? = null
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: InternalRunLogRecord(runId = normalizedRunId)
        saveRunLocked(
            context,
            record.copy(
                finishedAtMs = System.currentTimeMillis(),
                success = success,
                doneReason = doneReason,
                errorMessage = errorMessage.orEmpty()
            )
        )
        pruneLocked(context)
    }

    @Synchronized
    fun listRuns(context: Context, limit: Int = 50): Map<String, Any?> {
        val safeLimit = limit.coerceIn(1, MAX_RUN_COUNT)
        val runs = readAllRunsLocked(context)
            .sortedByDescending { it.startedAtMs }
            .take(safeLimit)
        val dir = storageDir(context)
        return linkedMapOf(
            "success" to true,
            "count" to runs.size,
            "provider" to PROVIDER,
            "run_index_path" to File(dir, "index.json").absolutePath,
            "run_storage_dir" to dir.absolutePath,
            "runs" to runs.map(::summaryMap)
        )
    }

    @Synchronized
    fun timelinePayload(context: Context, runId: String): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return notFoundPayload(normalizedRunId)
        }
        val record = readRunLocked(context, normalizedRunId)
            ?: return notFoundPayload(normalizedRunId)
        return linkedMapOf(
            "success" to true,
            "provider" to PROVIDER,
            "run_id" to record.runId,
            "goal" to record.goal,
            "source" to record.source,
            "tool_name" to record.toolName,
            "operation_description" to record.operationDescription,
            "started_at" to formatTime(record.startedAtMs),
            "finished_at" to record.finishedAtMs?.let(::formatTime).orEmpty(),
            "duration_ms" to durationMs(record),
            "step_count" to record.cards.size,
            "done_reason" to record.doneReason,
            "error_message" to record.errorMessage,
            "cards" to record.cards
        )
    }

    private fun summaryMap(record: InternalRunLogRecord): Map<String, Any?> {
        return linkedMapOf(
            "run_id" to record.runId,
            "goal" to record.goal,
            "success" to (record.success == true),
            "done_reason" to record.doneReason,
            "step_count" to record.cards.size,
            "started_at" to formatTime(record.startedAtMs),
            "finished_at" to record.finishedAtMs?.let(::formatTime).orEmpty(),
            "duration_ms" to durationMs(record),
            "tool_name" to record.toolName,
            "source" to record.source,
            "operation_description" to record.operationDescription,
            "error_message" to record.errorMessage,
            "raw_run" to linkedMapOf(
                "run_id" to record.runId,
                "provider" to PROVIDER,
                "source" to record.source,
                "goal" to record.goal
            )
        )
    }

    private fun notFoundPayload(runId: String): Map<String, Any?> {
        return linkedMapOf(
            "success" to false,
            "provider" to PROVIDER,
            "run_id" to runId,
            "error_code" to "NOT_FOUND",
            "error_message" to "Internal run log not found"
        )
    }

    private fun durationMs(record: InternalRunLogRecord): Long? {
        val finishedAt = record.finishedAtMs ?: return null
        return (finishedAt - record.startedAtMs).coerceAtLeast(0L)
    }

    private fun readAllRunsLocked(context: Context): List<InternalRunLogRecord> {
        val dir = storageDir(context)
        return dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> readRunFileLocked(file) }
            .orEmpty()
    }

    private fun readRunLocked(context: Context, runId: String): InternalRunLogRecord? {
        return readRunFileLocked(runFile(context, runId))
    }

    private fun readRunFileLocked(file: File): InternalRunLogRecord? {
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(), InternalRunLogRecord::class.java)
        }.getOrElse {
            OmniLog.w(TAG, "read run log failed: ${file.absolutePath}, ${it.message}")
            null
        }
    }

    private fun saveRunLocked(context: Context, record: InternalRunLogRecord) {
        val file = runFile(context, record.runId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeText(gson.toJson(record))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(record))
                tmp.delete()
            }
        }.onFailure {
            OmniLog.w(TAG, "save run log failed: ${file.absolutePath}, ${it.message}")
            tmp.delete()
        }
    }

    private fun pruneLocked(context: Context) {
        val dir = storageDir(context)
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.drop(MAX_RUN_COUNT).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun storageDir(context: Context): File {
        return File(context.applicationContext.filesDir, STORAGE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun runFile(context: Context, runId: String): File {
        return File(storageDir(context), "${sha256(runId).take(16)}_${safeFilePart(runId)}.json")
    }

    private fun safeFilePart(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "run" }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeMap(value: Map<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, sanitizeValue(item))
            }
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String, is Number, is Boolean -> value
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) {
                        put(key.toString(), sanitizeValue(item))
                    }
                }
            }
            is List<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }
    }

    private fun formatTime(ms: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return formatter.format(Date(ms))
    }
}
