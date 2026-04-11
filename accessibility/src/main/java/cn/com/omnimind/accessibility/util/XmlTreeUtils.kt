package cn.com.omnimind.accessibility.util

import android.graphics.Rect
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.action.AccessibilityNode
import cn.com.omnimind.accessibility.action.OmniScreenshotAction
import cn.com.omnimind.accessibility.action.XmlTreeNode
import cn.com.omnimind.baselib.util.OmniLog
import java.io.StringWriter

object XmlTreeUtils {
    private const val DIRECT_XML_MAX_DEPTH = 20
    private const val DIRECT_XML_MAX_VISITED_NODES = 600
    private const val DIRECT_XML_MAX_CHARS = 256 * 1024
    private const val DIRECT_XML_MAX_TEXT_ATTR_LENGTH = 256
    private const val DIRECT_XML_MAX_GENERIC_ATTR_LENGTH = 128

    private data class DirectBuildResult(
        val xml: String?,
        val nodeMap: Map<String, AccessibilityNode>?
    )

    /**
     * 直接生成 XML 字符串（优化版本，避免构建中间树结构）
     * 性能优化：
     * 1. 避免构建中间树结构
     * 2. 限制最大深度、节点数和字符串大小
     * 3. 只处理可见节点
     * 4. 非采集 nodeMap 场景下及时回收子节点
     */
    fun buildXmlDirectly(root: AccessibilityNodeInfo?): String? {
        return buildXmlDirectlyInternal(
            root = root,
            includeXml = true,
            includeNodeMap = false
        )?.xml
    }

    fun buildNodeMapDirectly(root: AccessibilityNodeInfo?): Map<String, AccessibilityNode>? {
        return buildXmlDirectlyInternal(
            root = root,
            includeXml = false,
            includeNodeMap = true
        )?.nodeMap
    }

    @Suppress("DEPRECATION")
    fun findFocusedNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()

        fun dfs(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
            if (node == null || depth > DIRECT_XML_MAX_DEPTH) return null
            if (!visitedNodes.add(node)) return null
            try {
                if ((depth == 0 || node.isVisibleToUser) && node.isFocused) {
                    return node
                }

                val childCount = node.childCount
                for (index in 0 until childCount) {
                    val child = try {
                        node.getChild(index)
                    } catch (e: Exception) {
                        OmniLog.w(
                            OmniScreenshotAction.TAG,
                            "Failed to get child at index $index while finding focused node: ${e.message}"
                        )
                        continue
                    }

                    if (child != null) {
                        val found = try {
                            dfs(child, depth + 1)
                        } finally {
                            if (!child.isFocused) {
                                child.recycle()
                            }
                        }
                        if (found != null) {
                            return found
                        }
                    }
                }
                return null
            } finally {
                visitedNodes.remove(node)
            }
        }

        return dfs(root)
    }

    @Suppress("DEPRECATION")
    private fun buildXmlDirectlyInternal(
        root: AccessibilityNodeInfo?,
        includeXml: Boolean,
        includeNodeMap: Boolean
    ): DirectBuildResult? {
        if (root == null) return null

        val writer = if (includeXml) StringWriter() else null
        val serializer = if (includeXml) {
            val namespace = "http://schemas.android.com/apk/res/android"
            Xml.newSerializer().apply {
                setOutput(writer)
                startDocument("UTF-8", true)
                setPrefix("", namespace)
                startTag(namespace, "hierarchy")
            }
        } else {
            null
        }

        val nodeMap = if (includeNodeMap) linkedMapOf<String, AccessibilityNode>() else null

        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()
        var nextNodeId = 0
        var visitedNodeCount = 0
        var truncated = false

        fun canContinueSerializing(): Boolean {
            if (truncated) return false
            if (visitedNodeCount >= DIRECT_XML_MAX_VISITED_NODES) {
                truncated = true
                return false
            }
            if (writer != null && writer.buffer.length >= DIRECT_XML_MAX_CHARS) {
                truncated = true
                return false
            }
            return true
        }

        fun addAttr(name: String, value: String?) {
            if (!value.isNullOrEmpty() && value != "false" && serializer != null) {
                serializer.attribute(null, name, value)
            }
        }

        fun trimAttr(
            text: String?,
            maxLen: Int
        ): String? {
            val sanitized = sanitizeXmlString(text)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (sanitized.length <= maxLen) sanitized else sanitized.take(maxLen) + "…"
        }

        fun traverse(
            node: AccessibilityNodeInfo?,
            depth: Int = 0
        ) {
            if (!canContinueSerializing()) return
            if (depth > DIRECT_XML_MAX_DEPTH || node == null) return

            if (visitedNodes.contains(node)) {
                return
            }

            if (depth > 0 && !node.isVisibleToUser) {
                return
            }

            visitedNodes.add(node)

            try {
                if (!canContinueSerializing()) return
                visitedNodeCount++

                val nodeId = nextNodeId.toString()
                nextNodeId++
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                val hasText = !node.text.isNullOrEmpty()
                val interactive = node.isClickable || node.isLongClickable ||
                    node.isFocusable || node.isFocused ||
                    node.isScrollable || node.isPassword ||
                    node.isSelected || node.isEditable
                val show = hasText || interactive || depth == 0

                if (nodeMap != null) {
                    nodeMap[nodeId] = AccessibilityNode(
                        info = node,
                        bounds = bounds,
                        show = show,
                        interactive = interactive
                    )
                }

                if (show && serializer != null) {
                    serializer.startTag(null, "node")
                    serializer.attribute(null, "id", nodeId)
                    addAttr("text", trimAttr(node.text?.toString(), DIRECT_XML_MAX_TEXT_ATTR_LENGTH))
                    addAttr(
                        "content-desc",
                        trimAttr(node.contentDescription?.toString(), DIRECT_XML_MAX_TEXT_ATTR_LENGTH)
                    )
                    addAttr("clickable", node.isClickable.toString())
                    addAttr("long-clickable", node.isLongClickable.toString())
                    addAttr("focusable", node.isFocusable.toString())
                    addAttr("focused", node.isFocused.toString())
                    addAttr("scrollable", node.isScrollable.toString())
                    addAttr("password", node.isPassword.toString())
                    addAttr("selected", node.isSelected.toString())
                    addAttr("editable", node.isEditable.toString())
                    addAttr(
                        "class",
                        trimAttr(node.className?.toString(), DIRECT_XML_MAX_GENERIC_ATTR_LENGTH)
                    )
                    addAttr(
                        "resource-id",
                        trimAttr(node.viewIdResourceName, DIRECT_XML_MAX_GENERIC_ATTR_LENGTH)
                    )
                    serializer.attribute(
                        null,
                        "bounds",
                        "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
                    )
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    if (!canContinueSerializing()) break
                    val child = try {
                        node.getChild(i)
                    } catch (e: Exception) {
                        OmniLog.w(
                            OmniScreenshotAction.TAG,
                            "Failed to get child at index $i: ${e.message}"
                        )
                        continue
                    }

                    if (child != null) {
                        try {
                            traverse(child, depth + 1)
                        } finally {
                            if (nodeMap == null) {
                                child.recycle()
                            }
                        }
                    }
                }

                if (show && serializer != null) {
                    serializer.endTag(null, "node")
                }
            } finally {
                visitedNodes.remove(node)
            }
        }

        traverse(root, 0)

        if (serializer != null) {
            val namespace = "http://schemas.android.com/apk/res/android"
            serializer.endTag(namespace, "hierarchy")
            serializer.endDocument()
        }
        return DirectBuildResult(
            xml = writer?.toString(),
            nodeMap = nodeMap
        )
    }

    fun buildXmlTree(root: AccessibilityNodeInfo?): XmlTreeNode? =
        buildRecursive(root, 0, visitedNodes = mutableSetOf(), depth = 0).first

    // TODO: nodeId allocation algorithm is not optimal
    private fun buildRecursive(
        node: AccessibilityNodeInfo?,
        currentId: Int,
        visitedNodes: MutableSet<AccessibilityNodeInfo> = mutableSetOf(),
        depth: Int = 0
    ): Pair<XmlTreeNode?, Int> {
        // 添加最大深度限制，防止过深的递归调用
        val maxDepth = 50
        if (depth > maxDepth) {
            OmniLog.w(OmniScreenshotAction.TAG, "Maximum recursion depth reached: $maxDepth")
            return null to currentId
        }

        // 检查节点是否已经访问过，防止循环引用导致的无限递归
        if (node != null && visitedNodes.contains(node)) {
            OmniLog.w(OmniScreenshotAction.TAG, "Circular reference detected in accessibility tree")
            return null to currentId
        }

        if (node == null || (!node.isVisibleToUser && currentId != 0)) {
            return null to currentId
        }

        // 将当前节点添加到已访问节点集合中
        visitedNodes.add(node)

        val nodeId = currentId.toString()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val hasText = !node.text.isNullOrEmpty()
        val interactive =
            node.isClickable || node.isLongClickable || node.isFocusable || node.isFocused || node.isScrollable || node.isPassword || node.isSelected || node.isEditable
        val show = hasText || interactive || currentId == 0 // Always show root node

        var nextId = currentId + 1
        val children = mutableListOf<XmlTreeNode>()
        for (i in 0 until node.childCount) {
            // 获取子节点时添加异常处理
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                OmniLog.w(OmniScreenshotAction.TAG, "Failed to get child at index $i: ${e.message}")
                continue
            }

            // 递归调用时传递visitedNodes和增加depth
            val (childTree, newId) = buildRecursive(child, nextId, visitedNodes, depth + 1)
            if (childTree != null) {
                children.add(childTree)
                nextId = newId
            }
        }

        // 从已访问节点集合中移除当前节点（确保在其他路径中可以再次访问）
        visitedNodes.remove(node)

        return XmlTreeNode(
            id = nodeId,
            node = AccessibilityNode(
                info = node,
                bounds = bounds,
                show = show,
                interactive = interactive,
            ),
            children = children,
        ) to nextId
    }

    fun extractNodeMap(tree: XmlTreeNode): Map<String, AccessibilityNode> {
        val map = mutableMapOf<String, AccessibilityNode>()

        fun dfs(node: XmlTreeNode) {
            map[node.id] = node.node
            node.children.forEach(::dfs)
        }
        dfs(tree)
        return map
    }

    private fun sanitizeXmlString(text: String?): String? {
        if (text == null) return null
        // This regex matches any character that is NOT a valid XML 1.0 character.
        val illegalXmlCharRegex = "[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]"
        return text.replace(Regex(illegalXmlCharRegex), "")
    }

    fun serializeXml(tree: XmlTreeNode): String {
        val writer = StringWriter()
        val namespace = "http://schemas.android.com/apk/res/android"
        val serializer = Xml.newSerializer().apply {
            setOutput(writer)
            startDocument("UTF-8", true)
            setPrefix("", namespace)
            startTag(namespace, "hierarchy")
        }

        fun addAttr(
            name: String,
            value: String?,
        ) {
            if (!value.isNullOrEmpty() && value != "false") {
                serializer.attribute(null, name, value)
            }
        }

        fun serializeNode(node: XmlTreeNode) {
            if (node.node.show) {
                val n = node.node.info
                val bounds = node.node.bounds
                serializer.startTag(null, "node")
                serializer.attribute(null, "id", node.id)
                // Sanitize text-based attributes before adding them
                addAttr("text", sanitizeXmlString(n.text?.toString()))
                addAttr("content-desc", sanitizeXmlString(n.contentDescription?.toString()))
                addAttr("clickable", n.isClickable.toString())
                addAttr("long-clickable", n.isLongClickable.toString())
                addAttr("focusable", n.isFocusable.toString())
                addAttr("focused", n.isFocused.toString())
                addAttr("scrollable", n.isScrollable.toString())
                addAttr("password", n.isPassword.toString())
                addAttr("selected", n.isSelected.toString())
                addAttr("editable", n.isEditable.toString())
                // addAttr("class-name", n.className?.toString() ?: "")
                serializer.attribute(
                    null,
                    "bounds",
                    "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                )
                node.children.forEach { serializeNode(it) }
                serializer.endTag(null, "node")
            } else {
                node.children.forEach { serializeNode(it) }
            }
        }

        serializeNode(tree)

        serializer.endTag(namespace, "hierarchy")
        serializer.endDocument()
        return writer.toString()
    }
}
