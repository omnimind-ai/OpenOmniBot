package cn.com.omnimind.bot.runlog

import android.app.Instrumentation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.io.FileInputStream
import kotlinx.coroutines.delay

class UiAutomationOmniflowActionBackend(
    private val instrumentation: Instrumentation,
    private val fixturePackageName: String,
) : OmniflowActionBackend {
    private val automation = instrumentation.uiAutomation

    override fun isReady(): Boolean = true

    override suspend fun click(x: Float, y: Float) {
        shell("input tap ${x.toInt()} ${y.toInt()}")
        settle()
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
        val ix = x.toInt()
        val iy = y.toInt()
        shell("input swipe $ix $iy $ix $iy ${durationMs.coerceAtLeast(1L)}")
        settle()
    }

    override suspend fun scroll(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
    ) {
        val end = when (direction) {
            ScrollDirection.UP -> x to (y - distance).coerceAtLeast(0f)
            ScrollDirection.DOWN -> x to (y + distance)
            ScrollDirection.LEFT -> (x - distance).coerceAtLeast(0f) to y
            ScrollDirection.RIGHT -> (x + distance) to y
        }
        shell(
            "input swipe ${x.toInt()} ${y.toInt()} " +
                "${end.first.toInt()} ${end.second.toInt()} ${durationMs.coerceAtLeast(1L)}"
        )
        settle()
    }

    override suspend fun inputTextToFocusedNode(text: String) {
        shell("input text ${text.replace(" ", "%s")}")
        settle()
    }

    override suspend fun launchApplication(packageName: String) {
        if (packageName == fixturePackageName) {
            shell(
                "am start -W -S -n " +
                    "$fixturePackageName/cn.com.omnimind.bot.runlog.OobOmniFlowFixtureActivity"
            )
        } else {
            shell("monkey -p $packageName 1")
        }
        settle()
    }

    override suspend fun pressHotKey(key: String) {
        val keyCode = when (key.trim().uppercase()) {
            "BACK" -> "KEYCODE_BACK"
            "HOME" -> "KEYCODE_HOME"
            "ENTER" -> "KEYCODE_ENTER"
            else -> key.trim().ifBlank { "KEYCODE_BACK" }
        }
        shell("input keyevent $keyCode")
        settle()
    }

    override fun currentXml(): String? {
        waitForIdle()
        val root = automation.rootInActiveWindow ?: return null
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val builder = StringBuilder()
        builder.append("<hierarchy bounds=\"")
        builder.append(boundsString(rootBounds))
        builder.append("\">")
        appendNode(builder, root, 0)
        builder.append("</hierarchy>")
        return builder.toString()
    }

    override fun currentPackageName(): String? {
        waitForIdle()
        return automation.rootInActiveWindow?.packageName?.toString()
    }

    override fun currentActivityName(): String = "OobOmniFlowFixtureActivity"

    private suspend fun settle() {
        delay(500)
        waitForIdle()
    }

    private fun waitForIdle() {
        runCatching { automation.waitForIdle(100, 2_000) }
    }

    private fun shell(command: String): String {
        val fd = automation.executeShellCommand(command)
        return fd.drainText()
    }

    private fun appendNode(
        builder: StringBuilder,
        node: AccessibilityNodeInfo,
        index: Int,
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        builder.append("<node")
        attr(builder, "index", index.toString())
        attr(builder, "package", node.packageName?.toString().orEmpty())
        attr(builder, "class", node.className?.toString().orEmpty())
        attr(builder, "text", node.text?.toString().orEmpty())
        attr(builder, "content-desc", node.contentDescription?.toString().orEmpty())
        attr(builder, "resource-id", node.viewIdResourceName.orEmpty())
        attr(builder, "clickable", node.isClickable.toString())
        attr(builder, "long-clickable", node.isLongClickable.toString())
        attr(builder, "scrollable", node.isScrollable.toString())
        attr(builder, "checkable", node.isCheckable.toString())
        attr(builder, "enabled", node.isEnabled.toString())
        attr(builder, "visible-to-user", node.isVisibleToUser.toString())
        attr(builder, "bounds", boundsString(bounds))
        if (node.childCount == 0) {
            builder.append(" />")
            return
        }
        builder.append(">")
        for (childIndex in 0 until node.childCount) {
            val child = node.getChild(childIndex) ?: continue
            appendNode(builder, child, childIndex)
            child.recycle()
        }
        builder.append("</node>")
    }

    private fun attr(builder: StringBuilder, name: String, value: String) {
        builder.append(' ')
        builder.append(name)
        builder.append("=\"")
        builder.append(escape(value))
        builder.append('"')
    }

    private fun boundsString(bounds: Rect): String =
        "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    private fun ParcelFileDescriptor.drainText(): String {
        return try {
            FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            runCatching { close() }
        }
    }
}
