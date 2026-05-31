package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Matches a repaired target text against the recorded source XML for a Function
 * step. update_function owns the patch; this matcher owns XML parsing/scoring.
 */
class OobFunctionTargetSourceMatcher {
    fun match(
        step: Map<String, Any?>,
        args: Map<String, Any?>,
        desiredText: String,
        action: String,
    ): Match? {
        val sourceXml = sourceXmlForStep(step, args)
        return findNodeByText(sourceXml, desiredText, action)
    }

    data class Bounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val raw: String,
    ) {
        val centerX: Double get() = (left + right) / 2.0
        val centerY: Double get() = (top + bottom) / 2.0
        val area: Double get() = (right - left).coerceAtLeast(0.0) * (bottom - top).coerceAtLeast(0.0)
    }

    data class Match(
        val text: String,
        val contentDesc: String,
        val resourceId: String,
        val bounds: Bounds,
        val clickable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val score: Int,
    )

    private fun sourceXmlForStep(step: Map<String, Any?>, args: Map<String, Any?>): String {
        val sourceContext = mapArg(step["source_context"])
            .ifEmpty { mapArg(args["source_context"]) }
        val srcCtx = mapArg(sourceContext["src_ctx"])
        return firstNonBlank(
            srcCtx["page"],
            srcCtx["xml"],
            sourceContext["page"],
            sourceContext["xml"],
        )
    }

    private fun findNodeByText(xml: String, desiredText: String, action: String): Match? {
        if (xml.isBlank() || desiredText.isBlank()) return null
        return parseXmlElements(xml).mapNotNull { element ->
            val bounds = parseXmlBounds(element.getAttribute("bounds")) ?: return@mapNotNull null
            val text = element.getAttribute("text").trim()
            val contentDesc = element.getAttribute("content-desc").trim()
            val resourceId = element.getAttribute("resource-id").trim()
            val label = listOf(text, contentDesc, resourceId).joinToString(" ")
            if (!containsLoose(label, desiredText)) return@mapNotNull null
            val clickable = element.boolAttr("clickable") || element.boolAttr("long-clickable")
            val enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled")
            val visible = !element.hasAttribute("visible-to-user") || element.boolAttr("visible-to-user")
            val score = nodeTextScore(
                desiredText = desiredText,
                text = text,
                contentDesc = contentDesc,
                resourceId = resourceId,
                clickable = clickable,
                enabled = enabled,
                visible = visible,
                action = action,
                area = bounds.area,
            )
            Match(
                text = text,
                contentDesc = contentDesc,
                resourceId = resourceId,
                bounds = bounds,
                clickable = clickable,
                enabled = enabled,
                visible = visible,
                score = score,
            )
        }.maxWithOrNull(
            compareBy<Match> { it.score }
                .thenByDescending { it.clickable }
                .thenBy { it.bounds.area }
        )
    }

    private fun nodeTextScore(
        desiredText: String,
        text: String,
        contentDesc: String,
        resourceId: String,
        clickable: Boolean,
        enabled: Boolean,
        visible: Boolean,
        action: String,
        area: Double,
    ): Int {
        val desired = normalizeText(desiredText)
        var score = when {
            normalizeText(text) == desired -> 100
            normalizeText(contentDesc) == desired -> 96
            normalizeText(text).contains(desired) -> 85
            normalizeText(contentDesc).contains(desired) -> 80
            normalizeText(resourceId).contains(desired) -> 50
            else -> 0
        }
        if (score == 0) return 0
        if (visible) score += 8
        if (enabled) score += 6
        if (clickable && action in setOf("click", "long_press")) score += 10
        if (area <= 0.0) score -= 20
        return score
    }

    private fun parseXmlElements(xml: String): List<Element> {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            val document = factory.newDocumentBuilder()
                .parse(InputSource(StringReader(xml.trim())))
            val result = mutableListOf<Element>()
            collectElements(document.documentElement, result)
            result
        }.getOrDefault(emptyList())
    }

    private fun collectElements(node: Node?, result: MutableList<Element>) {
        if (node == null) return
        if (node is Element) result += node
        val children = node.childNodes ?: return
        for (index in 0 until children.length) {
            collectElements(children.item(index), result)
        }
    }

    private fun Element.boolAttr(name: String): Boolean {
        val value = getAttribute(name).trim().lowercase()
        return value == "true" || value == "1"
    }

    private fun parseXmlBounds(raw: String?): Bounds? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        val values = Regex("-?\\d+(?:\\.\\d+)?")
            .findAll(text)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (values.size < 4) return null
        return Bounds(
            left = values[0],
            top = values[1],
            right = values[2],
            bottom = values[3],
            raw = text,
        )
    }

    private fun containsLoose(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        return normalizeText(haystack).contains(normalizeText(needle))
    }

    private fun normalizeText(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

}
