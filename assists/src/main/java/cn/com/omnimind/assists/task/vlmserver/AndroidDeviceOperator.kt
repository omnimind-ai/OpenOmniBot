package cn.com.omnimind.assists.task.vlmserver

/**
 * Android设备操作器 - 基于现有的AccessibilityController实现
 */

import android.content.Context
import android.content.Intent
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

class AndroidDeviceOperator(
    private val executionTaskEventApi: ExecutionTaskEventApi?,
    private val context: Context? = null,
    private val backend: VlmAutomationBackend = VlmAutomationBackend.ACCESSIBILITY
) : DeviceOperator {

    private val Tag = "AndroidDeviceOperator"
    private val shizukuDriverInstance by lazy { context?.let { ShizukuVlmAutomationDriver(it) } }

    // 存储最后一次截图的尺寸（传给VLM的图片）以及设备实际尺寸
    private var lastScreenshotWidth: Int = 1080
    private var lastScreenshotHeight: Int = 1920
    private var lastDisplayWidth: Int = 1080
    private var lastDisplayHeight: Int = 1920

    override fun supportsAccessibilityTree(): Boolean = backend == VlmAutomationBackend.ACCESSIBILITY

    override fun actionProtocol(): VlmActionProtocol {
        return if (backend == VlmAutomationBackend.SHIZUKU) {
            VlmActionProtocol.DO_TEXT
        } else {
            VlmActionProtocol.OPENAI_TOOL_CALLS
        }
    }

    private fun shizukuDriver(): ShizukuVlmAutomationDriver? {
        return shizukuDriverInstance
    }

    private fun checkLaunchPrivacy(packageName: String) {
        if (APPPackageUtil.isPackageAuthorized(packageName)) {
            return
        }
        val appName = context?.let { APPPackageUtil.getAppName(it, packageName) }
            ?.takeIf { it.isNotBlank() }
            ?: packageName
        throw PrivacyBlockedException("应用 $appName 未授权，已被隐私设置限制")
    }

    companion object {
        private var clipboardResultCallback: ((Boolean) -> Unit)? = null
        private var clipboardGetResultCallback: ((String?) -> Unit)? = null
        private const val CLIPBOARD_ACTIVITY_CLASS =
            "cn.com.omnimind.bot.activity.ClipboardHelperActivity"
        private const val EXTRA_TEXT = "clipboard_text"
        private const val EXTRA_OPERATION = "clipboard_operation"
        private const val OPERATION_COPY = "copy"
        private const val OPERATION_GET = "get"

        @JvmStatic
        fun notifyClipboardResult(success: Boolean) {
            clipboardResultCallback?.invoke(success)
            clipboardResultCallback = null
        }

        @JvmStatic
        fun notifyClipboardGetResult(text: String?) {
            clipboardGetResultCallback?.invoke(text)
            clipboardGetResultCallback = null
        }
    }

    override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    shizukuDriver()?.tap(x, y)
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    if (executionTaskEventApi != null) {
                        executionTaskEventApi.clickCoordinate(x, y) {
                            AccessibilityController.clickCoordinate(x, y)
                        }
                    } else {
                        AccessibilityController.clickCoordinate(x, y)
                    }
                    OperationResult(true, "点击坐标 ($x, $y) 成功", null)
                }
            }
            if (result.success) result.copy(message = "点击坐标 ($x, $y) 成功") else result
        } catch (e: Exception) {
            OperationResult(false, "点击失败: ${e.message}", null)
        }
    }

    override suspend fun doubleTapCoordinate(x: Float, y: Float): OperationResult {
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    shizukuDriver()?.doubleTap(x, y)
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    AccessibilityController.clickCoordinate(x, y)
                    kotlinx.coroutines.delay(80L)
                    AccessibilityController.clickCoordinate(x, y)
                    OperationResult(true, "双击坐标 ($x, $y) 成功", null)
                }
            }
            if (result.success) result.copy(message = "双击坐标 ($x, $y) 成功") else result
        } catch (e: Exception) {
            OperationResult(false, "双击失败: ${e.message}", null)
        }
    }

    override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult {
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    shizukuDriver()?.longPress(x, y, duration)
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    if (executionTaskEventApi != null) {
                        executionTaskEventApi.longClickCoordinate(x, y) {
                            AccessibilityController.longClickCoordinate(x, y, duration)
                        }
                    } else {
                        AccessibilityController.longClickCoordinate(x, y, duration)
                    }
                    OperationResult(true, "长按坐标 ($x, $y) 成功", null)
                }
            }
            if (result.success) result.copy(message = "长按坐标 ($x, $y) 成功") else result
        } catch (e: Exception) {
            OperationResult(false, "长按失败: ${e.message}", null)
        }
    }

    override suspend fun inputText(text: String): OperationResult {
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    val driver = shizukuDriver()
                        ?: return OperationResult(false, "Shizuku driver unavailable", null)
                    val primaryResult = driver.inputText(text)
                    if (primaryResult.success) {
                        primaryResult
                    } else if (primaryResult.message.contains("ADB Keyboard", ignoreCase = true)) {
                        val clipboardResult = copyToClipboard(text)
                        if (!clipboardResult.success) {
                            primaryResult
                        } else {
                            val pasteResult = driver.keyEvent("KEYCODE_PASTE")
                            if (pasteResult.success) {
                                OperationResult(true, "输入文本成功: $text", null)
                            } else {
                                pasteResult
                            }
                        }
                    } else {
                        primaryResult
                    }
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    if (executionTaskEventApi != null) {
                        executionTaskEventApi.inputText {
                            AccessibilityController.inputTextToFocusedNode(text)
                        }
                    } else {
                        AccessibilityController.inputTextToFocusedNode(text)
                    }
                    OperationResult(true, "输入文本成功: $text", null)
                }
            }
            if (result.success) result.copy(message = "输入文本成功: $text") else result
        } catch (e: Exception) {
            OperationResult(false, "输入失败: ${e.message}", null)
        }
    }

    override suspend fun pressHotKey(key: String): OperationResult {
        val normalized = key.trim().uppercase()
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    shizukuDriver()?.keyEvent(normalized)
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    AccessibilityController.pressHotKey(normalized)
                    OperationResult(true, "按下热键 $normalized 成功", null)
                }
            }
            if (result.success) result.copy(message = "按下热键 $normalized 成功") else result
        } catch (primaryError: Exception) {
            OperationResult(false, "热键执行失败: ${primaryError.message}", null)
        }
    }

    override suspend fun copyToClipboard(text: String): OperationResult {
        val ctx = context ?: return try {
            // 无 context 时回退到原方法
            AccessibilityController.copyToClipboard(text)
            OperationResult(true, "已复制到剪贴板", null)
        } catch (e: Exception) {
            OmniLog.e(Tag, "copyToClipboard failed: ${e.message}", e)
            OperationResult(false, "复制到剪贴板失败: ${e.message}", null)
        }

        return try {
            val success = withTimeoutOrNull(5000L) {
                suspendCancellableCoroutine { continuation ->
                    clipboardResultCallback = { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    try {
                        val intent = Intent().apply {
                            setClassName(ctx.packageName, CLIPBOARD_ACTIVITY_CLASS)
                            putExtra(EXTRA_TEXT, text)
                            putExtra(EXTRA_OPERATION, OPERATION_COPY)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        clipboardResultCallback = null
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false

            if (success) {
                OperationResult(true, "已复制到剪贴板", null)
            } else {
                OperationResult(false, "复制到剪贴板失败", null)
            }
        } catch (e: Exception) {
            OperationResult(false, "复制到剪贴板失败: ${e.message}", null)
        }
    }

    override suspend fun getClipboard(): String? {
        val ctx = context ?: return null
        return try {
            withTimeoutOrNull(5000L) {
                suspendCancellableCoroutine { continuation ->
                    clipboardGetResultCallback = { text ->
                        if (continuation.isActive) continuation.resume(text)
                    }
                    try {
                        val intent = Intent().apply {
                            setClassName(ctx.packageName, CLIPBOARD_ACTIVITY_CLASS)
                            putExtra(EXTRA_OPERATION, OPERATION_GET)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        clipboardGetResultCallback = null
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } catch (e: Exception) {
            OmniLog.e(Tag, "getClipboard failed: ${e.message}")
            null
        }
    }

    override suspend fun slideCoordinate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long
    ): OperationResult {
        return try {
            val dx = x2 - x1
            val dy = y2 - y1
            val scrollDirection = if (abs(dy) > abs(dx)) {
                if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
            } else {
                if (dx > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT
            }

            val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    shizukuDriver()?.swipe(x1, y1, x2, y2, duration)
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    if (executionTaskEventApi != null) {
                        executionTaskEventApi.scrollCoordinate(
                            x1,
                            y1,
                            scrollDirection,
                            distance.toInt()
                        ) {
                            AccessibilityController.scrollCoordinate(
                                x1,
                                y1,
                                scrollDirection,
                                distance,
                                duration = duration
                            )
                        }
                    } else {
                        AccessibilityController.scrollCoordinate(
                            x1,
                            y1,
                            scrollDirection,
                            distance,
                            duration = duration
                        )
                    }
                    OperationResult(true, "滑动 ($x1, $y1) → ($x2, $y2) 成功", null)
                }
            }
            if (result.success) result.copy(message = "滑动 ($x1, $y1) → ($x2, $y2) 成功") else result
        } catch (e: Exception) {
            OperationResult(false, "滑动失败: ${e.message}", null)
        }
    }

    override suspend fun goHome(): OperationResult {
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    shizukuDriver()?.keyEvent("HOME")
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    if (executionTaskEventApi != null) {
                        executionTaskEventApi.goHome {
                            AccessibilityController.goHome()
                        }
                    } else {
                        AccessibilityController.goHome()
                    }
                    OperationResult(true, "返回桌面成功", null)
                }
            }
            if (result.success) result.copy(message = "返回桌面成功") else result
        } catch (e: Exception) {
            OperationResult(false, "返回桌面失败: ${e.message}", null)
        }
    }

    override suspend fun goBack(): OperationResult {
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    shizukuDriver()?.keyEvent("BACK")
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    if (executionTaskEventApi != null) {
                        executionTaskEventApi.goBack {
                            AccessibilityController.goBack()
                        }
                    } else {
                        AccessibilityController.goBack()
                    }
                    OperationResult(true, "返回上一级成功", null)
                }
            }
            if (result.success) result.copy(message = "返回上一级成功") else result
        } catch (e: Exception) {
            OperationResult(false, "返回上一级失败: ${e.message}", null)
        }
    }

    /**
     * 启动应用
     */
    override suspend fun launchApplication(packageName: String): OperationResult {
        return try {
            val result = when (backend) {
                VlmAutomationBackend.SHIZUKU -> {
                    checkLaunchPrivacy(packageName)
                    shizukuDriver()?.launch(packageName)
                        ?: OperationResult(false, "Shizuku driver unavailable", null)
                }
                VlmAutomationBackend.ACCESSIBILITY -> {
                    AccessibilityController.launchApplication(packageName) { x, y ->
                        if (executionTaskEventApi != null) {
                            executionTaskEventApi.clickCoordinate(x, y) {
                                AccessibilityController.clickCoordinate(x, y)
                            }
                        } else {
                            AccessibilityController.clickCoordinate(x, y)
                        }
                    }
                    OperationResult(true, "启动应用 $packageName 成功", null)
                }
            }
            if (result.success) result.copy(message = "启动应用 $packageName 成功") else result
        } catch (e: PrivacyBlockedException) {
            // 隐私限制异常需要终止任务，重新抛出
            throw e
        } catch (e: Exception) {
            OperationResult(false, "启动应用失败: ${e.message}", null)
        }
    }

    /**
     * 捕获截图并返回Base64编码字符串
     */
    override suspend fun captureScreenshot(): String {
        return try {
            val start = System.currentTimeMillis()
            val finalBase64 = when (backend) {
                VlmAutomationBackend.SHIZUKU -> captureScreenshotViaShizuku()
                VlmAutomationBackend.ACCESSIBILITY -> captureScreenshotViaAccessibility()
            }
            OmniLog.d(
                Tag,
                "captureScreenshot cost ${System.currentTimeMillis() - start}ms, backend=$backend"
            )

            finalBase64
        } catch (e: Exception) {
            OmniLog.e("Assists", "captureScreenshot failed: ${e.message}", e)
            throw RuntimeException("截图失败: ${e.message}")
        }
    }

    private suspend fun captureScreenshotViaAccessibility(): String {
        val payload = AccessibilityController.captureScreenshotImage(
            isFilterOverlay = true,
            isBase64 = true,
            compressQuality = ImageQuality.MEDIUM
        )
        if (!payload.isSuccess) {
            throw RuntimeException("截图数据为空")
        }

        lastScreenshotWidth = payload.compressedWidth
        lastScreenshotHeight = payload.compressedHeight

        val displayMetrics = context?.resources?.displayMetrics
        val metricsWidth = displayMetrics?.widthPixels ?: payload.originalWidth
        val metricsHeight = displayMetrics?.heightPixels ?: payload.originalHeight

        lastDisplayWidth = maxOf(payload.originalWidth, metricsWidth)
        lastDisplayHeight = maxOf(payload.originalHeight, metricsHeight)

        OmniLog.d(
            Tag,
            "accessibility screenshot=${lastScreenshotWidth}x${lastScreenshotHeight}, originalDisplay=${payload.originalWidth}x${payload.originalHeight}, metrics=${metricsWidth}x${metricsHeight}, chosenDisplay=${lastDisplayWidth}x${lastDisplayHeight}, scale=${payload.appliedScale}"
        )

        return payload.imageBase64!!
    }

    private suspend fun captureScreenshotViaShizuku(): String {
        val screenshot = shizukuDriver()?.captureScreenshot()
            ?: throw RuntimeException("Shizuku driver unavailable")
        lastScreenshotWidth = screenshot.width
        lastScreenshotHeight = screenshot.height
        lastDisplayWidth = screenshot.displayWidth
        lastDisplayHeight = screenshot.displayHeight
        OmniLog.d(
            Tag,
            "shizuku screenshot=${screenshot.width}x${screenshot.height}, display=${screenshot.displayWidth}x${screenshot.displayHeight}, method=${screenshot.method}, elapsed=${screenshot.elapsedMs}ms"
        )
        return screenshot.dataUri
    }

    override fun getLastScreenshotWidth(): Int = lastScreenshotWidth

    override fun getLastScreenshotHeight(): Int = lastScreenshotHeight

    override fun getDisplayWidth(): Int = lastDisplayWidth

    override fun getDisplayHeight(): Int = lastDisplayHeight
    override suspend fun showInfo(message: String) {
        executionTaskEventApi?.updateShowStepText(message)
    }

}
