package cn.com.omnimind.uikit.loader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.dpToPx
import cn.com.omnimind.uikit.UIKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ManualRecordingControlOverlay {
    private const val TAG = "ManualRecordingControlOverlay"

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var state: State = State.READY
    private val recordingControlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastOverlayX: Int? = null
    private var lastOverlayY: Int? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false
    private var transientStatusToken = 0
    private var captureStateCallback: (suspend () -> Map<String, Any?>)? = null
    private var topEnsuredForCurrentAttachment: Boolean = false

    enum class State {
        PREPARING,
        READY,
        RECORDING,
        PAUSED
    }

    fun show(
        context: Context? = UIKit.appContext,
        state: State = State.READY,
        onCaptureState: (suspend () -> Map<String, Any?>)? = null
    ): Boolean {
        this.state = state
        val appContext = context?.applicationContext ?: UIKit.appContext
        val safeContext = appContext ?: return false
        return synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                captureStateCallback = onCaptureState
                bindState(overlayView, state)
                return@synchronized true
            }
            dismissLocked()
            captureStateCallback = onCaptureState
            tryShow(safeContext, state)
        }
    }

    /**
     * Shows the overlay in active recording state.
     */
    fun markRecording() {
        val context = synchronized(this) {
            state = State.RECORDING
            bindState(overlayView, State.RECORDING)
            overlayView?.context
        }
        val shown = ManualTouchRecordLoader.show(context ?: UIKit.appContext)
        if (shown) {
            keepControlsAboveTouchRecorderOnce()
        } else {
            recordingControlScope.launch {
                HumanTrajectoryLearningSession.pauseActive()
                withContext(Dispatchers.Main) {
                    markPaused()
                    showTransientStatus("开启悬浮窗权限", 1400L)
                }
            }
        }
    }

    /**
     * Shows the overlay in ready state before event capture starts.
     */
    fun markReady() {
        synchronized(this) {
            state = State.READY
            bindState(overlayView, State.READY)
        }
        ManualTouchRecordLoader.hide()
    }

    /**
     * Shows the overlay in paused state.
     */
    fun markPaused() {
        synchronized(this) {
            state = State.PAUSED
            bindState(overlayView, State.PAUSED)
        }
        ManualTouchRecordLoader.hide()
    }

    fun dismiss() {
        synchronized(this) {
            dismissLocked()
        }
        // Cancel any active session that was never explicitly completed.
        // This covers force-dismissal paths (back press, system overlay kill, etc.)
        // where the Finish button was never tapped.
        if (HumanTrajectoryLearningSession.isActive()) {
            recordingControlScope.launch {
                HumanTrajectoryLearningSession.cancelActive("录制窗口关闭，轨迹学习已取消")
            }
        }
    }

    private fun hideTemporarily() {
        synchronized(this) {
            dismissLocked()
        }
    }

    private fun dismissLocked() {
        ManualTouchRecordLoader.hide()
        val view = overlayView
        val manager = windowManager
        val params = overlayParams
        if (params != null) {
            lastOverlayX = params.x
            lastOverlayY = params.y
        }
        overlayView = null
        windowManager = null
        overlayParams = null
        captureStateCallback = null
        topEnsuredForCurrentAttachment = false
        if (view != null && manager != null && view.isAttachedToWindow) {
            runCatching { manager.removeView(view) }
                .onFailure { OmniLog.w(TAG, "dismiss failed: ${it.message}") }
        }
    }

    fun showTransientStatus(message: String, durationMs: Long = 800L) {
        val token = synchronized(this) {
            transientStatusToken += 1
            transientStatusToken
        }
        recordingControlScope.launch {
            withContext(Dispatchers.Main) {
                setTitleText(message)
            }
            delay(durationMs)
            withContext(Dispatchers.Main) {
                synchronized(this@ManualRecordingControlOverlay) {
                    if (transientStatusToken == token) {
                        bindState(overlayView, state)
                    }
                }
            }
        }
    }

    private fun tryShow(
        context: Context,
        state: State
    ): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildView(context)
        bindState(view, state)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = android.graphics.PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = lastOverlayX ?: ((screenWidth - 140.dpToPx()) / 2).coerceAtLeast(8.dpToPx())
            y = lastOverlayY ?: 56.dpToPx()
        }
        attachDragHandler(view, manager, params)
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            topEnsuredForCurrentAttachment = false
            OmniLog.d(
                TAG,
                "manual recording control overlay shown type=application state=$state"
            )
            true
        }.getOrElse { error ->
            OmniLog.e(
                TAG,
                "show failed type=application: ${error.message}",
                error
            )
            false
        }
    }

    private fun keepControlsAboveTouchRecorderOnce() {
        synchronized(this) {
            if (topEnsuredForCurrentAttachment) return
            val view = overlayView ?: return
            val manager = windowManager ?: return
            val params = overlayParams ?: return
            if (!view.isAttachedToWindow) return
            runCatching {
                manager.removeView(view)
                manager.addView(view, params)
                topEnsuredForCurrentAttachment = true
            }.onFailure { error ->
                OmniLog.w(TAG, "keep controls above touch recorder failed: ${error.message}")
            }
        }
    }

    private fun buildView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12.dpToPx().toFloat()
                setColor(Color.rgb(28, 30, 36))
                setStroke(1.dpToPx(), Color.argb(60, 255, 255, 255))
            }
            elevation = 6.dpToPx().toFloat()
        }
        val title = TextView(context).apply {
            tag = "manual_recording_title"
            text = "录制"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, 0, 2.dpToPx(), 0)
        }
        val pauseButton = TextView(context).apply {
            tag = "manual_recording_pause_action"
            text = "暂停"
            contentDescription = "暂停手动录制"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(58, 64, 78))
            }
            setOnClickListener {
                val shouldResume = when (ManualRecordingControlOverlay.state) {
                    State.PREPARING -> return@setOnClickListener
                    State.READY -> true
                    State.RECORDING -> false
                    State.PAUSED -> true
                }
                isEnabled = false
                recordingControlScope.launch {
                    val updated = if (shouldResume) {
                        HumanTrajectoryLearningSession.resumeActive()
                    } else {
                        HumanTrajectoryLearningSession.pauseActive()
                    }
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        if (!updated) {
                            return@withContext
                        }
                        if (shouldResume) {
                            markRecording()
                        } else {
                            markPaused()
                        }
                    }
                }
            }
        }
        val captureButton = TextView(context).apply {
            tag = "manual_recording_capture_action"
            text = "截图"
            contentDescription = "保存当前屏幕状态"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(58, 64, 78))
            }
            setOnClickListener {
                handleCaptureClick()
            }
        }
        val finishButton = TextView(context).apply {
            tag = "manual_recording_finish_action"
            text = "完成"
            contentDescription = "结束手动录制"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(31, 111, 235))
            }
            setOnClickListener {
                isEnabled = false
                val finishingState = ManualRecordingControlOverlay.state
                // Complete/cancel BEFORE dismiss so that dismiss() sees isActive()=false
                // and does not trigger a redundant cancelActive().
                recordingControlScope.launch {
                    val updated = if (finishingState == State.PREPARING) {
                        HumanTrajectoryLearningSession.cancelActive("人工轨迹学习已取消")
                    } else {
                        HumanTrajectoryLearningSession.completeActive()
                    }
                    withContext(Dispatchers.Main) { dismiss() }
                    if (!updated) {
                        OmniLog.w(TAG, "finish clicked without active manual recording session state=$finishingState")
                    }
                }
            }
        }
        container.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            pauseButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            captureButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            finishButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        return container
    }

    private fun handleCaptureClick() {
        val callback = synchronized(this) { captureStateCallback } ?: return
        val previousState = synchronized(this) { state }
        val context = overlayView?.context ?: UIKit.appContext ?: return
        // pauseActive() calls awaitOverlayRecordJobs() which blocks the calling thread.
        // Must run on the IO thread — calling it on the main thread deadlocks against
        // the IO coroutine's replay callbacks that hop back to Main.
        recordingControlScope.launch {
            val wasPaused = HumanTrajectoryLearningSession.isPaused()
            val shouldResume = HumanTrajectoryLearningSession.isActive() &&
                !wasPaused &&
                HumanTrajectoryLearningSession.pauseActive()
            withContext(Dispatchers.Main) {
                if (shouldResume) markPaused()
                hideTemporarily()
            }
            val result = runCatching { callback() }
                .getOrElse { error ->
                    OmniLog.e(TAG, "manual recording state capture failed: ${error.message}", error)
                    linkedMapOf(
                        "success" to false,
                        "error_message" to error.message.orEmpty()
                    )
                }
            val success = result["success"] == true
            OmniLog.d(
                TAG,
                "manual recording state capture success=$success state=${result["state_artifact"]}"
            )
            val resumed = if (shouldResume) {
                HumanTrajectoryLearningSession.resumeActive()
            } else {
                false
            }
            if (HumanTrajectoryLearningSession.isActive()) {
                withContext(Dispatchers.Main) {
                    val restoredState = if (HumanTrajectoryLearningSession.isPaused()) {
                        if (previousState == State.READY) State.READY else State.PAUSED
                    } else {
                        State.RECORDING
                    }
                    show(context, restoredState, callback)
                }
            }
            if (!resumed && shouldResume) {
                OmniLog.w(TAG, "manual recording resume after state capture failed")
            }
        }
    }

    private fun attachDragHandler(
        view: View,
        manager: WindowManager,
        params: WindowManager.LayoutParams
    ) {
        val touchListener = View.OnTouchListener { target, event ->
            handleDragTouch(target, event, manager, params)
        }
        view.setOnTouchListener(touchListener)
        (view as? LinearLayout)?.let { container ->
            (0 until container.childCount)
                .map { container.getChildAt(it) }
                .firstOrNull { it.tag == "manual_recording_title" }
                ?.setOnTouchListener(touchListener)
        }
    }

    private fun handleDragTouch(
        target: View,
        event: MotionEvent,
        manager: WindowManager,
        params: WindowManager.LayoutParams
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragging && (abs(dx) > 6.dpToPx() || abs(dy) > 6.dpToPx())) {
                    dragging = true
                    beginDragRecordingSuppression()
                }
                if (dragging) {
                    val layoutView = overlayView ?: target
                    val display = target.context.resources.displayMetrics
                    val maxX = max(0, display.widthPixels - layoutView.width)
                    val maxY = max(0, display.heightPixels - layoutView.height)
                    params.x = min(max(0, dragStartX + dx.toInt()), maxX)
                    params.y = min(max(8.dpToPx(), dragStartY + dy.toInt()), maxY)
                    lastOverlayX = params.x
                    lastOverlayY = params.y
                    runCatching {
                        if (layoutView.isAttachedToWindow) {
                            manager.updateViewLayout(layoutView, params)
                        }
                    }.onFailure { OmniLog.w(TAG, "drag update failed: ${it.message}") }
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    endDragRecordingSuppression()
                }
                return true
            }
        }
        return true
    }

    private fun beginDragRecordingSuppression() {
        // Keep drag handling UI-only. Session state changes can wait for replay/XML
        // work, and doing that inside ACTION_MOVE can trigger an overlay Input ANR.
    }

    private fun endDragRecordingSuppression() {
    }

    private fun bindState(view: View?, state: State) {
        val container = view as? LinearLayout ?: return
        val title = findChildByTag(container, "manual_recording_title") as? TextView
        val button = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_finish_action" } as? TextView
        val pauseButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_pause_action" } as? TextView
        val captureButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_capture_action" } as? TextView
        title?.text = when (state) {
            State.PREPARING -> "准备"
            State.READY -> "待机"
            State.RECORDING -> "录制"
            State.PAUSED -> "暂停"
        }
        pauseButton?.apply {
            visibility = if (state == State.PREPARING) View.GONE else View.VISIBLE
            isEnabled = state != State.PREPARING
            text = when (state) {
                State.PREPARING -> "暂停"
                State.READY -> "开始"
                State.RECORDING -> "暂停"
                State.PAUSED -> "继续"
            }
            contentDescription = when (state) {
                State.PREPARING -> "暂停手动录制"
                State.READY -> "开始手动录制"
                State.RECORDING -> "暂停手动录制"
                State.PAUSED -> "继续手动录制"
            }
        }
        captureButton?.apply {
            visibility = if (state == State.PREPARING) View.GONE else View.VISIBLE
            isEnabled = state != State.PREPARING
            text = "截图"
            contentDescription = "保存当前屏幕状态"
        }
        button?.apply {
            isEnabled = true
            text = when (state) {
                State.PREPARING -> "取消"
                State.READY -> "完成"
                State.RECORDING -> "完成"
                State.PAUSED -> "完成"
            }
            contentDescription = when (state) {
                State.PREPARING -> "取消手动录制"
                State.READY -> "结束手动录制"
                State.RECORDING -> "结束手动录制"
                State.PAUSED -> "结束手动录制"
            }
        }
    }

    private fun setTitleText(message: String) {
        val container = overlayView as? LinearLayout ?: return
        val title = findChildByTag(container, "manual_recording_title") as? TextView ?: return
        title.text = message
    }

    private fun findChildByTag(container: LinearLayout, tag: String): View? {
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == tag }
    }
}
