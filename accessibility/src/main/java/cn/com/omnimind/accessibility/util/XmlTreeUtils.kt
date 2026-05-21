package cn.com.omnimind.accessibility.util

import android.graphics.Rect
import android.os.Build
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST
import android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST
import android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS
import cn.com.omnimind.accessibility.action.AccessibilityNode
import cn.com.omnimind.accessibility.action.OmniScreenshotAction
import cn.com.omnimind.accessibility.action.XmlTreeNode
import cn.com.omnimind.baselib.util.OmniLog
import java.io.StringWriter

object XmlTreeUtils {
    /**
     * 直接生成 XML 字符串（优化版本，避免构建中间树结构）
     * 性能优化：
     * 1. 限制子节点数量（浅层50，深层20）
     * 2. 限制最大深度（15层）
     * 3. 只处理可见节点
     * 4. 及时回收节点
     */
    fun buildXmlDirectly(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null

        val writer = StringWriter()
        val namespace = "http://schemas.android.com/apk/res/android"
        val serializer = Xml.newSerializer().apply {
            setOutput(writer)
            startDocument("UTF-8", true)
            setPrefix("", namespace)
            startTag(namespace, "hierarchy")
        }

        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()
        var nodeIdCounter = 0

        fun addAttr(name: String, value: String?) {
            if (!value.isNullOrEmpty() && value != "false") {
                serializer.attribute(null, name, value)
            }
        }

        fun serializeNodeDirectly(
            node: AccessibilityNodeInfo?,
            depth: Int = 0
        ): Int {
            // 深度限制
            if (depth > 15) {
                return 0
            }

            if (node == null) return 0

            // 循环引用检查
            if (visitedNodes.contains(node)) {
                return 0
            }

            // 可见性检查（根节点除外）
            if (depth > 0 && !node.isVisibleToUser) {
                return 0
            }

            visitedNodes.add(node)

            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                val hasText = !node.text.isNullOrEmpty()
                val interactive = node.isClickable || node.isLongClickable ||
                        node.isFocusable || node.isFocused ||
                        node.isScrollable || node.isPassword ||
                        node.isSelected || node.isEditable
                val show = hasText || interactive || depth == 0

//                if (show) {
                val currentId = nodeIdCounter++
                serializer.startTag(null, "node")
                serializer.attribute(null, "id", currentId.toString())

                // 文本和描述属性
                addAttr("text", sanitizeXmlString(node.text?.toString()))
                addAttr("content-desc", sanitizeXmlString(node.contentDescription?.toString()))
                addAttr("hintText", sanitizeXmlString(node.hintText?.toString()))
                // 标识属性（非常重要，用于元素定位）
                addAttr("resource-id", node.viewIdResourceName)
                addAttr("class", node.className?.toString())
                // 交互状态属性
                addAttr("clickable", node.isClickable.toString())
                addAttr("long-clickable", node.isLongClickable.toString())
                addAttr("focusable", node.isFocusable.toString())
                addAttr("focused", node.isFocused.toString())
                addAttr("scrollable", node.isScrollable.toString())
                addAttr("editable", node.isEditable.toString())
                addAttr("selected", node.isSelected.toString())
                // 状态属性
                addAttr("enabled", node.isEnabled.toString())
                addAttr("checkable", node.isCheckable.toString())
                addAttr("checked", node.isChecked.toString())
                addAttr("password", node.isPassword.toString())
                // 特征点强的属性：输入类型（对于输入框很重要）
                if (node.isEditable) {
                    val inputType = node.inputType
                    if (inputType != 0) {
                        addAttr("input-type", inputType.toString())
                    }
                    val maxTextLength = node.maxTextLength
                    if (maxTextLength > 0) {
                        addAttr("max-text-length", maxTextLength.toString())
                    }
                }
                // 特征点强的属性：面板和容器标题
                addAttr("pane-title", sanitizeXmlString(node.paneTitle?.toString()))
                addAttr("state-description", sanitizeXmlString(node.stateDescription?.toString()))
                addAttr("tooltip-text", sanitizeXmlString(node.tooltipText?.toString()))

                // 特征点强的属性：错误信息
                addAttr("error", sanitizeXmlString(node.error?.toString()))
                // 特征点强的属性：绘制顺序（用于区分重叠元素）
                val drawingOrder = node.drawingOrder
                if (drawingOrder > 0) {
                    addAttr("drawing-order", drawingOrder.toString())
                }
                serializer.attribute(
                    null,
                    "bounds",
                    "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
                )
//                }

                // 限制子节点数量：浅层50，深层20
                val maxChildren = if (depth < 3) 50 else 20
                val childCount = minOf(node.childCount, maxChildren)


                var processedChildren = 0
                for (i in 0 until childCount) {
                    val child = try {
                        val startTime = System.currentTimeMillis()
                        // 获取子节点时添加异常处理
                        val child =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                node.getChild(i, FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
                            } else {
                                node.getChild(i)
                            }
                        OmniLog.d(
                            OmniScreenshotAction.TAG,
                            "Processing child used time ${System.currentTimeMillis() - startTime}ms"
                        )
                        child
                    } catch (e: Exception) {
                        OmniLog.w(
                            OmniScreenshotAction.TAG,
                            "Failed to get child at index $i: ${e.message}"
                        )
                        continue
                    }

                    if (child != null) {
                        try {
                            // 只处理可见的子节点
                            if (child.isVisibleToUser || depth == 0) {
                                processedChildren += serializeNodeDirectly(child, depth + 1)
                            }
                        } finally {
                            // 及时回收节点，避免内存泄漏
                            child.recycle()
                        }
                    }
                }

//                if (show) {
                serializer.endTag(null, "node")
//                }

                return processedChildren + 1
            } finally {
                visitedNodes.remove(node)
            }
        }

        serializeNodeDirectly(root, 0)

        serializer.endTag(namespace, "hierarchy")
        serializer.endDocument()
        return writer.toString()
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
        if (node != null) {
            visitedNodes.add(node)
        }

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
        if (node != null) {
            visitedNodes.remove(node)
        }

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
                addAttr("class", sanitizeXmlString(n.className?.toString()))
                addAttr("resource-id", sanitizeXmlString(n.viewIdResourceName))
                addAttr("enabled", n.isEnabled.toString())
                addAttr("clickable", n.isClickable.toString())
                addAttr("long-clickable", n.isLongClickable.toString())
                addAttr("focusable", n.isFocusable.toString())
                addAttr("focused", n.isFocused.toString())
                addAttr("scrollable", n.isScrollable.toString())
                addAttr("password", n.isPassword.toString())
                addAttr("selected", n.isSelected.toString())
                addAttr("editable", n.isEditable.toString())
                addAttr("checkable", n.isCheckable.toString())
                addAttr("checked", n.isChecked.toString())
                addAttr("state-description", sanitizeXmlString(n.stateDescription?.toString()))
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
