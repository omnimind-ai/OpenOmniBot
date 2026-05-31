package cn.com.omnimind.uikit.loader.cat

import android.annotation.SuppressLint
import android.view.View
import android.view.WindowManager.BadTokenException
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
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


/**
 * 实现小猫单例,防止空指针或删除执行
 */
object DraggableBallInstance {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var dragBall: DraggableBallLoader? = null

    @Volatile
    private var isTaskCompletionHintVisible = false

    @Volatile
    private var taskCompletionHintClickAction: (() -> Unit)? = null

    private const val TAG = "DraggableBallInstance"

    private fun shouldUseApplicationOverlay(preferApplicationOverlay: Boolean): Boolean {
        return preferApplicationOverlay || HumanTrajectoryLearningSession.isActive()
    }

    fun getInstance(preferApplicationOverlay: Boolean = false): DraggableBallLoader? {
        val forceApplicationOverlay = shouldUseApplicationOverlay(preferApplicationOverlay)
        val accessibilityService = AssistsService.instance
        val useAccessibilityOverlay = accessibilityService != null && !forceApplicationOverlay
        val context = if (useAccessibilityOverlay) {
            accessibilityService
        } else {
            UIKit.appContext ?: accessibilityService
        } ?: return null
        return synchronized(this) {
            val current = dragBall
            if (current != null && current.useAccessibilityOverlay == useAccessibilityOverlay) {
                return@synchronized current
            }
            if (current != null) {
                current.destroy()
                dragBall = null
            }
            DraggableBallLoader(
                    context,
                    useAccessibilityOverlay = useAccessibilityOverlay,
                    UIKit.catLayoutApi,
                    UIKit.menuApi,
                    UIKit.catApi,
                    UIKit.catStepLayoutApi
                )
                .also { dragBall = it }
        }
    }

    private fun refreshOverlayContextIfAccessibilityStateChanged(
        preferApplicationOverlay: Boolean = false
    ) {
        val current = dragBall ?: return
        val forceApplicationOverlay = shouldUseApplicationOverlay(preferApplicationOverlay)
        val shouldUseAccessibilityOverlay =
            AssistsService.instance != null && !forceApplicationOverlay
        if (current.useAccessibilityOverlay == shouldUseAccessibilityOverlay) {
            return
        }
        destroy()
    }

    /**
     * 加载小猫
     */
    fun loadBall(preferApplicationOverlay: Boolean = false): Boolean {
        val forceApplicationOverlay = shouldUseApplicationOverlay(preferApplicationOverlay)
        refreshOverlayContextIfAccessibilityStateChanged(forceApplicationOverlay)
        val instance = getInstance(forceApplicationOverlay) ?: return false
        instance.loadBall()
        if (!forceApplicationOverlay &&
            instance.useAccessibilityOverlay &&
            !instance.isAttachedToWindow
        ) {
            OmniLog.w(TAG, "loadBall accessibility overlay failed; retrying application overlay")
            destroy()
            val fallback = getInstance(preferApplicationOverlay = true) ?: return false
            fallback.loadBall()
            return fallback.isAttachedToWindow
        }
        return instance.isAttachedToWindow
    }

    fun isShowing(): Boolean {
        return dragBall?.isAttachedToWindow == true
    }

    fun refreshPetAppearance() {
        dragBall?.catView?.post {
            dragBall?.catView?.refreshPetAppearance()
        }
    }

    private fun getLoadedInstance(
        preferApplicationOverlay: Boolean = false
    ): DraggableBallLoader? {
        val forceApplicationOverlay = shouldUseApplicationOverlay(preferApplicationOverlay)
        refreshOverlayContextIfAccessibilityStateChanged(forceApplicationOverlay)
        val instance = getInstance(forceApplicationOverlay) ?: return null
        if (!instance.isAttachedToWindow) {
            instance.loadBall()
        }
        if (!forceApplicationOverlay &&
            instance.useAccessibilityOverlay &&
            !instance.isAttachedToWindow
        ) {
            OmniLog.w(TAG, "getLoadedInstance accessibility overlay failed; retrying application overlay")
            destroy()
            val fallback = getInstance(preferApplicationOverlay = true) ?: return null
            if (!fallback.isAttachedToWindow) {
                fallback.loadBall()
            }
            return fallback.takeIf { it.isAttachedToWindow }
        }
        return instance.takeIf { it.isAttachedToWindow }
    }

    /**
     * 收起小猫
     */
    fun collapse() {
        resetTaskCompletionHintState()
        dragBall?.collapse()

    }

    /**
     * 学习中
     */
    /**
     * 首次展示长条方形形态（准备做任务）
     * 无论当前是什么状态，都会先显示一个中间的40dp*40dp小圆形（带跑马灯效果），然后扩散成自适应宽高的长条
     */
    fun readyDoingTask(message: String) {
        resetTaskCompletionHintState()
        val instance = getLoadedInstance() ?: return
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
        resetTaskCompletionHintState()
        val instance = getLoadedInstance() ?: return
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

    fun setDoing(
        message: String,
        isShowTakeOver: Boolean = true,
        subMessage: String? = null,
        isShowStop: Boolean = true,
        preferApplicationOverlay: Boolean = false,
        isTouchable: Boolean = true
    ) {
        resetTaskCompletionHintState()
        val instance = getLoadedInstance(preferApplicationOverlay) ?: return
        CancelClickLoader.cancelIntercepting()
        instance.catView.setViewState(DraggableViewState.DOING_TASK)
        instance.collapseMenu()
        val windowFlag = if (isTouchable) {
            WindowFlag.SCREEN_LOCK_FLAG
        } else {
            WindowFlag.SCREEN_UNLOCK_FLAG
        }
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY ||
            instance.catDialogShowInfoViewParams.flags != windowFlag
        ) {
            instance.catDialogShowInfoViewParams = instance.getParams(windowFlag)
            val (x, y) = CatDialogStateData.getDoingTaskXY()
            val (w, h) = CatDialogStateData.getTaskDoingWH()
            instance.catDialogShowInfoViewParams.y = y
            instance.catDialogShowInfoViewParams.x = x
            instance.catDialogShowInfoViewParams.width = w
            instance.catDialogShowInfoViewParams.height = h
            instance.catDialogShowInfoView.visibility = View.VISIBLE
            if (instance.catDialogShowInfoView.isAttachedToWindow) {
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
            isShowTakeOver = isShowTakeOver,
            isShowStop = isShowStop,
            subMessage = subMessage
        )
    }

    fun learningTask(
        message: String,
        subMessage: String,
        preferApplicationOverlay: Boolean = false,
        isPaused: Boolean = false
    ) {
        resetTaskCompletionHintState()
        val instance = getLoadedInstance(preferApplicationOverlay) ?: return
        CancelClickLoader.cancelIntercepting()
        instance.catView.setViewState(DraggableViewState.DOING_TASK)
        instance.collapseMenu()
        val needsAttach = !instance.catDialogShowInfoView.isAttachedToWindow
        val needsFreshLearningLayout =
            needsAttach ||
                instance.catDialogShowInfoView.visibility != View.VISIBLE ||
                CatDialogStateData.viewState == CatDialogViewState.TASK_DOING
        if (needsFreshLearningLayout) {
            CatDialogStateData.viewState = CatDialogViewState.EMPTY
        }
        instance.catDialogShowInfoViewParams.flags = WindowFlag.SCREEN_LOCK_FLAG
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY || needsAttach) {
            instance.catDialogShowInfoViewParams = instance.getParams(WindowFlag.SCREEN_LOCK_FLAG)
            val (x, y) = CatDialogStateData.getDoingTaskXY()
            val (w, h) = CatDialogStateData.getTaskDoingWH()
            instance.catDialogShowInfoViewParams.y = y
            instance.catDialogShowInfoViewParams.x = x
            instance.catDialogShowInfoViewParams.width = w
            instance.catDialogShowInfoViewParams.height = h
            instance.catDialogShowInfoView.visibility = View.VISIBLE
            if (instance.catDialogShowInfoView.isAttachedToWindow) {
                instance.catDialogShowInfoView.cancelAnimations()
                instance.getWindowManager().removeView(instance.catDialogShowInfoView)
            }
            try {
                instance.getWindowManager()
                    .addView(instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams)
            } catch (e: BadTokenException) {
                OmniLog.e(TAG, "learningTask addView BadTokenException: ${e.message}")
                return
            }
        } else {
            val (x, y) = CatDialogStateData.getDoingTaskXY()
            val (w, h) = CatDialogStateData.getTaskDoingWH()
            instance.catDialogShowInfoViewParams.x = x
            instance.catDialogShowInfoViewParams.y = y
            instance.catDialogShowInfoViewParams.width = w
            instance.catDialogShowInfoViewParams.height = h
            instance.catDialogShowInfoView.visibility = View.VISIBLE
            try {
                instance.getWindowManager()
                    .updateViewLayout(
                        instance.catDialogShowInfoView,
                        instance.catDialogShowInfoViewParams
                    )
            } catch (e: IllegalArgumentException) {
                OmniLog.e(TAG, "learningTask updateViewLayout skipped: ${e.message}")
            }
        }
        OmniLog.d(
            TAG,
            "learningTask overlay visible x=${instance.catDialogShowInfoViewParams.x} " +
                "y=${instance.catDialogShowInfoViewParams.y} " +
                "w=${instance.catDialogShowInfoViewParams.width} " +
                "h=${instance.catDialogShowInfoViewParams.height}"
        )
        instance.catDialogShowInfoView.learningTask(
            message = message,
            subMessage = subMessage,
            layoutParams = instance.catDialogShowInfoViewParams,
            catDialogShowInfoView = instance.catDialogShowInfoView,
            windowManager = instance.getWindowManager(),
            isPaused = isPaused
        )
    }

    /**
     * VLM任务暂停（用户接管）
     */
    fun pauseTask(message: String) {
        resetTaskCompletionHintState()
        // 取消待执行的动画任务
        val instance = getLoadedInstance() ?: return
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
        resetTaskCompletionHintState()
        // 取消待执行的动画任务
        val instance = getLoadedInstance() ?: return false
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
        resetTaskCompletionHintState()
        getInstance()?.catView?.setViewState(DraggableViewState.MESSAGE)
        getInstance()?.catDialogLayoutView?.message(message)
        getInstance()?.collapseMenu()

    }

    fun showTaskCompletionHint(message: String, onClick: (() -> Unit)? = null): Boolean {
        val instance = dragBall ?: return false
        if (!instance.isAttachedToWindow) {
            return false
        }
        isTaskCompletionHintVisible = true
        taskCompletionHintClickAction = onClick
        instance.catView.post {
            instance.catView.visibility = View.VISIBLE
            instance.catDialogLayoutView.visibility = View.VISIBLE
            instance.catView.setViewState(DraggableViewState.MESSAGE)
            instance.catDialogLayoutView.message(message, keepVisibleUntilClosed = true)
            instance.collapseMenu()
            instance.bringCatToFront()
        }
        return true
    }

    fun consumeTaskCompletionHintClick(): Boolean {
        if (!isTaskCompletionHintVisible) {
            return false
        }
        val action = taskCompletionHintClickAction
        resetTaskCompletionHintState()
        dragBall?.let { instance ->
            instance.catView.post {
                if (instance.catDialogLayoutView.getCurrentState() == DraggableViewState.MESSAGE) {
                    instance.collapse()
                }
            }
        }
        action?.invoke()
        return true
    }

    fun clearTaskCompletionHint() {
        if (!isTaskCompletionHintVisible) {
            taskCompletionHintClickAction = null
            return
        }
        resetTaskCompletionHintState()
        val instance = dragBall ?: return
        instance.catView.post {
            if (instance.catDialogLayoutView.getCurrentState() == DraggableViewState.MESSAGE) {
                instance.collapse()
            }
        }
    }

    /**
     *  显示任务结束消息
     */
    fun finishDoingTask(message: String) {
        resetTaskCompletionHintState()
        // 取消待执行的动画任务
        val instance = getInstance() ?: return
        val catDialogShowInfoView = instance.catDialogShowInfoView
        val windowManager = instance.getWindowManager()
        catDialogShowInfoView.cancelAnimations()
        instance.catView.setViewState(DraggableViewState.MESSAGE)
        instance.catDialogLayoutView.finishDoingTask(message)
        catDialogShowInfoView.finishDoingTask()
        instance.catDialogShowInfoViewParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG
        if (catDialogShowInfoView.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(
                    instance.catDialogShowInfoView, instance.catDialogShowInfoViewParams
                )
            } catch (e: IllegalArgumentException) {
                OmniLog.e(TAG, "finishDoingTask updateViewLayout skipped: ${e.message}")
            }
        } else {
            OmniLog.d(TAG, "finishDoingTask skipped updateViewLayout because showInfoView is detached")
        }
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
        resetTaskCompletionHintState()
        getInstance()?.catView?.setViewState(DraggableViewState.SCHEDULED_TIP)
        getInstance()?.catDialogLayoutView?.showScheduledTip(
            closeTimer, doTaskTimer
        )
        getInstance()?.collapseMenu()
    }

    fun destroy() {
        resetTaskCompletionHintState()
        // 取消待执行的动画任务
        dragBall?.destroy()
        // 清除单例实例引用
        dragBall = null
    }

    private fun resetTaskCompletionHintState() {
        isTaskCompletionHintVisible = false
        taskCompletionHintClickAction = null
    }

    fun closeAllWithoutDoing() {
        val instance = dragBall ?: return
        val currentState = instance.catDialogLayoutView?.getCurrentState()
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
        val instance = dragBall ?: getInstance()
        if (instance == null) {
            onAnimEnd()
            return
        }
        val targetX = if (instance.isAttachedToRight) {
            (DisplayUtil.getScreenHeight() ?: (0)) - CatView.width.dpToPx() * 2 / 3
        } else {
            0 - CatView.width.dpToPx() / 3
        }
        instance.catViewLayoutParams.x = targetX
        if (!instance.catView.isAttachedToWindow) {
            OmniLog.d(TAG, "finish skipped animation because catView is detached")
            instance.isAttachedToWindow = false
            onAnimEnd()
            return
        }
        try {
            instance.updateViewLayoutIfAttached(instance.catView, instance.catViewLayoutParams)
            instance.catView.doFinish(onAnimEnd)
        } catch (e: IllegalArgumentException) {
            OmniLog.e(TAG, "finish updateViewLayout skipped: ${e.message}")
            onAnimEnd()
        } catch (e: BadTokenException) {
            OmniLog.e(TAG, "finish updateViewLayout BadTokenException: ${e.message}")
        } catch (e: Exception) {
            OmniLog.e(TAG, "finish failed: ${e.message}", e)
            instance.isAttachedToWindow = false
            onAnimEnd()
        }
    }

    fun cancelAnimation() {
        dragBall?.moveToScreenAnimator?.cancel()
        dragBall?.catView?.cancelAnimation()
    }

    fun moveToTop() {

        getInstance()?.startMove(Constant.CAT_SAFETY_MARGIN_TOP)
    }

    fun moveToCenter() {
        getInstance()?.startMove(DisplayUtil.getScreenHeight() / 2)
    }

    fun gone() {
        val instance = dragBall ?: return
        val windowManager = instance.getWindowManager()
        val isAttachedToWindow = instance.isAttachedToWindow

        // 设置 visibility 为 GONE
        instance.catView.visibility = View.GONE
        instance.catDialogLayoutView.visibility = View.GONE
        instance.catDialogShowInfoView.visibility = View.GONE

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
                if (instance.catDialogShowInfoView.getShowInfoViewVisibility() == View.VISIBLE) {
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
        instance.catView.visibility = View.VISIBLE
        instance.catDialogLayoutView.visibility = View.VISIBLE
        instance.catDialogShowInfoView.visibility =
            instance.catDialogShowInfoView.getShowInfoViewVisibility()
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
