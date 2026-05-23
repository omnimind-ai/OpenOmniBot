package cn.com.omnimind.assists.task.vlmserver

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object VLMPostActionObservation {
    data class Summary(
        val screenChanged: Boolean,
        val packageChanged: Boolean,
        val beforePackageName: String?,
        val afterPackageName: String?,
        val afterVisibleTexts: List<String>,
        val afterFocusedEditable: String?,
        val summaryText: String
    )

    fun summarize(step: UIStep): Summary? {
        val beforeXml = step.observationXml.orEmpty()
        val afterXml = step.afterObservationXml.orEmpty()
        if (afterXml.isBlank() && step.afterPackageName.isNullOrBlank()) return null

        val beforeTexts = visibleTexts(beforeXml)
        val afterTexts = visibleTexts(afterXml)
        val focused = focusedEditable(afterXml)
        val packageChanged = !step.packageName.isNullOrBlank() &&
            !step.afterPackageName.isNullOrBlank() &&
            step.packageName != step.afterPackageName
        val screenChanged = packageChanged || beforeTexts != afterTexts
        val summaryText = buildString {
            append(if (screenChanged) "after action screen changed" else "after action screen appears unchanged")
            if (!step.afterPackageName.isNullOrBlank()) {
                append("; package=").append(step.afterPackageName)
            }
            if (afterTexts.isNotEmpty()) {
                append("; visible=").append(afterTexts.take(POST_TEXT_COUNT).joinToString(" / "))
            }
            if (!focused.isNullOrBlank()) {
                append("; focused_editable=").append(focused)
            }
        }.take(MAX_SUMMARY_CHARS)

        return Summary(
            screenChanged = screenChanged,
            packageChanged = packageChanged,
            beforePackageName = step.packageName,
            afterPackageName = step.afterPackageName,
            afterVisibleTexts = afterTexts.take(POST_TEXT_COUNT),
            afterFocusedEditable = focused,
            summaryText = summaryText
        )
    }

    private fun visibleTexts(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val nodes = parseNodes(xml)
        val result = linkedSetOf<String>()
        nodes.forEach { element ->
            val label = normalizeLabel(
                firstNonBlank(
                    element.attr("text"),
                    element.attr("content-desc"),
                    element.attr("hintText"),
                    element.attr("hint-text"),
                    element.attr("state-description")
                )
            )
            if (label.isNotBlank() && !isOverlayLabel(label)) {
                result += label
            }
            if (result.size >= MAX_VISIBLE_TEXTS) return@forEach
        }
        return result.toList()
    }

    private fun focusedEditable(xml: String): String? {
        if (xml.isBlank()) return null
        return parseNodes(xml)
            .firstOrNull { it.boolAttr("editable") && it.boolAttr("focused") }
            ?.let { element ->
                normalizeLabel(
                    firstNonBlank(
                        element.attr("text"),
                        element.attr("content-desc"),
                        element.attr("hintText"),
                        element.attr("hint-text"),
                        element.attr("resource-id").substringAfterLast('/').substringAfterLast(':')
                    )
                )
            }
            ?.takeIf { it.isNotBlank() && !isOverlayLabel(it) }
    }

    private fun parseNodes(xml: String): List<Element> {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return emptyList()

        val nodeList = document.getElementsByTagName("node")
        val result = ArrayList<Element>(nodeList.length)
        for (index in 0 until nodeList.length) {
            (nodeList.item(index) as? Element)?.let { result += it }
        }
        return result
    }

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun Element.boolAttr(name: String): Boolean =
        attr(name).equals("true", ignoreCase = true)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun normalizeLabel(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().take(MAX_LABEL_CHARS)

    private fun isOverlayLabel(value: String): Boolean {
        val normalized = value.lowercase().replace(" ", "")
        return OVERLAY_LABELS.any { normalized.contains(it) }
    }

    private val OVERLAY_LABELS = setOf(
        "接管",
        "继续执行",
        "已接管控制",
        "小万",
        "omnibot",
        "oob"
    )
    private const val MAX_VISIBLE_TEXTS = 14
    private const val POST_TEXT_COUNT = 10
    private const val MAX_LABEL_CHARS = 80
    private const val MAX_SUMMARY_CHARS = 900
}
