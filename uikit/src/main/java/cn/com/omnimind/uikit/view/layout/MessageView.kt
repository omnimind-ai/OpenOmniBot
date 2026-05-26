package cn.com.omnimind.uikit.view.layout

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.TextView
import android.view.LayoutInflater
import android.view.animation.OvershootInterpolator
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

class MessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : BaseFrameLayout(context, attrs, defStyleAttr) {

    private var finishView:CoroutineScope? =null;
    private var keepVisible: Boolean = false
    private var keepVisibleOverride: Boolean = false
    private var customDelayMillis: Long = 4000 // 4秒显示时间

    private lateinit var tvTip: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_message, this, true)
        tvTip = findViewById(R.id.tvTip)
        // 启用自定义绘制
        setWillNotDraw(false)
    }

    fun setMessage(message: String, keepVisibleOverride: Boolean = false) {
        tvTip.text = message
        this.keepVisibleOverride = keepVisibleOverride
        keepVisible = message.startsWith("小猫需要你的帮助")
    }

    override fun show(isLeft: Boolean) {
        super.show(isLeft)

        // 根据isLeft参数确定动画起始位置
        translationX = if (isLeft) -width.toFloat() else width.toFloat()
        animate().translationX(0f).setDuration(durations)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
        if (finishView?.isActive==true){
            finishView?.cancel()
        }
        finishView= CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (!keepVisible && !keepVisibleOverride) {
            finishView?.launch {
                delay(customDelayMillis)
                withContext(Dispatchers.Main){
                    close(isLeft){}
                }
            }
        } else {
            Log.i("MessageView", "Message is keepVisible, timer not started")
        }

    }

    override fun close(isLeft: Boolean, closeFinishListener: () -> Unit) {
        // 创建向左或向右消失的动画（基于isLeft参数）
        finishView?.cancel()
        keepVisible = false
        keepVisibleOverride = false
        val targetTranslationX = if (isLeft) -(width.toFloat()+ Constant.CAT_VIEW_LAYOUT_MARGIN) else width.toFloat()+ Constant.CAT_VIEW_LAYOUT_MARGIN
        animate().translationX(targetTranslationX).setDuration(durations)
            .setInterpolator(OvershootInterpolator(1.2f)).withEndAction {
                super@MessageView.close(isLeft, closeFinishListener)
            }.start()
    }

}
