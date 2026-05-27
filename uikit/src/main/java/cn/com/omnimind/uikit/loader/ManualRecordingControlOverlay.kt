package cn.com.omnimind.uikit.loader

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.dpToPx
import cn.com.omnimind.uikit.UIKit

object ManualRecordingControlOverlay {
    private const val TAG = "ManualRecordingControlOverlay"

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var state: State = State.RECORDING

    enum class State {
        PREPARING,
        RECORDING
    }

    fun show(
        context: Context? = UIKit.appContext,
        state: State = State.RECORDING
    ): Boolean {
        this.state = state
        val appContext = context?.applicationContext ?: UIKit.appContext
        val accessibilityContext = AssistsService.instance
        val safeContext = appContext ?: accessibilityContext ?: return false
        return synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                bindState(overlayView, state)
                return@synchronized true
            }
            dismissLocked()
            val candidates = buildList {
                accessibilityContext?.let { add(it to true) }
                appContext?.let { add(it to false) }
            }.distinctBy { it.first }
            for ((candidateContext, useAccessibilityOverlay) in candidates) {
                if (tryShow(candidateContext, useAccessibilityOverlay, state)) {
                    return@synchronized true
                }
            }
            false
        }
    }

    fun markRecording() {
        synchronized(this) {
            state = State.RECORDING
            bindState(overlayView, State.RECORDING)
        }
    }

    fun dismiss() {
        synchronized(this) {
            dismissLocked()
        }
    }

    private fun dismissLocked() {
        val view = overlayView
        val manager = windowManager
        overlayView = null
        windowManager = null
        if (view != null && manager != null && view.isAttachedToWindow) {
            runCatching { manager.removeView(view) }
                .onFailure { OmniLog.w(TAG, "dismiss failed: ${it.message}") }
        }
    }

    private fun tryShow(
        context: Context,
        useAccessibilityOverlay: Boolean,
        state: State
    ): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildView(context)
        bindState(view, state)
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (useAccessibilityOverlay && context is AccessibilityService) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 72.dpToPx()
        }
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            OmniLog.d(
                TAG,
                "manual recording control overlay shown type=${if (useAccessibilityOverlay) "accessibility" else "application"} state=$state"
            )
            true
        }.getOrElse { error ->
            OmniLog.e(
                TAG,
                "show failed type=${if (useAccessibilityOverlay) "accessibility" else "application"}: ${error.message}",
                error
            )
            false
        }
    }

    private fun buildView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18.dpToPx().toFloat()
                setColor(Color.rgb(28, 30, 36))
                setStroke(1.dpToPx(), Color.argb(60, 255, 255, 255))
            }
            elevation = 10.dpToPx().toFloat()
        }
        val title = TextView(context).apply {
            tag = "manual_recording_title"
            text = "手动录制中"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val button = TextView(context).apply {
            tag = "manual_recording_action"
            text = "结束录制"
            contentDescription = "结束手动录制"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14.dpToPx().toFloat()
                setColor(Color.rgb(31, 111, 235))
            }
            setOnClickListener {
                isEnabled = false
                if (ManualRecordingControlOverlay.state == State.PREPARING) {
                    text = "取消中..."
                    contentDescription = "正在取消手动录制"
                    if (!HumanTrajectoryLearningSession.cancelActive("人工轨迹学习已取消")) {
                        dismiss()
                    }
                    return@setOnClickListener
                }
                text = "整理中..."
                contentDescription = "正在结束手动录制"
                if (!HumanTrajectoryLearningSession.completeActive()) {
                    dismiss()
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
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 12.dpToPx()
            }
        )
        return container
    }

    private fun bindState(view: View?, state: State) {
        val container = view as? LinearLayout ?: return
        val title = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_title" } as? TextView
        val button = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_action" } as? TextView
        title?.text = when (state) {
            State.PREPARING -> "准备录制中"
            State.RECORDING -> "手动录制中"
        }
        button?.apply {
            isEnabled = true
            text = when (state) {
                State.PREPARING -> "取消"
                State.RECORDING -> "结束录制"
            }
            contentDescription = when (state) {
                State.PREPARING -> "取消手动录制"
                State.RECORDING -> "结束手动录制"
            }
        }
    }
}
