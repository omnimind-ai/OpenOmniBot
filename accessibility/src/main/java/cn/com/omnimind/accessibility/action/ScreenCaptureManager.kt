package cn.com.omnimind.accessibility.action

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.graphics.createBitmap
import BaseApplication
import cn.com.omnimind.baselib.permission.ServiceRequest
import cn.com.omnimind.accessibility.service.MediaProjectionForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ScreenCaptureManager private constructor() {

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "screen_capture"
        const val REQUEST_MEDIA_PROJECTION = 1001

        @Volatile
        private var instance: ScreenCaptureManager? = null

        fun getInstance(): ScreenCaptureManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenCaptureManager().also { instance = it }
            }
        }

        private val screenshotMutex = Mutex()

        /** API 29+ 时由前台服务设置 MediaProjection 后回调，用于恢复挂起的协程 */
        @Volatile
        internal var pendingMediaProjectionContinuation: ((Boolean) -> Unit)? = null

        /** 专用线程 + Handler，供 ImageReader 回调使用，避免 API 29 上主线程 Looper 导致回调不触发 */
        private var imageHandlerThread: HandlerThread? = null
        private var imageHandler: Handler? = null

        private fun getImageHandler(): Handler {
            if (imageHandler == null) {
                imageHandlerThread = HandlerThread("ScreenCaptureImage").apply { start() }
                imageHandler = Handler(imageHandlerThread!!.looper)
            }
            return imageHandler!!
        }

        fun clearImageHandler() {
            imageHandlerThread?.quitSafely()
            imageHandlerThread = null
            imageHandler = null
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /**
     * 由 [MediaProjectionForegroundService] 在获得 MediaProjection 后调用（仅 API 29+）
     */
    fun setMediaProjection(projection: MediaProjection?) {
        mediaProjection = projection
    }

    /**
     * 由 [MediaProjectionForegroundService] 在设置完 MediaProjection 或用户拒绝后调用，恢复挂起的协程
     */
    fun onMediaProjectionReady() {
        pendingMediaProjectionContinuation?.let { cont ->
            pendingMediaProjectionContinuation = null
            cont(hasPermission())
        }
    }

    /**
     * 在 Activity 中调用，拉起系统截屏授权弹窗。
     * Android 10 (API 29) 及以上需先启动类型为 mediaProjection 的前台服务，否则会抛出 SecurityException。
     */
    suspend fun requestScreenCapturePermission(): Boolean {
        return suspendCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ctx = BaseApplication.instance
                // 存的是「用 Boolean 恢复协程」的回调，避免重复 resume
                pendingMediaProjectionContinuation = { value -> cont.resume(value) }
                ctx.startService(
                    Intent(ctx, MediaProjectionForegroundService::class.java)
                        .setAction(MediaProjectionForegroundService.ACTION_START_FOREGROUND)
                )
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    delay(500)
                    ServiceRequest.requestService(ctx, Context.MEDIA_PROJECTION_SERVICE) { resultCode, data ->
                        if (resultCode != Activity.RESULT_OK || data == null) {
                            pendingMediaProjectionContinuation?.invoke(false)
                            pendingMediaProjectionContinuation = null
                            return@requestService
                        }
                        ctx.startService(
                            Intent(ctx, MediaProjectionForegroundService::class.java)
                                .setAction(MediaProjectionForegroundService.ACTION_ON_MEDIA_PROJECTION_RESULT)
                                .putExtra(MediaProjectionForegroundService.EXTRA_RESULT_CODE, resultCode)
                                .putExtra(MediaProjectionForegroundService.EXTRA_RESULT_DATA, data)
                        )
                    }
                }
            } else {
                ServiceRequest.requestService(
                    BaseApplication.instance,
                    Context.MEDIA_PROJECTION_SERVICE
                ) { resultCode, data ->
                    if (resultCode != Activity.RESULT_OK || data == null) {
                        cont.resume(false)
                        return@requestService
                    }
                    val mpm = BaseApplication.instance.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(resultCode, data)
                    cont.resume(hasPermission())
                }
            }
        }
    }

    fun hasPermission(): Boolean {
        return mediaProjection != null
    }

    /**
     * 截一张图，回调 Bitmap（在 IO/子线程回调）。
     * API 29 上部分机型 onImageAvailable 不触发，改为轮询 acquireLatestImage() 取帧。
     */
    suspend fun captureOnce(): Bitmap? {
        val projection = mediaProjection ?: return null
        return screenshotMutex.withLock {
            withTimeoutOrNull(2000L) {
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    val metrics = resolveDisplayMetrics()
                    val width = metrics.widthPixels
                    val height = metrics.heightPixels
                    val densityDpi = metrics.densityDpi

                    imageReader?.close()
                    imageReader = ImageReader.newInstance(
                        width,
                        height,
                        PixelFormat.RGBA_8888,
                        3
                    )

                    virtualDisplay?.release()
                    virtualDisplay = projection.createVirtualDisplay(
                        VIRTUAL_DISPLAY_NAME,
                        width,
                        height,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader?.surface,
                        null,
                        null
                    )

                    var pollJob: Job? = null
                    cont.invokeOnCancellation {
                        pollJob?.cancel()
                        virtualDisplay?.release()
                        virtualDisplay = null
                    }

                    pollJob = CoroutineScope(Dispatchers.Default).launch {
                        delay(150)
                        var elapsed = 150L
                        val pollInterval = 80L
                        val timeout = 2000L
                        while (elapsed < timeout && !cont.isCancelled) {
                            delay(pollInterval)
                            elapsed += pollInterval
                            if (!cont.isCancelled) {
                                val image = try {
                                    imageReader?.acquireLatestImage()
                                } catch (e: Exception) {
                                    null
                                }
                                if (image != null) {
                                    var bitmap: Bitmap? = null
                                    try {
                                        val plane = image.planes[0]
                                        val buffer = plane.buffer
                                        val pixelStride = plane.pixelStride
                                        val rowStride = plane.rowStride
                                        val rowPadding = rowStride - pixelStride * width
                                        val rowPixels = width + rowPadding / pixelStride
                                        bitmap = createBitmap(
                                            rowPixels,
                                            height,
                                            Bitmap.Config.ARGB_8888
                                        )
                                        bitmap.copyPixelsFromBuffer(buffer)
                                        if (rowPixels != width) {
                                            bitmap = Bitmap.createBitmap(bitmap!!, 0, 0, width, height)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    } finally {
                                        image.close()
                                    }
                                    virtualDisplay?.release()
                                    virtualDisplay = null
                                    cont.resume(bitmap)
                                    return@launch
                                }
                            }
                        }
                        virtualDisplay?.release()
                        virtualDisplay = null
                        cont.resume(null)
                    }
                }
            }
        }
    }

    private fun resolveDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics().apply {
            setTo(BaseApplication.instance.resources.displayMetrics)
        }
        runCatching {
            val displayManager =
                BaseApplication.instance.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            @Suppress("DEPRECATION")
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.setTo(BaseApplication.instance.resources.displayMetrics)
        }
        return metrics
    }

    /**
     * 释放全部资源（比如在退出时调用）。
     * API 29+ 会同时停止 MediaProjection 前台服务，否则通知会一直存在。
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BaseApplication.instance.stopService(
                Intent(BaseApplication.instance, MediaProjectionForegroundService::class.java)
            )
        }
        clearImageHandler()
    }
}
