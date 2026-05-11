package cn.com.omnimind.assists.task.companion

import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.eventapi.CompanionTaskEventApi
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.task.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开源版陪伴任务仅保留基础状态能力，不再承载旧版技能链路逻辑。
 */
class CompanionTask(
    override val taskChangeListener: TaskChangeListener,
    private val companionTaskEventApi: CompanionTaskEventApi?,
    override val taskManager: TaskManager
) : Task(taskChangeListener, taskManager) {

    @Volatile
    private var paused = false

    @Volatile
    private var cancelGoHomeRequested = false

    override fun getTaskType(): TaskType = TaskType.COMPANION

    override fun getTaskRunLogGoal(): String = "companion"

    override fun getTaskRunLogSource(): String = "companion"

    fun start(companionFinishListener: () -> Unit, onTaskFinishListener: () -> Unit) {
        super.start {
            try {
                while (isRunning) {
                    delay(200)
                    if (paused) {
                        delay(200)
                    }
                }
            } catch (_: CancellationException) {
                // ignore
            } finally {
                companionFinishListener.invoke()
                onTaskFinishListener.invoke()
            }
        }
    }

    fun pauseTask() {
        paused = true
    }

    fun resumeTask() {
        paused = false
    }

    fun cancelGoHome() {
        cancelGoHomeRequested = true
    }

    fun isCancelGoHomeRequested(): Boolean = cancelGoHomeRequested

    override fun finishTask(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        cancelScope.launch {
            block.invoke(this)
            taskScope.cancel()
            onTaskStop(TaskFinishType.CANCEL, "")
            onTaskDestroy()
        }
    }
}
