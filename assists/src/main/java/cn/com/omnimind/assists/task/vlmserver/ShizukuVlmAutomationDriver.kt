package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import android.util.Base64
import cn.com.omnimind.baselib.shizuku.PrivilegedResult
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.shizuku.ShizukuInputTextMode
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class ShizukuVlmAutomationDriver(context: Context) {
    private val appContext = context.applicationContext
    private val manager = ShizukuCapabilityManager.get(appContext)

    suspend fun tap(x: Float, y: Float): OperationResult {
        return manager.tap(x.roundForInput(), y.roundForInput()).toOperationResult("点击")
    }

    suspend fun doubleTap(x: Float, y: Float): OperationResult {
        val first = manager.tap(x.roundForInput(), y.roundForInput())
        if (!first.success) {
            return first.toOperationResult("双击-第一次点击")
        }
        delay(DOUBLE_TAP_INTERVAL_MS)
        val second = manager.tap(x.roundForInput(), y.roundForInput())
        return second.toOperationResult("双击")
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long): OperationResult {
        val inputX = x.roundForInput()
        val inputY = y.roundForInput()
        return manager.swipe(
            x1 = inputX,
            y1 = inputY,
            x2 = inputX,
            y2 = inputY,
            durationMs = durationMs.coerceIn(1L, 60_000L)
        ).toOperationResult("长按")
    }

    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): OperationResult {
        return manager.swipe(
            x1 = x1.roundForInput(),
            y1 = y1.roundForInput(),
            x2 = x2.roundForInput(),
            y2 = y2.roundForInput(),
            durationMs = durationMs.coerceIn(1L, 60_000L)
        ).toOperationResult("滑动")
    }

    suspend fun keyEvent(key: String): OperationResult {
        return manager.pressKeyEvent(key).toOperationResult("按键 $key")
    }

    suspend fun launch(packageName: String): OperationResult {
        return manager.launchApp(packageName).toOperationResult("启动应用 $packageName")
    }

    suspend fun inputText(text: String): OperationResult {
        return manager.inputText(text, ShizukuInputTextMode.AUTO).toOperationResult("输入文本")
    }

    suspend fun captureScreenshot(
        maxWidth: Int = DEFAULT_CAPTURE_MAX_WIDTH,
        maxHeight: Int = DEFAULT_CAPTURE_MAX_HEIGHT,
        quality: Int = DEFAULT_CAPTURE_QUALITY
    ): ShizukuVlmScreenshot {
        val result = manager.captureScreen(maxWidth, maxHeight, quality)
        if (!result.success) {
            throw RuntimeException("Shizuku 截图失败: ${result.errorMessage ?: "unknown"}")
        }
        val imageBytes = result.readImageBytesAndClose()
        if (imageBytes.isEmpty()) {
            throw RuntimeException("Shizuku 截图数据为空")
        }
        val mimeType = result.mimeType.ifBlank { "image/jpeg" }
        OmniLog.d(
            TAG,
            "Shizuku VLM screenshot method=${result.method} encoded=${result.width}x${result.height} display=${result.displayWidth}x${result.displayHeight} bytes=${imageBytes.size} elapsed=${result.elapsedMs}ms"
        )
        return ShizukuVlmScreenshot(
            dataUri = "data:$mimeType;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}",
            width = result.width,
            height = result.height,
            displayWidth = result.displayWidth.takeIf { it > 0 } ?: result.width,
            displayHeight = result.displayHeight.takeIf { it > 0 } ?: result.height,
            method = result.method,
            elapsedMs = result.elapsedMs
        )
    }

    private fun Float.roundForInput(): Int {
        return roundToInt().coerceAtLeast(0)
    }

    private fun PrivilegedResult.toOperationResult(operation: String): OperationResult {
        if (success) {
            return OperationResult(true, "$operation 成功", null)
        }
        val detail = output.ifBlank { stderr }.ifBlank { message }
        return OperationResult(false, "Shizuku $operation 失败 ($code): $detail", null)
    }

    companion object {
        private const val TAG = "ShizukuVlmDriver"
        private const val DOUBLE_TAP_INTERVAL_MS = 80L
        private const val DEFAULT_CAPTURE_MAX_WIDTH = 720
        private const val DEFAULT_CAPTURE_MAX_HEIGHT = 1280
        private const val DEFAULT_CAPTURE_QUALITY = 90
    }
}

data class ShizukuVlmScreenshot(
    val dataUri: String,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val method: String,
    val elapsedMs: Long
)
