package cn.com.omnimind.uikit.loader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.view.indicator.ClickIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object ManualTouchRecordLoader {
    private const val TAG = "ManualTouchRecordLoader"
    private const val MIN_SWIPE_DISTANCE_DP = 24f
    private const val OVERLAY_UNLOCK_REPLAY_DELAY_MS = 32L
    private const val IME_VISIBILITY_GRACE_MS = 180L
    private const val IME_RELOCK_POLL_MS = 300L

    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var imeBypassJob: Job? = null
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var downAtMs = 0L
    private var isTracking = false
    private var isSwipe = false
    private var isProcessing = false

    fun show(context: Context? = UIKit.appContext): Boolean {
        val appContext = context?.applicationContext ?: UIKit.appContext
        val accessibilityContext = AssistsService.instance
        val safeContext = appContext ?: accessibilityContext ?: return false
        val shown = synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                lockTouchLocked()
                return@synchronized true
            }
            val candidates = buildList {
                appContext?.let { add(it to false) }
                accessibilityContext?.let { add(it to false) }
            }.distinctBy { it.first }
            for ((candidateContext, accessibilityOverlay) in candidates) {
                if (tryShowLocked(candidateContext, accessibilityOverlay)) {
                    return@synchronized true
                }
            }
            OmniLog.w(TAG, "manual touch recording overlay unavailable")
            false
        }
        if (shown) {
            ManualRecordingControlOverlay.ensureOnTop()
        }
        return shown
    }

    fun hide() {
        synchronized(this) {
            isTracking = false
            isSwipe = false
            isProcessing = false
            imeBypassJob?.cancel()
            imeBypassJob = null
            val view = overlayView
            val manager = windowManager
            overlayView = null
            overlayParams = null
            windowManager = null
            if (view != null && manager != null && view.isAttachedToWindow) {
                runCatching { manager.removeView(view) }
                    .onFailure { OmniLog.w(TAG, "hide failed: ${it.message}") }
            }
        }
    }

    private fun tryShowLocked(
        context: Context,
        accessibilityOverlay: Boolean
    ): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildTouchView(context)
        val params = buildParams(context, accessibilityOverlay, touchable = true)
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            val overlayType = if (accessibilityOverlay) "accessibility" else "application"
            OmniLog.d(
                TAG,
                "manual touch recording overlay shown type=$overlayType"
            )
            true
        }.getOrElse { error ->
            OmniLog.e(
                TAG,
                "show failed type=${if (accessibilityOverlay) "accessibility" else "application"}: ${error.message}",
                error
            )
            false
        }
    }

    private fun buildTouchView(context: Context): View {
        val minSwipeDistance = MIN_SWIPE_DISTANCE_DP * context.resources.displayMetrics.density
        val touchSlop = max(
            minSwipeDistance,
            ViewConfiguration.get(context).scaledTouchSlop.toFloat() * 2f
        )
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        return View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                handleTouchEvent(event, touchSlop, longPressTimeout)
                true
            }
        }
    }

    private fun buildParams(
        context: Context,
        accessibilityOverlay: Boolean,
        touchable: Boolean
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val touchFlag = if (touchable) {
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                } else {
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                touchFlag
            format = PixelFormat.TRANSLUCENT
            val displaySize = realDisplaySize(context)
            width = displaySize.x
            height = displaySize.y
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun handleTouchEvent(
        event: MotionEvent,
        touchSlop: Float,
        longPressTimeout: Long
    ) {
        if (isProcessing) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = clampRawPoint(event)
                startX = point.x
                startY = point.y
                endX = point.x
                endY = point.y
                downAtMs = System.currentTimeMillis()
                isTracking = true
                isSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
                if (distance(startX, startY, endX, endY) >= touchSlop) {
                    isSwipe = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
                val finishedAtMs = System.currentTimeMillis()
                val durationMs = finishedAtMs - downAtMs
                val distancePx = distance(startX, startY, endX, endY)
                val actionName = when {
                    isSwipe || distancePx >= touchSlop -> "swipe"
                    durationMs >= longPressTimeout -> "long_press"
                    else -> "click"
                }
                isTracking = false
                dispatchGesture(
                    ManualOverlayTouchGesture(
                        actionName = actionName,
                        startX = startX,
                        startY = startY,
                        endX = endX,
                        endY = endY,
                        durationMs = durationMs,
                        distancePx = distancePx,
                        direction = directionName(startX, startY, endX, endY).takeIf { actionName == "swipe" },
                        startedAtMs = downAtMs,
                        finishedAtMs = finishedAtMs,
                        displayWidth = realDisplaySize(overlayView?.context ?: UIKit.appContext).x,
                        displayHeight = realDisplaySize(overlayView?.context ?: UIKit.appContext).y
                    )
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                isTracking = false
                isSwipe = false
            }
        }
    }

    private fun dispatchGesture(gesture: ManualOverlayTouchGesture) {
        isProcessing = true
        showGestureFeedback(gesture)
        synchronized(this) {
            unlockTouchLocked()
        }
        recordScope.launch {
            runCatching {
                delay(OVERLAY_UNLOCK_REPLAY_DELAY_MS)
                HumanTrajectoryLearningSession.recordOverlayGesture(gesture)
            }.onFailure { error ->
                OmniLog.w(TAG, "record overlay gesture failed: ${error.message}")
            }
            if (gesture.actionName == "click") {
                delay(IME_VISIBILITY_GRACE_MS)
            }
            withContext(Dispatchers.Main) {
                var shouldKeepRecording = false
                var shouldStayUnlockedForIme = false
                synchronized(this@ManualTouchRecordLoader) {
                    isProcessing = false
                    if (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
                        if (isImeVisibleLocked()) {
                            shouldStayUnlockedForIme = true
                            scheduleImeRelockLocked()
                        } else {
                            lockTouchLocked()
                        }
                        shouldKeepRecording = true
                    } else {
                        hide()
                    }
                }
                if (shouldKeepRecording) {
                    ManualRecordingControlOverlay.ensureOnTop()
                    if (shouldStayUnlockedForIme) {
                        ManualRecordingControlOverlay.showTransientStatus("输入中", 1400L)
                    }
                }
            }
        }
    }

    private fun showGestureFeedback(gesture: ManualOverlayTouchGesture) {
        ManualRecordingControlOverlay.showTransientStatus(
            when (gesture.actionName) {
                "swipe" -> "重放滑动"
                "long_press" -> "重放长按"
                else -> "重放点击"
            },
            durationMs = 700L
        )
        val service = AssistsService.instance ?: return
        val x = if (gesture.actionName == "swipe") {
            (gesture.startX + gesture.endX) / 2f
        } else {
            gesture.startX
        }
        val y = if (gesture.actionName == "swipe") {
            (gesture.startY + gesture.endY) / 2f
        } else {
            gesture.startY
        }
        runCatching {
            ClickIndicator(service, x, y).showWithoutSuspend { }
        }.onFailure { error ->
            OmniLog.w(TAG, "show manual gesture feedback failed: ${error.message}")
        }
    }

    private fun scheduleImeRelockLocked() {
        if (imeBypassJob?.isActive == true) return
        imeBypassJob = recordScope.launch {
            while (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
                val imeVisible = withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        isImeVisibleLocked()
                    }
                }
                if (!imeVisible) break
                delay(IME_RELOCK_POLL_MS)
            }
            withContext(Dispatchers.Main) {
                var relocked = false
                synchronized(this@ManualTouchRecordLoader) {
                    imeBypassJob = null
                    if (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
                        lockTouchLocked()
                        relocked = true
                    }
                }
                if (relocked) {
                    ManualRecordingControlOverlay.ensureOnTop()
                    ManualRecordingControlOverlay.showTransientStatus("继续录制", 700L)
                }
            }
        }
    }

    private fun isImeVisibleLocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return overlayView?.rootWindowInsets
            ?.isVisible(WindowInsets.Type.ime()) == true
    }

    private fun lockTouchLocked() {
        updateTouchableLocked(touchable = true)
    }

    private fun unlockTouchLocked() {
        updateTouchableLocked(touchable = false)
    }

    private fun updateTouchableLocked(touchable: Boolean) {
        val view = overlayView ?: return
        val manager = windowManager ?: return
        val context = view.context ?: return
        if (!view.isAttachedToWindow) return
        val params = buildParams(context, accessibilityOverlay = false, touchable)
        overlayParams = params
        runCatching { manager.updateViewLayout(view, params) }
            .onFailure { OmniLog.w(TAG, "update touchable=$touchable failed: ${it.message}") }
    }

    private fun clampRawPoint(event: MotionEvent): PointF {
        val displaySize = realDisplaySize(overlayView?.context ?: UIKit.appContext)
        val maxX = (displaySize.x - 1).coerceAtLeast(0).toFloat()
        val maxY = (displaySize.y - 1).coerceAtLeast(0).toFloat()
        return PointF(
            event.rawX.coerceIn(0f, maxX),
            event.rawY.coerceIn(0f, maxY)
        )
    }

    private fun realDisplaySize(context: Context?): Point {
        val safeContext = context ?: UIKit.appContext
        if (safeContext == null) return Point(0, 0)
        return runCatching {
            val manager = safeContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenSize = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(screenSize)
            screenSize
        }.getOrDefault(Point(0, 0))
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun directionName(x1: Float, y1: Float, x2: Float, y2: Float): String {
        val dx = x2 - x1
        val dy = y2 - y1
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }
}
