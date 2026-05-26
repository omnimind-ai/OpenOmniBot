package cn.com.omnimind.uikit.view.overlay.cat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import cn.com.omnimind.baselib.util.dpToPx
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.api.callback.CatLayoutApi
import cn.com.omnimind.uikit.loader.cat.DraggableViewState
import cn.com.omnimind.uikit.loader.cat.OnStateChangeListener
import cn.com.omnimind.uikit.view.data.Constant
import cn.com.omnimind.uikit.view.layout.BaseFrameLayout
import cn.com.omnimind.uikit.view.layout.MessageView
import cn.com.omnimind.uikit.view.layout.ScheduledTipView

class CatDialogLayoutView @JvmOverloads constructor(
    context: Context,
    var catLayoutApi: CatLayoutApi?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private lateinit var messageView: MessageView
    private lateinit var scheduledTipView: ScheduledTipView
    private lateinit var subView: View
    private var currentState = DraggableViewState.COLLAPSED
    private var isAttachedToLeft = false
    private lateinit var onStateChangeListener: OnStateChangeListener
    private var lockDoingTaskState = false
    private var lastView: BaseFrameLayout? = null
    private var onCloseListener: () -> Unit = {
        collapse()
    }

    init {
        setupView()
    }

    private fun setupView() {
        subView = inflate(context, R.layout.view_cat_dialog_layout, this)
        messageView = subView.findViewById(R.id.messageView)
        scheduledTipView = subView.findViewById(R.id.scheduledTipView)
        scheduledTipView.setOnCloseListener(onCloseListener)
        setMargin(messageView, 7.dpToPx(), isAttachedToLeft)
        setMargin(scheduledTipView, 7.dpToPx(), isAttachedToLeft)
    }

    fun setMargin(view: View, topMargin: Int = 0, isLeft: Boolean) {
        val params = view.layoutParams as MarginLayoutParams
        params.topMargin = Constant.CAT_DIALOG_LAYOUT_MARGIN + topMargin
        if (isLeft) {
            params.leftMargin = (CatView.width * 2 / 3).dpToPx()
            params.rightMargin = 0
        } else {
            params.rightMargin = (CatView.width * 2 / 3).dpToPx()
            params.leftMargin = 0
        }
        view.layoutParams = params
    }

    fun setOnStateChangeListener(listener: OnStateChangeListener) {
        onStateChangeListener = listener
    }

    private fun updateDraggableViewState() {
        if (lockDoingTaskState) {
            onStateChangeListener.onStateChange(currentState)
            return
        }
        updateViewForAttachment()
        onStateChangeListener.onStateChange(currentState)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        onStateChangeListener.onCatLayoutDialogMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateViewForAttachment() {
        when (currentState) {
            DraggableViewState.COLLAPSED -> setViewToCollapsed()
            DraggableViewState.DRAGGING -> setViewToDragging()
            DraggableViewState.MESSAGE -> setCardChildViewVisibility(messageView)
            DraggableViewState.SCHEDULED_TIP -> setCardChildViewVisibility(scheduledTipView)
            DraggableViewState.DOING_TASK,
            DraggableViewState.PAUSE_TASK,
            DraggableViewState.SHOW_CHAT -> {}
        }
    }

    fun setViewToCollapsed() {
        if (lastView != null) {
            lastView?.close(isAttachedToLeft) {
                lastView = null
                subView.visibility = GONE
            }
        } else {
            subView.visibility = GONE
        }
    }

    fun setViewToDragging() {
        subView.visibility = GONE
        messageView.visibility = GONE
        scheduledTipView.visibility = GONE
    }

    fun setCardChildViewVisibility(view: BaseFrameLayout) {
        if (view != messageView) {
            messageView.visibility = GONE
        }
        if (view != scheduledTipView) {
            scheduledTipView.visibility = GONE
        }
        subView.visibility = VISIBLE
        if (view.visibility != VISIBLE) {
            view.show(isAttachedToLeft)
        }
        lastView = view
    }

    fun collapse() {
        checkLockAndChangeStatus(DraggableViewState.COLLAPSED)
    }

    fun collapseTakeover() {
        lockDoingTaskState = false
        collapse()
    }

    fun finishDoingTask(message: String) {
        lockDoingTaskState = false
        this.message(message)
    }

    fun message(message: String, keepVisibleUntilClosed: Boolean = false) {
        checkLockAndChangeStatus(DraggableViewState.MESSAGE) {
            messageView.setMessage(message, keepVisibleUntilClosed)
        }
    }

    fun isPointInsideMessageView(rawX: Float, rawY: Float): Boolean {
        if (currentState != DraggableViewState.MESSAGE || messageView.visibility != VISIBLE) {
            return false
        }
        val location = IntArray(2)
        messageView.getLocationOnScreen(location)
        return rawX >= location[0] &&
            rawX <= location[0] + messageView.width &&
            rawY >= location[1] &&
            rawY <= location[1] + messageView.height
    }

    fun showScheduledTip(closeTimer: Long, doTaskTimer: Long) {
        checkLockAndChangeStatus(DraggableViewState.SCHEDULED_TIP) {
            scheduledTipView.setMessage(closeTimer, doTaskTimer)
            scheduledTipView.setOnStopClickListener {
                catLayoutApi?.cancelScheduledTask()
                collapse()
            }
        }
    }

    fun startDragging() {
        checkLockAndChangeStatus(DraggableViewState.DRAGGING)
    }

    fun checkLockAndChangeStatus(state: DraggableViewState, block: () -> Unit = {}) {
        if (!lockDoingTaskState) {
            currentState = state
            block.invoke()
        }
        updateDraggableViewState()
    }

    fun getCurrentState(): DraggableViewState {
        return currentState
    }

    fun setAttachmentSideView(isLeft: Boolean) {
        isAttachedToLeft = isLeft
        setMargin(messageView, 7.dpToPx(), isAttachedToLeft)
        setMargin(scheduledTipView, 7.dpToPx(), isAttachedToLeft)
    }
}
