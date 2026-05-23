package cn.com.omnimind.assists.task.vlmserver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds OOB indexed page evidence from live Accessibility XML.
 *
 * The VLM still receives the screenshot as the primary visual signal. This
 * context gives it stable element labels, flags, 0-1000 normalized centers, and
 * an optional marked screenshot so AndroidWorld-style tasks do not depend on raw
 * coordinate guessing.
 */
object VLMIndexedPageContext {
    fun enrich(
        context: UIContext,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): UIContext {
        val section = render(
            currentXml = currentXml,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
        if (section.isBlank()) return context

        val pageSummary = listOf(
            context.currentPageSummary.trim(),
            section
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_CONTEXT_CHARS)

        return context.copy(currentPageSummary = pageSummary)
    }

    fun render(
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): String {
        val snapshot = buildIndexedSnapshot(currentXml, displayWidth, displayHeight) ?: return ""
        val screen = snapshot.screen
        val candidates = snapshot.candidates
        val focusedEditable = snapshot.focusedEditable
        val formFields = snapshot.formFields
        val scrollables = snapshot.scrollables

        if (candidates.isEmpty() && scrollables.isEmpty() && focusedEditable == null && formFields.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("OOB indexed page evidence (live Accessibility XML; coordinates are 0-1000 normalized and match the marked screenshot indexes):")
            candidates.forEachIndexed { index, node ->
                append("#").append(index)
                    .append(" center=(").append(norm(node.bounds.centerX, screen.left, screen.width))
                    .append(",").append(norm(node.bounds.centerY, screen.top, screen.height)).append(")")
                    .append(" bounds=(").append(norm(node.bounds.left, screen.left, screen.width))
                    .append(",").append(norm(node.bounds.top, screen.top, screen.height))
                    .append(")-(").append(norm(node.bounds.right, screen.left, screen.width))
                    .append(",").append(norm(node.bounds.bottom, screen.top, screen.height)).append(")")
                    .append(" flags=").append(node.flags())
                    .append(" role=").append(node.role)
                    .append(" label=\"").append(node.displayLabel.take(MAX_LABEL_CHARS)).append("\"")
                    .appendLine()
            }
            if (focusedEditable != null) {
                append("Focused editable: center=(")
                    .append(norm(focusedEditable.bounds.centerX, screen.left, screen.width))
                    .append(",")
                    .append(norm(focusedEditable.bounds.centerY, screen.top, screen.height))
                    .append(") label=\"")
                    .append(focusedEditable.displayLabel.take(MAX_LABEL_CHARS))
                    .appendLine("\"")
            }
            if (formFields.isNotEmpty()) {
                appendLine("Form anchors:")
                formFields.forEachIndexed { index, node ->
                    append("F").append(index)
                        .append(" center=(").append(norm(node.bounds.centerX, screen.left, screen.width))
                        .append(",").append(norm(node.bounds.centerY, screen.top, screen.height)).append(")")
                        .append(" role=").append(node.formRole())
                        .append(" label=\"").append(node.formLabel.take(MAX_LABEL_CHARS)).append("\"")
                    node.formValueHint.takeIf { it.isNotBlank() }?.let { valueHint ->
                        append(" value_hint=\"").append(valueHint.take(MAX_LABEL_CHARS)).append("\"")
                    }
                    appendLine()
                }
            }
            if (scrollables.isNotEmpty()) {
                appendLine("Scrollable regions:")
                scrollables.forEachIndexed { index, node ->
                    val x = norm(node.bounds.centerX, screen.left, screen.width)
                    val y1 = norm(node.bounds.bottom - node.bounds.height * 0.14f, screen.top, screen.height)
                    val y2 = norm(node.bounds.top + node.bounds.height * 0.22f, screen.top, screen.height)
                    append("S").append(index)
                        .append(" vertical_down=(").append(x).append(",").append(y1)
                        .append(")->(").append(x).append(",").append(y2).append(")")
                        .append(" label=\"").append(node.displayLabel.take(MAX_LABEL_CHARS)).append("\"")
                        .appendLine()
                }
            }
        }.trim().take(MAX_SECTION_CHARS)
    }

    fun renderMarkedScreenshot(
        screenshotBase64: String?,
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): String? {
        if (screenshotBase64.isNullOrBlank()) return null
        val snapshot = buildIndexedSnapshot(currentXml, displayWidth, displayHeight) ?: return null
        if (snapshot.candidates.isEmpty()) return null
        val bitmap = decodeBitmap(screenshotBase64)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(bitmap)
        val widthScale = bitmap.width.toFloat() / snapshot.screen.width.coerceAtLeast(1f)
        val heightScale = bitmap.height.toFloat() / snapshot.screen.height.coerceAtLeast(1f)
        val strokeWidth = max(2f, min(bitmap.width, bitmap.height) / 260f)
        val textSize = max(18f, min(bitmap.width, bitmap.height) / 24f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 180, 0)
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            this.textSize = textSize
            isFakeBoldText = true
        }

        snapshot.candidates.forEachIndexed { index, node ->
            val rect = node.bounds.toBitmapRect(snapshot.screen, widthScale, heightScale)
            if (rect.width() <= 1f || rect.height() <= 1f) return@forEachIndexed
            canvas.drawRect(rect, boxPaint)
            val label = index.toString()
            val padding = max(4f, textSize * 0.16f)
            val labelWidth = labelTextPaint.measureText(label) + padding * 2
            val labelHeight = textSize + padding * 2
            val labelLeft = rect.left.coerceIn(0f, (bitmap.width - labelWidth).coerceAtLeast(0f))
            val labelTop = rect.top.coerceIn(0f, (bitmap.height - labelHeight).coerceAtLeast(0f))
            canvas.drawRect(
                labelLeft,
                labelTop,
                labelLeft + labelWidth,
                labelTop + labelHeight,
                labelBgPaint
            )
            canvas.drawText(label, labelLeft + padding, labelTop + textSize + padding * 0.25f, labelTextPaint)
        }
        return encodeJpegDataUri(bitmap)
    }

    private fun buildIndexedSnapshot(
        currentXml: String?,
        displayWidth: Int,
        displayHeight: Int
    ): IndexedSnapshot? {
        val page = parsePage(currentXml) ?: return null
        val screen = page.screenBounds(displayWidth, displayHeight)
        val screenArea = screen.area.coerceAtLeast(1f)

        val candidates = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled }
            .filter { it.bounds.area >= MIN_ELEMENT_AREA }
            .filter { !isOverlayLabel(it.label) }
            .filter { it.actionable || it.displayLabel.isNotBlank() }
            .filter { it.bounds.area <= screenArea * MAX_ELEMENT_AREA_RATIO }
            .distinctBy { it.dedupKey() }
            .sortedWith(
                compareByDescending<PageNode> { if (it.actionable) 1 else 0 }
                    .thenByDescending { if (it.editable) 1 else 0 }
                    .thenByDescending { if (it.checkable) 1 else 0 }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
                    .thenBy { it.bounds.area }
            )
            .take(MAX_ELEMENTS)
            .toList()

        val scrollables = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.scrollable }
            .filter { it.bounds.area >= MIN_SCROLLABLE_AREA }
            .filter { !isOverlayLabel(it.label) }
            .distinctBy { it.dedupKey() }
            .sortedByDescending { it.bounds.area }
            .take(MAX_SCROLLABLES)
            .toList()

        val formFields = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled }
            .filter { it.bounds.area >= MIN_FORM_FIELD_AREA }
            .filter { it.bounds.area <= screenArea * MAX_FORM_FIELD_AREA_RATIO }
            .filter { !isOverlayLabel(it.displayLabel) }
            .filter { it.isFormFieldLike }
            .distinctBy { it.dedupKey() }
            .sortedWith(
                compareByDescending<PageNode> { if (it.focused && it.editable) 1 else 0 }
                    .thenByDescending { if (it.editable) 1 else 0 }
                    .thenByDescending { if (it.isSelectionRowLike) 1 else 0 }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
                    .thenBy { it.bounds.area }
            )
            .take(MAX_FORM_FIELDS)
            .toList()

        val focusedEditable = page.nodes.firstOrNull {
            it.visible && it.enabled && it.editable && it.focused && !isOverlayLabel(it.label)
        }
        return IndexedSnapshot(
            screen = screen,
            candidates = candidates,
            scrollables = scrollables,
            focusedEditable = focusedEditable,
            formFields = formFields
        )
    }

    private fun parsePage(xml: String?): PageModel? {
        if (xml.isNullOrBlank()) return null
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null

        val nodeList = document.getElementsByTagName("node")
        val nodes = ArrayList<PageNode>(nodeList.length)
        for (index in 0 until nodeList.length) {
            val element = nodeList.item(index) as? Element ?: continue
            val bounds = parseBounds(element.attr("bounds")) ?: continue
            if (bounds.area <= 0f) continue
            nodes += PageNode(
                bounds = bounds,
                text = element.attr("text"),
                contentDesc = element.attr("content-desc"),
                hintText = firstNonBlank(element.attr("hintText"), element.attr("hint-text"), element.attr("hint")),
                resourceId = element.attr("resource-id"),
                className = firstNonBlank(element.attr("class"), element.attr("class-name")),
                packageName = element.attr("package"),
                descendantText = descendantSemanticText(element),
                clickable = element.boolAttr("clickable"),
                longClickable = element.boolAttr("long-clickable"),
                focusable = element.boolAttr("focusable"),
                editable = element.boolAttr("editable"),
                focused = element.boolAttr("focused"),
                scrollable = element.boolAttr("scrollable"),
                checkable = element.boolAttr("checkable"),
                checked = element.boolAttr("checked"),
                selected = element.boolAttr("selected"),
                enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled"),
                visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                    element.boolAttr("displayed", defaultValue = true)
            )
        }
        return PageModel(nodes)
    }

    private fun PageModel.screenBounds(displayWidth: Int, displayHeight: Int): Rect {
        val maxRight = nodes.maxOfOrNull { it.bounds.right } ?: 1f
        val maxBottom = nodes.maxOfOrNull { it.bounds.bottom } ?: 1f
        val width = displayWidth.takeIf { it > 0 }?.toFloat() ?: maxRight
        val height = displayHeight.takeIf { it > 0 }?.toFloat() ?: maxBottom
        return Rect(0f, 0f, max(width, maxRight), max(height, maxBottom))
    }

    private fun descendantSemanticText(element: Element): String {
        val parts = linkedSetOf<String>()
        val descendants = element.getElementsByTagName("node")
        for (index in 0 until descendants.length) {
            val child = descendants.item(index) as? Element ?: continue
            if (child === element) continue
            listOf(
                child.attr("text"),
                child.attr("content-desc"),
                child.attr("hintText"),
                child.attr("hint-text"),
                child.attr("hint")
            )
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { parts += it }
            if (parts.size >= MAX_DESCENDANT_PARTS || parts.joinToString(" ").length >= MAX_DESCENDANT_CHARS) break
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

    private fun Element.attr(name: String): String =
        if (hasAttribute(name)) getAttribute(name).trim() else ""

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean =
        if (hasAttribute(name)) attr(name).equals("true", ignoreCase = true) else defaultValue

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun norm(value: Float, origin: Float, size: Float): Int =
        (((value - origin) / size.coerceAtLeast(1f)) * 1000f)
            .roundToInt()
            .coerceIn(0, 1000)

    private fun isOverlayLabel(value: String): Boolean {
        val normalized = value.lowercase().replace(" ", "")
        return OVERLAY_LABELS.any { normalized.contains(it) }
    }

    private data class PageModel(val nodes: List<PageNode>)

    private data class IndexedSnapshot(
        val screen: Rect,
        val candidates: List<PageNode>,
        val scrollables: List<PageNode>,
        val focusedEditable: PageNode?,
        val formFields: List<PageNode>
    )

    private data class PageNode(
        val bounds: Rect,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val resourceId: String,
        val className: String,
        val packageName: String,
        val descendantText: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val selected: Boolean,
        val enabled: Boolean,
        val visible: Boolean
    ) {
        val actionable: Boolean
            get() = clickable || longClickable || focusable || editable || scrollable || checkable

        val role: String
            get() = className.substringAfterLast('.').ifBlank { "node" }

        val displayLabel: String
            get() = firstNonBlank(text, contentDesc, hintText, descendantText, resourceTail(), role)
                .replace(Regex("""\s+"""), " ")
                .trim()

        val formLabel: String
            get() = if (editable) {
                firstNonBlank(hintText, contentDesc, resourceTail(), text, role)
            } else {
                firstNonBlank(text, contentDesc, hintText, resourceTail(), role)
            }
                .replace(Regex("""\s+"""), " ")
                .trim()

        val formValueHint: String
            get() = if (editable) {
                firstNonBlank(text, contentDesc)
            } else {
                firstNonBlank(descendantText, contentDesc, text)
            }
                .replace(Regex("""\s+"""), " ")
                .trim()

        val isSelectionRowLike: Boolean
            get() {
                val normalized = formSemanticText()
                val spinnerLike = normalized.contains("spinner") ||
                    normalized.contains("dropdown") ||
                    normalized.contains("select")
                if (spinnerLike) return actionable
                if (editable) return false
                return actionable &&
                    descendantText.isNotBlank() &&
                    (
                        normalized.contains("label") ||
                            normalized.contains("type") ||
                            normalized.contains("category") ||
                            normalized.contains("account") ||
                            normalized.contains("country") ||
                            normalized.contains("language")
                        )
            }

        val isFormFieldLike: Boolean
            get() {
                val normalized = formSemanticText()
                return editable ||
                    normalized.contains("edittext") ||
                    normalized.contains("textfield") ||
                    normalized.contains("autocompletetextview") ||
                    normalized.contains("spinner") ||
                    isSelectionRowLike ||
                    (actionable && FORM_FIELD_TERMS.any { term -> normalized.contains(term) })
            }

        fun formRole(): String =
            when {
                isSelectionRowLike -> "selection_row"
                editable && focused -> "focused_editable"
                editable -> "editable"
                checkable -> "toggle"
                else -> "field"
            }

        val label: String
            get() = listOf(text, contentDesc, hintText, descendantText, resourceTail(), role)
                .filter { it.isNotBlank() }
                .joinToString(" ")

        fun flags(): String {
            val flags = buildList {
                if (clickable) add("click")
                if (longClickable) add("long")
                if (editable) add(if (focused) "edit_focused" else "edit")
                if (scrollable) add("scroll")
                if (checkable) add(if (checked) "checked" else "checkable")
                if (selected) add("selected")
                if (!enabled) add("disabled")
            }
            return if (flags.isEmpty()) "text" else flags.joinToString("|")
        }

        fun dedupKey(): String =
            listOf(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt(),
                displayLabel,
                role,
                packageName
            ).joinToString("|")

        private fun resourceTail(): String =
            resourceId.substringAfterLast('/').substringAfterLast(':')

        private fun formSemanticText(): String =
            listOf(text, contentDesc, hintText, descendantText, resourceTail(), role)
                .joinToString(" ")
                .lowercase()
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

        fun toBitmapRect(screen: Rect, widthScale: Float, heightScale: Float): RectF =
            RectF(
                (left - screen.left) * widthScale,
                (top - screen.top) * heightScale,
                (right - screen.left) * widthScale,
                (bottom - screen.top) * heightScale
            )
    }

    private fun decodeBitmap(rawImage: String): Bitmap? {
        val cleanBase64 = rawImage.substringAfter(",")
        val bytes = runCatching { Base64.decode(cleanBase64, Base64.DEFAULT) }.getOrNull() ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun encodeJpegDataUri(bitmap: Bitmap): String? {
        return runCatching {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, MARKED_SCREENSHOT_JPEG_QUALITY, output)
            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    private val BOUNDS_REGEX = Regex("""\[(\-?\d+(?:\.\d+)?),(\-?\d+(?:\.\d+)?)\]\[(\-?\d+(?:\.\d+)?),(\-?\d+(?:\.\d+)?)\]""")
    private val OVERLAY_LABELS = setOf(
        "接管",
        "继续执行",
        "已接管控制",
        "小万",
        "omnibot",
        "oob"
    )
    private const val MIN_ELEMENT_AREA = 18f
    private const val MIN_SCROLLABLE_AREA = 2_500f
    private const val MIN_FORM_FIELD_AREA = 200f
    private const val MAX_ELEMENT_AREA_RATIO = 0.72f
    private const val MAX_FORM_FIELD_AREA_RATIO = 0.60f
    private const val MAX_ELEMENTS = 24
    private const val MAX_SCROLLABLES = 4
    private const val MAX_FORM_FIELDS = 8
    private const val MAX_LABEL_CHARS = 90
    private const val MAX_DESCENDANT_PARTS = 8
    private const val MAX_DESCENDANT_CHARS = 120
    private const val MAX_SECTION_CHARS = 3_600
    private const val MAX_CONTEXT_CHARS = 5_000
    private const val MARKED_SCREENSHOT_JPEG_QUALITY = 92
    private val FORM_FIELD_TERMS = setOf(
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
        "contact",
        "type",
        "category",
        "account",
        "country",
        "language",
        "birthday",
        "date",
        "time",
        "number",
        "value"
    )
}
