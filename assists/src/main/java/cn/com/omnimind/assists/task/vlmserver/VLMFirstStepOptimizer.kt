package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.util.OmniLog
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Builds a one-shot first-step hint from the live page only.
 *
 * This is intentionally not a memory layer: the generated fields are injected
 * only for the first VLM turn and are cleared from later turns.
 */
object VLMFirstStepOptimizer {
    fun enrichContext(
        context: UIContext,
        currentXml: String?,
        currentPackageName: String?,
        stepIndex: Int
    ): UIContext {
        val currentPackage = currentPackageName?.trim().orEmpty()
        if (stepIndex != 0 || context.trace.isNotEmpty()) {
            return context.copy(
                currentPackageName = currentPackage,
                currentPageSummary = "",
                firstStepGuidance = ""
            )
        }

        val page = parsePage(currentXml)
        val goalText = firstNonBlank(context.activeGoal(), context.overallTask)
        val goalMatches = findGoalMatches(goalText, page)
        val pageSummary = buildPageSummary(
            page = page,
            currentPackage = currentPackage,
            targetPackage = context.targetPackageName,
            goalMatches = goalMatches
        )
        val firstStepGuidance = buildFirstStepGuidance(
            currentPackage = currentPackage,
            targetPackage = context.targetPackageName,
            goalText = goalText,
            goalMatches = goalMatches,
            hasPageSignal = page.hasSignals,
            hasScrollable = page.hasScrollable
        )
        runCatching {
            OmniLog.d(
                TAG,
                "first-step context enriched: currentPackage=$currentPackage " +
                    "targetPackage=${context.targetPackageName} " +
                    "pageSignals=${page.hasSignals} summaryChars=${pageSummary.length} " +
                    "guidanceChars=${firstStepGuidance.length}"
            )
        }
        return context.copy(
            currentPackageName = currentPackage,
            currentPageSummary = pageSummary,
            firstStepGuidance = firstStepGuidance
        )
    }

    private fun buildPageSummary(
        page: PageSignals,
        currentPackage: String,
        targetPackage: String,
        goalMatches: List<String>
    ): String {
        val lines = mutableListOf<String>()
        if (currentPackage.isNotBlank()) {
            lines += "前台包名: $currentPackage"
        }
        if (targetPackage.isNotBlank()) {
            lines += "目标包名: $targetPackage"
        }
        if (goalMatches.isNotEmpty()) {
            lines += "任务相关首屏候选: ${goalMatches.joinToString(" / ")}"
        }
        if (page.visibleTexts.isNotEmpty()) {
            lines += "首屏可见文本: ${page.visibleTexts.joinToString(" / ")}"
        }
        if (page.actionableLabels.isNotEmpty()) {
            lines += "首屏可交互元素: ${page.actionableLabels.joinToString(" / ")}"
        }
        val capabilities = buildList {
            if (page.hasFocusedInput) add("当前已有输入框焦点")
            if (page.hasEditable) add("存在输入框")
            if (page.hasScrollable) add("存在可滚动区域")
        }
        if (capabilities.isNotEmpty()) {
            lines += "页面能力: ${capabilities.joinToString(" / ")}"
        }
        return lines.joinToString("\n").take(MAX_SUMMARY_CHARS)
    }

    private fun buildFirstStepGuidance(
        currentPackage: String,
        targetPackage: String,
        goalText: String,
        goalMatches: List<String>,
        hasPageSignal: Boolean,
        hasScrollable: Boolean
    ): String {
        val normalizedCurrent = currentPackage.trim()
        val normalizedTarget = targetPackage.trim()
        return when {
            normalizedTarget.isNotBlank() &&
                !normalizedCurrent.equals(normalizedTarget, ignoreCase = true) ->
                "首步策略: 当前前台不是目标应用，优先调用 open_app(package_name=$normalizedTarget)，不要猜其他包名，也不要先点当前页控件。"

            wantsScroll(goalText) ->
                "首步策略: 用户明确要求滑动/滚动，优先对当前可滚动区域执行 scroll；不要改点首个列表项。"

            goalMatches.isNotEmpty() ->
                "首步策略: 用户任务与首屏候选「${goalMatches.joinToString(" / ")}」匹配，优先点击匹配候选；不要默认点击列表第一项，也不要点击与任务关键词无关的控件。"

            normalizedTarget.isNotBlank() ->
                if (hasScrollable) {
                    "首步策略: 目标应用已在前台，不要重复 open_app；先按用户任务匹配首屏文本。若目标文本不在首屏，优先使用较大幅度 scroll（从可滚动区域下部滑到中上部）或搜索，不要短滑，也不要点击无关的首个列表项。"
                } else {
                    "首步策略: 目标应用已在前台，不要重复 open_app；先按用户任务匹配首屏文本。若目标文本不在首屏，优先搜索，不要点击无关的首个列表项。"
                }

            hasPageSignal ->
                if (hasScrollable) {
                    "首步策略: 先按用户任务匹配首屏可见文本和可交互元素。若目标文本不在首屏，优先使用较大幅度 scroll（从可滚动区域下部滑到中上部）或搜索；不要短滑，也不要默认点击无关的首个列表项。"
                } else {
                    "首步策略: 先按用户任务匹配首屏可见文本和可交互元素。若目标文本不在首屏，优先搜索；不要默认点击无关的首个列表项。"
                }

            else ->
                "首步策略: 当前页面结构不可用，主要依据截图判断；若无法确认下一步，不要猜测，使用 info 请求补充。"
        }
    }

    private fun parsePage(xml: String?): PageSignals {
        if (xml.isNullOrBlank()) return PageSignals()
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return PageSignals()

        val visibleTexts = linkedSetOf<String>()
        val actionableLabels = linkedSetOf<String>()
        var hasEditable = false
        var hasFocusedInput = false
        var hasScrollable = false

        val nodeList = document.getElementsByTagName("node")
        for (index in 0 until nodeList.length) {
            val element = nodeList.item(index) as? Element ?: continue
            val text = normalizeLabel(firstNonBlank(
                element.attr("text"),
                element.attr("content-desc"),
                element.attr("hintText"),
                element.attr("hint")
            ))
            if (text.isNotBlank() && !isOverlayLabel(text)) {
                visibleTexts += text
            }

            val editable = element.boolAttr("editable")
            val focused = element.boolAttr("focused")
            val scrollable = element.boolAttr("scrollable")
            val actionable = element.boolAttr("clickable") ||
                element.boolAttr("long-clickable") ||
                element.boolAttr("focusable") ||
                editable ||
                scrollable

            hasEditable = hasEditable || editable
            hasFocusedInput = hasFocusedInput || (editable && focused)
            hasScrollable = hasScrollable || scrollable

            if (actionable) {
                val label = normalizeLabel(firstNonBlank(text, descendantSemanticText(element)))
                if (label.isNotBlank() && !isOverlayLabel(label)) {
                    actionableLabels += label
                } else if (scrollable) {
                    actionableLabels += "可滚动区域"
                } else if (editable) {
                    actionableLabels += "输入框"
                }
            }

            if (visibleTexts.size >= MAX_VISIBLE_TEXTS && actionableLabels.size >= MAX_ACTIONABLE_LABELS) {
                break
            }
        }

        return PageSignals(
            visibleTexts = visibleTexts.take(MAX_VISIBLE_TEXTS),
            actionableLabels = actionableLabels.take(MAX_ACTIONABLE_LABELS),
            hasEditable = hasEditable,
            hasFocusedInput = hasFocusedInput,
            hasScrollable = hasScrollable
        )
    }

    private fun findGoalMatches(goalText: String, page: PageSignals): List<String> {
        val goalTerms = semanticTerms(goalText).filterNot { it in GOAL_STOP_WORDS }.toSet()
        if (goalTerms.isEmpty()) return emptyList()

        val candidates = (page.visibleTexts + page.actionableLabels)
            .map(::normalizeLabel)
            .filter { it.isNotBlank() && !isOverlayLabel(it) }
            .distinct()

        return candidates.mapNotNull { candidate ->
            val labelTerms = semanticTerms(candidate).filterNot { it in LABEL_STOP_WORDS }.distinct()
            if (labelTerms.isEmpty()) return@mapNotNull null
            val aliasMatch = domainAliasMatch(goalTerms, labelTerms.toSet())
            val rawMatched = labelTerms.count { it in goalTerms }
            if (rawMatched == 0 && !aliasMatch) return@mapNotNull null
            val matched = if (rawMatched == 0 && aliasMatch) 1 else rawMatched
            val coverage = matched.toDouble() / labelTerms.size.toDouble()
            val exactBonus = if (goalText.contains(candidate, ignoreCase = true)) 1.0 else 0.0
            val aliasBonus = if (aliasMatch) 0.7 else 0.0
            val score = coverage + exactBonus + aliasBonus - (candidate.length.coerceAtMost(80) / 1000.0)
            GoalMatch(candidate, score)
        }
            .filter { it.score >= MIN_GOAL_MATCH_SCORE }
            .sortedWith(compareByDescending<GoalMatch> { it.score }.thenBy { it.label.length })
            .map { it.label }
            .take(MAX_GOAL_MATCHES)
    }

    private fun domainAliasMatch(goalTerms: Set<String>, labelTerms: Set<String>): Boolean {
        fun hasAny(terms: Set<String>, values: Set<String>): Boolean =
            terms.any { it in values }

        return (hasAny(goalTerms, BRIGHTNESS_TERMS) && hasAny(labelTerms, DISPLAY_TERMS)) ||
            (hasAny(goalTerms, WIFI_NETWORK_TERMS) && hasAny(labelTerms, NETWORK_ROW_TERMS)) ||
            (hasAny(goalTerms, BLUETOOTH_TERMS) && hasAny(labelTerms, CONNECTED_DEVICE_TERMS)) ||
            (hasAny(goalTerms, VOLUME_SOUND_TERMS) && hasAny(labelTerms, SOUND_ROW_TERMS))
    }

    private fun semanticTerms(value: String): List<String> =
        TERM_REGEX.findAll(value.lowercase())
            .map { it.value.trim('_', '-') }
            .filter { it.isNotBlank() }
            .toList()

    private fun wantsScroll(goalText: String): Boolean {
        val normalized = goalText.lowercase()
        return SCROLL_KEYWORDS.any { normalized.contains(it) }
    }

    private fun descendantSemanticText(element: Element): String {
        val parts = linkedSetOf<String>()
        val descendants = element.getElementsByTagName("node")
        for (index in 0 until descendants.length) {
            val child = descendants.item(index) as? Element ?: continue
            if (child === element) continue
            val label = normalizeLabel(firstNonBlank(
                child.attr("text"),
                child.attr("content-desc"),
                child.attr("hintText"),
                child.attr("hint")
            ))
            if (label.isNotBlank() && !isOverlayLabel(label)) {
                parts += label
            }
            if (parts.size >= 4 || parts.joinToString(" ").length >= MAX_DESCENDANT_CHARS) break
        }
        return parts.joinToString(" ").take(MAX_DESCENDANT_CHARS)
    }

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun Element.boolAttr(name: String): Boolean =
        attr(name).equals("true", ignoreCase = true)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun normalizeLabel(value: String): String =
        value.replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_LABEL_CHARS)

    private fun isOverlayLabel(value: String): Boolean {
        val normalized = value.lowercase().replace(" ", "")
        return OVERLAY_LABELS.any { normalized.contains(it) }
    }

    private data class PageSignals(
        val visibleTexts: List<String> = emptyList(),
        val actionableLabels: List<String> = emptyList(),
        val hasEditable: Boolean = false,
        val hasFocusedInput: Boolean = false,
        val hasScrollable: Boolean = false
    ) {
        val hasSignals: Boolean
            get() = visibleTexts.isNotEmpty() || actionableLabels.isNotEmpty() ||
                hasEditable || hasFocusedInput || hasScrollable
    }

    private data class GoalMatch(
        val label: String,
        val score: Double
    )

    private val OVERLAY_LABELS = setOf(
        "接管",
        "继续执行",
        "已接管控制",
        "小万",
        "omnibot",
        "oob"
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
    private val GOAL_STOP_WORDS = setOf(
        "当前",
        "设置",
        "首页",
        "打开",
        "点击",
        "执行",
        "第一步",
        "即可",
        "如果",
        "没有",
        "请",
        "先",
        "列表",
        "current",
        "open",
        "click",
        "tap",
        "settings",
        "setting",
        "first",
        "step",
        "page",
        "option"
    )
    private val LABEL_STOP_WORDS = setOf(
        "settings",
        "setting",
        "option",
        "page"
    )
    private val SCROLL_KEYWORDS = setOf(
        "滑动",
        "滚动",
        "向下",
        "向上",
        "scroll",
        "swipe"
    )
    private val TERM_REGEX = Regex("""[\p{L}\p{N}]+""")
    private const val MAX_VISIBLE_TEXTS = 12
    private const val MAX_ACTIONABLE_LABELS = 10
    private const val MAX_LABEL_CHARS = 40
    private const val MAX_DESCENDANT_CHARS = 120
    private const val MAX_SUMMARY_CHARS = 900
    private const val MAX_GOAL_MATCHES = 4
    private const val MIN_GOAL_MATCH_SCORE = 0.45
    private const val TAG = "VLMFirstStepOptimizer"
}
