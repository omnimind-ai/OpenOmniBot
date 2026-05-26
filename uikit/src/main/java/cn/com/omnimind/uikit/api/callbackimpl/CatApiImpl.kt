package cn.com.omnimind.uikit.api.callbackimpl

import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.callback.CatApi
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatApiImpl : CatApi {
    private var doTask = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 如果在任务进行中,长按小猫可以结束任务
     */
    override fun onCatLongPressInDoingTask() {
        AssistsCore.finishCompanionTask()
    }

    /**
     * 普通状态下,长按小猫可以打开菜单
     */
    override fun onCatLongPress() {
        if (!CompanionOverlaySettings.isEnabled()) return
        DraggableBallInstance.showMenu()
    }

    override fun onCatDoubleClick() {

    }

    override fun onCatClick(x: Int, y: Int) {
        if (!CompanionOverlaySettings.isEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
            return
        }
        DraggableBallInstance.clearTaskCompletionHint()
        doTask.cancel()
        doTask = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        doTask.launch {
            withContext(Dispatchers.Main) {
                ScreenMaskLoader.loadLockScreenMask(x, y)
            }
            delay(500)
            UIKit.uiChatEvent?.showChatBotHalfScreen()
        }
    }

    override fun onCloseChatBotDialog() {
        ScreenMaskLoader.loadGoneViewScreenMask()
        DraggableBallInstance.collapse()
        UIKit.uiChatEvent?.dismissHalfScreenInMain()

    }
}
