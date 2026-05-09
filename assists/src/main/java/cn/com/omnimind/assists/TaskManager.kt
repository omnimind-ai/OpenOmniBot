package cn.com.omnimind.assists

import android.content.Context
import androidx.work.WorkManager
import cn.com.omnimind.assists.api.eventapi.AssistsEventApi
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.ChatTask
import cn.com.omnimind.assists.task.Task
import cn.com.omnimind.assists.task.companion.CompanionTask
import cn.com.omnimind.assists.task.scheduled.ScheduledVLMOperationTask
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledParams
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledStates
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledTask
import cn.com.omnimind.assists.task.vlmserver.VLMOperationTask
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledParamsJson
import cn.com.omnimind.assists.task.scheduled.worker.toScheduledVLMOperationTaskParams
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class TaskManager(
    val context: Context,
    val taskChangeListener: TaskChangeListener,
    val assistsEventApi: AssistsEventApi?
) {

    private val TAG = "[Assists] TaskManager"
    private var companionTask: CompanionTask? = null;//陪伴任务
    private var runningTask: Task? = null;//执行中的任务,包括,学习,执行,视觉执行
    private val chatTasks: LinkedHashMap<String, ChatTask> = linkedMapOf()//聊天任务
    private var scheduledTask: ScheduledTask? = null;//预约任务

    init {
        val scheduled_task_id = MMKV.defaultMMKV().decodeString("scheduled_task_id", "")
        val scheduled_task_jsonData = MMKV.defaultMMKV().decodeString("scheduled_task_jsonData", "")
        WorkManager.getInstance(context).cancelAllWork()
        if (!scheduled_task_id.isNullOrEmpty() && !scheduled_task_jsonData.isNullOrEmpty()) {
            val scheduledTask =
                Gson().fromJson(scheduled_task_jsonData, ScheduledParamsJson::class.java)
            OmniLog.d(TAG, "Ready create scheduled task on init  params=${scheduledTask}")

            if (scheduledTask != null) {
                val newDelayTime =
                    scheduledTask.delayTimes - (System.currentTimeMillis() - scheduledTask.startCTimeStamp) / 1000
                OmniLog.d(TAG, "newDelayTime  params=${newDelayTime}")

                if (newDelayTime >= 0) {
                    val params = scheduledTask.vlmTaskParams?.toScheduledVLMOperationTaskParams(
                        ""
                    )
                    if (params != null) {
                        OmniLog.d(TAG, "Create scheduled task on init")
                        MMKV.defaultMMKV().encode("scheduled_task_id", "")
                        MMKV.defaultMMKV().encode("scheduled_task_jsonData", "")
                        createScheduledTask(TaskParams.ScheduledTaskParams(params, newDelayTime) {})
                    }
                }

            }
        }
    }

    fun createAndStartTask(params: TaskParams) {
        AccessibilityController.initController()
        when (params) {
            is TaskParams.ChatTaskParams -> {
                createChatTaskAndStart(params)
            }

            is TaskParams.CompanionTaskParams -> {
                createCompanionTaskAndStart(params)
            }

            is TaskParams.VLMOperationTaskParams -> {
                startVLMOperationTask(params)
            }

            is TaskParams.ScheduledTaskParams -> {
                createScheduledTask(params)
            }

            is TaskParams.ScheduledVLMOperationTaskParams -> {
                startScheduledVLMOperationTask(params)
            }
        }
    }


    fun getCompanionTask(): CompanionTask? {
        return companionTask
    }

    fun isCompanionRunning(): Boolean {
        return companionTask?.isRunning == true
    }

    fun resumeCompanionTask() {
        if (companionTask?.isRunning == true) {
            // 有陪伴模式：恢复陪伴模式
            companionTask?.resumeTask()
        }
    }

    /**
     * 取消陪伴任务的回到桌面操作
     * 当用户在开启陪伴后离开主页时调用
     */
    fun cancelCompanionGoHome() {
        companionTask?.cancelGoHome()
    }

    private fun createScheduledTask(params: TaskParams.ScheduledTaskParams) {
        if (scheduledTask?.isRunning == true) {
            OmniLog.w(
                TAG,
                "createScheduledTask is not worked! There has a running task! Please finish it first!"
            )
            return
        }
        scheduledTask = ScheduledTask(context, assistsEventApi?.getExecutionEventImpl(),taskChangeListener,this)
        scheduledTask!!.start(params.taskParams, params.delay, params.onTaskFinishListener)
    }

    private fun createCompanionTaskAndStart(params: TaskParams.CompanionTaskParams) {
        if (companionTask?.isRunning == true) {
            OmniLog.w(
                TAG, "CreateTask is not worked! There has a running task! Please finish it first!"
            )
            return
        }
        companionTask = CompanionTask(taskChangeListener, assistsEventApi?.getCompanionEventImpl(),this)
        companionTask!!.start(params.companionFinishListener) {}
    }

    private fun createChatTaskAndStart(params: TaskParams.ChatTaskParams) {
        cleanupFinishedChatTasks()
        if (chatTasks[params.taskId]?.isRunning == true) {
            OmniLog.w(
                TAG, "ChatTask is not worked! taskId=${params.taskId} already running"
            )
            return
        }
        val chatTask = ChatTask(taskChangeListener,this)
        chatTasks[params.taskId] = chatTask
        chatTask.start(
            params.taskId,
            params.content,
            params.onMessagePush,
            params.provider,
            params.openClawConfig,
            params.modelOverride,
            params.reasoningEffort
        )
    }


    fun startScheduledVLMOperationTask(
        params: TaskParams.ScheduledVLMOperationTaskParams
    ) {
        finishDoingTask()
        pauseCompanionTaskRunning()
        runningTask = ScheduledVLMOperationTask(
            params.scheduledTaskID,
            assistsEventApi?.getExecutionEventImpl(),
            taskChangeListener,
            params.onMessagePushListener,
            params.needSummary
            ,this
        )
        (runningTask as ScheduledVLMOperationTask).start(
            context, params.goal, params.model, params.maxSteps, params.packageName,
            onTaskFinishListener = {},
            skipGoHome = false  // 定时任务默认从主页开始
        )
    }

    fun finishDoingTask() {
        if (runningTask?.isRunning == true) {
            if (runningTask is VLMOperationTask) {
                (runningTask as VLMOperationTask).finishTask()
            } else {
                runningTask?.finishTask {

                }
            }

        }
    }

    fun stopCompanionTask() {
        stopAllTask()
    }

    private fun stopAllTask() {
        if (runningTask?.isRunning == true) {
            runningTask?.finishTask {}
            return
        }
        companionTask?.finishTask() {}
    }

    fun cancelChatTask(taskId: String? = null) {
        cleanupFinishedChatTasks()
        val targetChatTask = if (taskId.isNullOrBlank()) {
            chatTasks.values.lastOrNull { it.isRunning }
        } else {
            chatTasks[taskId]
        }
        if (targetChatTask?.isRunning == true) {
            targetChatTask.finishTask()
            return
        }
        when (val task = runningTask) {
            is VLMOperationTask -> {
                if (taskId.isNullOrBlank() || task.id == taskId) {
                    task.finishTask()
                }
            }
            else -> {
                if (taskId.isNullOrBlank() || task?.id == taskId) {
                    task?.finishTask {}
                }
            }
        }
    }

    /**
     * 取消等待中或运行中的任务，不检查 isRunning 状态
     * 用于在预执行 delay 期间取消任务
     */
    fun cancelPendingTask(taskId: String? = null) {
        OmniLog.d(TAG, "cancelPendingTask called, runningTask=$runningTask")
        when (runningTask) {
            is VLMOperationTask -> {
                if (!taskId.isNullOrBlank() && runningTask?.id != taskId) {
                    return
                }
                OmniLog.d(TAG, "Cancelling pending VLM task")
                // Use finishTask to trigger onTaskStop and close ready UI (onReadyStartVLMTask)
                (runningTask as VLMOperationTask).finishTask()
            }
            else -> {
                if (!taskId.isNullOrBlank() && runningTask?.id != taskId) {
                    return
                }
                // 兜底：尝试调用 finishDoingTask
                finishDoingTask()
            }
        }
    }

    fun startVLMOperationTask(
        params: TaskParams.VLMOperationTaskParams
    ) {
        finishDoingTask()
        pauseCompanionTaskRunning()
        runningTask = VLMOperationTask(
            assistsEventApi?.getExecutionEventImpl(),
            taskChangeListener,
            params.onMessagePushListener,
            params.needSummary
            ,this
        )
        (runningTask as VLMOperationTask).start(
            context,
            params.goal,
            params.model,
            params.maxSteps,
            params.packageName,
            params.onTaskFinishListener,
            params.skipGoHome,
            params.stepSkillGuidance
        )
    }

    fun pauseCompanionTaskRunning() {
        if (companionTask?.isRunning == true) {
            // 有陪伴模式：暂停陪伴模式
            companionTask?.pauseTask()
        }
    }

    fun cancelScheduledTask() {
        if (scheduledTask?.isRunning == true) {
            scheduledTask?.finishTask()
        }

    }

    /**
     * 提供用户输入给正在运行的VLM任务，用于响应INFO动作
     */
    fun provideUserInputToVLMTask(userInput: String): Boolean {
        if (runningTask?.isRunning == true && runningTask is VLMOperationTask) {
            OmniLog.d(TAG, "提供用户输入给VLM任务：$userInput")
            (runningTask as VLMOperationTask).provideUserInput(userInput)
            return true
        }
        OmniLog.w(TAG, "没有正在运行的VLM任务，无法提供用户输入")
        return false
    }

    fun appendVlmExternalMemory(memory: String): Boolean {
        if (runningTask?.isRunning == true && runningTask is VLMOperationTask) {
            OmniLog.d(TAG, "追加VLM外部记忆：$memory")
            return (runningTask as VLMOperationTask).appendExternalMemory(memory)
        }
        OmniLog.w(TAG, "没有正在运行的VLM任务，无法追加外部记忆")
        return false
    }

    /**
     * Append a priority event to the VLM task
     * @param memory The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun appendVlmPriorityEvent(memory: String, eventType: String, suggestCompletion: Boolean = false): Boolean {
        if (runningTask?.isRunning == true && runningTask is VLMOperationTask) {
            OmniLog.d(TAG, "追加VLM优先事件：type=$eventType, suggestCompletion=$suggestCompletion, msg=$memory")
            return (runningTask as VLMOperationTask).appendPriorityEvent(memory, eventType, suggestCompletion)
        }
        OmniLog.w(TAG, "没有正在运行的VLM任务，无法追加优先事件")
        return false
    }


    /**
     * 通知VLM任务或ExecutionTask总结Sheet已准备就绪
     */
    fun notifySummarySheetReady(): Boolean {
        when (runningTask) {
            is VLMOperationTask -> {
                OmniLog.d(TAG, "通知VLM任务总结Sheet已准备就绪")
                (runningTask as VLMOperationTask).notifySummarySheetReady()
                return true
            }
            else -> {
                OmniLog.w(TAG, "没有任务，无法通知总结Sheet就绪")
                return false
            }
        }
    }

    suspend fun changeScheduledStates(states: ScheduledStates) {
        if (scheduledTask?.isRunning == true) {
            scheduledTask?.setStates(states)
        }
    }

    fun getScheduleStatus(): ScheduledStates? {
        return scheduledTask?.getStates()
    }

    fun getScheduleParams(): ScheduledParams? {
        return scheduledTask?.getScheduledParams()
    }

    fun clearScheduleTask() {
        if (scheduledTask?.isRunning == true) {
            throw IllegalStateException("There has a running scheduled task! Please finish it first!")
        }
        scheduledTask = null
    }

    fun doScheduleNow() {
        if (scheduledTask?.isRunning == true) {
            scheduledTask?.finishTask()
        }
        if (getScheduleParams()?.taskParams != null) {
            createAndStartTask(getScheduleParams()!!.taskParams)
        }
    }


    /**
     * 请求暂停正在运行的VLM任务（用户主动暂停）
     */
    fun pauseVLMTask(): Boolean {
        if (runningTask?.isRunning == true && runningTask is VLMOperationTask) {
            OmniLog.d(TAG, "请求暂停VLM任务")
            (runningTask as VLMOperationTask).requestPause()
            return true
        }
        OmniLog.w(TAG, "没有正在运行的VLM任务，无法暂停")
        return false
    }

    /**
     * 恢复暂停的VLM任务
     */
    fun resumeVLMTask(): Boolean {
        if (runningTask?.isRunning == true && runningTask is VLMOperationTask) {
            OmniLog.d(TAG, "恢复VLM任务")
            (runningTask as VLMOperationTask).resumeFromPause()
            return true
        }
        OmniLog.w(TAG, "没有正在运行的VLM任务，无法恢复")
        return false
    }

    fun hasRunningTask(): Boolean {
        return runningTask?.isRunning == true
    }

    fun unregisterChatTask(taskId: String) {
        chatTasks.remove(taskId)
    }

    private fun cleanupFinishedChatTasks() {
        val iterator = chatTasks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.isRunning) {
                iterator.remove()
            }
        }
    }
}
