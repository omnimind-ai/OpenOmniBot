package cn.com.omnimind.uikit.loader.cat

import android.annotation.SuppressLint
import android.view.View
import android.view.WindowManager.BadTokenException
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.util.DisplayUtil
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.dpToPx
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.view.data.Constant
import cn.com.omnimind.uikit.view.data.WindowFlag
import cn.com.omnimind.uikit.loader.CancelClickLoader
import cn.com.omnimind.uikit.view.data.CatDialogStateData
import cn.com.omnimind.uikit.view.data.CatDialogViewState
import cn.com.omnimind.uikit.view.overlay.cat.CatView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * 实现小猫单例,防止空指针或删除执行
 */
object DraggableBallInstance {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var dragBall: DraggableBallLoader? = null
    private const val TAG = "DraggableBallInstance"

    fun getInstance(): DraggableBallLoader? {
        if (AssistsService.instance != null) {
            return dragBall ?: synchronized(this) {
                dragBall ?: DraggableBallLoader(
                    AssistsService.instance!!,
                    UIKit.catLayoutApi,
                    UIKit.menuApi,
                    UIKit.catApi,
                    UIKit.catStepLayoutApi
                ).also { dragBall = it }
            }
        }
        return null
    }

    /**
     * 加载小猫
     */
    fun loadBall() {
        getInstance()?.loadBall()
    }

    /**
     * 收起小猫
     */
    fun collapse() {
        getInstance()?.collapse()

    }

    /**
     * 学习中
     */
    /**
     * 首次展示长条方形形态（准备做任务）
     * 无论当前是什么状态，都会先显示一个中间的40dp*40dp小圆形（带跑马灯效果），然后扩散成自适应宽高的长条
     */
    fun readyDoingTask(message: String) {
        val instance = getInstance() ?: return
        CancelClickLoader.cancelIntercepting()
        instance.collapseMenu()
        instance.catView.setViewState(DraggableViewState.DOING_TASK)
        instance.catDialogShowInfoViewParams = instance.getParams(WindowFlag.SCREEN_LOCK_FLAG)
        val (x, y) = CatDialogStateData.getMessageInfoXY()
        val (w, h) = CatDialogStateData.getMessageInfoWH()
        instance.catDialogShowInfoViewParams.y = y
        instance.catDialogShowInfoViewParams.x = x
        instance.catDialogShowInfoViewParams.width = w
        instance.catDialogShowInfoViewParams.height = h
        instance.catDialogShowInfoView.visibility = View.VISIBLE
        if (instance.isAttachedToWindow) {
            instance.catDialogShowInfoView.cancelAnimations()
            instance.getWindowManager().removeView(instance.catDialogShowInfoView)
        }
        try {
            instance.getWindowManager()
                .addView(instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams)
        } catch (e: BadTokenException) {
            OmniLog.e(TAG, "readyDoingTask addView BadTokenException: ${e.message}")
            return
        }
        // 无论当前是什么状态，都直接执行首次展示动画
        instance.catDialogShowInfoView.readyDoingTask(
            message = message
        )
    }

    /**
     * 做任务中
     */
    fun doingTask(
        message: String, subMessage: String
    ) {
        val instance = getInstance() ?: return
        CancelClickLoader.cancelIntercepting()
        instance.catView.setViewState(DraggableViewState.DOING_TASK)
        instance.collapseMenu()
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY) {
            instance.catDialogShowInfoViewParams = instance.getParams(WindowFlag.SCREEN_LOCK_FLAG)
            val (x, y) = CatDialogStateData.getDoingTaskXY()
            val (w, h) = CatDialogStateData.getTaskDoingWH()
            instance.catDialogShowInfoViewParams.y = y
            instance.catDialogShowInfoViewParams.x = x
            instance.catDialogShowInfoViewParams.width = w
            instance.catDialogShowInfoViewParams.height = h
            instance.catDialogShowInfoView.visibility = View.VISIBLE
            if (instance.isAttachedToWindow) {
                instance.catDialogShowInfoView.cancelAnimations()
                instance.getWindowManager().removeView(instance.catDialogShowInfoView)
            }
            try {
                instance.getWindowManager()
                    .addView(instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams)
            } catch (e: BadTokenException) {
                OmniLog.e(TAG, "doingTask addView BadTokenException: ${e.message}")
                return
            }
        }

        // 调用 doingTask，传递必要的参数以支持状态切换动画
        instance.catDialogShowInfoView.doingTask(
            message = message,
            subMessage = subMessage,
            layoutParams = instance.catDialogShowInfoViewParams,
            catDialogShowInfoView = instance.catDialogShowInfoView,
            windowManager = instance.getWindowManager()

        )

    }

    fun setDoing(message: String, isShowTakeOver: Boolean = true) {
        val instance = getInstance() ?: return
        CancelClickLoader.cancelIntercepting()
        instance.catView.setViewState(DraggableViewState.DOING_TASK)
        instance.collapseMenu()
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY) {
            instance.catDialogShowInfoViewParams = instance.getParams(WindowFlag.SCREEN_LOCK_FLAG)
            val (x, y) = CatDialogStateData.getDoingTaskXY()
            val (w, h) = CatDialogStateData.getTaskDoingWH()
            instance.catDialogShowInfoViewParams.y = y
            instance.catDialogShowInfoViewParams.x = x
            instance.catDialogShowInfoViewParams.width = w
            instance.catDialogShowInfoViewParams.height = h
            instance.catDialogShowInfoView.visibility = View.VISIBLE
            if (instance.isAttachedToWindow) {
                instance.catDialogShowInfoView.cancelAnimations()
                instance.getWindowManager().removeView(instance.catDialogShowInfoView)
            }
            try {
                instance.getWindowManager()
                    .addView(instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams)
            } catch (e: BadTokenException) {
                OmniLog.e(TAG, "setDoing addView BadTokenException: ${e.message}")
                return
            }
        }
        var msg = if (message.isEmpty() || message.equals("null")) {
            "正在执行中..."
        } else {
            message
        }
        // 调用 doingTask，传递必要的参数以支持状态切换动画
        instance.catDialogShowInfoView.setDoing(
            message = msg,
            layoutParams = instance.catDialogShowInfoViewParams,
            catDialogShowInfoView = instance.catDialogShowInfoView,
            windowManager = instance.getWindowManager(),
            isShowTakeOver = isShowTakeOver
        )
    }

    /**
     * VLM任务暂停（用户接管）
     */
    fun pauseTask(message: String) {
        // 取消待执行的动画任务
        val instance = getInstance() ?: return
        CancelClickLoader.cancelIntercepting()
        instance.catDialogShowInfoView.cancelAnimations()
        instance.catView.setViewState(DraggableViewState.DOING_TASK)
        instance.collapseMenu()
        instance.catDialogShowInfoView.pauseTask(
            message = message,
            catDialogShowInfoView = instance.catDialogShowInfoView,
            catDialogShowInfoViewParams = instance.catDialogShowInfoViewParams,
            windowManager = instance.getWindowManager()
        )
    }

    /**
     * VLM任务暂停（用户接管）
     */
    suspend fun userTakeover(
        message: String
    ): Boolean {
        // 取消待执行的动画任务
        val instance = getInstance() ?: return false
        CancelClickLoader.cancelIntercepting()
        instance.catDialogShowInfoView.cancelAnimations()
        instance.catView.setViewState(DraggableViewState.DOING_TASK)
        instance.collapseMenu()

        return instance.catDialogShowInfoView.userAction(
            message = message,
            catDialogShowInfoView = instance.catDialogShowInfoView,
            catDialogShowInfoViewParams = instance.catDialogShowInfoViewParams,
            windowManager = instance.getWindowManager()
        )
    }

    /**
     *  显示消息
     */
    fun message(message: String) {
        getInstance()?.catView?.setViewState(DraggableViewState.MESSAGE)
        getInstance()?.catDialogLayoutView?.message(message)
        getInstance()?.collapseMenu()

    }

    /**
     *  显示任务结束消息
     */
    fun finishDoingTask(message: String) {
        // 取消待执行的动画任务
        val instance = getInstance() ?: return
        val catDialogShowInfoView = instance.catDialogShowInfoView
        val windowManager = instance.getWindowManager()
        catDialogShowInfoView.cancelAnimations()
        instance.catView?.setViewState(DraggableViewState.MESSAGE)
        instance.catDialogLayoutView?.finishDoingTask(message)
        catDialogShowInfoView.finishDoingTask()
        instance.catDialogShowInfoViewParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG
        windowManager.updateViewLayout(
            instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams
        )
        instance.load()
        instance.collapseMenu()
    }

    /**
     *  显示学习结束消息
     */
    /**
     * 显示预约提醒
     */
    fun showScheduledTip(closeTimer: Long, doTaskTimer: Long) {
        getInstance()?.catView?.setViewState(DraggableViewState.SCHEDULED_TIP)
        getInstance()?.catDialogLayoutView?.showScheduledTip(
            closeTimer, doTaskTimer
        )
        getInstance()?.collapseMenu()
    }

    fun destroy() {
        // 取消待执行的动画任务
        getInstance()?.destroy()
        // 清除单例实例引用
        dragBall = null
    }

    fun closeAllWithoutDoing() {
        val currentState = getInstance()?.catDialogLayoutView?.getCurrentState()
        if (currentState != DraggableViewState.MESSAGE && currentState != DraggableViewState.DOING_TASK && currentState != DraggableViewState.COLLAPSED) {
            collapse()
        }
    }

    fun showMenu() {
        getInstance()?.catView?.setViewState(DraggableViewState.COLLAPSED)
        getInstance()?.catDialogLayoutView?.collapse()
        CancelClickLoader.interceptingOtherViewClick {
            getInstance()?.collapseMenu()
        }
        getInstance()?.showMenu()
    }

    /**退出陪伴使用 */
    fun finish(
        onAnimEnd: () -> Unit = {},
    ) {
        val targetX = if (getInstance()?.isAttachedToRight == true) {
            (DisplayUtil.getScreenHeight() ?: (0)) - CatView.width.dpToPx() * 2 / 3
        } else {
            0 - CatView.width.dpToPx() / 3
        }
        getInstance()?.catViewLayoutParams?.x = targetX
        getInstance()?.getWindowManager()
            ?.updateViewLayout(getInstance()?.catView, getInstance()?.catViewLayoutParams)
        getInstance()?.catView?.doFinish(onAnimEnd)
    }

    fun cancelAnimation() {
        getInstance()?.moveToScreenAnimator?.cancel()
        getInstance()?.catView?.cancelAnimation()
    }

    fun moveToTop() {

        getInstance()?.startMove(Constant.CAT_SAFETY_MARGIN_TOP)
    }

    fun moveToCenter() {
        getInstance()?.startMove((DisplayUtil.getScreenHeight() ?: 0) / 2)
    }

    fun gone() {
        val instance = getInstance() ?: return
        val windowManager = instance.getWindowManager()
        val isAttachedToWindow = instance.isAttachedToWindow

        // 设置 visibility 为 GONE
        instance.catView?.visibility = View.GONE
        instance.catDialogLayoutView?.visibility = View.GONE
        instance.catDialogShowInfoView?.visibility = View.GONE

        // 将窗口 flags 改为 SCREEN_UNLOCK_FLAG
        if (isAttachedToWindow) {
            try {
                instance.catViewLayoutParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG
                instance.catDialogLayoutViewLayoutParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG
                instance.catDialogShowInfoViewParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG

                windowManager.updateViewLayout(instance.catView, instance.catViewLayoutParams)
                windowManager.updateViewLayout(
                    instance.catDialogLayoutView, instance.catDialogLayoutViewLayoutParams
                )
                windowManager.updateViewLayout(
                    instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to update window flags in gone: ${e.message}", e)
            }
        }
    }

    fun visible() {
        val instance = getInstance() ?: return
        val windowManager = instance.getWindowManager()
        val isAttachedToWindow = instance.isAttachedToWindow

        // 将窗口 flags 改回 SCREEN_LOCK_FLAG
        if (isAttachedToWindow) {
            try {
                instance.catViewLayoutParams.flags = WindowFlag.SCREEN_LOCK_FLAG
                instance.catDialogLayoutViewLayoutParams.flags = WindowFlag.SCREEN_LOCK_FLAG

                windowManager.updateViewLayout(instance.catView, instance.catViewLayoutParams)
                windowManager.updateViewLayout(
                    instance.catDialogLayoutView, instance.catDialogLayoutViewLayoutParams
                )
                if (instance.catDialogShowInfoView?.getShowInfoViewVisibility() == View.VISIBLE) {
                    instance.catDialogShowInfoViewParams.flags = WindowFlag.SCREEN_LOCK_FLAG
                    windowManager.updateViewLayout(
                        instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams
                    )
                } else {
                    instance.catDialogShowInfoViewParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG
                    windowManager.updateViewLayout(
                        instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to update window flags in visible: ${e.message}", e)
            }
        }

        // 设置 visibility 为 VISIBLE
        instance.catView?.visibility = View.VISIBLE
        instance.catDialogLayoutView?.visibility = View.VISIBLE
        instance.catDialogShowInfoView?.visibility =
            instance.catDialogShowInfoView?.getShowInfoViewVisibility() ?: View.GONE
    }

    fun hideForExternalActivity(): Boolean {
        return getInstance()?.hideForExternalActivity() ?: false
    }

    fun restoreAfterExternalActivity(): Boolean {
        return getInstance()?.restoreAfterExternalActivity() ?: false
    }

    fun bringCatToFront() {
        getInstance()?.bringCatToFront()
    }

    fun moveBack() {

        getInstance()?.moveBack()
    }


}
