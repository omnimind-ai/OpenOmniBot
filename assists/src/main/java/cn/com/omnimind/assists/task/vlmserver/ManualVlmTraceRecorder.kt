package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.action.OmniGestureDispatchTimeoutException
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.assists.ManualOverlayGestureReplayResult
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ManualVlmTraceResult(
    val actions: List<ManualVlmRecordedAction>,
    val summary: String,
    val diagnostics: Map<String, Any?> = emptyMap()
) {
    val actionCount: Int get() = actions.size
}

data class ManualVlmRecordedAction(
    val actionName: String,
    val title: String,
    val params: Map<String, Any?>,
    val packageName: String?,
    val beforeXml: String?,
    val afterXml: String?,
    val beforeScreenshot: ManualVlmScreenshotRef? = null,
    val afterScreenshot: ManualVlmScreenshotRef? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val summary: String,
    val eventContext: Map<String, Any?> = emptyMap()
)

data class ManualVlmScreenshotRef(
    val path: String,
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val sha256: String,
    val capturedAtMs: Long,
    val captureStage: String
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "schema_version" to "oob.runlog.screenshot_ref.v1",
        "kind" to "screenshot",
        "path" to path,
        "relative_path" to relativePath,
        "screenshot_path" to path,
        "mime_type" to mimeType,
        "width" to width,
        "height" to height,
        "bytes" to bytes,
        "sha256" to sha256,
        "captured_at_ms" to capturedAtMs,
        "capture_stage" to captureStage,
        "storage" to "app_private_files"
    )
}

internal data class ManualVlmTraceSnapshot(
    val isStarted: Boolean,
    val isPaused: Boolean,
    val actionCount: Int,
    val latestActionSummary: String?,
    val pendingActionSummary: String?,
    val accessibilityEventCount: Int,
    val rawTouchEnabled: Boolean,
    val rawTouchAvailable: Boolean,
    val overlayTouchRecordedCount: Int,
    val recordingBackend: String
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "is_started" to isStarted,
        "is_paused" to isPaused,
        "action_count" to actionCount,
        "latest_action_summary" to latestActionSummary,
        "pending_action_summary" to pendingActionSummary,
        "accessibility_event_count" to accessibilityEventCount,
        "raw_touch_enabled" to rawTouchEnabled,
        "raw_touch_available" to rawTouchAvailable,
        "overlay_touch_recorded_count" to overlayTouchRecordedCount,
        "recording_backend" to recordingBackend
    ).filterValues { it != null }
}

internal object ManualRecordingDiagnostics {
    const val COMPLETE_OVERLAY_TOUCH = "complete_overlay_touch"
    const val INCOMPLETE_OVERLAY_TOUCH = "incomplete_overlay_touch"
    const val COMPLETE_RAW_TOUCH = "complete_raw_touch"
    const val MISSING_RAW_TOUCH = "missing_raw_touch"
    const val RAW_TOUCH_INTERRUPTED = "raw_touch_interrupted"

    @Deprecated("Manual recording requires concrete touch capture; use MISSING_RAW_TOUCH.")
    const val PARTIAL_SEMANTIC_ONLY = MISSING_RAW_TOUCH

    @Deprecated("Manual recording requires concrete touch capture; use RAW_TOUCH_INTERRUPTED.")
    const val PARTIAL_RAW_TOUCH_INTERRUPTED = RAW_TOUCH_INTERRUPTED

    fun completeness(rawTouchAvailable: Boolean, rawTouchActiveAtStop: Boolean?): String {
        return when {
            rawTouchAvailable && rawTouchActiveAtStop == true -> COMPLETE_RAW_TOUCH
            rawTouchAvailable -> RAW_TOUCH_INTERRUPTED
            else -> MISSING_RAW_TOUCH
        }
    }

    fun guaranteesNoMissingClicks(rawTouchAvailable: Boolean, rawTouchActiveAtStop: Boolean?): Boolean {
        return completeness(rawTouchAvailable, rawTouchActiveAtStop) == COMPLETE_RAW_TOUCH
    }

    fun warningMessage(completeness: String): String? = when (completeness) {
        COMPLETE_OVERLAY_TOUCH -> null
        INCOMPLETE_OVERLAY_TOUCH -> "overlay touch 录制未全部落盘，本次轨迹可能遗漏点击/滑动"
        COMPLETE_RAW_TOUCH -> null
        RAW_TOUCH_INTERRUPTED -> "raw touch 录制中断，本次轨迹可能遗漏点击/滑动"
        else -> "raw touch 不可用，且没有真实触摸动作锚点，本次轨迹可能遗漏点击/滑动"
    }

    fun guaranteesNoMissingClicks(diagnostics: Map<String, Any?>): Boolean {
        val manual = diagnostics["manual_recording"] as? Map<*, *> ?: return false
        return manual["guarantees_no_missing_clicks"] == true
    }

    fun warningMessage(diagnostics: Map<String, Any?>): String? {
        val manual = diagnostics["manual_recording"] as? Map<*, *> ?: return null
        return manual["warning_message"]?.toString()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Records user actions during VLM takeover from concrete touch streams.
 *
 * Accessibility is evidence only: it provides XML/window observations and text
 * content for an input that was anchored by a real overlay/raw touch. A11-only
 * click, long-click, focus, and scroll events are intentionally not replayable
 * actions because they can be incomplete.
 */
class ManualVlmTraceRecorder(
    private val context: Context,
    private val sessionLabel: String,
    private val enableRawTouch: Boolean = false
) {
    private val ownPackageName = context.packageName
    private val recordingLock = java.lang.Object()
    private val recordedActions = mutableListOf<ManualVlmRecordedAction>()
    private val rawGestureBeforeXml = mutableMapOf<Long, String?>()
    private val rawGestureBeforeScreenshot = mutableMapOf<Long, ManualVlmScreenshotRef?>()
    private var textInputAnchor: TextInputAnchor? = null
    private var pendingText: PendingTextAction? = null
    private var rawTouchRecorder: ManualRawTouchRecorder? = null
    private var rawTouchStatus: ManualRawTouchStatus? = null
    private var lastDiscreteSignature: String = ""
    private var lastDiscreteAtMs: Long = 0L
    private var lastXmlSnapshot: String? = null
    private var lastScreenshotSnapshot: ManualVlmScreenshotRef? = null
    private var screenshotSkippedCount: Int = 0
    private var accessibilityEventCount: Int = 0
    private var accessibilityIgnoredPackageCount: Int = 0
    private val accessibilityEventTypeCounts = linkedMapOf<String, Int>()
    private var rawGestureStartedCount: Int = 0
    private var rawGestureFinishedCount: Int = 0
    private var rawGestureRecordedCount: Int = 0
    private var rawGestureIgnoredControlCount: Int = 0
    private var rawGeteventLineCount: Int = 0
    private var rawGeteventDroppedLineCount: Int = 0
    private val rawGeteventRecentLines = ArrayDeque<Map<String, Any?>>()
    private var rawTouchActiveAtStop: Boolean? = null
    private var overlayGestureStartedCount: Int = 0
    private var overlayGestureRecordedCount: Int = 0
    private var overlayGestureIgnoredControlCount: Int = 0
    private var overlayGestureFailedCount: Int = 0
    private var overlayCoordinateReplayCount: Int = 0
    private var overlayCoordinateReplayFailedCount: Int = 0
    private var overlayNodeReplayFallbackCount: Int = 0
    private var overlayNodeReplayFallbackFailedCount: Int = 0
    private var overlayGestureActiveCount: Int = 0
    private var overlayPostRecordTimeoutCount: Int = 0
    private var suppressedSemanticActionEventCount: Int = 0
    private var suppressedNonRawActionCount: Int = 0
    private var windowTransitionEventCount: Int = 0
    private var isStarted = false
    private var isPaused = false

    private val listener = object : AssistsServiceListener {
        override fun onAccessibilityEvent(event: AccessibilityEvent) {
            handleAccessibilityEvent(event)
        }
    }

    fun start(): Boolean {
        if (isStarted) return true
        if (!AssistsService.isInit()) {
            OmniLog.w(TAG, "manual trace recorder skipped: accessibility service is not ready")
            return false
        }
        if (!AccessibilityController.initController()) {
            OmniLog.w(TAG, "manual trace recorder skipped: accessibility controller is not ready")
            return false
        }
        isStarted = true
        isPaused = false
        lastXmlSnapshot = captureCurrentXml()
        lastScreenshotSnapshot = captureCurrentScreenshotRef("start")
        if (enableRawTouch) {
            startRawTouchRecorder()
        } else {
            rawTouchStatus = ManualRawTouchStatus(
                available = false,
                backend = RAW_TOUCH_BACKEND,
                errorCode = "raw_touch_disabled",
                errorMessage = "Raw getevent recording is disabled; using overlay touch actions with Accessibility evidence"
            )
        }
        AssistsService.addListener(listener)
        OmniLog.d(TAG, "manual trace recorder started: $sessionLabel rawTouch=$enableRawTouch")
        return true
    }

    /**
     * Pauses event collection while keeping the learning session alive.
     *
     * @return True when the recorder is active and is now paused.
     */
    fun pause(): Boolean {
        if (!isStarted) return false
        if (isPaused) return true
        awaitOverlayRecordJobs("pause")
        val currentXml = captureCurrentXml()
        val currentScreenshot = captureCurrentScreenshotRef("pause")
        flushPendingText(currentXml)
        lastXmlSnapshot = currentXml
        lastScreenshotSnapshot = currentScreenshot
        isPaused = true
        rawTouchRecorder?.pause()
        OmniLog.d(TAG, "manual trace recorder paused: $sessionLabel")
        return true
    }

    /**
     * Resumes event collection and refreshes the current screen baseline.
     *
     * @return True when the recorder is active and is now recording.
     */
    fun resume(): Boolean {
        if (!isStarted) return false
        if (!isPaused) return true
        lastXmlSnapshot = captureCurrentXml()
        lastScreenshotSnapshot = captureCurrentScreenshotRef("resume")
        lastDiscreteSignature = ""
        lastDiscreteAtMs = 0L
        isPaused = false
        rawTouchRecorder?.resume()
        OmniLog.d(TAG, "manual trace recorder resumed: $sessionLabel")
        return true
    }

    fun stop(): ManualVlmTraceResult {
        awaitOverlayRecordJobs("stop")
        if (isStarted) {
            AssistsService.removeListener(listener)
            isStarted = false
        }
        stopRawTouchRecorder()
        isPaused = false
        flushPendingText(lastXmlSnapshot)
        val summary = buildSummary(recordedActions)
        OmniLog.d(TAG, "manual trace recorder stopped: $sessionLabel actions=${recordedActions.size}")
        return ManualVlmTraceResult(
            actions = recordedActions.toList(),
            summary = summary,
            diagnostics = buildDiagnostics()
        )
    }

    internal fun snapshot(): ManualVlmTraceSnapshot = synchronized(recordingLock) {
        val pendingSummary = when {
            pendingText != null -> "正在输入：${pendingText?.label.orEmpty()}"
            else -> null
        }
        ManualVlmTraceSnapshot(
            isStarted = isStarted,
            isPaused = isPaused,
            actionCount = recordedActions.size,
            latestActionSummary = pendingSummary ?: recordedActions.lastOrNull()?.summary,
            pendingActionSummary = pendingSummary,
            accessibilityEventCount = accessibilityEventCount,
            rawTouchEnabled = enableRawTouch,
            rawTouchAvailable = rawTouchStatus?.available == true,
            overlayTouchRecordedCount = overlayGestureRecordedCount,
            recordingBackend = recordingBackendForStatus()
        )
    }

    suspend fun recordOverlayGesture(
        gesture: ManualOverlayTouchGesture,
        onGestureDispatched: suspend (mayOpenIme: Boolean) -> Unit = {}
    ): ManualOverlayGestureReplayResult {
        val operationId = synchronized(recordingLock) {
            if (!isStarted || isPaused) return ManualOverlayGestureReplayResult(executed = false)
            overlayGestureStartedCount += 1
            overlayOperationId(gesture, overlayGestureStartedCount)
        }
        // captureCurrentXml() is a blocking binder call (service.windows + window.root).
        // It has no internal timeout and can hang indefinitely during UI transitions.
        // Run on a separate IO thread so the timeout can free the processing coroutine
        // even if the underlying binder call is still waiting.
        val beforeXml = withTimeoutOrNull(BEFORE_XML_CAPTURE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { captureCurrentXml() }
        }?.takeIf { it.isNotBlank() }
        val beforeScreenshot = synchronized(recordingLock) { lastScreenshotSnapshot }
        val mayOpenIme = gesture.actionName == "click" &&
            overlayClickMayOpenIme(beforeXml, gesture.startX, gesture.startY)
        val touchX = if (gesture.actionName == "swipe") {
            (gesture.startX + gesture.endX) / 2f
        } else {
            gesture.startX
        }
        val touchY = if (gesture.actionName == "swipe") {
            (gesture.startY + gesture.endY) / 2f
        } else {
            gesture.startY
        }
        if (coordinateHitsIgnoredTarget(beforeXml, touchX, touchY)) {
            synchronized(recordingLock) {
                overlayGestureIgnoredControlCount += 1
            }
            OmniLog.d(TAG, "manual overlay touch ignored OOB/control gesture")
            return ManualOverlayGestureReplayResult(
                executed = false,
                mayOpenIme = false,
                ignoredControl = true
            )
        }

        synchronized(recordingLock) {
            overlayGestureActiveCount += 1
        }
        val dispatchOutcome = try {
            runCatching { performOverlayGesture(gesture) }
                .fold(
                    onSuccess = { OverlayDispatchOutcome.completed() },
                    onFailure = { error ->
                        synchronized(recordingLock) { overlayGestureFailedCount += 1 }
                        val outcome = OverlayDispatchOutcome.fromError(error)
                        OmniLog.w(
                            TAG,
                            "manual overlay touch dispatch ${outcome.status}: ${outcome.errorMessage}"
                        )
                        outcome
                    }
                )
        } finally {
            // Normal gestures are before-only. Re-lock as soon as dispatch completes
            // or times out. UIKit probes IME visibility asynchronously after clicks
            // so XML-missing screens do not block typing.
            try {
                onGestureDispatched(mayOpenIme)
            } catch (error: Exception) {
                OmniLog.w(TAG, "manual overlay dispatch callback failed: ${error.message}")
            }
        }

        val recorded = synchronized(recordingLock) {
            try {
                if (!isStarted || isPaused) return@synchronized false
                val currentTextAnchorId = if (gesture.actionName == "click") {
                    overlayTextAnchorId(gesture)
                } else {
                    null
                }
                if (gesture.actionName == "click") {
                    rememberTextInputAnchorFromRealTouch(
                        beforeXml = beforeXml,
                        beforeScreenshot = beforeScreenshot,
                        x = gesture.startX,
                        y = gesture.startY,
                        backend = OVERLAY_TOUCH_BACKEND,
                        anchorId = currentTextAnchorId.orEmpty(),
                        startedAtMs = gesture.startedAtMs,
                        finishedAtMs = gesture.finishedAtMs
                    )
                } else {
                    clearTextInputAnchor()
                }
                flushPendingTextUnlessAnchoredTo(currentTextAnchorId, beforeXml)
                when (gesture.actionName) {
                    "click", "long_press" -> appendOverlayClickGesture(
                        gesture = gesture,
                        beforeXml = beforeXml,
                        beforeScreenshot = beforeScreenshot,
                        operationId = operationId,
                        dispatchOutcome = dispatchOutcome
                    )
                    "swipe" -> appendOverlaySwipeGesture(
                        gesture = gesture,
                        beforeXml = beforeXml,
                        beforeScreenshot = beforeScreenshot,
                        operationId = operationId,
                        dispatchOutcome = dispatchOutcome
                    )
                    else -> {
                        overlayGestureFailedCount += 1
                        OmniLog.w(TAG, "manual overlay touch ignored unknown action=${gesture.actionName}")
                        return@synchronized false
                    }
                }
                overlayGestureRecordedCount += 1
                lastXmlSnapshot = beforeXml
                lastScreenshotSnapshot = beforeScreenshot ?: lastScreenshotSnapshot
                true
            } finally {
                decrementOverlayGestureActiveLocked()
            }
        }
        return ManualOverlayGestureReplayResult(
            executed = dispatchOutcome.executed,
            recorded = recorded,
            mayOpenIme = mayOpenIme
        )
    }

    private fun overlayOperationId(
        gesture: ManualOverlayTouchGesture,
        sequence: Int
    ): String = "overlay_${gesture.startedAtMs}_$sequence"

    private data class OverlayDispatchOutcome(
        val status: String,
        val executed: Boolean,
        val errorCode: String? = null,
        val errorMessage: String? = null
    ) {
        companion object {
            fun completed(): OverlayDispatchOutcome = OverlayDispatchOutcome(
                status = DISPATCH_STATUS_COMPLETED,
                executed = true
            )

            fun fromError(error: Throwable): OverlayDispatchOutcome {
                val timeout = error is TimeoutCancellationException ||
                    error is OmniGestureDispatchTimeoutException ||
                    error.message.orEmpty().contains("dispatch_timeout", ignoreCase = true) ||
                    error.message.orEmpty().contains("Timed out", ignoreCase = true)
                val cancelled = error.message.orEmpty().contains("cancel", ignoreCase = true)
                val status = when {
                    timeout -> DISPATCH_STATUS_TIMEOUT
                    cancelled -> DISPATCH_STATUS_CANCELLED
                    else -> DISPATCH_STATUS_FAILED
                }
                val code = when (status) {
                    DISPATCH_STATUS_TIMEOUT -> "dispatch_timeout"
                    DISPATCH_STATUS_CANCELLED -> "dispatch_cancelled"
                    else -> "dispatch_failed"
                }
                return OverlayDispatchOutcome(
                    status = status,
                    executed = false,
                    errorCode = code,
                    errorMessage = error.message?.take(MAX_ERROR_MESSAGE_LENGTH)
                        ?: error::class.java.simpleName
                )
            }
        }
    }

    private fun overlayDispatchDiagnostics(
        operationId: String,
        beforeXml: String?,
        dispatchOutcome: OverlayDispatchOutcome
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "operation_id" to operationId,
        "dispatch_status" to dispatchOutcome.status,
        "before_xml_present" to !beforeXml.isNullOrBlank(),
        "error_code" to dispatchOutcome.errorCode,
        "error_message" to dispatchOutcome.errorMessage
    ).filterValues { it != null }

    private fun awaitOverlayRecordJobs(reason: String = "manual_recording") {
        val deadlineMs = SystemClock.uptimeMillis() + OVERLAY_RECORD_DRAIN_TIMEOUT_MS
        synchronized(recordingLock) {
            while (overlayGestureActiveCount > 0) {
                val remainingMs = deadlineMs - SystemClock.uptimeMillis()
                if (remainingMs <= 0L) {
                    overlayPostRecordTimeoutCount += 1
                    OmniLog.w(
                        TAG,
                        "manual overlay drain timeout reason=$reason active=$overlayGestureActiveCount"
                    )
                    overlayGestureActiveCount = 0
                    recordingLock.notifyAll()
                    return
                }
                try {
                    recordingLock.wait(min(OVERLAY_RECORD_DRAIN_POLL_MS, remainingMs))
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    overlayPostRecordTimeoutCount += 1
                    OmniLog.w(TAG, "manual overlay drain interrupted reason=$reason")
                    overlayGestureActiveCount = 0
                    recordingLock.notifyAll()
                    return
                }
            }
        }
    }

    private fun decrementOverlayGestureActiveLocked() {
        overlayGestureActiveCount = (overlayGestureActiveCount - 1).coerceAtLeast(0)
        if (overlayGestureActiveCount == 0) {
            recordingLock.notifyAll()
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        synchronized(recordingLock) {
            handleAccessibilityEventLocked(event)
        }
    }

    private fun handleAccessibilityEventLocked(event: AccessibilityEvent) {
        if (!isStarted) return
        if (isPaused) return
        val packageName = event.packageName?.toString()
        accessibilityEventCount += 1
        incrementCount(accessibilityEventTypeCounts, eventTypeName(event.eventType))
        if (shouldIgnorePackage(packageName)) {
            accessibilityIgnoredPackageCount += 1
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                recordTextChanged(event, packageName, lastXmlSnapshot, lastScreenshotSnapshot)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                windowTransitionEventCount += 1
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> suppressA11OnlyActionEvent(event)
            else -> Unit
        }
    }

    private fun suppressSemanticActionEvent(event: AccessibilityEvent) {
        suppressedSemanticActionEventCount += 1
    }

    private fun suppressA11OnlyActionEvent(event: AccessibilityEvent) {
        suppressedNonRawActionCount += 1
        suppressSemanticActionEvent(event)
    }

    private fun startRawTouchRecorder(): Boolean {
        val metrics = context.resources.displayMetrics
        val recorder = ManualRawTouchRecorder(
            context = context,
            displayWidth = metrics.widthPixels.coerceAtLeast(1),
            displayHeight = metrics.heightPixels.coerceAtLeast(1),
            onGestureStarted = ::rememberRawGestureStart,
            onGestureFinished = ::recordRawTouchGesture,
            onRawEventLine = ::recordRawGeteventLine
        )
        val status = recorder.start()
        rawTouchStatus = status
        if (status.available) {
            rawTouchRecorder = recorder
            OmniLog.d(TAG, "manual trace raw touch enabled: ${status.asMap()}")
            return true
        } else {
            rawTouchRecorder = null
            OmniLog.w(TAG, "manual trace raw touch unavailable: ${status.asMap()}")
            return false
        }
    }

    private fun stopRawTouchRecorder() {
        rawTouchActiveAtStop = rawTouchRecorder?.isActive()
        val status = rawTouchRecorder?.stop()
        if (status != null) {
            rawTouchStatus = status
        }
        rawTouchRecorder = null
        rawGestureBeforeXml.clear()
        rawGestureBeforeScreenshot.clear()
    }

    private fun rememberRawGestureStart(start: ManualRawTouchStart) {
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            rawGestureStartedCount += 1
            val beforeXml = lastXmlSnapshot ?: captureCurrentXml()
            val beforeScreenshot = lastScreenshotSnapshot
                ?: captureCurrentScreenshotRef("raw_${start.gestureId}_before")
            rawGestureBeforeXml[start.gestureId] = beforeXml
            rawGestureBeforeScreenshot[start.gestureId] = beforeScreenshot
            rememberTextInputAnchorFromRealTouch(
                beforeXml = beforeXml,
                beforeScreenshot = beforeScreenshot,
                x = start.x,
                y = start.y,
                backend = RAW_TOUCH_BACKEND,
                anchorId = rawTextAnchorId(start.gestureId),
                startedAtMs = start.startedAtMs,
                finishedAtMs = start.startedAtMs
            )
        }
    }

    private fun recordRawGeteventLine(eventLine: ManualRawTouchEventLine) {
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            rawGeteventLineCount += 1
            if (rawGeteventRecentLines.size >= MAX_RAW_GETEVENT_RECENT_LINES) {
                rawGeteventRecentLines.removeFirst()
                rawGeteventDroppedLineCount += 1
            }
            rawGeteventRecentLines.addLast(eventLine.asMap())
        }
    }

    private fun recordRawTouchGesture(gesture: ManualRawTouchGesture) {
        if (!isStarted || isPaused) return
        synchronized(recordingLock) {
            rawGestureFinishedCount += 1
        }
        val beforeXml = synchronized(recordingLock) {
            rawGestureBeforeXml.remove(gesture.gestureId) ?: lastXmlSnapshot ?: captureCurrentXml()
        }
        val beforeScreenshot = synchronized(recordingLock) {
            rawGestureBeforeScreenshot.remove(gesture.gestureId)
                ?: lastScreenshotSnapshot
                ?: captureCurrentScreenshotRef("raw_${gesture.gestureId}_before")
        }
        val touchX = if (gesture.actionName == "swipe") gesture.startX else (gesture.startX + gesture.endX) / 2f
        val touchY = if (gesture.actionName == "swipe") gesture.startY else (gesture.startY + gesture.endY) / 2f
        if (coordinateHitsIgnoredTarget(beforeXml, touchX, touchY)) {
            synchronized(recordingLock) {
                rawGestureIgnoredControlCount += 1
            }
            OmniLog.d(TAG, "manual raw touch ignored OOB/control gesture")
            return
        }
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            val currentTextAnchorId = if (gesture.actionName == "click") {
                rawTextAnchorId(gesture.gestureId)
            } else {
                null
            }
            if (gesture.actionName != "click") {
                clearTextInputAnchor()
            }
            flushPendingTextUnlessAnchoredTo(currentTextAnchorId, beforeXml)
            when (gesture.actionName) {
                "click", "long_press" -> appendRawClickGesture(gesture, beforeXml, beforeScreenshot)
                "swipe" -> appendRawSwipeGesture(gesture, beforeXml, beforeScreenshot)
            }
            rawGestureRecordedCount += 1
            lastXmlSnapshot = beforeXml ?: lastXmlSnapshot
            lastScreenshotSnapshot = beforeScreenshot ?: lastScreenshotSnapshot
        }
    }

    private fun appendRawClickGesture(
        gesture: ManualRawTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        val x = ((gesture.startX + gesture.endX) / 2f)
        val y = ((gesture.startY + gesture.endY) / 2f)
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = x,
            y = y,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = false
        ) ?: coordinateOnlyTarget(beforeXml, x, y, "raw_touch_coordinate_only")
        val label = target.label.ifBlank { "屏幕坐标 ${x.toInt()},${y.toInt()}" }
        val recordedActionName = gesture.actionName
        val title = when (recordedActionName) {
            "long_press" -> "人工长按 $label"
            else -> "人工点击 $label"
        }
        val params = linkedMapOf<String, Any?>(
            "target_description" to label,
            "x" to x,
            "y" to y,
            "raw_x" to ((gesture.rawStartX + gesture.rawEndX) / 2),
            "raw_y" to ((gesture.rawStartY + gesture.rawEndY) / 2),
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "node_text" to target.text,
            "node_content_description" to target.contentDescription,
            "duration_ms" to gesture.durationMs.takeIf { recordedActionName == "long_press" },
            "gesture_duration_ms" to gesture.durationMs,
            "gesture_distance_px" to gesture.distancePx,
            "gesture_point_count" to gesture.pointCount,
            "recording_backend" to gesture.backend,
            "target_resolution" to target.resolution
        ).filterValues { it != null }
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = recordedActionName,
                title = title,
                params = params,
                packageName = target.packageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = rawEventContextFor(gesture, target)
            )
        )
    }

    private fun appendRawSwipeGesture(
        gesture: ManualRawTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        val midX = (gesture.startX + gesture.endX) / 2f
        val midY = (gesture.startY + gesture.endY) / 2f
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = midX,
            y = midY,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = true
        ) ?: coordinateOnlyTarget(beforeXml, midX, midY, "raw_touch_coordinate_only")
        val direction = rawSwipeDirection(gesture)
        val label = target.label.ifBlank { "当前页面" }
        val title = "人工滑动 $label"
        val params = linkedMapOf<String, Any?>(
            "target_description" to label,
            "x1" to gesture.startX,
            "y1" to gesture.startY,
            "x2" to gesture.endX,
            "y2" to gesture.endY,
            "raw_x1" to gesture.rawStartX,
            "raw_y1" to gesture.rawStartY,
            "raw_x2" to gesture.rawEndX,
            "raw_y2" to gesture.rawEndY,
            "duration_ms" to gesture.durationMs.coerceAtLeast(120L),
            "gesture_distance_px" to gesture.distancePx,
            "gesture_point_count" to gesture.pointCount,
            "direction" to direction,
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "recording_backend" to gesture.backend,
            "target_resolution" to target.resolution
        ).filterValues { it != null }
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = "swipe",
                title = title,
                params = params,
                packageName = target.packageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = rawEventContextFor(gesture, target)
            )
        )
    }

    private suspend fun performOverlayGesture(gesture: ManualOverlayTouchGesture) {
        when (gesture.actionName) {
            "click" -> performOverlayClickGesture(gesture)
            "long_press" -> AccessibilityController.longClickCoordinate(
                gesture.startX,
                gesture.startY,
                gesture.durationMs.coerceAtLeast(OVERLAY_LONG_PRESS_MIN_DURATION_MS)
            )
            "swipe" -> {
                val direction = overlaySwipeDirection(gesture)
                AccessibilityController.scrollCoordinate(
                    x = gesture.startX,
                    y = gesture.startY,
                    direction = direction,
                    distance = gesture.distancePx.coerceAtLeast(OVERLAY_SWIPE_MIN_DISTANCE_PX),
                    duration = gesture.durationMs.coerceAtLeast(OVERLAY_SWIPE_MIN_DURATION_MS)
                )
            }
            else -> throw IllegalArgumentException("Unsupported overlay gesture: ${gesture.actionName}")
        }
    }

    private suspend fun performOverlayClickGesture(gesture: ManualOverlayTouchGesture) {
        val coordinateResult = runCatching {
            AccessibilityController.clickCoordinate(
                gesture.startX,
                gesture.startY,
                timeoutMs = OVERLAY_CLICK_REPLAY_TIMEOUT_MS
            )
        }
        if (coordinateResult.isSuccess) {
            synchronized(recordingLock) {
                overlayCoordinateReplayCount += 1
            }
            return
        }

        synchronized(recordingLock) {
            overlayCoordinateReplayFailedCount += 1
        }
        val coordinateError = coordinateResult.exceptionOrNull()
        OmniLog.w(TAG, "overlay click coordinate replay failed: ${coordinateError?.message}")
        throw coordinateError ?: IllegalStateException("Overlay click replay failed")
    }

    private fun appendOverlayClickGesture(
        gesture: ManualOverlayTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        operationId: String,
        dispatchOutcome: OverlayDispatchOutcome
    ) {
        val x = gesture.startX
        val y = gesture.startY
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = x,
            y = y,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = false
        )?.asOverlayTarget() ?: coordinateOnlyTarget(beforeXml, x, y, "overlay_touch_coordinate_only")
        val label = target.label.ifBlank { "屏幕坐标 ${x.toInt()},${y.toInt()}" }
        val recordedActionName = gesture.actionName
        val title = when (recordedActionName) {
            "long_press" -> "人工长按 $label"
            else -> "人工点击 $label"
        }
        val params = (linkedMapOf<String, Any?>(
            "target_description" to label,
            "x" to x,
            "y" to y,
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "node_text" to target.text,
            "node_content_description" to target.contentDescription,
            "duration_ms" to gesture.durationMs.takeIf { recordedActionName == "long_press" },
            "gesture_duration_ms" to gesture.durationMs,
            "gesture_distance_px" to gesture.distancePx,
            "recording_backend" to OVERLAY_TOUCH_BACKEND,
            "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
            "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
            "target_resolution" to target.resolution,
            "display_width" to gesture.displayWidth.takeIf { it > 0 },
            "display_height" to gesture.displayHeight.takeIf { it > 0 }
        ) + overlayDispatchDiagnostics(operationId, beforeXml, dispatchOutcome)).filterValues { it != null }
        // Package name from XML is null for SurfaceView/WebView apps (no accessible nodes).
        // Fall back to the accessibility service's current window package so that the
        // compiled Function step carries a valid src_ctx.package_name for the checker.
        val resolvedPackageName = target.packageName
            ?: AccessibilityController.getPackageName()
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = recordedActionName,
                title = title,
                params = params,
                packageName = resolvedPackageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = overlayEventContextFor(gesture, target, operationId, dispatchOutcome, beforeXml)
            )
        )
    }

    private fun appendOverlaySwipeGesture(
        gesture: ManualOverlayTouchGesture,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        operationId: String,
        dispatchOutcome: OverlayDispatchOutcome
    ) {
        val midX = (gesture.startX + gesture.endX) / 2f
        val midY = (gesture.startY + gesture.endY) / 2f
        val target = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = midX,
            y = midY,
            fallbackPackageName = packageNameFromXml(beforeXml),
            preferScrollable = true
        )?.asOverlayTarget() ?: coordinateOnlyTarget(beforeXml, midX, midY, "overlay_touch_coordinate_only")
        val direction = overlaySwipeDirectionName(gesture)
        val label = target.label.ifBlank { "当前页面" }
        val title = "人工滑动 $label"
        val params = (linkedMapOf<String, Any?>(
            "target_description" to label,
            "x1" to gesture.startX,
            "y1" to gesture.startY,
            "x2" to gesture.endX,
            "y2" to gesture.endY,
            "duration_ms" to gesture.durationMs.coerceAtLeast(OVERLAY_SWIPE_MIN_DURATION_MS),
            "gesture_duration_ms" to gesture.durationMs,
            "gesture_distance_px" to gesture.distancePx,
            "direction" to direction,
            "bounds" to boundsString(target.bounds),
            "node_class" to target.className,
            "node_resource_id" to target.resourceId,
            "recording_backend" to OVERLAY_TOUCH_BACKEND,
            "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
            "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
            "target_resolution" to target.resolution,
            "display_width" to gesture.displayWidth.takeIf { it > 0 },
            "display_height" to gesture.displayHeight.takeIf { it > 0 }
        ) + overlayDispatchDiagnostics(operationId, beforeXml, dispatchOutcome)).filterValues { it != null }
        val resolvedPackageName = target.packageName
            ?: AccessibilityController.getPackageName()
        appendRecordedAction(
            ManualVlmRecordedAction(
                actionName = "swipe",
                title = title,
                params = params,
                packageName = resolvedPackageName,
                beforeXml = beforeXml,
                afterXml = null,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = null,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = overlayEventContextFor(gesture, target, operationId, dispatchOutcome, beforeXml)
            )
        )
    }

    private fun recordTextChanged(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        val anchor = textInputAnchor
        if (anchor == null) {
            suppressA11OnlyActionEvent(event)
            return
        }
        val node = runCatching { event.source }.getOrNull()
        val now = System.currentTimeMillis()
        val text = event.text.joinToString("").ifBlank { node?.text?.toString().orEmpty() }
        val safeText = if (node?.isPassword == true) REDACTED_TEXT else text
        if (safeText.isBlank()) return
        val sourceTarget = node?.let { targetFromTextSourceNode(it, packageName) }
        if (anchor.isCoordinateOnlyTextAnchor() &&
            (sourceTarget == null ||
                (!sourceTarget.bounds.containsPoint(anchor.x, anchor.y) &&
                    !isTextInputClass(sourceTarget.className)))
        ) {
            suppressA11OnlyActionEvent(event)
            return
        }
        val target = textReplayTargetFromAnchor(
            anchor = anchor,
            sourceTarget = sourceTarget,
            inputText = safeText
        )
        val bounds = target.bounds
        val key = target.stableKey
        val existingPending = pendingText
        if (existingPending != null && existingPending.nodeKey != key) {
            flushPendingText(beforeXml ?: lastXmlSnapshot)
        }
        val sameNodePending = pendingText?.takeIf { it.nodeKey == key }
        val pendingBeforeXml = sameNodePending?.beforeXml ?: beforeXml
        val pendingBeforeScreenshot = sameNodePending?.beforeScreenshot ?: beforeScreenshot

        pendingText = PendingTextAction(
            nodeKey = key,
            anchorId = anchor.id,
            packageName = target.packageName ?: packageName,
            label = target.label.ifBlank { "输入框" },
            text = safeText,
            bounds = bounds,
            className = target.className,
            resourceId = target.resourceId,
            resolution = target.resolution,
            recordingBackend = textInputBackendFor(anchor.backend),
            beforeXml = sameNodePending?.beforeXml ?: anchor.beforeXml ?: pendingBeforeXml,
            beforeScreenshot = sameNodePending?.beforeScreenshot ?: anchor.beforeScreenshot ?: pendingBeforeScreenshot,
            startedAtMs = sameNodePending?.startedAtMs ?: anchor.startedAtMs,
            updatedAtMs = now,
            eventContext = textInputEventContextFor(event, target, node, anchor)
        )
        lastXmlSnapshot = beforeXml ?: lastXmlSnapshot
        lastScreenshotSnapshot = beforeScreenshot ?: lastScreenshotSnapshot
    }

    private fun textReplayTargetFromAnchor(
        anchor: TextInputAnchor,
        sourceTarget: ManualEventTarget?,
        inputText: String
    ): ManualEventTarget {
        val anchorTarget = anchor.target
        val beforeLabel = anchorTarget.label.trim()
        val stableLabel = beforeLabel.takeIf {
            it.isNotBlank() && it != inputText && it != REDACTED_TEXT
        } ?: sourceTarget?.label ?: anchorTarget.label
        return anchorTarget.copy(
            label = stableLabel,
            packageName = anchorTarget.packageName ?: sourceTarget?.packageName,
            resourceId = anchorTarget.resourceId ?: sourceTarget?.resourceId,
            text = anchorTarget.text ?: sourceTarget?.text,
            contentDescription = anchorTarget.contentDescription ?: sourceTarget?.contentDescription,
            stableKey = anchorTarget.stableKey.ifBlank { sourceTarget?.stableKey.orEmpty() },
            resolution = "${anchorTarget.resolution}+real_touch_text_anchor"
        )
    }

    private fun flushPendingTextUnlessAnchoredTo(anchorId: String?, xmlOverride: String? = lastXmlSnapshot) {
        val pending = pendingText ?: return
        if (anchorId != null && pending.anchorId == anchorId) return
        flushPendingText(xmlOverride)
    }

    private fun flushPendingText(xmlOverride: String? = lastXmlSnapshot) {
        val pending = pendingText ?: return
        pendingText = null
        val title = if (pending.text == REDACTED_TEXT) {
            "人工输入敏感文本"
        } else {
            "人工输入文本"
        }
        lastXmlSnapshot = xmlOverride ?: lastXmlSnapshot
        lastScreenshotSnapshot = pending.beforeScreenshot ?: lastScreenshotSnapshot
        val params = linkedMapOf<String, Any?>(
            "target_description" to pending.label,
            "content" to pending.text,
            "text" to pending.text,
            "x" to pending.bounds.centerX().toFloat(),
            "y" to pending.bounds.centerY().toFloat(),
            "bounds" to boundsString(pending.bounds),
            "node_class" to pending.className,
            "node_resource_id" to pending.resourceId,
            "recording_backend" to pending.recordingBackend,
            "target_resolution" to pending.resolution
        ).filterValues { it != null }
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = "input_text",
            title = title,
            params = params,
            packageName = pending.packageName,
            beforeXml = pending.beforeXml,
            afterXml = null,
            beforeScreenshot = pending.beforeScreenshot,
            afterScreenshot = null,
            startedAtMs = pending.startedAtMs,
            finishedAtMs = pending.updatedAtMs,
            summary = if (pending.text == REDACTED_TEXT) title else "$title：${pending.text.take(MAX_SUMMARY_TEXT)}",
            eventContext = pending.eventContext
        ))
    }

    private fun shouldIgnorePackage(packageName: String?): Boolean {
        val normalized = packageName?.trim().orEmpty()
        return normalized.isNotEmpty() && normalized == ownPackageName
    }

    private fun navigationActionFor(packageName: String?, label: String): String? {
        if (packageName != SYSTEM_UI_PACKAGE) return null
        val normalized = label.lowercase()
        return when {
            normalized == "back" ||
                normalized.contains("back") ||
                normalized.contains("返回") -> "press_back"
            normalized == "home" ||
                normalized.contains("home") ||
                normalized.contains("主页") ||
                normalized.contains("主屏幕") -> "press_home"
            else -> null
        }
    }

    private fun shouldIgnoreNode(node: AccessibilityNodeInfo): Boolean {
        val packageName = node.packageName?.toString()
        return shouldIgnoreTarget(
            packageName = packageName,
            label = node.bestLabel(),
            resourceId = node.viewIdResourceName
        )
    }

    private fun shouldIgnoreTarget(
        packageName: String?,
        label: String?,
        resourceId: String?
    ): Boolean {
        if (shouldIgnorePackage(packageName)) return true
        val text = listOfNotNull(label, resourceId).joinToString(" ").lowercase()
        return OOB_CONTROL_HINTS.any { text.contains(it) }
    }

    private fun AccessibilityNodeInfo.boundsInScreenOrNull(): Rect? {
        val rect = Rect()
        getBoundsInScreen(rect)
        return rect.takeUnless { it.isEmpty }
    }

    private fun AccessibilityNodeInfo.bestLabel(): String {
        return firstNonBlank(
            contentDescription?.toString(),
            text?.toString(),
            hintText?.toString(),
            viewIdResourceName?.substringAfterLast('/'),
            className?.toString()
        ).take(MAX_LABEL_LENGTH)
    }

    private fun AccessibilityNodeInfo.stableKey(bounds: Rect?): String {
        return firstNonBlank(viewIdResourceName, className?.toString()) + "|" +
            "${bounds?.left},${bounds?.top},${bounds?.right},${bounds?.bottom}"
    }

    private fun targetFromSourceNode(
        node: AccessibilityNodeInfo,
        fallbackPackageName: String?
    ): ManualEventTarget? {
        val targetNode = actionableSourceNode(node) ?: node
        if (shouldIgnoreNode(targetNode)) return null
        val bounds = targetNode.boundsInScreenOrNull() ?: return null
        if (bounds.isEmpty) return null
        val packageName = targetNode.packageName?.toString() ?: fallbackPackageName
        val resolution = if (targetNode === node) {
            "event_source"
        } else {
            "event_source_actionable_ancestor"
        }
        return ManualEventTarget(
            label = targetNode.bestLabel().ifBlank { node.bestLabel() },
            bounds = bounds,
            packageName = packageName,
            className = targetNode.className?.toString(),
            resourceId = targetNode.viewIdResourceName,
            text = targetNode.text?.toString() ?: node.text?.toString(),
            contentDescription = targetNode.contentDescription?.toString()
                ?: node.contentDescription?.toString(),
            stableKey = targetNode.stableKey(bounds),
            resolution = resolution
        )
    }

    private fun targetFromTextSourceNode(
        node: AccessibilityNodeInfo,
        fallbackPackageName: String?
    ): ManualEventTarget? {
        if (!shouldIgnoreNode(node)) {
            val bounds = node.boundsInScreenOrNull()
            if (bounds != null && !bounds.isEmpty) {
                val packageName = node.packageName?.toString() ?: fallbackPackageName
                return ManualEventTarget(
                    label = node.bestLabel(),
                    bounds = bounds,
                    packageName = packageName,
                    className = node.className?.toString(),
                    resourceId = node.viewIdResourceName,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    stableKey = node.stableKey(bounds),
                    resolution = "event_source_text"
                )
            }
        }
        return targetFromSourceNode(node, fallbackPackageName)
    }

    private fun actionableSourceNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ACTIONABLE_ANCESTOR_DEPTH + 1) {
            val candidate = current ?: return null
            val bounds = candidate.boundsInScreenOrNull()
            if (
                bounds != null &&
                !bounds.isEmpty &&
                candidate.isEnabled &&
                (candidate.isClickable || candidate.isLongClickable || candidate.isScrollable)
            ) {
                return candidate
            }
            current = candidate.parent
        }
        return null
    }

    private fun isTextInputClass(className: String?): Boolean {
        val normalized = className.orEmpty()
        return normalized.contains("EditText", ignoreCase = true) ||
            normalized.contains("TextInput", ignoreCase = true)
    }

    private fun targetAtCoordinateFromXml(
        xml: String?,
        x: Float,
        y: Float,
        fallbackPackageName: String?,
        preferScrollable: Boolean
    ): ManualEventTarget? {
        val nodes = parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.bounds.containsPoint(x, y) &&
                    !shouldIgnoreTarget(
                        packageName = candidate.packageName ?: fallbackPackageName,
                        label = candidate.bestLabel,
                        resourceId = candidate.resourceId
                    )
            }
        if (nodes.isEmpty()) return null
        val best = nodes.maxWithOrNull(
            compareBy<XmlNodeCandidate> { it.coordinateScore(preferScrollable) }
                .thenByDescending { it.bounds.width() * it.bounds.height() }
        ) ?: return null
        return best.toManualTarget(
            fallbackPackageName = fallbackPackageName,
            resolution = if (best.isActionableForCoordinate(preferScrollable)) {
                "raw_touch_coordinate_xml_grounded"
            } else {
                "raw_touch_coordinate_xml_container"
            }
        )
    }

    private fun coordinateOnlyTarget(
        xml: String?,
        x: Float,
        y: Float,
        resolution: String
    ): ManualEventTarget {
        val left = x.toInt().coerceAtLeast(0)
        val top = y.toInt().coerceAtLeast(0)
        return ManualEventTarget(
            label = "屏幕坐标",
            bounds = Rect(left, top, left + 1, top + 1),
            packageName = packageNameFromXml(xml),
            className = null,
            resourceId = null,
            text = null,
            contentDescription = null,
            stableKey = "raw_coordinate|$left,$top",
            resolution = resolution
        )
    }

    private fun rememberTextInputAnchorFromRealTouch(
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        x: Float,
        y: Float,
        backend: String,
        anchorId: String,
        startedAtMs: Long,
        finishedAtMs: Long
    ) {
        val fallbackPackageName = packageNameFromXml(beforeXml)
        val target = textInputTargetAtCoordinateFromXml(
            xml = beforeXml,
            x = x,
            y = y,
            fallbackPackageName = fallbackPackageName,
            backend = backend
        )
        val anchorTarget = target ?: coordinateTextAnchorTarget(
            beforeXml = beforeXml,
            x = x,
            y = y,
            backend = backend
        )
        val anchor = anchorTarget.let {
            TextInputAnchor(
                id = anchorId,
                backend = backend,
                beforeXml = beforeXml,
                beforeScreenshot = beforeScreenshot,
                target = it,
                x = x,
                y = y,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs
            )
        }
        synchronized(recordingLock) {
            textInputAnchor = anchor
        }
    }

    private fun clearTextInputAnchor() {
        synchronized(recordingLock) {
            textInputAnchor = null
        }
    }

    private fun textInputTargetAtCoordinateFromXml(
        xml: String?,
        x: Float,
        y: Float,
        fallbackPackageName: String?,
        backend: String
    ): ManualEventTarget? {
        val packageName = packageNameFromXml(xml) ?: fallbackPackageName
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        val candidates = parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.visible &&
                    candidate.enabled &&
                    candidate.bounds.containsPoint(x, y) &&
                    candidate.isEditableLike() &&
                    !(
                        shouldIgnoreTarget(
                            packageName = candidate.packageName ?: packageName,
                            label = candidate.bestLabel,
                            resourceId = candidate.resourceId
                        ) && candidate.isExplicitIgnoredControl(rootArea)
                    )
            }
        val best = candidates.maxWithOrNull(
            compareBy<XmlNodeCandidate> { it.coordinateScore(preferScrollable = false) }
                .thenByDescending { it.bounds.width() * it.bounds.height() }
        ) ?: return null
        val resolution = when (backend) {
            OVERLAY_TOUCH_BACKEND -> "overlay_touch_before_xml_text_target"
            RAW_TOUCH_BACKEND -> "raw_touch_before_xml_text_target"
            else -> "real_touch_before_xml_text_target"
        }
        return best.toManualTarget(packageName, resolution)
    }

    private fun textInputBackendFor(backend: String): String {
        return when (backend) {
            OVERLAY_TOUCH_BACKEND -> OVERLAY_TOUCH_TEXT_INPUT_BACKEND
            RAW_TOUCH_BACKEND -> RAW_TOUCH_TEXT_INPUT_BACKEND
            else -> REAL_TOUCH_TEXT_INPUT_BACKEND
        }
    }

    private fun coordinateTextAnchorTarget(
        beforeXml: String?,
        x: Float,
        y: Float,
        backend: String
    ): ManualEventTarget {
        val resolution = when (backend) {
            OVERLAY_TOUCH_BACKEND -> "overlay_touch_coordinate_text_anchor_unresolved"
            RAW_TOUCH_BACKEND -> "raw_touch_coordinate_text_anchor_unresolved"
            else -> "real_touch_coordinate_text_anchor_unresolved"
        }
        return coordinateOnlyTarget(beforeXml, x, y, resolution).copy(
            packageName = packageNameFromXml(beforeXml)
        )
    }

    private fun overlayTextAnchorId(gesture: ManualOverlayTouchGesture): String {
        return listOf(
            OVERLAY_TOUCH_BACKEND,
            gesture.startedAtMs,
            gesture.finishedAtMs,
            gesture.startX.toInt(),
            gesture.startY.toInt()
        ).joinToString("|")
    }

    private fun rawTextAnchorId(gestureId: Long): String = "$RAW_TOUCH_BACKEND|$gestureId"

    private fun coordinateHitsIgnoredTarget(xml: String?, x: Float, y: Float): Boolean {
        val packageName = packageNameFromXml(xml)
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        return parseXmlNodeCandidates(xml).any { candidate ->
            candidate.bounds.containsPoint(x, y) &&
                shouldIgnoreTarget(
                    packageName = candidate.packageName ?: packageName,
                    label = candidate.bestLabel,
                    resourceId = candidate.resourceId
                ) &&
                candidate.isExplicitIgnoredControl(rootArea)
        }
    }

    private fun overlayClickMayOpenIme(xml: String?, x: Float, y: Float): Boolean {
        if (xml.isNullOrBlank()) return false
        val packageName = packageNameFromXml(xml)
        val rootArea = parseRootBounds(xml)?.area() ?: Int.MAX_VALUE
        val candidates = parseXmlNodeCandidates(xml)
            .filter { candidate ->
                candidate.visible &&
                    candidate.enabled &&
                    candidate.bounds.containsPoint(x, y) &&
                    !(
                        shouldIgnoreTarget(
                            packageName = candidate.packageName ?: packageName,
                            label = candidate.bestLabel,
                            resourceId = candidate.resourceId
                        ) && candidate.isExplicitIgnoredControl(rootArea)
                    )
            }
        if (candidates.isEmpty()) return false
        return candidates.any { it.isEditableLike() }
    }

    private fun XmlNodeCandidate.isExplicitIgnoredControl(rootArea: Int): Boolean {
        val text = listOfNotNull(bestLabel, resourceId, className).joinToString(" ").lowercase()
        if (OOB_CONTROL_HINTS.any { text.contains(it) }) return true
        if (!visible || !enabled) return false
        if (!clickable && !focusable && !editable && !scrollable) return false
        val area = bounds.area()
        if (area <= 0) return false
        val maxControlArea = if (rootArea == Int.MAX_VALUE) {
            MAX_IGNORED_CONTROL_AREA_PX
        } else {
            (rootArea * MAX_IGNORED_CONTROL_AREA_RATIO).toInt()
                .coerceAtLeast(MAX_IGNORED_CONTROL_AREA_PX)
        }
        return area <= maxControlArea
    }

    private fun XmlNodeCandidate.isEditableLike(): Boolean {
        val text = listOfNotNull(className, resourceId, bestLabel)
            .joinToString(" ")
            .lowercase()
        return editable ||
            text.contains("edittext") ||
            text.contains("textinput") ||
            text.contains("editable")
    }

    private fun packageNameFromXml(xml: String?): String? =
        parseXmlNodeCandidates(xml).firstOrNull { !it.packageName.isNullOrBlank() }?.packageName

    private fun pageStableFingerprint(xml: String?): String {
        if (xml.isNullOrBlank()) return ""
        return parseXmlNodeCandidates(xml)
            .asSequence()
            .filter { it.visible }
            .take(MAX_PAGE_FINGERPRINT_NODES)
            .joinToString("\n") { candidate ->
                val stableLabel = if (candidate.editable) "" else candidate.bestLabel.take(MAX_PAGE_LABEL_LENGTH)
                listOf(
                    candidate.packageName.orEmpty(),
                    candidate.className.orEmpty(),
                    candidate.resourceId.orEmpty(),
                    stableLabel,
                    boundsString(candidate.bounds),
                    candidate.clickable,
                    candidate.scrollable,
                    candidate.focusable,
                    candidate.editable,
                    candidate.enabled
                ).joinToString("|")
            }
    }

    private fun pageSummary(xml: String?): Map<String, Any?> {
        val candidates = parseXmlNodeCandidates(xml)
        if (candidates.isEmpty()) return emptyMap()
        val labels = candidates
            .asSequence()
            .filter { it.visible }
            .map { it.bestLabel }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PAGE_SUMMARY_LABELS)
            .toList()
        return linkedMapOf<String, Any?>(
            "package_name" to packageNameFromXml(xml),
            "labels" to labels.takeIf { it.isNotEmpty() },
            "visible_node_count" to candidates.count { it.visible },
            "clickable_count" to candidates.count { it.visible && it.clickable },
            "editable_count" to candidates.count { it.visible && it.editable },
            "scrollable_count" to candidates.count { it.visible && it.scrollable }
        ).filterValues { it != null }
    }

    private fun fingerprintHash(value: String): String =
        Integer.toHexString(value.hashCode())

    private fun Rect.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    private fun Rect.area(): Int =
        width().coerceAtLeast(0) * height().coerceAtLeast(0)

    private fun XmlNodeCandidate.isActionableForCoordinate(preferScrollable: Boolean): Boolean =
        clickable || focusable || editable || (preferScrollable && scrollable)

    private fun XmlNodeCandidate.coordinateScore(preferScrollable: Boolean): Int {
        var score = 0
        if (visible) score += 20
        if (enabled) score += 20
        if (clickable) score += 80
        if (focusable) score += 40
        if (editable) score += 60
        if (preferScrollable && scrollable) score += 100
        val area = bounds.width().coerceAtLeast(1) * bounds.height().coerceAtLeast(1)
        score += (1_000_000 / area).coerceIn(0, 40)
        return score
    }

    private fun parseXmlNodeCandidates(xml: String?): List<XmlNodeCandidate> {
        if (xml.isNullOrBlank()) return emptyList()
        return NODE_TAG_REGEX.findAll(xml).mapNotNull { match ->
            val attrs = parseXmlAttributes(match.groupValues.getOrNull(1).orEmpty())
            val bounds = parseBounds(attrs["bounds"]) ?: return@mapNotNull null
            XmlNodeCandidate(
                bounds = bounds,
                packageName = attrs["package"],
                className = attrs["class"],
                text = decodeXmlAttr(attrs["text"]),
                contentDescription = decodeXmlAttr(attrs["content-desc"] ?: attrs["contentDescription"]),
                hintText = decodeXmlAttr(attrs["hint-text"] ?: attrs["hintText"]),
                resourceId = attrs["resource-id"] ?: attrs["resourceId"],
                clickable = attrs["clickable"] == "true",
                scrollable = attrs["scrollable"] == "true",
                focusable = attrs["focusable"] == "true",
                editable = attrs["editable"] == "true",
                enabled = attrs["enabled"] != "false",
                visible = attrs["visible-to-user"] != "false"
            )
        }.toList()
    }

    private fun parseXmlAttributes(raw: String): Map<String, String> {
        return ATTR_REGEX.findAll(raw).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun parseRootBounds(xml: String?): Rect? {
        if (xml.isNullOrBlank()) return null
        val hierarchy = HIERARCHY_TAG_REGEX.find(xml)?.groupValues?.getOrNull(1) ?: return null
        return parseBounds(parseXmlAttributes(hierarchy)["bounds"])
    }

    private fun parseBounds(value: String?): Rect? {
        if (value.isNullOrBlank()) return null
        val match = BOUNDS_REGEX.find(value) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[3].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return Rect(left, top, right, bottom)
    }

    private fun decodeXmlAttr(value: String?): String? {
        val raw = value ?: return null
        return raw
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun captureCurrentXml(): String? {
        return try {
            AccessibilityController.initController()
            // withOld=false: live accessibility-tree query, not event-driven cache.
            // When the current surface has no accessibility tree (Launcher/Desktop,
            // WebView/SurfaceView, games), source XML is optional. Do not fall back
            // to an older cached tree because that pollutes coordinate-only RunLog.
            AccessibilityController.getCaptureScreenShotXml(false)
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            OmniLog.w(TAG, "manual trace xml capture failed: ${e.message}")
            null
        }
    }

    private fun captureCurrentScreenshotRef(stage: String): ManualVlmScreenshotRef? {
        // Manual recording is XML-only. Screenshots are intentionally not captured
        // on this path to keep takeover latency low and avoid extra artifacts.
        return null
    }

    private fun buildSummary(actions: List<ManualVlmRecordedAction>): String {
        if (actions.isEmpty()) return ""
        val actionSummary = actions.take(MAX_SUMMARY_ACTIONS).joinToString("；") { action ->
            action.summary.ifBlank { action.title }
        }
        val suffix = if (actions.size > MAX_SUMMARY_ACTIONS) "；..." else ""
        return "用户在接管期间手动完成了 ${actions.size} 步操作：$actionSummary$suffix。请基于当前屏幕继续执行原任务。"
    }

    private fun recordingBackendForStatus(): String {
        return when {
            overlayGestureRecordedCount > 0 -> OVERLAY_TOUCH_BACKEND
            rawTouchStatus?.available == true && enableRawTouch -> "mixed"
            enableRawTouch -> "overlay_touch_with_raw_unavailable"
            else -> OVERLAY_TOUCH_BACKEND
        }
    }

    private fun appendRecordedAction(action: ManualVlmRecordedAction) {
        recordedActions += action
        OmniLog.d(TAG, "manual trace recorded: ${action.actionName} ${action.summary}")
    }

    private fun eventContextFor(
        event: AccessibilityEvent,
        target: ManualEventTarget,
        sourceNode: AccessibilityNodeInfo? = null,
        clickInference: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val source = sourceNode ?: runCatching { event.source }.getOrNull()
        return linkedMapOf<String, Any?>(
            "event_type" to eventTypeName(event.eventType),
            "event_package" to event.packageName?.toString(),
            "event_class" to event.className?.toString(),
            "event_text" to event.text.joinToString(" ").take(120).ifBlank { null },
            "event_time_ms" to event.eventTime,
            "event_has_source" to (source != null),
            "source_class" to source?.className?.toString(),
            "source_view_id" to source?.viewIdResourceName,
            "source_text" to source?.text?.toString()?.take(120),
            "source_content_description" to source?.contentDescription?.toString()?.take(120),
            "source_bounds" to source?.boundsInScreenOrNull()?.let(::boundsString),
            "scroll_x" to event.scrollX,
            "scroll_y" to event.scrollY,
            "scroll_delta_x" to event.scrollDeltaX,
            "scroll_delta_y" to event.scrollDeltaY,
            "from_index" to event.fromIndex,
            "to_index" to event.toIndex,
            "item_count" to event.itemCount,
            "target_resolution" to target.resolution,
            "target_package" to target.packageName,
            "target_resource_id" to target.resourceId,
            "target_class" to target.className,
            "target_bounds" to boundsString(target.bounds),
            "click_inference" to clickInference.takeIf { it.isNotEmpty() }
        ).filterValues { it != null }
    }

    private fun rawEventContextFor(
        gesture: ManualRawTouchGesture,
        target: ManualEventTarget
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "event_type" to "RAW_GETEVENT_${gesture.actionName.uppercase()}",
        "event_has_source" to false,
        "recording_backend" to gesture.backend,
        "device_path" to gesture.devicePath,
        "device_name" to gesture.deviceName,
        "gesture_id" to gesture.gestureId,
        "gesture_duration_ms" to gesture.durationMs,
        "gesture_distance_px" to gesture.distancePx,
        "gesture_point_count" to gesture.pointCount,
        "start_x" to gesture.startX,
        "start_y" to gesture.startY,
        "end_x" to gesture.endX,
        "end_y" to gesture.endY,
        "raw_start_x" to gesture.rawStartX,
        "raw_start_y" to gesture.rawStartY,
        "raw_end_x" to gesture.rawEndX,
        "raw_end_y" to gesture.rawEndY,
        "target_resolution" to target.resolution,
        "target_package" to target.packageName,
        "target_resource_id" to target.resourceId,
        "target_class" to target.className,
        "target_bounds" to boundsString(target.bounds)
    ).filterValues { it != null }

    private fun overlayEventContextFor(
        gesture: ManualOverlayTouchGesture,
        target: ManualEventTarget,
        operationId: String,
        dispatchOutcome: OverlayDispatchOutcome,
        beforeXml: String?
    ): Map<String, Any?> = (linkedMapOf<String, Any?>(
        "event_type" to "OVERLAY_TOUCH_${gesture.actionName.uppercase()}",
        "event_has_source" to false,
        "recording_backend" to OVERLAY_TOUCH_BACKEND,
        "coordinate_space" to SCREEN_ABSOLUTE_COORDINATE_SPACE,
        "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
        "gesture_duration_ms" to gesture.durationMs,
        "gesture_distance_px" to gesture.distancePx,
        "direction" to overlaySwipeDirectionName(gesture).takeIf { gesture.actionName == "swipe" },
        "start_x" to gesture.startX,
        "start_y" to gesture.startY,
        "end_x" to gesture.endX,
        "end_y" to gesture.endY,
        "display_width" to gesture.displayWidth.takeIf { it > 0 },
        "display_height" to gesture.displayHeight.takeIf { it > 0 },
        "target_resolution" to target.resolution,
        "target_package" to target.packageName,
        "target_resource_id" to target.resourceId,
        "target_class" to target.className,
        "target_bounds" to boundsString(target.bounds)
    ) + overlayDispatchDiagnostics(operationId, beforeXml, dispatchOutcome)).filterValues { it != null }

    private fun textInputEventContextFor(
        event: AccessibilityEvent,
        target: ManualEventTarget,
        sourceNode: AccessibilityNodeInfo?,
        anchor: TextInputAnchor
    ): Map<String, Any?> {
        return eventContextFor(event, target, sourceNode) + linkedMapOf(
            "input_anchor" to anchor.asMap()
        )
    }

    private fun rawSwipeDirection(gesture: ManualRawTouchGesture): String {
        val dx = gesture.endX - gesture.startX
        val dy = gesture.endY - gesture.startY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun overlaySwipeDirection(gesture: ManualOverlayTouchGesture): ScrollDirection {
        return when (overlaySwipeDirectionName(gesture)) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> ScrollDirection.DOWN
        }
    }

    private fun overlaySwipeDirectionName(gesture: ManualOverlayTouchGesture): String {
        val explicit = gesture.direction?.lowercase()
        if (explicit == "up" || explicit == "down" || explicit == "left" || explicit == "right") {
            return explicit
        }
        val dx = gesture.endX - gesture.startX
        val dy = gesture.endY - gesture.startY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun ManualEventTarget.asOverlayTarget(): ManualEventTarget =
        copy(resolution = resolution.replace("raw_touch", "overlay_touch"))

    private fun buildDiagnostics(): Map<String, Any?> {
        val rawActions = recordedActions.count {
            val backend = it.params["recording_backend"]?.toString()
            backend == RAW_TOUCH_BACKEND || backend == RAW_TOUCH_TEXT_INPUT_BACKEND
        }
        val overlayActions = recordedActions.count {
            val backend = it.params["recording_backend"]?.toString()
            backend == OVERLAY_TOUCH_BACKEND || backend == OVERLAY_TOUCH_TEXT_INPUT_BACKEND
        }
        val semanticActions = 0
        val rawTouchAvailable = rawTouchStatus?.available == true
        val overlayTouchAvailable = overlayGestureStartedCount > 0 || overlayGestureRecordedCount > 0
        val expectedOverlayRecordCount = (overlayGestureStartedCount - overlayGestureIgnoredControlCount)
            .coerceAtLeast(0)
        val overlayTouchComplete = overlayTouchAvailable &&
            overlayGestureFailedCount == 0 &&
            overlayGestureRecordedCount >= expectedOverlayRecordCount
        val completeness = when {
            overlayTouchComplete -> ManualRecordingDiagnostics.COMPLETE_OVERLAY_TOUCH
            overlayTouchAvailable -> ManualRecordingDiagnostics.INCOMPLETE_OVERLAY_TOUCH
            else -> ManualRecordingDiagnostics.completeness(
                rawTouchAvailable = rawTouchAvailable,
                rawTouchActiveAtStop = rawTouchActiveAtStop
            )
        }
        val guaranteesNoMissingClicks = overlayTouchComplete ||
            ManualRecordingDiagnostics.guaranteesNoMissingClicks(
                rawTouchAvailable = rawTouchAvailable,
                rawTouchActiveAtStop = rawTouchActiveAtStop
            )
        val warningMessage = when {
            overlayTouchAvailable && !overlayTouchComplete -> ManualRecordingDiagnostics.warningMessage(completeness)
            enableRawTouch -> ManualRecordingDiagnostics.warningMessage(completeness)
            else -> null
        }
        return linkedMapOf<String, Any?>(
            "raw_touch" to rawTouchStatus?.asMap()?.plus(
                linkedMapOf<String, Any?>(
                    "active_at_stop" to rawTouchActiveAtStop,
                    "started_gesture_count" to rawGestureStartedCount,
                    "finished_gesture_count" to rawGestureFinishedCount,
                    "recorded_gesture_count" to rawGestureRecordedCount,
                    "ignored_control_gesture_count" to rawGestureIgnoredControlCount,
                    "recorded_action_count" to rawActions,
                    "event_stream" to rawGeteventStreamDiagnostics()
                ).filterValues { it != null }
            ),
            "screenshots" to linkedMapOf(
                "schema_version" to "oob.runlog.screenshot_refs.v1",
                "enabled" to false,
                "disabled_reason" to "manual_recording_uses_real_touch_and_before_xml",
                "storage" to "app_private_files",
                "reference_style" to "path_only",
                "stored_count" to 0,
                "failed_count" to 0,
                "skipped_count" to screenshotSkippedCount,
                "root_relative_path" to "oob_runlog_artifacts/${safePathSegment(sessionLabel)}/screenshots"
            ),
            "accessibility_events" to linkedMapOf(
                "event_count" to accessibilityEventCount,
                "ignored_package_event_count" to accessibilityIgnoredPackageCount,
                "event_type_counts" to accessibilityEventTypeCounts.toMap(),
                "suppressed_semantic_action_event_count" to suppressedSemanticActionEventCount,
                "suppressed_non_raw_action_count" to suppressedNonRawActionCount,
                "records_replayable_actions" to false,
                "records_text_input_when_touch_anchored" to true,
                "recorded_action_count" to semanticActions
            ),
            "overlay_touch" to linkedMapOf(
                "available" to overlayTouchAvailable,
                "backend" to OVERLAY_TOUCH_BACKEND,
                "execution_mode" to SYNTHETIC_REPLAY_EXECUTION_MODE,
                "started_gesture_count" to overlayGestureStartedCount,
                "recorded_gesture_count" to overlayGestureRecordedCount,
                "ignored_control_gesture_count" to overlayGestureIgnoredControlCount,
                "failed_gesture_count" to overlayGestureFailedCount,
                "expected_recorded_gesture_count" to expectedOverlayRecordCount,
                "coordinate_replay_count" to overlayCoordinateReplayCount,
                "coordinate_replay_failed_count" to overlayCoordinateReplayFailedCount,
                "node_replay_fallback_count" to overlayNodeReplayFallbackCount,
                "node_replay_fallback_failed_count" to overlayNodeReplayFallbackFailedCount,
                "pending_post_record_count" to 0,
                "post_record_timeout_count" to overlayPostRecordTimeoutCount,
                "recorded_action_count" to overlayActions
            ),
            "unattributed_window_transitions" to linkedMapOf(
                "event_count" to windowTransitionEventCount,
                "count" to 0,
                "replayable" to false,
                "reason" to "window/content changes are ignored; manual RunLog only records concrete touch and text-input actions"
            ),
            "manual_recording" to linkedMapOf(
                "action_source" to when {
                    overlayActions > 0 && rawActions > 0 -> "mixed_real_touch"
                    overlayActions > 0 -> "overlay_touch"
                    rawActions > 0 -> "raw_touch"
                    else -> "none"
                },
                "overlay_touch_available" to overlayTouchAvailable,
                "execution_mode" to if (overlayTouchAvailable) SYNTHETIC_REPLAY_EXECUTION_MODE else null,
                "raw_touch_enabled" to enableRawTouch,
                "raw_touch_required" to false,
                "a11_replay_actions_enabled" to false,
                "a11_text_input_enabled" to true,
                "a11_text_input_requires_real_touch_anchor" to true,
                "action_count" to recordedActions.size,
                "overlay_action_count" to overlayActions,
                "raw_action_count" to rawActions,
                "semantic_action_count" to semanticActions,
                "suppressed_non_raw_action_count" to suppressedNonRawActionCount,
                "completeness" to completeness,
                "missing_raw_touch" to (enableRawTouch && !rawTouchAvailable),
                "raw_touch_active_at_stop" to rawTouchActiveAtStop,
                "guarantees_no_missing_clicks" to guaranteesNoMissingClicks,
                "unattributed_window_transition_count" to 0,
                "warning_message" to warningMessage
            )
        ).filterValues { it != null }
    }

    private fun eventTypeName(eventType: Int): String = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
        else -> "TYPE_$eventType"
    }

    private fun rawGeteventStreamDiagnostics(): Map<String, Any?>? {
        if (rawGeteventLineCount <= 0 && rawGeteventRecentLines.isEmpty()) return null
        return linkedMapOf(
            "format" to "getevent -lt",
            "scope" to "selected_touch_device_only",
            "retention_policy" to "last_$MAX_RAW_GETEVENT_RECENT_LINES",
            "line_count" to rawGeteventLineCount,
            "retained_line_count" to rawGeteventRecentLines.size,
            "dropped_line_count" to rawGeteventDroppedLineCount,
            "truncated" to (rawGeteventDroppedLineCount > 0),
            "events" to rawGeteventRecentLines.toList()
        )
    }

    private fun eventWallTime(eventTimeMs: Long, nowWallMs: Long): Long {
        if (eventTimeMs <= 0L) return nowWallMs
        val ageMs = (SystemClock.uptimeMillis() - eventTimeMs).coerceAtLeast(0L)
        return (nowWallMs - ageMs).coerceAtMost(nowWallMs)
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (value in values) {
            val normalized = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (normalized.isNotEmpty()) return normalized
        }
        return ""
    }

    private fun incrementCount(counts: MutableMap<String, Int>, key: String) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    private data class PendingTextAction(
        val nodeKey: String,
        val anchorId: String,
        val packageName: String?,
        val label: String,
        val text: String,
        val bounds: Rect,
        val className: String?,
        val resourceId: String?,
        val resolution: String,
        val recordingBackend: String,
        val beforeXml: String?,
        val beforeScreenshot: ManualVlmScreenshotRef?,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val eventContext: Map<String, Any?>
    )

    private data class TextInputAnchor(
        val id: String,
        val backend: String,
        val beforeXml: String?,
        val beforeScreenshot: ManualVlmScreenshotRef?,
        val target: ManualEventTarget,
        val x: Float,
        val y: Float,
        val startedAtMs: Long,
        val finishedAtMs: Long
    ) {
        fun asMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "id" to id,
            "backend" to backend,
            "x" to x,
            "y" to y,
            "started_at_ms" to startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "target_description" to target.label,
            "target_bounds" to "[${target.bounds.left},${target.bounds.top}][${target.bounds.right},${target.bounds.bottom}]",
            "target_package" to target.packageName,
            "target_resource_id" to target.resourceId,
            "target_class" to target.className,
            "target_resolution" to target.resolution,
            "has_before_xml" to !beforeXml.isNullOrBlank(),
            "has_before_screenshot" to (beforeScreenshot != null)
        ).filterValues { it != null }

        fun isCoordinateOnlyTextAnchor(): Boolean =
            target.resolution.contains("coordinate_text_anchor")
    }

    private data class ManualEventTarget(
        val label: String,
        val bounds: Rect,
        val packageName: String?,
        val className: String?,
        val resourceId: String?,
        val text: String?,
        val contentDescription: String?,
        val stableKey: String,
        val resolution: String
    )

    private data class XmlNodeCandidate(
        val bounds: Rect,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val contentDescription: String?,
        val hintText: String?,
        val resourceId: String?,
        val clickable: Boolean,
        val scrollable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
        val visible: Boolean
    ) {
        val bestLabel: String
            get() = firstNonBlankStatic(
                contentDescription,
                text,
                hintText,
                resourceId?.substringAfterLast('/'),
                className
            )

        fun toManualTarget(
            fallbackPackageName: String?,
            resolution: String
        ): ManualEventTarget {
            return ManualEventTarget(
                label = bestLabel,
                bounds = bounds,
                packageName = packageName ?: fallbackPackageName,
                className = className,
                resourceId = resourceId,
                text = text,
                contentDescription = contentDescription,
                stableKey = firstNonBlankStatic(resourceId, className) + "|" + boundsString(bounds),
                resolution = resolution
            )
        }
    }

    private companion object {
        private const val TAG = "ManualVlmTraceRecorder"
        private const val DUPLICATE_EVENT_WINDOW_MS = 400L
        private const val OVERLAY_TOUCH_BACKEND = "overlay_touch"
        private const val OVERLAY_TOUCH_TEXT_INPUT_BACKEND = "overlay_touch_text_input"
        private const val SCREEN_ABSOLUTE_COORDINATE_SPACE = "screen_absolute_px"
        private const val SYNTHETIC_REPLAY_EXECUTION_MODE = "synthetic_replay"
        private const val OVERLAY_RECORD_DRAIN_POLL_MS = 100L
        private const val OVERLAY_RECORD_DRAIN_TIMEOUT_MS = 600L
        private const val BEFORE_XML_CAPTURE_TIMEOUT_MS = 300L
        private const val OVERLAY_LONG_PRESS_MIN_DURATION_MS = 600L
        private const val OVERLAY_SWIPE_MIN_DURATION_MS = 120L
        private const val OVERLAY_SWIPE_MIN_DISTANCE_PX = 16f
        private const val OVERLAY_CLICK_REPLAY_TIMEOUT_MS = 500L
        private const val DISPATCH_STATUS_COMPLETED = "dispatch_completed"
        private const val DISPATCH_STATUS_TIMEOUT = "dispatch_timeout"
        private const val DISPATCH_STATUS_CANCELLED = "dispatch_cancelled"
        private const val DISPATCH_STATUS_FAILED = "dispatch_failed"
        private const val RAW_TOUCH_BACKEND = "device_getevent"
        private const val RAW_TOUCH_TEXT_INPUT_BACKEND = "device_getevent_text_input"
        private const val REAL_TOUCH_TEXT_INPUT_BACKEND = "real_touch_text_input"
        private const val MAX_LABEL_LENGTH = 80
        private const val MAX_SUMMARY_TEXT = 40
        private const val MAX_SUMMARY_ACTIONS = 8
        private const val MAX_ACTIONABLE_ANCESTOR_DEPTH = 4
        private const val MAX_IGNORED_CONTROL_AREA_RATIO = 0.20f
        private const val MAX_IGNORED_CONTROL_AREA_PX = 160_000
        private const val MAX_WINDOW_TRANSITION_SAMPLES = 8
        private const val MAX_RAW_GETEVENT_RECENT_LINES = 2_000
        private const val MAX_PAGE_FINGERPRINT_NODES = 220
        private const val MAX_PAGE_SUMMARY_LABELS = 8
        private const val MAX_PAGE_LABEL_LENGTH = 48
        private const val MAX_ERROR_MESSAGE_LENGTH = 240
        private const val REDACTED_TEXT = "[REDACTED]"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private val NODE_TAG_REGEX = Regex("<node\\b([^>]*)>")
        private val HIERARCHY_TAG_REGEX = Regex("<hierarchy\\b([^>]*)>")
        private val ATTR_REGEX = Regex("([A-Za-z0-9_:-]+)=\"([^\"]*)\"")
        private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
        private val OOB_CONTROL_HINTS = listOf(
            "已完成操作",
            "完成学习",
            "取消学习",
            "继续执行",
            "用户已接管",
            "接管",
            "学习中",
            "resume",
            "continue",
            "takeover",
            "omnimind",
            "omnibot"
        )

        private fun firstNonBlankStatic(vararg values: String?): String {
            for (value in values) {
                val normalized = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (normalized.isNotEmpty()) return normalized
            }
            return ""
        }

        private fun boundsString(bounds: Rect): String =
            "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

        private fun safePathSegment(value: String): String {
            val normalized = value
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
            return normalized.ifBlank { "manual_trace" }.take(96)
        }
    }
}
