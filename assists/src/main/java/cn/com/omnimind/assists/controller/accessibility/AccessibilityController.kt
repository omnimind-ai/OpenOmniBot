package cn.com.omnimind.assists.controller.accessibility

import BaseApplication
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.action.AccessibilityNodeScrollDirection
import cn.com.omnimind.accessibility.action.AccessibilityScrollDirection
import cn.com.omnimind.accessibility.action.AccessibilityNode
import cn.com.omnimind.accessibility.action.OmniAction
import cn.com.omnimind.accessibility.action.OmniCaptureAction
import cn.com.omnimind.accessibility.action.OmniScreenshotAction
import cn.com.omnimind.accessibility.action.ScreenCaptureManager
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.service.AssistsServiceListener
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.api.bean.CaptureData
import cn.com.omnimind.assists.detection.scenarios.stability.PageStabilityDetector
import cn.com.omnimind.assists.detection.state.SystemNotificationStateManager
import cn.com.omnimind.baselib.util.ImageCompressor
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.ImageUtils
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PermissionException
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.omniintelligence.models.HostResponse
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 控制器辅助类
 */
class AccessibilityController() {

    companion object {
        const val TAG = "[ControllerHelper]"
        private var actionController: OmniAction? = null;
        private var captureAction: OmniCaptureAction? = null;
        private var service: AssistsService? = null;


        private var screenshotAction: OmniScreenshotAction? = null
        private var accessibilityEventListenerRegistered = false

        /**
         * 初始化控制器
         * 需注意初始化时机,保证AssistsService以运行后初始化
         */
        fun initController(): Boolean {
            val currentService = AssistsService.instance ?: run {
                destroy()
                return false
            }
            if (service === currentService &&
                actionController != null &&
                captureAction != null &&
                screenshotAction != null
            ) {
                return true
            }
            this.service = currentService
            actionController = OmniAction(currentService)
            captureAction = OmniCaptureAction(currentService)
            screenshotAction = OmniScreenshotAction(currentService)
            if (!accessibilityEventListenerRegistered) {
                AssistsService.addListener(object : AssistsServiceListener {
                    override fun onAccessibilityEvent(event: AccessibilityEvent) {
                        captureAction?.onAccessibilityEvent(event)

                        // 将事件传递给系统通知状态管理器处理
                        SystemNotificationStateManager.handleAccessibilityEvent(event)

                    }

                    override fun onUnbind() {
                        destroy()
                    }
                })
                accessibilityEventListenerRegistered = true
            }

            return true
        }

        fun hideKeyboard() {
            service?.hideKeyboard()
        }

        fun restoreKeyboard() {
            service?.restoreKeyboard()
        }

        private fun checkAccessibilityPermissions() {
            if (!AssistsCore.isAccessibilityServiceEnabled()) {
                throw PermissionException("无障碍服务未启用或权限未授予!")
            }
            if (!initController() || actionController == null) {
                throw IllegalStateException("Accessibility action controller is not ready")
            }
        }

        //
        suspend fun inputText(
            nodeId: String, text: String
        ) {
            val node = captureAction?.getNodeMap()?.get(nodeId)?.info
                ?: throw IllegalArgumentException("Node with ID '$nodeId' not found.")
            actionController?.inputText(node, text)
        }

        suspend fun inputTextToFocusedNode(text: String) {
            val focusedNode =
                captureAction?.getNodeMap()?.values?.firstOrNull { it.info.isFocused }?.info
                    ?: throw NoFocusedNodeException()
            actionController?.inputText(focusedNode, text)
        }

        suspend fun inputTextToBestNode(
            text: String,
            targetDescription: String = "",
            x: Float? = null,
            y: Float? = null,
            nodeResourceId: String = "",
        ) {
            checkAccessibilityPermissions()
            val errors = mutableListOf<String>()

            // Try direct node focus before any click. The editable node is visible in
            // the main window before the keyboard opens; once a click triggers the IME,
            // the node migrates to the IME window and disappears from getNodeMap().
            val preClickNode = withTimeout(INPUT_TARGET_LOOKUP_TIMEOUT_MS) {
                findEditableInputCandidate(
                    targetDescription = targetDescription,
                    nodeResourceId = nodeResourceId,
                    x = x,
                    y = y,
                )
            }
            if (preClickNode != null) {
                runCatching {
                    inputTextIntoNode(preClickNode, text)
                }.onSuccess {
                    return
                }.onFailure { error ->
                    errors += "pre_click_direct_input_failed=${error.message.orEmpty()}"
                }
            }

            if (x != null && y != null) {
                val clicked = runCatching {
                    clickCoordinate(x, y)
                    delay(INPUT_FOCUS_SETTLE_MS)
                }.onFailure { error ->
                    errors += "coordinate_focus_failed=${error.message.orEmpty()}"
                }.isSuccess

                if (clicked) {
                    runCatching {
                        inputTextToFocusedNode(text)
                    }.onSuccess {
                        return
                    }.onFailure { error ->
                        errors += "focused_input_after_click_failed=${error.message.orEmpty()}"
                    }

                    val postClickNode = withTimeout(INPUT_TARGET_LOOKUP_TIMEOUT_MS) {
                        findEditableInputCandidate(
                            targetDescription = targetDescription,
                            nodeResourceId = nodeResourceId,
                            x = x,
                            y = y
                        )
                    }
                    if (postClickNode != null) {
                        runCatching {
                            inputTextIntoNode(postClickNode, text)
                        }.onSuccess {
                            return
                        }.onFailure { error ->
                            errors += "candidate_input_after_click_failed=${error.message.orEmpty()}"
                        }
                    } else {
                        errors += "candidate_after_click_missing"
                    }
                }
            }

            val directNode = withTimeout(INPUT_TARGET_LOOKUP_TIMEOUT_MS) {
                findEditableInputCandidate(
                    targetDescription = targetDescription,
                    nodeResourceId = nodeResourceId,
                    x = x,
                    y = y
                )
            }
            if (directNode != null) {
                runCatching {
                    inputTextIntoNode(directNode, text)
                }.onSuccess {
                    return
                }.onFailure { error ->
                    errors += "direct_candidate_input_failed=${error.message.orEmpty()}"
                }
            }

            if (targetDescription.isBlank() && nodeResourceId.isBlank() && x == null && y == null) {
                inputTextToFocusedNode(text)
                return
            }
            throw NoFocusedNodeException(
                "No editable input target found for replay text action: " +
                    targetDescription.ifBlank { nodeResourceId.ifBlank { "x=$x y=$y" } } +
                    errors.takeIf { it.isNotEmpty() }?.joinToString(
                        prefix = " (",
                        postfix = ")"
                    ).orEmpty()
            )
        }

        suspend fun pressHotKey(key: String) {
            when (key.trim().uppercase()) {
                "ENTER" -> {
                    val focusedNode =
                        captureAction?.getNodeMap()?.values?.firstOrNull { it.info.isFocused }?.info
                            ?: throw NoFocusedNodeException()
                    actionController?.performImeEnter(focusedNode)
                        ?: throw IllegalStateException("Accessibility action controller is not ready")
                }

                "BACK" -> goBack()
                "HOME" -> goHome()
                else -> throw IllegalArgumentException("Unsupported hot key: $key")
            }
        }

        // 剪贴板回调
        private var clipboardCopyCallback: ((Boolean) -> Unit)? = null

        /**
         * 供 ClipboardHelperActivity 调用，通知复制结果
         */
        @JvmStatic
        fun notifyClipboardCopyResult(success: Boolean) {
            clipboardCopyCallback?.invoke(success)
            clipboardCopyCallback = null
        }

        /**
         * 复制文本到剪贴板
         * 使用 ClipboardHelperActivity 确保 Android 10+ 上的稳定性
         */
        suspend fun copyToClipboard(text: String) {
            val success = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    clipboardCopyCallback = { result ->
                        if (continuation.isActive) continuation.resume(result, null)
                    }
                    try {
                        val intent = android.content.Intent().apply {
                            setClassName(
                                BaseApplication.instance.packageName,
                                "cn.com.omnimind.bot.activity.ClipboardHelperActivity"
                            )
                            putExtra("clipboard_text", text)
                            putExtra("clipboard_operation", "copy")
                            putExtra("callback_target", "AccessibilityController")
                            addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            )
                        }
                        BaseApplication.instance.startActivity(intent)
                    } catch (e: Exception) {
                        clipboardCopyCallback = null
                        OmniLog.e(TAG, "copyToClipboard failed to start activity: ${e.message}")
                        if (continuation.isActive) continuation.resume(false, null)
                    }
                }
            } ?: false

            if (!success) {
                throw IllegalStateException("Failed to copy to clipboard")
            }
        }


        suspend fun clickCoordinate(
            x: Float,
            y: Float,
            timeoutMs: Long = 2000L
        ) {
            checkAccessibilityPermissions()
            withTimeout(timeoutMs) {
                val controller = actionController
                    ?: throw IllegalStateException("Accessibility action controller is not ready")
                controller.clickCoordinate(x, y).await()
            }
        }

        suspend fun clickNodeById(
            nodeId: String,
            targetDescription: String = ""
        ) {
            checkAccessibilityPermissions()
            withTimeout(2000) {
                val node = findNodeById(nodeId)
                    ?: throw IllegalArgumentException("Node with ID '$nodeId' not found.")
                val clickableNode = findActionableAncestor(
                    node = node.info,
                    supportsAction = { it.isEnabled && it.isClickable }
                ) ?: throw IllegalStateException(
                    "Node '$nodeId' is not clickable: ${targetDescription.ifBlank { nodeSemanticLabel(node.info) }}"
                )
                val controller = actionController
                    ?: throw IllegalStateException("Accessibility action controller is not ready")
                controller.clickNode(clickableNode)
            }
        }

        suspend fun clickNodeAtCoordinate(
            x: Float,
            y: Float,
            targetDescription: String = ""
        ) {
            checkAccessibilityPermissions()
            withTimeout(1200) {
                val clickableNode = findClickableNodeAtCoordinate(x, y)
                    ?: throw IllegalArgumentException(
                        "No clickable node at coordinate ${x.toInt()},${y.toInt()}: $targetDescription"
                    )
                val controller = actionController
                    ?: throw IllegalStateException("Accessibility action controller is not ready")
                controller.clickNode(clickableNode)
            }
        }

        suspend fun longClickCoordinate(
            x: Float, y: Float, duration: Long = 1000L
        ) {
            checkAccessibilityPermissions()
            withTimeout(2000 + duration) {
                val controller = actionController
                    ?: throw IllegalStateException("Accessibility action controller is not ready")
                controller.longClickCoordinate(x, y, duration).await()
            }
        }

        suspend fun longClickNodeById(
            nodeId: String,
            targetDescription: String = ""
        ) {
            checkAccessibilityPermissions()
            withTimeout(3000) {
                val node = findNodeById(nodeId)
                    ?: throw IllegalArgumentException("Node with ID '$nodeId' not found.")
                val longClickableNode = findActionableAncestor(
                    node = node.info,
                    supportsAction = { it.isEnabled && it.isLongClickable }
                ) ?: throw IllegalStateException(
                    "Node '$nodeId' is not long-clickable: ${targetDescription.ifBlank { nodeSemanticLabel(node.info) }}"
                )
                val controller = actionController
                    ?: throw IllegalStateException("Accessibility action controller is not ready")
                controller.longClickNode(longClickableNode)
            }
        }

        suspend fun inputTextToNodeById(
            nodeId: String,
            text: String,
            targetDescription: String = ""
        ) {
            checkAccessibilityPermissions()
            withTimeout(3000) {
                val node = findNodeById(nodeId)
                    ?: throw IllegalArgumentException("Node with ID '$nodeId' not found.")
                val editableNode = findActionableAncestor(
                    node = node.info,
                    supportsAction = { it.isEnabled && it.isEditable }
                ) ?: throw IllegalStateException(
                    "Node '$nodeId' is not editable: ${targetDescription.ifBlank { nodeSemanticLabel(node.info) }}"
                )
                actionController?.inputText(editableNode, text)
                    ?: throw IllegalStateException("Accessibility action controller is not ready")
            }
        }

        suspend fun scrollCoordinate(
            x: Float, y: Float, direction: ScrollDirection, distance: Float, duration: Long = 500L
        ) {
            var mDirection = when (direction) {
                ScrollDirection.UP -> {
                    AccessibilityScrollDirection.UP
                }

                ScrollDirection.DOWN -> {
                    AccessibilityScrollDirection.DOWN
                }

                ScrollDirection.LEFT -> {
                    AccessibilityScrollDirection.LEFT
                }

                ScrollDirection.RIGHT -> {
                    AccessibilityScrollDirection.RIGHT
                }
            }
            actionController?.scrollCoordinate(x, y, mDirection, distance, duration)?.await()
        }

        suspend fun setSliderProgressFromGesture(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            targetDescription: String = ""
        ): Boolean {
            checkAccessibilityPermissions()
            if (!isHorizontalEndpointGesture(x1, y1, x2, y2)) {
                return false
            }
            val nodes = captureAction?.getNodeMap()?.values.orEmpty()
            val candidate = findSliderProgressCandidate(
                nodes = nodes,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                targetDescription = targetDescription
            ) ?: return false
            return runCatching {
                val range = candidate.info.rangeInfo
                if (range != null) {
                    val progress = if (x2 >= x1) {
                        max(range.min, range.max)
                    } else {
                        min(range.min, range.max)
                    }
                    withTimeout(2000) {
                        actionController?.setProgress(candidate.info, progress)
                            ?: throw IllegalStateException("Accessibility action controller is not ready")
                    }
                } else {
                    val direction = if (x2 >= x1) {
                        AccessibilityScrollDirection.RIGHT
                    } else {
                        AccessibilityScrollDirection.LEFT
                    }
                    val bounds = candidate.bounds
                    val startX = if (x2 >= x1) {
                        bounds.left + bounds.width() * SLIDER_GESTURE_EDGE_FRACTION
                    } else {
                        bounds.right - bounds.width() * SLIDER_GESTURE_EDGE_FRACTION
                    }
                    val distance = (bounds.width() * SLIDER_GESTURE_DISTANCE_FRACTION)
                        .coerceAtLeast(MIN_SLIDER_WIDTH_PX.toFloat())
                    withTimeout(2000) {
                        actionController?.scrollCoordinate(
                            startX,
                            bounds.exactCenterY(),
                            direction,
                            distance,
                            duration = 700L
                        )?.await() ?: throw IllegalStateException("Accessibility action controller is not ready")
                    }
                }
                OmniLog.i(
                    TAG,
                    "setSliderProgressFromGesture semantic success target=$targetDescription " +
                        "range=${range != null} bounds=${candidate.bounds} " +
                        "label=${nodeSemanticLabel(candidate.info).take(80)}"
                )
                true
            }.getOrElse { error ->
                OmniLog.w(
                    TAG,
                    "setSliderProgressFromGesture semantic failed: ${error.message}; fallback to gesture"
                )
                false
            }
        }

        suspend fun scrollScrollableNodeFromGesture(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            targetDescription: String = ""
        ): Boolean {
            checkAccessibilityPermissions()
            if (!isVerticalScrollGesture(x1, y1, x2, y2)) {
                return false
            }
            val nodes = captureAction?.getNodeMap()?.values.orEmpty()
            val candidate = findScrollableNodeCandidate(
                nodes = nodes,
                x = (x1 + x2) / 2f,
                y = (y1 + y2) / 2f,
                targetDescription = targetDescription
            ) ?: return false
            val direction = if (y2 < y1) {
                AccessibilityNodeScrollDirection.FORWARD
            } else {
                AccessibilityNodeScrollDirection.BACKWARD
            }
            return runCatching {
                withTimeout(2000) {
                    actionController?.scrollNode(candidate.info, direction)
                        ?: throw IllegalStateException("Accessibility action controller is not ready")
                }
                OmniLog.i(
                    TAG,
                    "scrollScrollableNodeFromGesture semantic success direction=$direction " +
                        "target=$targetDescription bounds=${candidate.bounds} " +
                        "label=${nodeSemanticLabel(candidate.info).take(80)}"
                )
                true
            }.getOrElse { error ->
                OmniLog.w(
                    TAG,
                    "scrollScrollableNodeFromGesture semantic failed: ${error.message}; fallback to gesture"
                )
                false
            }
        }

        private fun isHorizontalEndpointGesture(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float
        ): Boolean {
            val dx = abs(x2 - x1)
            val dy = abs(y2 - y1)
            return dx >= 32f && dx >= dy * 1.4f
        }

        private fun isVerticalScrollGesture(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float
        ): Boolean {
            val dx = abs(x2 - x1)
            val dy = abs(y2 - y1)
            return dy >= 48f && dy >= dx * 1.2f
        }

        private fun findSliderProgressCandidate(
            nodes: Collection<AccessibilityNode>,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            targetDescription: String
        ): AccessibilityNode? {
            val gestureY = (y1 + y2) / 2f
            val gestureLeft = min(x1, x2)
            val gestureRight = max(x1, x2)
            val targetTerms = semanticTerms(targetDescription)

            return nodes.asSequence()
                .filter { node ->
                    node.show &&
                        node.bounds.width() >= MIN_SLIDER_WIDTH_PX &&
                        node.bounds.height() > 0 &&
                        node.info.isEnabled &&
                        hasSliderSignal(node.info, targetTerms)
                }
                .mapNotNull { node ->
                    val bounds = node.bounds
                    val yDistance = abs(bounds.exactCenterY() - gestureY)
                    val yLimit = max(MIN_SLIDER_Y_TOLERANCE_PX, bounds.height() * 3f)
                    val label = nodeSemanticLabel(node.info)
                    val labelTerms = semanticTerms(label)
                    val targetOverlap = if (targetTerms.isNotEmpty() && labelTerms.isNotEmpty()) {
                        targetTerms.intersect(labelTerms.toSet()).size
                    } else {
                        0
                    }
                    val matchesTarget = targetOverlap > 0
                    if (yDistance > yLimit && !matchesTarget) {
                        return@mapNotNull null
                    }
                    val xOverlap = max(0f, min(bounds.right.toFloat(), gestureRight) - max(bounds.left.toFloat(), gestureLeft))
                    val classOrId = listOf(
                        node.info.className?.toString(),
                        node.info.viewIdResourceName
                    ).joinToString(" ").lowercase()
                    val score =
                        1000f -
                            yDistance -
                            abs(bounds.exactCenterX() - ((gestureLeft + gestureRight) / 2f)) * 0.05f +
                            (xOverlap / max(1f, bounds.width().toFloat())) * 120f +
                            (if (classOrId.contains("seekbar") || classOrId.contains("slider")) 160f else 0f) +
                            (if (label.lowercase().contains("brightness") || label.lowercase().contains("volume")) 80f else 0f) +
                            targetOverlap * 90f
                    SliderCandidate(node = node, score = score)
                }
                .maxByOrNull { it.score }
                ?.node
        }

        private fun findScrollableNodeCandidate(
            nodes: Collection<AccessibilityNode>,
            x: Float,
            y: Float,
            targetDescription: String
        ): AccessibilityNode? {
            val targetTerms = semanticTerms(targetDescription)
            return nodes.asSequence()
                .filter { node ->
                    node.show &&
                        node.info.isEnabled &&
                        node.info.isScrollable &&
                        node.bounds.width() >= MIN_SCROLLABLE_WIDTH_PX &&
                        node.bounds.height() >= MIN_SCROLLABLE_HEIGHT_PX
                }
                .map { node ->
                    val bounds = node.bounds
                    val contains = bounds.contains(x.toInt(), y.toInt())
                    val label = nodeSemanticLabel(node.info)
                    val labelTerms = semanticTerms(label)
                    val targetOverlap = if (targetTerms.isNotEmpty() && labelTerms.isNotEmpty()) {
                        targetTerms.intersect(labelTerms).size
                    } else {
                        0
                    }
                    val distance = if (contains) {
                        0f
                    } else {
                        abs(bounds.exactCenterY() - y) + abs(bounds.exactCenterX() - x) * 0.2f
                    }
                    val score =
                        (if (contains) 500f else 0f) -
                            distance * 0.05f +
                            (bounds.width() * bounds.height()).toFloat() / 10000f +
                            targetOverlap * 40f
                    SliderCandidate(node = node, score = score)
                }
                .maxByOrNull { it.score }
                ?.node
        }

        private fun findNodeById(nodeId: String): AccessibilityNode? =
            captureAction?.getNodeMap()?.get(nodeId.trim())

        private fun findClickableNodeAtCoordinate(x: Float, y: Float): AccessibilityNodeInfo? {
            val px = x.toInt()
            val py = y.toInt()
            return captureAction?.getNodeMap()?.values.orEmpty()
                .asSequence()
                .mapNotNull { node ->
                    if (!node.show || !node.info.isEnabled) return@mapNotNull null
                    val clickableNode = findActionableAncestor(
                        node = node.info,
                        supportsAction = { it.isEnabled && it.isClickable }
                    ) ?: return@mapNotNull null
                    val bounds = clickableNode.boundsInScreenOrNull() ?: node.bounds
                    if (bounds.isEmpty || !bounds.contains(px, py)) return@mapNotNull null
                    CoordinateClickCandidate(
                        node = clickableNode,
                        area = bounds.width().coerceAtLeast(1) * bounds.height().coerceAtLeast(1)
                    )
                }
                .minByOrNull { it.area }
                ?.node
        }

        private fun inputTextIntoNode(
            node: AccessibilityNodeInfo,
            text: String
        ) {
            if (!node.isFocused) {
                runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }.onFailure { error ->
                    OmniLog.d(TAG, "focus editable node before input failed: ${error.message}")
                }
            }
            actionController?.inputText(node, text)
                ?: throw IllegalStateException("Accessibility action controller is not ready")
        }

        private fun findEditableInputCandidate(
            targetDescription: String,
            nodeResourceId: String,
            x: Float?,
            y: Float?
        ): AccessibilityNodeInfo? {
            val terms = semanticTerms(targetDescription)
            val resourceText = nodeResourceId.trim().lowercase()
            val resourceTail = resourceText.substringAfterLast('/').substringAfterLast(':')
            val hasSelector = targetDescription.isNotBlank() || resourceText.isNotBlank() ||
                (x != null && y != null)
            return captureAction?.getNodeMap()?.values.orEmpty()
                .mapNotNull { node ->
                    val editableNode = findActionableAncestor(
                        node = node.info,
                        supportsAction = { it.isEnabled && it.isEditable }
                    ) ?: return@mapNotNull null
                    val bounds = editableNode.boundsInScreenOrNull() ?: node.bounds
                    if (bounds.isEmpty) return@mapNotNull null
                    val label = nodeSemanticLabel(editableNode)
                    val labelLower = label.lowercase()
                    val containsPoint = x != null && y != null &&
                        bounds.contains(x.toInt(), y.toInt())
                    val distance = if (x != null && y != null) {
                        abs(bounds.exactCenterX() - x) + abs(bounds.exactCenterY() - y)
                    } else {
                        0f
                    }
                    val termScore = terms.sumOf { term ->
                        when {
                            term.isBlank() -> 0
                            labelLower == term -> 80
                            labelLower.contains(term) -> 40
                            else -> 0
                        }
                    }
                    val candidateResource = editableNode.viewIdResourceName
                        ?.toString()
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                    val candidateTail = candidateResource.substringAfterLast('/').substringAfterLast(':')
                    val resourceScore = when {
                        resourceText.isBlank() -> 0f
                        candidateResource == resourceText -> 900f
                        resourceTail.isNotBlank() && candidateTail == resourceTail -> 520f
                        candidateResource.contains(resourceTail) && resourceTail.isNotBlank() -> 260f
                        else -> 0f
                    }
                    val coordinateScore = when {
                        containsPoint -> 600f
                        x != null && y != null -> (240f - distance * 0.08f).coerceAtLeast(0f)
                        else -> 0f
                    }
                    val focusScore = if (editableNode.isFocused) 120f else 0f
                    val areaPenalty = (bounds.width() * bounds.height()).toFloat() / 100000f
                    EditableInputCandidate(
                        node = editableNode,
                        score = coordinateScore + termScore + resourceScore + focusScore - areaPenalty
                    )
                }
                .filter { candidate ->
                    candidate.score >= if (hasSelector) MIN_INPUT_TARGET_SCORE else 0f
                }
                .maxByOrNull { it.score }
                ?.node
        }

        private fun AccessibilityNodeInfo.boundsInScreenOrNull(): Rect? {
            val rect = Rect()
            getBoundsInScreen(rect)
            return rect.takeUnless { it.isEmpty }
        }

        private fun findActionableAncestor(
            node: AccessibilityNodeInfo,
            supportsAction: (AccessibilityNodeInfo) -> Boolean
        ): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            repeat(MAX_NODE_ACTION_ANCESTOR_DEPTH) {
                val candidate = current ?: return null
                if (supportsAction(candidate)) return candidate
                current = runCatching { candidate.parent }.getOrNull()
            }
            return null
        }

        private fun hasSliderSignal(
            node: AccessibilityNodeInfo,
            targetTerms: Set<String>
        ): Boolean {
            val label = nodeSemanticLabel(node).lowercase()
            val classOrId = listOf(
                node.className?.toString(),
                node.viewIdResourceName
            ).joinToString(" ").lowercase()
            if (classOrId.contains("seekbar") ||
                classOrId.contains("slider") ||
                classOrId.contains("range") ||
                label.contains("slider") ||
                label.contains("seekbar") ||
                label.contains("brightness") ||
                label.contains("volume") ||
                label.contains("亮度") ||
                label.contains("音量") ||
                label.contains("滑块") ||
                label.contains("进度条")
            ) {
                return true
            }
            return targetTerms.any { it in SLIDER_TARGET_TERMS }
        }

        private fun nodeSemanticLabel(node: AccessibilityNodeInfo): String =
            listOf(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.hintText?.toString(),
                node.viewIdResourceName,
                node.className?.toString()?.substringAfterLast('.')
            )
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")

        private data class EditableInputCandidate(
            val node: AccessibilityNodeInfo,
            val score: Float
        )

        private fun semanticTerms(value: String): Set<String> =
            TERM_REGEX.findAll(value.lowercase())
                .map { it.value.trim('_', '-') }
                .filter { it.isNotBlank() }
                .toSet()

        //
        suspend fun goHome() {
            if (actionController == null) {
                OmniLog.w(TAG, "goHome: actionController is null, skip")
                return
            }
            try {
                actionController?.goHome()
            } catch (e: Exception) {
                OmniLog.e(TAG, "goHome failed: ${e.message}", e)
            }
        }

        suspend fun goBack() {
            actionController?.goBack()
        }

        fun getPackageName(): String? {
            return captureAction?.getCurrentPackageName()
        }

        fun getCaptureScreenShotXml(withOld: Boolean = true): String? {
            return captureAction?.captureScreenshotXml(withOld)
        }

        fun getCurrentActivity(): String? {
            return captureAction?.getCurrentActivity()
        }

        suspend fun launchApplication(
            packageName: String, doClickInvoke: suspend (x: Float, y: Float) -> Unit
        ) {
            checkAccessibilityPermissions()
            val controller = actionController
                ?: throw IllegalStateException("Accessibility action controller is not ready")

            controller.launchApplication(packageName)
            OmniLog.d("[Omni] Running", "before awaitTargetPackage")
            awaitTargetPackage(packageName)
            OmniLog.d("[Omni] Running", "after awaitTargetPackage")

            if (getPackageName() == packageName) {
                return
            }

            OmniLog.w(
                TAG,
                "launchApplication did not reach target package through accessibility action, trying launcher intent: $packageName"
            )
            if (launchApplicationByIntent(packageName)) {
                awaitTargetPackage(packageName)
                if (getPackageName() == packageName) {
                    return
                }
            }

            val currentPackage = getPackageName().orEmpty()
            val currentActivity = getCurrentActivity().orEmpty()
            val message =
                "launchApplication did not reach target package after stability wait: " +
                    "target=$packageName current=$currentPackage activity=$currentActivity"
            OmniLog.w(
                TAG,
                message
            )
            throw IllegalStateException(message)
        }

        suspend fun launchApplicationBestEffort(
            packageName: String,
            doClickInvoke: suspend (x: Float, y: Float) -> Unit
        ) {
            checkAccessibilityPermissions()
            val controller = actionController
                ?: throw IllegalStateException("Accessibility action controller is not ready")
            val accessibilityLaunch = runCatching {
                controller.launchApplication(packageName)
            }.onFailure { error ->
                if (error is PrivacyBlockedException) throw error
                OmniLog.w(TAG, "best-effort accessibility launch failed: target=$packageName error=${error.message}")
            }.isSuccess
            awaitAnyLaunchObservation(packageName)
            val intentLaunch = launchApplicationByIntent(packageName)
            if (intentLaunch) {
                awaitAnyLaunchObservation(packageName)
            }
            if (!accessibilityLaunch && !intentLaunch) {
                throw IllegalStateException("launchApplication could not start target package: $packageName")
            }
        }

        private suspend fun launchApplicationByIntent(packageName: String): Boolean =
            withContext(Dispatchers.Main) {
                val appContext = BaseApplication.instance
                val startContext = BaseApplication.foregroundActivity ?: appContext
                val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return@withContext false
                return@withContext try {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    if (startContext !is Activity) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    OmniLog.d(
                        TAG,
                        "launchApplicationByIntent startContext=${startContext.javaClass.simpleName} target=$packageName"
                    )
                    startContext.startActivity(launchIntent)
                    true
                } catch (e: Exception) {
                    OmniLog.e(TAG, "launchApplicationByIntent failed: ${e.message}", e)
                    false
                }
            }

        private suspend fun awaitTargetPackage(packageName: String, timeoutMs: Long = 3000L) {
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < timeoutMs) {
                if (getPackageName() == packageName) {
                    return
                }
                delay(150)
            }
            PageStabilityDetector.awaitStability()
        }

        private suspend fun awaitAnyLaunchObservation(packageName: String, timeoutMs: Long = 1500L) {
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < timeoutMs) {
                val observedPackage = runCatching { getPackageName() }.getOrNull()
                val observedXml = runCatching { getCaptureScreenShotXml(true) }.getOrNull()
                if (observedPackage == packageName || !observedXml.isNullOrBlank()) {
                    return
                }
                delay(150)
            }
            PageStabilityDetector.awaitStability()
        }

        suspend fun captureScreenshotImage(
            isBitmap: Boolean = true,
            isBase64: Boolean = true,
            isFile: Boolean = false,
            isFilterOverlay: Boolean = true,
            isCheckSingleColor: Boolean = false,
            isCheckMostlyLightBackground: Boolean = false,
            isCheckSideRegionMostlySingleColor: Boolean = false,
            compressQuality: ImageQuality? = null    // null = 不压缩
        ): CaptureData {
            if (service == null || screenshotAction == null) {
                initController()
            }
            var image = if (isFilterOverlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    screenshotAction?.captureExcludingOverlaysV14()

                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        screenshotAction?.captureDefaultScreenshot({
                            AssistsCore.screenshotImageEventApi?.onScreenShotHideOverlay()
                        }, {
                            AssistsCore.screenshotImageEventApi?.onScreenShotShowOverlay()
                        })
                    } else {
                        withContext(Dispatchers.Main) {
                            AssistsCore.screenshotImageEventApi?.onScreenShotHideOverlay()

                        }
                        val bitmap = ScreenCaptureManager.getInstance().captureOnce()
                        withContext(Dispatchers.Main) {
                            AssistsCore.screenshotImageEventApi?.onScreenShotShowOverlay()

                        }
                        bitmap
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    screenshotAction?.captureDefaultScreenshot()
                } else {
                    ScreenCaptureManager.getInstance().captureOnce()
                }
            }
            if (image == null && !ScreenCaptureManager.getInstance().hasPermission()) {
                val hasProjectionPermission = try {
                    ScreenCaptureManager.getInstance().requestScreenCapturePermission()
                } catch (e: Exception) {
                    OmniLog.w("Assists", "Request MediaProjection permission failed: ${e.message}")
                    false
                }
                if (hasProjectionPermission) {
                    image = if (isFilterOverlay) {
                        withContext(Dispatchers.Main) {
                            AssistsCore.screenshotImageEventApi?.onScreenShotHideOverlay()
                        }
                        try {
                            ScreenCaptureManager.getInstance().captureOnce()
                        } finally {
                            withContext(Dispatchers.Main) {
                                AssistsCore.screenshotImageEventApi?.onScreenShotShowOverlay()
                            }
                        }
                    } else {
                        ScreenCaptureManager.getInstance().captureOnce()
                    }
                }
            }
            if (image == null && ScreenCaptureManager.getInstance().hasPermission()) {
                OmniLog.w(
                    "Assists",
                    "Accessibility screenshot returned null, fallback to MediaProjection capture"
                )
                image = if (isFilterOverlay) {
                    withContext(Dispatchers.Main) {
                        AssistsCore.screenshotImageEventApi?.onScreenShotHideOverlay()
                    }
                    try {
                        ScreenCaptureManager.getInstance().captureOnce()
                    } finally {
                        withContext(Dispatchers.Main) {
                            AssistsCore.screenshotImageEventApi?.onScreenShotShowOverlay()
                        }
                    }
                } else {
                    ScreenCaptureManager.getInstance().captureOnce()
                }
            }
            if (image == null) {
                return CaptureData(
                    isSuccess = false,
                    isFilterOverlay = false,
                    isLotOfSingleColor = false,
                    isMostlyLightBackground = false,
                    imageFilePath = null,
                    imageBase64 = null,
                    imageBitmap = null
                )
            }

            // 获取原始图片尺寸
            val originalWidth = image.width
            val originalHeight = image.height

            val isSingleColor = if (isCheckSingleColor) {
                ImageUtils.isMostlySingleColor(image)
            } else false

            val imageFile = if (isFile) {
                ImageUtils.bitmapToFile(service!!, image)
            } else null

            val isMostlyLightBackground = if (isCheckMostlyLightBackground) {
                ImageUtils.isMostlyLightBackground(image)
            } else false

            val isSideRegionMostlySingleColor = if (isCheckSideRegionMostlySingleColor) {
                ImageUtils.isSideRegionMostlySingleColor(image)
            } else false

            // 处理压缩：直接基于 Bitmap 压缩，避免 Base64 中间转换
            var imageBase64Str: String? = null
            var appliedScale = 1f
            var compressedWidth = originalWidth
            var compressedHeight = originalHeight
            var bitmapToReturn: Bitmap? = null

            if (compressQuality != null) {
                if (isBitmap) {
                    // 需要返回 Bitmap：使用 scaleBitmap 缩放
                    val scaleResult = ImageCompressor.scaleBitmap(image, compressQuality)
                    bitmapToReturn = scaleResult.bitmap
                    appliedScale = scaleResult.appliedScale
                    compressedWidth = scaleResult.scaledWidth
                    compressedHeight = scaleResult.scaledHeight

                    // 如果需要 Base64，从缩放后的 bitmap 生成
                    if (isBase64) {
                        imageBase64Str = ImageUtils.bitmapToJpegBase64(bitmapToReturn!!)
                    }

                    // 如果缩放产生了新的 bitmap，回收原始 image
                    if (bitmapToReturn != image && !image.isRecycled) {
                        image.recycle()
                    }
                } else {
                    // 不需要返回 Bitmap：直接使用 compressBitmapImage 生成 Base64
                    val compressResult = ImageCompressor.compressBitmapImage(image, compressQuality)
                    if (isBase64) {
                        imageBase64Str = compressResult.base64
                    }
                    appliedScale = compressResult.appliedScale
                    compressedWidth = compressResult.compressedWidth
                    compressedHeight = compressResult.compressedHeight

                    // 回收原始 image（compressBitmapImage 不会回收它）
                    if (!image.isRecycled) {
                        image.recycle()
                    }
                }
            } else {
                // 不压缩：按原有逻辑处理
                if (isBase64) {
                    imageBase64Str = ImageUtils.bitmapToJpegBase64(image)
                }
                if (isBitmap) {
                    bitmapToReturn = image
                } else {
                    image.recycle()
                }
            }

            return CaptureData(
                isSuccess = true,
                isFilterOverlay = isFilterOverlay,
                isLotOfSingleColor = isSingleColor,
                isMostlyLightBackground = isMostlyLightBackground,
                isSideRegionMostlySingleColor = isSideRegionMostlySingleColor,
                imageFilePath = imageFile,
                imageBase64 = imageBase64Str,
                imageBitmap = bitmapToReturn,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                compressedWidth = compressedWidth,
                compressedHeight = compressedHeight,
                appliedScale = appliedScale
            )
        }

        //
        suspend fun listInstalledApplications(): HostResponse.Payload.ListInstalledApplicationsPayload {
            val (packageNames, applicationNames) = actionController
                ?.listInstalledApplications()
                ?: listInstalledApplicationsFromPackageManager()
            return HostResponse.Payload.ListInstalledApplicationsPayload(
                packageNames, applicationNames
            )
        }

        suspend fun mapInstalledApplications(): Map<String, String> {
            val (packageNames, applicationNames) = actionController
                ?.listInstalledApplications()
                ?: listInstalledApplicationsFromPackageManager()
            return packageNames.zip(applicationNames).toMap()
        }

        private fun listInstalledApplicationsFromPackageManager(): Pair<List<String>, List<String>> {
            val packageManager = BaseApplication.instance.packageManager
            val filteredApps = packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { it.loadLabel(packageManager).toString() }
            return filteredApps.map { it.packageName } to
                filteredApps.map { it.loadLabel(packageManager).toString() }
        }

        fun destroy() {
            service = null;
            actionController = null;
            captureAction = null
            screenshotAction = null
        }

        private data class SliderCandidate(
            val node: AccessibilityNode,
            val score: Float
        )

        private data class CoordinateClickCandidate(
            val node: AccessibilityNodeInfo,
            val area: Int
        )

        private val TERM_REGEX = Regex("""[\p{L}\p{N}]+""")
        private val SLIDER_TARGET_TERMS = setOf(
            "slider",
            "seekbar",
            "brightness",
            "volume",
            "sound",
            "display",
            "亮度",
            "音量",
            "滑块",
            "进度条"
        )
        private const val MIN_SLIDER_WIDTH_PX = 40
        private const val MIN_SLIDER_Y_TOLERANCE_PX = 96f
        private const val SLIDER_GESTURE_EDGE_FRACTION = 0.08f
        private const val SLIDER_GESTURE_DISTANCE_FRACTION = 0.84f
        private const val MIN_SCROLLABLE_WIDTH_PX = 120
        private const val MIN_SCROLLABLE_HEIGHT_PX = 160
        private const val MAX_NODE_ACTION_ANCESTOR_DEPTH = 6
        private const val MIN_INPUT_TARGET_SCORE = 80f
        private const val INPUT_TARGET_LOOKUP_TIMEOUT_MS = 3000L
        private const val INPUT_FOCUS_SETTLE_MS = 500L
    }
}
