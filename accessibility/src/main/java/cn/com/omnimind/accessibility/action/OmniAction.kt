package cn.com.omnimind.accessibility.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.MobileManufacturer
import cn.com.omnimind.baselib.util.MobileManufacturerUtil
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OmniGestureDispatchTimeoutException(message: String) : RuntimeException(message)

class OmniAction(
    private val service: AssistsService,
) {
    companion object {
        private const val TAG = "OmniGestureController"
        private const val CLICK_DURATION = 50L
        private const val CLICK_TIMEOUT_MS = 900L
        private const val LONG_CLICK_DURATION = 1000L
        private const val SCROLL_DISTANCE = 300f
        private const val SCROLL_DURATION = 500L
        private const val GESTURE_TIMEOUT_GRACE_MS = 700L
        private const val GESTURE_CALLBACK_THREAD_NAME = "OmniGestureCallback"

        private val gestureCallbackThread: HandlerThread by lazy {
            HandlerThread(GESTURE_CALLBACK_THREAD_NAME).apply { start() }
        }
    }

    private val windowBounds: Rect by lazy {
        val metrics = DisplayMetrics().apply { setTo(service.resources.displayMetrics) }
        runCatching {
            val displayManager = service.getSystemService(DisplayManager::class.java)
            @Suppress("DEPRECATION")
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        }
        Rect(0, 0, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }
    private val screenWidth: Float by lazy { windowBounds.width().toFloat() }
    private val screenHeight: Float by lazy { windowBounds.height().toFloat() }
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val gestureCallbackHandler: Handler by lazy { Handler(gestureCallbackThread.looper) }

    /**
     * accessibility click action
     * if node clickable ==> performAction(ACTION_CLICK)
     * otherwise         ==> clickCoordinate
     */
    fun clickNode(node: AccessibilityNodeInfo) {

        if (node.isClickable) {
            if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                throw RuntimeException("Perform click on node failed")
            }
        } else {
            throw RuntimeException("Node is not clickable")
        }
    }

    /**
     * accessibility long click action
     * if node clickable ==> performAction(ACTION_LONG_CLICK)
     * otherwise         ==> clickCoordinate
     */
    fun longClickNode(node: AccessibilityNodeInfo) {

        if (node.isLongClickable) {
            if (!node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                throw RuntimeException("Perform long click on node failed")
            }
        } else {
            throw RuntimeException("Node is not long clickable")
        }
    }

    /**
     * accessibility scroll node action
     */
    fun scrollNode(
        node: AccessibilityNodeInfo,
        direction: AccessibilityNodeScrollDirection,
    ) {
        OmniLog.v(TAG, "fun scrollNode")

        val action =
            when (direction) {
                AccessibilityNodeScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                AccessibilityNodeScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }

        if (node.isScrollable) {
            if (!node.performAction(action)) {
                throw RuntimeException("Scroll on node failed")
            }
        } else {
            throw RuntimeException("Node is not scrollable")
        }
    }

    /**
     * accessibility text input action
     */
    fun inputText(
        node: AccessibilityNodeInfo,
        text: String,
    ) {
        OmniLog.v(TAG, "fun inputText")

        if (node.isEditable) {
            val arguments =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }

            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                throw RuntimeException("Perform input text on node failed")
            }
        } else {
            throw RuntimeException("Node is not editable")
        }
    }

    fun performImeEnter(node: AccessibilityNodeInfo) {
        OmniLog.v(TAG, "fun performImeEnter")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw RuntimeException("IME enter requires Android 11+")
        }
        if (!node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            throw RuntimeException("Perform IME enter failed")
        }
    }

    fun setProgress(
        node: AccessibilityNodeInfo,
        value: Float,
    ) {
        OmniLog.v(TAG, "fun setProgress")
        val range = node.rangeInfo ?: throw RuntimeException("Node has no range info")
        val progress = value.coerceIn(range.min, range.max)
        val arguments = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, progress)
        }
        if (!node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments)) {
            throw RuntimeException("Perform set progress on node failed")
        }
    }

    /**
     * 复制文本到系统剪贴板（需在主线程执行）
     * 注意：Android 10+ 后台应用无法访问剪贴板，建议使用 ClipboardHelperActivity
     */
    fun copyToClipboard(text: String) {
        val latch = CountDownLatch(1)
        var success = false

        val runnable = Runnable {
            try {
                val clipboard = service.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("omni_clipboard", text)
                clipboard.setPrimaryClip(clip)
                success = clipboard.hasPrimaryClip()
            } catch (e: Exception) {
                OmniLog.e(TAG, "copyToClipboard failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            Handler(Looper.getMainLooper()).post(runnable)
            latch.await(2, TimeUnit.SECONDS)
        }

        if (!success) {
            throw RuntimeException("Failed to copy to clipboard")
        }
    }

    /**
     * android inject text by Omni IME action
     */
    fun injectTextByIME(text: String) {
        OmniLog.v(TAG, "fun injectTextByIME")

        // OmniIME.instance?.inputText(text)
    }

    /**
     * accessibility gesture click action
     */
    fun clickCoordinate(
        x: Float,
        y: Float,
    ): CompletableFuture<Unit> {
        OmniLog.v(TAG, "fun clickCoordinate")

        return clickCoordinateImpl(x, y, CLICK_DURATION, CLICK_TIMEOUT_MS)
    }

    fun longClickCoordinate(
        x: Float,
        y: Float,
        duration: Long = LONG_CLICK_DURATION,
    ): CompletableFuture<Unit> {
        OmniLog.v(TAG, "fun longClickCoordinate")

        return clickCoordinateImpl(x, y, duration, duration + GESTURE_TIMEOUT_GRACE_MS)
    }

    private fun clickCoordinateImpl(
        x: Float,
        y: Float,
        duration: Long,
        timeoutMs: Long,
    ): CompletableFuture<Unit> {
        val safeX = x.coerceIn(0f, (screenWidth - 1f).coerceAtLeast(0f))
        val safeY = y.coerceIn(0f, (screenHeight - 1f).coerceAtLeast(0f))
        val endX = if (safeX < screenWidth - 1f) safeX + 0.1f else (safeX - 0.1f).coerceAtLeast(0f)
        val path = Path()
        path.moveTo(safeX, safeY)
        path.lineTo(endX, safeY)

        val gestureBuilder =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration,
                    ),
                )

        return dispatchGestureWithTimeout(
            gestureDescription = gestureBuilder.build(),
            timeoutMs = timeoutMs,
            timeoutLabel = "coordinate_click"
        )
    }

    /**
     * accessibility perform gesture scroll action
     */
    fun scrollCoordinate(
        x: Float,
        y: Float,
        direction: AccessibilityScrollDirection,
        distance: Float,
        duration: Long = SCROLL_DURATION,
    ): CompletableFuture<Unit> {
        OmniLog.v(TAG, "fun swipeCoordinate")
        OmniLog.v(TAG, "Window width: $screenWidth, height: $screenHeight")

        val (endX, endY) =
            when (direction) {
                AccessibilityScrollDirection.LEFT -> Pair(maxOf(x - distance, 0f), y)
                AccessibilityScrollDirection.RIGHT -> Pair(minOf(x + distance, screenWidth), y)
                AccessibilityScrollDirection.UP -> Pair(x, maxOf(y - distance, 0f))
                AccessibilityScrollDirection.DOWN -> Pair(x, minOf(y + distance, screenHeight))
            }

        val path =
            Path().apply {
                moveTo(x, y)
                lineTo(endX, endY)
            }

        val gestureBuilder =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration,
                    ),
                )

        return dispatchGestureWithTimeout(
            gestureDescription = gestureBuilder.build(),
            timeoutMs = duration + GESTURE_TIMEOUT_GRACE_MS,
            timeoutLabel = "coordinate_scroll"
        )
    }

    private fun dispatchGestureWithTimeout(
        gestureDescription: GestureDescription,
        timeoutMs: Long,
        timeoutLabel: String,
    ): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        val completed = AtomicBoolean(false)
        val boundedTimeoutMs = timeoutMs.coerceAtLeast(1L)
        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                future.completeExceptionally(
                    OmniGestureDispatchTimeoutException(
                        "dispatch_timeout:$timeoutLabel after ${boundedTimeoutMs}ms"
                    )
                )
            }
        }
        val callback =
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completeGestureFuture(completed, timeoutRunnable, future, null)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completeGestureFuture(
                        completed,
                        timeoutRunnable,
                        future,
                        RuntimeException("Gesture was cancelled")
                    )
                }
            }

        val dispatchGesture = Runnable {
            if (completed.get()) return@Runnable
            val dispatchResult =
                service.dispatchGesture(
                    gestureDescription,
                    callback,
                    gestureCallbackHandler,
                )

            if (!dispatchResult) {
                completeGestureFuture(
                    completed,
                    timeoutRunnable,
                    future,
                    RuntimeException("Failed to dispatch gesture")
                )
            }
        }

        if (!gestureCallbackHandler.postDelayed(timeoutRunnable, boundedTimeoutMs)) {
            future.completeExceptionally(RuntimeException("Failed to post gesture timeout"))
            return future
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchGesture.run()
        } else if (!mainHandler.post(dispatchGesture)) {
            completeGestureFuture(
                completed,
                timeoutRunnable,
                future,
                RuntimeException("Failed to post gesture dispatch")
            )
        }

        return future
    }

    private fun completeGestureFuture(
        completed: AtomicBoolean,
        timeoutRunnable: Runnable,
        future: CompletableFuture<Unit>,
        error: Throwable?,
    ) {
        if (!completed.compareAndSet(false, true)) return
        gestureCallbackHandler.removeCallbacks(timeoutRunnable)
        if (error == null) {
            future.complete(Unit)
        } else {
            future.completeExceptionally(error)
        }
    }

    private fun performGlobalActionImpl(action: Int) {
        if (!service.performGlobalAction(action)) {
            OmniLog.w(TAG, "performGlobalAction failed (id=$action), service may be not ready")
        }
    }

    fun goHome() {
        OmniLog.v(TAG, "fun globalActionHome")
        performGlobalActionImpl(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun goBack() {
        OmniLog.v(TAG, "fun globalActionBack")
        performGlobalActionImpl(AccessibilityService.GLOBAL_ACTION_BACK)
    }

//    fun pressEnterKey() {
//        OmniLog.v(TAG, "fun pressEnterKey")
//        val now = SystemClock.uptimeMillis()
//
//
//        val downEvent: KeyEvent = KeyEvent(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
//        dispatchKeyEvent(downEvent)
//
//
//        val upEvent: KeyEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
//        dispatchKeyEvent(upEvent)
//    }

    /**
     * accessibility launch app action
     */
    fun launchApplication(packageName: String) {
        OmniLog.v(TAG, "fun launchApplication")

        // 检查应用是否在隐私黑名单中
        if (!APPPackageUtil.isPackageAuthorized(packageName)) {
            var appName = if (APPPackageUtil.getAppName(service, packageName).isNotEmpty()) {
                APPPackageUtil.getAppName(service, packageName)
            } else {
                packageName
            }
            throw PrivacyBlockedException("应用 $appName 未授权，已被隐私设置限制")
        }

        if (MobileManufacturerUtil.getDeviceManufacturer() == MobileManufacturer.HONOR) {
            LaunchRequest.requestLaunch(service, packageName)
        } else {
            var intent = service.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // 清除原有任务栈
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                service.startActivity(intent)
            } else {
                throw RuntimeException("Application with package name $packageName not found")
            }
        }
    }

    /**
     * get app info
     */
    fun listInstalledApplications(): Pair<List<String>, List<String>> {
        OmniLog.v(TAG, "fun listInstalledApplications")
        val packageManager = service.packageManager
        val applications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

        val filteredApps =
            applications
                .filter {
                    val launchIntent = packageManager.getLaunchIntentForPackage(it.packageName)
                    launchIntent != null
                }.sortedBy { it.loadLabel(packageManager).toString() }

        val applicationNames = filteredApps.map { it.loadLabel(packageManager).toString() }
        val packageNames = filteredApps.map { it.packageName }
        OmniLog.i(TAG, "Find ${packageNames.size} app packages!")

        return Pair(packageNames, applicationNames)
    }
}
