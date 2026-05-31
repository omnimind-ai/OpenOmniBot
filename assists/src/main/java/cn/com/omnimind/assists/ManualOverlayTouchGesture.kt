package cn.com.omnimind.assists

/**
 * A user gesture captured by the manual recording overlay.
 *
 * UIKit owns touch interception/classification. The assists recorder owns
 * execution, UI-state capture, and RunLog action generation.
 * The original MotionEvent is consumed by the overlay; execution is a
 * synthetic Accessibility replay, not pass-through.
 *
 * Coordinates are screen-absolute pixels. They are intentionally not relative
 * to the overlay view because AccessibilityService gesture replay consumes
 * screen coordinates.
 */
data class ManualOverlayTouchGesture(
    val actionName: String,
    val startX: Float,
    val startY: Float,
    val endX: Float = startX,
    val endY: Float = startY,
    val durationMs: Long = 0L,
    val distancePx: Float = 0f,
    val direction: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0
)

data class ManualOverlayGestureReplayResult(
    val executed: Boolean,
    val recorded: Boolean = executed,
    val mayOpenIme: Boolean = false,
    val ignoredControl: Boolean = false
)
