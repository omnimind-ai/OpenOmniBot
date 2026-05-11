package cn.com.omnimind.assists.api.bean

import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.baselib.util.OmniLog

enum class TaskRunLogPhase {
    STARTED,
    STOPPED
}

data class TaskRunLogEvent(
    val taskId: String,
    val taskType: TaskType,
    val phase: TaskRunLogPhase,
    val goal: String = "",
    val source: String = "",
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val finishType: TaskFinishType? = null,
    val message: String = "",
    val metadata: Map<String, String> = emptyMap()
)

interface TaskRunLogListener {
    fun onTaskRunLogEvent(event: TaskRunLogEvent)
}

object TaskRunLogEventBus {
    @Volatile
    var listener: TaskRunLogListener? = null

    fun emit(event: TaskRunLogEvent) {
        runCatching {
            listener?.onTaskRunLogEvent(event)
        }.onFailure {
            OmniLog.w("TaskRunLogEventBus", "emit failed: ${it.message}")
        }
    }
}
