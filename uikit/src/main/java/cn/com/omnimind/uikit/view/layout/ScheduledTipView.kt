package cn.com.omnimind.uikit.view.layout

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.Shader.TileMode
import android.graphics.drawable.Drawable
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import cn.com.omnimind.baselib.util.getResString
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.view.data.Constant


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScheduledTipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseFrameLayout(context, attrs, defStyleAttr) {
    private var finishView: CoroutineScope? = null;

    private val cornerRadius = 50.dpToPxF()
    private val startColor = Color.parseColor("#4A90E2")
    private val endColor = Color.WHITE

    private lateinit var titleTextView: TextView
    private lateinit var stopButton: ImageView

    // 动画相关属性
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val shaderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()
    private val pathMeasure = PathMeasure()
    private var pathLength = 0f
    private var strokeWidth = 2.dpToPxF()
    private var animationDuration = 5000L
    private var animator: ValueAnimator? = null
    private var shaderMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderTranslate = 0f
    private var countDownTimer: CountDownTimer? = null

    init {
        // 使用LayoutInflater加载布局
        LayoutInflater.from(context).inflate(R.layout.layout_learning_status_card, this, true)

        // 初始化视图组件
        titleTextView = findViewById(R.id.titleTextView)
        stopButton = findViewById(R.id.stopButton)
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        stopButton.isClickable = true
        stopButton.isFocusable = true
        stopButton.contentDescription = context.getString(R.string.accessibility_scheduled_stop)

        // 设置基础样式
        elevation = 4.dpToPxF()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true

        // 设置默认背景
        background = createGradientBackground()

    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 定义光圈移动的路径
        path.reset()
        val cornerRadius = 19.dpToPxF() // 圆角半径
        val rectF = RectF(strokeWidth, strokeWidth, w - strokeWidth, h - strokeWidth)
        path.addRoundRect(
            rectF, cornerRadius, cornerRadius, Path.Direction.CW
        )

        // 计算路径长度
        pathMeasure.setPath(path, true)
        pathLength = pathMeasure.length

        // 创建蓝色渐变着色器
        shader = LinearGradient(
            0f, 0f, pathLength, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.BLUE,
                Color.CYAN,
                Color.BLUE,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
            TileMode.MIRROR
        )

        shaderPaint.shader = shader
        shaderPaint.strokeWidth = strokeWidth * 2
    }

    private fun createGradientBackground(): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val rectF = RectF()

            override fun draw(canvas: Canvas) {
                rectF.set(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())

                // 执行中状态：使用原始渐变
                paint.shader = LinearGradient(
                    0f, 0f, bounds.width().toFloat(), 0f,
                    startColor, endColor, TileMode.CLAMP
                )
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun setOnStopClickListener(listener: () -> Unit) {
        stopButton.setOnClickListener {
            listener.invoke()
        }
    }


    fun setMessage(closeTimer: Long, doTaskTimer: Long) {
        // 恢复执行中状态
        titleTextView.text = "${doTaskTimer}${R.string.scheduled_tip.getResString()}"
        contentDescription = titleTextView.text
        if (animator?.isRunning != true) {
            startAnimation()
        }
        if (finishView?.isActive == true) {
            finishView?.cancel()
        }
        //创建一个计时器,将closeTimer传入 倒计时
        countDownTimer?.cancel()

        // Create and start a new timer
        countDownTimer = object : CountDownTimer(doTaskTimer * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                titleTextView.text =
                    "${seconds+1}${R.string.scheduled_tip.getResString()}"
                contentDescription = titleTextView.text

            }

            override fun onFinish() {
                close(isLeft) {}
                countDownTimer = null
            }
        }.start()


        finishView = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        finishView?.launch {
            delay(closeTimer * 1000)
            withContext(Dispatchers.Main) {
                countDownTimer?.cancel()
                countDownTimer = null
                close(isLeft) {}
            }
        }
    }

    fun startAnimation() {
        if (animator?.isRunning == true) {
            animator!!.cancel()
        }
        animator = ValueAnimator.ofFloat(0f, 1f)
        animator!!.duration = animationDuration
        animator!!.interpolator = LinearInterpolator()
        animator!!.repeatCount = ValueAnimator.INFINITE
        animator!!.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            shaderTranslate = value * pathLength
            shaderMatrix.setTranslate(shaderTranslate, 0f)
            shader?.setLocalMatrix(shaderMatrix)
            invalidate()
        }
        animator!!.start()
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制边缘流光效果
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth * 2
        paint.color = Color.WHITE
        paint.alpha = 100
        paint.isAntiAlias = true
        paint.isDither = true
        canvas.drawPath(path, paint)

        // 绘制彩色流光跑马灯作为边缘效果
        shaderPaint.strokeWidth = strokeWidth * 2f
        shaderPaint.isAntiAlias = true
        shaderPaint.isDither = true
        canvas.drawPath(path, shaderPaint)
    }

    override fun show(isLeft: Boolean) {
        super.show(isLeft)
        visibility = INVISIBLE
        // 根据isLeft参数确定动画起始位置
        translationX = if (isLeft) -width.toFloat() else width.toFloat()
        animate().translationX(0f).setDuration(durations)
            .withStartAction {
                visibility = VISIBLE
            }
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    override fun close(isLeft: Boolean, closeFinishListener: () -> Unit) {
        // 创建向左或向右消失的动画（基于isLeft参数）
        val targetTranslationX =
            if (isLeft) -(width.toFloat() + Constant.CAT_VIEW_LAYOUT_MARGIN) else width.toFloat() + Constant.CAT_VIEW_LAYOUT_MARGIN
        animate().translationX(targetTranslationX).setDuration(durations)
            .setInterpolator(OvershootInterpolator(1.2f)).withEndAction {
                super@ScheduledTipView.close(isLeft, closeFinishListener)
            }.start()
    }

}
