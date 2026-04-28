package cn.com.omnimind.bot.quicklog

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.util.UUID

data class QuickLogRecord(
    val id: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val source: String,
    val shortMemorySynced: Boolean
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "content" to content,
        "createdAtMillis" to createdAtMillis,
        "updatedAtMillis" to updatedAtMillis,
        "source" to source,
        "shortMemorySynced" to shortMemorySynced
    )
}

class QuickLogService(private val context: Context) {
    companion object {
        private const val TAG = "QuickLogService"
        private const val KEY_QUICK_LOGS = "quick_logs_records_v1"
        private const val MAX_RECORDS = 200

        const val SOURCE_APP = "app"
        const val SOURCE_WIDGET = "widget"
    }

    private val gson = Gson()
    private val mmkv: MMKV? = MMKV.defaultMMKV()
    private val listType = object : TypeToken<List<QuickLogRecord>>() {}.type

    fun listLogs(limit: Int = MAX_RECORDS): List<QuickLogRecord> {
        return loadRecords()
            .sortedByDescending { it.updatedAtMillis }
            .take(limit.coerceIn(1, MAX_RECORDS))
    }

    fun countLogs(): Int = loadRecords().size

    fun addLog(
        content: String,
        source: String = SOURCE_APP
    ): QuickLogRecord {
        val normalized = content.trim()
        require(normalized.isNotEmpty()) { "log content is empty" }

        val now = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        val synced = runCatching {
            WorkspaceMemoryService(context).appendQuickLogMemory(
                logId = recordId,
                content = normalized
            )
            true
        }.onFailure { error ->
            OmniLog.w(TAG, "Failed to sync quick log to short memory: ${error.message}")
        }.getOrDefault(false)

        val record = QuickLogRecord(
            id = recordId,
            content = normalized,
            createdAtMillis = now,
            updatedAtMillis = now,
            source = source,
            shortMemorySynced = synced
        )
        val nextRecords = buildList {
            add(record)
            addAll(loadRecords().filterNot { it.id == record.id })
        }.sortedByDescending { it.updatedAtMillis }
            .take(MAX_RECORDS)

        saveRecords(nextRecords)
        QuickLogWidgetUpdater.updateAll(context)
        return record
    }

    fun updateLog(id: String, content: String): QuickLogRecord? {
        val targetId = id.trim()
        val normalized = content.trim()
        require(targetId.isNotEmpty()) { "log id is empty" }
        require(normalized.isNotEmpty()) { "log content is empty" }

        val current = loadRecords()
        val existing = current.firstOrNull { it.id == targetId } ?: return null
        val synced = runCatching {
            val service = WorkspaceMemoryService(context)
            service.updateQuickLogMemory(
                logId = targetId,
                previousContent = existing.content,
                newContent = normalized
            ) ?: service.appendQuickLogMemory(
                logId = targetId,
                content = normalized
            )
            true
        }.onFailure { error ->
            OmniLog.w(TAG, "Failed to update synced short memory: ${error.message}")
        }.getOrDefault(false)
        val updated = current.map { record ->
            if (record.id != targetId) {
                record
            } else {
                record.copy(
                    content = normalized,
                    updatedAtMillis = System.currentTimeMillis(),
                    shortMemorySynced = synced
                )
            }
        }
        val target = updated.firstOrNull { it.id == targetId } ?: return null
        saveRecords(updated.sortedByDescending { it.updatedAtMillis }.take(MAX_RECORDS))
        QuickLogWidgetUpdater.updateAll(context)
        return target
    }

    fun deleteLog(id: String): Boolean {
        val targetId = id.trim()
        require(targetId.isNotEmpty()) { "log id is empty" }

        val current = loadRecords()
        val existing = current.firstOrNull { it.id == targetId } ?: return false
        runCatching {
            WorkspaceMemoryService(context).deleteQuickLogMemory(
                logId = targetId,
                contentHint = existing.content
            )
        }.onFailure { error ->
            OmniLog.w(TAG, "Failed to delete synced short memory: ${error.message}")
        }
        val next = current.filterNot { it.id == targetId }
        if (next.size == current.size) {
            return false
        }
        saveRecords(next)
        QuickLogWidgetUpdater.updateAll(context)
        return true
    }

    fun latestLogsForWidget(limit: Int = 3): List<QuickLogRecord> {
        return loadRecords()
            .sortedByDescending { it.updatedAtMillis }
            .take(limit.coerceAtLeast(1))
    }

    fun getLog(id: String): QuickLogRecord? {
        val targetId = id.trim()
        if (targetId.isEmpty()) {
            return null
        }
        return loadRecords().firstOrNull { it.id == targetId }
    }

    private fun loadRecords(): List<QuickLogRecord> {
        val raw = mmkv?.getString(KEY_QUICK_LOGS, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson<List<QuickLogRecord>>(raw, listType).orEmpty()
        }.onFailure { error ->
            OmniLog.w(TAG, "Failed to parse quick logs: ${error.message}")
        }.getOrDefault(emptyList())
    }

    private fun saveRecords(records: List<QuickLogRecord>) {
        mmkv?.encode(KEY_QUICK_LOGS, gson.toJson(records))
    }
}
