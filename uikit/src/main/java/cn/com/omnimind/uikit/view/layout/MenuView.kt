package cn.com.omnimind.uikit.view.layout

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import androidx.constraintlayout.widget.ConstraintLayout
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.api.callback.MenuApi
import cn.com.omnimind.uikit.view.overlay.cat.CatView

class MenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseFrameLayout(context, attrs, defStyleAttr) {

    private var menuApi: MenuApi? = null
    var homeButton: ConstraintLayout
    var centerPoint: View
    var myRoot: ConstraintLayout

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.layout_menu_view, this, true)
        centerPoint = findViewById(R.id.centerPoint)
        homeButton = findViewById(R.id.btn_home)
        myRoot = findViewById(R.id.myRoot)
        myRoot.clipChildren = false
        myRoot.clipToPadding = false
        homeButton.isClickable = true
        homeButton.isFocusable = true
        homeButton.contentDescription = context.getString(R.string.accessibility_open_home)
        centerPoint.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        homeButton.setOnClickListener {
            doClose()
            menuApi?.onOpenHomeParams(null)
        }
    }

    override fun show(isLeft: Boolean) {
        super.show(isLeft)
        visibility = INVISIBLE

        val centerPointLayoutParams = centerPoint.layoutParams as ConstraintLayout.LayoutParams
        if (isLeft) {
            centerPointLayoutParams.startToStart = myRoot.id
            centerPointLayoutParams.endToEnd = -1
            centerPointLayoutParams.leftMargin = CatView.width.dpToPx()
            centerPointLayoutParams.rightMargin = 0
        } else {
            centerPointLayoutParams.startToStart = -1
            centerPointLayoutParams.endToEnd = myRoot.id
            centerPointLayoutParams.rightMargin = CatView.width.dpToPx() * 4 / 5
            centerPointLayoutParams.leftMargin = 0
        }
        centerPoint.layoutParams = centerPointLayoutParams

        post {
            showWithAnimation(isLeft)
        }
    }

    private fun showWithAnimation(isLeft: Boolean) {
        val animatorSet = AnimatorSet()
        val alphaAnimator = ObjectAnimator.ofFloat(homeButton, ALPHA, 0f, 1f).apply {
            duration = durations
        }
        val startX = if (isLeft) -width.toFloat() else width.toFloat()
        homeButton.translationY = 0f
        homeButton.translationX = startX
        val translateXAnimator = ObjectAnimator.ofFloat(homeButton, TRANSLATION_X, startX, 0f).apply {
            duration = durations
        }
        val translateYAnimator = ObjectAnimator.ofFloat(homeButton, TRANSLATION_Y, 0f, 0f).apply {
            duration = durations
        }
        val scaleAnimation = ScaleAnimation(
            1f,
            0f,
            1f,
            0f,
            Animation.RELATIVE_TO_SELF,
            if (isLeft) 0f else 1f,
            Animation.RELATIVE_TO_SELF,
            0f
        )
        scaleAnimation.duration = durations
        val scaleAnimatorX = ObjectAnimator.ofFloat(homeButton, SCALE_X, 0f, 1f).apply {
            duration = durations
        }
        val scaleAnimatorY = ObjectAnimator.ofFloat(homeButton, SCALE_Y, 0f, 1f).apply {
            duration = durations
        }
        animatorSet.playTogether(
            scaleAnimatorY,
            scaleAnimatorX,
            alphaAnimator,
            translateXAnimator,
            translateYAnimator
        )
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                visibility = VISIBLE
            }
        })
        animatorSet.start()
    }

    override fun close(isLeft: Boolean, closeFinishListener: () -> Unit) {
        val animatorSet = AnimatorSet()
        val alphaAnimator = ObjectAnimator.ofFloat(homeButton, ALPHA, 1f, 0f).apply {
            duration = durations
        }
        val endX = if (isLeft) -width.toFloat() else width.toFloat()
        val translateXAnimator = ObjectAnimator.ofFloat(homeButton, TRANSLATION_X, 0f, endX).apply {
            duration = durations
        }
        val translateYAnimator = ObjectAnimator.ofFloat(homeButton, TRANSLATION_Y, 0f, 0f).apply {
            duration = durations
        }
        val scaleAnimatorX = ObjectAnimator.ofFloat(homeButton, SCALE_X, 1f, 0f).apply {
            duration = durations
        }
        val scaleAnimatorY = ObjectAnimator.ofFloat(homeButton, SCALE_Y, 1f, 0f).apply {
            duration = durations
        }
        animatorSet.playTogether(
            scaleAnimatorY,
            scaleAnimatorX,
            alphaAnimator,
            translateXAnimator,
            translateYAnimator
        )
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                super@MenuView.close(isLeft, closeFinishListener)
            }
        })
        animatorSet.start()
    }

    fun setDraggableListener(menuApi: MenuApi?) {
        this.menuApi = menuApi
    }
}
