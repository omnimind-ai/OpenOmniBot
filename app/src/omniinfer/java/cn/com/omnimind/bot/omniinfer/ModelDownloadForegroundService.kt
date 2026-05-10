package cn.com.omnimind.bot.omniinfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import cn.com.omnimind.bot.activity.MainActivity

/**
 * Foreground service that keeps the process (and its network sockets) alive while
 * model files are being downloaded. Shows an ongoing low-priority notification so
 * the user knows a download is in progress.
 */
class ModelDownloadForegroundService : Service() {

    private lateinit var notificationManager: NotificationManager
    private var downloadCount = 0
    private var modelName: String? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        instance = this
        Log.d(TAG, "onCreate")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "模型下载",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w(TAG, "onStartCommand: null intent, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        downloadCount = intent.getIntExtra(EXTRA_DOWNLOAD_COUNT, 0)
        modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
        Log.d(TAG, "onStartCommand count=$downloadCount model=$modelName")

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SERVICE_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(SERVICE_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val text = when {
            downloadCount <= 0 -> "正在下载模型..."
            downloadCount == 1 && modelName != null -> "正在下载 $modelName"
            else -> "正在下载 $downloadCount 个模型"
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("模型下载")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun updateNotification(count: Int, name: String? = null) {
        downloadCount = count
        modelName = name
        notificationManager.notify(SERVICE_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "onDestroy")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ModelDownloadFgSvc"
        private const val CHANNEL_ID = "ModelDownloadChannel"
        private const val SERVICE_ID = 8889
        private const val EXTRA_DOWNLOAD_COUNT = "download_count"
        private const val EXTRA_MODEL_NAME = "model_name"

        @Volatile
        private var instance: ModelDownloadForegroundService? = null

        /**
         * Start (or update) the foreground service. Safe to call repeatedly —
         * Android will just deliver a new onStartCommand.
         */
        fun start(context: Context, downloadCount: Int, modelName: String? = null) {
            val intent = Intent(context, ModelDownloadForegroundService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_COUNT, downloadCount)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        /** Stop the foreground service. No-op if not running. */
        fun stop(context: Context) {
            try {
                context.stopService(
                    Intent(context, ModelDownloadForegroundService::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground service", e)
            }
        }

        /** Convenience: stop only if there are no active downloads across both managers. */
        fun stopIfIdle(context: Context) {
            val totalActive = OmniInferModelsManager.activeDownloadCount() +
                OmniInferMnnModelsManager.activeDownloadCount()
            if (totalActive == 0) {
                stop(context)
            } else {
                instance?.updateNotification(totalActive)
            }
        }
    }
}
