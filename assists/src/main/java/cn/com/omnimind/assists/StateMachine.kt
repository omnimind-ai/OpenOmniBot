package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.api.eventapi.AssistsEventApi
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.TaskChangeImpl
import cn.com.omnimind.assists.task.companion.CompanionTask
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledParams
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledStates
import cn.com.omnimind.assists.util.ScreenState
import cn.com.omnimind.baselib.util.CompanionUiState
import kotlinx.coroutines.delay

/**
 * 状态机
 */
class StateMachine() {
    private var isInitialized = false;//是否初始化
    lateinit var instance: Context;//上下文
    private var taskManager: TaskManager? = null//任务管理器
    private var assistsEventApi: AssistsEventApi? = null//事件接口

    //屏幕状态
    private var screenState: ScreenState = ScreenState({
        if (taskManager?.hasRunningTask() == true) {
            taskManager?.finishDoingTask()
        } else {
            assistsEventApi?.getCommentEventImpl()?.onScreenLock()

        }

    }, {
        // 设备进入可操作状态（屏幕亮屏且已解锁）
        if (taskManager?.isCompanionRunning() == true) {
            assistsEventApi?.getCommentEventImpl()
                ?.onScreenUnLock(taskManager?.isCompanionRunning() == true)
        }
    })

    /**
     * 是否初始化
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }

    /**
     * 初始化无障碍
     */
    fun initAssists() {
        AssistsService.removeScreenStateListener(screenState)
        AccessibilityController.initController()
        AssistsService.addScreenStateListener(screenState)
    }

    /**
     * 初始化
     */
    fun init(context: Context) {
        instance = context
        taskManager = TaskManager(context, TaskChangeImpl(assistsEventApi), assistsEventApi)
        isInitialized = true;
    }

    fun init(context: Context, assistsEventApi: AssistsEventApi) {
        this.assistsEventApi = assistsEventApi
        init(context)
    }

    /**
     * 开启陪伴
     */
    fun startTask(params: TaskParams) {
        initAssists();
        taskManager?.createAndStartTask(params)
    }

    /**
     * 结束陪伴
     */
    fun finishAppCompanion() {
        taskManager?.stopCompanionTask()
        AccessibilityController.destroy()

    }

    /**
     * 取消聊天任务
     */
    fun cancelChatTask(taskId: String? = null) {
        taskManager?.cancelChatTask(taskId)
    }

    /**
     * 获取当前陪伴任务
     */
    fun getRunningCompanionTask(): CompanionTask? {
        return taskManager?.getCompanionTask()
    }

    /**
     * 判断是否有陪伴任务执行
     */
    fun isRunningCompanionTask(): Boolean {
        return taskManager?.getCompanionTask()?.isRunning == true
    }

    /**
     * 取消陪伴任务的回到桌面操作
     */
    fun cancelCompanionGoHome() {
        taskManager?.cancelCompanionGoHome()
    }
    //UI交互相关

    /**
     * 提供用户输入给正在运行的VLM任务（INFO交互）
     */
    fun provideUserInputToVLMTask(userInput: String): Boolean {
        return taskManager?.provideUserInputToVLMTask(userInput) ?: false
    }

    fun pauseVLMTask(): Boolean {
        return taskManager?.pauseVLMTask() ?: false
    }

    fun resumeVLMTask(): Boolean {
        return taskManager?.resumeVLMTask() ?: false
    }

    fun appendVlmExternalMemory(memory: String): Boolean {
        return taskManager?.appendVlmExternalMemory(memory) ?: false
    }

    /**
     * Append a priority event to the VLM task
     * @param memory The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun appendVlmPriorityEvent(memory: String, eventType: String, suggestCompletion: Boolean = false): Boolean {
        return taskManager?.appendVlmPriorityEvent(memory, eventType, suggestCompletion) ?: false
    }

    /**
     * 通知VLM任务总结Sheet已准备就绪
     */
    fun notifySummarySheetReady(): Boolean {
        return taskManager?.notifySummarySheetReady() ?: false
    }

    fun getScheduleStatus(): ScheduledStates? {
        return taskManager?.getScheduleStatus()
    }

    fun getScheduleParams(): ScheduledParams? {
        return taskManager?.getScheduleParams()
    }

    fun clearScheduleTask() {
        taskManager?.clearScheduleTask()
    }

    fun doScheduleNow() {
        taskManager?.doScheduleNow()
    }


    suspend fun startFirstUse(companionFinishListener: () -> Unit, packageName: String) {
        CompanionUiState.setSuppressStartMessage(true)
        try {
            startTask(TaskParams.CompanionTaskParams(companionFinishListener))

            try {
                AccessibilityController.launchApplication(packageName) { x, y ->
                    if (assistsEventApi == null || assistsEventApi!!.getExecutionEventImpl() == null) {
                        AccessibilityController.clickCoordinate(x, y)

                    } else {
                        assistsEventApi!!.getExecutionEventImpl()!!.clickCoordinateWithOutLock(x, y) {
                            AccessibilityController.clickCoordinate(x, y)
                        }
                    }
                }
            } catch (e: Exception) {
                assistsEventApi?.getExecutionEventImpl()
                    ?.startFirstUseMessage("检测到您没有安装该应用，任务结束啦~")
                return
            }
            delay(1000)
            assistsEventApi?.getExecutionEventImpl()?.startFirstUseMessage("hi~我在这,轻点我一下!")
        } finally {
            CompanionUiState.setSuppressStartMessage(false)
        }
    }


    suspend fun showScheduledTip(closeTime: Long, doTaskTime: Long) {
        assistsEventApi?.getExecutionEventImpl()?.showScheduledTip(closeTime, doTaskTime)
    }

    fun cancelScheduledTask() {
        taskManager?.cancelScheduledTask()
    }

    fun finishDoingTask() {
        taskManager?.finishDoingTask()
    }

    /**
     * 取消等待中或运行中的任务，不检查 isRunning 状态
     * 用于在预执行 delay 期间取消任务
     */
    fun cancelPendingTask(taskId: String? = null): Boolean {
        return taskManager?.cancelPendingTask(taskId) ?: false
    }

}
