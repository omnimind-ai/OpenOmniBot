package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import java.util.ArrayDeque
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    val rawTouchAvailable: Boolean
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "is_started" to isStarted,
        "is_paused" to isPaused,
        "action_count" to actionCount,
        "latest_action_summary" to latestActionSummary,
        "pending_action_summary" to pendingActionSummary,
        "accessibility_event_count" to accessibilityEventCount,
        "raw_touch_enabled" to rawTouchEnabled,
        "raw_touch_available" to rawTouchAvailable
    ).filterValues { it != null }
}

internal object ManualRecordingDiagnostics {
    const val COMPLETE_RAW_TOUCH = "complete_raw_touch"
    const val MISSING_RAW_TOUCH = "missing_raw_touch"
    const val RAW_TOUCH_INTERRUPTED = "raw_touch_interrupted"

    @Deprecated("Manual recording is A11-first; use MISSING_RAW_TOUCH.")
    const val PARTIAL_SEMANTIC_ONLY = MISSING_RAW_TOUCH

    @Deprecated("Manual recording is A11-first; use RAW_TOUCH_INTERRUPTED.")
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
        COMPLETE_RAW_TOUCH -> null
        RAW_TOUCH_INTERRUPTED -> "raw touch 录制中断，本次轨迹可能遗漏点击/滑动"
        else -> "raw touch 不可用，本次使用 A11Event 录制，可能遗漏部分点击/滑动"
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

internal data class ManualScrollSnapshot(
    val scrollX: Int,
    val scrollY: Int,
    val scrollDeltaX: Int,
    val scrollDeltaY: Int,
    val fromIndex: Int,
    val toIndex: Int,
    val itemCount: Int
) {
    fun hasViewportSignal(): Boolean =
        abs(scrollDeltaX) >= ManualScrollEventPolicy.MIN_SCROLL_DELTA ||
            abs(scrollDeltaY) >= ManualScrollEventPolicy.MIN_SCROLL_DELTA ||
            scrollX != 0 ||
            scrollY != 0 ||
            fromIndex >= 0 ||
            toIndex >= 0 ||
            itemCount > 0
}

internal enum class ManualScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

internal object ManualScrollEventPolicy {
    const val MIN_SCROLL_DELTA = 4

    fun inferDirection(
        previous: ManualScrollSnapshot?,
        current: ManualScrollSnapshot
    ): ManualScrollDirection? {
        if (abs(current.scrollDeltaY) >= MIN_SCROLL_DELTA) {
            return if (current.scrollDeltaY > 0) ManualScrollDirection.UP else ManualScrollDirection.DOWN
        }
        if (abs(current.scrollDeltaX) >= MIN_SCROLL_DELTA) {
            return if (current.scrollDeltaX > 0) ManualScrollDirection.LEFT else ManualScrollDirection.RIGHT
        }
        if (previous != null) {
            val dy = current.scrollY - previous.scrollY
            if (abs(dy) >= MIN_SCROLL_DELTA) {
                return if (dy > 0) ManualScrollDirection.UP else ManualScrollDirection.DOWN
            }
            val dx = current.scrollX - previous.scrollX
            if (abs(dx) >= MIN_SCROLL_DELTA) {
                return if (dx > 0) ManualScrollDirection.LEFT else ManualScrollDirection.RIGHT
            }
            val indexDelta = current.fromIndex - previous.fromIndex
            if (indexDelta != 0) {
                return if (indexDelta > 0) ManualScrollDirection.UP else ManualScrollDirection.DOWN
            }
        }
        return null
    }
}

internal object ManualClickGrounding {
    fun inferClickTarget(
        beforeXml: String?,
        afterXml: String?,
        packageName: String?,
        eventLabel: String,
        eventClassName: String?,
        ignoredPackageName: String,
        ignoredTextHints: List<String>
    ): Result {
        val candidates = parseXmlNodeCandidates(beforeXml)
            .filter { !it.bounds.isEmpty }
            .filterNot { shouldIgnoreTarget(it, packageName, ignoredPackageName, ignoredTextHints) }
        if (candidates.isEmpty()) {
            return emptyResult("no_xml_candidates", eventLabel, eventClassName, pageChanged = false)
        }

        val eventTokens = meaningfulTokens(eventLabel)
        val afterPage = PageSummary.from(afterXml)
        val pageChanged = pageFingerprint(beforeXml) != pageFingerprint(afterXml)
        val scored = candidates.mapNotNull { candidate ->
            val base = candidate.matchScore(
                packageName = packageName,
                labelTokens = eventTokens,
                eventClassName = eventClassName
            )
            val transition = candidate.transitionScore(afterPage, pageChanged)
            if (base <= 0 && transition <= 0) return@mapNotNull null
            CandidateScore(
                candidate = candidate,
                baseScore = base,
                transitionScore = transition,
                eventTextMatched = eventTokens.isNotEmpty() &&
                    eventTokens.any { candidate.searchText.contains(it) }
            )
        }.sortedWith(
            compareByDescending<CandidateScore> { it.totalScore }
                .thenByDescending { it.transitionScore }
                .thenByDescending { it.baseScore }
                .thenBy { it.candidate.bounds.area }
        )

        if (scored.isEmpty()) {
            return emptyResult("no_scored_candidates", eventLabel, eventClassName, pageChanged)
        }

        val top = scored.first()
        val second = scored.getOrNull(1)
        val finalMargin = top.totalScore - (second?.totalScore ?: 0)
        val baseMargin = top.baseScore - (second?.baseScore ?: 0)
        val hasEventText = eventTokens.isNotEmpty()
        val strongTextMatch = hasEventText &&
            top.eventTextMatched &&
            top.baseScore >= MIN_STRONG_TEXT_SCORE &&
            baseMargin >= MIN_SELECTION_MARGIN
        val strongTransition = top.transitionScore >= MIN_STRONG_TRANSITION_SCORE &&
            finalMargin >= MIN_SELECTION_MARGIN
        val singleCandidate = scored.size == 1 &&
            (top.baseScore >= MIN_SINGLE_CANDIDATE_SCORE || top.transitionScore >= MIN_STRONG_TRANSITION_SCORE)

        val reason = when {
            strongTextMatch -> "event_text_xml_match"
            strongTransition -> "after_page_transition_match"
            singleCandidate -> "single_xml_candidate"
            hasEventText && !top.eventTextMatched -> "ambiguous_event_text_not_matched"
            !pageChanged -> "ambiguous_no_page_change"
            else -> "ambiguous_low_margin"
        }
        val confidence = when {
            strongTextMatch -> 0.92
            strongTransition -> 0.84
            singleCandidate -> 0.72
            else -> 0.0
        }
        val inference = inferenceMap(
            reason = reason,
            confidence = confidence,
            eventLabel = eventLabel,
            eventClassName = eventClassName,
            pageChanged = pageChanged,
            selected = top,
            candidates = scored
        )
        if (!strongTextMatch && !strongTransition && !singleCandidate) {
            return Result(
                target = null,
                resolution = "xml_fallback_ambiguous",
                inference = inference
            )
        }
        val resolution = when {
            strongTextMatch -> "xml_fallback_text_inferred"
            strongTransition -> "xml_fallback_transition_inferred"
            else -> "xml_fallback_single_candidate"
        }
        return Result(
            target = top.candidate.toTarget(),
            resolution = resolution,
            inference = inference
        )
    }

    data class Result(
        val target: Target?,
        val resolution: String,
        val inference: Map<String, Any?>
    )

    data class Target(
        val label: String,
        val bounds: Bounds,
        val packageName: String?,
        val className: String?,
        val resourceId: String?,
        val text: String?,
        val contentDescription: String?
    )

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Int get() = width.coerceAtLeast(0) * height.coerceAtLeast(0)
        val isEmpty: Boolean get() = width <= 0 || height <= 0
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        fun asString(): String = "[$left,$top][$right,$bottom]"
    }

    private data class CandidateScore(
        val candidate: XmlCandidate,
        val baseScore: Int,
        val transitionScore: Int,
        val eventTextMatched: Boolean
    ) {
        val totalScore: Int get() = baseScore + transitionScore
    }

    private data class PageSummary(
        val labels: List<String>,
        val topLabels: List<String>,
        val editableCount: Int
    ) {
        val labelText: String = labels.joinToString(" ").lowercase()
        val topLabelText: String = topLabels.joinToString(" ").lowercase()

        companion object {
            fun from(xml: String?): PageSummary {
                val candidates = parseXmlNodeCandidates(xml)
                return PageSummary(
                    labels = candidates.map { it.bestLabel }.filter { it.isNotBlank() },
                    topLabels = candidates
                        .filter { it.bounds.top <= TOP_REGION_PX }
                        .map { it.bestLabel }
                        .filter { it.isNotBlank() },
                    editableCount = candidates.count { it.isEditableLike }
                )
            }
        }
    }

    private data class XmlCandidate(
        val bounds: Bounds,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val contentDescription: String?,
        val hintText: String?,
        val resourceId: String?,
        val clickable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val editable: Boolean,
        val focusable: Boolean
    ) {
        val bestLabel: String
            get() = firstNonBlank(
                contentDescription,
                text,
                hintText,
                resourceId?.substringAfterLast('/'),
                className
            )
        val searchText: String
            get() = listOfNotNull(text, contentDescription, hintText, resourceId, className, bestLabel)
                .joinToString(" ")
                .lowercase()
        val isEditableLike: Boolean
            get() = editable ||
                className.orEmpty().contains("EditText", ignoreCase = true) ||
                className.orEmpty().contains("TextInput", ignoreCase = true)

        fun matchScore(
            packageName: String?,
            labelTokens: List<String>,
            eventClassName: String?
        ): Int {
            var score = 0
            if (visible) score += 2
            if (enabled) score += 2
            if (!packageName.isNullOrBlank() && packageName == this.packageName) score += 8
            if (clickable || focusable || isEditableLike) score += 12
            if (!eventClassName.isNullOrBlank() && eventClassName == className) score += 8
            for (token in labelTokens) {
                if (searchText == token) score += 35
                if (searchText.contains(token)) score += 25
            }
            return score
        }

        fun transitionScore(afterPage: PageSummary, pageChanged: Boolean): Int {
            if (!pageChanged) return 0
            var score = 0
            val label = bestLabel.lowercase()
            if (label.isNotBlank() && afterPage.labelText.contains(label)) score += 70
            if (label.isNotBlank() && afterPage.topLabelText.contains(label)) score += 35
            if (isCreateAffordance(label) && afterPage.editableCount >= 2) score += 90
            if (isCreateAffordance(label) && afterPage.editableCount == 1) score += 45
            return score
        }

        fun toTarget(): Target = Target(
            label = bestLabel,
            bounds = bounds,
            packageName = packageName,
            className = className,
            resourceId = resourceId,
            text = text,
            contentDescription = contentDescription
        )
    }

    private fun emptyResult(
        reason: String,
        eventLabel: String,
        eventClassName: String?,
        pageChanged: Boolean
    ): Result = Result(
        target = null,
        resolution = "xml_fallback_ambiguous",
        inference = linkedMapOf<String, Any?>(
            "source" to "xml_fallback",
            "reason" to reason,
            "confidence" to 0.0,
            "event_label" to eventLabel.takeIf { it.isNotBlank() },
            "event_class" to eventClassName,
            "page_changed" to pageChanged,
            "candidate_count" to 0
        ).filterValues { it != null }
    )

    private fun inferenceMap(
        reason: String,
        confidence: Double,
        eventLabel: String,
        eventClassName: String?,
        pageChanged: Boolean,
        selected: CandidateScore,
        candidates: List<CandidateScore>
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "source" to "xml_fallback",
        "reason" to reason,
        "confidence" to confidence,
        "event_label" to eventLabel.takeIf { it.isNotBlank() },
        "event_class" to eventClassName,
        "page_changed" to pageChanged,
        "candidate_count" to candidates.size,
        "selected" to selected.summaryMap(),
        "candidates" to candidates.take(MAX_INFERENCE_CANDIDATES).map { it.summaryMap() }
    ).filterValues { it != null }

    private fun CandidateScore.summaryMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "label" to candidate.bestLabel,
        "class" to candidate.className,
        "resource_id" to candidate.resourceId,
        "bounds" to candidate.bounds.asString(),
        "base_score" to baseScore,
        "transition_score" to transitionScore,
        "total_score" to totalScore,
        "event_text_matched" to eventTextMatched
    ).filterValues { it != null }

    private fun shouldIgnoreTarget(
        candidate: XmlCandidate,
        fallbackPackageName: String?,
        ignoredPackageName: String,
        ignoredTextHints: List<String>
    ): Boolean {
        val candidatePackage = candidate.packageName ?: fallbackPackageName
        if (candidatePackage.isNullOrBlank() || candidatePackage == ignoredPackageName) return true
        val text = listOf(candidate.bestLabel, candidate.resourceId.orEmpty()).joinToString(" ").lowercase()
        return ignoredTextHints.any { text.contains(it) }
    }

    private fun parseXmlNodeCandidates(xml: String?): List<XmlCandidate> {
        if (xml.isNullOrBlank()) return emptyList()
        return NODE_TAG_REGEX.findAll(xml).mapNotNull { match ->
            val attrs = parseXmlAttributes(match.groupValues.getOrNull(1).orEmpty())
            val bounds = parseBounds(attrs["bounds"]) ?: return@mapNotNull null
            XmlCandidate(
                bounds = bounds,
                packageName = attrs["package"],
                className = attrs["class"],
                text = decodeXmlAttr(attrs["text"]),
                contentDescription = decodeXmlAttr(attrs["content-desc"] ?: attrs["contentDescription"]),
                hintText = decodeXmlAttr(attrs["hint-text"] ?: attrs["hintText"]),
                resourceId = attrs["resource-id"] ?: attrs["resourceId"],
                clickable = attrs["clickable"] == "true",
                enabled = attrs["enabled"] != "false",
                visible = attrs["visible-to-user"] != "false",
                editable = attrs["editable"] == "true",
                focusable = attrs["focusable"] == "true"
            )
        }.toList()
    }

    private fun pageFingerprint(xml: String?): String {
        if (xml.isNullOrBlank()) return ""
        return parseXmlNodeCandidates(xml)
            .map { listOf(it.bestLabel, it.className.orEmpty(), it.resourceId.orEmpty()).joinToString("|") }
            .joinToString("\n")
    }

    private fun parseXmlAttributes(raw: String): Map<String, String> =
        ATTR_REGEX.findAll(raw).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }

    private fun parseBounds(value: String?): Bounds? {
        if (value.isNullOrBlank()) return null
        val match = BOUNDS_REGEX.find(value) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[3].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return Bounds(left, top, right, bottom)
    }

    private fun meaningfulTokens(value: String): List<String> {
        val normalized = value.replace(Regex("\\s+"), " ").trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        val asciiTokens = normalized
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
        return if (asciiTokens.isNotEmpty()) asciiTokens else listOf(normalized)
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

    private fun firstNonBlank(vararg values: String?): String {
        for (value in values) {
            val normalized = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (normalized.isNotEmpty()) return normalized
        }
        return ""
    }

    private fun isCreateAffordance(label: String): Boolean {
        if (label.isBlank()) return false
        return CREATE_AFFORDANCE_TERMS.any { label.contains(it) }
    }

    private const val TOP_REGION_PX = 420
    private const val MAX_INFERENCE_CANDIDATES = 5
    private const val MIN_STRONG_TEXT_SCORE = 45
    private const val MIN_SINGLE_CANDIDATE_SCORE = 25
    private const val MIN_STRONG_TRANSITION_SCORE = 70
    private const val MIN_SELECTION_MARGIN = 20
    private val NODE_TAG_REGEX = Regex("<node\\b([^>]*)>")
    private val ATTR_REGEX = Regex("([A-Za-z0-9_:-]+)=\"([^\"]*)\"")
    private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
    private val CREATE_AFFORDANCE_TERMS = listOf(
        "new",
        "create",
        "add",
        "insert",
        "新增",
        "新建",
        "创建",
        "添加",
        "加入"
    )
}

/**
 * Records user actions during VLM takeover using Accessibility semantic events.
 *
 * Raw touch capture is opt-in enrichment. It is disabled by default because
 * most devices cannot read `/dev/input/event*` from an app UID without
 * root/Shizuku; the default recorder path is A11Event-only.
 */
class ManualVlmTraceRecorder(
    private val context: Context,
    private val sessionLabel: String,
    private val enableRawTouch: Boolean = false
) {
    private val ownPackageName = context.packageName
    private val recordingLock = Any()
    private val recordedActions = mutableListOf<ManualVlmRecordedAction>()
    private val nodeScrollState = mutableMapOf<String, ManualScrollSnapshot>()
    private val rawGestureBeforeXml = mutableMapOf<Long, String?>()
    private val rawGestureBeforeScreenshot = mutableMapOf<Long, ManualVlmScreenshotRef?>()
    private var pendingText: PendingTextAction? = null
    private var pendingScroll: PendingScrollAction? = null
    private var rawTouchRecorder: ManualRawTouchRecorder? = null
    private var rawTouchStatus: ManualRawTouchStatus? = null
    private var lastDiscreteSignature: String = ""
    private var lastDiscreteAtMs: Long = 0L
    private var lastXmlSnapshot: String? = null
    private var lastScreenshotSnapshot: ManualVlmScreenshotRef? = null
    private var screenshotSeq: Int = 0
    private var screenshotStoredCount: Int = 0
    private var screenshotFailedCount: Int = 0
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
    private var suppressedSemanticActionEventCount: Int = 0
    private var suppressedNonRawActionCount: Int = 0
    private var windowTransitionEventCount: Int = 0
    private var unattributedWindowTransitionCount: Int = 0
    private var lastUnattributedWindowTransitionSignature: String = ""
    private val unattributedWindowTransitionSamples = mutableListOf<Map<String, Any?>>()
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
                errorMessage = "Raw getevent recording is disabled; using Accessibility events"
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
        val currentXml = captureCurrentXml()
        val currentScreenshot = captureCurrentScreenshotRef("pause")
        flushPendingText(currentXml)
        flushPendingScroll(currentXml)
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
        if (isStarted) {
            AssistsService.removeListener(listener)
            isStarted = false
        }
        stopRawTouchRecorder()
        isPaused = false
        flushPendingText(lastXmlSnapshot)
        flushPendingScroll(lastXmlSnapshot)
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
            pendingScroll != null -> "正在滑动：${pendingScroll?.label.orEmpty()}"
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
            rawTouchAvailable = rawTouchStatus?.available == true
        )
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

        val beforeXml = lastXmlSnapshot ?: captureCurrentXml()
        val beforeScreenshot = lastScreenshotSnapshot ?: captureCurrentScreenshotRef("event_before")
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val afterXml = captureCurrentXml()
                recordWindowTransitionObservation(event, beforeXml, afterXml)
                lastXmlSnapshot = afterXml
                lastScreenshotSnapshot = captureCurrentScreenshotRef("window_transition_after")
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordTextChanged(event, packageName, beforeXml, beforeScreenshot)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScrolled(event, packageName, beforeXml, beforeScreenshot)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(event, packageName, "click", beforeXml, beforeScreenshot)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> recordFocusedTextTarget(event, packageName, beforeXml, beforeScreenshot)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordClick(
                event,
                packageName,
                "long_press",
                beforeXml,
                beforeScreenshot
            )
            else -> Unit
        }
    }

    private fun suppressSemanticActionEvent(event: AccessibilityEvent) {
        suppressedSemanticActionEventCount += 1
        OmniLog.d(TAG, "manual trace semantic action suppressed: ${eventTypeName(event.eventType)}")
    }

    private fun recordWindowTransitionObservation(
        event: AccessibilityEvent,
        beforeXml: String?,
        afterXml: String?
    ) {
        windowTransitionEventCount += 1
        if (isRawTouchActive()) return
        val beforeFingerprint = pageStableFingerprint(beforeXml)
        val afterFingerprint = pageStableFingerprint(afterXml)
        if (beforeFingerprint.isBlank() || afterFingerprint.isBlank()) return
        if (beforeFingerprint == afterFingerprint) return

        val signature = listOf(
            eventTypeName(event.eventType),
            fingerprintHash(beforeFingerprint),
            fingerprintHash(afterFingerprint)
        ).joinToString("|")
        if (signature == lastUnattributedWindowTransitionSignature) return
        lastUnattributedWindowTransitionSignature = signature
        unattributedWindowTransitionCount += 1

        if (unattributedWindowTransitionSamples.size >= MAX_WINDOW_TRANSITION_SAMPLES) return
        val sourceNode = runCatching { event.source }.getOrNull()
        unattributedWindowTransitionSamples += linkedMapOf<String, Any?>(
            "observation_kind" to "unattributed_window_transition",
            "replayable" to false,
            "reason" to "window changed without raw touch gesture",
            "event_type" to eventTypeName(event.eventType),
            "event_package" to event.packageName?.toString(),
            "event_class" to event.className?.toString(),
            "event_time_ms" to event.eventTime,
            "event_has_source" to (sourceNode != null),
            "source_class" to sourceNode?.className?.toString(),
            "source_view_id" to sourceNode?.viewIdResourceName,
            "before_fingerprint" to fingerprintHash(beforeFingerprint),
            "after_fingerprint" to fingerprintHash(afterFingerprint),
            "before_page" to pageSummary(beforeXml),
            "after_page" to pageSummary(afterXml)
        ).filterValues { it != null }
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

    private fun isRawTouchActive(): Boolean = rawTouchRecorder?.isActive() == true

    private fun rememberRawGestureStart(start: ManualRawTouchStart) {
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            rawGestureStartedCount += 1
            rawGestureBeforeXml[start.gestureId] = lastXmlSnapshot ?: captureCurrentXml()
            rawGestureBeforeScreenshot[start.gestureId] = lastScreenshotSnapshot
                ?: captureCurrentScreenshotRef("raw_${start.gestureId}_before")
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
        Thread.sleep(RAW_TOUCH_SETTLE_MS)
        val afterXml = captureCurrentXml()
        val afterScreenshot = captureCurrentScreenshotRef("raw_${gesture.gestureId}_after")
        synchronized(recordingLock) {
            if (!isStarted || isPaused) return
            flushPendingText(beforeXml)
            flushPendingScroll(beforeXml)
            when (gesture.actionName) {
                "click", "long_press" -> appendRawClickGesture(gesture, beforeXml, afterXml, beforeScreenshot, afterScreenshot)
                "swipe" -> appendRawSwipeGesture(gesture, beforeXml, afterXml, beforeScreenshot, afterScreenshot)
            }
            rawGestureRecordedCount += 1
            lastXmlSnapshot = afterXml ?: beforeXml
            lastScreenshotSnapshot = afterScreenshot ?: beforeScreenshot
        }
    }

    private fun appendRawClickGesture(
        gesture: ManualRawTouchGesture,
        beforeXml: String?,
        afterXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        afterScreenshot: ManualVlmScreenshotRef?
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
                afterXml = afterXml,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = afterScreenshot,
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
        afterXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?,
        afterScreenshot: ManualVlmScreenshotRef?
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
                afterXml = afterXml,
                beforeScreenshot = beforeScreenshot,
                afterScreenshot = afterScreenshot,
                startedAtMs = gesture.startedAtMs,
                finishedAtMs = gesture.finishedAtMs,
                summary = title,
                eventContext = rawEventContextFor(gesture, target)
            )
        )
    }

    private fun recordClick(
        event: AccessibilityEvent,
        packageName: String?,
        actionName: String,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        val sourceNode = runCatching { event.source }.getOrNull()
        flushPendingText(lastXmlSnapshot)
        flushPendingScroll(lastXmlSnapshot)
        val eventLabel = event.text.joinToString(" ").trim()
        val beforePageXml = beforeXml ?: captureCurrentXml()
        var afterXmlForAction: String? = null
        val clickGrounding = sourceNode
            ?.let { source ->
                targetFromSourceNode(source, packageName)?.let {
                    ClickGroundingDecision(target = it, inference = emptyMap())
                }
            }
            ?: run {
                val afterXml = captureCurrentXml()
                afterXmlForAction = afterXml
                targetFromXmlClick(
                    beforeXml = beforePageXml,
                    afterXml = afterXml,
                    packageName = packageName,
                    eventLabel = eventLabel,
                    eventClassName = event.className?.toString()
                )
            }
            ?: run {
                lastXmlSnapshot = afterXmlForAction ?: beforePageXml
                OmniLog.d(TAG, "manual trace ignored ungrounded $actionName event")
                return
            }
        val target = clickGrounding.target
        val bounds = target.bounds
        if (bounds.isEmpty) return

        val label = target.label.ifBlank { eventLabel }
        val targetPackage = target.packageName ?: packageName
        val recordedActionName = navigationActionFor(targetPackage, label) ?: actionName
        val now = System.currentTimeMillis()

        val title = when (recordedActionName) {
            "press_back" -> "人工返回"
            "press_home" -> "人工回到主页"
            "long_press" -> if (label.isNotBlank()) "人工长按 $label" else "人工长按"
            else -> if (label.isNotBlank()) "人工点击 $label" else "人工点击"
        }
        val params: LinkedHashMap<String, Any?> = if (
            recordedActionName == "press_back" ||
            recordedActionName == "press_home"
        ) {
            linkedMapOf<String, Any?>(
                "target_description" to label.ifBlank { recordedActionName },
                "key" to if (recordedActionName == "press_back") "BACK" else "HOME"
            )
        } else {
            linkedMapOf<String, Any?>(
                "target_description" to label.ifBlank { target.className.orEmpty() },
                "x" to bounds.centerX().toFloat(),
                "y" to bounds.centerY().toFloat(),
                "bounds" to boundsString(bounds),
                "node_class" to target.className,
                "node_resource_id" to target.resourceId,
                "node_text" to target.text,
                "node_content_description" to target.contentDescription,
                "recording_backend" to "accessibility_event",
                "target_resolution" to target.resolution,
                "click_inference_reason" to clickGrounding.inference["reason"],
                "click_inference_confidence" to clickGrounding.inference["confidence"]
            )
        }
        if (recordedActionName == "long_press") {
            params["duration_ms"] = 1000L
        }
        val afterXml = afterXmlForAction ?: captureCurrentXml()
        val afterScreenshot = captureCurrentScreenshotRef("click_after")
        lastXmlSnapshot = afterXml
        lastScreenshotSnapshot = afterScreenshot ?: beforeScreenshot
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = recordedActionName,
            title = title,
            params = params,
            packageName = targetPackage,
            beforeXml = beforeXml,
            afterXml = afterXml,
            beforeScreenshot = beforeScreenshot,
            afterScreenshot = afterScreenshot,
            startedAtMs = eventWallTime(event.eventTime, now),
            finishedAtMs = now,
            summary = title,
            eventContext = eventContextFor(event, target, sourceNode, clickGrounding.inference)
        ))
    }

    private fun recordFocusedTextTarget(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        flushPendingText(lastXmlSnapshot)
        flushPendingScroll(lastXmlSnapshot)
        val sourceNode = event.source ?: return
        if (!isTextInputNode(sourceNode)) return
        val target = targetFromTextSourceNode(sourceNode, packageName) ?: return
        val bounds = target.bounds
        if (bounds.isEmpty) return

        val now = System.currentTimeMillis()
        val targetPackage = target.packageName ?: packageName
        val signature = "click|$targetPackage|${target.stableKey}"
        if (signature == lastDiscreteSignature && now - lastDiscreteAtMs < DUPLICATE_EVENT_WINDOW_MS) {
            return
        }
        lastDiscreteSignature = signature
        lastDiscreteAtMs = now

        val label = target.label.ifBlank { "输入框" }
        val title = "人工点击 $label"
        val afterXml = captureCurrentXml()
        val afterScreenshot = captureCurrentScreenshotRef("focus_after")
        lastXmlSnapshot = afterXml
        lastScreenshotSnapshot = afterScreenshot ?: beforeScreenshot
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = "click",
            title = title,
            params = linkedMapOf<String, Any?>(
                "target_description" to label,
                "x" to bounds.centerX().toFloat(),
                "y" to bounds.centerY().toFloat(),
                "bounds" to boundsString(bounds),
                "node_class" to target.className,
                "node_resource_id" to target.resourceId,
                "node_text" to target.text,
                "node_content_description" to target.contentDescription,
                "recording_backend" to "accessibility_event",
                "target_resolution" to target.resolution
            ).filterValues { it != null },
            packageName = targetPackage,
            beforeXml = beforeXml,
            afterXml = afterXml,
            beforeScreenshot = beforeScreenshot,
            afterScreenshot = afterScreenshot,
            startedAtMs = eventWallTime(event.eventTime, now),
            finishedAtMs = now,
            summary = title,
            eventContext = eventContextFor(event, target, sourceNode)
        ))
    }

    private fun recordTextChanged(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        val node = event.source ?: return
        val now = System.currentTimeMillis()
        val text = event.text.joinToString("").ifBlank { node.text?.toString().orEmpty() }
        val safeText = if (node.isPassword) REDACTED_TEXT else text
        if (safeText.isBlank()) return
        val sourceTarget = targetFromTextSourceNode(node, packageName) ?: return
        val target = textReplayTargetFromBeforeXml(
            sourceTarget = sourceTarget,
            beforeXml = beforeXml,
            fallbackPackageName = packageName,
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
            packageName = target.packageName ?: packageName,
            label = target.label.ifBlank { "输入框" },
            text = safeText,
            bounds = bounds,
            className = target.className,
            resourceId = target.resourceId,
            resolution = target.resolution,
            beforeXml = pendingBeforeXml,
            beforeScreenshot = pendingBeforeScreenshot,
            startedAtMs = sameNodePending?.startedAtMs ?: eventWallTime(event.eventTime, now),
            updatedAtMs = now,
            eventContext = eventContextFor(event, target, node)
        )
        lastXmlSnapshot = captureCurrentXml()
        lastScreenshotSnapshot = captureCurrentScreenshotRef("text_changed_after")
    }

    private fun textReplayTargetFromBeforeXml(
        sourceTarget: ManualEventTarget,
        beforeXml: String?,
        fallbackPackageName: String?,
        inputText: String
    ): ManualEventTarget {
        val beforeTarget = targetAtCoordinateFromXml(
            xml = beforeXml,
            x = sourceTarget.bounds.centerX().toFloat(),
            y = sourceTarget.bounds.centerY().toFloat(),
            fallbackPackageName = fallbackPackageName,
            preferScrollable = false
        ) ?: return sourceTarget
        if (!isTextInputClass(beforeTarget.className)) return sourceTarget
        val beforeLabel = beforeTarget.label.trim()
        val stableLabel = beforeLabel.takeIf {
            it.isNotBlank() && it != inputText && it != REDACTED_TEXT
        } ?: sourceTarget.label
        return sourceTarget.copy(
            label = stableLabel,
            resourceId = sourceTarget.resourceId ?: beforeTarget.resourceId,
            text = beforeTarget.text ?: sourceTarget.text,
            contentDescription = beforeTarget.contentDescription ?: sourceTarget.contentDescription,
            stableKey = sourceTarget.stableKey.ifBlank { beforeTarget.stableKey },
            resolution = "${sourceTarget.resolution}+before_xml_text_target"
        )
    }

    private fun flushPendingText(afterXmlOverride: String? = lastXmlSnapshot) {
        val pending = pendingText ?: return
        pendingText = null
        val title = if (pending.text == REDACTED_TEXT) {
            "人工输入敏感文本"
        } else {
            "人工输入文本"
        }
        val afterXml = afterXmlOverride ?: captureCurrentXml()
        val afterScreenshot = captureCurrentScreenshotRef("text_after")
        lastXmlSnapshot = afterXml
        lastScreenshotSnapshot = afterScreenshot ?: pending.beforeScreenshot
        val params = linkedMapOf<String, Any?>(
            "target_description" to pending.label,
            "content" to pending.text,
            "text" to pending.text,
            "x" to pending.bounds.centerX().toFloat(),
            "y" to pending.bounds.centerY().toFloat(),
            "bounds" to boundsString(pending.bounds),
            "node_class" to pending.className,
            "node_resource_id" to pending.resourceId,
            "recording_backend" to "accessibility_event",
            "target_resolution" to pending.resolution
        ).filterValues { it != null }
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = "input_text",
            title = title,
            params = params,
            packageName = pending.packageName,
            beforeXml = pending.beforeXml,
            afterXml = afterXml,
            beforeScreenshot = pending.beforeScreenshot,
            afterScreenshot = afterScreenshot,
            startedAtMs = pending.startedAtMs,
            finishedAtMs = pending.updatedAtMs,
            summary = if (pending.text == REDACTED_TEXT) title else "$title：${pending.text.take(MAX_SUMMARY_TEXT)}",
            eventContext = pending.eventContext
        ))
    }

    private fun recordScrolled(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?,
        beforeScreenshot: ManualVlmScreenshotRef?
    ) {
        flushPendingText(lastXmlSnapshot)
        val sourceNode = event.source
        val current = ManualScrollSnapshot(
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            scrollDeltaX = event.scrollDeltaX,
            scrollDeltaY = event.scrollDeltaY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount
        )
        if (!current.hasViewportSignal()) {
            OmniLog.d(TAG, "manual trace ignored scroll without movement signal")
            return
        }
        val eventLabel = event.text.joinToString(" ").trim()
        val target = sourceNode
            ?.let { source -> targetFromSourceNode(source, packageName) }
            ?: targetFromXml(
                xml = beforeXml ?: captureCurrentXml(),
                packageName = packageName,
                eventLabel = eventLabel,
                eventClassName = event.className?.toString(),
                preferScrollable = true
            )
            ?: return
        val bounds = target.bounds
        if (bounds.isEmpty) return

        val key = target.stableKey
        val previous = nodeScrollState[key]
        nodeScrollState[key] = current

        val inferredDirection = ManualScrollEventPolicy.inferDirection(previous, current)
        if (inferredDirection == null) {
            OmniLog.d(TAG, "manual trace ignored scroll viewport baseline without direction signal")
            return
        }
        val direction = inferredDirection
        val now = System.currentTimeMillis()
        val pendingBeforeXml = pendingScroll?.takeIf { it.nodeKey == key }?.beforeXml ?: beforeXml
        val pendingBeforeScreenshot = pendingScroll?.takeIf { it.nodeKey == key }?.beforeScreenshot
            ?: beforeScreenshot
        pendingScroll = PendingScrollAction(
            nodeKey = key,
            packageName = target.packageName ?: packageName,
            label = target.label.ifBlank { "列表" },
            bounds = bounds,
            direction = direction,
            className = target.className,
            resourceId = target.resourceId,
            resolution = target.resolution,
            beforeXml = pendingBeforeXml,
            beforeScreenshot = pendingBeforeScreenshot,
            startedAtMs = pendingScroll?.takeIf { it.nodeKey == key }?.startedAtMs
                ?: eventWallTime(event.eventTime, now),
            updatedAtMs = now,
            eventContext = eventContextFor(event, target, sourceNode)
        )
        lastXmlSnapshot = captureCurrentXml()
        lastScreenshotSnapshot = captureCurrentScreenshotRef("scroll_after")
    }

    private fun flushPendingScroll(afterXmlOverride: String? = lastXmlSnapshot) {
        val pending = pendingScroll ?: return
        pendingScroll = null
        val swipe = swipeFromBounds(pending.bounds, pending.direction)
        val title = "人工滑动 ${pending.label}"
        val afterXml = afterXmlOverride ?: captureCurrentXml()
        val afterScreenshot = captureCurrentScreenshotRef("scroll_flush_after")
        lastXmlSnapshot = afterXml
        lastScreenshotSnapshot = afterScreenshot ?: pending.beforeScreenshot
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = "swipe",
            title = title,
            params = linkedMapOf(
                "target_description" to pending.label,
                "x1" to swipe.x1,
                "y1" to swipe.y1,
                "x2" to swipe.x2,
                "y2" to swipe.y2,
                "duration_ms" to 500L,
                "direction" to pending.direction.name.lowercase(),
                "bounds" to boundsString(pending.bounds),
                "node_class" to pending.className,
                "node_resource_id" to pending.resourceId,
                "recording_backend" to "accessibility_event",
                "target_resolution" to pending.resolution
            ),
            packageName = pending.packageName,
            beforeXml = pending.beforeXml,
            afterXml = afterXml,
            beforeScreenshot = pending.beforeScreenshot,
            afterScreenshot = afterScreenshot,
            startedAtMs = pending.startedAtMs,
            finishedAtMs = pending.updatedAtMs,
            summary = title,
            eventContext = pending.eventContext
        ))
    }

    private fun swipeFromBounds(bounds: Rect, direction: ManualScrollDirection): SwipeParams {
        val horizontalInset = max(24, bounds.width() / 5)
        val verticalInset = max(24, bounds.height() / 5)
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val left = (bounds.left + horizontalInset).toFloat()
        val right = (bounds.right - horizontalInset).toFloat()
        val top = (bounds.top + verticalInset).toFloat()
        val bottom = (bounds.bottom - verticalInset).toFloat()
        return when (direction) {
            ManualScrollDirection.UP -> SwipeParams(cx, bottom, cx, top)
            ManualScrollDirection.DOWN -> SwipeParams(cx, top, cx, bottom)
            ManualScrollDirection.LEFT -> SwipeParams(right, cy, left, cy)
            ManualScrollDirection.RIGHT -> SwipeParams(left, cy, right, cy)
        }
    }

    private fun shouldIgnorePackage(packageName: String?): Boolean {
        val normalized = packageName?.trim().orEmpty()
        return normalized.isEmpty() || normalized == ownPackageName
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

    private fun isTextInputNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEditable ||
            isTextInputClass(className)
    }

    private fun isTextInputClass(className: String?): Boolean {
        val normalized = className.orEmpty()
        return normalized.contains("EditText", ignoreCase = true) ||
            normalized.contains("TextInput", ignoreCase = true)
    }

    private fun targetFromXml(
        xml: String?,
        packageName: String?,
        eventLabel: String,
        eventClassName: String?,
        preferScrollable: Boolean
    ): ManualEventTarget? {
        val nodes = parseXmlNodeCandidates(xml)
            .filter { candidate ->
                !candidate.bounds.isEmpty &&
                    !shouldIgnoreTarget(
                        packageName = candidate.packageName ?: packageName,
                        label = candidate.bestLabel,
                        resourceId = candidate.resourceId
                    )
            }
        if (nodes.isEmpty()) return rootTargetFromXml(xml, packageName, preferScrollable)
        val labelTokens = meaningfulTokens(eventLabel)
        if (!preferScrollable && labelTokens.isEmpty() && eventClassName.isNullOrBlank()) {
            return null
        }
        val best = nodes
            .mapNotNull { candidate ->
                val score = candidate.matchScore(
                    packageName = packageName,
                    labelTokens = labelTokens,
                    eventClassName = eventClassName,
                    preferScrollable = preferScrollable
                )
                if (score <= 0 && !preferScrollable) null else score to candidate
            }
            .maxWithOrNull(
                compareBy<Pair<Int, XmlNodeCandidate>> { it.first }
                    .thenBy { it.second.bounds.width() * it.second.bounds.height() }
            )
            ?.second
            ?: return rootTargetFromXml(xml, packageName, preferScrollable)
        return best.toManualTarget(packageName, "xml_fallback")
    }

    private fun targetFromXmlClick(
        beforeXml: String?,
        afterXml: String?,
        packageName: String?,
        eventLabel: String,
        eventClassName: String?
    ): ClickGroundingDecision? {
        val result = ManualClickGrounding.inferClickTarget(
            beforeXml = beforeXml,
            afterXml = afterXml,
            packageName = packageName,
            eventLabel = eventLabel,
            eventClassName = eventClassName,
            ignoredPackageName = ownPackageName,
            ignoredTextHints = OOB_CONTROL_HINTS
        )
        if (result.target == null) {
            OmniLog.d(TAG, "manual trace source-less click not replayable: ${result.inference}")
            return null
        }
        val target = result.target.toManualTarget(packageName, result.resolution)
        return ClickGroundingDecision(
            target = target,
            inference = result.inference
        )
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

    private fun ManualClickGrounding.Target.toManualTarget(
        fallbackPackageName: String?,
        resolution: String
    ): ManualEventTarget {
        val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        return ManualEventTarget(
            label = label,
            bounds = rect,
            packageName = packageName ?: fallbackPackageName,
            className = className,
            resourceId = resourceId,
            text = text,
            contentDescription = contentDescription,
            stableKey = firstNonBlank(resourceId, className) + "|" + boundsString(rect),
            resolution = resolution
        )
    }

    private fun rootTargetFromXml(
        xml: String?,
        packageName: String?,
        allow: Boolean
    ): ManualEventTarget? {
        if (!allow) return null
        val bounds = parseRootBounds(xml) ?: return null
        if (bounds.isEmpty) return null
        return ManualEventTarget(
            label = "当前页面",
            bounds = bounds,
            packageName = packageName,
            className = "hierarchy",
            resourceId = null,
            text = null,
            contentDescription = null,
            stableKey = "hierarchy|${boundsString(bounds)}",
            resolution = "xml_root_fallback"
        )
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

    private fun meaningfulTokens(value: String): List<String> {
        val normalized = value.replace(Regex("\\s+"), " ").trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        val asciiTokens = normalized
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
        return if (asciiTokens.isNotEmpty()) asciiTokens else listOf(normalized)
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
            AccessibilityController.getCaptureScreenShotXml(true)
        } catch (e: Exception) {
            OmniLog.w(TAG, "manual trace xml capture failed: ${e.message}")
            null
        }
    }

    private fun captureCurrentScreenshotRef(stage: String): ManualVlmScreenshotRef? {
        val capturedAtMs = System.currentTimeMillis()
        return runCatching {
            AccessibilityController.initController()
            val capture = runBlocking {
                AccessibilityController.captureScreenshotImage(
                    isBitmap = true,
                    isBase64 = false,
                    isFile = false,
                    isFilterOverlay = true,
                    compressQuality = ImageQuality.SUMMARY
                )
            }
            val bitmap = capture.imageBitmap ?: return null
            try {
                saveScreenshotRef(
                    bitmap = bitmap,
                    stage = stage,
                    capturedAtMs = capturedAtMs
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }.getOrElse { error ->
            screenshotFailedCount += 1
            OmniLog.w(TAG, "manual trace screenshot capture failed: ${error.message}")
            null
        }
    }

    private fun saveScreenshotRef(
        bitmap: Bitmap,
        stage: String,
        capturedAtMs: Long
    ): ManualVlmScreenshotRef {
        val safeSession = safePathSegment(sessionLabel)
        val safeStage = safePathSegment(stage)
        val dir = File(context.filesDir, "oob_runlog_artifacts/$safeSession/screenshots")
        dir.mkdirs()
        val seq = (++screenshotSeq).toString().padStart(4, '0')
        val file = File(dir, "${seq}_${safeStage}.jpg")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, stream)
        }
        val bytes = file.readBytes()
        screenshotStoredCount += 1
        val relativePath = file.relativeToOrSelf(context.filesDir).path
        return ManualVlmScreenshotRef(
            path = file.absolutePath,
            relativePath = relativePath,
            mimeType = "image/jpeg",
            width = bitmap.width,
            height = bitmap.height,
            bytes = file.length(),
            sha256 = sha256(bytes),
            capturedAtMs = capturedAtMs,
            captureStage = stage
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun buildSummary(actions: List<ManualVlmRecordedAction>): String {
        if (actions.isEmpty()) return ""
        val actionSummary = actions.take(MAX_SUMMARY_ACTIONS).joinToString("；") { action ->
            action.summary.ifBlank { action.title }
        }
        val suffix = if (actions.size > MAX_SUMMARY_ACTIONS) "；..." else ""
        return "用户在接管期间手动完成了 ${actions.size} 步操作：$actionSummary$suffix。请基于当前屏幕继续执行原任务。"
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

    private fun rawSwipeDirection(gesture: ManualRawTouchGesture): String {
        val dx = gesture.endX - gesture.startX
        val dy = gesture.endY - gesture.startY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun buildDiagnostics(): Map<String, Any?> {
        val rawActions = recordedActions.count {
            it.params["recording_backend"]?.toString() == "device_getevent"
        }
        val semanticActions = recordedActions.size - rawActions
        val rawTouchAvailable = rawTouchStatus?.available == true
        val completeness = ManualRecordingDiagnostics.completeness(
            rawTouchAvailable = rawTouchAvailable,
            rawTouchActiveAtStop = rawTouchActiveAtStop
        )
        val guaranteesNoMissingClicks = ManualRecordingDiagnostics.guaranteesNoMissingClicks(
            rawTouchAvailable = rawTouchAvailable,
            rawTouchActiveAtStop = rawTouchActiveAtStop
        )
        val warningMessage = if (enableRawTouch) {
            ManualRecordingDiagnostics.warningMessage(completeness)
        } else {
            null
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
                "storage" to "app_private_files",
                "reference_style" to "path_only",
                "stored_count" to screenshotStoredCount,
                "failed_count" to screenshotFailedCount,
                "root_relative_path" to "oob_runlog_artifacts/${safePathSegment(sessionLabel)}/screenshots"
            ),
            "accessibility_events" to linkedMapOf(
                "event_count" to accessibilityEventCount,
                "ignored_package_event_count" to accessibilityIgnoredPackageCount,
                "event_type_counts" to accessibilityEventTypeCounts.toMap(),
                "suppressed_semantic_action_event_count" to suppressedSemanticActionEventCount,
                "suppressed_non_raw_action_count" to suppressedNonRawActionCount,
                "records_replayable_actions" to true,
                "recorded_action_count" to semanticActions
            ),
            "unattributed_window_transitions" to linkedMapOf(
                "event_count" to windowTransitionEventCount,
                "count" to unattributedWindowTransitionCount,
                "samples" to unattributedWindowTransitionSamples.takeIf { it.isNotEmpty() },
                "replayable" to false,
                "reason" to "window/content changes are evidence only unless a concrete A11 or raw action identifies the gesture"
            ),
            "manual_recording" to linkedMapOf(
                "action_source" to when {
                    rawActions > 0 && semanticActions > 0 -> "mixed"
                    rawActions > 0 -> "raw_touch"
                    else -> "accessibility_event"
                },
                "raw_touch_enabled" to enableRawTouch,
                "raw_touch_required" to false,
                "a11_replay_actions_enabled" to true,
                "action_count" to recordedActions.size,
                "raw_action_count" to rawActions,
                "semantic_action_count" to semanticActions,
                "suppressed_non_raw_action_count" to suppressedNonRawActionCount,
                "completeness" to completeness,
                "missing_raw_touch" to (enableRawTouch && !rawTouchAvailable),
                "raw_touch_active_at_stop" to rawTouchActiveAtStop,
                "guarantees_no_missing_clicks" to guaranteesNoMissingClicks,
                "unattributed_window_transition_count" to unattributedWindowTransitionCount,
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
        val packageName: String?,
        val label: String,
        val text: String,
        val bounds: Rect,
        val className: String?,
        val resourceId: String?,
        val resolution: String,
        val beforeXml: String?,
        val beforeScreenshot: ManualVlmScreenshotRef?,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val eventContext: Map<String, Any?>
    )

    private data class PendingScrollAction(
        val nodeKey: String,
        val packageName: String?,
        val label: String,
        val bounds: Rect,
        val direction: ManualScrollDirection,
        val className: String?,
        val resourceId: String?,
        val resolution: String,
        val beforeXml: String?,
        val beforeScreenshot: ManualVlmScreenshotRef?,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val eventContext: Map<String, Any?>
    )

    private data class SwipeParams(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

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

    private data class ClickGroundingDecision(
        val target: ManualEventTarget,
        val inference: Map<String, Any?>
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

        fun matchScore(
            packageName: String?,
            labelTokens: List<String>,
            eventClassName: String?,
            preferScrollable: Boolean
        ): Int {
            var score = 0
            if (visible) score += 2
            if (enabled) score += 2
            if (!packageName.isNullOrBlank() && packageName == this.packageName) score += 8
            if (preferScrollable && scrollable) score += 40
            if (!preferScrollable && clickable) score += 12
            if (!eventClassName.isNullOrBlank() && eventClassName == className) score += 8
            val haystack = listOfNotNull(text, contentDescription, hintText, resourceId, className)
                .joinToString(" ")
                .lowercase()
            for (token in labelTokens) {
                if (haystack == token) score += 35
                if (haystack.contains(token)) score += 25
            }
            return score
        }

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
        private const val RAW_TOUCH_BACKEND = "device_getevent"
        private const val RAW_TOUCH_SETTLE_MS = 350L
        private const val SCREENSHOT_JPEG_QUALITY = 90
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
