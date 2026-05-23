package cn.com.omnimind.assists.task.vlmserver

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies deterministic, low-risk corrections after the VLM has selected an
 * action but before the action is dispatched to Accessibility.
 */
object VLMActionPostProcessor {
    data class Result(
        val step: VLMStep,
        val applied: Boolean = false,
        val reason: String = ""
    )

    fun correct(
        step: VLMStep,
        context: UIContext,
        currentXml: String?,
        currentPackageName: String?,
        stepIndex: Int,
        displayWidth: Int,
        displayHeight: Int
    ): Result {
        val currentPackage = currentPackageName?.trim().orEmpty()
        val targetPackage = context.targetPackageName.trim()
        if (
            stepIndex == 0 &&
            context.trace.isEmpty() &&
            targetPackage.isNotBlank() &&
            currentPackage.isNotBlank() &&
            !currentPackage.equals(targetPackage, ignoreCase = true) &&
            step.action !is OpenAppAction
        ) {
            return corrected(
                step = step,
                action = OpenAppAction(packageName = targetPackage),
                reason = "target_package_not_foreground"
            )
        }

        VLMActionControllerRegistry.correct(
            VLMActionControllerRequest(
                step = step,
                context = context,
                currentXml = currentXml,
                currentPackageName = currentPackageName,
                stepIndex = stepIndex,
                displayWidth = displayWidth,
                displayHeight = displayHeight
            )
        )?.let { decision ->
            return controllerCorrected(decision)
        }

        val page = parsePage(currentXml)
        correctPrematureFinishedForOrderedTarget(
            step = step,
            context = context,
            page = page,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )?.let { return it }
        correctTypeToNumericKeypadClick(step, page)?.let { return it }

        if (step.action is ClickAction) {
            correctOrderedGoalClickTarget(
                step = step,
                context = context,
                page = page
            )?.let { return it }
            if (isOrderedGoalClickSatisfied(step, context, page)) {
                return Result(step = step)
            }
            correctSettingsToggleTarget(
                step = step,
                context = context,
                currentPackageName = currentPackage,
                page = page,
                displayWidth = displayWidth
            )?.let { return it }
            correctMissingSettingsTopLevelTarget(
                step = step,
                context = context,
                currentPackageName = currentPackage,
                page = page
            )?.let { return it }
        }

        correctPendingSettingsTopLevelTarget(
            step = step,
            context = context,
            currentPackageName = currentPackage,
            page = page
        )?.let { return it }

        correctSliderEndpointAction(
            step = step,
            context = context,
            page = page,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )?.let { return it }

        if (step.action is ScrollAction) {
            correctVisibleGoalBeforeScroll(step, context, page)?.let { return it }
        }

        if (step.action is ClickAction) {
            correctFirstClickToVisibleGoal(step, context, page)?.let { return it }
            correctGenericClickToSearchScroll(
                step = step,
                context = context,
                page = page,
                displayWidth = displayWidth,
                displayHeight = displayHeight
            )?.let { return it }
        }

        return Result(step = step)
    }

    private fun correctPrematureFinishedForOrderedTarget(
        step: VLMStep,
        context: UIContext,
        page: PageModel,
        displayWidth: Int,
        displayHeight: Int
    ): Result? {
        if (step.action !is FinishedAction) return null
        val progress = orderedGoalProgress(context) ?: return null
        val pendingTarget = progress.pendingTarget ?: return null

        if (step.hasOrderedTargetCompletionEvidence(pendingTarget)) {
            return null
        }

        val visibleTarget = page.bestOrderedGoalClickTarget(pendingTarget)
        if (visibleTarget != null) {
            return corrected(
                step = step,
                action = ClickAction(
                    targetDescription = visibleTarget.label,
                    x = visibleTarget.bounds.centerX,
                    y = visibleTarget.bounds.centerY
                ),
                reason = "premature_finished_ordered_target",
                extraSummary = "Pending ordered target: ${pendingTarget.label}"
            )
        }

        page.bestSearchScroll(displayWidth, displayHeight, pendingTarget.label)?.let { scroll ->
            if (!page.hasNavigateUp() || page.hasAnyOrderedTargetVisible(progress.targets)) {
                return corrected(
                    step = step,
                    action = scroll,
                    reason = "premature_finished_ordered_target_scroll",
                    extraSummary = "Pending ordered target: ${pendingTarget.label}"
                )
            }
        }

        if (page.hasNavigateUp()) {
            return corrected(
                step = step,
                action = PressBackAction(),
                reason = "premature_finished_ordered_target_go_back",
                extraSummary = "Pending ordered target: ${pendingTarget.label}"
            )
        }

        return null
    }

    private fun correctOrderedGoalClickTarget(
        step: VLMStep,
        context: UIContext,
        page: PageModel
    ): Result? {
        val action = step.action as? ClickAction ?: return null
        val progress = orderedGoalProgress(context) ?: return null
        val pendingTarget = progress.pendingTarget ?: return null
        val target = page.bestOrderedGoalClickTarget(pendingTarget) ?: return null
        if (target.bounds.contains(action.x, action.y)) return null
        if (orderedTargetTextScore(pendingTarget, action.targetDescription) >= MIN_ORDERED_TARGET_ACTION_SCORE) {
            return null
        }

        return corrected(
            step = step,
            action = action.copy(
                targetDescription = target.label,
                x = target.bounds.centerX,
                y = target.bounds.centerY
            ),
            reason = "ordered_goal_target",
            extraSummary = "Pending ordered target: ${pendingTarget.label}"
        )
    }

    private fun isOrderedGoalClickSatisfied(
        step: VLMStep,
        context: UIContext,
        page: PageModel
    ): Boolean {
        val action = step.action as? ClickAction ?: return false
        val progress = orderedGoalProgress(context) ?: return false
        val pendingTarget = progress.pendingTarget ?: return false
        if (orderedTargetTextScore(pendingTarget, action.targetDescription) >= MIN_ORDERED_TARGET_ACTION_SCORE) {
            return true
        }
        val visibleTarget = page.bestOrderedGoalClickTarget(pendingTarget) ?: return false
        return visibleTarget.bounds.contains(action.x, action.y)
    }

    private fun correctTypeToNumericKeypadClick(
        step: VLMStep,
        page: PageModel
    ): Result? {
        val action = step.action as? TypeAction ?: return null
        if (page.hasFocusedEditable()) return null
        if (page.numericKeyCount() < MIN_NUMERIC_KEYPAD_KEYS) return null
        val content = action.content.trim()
        if (content.isBlank() || !content.all { it.isDigit() }) return null

        val key = content.first().toString()
        val target = page.bestNumericKeyTarget(key) ?: return null
        return corrected(
            step = step,
            action = ClickAction(
                targetDescription = "digit $key",
                x = target.bounds.centerX,
                y = target.bounds.centerY
            ),
            reason = "type_to_numeric_key_click",
            extraSummary = "Converted numeric keypad type to first key; remaining=${content.drop(1)}"
        )
    }

    private fun correctMissingSettingsTopLevelTarget(
        step: VLMStep,
        context: UIContext,
        currentPackageName: String,
        page: PageModel
    ): Result? {
        val action = step.action as? ClickAction ?: return null
        if (!currentPackageName.equals("com.android.settings", ignoreCase = true)) return null
        if (!page.hasNavigateUp()) return null
        val requestedDomain = settingsDomainForText(action.targetDescription)
        if (!isSettingsTopLevelTarget(action.targetDescription) && requestedDomain == null) return null
        if (requestedDomain != null && page.hasVisibleSettingsDomainTarget(requestedDomain)) return null
        if (requestedDomain == null && page.hasVisibleSemanticTarget(action.targetDescription)) return null

        return corrected(
            step = step,
            action = PressBackAction(),
            reason = "missing_settings_target_go_back"
        )
    }

    private fun correctPendingSettingsTopLevelTarget(
        step: VLMStep,
        context: UIContext,
        currentPackageName: String,
        page: PageModel
    ): Result? {
        if (!currentPackageName.equals("com.android.settings", ignoreCase = true)) return null
        val progress = settingsProgress(context) ?: return null
        if (progress.pendingIndex <= 0) return null
        if (!page.hasNavigateUp()) return null
        if (page.hasVisibleSettingsDomainTarget(progress.pendingDomain)) return null
        val currentDomain = page.primarySettingsDomain() ?: return null
        if (currentDomain == progress.pendingDomain) return null

        return corrected(
            step = step,
            action = PressBackAction(),
            reason = "pending_settings_target_go_back"
        )
    }

    private fun correctSettingsToggleTarget(
        step: VLMStep,
        context: UIContext,
        currentPackageName: String,
        page: PageModel,
        displayWidth: Int
    ): Result? {
        val action = step.action as? ClickAction ?: return null
        if (!currentPackageName.equals("com.android.settings", ignoreCase = true)) return null
        val intentText = listOf(context.overallTask, context.activeGoal(), stepIntentText(step)).joinToString(" ")
        if (!hasSettingsToggleIntent(intentText)) return null

        val domain = settingsProgress(context)?.pendingDomain
            ?: settingsDomainForText(stepIntentText(step))
            ?: settingsDomainForText(context.activeGoal())
            ?: return null
        if (domain != SettingsDomain.NETWORK && domain != SettingsDomain.CONNECTED_DEVICES) return null

        val target = page.bestSettingsToggleTarget(domain) ?: return null
        if (target.bounds.contains(action.x, action.y) && action.x >= toggleClickX(target.bounds, displayWidth) - 48f) {
            return null
        }
        return corrected(
            step = step,
            action = action.copy(
                targetDescription = target.label,
                x = toggleClickX(target.bounds, displayWidth),
                y = target.bounds.centerY
            ),
            reason = "settings_toggle_target"
        )
    }

    private fun correctFirstClickToVisibleGoal(
        step: VLMStep,
        context: UIContext,
        page: PageModel
    ): Result? {
        val action = step.action as? ClickAction ?: return null
        val best = page.bestNarrativeFormFieldTarget(context, step)
            ?: run {
                val goal = visibleGoal(context, step, page)
                page.bestGoalClickTarget(goal.text, goal.preferredSettingsDomain)
            }
            ?: return null
        if (best.bounds.contains(action.x, action.y)) return null
        if (textSimilarity(action.targetDescription, best.label) >= 0.72) return null

        return corrected(
            step = step,
            action = action.copy(
                targetDescription = best.label,
                x = best.bounds.centerX,
                y = best.bounds.centerY
            ),
            reason = "visible_goal_target"
        )
    }

    private fun correctGenericClickToSearchScroll(
        step: VLMStep,
        context: UIContext,
        page: PageModel,
        displayWidth: Int,
        displayHeight: Int
    ): Result? {
        val action = step.action as? ClickAction ?: return null
        val activeGoalText = firstNonBlank(context.activeGoal(), context.overallTask)
        if (wantsScroll(activeGoalText)) return null
        if (!isGenericTargetDescription(action.targetDescription)) return null
        val scrollBounds = page.bestScrollableBounds() ?: return null

        val goalText = visibleGoal(context, step, page).text
        val width = displayWidth.coerceAtLeast(1)
        val height = displayHeight.coerceAtLeast(1)
        val x = scrollBounds.centerX.coerceIn(0f, width.toFloat())
        val top = scrollBounds.top.coerceIn(0f, height.toFloat())
        val bottom = scrollBounds.bottom.coerceIn(top, height.toFloat())
        val y1 = (bottom - scrollBounds.height * 0.12f).coerceIn(top, bottom)
        val y2 = (top + scrollBounds.height * 0.22f).coerceIn(top, bottom)
        if (abs(y1 - y2) < 48f) return null

        return corrected(
            step = step,
            action = ScrollAction(
                targetDescription = "Scroll current list to find ${goalText.take(80)}",
                x1 = x,
                y1 = y1,
                x2 = x,
                y2 = y2,
                duration = 1.0f
            ),
            reason = "generic_click_to_search_scroll"
        )
    }

    private fun correctVisibleGoalBeforeScroll(
        step: VLMStep,
        context: UIContext,
        page: PageModel
    ): Result? {
        val action = step.action as? ScrollAction ?: return null
        val goalText = firstNonBlank(context.activeGoal(), context.overallTask)
        if (wantsScroll(goalText)) return null
        if (!isVerticalGesture(action)) return null

        val progress = orderedGoalProgress(context)
        val pendingTarget = progress?.pendingTarget
        if (pendingTarget != null) {
            val orderedTarget = page.bestOrderedGoalClickTarget(pendingTarget) ?: return null
            if (orderedTarget.bounds.area > page.maxNodeArea() * MAX_SCROLL_TO_CLICK_AREA_RATIO) return null
            return corrected(
                step = step,
                action = ClickAction(
                    targetDescription = orderedTarget.label,
                    x = orderedTarget.bounds.centerX,
                    y = orderedTarget.bounds.centerY
                ),
                reason = "ordered_goal_target_before_scroll",
                extraSummary = "Pending ordered target: ${pendingTarget.label}"
            )
        }

        val goal = visibleGoal(context, step, page)
        val best = page.bestGoalClickTarget(goal.text, goal.preferredSettingsDomain) ?: return null
        if (best.bounds.area > page.maxNodeArea() * MAX_SCROLL_TO_CLICK_AREA_RATIO) return null

        return corrected(
            step = step,
            action = ClickAction(
                targetDescription = best.label,
                x = best.bounds.centerX,
                y = best.bounds.centerY
            ),
            reason = "visible_goal_target_before_scroll"
        )
    }

    private fun correctSliderEndpointAction(
        step: VLMStep,
        context: UIContext,
        page: PageModel,
        displayWidth: Int,
        displayHeight: Int
    ): Result? {
        val intent = inferSliderEndpointIntent(
            listOf(
                context.activeGoal(),
                context.overallTask,
                step.thought,
                step.summary,
                when (val action = step.action) {
                    is ClickAction -> action.targetDescription
                    is ScrollAction -> action.targetDescription
                    is LongPressAction -> action.targetDescription
                    else -> ""
                }
            ).joinToString(" ")
        ) ?: return null
        if (!page.hasSliderSemanticSignal(context, step.action)) return null

        return when (val action = step.action) {
            is ScrollAction -> {
                if (!isHorizontalGesture(action)) return null
                val desired = endpointScroll(
                    original = action,
                    intent = intent,
                    page = page,
                    context = context,
                    fallbackY = ((action.y1 + action.y2) / 2f),
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )
                if (sameScrollDirectionAndEndpoint(action, desired, intent, displayWidth)) {
                    null
                } else {
                    corrected(step, desired, "slider_endpoint_direction")
                }
            }

            is ClickAction -> {
                val desired = endpointScroll(
                    original = ScrollAction(
                        targetDescription = action.targetDescription,
                        x1 = action.x,
                        y1 = action.y,
                        x2 = action.x,
                        y2 = action.y,
                        duration = 0.6f
                    ),
                    intent = intent,
                    page = page,
                    context = context,
                    fallbackY = action.y,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )
                corrected(step, desired, "slider_click_to_drag")
            }

            else -> null
        }
    }

    private fun sameScrollDirectionAndEndpoint(
        action: ScrollAction,
        desired: ScrollAction,
        intent: SliderEndpointIntent,
        displayWidth: Int
    ): Boolean {
        val minEndpointDelta = (displayWidth * 0.12f).roundToInt().coerceAtLeast(24)
        return when (intent) {
            SliderEndpointIntent.MAX -> action.x2 > action.x1 &&
                abs(desired.x2 - action.x2) <= minEndpointDelta

            SliderEndpointIntent.MIN -> action.x1 > action.x2 &&
                abs(desired.x2 - action.x2) <= minEndpointDelta
        }
    }

    private fun endpointScroll(
        original: ScrollAction,
        intent: SliderEndpointIntent,
        page: PageModel,
        context: UIContext,
        fallbackY: Float,
        displayWidth: Int,
        displayHeight: Int
    ): ScrollAction {
        val width = displayWidth.coerceAtLeast(1)
        val height = displayHeight.coerceAtLeast(1)
        val horizontalInset = (width * 0.025f).roundToInt().coerceIn(8, 32)
        val topInset = (height * 0.04f).roundToInt().coerceAtLeast(48)
        val bottomInset = (height * 0.08f).roundToInt().coerceAtLeast(96)
        val left = horizontalInset.toFloat()
        val right = (width - horizontalInset).toFloat().coerceAtLeast(left)
        val minY = topInset.toFloat()
        val maxY = (height - bottomInset).toFloat().coerceAtLeast(minY)
        val y = page.bestSliderY(context, original.targetDescription)
            ?.coerceIn(minY, maxY)
            ?: fallbackY.coerceIn(minY, maxY)

        return when (intent) {
            SliderEndpointIntent.MAX -> original.copy(x1 = left, y1 = y, x2 = right, y2 = y)
            SliderEndpointIntent.MIN -> original.copy(x1 = right, y1 = y, x2 = left, y2 = y)
        }
    }

    private fun isHorizontalGesture(action: ScrollAction): Boolean {
        val dx = abs(action.x2 - action.x1)
        val dy = abs(action.y2 - action.y1)
        return dx >= 32f && dx >= dy * 1.4f
    }

    private fun isVerticalGesture(action: ScrollAction): Boolean {
        val dx = abs(action.x2 - action.x1)
        val dy = abs(action.y2 - action.y1)
        return dy >= 32f && dy >= dx * 1.2f
    }

    private fun corrected(step: VLMStep, action: UIAction, reason: String, extraSummary: String = ""): Result {
        val summary = buildString {
            append(step.summary)
            if (isNotBlank()) append(' ')
            append("[runtime_corrected:$reason]")
            if (extraSummary.isNotBlank()) {
                append(' ')
                append(extraSummary)
            }
        }
        return Result(step = step.copy(action = action, summary = summary), applied = true, reason = reason)
    }

    private fun controllerCorrected(decision: VLMActionControllerDecision): Result {
        val summary = buildString {
            append(decision.step.summary)
            if (isNotBlank()) append(' ')
            append("[controller_corrected:${decision.reason}]")
            if (decision.summary.isNotBlank()) {
                append(' ')
                append(decision.summary)
            }
        }
        return Result(
            step = decision.step.copy(summary = summary),
            applied = true,
            reason = decision.reason
        )
    }

    private fun PageModel.hasSliderSemanticSignal(context: UIContext, action: UIAction): Boolean {
        if (nodes.any { it.isSliderClass }) return true
        if (nodes.any { it.hasDirectSliderLabel }) return true
        val actionText = when (action) {
            is ClickAction -> action.targetDescription
            is ScrollAction -> action.targetDescription
            is LongPressAction -> action.targetDescription
            else -> ""
        }.lowercase()
        if (!CONCRETE_SLIDER_TERMS.any { actionText.contains(it) }) return false

        val text = listOf(
            context.activeGoal(),
            context.overallTask,
            actionText,
            nodes.joinToString(" ") { it.label }
        ).joinToString(" ").lowercase()
        return SLIDER_TERMS.any { text.contains(it) }
    }

    private fun PageModel.bestSliderY(context: UIContext, targetDescription: String): Float? {
        val goal = listOf(context.activeGoal(), context.overallTask, targetDescription)
            .joinToString(" ")
        return nodes
            .asSequence()
            .filter { it.isSliderClass || SLIDER_TERMS.any { term -> it.label.lowercase().contains(term) } }
            .sortedWith(
                compareByDescending<PageNode> { if (it.isSliderClass) 1 else 0 }
                    .thenByDescending { textSimilarity(goal, it.label) }
                    .thenBy { it.bounds.area }
            )
            .firstOrNull()
            ?.bounds
            ?.centerY
    }

    private fun PageModel.bestNarrativeFormFieldTarget(
        context: UIContext,
        step: VLMStep
    ): PageNode? {
        val segments = narrativeTargetSegments(context, step)
        if (segments.isEmpty()) return null
        return nodes
            .asSequence()
            .filter { it.actionable && it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.isFormFieldLike }
            .filterNot { isOverlayLabel(it.label) }
            .mapNotNull { node ->
                val score = node.bestFormFieldNarrativeScore(segments)
                if (score >= MIN_FORM_FIELD_NARRATIVE_SCORE) node to score else null
            }
            .sortedWith(
                compareByDescending<Pair<PageNode, Double>> { it.second }
                    .thenBy { it.first.bounds.area }
            )
            .firstOrNull()
            ?.first
    }

    private fun PageModel.bestGoalClickTarget(
        goalText: String,
        preferredSettingsDomain: SettingsDomain? = null
    ): PageNode? {
        val normalizedGoal = normalizeText(goalText)
        if (normalizedGoal.isEmpty()) return null
        val maxArea = nodes.maxOfOrNull { it.bounds.area } ?: return null
        if (preferredSettingsDomain != null) {
            bestSettingsDomainClickTarget(preferredSettingsDomain, maxArea)?.let { return it }
        }
        return nodes
            .asSequence()
            .filter { it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.bounds.area <= maxArea * MAX_TARGET_AREA_RATIO }
            .filterNot { isOverlayLabel(it.label) }
            .mapNotNull { node ->
                val score = textSimilarity(goalText, node.label)
                if (score >= MIN_GOAL_TARGET_SCORE) node to score else null
            }
            .sortedWith(
                compareByDescending<Pair<PageNode, Double>> { it.second }
                    .thenBy { it.first.bounds.area }
            )
            .firstOrNull()
            ?.first
    }

    private fun PageModel.bestOrderedGoalClickTarget(target: OrderedGoalTarget): PageNode? {
        val maxArea = nodes.maxOfOrNull { it.bounds.area } ?: return null
        return nodes
            .asSequence()
            .filter { it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.bounds.area <= maxArea * MAX_TARGET_AREA_RATIO }
            .filterNot { isOverlayLabel(it.label) }
            .mapNotNull { node ->
                val score = orderedTargetTextScore(target, node.label)
                if (score >= MIN_ORDERED_TARGET_NODE_SCORE) node to score else null
            }
            .sortedWith(
                compareByDescending<Pair<PageNode, Double>> { it.second }
                    .thenBy { it.first.bounds.area }
            )
            .firstOrNull()
            ?.first
    }

    private fun PageModel.bestSearchScroll(
        displayWidth: Int,
        displayHeight: Int,
        pendingLabel: String
    ): ScrollAction? {
        val scrollBounds = bestScrollableBounds() ?: return null
        val width = displayWidth.coerceAtLeast(1)
        val height = displayHeight.coerceAtLeast(1)
        val x = scrollBounds.centerX.coerceIn(0f, width.toFloat())
        val top = scrollBounds.top.coerceIn(0f, height.toFloat())
        val bottom = scrollBounds.bottom.coerceIn(top, height.toFloat())
        val y1 = (bottom - scrollBounds.height * 0.12f).coerceIn(top, bottom)
        val y2 = (top + scrollBounds.height * 0.22f).coerceIn(top, bottom)
        if (abs(y1 - y2) < 48f) return null
        return ScrollAction(
            targetDescription = "Scroll current list to find ${pendingLabel.take(80)}",
            x1 = x,
            y1 = y1,
            x2 = x,
            y2 = y2,
            duration = 1.0f
        )
    }

    private fun PageModel.hasAnyOrderedTargetVisible(targets: List<OrderedGoalTarget>): Boolean =
        targets.any { bestOrderedGoalClickTarget(it) != null }

    private fun PageModel.bestSettingsDomainClickTarget(domain: SettingsDomain, maxArea: Float): PageNode? =
        nodes
            .asSequence()
            .filter { it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.bounds.area <= maxArea * MAX_TARGET_AREA_RATIO }
            .filterNot { isOverlayLabel(it.label) }
            .mapNotNull { node ->
                val score = settingsDomainTargetScore(domain, node.label)
                if (score > 0) node to score else null
            }
            .sortedWith(
                compareByDescending<Pair<PageNode, Int>> { it.second }
                    .thenBy { it.first.bounds.area }
            )
            .firstOrNull()
            ?.first

    private fun PageModel.bestSettingsToggleTarget(domain: SettingsDomain): PageNode? =
        nodes
            .asSequence()
            .filter { it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filterNot { isOverlayLabel(it.label) }
            .mapNotNull { node ->
                val score = settingsToggleTargetScore(domain, node)
                if (score > 0) node to score else null
            }
            .sortedWith(
                compareByDescending<Pair<PageNode, Int>> { it.second }
                    .thenBy { it.first.bounds.area }
            )
            .firstOrNull()
            ?.first

    private fun settingsToggleTargetScore(domain: SettingsDomain, node: PageNode): Int {
        val label = normalizeText(node.label)
        if (label.isBlank()) return 0
        val resource = node.resourceId.lowercase()
        val className = node.className.lowercase()
        val switchLike = node.checkable ||
            resource.contains("switch") ||
            className.contains("switch")
        return when (domain) {
            SettingsDomain.NETWORK -> when {
                label == "wi fi" || label == "wifi" -> 120
                label.contains("use wi fi") || label.contains("use wifi") -> 130
                switchLike && (label.contains("wi fi") || label.contains("wifi")) -> 110
                label.contains("wi fi") || label.contains("wifi") -> {
                    if (isWifiToggleDecoy(label)) 0 else 70
                }
                else -> 0
            }

            SettingsDomain.CONNECTED_DEVICES -> when {
                label.contains("use bluetooth") -> 130
                switchLike && label.contains("bluetooth") -> 120
                label == "bluetooth" -> 100
                label.contains("bluetooth") -> {
                    if (isBluetoothToggleDecoy(label)) 0 else 65
                }
                else -> 0
            }

            else -> 0
        }
    }

    private fun PageModel.maxNodeArea(): Float =
        nodes.maxOfOrNull { it.bounds.area } ?: 0f

    private fun PageModel.hasFocusedEditable(): Boolean =
        nodes.any { it.enabled && it.editable && it.focused }

    private fun PageModel.numericKeyCount(): Int =
        nodes.count { node ->
            node.actionable &&
                node.bounds.area >= MIN_TARGET_AREA &&
                NUMERIC_KEYS.any { key -> node.matchesNumericKey(key) }
        }

    private fun PageModel.bestNumericKeyTarget(key: String): PageNode? =
        nodes
            .asSequence()
            .filter { it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.matchesNumericKey(key) }
            .sortedBy { it.bounds.area }
            .firstOrNull()

    private fun PageModel.bestScrollableBounds(): Rect? =
        nodes
            .asSequence()
            .filter { it.enabled && it.scrollable && it.bounds.area >= MIN_SCROLLABLE_AREA }
            .maxByOrNull { it.bounds.area }
            ?.bounds

    private fun PageModel.hasNavigateUp(): Boolean =
        nodes.any { node ->
            node.enabled && listOf(node.text, node.contentDesc, node.hintText, node.resourceId)
                .any { it.equals("Navigate up", ignoreCase = true) }
        }

    private fun PageModel.hasVisibleSemanticTarget(targetDescription: String): Boolean =
        nodes.any { node ->
            node.enabled &&
                !isOverlayLabel(node.label) &&
                textSimilarity(targetDescription, node.label) >= MIN_VISIBLE_TARGET_SCORE
        }

    private fun PageModel.hasVisibleSettingsDomainTarget(domain: SettingsDomain): Boolean {
        val maxArea = nodes.maxOfOrNull { it.bounds.area } ?: return false
        return bestSettingsDomainClickTarget(domain, maxArea) != null
    }

    private fun PageModel.primarySettingsDomain(): SettingsDomain? {
        val pageText = nodes
            .asSequence()
            .filter { it.enabled && !isOverlayLabel(it.label) }
            .joinToString(" ") { it.label }
        return settingsDomainScores(pageText)
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= 2 }
            ?.key
    }

    private fun parsePage(xml: String?): PageModel {
        if (xml.isNullOrBlank()) return PageModel()
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return PageModel()

        val nodeList = document.getElementsByTagName("node")
        val nodes = ArrayList<PageNode>(nodeList.length)
        for (index in 0 until nodeList.length) {
            val element = nodeList.item(index) as? Element ?: continue
            val bounds = parseBounds(element.attr("bounds")) ?: continue
            if (bounds.area <= 0f) continue
            val node = PageNode(
                bounds = bounds,
                text = element.attr("text"),
                contentDesc = element.attr("content-desc"),
                hintText = element.attr("hintText"),
                resourceId = element.attr("resource-id"),
                className = element.attr("class"),
                descendantText = descendantSemanticText(element),
                clickable = element.boolAttr("clickable"),
                longClickable = element.boolAttr("long-clickable"),
                focusable = element.boolAttr("focusable"),
                editable = element.boolAttr("editable"),
                focused = element.boolAttr("focused"),
                scrollable = element.boolAttr("scrollable"),
                checkable = element.boolAttr("checkable"),
                enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled")
            )
            nodes += node
        }
        return PageModel(nodes)
    }

    private fun descendantSemanticText(element: Element): String {
        val parts = linkedSetOf<String>()
        val descendants = element.getElementsByTagName("node")
        for (index in 0 until descendants.length) {
            val child = descendants.item(index) as? Element ?: continue
            if (child === element) continue
            listOf(child.attr("text"), child.attr("content-desc"), child.attr("hintText"))
                .filter { it.isNotBlank() }
                .forEach { parts += it }
            if (parts.joinToString(" ").length >= MAX_DESCENDANT_CHARS) break
        }
        return parts.joinToString(" ").take(MAX_DESCENDANT_CHARS)
    }

    private fun parseBounds(raw: String): Rect? {
        val values = BOUNDS_REGEX.find(raw)?.groupValues ?: return null
        val left = values.getOrNull(1)?.toFloatOrNull() ?: return null
        val top = values.getOrNull(2)?.toFloatOrNull() ?: return null
        val right = values.getOrNull(3)?.toFloatOrNull() ?: return null
        val bottom = values.getOrNull(4)?.toFloatOrNull() ?: return null
        return Rect(
            left = min(left, right),
            top = min(top, bottom),
            right = max(left, right),
            bottom = max(top, bottom)
        )
    }

    private fun inferSliderEndpointIntent(value: String): SliderEndpointIntent? {
        val normalized = value.lowercase()
        val wantsMin = MIN_ENDPOINT_TERMS.any { normalized.contains(it) }
        val wantsMax = MAX_ENDPOINT_TERMS.any { normalized.contains(it) }
        return when {
            wantsMin && !wantsMax -> SliderEndpointIntent.MIN
            wantsMax && !wantsMin -> SliderEndpointIntent.MAX
            else -> null
        }
    }

    private fun textSimilarity(left: String, right: String): Double {
        val a = normalizeText(left)
        val b = normalizeText(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b || a.contains(b) || b.contains(a)) return 1.0

        val aTerms = semanticTerms(a).filterNot { it in STOP_WORDS }.toSet()
        val bTerms = semanticTerms(b).filterNot { it in STOP_WORDS }.toSet()
        val tokenScore = if (aTerms.isNotEmpty() && bTerms.isNotEmpty()) {
            aTerms.intersect(bTerms).size.toDouble() / min(aTerms.size, bTerms.size).toDouble()
        } else {
            0.0
        }
        val lexicalScore = max(tokenScore, domainAliasScore(aTerms, bTerms))
        val charScore = if (shouldUseCharacterOverlap(a, b, aTerms, bTerms)) {
            characterOverlap(a, b)
        } else {
            0.0
        }
        return max(lexicalScore, charScore)
    }

    private fun shouldUseCharacterOverlap(
        normalizedLeft: String,
        normalizedRight: String,
        leftTerms: Set<String>,
        rightTerms: Set<String>
    ): Boolean {
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) return true
        return normalizedLeft.any(::isCjk) || normalizedRight.any(::isCjk)
    }

    private fun isCjk(char: Char): Boolean =
        char in '\u4e00'..'\u9fff'

    private fun domainAliasScore(aTerms: Set<String>, bTerms: Set<String>): Double {
        fun hasAny(terms: Set<String>, values: Set<String>): Boolean =
            terms.any { it in values }

        return when {
            hasAny(aTerms, BRIGHTNESS_TERMS) && hasAny(bTerms, DISPLAY_TERMS) -> 0.86
            hasAny(bTerms, BRIGHTNESS_TERMS) && hasAny(aTerms, DISPLAY_TERMS) -> 0.86
            hasAny(aTerms, WIFI_NETWORK_TERMS) && hasAny(bTerms, NETWORK_ROW_TERMS) -> 0.82
            hasAny(bTerms, WIFI_NETWORK_TERMS) && hasAny(aTerms, NETWORK_ROW_TERMS) -> 0.82
            hasAny(aTerms, BLUETOOTH_TERMS) && hasAny(bTerms, CONNECTED_DEVICE_TERMS) -> 0.82
            hasAny(bTerms, BLUETOOTH_TERMS) && hasAny(aTerms, CONNECTED_DEVICE_TERMS) -> 0.82
            hasAny(aTerms, VOLUME_SOUND_TERMS) && hasAny(bTerms, SOUND_ROW_TERMS) -> 0.82
            hasAny(bTerms, VOLUME_SOUND_TERMS) && hasAny(aTerms, SOUND_ROW_TERMS) -> 0.82
            else -> 0.0
        }
    }

    private fun characterOverlap(a: String, b: String): Double {
        val charsA = a.filter { !it.isWhitespace() }.toSet()
        val charsB = b.filter { !it.isWhitespace() }.toSet()
        if (charsA.isEmpty() || charsB.isEmpty()) return 0.0
        return charsA.intersect(charsB).size.toDouble() / min(charsA.size, charsB.size).toDouble()
    }

    private fun semanticTerms(value: String): List<String> =
        TERM_REGEX.findAll(value.lowercase())
            .map { it.value.trim('_', '-') }
            .filter { it.isNotBlank() }
            .toList()

    private fun normalizeText(value: String): String =
        value.lowercase()
            .replace(Regex("""[\s_\p{Pd}:/\\|]+"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\u4e00-\u9fff ]"""), "")
            .trim()

    private fun narrativeTargetSegments(context: UIContext, step: VLMStep): List<NarrativeSegment> {
        val overall = normalizeText(context.overallTask)
        return buildList {
            val currentGoal = normalizeText(context.currentStepGoal)
            if (currentGoal.isNotBlank() && currentGoal != overall) {
                add(NarrativeSegment(currentGoal, 36.0))
            }
            addNarrativeSegment(step.thought, 32.0)
            addNarrativeSegment(step.summary, 18.0)
            addNarrativeSegment(step.observation, 10.0)
        }
    }

    private fun MutableList<NarrativeSegment>.addNarrativeSegment(value: String, weight: Double) {
        val normalized = normalizeText(value)
        if (normalized.isNotBlank()) add(NarrativeSegment(normalized, weight))
    }

    private fun PageNode.bestFormFieldNarrativeScore(segments: List<NarrativeSegment>): Double {
        val phrases = formFieldLabelPhrases()
        if (phrases.isEmpty()) return 0.0
        return segments.maxOfOrNull { segment ->
            phrases.maxOfOrNull { phrase ->
                formFieldPhraseScore(segment, phrase)
            } ?: 0.0
        } ?: 0.0
    }

    private fun PageNode.formFieldLabelPhrases(): List<String> =
        directSemanticValues()
            .asSequence()
            .map(::normalizeText)
            .filter { it.isNotBlank() && it.length <= MAX_FORM_FIELD_LABEL_CHARS }
            .filter(::isLikelyFormFieldPhrase)
            .distinct()
            .toList()

    private fun PageNode.directSemanticValues(): List<String> =
        listOf(text, contentDesc, hintText)
            .filter { it.isNotBlank() }

    private fun formFieldPhraseScore(segment: NarrativeSegment, phrase: String): Double {
        val index = phraseLastIndex(segment.text, phrase)
        if (index < 0) return 0.0
        val terms = semanticTerms(phrase).filterNot { it in STOP_WORDS }
        val recency = index.toDouble() / segment.text.length.coerceAtLeast(1).toDouble()
        val phraseWeight = min(terms.size, 4) * 8.0
        val actionCueWeight = if (hasNearbyFormActionCue(segment.text, index)) 12.0 else 0.0
        return 80.0 + segment.weight + phraseWeight + actionCueWeight + recency
    }

    private fun phraseLastIndex(text: String, phrase: String): Int {
        val paddedText = " $text "
        val paddedPhrase = " $phrase "
        val paddedIndex = paddedText.lastIndexOf(paddedPhrase)
        return if (paddedIndex < 0) -1 else paddedIndex.coerceAtLeast(0)
    }

    private fun hasNearbyFormActionCue(text: String, phraseIndex: Int): Boolean {
        val start = (phraseIndex - FORM_ACTION_CUE_WINDOW).coerceAtLeast(0)
        val before = text.substring(start, phraseIndex)
        return FORM_ACTION_CUE_TERMS.any { before.contains(it) }
    }

    private fun isLikelyFormFieldPhrase(value: String): Boolean {
        val terms = semanticTerms(value).filterNot { it in STOP_WORDS }.toSet()
        return terms.isNotEmpty() && terms.any { it in FORM_FIELD_TERMS }
    }

    private fun isOverlayLabel(value: String): Boolean {
        val normalized = value.lowercase().replace(" ", "")
        return OVERLAY_LABELS.any { normalized.contains(it) }
    }

    private fun wantsScroll(value: String): Boolean {
        val normalized = value.lowercase()
        return SCROLL_INTENT_TERMS.any { normalized.contains(it) }
    }

    private fun hasSettingsToggleIntent(value: String): Boolean {
        val normalized = normalizeText(value)
        if (normalized.isBlank()) return false
        return SETTINGS_TOGGLE_INTENT_TERMS.any { normalized.contains(it) }
    }

    private fun toggleClickX(bounds: Rect, displayWidth: Int): Float {
        val width = displayWidth.coerceAtLeast(1)
        val rightInset = (width * 0.12f).roundToInt().coerceIn(64, 112)
        return (bounds.right - rightInset).coerceIn(bounds.left, bounds.right)
    }

    private fun isWifiToggleDecoy(label: String): Boolean =
        label.contains("network internet") ||
            label.contains("mobile wi fi") ||
            label.contains("hotspot") ||
            label.contains("add network") ||
            label.contains("network preferences") ||
            label.contains("turns back on") ||
            label.contains("connected") ||
            label.contains("open network")

    private fun isBluetoothToggleDecoy(label: String): Boolean =
        label.contains("connected devices") ||
            label.contains("pairing") ||
            label.contains("pair new") ||
            label.contains("will turn on to pair") ||
            label.contains("saved devices") ||
            label.contains("see all")

    private fun isGenericTargetDescription(value: String): Boolean {
        val terms = semanticTerms(value).filterNot { it in GENERIC_TARGET_TERMS }
        return terms.isEmpty()
    }

    private fun isSettingsTopLevelTarget(value: String): Boolean {
        val terms = semanticTerms(value).filterNot { it in STOP_WORDS }.toSet()
        return terms.any { it in SETTINGS_TOP_LEVEL_TARGET_TERMS }
    }

    private fun visibleGoal(context: UIContext, step: VLMStep, page: PageModel): VisibleGoal {
        val progress = settingsProgress(context)
        val pendingDomain = progress?.pendingDomain
        if (pendingDomain != null && page.hasVisibleSettingsDomainTarget(pendingDomain)) {
            return VisibleGoal(settingsDomainFocusText(pendingDomain), pendingDomain)
        }

        val actionDomain = settingsDomainForText(stepIntentText(step))
        val overallDomains = orderedSettingsDomains(context.overallTask).toSet()
        if (
            actionDomain != null &&
            actionDomain in overallDomains &&
            page.hasVisibleSettingsDomainTarget(actionDomain)
        ) {
            return VisibleGoal(settingsDomainFocusText(actionDomain), actionDomain)
        }

        return VisibleGoal(firstNonBlank(context.activeGoal(), context.overallTask), null)
    }

    private fun settingsProgress(context: UIContext): SettingsProgress? {
        val orderedDomains = orderedSettingsDomains(context.overallTask)
        if (orderedDomains.size < 2) return null
        val pendingIndex = orderedDomains.indexOfFirst { domain ->
            !context.trace.any { step -> isSettingsDomainMutationStep(step, domain) }
        }
        if (pendingIndex < 0) return null
        return SettingsProgress(
            orderedDomains = orderedDomains,
            pendingIndex = pendingIndex,
            pendingDomain = orderedDomains[pendingIndex]
        )
    }

    private fun orderedSettingsDomains(goalText: String): List<SettingsDomain> =
        SettingsDomain.values()
            .mapNotNull { domain ->
                settingsDomainFirstIndex(goalText, domain)?.let { domain to it }
            }
            .sortedBy { it.second }
            .map { it.first }
            .distinct()

    private fun isSettingsDomainMutationStep(step: UIStep, domain: SettingsDomain): Boolean {
        settingsStateMarkerCompletion(step, domain)?.let { return it }
        if (step.action is OpenAppAction || step.action is PressBackAction || step.action is PressHomeAction) {
            return false
        }
        val actionText = actionSemanticText(step.action)
        val text = listOf(actionText, step.thought, step.summary).joinToString(" ")
        if (!containsSettingsDomainSignal(text, domain)) return false
        val terms = semanticTerms(text).toSet()
        return terms.any { it in SETTINGS_MUTATION_TERMS }
    }

    private fun settingsStateMarkerCompletion(step: UIStep, domain: SettingsDomain): Boolean? {
        val key = when (domain) {
            SettingsDomain.NETWORK -> "wifi"
            SettingsDomain.CONNECTED_DEVICES -> "bluetooth"
            else -> return null
        }
        val summary = step.summary.lowercase()
        return when {
            summary.contains("settings_state_verified:$key=") -> true
            summary.contains("settings_state_pending:$key=") -> false
            summary.contains("settings_state_unknown:$key=") -> false
            else -> null
        }
    }

    private fun settingsDomainForText(text: String): SettingsDomain? {
        val scores = settingsDomainScores(text)
        return scores
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= 2 }
            ?.key
    }

    private fun settingsDomainScores(text: String): Map<SettingsDomain, Int> =
        SettingsDomain.values().associateWith { domain ->
            settingsDomainTargetScore(domain, text)
        }.filterValues { it > 0 }

    private fun settingsDomainTargetScore(domain: SettingsDomain, text: String): Int {
        val normalized = normalizeText(text)
        if (normalized.isBlank()) return 0
        val terms = semanticTerms(normalized).toSet()
        return when (domain) {
            SettingsDomain.NETWORK -> scoreSignals(
                normalized = normalized,
                semanticTerms = terms,
                strongPhrases = listOf("network internet", "internet", "wi fi", "wifi"),
                termSignals = listOf("wifi", "network", "internet", "hotspot", "wireless", "网络", "无线"),
                wifiPairSignal = true
            )

            SettingsDomain.CONNECTED_DEVICES -> scoreSignals(
                normalized = normalized,
                semanticTerms = terms,
                strongPhrases = listOf("connected devices", "bluetooth", "pairing"),
                termSignals = listOf("bluetooth", "pairing", "paired", "device", "devices", "蓝牙", "配对", "设备")
            )

            SettingsDomain.DISPLAY -> scoreSignals(
                normalized = normalized,
                semanticTerms = terms,
                strongPhrases = listOf("display", "brightness"),
                termSignals = listOf("display", "brightness", "bright", "显示", "亮度")
            )

            SettingsDomain.SOUND -> scoreSignals(
                normalized = normalized,
                semanticTerms = terms,
                strongPhrases = listOf("sound vibration", "sound", "volume"),
                termSignals = listOf("sound", "volume", "vibration", "ring", "alarm", "media", "声音", "音量", "振动")
            )
        }
    }

    private fun scoreSignals(
        normalized: String,
        semanticTerms: Set<String>,
        strongPhrases: List<String>,
        termSignals: List<String>,
        wifiPairSignal: Boolean = false
    ): Int {
        var score = 0
        strongPhrases.forEach { phrase ->
            if (normalized.contains(phrase)) score += 2
        }
        termSignals.forEach { term ->
            if (term in semanticTerms) score += 1
        }
        if (wifiPairSignal && "wi" in semanticTerms && "fi" in semanticTerms) score += 2
        return score
    }

    private fun settingsDomainFirstIndex(text: String, domain: SettingsDomain): Int? {
        val normalized = normalizeText(text)
        val needles = when (domain) {
            SettingsDomain.NETWORK -> listOf("wifi", "wi fi", "network", "internet", "hotspot", "wireless", "网络", "无线")
            SettingsDomain.CONNECTED_DEVICES -> listOf("bluetooth", "connected devices", "pairing", "device", "devices", "蓝牙", "配对", "设备")
            SettingsDomain.DISPLAY -> listOf("brightness", "display", "screen", "亮度", "显示", "屏幕")
            SettingsDomain.SOUND -> listOf("volume", "sound", "vibration", "ring", "alarm", "media", "音量", "声音", "振动")
        }
        return needles
            .mapNotNull { needle -> normalized.indexOf(needle).takeIf { it >= 0 } }
            .minOrNull()
    }

    private fun containsSettingsDomainSignal(text: String, domain: SettingsDomain): Boolean =
        settingsDomainTargetScore(domain, text) > 0

    private fun settingsDomainFocusText(domain: SettingsDomain): String =
        when (domain) {
            SettingsDomain.NETWORK -> "Network & internet Wi-Fi"
            SettingsDomain.CONNECTED_DEVICES -> "Connected devices Bluetooth pairing"
            SettingsDomain.DISPLAY -> "Display brightness screen"
            SettingsDomain.SOUND -> "Sound vibration volume"
        }

    private fun orderedGoalProgress(context: UIContext): OrderedGoalProgress? {
        val targets = orderedGoalTargets(context.overallTask)
        if (targets.size < MIN_ORDERED_TARGET_COUNT) return null

        var pendingIndex = 0
        for (traceStep in context.trace) {
            val pending = targets.getOrNull(pendingIndex) ?: break
            if (stepCompletesOrderedTarget(traceStep, pending)) {
                pendingIndex++
                continue
            }
            val next = targets.getOrNull(pendingIndex + 1)
            if (
                next != null &&
                orderedTargetCanImplyPrevious(previous = pending, next = next) &&
                stepCompletesOrderedTarget(traceStep, next)
            ) {
                pendingIndex += 2
            }
        }

        return OrderedGoalProgress(
            targets = targets,
            pendingIndex = pendingIndex,
            pendingTarget = targets.getOrNull(pendingIndex)
        )
    }

    private fun orderedGoalTargets(goalText: String): List<OrderedGoalTarget> {
        if (goalText.isBlank()) return emptyList()
        val actionCandidates = mutableListOf<Pair<Int, String>>()
        val verifyCandidates = mutableListOf<Pair<Int, String>>()
        ORDERED_ACTION_TARGET_REGEX.findAll(goalText).forEach { match ->
            actionCandidates += match.range.first to match.groupValues.getOrNull(1).orEmpty()
        }
        ORDERED_VERIFY_TARGET_REGEX.findAll(goalText).forEach { match ->
            verifyCandidates += match.range.first to match.groupValues.getOrNull(1).orEmpty()
        }

        val cleanedVerifyTargets = cleanOrderedTargetCandidates(verifyCandidates)
        if (cleanedVerifyTargets.size >= MIN_ORDERED_TARGET_COUNT) {
            return cleanedVerifyTargets
        }
        return cleanOrderedTargetCandidates(actionCandidates + verifyCandidates)
    }

    private fun cleanOrderedTargetCandidates(candidates: List<Pair<Int, String>>): List<OrderedGoalTarget> {
        val seen = linkedSetOf<String>()
        return candidates
            .sortedBy { it.first }
            .mapNotNull { (_, raw) ->
                val label = cleanOrderedTargetLabel(raw)
                val key = normalizeText(label)
                if (key.isBlank() || !seen.add(key) || !isUsefulOrderedTarget(label)) {
                    null
                } else {
                    OrderedGoalTarget(label = label, normalizedLabel = key)
                }
            }
    }

    private fun cleanOrderedTargetLabel(raw: String): String {
        var value = raw
            .replace(Regex("""["'“”‘’]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.', ',', ';', ':')
        value = value.replace(Regex("""(?i)^(?:the|a|an)\s+"""), "")
        value = value.replace(Regex("""(?i)\s+(?:is|are|be)\s+visible$"""), "")
        while (true) {
            val trimmed = value.replace(Regex("""(?i)\s+(?:page|screen|row|option|settings|menu|list)$"""), "")
            if (trimmed == value) break
            value = trimmed
        }
        return value.replace(Regex("""\s+"""), " ").trim().take(MAX_ORDERED_TARGET_LABEL_CHARS)
    }

    private fun isUsefulOrderedTarget(label: String): Boolean {
        val normalized = normalizeText(label)
        if (normalized in ORDERED_TARGET_STOP_PHRASES) return false
        val terms = semanticTerms(normalized).filterNot { it in STOP_WORDS || it in ORDERED_TARGET_STOP_WORDS }
        return terms.isNotEmpty()
    }

    private fun stepCompletesOrderedTarget(step: UIStep, target: OrderedGoalTarget): Boolean {
        if (step.action !is ClickAction && step.action !is LongPressAction) return false
        val actionText = actionSemanticText(step.action)
        return orderedTargetTextScore(target, actionText) >= MIN_ORDERED_TARGET_ACTION_SCORE
    }

    private fun VLMStep.hasOrderedTargetCompletionEvidence(target: OrderedGoalTarget): Boolean {
        val finishedContent = (action as? FinishedAction)?.content.orEmpty()
        val evidence = listOf(observation, summary, finishedContent)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return orderedTargetTextScore(target, evidence) >= MIN_ORDERED_TARGET_VISIBLE_SCORE
    }

    private fun orderedTargetTextScore(target: OrderedGoalTarget, text: String): Double {
        val normalizedText = normalizeText(text)
        if (target.normalizedLabel.isBlank() || normalizedText.isBlank()) return 0.0
        val targetTerms = semanticTerms(target.normalizedLabel)
            .filterNot { it in STOP_WORDS || it in ORDERED_TARGET_STOP_WORDS }
            .distinct()
        val textTerms = semanticTerms(normalizedText)
            .filterNot { it in STOP_WORDS || it in ORDERED_TARGET_STOP_WORDS }
            .distinct()
        if (targetTerms.isEmpty() || textTerms.isEmpty()) return 0.0

        if (targetTerms.size == 1) {
            val targetTerm = targetTerms.first()
            val firstMeaningful = textTerms.firstOrNull()
            return when {
                normalizedText == targetTerm -> 1.0
                normalizedText.startsWith("$targetTerm ") -> 0.98
                firstMeaningful == targetTerm -> 0.92
                else -> 0.0
            }
        }

        if (normalizedText == target.normalizedLabel) return 1.0
        if (normalizedText.contains(target.normalizedLabel)) return 0.96
        val overlap = targetTerms.intersect(textTerms.toSet()).size.toDouble() /
            targetTerms.size.toDouble().coerceAtLeast(1.0)
        return when {
            overlap >= 1.0 -> 0.9
            overlap >= 0.72 -> 0.76
            else -> 0.0
        }
    }

    private fun orderedTargetCanImplyPrevious(
        previous: OrderedGoalTarget,
        next: OrderedGoalTarget
    ): Boolean {
        val previousTerms = orderedTargetTerms(previous.normalizedLabel)
        val nextTerms = orderedTargetTerms(next.normalizedLabel).toSet()
        return previousTerms.isNotEmpty() && previousTerms.all { it in nextTerms }
    }

    private fun orderedTargetTerms(value: String): List<String> =
        semanticTerms(value)
            .filterNot { it in STOP_WORDS || it in ORDERED_TARGET_STOP_WORDS }
            .distinct()

    private fun stepIntentText(step: VLMStep): String =
        listOf(actionSemanticText(step.action), step.thought, step.summary).joinToString(" ")

    private fun actionSemanticText(action: UIAction): String =
        when (action) {
            is ClickAction -> action.targetDescription
            is ScrollAction -> action.targetDescription
            is LongPressAction -> action.targetDescription
            is TypeAction -> action.content
            is RecordAction -> action.content
            is InfoAction -> action.value
            is FeedbackAction -> action.value
            is AbortAction -> action.value
            is OpenAppAction -> action.packageName
            is RequireUserChoiceAction -> listOf(action.prompt, action.options.joinToString(" ")).joinToString(" ")
            is RequireUserConfirmationAction -> action.prompt
            is HotKeyAction -> action.key
            else -> ""
        }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun Element.boolAttr(name: String): Boolean =
        attr(name).equals("true", ignoreCase = true)

    private data class PageModel(
        val nodes: List<PageNode> = emptyList()
    )

    private data class PageNode(
        val bounds: Rect,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val resourceId: String,
        val className: String,
        val descendantText: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val enabled: Boolean
    ) {
        val actionable: Boolean
            get() = enabled && (clickable || longClickable || focusable || editable || checkable)

        val isFormFieldLike: Boolean
            get() {
                val lower = listOf(className, resourceId, text, contentDesc, hintText)
                    .joinToString(" ")
                    .lowercase()
                return editable ||
                    lower.contains("edittext") ||
                    lower.contains("spinner") ||
                    lower.contains("textfield")
            }

        val isSliderClass: Boolean
            get() {
                val lower = listOf(className, resourceId).joinToString(" ").lowercase()
                return lower.contains("seekbar") ||
                    lower.contains("slider") ||
                    lower.contains("range")
            }

        val hasDirectSliderLabel: Boolean
            get() {
                val direct = listOf(text, contentDesc, hintText, resourceTail())
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .lowercase()
                if (direct.isBlank()) return false
                if (direct.contains(",") || direct.contains("，")) return false
                return direct.contains("display brightness") ||
                    direct.contains("brightness slider") ||
                    direct.contains("media volume slider") ||
                    direct.contains("call volume slider") ||
                    direct.contains("ring volume slider") ||
                    direct.contains("alarm volume slider") ||
                    direct.contains("音量滑块") ||
                    direct.contains("亮度滑块")
            }

        val label: String
            get() = listOf(text, contentDesc, hintText, descendantText, resourceTail(), classSuffix())
                .filter { it.isNotBlank() }
                .joinToString(" ")

        fun matchesNumericKey(key: String): Boolean {
            val direct = listOf(text, contentDesc, hintText)
                .map(::normalizeText)
            if (direct.any { it == key }) return true
            val resource = normalizeText(resourceTail())
            return resource.endsWith("digit $key") || resource.endsWith("key $key")
        }

        private fun resourceTail(): String =
            resourceId.substringAfterLast('/').substringAfterLast(':')

        private fun classSuffix(): String =
            className.substringAfterLast('.')
    }

    private data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    private enum class SliderEndpointIntent {
        MIN,
        MAX
    }

    private enum class SettingsDomain {
        NETWORK,
        CONNECTED_DEVICES,
        DISPLAY,
        SOUND
    }

    private data class SettingsProgress(
        val orderedDomains: List<SettingsDomain>,
        val pendingIndex: Int,
        val pendingDomain: SettingsDomain
    )

    private data class VisibleGoal(
        val text: String,
        val preferredSettingsDomain: SettingsDomain?
    )

    private data class OrderedGoalProgress(
        val targets: List<OrderedGoalTarget>,
        val pendingIndex: Int,
        val pendingTarget: OrderedGoalTarget?
    )

    private data class OrderedGoalTarget(
        val label: String,
        val normalizedLabel: String
    )

    private data class NarrativeSegment(
        val text: String,
        val weight: Double
    )

    private val BOUNDS_REGEX = Regex("""\[(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)\]\[(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)\]""")
    private val TERM_REGEX = Regex("""[\p{L}\p{N}]+""")
    private val ORDERED_ACTION_TARGET_REGEX = Regex(
        """(?i)\b(?:open|tap|click|select|choose|enter|go to|navigate to)\s+(?:the\s+)?(.+?)(?=,|\bthen\b|\band\b|$)"""
    )
    private val ORDERED_VERIFY_TARGET_REGEX = Regex(
        """(?i)\bverify\s+(?:that\s+)?(?:the\s+)?(.+?)(?=,|\bthen\b|\band\b|$)"""
    )
    private val NUMERIC_KEYS = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
    private val SLIDER_TERMS = setOf(
        "slider",
        "seekbar",
        "brightness",
        "volume",
        "sound",
        "display brightness",
        "亮度",
        "音量",
        "滑块",
        "进度条"
    )
    private val CONCRETE_SLIDER_TERMS = setOf(
        "slider",
        "seekbar",
        "滑块",
        "进度条"
    )
    private val MIN_ENDPOINT_TERMS = setOf(
        "minimum",
        "lowest",
        "least",
        "min",
        "0%",
        "zero",
        "最小",
        "最低",
        "调低",
        "降到最低",
        "减小"
    )
    private val MAX_ENDPOINT_TERMS = setOf(
        "maximum",
        "highest",
        "full",
        "max",
        "100%",
        "最大",
        "最高",
        "调高",
        "升到最高",
        "增加",
        "拉满"
    )
    private val STOP_WORDS = setOf(
        "open",
        "click",
        "tap",
        "settings",
        "setting",
        "page",
        "option",
        "the",
        "to",
        "a",
        "an",
        "set",
        "turn",
        "toggle",
        "switch",
        "button",
        "当前",
        "打开",
        "点击",
        "设置",
        "页面",
        "选项"
    )
    private val OVERLAY_LABELS = setOf(
        "接管",
        "继续执行",
        "已接管控制",
        "小万",
        "omnibot",
        "oob"
    )
    private val SCROLL_INTENT_TERMS = setOf(
        "scroll",
        "swipe",
        "滑动",
        "滚动"
    )
    private val BRIGHTNESS_TERMS = setOf("brightness", "bright", "亮度")
    private val DISPLAY_TERMS = setOf("display", "screen", "显示", "屏幕")
    private val WIFI_NETWORK_TERMS = setOf(
        "wifi",
        "wi",
        "fi",
        "network",
        "internet",
        "mobile",
        "hotspot",
        "网络",
        "无线"
    )
    private val NETWORK_ROW_TERMS = setOf("network", "internet", "网络")
    private val BLUETOOTH_TERMS = setOf("bluetooth", "pair", "pairing", "蓝牙")
    private val CONNECTED_DEVICE_TERMS = setOf("device", "devices", "pairing", "连接", "设备", "配对")
    private val VOLUME_SOUND_TERMS = setOf("volume", "sound", "ring", "alarm", "media", "音量", "声音")
    private val SOUND_ROW_TERMS = setOf("sound", "vibration", "声音", "振动")
    private val FORM_FIELD_TERMS = setOf(
        "first",
        "last",
        "middle",
        "name",
        "phone",
        "mobile",
        "email",
        "mail",
        "label",
        "company",
        "title",
        "address",
        "city",
        "state",
        "zip",
        "postal",
        "note",
        "notes",
        "birthday",
        "date",
        "time",
        "hour",
        "minute",
        "second",
        "contact",
        "姓名",
        "名字",
        "姓",
        "名",
        "电话",
        "手机",
        "邮箱",
        "标签",
        "公司",
        "地址",
        "日期",
        "时间"
    )
    private val FORM_ACTION_CUE_TERMS = setOf(
        "click",
        "tap",
        "select",
        "focus",
        "enter",
        "type",
        "input",
        "fill",
        "correct",
        "clear",
        "choose",
        "点击",
        "选择",
        "输入",
        "填写",
        "修正",
        "清空"
    )
    private val GENERIC_TARGET_TERMS = setOf(
        "app",
        "application",
        "screen",
        "page",
        "main",
        "home",
        "homepage",
        "settings",
        "setting",
        "list",
        "menu",
        "current",
        "area",
        "container",
        "view"
    )
    private val SETTINGS_TOP_LEVEL_TARGET_TERMS = setOf(
        "bluetooth",
        "connected",
        "device",
        "devices",
        "wifi",
        "wi",
        "fi",
        "network",
        "internet"
    )
    private val SETTINGS_MUTATION_TERMS = setOf(
        "toggle",
        "switch",
        "enable",
        "disable",
        "turn",
        "set",
        "adjust",
        "minimum",
        "maximum",
        "min",
        "max",
        "mute",
        "unmute",
        "increase",
        "decrease",
        "drag",
        "slider",
        "开启",
        "关闭",
        "启用",
        "禁用",
        "切换",
        "设置",
        "调整",
        "最大",
        "最小"
    )
    private val SETTINGS_TOGGLE_INTENT_TERMS = setOf(
        "toggle",
        "switch",
        "turn on",
        "turn off",
        "enable",
        "disable",
        "enabled",
        "disabled",
        "on",
        "off",
        "开启",
        "关闭",
        "启用",
        "禁用",
        "切换"
    )
    private val ORDERED_TARGET_STOP_PHRASES = setOf(
        "settings",
        "settings home",
        "home",
        "current screen",
        "current page",
        "current"
    )
    private val ORDERED_TARGET_STOP_WORDS = setOf(
        "visible",
        "visibility",
        "shown",
        "showing",
        "show",
        "is",
        "are",
        "be",
        "with",
        "and",
        "or",
        "that",
        "has",
        "have",
        "contains",
        "finish",
        "finished"
    )
    private const val MIN_TARGET_AREA = 24f * 24f
    private const val MIN_SCROLLABLE_AREA = 120f * 160f
    private const val MAX_TARGET_AREA_RATIO = 0.45f
    private const val MAX_SCROLL_TO_CLICK_AREA_RATIO = 0.45f
    private const val MIN_GOAL_TARGET_SCORE = 0.58
    private const val MIN_VISIBLE_TARGET_SCORE = 0.58
    private const val MIN_ORDERED_TARGET_COUNT = 2
    private const val MIN_ORDERED_TARGET_NODE_SCORE = 0.76
    private const val MIN_ORDERED_TARGET_VISIBLE_SCORE = 0.76
    private const val MIN_ORDERED_TARGET_ACTION_SCORE = 0.88
    private const val MIN_FORM_FIELD_NARRATIVE_SCORE = 118.0
    private const val MIN_NUMERIC_KEYPAD_KEYS = 6
    private const val MAX_DESCENDANT_CHARS = 120
    private const val MAX_FORM_FIELD_LABEL_CHARS = 48
    private const val MAX_ORDERED_TARGET_LABEL_CHARS = 48
    private const val FORM_ACTION_CUE_WINDOW = 36
}
