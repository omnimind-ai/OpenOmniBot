package cn.com.omnimind.accessibility.action

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.OmniLog

import cn.com.omnimind.accessibility.api.Constant
import cn.com.omnimind.accessibility.util.XmlTreeUtils

class OmniCaptureAction(
    private val service: AssistsService,
) {
    companion object {
        const val TAG = "OmniScreenshotController"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MAX_CAPTURE_NODE_SCAN_COUNT = 120
        private val SYSTEM_UI_CAPTURE_TERMS = setOf(
            "brightness",
            "volume",
            "slider",
            "seekbar",
            "quick_settings",
            "qs_tile",
            "tile_label",
            "dialog",
            "media"
        )
        /**
         * 判断是否应该忽略窗口变化
         * 屏蔽状态栏、输入法等系统组件
         */
        fun shouldIgnoreWindowChange(packageName: String, className: String): Boolean {
            if (isOobPackage(packageName)) {
                return true
            }
            if (packageName == SYSTEM_UI_PACKAGE) {
                return isIgnoredSystemUiClass(className)
            }

            // 检查包名
            if (Constant.IGNORED_PACKAGES.any { packageName.startsWith(it) }) {
                return true
            }

            // 检查类名
            if (Constant.IGNORED_CLASSES.contains(className)) {
                return true
            }
            // 检查正则表达式匹配
            if (Constant.IGNORED_PATTERNS.any { className.matches(it) }) {
                return true
            }

            // 屏蔽包含特定关键词的类
            if (Constant.IGNORED_VIEWS.any {
                    className.lowercase()
                        .contains(it) && packageName.startsWith(Constant.SYSTEM_PACKAGE_START)
                }) {
                return true
            }

            return false
        }

        fun filterCurrentPackageName(
            event: AccessibilityEvent?, filterBack: (packageName: String) -> Unit
        ) {
            when (event?.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // 过滤不需要监听的界面变化
                    val packageName = event.packageName?.toString() ?: return
                    val className = event.className?.toString() ?: return
                    if (shouldIgnoreWindowChange(packageName, className)) {
                        return
                    }
                    filterBack.invoke(packageName);
                }
            }
        }

        private fun isIgnoredSystemUiClass(className: String): Boolean {
            val normalizedClass = className.lowercase()
            return normalizedClass.contains("statusbar") ||
                normalizedClass.contains("navigationbar") ||
                normalizedClass.contains("notification") ||
                normalizedClass.contains("keyguard") ||
                normalizedClass.contains("volumeui") ||
                normalizedClass.contains("screenrecord") ||
                normalizedClass.contains("toast")
        }

        private fun isOobPackage(packageName: String): Boolean =
            packageName == BaseApplication.instance.packageName ||
                packageName.startsWith("cn.com.omnimind.")
    }

    private var packageName: String = ""
    private var className: String = ""
    private var xml: String = ""

    /**
     * 判断当前是否是半屏
     * 对node特殊处理了
     */
    fun isMineHalfScreen(): Boolean {
        return service.rootInActiveWindow?.availableExtraData?.contains("HalfScreenView") == true
    }

    fun getCurrentPackageName(): String {
        val windowPackage = selectCurrentWindowRoot()?.packageName
//        if (isMineHalfScreen()) {
//            return packageName
//        }
        return windowPackage ?: packageName
    }

    fun getCurrentActivity(): String {
        return selectCurrentWindowRoot()?.className ?: className
    }


    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
        }
    }

    /**
     * 处理窗口状态变化事件
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return

        // 过滤不需要监听的界面变化
        if (shouldIgnoreWindowChange(packageName, className)) {
            return
        }

        OmniLog.d(TAG, "package变化: package=$packageName, class=$className")
        this.packageName = packageName
        this.className = className;
    }

    /**
     * 判断是否应该忽略窗口变化
     * 屏蔽状态栏、输入法等系统组件
     */
    private fun shouldIgnoreWindowChange(packageName: String, className: String): Boolean {
        if (isOobPackage(packageName)) {
            return true
        }
        // 屏蔽系统UI组件
        val ignoredPackages = listOf(
            "com.google.android.inputmethod.latin", // Google输入法
            "com.samsung.android.app.clipboardedge", // Samsung边缘面板
            "com.android.quickstep",           // 最近任务
            "com.vivo.upslide"                 // vivo 手势栏/侧滑栏/上滑控制层
        )

        // 屏蔽特定类名
        val ignoredClasses = listOf(
            "android.view.View",//普通view变化
            "android.inputmethodservice.SoftInputWindow",  // 输入法窗口
            "android.widget.Toast\$ToastView",             // Toast提示
            "com.android.systemui.statusbar.phone.StatusBarWindowView", // 状态栏
            "com.android.systemui.popup.PopupContainerWithArrow" // 弹窗
        )
        // 使用正则表达式匹配android.widget包下的所有类
        val ignoredPatterns = listOf(
            "^android\\.widget\\..*".toRegex(),  // 匹配android.widget包下的所有类
            "^android\\.view\\..*".toRegex(),  // 匹配android.widget包下的所有类
            "^android\\.support\\..*".toRegex()  // 匹配android.widget包下的所有类


        )
        // 检查包名
        if (packageName == SYSTEM_UI_PACKAGE) {
            return isIgnoredSystemUiClass(className)
        }
        if (ignoredPackages.any { packageName.startsWith(it) }) {
            return true
        }

        // 检查类名
        if (ignoredClasses.contains(className)) {
            return true
        }
        // 检查正则表达式匹配
        if (ignoredPatterns.any { className.matches(it) }) {
            return true
        }

        // 屏蔽包含特定关键词的类
        val ignoredKeywords = listOf("toast", "popup", "dialog", "ime")
        if (ignoredKeywords.any {
                className.lowercase().contains(it) && packageName.startsWith("com.android")
            }) {
            return true
        }
        if (isMineHalfScreen()) {
            return true
        }

        return false
    }
    private fun getActualRootNode(): AccessibilityNodeInfo? =
        selectCurrentWindowRoot()?.root

    private data class WindowRoot(
        val root: AccessibilityNodeInfo,
        val packageName: String,
        val className: String,
        val type: Int,
        val layer: Int,
        val active: Boolean,
        val focused: Boolean,
        val accessibilityFocused: Boolean,
    )

    private fun selectForegroundWindowRoot(): WindowRoot? {
        val windows = service.windows ?: emptyList()
        return windows
            .asSequence()
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val packageName = root.packageName?.toString().orEmpty()
                val className = root.className?.toString().orEmpty()
                if (shouldIgnoreCaptureWindow(window, root, packageName, className)) {
                    null
                } else {
                    WindowRoot(
                        root = root,
                        packageName = packageName,
                        className = className,
                        type = window.type,
                        layer = window.layer,
                        active = window.isActive,
                        focused = window.isFocused,
                        accessibilityFocused = window.isAccessibilityFocused,
                    )
                }
            }
            .sortedWith(
                compareByDescending<WindowRoot> { it.layer }
                    .thenByDescending { captureWindowTypePriority(it.type) }
                    .thenByDescending { if (it.active) 1 else 0 }
                    .thenByDescending { if (it.accessibilityFocused) 1 else 0 }
                    .thenByDescending { if (it.focused) 1 else 0 }
            )
            .firstOrNull()
    }

    private fun selectCurrentWindowRoot(): WindowRoot? =
        selectForegroundWindowRoot() ?: activeWindowRoot()

    private fun activeWindowRoot(): WindowRoot? {
        val root = service.rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val className = root.className?.toString().orEmpty()
        if (shouldIgnoreCaptureRoot(root, packageName, className)) {
            return null
        }
        return WindowRoot(
            root = root,
            packageName = packageName,
            className = className,
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            layer = Int.MIN_VALUE,
            active = true,
            focused = root.isFocused,
            accessibilityFocused = root.isAccessibilityFocused,
        )
    }

    private fun shouldIgnoreCaptureWindow(
        window: AccessibilityWindowInfo,
        root: AccessibilityNodeInfo,
        packageName: String,
        className: String,
    ): Boolean {
        if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        ) {
            return true
        }
        return shouldIgnoreCaptureRoot(root, packageName, className)
    }

    private fun shouldIgnoreCaptureRoot(
        root: AccessibilityNodeInfo,
        packageName: String,
        className: String,
    ): Boolean {
        if (isOobPackage(packageName)) {
            return true
        }
        if (Constant.IGNORED_PACKAGES.any { packageName.startsWith(it) }) {
            return true
        }
        if (packageName.startsWith("com.google.android.inputmethod") ||
            packageName.startsWith("com.android.inputmethod")
        ) {
            return true
        }
        val normalizedClass = className.lowercase()
        if (normalizedClass.contains("toast") ||
            normalizedClass.contains("softinputwindow") ||
            normalizedClass.contains("ime")
        ) {
            return true
        }
        if (packageName == SYSTEM_UI_PACKAGE && isIgnoredSystemUiClass(className)) {
            return true
        }
        if (packageName == SYSTEM_UI_PACKAGE && !hasMeaningfulSystemUiContent(root)) {
            return true
        }
        return false
    }

    private fun hasMeaningfulSystemUiContent(root: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_CAPTURE_NODE_SCAN_COUNT) {
            val node = stack.removeLast()
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            val contentDescription = node.contentDescription?.toString()?.trim().orEmpty()
            val resourceId = node.viewIdResourceName?.trim().orEmpty()
            val className = node.className?.toString()?.lowercase().orEmpty()
            val semantic = listOf(text, contentDescription, resourceId, className)
                .joinToString(" ")
                .lowercase()
            if (SYSTEM_UI_CAPTURE_TERMS.any { semantic.contains(it) }) {
                return true
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(stack::add)
            }
        }
        return false
    }

    private fun captureWindowTypePriority(type: Int): Int =
        when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> 3
            AccessibilityWindowInfo.TYPE_SYSTEM -> 2
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> 1
            else -> 0
        }

    fun captureScreenshotXml(withOld: Boolean): String? {
        val startTime=System.currentTimeMillis()
//        半屏特殊处理
//        if (isMineHalfScreen()) {
//            return xml
//        }
        val rootNode = getActualRootNode()
//        val rstartTime=System.currentTimeMillis()
        // 在 API 33+ 上，先预取整个树结构

//                rootNode.refresh()
//        OmniLog.d("CaptureServer", "refresh used time ${System.currentTimeMillis()-rstartTime}")

//        if (rootNode == null){
//            return null
//        }
//        if (withOld){
//            val rootNode = service.rootInActiveWindow ?: return null
            val xmlTree = XmlTreeUtils.buildXmlTree(rootNode) ?: return null
            xml = XmlTreeUtils.serializeXml(xmlTree)
//        }else{
//            xml = XmlTreeUtils.buildXmlDirectly(rootNode) ?: return null

//        }
        OmniLog.d("CaptureServer", "xml used time ${System.currentTimeMillis()-startTime}")

        // 使用优化的直接生成 XML 方法，避免构建中间树结构
        return xml
    }

    fun getNodeMap(): Map<String, AccessibilityNode>? {
        val rootNode = getActualRootNode() ?: return null
        val xmlTree = XmlTreeUtils.buildXmlTree(rootNode) ?: return null
        return XmlTreeUtils.extractNodeMap(xmlTree)
    }
}

data class AccessibilityNode(
    val info: AccessibilityNodeInfo,
    val bounds: Rect,
    val show: Boolean,
    val interactive: Boolean,
)

data class XmlTreeNode(
    val id: String,
    val node: AccessibilityNode,
    val children: List<XmlTreeNode>,
)
