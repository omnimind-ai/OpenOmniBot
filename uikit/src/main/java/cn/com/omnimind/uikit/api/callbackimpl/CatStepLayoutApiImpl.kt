package cn.com.omnimind.uikit.api.callbackimpl

import cn.com.omnimind.assists.AgentVlmUiSession
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.api.eventapi.ExecutingTaskType
import cn.com.omnimind.baselib.util.VibrationUtil
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings

class CatStepLayoutApiImpl : CatStepLayoutApi {
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
        if (HumanTrajectoryLearningSession.completeActive()) {
            DraggableBallInstance.setDoing(
                message = "正在整理复用指令",
                isShowTakeOver = false,
                subMessage = "请稍候",
                isShowStop = false
            )
            return
        }
        if (AgentVlmUiSession.requestCompleteActiveSession()) {
            DraggableBallInstance.finishDoingTask("任务已完成")
            UIKit.executionTaskEventApi?.vlmTask?.completeByUser()
            if (!CompanionOverlaySettings.isEnabled()) {
                CompanionOverlaySettings.dismissFloatingUi()
            }
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
            HumanTrajectoryLearningSession.cancelActive("人工轨迹学习已取消")
            DraggableBallInstance.finishDoingTask("学习已取消")
            if (!CompanionOverlaySettings.isEnabled()) {
                CompanionOverlaySettings.dismissFloatingUi()
            }
            return
        }
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            DraggableBallInstance.pauseTask("用户已接管任务")
            UIKit.executionTaskEventApi?.vlmTask?.requestPause()
        }
    }
}
