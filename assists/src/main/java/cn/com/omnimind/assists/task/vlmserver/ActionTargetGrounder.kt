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
        val descendantTextParts: List<String>,
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

        val directSemanticParts: List<String>
            get() = listOf(text, contentDesc, hintText)
                .filter { it.isNotBlank() }

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

            is InputTextAction -> groundPointAction(
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
        exactKeyTarget(
            targetDescription = targetDescription,
            x = x,
            y = y,
            nodes = nodes,
            copyAction = copyAction
        )?.let { return it }

        if (isGenericTargetDescription(targetDescription)) {
            return Result(action = action, applied = false, reason = "generic_target_description")
        }
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
                    .thenByDescending { if (it.node.actionable) 1 else 0 }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.textScore }
                    .thenBy { it.node.bounds.area }
            )
            .toList()

        val best = candidates.firstOrNull()
            ?: return Result(action = action, applied = false, reason = "no_semantic_target")

        if (!best.contains && best.directTextScore < HIGH_DIRECT_TEXT_MATCH && best.confidence < MIN_CONFIDENCE) {
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

    private fun exactKeyTarget(
        targetDescription: String,
        x: Float,
        y: Float,
        nodes: List<Node>,
        copyAction: (Float, Float) -> UIAction,
    ): Result? {
        val key = extractKeyTarget(targetDescription) ?: return null
        val maxArea = maxPageArea(nodes)
        val candidates = nodes
            .asSequence()
            .filter { it.actionable }
            .filter { it.bounds.area >= MIN_TARGET_AREA }
            .filter { it.bounds.area <= maxArea * MAX_KEY_TARGET_AREA_RATIO }
            .filter { it.matchesExactKey(key) }
            .sortedWith(
                compareBy<Node> { it.bounds.area }
                    .thenByDescending { proximityScore(x, y, it.bounds, nodes) }
            )
            .toList()

        val target = candidates.firstOrNull() ?: return null
        val groundedX = target.bounds.centerX
        val groundedY = target.bounds.centerY
        if (hypot((groundedX - x).toDouble(), (groundedY - y).toDouble()) < MIN_NUDGE_DISTANCE) {
            return Result(
                action = copyAction(groundedX, groundedY),
                applied = false,
                reason = "exact_key_target_already_centered",
                confidence = 1.0,
                targetLabel = target.label,
                originalX = x,
                originalY = y,
                groundedX = groundedX,
                groundedY = groundedY,
            )
        }
        return Result(
            action = copyAction(groundedX, groundedY),
            applied = true,
            reason = "exact_key_target",
            confidence = 1.0,
            targetLabel = target.label,
            originalX = x,
            originalY = y,
            groundedX = groundedX,
            groundedY = groundedY,
        )
    }

    private fun buildCandidate(
        targetDescription: String,
        x: Float,
        y: Float,
        node: Node,
        nodes: List<Node>,
    ): Candidate {
        val textScore = textSimilarity(targetDescription, node.label)
        val descendantPartScore = if (node.canUseDescendantTextAsClickAnchor) {
            bestTextPartScore(targetDescription, node.descendantTextParts)
        } else {
            0.0
        }
        val directTextScore = max(
            textSimilarity(targetDescription, node.directLabel),
            max(
                bestTextPartScore(targetDescription, node.directSemanticParts),
                descendantPartScore
            )
        )
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
                hintText = firstNonBlank(element.attr("hintText"), element.attr("hint")),
                descendantText = descendantSemanticText(element),
                descendantTextParts = descendantSemanticParts(element),
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

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun Element.boolAttr(name: String): Boolean =
        attr(name).equals("true", ignoreCase = true)

    private fun descendantSemanticText(element: Element): String {
        val parts = linkedSetOf<String>()
        val descendants = element.getElementsByTagName("node")
        for (index in 0 until descendants.length) {
            val child = descendants.item(index) as? Element ?: continue
            if (child === element) continue
            listOf(child.attr("text"), child.attr("content-desc"), child.attr("hintText"), child.attr("hint"))
                .filter { it.isNotBlank() }
                .forEach { parts += it }
            if (parts.joinToString(" ").length >= MAX_DESCENDANT_LABEL_LENGTH) break
        }
        return parts.joinToString(" ").take(MAX_DESCENDANT_LABEL_LENGTH)
    }

    private fun descendantSemanticParts(element: Element): List<String> {
        val parts = linkedSetOf<String>()
        val descendants = element.getElementsByTagName("node")
        for (index in 0 until descendants.length) {
            val child = descendants.item(index) as? Element ?: continue
            if (child === element) continue
            listOf(child.attr("text"), child.attr("content-desc"), child.attr("hintText"), child.attr("hint"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { parts += it }
            if (parts.size >= MAX_DESCENDANT_PARTS) break
        }
        return parts.toList()
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
        val weightedCharScore = if (shouldUseCharacterOverlap(target, label, targetTokens, labelTokens)) {
            characterOverlap(target, label)
        } else {
            0.0
        }
        return max(max(tokenScore, weightedCharScore), semanticAliasScore(targetTokens, labelTokens))
    }

    private fun bestTextPartScore(targetDescription: String, parts: List<String>): Double =
        parts.maxOfOrNull { textSimilarity(targetDescription, it) } ?: 0.0

    private fun semanticAliasScore(targetTokens: Set<String>, labelTokens: Set<String>): Double {
        fun hasAny(tokens: Set<String>, values: Set<String>): Boolean =
            tokens.any { it in values }

        val creationIntent = hasAny(targetTokens, ADD_CREATE_TOKENS) && hasAny(labelTokens, ADD_CREATE_TOKENS)
        if (!creationIntent) return 0.0
        return when {
            hasAny(targetTokens, CONTACT_TOKENS) && hasAny(labelTokens, CONTACT_TOKENS) -> 0.94
            hasAny(targetTokens, EVENT_TOKENS) && hasAny(labelTokens, EVENT_TOKENS) -> 0.94
            else -> 0.0
        }
    }

    private fun extractKeyTarget(targetDescription: String): String? {
        val normalized = normalizeText(targetDescription)
        if (normalized == "00" || normalized.length == 1 && normalized[0].isDigit()) {
            return normalized
        }
        KEY_TARGET_REGEX.find(normalized)?.let { match ->
            return match.groupValues.getOrNull(1)?.takeIf { it == "00" || it.length == 1 && it[0].isDigit() }
        }
        return NUMBER_WORDS.entries.firstOrNull { (word, _) ->
            Regex("""\b(digit|number|key|button|press|tap|enter)\s+$word\b""").containsMatchIn(normalized)
        }?.value
    }

    private fun Node.matchesExactKey(key: String): Boolean {
        val direct = directSemanticParts.map { normalizeText(it) }
        if (direct.any { it == key }) return true
        val resource = normalizeText(resourceId.substringAfterLast('/').substringAfterLast(':'))
        return resource.endsWith("digit $key") ||
            resource.endsWith("key $key") ||
            resource == "digit $key" ||
            resource == "key $key"
    }

    private val Node.canUseDescendantTextAsClickAnchor: Boolean
        get() = enabled && (clickable || longClickable || editable || checkable)

    private fun shouldUseCharacterOverlap(
        target: String,
        label: String,
        targetTokens: Set<String>,
        labelTokens: Set<String>
    ): Boolean {
        if (targetTokens.isEmpty() || labelTokens.isEmpty()) return true
        return target.any(::isCjk) || label.any(::isCjk)
    }

    private fun isCjk(char: Char): Boolean =
        char in '\u4e00'..'\u9fff'

    private fun isGenericTargetDescription(value: String): Boolean {
        val meaningfulTokens = tokens(normalizeText(value))
            .filterNot { it in GENERIC_TARGET_TOKENS }
        return meaningfulTokens.isEmpty()
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
    private val KEY_TARGET_REGEX = Regex("""\b(?:digit|number|key|button|press|tap|enter)\s+(00|\d)\b""")
    private val NUMBER_WORDS = mapOf(
        "zero" to "0",
        "one" to "1",
        "two" to "2",
        "three" to "3",
        "four" to "4",
        "five" to "5",
        "six" to "6",
        "seven" to "7",
        "eight" to "8",
        "nine" to "9"
    )
    private val GENERIC_TARGET_TOKENS = setOf(
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
    private val ADD_CREATE_TOKENS = setOf("add", "create", "new", "plus")
    private val CONTACT_TOKENS = setOf("contact", "contacts")
    private val EVENT_TOKENS = setOf("event", "events")
    private const val MIN_TARGET_AREA = 16f
    private const val MAX_TARGET_AREA_RATIO = 0.55f
    private const val MAX_KEY_TARGET_AREA_RATIO = 0.08f
    private const val MIN_CONTAINED_TEXT_MATCH = 0.32
    private const val MIN_TEXT_MATCH = 0.42
    private const val HIGH_DIRECT_TEXT_MATCH = 0.88
    private const val MIN_PROXIMITY = 0.08
    private const val MIN_CONFIDENCE = 0.56
    private const val MIN_NUDGE_DISTANCE = 2.5
    private const val MAX_DESCENDANT_LABEL_LENGTH = 160
    private const val MAX_DESCENDANT_PARTS = 12
}
