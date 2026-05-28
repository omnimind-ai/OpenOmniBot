package cn.com.omnimind.uikit.view.layout

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.InvalidDisplayException
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import cn.com.omnimind.baselib.util.ShapeBuilder
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.view.data.CatDialogStateData
import cn.com.omnimind.uikit.view.data.CatDialogViewState
import cn.com.omnimind.uikit.view.overlay.cat.CatDialogShowInfoView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ShowInfoView - 显示信息视图
 * 支持从矩形方框到圆形状态条的流畅动画转换
 */
class ShowInfoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : BaseFrameLayout(context, attrs, defStyleAttr) {
    // 边框颜色类型
    var borderColorType: GradientBorderContainerView.BorderColor =
        GradientBorderContainerView.BorderColor.BLUE
        set(value) {
            field = value
            gradientBorderContainer?.setBorderColorType(value)
        }

    // 子视图引用
    private var gradientBorderContainer: GradientBorderContainerView? = null
    private var gradientTextView: GradientTextView? = null
    private var llTakeOver: View? = null
    private var llResume: View? = null
    private var llStop: View? = null
    private var executingTextView: TextView? = null
    private var executingTextViewContainer: View? = null // executingTextView 所在的容器
    private var contentContainer: View? = null
    private var innerRelativeLayout: View? = null
    private var bottomContentLayout: View? = null // 包含 contentContainer 的 RelativeLayout
    private var llResumeTextView: TextView? = null // llResume 中的文字
    private var llTakeOverTextView: TextView? = null
    private var llStopTextView: TextView? = null
    private var ivResume: ImageView? = null

    var delayTask: CoroutineScope = CoroutineScope(Dispatchers.IO)

    var isUserActionCompleted: CompletableDeferred<Boolean>? = null
    var catStepLayoutApi: CatStepLayoutApi? = null
    var readyDoingTaskAnimator: AnimatorSet? = null

    init {
        // 从XML加载布局
        LayoutInflater.from(context).inflate(R.layout.layout_show_info_view, this, true)
        // 初始化视图引用
        gradientBorderContainer = findViewById(R.id.gradientBorderContainer)
        gradientTextView = findViewById(R.id.gradientTextView)
        llTakeOver = findViewById(R.id.llTakeOver)
        llResume = findViewById(R.id.llResume)
        llStop = findViewById(R.id.llStop)
        executingTextView = findViewById(R.id.executingTextView)
        // 通过id获取 executingTextView 的父容器（LinearLayout）
        executingTextViewContainer = findViewById(R.id.executingTextViewContainer)
        contentContainer = findViewById(R.id.contentContainer)
        // 通过id获取包含 contentContainer 的 RelativeLayout（底部内容容器）
        bottomContentLayout = findViewById(R.id.bottomContentLayout)
        // 通过id获取内部 RelativeLayout（rlContent）
        innerRelativeLayout = findViewById(R.id.rlContent)
        // 通过id获取 llResume 中的 TextView（"继续"文字）
        llResumeTextView = findViewById(R.id.llResumeTextView)
        llTakeOverTextView = findViewById(R.id.llTakeOverTextView)
        llStopTextView = findViewById(R.id.llStopTextView)
        //收起后展示的继续按钮
        ivResume = findViewById(R.id.ivResume)
//        ShapeBuilder.roundedRectangle("#F3F4F5".toColorInt(), 30.dpToPxF()).applyTo(ivResume!!)
        // 设置初始值
        gradientBorderContainer?.setBorderColorType(borderColorType)
        // 设置按钮点击监听
        llTakeOver?.setOnClickListener {
            catStepLayoutApi?.onPauseClick()
        }
        llStop?.setOnClickListener {
            val deferred = isUserActionCompleted
            if (deferred != null) {
                // 只调用 complete，不要立即设置为 null
                // 让 await() 在 finally 块中处理清理
                try {
                    deferred.complete(false)
                } catch (e: IllegalStateException) {
                    // 如果已经完成，忽略异常
                }
                return@setOnClickListener
            }
            catStepLayoutApi?.onStopClick()

        }
        ivResume?.setOnClickListener {
            val deferred = isUserActionCompleted
            if (deferred != null) {
                // 只调用 complete，不要立即设置为 null
                // 让 await() 在 finally 块中处理清理
                try {
                    deferred.complete(true)
                } catch (e: IllegalStateException) {
                    // 如果已经完成，忽略异常
                }
                return@setOnClickListener
            }
            catStepLayoutApi?.onResumeClick()
        }
        llResume?.setOnClickListener {
            val deferred = isUserActionCompleted
            if (deferred != null) {
                // 只调用 complete，不要立即设置为 null
                // 让 await() 在 finally 块中处理清理
                try {
                    deferred.complete(true)
                } catch (e: IllegalStateException) {
                    // 如果已经完成，忽略异常
                }
                return@setOnClickListener
            }
            catStepLayoutApi?.onResumeClick()
        }
    }

    fun pause(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        applyNormalActionStyle()
        bottomContentLayout?.visibility = VISIBLE
        ivResume?.visibility = GONE
        llResume?.visibility = VISIBLE
        llTakeOver?.visibility = GONE
        llStop?.visibility = VISIBLE
        if (CatDialogStateData.viewState == CatDialogViewState.USER_INFO) {
            return
        }
        if (CatDialogStateData.viewState == CatDialogViewState.USER_INFO_HINT) {
            return
        }
        clearAllAminAndDelay()
        CatDialogStateData.viewState = CatDialogViewState.USER_INFO
        delayTask.launch {
            delay(2000)

            withContext(Dispatchers.Main) {
                gradientBorderContainer?.cornerRadiusProgress = 1.0f
                innerRelativeLayout?.setPadding(10.dpToPx(), 3.dpToPx(), 10.dpToPx(), 3.dpToPx())
                bottomContentLayout?.visibility = GONE
                ivResume?.visibility = VISIBLE
                CatDialogStateData.viewState = CatDialogViewState.USER_INFO_HINT
                val (startX, startY) = CatDialogStateData.getDoingTaskXY()
                val (endX, endY) = CatDialogStateData.getUserInfoXY()
                val (startW, startH) = CatDialogStateData.getTaskDoingWH()
                val (endW, endH) = CatDialogStateData.getUserInfoWH()
                post {
                    // 创建宽度动画，使用 OvershootInterpolator 让动画有轻微的弹跳效果，更生动自然
                    val widthAnimator = ValueAnimator.ofInt(startW, endW).apply {
                        duration = 1000L // 动画时长 500ms
                        interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹，与项目中其他动画保持一致
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            val params = layoutParams
                            params.width = animatedValue
                            catDialogShowInfoViewParams.width = animatedValue

                        }
                    }
                    // 创建高度动画，使用相同的插值器保持动画协调
                    val heightAnimator = ValueAnimator.ofInt(startH, endH).apply {
                        duration = 1000L // 动画时长 500ms
                        interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            val params = layoutParams
                            params.height = animatedValue
                            catDialogShowInfoViewParams.height = animatedValue
                        }
                    }
                    // 创建x坐标动画
                    val xAnimator = ValueAnimator.ofInt(startX, endX).apply {
                        duration = 1000L // 动画时长 500ms
                        interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            catDialogShowInfoViewParams.x = animatedValue

                        }
                    }
                    // 创建x坐标动画
                    val yAnimator = ValueAnimator.ofInt(startY, endY).apply {
                        duration = 1000L // 动画时长 500ms
                        interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹
                        addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            catDialogShowInfoViewParams.y = animatedValue

                        }
                    }
                    val dongingAnimator = ValueAnimator.ofInt(1, 100).apply {
                        duration = 1000L // 动画时长 500ms
                        addUpdateListener { animation ->
                            updateOverlayLayoutIfAttached(
                                windowManager,
                                catDialogShowInfoView,
                                catDialogShowInfoViewParams
                            )
                        }
                    }
                    // 同时执行宽度和高度动画
                    readyDoingTaskAnimator = AnimatorSet()
                    readyDoingTaskAnimator!!.playTogether(
                        widthAnimator, heightAnimator, xAnimator, yAnimator, dongingAnimator
                    )
                    readyDoingTaskAnimator!!.start()
                }
            }
        }

    }

    suspend fun userAction(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ): Boolean {
        withContext(Dispatchers.Main) {
            pause(
                catDialogShowInfoView,
                catDialogShowInfoViewParams,
                windowManager
            )
        }
        isUserActionCompleted = CompletableDeferred()
        val deferred = isUserActionCompleted
        return if (deferred != null) {
            try {
                deferred.await()
            } finally {
                // 确保在 await 完成后才设置为 null
                if (isUserActionCompleted == deferred) {
                    isUserActionCompleted = null
                }
            }
        } else {
            false
        }
    }

    fun doingTask(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewLayoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        setDoing(catDialogShowInfoView, catDialogShowInfoViewLayoutParams, windowManager, true)
    }


    fun doingAnimatorWithWH(startW: Int, startH: Int, endW: Int, endH: Int) {
        post {
            visibility = VISIBLE
            // 创建宽度动画，使用 OvershootInterpolator 让动画有轻微的弹跳效果，更生动自然
            val widthAnimator = ValueAnimator.ofInt(startW, endW).apply {
                duration = 1000L // 动画时长 500ms
                interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹，与项目中其他动画保持一致
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.width = animatedValue
                    layoutParams = params
                }
            }
            // 创建高度动画，使用相同的插值器保持动画协调
            val heightAnimator = ValueAnimator.ofInt(startH, endH).apply {
                duration = 1000L // 动画时长 500ms
                interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.height = animatedValue
                    layoutParams = params
                }
            }
            // 同时执行宽度和高度动画
            readyDoingTaskAnimator = AnimatorSet()
            readyDoingTaskAnimator!!.playTogether(widthAnimator, heightAnimator)
            readyDoingTaskAnimator!!.start()
        }
    }

    fun doingAnimatorWithWHXY(
        startW: Int,
        startH: Int,
        endW: Int,
        endH: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewLayoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        post {
            // 创建宽度动画，使用 OvershootInterpolator 让动画有轻微的弹跳效果，更生动自然
            val widthAnimator = ValueAnimator.ofInt(startW, endW).apply {
                duration = 1000L // 动画时长 500ms
                interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹，与项目中其他动画保持一致
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.width = animatedValue
                    catDialogShowInfoViewLayoutParams.width = animatedValue

                }
            }
            // 创建高度动画，使用相同的插值器保持动画协调
            val heightAnimator = ValueAnimator.ofInt(startH, endH).apply {
                duration = 1000L // 动画时长 500ms
                interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    val params = layoutParams
                    params.height = animatedValue
                    catDialogShowInfoViewLayoutParams.height = animatedValue
                }
            }
            // 创建x坐标动画
            val xAnimator = ValueAnimator.ofInt(startX, endX).apply {
                duration = 1000L // 动画时长 500ms
                interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    catDialogShowInfoViewLayoutParams.x = animatedValue

                }
            }
            // 创建x坐标动画
            val yAnimator = ValueAnimator.ofInt(startY, endY).apply {
                duration = 1000L // 动画时长 500ms
                interpolator = OvershootInterpolator(1.2f) // 超调插值器，轻微超过目标值再回弹
                addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    catDialogShowInfoViewLayoutParams.y = animatedValue

                }
            }
            val dongingAnimator = ValueAnimator.ofInt(1, 100).apply {
                duration = 1000L // 动画时长 500ms
                addUpdateListener { animation ->
                    updateOverlayLayoutIfAttached(
                        windowManager,
                        catDialogShowInfoView,
                        catDialogShowInfoViewLayoutParams
                    )
                }
            }
            // 同时执行宽度和高度动画
            readyDoingTaskAnimator = AnimatorSet()
            readyDoingTaskAnimator!!.playTogether(
                widthAnimator, heightAnimator, xAnimator, yAnimator, dongingAnimator
            )

            readyDoingTaskAnimator!!.start()
        }
    }

    fun setDoing(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewLayoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager,
        isShowTakeOver: Boolean = true,
        isShowStop: Boolean = true,
    ) {
        applyNormalActionStyle()
        bottomContentLayout?.visibility = VISIBLE
        ivResume?.visibility = GONE
        llResume?.visibility = GONE
        llStop?.visibility = if (isShowStop) VISIBLE else GONE
        llTakeOver?.visibility = if (isShowTakeOver) {
            VISIBLE
        } else {
            GONE
        }
        gradientBorderContainer?.cornerRadiusProgress = 0.0f
        innerRelativeLayout?.setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        if (CatDialogStateData.viewState == CatDialogViewState.TASK_DOING) {
            return
        }
        clearAllAminAndDelay()

        val (startWH, startXY) = when (CatDialogStateData.viewState) {
            CatDialogViewState.MESSAGE_INFO -> {
                Pair(CatDialogStateData.getMessageInfoWH(), CatDialogStateData.getMessageInfoXY())
            }

            CatDialogViewState.USER_INFO -> {
                Pair(
                    CatDialogStateData.getMessageInfoStartWH(),
                    CatDialogStateData.getDoingTaskXY()
                )
            }

            CatDialogViewState.USER_INFO_HINT -> {
                Pair(CatDialogStateData.getUserInfoWH(), CatDialogStateData.getUserInfoXY())
            }

            CatDialogViewState.EMPTY -> {

                Pair(
                    CatDialogStateData.getMessageInfoStartWH(), CatDialogStateData.getDoingTaskXY()
                )
            }

            else -> {
                Pair(CatDialogStateData.getTaskDoingWH(), CatDialogStateData.getDoingTaskXY())
            }
        }

        val (endW, endH) = CatDialogStateData.getTaskDoingWH()
        val (endX, endY) = CatDialogStateData.getDoingTaskXY()
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY) {
            doingAnimatorWithWH(startWH.first, startWH.second, endW, endH)
        } else {
            doingAnimatorWithWHXY(
                startWH.first,
                startWH.second,
                endW,
                endH,
                startXY.first,
                startXY.second,
                endX,
                endY,
                catDialogShowInfoView,
                catDialogShowInfoViewLayoutParams,
                windowManager
            )
        }


    }

    fun learningTask(
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewLayoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager,
        isPaused: Boolean = false,
    ) {
        clearAllAminAndDelay()
        applyLearningActionStyle(isPaused)
        visibility = VISIBLE
        layoutParams?.let { params ->
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams = params
        }
        bottomContentLayout?.visibility = VISIBLE
        ivResume?.visibility = GONE
        llResume?.visibility = GONE
        llTakeOver?.visibility = VISIBLE
        llStop?.visibility = VISIBLE
        llTakeOver?.contentDescription = if (isPaused) "继续手动录制" else "暂停手动录制"
        llStop?.contentDescription = "完成学习"
        gradientBorderContainer?.cornerRadiusProgress = 0.0f
        innerRelativeLayout?.setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        val (endW, endH) = CatDialogStateData.getTaskDoingWH()
        val (endX, endY) = CatDialogStateData.getDoingTaskXY()
        catDialogShowInfoView.visibility = VISIBLE
        catDialogShowInfoViewLayoutParams.width = endW
        catDialogShowInfoViewLayoutParams.height = endH
        catDialogShowInfoViewLayoutParams.x = endX
        catDialogShowInfoViewLayoutParams.y = endY
        updateOverlayLayoutIfAttached(
            windowManager,
            catDialogShowInfoView,
            catDialogShowInfoViewLayoutParams
        )
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化时更新形状
    }

    fun setMessage(message: String) {
        gradientTextView?.setText(message)

    }

    fun setSubMessage(message: String) {
        executingTextView?.setText(message)
    }

    fun finishDoingTask() {
        clearAllAminAndDelay()
    }

    /**
     * 准备执行任务，显示动画效果
     * 宽高从 40dp 动画到 80dp
     */
    fun readyDoingTask() {
        applyNormalActionStyle()
        bottomContentLayout?.visibility = VISIBLE
        ivResume?.visibility = GONE
        llResume?.visibility = GONE
        llTakeOver?.visibility = GONE
        llStop?.visibility = VISIBLE
        gradientBorderContainer?.cornerRadiusProgress = 0.0f
        innerRelativeLayout?.setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        visibility = GONE
        clearAllAminAndDelay()
        val (startW, startH) = CatDialogStateData.getMessageInfoStartWH()
        val (endW, endH) = CatDialogStateData.getMessageInfoWH()
        // 获取当前的 layoutParams
        layoutParams.width = startW
        layoutParams.height = startH
        doingAnimatorWithWH(startW, startH, endW, endH)
    }

    private fun applyNormalActionStyle() {
        borderColorType = GradientBorderContainerView.BorderColor.BLUE
        llTakeOver?.setBackgroundResource(R.drawable.bg_vlm_action_takeover)
        llStop?.setBackgroundResource(R.drawable.bg_vlm_action_complete)
        llTakeOverTextView?.text = "接管"
        llTakeOverTextView?.setTextColor("#202f51".toColorInt())
        llStopTextView?.text = "已完成"
        llStopTextView?.setTextColor("#16794A".toColorInt())
    }

    private fun applyLearningActionStyle(isPaused: Boolean = false) {
        borderColorType = GradientBorderContainerView.BorderColor.PURPLE
        llTakeOver?.setBackgroundResource(R.drawable.bg_learning_action_cancel)
        llStop?.setBackgroundResource(R.drawable.bg_learning_action_complete)
        llTakeOverTextView?.text = if (isPaused) "继续" else "暂停"
        llTakeOverTextView?.setTextColor("#5F3DC4".toColorInt())
        llStopTextView?.text = "完成学习"
        llStopTextView?.setTextColor("#5B21B6".toColorInt())
    }


    fun clearAllAminAndDelay() {
        delayTask.cancel()
        readyDoingTaskAnimator?.cancel()
        readyDoingTaskAnimator = null
        delayTask = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onDetachedFromWindow() {
        clearAllAminAndDelay()
        super.onDetachedFromWindow()
    }

    private fun updateOverlayLayoutIfAttached(
        windowManager: WindowManager,
        catDialogShowInfoView: CatDialogShowInfoView,
        params: WindowManager.LayoutParams
    ) {
        if (!catDialogShowInfoView.isAttachedToWindow) {
            readyDoingTaskAnimator?.cancel()
            readyDoingTaskAnimator = null
            return
        }
        try {
            windowManager.updateViewLayout(catDialogShowInfoView, params)
        } catch (e: IllegalArgumentException) {
            readyDoingTaskAnimator?.cancel()
            readyDoingTaskAnimator = null
        } catch (e: InvalidDisplayException) {
            readyDoingTaskAnimator?.cancel()
            readyDoingTaskAnimator = null
        }
    }
}
