package cn.com.omnimind.uikit.api.uieventimpl

import android.content.Context
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.uikit.api.uievent.UIBaseEvent
import cn.com.omnimind.baselib.util.CompanionUiState
import cn.com.omnimind.baselib.util.VibrationUtil
import cn.com.omnimind.uikit.loader.CancelClickLoader
import cn.com.omnimind.uikit.loader.FloatingHalfScreenLoader
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.settings.CompanionOverlaySettings
import cn.com.omnimind.uikit.view.indicator.BaseIndicator
import cn.com.omnimind.uikit.view.indicator.ClickIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UIBaseEventImpl : UIBaseEvent {
    private var currentIndicator: BaseIndicator? = null//点击动效
    private var context: Context? = null
    private var taskUIJob: CoroutineScope? = null

    override fun onUIInit(context: Context) {
        this.context = context;
    }
    private fun isFloatingUiEnabled(): Boolean {
        val enabled = CompanionOverlaySettings.isEnabled(context)
        if (!enabled) {
            CompanionOverlaySettings.dismissFloatingUi()
        }
        return enabled
    }

    override fun visibleCatInMain() {
        if (!isFloatingUiEnabled()) return
        DraggableBallInstance.visible()
    }

    override fun goneCatInMain() {
        DraggableBallInstance.gone()
    }

    override fun goneLockMaskInMain() {
        ScreenMaskLoader.gone()
    }
    override fun closeWithOutTaskDoingInMain() {
        DraggableBallInstance.closeAllWithoutDoing()
        FloatingHalfScreenLoader.destroyInstance()
    }
    override suspend fun showClickIndicator(x: Int, y: Int) = withContext(Dispatchers.Main) {
        if (!isFloatingUiEnabled()) return@withContext
        currentIndicator?.dismiss()
        if (context == null) {
            return@withContext
        }
        val indicator = ClickIndicator(AssistsService.instance!!, x.toFloat(), y.toFloat())
        currentIndicator = indicator
        try {
            indicator.showWithoutSuspend() { }
        } finally {
            if (currentIndicator == indicator) {
                currentIndicator = null
            }
        }
    }

    override suspend fun closeWithOutTaskDoing() = withContext(Dispatchers.Main) {
        DraggableBallInstance.closeAllWithoutDoing()
        FloatingHalfScreenLoader.destroyInstance()
    }


    override suspend fun visibleCat() = withContext(Dispatchers.Main) {
        if (!isFloatingUiEnabled()) return@withContext
        DraggableBallInstance.visible()
    }


    override suspend fun move(
        startX: Float, startY: Float, endX: Float, endY: Float
    ): Boolean {
        if (!isFloatingUiEnabled()) return false
        withContext(Dispatchers.Main) {
            DraggableBallInstance.getInstance()
                ?.move(startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt())
        }
        return DraggableBallInstance.getInstance()?.moveFinish() == true
    }

    override suspend fun message(text: String) = withContext(Dispatchers.Main) {
        if (!isFloatingUiEnabled()) return@withContext
        DraggableBallInstance.message(text)
    }

    override suspend fun startCompanion() {
        if (!isFloatingUiEnabled()) return
        if (taskUIJob?.isActive == true) {
            withContext(Dispatchers.Main) {
                DraggableBallInstance.cancelAnimation()
                taskUIJob?.cancel()
                DraggableBallInstance.destroy()
                destroyAccessibilityOverlays()
            }
        }
        taskUIJob = CoroutineScope(Dispatchers.IO)
        taskUIJob?.launch {
            VibrationUtil.vibrateLight()
            withContext(Dispatchers.Main) {
                if (AssistsService.isInit()) {
                    ScreenMaskLoader.loadGoneViewScreenMask()
                    CancelClickLoader.cancelIntercepting()
                }
                DraggableBallInstance.loadBall()
                if (!CompanionUiState.shouldSuppressStartMessage()) {
                    message("小万已经开始陪伴啦~")
                }
            }
        }

    }

    override suspend fun finishCompanion() {
        if (!isFloatingUiEnabled()) {
            CompanionOverlaySettings.dismissFloatingUi()
            return
        }
        VibrationUtil.vibrateLight()
        if (taskUIJob?.isActive == true) {
            taskUIJob?.cancel()
        }
        taskUIJob = CoroutineScope(Dispatchers.IO)
        taskUIJob?.launch {
            withContext(Dispatchers.Main) {
                // 先关闭消息气泡等所有功能视图
                DraggableBallInstance.collapse()

                DraggableBallInstance.cancelAnimation()
                DraggableBallInstance.finish() {
                    DraggableBallInstance.destroy()
                    destroyAccessibilityOverlays()
                }
            }
        }
    }

    private fun destroyAccessibilityOverlays() {
        ScreenMaskLoader.destroyInstance()
        CancelClickLoader.destroyInstance()
        FloatingHalfScreenLoader.destroyInstance()
    }

    override suspend fun <T> doAssistsUnlockScreenMask(
        block: suspend () -> T, lockScreenDelay: Long
    ): T {
        if (!isFloatingUiEnabled()) {
            return block()
        }
        if (AssistsService.isInit()) {
            withContext(Dispatchers.Main) {
                ScreenMaskLoader.loadUnlockScreenMask()
            }        //通过携程切换IO层执行
            delay(lockScreenDelay)
            VibrationUtil.vibrateLight()
            val data = block()
            withContext(Dispatchers.Main) {
                ScreenMaskLoader.loadLockScreenMask()
                DraggableBallInstance.moveBack()
            }
            return data
        } else {
            return block()
        }
    }

    override suspend fun lockScreenMask() = withContext(Dispatchers.Main) {
        if (!isFloatingUiEnabled()) return@withContext
        if (AssistsService.isInit()) {
            ScreenMaskLoader.loadUnlockScreenMask()
        }
    }

    override suspend fun cancelLockScreenMask() = withContext(Dispatchers.Main) {
        if (!isFloatingUiEnabled()) return@withContext
        if (AssistsService.isInit()) {
            ScreenMaskLoader.loadLockScreenMask()
        }
    }
}
