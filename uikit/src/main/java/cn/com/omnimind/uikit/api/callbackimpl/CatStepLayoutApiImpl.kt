package cn.com.omnimind.uikit.api.callbackimpl

import cn.com.omnimind.assists.AgentVlmUiSession
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.OmniFlowUiSession
import cn.com.omnimind.assists.api.eventapi.ExecutingTaskType
import cn.com.omnimind.baselib.util.VibrationUtil
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.loader.ManualRecordingControlOverlay
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatStepLayoutApiImpl : CatStepLayoutApi {
    private val manualRecordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onResumeClick() {
        VibrationUtil.vibrateLight()

        // 只有暂停状态会回调该方法
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            // 快速恢复可先更新 UI
            DraggableBallInstance.doingTask("用户操作已完成", "智能执行中")
            UIKit.executionTaskEventApi?.vlmTask?.resumeFromPause()
        }
    }

    override fun onStopClick() {
        if (HumanTrajectoryLearningSession.isActive()) {
            ManualRecordingControlOverlay.dismiss()
            DraggableBallInstance.setDoing(
                message = "正在整理复用指令",
                isShowTakeOver = false,
                subMessage = "请稍候",
                isShowStop = false
            )
            manualRecordingScope.launch {
                HumanTrajectoryLearningSession.completeActive()
            }
            return
        }
        if (UIKit.executionTaskEventApi?.vlmTask != null && completeActiveVlmUiSession()) {
            return
        }
        if (OmniFlowUiSession.requestStopActiveSession()) {
            DraggableBallInstance.finishDoingTask("任务已停止")
            if (!CompanionOverlaySettings.isEnabled()) {
                CompanionOverlaySettings.dismissFloatingUi()
            }
            return
        }
        if (completeActiveVlmUiSession()) {
            return
        }
        DraggableBallInstance.finishDoingTask("任务已完成")
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            UIKit.executionTaskEventApi?.vlmTask?.completeByUser()
        } else {
            AssistsCore.finishDoingTask()
        }
        if (!CompanionOverlaySettings.isEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
    }

    override fun onPauseClick() {
        VibrationUtil.vibrateLight()

        if (HumanTrajectoryLearningSession.isActive()) {
            val shouldResume = HumanTrajectoryLearningSession.isPaused()
            manualRecordingScope.launch {
                val updated = if (shouldResume) {
                    HumanTrajectoryLearningSession.resumeActive()
                } else {
                    HumanTrajectoryLearningSession.pauseActive()
                }
                withContext(Dispatchers.Main) {
                    if (!updated) {
                        return@withContext
                    }
                    if (shouldResume) {
                        ManualRecordingControlOverlay.markRecording()
                    } else {
                        ManualRecordingControlOverlay.markPaused()
                    }
                }
            }
            return
        }
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            DraggableBallInstance.pauseTask("用户已接管任务")
            UIKit.executionTaskEventApi?.vlmTask?.requestPause()
        }
    }

    private fun completeActiveVlmUiSession(): Boolean {
        if (!AgentVlmUiSession.requestCompleteActiveSession()) {
            return false
        }
        DraggableBallInstance.finishDoingTask("任务已完成")
        UIKit.executionTaskEventApi?.vlmTask?.completeByUser()
        if (!CompanionOverlaySettings.isEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
        return true
    }
}
