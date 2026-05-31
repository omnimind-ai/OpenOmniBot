package cn.com.omnimind.uikit.loader

import android.content.Context
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
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
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object ManualTouchRecordLoader {
    private const val TAG = "ManualTouchRecordLoader"
    private const val MIN_SWIPE_DISTANCE_DP = 24f
    private const val OVERLAY_UNLOCK_REPLAY_DELAY_MS = 32L
    private const val IME_VISIBILITY_PROBE_DELAY_MS = 120L
    private const val IME_VISIBILITY_PROBE_TIMEOUT_MS = 700L
    private const val IME_VISIBILITY_PROBE_POLL_MS = 70L
    private const val IME_RELOCK_INITIAL_DELAY_MS = 400L
    private const val IME_RELOCK_POLL_MS = 300L

    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var currentTouchable: Boolean? = null
    private var currentOverlayHeight: Int = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var imeVisibilityProbeJob: Job? = null
    private var imeBypassJob: Job? = null
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var downAtMs = 0L
    private var isTracking = false
    private var isProcessing = false
    private val pendingGestures = ArrayDeque<ManualOverlayTouchGesture>()

    fun show(context: Context? = UIKit.appContext): Boolean {
        val appContext = context?.applicationContext ?: UIKit.appContext
        if (appContext == null) return false
        return synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                lockTouchLocked()
                return@synchronized true
            }
            if (tryShowLocked(appContext)) return@synchronized true
            OmniLog.w(TAG, "manual touch recording overlay unavailable")
            false
        }
    }

    fun hide() {
        synchronized(this) {
            isTracking = false
            isProcessing = false
            pendingGestures.clear()
            imeVisibilityProbeJob?.cancel()
            imeVisibilityProbeJob = null
            imeBypassJob?.cancel()
            imeBypassJob = null
            val view = overlayView
            val manager = windowManager
            overlayView = null
            overlayParams = null
            windowManager = null
            currentTouchable = null
            currentOverlayHeight = 0
            displayWidth = 0
            displayHeight = 0
            if (view != null && manager != null && view.isAttachedToWindow) {
                runCatching { manager.removeView(view) }
                    .onFailure { OmniLog.w(TAG, "hide failed: ${it.message}") }
            }
        }
    }

    private fun tryShowLocked(context: Context): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildTouchView(context)
        val displaySize = realDisplaySize(context)
        val params = buildParams(context, touchable = true)
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            currentTouchable = true
            currentOverlayHeight = params.height
            displayWidth = displaySize.x
            displayHeight = displaySize.y
            OmniLog.d(TAG, "manual touch recording overlay shown type=application")
            true
        }.getOrElse { error ->
            OmniLog.e(TAG, "show failed type=application: ${error.message}", error)
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
        touchable: Boolean
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
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
            height = overlayHeightForParamsLocked(touchable, displaySize.y)
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun handleTouchEvent(
        event: MotionEvent,
        touchSlop: Float,
        longPressTimeout: Long
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = clampRawPoint(event)
                startX = point.x
                startY = point.y
                endX = point.x
                endY = point.y
                downAtMs = System.currentTimeMillis()
                isTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
            }
            MotionEvent.ACTION_UP -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
                val finishedAtMs = System.currentTimeMillis()
                val durationMs = finishedAtMs - downAtMs
                val distancePx = distance(startX, startY, endX, endY)
                // Use net displacement (start→end), not peak displacement during move.
                // Peak-based detection misclassifies taps where the finger briefly drifts.
                val actionName = when {
                    distancePx >= touchSlop -> "swipe"
                    durationMs >= longPressTimeout -> "long_press"
                    else -> "click"
                }
                isTracking = false
                val gesture = ManualOverlayTouchGesture(
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
                    displayWidth = currentDisplaySize().x,
                    displayHeight = currentDisplaySize().y
                )
                enqueueGesture(gesture)
            }
            MotionEvent.ACTION_CANCEL -> {
                isTracking = false
            }
        }
    }

    private fun enqueueGesture(gesture: ManualOverlayTouchGesture) {
        val shouldStartWorker = synchronized(this) {
            pendingGestures.addLast(gesture)
            if (isProcessing) {
                false
            } else {
                isProcessing = true
                true
            }
        }
        if (shouldStartWorker) {
            processGestureQueue()
        }
    }

    private fun processGestureQueue() {
        recordScope.launch {
            while (true) {
                val gesture = synchronized(this@ManualTouchRecordLoader) {
                    pendingGestures.pollFirst()
                }
                if (gesture == null) {
                    val shouldContinue = withContext(Dispatchers.Main) {
                        var continueProcessing = false
                        var shouldUseImeAwareCapture = false
                        var shouldShowInputStatus = false
                        synchronized(this@ManualTouchRecordLoader) {
                            if (pendingGestures.isNotEmpty()) {
                                continueProcessing = true
                            } else {
                                isProcessing = false
                                if (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
                                    if (isImeVisibleLocked()) {
                                        lockTouchLocked()
                                        shouldUseImeAwareCapture = true
                                        shouldShowInputStatus = true
                                        scheduleImeRelockLocked()
                                    } else {
                                        lockTouchLocked()
                                    }
                                } else {
                                    hide()
                                }
                            }
                        }
                        if (shouldUseImeAwareCapture && shouldShowInputStatus) {
                            ManualRecordingControlOverlay.showTransientStatus("输入中", 1400L)
                        }
                        continueProcessing
                    }
                    if (shouldContinue) {
                        continue
                    }
                    return@launch
                }
                val keepRecording = processQueuedGesture(gesture)
                if (!keepRecording) {
                    withContext(Dispatchers.Main) {
                        synchronized(this@ManualTouchRecordLoader) {
                            pendingGestures.clear()
                            isProcessing = false
                            hide()
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private suspend fun processQueuedGesture(gesture: ManualOverlayTouchGesture): Boolean {
        var sessionStillActive = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                val active = HumanTrajectoryLearningSession.isActive() &&
                    !HumanTrajectoryLearningSession.isPaused()
                if (active) {
                    unlockTouchLocked()
                }
                active
            }
        }
        if (!sessionStillActive) return false

        var executed = false
        var recorded = false
        runCatching {
            delay(OVERLAY_UNLOCK_REPLAY_DELAY_MS)
            val replayResult = HumanTrajectoryLearningSession.recordOverlayGesture(gesture) {
                withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        if (overlayView?.isAttachedToWindow == true &&
                            HumanTrajectoryLearningSession.isActive() &&
                            !HumanTrajectoryLearningSession.isPaused()) {
                            lockTouchLocked()
                        }
                    }
                }
            }
            executed = replayResult.executed
            recorded = replayResult.recorded
        }.onFailure { error ->
            OmniLog.w(TAG, "record overlay gesture failed: ${error.message}")
        }

        if (executed && recorded) {
            withContext(Dispatchers.Main) { showGestureFeedback(gesture) }
        } else if (executed && !recorded) {
            withContext(Dispatchers.Main) {
                ManualRecordingControlOverlay.showTransientStatus("录制失败", 1000L)
            }
        }

        sessionStillActive = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                val active = HumanTrajectoryLearningSession.isActive() &&
                    !HumanTrajectoryLearningSession.isPaused()
                if (active) {
                    lockTouchLocked()
                    if (gesture.actionName == "click" && executed) {
                        scheduleImeVisibilityProbeLocked()
                    }
                }
                active
            }
        }
        if (!sessionStillActive) return false
        return true
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

    private fun scheduleImeVisibilityProbeLocked() {
        if (imeBypassJob?.isActive == true || imeVisibilityProbeJob?.isActive == true) return
        imeVisibilityProbeJob = recordScope.launch {
            delay(IME_VISIBILITY_PROBE_DELAY_MS)
            val deadline = System.currentTimeMillis() + IME_VISIBILITY_PROBE_TIMEOUT_MS
            var appeared = false
            while (System.currentTimeMillis() <= deadline &&
                HumanTrajectoryLearningSession.isActive() &&
                !HumanTrajectoryLearningSession.isPaused()
            ) {
                appeared = withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        isImeVisibleLocked()
                    }
                }
                if (appeared) break
                delay(IME_VISIBILITY_PROBE_POLL_MS)
            }

            var shouldShowInputStatus = false
            withContext(Dispatchers.Main) {
                synchronized(this@ManualTouchRecordLoader) {
                    imeVisibilityProbeJob = null
                    if (appeared &&
                        HumanTrajectoryLearningSession.isActive() &&
                        !HumanTrajectoryLearningSession.isPaused()) {
                        lockTouchLocked()
                        shouldShowInputStatus = true
                        scheduleImeRelockLocked()
                    }
                }
            }
            if (shouldShowInputStatus) {
                withContext(Dispatchers.Main) {
                    ManualRecordingControlOverlay.showTransientStatus("输入中", 1400L)
                }
            }
        }
    }

    private fun scheduleImeRelockLocked() {
        if (imeBypassJob?.isActive == true) return
        imeBypassJob = recordScope.launch {
            delay(IME_RELOCK_INITIAL_DELAY_MS)
            while (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
                val imeVisible = withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        val visible = isImeVisibleLocked()
                        if (visible) {
                            lockTouchLocked()
                        }
                        visible
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
                    ManualRecordingControlOverlay.showTransientStatus("继续录制", 700L)
                }
            }
        }
    }

    private fun isImeVisibleLocked(): Boolean = imeTopLocked() != null

    private fun overlayHeightForParamsLocked(touchable: Boolean, fullHeight: Int): Int {
        if (!touchable) return fullHeight
        return imeTopLocked()?.coerceIn(1, fullHeight) ?: fullHeight
    }

    private fun imeTopLocked(): Int? {
        val displayHeight = currentDisplaySize().y.takeIf { it > 0 } ?: return null
        // Primary: window insets — works for TYPE_APPLICATION_OVERLAY.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = overlayView?.rootWindowInsets
            if (insets?.isVisible(WindowInsets.Type.ime()) == true) {
                val bottomInset = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (bottomInset > 0) {
                    return (displayHeight - bottomInset).coerceIn(1, displayHeight)
                }
            }
        }
        // Fallback: scan accessibility windows when overlay insets are unavailable.
        return AssistsService.instance?.windows
            ?.asSequence()
            ?.filter { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.mapNotNull { window ->
                val bounds = Rect()
                runCatching { window.getBoundsInScreen(bounds) }.getOrNull()
                bounds.takeUnless { it.isEmpty }
            }
            ?.map { bounds -> bounds.top.coerceIn(1, displayHeight) }
            ?.minOrNull()
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
        val params = buildParams(context, touchable)
        if (currentTouchable == touchable && currentOverlayHeight == params.height) return
        overlayParams = params
        runCatching { manager.updateViewLayout(view, params) }
            .onSuccess {
                currentTouchable = touchable
                currentOverlayHeight = params.height
            }
            .onFailure { OmniLog.w(TAG, "update touchable=$touchable failed: ${it.message}") }
    }

    private fun clampRawPoint(event: MotionEvent): PointF {
        val displaySize = currentDisplaySize()
        val maxX = (displaySize.x - 1).coerceAtLeast(0).toFloat()
        val maxY = (displaySize.y - 1).coerceAtLeast(0).toFloat()
        return PointF(
            event.rawX.coerceIn(0f, maxX),
            event.rawY.coerceIn(0f, maxY)
        )
    }

    private fun currentDisplaySize(): Point {
        val width = displayWidth
        val height = displayHeight
        if (width > 0 && height > 0) return Point(width, height)
        return realDisplaySize(overlayView?.context ?: UIKit.appContext)
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
