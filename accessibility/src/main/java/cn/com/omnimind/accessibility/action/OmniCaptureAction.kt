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
        /**
         * 判断是否应该忽略窗口变化
         * 屏蔽状态栏等系统组件
         */
        fun shouldIgnoreWindowChange(packageName: String, className: String): Boolean {

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
    }

    private var packageName: String = BaseApplication.instance.packageName
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
        val windowPackage = service.windows.find {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }?.root?.packageName?.toString()
//        if (isMineHalfScreen()) {
//            return packageName
//        }
        return windowPackage ?: packageName
    }

    fun getCurrentActivity(): String {
        return service.rootInActiveWindow?.className?.toString() ?: packageName
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
     * 屏蔽状态栏等系统组件
     */
    private fun shouldIgnoreWindowChange(packageName: String, className: String): Boolean {
        // 屏蔽系统UI组件
        val ignoredPackages = listOf(
            "com.android.systemui",           // 状态栏、通知栏
            "com.samsung.android.app.clipboardedge", // Samsung边缘面板
            "com.android.quickstep"           // 最近任务
        )

        // 屏蔽特定类名
        val ignoredClasses = listOf(
            "android.view.View",//普通view变化
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
        val ignoredKeywords = listOf("toast", "popup", "dialog")
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
    private fun getActualRootNode(): AccessibilityNodeInfo? {
        val windows = service.windows
        windows?.forEach { window ->
            // 排除悬浮窗类型的窗口
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = window.root
                if (root != null) {
                    return root
                }
            }
        }
        return null
    }

    fun captureScreenshotXml(withOld: Boolean): String? {
        val startTime=System.currentTimeMillis()
//        半屏特殊处理
//        if (isMineHalfScreen()) {
//            return xml
//        }
        var rootNode = getActualRootNode()
        if (rootNode == null) {
            rootNode = service.rootInActiveWindow
        }
//        val rstartTime=System.currentTimeMillis()
        // 在 API 33+ 上，先预取整个树结构

//                rootNode.refresh()
//        OmniLog.d("CaptureServer", "refresh used time ${System.currentTimeMillis()-rstartTime}")

//        if (rootNode == null){
//            return null
//        }
        xml = XmlTreeUtils.buildXmlDirectly(rootNode) ?: return null
        OmniLog.d("CaptureServer", "xml used time ${System.currentTimeMillis()-startTime}")

        // 使用优化的直接生成 XML 方法，避免构建中间树结构
        return xml
    }

    fun getNodeMap(): Map<String, AccessibilityNode>? {
        val rootNode = service.rootInActiveWindow ?: return null
        return XmlTreeUtils.buildNodeMapDirectly(rootNode)
    }

    fun findFocusedNode(): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null
        return XmlTreeUtils.findFocusedNode(rootNode)
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
