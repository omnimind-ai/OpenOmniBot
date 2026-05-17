package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.delay
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal OOB-native UTG explorer.
 *
 * The provider-side OmniFlow graph runtime is not embedded in OOB. This class
 * builds a small local UTG from Accessibility XML, records the explored path as
 * an InternalRunLog, and lets the existing RunLog -> Function -> replay stack
 * handle the rest.
 */
class OobOmniFlowExplorer(
    private val context: Context
) {
    data class ExploreOptions(
        val goal: String,
        val packageName: String,
        val maxSteps: Int,
        val settleDelayMs: Long,
        val allowRiskyActions: Boolean,
        val stopText: String,
        val runId: String,
    )

    data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = max(0, right - left)
        val height: Int get() = max(0, bottom - top)
        val area: Int get() = width * height
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun normalizedBucket(root: Rect): String {
            val rootWidth = root.width.coerceAtLeast(1)
            val rootHeight = root.height.coerceAtLeast(1)
            val x = ((centerX - root.left) / rootWidth * 12f).toInt().coerceIn(0, 11)
            val y = ((centerY - root.top) / rootHeight * 20f).toInt().coerceIn(0, 19)
            val w = (width.toFloat() / rootWidth * 12f).toInt().coerceIn(0, 12)
            val h = (height.toFloat() / rootHeight * 20f).toInt().coerceIn(0, 20)
            return "$x,$y,$w,$h"
        }
    }

    data class UtgActionCandidate(
        val index: Int,
        val bounds: Rect,
        val className: String,
        val resourceId: String,
        val text: String,
        val contentDesc: String,
        val packageName: String,
        val action: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val scrollDirection: String,
        val scrollDistancePx: Float,
        val scrollDurationMs: Long,
        val signature: String,
        val actionId: String,
        val score: Double,
        val skipReason: String = "",
    ) {
        val label: String
            get() = listOf(text, contentDesc, resourceId.substringAfterLast('/'), className)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "index" to index,
            "bounds" to bounds.toMap(),
            "class_name" to className,
            "resource_id" to resourceId,
            "text" to text,
            "content_desc" to contentDesc,
            "package_name" to packageName,
            "action" to action,
            "clickable" to clickable,
            "long_clickable" to longClickable,
            "scrollable" to scrollable,
            "checkable" to checkable,
            "enabled" to enabled,
            "visible" to visible,
            "scroll_direction" to scrollDirection.takeIf { it.isNotBlank() },
            "scroll_distance_px" to scrollDistancePx.takeIf { it > 0f },
            "scroll_duration_ms" to scrollDurationMs.takeIf { it > 0L },
            "signature" to signature,
            "action_id" to actionId,
            "score" to score,
            "skip_reason" to skipReason.takeIf { it.isNotBlank() },
        )
    }

    data class UtgPageSnapshot(
        val nodeId: String,
        val packageName: String,
        val activityName: String,
        val xml: String,
        val rootBounds: Rect,
        val candidates: List<UtgActionCandidate>,
    ) {
        fun rawActionableCount(): Int = candidates.count { it.enabled && it.visible }

        fun safeCandidates(
            goal: String,
            visitedActionIds: Set<String> = emptySet(),
            allowRiskyActions: Boolean = false,
        ): List<UtgActionCandidate> = rankCandidates(
            snapshot = this,
            goal = goal,
            visitedActionIds = visitedActionIds,
            allowRiskyActions = allowRiskyActions,
        )

        fun skipReasonSummary(
            allowRiskyActions: Boolean = false,
        ): Map<String, Int> {
            return candidates
                .filter { it.enabled && it.visible }
                .mapNotNull { candidate -> skipReason(candidate, rootBounds, allowRiskyActions) }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        }

        fun observationMap(): Map<String, Any?> = linkedMapOf(
            "package_name" to packageName,
            "activity_name" to activityName,
            "observation_xml" to xml,
            "xml" to xml,
            "utg_node_id" to nodeId,
            "actionable_count" to candidates.size,
        )

        fun summaryMap(): Map<String, Any?> = linkedMapOf(
            "node_id" to nodeId,
            "package_name" to packageName,
            "activity_name" to activityName,
            "actionable_count" to candidates.size,
            "raw_actionable_count" to rawActionableCount(),
            "root_bounds" to rootBounds.toMap(),
        )

        fun containsText(query: String): Boolean {
            val needle = query.trim().lowercase(Locale.US)
            if (needle.isEmpty()) return false
            if (xml.lowercase(Locale.US).contains(needle)) return true
            return candidates.any { candidate ->
                listOf(candidate.text, candidate.contentDesc, candidate.resourceId)
                    .any { it.lowercase(Locale.US).contains(needle) }
            }
        }
    }

    data class UtgEdge(
        val edgeId: String,
        val fromNodeId: String,
        val toNodeId: String,
        val action: String,
        val actionId: String,
        val x: Float,
        val y: Float,
        val args: Map<String, Any?>,
        val targetDescription: String,
        val candidate: Map<String, Any?>,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "edge_id" to edgeId,
            "from_node_id" to fromNodeId,
            "to_node_id" to toNodeId,
            "action" to action,
            "action_id" to actionId,
            "x" to x,
            "y" to y,
            "args" to args,
            "target_description" to targetDescription,
            "candidate" to candidate,
        )
    }

    suspend fun explore(args: Map<String, Any?>?): Map<String, Any?> {
        val options = optionsFrom(args)
        if (!AccessibilityController.initController()) {
            return errorPayload(
                code = "ACCESSIBILITY_NOT_READY",
                message = "Accessibility service is not ready"
            )
        }

        if (options.packageName.isNotEmpty()) {
            AccessibilityController.launchApplication(options.packageName) { x, y ->
                AccessibilityController.clickCoordinate(x, y)
            }
            delay(options.settleDelayMs)
        }

        val start = captureSnapshot()
            ?: return errorPayload(
                code = "UTG_START_PAGE_EMPTY",
                message = "Unable to capture current page XML"
            )

        val cards = mutableListOf<Map<String, Any?>>()
        val nodeById = linkedMapOf(start.nodeId to start)
        val edges = mutableListOf<UtgEdge>()
        val visitedActionIds = mutableSetOf<String>()
        val diagnostics = mutableListOf<Map<String, Any?>>()
        var current = start
        var failure: Throwable? = null
        var doneReason = "utg_exploration_completed"

        InternalRunLogStore.beginRun(
            context = context,
            runId = options.runId,
            goal = options.goal,
            source = "oob_native_omniflow_explorer",
            toolName = "omniflow.explore_replay",
            operationDescription = options.goal
        )

        for (stepIndex in 0 until options.maxSteps) {
            if (options.stopText.isNotBlank() && current.containsText(options.stopText)) {
                doneReason = "target_text_found"
                break
            }
            val rankedCandidates = current.safeCandidates(
                goal = options.goal,
                visitedActionIds = visitedActionIds,
                allowRiskyActions = options.allowRiskyActions,
            )
            val stepDiagnostics = stepDiagnostics(
                stepIndex = stepIndex,
                snapshot = current,
                rankedCandidates = rankedCandidates,
                allowRiskyActions = options.allowRiskyActions,
            )
            diagnostics += stepDiagnostics
            val candidate = rankedCandidates.firstOrNull() ?: break

            visitedActionIds += candidate.actionId
            val before = current
            val card: Map<String, Any?> = try {
                executeCandidate(candidate)
                delay(options.settleDelayMs)
                val after = captureSnapshot() ?: before
                nodeById[after.nodeId] = after
                val edge = edgeFor(
                    before = before,
                    after = after,
                    candidate = candidate,
                    stepIndex = stepIndex
                )
                edges += edge
                val successCard = buildActionCard(
                    stepIndex = stepIndex,
                    before = before,
                    after = after,
                    candidate = candidate,
                    edge = edge,
                    success = true,
                    errorMessage = ""
                )
                current = after
                if (options.stopText.isNotBlank() && after.containsText(options.stopText)) {
                    doneReason = "target_text_found"
                }
                successCard
            } catch (error: Throwable) {
                failure = error
                val edge = edgeFor(
                    before = before,
                    after = before,
                    candidate = candidate,
                    stepIndex = stepIndex
                )
                buildActionCard(
                    stepIndex = stepIndex,
                    before = before,
                    after = before,
                    candidate = candidate,
                    edge = edge,
                    success = false,
                    errorMessage = error.message.orEmpty()
                )
            }

            cards += card
            InternalRunLogStore.appendCard(context, options.runId, card)
            if (failure != null) break
            if (doneReason == "target_text_found") break
        }

        val success = failure == null && cards.isNotEmpty()
        InternalRunLogStore.finishRun(
            context = context,
            runId = options.runId,
            success = success,
            doneReason = if (success) doneReason else "utg_exploration_failed",
            errorMessage = failure?.message
                ?: if (cards.isEmpty()) "No safe actionable UI node found" else null
        )

        return linkedMapOf(
            "success" to success,
            "run_id" to options.runId,
            "goal" to options.goal,
            "source" to "oob_native_omniflow_explorer",
            "step_count" to cards.size,
            "safe_action_only" to !options.allowRiskyActions,
            "done_reason" to if (success) doneReason else "utg_exploration_failed",
            "diagnostics" to linkedMapOf(
                "start_node_id" to start.nodeId,
                "start_raw_actionable_count" to start.rawActionableCount(),
                "start_safe_candidate_count" to start.safeCandidates(
                    goal = options.goal,
                    allowRiskyActions = options.allowRiskyActions,
                ).size,
                "start_skip_reasons" to start.skipReasonSummary(options.allowRiskyActions),
                "steps" to diagnostics,
            ),
            "error_code" to if (success) null else "UTG_EXPLORATION_NO_REPLAYABLE_PATH",
            "error_message" to if (success) null else (
                failure?.message ?: "No safe actionable UI node found"
                ),
            "utg" to utgMap(
                runId = options.runId,
                start = start,
                end = current,
                nodes = nodeById.values.toList(),
                edges = edges,
            ),
            "cards" to cards,
        )
    }

    private fun stepDiagnostics(
        stepIndex: Int,
        snapshot: UtgPageSnapshot,
        rankedCandidates: List<UtgActionCandidate>,
        allowRiskyActions: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "step_index" to stepIndex,
        "node_id" to snapshot.nodeId,
        "raw_actionable_count" to snapshot.rawActionableCount(),
        "safe_candidate_count" to rankedCandidates.size,
        "skip_reasons" to snapshot.skipReasonSummary(allowRiskyActions),
        "top_candidates" to rankedCandidates.take(5).map {
            linkedMapOf(
                "action" to it.action,
                "label" to it.label,
                "score" to it.score,
                "action_id" to it.actionId,
                "bounds" to it.bounds.toMap(),
            )
        },
        "chosen" to rankedCandidates.firstOrNull()?.let {
            linkedMapOf(
                "action" to it.action,
                "label" to it.label,
                "score" to it.score,
                "action_id" to it.actionId,
            )
        },
    )

    private suspend fun executeCandidate(candidate: UtgActionCandidate) {
        when (candidate.action) {
            ACTION_CLICK -> AccessibilityController.clickCoordinate(
                candidate.bounds.centerX,
                candidate.bounds.centerY
            )
            ACTION_SCROLL -> AccessibilityController.scrollCoordinate(
                x = candidate.bounds.centerX,
                y = candidate.bounds.centerY,
                direction = scrollDirection(candidate.scrollDirection),
                distance = candidate.scrollDistancePx.coerceAtLeast(DEFAULT_SCROLL_DISTANCE_PX),
                duration = candidate.scrollDurationMs.coerceAtLeast(DEFAULT_SCROLL_DURATION_MS),
            )
            else -> throw IllegalArgumentException("Unsupported UTG action: ${candidate.action}")
        }
    }

    suspend fun resetBeforeReplay(
        targetPackageName: String,
        backSteps: Int,
        settleDelayMs: Long,
    ) {
        if (!AccessibilityController.initController()) return
        repeat(backSteps.coerceIn(0, MAX_RESET_BACK_STEPS)) {
            AccessibilityController.goBack()
            delay(settleDelayMs)
        }
        if (targetPackageName.isNotBlank()) {
            AccessibilityController.launchApplication(targetPackageName) { x, y ->
                AccessibilityController.clickCoordinate(x, y)
            }
            delay(settleDelayMs)
        }
    }

    private fun captureSnapshot(): UtgPageSnapshot? {
        val xml = AccessibilityController.getCaptureScreenShotXml(true)?.trim().orEmpty()
        if (xml.isEmpty()) return null
        return parseSnapshot(
            xml = xml,
            packageName = AccessibilityController.getPackageName().orEmpty(),
            activityName = AccessibilityController.getCurrentActivity().orEmpty()
        )
    }

    private fun optionsFrom(args: Map<String, Any?>?): ExploreOptions {
        val request = args ?: emptyMap()
        val goal = firstNonBlank(request["goal"], request["query"], request["task"])
            .ifBlank { "OOB native OmniFlow exploration" }
        val packageName = firstNonBlank(
            request["package_name"],
            request["packageName"],
            request["target_package"],
            request["targetPackage"],
        )
        val maxSteps = intArg(request["max_steps"], request["maxSteps"], defaultValue = 3)
            .coerceIn(1, 8)
        val settleDelayMs = longArg(
            request["settle_delay_ms"],
            request["settleDelayMs"],
            request["delay_ms"],
            defaultValue = 800L
        ).coerceIn(100L, 5_000L)
        val allowRisky = boolArg(request["allow_risky_actions"]) ||
            boolArg(request["allowRiskyActions"])
        val stopText = firstNonBlank(
            request["stop_text"],
            request["stopText"],
            request["target_text"],
            request["targetText"],
        )
        val explicitRunId = firstNonBlank(request["run_id"], request["runId"])
        val runId = explicitRunId.ifBlank {
            "omniflow_utg_${System.currentTimeMillis()}_${shortHash(goal).take(8)}"
        }
        return ExploreOptions(
            goal = goal,
            packageName = packageName,
            maxSteps = maxSteps,
            settleDelayMs = settleDelayMs,
            allowRiskyActions = allowRisky,
            stopText = stopText,
            runId = runId,
        )
    }

    companion object {
        const val ACTION_CLICK = "click"
        const val ACTION_SCROLL = "scroll"
        private const val MIN_ACTION_AREA = 36 * 36
        private const val MAX_RESET_BACK_STEPS = 8
        private const val DEFAULT_SCROLL_DISTANCE_PX = 360f
        private const val DEFAULT_SCROLL_DURATION_MS = 900L

        fun parseSnapshot(
            xml: String,
            packageName: String = "",
            activityName: String = "",
        ): UtgPageSnapshot? {
            val text = xml.trim()
            if (text.isEmpty()) return null
            val elements = parseNodeElements(text)
            if (elements.isEmpty()) return null

            val allBounds = elements.mapNotNull { parseBounds(it.attr("bounds")) }
            val rootBounds = allBounds.reduceOrNull { acc, rect ->
                Rect(
                    left = min(acc.left, rect.left),
                    top = min(acc.top, rect.top),
                    right = max(acc.right, rect.right),
                    bottom = max(acc.bottom, rect.bottom),
                )
            } ?: Rect(0, 0, 1080, 1920)

            val rawCandidates = elements.flatMapIndexed { index, element ->
                candidatesFromElement(
                    index = index,
                    element = element,
                    rootBounds = rootBounds,
                    fallbackPackage = packageName,
                    stateSeed = text,
                )
            }
            val stateDigest = rawCandidates.joinToString("\n") { candidate ->
                listOf(
                    candidate.packageName,
                    candidate.className.substringAfterLast('.'),
                    candidate.resourceId.substringAfterLast('/'),
                    candidate.text,
                    candidate.contentDesc,
                    candidate.bounds.normalizedBucket(rootBounds),
                ).joinToString("|")
            }.ifBlank { text.take(512) }
            val nodeId = "utg_node_${shortHash("$packageName|$activityName|$stateDigest").take(16)}"
            val candidates = rawCandidates.map { candidate ->
                candidate.copy(
                    actionId = "utg_action_${shortHash("$nodeId|${candidate.action}|${candidate.signature}").take(16)}"
                )
            }
            return UtgPageSnapshot(
                nodeId = nodeId,
                packageName = packageName,
                activityName = activityName,
                xml = text,
                rootBounds = rootBounds,
                candidates = candidates,
            )
        }

        fun rankCandidates(
            snapshot: UtgPageSnapshot,
            goal: String,
            visitedActionIds: Set<String> = emptySet(),
            allowRiskyActions: Boolean = false,
        ): List<UtgActionCandidate> {
            val goalTokens = tokens(goal)
            return snapshot.candidates
                .filter { it.enabled && it.visible && it.actionId !in visitedActionIds }
                .mapNotNull { candidate ->
                    val skipReason = skipReason(candidate, snapshot.rootBounds, allowRiskyActions)
                    if (skipReason != null) return@mapNotNull null
                    val labelTokens = tokens(
                        listOf(
                            candidate.text,
                            candidate.contentDesc,
                            candidate.resourceId,
                            candidate.className,
                        ).joinToString(" ")
                    ).toSet()
                    val overlap = goalTokens.count { it in labelTokens }
                    val labelBonus = if (candidate.label.isNotBlank()) 0.35 else 0.0
                    val actionBonus = when (candidate.action) {
                        ACTION_CLICK -> 0.30
                        ACTION_SCROLL -> 0.18
                        else -> 0.0
                    }
                    val sizePenalty =
                        if (candidate.action != ACTION_SCROLL && isOverbroad(candidate, snapshot.rootBounds)) {
                            -0.80
                        } else {
                            0.0
                        }
                    val score = 1.0 + labelBonus + actionBonus +
                        overlap * 0.45 + sizePenalty - candidate.index * 0.0001
                    candidate.copy(score = score)
                }
                .sortedWith(
                    compareByDescending<UtgActionCandidate> { it.score }
                        .thenBy { it.index }
                )
        }

        fun buildActionCard(
            stepIndex: Int,
            before: UtgPageSnapshot,
            after: UtgPageSnapshot,
            candidate: UtgActionCandidate,
            edge: UtgEdge,
            success: Boolean = true,
            errorMessage: String = "",
        ): Map<String, Any?> = linkedMapOf(
            "card_id" to "utg_edge_${stepIndex + 1}",
            "tool_name" to candidate.action,
            "title" to "Explore ${candidate.action} ${candidate.label}".take(120),
            "summary" to "UTG explore ${candidate.action} ${candidate.label}".take(160),
            "success" to success,
            "args" to actionArgs(candidate),
            "before" to before.observationMap(),
            "after" to after.observationMap(),
            "utg" to edge.toMap(),
            "error_message" to errorMessage.takeIf { it.isNotBlank() },
        ).filterValues { it != null }

        fun buildClickCard(
            stepIndex: Int,
            before: UtgPageSnapshot,
            after: UtgPageSnapshot,
            candidate: UtgActionCandidate,
            edge: UtgEdge,
            success: Boolean = true,
            errorMessage: String = "",
        ): Map<String, Any?> = buildActionCard(
            stepIndex = stepIndex,
            before = before,
            after = after,
            candidate = candidate.copy(action = ACTION_CLICK),
            edge = edge,
            success = success,
            errorMessage = errorMessage,
        )

        fun edgeFor(
            before: UtgPageSnapshot,
            after: UtgPageSnapshot,
            candidate: UtgActionCandidate,
            stepIndex: Int,
        ): UtgEdge {
            val edgeId = "utg_edge_${shortHash("${before.nodeId}|${candidate.actionId}|${after.nodeId}|$stepIndex").take(16)}"
            return UtgEdge(
                edgeId = edgeId,
                fromNodeId = before.nodeId,
                toNodeId = after.nodeId,
                action = candidate.action,
                actionId = candidate.actionId,
                x = candidate.bounds.centerX,
                y = candidate.bounds.centerY,
                args = actionArgs(candidate),
                targetDescription = candidate.label,
                candidate = candidate.toMap(),
            )
        }

        fun utgMap(
            runId: String,
            start: UtgPageSnapshot,
            end: UtgPageSnapshot,
            nodes: List<UtgPageSnapshot>,
            edges: List<UtgEdge>,
        ): Map<String, Any?> = linkedMapOf(
            "schema_version" to "oob.omniflow_utg.v1",
            "run_id" to runId,
            "start_node_id" to start.nodeId,
            "end_node_id" to end.nodeId,
            "node_count" to nodes.map { it.nodeId }.distinct().size,
            "edge_count" to edges.size,
            "nodes" to nodes.distinctBy { it.nodeId }.map { it.summaryMap() },
            "edges" to edges.map { it.toMap() },
            "path" to edges.map { it.edgeId },
        )

        private fun candidateFromElement(
            index: Int,
            element: Element,
            rootBounds: Rect,
            fallbackPackage: String,
            stateSeed: String,
        ): UtgActionCandidate? = candidatesFromElement(
            index = index,
            element = element,
            rootBounds = rootBounds,
            fallbackPackage = fallbackPackage,
            stateSeed = stateSeed,
        ).firstOrNull()

        private fun candidatesFromElement(
            index: Int,
            element: Element,
            rootBounds: Rect,
            fallbackPackage: String,
            stateSeed: String,
        ): List<UtgActionCandidate> {
            val bounds = parseBounds(element.attr("bounds")) ?: return emptyList()
            if (bounds.area < MIN_ACTION_AREA) return emptyList()
            val clickable = element.boolAttr("clickable")
            val longClickable = element.boolAttr("long-clickable")
            val scrollable = element.boolAttr("scrollable")
            if (!clickable && !longClickable && !scrollable) return emptyList()
            val enabled = element.attr("enabled").isBlank() || element.boolAttr("enabled")
            val visible = element.attr("visible-to-user").isBlank() ||
                element.boolAttr("visible-to-user")
            val className = element.attr("class")
            val resourceId = element.attr("resource-id")
            val text = element.attr("text")
            val contentDesc = element.attr("content-desc")
            val nodePackage = element.attr("package").ifBlank { fallbackPackage }
            val normalizedBounds = bounds.normalizedBucket(rootBounds)
            val signature = listOf(
                nodePackage,
                className.substringAfterLast('.'),
                resourceId.substringAfterLast('/'),
                text,
                contentDesc,
                normalizedBounds,
            ).joinToString("|")
            val base = UtgActionCandidate(
                index = index,
                bounds = bounds,
                className = className,
                resourceId = resourceId,
                text = text,
                contentDesc = contentDesc,
                packageName = nodePackage,
                action = ACTION_CLICK,
                clickable = clickable,
                longClickable = longClickable,
                scrollable = scrollable,
                checkable = element.boolAttr("checkable"),
                enabled = enabled,
                visible = visible,
                scrollDirection = "",
                scrollDistancePx = 0f,
                scrollDurationMs = 0L,
                signature = signature,
                actionId = "utg_action_${shortHash("$stateSeed|click|$signature").take(16)}",
                score = 0.0,
            )
            return buildList {
                if (clickable) add(base)
                if (scrollable) {
                    add(
                        base.copy(
                            action = ACTION_SCROLL,
                            scrollDirection = "up",
                            scrollDistancePx = scrollDistance(bounds, rootBounds),
                            scrollDurationMs = DEFAULT_SCROLL_DURATION_MS,
                            actionId = "utg_action_${shortHash("$stateSeed|scroll|$signature").take(16)}",
                        )
                    )
                }
            }
        }

        private fun skipReason(
            candidate: UtgActionCandidate,
            rootBounds: Rect,
            allowRiskyActions: Boolean,
        ): String? {
            if (candidate.checkable) return "checkable_control"
            val classTail = candidate.className.substringAfterLast('.')
                .lowercase(Locale.US)
            if (classTail in riskyClassTails) return "risky_class:$classTail"
            if (candidate.action != ACTION_SCROLL && isOverbroad(candidate, rootBounds)) {
                return "overbroad_bounds"
            }
            if (!allowRiskyActions && candidate.action != ACTION_SCROLL) {
                val label = listOf(
                    candidate.text,
                    candidate.contentDesc,
                    candidate.resourceId.substringAfterLast('/'),
                ).joinToString(" ").lowercase(Locale.US)
                riskyLabelTokens.firstOrNull { label.contains(it) }?.let {
                    return "risky_label:$it"
                }
            }
            return null
        }

        private fun actionArgs(candidate: UtgActionCandidate): Map<String, Any?> {
            val base = linkedMapOf<String, Any?>(
                "target_description" to candidate.label,
                "x" to candidate.bounds.centerX,
                "y" to candidate.bounds.centerY,
            )
            if (candidate.action != ACTION_SCROLL) return base

            val end = scrollEndPoint(
                x = candidate.bounds.centerX,
                y = candidate.bounds.centerY,
                direction = candidate.scrollDirection,
                distance = candidate.scrollDistancePx.coerceAtLeast(DEFAULT_SCROLL_DISTANCE_PX),
                bounds = candidate.bounds,
            )
            return linkedMapOf(
                "target_description" to candidate.label,
                "x1" to candidate.bounds.centerX,
                "y1" to candidate.bounds.centerY,
                "x2" to end.first,
                "y2" to end.second,
                "direction" to candidate.scrollDirection,
                "distance" to candidate.scrollDistancePx.coerceAtLeast(DEFAULT_SCROLL_DISTANCE_PX),
                "duration_ms" to candidate.scrollDurationMs.coerceAtLeast(DEFAULT_SCROLL_DURATION_MS),
            )
        }

        private fun scrollDistance(bounds: Rect, rootBounds: Rect): Float {
            val byContainer = bounds.height * 0.45f
            val byRoot = rootBounds.height * 0.35f
            return min(max(byContainer, DEFAULT_SCROLL_DISTANCE_PX), max(byRoot, DEFAULT_SCROLL_DISTANCE_PX))
        }

        private fun scrollEndPoint(
            x: Float,
            y: Float,
            direction: String,
            distance: Float,
            bounds: Rect,
        ): Pair<Float, Float> {
            return when (direction.lowercase(Locale.US)) {
                "down" -> x to min(y + distance, bounds.bottom.toFloat())
                "left" -> max(x - distance, bounds.left.toFloat()) to y
                "right" -> min(x + distance, bounds.right.toFloat()) to y
                else -> x to max(y - distance, bounds.top.toFloat())
            }
        }

        private fun scrollDirection(value: String): ScrollDirection {
            return when (value.trim().lowercase(Locale.US)) {
                "down" -> ScrollDirection.DOWN
                "left" -> ScrollDirection.LEFT
                "right" -> ScrollDirection.RIGHT
                else -> ScrollDirection.UP
            }
        }

        private fun isOverbroad(candidate: UtgActionCandidate, rootBounds: Rect): Boolean {
            val rootArea = rootBounds.area.coerceAtLeast(1)
            return candidate.bounds.area > rootArea * 0.75
        }

        private val riskyClassTails = setOf(
            "switch",
            "checkbox",
            "radiobutton",
            "edittext",
            "seekbar",
            "ratingbar",
        )

        private val riskyLabelTokens = setOf(
            "delete",
            "remove",
            "clear",
            "erase",
            "pay",
            "purchase",
            "buy",
            "order",
            "send",
            "submit",
            "confirm",
            "logout",
            "sign out",
            "uninstall",
            "删除",
            "移除",
            "清空",
            "支付",
            "购买",
            "下单",
            "发送",
            "提交",
            "确认",
            "退出登录",
            "卸载",
        )

        private fun parseNodeElements(xml: String): List<Element> {
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    isExpandEntityReferences = false
                    runCatching {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                    runCatching {
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                    }
                    runCatching {
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    }
                }
                val document = factory.newDocumentBuilder().parse(
                    InputSource(StringReader(xml))
                )
                val nodes = document.getElementsByTagName("node")
                buildList {
                    for (i in 0 until nodes.length) {
                        val element = nodes.item(i) as? Element ?: continue
                        add(element)
                    }
                }
            }.getOrElse { emptyList() }
        }

        private fun parseBounds(value: String): Rect? {
            val match = Regex("""\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]""")
                .matchEntire(value.trim()) ?: return null
            val left = match.groupValues[1].toIntOrNull() ?: return null
            val top = match.groupValues[2].toIntOrNull() ?: return null
            val right = match.groupValues[3].toIntOrNull() ?: return null
            val bottom = match.groupValues[4].toIntOrNull() ?: return null
            if (right <= left || bottom <= top) return null
            return Rect(left, top, right, bottom)
        }

        private fun Element.attr(name: String): String =
            getAttribute(name)?.trim().orEmpty()

        private fun Element.boolAttr(name: String): Boolean =
            attr(name).equals("true", ignoreCase = true)

        private fun Rect.toMap(): Map<String, Any?> = linkedMapOf(
            "left" to left,
            "top" to top,
            "right" to right,
            "bottom" to bottom,
            "center_x" to centerX,
            "center_y" to centerY,
            "width" to width,
            "height" to height,
        )

        private fun tokens(text: String): List<String> {
            return text.lowercase(Locale.US)
                .split(Regex("""[^a-z0-9\u4e00-\u9fa5]+"""))
                .map { it.trim() }
                .filter { it.length >= 2 }
        }

        private fun firstNonBlank(vararg values: Any?): String {
            for (value in values) {
                val text = value?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) return text
            }
            return ""
        }

        private fun intArg(vararg values: Any?, defaultValue: Int): Int {
            values.forEach { value ->
                when (value) {
                    is Number -> return value.toInt()
                    is String -> value.trim().toIntOrNull()?.let { return it }
                }
            }
            return defaultValue
        }

        private fun longArg(vararg values: Any?, defaultValue: Long): Long {
            values.forEach { value ->
                when (value) {
                    is Number -> return value.toLong()
                    is String -> value.trim().toLongOrNull()?.let { return it }
                }
            }
            return defaultValue
        }

        private fun boolArg(value: Any?): Boolean {
            return when (value) {
                is Boolean -> value
                is String -> value.trim().equals("true", ignoreCase = true) ||
                    value.trim() == "1"
                is Number -> value.toInt() != 0
                else -> false
            }
        }

        private fun shortHash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun errorPayload(code: String, message: String): Map<String, Any?> = linkedMapOf(
            "success" to false,
            "error_code" to code,
            "error_message" to message,
            "source" to "oob_native_omniflow_explorer"
        )
    }
}
