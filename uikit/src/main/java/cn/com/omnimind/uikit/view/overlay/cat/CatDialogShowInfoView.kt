package cn.com.omnimind.uikit.view.overlay.cat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.api.callback.CatStepLayoutApi
import cn.com.omnimind.uikit.view.data.CatDialogStateData
import cn.com.omnimind.uikit.view.data.CatDialogViewState

import cn.com.omnimind.uikit.view.layout.ShowInfoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 */
class CatDialogShowInfoView @JvmOverloads constructor(
    context: Context,
    val catStepLayoutApi: CatStepLayoutApi? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        setUpView()
    }

    companion object {
        private const val TAG = "CatDialogShowInfoView"
    }

    private lateinit var showInfoView: ShowInfoView
    private lateinit var subView: View//总布局


    private fun setUpView() {
        subView = inflate(context, R.layout.view_cat_show_info_layout, this);
        showInfoView = subView.findViewById<ShowInfoView>(R.id.showInfoView)
        showInfoView.catStepLayoutApi = catStepLayoutApi
    }

    fun setMessage(message: String) {
        showInfoView.setMessage(message)
    }

    fun setSubMessage(message: String) {
        showInfoView.setSubMessage(message)
    }


    fun doingTask(
        message: String,
        subMessage: String,
        catDialogShowInfoView: CatDialogShowInfoView,
        layoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        setMessage(message)
        setSubMessage(subMessage)
        // 取消首次展示动画（如果正在执行）
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY) {
            showInfoView.visibility = GONE
        } else {
            showInfoView.visibility = VISIBLE

        }
        visibility = VISIBLE
        showInfoView.doingTask(catDialogShowInfoView, layoutParams, windowManager)
        CatDialogStateData.viewState = CatDialogViewState.TASK_DOING

    }

    fun setDoing(
        message: String,
        catDialogShowInfoView: CatDialogShowInfoView,
        layoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager,
        isShowTakeOver: Boolean = true,
        isShowStop: Boolean = true,
        subMessage: String? = null,

        ) {
        setMessage(message)
        subMessage?.let { setSubMessage(it) }
        // 取消首次展示动画（如果正在执行）
        if (CatDialogStateData.viewState == CatDialogViewState.EMPTY) {
            showInfoView.visibility = GONE
        } else {
            showInfoView.visibility = VISIBLE

        }
        visibility = VISIBLE
        showInfoView.setDoing(
            catDialogShowInfoView,
            layoutParams,
            windowManager,
            isShowTakeOver,
            isShowStop
        )
        CatDialogStateData.viewState = CatDialogViewState.TASK_DOING
    }

    fun learningTask(
        message: String,
        subMessage: String,
        catDialogShowInfoView: CatDialogShowInfoView,
        layoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager,
        isPaused: Boolean = false
    ) {
        setMessage(message)
        setSubMessage(subMessage)
        showInfoView.visibility = VISIBLE
        visibility = VISIBLE
        showInfoView.learningTask(
            catDialogShowInfoView,
            layoutParams,
            windowManager,
            isPaused
        )
        CatDialogStateData.viewState = CatDialogViewState.TASK_DOING
    }

    fun pauseTask(
        message: String,
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewParams: WindowManager.LayoutParams,
        windowManager: WindowManager,
    ) {
        // 重要：先恢复所有视图到默认 XML 状态（从任何状态恢复）
        showInfoView.visibility = VISIBLE
        visibility = VISIBLE
        setMessage(message)
        setSubMessage("完成后请点击继续或已完成")
        showInfoView.pause(catDialogShowInfoView, catDialogShowInfoViewParams, windowManager)

    }

    suspend fun userAction(
        message: String,
        catDialogShowInfoView: CatDialogShowInfoView,
        catDialogShowInfoViewParams: WindowManager.LayoutParams,
        windowManager: WindowManager,
    ): Boolean {
        withContext(Dispatchers.Main) {
            showInfoView.visibility = VISIBLE
            visibility = VISIBLE
            setMessage(message)
            setSubMessage("完成后请点击继续或已完成")
        }
        return showInfoView.userAction(
            catDialogShowInfoView,
            catDialogShowInfoViewParams,
            windowManager
        )
    }

    fun finishDoingTask() {
        // 取消首次展示动画（如果正在执行）
        showInfoView.finishDoingTask()
        CatDialogStateData.viewState = CatDialogViewState.EMPTY
        showInfoView.visibility = GONE
        visibility = GONE
    }

    fun cancelAnimations() {
        showInfoView.clearAllAminAndDelay()
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }


    fun getShowInfoViewVisibility(): Int {
        return showInfoView.visibility
    }

    /**
     * 准备执行任务
     */
    fun readyDoingTask(
        message: String,
    ) {
        showInfoView.visibility = GONE
        visibility = VISIBLE
        setMessage(message)
        setSubMessage("准备执行中")
        showInfoView.readyDoingTask()
        CatDialogStateData.viewState = CatDialogViewState.MESSAGE_INFO

    }

}
