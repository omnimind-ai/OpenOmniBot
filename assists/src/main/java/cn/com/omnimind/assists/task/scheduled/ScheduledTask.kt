package cn.com.omnimind.assists.task.scheduled.worker

import android.content.Context
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.task.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 预约任务
 */
class ScheduledTask(val context: Context,
                    val executionTaskEventApi: ExecutionTaskEventApi?,
                    override val taskChangeListener: TaskChangeListener,
                    taskManager: TaskManager
) :
    Task(taskChangeListener, taskManager) {
    private var states: ScheduledStates = ScheduledStates.SCHEDULED
    private var scheduledTaskManager: ScheduledTaskManager? = null
    private var scheduled: Scheduled? = null
    private var scheduledParams: ScheduledParams? = null
    private var onTaskFinishListener: (() -> Unit)? = null

    suspend fun setStates(states: ScheduledStates) {
        this.states = states
        when (states) {
            ScheduledStates.SCHEDULED -> {
            }

            ScheduledStates.RUNNING -> {

            }

            ScheduledStates.FINISHED -> {
                onTaskStop(TaskFinishType.FINISH, "")
                onTaskDestroy()
            }

            ScheduledStates.CANCELED -> {
                onTaskStop(TaskFinishType.CANCEL, "")
                onTaskDestroy()
            }

            ScheduledStates.FAILED -> {
                onTaskStop(TaskFinishType.ERROR, "")
                onTaskDestroy()
            }
        }
    }

    fun getStates(): ScheduledStates {
        return states
    }

    override fun getTaskType(): TaskType {
        return TaskType.SCHEDULED
    }

    override fun getTaskRunLogGoal(): String {
        return when (val params = scheduledParams?.taskParams) {
            is TaskParams.VLMOperationTaskParams -> params.goal
            is TaskParams.ScheduledVLMOperationTaskParams -> params.goal
            is TaskParams.ChatTaskParams -> params.taskId
            is TaskParams.CompanionTaskParams -> "companion"
            is TaskParams.ScheduledTaskParams -> "scheduled_task"
            null -> "scheduled_task"
        }
    }

    override fun getTaskRunLogSource(): String = "scheduled"

    override fun getTaskRunLogMetadata(): Map<String, String> {
        return buildMap {
            scheduled?.taskID?.takeIf { it.isNotBlank() }?.let { put("work_id", it) }
            scheduledParams?.let {
                put("delay_seconds", it.delayTimes.toString())
                put("state", states.name.lowercase())
                put("nested_task_type", it.taskParams::class.java.simpleName)
            }
        }
    }

    override suspend fun onTaskStarted() {
        super.onTaskStarted()
        scheduledTaskManager = ScheduledTaskManager(context,executionTaskEventApi)
    }

    /**
     * 创建预约任务
     */
    fun start(taskParams: TaskParams, delayTimes: Long,onTaskFinishListener: (() -> Unit)?) {
        this.onTaskFinishListener=onTaskFinishListener
        super.start {
            val isShowTip = delayTimes > ScheduledConstants.TIP_BEFORE_TIME
            val isShowReadyDoTaskTip = delayTimes > ScheduledConstants.READY_DO_TASK_TIP_BEFORE_TIME
            scheduledParams = ScheduledParams(
                delayTimes,
                taskParams,
                isShowTip,
                isShowReadyDoTaskTip,
                System.nanoTime() / 1_000_000
            )
            scheduled = scheduledTaskManager?.scheduleExecutionTask(scheduledParams!!)
            initStateListener()
        }
    }

    fun getOnTaskFinishListener(): (() -> Unit)? {
        return onTaskFinishListener
    }
    fun getScheduledParams(): ScheduledParams? {
        return scheduledParams
    }


   suspend fun initStateListener() {
        // Listen to the worker status through taskOperation
        scheduled?.taskID?.let { workId ->
            try {
                val workIdUuid = UUID.fromString(workId)
                withContext(Dispatchers.Main){
                    val workInfoLiveData =
                        WorkManager.getInstance(context).getWorkInfoByIdLiveData(workIdUuid)
                    workInfoLiveData.observeForever(object : Observer<WorkInfo> {

                        override fun onChanged(value: WorkInfo) {
                            value.state.let { state ->
                                when (state) {
                                    WorkInfo.State.ENQUEUED -> {
                                        taskScope.launch {
                                            setStates(ScheduledStates.SCHEDULED)
                                        }
                                    }

                                    WorkInfo.State.RUNNING -> {
                                        taskScope.launch {
                                            setStates(ScheduledStates.RUNNING)
                                        }
                                    }

                                    WorkInfo.State.FAILED -> {
                                        taskScope.launch {
                                            setStates(ScheduledStates.FAILED)
                                        }
                                    }

                                    WorkInfo.State.CANCELLED -> {
                                        taskScope.launch {
                                            setStates(ScheduledStates.CANCELED)
                                        }
                                    }

                                    WorkInfo.State.BLOCKED -> {
                                        taskScope.launch {
                                            setStates(ScheduledStates.CANCELED)
                                        }
                                    }

                                    else -> {

                                    }
                                }
                            }
                        }
                    })

                }
            } catch (e: IllegalArgumentException) {
            }
        }

    }


    fun finishTask() {
        super.finishTask() {
            scheduledTaskManager?.cancelAllScheduledTasks()
            executionTaskEventApi?.dismissScheduledNotification()
        }
    }


}
