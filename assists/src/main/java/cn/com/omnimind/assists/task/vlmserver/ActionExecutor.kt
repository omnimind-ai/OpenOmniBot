package cn.com.omnimind.assists.task.vlmserver

/**
 * 动作执行器 - 负责执行UI操作动作
 * 对应Python中的 act 方法
 */

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

interface DeviceOperator {
    suspend fun clickCoordinate(x: Float, y: Float): OperationResult
    suspend fun doubleTapCoordinate(x: Float, y: Float): OperationResult
    suspend fun longClickCoordinate(x: Float, y: Float, duration: Long = 1000L): OperationResult
    suspend fun inputText(text: String): OperationResult
    suspend fun pressHotKey(key: String): OperationResult
    suspend fun copyToClipboard(text: String): OperationResult
    suspend fun getClipboard(): String? // 获取剪贴板内容
    suspend fun slideCoordinate(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): OperationResult
    suspend fun goHome(): OperationResult
    suspend fun goBack(): OperationResult
    suspend fun launchApplication(packageName: String): OperationResult
    suspend fun captureScreenshot(): String // 返回base64编码的截图
    fun getLastScreenshotWidth(): Int // 获取最后一次截图的宽度
    fun getLastScreenshotHeight(): Int // 获取最后一次截图的高度
    fun getDisplayWidth(): Int // 设备实际屏幕宽度
    fun getDisplayHeight(): Int // 设备实际屏幕高度
    fun supportsAccessibilityTree(): Boolean
    fun actionProtocol(): VlmActionProtocol
    suspend fun showInfo(message: String)

}

class ActionExecutor(
    private val deviceOperator: DeviceOperator,
    private val contextManager: UIContextManager
) {
    private val TAG = "ActionExecutor"
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ensureActionActive() {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 将相对坐标(0-1000)转换为绝对像素坐标
     * 基于截图图片的实际尺寸进行转换
     */
    private fun convertRelativeToAbsolute(relativeValue: Int, imageSize: Int): Float {
        val clamped = relativeValue.coerceIn(0, 1000)
        return (clamped / 1000.0f) * imageSize
    }

    /**
     * 执行VLM推理出的动作
     * 对应Python中的 act 方法
     * 注意：只执行动作，不更新上下文
     */
    suspend fun executeAction(
        vlmStep: VLMStep
    ): UIStep {

        val actionStart = System.currentTimeMillis()
        ensureActionActive()
        val result = when (val action = vlmStep.action) {
            is ClickAction -> {
                deviceOperator.clickCoordinate(action.x.toFloat(), action.y.toFloat())
            }

            is LongPressAction -> {
                deviceOperator.longClickCoordinate(action.x.toFloat(), action.y.toFloat())
            }

            is DoubleTapAction -> {
                deviceOperator.doubleTapCoordinate(action.x.toFloat(), action.y.toFloat())
            }

            is TypeAction -> {
                deviceOperator.inputText(action.content)
            }

            is ScrollAction -> {
                // VLM 模型返回的 duration 是秒，需要转换为毫秒
                val durationMs = (action.duration * 1000).toLong()
                deviceOperator.slideCoordinate(
                    action.x1.toFloat(),
                    action.y1.toFloat(),
                    action.x2.toFloat(),
                    action.y2.toFloat(),
                    durationMs
                )
            }

            is OpenAppAction -> {
                deviceOperator.launchApplication(action.packageName)
            }

            is PressHomeAction -> {
                deviceOperator.goHome()
            }

            is PressBackAction -> {
                deviceOperator.goBack()
            }

            is WaitAction -> {
                val waitMs = action.durationMs ?: action.duration?.times(1000) ?: 1000L
                kotlinx.coroutines.delay(waitMs)
                OperationResult(
                    success = true,
                    message = "等待${waitMs}ms",
                    data = null
                )
            }


            is RecordAction -> {
                // 特殊处理：记录动作不调用设备，返回成功结果
                OperationResult(
                    success = true,
                    message = "记忆关键信息成功",
                    data = null
                )
            }

            is FinishedAction -> {
                OperationResult(
                    success = true,
                    message = action.content.ifEmpty { "任务完成" },
                    data = null
                )
            }

            is RequireUserChoiceAction -> {
                OperationResult(
                    success = true,
                    message = "需要用户选择: ${action.prompt}",
                    data = json.encodeToJsonElement(action.options)
                )
            }

            is RequireUserConfirmationAction -> {
                OperationResult(
                    success = true,
                    message = "需要用户确认: ${action.prompt}",
                    data = null
                )
            }

            is InfoAction -> {
                OperationResult(
                    success = true,
                    message = "Agent询问: ${action.value}",
                    data = null
                )
            }

            is FeedbackAction -> {
                OperationResult(
                    success = true,
                    message = "收到反馈: ${action.value}",
                    data = null
                )
            }

            is AbortAction -> {
                OperationResult(
                    success = true,
                    message = "任务终止: ${action.value}",
                    data = null
                )
            }

            is HotKeyAction -> {
                deviceOperator.pressHotKey(action.key)
            }


            else -> {
                OperationResult(
                    success = false,
                    message = "不支持的操作类型: ${action.name}",
                    data = null
                )
            }
        }

        val needsPostDelay = when (vlmStep.action) {
            is ClickAction,
            is LongPressAction,
            is DoubleTapAction,
            is ScrollAction,
            is OpenAppAction,
            is PressHomeAction,
            is PressBackAction,
            is HotKeyAction -> true
            else -> false
        }

        val postDelayMs = if (needsPostDelay) 1000L else 0L
        if (postDelayMs > 0) {
            ensureActionActive()
            kotlinx.coroutines.delay(postDelayMs)
        }
        OmniLog.i(
            "TimeRecord",
            "VLM-actionExecutor ${vlmStep.action.name} took ${System.currentTimeMillis() - actionStart} ms (postDelayMs=$postDelayMs)"
        )

        return UIStep(
            observation = vlmStep.observation,
            thought = vlmStep.thought,
            action = vlmStep.action,
            result = if (result.success) result.message else "执行失败: ${result.message}"
        )
    }


    suspend fun act(vlmStep: VLMStep): UIStep {
        return executeAction(vlmStep)
    }
}
