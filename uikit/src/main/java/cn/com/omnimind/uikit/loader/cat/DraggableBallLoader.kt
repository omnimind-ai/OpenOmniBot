package cn.com.omnimind.uikit.loader.cat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import cn.com.omnimind.baselib.util.DisplayUtil
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.dpToPx

import cn.com.omnimind.uikit.api.callback.CatApi
import cn.com.omnimind.uikit.api.callback.CatLayoutApi
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.api.callback.MenuApi
import cn.com.omnimind.uikit.view.data.Constant
import cn.com.omnimind.uikit.view.data.WindowFlag
import cn.com.omnimind.uikit.loader.CancelClickLoader
import cn.com.omnimind.uikit.view.data.CatDialogStateData
import cn.com.omnimind.uikit.view.data.CatDialogViewState
import cn.com.omnimind.uikit.view.overlay.cat.CatDialogLayoutView
import cn.com.omnimind.uikit.view.overlay.cat.CatDialogMenuLayoutView
import cn.com.omnimind.uikit.view.overlay.cat.CatDialogShowInfoView
import cn.com.omnimind.uikit.view.overlay.cat.CatView
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


@SuppressLint("ClickableViewAccessibility")
class DraggableBallLoader(
    val context: Context,
    val useAccessibilityOverlay: Boolean,
    val catLayoutApi: CatLayoutApi?,
    val menuApi: MenuApi?,
    val catApi: CatApi?,
    val catStepLayoutApi: CatStepLayoutApi?
) : OnStateChangeListener, View.OnTouchListener, ComponentCallbacks2,
    GestureDetector.SimpleOnGestureListener() {


    private val TAG: String = "[OverlayLoader]"

    var isAttachedToRight = true
    private var isDragging = false
    private var lastY: Float = 0f
    private var lastX: Float = 0f
    private var touchStartY: Float = 0f
    private var touchStartX: Float = 0f
    private lateinit var windowManager: WindowManager
    var isAttachedToWindow: Boolean = false
    var catView: CatView = CatView(context)
    var catDialogLayoutView: CatDialogLayoutView =
        CatDialogLayoutView(context, catLayoutApi)
    var catViewLayoutParams: WindowManager.LayoutParams
    var catDialogLayoutViewLayoutParams: WindowManager.LayoutParams
    private var catDialogMenuLayoutView: CatDialogMenuLayoutView? = null
    private var catDialogMenuLayoutViewLayoutParams: WindowManager.LayoutParams? = null
    var catDialogShowInfoView: CatDialogShowInfoView =
        CatDialogShowInfoView(context, catStepLayoutApi)
    var catDialogShowInfoViewParams: WindowManager.LayoutParams
    var moveToScreenAnimator: ValueAnimator? = null
    private val gestureDetector = GestureDetector(context, this)
    private var hiddenForExternalActivity: Boolean = false
    private var externalActivityCatVisibility: Int = View.VISIBLE
    private var externalActivityDialogVisibility: Int = View.VISIBLE
    private var externalActivityShowInfoVisibility: Int = View.GONE
    private var externalActivityCatFlags: Int = WindowFlag.SCREEN_LOCK_FLAG
    private var externalActivityDialogFlags: Int = WindowFlag.SCREEN_LOCK_FLAG
    private var externalActivityShowInfoFlags: Int = WindowFlag.SCREEN_LOCK_FLAG

    // 记录 catDialogShowInfoView 的初始 Y 位置

    init {
        catView.setOnTouchListener(this)
        catView.setOnStateChangeListener(this)
        catDialogLayoutView.setOnTouchListener(this)
        catDialogLayoutView.setOnStateChangeListener(this)

        // 注册组件回调以监听配置更改（如屏幕旋转）
        context.registerComponentCallbacks(this)
        catDialogShowInfoViewParams = getParams(WindowFlag.SCREEN_LOCK_FLAG)
        catViewLayoutParams = getParams(WindowFlag.SCREEN_LOCK_FLAG)
        catDialogLayoutViewLayoutParams = getParams(WindowFlag.SCREEN_LOCK_FLAG)

        catDialogShowInfoViewParams.y = CatDialogStateData.getDoingTaskXY().second
        catDialogShowInfoViewParams.x = 0 // 临时值，将在视图测量后更新为居中
        catDialogLayoutViewLayoutParams.y =
            catViewLayoutParams.y - Constant.CAT_DIALOG_LAYOUT_MARGIN
        CatDialogStateData.catX = catViewLayoutParams.x
        CatDialogStateData.catY = catViewLayoutParams.y
        CatDialogStateData.isLeft = !isAttachedToRight

        // 设置动画控制器
//        catDialogShowInfoView.setAnimationController(this)
    }


    /**
     * load ball and move to screen edge
     * call loadBall() in start companion task
     */
    fun loadBall() {
        load()
        moveToScreenEdgeBack()
    }

    /**
     * add the cat view and dialog view to window manager
     * @return true if success
     */
    fun load(): Boolean {
        try {
            val type = if (useAccessibilityOverlay) {
                "accessibility"
            } else {
                "application"
            }
            if (isAttachedToWindow) {
                getWindowManager().updateViewLayout(catView, catViewLayoutParams)
                getWindowManager().updateViewLayout(
                    catDialogLayoutView, catDialogLayoutViewLayoutParams
                )
                updateCatDialogShowInfoViewPosition()
                getWindowManager().updateViewLayout(
                    catDialogShowInfoView, catDialogShowInfoViewParams
                )
            } else {
                if (catDialogShowInfoView.isAttachedToWindow) {
                    getWindowManager().updateViewLayout(
                        catDialogShowInfoView, catDialogShowInfoViewParams
                    )
                } else {
                    getWindowManager().addView(catDialogShowInfoView, catDialogShowInfoViewParams)

                }
                getWindowManager().addView(catView, catViewLayoutParams)
                getWindowManager().addView(catDialogLayoutView, catDialogLayoutViewLayoutParams)
                isAttachedToWindow = true
                OmniLog.d(TAG, "load: draggable overlays added with $type overlay")

                // 初始状态下隐藏 catDialogShowInfoView，避免拦截底部按钮的触摸事件
                // 只有在真正需要显示信息时（如 doingTask、pauseTask 等）才会设置为 VISIBLE
                catDialogShowInfoView.visibility = View.GONE

                // 视图添加后，延迟更新位置以确保视图已测量完成
                catDialogShowInfoView.post {
                    // 等待视图完成布局
                    catDialogShowInfoView.post {
                        updateCatDialogShowInfoViewPosition()
                        if (isAttachedToWindow) {
                            getWindowManager().updateViewLayout(
                                catDialogShowInfoView, catDialogShowInfoViewParams
                            )
                        }
                    }
                }
            }
            return true
        } catch (e: BadTokenException) {
            OmniLog.e(TAG, "load BadTokenException (token null): ${e.message}")
            return false
        } catch (e: Exception) {
            OmniLog.e(TAG, "loadScreenMask: ${e.message}")
            return false
        }

    }


    /**
     * 更新 catDialogShowInfoView 的水平居中位置和固定尺寸
     * 根据要求，方形视图的宽度固定为屏幕宽度-120dp，高度固定为68dp
     */
    private fun updateCatDialogShowInfoViewPosition() {

        // 计算固定的方形尺寸：宽度=屏幕宽度-60dp，高度=68dp
        val fixedWidth = (DisplayUtil.getScreenWidth() - 60.dpToPx()).coerceAtLeast(0)
        val fixedHeight = 68.dpToPx()
        // 设置固定尺寸
        catDialogShowInfoViewParams.width = fixedWidth
        catDialogShowInfoViewParams.height = fixedHeight

        // 计算水平居中位置
        if (fixedWidth > 0 && DisplayUtil.getScreenWidth() > 0) {
            catDialogShowInfoViewParams.x = (DisplayUtil.getScreenWidth() - fixedWidth) / 2
        }
    }

    /**
     * collapse the dialog view
     */
    fun collapse() {
        CancelClickLoader.cancelIntercepting()
        catView.setViewState(DraggableViewState.COLLAPSED)
        catDialogLayoutView?.collapse()
        collapseMenu()

    }

    /**
     * collapse the menu view
     */
    fun collapseMenu() {
        try {
            // 检查是否已附加到窗口，避免在无效状态下操作
            if (isAttachedToWindow) {
                CancelClickLoader.cancelIntercepting()
                catDialogMenuLayoutView?.setViewToCollapsed()
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "collapseMenu failed: ${e.message}", e)
        }
    }


    /**
     * close the dialog view but not change the state
     */
    fun collapseNotChangeState() {
        CancelClickLoader.cancelIntercepting()
        catView.setViewStateToDialogState()
        // 如果当前状态是 MESSAGE，不关闭消息，让消息自己定时关闭
        val currentState = catDialogLayoutView.getCurrentState()
        if (currentState != DraggableViewState.MESSAGE) {
            catDialogLayoutView.collapse()
        }
    }


    var singleButtonDeferred: CompletableDeferred<Boolean>? = null

    fun move(startX: Int, startY: Int, endX: Int, endY: Int) {
        singleButtonDeferred = CompletableDeferred<Boolean>()

        OmniLog.i("catViewMove", "startX: $startX, startY: $startY, endX: $endX, endY: $endY")
        // 判断起始点和终点是否在catView和catDialogLayoutView的范围内
        val catViewParams = catView.layoutParams as WindowManager.LayoutParams
        val catDialogParams = catDialogLayoutView.layoutParams as WindowManager.LayoutParams
        val catDialogShowInfoParams = catDialogShowInfoViewParams

        val isStartInCatViewRange =
            startX >= catViewParams.x && startX <= catViewParams.x + catView.width && startY >= catViewParams.y && startY <= catViewParams.y + catView.height

        val isEndInCatViewRange =
            endX >= catViewParams.x && endX <= catViewParams.x + catView.width && endY >= catViewParams.y && endY <= catViewParams.y + catView.height

        val isStartInCatDialogRange =
            startX >= catDialogParams.x && startX <= catDialogParams.x + catDialogLayoutView.width && startY >= catDialogParams.y && startY <= catDialogParams.y + catDialogLayoutView.height

        val isEndInCatDialogRange =
            endX >= catDialogParams.x && endX <= catDialogParams.x + catDialogLayoutView.width && endY >= catDialogParams.y && endY <= catDialogParams.y + catDialogLayoutView.height

        // 判断起始点和终点是否在 catDialogShowInfoView 的范围内
        val isStartInCatDialogShowInfoRange =
            startX >= catDialogShowInfoParams.x && startX <= catDialogShowInfoParams.x + catDialogShowInfoView.width &&
                    startY >= catDialogShowInfoParams.y && startY <= catDialogShowInfoParams.y + catDialogShowInfoView.height

        val isEndInCatDialogShowInfoRange =
            endX >= catDialogShowInfoParams.x && endX <= catDialogShowInfoParams.x + catDialogShowInfoView.width &&
                    endY >= catDialogShowInfoParams.y && endY <= catDialogShowInfoParams.y + catDialogShowInfoView.height

        // 如果起始点或终点在 catDialogShowInfoView 范围内，让它向上移动一个自身高度的距离
        if (isStartInCatDialogShowInfoRange || isEndInCatDialogShowInfoRange) {
            val currentY = catDialogShowInfoParams.y
            // 如果高度为0，尝试使用 measuredHeight
            val targetY = max(0, currentY - 78.dpToPx())

            OmniLog.d(TAG, "Moving catDialogShowInfoView: currentY=$currentY, targetY=$targetY")

            val showInfoAnimator = ObjectAnimator.ofInt(currentY, targetY)
            showInfoAnimator.duration = Constant.NORMAL_ANIM_TIME
            showInfoAnimator.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                catDialogShowInfoParams.y = value
                if (catDialogShowInfoView.isAttachedToWindow) {
                    getWindowManager().updateViewLayout(
                        catDialogShowInfoView,
                        catDialogShowInfoParams
                    )
                }
            }
            showInfoAnimator.start()
            showInfoAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    singleButtonDeferred?.complete(true)
                }
            })
            // 如果起始点或终点在任一视图范围内，则执行移动动画
        } else if (isStartInCatViewRange || isEndInCatViewRange || isStartInCatDialogRange || isEndInCatDialogRange) {
            OmniLog.i("catViewMove", "catViewMove")
            // 获取当前的位置
            val currentCatViewY = catViewParams.y
            val currentCatDialogY = catDialogParams.y

            // 智能判断移动方向，优先选择能够容纳小猫的区域
            val catHeight = catView.height
            val availableSpaceAbove = startY - catHeight
            val availableSpaceBelow = DisplayUtil.getScreenWidth() - endY - catHeight

            // 判断向上移动还是向下移动
            val shouldMoveUp = availableSpaceAbove > availableSpaceBelow

            // 根据判断结果计算目标位置 - 修改为直接移到最上方或最下方
            val targetCatViewY = if (shouldMoveUp) {
                // 向上移动到屏幕最上方
                0 + Constant.CAT_SAFETY_MARGIN_TOP
            } else {
                // 向下移动到屏幕最下方
                DisplayUtil.getScreenHeight() - catView.height - Constant.CAT_SAFETY_MARGIN_BOTTOM
            }

            // 应用最大高度限制，考虑视图本身的高度，确保不会移出屏幕
            // 保留100像素的安全距离，防止小猫完全贴到屏幕边缘
            val maxY =
                DisplayUtil.getScreenHeight() - catView.height - Constant.CAT_SAFETY_MARGIN_BOTTOM
            val finalCatViewY = min(max(targetCatViewY, Constant.CAT_SAFETY_MARGIN_TOP), maxY)
            // 创建动画，在500ms内完成移动
            val catViewAnimator = ObjectAnimator.ofInt(currentCatViewY, finalCatViewY)
            catViewAnimator.duration = Constant.NORMAL_ANIM_TIME

            val catDialogAnimator = ObjectAnimator.ofInt(
                currentCatDialogY, finalCatViewY - Constant.CAT_DIALOG_LAYOUT_MARGIN
            )
            catDialogAnimator.duration = Constant.NORMAL_ANIM_TIME
            catViewAnimator.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                val params = catView.layoutParams as WindowManager.LayoutParams
                params.y = value
                CatDialogStateData.catY = params.y

                getWindowManager().updateViewLayout(catView, params)
            }

            catDialogAnimator.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                // 同步更新dialog的位置
                val dialogParams = catDialogLayoutView.layoutParams as WindowManager.LayoutParams
                dialogParams.y = value
                getWindowManager().updateViewLayout(catDialogLayoutView, dialogParams)
            }

            catViewAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    singleButtonDeferred?.complete(true)
                }
            })

            catViewAnimator.start()
            catDialogAnimator.start()
        } else {
            // 不需要移动小猫，直接完成
            singleButtonDeferred?.complete(true)
        }
        collapseMenu()

    }


    suspend fun moveFinish(): Boolean {
        return singleButtonDeferred!!.await()
    }

    fun startMove(targetY: Int) {
        // 获取当前的位置
        val currentCatViewY = catViewLayoutParams.y
        val currentCatDialogY = catDialogLayoutViewLayoutParams.y

        // 应用最大高度限制，考虑视图本身的高度，确保不会移出屏幕
        // 保留100像素的安全距离，防止小猫完全贴到屏幕边缘
        val maxY =
            DisplayUtil.getScreenHeight() - catView.height - Constant.CAT_SAFETY_MARGIN_BOTTOM
        val finalCatViewY = min(max(targetY, Constant.CAT_SAFETY_MARGIN_TOP), maxY)
        // 创建动画，在500ms内完成移动
        val catViewAnimator = ObjectAnimator.ofInt(currentCatViewY, finalCatViewY)
        catViewAnimator.duration = Constant.NORMAL_ANIM_TIME
        val catDialogAnimator = ObjectAnimator.ofInt(
            currentCatDialogY, finalCatViewY - Constant.CAT_DIALOG_LAYOUT_MARGIN
        )
        catDialogAnimator.duration = Constant.NORMAL_ANIM_TIME
        catViewAnimator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            val params = catView.layoutParams as WindowManager.LayoutParams
            params.y = value
            CatDialogStateData.catY = params.y

            getWindowManager().updateViewLayout(catView, params)
        }
        catDialogAnimator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            // 同步更新dialog的位置
            val dialogParams = catDialogLayoutView.layoutParams as WindowManager.LayoutParams
            dialogParams.y = value
            getWindowManager().updateViewLayout(catDialogLayoutView, dialogParams)
        }


        catViewAnimator.start()
        catDialogAnimator.start()
        collapseMenu()

    }

    fun showMenu() {
        catDialogMenuLayoutView =
            CatDialogMenuLayoutView(context, menuApi)
        catDialogMenuLayoutView?.visibility = View.VISIBLE
        catDialogMenuLayoutView?.setAttachmentSideView(!isAttachedToRight)
        catDialogMenuLayoutViewLayoutParams = getParams(WindowFlag.SCREEN_LOCK_FLAG)
        catDialogMenuLayoutViewLayoutParams?.y = catViewLayoutParams.y - 60.dpToPx()
        if (isAttachedToRight) {
            catDialogMenuLayoutViewLayoutParams?.x = catViewLayoutParams.x - 100.dpToPx()
        } else {
            catDialogMenuLayoutViewLayoutParams?.x = catViewLayoutParams.x + 0.dpToPx()
        }
        catDialogMenuLayoutView?.setOnCloseFinishListener {
            try {
                getWindowManager().removeView(catDialogMenuLayoutView)
                catDialogMenuLayoutViewLayoutParams = null
            } catch (e: Exception) {

            }
        }
        getWindowManager().addView(catDialogMenuLayoutView, catDialogMenuLayoutViewLayoutParams)
        catDialogMenuLayoutView?.showOptionsMenu()
    }

    /**
     * 将小猫和气泡重新添加到 WindowManager 的最前面
     * 使其获得最高的 Z-order，优先响应点击事件
     *
     * 注意：此方法会中断正在进行的动画，建议在动画完成后或静止状态下调用
     */
    fun bringCatToFront() {
        try {
            if (!isAttachedToWindow) {
                OmniLog.w(TAG, "bringCatToFront: 视图未附加到窗口，跳过操作")
                return
            }

            // 取消正在进行的动画，避免冲突
            moveToScreenAnimator?.cancel()

            // 先移除
            getWindowManager().removeView(catView)
            getWindowManager().removeView(catDialogLayoutView)

            // 再重新添加，这样就会在最上层
            getWindowManager().addView(catView, catViewLayoutParams)
            getWindowManager().addView(catDialogLayoutView, catDialogLayoutViewLayoutParams)

            OmniLog.d(TAG, "bringCatToFront: 小猫和气泡已提升到最前面")
        } catch (e: Exception) {
            OmniLog.e(TAG, "bringCatToFront failed: ${e.message}", e)
            // 如果操作失败，尝试恢复状态
            try {
                if (!catView.isAttachedToWindow) {
                    getWindowManager().addView(catView, catViewLayoutParams)
                }
                if (!catDialogLayoutView.isAttachedToWindow) {
                    getWindowManager().addView(catDialogLayoutView, catDialogLayoutViewLayoutParams)
                }
            } catch (recoverException: Exception) {
                OmniLog.e(TAG, "bringCatToFront recover failed: ${recoverException.message}")
            }
        }
    }

    fun hideForExternalActivity(): Boolean {
        if (hiddenForExternalActivity) {
            return true
        }
        val hasAttachedOverlay =
            catView.isAttachedToWindow ||
                catDialogLayoutView.isAttachedToWindow ||
                catDialogShowInfoView.isAttachedToWindow
        if (!hasAttachedOverlay) {
            return false
        }

        externalActivityCatVisibility = catView.visibility
        externalActivityDialogVisibility = catDialogLayoutView.visibility
        externalActivityShowInfoVisibility = catDialogShowInfoView.visibility
        externalActivityCatFlags = catViewLayoutParams.flags
        externalActivityDialogFlags = catDialogLayoutViewLayoutParams.flags
        externalActivityShowInfoFlags = catDialogShowInfoViewParams.flags

        return try {
            moveToScreenAnimator?.cancel()
            catDialogShowInfoView.cancelAnimations()

            catView.visibility = View.GONE
            catDialogLayoutView.visibility = View.GONE
            catDialogShowInfoView.visibility = View.GONE

            catViewLayoutParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG
            catDialogLayoutViewLayoutParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG
            catDialogShowInfoViewParams.flags = WindowFlag.SCREEN_UNLOCK_FLAG

            updateViewLayoutIfAttached(catView, catViewLayoutParams)
            updateViewLayoutIfAttached(catDialogLayoutView, catDialogLayoutViewLayoutParams)
            updateViewLayoutIfAttached(catDialogShowInfoView, catDialogShowInfoViewParams)

            hiddenForExternalActivity = true
            OmniLog.d(TAG, "hideForExternalActivity: draggable overlays hidden")
            true
        } catch (e: Exception) {
            OmniLog.e(TAG, "hideForExternalActivity failed: ${e.message}", e)
            false
        }
    }

    fun restoreAfterExternalActivity(): Boolean {
        if (!hiddenForExternalActivity) {
            return false
        }
        return try {
            catViewLayoutParams.flags = externalActivityCatFlags
            catDialogLayoutViewLayoutParams.flags = externalActivityDialogFlags
            catDialogShowInfoViewParams.flags = externalActivityShowInfoFlags

            updateViewLayoutIfAttached(catView, catViewLayoutParams)
            updateViewLayoutIfAttached(catDialogLayoutView, catDialogLayoutViewLayoutParams)
            updateViewLayoutIfAttached(catDialogShowInfoView, catDialogShowInfoViewParams)

            catView.visibility = externalActivityCatVisibility
            catDialogLayoutView.visibility = externalActivityDialogVisibility
            catDialogShowInfoView.visibility = externalActivityShowInfoVisibility

            hiddenForExternalActivity = false
            OmniLog.d(TAG, "restoreAfterExternalActivity: draggable overlays restored")
            true
        } catch (e: Exception) {
            OmniLog.e(TAG, "restoreAfterExternalActivity failed: ${e.message}", e)
            false
        }
    }

    fun updateViewLayoutIfAttached(view: View, params: WindowManager.LayoutParams) {
        if (view.isAttachedToWindow) {
            getWindowManager().updateViewLayout(view, params)
        }
    }

    fun destroy() {
        try {
            // 取消动画
            catDialogShowInfoView.cancelAnimations()
            moveToScreenAnimator?.cancel()
            catView.visibility = View.GONE
            catDialogLayoutView.visibility = View.GONE
            catDialogShowInfoView.visibility = View.GONE
            getWindowManager().removeView(catView)
            getWindowManager().removeView(catDialogLayoutView)
            getWindowManager().removeView(catDialogShowInfoView)
            isAttachedToWindow = false
            hiddenForExternalActivity = false
            // 取消注册组件回调
            context.unregisterComponentCallbacks(this)
        } catch (e: Exception) {
            OmniLog.e(TAG, "destroy: ${e.message}")
        }

    }

    /**
     * move to screen edge
     * only called when catView is attached to window or dragging end
     */
    private fun moveToScreenEdgeBack() {
        val targetX = if (isAttachedToRight) {
            DisplayUtil.getScreenWidth() - CatView.width.dpToPx() * 2 / 3
        } else {
            0 - CatView.width.dpToPx() / 3
        }
        moveToScreenAnimator?.cancel()
        catViewLayoutParams.x = targetX
        CatDialogStateData.catX = catViewLayoutParams.x
        if (catView.isAttachedToWindow) {
            getWindowManager().updateViewLayout(catView, catViewLayoutParams)
        }
        collapseNotChangeState()
        collapseMenu()

    }

    /**
     * 监听配置变化（如屏幕旋转）
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        // 屏幕方向改变时更新屏幕尺寸
        catViewLayoutParams = getParams(WindowFlag.SCREEN_LOCK_FLAG)
        catDialogLayoutViewLayoutParams = getParams(WindowFlag.SCREEN_LOCK_FLAG)
        // 更新 catDialogShowInfoView 的位置和初始位置
        catDialogShowInfoViewParams.y = CatDialogStateData.getDoingTaskXY().second
        updateCatDialogShowInfoViewPosition()
        loadBall()

    }

    override fun onLowMemory() {
        // 内存不足时的处理
    }

    override fun onTrimMemory(level: Int) {
        // 内存清理时的处理
    }


    /**
     * 悬浮窗长按已禁用。
     */
    override fun onLongPress(e: MotionEvent) {
        // 悬浮窗长按功能已移除，保留空实现避免触发菜单或任务中断。
    }

    /**
     * 单击展开小猫
     */
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (catDialogLayoutView.isPointInsideMessageView(e.rawX, e.rawY) &&
            DraggableBallInstance.consumeTaskCompletionHintClick()
        ) {
            collapseMenu()
            return true
        }
        if (catView.getViewState() == DraggableViewState.DOING_TASK) {
            collapseMenu()
            catLayoutApi?.onOpenHomeParam("/home/chat", false)
            return true
        }
        if (catView.getViewState() != DraggableViewState.COLLAPSED) {
            collapse()
            catApi?.onCloseChatBotDialog()
            return true
        } else {
            OmniLog.i("onSingleTapConfirmed", "strat showChatBotDialog")
            catView.setViewState(DraggableViewState.SHOW_CHAT)
            catApi?.onCatClick(
                catDialogLayoutViewLayoutParams.x, catDialogLayoutViewLayoutParams.y
            )
        }
        collapseMenu()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        catApi?.onCatDoubleClick()
        return true
    }

    @SuppressLint("SuspiciousIndentation")
    fun getWindowManager(): WindowManager {
        if (!this::windowManager.isInitialized) windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager
    }

    fun getParams(flagsValue: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (useAccessibilityOverlay) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = flagsValue
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = DisplayUtil.getScreenWidth()
            y = DisplayUtil.getScreenHeight() / 2 - catView.measuredHeight / 2
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.rawX - touchStartX)
                val dy = abs(event.rawY - touchStartY)

                // 开始拖拽检测
                if (!isDragging && (dx > Constant.SLIDING_THRESHOLD || dy > Constant.SLIDING_THRESHOLD)) {
                    isDragging = true
                    catView.startDragging()
                    catDialogLayoutView.startDragging()
                }

                // 拖拽过程中更新位置
                if (isDragging) {
                    collapseMenu()
                    updateDragPosition(event)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    moveToScreenEdgeBack()
                }
                // 非拖拽状态下交给 GestureDetector 处理点击和长按
            }
        }

        // 非拖拽状态下的事件交给 GestureDetector 处理
        return gestureDetector.onTouchEvent(event)
    }

    private fun updateDragPosition(event: MotionEvent) {
        // 与原代码相同的拖拽位置更新逻辑
        catViewLayoutParams.x = (event.rawX - catView.width / 2).toInt()
        var y = max(
            (event.rawY - catDialogLayoutView.height / 2).toInt(), Constant.CAT_SAFETY_MARGIN_TOP
        )
        y = min(y, DisplayUtil.getScreenHeight() - Constant.CAT_SAFETY_MARGIN_BOTTOM)
        catViewLayoutParams.y = (y - catView.height / 2).toInt()

        catDialogLayoutViewLayoutParams.y =
            catViewLayoutParams.y - Constant.CAT_DIALOG_LAYOUT_MARGIN

        val params = catView.layoutParams as WindowManager.LayoutParams
        val measuredWidth = catView.measuredWidth
        val centerX = params.x + measuredWidth / 2
        isAttachedToRight = centerX > DisplayUtil.getScreenWidth() / 2
        CatDialogStateData.catX = catViewLayoutParams.x
        CatDialogStateData.catY = catViewLayoutParams.y
        CatDialogStateData.isLeft = !isAttachedToRight
        catView.setAttachmentSideView(!isAttachedToRight)
        catDialogLayoutView.setAttachmentSideView(!isAttachedToRight)
        getWindowManager().updateViewLayout(catView, params)
        getWindowManager().updateViewLayout(catDialogLayoutView, catDialogLayoutViewLayoutParams)
        if (CatDialogStateData.viewState == CatDialogViewState.USER_INFO_HINT) {
            val (x, y) = CatDialogStateData.getUserInfoXY()
            catDialogShowInfoViewParams.x = x
            catDialogShowInfoViewParams.y = y
            getWindowManager().updateViewLayout(catDialogShowInfoView, catDialogShowInfoViewParams)

        }


    }

    override fun onStateChange(state: DraggableViewState) {

        if (isAttachedToRight) {
            catDialogLayoutViewLayoutParams.x =
                DisplayUtil.getScreenWidth() - catDialogLayoutView.measuredWidth
        } else {
            catDialogLayoutViewLayoutParams.x = 0
        }
        if (state == DraggableViewState.COLLAPSED) {
            CancelClickLoader.cancelIntercepting()
        }
        // 检查视图是否已附加到窗口，避免在视图未附加时更新布局导致异常
        if (isAttachedToWindow && catDialogLayoutView.isAttachedToWindow) {
            try {
                getWindowManager().updateViewLayout(
                    catDialogLayoutView,
                    catDialogLayoutViewLayoutParams
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to update view layout in onStateChange: ${e.message}", e)
            }
        }

    }

    override fun onCatLayoutDialogMeasure(w: Int, h: Int) {

        if (isAttachedToRight) {
            catDialogLayoutViewLayoutParams.x =
                DisplayUtil.getScreenWidth() - catDialogLayoutView.measuredWidth

        } else {
            catDialogLayoutViewLayoutParams.x = 0
        }
        getWindowManager().updateViewLayout(catDialogLayoutView, catDialogLayoutViewLayoutParams)

    }


    /**
     * 将 catDialogShowInfoView 还原到初始位置
     * 如果 catDialogShowInfoView 不在初始位置，则执行动画还原
     */
    fun moveBack() {
        val (_, currentY) = CatDialogStateData.getDoingTaskXY()
        // 如果当前位置与初始位置不同，则还原
        if (currentY != catDialogShowInfoViewParams.y) {
            OmniLog.d(
                TAG,
                "Moving catDialogShowInfoView back: currentY=$ catDialogShowInfoViewParams.y, initialY=$currentY"
            )

            val showInfoAnimator = ObjectAnimator.ofInt(catDialogShowInfoViewParams.y, currentY)
            showInfoAnimator.duration = Constant.NORMAL_ANIM_TIME
            showInfoAnimator.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                catDialogShowInfoViewParams.y = value
                if (catDialogShowInfoView.isAttachedToWindow) {
                    getWindowManager().updateViewLayout(
                        catDialogShowInfoView,
                        catDialogShowInfoViewParams
                    )
                }
            }
            showInfoAnimator.start()
        } else {
            OmniLog.d(TAG, "catDialogShowInfoView is already at initial position: $currentY")
        }
    }
}

interface OnStateChangeListener {
    fun onStateChange(state: DraggableViewState)
    fun onCatLayoutDialogMeasure(w: Int, h: Int)
}
