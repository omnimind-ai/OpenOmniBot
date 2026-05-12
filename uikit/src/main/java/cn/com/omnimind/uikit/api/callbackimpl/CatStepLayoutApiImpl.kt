package cn.com.omnimind.uikit.api.callbackimpl

import cn.com.omnimind.assists.AssistsCore
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
        // 不论什么情况，执行结束都需要结束任务
        DraggableBallInstance.finishDoingTask("任务已结束!")
        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            UIKit.executionTaskEventApi?.vlmTask?.finishTask()
        } else {
            AssistsCore.finishDoingTask()
        }
        if (!CompanionOverlaySettings.isEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
    }

    override fun onPauseClick() {
        VibrationUtil.vibrateLight()

        if (UIKit.executionTaskEventApi?.taskType == ExecutingTaskType.VLM) {
            UIKit.executionTaskEventApi?.vlmTask?.requestPause()
        }
    }
}
