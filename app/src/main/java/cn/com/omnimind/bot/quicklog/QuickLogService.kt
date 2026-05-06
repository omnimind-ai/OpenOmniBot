package cn.com.omnimind.bot.quicklog

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAlarmCreateRequest
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class QuickLogRecord(
    val id: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val source: String?,
    val shortMemorySynced: Boolean,
    val listId: String? = QuickLogService.LIST_TASKS,
    val isImportant: Boolean = false,
    val isCompleted: Boolean = false,
    val dueAtMillis: Long? = null,
    val reminderAtMillis: Long? = null,
    val repeatRule: String? = null,
    val reminderAlarmId: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "content" to content,
        "createdAtMillis" to createdAtMillis,
        "updatedAtMillis" to updatedAtMillis,
        "source" to source,
        "shortMemorySynced" to shortMemorySynced,
        "listId" to listId,
        "isImportant" to isImportant,
        "isCompleted" to isCompleted,
        "dueAtMillis" to dueAtMillis,
        "reminderAtMillis" to reminderAtMillis,
        "repeatRule" to repeatRule,
        "reminderAlarmId" to reminderAlarmId
    )
}

data class QuickLogWidgetSettings(
    val selectedListId: String = QuickLogService.LIST_TASKS,
    val isListMenuExpanded: Boolean = false,
    val opacityPercent: Int = 92,
    val colorTheme: String = QuickLogService.COLOR_DARK,
    val fontSize: String = QuickLogService.FONT_REGULAR,
    val showCompleted: Boolean = true,
    val showDetails: Boolean = true
)

class QuickLogService(private val context: Context) {
    companion object {
        private const val TAG = "QuickLogService"
        private const val KEY_QUICK_LOGS = "quick_logs_records_v1"
        private const val KEY_WIDGET_SETTINGS = "quick_logs_widget_settings_v1"
        private const val MAX_RECORDS = 200

        const val SOURCE_APP = "app"
        const val SOURCE_WIDGET = "widget"

        const val LIST_MY_DAY = "my_day"
        const val LIST_IMPORTANT = "important"
        const val LIST_PLANNED = "planned"
        const val LIST_TASKS = "tasks"

        const val REPEAT_NONE = "none"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKDAYS = "weekdays"
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_MONTHLY = "monthly"
        const val REPEAT_YEARLY = "yearly"
        const val REPEAT_CUSTOM = "custom"
        private const val REPEAT_CUSTOM_PREFIX = "custom:"
        const val REPEAT_UNIT_DAY = "day"
        const val REPEAT_UNIT_WEEK = "week"
        const val REPEAT_UNIT_MONTH = "month"
        const val REPEAT_UNIT_YEAR = "year"

        const val COLOR_DARK = "dark"
        const val COLOR_LIGHT = "light"
        const val COLOR_BLUE = "blue"
        const val COLOR_PINK = "pink"
        const val COLOR_GREEN = "green"

        const val FONT_SMALL = "small"
        const val FONT_REGULAR = "regular"
        const val FONT_LARGE = "large"

        val listIds: List<String> = listOf(
            LIST_MY_DAY,
            LIST_IMPORTANT,
            LIST_PLANNED,
            LIST_TASKS
        )

        fun listLabel(listId: String): String = when (listId) {
            LIST_MY_DAY -> "我的一天"
            LIST_IMPORTANT -> "重要"
            LIST_PLANNED -> "计划内"
            else -> "任务"
        }

        fun repeatLabel(repeatRule: String?): String? {
            customRepeatParts(repeatRule)?.let { (interval, unit) ->
                val unitLabel = when (unit) {
                    REPEAT_UNIT_WEEK -> "周"
                    REPEAT_UNIT_MONTH -> "个月"
                    REPEAT_UNIT_YEAR -> "年"
                    else -> "天"
                }
                return "每 $interval $unitLabel"
            }
            return when (repeatRule) {
                REPEAT_DAILY -> "每 1 天"
                REPEAT_WEEKDAYS -> "工作日"
                REPEAT_WEEKLY -> "每 1 周"
                REPEAT_MONTHLY -> "每 1 个月"
                REPEAT_YEARLY -> "每 1 年"
                REPEAT_CUSTOM -> "自定义"
                else -> null
            }
        }

        fun customRepeatRule(interval: Int, unit: String): String {
            return "$REPEAT_CUSTOM_PREFIX${interval.coerceIn(1, 999)}:${normalizeRepeatUnit(unit)}"
        }

        fun customRepeatParts(repeatRule: String?): Pair<Int, String>? {
            val normalized = repeatRule?.trim().orEmpty()
            if (!normalized.startsWith(REPEAT_CUSTOM_PREFIX)) {
                return null
            }
            val parts = normalized.removePrefix(REPEAT_CUSTOM_PREFIX).split(":")
            if (parts.size != 2) {
                return null
            }
            val interval = parts[0].toIntOrNull()?.coerceIn(1, 999) ?: return null
            return interval to normalizeRepeatUnit(parts[1])
        }

        fun normalizeRepeatUnit(unit: String): String {
            return when (unit.trim()) {
                REPEAT_UNIT_WEEK -> REPEAT_UNIT_WEEK
                REPEAT_UNIT_MONTH -> REPEAT_UNIT_MONTH
                REPEAT_UNIT_YEAR -> REPEAT_UNIT_YEAR
                else -> REPEAT_UNIT_DAY
            }
        }
    }

    private val gson = Gson()
    private val mmkv: MMKV? = MMKV.defaultMMKV()
    private val listType = object : TypeToken<List<QuickLogRecord>>() {}.type

    fun listLogs(
        limit: Int = MAX_RECORDS,
        listId: String = LIST_TASKS,
        includeCompleted: Boolean = true
    ): List<QuickLogRecord> {
        val normalizedListId = normalizeListId(listId)
        return loadRecords()
            .filter { record ->
                matchesList(record, normalizedListId) &&
                    (includeCompleted || !record.isCompleted)
            }
            .sortedWith(
                compareBy<QuickLogRecord> { it.isCompleted }
                    .thenBy { it.dueAtMillis ?: Long.MAX_VALUE }
                    .thenByDescending { it.updatedAtMillis }
            )
            .take(limit.coerceIn(1, MAX_RECORDS))
    }

    fun countLogs(): Int = loadRecords().size

    fun addLog(
        content: String,
        source: String = SOURCE_APP,
        listId: String = LIST_TASKS,
        isImportant: Boolean = false,
        dueAtMillis: Long? = null,
        reminderAtMillis: Long? = null,
        repeatRule: String? = null
    ): QuickLogRecord {
        val normalized = content.trim()
        require(normalized.isNotEmpty()) { "log content is empty" }

        val now = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        val normalizedListId = normalizeListId(listId)
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
            shortMemorySynced = synced,
            listId = normalizedListId,
            isImportant = isImportant || normalizedListId == LIST_IMPORTANT,
            isCompleted = false,
            dueAtMillis = dueAtMillis,
            reminderAtMillis = reminderAtMillis,
            repeatRule = normalizeRepeatRule(repeatRule)
        ).let { created ->
            created.copy(
                reminderAlarmId = scheduleReminder(
                    recordId = created.id,
                    content = created.content,
                    reminderAtMillis = created.reminderAtMillis
                )
            )
        }
        val nextRecords = buildList {
            add(record)
            addAll(loadRecords().filterNot { it.id == record.id })
        }.sortedByDescending { it.updatedAtMillis }
            .take(MAX_RECORDS)

        saveRecords(nextRecords)
        QuickLogWidgetUpdater.updateAll(context)
        return record
    }

    fun updateLog(
        id: String,
        content: String,
        listId: String? = null,
        isImportant: Boolean? = null,
        dueAtMillis: Long? = null,
        reminderAtMillis: Long? = null,
        repeatRule: String? = null,
        updateTaskMetadata: Boolean = false
    ): QuickLogRecord? {
        val targetId = id.trim()
        val normalized = content.trim()
        require(targetId.isNotEmpty()) { "log id is empty" }
        require(normalized.isNotEmpty()) { "log content is empty" }

        val current = loadRecords()
        val existing = current.firstOrNull { it.id == targetId } ?: return null
        val nextListId = normalizeListId(listId ?: existing.listId)
        val nextDueAtMillis = if (updateTaskMetadata) dueAtMillis else existing.dueAtMillis
        val nextReminderAtMillis = if (updateTaskMetadata) {
            reminderAtMillis
        } else {
            existing.reminderAtMillis
        }
        val nextRepeatRule = if (updateTaskMetadata) {
            normalizeRepeatRule(repeatRule)
        } else {
            existing.repeatRule
        }
        val nextReminderAlarmId = rescheduleReminderIfNeeded(
            recordId = existing.id,
            previousContent = existing.content,
            content = normalized,
            previousAlarmId = existing.reminderAlarmId,
            previousReminderAtMillis = existing.reminderAtMillis,
            nextReminderAtMillis = nextReminderAtMillis
        )
        val synced = if (existing.isCompleted) {
            false
        } else {
            runCatching {
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
        }
        val updated = current.map { record ->
            if (record.id != targetId) {
                record
            } else {
                record.copy(
                    content = normalized,
                    updatedAtMillis = System.currentTimeMillis(),
                    shortMemorySynced = synced,
                    listId = nextListId,
                    isImportant = isImportant ?: (record.isImportant || nextListId == LIST_IMPORTANT),
                    dueAtMillis = nextDueAtMillis,
                    reminderAtMillis = nextReminderAtMillis,
                    repeatRule = nextRepeatRule,
                    reminderAlarmId = nextReminderAlarmId
                )
            }
        }
        val target = updated.firstOrNull { it.id == targetId } ?: return null
        saveRecords(updated.sortedByDescending { it.updatedAtMillis }.take(MAX_RECORDS))
        QuickLogWidgetUpdater.updateAll(context)
        return target
    }

    fun toggleCompleted(id: String): QuickLogRecord? {
        val targetId = id.trim()
        if (targetId.isEmpty()) return null
        val current = loadRecords()
        var updatedRecord: QuickLogRecord? = null
        var previousRecord: QuickLogRecord? = null
        val updated = current.map { record ->
            if (record.id == targetId) {
                previousRecord = record
                record.copy(
                    isCompleted = !record.isCompleted,
                    updatedAtMillis = System.currentTimeMillis()
                ).also { updatedRecord = it }
            } else {
                record
            }
        }
        if (updatedRecord != null) {
            val synced = previousRecord?.let { previous ->
                syncQuickLogMemory(previous, updatedRecord!!)
            } ?: false
            val finalUpdated = updated.map { record ->
                if (record.id == targetId) {
                    record.copy(shortMemorySynced = synced)
                } else {
                    record
                }
            }
            updatedRecord = finalUpdated.firstOrNull { it.id == targetId }
            saveRecords(finalUpdated.sortedByDescending { it.updatedAtMillis }.take(MAX_RECORDS))
            QuickLogWidgetUpdater.updateAll(context)
        }
        return updatedRecord
    }

    fun toggleImportant(id: String): QuickLogRecord? {
        val targetId = id.trim()
        if (targetId.isEmpty()) return null
        val current = loadRecords()
        var updatedRecord: QuickLogRecord? = null
        val updated = current.map { record ->
            if (record.id == targetId) {
                record.copy(
                    isImportant = !record.isImportant,
                    updatedAtMillis = System.currentTimeMillis()
                ).also { updatedRecord = it }
            } else {
                record
            }
        }
        if (updatedRecord != null) {
            saveRecords(updated.sortedByDescending { it.updatedAtMillis }.take(MAX_RECORDS))
            QuickLogWidgetUpdater.updateAll(context)
        }
        return updatedRecord
    }

    fun deleteLog(id: String): Boolean {
        val targetId = id.trim()
        require(targetId.isNotEmpty()) { "log id is empty" }

        val current = loadRecords()
        val existing = current.firstOrNull { it.id == targetId } ?: return false
        cancelReminder(existing.reminderAlarmId)
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

    fun latestLogsForWidget(limit: Int = 20): List<QuickLogRecord> {
        val settings = getWidgetSettings()
        return listLogs(
            limit = limit,
            listId = settings.selectedListId,
            includeCompleted = settings.selectedListId == LIST_TASKS && settings.showCompleted
        )
    }

    fun getLog(id: String): QuickLogRecord? {
        val targetId = id.trim()
        if (targetId.isEmpty()) {
            return null
        }
        return loadRecords().firstOrNull { it.id == targetId }
    }

    fun getWidgetSettings(): QuickLogWidgetSettings {
        val raw = mmkv?.getString(KEY_WIDGET_SETTINGS, null).orEmpty()
        val parsed = if (raw.isBlank()) {
            null
        } else {
            runCatching {
                gson.fromJson(raw, QuickLogWidgetSettings::class.java)
            }.onFailure { error ->
                OmniLog.w(TAG, "Failed to parse widget settings: ${error.message}")
            }.getOrNull()
        }
        return normalizeSettings(parsed)
    }

    fun updateWidgetSettings(transform: (QuickLogWidgetSettings) -> QuickLogWidgetSettings) {
        saveWidgetSettings(normalizeSettings(transform(getWidgetSettings())))
        QuickLogWidgetUpdater.updateAll(context)
    }

    fun selectWidgetList(listId: String) {
        updateWidgetSettings {
            it.copy(
                selectedListId = normalizeListId(listId),
                isListMenuExpanded = false
            )
        }
    }

    fun toggleWidgetListMenu() {
        updateWidgetSettings {
            it.copy(isListMenuExpanded = !it.isListMenuExpanded)
        }
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
            .mapNotNull { normalizeRecord(it) }
    }

    private fun saveRecords(records: List<QuickLogRecord>) {
        mmkv?.encode(KEY_QUICK_LOGS, gson.toJson(records))
    }

    private fun saveWidgetSettings(settings: QuickLogWidgetSettings) {
        mmkv?.encode(KEY_WIDGET_SETTINGS, gson.toJson(settings))
    }

    private fun normalizeRecord(record: QuickLogRecord): QuickLogRecord? {
        val id = record.id.trim()
        val content = record.content.trim()
        if (id.isEmpty() || content.isEmpty()) {
            return null
        }
        val createdAt = if (record.createdAtMillis > 0) {
            record.createdAtMillis
        } else {
            System.currentTimeMillis()
        }
        val updatedAt = if (record.updatedAtMillis > 0) {
            record.updatedAtMillis
        } else {
            createdAt
        }
        val listId = normalizeListId(record.listId)
        return record.copy(
            id = id,
            content = content,
            createdAtMillis = createdAt,
            updatedAtMillis = updatedAt,
            source = record.source ?: SOURCE_APP,
            listId = listId,
            isImportant = record.isImportant || listId == LIST_IMPORTANT,
            repeatRule = normalizeRepeatRule(record.repeatRule),
            reminderAlarmId = record.reminderAlarmId?.takeIf { it.isNotBlank() }
        )
    }

    private fun rescheduleReminderIfNeeded(
        recordId: String,
        previousContent: String,
        content: String,
        previousAlarmId: String?,
        previousReminderAtMillis: Long?,
        nextReminderAtMillis: Long?
    ): String? {
        if (previousReminderAtMillis == nextReminderAtMillis &&
            previousContent == content &&
            previousAlarmId != null
        ) {
            return previousAlarmId
        }
        cancelReminder(previousAlarmId)
        return scheduleReminder(
            recordId = recordId,
            content = content,
            reminderAtMillis = nextReminderAtMillis
        )
    }

    private fun scheduleReminder(
        recordId: String,
        content: String,
        reminderAtMillis: Long?
    ): String? {
        if (reminderAtMillis == null || reminderAtMillis <= System.currentTimeMillis()) {
            return null
        }
        return runCatching {
            val zone = ZoneId.systemDefault()
            val triggerAt = Instant.ofEpochMilli(reminderAtMillis)
                .atZone(zone)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val payload = AgentAlarmToolService(context).createReminder(
                AgentAlarmCreateRequest(
                    mode = AgentAlarmToolService.MODE_EXACT_ALARM,
                    title = content,
                    triggerAt = triggerAt,
                    message = content,
                    timezone = zone.id,
                    allowWhileIdle = true,
                    skipUi = true
                )
            )
            payload["alarmId"]?.toString()?.takeIf { it.isNotBlank() }
        }.onFailure { error ->
            OmniLog.w(
                TAG,
                "Failed to schedule quick task reminder for $recordId: ${error.message}"
            )
        }.getOrNull()
    }

    private fun cancelReminder(alarmId: String?) {
        val normalized = alarmId?.trim().orEmpty()
        if (normalized.isEmpty()) return
        runCatching {
            AgentAlarmToolService(context).deleteExactReminder(normalized)
        }.onFailure { error ->
            OmniLog.w(TAG, "Failed to cancel quick task reminder: ${error.message}")
        }
    }

    private fun syncQuickLogMemory(
        previous: QuickLogRecord,
        updated: QuickLogRecord
    ): Boolean {
        return runCatching {
            val service = WorkspaceMemoryService(context)
            if (updated.isCompleted) {
                return@runCatching service.deleteQuickLogMemory(
                    logId = updated.id,
                    contentHint = previous.content
                )
            } else {
                service.appendQuickLogMemory(
                    logId = updated.id,
                    content = updated.content
                )
                return@runCatching true
            }
        }.onFailure { error ->
            OmniLog.w(TAG, "Failed to sync quick task state to short memory: ${error.message}")
        }.getOrDefault(false)
    }

    private fun matchesList(record: QuickLogRecord, listId: String): Boolean {
        val recordListId = normalizeListId(record.listId)
        return when (listId) {
            LIST_MY_DAY -> recordListId == LIST_MY_DAY
            LIST_IMPORTANT -> record.isImportant || recordListId == LIST_IMPORTANT
            LIST_PLANNED -> recordListId == LIST_PLANNED || record.dueAtMillis != null
            else -> true
        }
    }

    private fun normalizeSettings(settings: QuickLogWidgetSettings?): QuickLogWidgetSettings {
        val current = settings ?: QuickLogWidgetSettings()
        val fontSize = when (current.fontSize) {
            FONT_SMALL, FONT_REGULAR, FONT_LARGE -> current.fontSize
            else -> FONT_REGULAR
        }
        val colorTheme = when (current.colorTheme) {
            COLOR_DARK, COLOR_LIGHT, COLOR_BLUE, COLOR_PINK -> current.colorTheme
            COLOR_GREEN -> COLOR_PINK
            else -> COLOR_DARK
        }
        return current.copy(
            selectedListId = normalizeListId(current.selectedListId),
            opacityPercent = current.opacityPercent.coerceIn(35, 100),
            colorTheme = colorTheme,
            fontSize = fontSize
        )
    }

    private fun normalizeListId(listId: String?): String {
        return if (listId in listIds) {
            listId ?: LIST_TASKS
        } else {
            LIST_TASKS
        }
    }

    private fun normalizeRepeatRule(repeatRule: String?): String? {
        return when (repeatRule) {
            REPEAT_DAILY,
            REPEAT_WEEKDAYS,
            REPEAT_WEEKLY,
            REPEAT_MONTHLY,
            REPEAT_YEARLY,
            REPEAT_CUSTOM -> REPEAT_CUSTOM
            else -> customRepeatParts(repeatRule)?.let { (interval, unit) ->
                customRepeatRule(interval, unit)
            }
        }
    }
}
