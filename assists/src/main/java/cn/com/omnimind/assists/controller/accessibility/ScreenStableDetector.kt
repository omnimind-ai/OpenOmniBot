package cn.com.omnimind.assists.controller.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.accessibility.util.XmlTreeUtils
import cn.com.omnimind.assists.util.TreeEditDistance
import kotlinx.coroutines.delay

class ScreenStableDetector(
    private val service: AssistsService?
) {
    private var lastXml: String? = null
    private var stableCount = 0
    private var lastCheckTime: Long = 0
    private val MIN_CHECK_INTERVAL_MS = 500L
    private val MAX_WAIT_TIME_MS = 2000L

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

    /**
     * 检测屏幕是否稳定
     * 通过比较连续几次的XML屏幕结构来判断屏幕是否稳定
     * 协程版本：在1秒内发起2-3次检测，若检测一致则返回true
     */
    suspend fun isScreenStable(): Boolean {
        var previousTree: String? = null
        val maxChecks = 5
        val checkInterval = 300L //
        for (i in 0 until maxChecks) {
            val currentTree = captureScreenshotXml()

            if (currentTree != null && previousTree != null) {
                // 计算节点树相似度
                val similarity = TreeEditDistance.getSimilarity(previousTree, currentTree)
                // 如果相似度超过90%则认为稳定
                if (similarity >= 0.8) {
                    reset()
                    return true
                }
            }

//             非最后一次检测时才delay
            if (i < maxChecks - 1) {
                delay(checkInterval)
            }

            previousTree = currentTree

        }

        return false
    }


    /**
     * 重置稳定状态检测器
     */
    fun reset() {
        lastXml = null
        stableCount = 0
        lastCheckTime = 0
    }

    /**
     * 获取当前屏幕的XML表示
     */
    private fun captureScreenshotXml(): String? {
        val rootNode = service?.rootInActiveWindow ?: return null
        return XmlTreeUtils.buildXmlDirectly(rootNode)
    }


}
