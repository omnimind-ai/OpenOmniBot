package cn.com.omnimind.assists.task.vlmserver

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

interface VLMActionController {
    val id: String

    fun correct(request: VLMActionControllerRequest): VLMActionControllerDecision?
}

data class VLMActionControllerRequest(
    val step: VLMStep,
    val context: UIContext,
    val currentXml: String?,
    val currentPackageName: String?,
    val stepIndex: Int,
    val displayWidth: Int,
    val displayHeight: Int
)

data class VLMActionControllerDecision(
    val step: VLMStep,
    val reason: String,
    val summary: String = ""
)

object VLMActionControllerRegistry {
    private val controllers = CopyOnWriteArrayList<VLMActionController>()

    init {
        register(SemanticSearchController)
    }

    fun register(controller: VLMActionController) {
        require(controller.id.isNotBlank()) { "controller id is blank" }
        unregister(controller.id)
        controllers += controller
    }

    fun unregister(id: String): Boolean {
        val normalized = id.trim()
        return controllers.removeAll { it.id == normalized }
    }

    fun registeredControllerIds(): List<String> = controllers.map { it.id }

    fun correct(request: VLMActionControllerRequest): VLMActionControllerDecision? {
        for (controller in controllers) {
            val decision = runCatching { controller.correct(request) }.getOrNull()
            if (decision != null) {
                return decision.copy(reason = "${controller.id}:${decision.reason}")
            }
        }
        return null
    }
}

private object SemanticSearchController : VLMActionController {
    override val id: String = "semantic_search"

    override fun correct(request: VLMActionControllerRequest): VLMActionControllerDecision? {
        correctSearchInputClick(request)?.let { return it }
        correctScrollToSearchAffordance(request)?.let { return it }
        return null
    }

    private fun correctSearchInputClick(request: VLMActionControllerRequest): VLMActionControllerDecision? {
        val action = request.step.action as? ClickAction ?: return null
        val page = SearchPageModel.parse(request.currentXml)
        if (!page.hasSearchInput()) return null
        if (!page.isSearchInputClick(action)) return null

        val targets = targetTexts(request)
        if (targets.isEmpty()) return null
        val target = page.bestSearchResultTarget(targets) ?: return null
        return VLMActionControllerDecision(
            step = request.step.copy(
                action = action.copy(
                    targetDescription = target.label,
                    x = target.bounds.centerX,
                    y = target.bounds.centerY
                )
            ),
            reason = "click_input_to_visible_result",
            summary = "target=${target.label.take(80)}"
        )
    }

    private fun correctScrollToSearchAffordance(request: VLMActionControllerRequest): VLMActionControllerDecision? {
        val action = request.step.action as? ScrollAction ?: return null
        val activeGoalText = listOf(request.context.activeGoal(), request.context.overallTask)
            .joinToString(" ")
        if (wantsScroll(activeGoalText)) return null

        val page = SearchPageModel.parse(request.currentXml)
        val searchTarget = page.bestSearchAffordance() ?: return null
        val targets = targetTexts(request)
        if (targets.isEmpty()) return null
        if (page.hasVisibleTarget(targets)) return null
        if (!shouldUseSearchAffordance(request, targets)) return null

        return VLMActionControllerDecision(
            step = request.step.copy(
                action = ClickAction(
                    targetDescription = searchTarget.label,
                    x = searchTarget.bounds.centerX,
                    y = searchTarget.bounds.centerY
                )
            ),
            reason = "scroll_to_search_affordance",
            summary = "search=${searchTarget.label.take(80)}"
        )
    }

    private fun targetTexts(request: VLMActionControllerRequest): List<String> {
        val values = linkedSetOf<String>()
        val lastTyped = request.context.trace
            .asReversed()
            .mapNotNull { (it.action as? TypeAction)?.content?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        if (!lastTyped.isNullOrBlank()) values += lastTyped

        val action = request.step.action as? ClickAction
        action?.targetDescription
            ?.let(::cleanSearchTargetText)
            ?.takeIf(::hasUsefulTerms)
            ?.let { values += it }

        extractOrderedTargets(request.step.thought).forEach { values += it }
        extractOrderedTargets(request.step.summary).forEach { values += it }
        extractOrderedTargets(request.context.activeGoal()).forEach { values += it }
        extractOrderedTargets(request.context.overallTask).forEach { values += it }

        return values
            .map(::cleanSearchTargetText)
            .filter(::hasUsefulTerms)
            .distinct()
            .take(MAX_TARGET_TEXTS)
    }

    private fun SearchPageModel.hasSearchInput(): Boolean =
        nodes.any { it.enabled && it.isSearchInput }

    private fun SearchPageModel.isSearchInputClick(action: ClickAction): Boolean {
        val actionTerms = semanticTerms(action.targetDescription).toSet()
        val actionLooksLikeSearchInput =
            actionTerms.any { it == "search" } &&
                actionTerms.any { it == "edittext" || it == "input" || it == "field" || it == "textfield" }
        return nodes.any { node ->
            node.enabled &&
                node.isSearchInput &&
                (node.bounds.contains(action.x, action.y) || actionLooksLikeSearchInput)
        }
    }

    private fun SearchPageModel.bestSearchResultTarget(targetTexts: List<String>): SearchPageNode? {
        val searchBottom = nodes
            .asSequence()
            .filter { it.enabled && it.isSearchInput }
            .minByOrNull { it.bounds.top }
            ?.bounds
            ?.bottom
            ?: return null

        val targets = targetTexts
            .mapNotNull { target ->
                val normalized = normalizeText(target)
                val terms = usefulTerms(normalized)
                if (terms.isEmpty()) null else SearchTarget(normalized, terms)
            }
        if (targets.isEmpty()) return null

        return nodes
            .asSequence()
            .filter { it.enabled && it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.bounds.top >= searchBottom + SEARCH_RESULT_MIN_GAP_PX }
            .filterNot { it.isSearchInput }
            .filterNot { it.isSearchContainer }
            .filterNot { isOverlayLabel(it.label) }
            .mapNotNull { node ->
                val score = targets.maxOfOrNull { target -> scoreTarget(node, target) } ?: 0.0
                if (score >= MIN_RESULT_SCORE) node to score else null
            }
            .sortedWith(
                compareByDescending<Pair<SearchPageNode, Double>> { it.second }
                    .thenBy { it.first.bounds.top }
                    .thenByDescending { it.first.bounds.area }
            )
            .firstOrNull()
            ?.first
    }

    private fun SearchPageModel.bestSearchAffordance(): SearchPageNode? =
        nodes
            .asSequence()
            .filter { it.enabled && it.actionable && it.isSearchAffordance }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filterNot { isOverlayLabel(it.label) }
            .sortedWith(
                compareByDescending<SearchPageNode> { it.searchAffordanceScore }
                    .thenBy { it.bounds.top }
                    .thenByDescending { it.bounds.area }
            )
            .firstOrNull()

    private fun SearchPageModel.hasVisibleTarget(targetTexts: List<String>): Boolean {
        val targets = targetTexts
            .mapNotNull { target ->
                val normalized = normalizeText(target)
                val terms = usefulTerms(normalized)
                if (terms.isEmpty()) null else SearchTarget(normalized, terms)
            }
        if (targets.isEmpty()) return false
        return nodes
            .asSequence()
            .filter { it.enabled && it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filterNot { it.isSearchAffordance }
            .filterNot { isOverlayLabel(it.label) }
            .any { node ->
                targets.any { target -> scoreTarget(node, target) >= MIN_VISIBLE_TARGET_SCORE }
            }
    }

    private fun shouldUseSearchAffordance(
        request: VLMActionControllerRequest,
        targetTexts: List<String>
    ): Boolean {
        val stepText = listOf(
            actionSemanticText(request.step.action),
            request.step.thought,
            request.step.summary
        ).joinToString(" ")
        if (targetTexts.none { targetMentionedInText(it, stepText) }) return false
        if (request.stepIndex >= MIN_REPEATED_TARGET_SCROLLS_BEFORE_SEARCH) return true

        val repeatedScrollCount = request.context.trace
            .asReversed()
            .takeWhile { it.action is ScrollAction }
            .count { traceStep ->
                val traceText = listOf(
                    actionSemanticText(traceStep.action),
                    traceStep.thought,
                    traceStep.summary
                ).joinToString(" ")
                targetTexts.any { targetMentionedInText(it, traceText) }
            }
        return repeatedScrollCount >= MIN_REPEATED_TARGET_SCROLLS_BEFORE_SEARCH
    }

    private fun scoreTarget(node: SearchPageNode, target: SearchTarget): Double {
        val label = normalizeText(node.label)
        if (label.isBlank()) return 0.0
        val labelTerms = usefulTerms(label).toSet()
        if (labelTerms.isEmpty()) return 0.0

        val overlap = target.terms.count { it in labelTerms }
        if (overlap == 0) return 0.0
        val coverage = overlap.toDouble() / target.terms.size.toDouble().coerceAtLeast(1.0)
        if (coverage < MIN_TARGET_TERM_COVERAGE) return 0.0

        val exactBonus = when {
            label == target.normalizedText -> 80.0
            label.startsWith("${target.normalizedText} ") -> 60.0
            label.contains(target.normalizedText) -> 45.0
            else -> 0.0
        }
        val actionBonus = when {
            node.clickable || node.longClickable -> 25.0
            node.focusable -> 15.0
            else -> 0.0
        }
        val compactness = 12.0 / max(1, labelTerms.size)
        return coverage * 100.0 + exactBonus + actionBonus + compactness
    }

    private fun extractOrderedTargets(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matches = ORDERED_ACTION_TARGET_REGEX.findAll(text)
            .mapNotNull { it.groups[1]?.value?.trim() }
            .flatMap { splitTargetPhrase(it) }
            .toMutableList()
        VERIFY_TARGET_REGEX.findAll(text)
            .mapNotNullTo(matches) { it.groups[1]?.value?.trim() }
        return matches
            .map(::cleanSearchTargetText)
            .filter(::hasUsefulTerms)
            .distinct()
            .take(MAX_TARGET_TEXTS)
    }

    private fun targetMentionedInText(target: String, text: String): Boolean {
        val targetTerms = usefulTerms(normalizeText(target))
        val textTerms = usefulTerms(normalizeText(text)).toSet()
        return targetTerms.isNotEmpty() && targetTerms.all { it in textTerms }
    }

    private fun actionSemanticText(action: UIAction): String =
        when (action) {
            is ClickAction -> action.targetDescription
            is ScrollAction -> action.targetDescription
            is LongPressAction -> action.targetDescription
            is TypeAction -> action.content
            is InputTextAction -> listOf(action.targetDescription, action.content).joinToString(" ")
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

    private fun wantsScroll(text: String): Boolean {
        val terms = semanticTerms(text).toSet()
        return SCROLL_INTENT_TERMS.any { it in terms }
    }

    private fun splitTargetPhrase(value: String): List<String> =
        value.split(Regex("""(?i)\s+or\s+|\s*/\s*|,|，"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun cleanSearchTargetText(value: String): String {
        var normalized = value
            .replace(Regex("""(?i)\b(open_search_view_edit_text|search_src_text)\b"""), " ")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""(?i)\b(edittext|textfield|input|field|search|result|results|row|option|button)\b"""), " ")
            .replace(Regex("""(?i)^(?:the|a|an)\s+"""), "")
            .replace(Regex("""(?i)\s+(?:page|screen|visible|is visible|are visible)$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        while (true) {
            val trimmed = normalized.replace(Regex("""(?i)\s+(?:page|screen|menu|list|settings|setting)$"""), "")
            if (trimmed == normalized) break
            normalized = trimmed.trim()
        }
        return normalized.take(MAX_TARGET_CHARS)
    }

    private fun hasUsefulTerms(value: String): Boolean = usefulTerms(normalizeText(value)).isNotEmpty()

    private fun usefulTerms(value: String): List<String> =
        semanticTerms(value)
            .filterNot { it in STOP_WORDS }
            .distinct()

    private fun semanticTerms(value: String): List<String> =
        TERM_REGEX.findAll(value.lowercase())
            .map { it.value.trim('_', '-') }
            .filter { it.isNotBlank() }
            .toList()

    private fun normalizeText(value: String): String =
        value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isOverlayLabel(value: String): Boolean {
        val normalized = normalizeText(value).replace(" ", "")
        return OVERLAY_LABELS.any { normalized.contains(it) }
    }

    private data class SearchTarget(
        val normalizedText: String,
        val terms: List<String>
    )

    private data class SearchPageModel(
        val nodes: List<SearchPageNode> = emptyList()
    ) {
        companion object {
            fun parse(xml: String?): SearchPageModel {
                if (xml.isNullOrBlank()) return SearchPageModel()
                val document = runCatching {
                    val factory = DocumentBuilderFactory.newInstance().apply {
                        isNamespaceAware = false
                        isExpandEntityReferences = false
                        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                    }
                    factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
                }.getOrNull() ?: return SearchPageModel()

                val nodeList = document.getElementsByTagName("node")
                val nodes = ArrayList<SearchPageNode>(nodeList.length)
                for (index in 0 until nodeList.length) {
                    val element = nodeList.item(index) as? Element ?: continue
                    val bounds = parseBounds(element.attr("bounds")) ?: continue
                    if (bounds.area <= 0f) continue
                    nodes += SearchPageNode(
                        bounds = bounds,
                        text = element.attr("text"),
                        contentDesc = element.attr("content-desc"),
                        hintText = firstNonBlank(element.attr("hintText"), element.attr("hint")),
                        resourceId = element.attr("resource-id"),
                        className = element.attr("class"),
                        descendantText = descendantSemanticText(element),
                        clickable = element.boolAttr("clickable"),
                        longClickable = element.boolAttr("long-clickable"),
                        focusable = element.boolAttr("focusable"),
                        editable = element.boolAttr("editable"),
                        enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled")
                    )
                }
                return SearchPageModel(nodes)
            }

            private fun descendantSemanticText(element: Element): String {
                val parts = linkedSetOf<String>()
                val descendants = element.getElementsByTagName("node")
                for (index in 0 until descendants.length) {
                    val child = descendants.item(index) as? Element ?: continue
                    if (child === element) continue
                    val label = listOf(
                        child.attr("text"),
                        child.attr("content-desc"),
                        child.attr("hintText"),
                        child.attr("hint")
                    ).firstOrNull { it.isNotBlank() }.orEmpty()
                    if (label.isNotBlank() && !isOverlayLabel(label)) {
                        parts += label
                    }
                    if (parts.size >= 4 || parts.joinToString(" ").length >= MAX_DESCENDANT_CHARS) break
                }
                return parts.joinToString(" ").take(MAX_DESCENDANT_CHARS)
            }

            private fun parseBounds(value: String): SearchRect? {
                val match = BOUNDS_REGEX.find(value) ?: return null
                val left = match.groupValues[1].toFloatOrNull() ?: return null
                val top = match.groupValues[2].toFloatOrNull() ?: return null
                val right = match.groupValues[3].toFloatOrNull() ?: return null
                val bottom = match.groupValues[4].toFloatOrNull() ?: return null
                return SearchRect(left, top, right, bottom)
            }
        }
    }

    private data class SearchPageNode(
        val bounds: SearchRect,
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
        val enabled: Boolean
    ) {
        val actionable: Boolean
            get() = enabled && (clickable || longClickable || focusable || editable)

        val isSearchInput: Boolean
            get() {
                val lower = listOf(resourceId, className, text, contentDesc, hintText)
                    .joinToString(" ")
                    .lowercase()
                val searchSignal = lower.contains("search") ||
                    lower.contains("open_search_view_edit_text") ||
                    lower.contains("search_src_text")
                val inputSignal = editable ||
                    lower.contains("edittext") ||
                    lower.contains("textfield") ||
                    lower.contains("input")
                return searchSignal && inputSignal
            }

        val isSearchContainer: Boolean
            get() {
                val lower = listOf(resourceId, className, text, contentDesc, hintText)
                    .joinToString(" ")
                    .lowercase()
                return lower.contains("search_action_bar") ||
                    lower.contains("search_bar") ||
                    lower.contains("search_view")
            }

        val isSearchAffordance: Boolean
            get() {
                val lower = listOf(resourceId, className, text, contentDesc, hintText, descendantText)
                    .joinToString(" ")
                    .lowercase()
                return lower.contains("search") ||
                    lower.contains("搜索")
            }

        val searchAffordanceScore: Int
            get() {
                val lower = listOf(resourceId, className, text, contentDesc, hintText, descendantText)
                    .joinToString(" ")
                    .lowercase()
                return when {
                    lower.contains("search_action_bar") -> 130
                    lower.contains("search_bar") -> 120
                    lower.contains("search_view") -> 110
                    lower.contains("search") && (clickable || focusable) -> 95
                    lower.contains("搜索") && (clickable || focusable) -> 95
                    else -> 0
                }
            }

        val label: String
            get() = listOf(text, contentDesc, hintText, descendantText, resourceTail(), classSuffix())
                .filter { it.isNotBlank() }
                .joinToString(" ")

        private fun resourceTail(): String =
            resourceId.substringAfterLast('/').substringAfterLast(':')

        private fun classSuffix(): String =
            className.substringAfterLast('.')
    }

    private data class SearchRect(
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

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun Element.boolAttr(name: String): Boolean =
        attr(name).equals("true", ignoreCase = true)

    private val BOUNDS_REGEX = Regex("""\[(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)\]\[(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)\]""")
    private val TERM_REGEX = Regex("""[\p{L}\p{N}]+""")
    private val ORDERED_ACTION_TARGET_REGEX = Regex(
        """(?i)\b(?:open|tap|click|select|choose|enter|go to|navigate to)\s+(?:the\s+)?(.+?)(?=,|\bthen\b|\band\b|$)"""
    )
    private val VERIFY_TARGET_REGEX = Regex(
        """(?i)\bverify\s+(?:that\s+)?(?:the\s+)?(.+?)(?=,|\bthen\b|\band\b|$)"""
    )
    private val STOP_WORDS = setOf(
        "open",
        "tap",
        "click",
        "select",
        "choose",
        "enter",
        "go",
        "to",
        "the",
        "a",
        "an",
        "app",
        "home",
        "screen",
        "page",
        "settings",
        "setting",
        "search",
        "result",
        "results",
        "find",
        "locate",
        "row",
        "option",
        "button",
        "edittext",
        "textfield",
        "input",
        "field",
        "edit",
        "view",
        "text",
        "visible",
        "verify",
        "then",
        "finish",
        "finished",
        "当前",
        "打开",
        "点击",
        "选择",
        "搜索",
        "结果",
        "查找",
        "寻找",
        "设置",
        "页面",
        "选项"
    )
    private val SCROLL_INTENT_TERMS = setOf(
        "scroll",
        "swipe",
        "滑动",
        "滚动"
    )
    private val OVERLAY_LABELS = setOf(
        "接管",
        "继续执行",
        "已接管控制",
        "小万",
        "omnibot",
        "oob"
    )
    private const val MIN_TARGET_AREA = 24f * 24f
    private const val SEARCH_RESULT_MIN_GAP_PX = 8f
    private const val MIN_TARGET_TERM_COVERAGE = 0.72
    private const val MIN_RESULT_SCORE = 100.0
    private const val MIN_VISIBLE_TARGET_SCORE = 95.0
    private const val MIN_REPEATED_TARGET_SCROLLS_BEFORE_SEARCH = 2
    private const val MAX_TARGET_TEXTS = 8
    private const val MAX_TARGET_CHARS = 64
    private const val MAX_DESCENDANT_CHARS = 120
}
