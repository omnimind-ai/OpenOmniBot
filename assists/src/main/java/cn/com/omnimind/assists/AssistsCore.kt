package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.accessibility.api.Constant
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.eventapi.AssistsEventApi
import cn.com.omnimind.assists.api.eventapi.ScreenshotImageEventApi
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledParams
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledStates

/**
 * 向外提供的辅助管理器
 */
object AssistsCore {

    const val TAG = "[Assists]"
    private var stateMachine: StateMachine? = null;
    var screenshotImageEventApi: ScreenshotImageEventApi? = null
//     var scheduleTaskParams:TaskParams?=null

    /**
     * 初始化
     */
    fun initCore(context: Context) {
        //若无障碍服务未开启，则抛出异常
        stateMachine = StateMachine()
        stateMachine!!.init(context)
        //尝试创建辅助功能
        stateMachine!!.initAssists();
    }
    fun initCoreWithEvent(context: Context,assistsEventApi: AssistsEventApi, screenshotImageEventApi: ScreenshotImageEventApi) {
        stateMachine = StateMachine()
        stateMachine!!.init(context, assistsEventApi)
        //尝试创建辅助功能
        stateMachine!!.initAssists();
        this.screenshotImageEventApi = screenshotImageEventApi
    }

    /**
     * 状态机是否初始化
     */
    fun isStateMachineInitialized(): Boolean {
        return stateMachine?.isInitialized() == true;
    }

    /**
     * 辅助服务知否已经执行
     */
    fun isAccessibilityServiceEnabled() = AssistsService.instance != null

    /**
     * 创建陪伴任务
     */
    fun startTask(
        params: TaskParams
    ) {
        stateMachine?.startTask(params)
    }

    fun isCompanionTaskRunning(): Boolean = stateMachine?.isRunningCompanionTask() ?: false

    /**
     * 取消陪伴任务的回到桌面操作
     */
    fun cancelCompanionGoHome() = stateMachine?.cancelCompanionGoHome()

    /**
     * 结束陪伴任务
     */
    fun finishCompanionTask() = stateMachine?.finishAppCompanion()

    /**
     * 结束进行中的任务
     */
    fun finishDoingTask() = stateMachine?.finishDoingTask()


    /**
     * 取消聊天任务
     */
    fun cancelChatTask(taskId: String? = null) {
        stateMachine?.cancelChatTask(taskId)
    }

    /**
     * 提供用户输入给正在运行的VLM任务（用于INFO交互）
     */
    fun provideUserInputToVLMTask(userInput: String): Boolean {
        return stateMachine?.provideUserInputToVLMTask(userInput) ?: false
    }

    fun appendVlmExternalMemory(memory: String): Boolean {
        return stateMachine?.appendVlmExternalMemory(memory) ?: false
    }

    /**
     * Append a priority event to the VLM task
     * @param memory The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun appendVlmPriorityEvent(memory: String, eventType: String, suggestCompletion: Boolean = false): Boolean {
        return stateMachine?.appendVlmPriorityEvent(memory, eventType, suggestCompletion) ?: false
    }

    /**
     * 通知VLM任务总结Sheet已准备就绪
     */
    fun notifySummarySheetReady(): Boolean {
        return stateMachine?.notifySummarySheetReady() ?: false
    }


    suspend fun showScheduledTip(closeTimer: Long, doTaskTimer: Long) {
        stateMachine?.showScheduledTip(closeTimer, doTaskTimer)
    }
    fun getScheduleStatus(): ScheduledStates? {
        return stateMachine?.getScheduleStatus()
    }

    fun getScheduleParams(): ScheduledParams? {
        return stateMachine?.getScheduleParams()
    }

    fun clearScheduleTask() {
        stateMachine?.clearScheduleTask()
    }

    fun doScheduleNow() {
        stateMachine?.doScheduleNow()
    }

    fun cancelScheduleTask() {
        stateMachine?.cancelScheduledTask()
    }

    suspend fun startFirstUse(companionFinishListener: () -> Unit, packageName: String) {
        stateMachine?.startFirstUse(companionFinishListener, packageName)
    }

    fun isInDesktop(): Boolean {
        try {
            val packageName = AccessibilityController.getPackageName()
            return Constant.LAUNCHER_PACKAGES.contains(packageName)
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 获取当前应用包名
     * 用于从当前页面开始执行任务
     */
    fun getCurrentPackageName(): String? {
        return try {
            AccessibilityController.getPackageName()
        } catch (e: Exception) {
            null
        }
    }
    /**
     * 取消等待中或运行中的任务，不检查 isRunning 状态
     * 用于在预执行 delay 期间取消任务
     */
    fun cancelPendingTask(taskId: String? = null) = stateMachine?.cancelPendingTask(taskId)

    /**
     * 导航到主应用指定路由
     * 会自动清理悬浮层（半屏/对话框等），避免背景是本应用时残留
     */
    fun navigateToMainApp(route: String?, needClear: Boolean = false) {
        // UI 路由跳转由 app 层处理（Assists 模块不直接依赖 UI 实现）
    }
}
