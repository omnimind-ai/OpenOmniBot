package cn.com.omnimind.bot.agent

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.baselib.permission.PermissionRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.resume

data class AgentAlarmCreateRequest(
    val mode: String,
    val title: String,
    val triggerAt: String,
    val message: String?,
    val timezone: String?,
    val allowWhileIdle: Boolean,
    val skipUi: Boolean
)

class AgentAlarmToolService(
    private val context: Context
) {
    companion object {
        const val MODE_EXACT_ALARM = "exact_alarm"
        const val MODE_CLOCK_APP = "clock_app"

        const val STATE_PENDING = "pending"
        const val STATE_COUNTDOWN = "countdown"
        const val STATE_RINGING = "ringing"

        const val SOUND_SOURCE_DEFAULT = "default"
        const val SOUND_SOURCE_LOCAL_MP3 = "local_mp3"
        const val SOUND_SOURCE_REMOTE_MP3_URL = "remote_mp3_url"

        const val PRE_ALERT_WINDOW_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_SNOOZE_MINUTES = 5

        private const val KEY_AGENT_EXACT_ALARM_RECORDS = "agent_exact_alarm_records_v2"
        private const val KEY_AGENT_ALARM_SOUND_SETTINGS = "agent_alarm_sound_settings_v1"
        const val KEY_CURRENT_RINGING_ALARM_ID = "agent_current_ringing_alarm_id_v1"

        fun stableNotificationId(alarmId: String): Int {
            val value = alarmId.hashCode() and 0x7FFFFFFF
            return if (value == 0) 1001 else value
        }

        fun stableRequestCode(seed: String): Int = seed.hashCode()
    }

    data class AlarmSoundSettings(
        val source: String,
        val localPath: String,
        val remoteUrl: String
    )

    private data class ExactAlarmRecordRaw(
        val alarmId: String?,
        val title: String?,
        val message: String?,
        val triggerAtMillis: Long?,
        val timezone: String?,
        val createdAtMillis: Long?,
        val state: String?,
        val preAlertAtMillis: Long?,
        val allowWhileIdle: Boolean?
    )

    private data class ExactAlarmRecord(
        val alarmId: String,
        val title: String,
        val message: String,
        val triggerAtMillis: Long,
        val timezone: String,
        val createdAtMillis: Long,
        val state: String,
        val preAlertAtMillis: Long,
        val allowWhileIdle: Boolean
    )

    private val gson = Gson()

    fun hasExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            PermissionRequest.requestPermissions(
                context,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            ) { result ->
                if (!continuation.isCompleted) {
                    continuation.resume(result[Manifest.permission.POST_NOTIFICATIONS] == true)
                }
            }
        }
    }

    fun openExactAlarmPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun createReminder(request: AgentAlarmCreateRequest): Map<String, Any?> {
        return when (request.mode) {
            MODE_EXACT_ALARM -> createExactAlarm(request)
            MODE_CLOCK_APP -> createSystemClockAlarm(request)
            else -> throw IllegalArgumentException("Unsupported alarm mode: ${request.mode}")
        }
    }

    fun listExactReminders(): List<Map<String, Any?>> {
        val records = loadRecords().sortedBy { it.triggerAtMillis }
        return records.map { toMap(it) }
    }

    fun getExactReminder(alarmId: String): Map<String, Any?>? {
        val record = loadRecords().firstOrNull { it.alarmId == alarmId } ?: return null
        return toMap(record)
    }

    fun reschedulePersistedExactRemindersIfPermitted(): Int {
        if (!hasExactAlarmPermission()) {
            return 0
        }
        val now = System.currentTimeMillis()
        val activeRecords = loadRecords().filter { record ->
            record.triggerAtMillis > now && record.state != STATE_RINGING
        }
        activeRecords.forEach { record ->
            cancelExactAlarms(record)
            scheduleExactAlarms(record)
        }
        return activeRecords.size
    }

    fun markCountdownState(alarmId: String): Map<String, Any?>? {
        val updated = updateRecord(alarmId) { record ->
            record.copy(state = STATE_COUNTDOWN)
        }
        return updated?.let { toMap(it) }
    }

    fun markRingingState(alarmId: String): Map<String, Any?>? {
        val updated = updateRecord(alarmId) { record ->
            record.copy(state = STATE_RINGING)
        }
        return updated?.let { toMap(it) }
    }

    fun deleteExactReminder(alarmId: String): Map<String, Any?> {
        val normalizedAlarmId = alarmId.trim()
        if (normalizedAlarmId.isBlank() ||
            normalizedAlarmId == "*" ||
            normalizedAlarmId.equals("all", ignoreCase = true)
        ) {
            return deleteAllExactReminders()
        }

        val closed = closeExactReminder(normalizedAlarmId)
        if (!closed) {
            throw IllegalArgumentException("未找到对应提醒闹钟")
        }
        return mapOf(
            "success" to true,
            "alarmId" to normalizedAlarmId,
            "summary" to "提醒闹钟已删除"
        )
    }

    fun deleteAllExactReminders(): Map<String, Any?> {
        val records = loadRecords()
        AgentAlarmRingingService.stop(context)
        records.forEach { record ->
            cancelExactAlarms(record)
            cancelNotification(record.alarmId)
        }
        MMKV.defaultMMKV().removeValueForKey(KEY_CURRENT_RINGING_ALARM_ID)
        persistRecords(emptyList())
        return mapOf(
            "success" to true,
            "deletedCount" to records.size,
            "summary" to if (records.isEmpty()) {
                "已停止所有提醒闹钟"
            } else {
                "已删除 ${records.size} 个提醒闹钟"
            }
        )
    }

    fun closeExactReminder(alarmId: String): Boolean {
        val normalizedAlarmId = alarmId.trim()
        if (normalizedAlarmId.isBlank()) return false
        val records = loadRecords().toMutableList()
        val record = records.firstOrNull { it.alarmId == normalizedAlarmId } ?: return false

        val currentRingingAlarmId = MMKV.defaultMMKV()
            .decodeString(KEY_CURRENT_RINGING_ALARM_ID)
            .orEmpty()
        if (record.state == STATE_RINGING || currentRingingAlarmId == normalizedAlarmId) {
            AgentAlarmRingingService.stop(context)
            MMKV.defaultMMKV().removeValueForKey(KEY_CURRENT_RINGING_ALARM_ID)
        }
        cancelExactAlarms(record)
        records.removeAll { it.alarmId == normalizedAlarmId }
        persistRecords(records)
        cancelNotification(normalizedAlarmId)
        return true
    }

    fun snoozeExactReminder(
        alarmId: String,
        snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES
    ): Map<String, Any?> {
        require(alarmId.isNotBlank()) { "alarmId 不能为空" }
        require(snoozeMinutes > 0) { "snoozeMinutes 必须大于 0" }

        val records = loadRecords().toMutableList()
        val index = records.indexOfFirst { it.alarmId == alarmId }
        require(index >= 0) { "未找到对应提醒闹钟" }

        val current = records[index]
        AgentAlarmRingingService.stop(context)
        cancelExactAlarms(current)

        val now = System.currentTimeMillis()
        val triggerAtMillis = now + snoozeMinutes * 60 * 1000L
        val preAlertAtMillis = calculatePreAlertAt(triggerAtMillis)
        val updated = current.copy(
            triggerAtMillis = triggerAtMillis,
            preAlertAtMillis = preAlertAtMillis,
            state = STATE_PENDING
        )

        records[index] = updated
        persistRecords(records)
        scheduleExactAlarms(updated)
        cancelNotification(alarmId)

        return mapOf(
            "success" to true,
            "alarmId" to alarmId,
            "state" to updated.state,
            "triggerAtMillis" to updated.triggerAtMillis,
            "triggerAt" to formatIso(updated.triggerAtMillis, updated.timezone),
            "preAlertAtMillis" to updated.preAlertAtMillis,
            "preAlertAt" to formatIso(updated.preAlertAtMillis, updated.timezone),
            "summary" to "已延后 ${snoozeMinutes} 分钟提醒"
        )
    }

    fun getAlarmSettings(): Map<String, Any?> {
        val settings = getAlarmSoundSettings()
        return mapOf(
            "source" to settings.source,
            "localPath" to settings.localPath,
            "remoteUrl" to settings.remoteUrl
        )
    }

    fun saveAlarmSettings(
        source: String,
        localPath: String?,
        remoteUrl: String?
    ): Map<String, Any?> {
        val normalizedSource = source.trim()
        val normalizedLocalPath = localPath?.trim().orEmpty()
        val normalizedRemoteUrl = remoteUrl?.trim().orEmpty()

        when (normalizedSource) {
            SOUND_SOURCE_DEFAULT -> {
                // no-op
            }

            SOUND_SOURCE_LOCAL_MP3 -> {
                require(normalizedLocalPath.isNotEmpty()) { "localPath 不能为空" }
            }

            SOUND_SOURCE_REMOTE_MP3_URL -> {
                require(normalizedRemoteUrl.startsWith("http://") || normalizedRemoteUrl.startsWith("https://")) {
                    "remoteUrl 必须为 http(s) 地址"
                }
            }

            else -> throw IllegalArgumentException("source 不支持: $source")
        }

        val settings = AlarmSoundSettings(
            source = normalizedSource,
            localPath = normalizedLocalPath,
            remoteUrl = normalizedRemoteUrl
        )
        MMKV.defaultMMKV().encode(KEY_AGENT_ALARM_SOUND_SETTINGS, gson.toJson(settings))

        return mapOf(
            "success" to true,
            "source" to settings.source,
            "localPath" to settings.localPath,
            "remoteUrl" to settings.remoteUrl,
            "summary" to "闹钟铃声设置已保存"
        )
    }

    fun getAlarmSoundSettings(): AlarmSoundSettings {
        val raw = MMKV.defaultMMKV().decodeString(KEY_AGENT_ALARM_SOUND_SETTINGS).orEmpty()
        if (raw.isBlank()) {
            return AlarmSoundSettings(
                source = SOUND_SOURCE_DEFAULT,
                localPath = "",
                remoteUrl = ""
            )
        }

        return runCatching {
            gson.fromJson(raw, AlarmSoundSettings::class.java)
        }.getOrNull()?.let { parsed ->
            val source = parsed.source.trim().ifEmpty { SOUND_SOURCE_DEFAULT }
            val localPath = parsed.localPath.trim()
            val remoteUrl = parsed.remoteUrl.trim()
            val normalizedSource = when (source) {
                SOUND_SOURCE_DEFAULT,
                SOUND_SOURCE_LOCAL_MP3,
                SOUND_SOURCE_REMOTE_MP3_URL -> source

                else -> SOUND_SOURCE_DEFAULT
            }
            AlarmSoundSettings(
                source = normalizedSource,
                localPath = localPath,
                remoteUrl = remoteUrl
            )
        } ?: AlarmSoundSettings(
            source = SOUND_SOURCE_DEFAULT,
            localPath = "",
            remoteUrl = ""
        )
    }

    private fun createExactAlarm(request: AgentAlarmCreateRequest): Map<String, Any?> {
        val triggerAt = parseDateTime(request.triggerAt, request.timezone)
        val triggerAtMillis = triggerAt.toInstant().toEpochMilli()
        require(triggerAtMillis > System.currentTimeMillis()) {
            "triggerAt 必须晚于当前时间"
        }

        val record = ExactAlarmRecord(
            alarmId = UUID.randomUUID().toString(),
            title = request.title.trim(),
            message = request.message?.trim().orEmpty(),
            triggerAtMillis = triggerAtMillis,
            timezone = triggerAt.zone.id,
            createdAtMillis = System.currentTimeMillis(),
            state = STATE_PENDING,
            preAlertAtMillis = calculatePreAlertAt(triggerAtMillis),
            allowWhileIdle = request.allowWhileIdle
        )

        val records = loadRecords().toMutableList().apply {
            removeAll { it.alarmId == record.alarmId }
            add(record)
        }
        persistRecords(records)
        scheduleExactAlarms(record)

        return mapOf(
            "success" to true,
            "mode" to MODE_EXACT_ALARM,
            "alarmId" to record.alarmId,
            "title" to record.title,
            "message" to record.message,
            "triggerAtMillis" to record.triggerAtMillis,
            "triggerAt" to formatIso(record.triggerAtMillis, record.timezone),
            "preAlertAtMillis" to record.preAlertAtMillis,
            "preAlertAt" to formatIso(record.preAlertAtMillis, record.timezone),
            "timezone" to record.timezone,
            "state" to record.state,
            "summary" to "已创建提醒闹钟“${record.title}”"
        )
    }

    private fun createSystemClockAlarm(request: AgentAlarmCreateRequest): Map<String, Any?> {
        val triggerAt = parseDateTime(request.triggerAt, request.timezone)
        val localTrigger = triggerAt.withZoneSameInstant(ZoneId.systemDefault())

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, localTrigger.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, localTrigger.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, request.title.trim())
            putExtra(AlarmClock.EXTRA_SKIP_UI, request.skipUi)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        if (intent.resolveActivity(context.packageManager) == null) {
            throw IllegalStateException("当前设备不支持系统闹钟创建")
        }

        context.startActivity(intent)

        return mapOf(
            "success" to true,
            "mode" to MODE_CLOCK_APP,
            "title" to request.title.trim(),
            "triggerAt" to localTrigger.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "timezone" to ZoneId.systemDefault().id,
            "summary" to "已发起系统闹钟创建“${request.title.trim()}”"
        )
    }

    private fun updateRecord(
        alarmId: String,
        transform: (ExactAlarmRecord) -> ExactAlarmRecord
    ): ExactAlarmRecord? {
        if (alarmId.isBlank()) return null
        val records = loadRecords().toMutableList()
        val index = records.indexOfFirst { it.alarmId == alarmId }
        if (index < 0) return null
        val updated = transform(records[index])
        records[index] = updated
        persistRecords(records)
        return updated
    }

    private fun scheduleExactAlarms(record: ExactAlarmRecord) {
        scheduleStageAlarm(
            alarmId = record.alarmId,
            triggerAtMillis = record.preAlertAtMillis,
            action = AgentAlarmReceiver.ACTION_AGENT_ALARM_PRE_ALERT_TRIGGER,
            allowWhileIdle = record.allowWhileIdle,
            requestSeed = "pre:${record.alarmId}"
        )
        scheduleStageAlarm(
            alarmId = record.alarmId,
            triggerAtMillis = record.triggerAtMillis,
            action = AgentAlarmReceiver.ACTION_AGENT_ALARM_RING_TRIGGER,
            allowWhileIdle = record.allowWhileIdle,
            requestSeed = "ring:${record.alarmId}"
        )
    }

    private fun cancelExactAlarms(record: ExactAlarmRecord) {
        cancelStageAlarm(
            alarmId = record.alarmId,
            action = AgentAlarmReceiver.ACTION_AGENT_ALARM_PRE_ALERT_TRIGGER,
            requestSeed = "pre:${record.alarmId}"
        )
        cancelStageAlarm(
            alarmId = record.alarmId,
            action = AgentAlarmReceiver.ACTION_AGENT_ALARM_RING_TRIGGER,
            requestSeed = "ring:${record.alarmId}"
        )
    }

    private fun scheduleStageAlarm(
        alarmId: String,
        triggerAtMillis: Long,
        action: String,
        allowWhileIdle: Boolean,
        requestSeed: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            stableRequestCode(requestSeed),
            Intent(context, AgentAlarmReceiver::class.java).apply {
                this.action = action
                putExtra(AgentAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        if (action == AgentAlarmReceiver.ACTION_AGENT_ALARM_RING_TRIGGER &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        ) {
            val showIntent = PendingIntent.getActivity(
                context,
                stableRequestCode("ring-show:$alarmId"),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                pendingIntent
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && allowWhileIdle) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun cancelStageAlarm(
        alarmId: String,
        action: String,
        requestSeed: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            stableRequestCode(requestSeed),
            Intent(context, AgentAlarmReceiver::class.java).apply {
                this.action = action
                putExtra(AgentAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            },
            PendingIntent.FLAG_NO_CREATE or immutableFlag()
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun calculatePreAlertAt(triggerAtMillis: Long): Long {
        val now = System.currentTimeMillis()
        val baseline = triggerAtMillis - PRE_ALERT_WINDOW_MILLIS
        val minTrigger = now + 1000L
        return when {
            baseline < minTrigger -> minTrigger.coerceAtMost(triggerAtMillis)
            else -> baseline
        }
    }

    private fun toMap(record: ExactAlarmRecord): Map<String, Any?> {
        return mapOf(
            "alarmId" to record.alarmId,
            "mode" to MODE_EXACT_ALARM,
            "title" to record.title,
            "message" to record.message,
            "triggerAtMillis" to record.triggerAtMillis,
            "triggerAt" to formatIso(record.triggerAtMillis, record.timezone),
            "preAlertAtMillis" to record.preAlertAtMillis,
            "preAlertAt" to formatIso(record.preAlertAtMillis, record.timezone),
            "timezone" to record.timezone,
            "createdAtMillis" to record.createdAtMillis,
            "allowWhileIdle" to record.allowWhileIdle,
            "state" to record.state
        )
    }

    private fun cancelNotification(alarmId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(stableNotificationId(alarmId))
    }

    private fun parseDateTime(raw: String, timezone: String?): ZonedDateTime {
        val value = raw.trim()
        require(value.isNotEmpty()) { "时间不能为空" }
        val zone = resolveZone(timezone)

        runCatching { return ZonedDateTime.parse(value) }
        runCatching { return OffsetDateTime.parse(value).toZonedDateTime().withZoneSameInstant(zone) }
        runCatching { return Instant.parse(value).atZone(zone) }
        runCatching { return LocalDateTime.parse(value).atZone(zone) }

        throw IllegalArgumentException("无法解析时间：$value")
    }

    private fun resolveZone(timezone: String?): ZoneId {
        val normalized = timezone?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return ZoneId.systemDefault()
        }
        return runCatching { ZoneId.of(normalized) }.getOrElse {
            throw IllegalArgumentException("Invalid timezone: $normalized")
        }
    }

    private fun formatIso(epochMillis: Long, timezone: String): String {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        return Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun loadRecords(): List<ExactAlarmRecord> {
        val raw = MMKV.defaultMMKV().decodeString(KEY_AGENT_EXACT_ALARM_RECORDS).orEmpty()
        if (raw.isBlank()) return emptyList()

        val type = object : TypeToken<List<ExactAlarmRecordRaw>>() {}.type
        val parsed = runCatching { gson.fromJson<List<ExactAlarmRecordRaw>>(raw, type) }
            .getOrDefault(emptyList())

        val result = mutableListOf<ExactAlarmRecord>()
        for (item in parsed) {
            val normalized = normalizeRecord(item) ?: continue
            result += normalized
        }
        return result
    }

    private fun normalizeRecord(raw: ExactAlarmRecordRaw): ExactAlarmRecord? {
        val triggerAtMillis = raw.triggerAtMillis ?: return null
        val alarmId = raw.alarmId?.trim().orEmpty().ifBlank { UUID.randomUUID().toString() }
        val title = raw.title?.trim().orEmpty().ifBlank { "提醒" }
        val message = raw.message?.trim().orEmpty()
        val timezone = raw.timezone?.trim().orEmpty().ifBlank { ZoneId.systemDefault().id }
        val createdAtMillis = raw.createdAtMillis ?: System.currentTimeMillis()
        val state = when (raw.state?.trim()) {
            STATE_PENDING,
            STATE_COUNTDOWN,
            STATE_RINGING -> raw.state.trim()

            else -> STATE_PENDING
        }
        val allowWhileIdle = raw.allowWhileIdle ?: true
        val preAlertAtMillis = (raw.preAlertAtMillis ?: (triggerAtMillis - PRE_ALERT_WINDOW_MILLIS))
            .coerceAtMost(triggerAtMillis)

        return ExactAlarmRecord(
            alarmId = alarmId,
            title = title,
            message = message,
            triggerAtMillis = triggerAtMillis,
            timezone = timezone,
            createdAtMillis = createdAtMillis,
            state = state,
            preAlertAtMillis = preAlertAtMillis,
            allowWhileIdle = allowWhileIdle
        )
    }

    private fun persistRecords(records: List<ExactAlarmRecord>) {
        MMKV.defaultMMKV().encode(KEY_AGENT_EXACT_ALARM_RECORDS, gson.toJson(records))
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
