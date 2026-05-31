package cn.com.omnimind.bot.runlog

import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object OmniflowStepExecutor {
    data class StepArgsResult(
        val args: Any?,
        val meta: Map<String, Any?> = emptyMap(),
    )

    private data class ReplayState(
        val snapshot: BackendSnapshot,
        val page: PageModel?,
        val capturedAtMs: Long,
        val reason: String,
    )

    private data class ReplayAction(
        val step: Map<String, Any?>,
        val action: String,
        val args: Map<String, Any?>,
    )

    class ExecutionException(
        val errorCode: String,
        message: String,
        val diagnostics: Map<String, Any?> = emptyMap(),
    ) : IllegalStateException(message)

    suspend fun currentPageSnapshotForRecovery(reason: String? = null): Map<String, Any?> =
        recoverySnapshotMap(readBackendSnapshot(), reason)

    fun isOmniflowStep(step: Map<String, Any?>): Boolean {
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val modelFree = step["model_free"] == true ||
            step["modelFree"] == true ||
            step["model_free"]?.toString()?.equals("true", ignoreCase = true) == true
        val action = actionNameForStep(step)
        return action in OobActionCodec.executableActions &&
            (executor == "omniflow" || modelFree)
    }

    fun actionNameForStep(step: Map<String, Any?>): String =
        OobActionCodec.actionNameForStep(step)

    fun normalizeArgsMap(rawArgs: Any?): Map<String, Any?> =
        when (rawArgs) {
            is Map<*, *> -> rawArgs.entries.associate { (key, value) -> key.toString() to value }
            else -> emptyMap()
        }

    fun requiresAccessibility(step: Map<String, Any?>): Boolean =
        isOmniflowStep(step) && actionRequiresAccessibility(actionNameForStep(step))

    fun actionRequiresAccessibility(action: String): Boolean {
        val normalized = OobActionCodec.canonicalActionForName(action)
            ?: OobActionCodec.normalizeName(action)
        return normalized in OobActionCodec.executableActions &&
            normalized != OobActionCodec.ACTION_OPEN_APP &&
            normalized != OobActionCodec.ACTION_FINISHED
    }

    fun stringArg(args: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = args[key] ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) {
                return text
            }
        }
        return null
    }

    suspend fun execute(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
    ): Map<String, Any?> {
        val timing = ReplayStepTiming()
        val action = actionNameForStep(step)
        if (action !in OobActionCodec.executableActions) {
            throw IllegalArgumentException("Unsupported omniflow action: $action")
        }
        val backend = OmniflowActionRuntime.backend
        if (actionRequiresAccessibility(action) && !backend.isReady()) {
            throw IllegalStateException("OmniFlow action backend is not ready")
        }
        val fixedReplay = RunLogReplayPolicy.fixedReplayOnly
        val initialArgs = OobActionCodec.argsForStep(step)
        val transferRequested = !fixedReplay &&
            action in OobActionCodec.coordinateActions &&
            shouldUseCoordinateHook(step)
        var currentState: ReplayState? = null

        suspend fun replayState(reason: String): ReplayState {
            val existing = currentState
            if (existing != null) return existing
            return observeReplayState(timing, reason).also { currentState = it }
        }

        suspend fun refreshReplayState(reason: String): ReplayState =
            observeReplayState(timing, reason).also { currentState = it }

        val preTransferControls = timing.measure("checker_ms") {
            if (fixedReplay) {
                emptyList()
            } else {
                runCheckerPhase(
                    phase = OmniflowCheckerRule.PHASE_PRE_TRANSFER,
                    state = replayState("before_step"),
                    replayAction = ReplayAction(step, action, initialArgs),
                    extraRules = checkerRules,
                )
            }
        }
        if (!fixedReplay && preTransferControls.isNotEmpty()) {
            refreshReplayState("after_pre_transfer_controls")
        }
        val attemptedRemapResult = timing.measure("action_transfer_ms") {
            if (fixedReplay) {
                StepArgsResult(initialArgs)
            } else {
                try {
                    remapStepArgsForState(step, replayState("action_transfer"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StepArgsResult(
                        args = initialArgs,
                        meta = mapOf(
                            "applied" to false,
                            "reason" to "action_transfer_exception",
                            "algorithm" to "anchor_projection",
                            "error_message" to e.message.orEmpty(),
                        )
                    )
                }
            }
        }
        var remapResult = recordedReplayFallbackIfNeeded(
            transferRequested = transferRequested,
            attempted = attemptedRemapResult,
            initialArgs = initialArgs,
        )
        var args = normalizeArgsMap(remapResult.args)
        val preActionControls = timing.measure("checker_ms") {
            if (fixedReplay) {
                emptyList()
            } else {
                runCheckerPhase(
                    phase = OmniflowCheckerRule.PHASE_PRE_ACTION,
                    state = replayState("before_action"),
                    replayAction = ReplayAction(step, action, args),
                    extraRules = checkerRules,
                )
            }
        }
        if (!fixedReplay && preActionControls.isNotEmpty()) {
            val refreshed = refreshReplayState("after_pre_action_controls")
            if (transferRequested) {
                val remappedAfterControl = timing.measure("action_transfer_ms") {
                    try {
                        remapStepArgsForState(step, refreshed)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StepArgsResult(
                            args = initialArgs,
                            meta = mapOf(
                                "applied" to false,
                                "reason" to "action_transfer_exception",
                                "algorithm" to "anchor_projection",
                                "error_message" to e.message.orEmpty(),
                            )
                        )
                    }
                }
                remapResult = recordedReplayFallbackIfNeeded(
                    transferRequested = transferRequested,
                    attempted = remappedAfterControl,
                    initialArgs = initialArgs,
                )
                args = normalizeArgsMap(remapResult.args)
            }
        }
        val actionTransferApplied = transferRequested && remapResult.meta["applied"] == true
        val controlEffects = preTransferControls + preActionControls
        val summary = timing.measure("act_ms") {
            when (action) {
                "click" -> {
                    val x = numberArg(args, "x", "center_x", "centerX")?.toFloat()
                        ?: throw IllegalArgumentException("click requires x")
                    val y = numberArg(args, "y", "center_y", "centerY")?.toFloat()
                        ?: throw IllegalArgumentException("click requires y")
                    backend.click(x, y)
                    "click"
                }

                "long_press" -> {
                    val x = numberArg(args, "x", "center_x", "centerX")?.toFloat()
                        ?: throw IllegalArgumentException("long_press requires x")
                    val y = numberArg(args, "y", "center_y", "centerY")?.toFloat()
                        ?: throw IllegalArgumentException("long_press requires y")
                    backend.longPress(
                        x = x,
                        y = y,
                        durationMs = durationMs(args, defaultMs = 1000L)
                    )
                    "long_press"
                }

                OobActionCodec.ACTION_SWIPE -> {
                    val swipe = swipeSpec(args, replayState("act_swipe"))
                    backend.scrollWithContext(
                        x = swipe.x,
                        y = swipe.y,
                        direction = swipe.direction,
                        distance = swipe.distance,
                        durationMs = durationMs(args, defaultMs = 1500L),
                        targetDescription = stringArg(args, "target_description", "targetDescription").orEmpty()
                    )
                    action
                }

                OobActionCodec.ACTION_INPUT_TEXT -> {
                    val text = stringArg(args, "content", "text", "value")
                        ?: throw IllegalArgumentException("$action requires content")
                    backend.inputText(
                        text = text,
                        targetDescription = stringArg(
                            args,
                            "target_description",
                            "targetDescription",
                            "label",
                            "selector",
                        ).orEmpty(),
                        x = numberArg(args, "x", "center_x", "centerX")?.toFloat(),
                        y = numberArg(args, "y", "center_y", "centerY")?.toFloat(),
                        nodeResourceId = stringArg(
                            args,
                            "node_resource_id",
                            "nodeResourceId",
                            "resource_id",
                            "resourceId",
                        ).orEmpty(),
                    )
                    action
                }

                "open_app" -> {
                    val packageName = stringArg(args, "package_name", "packageName")
                        ?: throw IllegalArgumentException("open_app requires package_name")
                    val resetTask = booleanArg(args["reset_task"]) ||
                        stringArg(args, "launch_mode")?.equals("fresh_task", ignoreCase = true) == true
                    if (resetTask) {
                        backend.launchApplication(packageName, true)
                    } else {
                        backend.launchApplication(packageName)
                    }
                    stabilizeOpenAppLaunch(packageName, resetTask, timing)
                    "open_app"
                }

                OobActionCodec.ACTION_PRESS_KEY -> {
                    val key = stringArg(args, "key", "hotkey", "hot_key")
                        ?: throw IllegalArgumentException("$action requires key")
                    backend.pressHotKey(key)
                    action
                }

                "finished" -> "finished"

                else -> throw IllegalArgumentException("Unsupported omniflow action: $action")
            }
        }
        timing.measureOverhead("settle_ms") {
            delay(POST_STEP_DELAY_MS)
        }
        val checker = timing.measureOverhead("result_summary_ms") {
            replayCheckerSummary(
                action = action,
                fixedReplay = fixedReplay,
                transfer = remapResult.meta,
                controlEffects = controlEffects,
            )
        }
        val timingResult = timing.finish()
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to action,
            "executor" to "omniflow",
            "model_free" to true,
            "replay_mode" to replayMode(actionTransferApplied, transferRequested, remapResult.meta),
            "success" to true,
            "summary" to (stepTitle.takeIf { it.isNotBlank() } ?: summary),
            "started_at_ms" to timingResult["started_at_ms"],
            "finished_at_ms" to timingResult["finished_at_ms"],
            "duration_ms" to timingResult["duration_ms"],
            "timing" to timingResult,
        ).apply {
            if (remapResult.meta.isNotEmpty()) {
                put("action_transfer", remapResult.meta)
            }
            if (checker.isNotEmpty()) {
                put("checker", checker)
            }
            if (controlEffects.isNotEmpty()) {
                put("control_effects", controlEffects)
            }
        }
    }

    private fun replayMode(
        actionTransferApplied: Boolean,
        transferRequested: Boolean,
        transfer: Map<String, Any?>,
    ): String = when {
        actionTransferApplied -> "action_transfer"
        transferRequested && transfer["recorded_action_args_used"] == true -> "recorded_action_replay"
        transferRequested -> "action_transfer_skipped"
        else -> "direct_replay"
    }

    fun remapStepArgs(step: Map<String, Any?>): StepArgsResult =
        remapStepArgsInternal(step, currentXmlOverride = null)

    private fun recordedReplayFallbackIfNeeded(
        transferRequested: Boolean,
        attempted: StepArgsResult,
        initialArgs: Map<String, Any?>,
    ): StepArgsResult {
        return if (transferRequested && attempted.meta["applied"] == false) {
            StepArgsResult(
                args = initialArgs,
                meta = attempted.meta + mapOf(
                    "fallback_replay_mode" to "recorded_action_replay",
                    "recorded_action_args_used" to true,
                )
            )
        } else {
            attempted
        }
    }

    private fun remapStepArgsInternal(
        step: Map<String, Any?>,
        currentXmlOverride: String?,
    ): StepArgsResult {
        val rawArgs = step["args"]
        val args = OobActionCodec.argsForStep(step)
        if (rawArgs !is Map<*, *> && args.isEmpty()) return StepArgsResult(rawArgs)
        if (!shouldUseCoordinateHook(step)) {
            return StepArgsResult(args)
        }
        val tool = actionNameForStep(step)
        if (tool !in OobActionCodec.coordinateActions) {
            return StepArgsResult(args)
        }
        val sourceContext = (step["source_context"] as? Map<*, *>)
            ?: (args["source_context"] as? Map<*, *>)
            ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_source_context", "algorithm" to "anchor_projection")
        )
        val srcCtx = sourceContext["src_ctx"] as? Map<*, *>
        val sourceXml = firstNonBlank(
            srcCtx?.get("page"),
            sourceContext["page"],
            sourceContext["xml"],
        )
        if (sourceXml.isEmpty()) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_source_xml", "algorithm" to "anchor_projection")
            )
        }
        val currentXml = currentXmlOverride ?: readCurrentXmlForCoordinateRemapDirect()
        if (currentXml.isEmpty()) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_current_xml", "algorithm" to "anchor_projection")
            )
        }
        return when (tool) {
            "click", "long_press", "input_text" -> remapPointActionArgs(tool, args, sourceXml, currentXml)
            OobActionCodec.ACTION_SWIPE -> remapScrollActionArgs(tool, args, sourceXml, currentXml)
            else -> StepArgsResult(args)
        }
    }

    private fun remapStepArgsForState(
        step: Map<String, Any?>,
        state: ReplayState,
    ): StepArgsResult =
        remapStepArgsInternal(step, currentXmlOverride = state.snapshot.xml)

    private fun readCurrentXmlForCoordinateRemapDirect(): String =
        readBackendSnapshotDirect().xml

    private fun shouldUseCoordinateHook(step: Map<String, Any?>): Boolean {
        val coordinateHook = step["coordinate_hook"]?.toString()?.trim()?.lowercase().orEmpty()
        val replayEngine = step["replay_engine"]?.toString()?.trim()?.lowercase().orEmpty()
        return coordinateHook == "omniflow" ||
            step["omniflow"] == true ||
            replayEngine == "omniflow_utg"
    }

    private fun numberArg(args: Map<String, Any?>, vararg keys: String): Number? {
        for (key in keys) {
            val value = args[key] ?: continue
            when (value) {
                is Number -> return value
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun durationMs(args: Map<String, Any?>, defaultMs: Long): Long {
        numberArg(args, "duration_ms", "durationMs")?.toLong()?.let {
            return it.coerceAtLeast(0L)
        }
        numberArg(args, "duration")?.toDouble()?.let { seconds ->
            return (seconds * 1000.0).toLong().coerceAtLeast(0L)
        }
        return defaultMs
    }

    private data class SwipeSpec(
        val x: Float,
        val y: Float,
        val direction: ScrollDirection,
        val distance: Float,
    )

    private fun swipeSpec(
        args: Map<String, Any?>,
        state: ReplayState,
    ): SwipeSpec {
        val x1 = numberArg(args, "x1")?.toFloat()
        val y1 = numberArg(args, "y1")?.toFloat()
        val x2 = numberArg(args, "x2")?.toFloat()
        val y2 = numberArg(args, "y2")?.toFloat()
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            val dx = x2 - x1
            val dy = y2 - y1
            val direction = if (abs(dy) > abs(dx)) {
                if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
            } else {
                if (dx > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT
            }
            return SwipeSpec(x1, y1, direction, hypot(dx, dy))
        }

        val direction = directionArg(args)
            ?: throw IllegalArgumentException("swipe requires direction or x1/y1/x2/y2")
        val rootCenter = currentRootCenter(state)
        val x: Float = numberArg(args, "x", "center_x", "centerX")?.toFloat()
            ?: rootCenter?.first
            ?: DEFAULT_SCREEN_CENTER_X
        val y: Float = numberArg(args, "y", "center_y", "centerY")?.toFloat()
            ?: rootCenter?.second
            ?: DEFAULT_SCREEN_CENTER_Y
        val distance: Float = numberArg(args, "distance", "distance_px", "distancePx")
            ?.toFloat()
            ?.coerceAtLeast(1f)
            ?: DEFAULT_SWIPE_DISTANCE
        return SwipeSpec(x, y, direction, distance)
    }

    private fun directionArg(args: Map<String, Any?>): ScrollDirection? {
        val raw = stringArg(args, "direction", "scroll_direction", "scrollDirection")
            ?.trim()
            ?.lowercase()
            ?: return null
        return when (raw) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> null
        }
    }

    private suspend fun stabilizeOpenAppLaunch(
        packageName: String,
        resetTask: Boolean,
        timing: ReplayStepTiming,
    ) {
        if (packageName.isBlank()) return
        var lastPackage = effectiveCurrentPackage(timing)
        repeat(OPEN_APP_STABILITY_ATTEMPTS) { index ->
            val matchMode = packageMatchMode(packageName, lastPackage)
            if (matchMode != null) return
            if (resetTask && index == OPEN_APP_RELAUNCH_ATTEMPT_INDEX) {
                runCatching { OmniflowActionRuntime.backend.pressHotKey("BACK") }
                delay(OPEN_APP_STABILITY_DELAY_MS)
                runCatching { OmniflowActionRuntime.backend.launchApplication(packageName, true) }
            }
            if (index < OPEN_APP_STABILITY_ATTEMPTS - 1) {
                delay(OPEN_APP_STABILITY_DELAY_MS)
                lastPackage = effectiveCurrentPackage(timing)
            }
        }
    }

    private suspend fun effectiveCurrentPackage(timing: ReplayStepTiming): String =
        readBackendSnapshot(timing).effectivePackage()

    private fun beforeActionCheckerSummary(
        action: String,
        transfer: Map<String, Any?>,
        controlEffects: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        if (transfer.isEmpty() && controlEffects.isEmpty()) return emptyMap()
        return mapOf(
            "phase" to "before_action",
            "effect" to "continue",
            "verified" to true,
            "action" to action,
            "action_transfer_applied" to transfer["applied"],
            "action_transfer_reason" to transfer["reason"],
            "control_effect_count" to controlEffects.size.takeIf { it > 0 },
            "controllers" to controlEffects.mapNotNull { it["controller"]?.toString() }
                .takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private suspend fun replayCheckerSummary(
        action: String,
        fixedReplay: Boolean,
        transfer: Map<String, Any?>,
        controlEffects: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        return if (fixedReplay) {
            emptyMap()
        } else {
            beforeActionCheckerSummary(action, transfer, controlEffects)
        }
    }

    private suspend fun runCheckerPhase(
        phase: String,
        state: ReplayState,
        replayAction: ReplayAction,
        extraRules: List<OmniflowCheckerRule>,
    ): List<Map<String, Any?>> {
        val action = replayAction.action
        if (action == "finished" || action == "open_app") return emptyList()
        val globalRules = when (phase) {
            OmniflowCheckerRule.PHASE_PRE_TRANSFER -> OmniflowCheckerRule.GLOBAL_PRE_TRANSFER
            OmniflowCheckerRule.PHASE_PRE_ACTION -> OmniflowCheckerRule.GLOBAL_PRE_ACTION
            else -> emptyList()
        }
        val activeRules = globalRules + extraRules.filter { it.phase == phase && it.enabled }
        for (rule in activeRules) {
            val result = evaluateAndExecuteRule(rule, state, replayAction) ?: continue
            // Stop after the first rule that produces a recovery action.
            return listOf(result)
        }
        return emptyList()
    }

    private suspend fun evaluateAndExecuteRule(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? = when (rule.condition) {
        OmniflowCheckerRule.COND_PERMISSION_DIALOG ->
            checkerPermissionDialog(rule, state, replayAction)
        OmniflowCheckerRule.COND_PACKAGE_MISMATCH ->
            checkerPackageMismatch(rule, state, replayAction)
        OmniflowCheckerRule.COND_OVERLAY_BLOCKING ->
            checkerOverlayBlocking(rule, state, replayAction)
        OmniflowCheckerRule.COND_KEYBOARD_OBSCURING ->
            checkerKeyboardObscuring(rule, state, replayAction)
        else -> null
    }

    private suspend fun checkerPermissionDialog(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        val page = state.page ?: return null
        val candidate = permissionAllowCandidate(page) ?: return null
        OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to OmniflowCheckerRule.ACTION_ALLOW,
            "button_text" to nodeLabelText(candidate),
            "x" to candidate.centerX,
            "y" to candidate.centerY,
        )
    }

    private fun permissionAllowCandidate(page: PageModel): UiNode? {
        val hasPermissionPackage = page.nodes.any { node ->
            PERMISSION_PACKAGES.any { node.packageName.startsWith(it) }
        }
        if (!hasPermissionPackage) return null
        return page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.clickable }
            .mapNotNull { node ->
                val score = allowButtonScore(node)
                if (score > 0f) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun allowButtonScore(node: UiNode): Float {
        val label = nodeLabelText(node).lowercase()
        val resource = node.resourceTail.lowercase()
        val resourceScore = when {
            ALLOW_RESOURCE_TAILS.any { resource == it } -> 400f
            else -> 0f
        }
        val labelScore = when {
            ALLOW_EXACT_LABELS.any { label == it } -> 300f
            ALLOW_CONTAINS_LABELS.any { label.contains(it) } -> 150f
            else -> 0f
        }
        // Penalise "only this time" style buttons — prefer broader grants.
        val oncePenalty = if (ALLOW_ONCE_LABELS.any { label.contains(it) }) -100f else 0f
        return resourceScore + labelScore + oncePenalty
    }

    private suspend fun checkerPackageMismatch(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        if (targetLooksLikeDismiss(replayAction.args)) return null
        val expectedPkg = rule.params["package_name"]?.toString()?.trim()
            ?: stepSourcePackage(replayAction.step)
        if (expectedPkg.isBlank()) return null
        val currentPkg = state.snapshot.effectivePackage()
        if (packageMatchMode(expectedPkg, currentPkg) != null) return null
        runCatching {
            OmniflowActionRuntime.backend.launchApplication(expectedPkg, resetTask = false)
        }
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to OmniflowCheckerRule.ACTION_OPEN_APP,
            "expected_package" to expectedPkg,
            "current_package" to currentPkg,
        )
    }

    private suspend fun checkerOverlayBlocking(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        if (targetLooksLikeDismiss(replayAction.args)) return null
        val page = state.page ?: return null
        val candidate = blockingOverlayDismissCandidate(page) ?: return null
        OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to "click",
            "x" to candidate.centerX,
            "y" to candidate.centerY,
            "target_element" to summarizeNode(candidate),
        )
    }

    private suspend fun checkerKeyboardObscuring(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        val action = replayAction.action
        if (action !in setOf("click", "long_press", OobActionCodec.ACTION_SWIPE)) return null
        val page = state.page ?: return null
        val keyboardTop = keyboardTop(page) ?: return null
        if (!actionTargetIntersectsKeyboard(action, replayAction.args, keyboardTop)) return null
        OmniflowActionRuntime.backend.hideKeyboard()
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to OmniflowCheckerRule.ACTION_HIDE_KEYBOARD,
            "keyboard_top" to keyboardTop,
        )
    }

    private fun stepSourcePackage(step: Map<String, Any?>): String {
        val srcCtx = (step["source_context"] as? Map<*, *>)?.get("src_ctx") as? Map<*, *>
        val pkg = srcCtx?.get("package_name")?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return ""
        if (pkg.startsWith("cn.com.omnimind")) return ""
        if (pkg == "android" || pkg == "com.android.systemui") return ""
        if (pkg.contains("launcher", ignoreCase = true)) return ""
        return pkg
    }

    private fun packageMatchMode(expectedPackage: String, currentPackage: String): String? {
        if (expectedPackage.isBlank()) return "expected_missing"
        if (currentPackage.isBlank()) return null
        if (expectedPackage == currentPackage) return "exact"
        return if (isAndroidSystemPackageAlias(expectedPackage, currentPackage)) {
            "android_system_alias"
        } else {
            null
        }
    }

    private fun isAndroidSystemPackageAlias(expectedPackage: String, currentPackage: String): Boolean {
        val expectedTail = expectedPackage.substringAfterLast('.')
        val currentTail = currentPackage.substringAfterLast('.')
        if (expectedTail.isBlank() || expectedTail != currentTail) return false
        val expectedSystem = expectedPackage.startsWith("com.android.") ||
            expectedPackage.startsWith("com.google.android.")
        val currentSystem = currentPackage.startsWith("com.android.") ||
            currentPackage.startsWith("com.google.android.")
        return expectedSystem && currentSystem
    }

    private data class BackendSnapshot(
        val xml: String,
        val rawPackage: String,
        val activityName: String,
    ) {
        fun effectivePackage(): String =
            RunLogPagePackageInference.effectivePackage(rawPackage, xml, activityName)
    }

    private suspend fun observeReplayState(
        timing: ReplayStepTiming,
        reason: String,
    ): ReplayState {
        val snapshot = readBackendSnapshot(timing)
        return ReplayState(
            snapshot = snapshot,
            page = parsePageModel(snapshot.xml),
            capturedAtMs = System.currentTimeMillis(),
            reason = reason,
        )
    }

    private suspend fun readBackendSnapshot(timing: ReplayStepTiming? = null): BackendSnapshot {
        val readBlock: suspend () -> BackendSnapshot = {
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    readBackendSnapshotDirect()
                }
            }.getOrElse {
                readBackendSnapshotDirect()
            }
        }
        return if (timing == null) {
            readBlock()
        } else {
            timing.measureObserve(readBlock)
        }
    }

    private fun readBackendSnapshotDirect(): BackendSnapshot {
        runCatching { OmniflowActionRuntime.backend.isReady() }
        val currentXml = runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        val rawPackage = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        val activityName = runCatching {
            OmniflowActionRuntime.backend.currentActivityName()?.trim().orEmpty()
        }.getOrDefault("")
        return BackendSnapshot(
            xml = currentXml,
            rawPackage = rawPackage,
            activityName = activityName,
        )
    }

    private fun recoverySnapshotMap(
        snapshot: BackendSnapshot,
        reason: String?,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "refetched_current_page" to true,
        "reason" to reason?.trim()?.takeIf { it.isNotEmpty() },
        "captured_at_ms" to System.currentTimeMillis(),
        "package_name" to snapshot.rawPackage.takeIf { it.isNotBlank() },
        "effective_package" to snapshot.effectivePackage().takeIf { it.isNotBlank() },
        "activity_name" to snapshot.activityName.takeIf { it.isNotBlank() },
        "has_observation_xml" to snapshot.xml.isNotBlank(),
        "observation_xml_length" to snapshot.xml.length,
        "observation_xml" to snapshot.xml.takeIf { it.isNotBlank() },
    ).filterValues { it != null }

    private fun blockingOverlayDismissCandidate(page: PageModel): UiNode? {
        val hasOverlayCue = page.nodes.any(::hasAdOrModalCue)
        return page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.area > 1f && it.interactive }
            .mapNotNull { node ->
                val score = dismissCandidateScore(node, page.rootBounds, hasOverlayCue)
                if (score >= MIN_DISMISS_OVERLAY_SCORE) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun dismissCandidateScore(
        node: UiNode,
        rootBounds: Rect,
        hasOverlayCue: Boolean,
    ): Float {
        val label = nodeLabelText(node)
        val resource = node.resourceTail
        val hasAdCue = hasAdOrModalCue(node)
        val dismissByLabel = DISMISS_EXACT_LABELS.any { label == it } ||
            DISMISS_CONTAINS_LABELS.any { label.contains(it) }
        val dismissByResource = DISMISS_RESOURCE_TAILS.any { resource == it || resource.contains(it) }
        if (!dismissByLabel && !dismissByResource) return 0f
        if (!hasOverlayCue && !hasAdCue) return 0f

        val rootArea = rootBounds.area.coerceAtLeast(1f)
        val relativeArea = node.area / rootArea
        val smallButtonScore = if (relativeArea <= 0.08f) 160f else -220f
        val topRightScore = if (
            node.centerX >= rootBounds.left + rootBounds.width * 0.60f &&
            node.centerY <= rootBounds.top + rootBounds.height * 0.35f
        ) {
            130f
        } else {
            0f
        }
        val labelScore = when {
            DISMISS_CONTAINS_LABELS.any { label.contains(it) } -> 520f
            DISMISS_EXACT_LABELS.any { label == it } -> 420f
            else -> 0f
        }
        val resourceScore = if (dismissByResource) 360f else 0f
        val overlayScore = if (hasAdCue) 220f else 120f
        return labelScore + resourceScore + overlayScore + smallButtonScore + topRightScore
    }

    private fun hasAdOrModalCue(node: UiNode): Boolean {
        val text = nodeLabelText(node)
        val classText = node.className.lowercase()
        val resource = node.resourceId.lowercase()
        return AD_OR_MODAL_TERMS.any { term ->
            text.contains(term) || classText.contains(term) || resource.contains(term)
        }
    }

    private fun targetLooksLikeDismiss(args: Map<String, Any?>): Boolean {
        val target = listOf(
            stringArg(args, "target_description", "targetDescription"),
            stringArg(args, "label"),
            stringArg(args, "selector"),
        ).filterNotNull().joinToString(" ").lowercase()
        return DISMISS_EXACT_LABELS.any { target == it } ||
            DISMISS_CONTAINS_LABELS.any { target.contains(it) }
    }

    private fun keyboardTop(page: PageModel): Float? {
        val rootHeight = page.rootBounds.height.coerceAtLeast(1f)
        return page.nodes
            .asSequence()
            .filter { node ->
                node.visible &&
                    node.bounds.bottom >= page.rootBounds.bottom - rootHeight * 0.04f &&
                    node.bounds.height >= rootHeight * 0.18f &&
                    nodeLabelForKeyboard(node).let { label ->
                        KEYBOARD_TERMS.any { label.contains(it) }
                    }
            }
            .minOfOrNull { it.bounds.top }
    }

    private fun actionTargetIntersectsKeyboard(
        action: String,
        args: Map<String, Any?>,
        keyboardTop: Float,
    ): Boolean {
        val threshold = keyboardTop - KEYBOARD_OBSCURE_MARGIN_PX
        if (action == OobActionCodec.ACTION_SWIPE) {
            val y1 = numberArg(args, "y1")?.toFloat()
            val y2 = numberArg(args, "y2")?.toFloat()
            return listOfNotNull(y1, y2).any { it >= threshold }
        }
        val y = numberArg(args, "y", "center_y", "centerY")?.toFloat() ?: return false
        return y >= threshold
    }

    private fun nodeLabelText(node: UiNode): String =
        listOf(node.text, node.contentDesc, node.hintText, node.resourceTail)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

    private fun nodeLabelForKeyboard(node: UiNode): String =
        listOf(
            node.text,
            node.contentDesc,
            node.hintText,
            node.resourceId,
            node.packageName,
            node.className,
        ).filter { it.isNotBlank() }.joinToString(" ").lowercase()

    private fun booleanArg(value: Any?): Boolean =
        value == true || value?.toString()?.equals("true", ignoreCase = true) == true

    private fun currentRootCenter(state: ReplayState): Pair<Float, Float>? =
        state.page?.let { it.rootBounds.centerX to it.rootBounds.centerY }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return text
            }
        }
        return ""
    }

    private data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom

        fun clampX(x: Float): Float = min(max(x, left), right)

        fun clampY(y: Float): Float = min(max(y, top), bottom)
    }

    private data class UiNode(
        val index: Int,
        val bounds: Rect,
        val className: String,
        val classSuffix: String,
        val resourceId: String,
        val resourceTail: String,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val packageName: String,
        val clickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val selected: Boolean,
        val checkable: Boolean,
    ) {
        val centerX: Float get() = bounds.centerX
        val centerY: Float get() = bounds.centerY
        val area: Float get() = bounds.area
        val interactive: Boolean get() = clickable || focusable || editable || scrollable
    }

    private data class PageModel(
        val rootBounds: Rect,
        val nodes: List<UiNode>,
    )

    private data class AnchorPair(
        val source: UiNode,
        val target: UiNode,
        val similarity: Float,
    )

    private data class TargetMatch(
        val node: UiNode,
        val confidence: Float,
        val anchorCount: Int,
        val mode: String,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private data class PointMapping(
        val newX: Float,
        val newY: Float,
        val sourceNode: UiNode,
        val targetNode: UiNode,
        val confidence: Float,
        val anchorCount: Int,
        val mode: String,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private fun remapPointActionArgs(
        tool: String,
        args: Map<String, Any?>,
        sourceXml: String,
        currentXml: String,
    ): StepArgsResult {
        val x = floatArg(args["x"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x", "algorithm" to "anchor_projection")
        )
        val y = floatArg(args["y"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y", "algorithm" to "anchor_projection")
        )
        val sourcePage = parsePageModel(sourceXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_source_page", "algorithm" to "anchor_projection")
        )
        val targetPage = parsePageModel(currentXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_current_page", "algorithm" to "anchor_projection")
        )
        val mapped = remapPointWithinPages(sourcePage, targetPage, x, y)
            ?: if (coordinateReplayAllowed(args)) {
                remapPointWithinRoots(sourcePage, targetPage, x, y)
            } else {
                null
            }
            ?: return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
            )
        return StepArgsResult(
            args = args + mapOf("x" to mapped.newX, "y" to mapped.newY),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to mapped.mode,
                "algorithm" to "anchor_projection",
                "confidence" to mapped.confidence,
                "anchor_count" to mapped.anchorCount,
                "old" to mapOf("x" to x, "y" to y),
                "new" to mapOf("x" to mapped.newX, "y" to mapped.newY),
                "source_element" to summarizeNode(mapped.sourceNode),
                "target_element" to summarizeNode(mapped.targetNode),
                "debug" to mapped.debug,
            )
        )
    }

    private fun remapScrollActionArgs(
        tool: String,
        args: Map<String, Any?>,
        sourceXml: String,
        currentXml: String,
    ): StepArgsResult {
        val x1 = floatArg(args["x1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x1", "algorithm" to "anchor_projection")
        )
        val y1 = floatArg(args["y1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y1", "algorithm" to "anchor_projection")
        )
        val x2 = floatArg(args["x2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x2", "algorithm" to "anchor_projection")
        )
        val y2 = floatArg(args["y2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y2", "algorithm" to "anchor_projection")
        )
        val sourcePage = parsePageModel(sourceXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_source_page", "algorithm" to "anchor_projection")
        )
        val targetPage = parsePageModel(currentXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_current_page", "algorithm" to "anchor_projection")
        )

        val sourceContainer = selectScrollSourceNode(sourcePage, x1, y1, x2, y2)
            ?: return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_scroll_source_element", "algorithm" to "anchor_projection")
            )
        val targetMatch = matchTargetNode(sourcePage, targetPage, sourceContainer)
            ?: return if (coordinateReplayAllowed(args)) {
                rootProjectionFallbackForScroll(tool, args, sourceContainer, sourcePage.rootBounds, targetPage.rootBounds)
            } else {
                StepArgsResult(
                    args,
                    meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
                )
            }

        val start = projectPoint(sourceContainer.bounds, targetMatch.node.bounds, x1, y1)
        val end = projectPoint(sourceContainer.bounds, targetMatch.node.bounds, x2, y2)
        return StepArgsResult(
            args = args + mapOf(
                "x1" to start.first,
                "y1" to start.second,
                "x2" to end.first,
                "y2" to end.second,
            ),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to targetMatch.mode,
                "algorithm" to "anchor_projection",
                "confidence" to targetMatch.confidence,
                "anchor_count" to targetMatch.anchorCount,
                "old" to mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2),
                "new" to mapOf(
                    "x1" to start.first,
                    "y1" to start.second,
                    "x2" to end.first,
                    "y2" to end.second,
                ),
                "source_element" to summarizeNode(sourceContainer),
                "target_element" to summarizeNode(targetMatch.node),
                "debug" to targetMatch.debug,
            )
        )
    }

    private fun remapPointWithinRoots(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceX: Float,
        sourceY: Float,
    ): PointMapping? {
        if (sourcePage.rootBounds.area <= 0f || targetPage.rootBounds.area <= 0f) {
            return null
        }
        val mapped = projectPoint(sourcePage.rootBounds, targetPage.rootBounds, sourceX, sourceY)
        return PointMapping(
            newX = mapped.first,
            newY = mapped.second,
            sourceNode = sourcePage.nodes.first(),
            targetNode = targetPage.nodes.first(),
            confidence = 0f,
            anchorCount = 0,
            mode = "root_projection_fallback",
            debug = mapOf(
                "source_root" to summarizeBounds(sourcePage.rootBounds),
                "target_root" to summarizeBounds(targetPage.rootBounds),
            )
        )
    }

    private fun coordinateReplayAllowed(args: Map<String, Any?>): Boolean =
        booleanArg(args["coordinate_replay_allowed"]) ||
            booleanArg(args["coordinateReplayAllowed"]) ||
            booleanArg(args["raw_coordinate_replay_allowed"]) ||
            booleanArg(args["allow_raw_coordinate_replay"]) ||
            stringArg(args, "projection_mode", "projectionMode")
                ?.equals("fixed", ignoreCase = true) == true

    private fun rootProjectionFallbackForScroll(
        tool: String,
        args: Map<String, Any?>,
        sourceContainer: UiNode,
        sourceRoot: Rect,
        targetRoot: Rect,
    ): StepArgsResult {
        val x1 = floatArg(args["x1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x1", "algorithm" to "anchor_projection")
        )
        val y1 = floatArg(args["y1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y1", "algorithm" to "anchor_projection")
        )
        val x2 = floatArg(args["x2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x2", "algorithm" to "anchor_projection")
        )
        val y2 = floatArg(args["y2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y2", "algorithm" to "anchor_projection")
        )
        if (sourceRoot.area <= 0f || targetRoot.area <= 0f) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
            )
        }
        val start = projectPoint(sourceRoot, targetRoot, x1, y1)
        val end = projectPoint(sourceRoot, targetRoot, x2, y2)
        return StepArgsResult(
            args = args + mapOf(
                "x1" to start.first,
                "y1" to start.second,
                "x2" to end.first,
                "y2" to end.second,
            ),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to "root_projection_fallback",
                "algorithm" to "root_projection",
                "confidence" to 0f,
                "anchor_count" to 0,
                "old" to mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2),
                "new" to mapOf(
                    "x1" to start.first,
                    "y1" to start.second,
                    "x2" to end.first,
                    "y2" to end.second,
                ),
                "source_element" to summarizeNode(sourceContainer),
                "target_element" to mapOf(
                    "bounds" to summarizeBounds(targetRoot),
                    "fallback" to true,
                ),
                "debug" to mapOf(
                    "source_root" to summarizeBounds(sourceRoot),
                    "target_root" to summarizeBounds(targetRoot),
                ),
            )
        )
    }

    private fun remapPointWithinPages(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceX: Float,
        sourceY: Float,
    ): PointMapping? {
        val sourceNode = selectPointSourceNode(sourcePage, sourceX, sourceY) ?: return null
        val targetMatch = matchTargetNode(sourcePage, targetPage, sourceNode) ?: return null
        val mapped = projectPoint(sourceNode.bounds, targetMatch.node.bounds, sourceX, sourceY)
        return PointMapping(
            newX = mapped.first,
            newY = mapped.second,
            sourceNode = sourceNode,
            targetNode = targetMatch.node,
            confidence = targetMatch.confidence,
            anchorCount = targetMatch.anchorCount,
            mode = targetMatch.mode,
            debug = targetMatch.debug,
        )
    }

    private fun matchTargetNode(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? {
        val anchors = buildAnchors(sourcePage, targetPage)
        if (anchors.isEmpty()) {
            val fallback = directSimilarityFallback(targetPage, sourceNode) ?: return null
            return fallback.copy(
                debug = fallback.debug + mapOf(
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to 0,
                )
            )
        }

        val pageDiagonal = hypot(targetPage.rootBounds.width, targetPage.rootBounds.height).coerceAtLeast(1f)
        val scaleX = targetPage.rootBounds.width / (sourcePage.rootBounds.width + 1e-6f)
        val scaleY = targetPage.rootBounds.height / (sourcePage.rootBounds.height + 1e-6f)

        var bestNode: UiNode? = null
        var bestDirect = 0f
        var bestSpatial = 0f
        var bestScore = 0f
        var bestVotes: List<Map<String, Any?>> = emptyList()

        for (candidate in targetPage.nodes) {
            val directSimilarity = nodeSimilarity(sourceNode, candidate)
            if (directSimilarity <= 0f) continue

            val votes = mutableListOf<Map<String, Any?>>()
            var contributionSum = 0f
            for ((anchorIndex, anchor) in anchors.withIndex()) {
                val predictedX = anchor.target.centerX + (sourceNode.centerX - anchor.source.centerX) * scaleX
                val predictedY = anchor.target.centerY + (sourceNode.centerY - anchor.source.centerY) * scaleY
                val distance = hypot(predictedX - candidate.centerX, predictedY - candidate.centerY)
                val geometryScore = max(0f, 1f - (distance / pageDiagonal))
                val contribution = anchor.similarity * geometryScore
                contributionSum += contribution
                votes += mapOf(
                    "anchor_index" to anchorIndex,
                    "anchor_similarity" to anchor.similarity,
                    "geometry_score" to geometryScore,
                    "contribution" to contribution,
                    "predicted_point" to mapOf("x" to predictedX, "y" to predictedY),
                )
            }
            if (votes.isEmpty()) continue

            val spatialScore = contributionSum / votes.size.toFloat()
            val matchScore = directSimilarity * spatialScore
            if (matchScore > bestScore) {
                bestNode = candidate
                bestDirect = directSimilarity
                bestSpatial = spatialScore
                bestScore = matchScore
                bestVotes = votes.sortedByDescending {
                    (it["contribution"] as? Number)?.toFloat() ?: 0f
                }.take(5)
            }
        }

        if (bestNode == null || bestScore < MIN_ANCHOR_MATCH_SCORE) {
            val fallback = directSimilarityFallback(targetPage, sourceNode) ?: return null
            return fallback.copy(
                debug = fallback.debug + mapOf(
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to anchors.size,
                    "anchor_fallback" to true,
                )
            )
        }

        return TargetMatch(
            node = bestNode,
            confidence = bestScore,
            anchorCount = anchors.size,
            mode = "anchor_projection",
            debug = mapOf(
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(bestNode),
                "anchor_count" to anchors.size,
                "direct_similarity" to bestDirect,
                "spatial_score" to bestSpatial,
                "match_score" to bestScore,
                "anchors" to anchors.take(5).map {
                    mapOf(
                        "source" to summarizeNode(it.source),
                        "target" to summarizeNode(it.target),
                        "similarity" to it.similarity,
                    )
                },
                "top_votes" to bestVotes,
            )
        )
    }

    private fun directSimilarityFallback(
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? {
        val best = targetPage.nodes
            .map { candidate -> candidate to nodeSimilarity(sourceNode, candidate) }
            .maxByOrNull { it.second }
            ?: return null
        if (best.second < MIN_DIRECT_FALLBACK_SIMILARITY) {
            return null
        }
        return TargetMatch(
            node = best.first,
            confidence = best.second,
            anchorCount = 0,
            mode = "direct_similarity_fallback",
            debug = mapOf(
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(best.first),
                "direct_similarity" to best.second,
                "anchor_count" to 0,
            )
        )
    }

    private fun buildAnchors(
        sourcePage: PageModel,
        targetPage: PageModel,
        maxAnchorCount: Int = MAX_ANCHOR_COUNT,
    ): List<AnchorPair> {
        val sourceNodes = sourcePage.nodes.filter { isAnchorCandidate(it, sourcePage.rootBounds) }
        val targetNodes = targetPage.nodes.filter { isAnchorCandidate(it, targetPage.rootBounds) }
        if (sourceNodes.isEmpty() || targetNodes.isEmpty()) {
            return emptyList()
        }

        val bestSourceByTarget = mutableMapOf<Int, Pair<UiNode, Float>>()
        for (target in targetNodes) {
            var bestSource: UiNode? = null
            var bestSimilarity = 0f
            for (source in sourceNodes) {
                val similarity = nodeSimilarity(source, target)
                if (similarity > bestSimilarity) {
                    bestSource = source
                    bestSimilarity = similarity
                }
            }
            if (bestSource != null) {
                bestSourceByTarget[target.index] = bestSource to bestSimilarity
            }
        }

        val anchors = mutableListOf<AnchorPair>()
        for (source in sourceNodes) {
            var bestTarget: UiNode? = null
            var bestSimilarity = 0f
            for (target in targetNodes) {
                val similarity = nodeSimilarity(source, target)
                if (similarity > bestSimilarity) {
                    bestTarget = target
                    bestSimilarity = similarity
                }
            }
            val reciprocal = bestTarget?.let { target ->
                bestSourceByTarget[target.index]?.first?.index == source.index
            } == true
            if (bestTarget != null && reciprocal && bestSimilarity >= MIN_ANCHOR_SIMILARITY) {
                anchors += AnchorPair(source, bestTarget, bestSimilarity)
            }
        }

        return anchors
            .sortedByDescending { it.similarity }
            .take(maxAnchorCount)
    }

    private fun selectPointSourceNode(
        page: PageModel,
        x: Float,
        y: Float,
    ): UiNode? {
        val containing = page.nodes
            .filter { it.bounds.contains(x, y) }
            .sortedBy { it.area }
        if (containing.isEmpty()) {
            return null
        }
        return containing.firstOrNull { it.interactive } ?: containing.first()
    }

    private fun selectScrollSourceNode(
        page: PageModel,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): UiNode? {
        val containingBoth = page.nodes
            .filter { it.bounds.contains(x1, y1) && it.bounds.contains(x2, y2) }
            .sortedBy { it.area }
        containingBoth.firstOrNull { it.scrollable }?.let { return it }
        containingBoth.firstOrNull { it.interactive }?.let { return it }
        containingBoth.firstOrNull()?.let { return it }
        return selectPointSourceNode(page, (x1 + x2) / 2f, (y1 + y2) / 2f)
    }

    private fun projectPoint(
        sourceBounds: Rect,
        targetBounds: Rect,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val relativeX = if (sourceBounds.width <= 1e-3f) {
            0.5f
        } else {
            ((x - sourceBounds.left) / sourceBounds.width).coerceIn(0f, 1f)
        }
        val relativeY = if (sourceBounds.height <= 1e-3f) {
            0.5f
        } else {
            ((y - sourceBounds.top) / sourceBounds.height).coerceIn(0f, 1f)
        }
        val newX = targetBounds.clampX(targetBounds.left + targetBounds.width * relativeX)
        val newY = targetBounds.clampY(targetBounds.top + targetBounds.height * relativeY)
        return newX to newY
    }

    private fun parsePageModel(xml: String): PageModel? {
        val root = parseXmlRoot(xml) ?: return null
        val nodes = mutableListOf<UiNode>()
        val elements = root.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            val bounds = parseBounds(element.getAttribute("bounds")) ?: continue
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val className = element.stringAttr("class-name").ifEmpty {
                element.stringAttr("class")
            }
            val resourceId = element.stringAttr("resource-id")
            nodes += UiNode(
                index = i,
                bounds = bounds,
                className = className,
                classSuffix = classSuffix(className),
                resourceId = resourceId,
                resourceTail = resourceTail(resourceId),
                text = normalizeText(element.getAttribute("text")),
                contentDesc = normalizeText(element.getAttribute("content-desc")),
                hintText = normalizeText(element.getAttribute("hint-text")),
                packageName = normalizeText(element.getAttribute("package")),
                clickable = element.boolAttr("clickable"),
                focusable = element.boolAttr("focusable"),
                editable = element.boolAttr("editable"),
                scrollable = element.boolAttr("scrollable"),
                enabled = element.boolAttr("enabled", defaultValue = true),
                visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                    element.boolAttr("displayed", defaultValue = true),
                selected = element.boolAttr("selected"),
                checkable = element.boolAttr("checkable"),
            )
        }
        if (nodes.isEmpty()) {
            return null
        }
        val rootBounds = parseBounds(root.getAttribute("bounds")) ?: inferRootBounds(nodes)
        return PageModel(rootBounds = rootBounds, nodes = nodes)
    }

    private fun parseXmlRoot(xml: String): Element? {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
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
            val builder = factory.newDocumentBuilder()
            builder.parse(InputSource(StringReader(xml))).documentElement
        }.getOrNull()
    }

    private fun inferRootBounds(nodes: List<UiNode>): Rect {
        val left = nodes.minOf { it.bounds.left }
        val top = nodes.minOf { it.bounds.top }
        val right = nodes.maxOf { it.bounds.right }
        val bottom = nodes.maxOf { it.bounds.bottom }
        return Rect(left, top, right, bottom)
    }

    private fun nodeSimilarity(source: UiNode, target: UiNode): Float {
        var score = 0f
        var total = 0f

        fun add(weight: Float, contribution: Float) {
            total += weight
            score += weight * contribution.coerceIn(0f, 1f)
        }

        if (source.resourceId.isNotBlank()) {
            add(
                6f,
                resourceAffinity(source, target)
            )
        }
        if (source.text.isNotBlank()) {
            add(4.5f, textAffinity(source.text, target.text))
        }
        if (source.contentDesc.isNotBlank()) {
            add(3.5f, textAffinity(source.contentDesc, target.contentDesc))
        }
        if (source.hintText.isNotBlank()) {
            add(2.5f, textAffinity(source.hintText, target.hintText))
        }
        add(2f, classAffinity(source.className, target.className, source.classSuffix, target.classSuffix))
        add(1.5f, interactionAffinity(source, target))
        add(1f, geometryAffinity(source.bounds, target.bounds))

        if (total <= 1e-6f) {
            return 0f
        }
        return (score / total).coerceIn(0f, 1f)
    }

    private fun resourceAffinity(source: UiNode, target: UiNode): Float {
        val generic = isGenericResourceId(source.resourceId)
        return when {
            source.resourceId == target.resourceId -> if (generic) 0.25f else 1f
            source.resourceTail.isNotBlank() && source.resourceTail == target.resourceTail ->
                if (generic || isGenericResourceId(target.resourceId)) 0.18f else 0.72f
            else -> 0f
        }
    }

    private fun isGenericResourceId(resourceId: String): Boolean {
        val tail = resourceTail(resourceId)
        if (tail.isBlank()) return false
        if (resourceId.startsWith("android:id/")) {
            return tail in GENERIC_RESOURCE_TAILS
        }
        return tail in GENERIC_RESOURCE_TAILS
    }

    private fun textAffinity(source: String, target: String): Float {
        if (source.isBlank() || target.isBlank()) {
            return 0f
        }
        if (source == target) {
            return 1f
        }
        if (source.contains(target) || target.contains(source)) {
            val shorter = min(source.length, target.length).toFloat()
            val longer = max(source.length, target.length).toFloat().coerceAtLeast(1f)
            return (0.72f + 0.28f * (shorter / longer)).coerceIn(0f, 1f)
        }
        val sourceTokens = source.split(' ').filter { it.isNotBlank() }.toSet()
        val targetTokens = target.split(' ').filter { it.isNotBlank() }.toSet()
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0f
        }
        val intersect = sourceTokens.intersect(targetTokens).size.toFloat()
        val union = sourceTokens.union(targetTokens).size.toFloat().coerceAtLeast(1f)
        return (intersect / union).coerceIn(0f, 1f)
    }

    private fun classAffinity(
        sourceClass: String,
        targetClass: String,
        sourceSuffix: String,
        targetSuffix: String,
    ): Float {
        if (sourceClass.isBlank() || targetClass.isBlank()) {
            return 0f
        }
        return when {
            sourceClass == targetClass -> 1f
            sourceSuffix.isNotBlank() && sourceSuffix == targetSuffix -> 0.85f
            else -> 0f
        }
    }

    private fun interactionAffinity(source: UiNode, target: UiNode): Float {
        val signals = listOf(
            source.clickable to target.clickable,
            source.focusable to target.focusable,
            source.editable to target.editable,
            source.scrollable to target.scrollable,
            source.checkable to target.checkable,
        )
        val expected = signals.count { it.first }
        if (expected == 0) {
            return if (source.interactive == target.interactive) 0.5f else 0f
        }
        val matched = signals.count { it.first && it.second }
        return matched.toFloat() / expected.toFloat()
    }

    private fun geometryAffinity(source: Rect, target: Rect): Float {
        val sourceAspect = source.width / source.height.coerceAtLeast(1e-3f)
        val targetAspect = target.width / target.height.coerceAtLeast(1e-3f)
        val aspect = min(sourceAspect, targetAspect) / max(targetAspect, sourceAspect).coerceAtLeast(1e-3f)
        val sourceArea = source.area.coerceAtLeast(1f)
        val targetArea = target.area.coerceAtLeast(1f)
        val area = min(sourceArea, targetArea) / max(sourceArea, targetArea)
        return ((aspect + area) / 2f).coerceIn(0f, 1f)
    }

    private fun isAnchorCandidate(node: UiNode, rootBounds: Rect): Boolean {
        if (!node.visible || !node.enabled || node.area <= 1f) {
            return false
        }
        val rootArea = rootBounds.area.coerceAtLeast(1f)
        val fullScreenLike = node.area / rootArea >= 0.96f
        if (fullScreenLike && node.resourceId.isBlank() && node.text.isBlank() && node.contentDesc.isBlank()) {
            return false
        }
        return node.interactive || node.resourceId.isNotBlank() || node.text.isNotBlank() || node.contentDesc.isNotBlank()
    }

    private fun summarizeNode(node: UiNode): Map<String, Any?> = mapOf(
        "index" to node.index,
        "bounds" to listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom),
        "class" to node.className,
        "resource_id" to node.resourceId,
        "text" to node.text,
        "content_desc" to node.contentDesc,
        "scrollable" to node.scrollable,
        "clickable" to node.clickable,
        "editable" to node.editable,
    )

    private fun summarizeBounds(bounds: Rect): Map<String, Any?> = mapOf(
        "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
        "center_x" to bounds.centerX,
        "center_y" to bounds.centerY,
        "width" to bounds.width,
        "height" to bounds.height,
    )

    private fun parseBounds(bounds: String?): Rect? {
        val text = bounds?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val match = BOUNDS_REGEX.find(text) ?: return null
        val left = match.groupValues[1].toFloatOrNull() ?: return null
        val top = match.groupValues[2].toFloatOrNull() ?: return null
        val right = match.groupValues[3].toFloatOrNull() ?: return null
        val bottom = match.groupValues[4].toFloatOrNull() ?: return null
        if (right <= left || bottom <= top) {
            return null
        }
        return Rect(left, top, right, bottom)
    }

    private fun Element.stringAttr(name: String): String = getAttribute(name).trim()

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean {
        val value = getAttribute(name)?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) {
            return defaultValue
        }
        return value == "true" || value == "1" || value == "yes"
    }

    private fun normalizeText(value: String?): String =
        value.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    private fun classSuffix(className: String): String =
        className.substringAfterLast('.').lowercase()

    private fun resourceTail(resourceId: String): String {
        if (resourceId.isBlank()) {
            return ""
        }
        return resourceId.substringAfterLast('/').substringAfterLast(':').lowercase()
    }

    private fun floatArg(value: Any?): Float? =
        when (value) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull()
            else -> null
        }

    private class ReplayStepTiming {
        private val startedAtNanos = System.nanoTime()
        private val phaseNanos = linkedMapOf<String, Long>()
        private val overheadNanos = linkedMapOf<String, Long>()
        private var observedNanos = 0L
        val startedAtMs: Long = System.currentTimeMillis()

        suspend fun <T> measure(phaseName: String, block: suspend () -> T): T {
            val startedAt = System.nanoTime()
            val observedBefore = observedNanos
            return try {
                block()
            } finally {
                val elapsed = elapsedNanos(startedAt)
                val nestedObserve = (observedNanos - observedBefore).coerceAtLeast(0L)
                addNanos(phaseNanos, phaseName, (elapsed - nestedObserve).coerceAtLeast(0L))
            }
        }

        suspend fun <T> measureObserve(block: suspend () -> T): T {
            val startedAt = System.nanoTime()
            return try {
                block()
            } finally {
                val elapsed = elapsedNanos(startedAt)
                observedNanos += elapsed
                addNanos(phaseNanos, "observe_ms", elapsed)
            }
        }

        suspend fun <T> measureOverhead(phaseName: String, block: suspend () -> T): T {
            val startedAt = System.nanoTime()
            return try {
                block()
            } finally {
                addNanos(overheadNanos, phaseName, elapsedNanos(startedAt))
            }
        }

        fun finish(): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val phases = linkedMapOf<String, Long>()
            REPLAY_STEP_PHASE_NAMES.forEach { phaseName ->
                phases[phaseName] = nanosToMs(phaseNanos[phaseName] ?: 0L)
            }
            phaseNanos.forEach { (phaseName, durationNanos) ->
                phases.putIfAbsent(phaseName, nanosToMs(durationNanos))
            }
            val overhead = overheadNanos.mapValues { (_, durationNanos) -> nanosToMs(durationNanos) }
                .filterValues { it > 0L }
            return linkedMapOf<String, Any?>(
                "source" to "oob_omniflow_step_executor",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to nanosToMs(elapsedNanos(startedAtNanos)),
                "phase_ms" to phases,
                "overhead_ms" to overhead.takeIf { it.isNotEmpty() },
            ).filterValues { it != null }
        }

        private fun addNanos(target: MutableMap<String, Long>, key: String, value: Long) {
            target[key] = (target[key] ?: 0L) + value.coerceAtLeast(0L)
        }

        private fun elapsedNanos(startedAtNanos: Long): Long =
            (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)

        private fun nanosToMs(nanos: Long): Long =
            (nanos / 1_000_000L).coerceAtLeast(0L)
    }

    private const val POST_STEP_DELAY_MS = 1000L
    private const val PRE_ACTION_CONTROL_DELAY_MS = 300L
    private const val DEFAULT_SCREEN_CENTER_X = 540f
    private const val DEFAULT_SCREEN_CENTER_Y = 960f
    private const val DEFAULT_SWIPE_DISTANCE = 600f
    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private const val MAX_ANCHOR_COUNT = 5
    private const val MIN_ANCHOR_SIMILARITY = 0.45f
    private const val MIN_ANCHOR_MATCH_SCORE = 0.12f
    private const val MIN_DIRECT_FALLBACK_SIMILARITY = 0.86f
    private const val MIN_DISMISS_OVERLAY_SCORE = 760f
    private const val KEYBOARD_OBSCURE_MARGIN_PX = 16f
    private const val OPEN_APP_STABILITY_ATTEMPTS = 5
    private const val OPEN_APP_STABILITY_DELAY_MS = 350L
    private const val OPEN_APP_RELAUNCH_ATTEMPT_INDEX = 1
    private val REPLAY_STEP_PHASE_NAMES = listOf(
        "observe_ms",
        "checker_ms",
        "action_transfer_ms",
        "act_ms",
    )
    private val GENERIC_RESOURCE_TAILS = setOf(
        "title",
        "summary",
        "content",
        "content_parent",
        "content_frame",
        "main_content",
        "container_material",
        "list_container",
        "recycler_view",
        "icon",
        "icon_frame",
        "widget_frame",
    )
    private val AD_OR_MODAL_TERMS = setOf(
        "advert",
        "sponsor",
        "promo",
        "promotion",
        "dialog",
        "popup",
        "modal",
        "广告",
        "推广",
        "赞助",
        "弹窗",
    )
    private val DISMISS_EXACT_LABELS = setOf(
        "close",
        "dismiss",
        "skip",
        "x",
        "×",
        "关闭",
        "跳过",
    )
    private val DISMISS_CONTAINS_LABELS = setOf(
        "close ad",
        "close ads",
        "skip ad",
        "skip ads",
        "dismiss ad",
        "not now",
        "关闭广告",
        "跳过广告",
        "关闭弹窗",
        "稍后再说",
        "以后再说",
    )
    private val DISMISS_RESOURCE_TAILS = setOf(
        "close",
        "close_button",
        "btn_close",
        "iv_close",
        "dismiss",
        "skip",
        "skip_ad",
        "ad_close",
        "close_ad",
    )
    private val KEYBOARD_TERMS = setOf(
        "keyboard",
        "inputmethod",
        "input_method",
        "latin",
        "gboard",
        "softinput",
        "软键盘",
        "键盘",
    )

    private val PERMISSION_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
    )

    private val ALLOW_EXACT_LABELS = setOf(
        "允许", "allow", "始终允许", "always allow",
        "authorize", "授权", "同意", "agree",
    )

    private val ALLOW_CONTAINS_LABELS = setOf("允许", "allow")

    // "仅此一次" / "one time" style — valid allow but deprioritised vs broader grants.
    private val ALLOW_ONCE_LABELS = setOf("仅此一次", "one time", "once")

    private val ALLOW_RESOURCE_TAILS = setOf(
        "permission_allow_button",
        "permission_allow_one_time_button",
        "permission_allow_foreground_only_button",
        "allow_button",
        "btn_allow",
    )
}
