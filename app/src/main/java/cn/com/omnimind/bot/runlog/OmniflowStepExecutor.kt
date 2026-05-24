package cn.com.omnimind.bot.runlog

import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.delay
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

    fun isOmniflowStep(step: Map<String, Any?>): Boolean {
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val modelFree = step["model_free"] == true ||
            step["modelFree"] == true ||
            step["model_free"]?.toString()?.equals("true", ignoreCase = true) == true
        val action = actionNameForStep(step)
        return action in RunLogReplayPolicy.omniflowActions &&
            (executor == "omniflow" || modelFree)
    }

    fun actionNameForStep(step: Map<String, Any?>): String {
        val raw = firstNonBlank(
            step["omniflow_action"],
            step["local_action"],
            step["tool"],
            step["callable_tool"]
        )
        return RunLogReplayPolicy.omniflowActionForToolName(raw)
            ?: RunLogReplayPolicy.normalizeToolName(raw)
    }

    fun normalizeArgsMap(rawArgs: Any?): Map<String, Any?> =
        when (rawArgs) {
            is Map<*, *> -> rawArgs.entries.associate { (key, value) -> key.toString() to value }
            else -> emptyMap()
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
    ): Map<String, Any?> {
        val action = actionNameForStep(step)
        if (action !in RunLogReplayPolicy.omniflowActions) {
            throw IllegalArgumentException("Unsupported omniflow action: $action")
        }
        val backend = OmniflowActionRuntime.backend
        if (action.requiresAccessibility() && !backend.isReady()) {
            throw IllegalStateException("OmniFlow action backend is not ready")
        }
        preActionSatisfiedPostcondition(step, action)?.let { postcondition ->
            return linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to action,
                "executor" to "omniflow",
                "model_free" to true,
                "success" to true,
                "skipped" to true,
                "skip_reason" to "pre_action_postcondition_satisfied",
                "summary" to (stepTitle.takeIf { it.isNotBlank() } ?: action),
                "postcondition" to postcondition,
            )
        }
        val remapResult = remapStepArgsWithRetries(step)
        if (action in RunLogReplayPolicy.coordinateActions && shouldUseCoordinateHook(step)) {
            val applied = remapResult.meta["applied"] as? Boolean
            if (applied == false) {
                val reason = remapResult.meta["reason"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "coordinate_remap_unavailable"
                throw IllegalStateException("Coordinate remap failed: $reason")
            }
        }
        val args = normalizeArgsMap(remapResult.args)
        val summary = when (action) {
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

            "scroll", "swipe" -> {
                val swipe = swipeSpec(args)
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

            "type", "input_text" -> {
                val text = stringArg(args, "content", "text", "value")
                    ?: throw IllegalArgumentException("$action requires content")
                backend.inputTextToFocusedNode(text)
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
                stabilizeOpenAppLaunch(packageName, resetTask)
                "open_app"
            }

            "press_home", "press_back", "hot_key", "press_key" -> {
                val key = stringArg(args, "key", "hotkey", "hot_key")
                    ?: when (action) {
                        "press_home" -> "HOME"
                        "press_back" -> "BACK"
                        else -> throw IllegalArgumentException("$action requires key")
                    }
                backend.pressHotKey(key)
                action
            }

            "finished" -> "finished"

            else -> throw IllegalArgumentException("Unsupported omniflow action: $action")
        }
        delay(POST_STEP_DELAY_MS)
        val postcondition = verifyPostcondition(step, action, args)
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to action,
            "executor" to "omniflow",
            "model_free" to true,
            "success" to true,
            "summary" to (stepTitle.takeIf { it.isNotBlank() } ?: summary)
        ).apply {
            if (postcondition.isNotEmpty()) {
                put("postcondition", postcondition)
            }
        }
    }

    fun remapStepArgs(step: Map<String, Any?>): StepArgsResult {
        val rawArgs = step["args"]
        val args = (rawArgs as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
            ?: return StepArgsResult(rawArgs)
        if (!shouldUseCoordinateHook(step)) {
            return StepArgsResult(args)
        }
        val tool = actionNameForStep(step)
        if (tool !in RunLogReplayPolicy.coordinateActions) {
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
        val currentXml = readCurrentXmlForCoordinateRemap()
        if (currentXml.isEmpty()) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_current_xml", "algorithm" to "anchor_projection")
            )
        }
        return when (tool) {
            "click", "long_press" -> remapPointActionArgs(tool, args, sourceXml, currentXml)
            "scroll", "swipe" -> remapScrollActionArgs(tool, args, sourceXml, currentXml)
            else -> StepArgsResult(args)
        }
    }

    private suspend fun remapStepArgsWithRetries(step: Map<String, Any?>): StepArgsResult {
        var last = remapStepArgs(step)
        if (!shouldRetryCoordinateRemap(last)) {
            return last
        }
        repeat(COORDINATE_REMAP_XML_READ_ATTEMPTS - 1) {
            delay(COORDINATE_REMAP_XML_RETRY_DELAY_MS)
            last = remapStepArgs(step)
            if (!shouldRetryCoordinateRemap(last)) {
                return last
            }
        }
        return last
    }

    private fun readCurrentXmlForCoordinateRemap(): String {
        runCatching { OmniflowActionRuntime.backend.isReady() }
        return runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun shouldRetryCoordinateRemap(result: StepArgsResult): Boolean =
        result.meta["applied"] == false &&
            result.meta["reason"]?.toString() in RETRYABLE_COORDINATE_REMAP_REASONS

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

    private fun swipeSpec(args: Map<String, Any?>): SwipeSpec {
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
        val rootCenter = currentRootCenter()
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

    private suspend fun stabilizeOpenAppLaunch(packageName: String, resetTask: Boolean) {
        if (packageName.isBlank()) return
        var lastPackage = effectiveCurrentPackage()
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
                lastPackage = effectiveCurrentPackage()
            }
        }
    }

    private fun effectiveCurrentPackage(): String {
        val currentXml = runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        val rawPackage = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        return RunLogPagePackageInference.effectivePackage(rawPackage, currentXml)
    }

    private suspend fun verifyPostcondition(
        step: Map<String, Any?>,
        action: String = actionNameForStep(step),
        args: Map<String, Any?> = normalizeArgsMap(step["args"]),
    ): Map<String, Any?> {
        if (action == "open_app") {
            return verifyOpenAppPackagePostcondition(args)
        }
        val postcondition = normalizeArgsMap(step["postcondition"])
        if (postcondition.isEmpty()) return emptyMap()
        val kind = postcondition["kind"]?.toString()?.trim().orEmpty()
        if (kind != "recorded_after_page_similarity") return emptyMap()
        val sourceContext = (step["source_context"] as? Map<*, *>)
            ?: (normalizeArgsMap(step["args"])["source_context"] as? Map<*, *>)
            ?: throw IllegalStateException("Postcondition failed: missing_source_context")
        val dstCtx = sourceContext["dst_ctx"] as? Map<*, *>
            ?: throw IllegalStateException("Postcondition failed: missing_recorded_after_context")
        val expectedXml = firstNonBlank(
            dstCtx["page"],
            dstCtx["xml"],
        )
        if (expectedXml.isEmpty()) {
            throw IllegalStateException("Postcondition failed: missing_recorded_after_xml")
        }
        val recordedExpectedPackage = firstNonBlank(dstCtx["package_name"], dstCtx["packageName"])
        val expectedPackage = RunLogPagePackageInference.effectivePackage(
            recordedExpectedPackage,
            expectedXml,
        )
        val minScore = numberArg(postcondition, "min_score", "minScore")?.toFloat()
            ?: MIN_POSTCONDITION_PAGE_SCORE
        val requiredScore = postconditionRequiredScore(expectedXml, minScore)
        val observation = readCurrentObservationForPostcondition(
            expectedXml = expectedXml,
            expectedPackage = expectedPackage,
            minScore = requiredScore,
        )
        val currentXml = observation.xml
        if (currentXml.isEmpty()) {
            throw IllegalStateException("Postcondition failed: missing_current_xml")
        }
        val score = observation.score
        val currentPackage = observation.packageName
        val packageMatchMode = observation.packageMatchMode
        val packageMatched = packageMatchMode != null
        if (!packageMatched || score < requiredScore) {
            transientSettingsSearchPostcondition(
                step = step,
                postcondition = postcondition,
                expectedXml = expectedXml,
                currentXml = currentXml,
                expectedPackage = expectedPackage,
                currentPackage = currentPackage,
                packageMatched = packageMatched,
                score = score,
                minScore = requiredScore,
            )?.let { return it }
            throw IllegalStateException(
                "Postcondition failed: page_similarity=$score min=$requiredScore " +
                    "package_matched=$packageMatched expected_package=$expectedPackage current_package=$currentPackage"
            )
        }
        return linkedMapOf(
            "kind" to kind,
            "success" to true,
            "score" to score,
            "min_score" to minScore,
            "required_score" to requiredScore.takeIf { it > minScore },
            "package_matched" to packageMatched,
            "package_match_mode" to packageMatchMode.takeIf {
                it != "exact" && it != "expected_missing" && it != "current_missing"
            },
            "expected_package" to expectedPackage.takeIf { it.isNotBlank() },
            "recorded_expected_package" to recordedExpectedPackage
                .takeIf { it.isNotBlank() && it != expectedPackage },
            "current_package" to currentPackage.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
    }

    private fun preActionSatisfiedPostcondition(
        step: Map<String, Any?>,
        action: String,
    ): Map<String, Any?>? {
        if (action == "open_app" || action == "input_text" || action == "type") return null
        val postcondition = normalizeArgsMap(step["postcondition"])
        if (postcondition["kind"]?.toString()?.trim() != "recorded_after_page_similarity") return null
        val sourceContext = (step["source_context"] as? Map<*, *>)
            ?: (normalizeArgsMap(step["args"])["source_context"] as? Map<*, *>)
            ?: return null
        val dstCtx = sourceContext["dst_ctx"] as? Map<*, *> ?: return null
        val expectedXml = firstNonBlank(
            dstCtx["page"],
            dstCtx["xml"],
        )
        if (expectedXml.isBlank()) return null
        val recordedExpectedPackage = firstNonBlank(dstCtx["package_name"], dstCtx["packageName"])
        val expectedPackage = RunLogPagePackageInference.effectivePackage(
            recordedExpectedPackage,
            expectedXml,
        )
        val minScore = numberArg(postcondition, "min_score", "minScore")?.toFloat()
            ?: MIN_POSTCONDITION_PAGE_SCORE
        val requiredScore = postconditionRequiredScore(expectedXml, minScore)
        val observation = readCurrentObservationSnapshot(
            expectedXml = expectedXml,
            expectedPackage = expectedPackage,
        ) ?: return null
        val preActionRequiredScore = max(requiredScore, PRE_ACTION_ALREADY_SATISFIED_MIN_SCORE)
        if (observation.packageMatchMode == null || observation.score < preActionRequiredScore) return null

        val srcCtx = sourceContext["src_ctx"] as? Map<*, *>
        val sourceXml = firstNonBlank(
            srcCtx?.get("page"),
            srcCtx?.get("xml"),
            sourceContext["page"],
            sourceContext["xml"],
        )
        val sourceScore = if (sourceXml.isNotBlank()) {
            pageSimilarityScore(sourceXml, observation.xml)
        } else {
            0f
        }
        if (sourceXml.isNotBlank()) {
            val sourcePackage = RunLogPagePackageInference.effectivePackage(
                firstNonBlank(srcCtx?.get("package_name"), srcCtx?.get("packageName")),
                sourceXml,
            )
            val sourcePackageMatched = packageMatchMode(sourcePackage, observation.packageName) != null
            if (sourcePackageMatched &&
                sourceScore >= observation.score - PRE_ACTION_ALREADY_SATISFIED_SOURCE_MARGIN
            ) {
                return null
            }
        }

        return linkedMapOf(
            "kind" to "recorded_after_page_similarity",
            "success" to true,
            "score" to observation.score,
            "min_score" to minScore,
            "required_score" to preActionRequiredScore.takeIf { it > minScore },
            "package_matched" to true,
            "package_match_mode" to observation.packageMatchMode.takeIf {
                it != "exact" && it != "expected_missing" && it != "current_missing"
            },
            "expected_package" to expectedPackage.takeIf { it.isNotBlank() },
            "recorded_expected_package" to recordedExpectedPackage
                .takeIf { it.isNotBlank() && it != expectedPackage },
            "current_package" to observation.packageName.takeIf { it.isNotBlank() },
            "pre_action_satisfied" to true,
            "source_score" to sourceScore.takeIf { sourceXml.isNotBlank() },
        ).filterValues { it != null }
    }

    private suspend fun verifyOpenAppPackagePostcondition(args: Map<String, Any?>): Map<String, Any?> {
        val expectedPackage = stringArg(args, "package_name", "packageName")?.trim().orEmpty()
        if (expectedPackage.isEmpty()) return emptyMap()
        var lastPackage = ""
        repeat(POSTCONDITION_XML_READ_ATTEMPTS) { index ->
            runCatching { OmniflowActionRuntime.backend.isReady() }
            lastPackage = runCatching {
                val currentXml = OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
                val rawPackage = OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
                RunLogPagePackageInference.effectivePackage(rawPackage, currentXml)
            }.getOrDefault(lastPackage)
            val packageMatchMode = packageMatchMode(expectedPackage, lastPackage)
            if (packageMatchMode != null) {
                return linkedMapOf(
                    "kind" to "open_app_package",
                    "success" to true,
                    "package_matched" to true,
                    "package_match_mode" to packageMatchMode.takeIf {
                        it != "exact" && it != "expected_missing" && it != "current_missing"
                    },
                    "expected_package" to expectedPackage,
                    "current_package" to lastPackage.takeIf { it.isNotBlank() },
                ).filterValues { it != null }
            }
            if (index < POSTCONDITION_XML_READ_ATTEMPTS - 1) {
                delay(POSTCONDITION_XML_RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException(
            "Postcondition failed: open_app_package expected_package=$expectedPackage current_package=$lastPackage"
        )
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

    private data class PostconditionObservation(
        val xml: String,
        val packageName: String,
        val score: Float,
        val packageMatchMode: String?,
    )

    private fun readCurrentObservationSnapshot(
        expectedXml: String,
        expectedPackage: String,
    ): PostconditionObservation? {
        runCatching { OmniflowActionRuntime.backend.isReady() }
        val currentXml = runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        if (currentXml.isEmpty()) return null
        val rawPackage = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        val currentPackage = RunLogPagePackageInference.effectivePackage(
            rawPackage,
            currentXml,
        )
        return PostconditionObservation(
            xml = currentXml,
            packageName = currentPackage,
            score = pageSimilarityScore(expectedXml, currentXml),
            packageMatchMode = packageMatchMode(expectedPackage, currentPackage),
        )
    }

    private fun postconditionRequiredScore(expectedXml: String, recordedMinScore: Float): Float {
        val page = parsePageModel(expectedXml) ?: return recordedMinScore
        val anchors = page.nodes
            .filter { isAnchorCandidate(it, page.rootBounds) }
            .take(MAX_POSTCONDITION_NODES)
        if (anchors.size <= 1) return recordedMinScore
        val meaningfulAnchorCount = anchors.count(::isMeaningfulPostconditionAnchor)
        val adaptiveScore = if (meaningfulAnchorCount >= 3) {
            MIN_RICH_POSTCONDITION_PAGE_SCORE
        } else {
            MIN_POSTCONDITION_PAGE_SCORE
        }
        return max(recordedMinScore, adaptiveScore)
    }

    private fun isMeaningfulPostconditionAnchor(node: UiNode): Boolean =
        node.text.isNotBlank() ||
            node.contentDesc.isNotBlank() ||
            node.hintText.isNotBlank() ||
            (node.resourceId.isNotBlank() && !isGenericResourceId(node.resourceId))

    private suspend fun readCurrentObservationForPostcondition(
        expectedXml: String,
        expectedPackage: String,
        minScore: Float,
    ): PostconditionObservation {
        var best = PostconditionObservation(
            xml = "",
            packageName = "",
            score = 0f,
            packageMatchMode = null,
        )
        repeat(POSTCONDITION_XML_READ_ATTEMPTS) { index ->
            runCatching { OmniflowActionRuntime.backend.isReady() }
            val currentXml = runCatching {
                OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
            }.getOrDefault("")
            if (currentXml.isNotEmpty()) {
                val rawPackage = runCatching {
                    OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
                }.getOrDefault("")
                val currentPackage = RunLogPagePackageInference.effectivePackage(
                    rawPackage,
                    currentXml,
                )
                val score = pageSimilarityScore(expectedXml, currentXml)
                val packageMatchMode = packageMatchMode(expectedPackage, currentPackage)
                val observation = PostconditionObservation(
                    xml = currentXml,
                    packageName = currentPackage,
                    score = score,
                    packageMatchMode = packageMatchMode,
                )
                if (packageMatchMode != null && score >= minScore) {
                    return observation
                }
                if (isBetterPostconditionObservation(observation, best)) {
                    best = observation
                }
            }
            if (index < POSTCONDITION_XML_READ_ATTEMPTS - 1) {
                delay(POSTCONDITION_XML_RETRY_DELAY_MS)
            }
        }
        return best
    }

    private fun isBetterPostconditionObservation(
        candidate: PostconditionObservation,
        currentBest: PostconditionObservation,
    ): Boolean {
        val candidatePackageMatched = candidate.packageMatchMode != null
        val bestPackageMatched = currentBest.packageMatchMode != null
        if (candidatePackageMatched != bestPackageMatched) {
            return candidatePackageMatched
        }
        if (candidate.score != currentBest.score) {
            return candidate.score > currentBest.score
        }
        return candidate.xml.length > currentBest.xml.length
    }

    private fun transientSettingsSearchPostcondition(
        step: Map<String, Any?>,
        postcondition: Map<String, Any?>,
        expectedXml: String,
        currentXml: String,
        expectedPackage: String,
        currentPackage: String,
        packageMatched: Boolean,
        score: Float,
        minScore: Float,
    ): Map<String, Any?>? {
        if (!booleanArg(postcondition["allow_package_only_for_transient_search"])) return null
        if (!packageMatched) return null
        val isSettingsSearchPackage =
            expectedPackage == SETTINGS_SEARCH_PACKAGE || currentPackage == SETTINGS_SEARCH_PACKAGE
        if (!isSettingsSearchPackage) return null
        val hasSearchCue = isSettingsSearchTransitionStep(step) ||
            containsSearchCue(expectedXml) ||
            containsSearchCue(currentXml)
        if (!hasSearchCue) return null
        return linkedMapOf(
            "kind" to "recorded_after_page_similarity",
            "success" to true,
            "score" to score,
            "min_score" to minScore,
            "package_matched" to true,
            "expected_package" to expectedPackage.takeIf { it.isNotBlank() },
            "current_package" to currentPackage.takeIf { it.isNotBlank() },
            "semantic_fallback" to "settings_search_transition",
            "semantic_fallback_reason" to "package_matched_for_transient_settings_search",
        ).filterValues { it != null }
    }

    private fun isSettingsSearchTransitionStep(step: Map<String, Any?>): Boolean {
        val args = normalizeArgsMap(step["args"])
        val sourceContext = (step["source_context"] as? Map<*, *>)
            ?: (args["source_context"] as? Map<*, *>)
        val action = sourceContext?.get("action") as? Map<*, *>
        val haystack = listOf(
            step["title"],
            args["target_description"],
            args["targetDescription"],
            args["label"],
            action?.get("target_description"),
            action?.get("targetDescription"),
        ).joinToString(" ").lowercase()
        return haystack.contains("search settings") ||
            haystack.contains("search_action_bar") ||
            haystack.contains("settings search")
    }

    private fun containsSearchCue(xml: String): Boolean {
        val lower = xml.lowercase()
        return lower.contains("search settings") ||
            lower.contains("search_action_bar") ||
            lower.contains(SETTINGS_SEARCH_PACKAGE)
    }

    private fun booleanArg(value: Any?): Boolean =
        value == true || value?.toString()?.equals("true", ignoreCase = true) == true

    private fun pageSimilarityScore(expectedXml: String, currentXml: String): Float {
        val expected = parsePageModel(expectedXml) ?: return 0f
        val current = parsePageModel(currentXml) ?: return 0f
        val expectedNodes = expected.nodes
            .filter { isAnchorCandidate(it, expected.rootBounds) }
            .take(MAX_POSTCONDITION_NODES)
        if (expectedNodes.isEmpty()) return 0f
        val matched = expectedNodes.count { source ->
            current.nodes.any { target ->
                nodeSimilarity(source, target) >= MIN_POSTCONDITION_NODE_SIMILARITY
            }
        }
        return (matched.toFloat() / expectedNodes.size.toFloat()).coerceIn(0f, 1f)
    }

    private fun currentRootCenter(): Pair<Float, Float>? {
        val currentXml = OmniflowActionRuntime.backend.currentXml()
            ?.trim()
            .orEmpty()
        if (currentXml.isEmpty()) return null
        val page = parsePageModel(currentXml) ?: return null
        return page.rootBounds.centerX to page.rootBounds.centerY
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return text
            }
        }
        return ""
    }

    private fun String.requiresAccessibility(): Boolean =
        this != "open_app" && this != "finished"

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
            ?: remapPointWithinRoots(sourcePage, targetPage, x, y)
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
            ?: return rootProjectionFallbackForScroll(tool, args, sourceContainer, sourcePage.rootBounds, targetPage.rootBounds)

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

    private const val POST_STEP_DELAY_MS = 1000L
    private const val POSTCONDITION_XML_READ_ATTEMPTS = 5
    private const val POSTCONDITION_XML_RETRY_DELAY_MS = 400L
    private const val COORDINATE_REMAP_XML_READ_ATTEMPTS = 5
    private const val COORDINATE_REMAP_XML_RETRY_DELAY_MS = 300L
    private const val DEFAULT_SCREEN_CENTER_X = 540f
    private const val DEFAULT_SCREEN_CENTER_Y = 960f
    private const val DEFAULT_SWIPE_DISTANCE = 600f
    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private const val MAX_ANCHOR_COUNT = 5
    private const val MIN_ANCHOR_SIMILARITY = 0.45f
    private const val MIN_ANCHOR_MATCH_SCORE = 0.12f
    private const val MIN_DIRECT_FALLBACK_SIMILARITY = 0.58f
    private const val MIN_POSTCONDITION_PAGE_SCORE = 0.12f
    private const val MIN_RICH_POSTCONDITION_PAGE_SCORE = 0.70f
    private const val MIN_POSTCONDITION_NODE_SIMILARITY = 0.50f
    private const val MAX_POSTCONDITION_NODES = 24
    private const val PRE_ACTION_ALREADY_SATISFIED_MIN_SCORE = 0.72f
    private const val PRE_ACTION_ALREADY_SATISFIED_SOURCE_MARGIN = 0.25f
    private const val OPEN_APP_STABILITY_ATTEMPTS = 5
    private const val OPEN_APP_STABILITY_DELAY_MS = 350L
    private const val OPEN_APP_RELAUNCH_ATTEMPT_INDEX = 1
    private const val SETTINGS_SEARCH_PACKAGE = "com.google.android.settings.intelligence"
    private val RETRYABLE_COORDINATE_REMAP_REASONS = setOf(
        "missing_current_xml",
        "invalid_current_page",
        "no_anchor_match",
        "missing_scroll_source_element",
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
}
