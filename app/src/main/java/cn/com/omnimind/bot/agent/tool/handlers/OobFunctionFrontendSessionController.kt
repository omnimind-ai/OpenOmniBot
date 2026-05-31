package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.OmniFlowUiSession
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the transient frontend state shown while a local Function replay is
 * running. The replay handler owns execution; this controller only manages UI
 * lifecycle and user stop signals.
 */
class OobFunctionFrontendSessionController(
    private val helper: SharedHelper,
) {
    suspend fun start(
        functionId: String,
        spec: Map<String, Any?>,
        stepCount: Int,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        callStack: List<String>,
        fallbackRunIdProvider: () -> String,
    ): Session? {
        if (stepCount <= 0 || callStack.isNotEmpty()) return null
        if (!canUseMainDispatcher()) return null
        val runId = toolHandle?.runId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackRunIdProvider()
        val taskId = "${runId}_omniflow_ui"
        val stopRequested = AtomicBoolean(false)
        val label = frontendLabel(functionId, spec)
        OmniFlowUiSession.registerRun(
            runId = runId,
            onStopRequested = { stopRequested.set(true) },
            onCompleteRequested = { stopRequested.set(true) }
        )
        OmniFlowUiSession.beginTask(runId, taskId)
        runCatching {
            withContext(Dispatchers.Main) {
                ScreenMaskLoader.loadGoneViewScreenMask()
                DraggableBallInstance.loadBall()
                DraggableBallInstance.setDoing(
                    message = helper.localized("OmniFlow 准备执行"),
                    isShowTakeOver = false,
                    subMessage = helper.localized(label),
                    isShowStop = false,
                    isTouchable = false
                )
            }
        }.onFailure {
            OmniLog.w(TAG, "start OmniFlow frontend failed: ${it.message}")
        }
        return Session(
            runId = runId,
            taskId = taskId,
            stopRequested = stopRequested,
            label = label,
            helper = helper,
        )
    }

    private suspend fun canUseMainDispatcher(): Boolean =
        runCatching {
            withContext(Dispatchers.Main.immediate) { true }
        }.getOrDefault(false)

    private fun frontendLabel(
        functionId: String,
        spec: Map<String, Any?>,
    ): String {
        val name = firstNonBlank(
            spec["name"],
            spec["title"],
            spec["description"],
            functionId,
        )
        return name.replace(Regex("\\s+"), " ").take(32).ifBlank { "复用指令" }
    }

    class Session internal constructor(
        private val runId: String,
        private val taskId: String,
        private val stopRequested: AtomicBoolean,
        private val label: String,
        private val helper: SharedHelper,
    ) {
        fun throwIfStopRequested() {
            if (stopRequested.get()) {
                throw ManualToolStopCancellationException("OmniFlow execution stopped manually")
            }
        }

        suspend fun update(progress: String) {
            throwIfStopRequested()
            val message = helper.localized(
                "OmniFlow：${progress.trim().ifBlank { label }.take(48)}"
            )
            runCatching {
                withContext(Dispatchers.Main) {
                    ScreenMaskLoader.loadGoneViewScreenMask()
                    DraggableBallInstance.setDoing(
                        message = message,
                        isShowTakeOver = false,
                        subMessage = helper.localized("本地执行中"),
                        isShowStop = false,
                        isTouchable = false
                    )
                }
            }.onFailure {
                OmniLog.w(TAG, "update OmniFlow frontend failed: ${it.message}")
            }
            throwIfStopRequested()
        }

        suspend fun finish(message: String) {
            OmniFlowUiSession.endTask(taskId)
            val end = OmniFlowUiSession.endRun(runId)
            if (!end.wasActive) return
            runCatching {
                withContext(NonCancellable + Dispatchers.Main) {
                    ScreenMaskLoader.loadGoneViewScreenMask()
                    DraggableBallInstance.finishDoingTask(message)
                }
            }.onFailure {
                OmniLog.w(TAG, "finish OmniFlow frontend failed: ${it.message}")
            }
        }
    }

    private companion object {
        const val TAG = "OobFunctionFrontendSession"
    }
}
