package cn.com.omnimind.assists.task

import android.annotation.SuppressLint
import android.icu.text.SimpleDateFormat
import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.bean.TaskRunLogEvent
import cn.com.omnimind.assists.api.bean.TaskRunLogEventBus
import cn.com.omnimind.assists.api.bean.TaskRunLogPhase
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.api.interfaces.TaskLifeListener
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.omniintelligence.models.RequestHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * 任务基类
 */
abstract class Task(open val taskChangeListener: TaskChangeListener,open val taskManager: TaskManager) : TaskLifeListener {
    private val TAG = "[Task]"

    open var id: String = ""
    val APP_ID: String = "10001"
    var isRunning: Boolean = false
    open var taskScope = CoroutineScope( Dispatchers.IO)
    open val cancelScope = CoroutineScope( Dispatchers.IO)
    private var taskRunLogStartedAtMs: Long = 0L

    fun getRequestHeader(): RequestHeader {
        return RequestHeader(
            requestId = getRequestID(),
            appId = APP_ID,
            taskId = id,
            timestamp = System.currentTimeMillis(),
        )
    }
    //请求id，按照格式req-日期-自增序号命名，比如 req-client-20250912-123，req-server-20250912-123
    @SuppressLint("SimpleDateFormat")
    fun getRequestID(): String {
        val date = Date()
        val format = SimpleDateFormat("yyyyMMdd")
        return "req-client-" + format.format(date) + System.currentTimeMillis()
    }

    abstract fun getTaskType(): TaskType

    open fun getTaskRunLogGoal(): String = ""

    open fun getTaskRunLogSource(): String = getTaskType().name.lowercase()

    open fun getTaskRunLogMetadata(): Map<String, String> = emptyMap()


    open fun finishTask(block: suspend CoroutineScope.() -> Unit) {
        cancelScope.launch {
            block.invoke(this)
            onTaskStop(TaskFinishType.CANCEL,"")
            onTaskDestroy()
        }

    }

    open lateinit var taskJob: Job

    fun start(block: suspend CoroutineScope.() -> Unit) {
        taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        taskJob = taskScope.launch {
            onTaskCreated()
            onTaskStarted()
            block.invoke(this)
        }


    }

    override suspend fun onTaskCreated() {
        OmniLog.i(TAG, " task ready to create...")
    }

    init {
        id = java.util.UUID.randomUUID().toString() + System.currentTimeMillis()
    }


    override suspend fun onTaskStarted() {
        taskChangeListener.onTaskStart(getTaskType(),taskManager)
        OmniLog.i(TAG, " task started...")
        isRunning = true
        if (taskRunLogStartedAtMs <= 0L) {
            taskRunLogStartedAtMs = System.currentTimeMillis()
        }
        emitTaskRunLog(TaskRunLogPhase.STARTED)

    }

    override suspend fun onTaskStop(finishType: TaskFinishType,message:String) {
        taskChangeListener.onTaskStop(getTaskType(),finishType, message,taskManager)

        OmniLog.i(TAG, " task ready to stop...")
        emitTaskRunLog(
            phase = TaskRunLogPhase.STOPPED,
            finishType = finishType,
            message = message
        )
    }

    override suspend fun onTaskDestroy() {
        OmniLog.i(TAG, " task ready to destroy...")
        isRunning = false
        id = ""
    }

    // 新增任务取消的回调方法
    open suspend fun onTaskCancelled() {
        OmniLog.i(TAG, " task was cancelled...")
        isRunning = false
    }

    private fun emitTaskRunLog(
        phase: TaskRunLogPhase,
        finishType: TaskFinishType? = null,
        message: String = ""
    ) {
        val now = System.currentTimeMillis()
        if (taskRunLogStartedAtMs <= 0L) {
            taskRunLogStartedAtMs = now
        }
        TaskRunLogEventBus.emit(
            TaskRunLogEvent(
                taskId = id.ifBlank { "${getTaskType().name.lowercase()}-$now" },
                taskType = getTaskType(),
                phase = phase,
                goal = getTaskRunLogGoal(),
                source = getTaskRunLogSource(),
                startedAtMs = taskRunLogStartedAtMs,
                finishedAtMs = if (phase == TaskRunLogPhase.STOPPED) now else null,
                finishType = finishType,
                message = message,
                metadata = getTaskRunLogMetadata()
            )
        )
    }

}
