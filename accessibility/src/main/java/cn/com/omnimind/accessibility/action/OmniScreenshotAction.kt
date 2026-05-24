package cn.com.omnimind.accessibility.action

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Point
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.collections.forEach
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.use

/**
 * 初始截图服务
 */
class OmniScreenshotAction(
    private val service: AssistsService,
) {

    companion object {
        const val TAG = "CaptureServer"
        private val screenshotMutex = Mutex()
        private const val FAST_SCREENSHOT_INTERVAL_MS = 200L
        private const val SAFE_SCREENSHOT_INTERVAL_MS = 420L
        @Volatile
        private var lastScreenshotCompletedAtMs: Long = 0L
        @Volatile
        private var currentScreenshotIntervalMs: Long = FAST_SCREENSHOT_INTERVAL_MS
    }

    private val mainThreadExecutor: Executor = Executor { command ->
        Handler(Looper.getMainLooper()).post(command)
    }

    /**
     * 如果能过滤就过滤,如果过滤不了就截取默认截图
     */
    suspend fun captureScreenshotWithDefault(): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            captureExcludingOverlaysV14()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureDefaultScreenshot()
        }else {
            ScreenCaptureManager.getInstance().captureOnce()
        }
    }

    /**
     * Android 14+ 的截屏实现：窗口合成方式
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun captureExcludingOverlaysV14(): Bitmap? {
        screenshotMutex.lock()
        var delayUnlock = false // 成功时延迟解锁，失败时立即解锁
        try {
            var lastError: Exception? = null
            for (attemptIndex in 0..1) {
                val elapsedSinceLastShot =
                    SystemClock.elapsedRealtime() - lastScreenshotCompletedAtMs
                if (elapsedSinceLastShot in 0 until currentScreenshotIntervalMs) {
                    delay(currentScreenshotIntervalMs - elapsedSinceLastShot)
                }
                try {
                    val windows = service.windows ?: return null

                    // AccessibilityService runs from a non-visual Context on
                    // Android 14+, so WindowManager.defaultDisplay can throw.
                    // Window bounds are already in screen coordinates and are
                    // the safest source for the merge canvas size.
                    val screenSize = resolveScreenSizeFromWindows(windows)
                    val screenWidth = screenSize.x
                    val screenHeight = screenSize.y

                    OmniLog.d(TAG, "Screen size: width=$screenWidth, height=$screenHeight")

                    val validWindows = filterValidWindows(windows)
                    if (validWindows.isEmpty()) {
                        return null
                    }
                    val result = captureAndMergeWindows(validWindows, screenWidth, screenHeight)
                    if (result != null) {
                        delayUnlock = true
                        return result
                    }
                } catch (e: Exception) {
                    lastError = e
                }
                currentScreenshotIntervalMs = SAFE_SCREENSHOT_INTERVAL_MS
                lastScreenshotCompletedAtMs = SystemClock.elapsedRealtime()
                if (attemptIndex == 0) {
                    OmniLog.w(TAG, "captureExcludingOverlaysV14 retrying once with safe interval")
                }
            }
            currentScreenshotIntervalMs = SAFE_SCREENSHOT_INTERVAL_MS
            lastError?.let { OmniLog.e(TAG, "Failed to capture screenshot excluding overlays", it) }
            return null
        } finally {
            lastScreenshotCompletedAtMs = SystemClock.elapsedRealtime()
            // 统一在 finally 中解锁
            if (delayUnlock) {
                try {
                    screenshotMutex.unlock()
                } catch (e: IllegalStateException) {
                    // Mutex 可能已经被解锁（例如被取消），忽略此异常
                    OmniLog.d(TAG, "Mutex already unlocked, ignoring")
                }
            } else {
                // 失败时立即解锁
                try {
                    screenshotMutex.unlock()
                } catch (e: IllegalStateException) {
                    // Mutex 可能已经被解锁，忽略此异常
                    OmniLog.d(TAG, "Mutex already unlocked, ignoring")
                }
            }
        }
    }

    /**
     * Android 11-13 的截屏实现：普通截屏方式
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureDefaultScreenshot(
        hintOverlay: (() -> Unit)? = null,
        showOverlay: (() -> Unit)? = null
    ): Bitmap? {
        screenshotMutex.lock()
        var delayUnlock = false // 成功时延迟解锁，失败时立即解锁
        try {
            var lastError: Exception? = null
            for (attemptIndex in 0..1) {
                val elapsedSinceLastShot =
                    SystemClock.elapsedRealtime() - lastScreenshotCompletedAtMs
                if (elapsedSinceLastShot in 0 until currentScreenshotIntervalMs) {
                    delay(currentScreenshotIntervalMs - elapsedSinceLastShot)
                }
                try {
                    // 先同步执行隐藏悬浮框的操作，等待完成后再截屏
                    hintOverlay?.let {
                        withContext(Dispatchers.Main) {
                            it.invoke()
                        }
                    }

                    // 添加超时机制，避免永远阻塞（2秒超时）
                    val result = withTimeoutOrNull(2000L) {
                        suspendCancellableCoroutine<Bitmap?> { cont ->
                            service.takeScreenshot(
                                Display.DEFAULT_DISPLAY,
                                mainThreadExecutor,
                                object : AccessibilityService.TakeScreenshotCallback {
                                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                        showOverlay?.invoke()

                                        CoroutineScope(Dispatchers.Default).launch {
                                            screenshot.hardwareBuffer.use { hardwareBuffer ->
                                                try {
                                                    val bitmap = Bitmap.wrapHardwareBuffer(
                                                        hardwareBuffer,
                                                        screenshot.colorSpace,
                                                    )
                                                        ?: throw RuntimeException("Failed to wrap hardware buffer into Bitmap")

                                                    // 转换为软件 Bitmap 以便进行像素操作
                                                    val softwareBitmap = convertToSoftwareBitmap(bitmap)

                                                    cont.resume(softwareBitmap)
                                                } catch (e: Exception) {
                                                    cont.resumeWithException(e)
                                                }
                                            }
                                        }
                                    }

                                    override fun onFailure(errorCode: Int) {
                                        currentScreenshotIntervalMs = SAFE_SCREENSHOT_INTERVAL_MS
                                        // 截图失败时也要恢复显示悬浮框
                                        CoroutineScope(Dispatchers.Main).launch {
                                            showOverlay?.invoke()
                                        }
                                        cont.resumeWithException(
                                            RuntimeException("Screenshot failed with error code: $errorCode")
                                        )
                                    }
                                },
                            )
                        }
                    } ?: run {
                        currentScreenshotIntervalMs = SAFE_SCREENSHOT_INTERVAL_MS
                        // 超时处理
                        OmniLog.e(TAG, "captureDefaultScreenshot timeout after 10 seconds")
                        showOverlay?.invoke() // 恢复显示悬浮框
                        null
                    }
                    if (result != null) {
                        delayUnlock = true
                        return result
                    }
                } catch (e: Exception) {
                    lastError = e
                }
                currentScreenshotIntervalMs = SAFE_SCREENSHOT_INTERVAL_MS
                lastScreenshotCompletedAtMs = SystemClock.elapsedRealtime()
                if (attemptIndex == 0) {
                    OmniLog.w(TAG, "captureDefaultScreenshot retrying once with safe interval")
                }
            }
            currentScreenshotIntervalMs = SAFE_SCREENSHOT_INTERVAL_MS
            lastError?.let { OmniLog.e(TAG, "Failed to capture default screenshot", it) }
            return null
        } finally {
            lastScreenshotCompletedAtMs = SystemClock.elapsedRealtime()
            // 统一在 finally 中解锁
            if (delayUnlock) {
                try {
                    screenshotMutex.unlock()
                } catch (e: IllegalStateException) {
                    // Mutex 可能已经被解锁（例如被取消），忽略此异常
                    OmniLog.d(TAG, "Mutex already unlocked, ignoring")
                }
            } else {
                // 失败时立即解锁
                try {
                    screenshotMutex.unlock()
                } catch (e: IllegalStateException) {
                    // Mutex 可能已经被解锁，忽略此异常
                    OmniLog.d(TAG, "Mutex already unlocked, ignoring")
                }
            }
        }
    }

    /**
     * 过滤出所有有效窗口（排除我们的 overlay，包含应用窗口和系统UI如状态栏）
     * 这样可以确保截图包含状态栏，避免截图不完整
     */
    private fun filterValidWindows(
        windows: List<AccessibilityWindowInfo>
    ): List<Pair<AccessibilityWindowInfo, Int>> {
        return windows.mapNotNull { window ->
            when {
                // 排除无障碍服务的 overlay（我们的悬浮框）
                window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> {
                    window.recycle()
                    null
                }
                // 包含所有其他窗口类型：
                // - TYPE_APPLICATION: 应用窗口
                // - TYPE_SYSTEM_OVERLAY: 系统UI窗口（状态栏、导航栏等）
                // - TYPE_SPLIT_SCREEN_DIVIDER: 分屏分割线
                // - 其他系统窗口
                else -> {
                    window to window.id
                }
            }
        }
    }

    private fun resolveScreenSizeFromWindows(
        windows: List<AccessibilityWindowInfo>
    ): Point {
        val fallbackMetrics = service.resources.displayMetrics
        var minLeft = 0
        var minTop = 0
        var maxRight = 0
        var maxBottom = 0
        var sawBounds = false
        windows.forEach { window ->
            val bounds = Rect()
            val success = runCatching {
                window.getBoundsInScreen(bounds)
            }.isSuccess
            if (!success || bounds.isEmpty) return@forEach
            if (!sawBounds) {
                minLeft = bounds.left
                minTop = bounds.top
                maxRight = bounds.right
                maxBottom = bounds.bottom
                sawBounds = true
            } else {
                minLeft = minOf(minLeft, bounds.left)
                minTop = minOf(minTop, bounds.top)
                maxRight = maxOf(maxRight, bounds.right)
                maxBottom = maxOf(maxBottom, bounds.bottom)
            }
        }

        val boundsWidth = if (sawBounds) {
            maxOf(maxRight, maxRight - minLeft)
        } else {
            0
        }
        val boundsHeight = if (sawBounds) {
            maxOf(maxBottom, maxBottom - minTop)
        } else {
            0
        }
        return Point(
            maxOf(boundsWidth, fallbackMetrics.widthPixels, 1),
            maxOf(boundsHeight, fallbackMetrics.heightPixels, 1)
        )
    }

    /**
     * 截图多个窗口并合成
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun captureAndMergeWindows(
        windows: List<Pair<AccessibilityWindowInfo, Int>>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        // 按图层排序
        val sortedWindows = windows.sortedBy { it.first.layer }

        // 添加超时机制，避免永远阻塞（2秒超时）
        return withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine { cont ->
                val screenshots = mutableMapOf<Int, Pair<Bitmap, Rect>>()
                val lock = Any() // 用于同步计数器操作
                var successCount = 0
                var failureCount = 0
                val totalWindows = sortedWindows.size
                var isCompleted = false // 防止重复完成

                // 截图所有窗口
                sortedWindows.forEach { (window, windowId) ->
                    service.takeScreenshotOfWindow(
                        windowId, mainThreadExecutor, object :
                            AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                CoroutineScope(Dispatchers.Default).launch {
                                    try {
                                        screenshot.hardwareBuffer.use { hardwareBuffer ->
                                            val bitmap = Bitmap.wrapHardwareBuffer(
                                                hardwareBuffer, screenshot.colorSpace
                                            ) ?: run {
                                                // 位图转换失败，记录但继续
                                                OmniLog.w(
                                                    TAG,
                                                    "Failed to wrap hardware buffer for window: $windowId"
                                                )
                                                // 原子性递增计数器
                                                synchronized(lock) {
                                                    failureCount++
                                                }
                                                checkAndComplete()
                                                return@launch
                                            }

                                            val softwareBitmap = convertToSoftwareBitmap(bitmap)
                                            val bounds = Rect()
                                            window.getBoundsInScreen(bounds)

                                            synchronized(lock) {
                                                screenshots[windowId] = softwareBitmap to bounds
                                                successCount++
                                            }

                                            OmniLog.d(
                                                TAG,
                                                "Successfully captured window: id=$windowId, bounds=$bounds"
                                            )
                                            checkAndComplete()
                                        }
                                    } catch (e: Exception) {
                                        OmniLog.e(
                                            TAG,
                                            "Error processing screenshot for window: $windowId",
                                            e
                                        )
                                        // 原子性递增计数器
                                        synchronized(lock) {
                                            failureCount++
                                        }
                                        checkAndComplete()
                                    }
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                // 错误码 6 表示安全窗口（FLAG_SECURE），无法截图，这是正常的
                                // 记录日志但继续处理其他窗口
                                val bounds = Rect()
                                window.getBoundsInScreen(bounds)
                                when (errorCode) {
                                    6 -> {
                                        OmniLog.d(
                                            TAG,
                                            "Window $windowId is secure (FLAG_SECURE), skipping. bounds=$bounds"
                                        )
                                    }

                                    else -> {
                                        OmniLog.w(
                                            TAG,
                                            "Failed to capture window: id=$windowId, errorCode=$errorCode, bounds=$bounds"
                                        )
                                    }
                                }
                                // 原子性递增计数器
                                synchronized(lock) {
                                    failureCount++
                                }
                                checkAndComplete()
                            }

                            /**
                             * 检查是否所有窗口都已处理完成（成功或失败）
                             */
                            fun checkAndComplete() {
                                // 使用同步块防止并发问题
                                synchronized(lock) {
                                    val totalProcessed = successCount + failureCount
                                    if (totalProcessed == totalWindows && !isCompleted) {
                                        isCompleted = true

                                        // 所有窗口都已处理完成
                                        if (successCount > 0) {
                                            // 至少有一个窗口截图成功，进行合成
                                            try {
                                                // 先合并截图，再清理资源
                                                val mergedBitmap = mergeScreenshots(
                                                    screenshots,
                                                    sortedWindows,
                                                    screenWidth,
                                                    screenHeight
                                                )

                                                // 合并完成后再清理资源
                                                cleanupWindows(sortedWindows)
                                                cleanupBitmaps(screenshots.values.map { it.first })

                                                OmniLog.d(
                                                    TAG,
                                                    "Screenshot merge completed: success=$successCount, failed=$failureCount"
                                                )
                                                cont.resume(mergedBitmap)
                                            } catch (e: Exception) {
                                                OmniLog.e(TAG, "Failed to merge screenshots", e)
                                                cleanupWindows(sortedWindows)
                                                cleanupBitmaps(screenshots.values.map { it.first })
                                                cont.resume(null)
                                            }
                                        } else {
                                            // 所有窗口都失败了
                                            OmniLog.e(
                                                TAG,
                                                "All windows failed to capture: total=$totalWindows"
                                            )
                                            cleanupWindows(sortedWindows)
                                            cont.resume(null)
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        } ?: run {
            // 超时处理
            OmniLog.e(TAG, "captureAndMergeWindows timeout after 10 seconds")
            cleanupWindows(sortedWindows)
            null
        }
    }

    /**
     * 合并多个窗口截图
     */
    private fun mergeScreenshots(
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        sortedWindows: List<Pair<AccessibilityWindowInfo, Int>>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        return try {
            // 创建全屏位图
            val mergedBitmap = createBitmap(screenWidth, screenHeight)
            val canvas = Canvas(mergedBitmap)

            // 填充黑色背景
            canvas.drawColor(Color.BLACK)
            // 按图层顺序绘制所有窗口
            for ((window, windowId) in sortedWindows) {
                val screenshotPair = screenshots[windowId] ?: continue
                val (bitmap, bounds) = screenshotPair
                
                // 检查 Bitmap 是否已被回收
                if (bitmap.isRecycled) {
                    OmniLog.w(TAG, "Skipping recycled bitmap for window: id=$windowId")
                    continue
                }
                
                try {
                    val boundsWidth = bounds.width()
                    val boundsHeight = bounds.height()
                    val bitmapWidth = bitmap.getScaledWidth(canvas)
                    val bitmapHeight = bitmap.getScaledHeight(canvas)
                    OmniLog.d(TAG, "Merging window: boundsWidth=$boundsWidth, boundsHeight=$boundsHeight, bitmapWidth=$bitmapWidth, bitmapHeight=$bitmapHeight")
                    
                    // 检查位图尺寸与窗口边界是否匹配（允许 1 像素的误差）
                    val sizeMatches = (kotlin.math.abs(bitmapWidth - boundsWidth) <= 1 && 
                                      kotlin.math.abs(bitmapHeight - boundsHeight) <= 1)
                    
                    if (sizeMatches) {
                        // 尺寸匹配，直接绘制
                        canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), null)
                        OmniLog.d(
                            TAG,
                            "Drawing window: id=$windowId, bounds=$bounds, " +
                                    "bitmap=${bitmapWidth}x${bitmapHeight} (matches)"
                        )
                    } else {
                        // 尺寸不匹配，需要特殊处理
                        val widthDiff = bitmapWidth - boundsWidth
                        val heightDiff = bitmapHeight - boundsHeight
                        
                        OmniLog.w(
                            TAG,
                            "Window bitmap size mismatch: id=$windowId, " +
                                    "bounds=${boundsWidth}x${boundsHeight}, " +
                                    "bitmap=${bitmapWidth}x${bitmapHeight}, " +
                                    "diff=($widthDiff, $heightDiff)"
                        )
                        
                        // 返回的 bitmap 比 bounds 大时：先去掉四周纯透明边，再从中心取 bounds 尺寸的一块绘制
                        if (bitmapWidth > boundsWidth || bitmapHeight > boundsHeight) {
                            var workBitmap = bitmap
                            var trimmed = false
                            val pureTransparentRect = trimTransparentBorders(bitmap, alphaThreshold = 1)
                            if (pureTransparentRect != null &&
                                (pureTransparentRect.width() < bitmapWidth || pureTransparentRect.height() < bitmapHeight)) {
                                val trimmedBitmap = try {
                                    Bitmap.createBitmap(
                                        bitmap,
                                        pureTransparentRect.left,
                                        pureTransparentRect.top,
                                        pureTransparentRect.width(),
                                        pureTransparentRect.height()
                                    )
                                } catch (e: Exception) {
                                    OmniLog.w(TAG, "trim pure transparent failed: ${e.message}")
                                    null
                                }
                                if (trimmedBitmap != null) {
                                    workBitmap = trimmedBitmap
                                    trimmed = true
                                    OmniLog.d(
                                        TAG,
                                        "Trimmed pure transparent: id=$windowId, ${bitmapWidth}x${bitmapHeight} -> ${workBitmap.width}x${workBitmap.height}"
                                    )
                                }
                            }
                            val ww = workBitmap.width
                            val wh = workBitmap.height
                            val cropWidth = boundsWidth.coerceAtMost(ww)
                            val cropHeight = boundsHeight.coerceAtMost(wh)
                            val maxLeft = (ww - cropWidth).coerceAtLeast(0)
                            val maxTop = (wh - cropHeight).coerceAtLeast(0)
                            val cropLeft = ((ww - cropWidth) / 2).coerceIn(0, maxLeft)
                            val cropTop = ((wh - cropHeight) / 2).coerceIn(0, maxTop)
                            OmniLog.d(TAG, "crp[$cropLeft,$cropTop,$cropWidth,$cropHeight]")
                            val croppedBitmap = try {
                                Bitmap.createBitmap(workBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                            } catch (e: Exception) {
                                OmniLog.e(TAG, "Failed to crop bitmap for window: id=$windowId", e)
                                canvas.drawBitmap(workBitmap, bounds.left.toFloat(), bounds.top.toFloat(), null)
                                null
                            }
                            if (croppedBitmap != null) {
                                val cw = croppedBitmap.width
                                val ch = croppedBitmap.height
                                canvas.drawBitmap(croppedBitmap, bounds.left.toFloat(), bounds.top.toFloat(), null)
                                croppedBitmap.recycle()
                                if (trimmed) {
                                    workBitmap.recycle()
                                }
                                OmniLog.d(
                                    TAG,
                                    "Drawing window (cropped from center): id=$windowId, bounds=$bounds, " +
                                            "bitmap=${ww}x${wh}, cropped=${cw}x${ch}, cropOffset=($cropLeft,$cropTop)"
                                )
                            }
                        } else {
                            // 位图尺寸小于窗口边界：进行放大以适应边界
                            val scaleX = boundsWidth.toFloat() / bitmapWidth
                            val scaleY = boundsHeight.toFloat() / bitmapHeight
                            
                            val matrix = Matrix()
                            matrix.setScale(scaleX, scaleY)
                            matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                            
                            canvas.drawBitmap(bitmap, matrix, null)
                            
                            OmniLog.d(
                                TAG,
                                "Drawing window (scaled up): id=$windowId, bounds=$bounds, " +
                                        "bitmap=${bitmapWidth}x${bitmapHeight}, " +
                                        "scale=($scaleX, $scaleY)"
                            )
                        }
                    }
                } catch (e: Exception) {
                    OmniLog.e(TAG, "Failed to draw bitmap for window: id=$windowId", e)
                    // 继续处理其他窗口
                }
            }

            mergedBitmap
        } catch (e: Exception) {
            OmniLog.e(TAG, "Failed to merge screenshots", e)
            null
        }
    }

    /**
     * 裁掉 bitmap 四周的透明/半透明边和阴影，返回内容区域的 Rect（在 bitmap 坐标系内）。
     * 用于处理系统返回的 bitmap 带透明边框、阴影导致与 bounds 不一致的情况。
     * @param alphaThreshold 低于此 alpha 视为透明边（0~255），默认 25，可过滤阴影
     * @return 内容区域，若全透明或异常则返回 null
     */
    private fun trimTransparentBorders(bitmap: Bitmap, alphaThreshold: Int = 25): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return null
        val row = IntArray(w)
        val col = IntArray(h)
        var top = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            if (row.any { ((it shr 24) and 0xFF) > alphaThreshold }) {
                top = y
                break
            }
        }
        if (top < 0) return null
        var bottom = -1
        for (y in h - 1 downTo 0) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            if (row.any { ((it shr 24) and 0xFF) > alphaThreshold }) {
                bottom = y
                break
            }
        }
        var left = -1
        for (x in 0 until w) {
            bitmap.getPixels(col, 0, 1, x, 0, 1, h)
            if (col.any { ((it shr 24) and 0xFF) > alphaThreshold }) {
                left = x
                break
            }
        }
        var right = -1
        for (x in w - 1 downTo 0) {
            bitmap.getPixels(col, 0, 1, x, 0, 1, h)
            if (col.any { ((it shr 24) and 0xFF) > alphaThreshold }) {
                right = x
                break
            }
        }
        if (left < 0 || right < 0 || top > bottom || left > right) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    /**
     * 转换硬件位图为软件位图
     */
    private fun convertToSoftwareBitmap(bitmap: Bitmap): Bitmap {
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }

    /**
     * 清理窗口资源
     * 安全地回收窗口，如果窗口已被回收则跳过
     */
    private fun cleanupWindows(windows: List<Pair<AccessibilityWindowInfo, Int>>) {
        windows.forEach { (window, windowId) ->
            try {
                window.recycle()
            } catch (e: IllegalStateException) {
                // 捕获 "Already in the pool" 异常
                val message = e.message ?: ""
                if (message.contains("pool", ignoreCase = true) || 
                    message.contains("Already", ignoreCase = true)) {
                    OmniLog.d(TAG, "Window $windowId already recycled (in pool), skipping")
                } else {
                    OmniLog.e(TAG, "Error recycling window: $windowId", e)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error recycling window: $windowId", e)
            }
        }
    }

    /**
     * 清理位图资源
     */
    private fun cleanupBitmaps(bitmaps: List<Bitmap>) {
        bitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * 处理错误并清理资源
     */
    private fun <T> handleError(
        e: Exception,
        windows: List<Pair<AccessibilityWindowInfo, Int>>,
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        cont: Continuation<T?>
    ) {
        OmniLog.e(TAG, "Error during screenshot capture", e)
        cleanupWindows(windows)
        cleanupBitmaps(screenshots.values.map { it.first })
        cont.resume(null)
    }

}
