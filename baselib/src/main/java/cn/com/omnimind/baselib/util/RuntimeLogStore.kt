package cn.com.omnimind.baselib.util

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.util.UUID

data class RuntimeLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val tag: String = "",
    val message: String = "",
    val stackTrace: String? = null,
    val isCrash: Boolean = false,
) {
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "createdAt" to createdAt,
            "level" to level,
            "tag" to tag,
            "message" to message,
            "stackTrace" to stackTrace,
            "isCrash" to isCrash,
        )
    }
}

object RuntimeLogStore {
    private const val TAG = "RuntimeLogStore"
    private const val KEY_RUNTIME_LOGS = "runtime_logs_v1"
    private const val MAX_LOG_COUNT = 200

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    private val listType = object : TypeToken<List<RuntimeLogEntry>>() {}.type

    @Synchronized
    fun append(entry: RuntimeLogEntry) {
        val mmkv = MMKV.defaultMMKV() ?: return
        val current = readEntriesLocked(mmkv)
        val updated = buildList {
            add(entry)
            current.forEach { existing ->
                if (existing.id != entry.id) {
                    add(existing)
                }
            }
        }.take(MAX_LOG_COUNT)
        mmkv.encode(KEY_RUNTIME_LOGS, gson.toJson(updated))
    }

    @Synchronized
    fun listRecent(limit: Int = MAX_LOG_COUNT): List<RuntimeLogEntry> {
        val mmkv = MMKV.defaultMMKV() ?: return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_LOG_COUNT)
        return readEntriesLocked(mmkv).take(safeLimit)
    }

    @Synchronized
    fun clear() {
        val mmkv = MMKV.defaultMMKV() ?: return
        mmkv.remove(KEY_RUNTIME_LOGS)
    }

    private fun readEntriesLocked(mmkv: MMKV): List<RuntimeLogEntry> {
        val raw = mmkv.decodeString(KEY_RUNTIME_LOGS)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson<List<RuntimeLogEntry>>(raw, listType) ?: emptyList()
        }.getOrElse {
            OmniLog.w(TAG, "read logs failed: ${it.message}")
            emptyList()
        }
    }
}
