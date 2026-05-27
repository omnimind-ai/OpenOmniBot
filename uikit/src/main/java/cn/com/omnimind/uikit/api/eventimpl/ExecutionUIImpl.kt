package cn.com.omnimind.uikit.api.eventimpl

import cn.com.omnimind.assists.AgentVlmUiSession
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.eventapi.ExecutingTaskType
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.assists.task.vlmserver.VLMOperationTask
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import cn.com.omnimind.uikit.api.uievent.UIBaseEvent
import cn.com.omnimind.uikit.api.uievent.UIChatEvent
import cn.com.omnimind.uikit.api.uievent.UITaskEvent
import cn.com.omnimind.uikit.util.NotificationUtil
import kotlinx.coroutines.delay

class ExecutionUIImpl(
    val uiChatEvent: UIChatEvent,
    val uiBaseEvent: UIBaseEvent,
    val uiTaskEvent: UITaskEvent,
    override var taskType: ExecutingTaskType = ExecutingTaskType.EMPTY,
    override var vlmTask: VLMOperationTask? = null
) : ExecutionTaskEventApi {


    override suspend fun onReadyStartVLMTask(task: VLMOperationTask) {
        taskType = ExecutingTaskType.VLM
        vlmTask = task
        uiTaskEvent.readyDoingTask("小万即将为您执行任务...")
        
        // 可取消的延迟：每100ms检查一次取消状态，共检查20次（2秒）
        repeat(20) {
             if (task.isCancellationRequested) {
                throw kotlinx.coroutines.CancellationException("Task cancelled during pre-execution delay")
             }
            delay(100)
        }
    }

    override suspend fun onStartVLMTask(isCompanionRunning: Boolean) {
        AgentVlmUiSession.markTaskCompanionState(vlmTask?.id, isCompanionRunning)
        if (uiChatEvent.isChatBotHalfScreenShowing()) {
            uiChatEvent.dismissHalfScreen()
        }
        if (isCompanionRunning) {
            uiTaskEvent.startDoingAutoTask(
                "小万已领取任务，即将开始执行", "智能执行中"
            )
        } else {
            uiTaskEvent.startCompanionAndDoingTask()
        }
    }

    override suspend fun onVlmTaskPaused(vmlTask: VLMOperationTask) {
        uiBaseEvent.cancelLockScreenMask()
        uiTaskEvent.pauseTask("用户已接管任务")
    }

    override suspend fun onVLMTaskStop(
        finishType: TaskFinishType, message: String, isCompanionRunning: Boolean
    ) {
        if (finishType == TaskFinishType.WAITING_INPUT) {
            val isResume = uiTaskEvent.waitingUserAction(message)
            if (isResume) {
                vlmTask?.provideUserInput("用户已完成操作,请继续执行")
            } else {
                vlmTask?.completeByUser()
            }
        } else if (finishType == TaskFinishType.USER_PAUSED) {
            uiTaskEvent.pauseTask("用户已接管任务")
            OmniLog.d("StateMachine", "VLM任务进入用户主动暂停状态")
        } else {
            val currentTaskId = vlmTask?.id
            if (AgentVlmUiSession.shouldHoldUiForTask(currentTaskId)) {
                AgentVlmUiSession.markTaskStopSuppressed(currentTaskId, isCompanionRunning)
                vlmTask = null
                uiTaskEvent.setDoing("继续执行中...", false)
                OmniLog.d("StateMachine", "VLM子任务结束，agent-run 仍在运行，小万UI保持执行态")
                return
            }
            vlmTask = null
            uiTaskEvent.finishDoingTask(message.ifEmpty { finishType.message })
            if (!isCompanionRunning) {
                delay(500)//动画执行完毕再执行结束
                uiBaseEvent.finishCompanion()
            }
        }
    }

    override suspend fun readyOpenThirdAPP(packageName: String) {
        uiTaskEvent.setDoing("正在打开应用...", false);
    }

    //无障碍能力相关
    override suspend fun clickCoordinate(
        x: Float, y: Float, block: suspend () -> Unit
    ) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            uiBaseEvent.move(x, y, x, y)
            uiBaseEvent.showClickIndicator(x.toInt(), y.toInt())
            block.invoke()
        })
    }

    override suspend fun clickCoordinateWithOutLock(
        x: Float, y: Float, clickCoordinateFun: suspend () -> Unit
    ) {
        uiBaseEvent.move(x, y, x, y)
        uiBaseEvent.showClickIndicator(x.toInt(), y.toInt())
        clickCoordinateFun.invoke()
    }

    override suspend fun goBack(goBackFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            goBackFun.invoke()
        })
    }


    override suspend fun scrollCoordinate(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Int,
        scrollCoordinateFun: suspend () -> Unit
    ) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            var endX = x
            var endY = y
            when (direction) {
                ScrollDirection.LEFT -> {
                    endX = x - distance
                }

                ScrollDirection.RIGHT -> {
                    endX = x + distance
                }

                ScrollDirection.UP -> {
                    endY = y - distance
                }

                ScrollDirection.DOWN -> {
                    endY = y + distance
                }
            }
            // 等待动画完成后再执行滑动操作
            uiBaseEvent.move(x, y, endX, endY)
            // 确保动画完成后再执行实际滑动
            scrollCoordinateFun.invoke()
        })
    }

    override suspend fun longClickCoordinate(
        x: Float, y: Float, longClickCoordinateFun: suspend () -> Unit
    ) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            uiBaseEvent.move(x, y, x, y)
            longClickCoordinateFun.invoke()
        })
    }

    override suspend fun goHome(goHomeFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            goHomeFun.invoke()
        })
    }

    override suspend fun inputText(inputTextFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            inputTextFun.invoke()
        })
    }

    override suspend fun pasteText(pasteTextFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            pasteTextFun.invoke()
        })
    }

    //业务相关
    override suspend fun showChatWithSummary() {
        uiChatEvent.showChatBotHalfScreen("summary")
    }

    override suspend fun userTakeover(message: String): Boolean {
        return uiTaskEvent.waitingUserAction(message)
    }

    override suspend fun updateShowStepText(message: String) {
        uiTaskEvent.setDoing(message, true)
    }

    override suspend fun dismissScheduledNotification() {
        NotificationUtil.dismiss()
    }

    override suspend fun startFirstUseMessage(string: String) {
        uiBaseEvent.message(string)
    }

    override suspend fun showScheduledTip(closeTime: Long, doTaskTime: Long) {
        uiTaskEvent.showScheduledTip(closeTime, doTaskTime)
    }


}
