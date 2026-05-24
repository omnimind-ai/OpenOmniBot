package cn.com.omnimind.bot.runlog

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Local, model-free PageVectorSet encoder for OOB-native UDEG recall.
 *
 * This mirrors OmniFlow's paper-facing representation at the shape level:
 * 64-d element vectors are pooled into eight page slices and normalized into a
 * 512-d page recall vector. The serialized shared payload stores hashes and
 * vectors, not raw XML/text.
 */
object OobPageVectorSet {
    const val ELEMENT_DIM = 64
    const val PAGE_DIM = ELEMENT_DIM * 8
    const val SCHEMA_VERSION = "oob.page_vector_set.v1"

    data class Rect(
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

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "left" to left,
            "top" to top,
            "right" to right,
            "bottom" to bottom,
            "width" to width,
            "height" to height,
        )
    }

    data class PageVector(
        val nodeId: String,
        val packageName: String,
        val rootBounds: Rect,
        val vector: List<Float>,
        val elementCount: Int,
        val actionableCount: Int,
        val focusTargetCount: Int,
        val displayTextCount: Int,
        val signature: String,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "schema_version" to SCHEMA_VERSION,
            "node_id" to nodeId,
            "package_name" to packageName.takeIf { it.isNotBlank() },
            "root_bounds" to rootBounds.toMap(),
            "page_vector_dim" to vector.size,
            "page_vector" to vector.map { roundFloat(it) },
            "element_count" to elementCount,
            "actionable_count" to actionableCount,
            "focus_target_count" to focusTargetCount,
            "display_text_count" to displayTextCount,
            "signature" to signature,
            "privacy" to linkedMapOf(
                "raw_xml_stored" to false,
                "raw_text_stored" to false,
                "content_encoding" to "signed_hash_projection",
            ),
        ).filterValues { it != null }
    }

    fun encode(
        xml: String,
        packageName: String = "",
    ): PageVector? {
        val root = parseXmlRoot(xml.trim()) ?: return null
        val elements = mutableListOf<NodeElement>()
        collectElements(root, parentIndex = -1, output = elements)
        if (elements.isEmpty()) return null

        val fallbackRoot = inferRootBounds(elements.map { it.bounds })
        val rootBounds = parseBounds(root.getAttribute("bounds"))?.takeIf {
            it.width > 0f && it.height > 0f
        } ?: fallbackRoot
        val xmlPackages = elements.map { it.packageName }.filter { it.isNotBlank() }.distinct()
        val suppliedPackage = packageName.trim()
        val effectivePackage = when {
            suppliedPackage.isBlank() -> xmlPackages.firstOrNull().orEmpty()
            xmlPackages.isEmpty() || suppliedPackage in xmlPackages -> suppliedPackage
            else -> xmlPackages.firstOrNull().orEmpty()
        }

        val vectors = elements.map { elementVector(it, elements, rootBounds) }
        val pooled = pageVector(elements, vectors, rootBounds, effectivePackage)
        val normalized = normalize(pooled)
        val signature = signatureFor(normalized, effectivePackage, rootBounds)
        return PageVector(
            nodeId = "udeg_node_${signature.take(16)}",
            packageName = effectivePackage,
            rootBounds = rootBounds,
            vector = normalized,
            elementCount = elements.size,
            actionableCount = elements.count { it.actionable },
            focusTargetCount = elements.count { it.focusTarget },
            displayTextCount = elements.count { it.displayText },
            signature = signature,
        )
    }

    fun cosine(a: List<Float>, b: List<Float>): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            val av = a[index].toDouble()
            val bv = b[index].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA <= 1e-12 || normB <= 1e-12) return 0f
        return (dot / sqrt(normA * normB)).toFloat().coerceIn(-1f, 1f)
    }

    fun vectorFrom(value: Any?): List<Float> {
        return when (value) {
            is List<*> -> value.mapNotNull { item ->
                when (item) {
                    is Number -> item.toFloat()
                    is String -> item.toFloatOrNull()
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    private fun pageVector(
        elements: List<NodeElement>,
        vectors: List<FloatArray>,
        rootBounds: Rect,
        packageName: String,
    ): List<Float> {
        val yRatios = elements.map { element ->
            ((element.bounds.centerY - rootBounds.top) / rootBounds.height.coerceAtLeast(1f))
                .coerceIn(0f, 1f)
        }
        val appMask = elements.map { element ->
            packageName.isBlank() || element.packageName.isBlank() || element.packageName == packageName
        }
        val topbarReference = yRatios.filterIndexed { index, _ -> appMask[index] }
            .ifEmpty { yRatios }
        val topCut = quantile(topbarReference, 0.15f)
        val footerCut = quantile(yRatios, 0.85f)

        val slices = listOf<(Int) -> Boolean>(
            { true },
            { index -> appMask[index] && yRatios[index] <= topCut },
            { index -> yRatios[index] >= footerCut },
            { index -> appMask[index] && yRatios[index] > topCut && yRatios[index] < footerCut },
            { index -> appMask[index] && elements[index].selectedLike },
            { index ->
                appMask[index] && yRatios[index] > topCut && yRatios[index] < footerCut &&
                    elements[index].actionable
            },
            { index ->
                appMask[index] && yRatios[index] > topCut && yRatios[index] < footerCut &&
                    elements[index].focusTarget
            },
            { index ->
                appMask[index] && yRatios[index] > topCut && yRatios[index] < footerCut &&
                    elements[index].displayText
            },
        )

        val output = mutableListOf<Float>()
        slices.forEach { predicate ->
            output += pool(vectors, elements.indices.filter(predicate))
        }
        return output
    }

    private fun pool(vectors: List<FloatArray>, indices: List<Int>): List<Float> {
        if (indices.isEmpty()) return List(ELEMENT_DIM) { 0f }
        val values = FloatArray(ELEMENT_DIM)
        indices.forEach { index ->
            val vector = vectors[index]
            for (i in 0 until ELEMENT_DIM) {
                values[i] += vector[i]
            }
        }
        for (i in 0 until ELEMENT_DIM) {
            values[i] /= indices.size.toFloat()
        }
        return values.toList()
    }

    private fun elementVector(
        element: NodeElement,
        all: List<NodeElement>,
        rootBounds: Rect,
    ): FloatArray {
        val vector = FloatArray(ELEMENT_DIM)
        var offset = 0
        offset = signedHashInto(
            vector = vector,
            offset = offset,
            dim = 16,
            text = normalizeForHash(firstNonBlank(element.text, element.contentDesc, element.hintText)),
        )

        val hasText = firstNonBlank(element.text, element.contentDesc, element.hintText).isNotBlank()
        val iconLike = element.classSuffix.contains("image", ignoreCase = true)
        vector[offset + when {
            hasText && iconLike -> 3
            hasText -> 0
            iconLike -> 1
            else -> 2
        }] = 1f
        offset += 4

        vector[offset] = if (element.clickable || element.longClickable) 1f else 0f
        vector[offset + 1] = if (element.editable) 1f else 0f
        vector[offset + 2] = if (element.scrollable) 1f else 0f
        vector[offset + 3] = if (element.checkable) 1f else 0f
        offset += 4

        val areaRatio = element.bounds.area / rootBounds.area.coerceAtLeast(1f)
        vector[offset + when {
            areaRatio < 0.005f -> 0
            areaRatio < 0.02f -> 1
            areaRatio < 0.08f -> 2
            else -> 3
        }] = 1f
        offset += 4

        vector[offset] = if (element.selectedLike) 1f else 0f
        vector[offset + 1] = if (!element.enabled) 1f else 0f
        vector[offset + 2] = if (element.focused) 1f else 0f
        vector[offset + 3] = if (element.actionable && areaRatio >= 0.02f) 1f else 0f
        offset += 4

        offset = signedHashInto(vector, offset, 8, element.classSuffix.lowercase(Locale.US))
        vector[offset] = if (element.clickable) 1f else 0f
        vector[offset + 1] = if (element.longClickable) 1f else 0f
        vector[offset + 2] = if (element.focusable) 1f else 0f
        vector[offset + 3] = if (element.editable) 1f else 0f
        vector[offset + 4] = if (element.scrollable) 1f else 0f
        vector[offset + 5] = if (element.checkable) 1f else 0f
        vector[offset + 6] = if (element.enabled) 1f else 0f
        vector[offset + 7] = if (element.selectedLike) 1f else 0f
        offset += 8

        offset = signedHashInto(vector, offset, 12, subtreeSignature(element, all))
        vector[offset] = if (element.children.isEmpty()) 1f else 0f
        vector[offset + 1] = if (element.parentIndex >= 0 && all.any {
                it.parentIndex == element.parentIndex && it.index != element.index
            }
        ) 1f else 0f
        offset += 2

        val tail = element.resourceId.substringAfterLast('/').lowercase(Locale.US)
        vector[offset] = if (ACTION_ID_PREFIXES.any { tail.startsWith(it) }) 1f else 0f
        vector[offset + 1] = if (INPUT_ID_PREFIXES.any { tail.startsWith(it) }) 1f else 0f
        return vector
    }

    private fun subtreeSignature(element: NodeElement, all: List<NodeElement>): String {
        val childClasses = element.children.take(8).mapNotNull { childIndex ->
            all.getOrNull(childIndex)?.classSuffix
        }
        return listOf(element.classSuffix, childClasses.joinToString(",")).joinToString("|")
    }

    private fun signedHashInto(
        vector: FloatArray,
        offset: Int,
        dim: Int,
        text: String,
    ): Int {
        if (text.isBlank()) return offset + dim
        val digest = sha256(text)
        for (i in digest.indices step 2) {
            val bucket = (digest[i].toInt() and 0xff) % dim
            val sign = if ((digest.getOrNull(i + 1)?.toInt() ?: 0) and 1 == 0) 1f else -1f
            vector[offset + bucket] += sign
        }
        return offset + dim
    }

    private fun normalize(vector: List<Float>): List<Float> {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm <= 1e-6f) return vector
        return vector.map { it / norm }
    }

    private fun quantile(values: List<Float>, q: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * q.coerceIn(0f, 1f)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun signatureFor(vector: List<Float>, packageName: String, rootBounds: Rect): String {
        val rounded = vector.joinToString(",") { "%.3f".format(Locale.US, it) }
        return shortHash("$packageName|${rootBounds.width.toInt()}x${rootBounds.height.toInt()}|$rounded")
    }

    private fun collectElements(
        element: Element,
        parentIndex: Int,
        output: MutableList<NodeElement>,
    ) {
        val bounds = parseBounds(element.getAttribute("bounds"))
        val currentIndex = if (bounds != null && bounds.width > 0f && bounds.height > 0f) {
            val index = output.size
            output += NodeElement.from(index, parentIndex, element, bounds)
            index
        } else {
            parentIndex
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val beforeSize = output.size
            collectElements(child, currentIndex, output)
            if (currentIndex >= 0 && output.size > beforeSize) {
                output[currentIndex].children += beforeSize until output.size
            }
        }
    }

    private fun parseXmlRoot(xml: String): Element? {
        if (xml.isBlank()) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
                .documentElement
        }.getOrNull()
    }

    private fun parseBounds(value: String?): Rect? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(text) ?: return null
        val nums = match.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
        if (nums.size != 4) return null
        return Rect(nums[0], nums[1], nums[2], nums[3])
    }

    private fun inferRootBounds(bounds: List<Rect>): Rect {
        if (bounds.isEmpty()) return Rect(0f, 0f, 1080f, 1920f)
        return Rect(
            left = bounds.minOf { it.left },
            top = bounds.minOf { it.top },
            right = bounds.maxOf { it.right },
            bottom = bounds.maxOf { it.bottom },
        )
    }

    private fun Element.stringAttr(name: String): String = getAttribute(name)?.trim().orEmpty()

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean {
        val value = getAttribute(name)?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isEmpty()) return defaultValue
        return value == "true" || value == "1"
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun normalizeForHash(text: String): String =
        text.lowercase(Locale.US)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .take(80)

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun shortHash(text: String): String =
        sha256(text).joinToString("") { "%02x".format(it) }

    private fun roundFloat(value: Float): Float =
        ((value * 1_000_000f).toInt() / 1_000_000f)

    private data class NodeElement(
        val index: Int,
        val parentIndex: Int,
        val bounds: Rect,
        val className: String,
        val classSuffix: String,
        val resourceId: String,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val packageName: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val selected: Boolean,
        val activated: Boolean,
        val checked: Boolean,
        val focused: Boolean,
        val children: MutableList<Int> = mutableListOf(),
    ) {
        val actionable: Boolean get() =
            visible && enabled && (clickable || longClickable || scrollable || checkable)
        val focusTarget: Boolean get() = visible && enabled && (focusable || editable || focused)
        val selectedLike: Boolean get() = selected || activated || checked
        val displayText: Boolean get() =
            visible && firstNonBlank(text, contentDesc, hintText).isNotBlank() &&
                children.isEmpty() && !actionable && !focusTarget

        companion object {
            fun from(index: Int, parentIndex: Int, element: Element, bounds: Rect): NodeElement {
                val className = element.stringAttr("class").ifBlank {
                    element.stringAttr("class-name")
                }
                return NodeElement(
                    index = index,
                    parentIndex = parentIndex,
                    bounds = bounds,
                    className = className,
                    classSuffix = className.substringAfterLast('.'),
                    resourceId = element.stringAttr("resource-id"),
                    text = element.stringAttr("text"),
                    contentDesc = element.stringAttr("content-desc"),
                    hintText = element.stringAttr("hint-text"),
                    packageName = element.stringAttr("package"),
                    clickable = element.boolAttr("clickable"),
                    longClickable = element.boolAttr("long-clickable"),
                    focusable = element.boolAttr("focusable"),
                    editable = element.boolAttr("editable") ||
                        className.endsWith("EditText", ignoreCase = true),
                    scrollable = element.boolAttr("scrollable"),
                    checkable = element.boolAttr("checkable"),
                    enabled = element.boolAttr("enabled", defaultValue = true),
                    visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                        element.boolAttr("displayed", defaultValue = true),
                    selected = element.boolAttr("selected"),
                    activated = element.boolAttr("activated"),
                    checked = element.boolAttr("checked"),
                    focused = element.boolAttr("focused"),
                )
            }
        }
    }

    private val ACTION_ID_PREFIXES = listOf("btn_", "button_", "ib_", "fab_", "action_")
    private val INPUT_ID_PREFIXES = listOf("et_", "edit_", "input_", "search_", "txt_")
}
