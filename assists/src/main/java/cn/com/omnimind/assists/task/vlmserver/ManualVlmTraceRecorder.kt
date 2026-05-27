package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.OmniLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ManualVlmTraceResult(
    val actions: List<ManualVlmRecordedAction>,
    val summary: String
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
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val summary: String
)

/**
 * Records user actions during VLM takeover using Accessibility semantic events.
 *
 * This intentionally does not subscribe to raw MotionEvent streams: raw touch
 * capture can interfere with normal dispatch on newer Android versions, while
 * semantic events preserve the "user takes over naturally" interaction model.
 */
class ManualVlmTraceRecorder(
    private val context: Context,
    private val sessionLabel: String
) {
    private val ownPackageName = context.packageName
    private val recordedActions = mutableListOf<ManualVlmRecordedAction>()
    private val nodeScrollState = mutableMapOf<String, ScrollSnapshot>()
    private var pendingText: PendingTextAction? = null
    private var pendingScroll: PendingScrollAction? = null
    private var lastDiscreteSignature: String = ""
    private var lastDiscreteAtMs: Long = 0L
    private var lastXmlSnapshot: String? = null
    private var isStarted = false

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
        lastXmlSnapshot = captureCurrentXml()
        AssistsService.addListener(listener)
        OmniLog.d(TAG, "manual trace recorder started: $sessionLabel")
        return true
    }

    fun stop(): ManualVlmTraceResult {
        if (isStarted) {
            AssistsService.removeListener(listener)
            isStarted = false
        }
        flushPendingText(lastXmlSnapshot)
        flushPendingScroll(lastXmlSnapshot)
        val summary = buildSummary(recordedActions)
        OmniLog.d(TAG, "manual trace recorder stopped: $sessionLabel actions=${recordedActions.size}")
        return ManualVlmTraceResult(
            actions = recordedActions.toList(),
            summary = summary
        )
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        if (!isStarted) return
        val packageName = event.packageName?.toString()
        if (shouldIgnorePackage(packageName)) return

        val beforeXml = lastXmlSnapshot ?: captureCurrentXml()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastXmlSnapshot = captureCurrentXml()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordTextChanged(event, packageName, beforeXml)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScrolled(event, packageName, beforeXml)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(event, packageName, "click", beforeXml)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordClick(
                event,
                packageName,
                "long_press",
                beforeXml
            )
            else -> Unit
        }
    }

    private fun recordClick(
        event: AccessibilityEvent,
        packageName: String?,
        actionName: String,
        beforeXml: String?
    ) {
        flushPendingText(lastXmlSnapshot)
        flushPendingScroll(lastXmlSnapshot)
        val eventLabel = event.text.joinToString(" ").trim()
        val eventClassName = event.className?.toString()
        val target = event.source
            ?.let { source -> targetFromSourceNode(source, packageName) }
            ?: targetFromXml(
                xml = beforeXml ?: captureCurrentXml(),
                packageName = packageName,
                eventLabel = eventLabel,
                eventClassName = eventClassName,
                preferScrollable = false
            )
            ?: return
        val bounds = target.bounds
        if (bounds.isEmpty) return

        val now = System.currentTimeMillis()
        val label = target.label.ifBlank { eventLabel }
        val targetPackage = target.packageName ?: packageName
        val recordedActionName = navigationActionFor(targetPackage, label) ?: actionName
        val signature = "$recordedActionName|$targetPackage|${target.stableKey}"
        if (signature == lastDiscreteSignature && now - lastDiscreteAtMs < DUPLICATE_EVENT_WINDOW_MS) {
            return
        }
        lastDiscreteSignature = signature
        lastDiscreteAtMs = now

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
                "target_resolution" to target.resolution
            )
        }
        if (recordedActionName == "long_press") {
            params["duration_ms"] = 1000L
        }
        val afterXml = captureCurrentXml()
        lastXmlSnapshot = afterXml
        appendRecordedAction(ManualVlmRecordedAction(
            actionName = recordedActionName,
            title = title,
            params = params,
            packageName = targetPackage,
            beforeXml = beforeXml,
            afterXml = afterXml,
            startedAtMs = eventWallTime(event.eventTime, now),
            finishedAtMs = now,
            summary = title
        ))
    }

    private fun recordTextChanged(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?
    ) {
        val node = event.source ?: return
        val target = targetFromSourceNode(node, packageName) ?: return
        val bounds = target.bounds
        val key = target.stableKey
        val now = System.currentTimeMillis()
        val pendingBeforeXml = pendingText?.takeIf { it.nodeKey == key }?.beforeXml ?: beforeXml
        val text = event.text.joinToString("").ifBlank { node.text?.toString().orEmpty() }
        val safeText = if (node.isPassword) REDACTED_TEXT else text
        if (safeText.isBlank()) return

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
            startedAtMs = pendingText?.takeIf { it.nodeKey == key }?.startedAtMs
                ?: eventWallTime(event.eventTime, now),
            updatedAtMs = now
        )
        lastXmlSnapshot = captureCurrentXml()
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
        lastXmlSnapshot = afterXml
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
            startedAtMs = pending.startedAtMs,
            finishedAtMs = pending.updatedAtMs,
            summary = if (pending.text == REDACTED_TEXT) title else "$title：${pending.text.take(MAX_SUMMARY_TEXT)}"
        ))
    }

    private fun recordScrolled(
        event: AccessibilityEvent,
        packageName: String?,
        beforeXml: String?
    ) {
        flushPendingText(lastXmlSnapshot)
        val eventLabel = event.text.joinToString(" ").trim()
        val target = event.source
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
        val current = ScrollSnapshot(
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            scrollDeltaX = event.scrollDeltaX,
            scrollDeltaY = event.scrollDeltaY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount
        )
        nodeScrollState[key] = current

        val direction = inferScrollDirection(previous, current) ?: fallbackScrollDirection(bounds)
        val now = System.currentTimeMillis()
        val pendingBeforeXml = pendingScroll?.takeIf { it.nodeKey == key }?.beforeXml ?: beforeXml
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
            startedAtMs = pendingScroll?.takeIf { it.nodeKey == key }?.startedAtMs
                ?: eventWallTime(event.eventTime, now),
            updatedAtMs = now
        )
        lastXmlSnapshot = captureCurrentXml()
    }

    private fun flushPendingScroll(afterXmlOverride: String? = lastXmlSnapshot) {
        val pending = pendingScroll ?: return
        pendingScroll = null
        val swipe = swipeFromBounds(pending.bounds, pending.direction)
        val title = "人工滑动 ${pending.label}"
        val afterXml = afterXmlOverride ?: captureCurrentXml()
        lastXmlSnapshot = afterXml
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
            startedAtMs = pending.startedAtMs,
            finishedAtMs = pending.updatedAtMs,
            summary = title
        ))
    }

    private fun inferScrollDirection(
        previous: ScrollSnapshot?,
        current: ScrollSnapshot
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
        if (current.fromIndex > 0 || current.toIndex > 0) {
            return ManualScrollDirection.UP
        }
        return null
    }

    private fun fallbackScrollDirection(bounds: Rect): ManualScrollDirection {
        return if (bounds.height() >= bounds.width()) {
            ManualScrollDirection.UP
        } else {
            ManualScrollDirection.LEFT
        }
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
        val startedAtMs: Long,
        val updatedAtMs: Long
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
        val startedAtMs: Long,
        val updatedAtMs: Long
    )

    private data class ScrollSnapshot(
        val scrollX: Int,
        val scrollY: Int,
        val scrollDeltaX: Int,
        val scrollDeltaY: Int,
        val fromIndex: Int,
        val toIndex: Int,
        val itemCount: Int
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

    private enum class ManualScrollDirection {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    private companion object {
        private const val TAG = "ManualVlmTraceRecorder"
        private const val DUPLICATE_EVENT_WINDOW_MS = 400L
        private const val MIN_SCROLL_DELTA = 4
        private const val MAX_LABEL_LENGTH = 80
        private const val MAX_SUMMARY_TEXT = 40
        private const val MAX_SUMMARY_ACTIONS = 8
        private const val MAX_ACTIONABLE_ANCESTOR_DEPTH = 4
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
    }
}
