package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.agent.AgentToolNames

import cn.com.omnimind.bot.runlog.RunLogCardAccessors.afterObservationForCard
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.asMap
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.beforeObservationForCard
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.extractArgs
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.firstNonBlank
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.nullableMap
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.observationXml
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.toolNameForCard
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.androidPrivilegedReplayAction

/**
 * Removes deterministic startup/launcher bridge noise before a RunLog becomes
 * a reusable Function.
 */
internal object RunLogStartupBridgeCleaner {
    data class InitialStepCleanup(
        val steps: List<Map<String, Any?>>,
        val injectedLaunchBridgeDroppedCount: Int,
    )

    fun dropTransientStartupBridgeCards(
        replayableCards: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (replayableCards.size < 2) return replayableCards
        val output = mutableListOf<Map<String, Any?>>()
        var concreteActionIndex = 0
        replayableCards.forEachIndexed { index, card ->
            val isConcrete = hasRecordedReplayStep(card)
            if (isConcrete &&
                shouldDropTransientStartupBridgeCard(
                    cards = replayableCards,
                    cardIndex = index,
                    concreteActionIndex = concreteActionIndex,
                )
            ) {
                concreteActionIndex += 1
                return@forEachIndexed
            }
            output += card
            if (isConcrete) {
                concreteActionIndex += 1
            }
        }
        return output
    }

    fun normalizeInitialOpenAppStep(
        replayableCards: List<Map<String, Any?>>,
        steps: List<Map<String, Any?>>,
    ): InitialStepCleanup {
        val stepsWithStart = prepareInitialOpenAppStep(
            prependInitialOpenAppStepIfNeeded(replayableCards, steps)
        )
        val stepsAfterInitialLaunchBridgeDrop = dropRedundantInjectedLaunchBridgeStep(stepsWithStart)
        return InitialStepCleanup(
            steps = stepsAfterInitialLaunchBridgeDrop,
            injectedLaunchBridgeDroppedCount =
                stepsWithStart.size - stepsAfterInitialLaunchBridgeDrop.size,
        )
    }

    fun hasRecordedReplayStep(card: Map<String, Any?>): Boolean {
        val toolName = toolNameForCard(card)
        if (OobActionCodec.canonicalActionForName(toolName) != null) {
            return true
        }
        if (RunLogReplayPolicy.normalizeToolName(toolName) != AgentToolNames.ANDROID_PRIVILEGED_ACTION) {
            return false
        }
        val args = asMap(extractArgs(card))
        return androidPrivilegedReplayAction(args) != null
    }

    private fun replayActionForStep(step: Map<String, Any?>): String {
        return OobActionCodec.actionNameForStep(step)
    }

    private fun prependInitialOpenAppStepIfNeeded(
        replayableCards: List<Map<String, Any?>>,
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.isEmpty()) return steps
        val firstAction = replayActionForStep(steps.first())
        if (firstAction == OobActionCodec.ACTION_OPEN_APP) return steps

        val packageName = initialReplayPackage(steps, replayableCards) ?: return steps
        val openAppStep = nullableMap(
            "title" to "open_app: $packageName",
            "kind" to "omniflow_action",
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "omniflow_action" to OobActionCodec.ACTION_OPEN_APP,
            "local_action" to OobActionCodec.ACTION_OPEN_APP,
            "model_free" to true,
            "scriptable" to true,
            "tool" to OobActionCodec.ACTION_OPEN_APP,
            "callable_tool" to OobActionCodec.ACTION_OPEN_APP,
            "args" to linkedMapOf(
                "package_name" to packageName,
                "reset_task" to true,
                "launch_mode" to "fresh_task",
            ),
            "route_note" to "injected_initial_package_from_runlog",
        )
        return listOf(openAppStep) + steps
    }

    private fun prepareInitialOpenAppStep(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.isEmpty()) return steps
        val first = steps.first()
        val firstAction = replayActionForStep(first)
        if (firstAction != OobActionCodec.ACTION_OPEN_APP) return steps
        val args = asMap(first["args"])
        val packageName = firstNonBlank(args["package_name"], args["packageName"])
        if (!isLaunchableInitialPackageCandidate(packageName)) return steps
        val preparedFirst = linkedMapOf<String, Any?>().apply {
            putAll(first)
            put(
                "args",
                linkedMapOf<String, Any?>().apply {
                    putAll(args)
                    put("package_name", packageName)
                    putIfAbsent("reset_task", true)
                    putIfAbsent("launch_mode", "fresh_task")
                }
            )
            putIfAbsent("route_note", "initial_open_app_fresh_launch")
        }
        return listOf(preparedFirst) + steps.drop(1)
    }

    private fun dropRedundantInjectedLaunchBridgeStep(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val first = steps.first()
        if (replayActionForStep(first) != OobActionCodec.ACTION_OPEN_APP) return steps
        if (first["route_note"] != "injected_initial_package_from_runlog") return steps

        val packageName = firstNonBlank(
            asMap(first["args"])["package_name"],
            asMap(first["args"])["packageName"],
        )
        if (!isLaunchableInitialPackageCandidate(packageName)) return steps

        val candidate = steps[1]
        if (replayActionForStep(candidate) != OobActionCodec.ACTION_CLICK) return steps
        if (!isInjectedLaunchBridgeClick(candidate, packageName, steps.drop(2))) {
            return steps
        }
        return listOf(first) + steps.drop(2)
    }

    private fun isInjectedLaunchBridgeClick(
        step: Map<String, Any?>,
        launchedPackage: String,
        followingSteps: List<Map<String, Any?>>,
    ): Boolean {
        val sourceContext = asMap(step["source_context"])
        val srcCtx = asMap(sourceContext["src_ctx"])
        val rawSourcePackage = firstNonBlank(
            srcCtx["package_name"],
            srcCtx["packageName"],
        )
        val sourceXml = firstNonBlank(srcCtx["page"], srcCtx["xml"])
        val effectiveSourcePackage = RunLogPagePackageInference.effectivePackage(
            rawSourcePackage,
            sourceXml,
        )
        val followingUsesLaunchedPackage = followingSteps.any { following ->
            stepMentionsPackage(following, launchedPackage)
        }
        if (!followingUsesLaunchedPackage) return false

        if (isLauncherPackage(rawSourcePackage)) {
            return true
        }
        return effectiveSourcePackage == launchedPackage &&
            isSparseLaunchSurface(sourceXml, launchedPackage)
    }

    private fun stepMentionsPackage(
        step: Map<String, Any?>,
        packageName: String,
    ): Boolean {
        val args = asMap(step["args"])
        val sourceContext = asMap(step["source_context"])
        val srcCtx = asMap(sourceContext["src_ctx"])
        val dstCtx = asMap(sourceContext["dst_ctx"])
        return listOf(
            firstNonBlank(args["package_name"], args["packageName"]),
            effectivePackageForContext(srcCtx),
            effectivePackageForContext(dstCtx),
        ).any { it == packageName }
    }

    private fun effectivePackageForContext(context: Map<String, Any?>): String {
        val rawPackage = firstNonBlank(context["package_name"], context["packageName"])
        val xml = firstNonBlank(context["page"], context["xml"])
        return RunLogPagePackageInference.effectivePackage(rawPackage, xml)
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (normalized.contains("launcher")) return true
        return normalized in setOf(
            "com.bbk.launcher2",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.sec.android.app.launcher",
        )
    }

    private fun isSparseLaunchSurface(xml: String, packageName: String): Boolean {
        if (xml.isBlank()) return false
        val vector = OobPageVectorSet.encode(xml, packageName) ?: return false
        return vector.packageName == packageName &&
            vector.displayTextCount == 0 &&
            vector.elementCount <= 4 &&
            vector.actionableCount <= 2
    }

    private fun initialReplayPackage(
        steps: List<Map<String, Any?>>,
        replayableCards: List<Map<String, Any?>>,
    ): String? {
        initialReplayPackageFromSteps(steps)?.let { return it }
        val packageName = replayableCards.asSequence()
            .mapNotNull { card ->
                firstNonBlank(
                    card["package_name"],
                    card["packageName"],
                    asMap(card["before"])["package_name"],
                    asMap(card["before"])["packageName"],
                ).takeIf { it.isNotBlank() }
            }
            .firstOrNull()
            ?: return null
        return packageName.takeIf(::isLaunchableInitialPackageCandidate)
    }

    private fun initialReplayPackageFromSteps(steps: List<Map<String, Any?>>): String? {
        return steps.asSequence()
            .mapNotNull { step ->
                val sourceContext = asMap(step["source_context"])
                val srcCtx = asMap(sourceContext["src_ctx"])
                firstNonBlank(
                    srcCtx["package_name"],
                    srcCtx["packageName"],
                ).takeIf { it.isNotBlank() }
            }
            .firstOrNull(::isLaunchableInitialPackageCandidate)
    }

    private fun isLaunchableInitialPackageCandidate(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (!PACKAGE_NAME_PATTERN.matches(normalized)) return false
        if (normalized.startsWith("cn.com.omnimind.")) return false
        if (normalized == "android") return false
        if (normalized == "com.android.systemui") return false
        if (normalized.startsWith("com.android.inputmethod")) return false
        if (normalized.startsWith("com.google.android.inputmethod")) return false
        if (normalized.contains("launcher", ignoreCase = true)) return false
        if (normalized.startsWith("com.example")) return false
        return true
    }

    private fun shouldDropTransientStartupBridgeCard(
        cards: List<Map<String, Any?>>,
        cardIndex: Int,
        concreteActionIndex: Int,
    ): Boolean {
        if (concreteActionIndex > 1) return false
        val card = cards[cardIndex]
        if (isManualRecordingCard(card)) return false
        val action = replayActionForCard(card) ?: return false
        if (action != OobActionCodec.ACTION_CLICK) return false

        val args = asMap(extractArgs(card))
        val target = firstNonBlank(
            args["target_description"],
            args["targetDescription"],
            args["label"],
            asMap(card["header"])["title"],
            card["title"],
            card["summary"],
        )
        if (target.isBlank()) return false

        val sourceXml = observationXml(beforeObservationForCard(card))
        val afterXml = observationXml(afterObservationForCard(card))
        if (sourceXml.isBlank() || afterXml.isBlank()) return false

        val nextCard = cards.asSequence()
            .drop(cardIndex + 1)
            .firstOrNull(::hasRecordedReplayStep)
            ?: return false
        val nextBeforeXml = observationXml(beforeObservationForCard(nextCard))
        if (nextBeforeXml.isBlank()) return false

        if (!pagesAreSameTransition(afterXml, nextBeforeXml)) return false
        if (xmlContainsTarget(sourceXml, target)) return false
        if (!xmlContainsTarget(afterXml, target) && !xmlContainsTarget(nextBeforeXml, target)) {
            return false
        }
        if (!hasCompatibleTransitionPackage(card, nextCard, afterXml, nextBeforeXml)) return false
        return isTransientStartupSource(sourceXml, nextBeforeXml)
    }

    private fun isManualRecordingCard(card: Map<String, Any?>): Boolean {
        return firstNonBlank(card["compile_kind"], card["compileKind"]) == "manual_recording" ||
            firstNonBlank(card["source"], asMap(card["header"])["source"]) == "human_takeover"
    }

    private fun replayActionForCard(card: Map<String, Any?>): String? {
        val toolName = toolNameForCard(card)
        val normalizedToolName = RunLogReplayPolicy.normalizeToolName(toolName)
        val args = asMap(extractArgs(card))
        return if (normalizedToolName == AgentToolNames.ANDROID_PRIVILEGED_ACTION) {
            androidPrivilegedReplayAction(args)
        } else {
            OobActionCodec.canonicalActionForName(toolName)
        }
    }

    private fun pagesAreSameTransition(firstXml: String, secondXml: String): Boolean {
        if (firstXml == secondXml) return true
        val firstVector = OobPageVectorSet.encode(firstXml) ?: return false
        val secondVector = OobPageVectorSet.encode(secondXml) ?: return false
        return OobPageVectorSet.cosine(firstVector.vector, secondVector.vector) >=
            TRANSIENT_BRIDGE_SAME_PAGE_SCORE
    }

    private fun xmlContainsTarget(xml: String, target: String): Boolean {
        val tokens = meaningfulTargetTokens(target)
        if (tokens.isEmpty()) return false
        val haystack = xml.lowercase()
        return tokens.any { token -> haystack.contains(token) }
    }

    private fun meaningfulTargetTokens(target: String): List<String> {
        return target
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { token ->
                token.length >= 3 && token !in GENERIC_TARGET_TOKENS
            }
            .distinct()
    }

    private fun hasCompatibleTransitionPackage(
        card: Map<String, Any?>,
        nextCard: Map<String, Any?>,
        afterXml: String,
        nextBeforeXml: String,
    ): Boolean {
        val afterObservation = afterObservationForCard(card)
        val nextBefore = beforeObservationForCard(nextCard)
        val afterPackage = RunLogPagePackageInference.effectivePackage(
            firstNonBlank(afterObservation["package_name"], afterObservation["packageName"]),
            afterXml,
        )
        val nextPackage = RunLogPagePackageInference.effectivePackage(
            firstNonBlank(nextBefore["package_name"], nextBefore["packageName"]),
            nextBeforeXml,
        )
        return afterPackage.isBlank() ||
            nextPackage.isBlank() ||
            afterPackage == nextPackage
    }

    private fun isTransientStartupSource(sourceXml: String, nextSourceXml: String): Boolean {
        val sourceVector = OobPageVectorSet.encode(sourceXml) ?: return false
        val nextVector = OobPageVectorSet.encode(nextSourceXml) ?: return false
        val sourceStrength = observationStrength(sourceVector)
        val nextStrength = observationStrength(nextVector)
        val compactPromptLike = sourceVector.elementCount <= TRANSIENT_BRIDGE_SOURCE_MAX_ELEMENTS &&
            sourceVector.displayTextCount <= TRANSIENT_BRIDGE_SOURCE_MAX_TEXTS &&
            sourceVector.actionableCount <= TRANSIENT_BRIDGE_SOURCE_MAX_ACTIONABLES
        val nextIsRicher = nextStrength >= sourceStrength + TRANSIENT_BRIDGE_MIN_STRENGTH_GAIN
        return compactPromptLike && nextIsRicher
    }

    private fun observationStrength(vector: OobPageVectorSet.PageVector): Int {
        return vector.displayTextCount * 4 +
            vector.actionableCount * 3 +
            vector.focusTargetCount * 2 +
            vector.elementCount
    }

    private val PACKAGE_NAME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
    private const val TRANSIENT_BRIDGE_SAME_PAGE_SCORE = 0.92f
    private const val TRANSIENT_BRIDGE_SOURCE_MAX_ELEMENTS = 8
    private const val TRANSIENT_BRIDGE_SOURCE_MAX_TEXTS = 3
    private const val TRANSIENT_BRIDGE_SOURCE_MAX_ACTIONABLES = 3
    private const val TRANSIENT_BRIDGE_MIN_STRENGTH_GAIN = 8
    private val GENERIC_TARGET_TOKENS = setOf(
        "click",
        "clicked",
        "tap",
        "tapped",
        "press",
        "button",
        "tab",
        "view",
        "viewgroup",
        "textview",
        "imageview",
        "imagebutton",
        "layout",
        "frame",
        "item",
        "row",
        "list",
        "menu",
        "icon",
        "the",
        "and",
    )
}
