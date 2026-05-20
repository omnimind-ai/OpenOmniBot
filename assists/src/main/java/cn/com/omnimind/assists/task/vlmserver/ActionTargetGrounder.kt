package cn.com.omnimind.assists.task.vlmserver

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight semantic grounding layer for live GUI actions.
 *
 * VLM coordinates are still the source of intent. This layer only nudges
 * click-like actions to the center of a matching Accessibility node when the
 * current XML tree provides a high-confidence target.
 */
object ActionTargetGrounder {
    data class Result(
        val action: UIAction,
        val applied: Boolean,
        val reason: String,
        val confidence: Double = 0.0,
        val targetLabel: String = "",
        val originalX: Float? = null,
        val originalY: Float? = null,
        val groundedX: Float? = null,
        val groundedY: Float? = null,
    )

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
    }

    private data class Node(
        val bounds: Rect,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val descendantText: String,
        val resourceId: String,
        val className: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val checkable: Boolean,
    ) {
        val actionable: Boolean
            get() = enabled && (clickable || longClickable || focusable || editable || checkable)

        val label: String
            get() = listOf(text, contentDesc, hintText, descendantText, resourceTail(), classSuffix())
                .filter { it.isNotBlank() }
                .joinToString(" ")

        val directLabel: String
            get() = listOf(text, contentDesc, hintText, resourceTail())
                .filter { it.isNotBlank() }
                .joinToString(" ")

        private fun resourceTail(): String =
            resourceId.substringAfterLast('/').substringAfterLast(':')

        private fun classSuffix(): String =
            className.substringAfterLast('.')
    }

    fun ground(action: UIAction, currentXml: String?): Result {
        if (currentXml.isNullOrBlank()) {
            return Result(action = action, applied = false, reason = "missing_current_xml")
        }
        val page = parseNodes(currentXml)
        if (page.isEmpty()) {
            return Result(action = action, applied = false, reason = "empty_current_xml")
        }
        return when (action) {
            is ClickAction -> groundPointAction(
                action = action,
                x = action.x,
                y = action.y,
                targetDescription = action.targetDescription,
                nodes = page,
            ) { groundedX, groundedY -> action.copy(x = groundedX, y = groundedY) }

            is LongPressAction -> groundPointAction(
                action = action,
                x = action.x,
                y = action.y,
                targetDescription = action.targetDescription,
                nodes = page,
            ) { groundedX, groundedY -> action.copy(x = groundedX, y = groundedY) }

            else -> Result(action = action, applied = false, reason = "unsupported_action")
        }
    }

    private fun groundPointAction(
        action: UIAction,
        x: Float,
        y: Float,
        targetDescription: String,
        nodes: List<Node>,
        copyAction: (Float, Float) -> UIAction,
    ): Result {
        val maxArea = maxPageArea(nodes)
        val semanticDirectCandidates = nodes
            .asSequence()
            .filter { it.enabled }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.bounds.area <= maxArea * MAX_TARGET_AREA_RATIO }
            .map { node ->
                buildCandidate(
                    targetDescription = targetDescription,
                    x = x,
                    y = y,
                    node = node,
                    nodes = nodes,
                )
            }
            .filter { it.directTextScore >= MIN_TEXT_MATCH }
            .toList()

        val candidateSource = semanticDirectCandidates.ifEmpty {
            nodes
                .asSequence()
                .filter { it.actionable }
                .filter { it.bounds.area >= MIN_TARGET_AREA }
                .filter { it.bounds.area <= maxArea * MAX_TARGET_AREA_RATIO }
                .map { node ->
                    buildCandidate(
                        targetDescription = targetDescription,
                        x = x,
                        y = y,
                        node = node,
                        nodes = nodes,
                    )
                }
                .toList()
        }

        val directMode = semanticDirectCandidates.isNotEmpty()
        val candidates = candidateSource
            .filter { candidate ->
                (directMode && candidate.directTextScore >= HIGH_DIRECT_TEXT_MATCH) ||
                    (directMode && candidate.directTextScore >= MIN_TEXT_MATCH && candidate.proximity >= MIN_PROXIMITY) ||
                    (candidate.contains && candidate.textScore >= MIN_CONTAINED_TEXT_MATCH) ||
                    (candidate.textScore >= MIN_TEXT_MATCH && candidate.proximity >= MIN_PROXIMITY)
            }
            .sortedWith(
                compareByDescending<Candidate> { it.directTextScore }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.textScore }
                    .thenBy { it.node.bounds.area }
            )
            .toList()

        val best = candidates.firstOrNull()
            ?: return Result(action = action, applied = false, reason = "no_semantic_target")

        if (!best.contains && best.confidence < MIN_CONFIDENCE) {
            return Result(
                action = action,
                applied = false,
                reason = "low_confidence",
                confidence = best.confidence,
                targetLabel = best.node.label,
            )
        }

        val groundedX = best.node.bounds.centerX
        val groundedY = best.node.bounds.centerY
        if (hypot((groundedX - x).toDouble(), (groundedY - y).toDouble()) < MIN_NUDGE_DISTANCE) {
            return Result(
                action = action,
                applied = false,
                reason = "already_centered",
                confidence = best.confidence,
                targetLabel = best.node.label,
                originalX = x,
                originalY = y,
                groundedX = groundedX,
                groundedY = groundedY,
            )
        }

        return Result(
            action = copyAction(groundedX, groundedY),
            applied = true,
            reason = if (best.contains) "inside_target_centered" else "semantic_target_centered",
            confidence = best.confidence,
            targetLabel = best.node.label,
            originalX = x,
            originalY = y,
            groundedX = groundedX,
            groundedY = groundedY,
        )
    }

    private data class Candidate(
        val node: Node,
        val confidence: Double,
        val textScore: Double,
        val directTextScore: Double,
        val proximity: Double,
        val contains: Boolean,
    )

    private fun buildCandidate(
        targetDescription: String,
        x: Float,
        y: Float,
        node: Node,
        nodes: List<Node>,
    ): Candidate {
        val textScore = textSimilarity(targetDescription, node.label)
        val directTextScore = textSimilarity(targetDescription, node.directLabel)
        val proximity = proximityScore(x, y, node.bounds, nodes)
        val contains = node.bounds.contains(x, y)
        val confidence = (textScore * 0.56) +
            (directTextScore * 0.28) +
            (proximity * 0.16) +
            if (contains) 0.05 else 0.0
        return Candidate(
            node = node,
            confidence = confidence.coerceAtMost(1.0),
            textScore = textScore,
            directTextScore = directTextScore,
            proximity = proximity,
            contains = contains,
        )
    }

    private fun parseNodes(xml: String): List<Node> {
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
        val result = ArrayList<Node>(nodeList.length)
        for (index in 0 until nodeList.length) {
            val element = nodeList.item(index) as? Element ?: continue
            val bounds = parseBounds(element.attr("bounds")) ?: continue
            if (bounds.area <= 0f) continue
            result += Node(
                bounds = bounds,
                text = element.attr("text"),
                contentDesc = element.attr("content-desc"),
                hintText = element.attr("hintText"),
                descendantText = descendantSemanticText(element),
                resourceId = element.attr("resource-id"),
                className = element.attr("class"),
                clickable = element.boolAttr("clickable"),
                longClickable = element.boolAttr("long-clickable"),
                focusable = element.boolAttr("focusable"),
                editable = element.boolAttr("editable"),
                scrollable = element.boolAttr("scrollable"),
                enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled"),
                checkable = element.boolAttr("checkable"),
            )
        }
        return result
    }

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun Element.boolAttr(name: String): Boolean =
        attr(name).equals("true", ignoreCase = true)

    private fun descendantSemanticText(element: Element): String {
        val parts = linkedSetOf<String>()
        val descendants = element.getElementsByTagName("node")
        for (index in 0 until descendants.length) {
            val child = descendants.item(index) as? Element ?: continue
            if (child === element) continue
            listOf(child.attr("text"), child.attr("content-desc"), child.attr("hintText"))
                .filter { it.isNotBlank() }
                .forEach { parts += it }
            if (parts.joinToString(" ").length >= MAX_DESCENDANT_LABEL_LENGTH) break
        }
        return parts.joinToString(" ").take(MAX_DESCENDANT_LABEL_LENGTH)
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
            bottom = max(top, bottom),
        )
    }

    private fun textSimilarity(targetDescription: String, nodeLabel: String): Double {
        val target = normalizeText(targetDescription)
        val label = normalizeText(nodeLabel)
        if (target.isEmpty() || label.isEmpty()) return 0.0
        if (target == label || target.contains(label) || label.contains(target)) return 1.0

        val targetTokens = tokens(target)
        val labelTokens = tokens(label)
        val tokenScore = if (targetTokens.isNotEmpty() && labelTokens.isNotEmpty()) {
            val overlap = targetTokens.intersect(labelTokens).size.toDouble()
            overlap / min(targetTokens.size, labelTokens.size).toDouble()
        } else {
            0.0
        }
        val charScore = characterOverlap(target, label)
        val weightedCharScore = if (targetTokens.isNotEmpty() && labelTokens.isNotEmpty()) {
            charScore * 0.65
        } else {
            charScore
        }
        return max(tokenScore, weightedCharScore)
    }

    private fun normalizeText(value: String): String =
        value.lowercase()
            .replace(Regex("""[\s_\-:/\\|]+"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\u4e00-\u9fff ]"""), "")
            .trim()

    private fun tokens(value: String): Set<String> =
        value.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    private fun characterOverlap(a: String, b: String): Double {
        val charsA = a.filter { !it.isWhitespace() }.toSet()
        val charsB = b.filter { !it.isWhitespace() }.toSet()
        if (charsA.isEmpty() || charsB.isEmpty()) return 0.0
        return charsA.intersect(charsB).size.toDouble() / min(charsA.size, charsB.size).toDouble()
    }

    private fun proximityScore(x: Float, y: Float, rect: Rect, nodes: List<Node>): Double {
        if (rect.contains(x, y)) return 1.0
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        val diagonal = pageDiagonal(nodes).coerceAtLeast(1.0)
        return (1.0 - (hypot(dx.toDouble(), dy.toDouble()) / (diagonal * 0.18))).coerceIn(0.0, 1.0)
    }

    private fun pageDiagonal(nodes: List<Node>): Double {
        val right = nodes.maxOfOrNull { it.bounds.right } ?: 1f
        val bottom = nodes.maxOfOrNull { it.bounds.bottom } ?: 1f
        return hypot(right.toDouble(), bottom.toDouble())
    }

    private fun maxPageArea(nodes: List<Node>): Float {
        val right = nodes.maxOfOrNull { it.bounds.right } ?: 1f
        val bottom = nodes.maxOfOrNull { it.bounds.bottom } ?: 1f
        return right * bottom
    }

    private val BOUNDS_REGEX = Regex("""\[(\-?\d+(?:\.\d+)?),(\-?\d+(?:\.\d+)?)\]\[(\-?\d+(?:\.\d+)?),(\-?\d+(?:\.\d+)?)\]""")
    private const val MIN_TARGET_AREA = 16f
    private const val MAX_TARGET_AREA_RATIO = 0.55f
    private const val MIN_CONTAINED_TEXT_MATCH = 0.32
    private const val MIN_TEXT_MATCH = 0.42
    private const val HIGH_DIRECT_TEXT_MATCH = 0.88
    private const val MIN_PROXIMITY = 0.08
    private const val MIN_CONFIDENCE = 0.56
    private const val MIN_NUDGE_DISTANCE = 2.5
    private const val MAX_DESCENDANT_LABEL_LENGTH = 160
}
