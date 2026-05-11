package cn.com.omnimind.bot.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import com.tencent.mmkv.MMKV

class AgentAlarmRingingService : Service() {

    companion object {
        private const val ACTION_START_RINGING =
            "cn.com.omnimind.bot.agent.ACTION_START_RINGING"
        private const val ACTION_STOP_RINGING =
            "cn.com.omnimind.bot.agent.ACTION_STOP_RINGING"
        private const val CHANNEL_ID = "agent_alarm_ringing_channel"
        private const val CHANNEL_NAME = "闹钟响铃"

        private const val EXTRA_ALARM_ID = "extra_alarm_id"
        private const val EXTRA_ALARM_TITLE = "extra_alarm_title"
        private const val EXTRA_ALARM_MESSAGE = "extra_alarm_message"

        fun start(
            context: Context,
            alarmId: String,
            title: String,
            message: String
        ) {
            val intent = Intent(context, AgentAlarmRingingService::class.java).apply {
                action = ACTION_START_RINGING
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_ALARM_TITLE, title)
                putExtra(EXTRA_ALARM_MESSAGE, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.startService(Intent(appContext, AgentAlarmRingingService::class.java).apply {
                    action = ACTION_STOP_RINGING
                })
            }
            appContext.stopService(Intent(appContext, AgentAlarmRingingService::class.java))
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RINGING) {
            stopRingingAndSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START_RINGING) {
            stopRingingAndSelf()
            return START_NOT_STICKY
        }

        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (alarmId.isBlank()) {
            stopRingingAndSelf()
            return START_NOT_STICKY
        }
        MMKV.defaultMMKV().encode(AgentAlarmToolService.KEY_CURRENT_RINGING_ALARM_ID, alarmId)

        val title = intent.getStringExtra(EXTRA_ALARM_TITLE).orEmpty().ifBlank { "提醒" }
        val message = intent.getStringExtra(EXTRA_ALARM_MESSAGE).orEmpty().ifBlank { "闹钟响了" }

        ensureChannel()
        startForeground(
            AgentAlarmToolService.stableNotificationId(alarmId),
            buildNotification(alarmId, title, message)
        )
        startAlarmAudio()
        startVibrationLoop()

        return START_STICKY
    }

    override fun onDestroy() {
        stopAlarmAudio()
        stopVibrationLoop()
        stopForegroundCompat()
        clearCurrentRingingAlarmId()
        super.onDestroy()
    }

    private fun stopRingingAndSelf() {
        stopAlarmAudio()
        stopVibrationLoop()
        stopForegroundCompat()
        clearCurrentRingingAlarmId()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun clearCurrentRingingAlarmId() {
        MMKV.defaultMMKV().removeValueForKey(AgentAlarmToolService.KEY_CURRENT_RINGING_ALARM_ID)
    }

    private fun buildNotification(alarmId: String, title: String, message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            AgentAlarmToolService.stableRequestCode("ring-open:$alarmId"),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val closePendingIntent = PendingIntent.getBroadcast(
            this,
            AgentAlarmToolService.stableRequestCode("ring-close:$alarmId"),
            Intent(this, AgentAlarmReceiver::class.java).apply {
                action = AgentAlarmReceiver.ACTION_AGENT_ALARM_CLOSE
                putExtra(AgentAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val snoozePendingIntent = PendingIntent.getBroadcast(
            this,
            AgentAlarmToolService.stableRequestCode("ring-snooze:$alarmId"),
            Intent(this, AgentAlarmReceiver::class.java).apply {
                action = AgentAlarmReceiver.ACTION_AGENT_ALARM_SNOOZE
                putExtra(AgentAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setSubText("闹钟响铃中")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "关闭", closePendingIntent)
            .addAction(0, "5分钟后再提醒", snoozePendingIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "闹钟响铃与快捷操作"
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startAlarmAudio() {
        stopAlarmAudio()
        val settings = AgentAlarmToolService(this).getAlarmSoundSettings()
        val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val primaryUri = resolvePrimaryUri(settings) ?: fallbackUri
        startPlayerWithFallback(primaryUri, fallbackUri)
    }

    private fun resolvePrimaryUri(settings: AgentAlarmToolService.AlarmSoundSettings): Uri? {
        return when (settings.source) {
            AgentAlarmToolService.SOUND_SOURCE_LOCAL_MP3 -> {
                val raw = settings.localPath.trim()
                if (raw.isBlank()) null else toUri(raw)
            }

            AgentAlarmToolService.SOUND_SOURCE_REMOTE_MP3_URL -> {
                val raw = settings.remoteUrl.trim()
                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    Uri.parse(raw)
                } else {
                    null
                }
            }

            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
    }

    private fun toUri(raw: String): Uri {
        return when {
            raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
            else -> Uri.parse("file://$raw")
        }
    }

    private fun startPlayerWithFallback(primaryUri: Uri?, fallbackUri: Uri?) {
        if (primaryUri == null && fallbackUri == null) return
        val uri = primaryUri ?: fallbackUri ?: return

        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        player.isLooping = true
        player.setOnPreparedListener {
            runCatching { it.start() }
        }
        player.setOnErrorListener { _, _, _ ->
            runCatching { player.release() }
            if (fallbackUri != null && uri != fallbackUri) {
                startPlayerWithFallback(fallbackUri, null)
                true
            } else {
                false
            }
        }

        runCatching {
            player.setDataSource(applicationContext, uri)
            player.prepareAsync()
            mediaPlayer = player
        }.onFailure {
            runCatching { player.release() }
            if (fallbackUri != null && uri != fallbackUri) {
                startPlayerWithFallback(fallbackUri, null)
            }
        }
    }

    private fun stopAlarmAudio() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }

    private fun startVibrationLoop() {
        stopVibrationLoop()

        val resolvedVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = resolvedVibrator

        val pattern = longArrayOf(0L, 900L, 400L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, 0)
            resolvedVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            resolvedVibrator.vibrate(pattern, 0)
        }
    }

    private fun stopVibrationLoop() {
        vibrator?.cancel()
        vibrator = null
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
