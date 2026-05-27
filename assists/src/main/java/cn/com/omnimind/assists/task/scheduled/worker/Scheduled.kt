package cn.com.omnimind.assists.task.scheduled.worker

import androidx.work.Operation
import cn.com.omnimind.assists.api.bean.TaskParams
import kotlinx.serialization.Serializable

object ScheduledConstants {
    const val TIP_TYPE_KEY = "tip_type"
    const val TASK_DATA_KEY = "task_data"

    const val TYPE_TIP = "type_tip"
    const val TYPE_READY_DO_TASK_TIP = "type_ready_do_task_tip"
    const val TIP_BEFORE_TIME = 30L
    const val TIP_BEFORE_CLOSE_TIME = 3L

    const val READY_DO_TASK_TIP_BEFORE_TIME = 10L
}

enum class ScheduledStates {
    SCHEDULED,
    RUNNING,
    FINISHED,
    CANCELED,
    FAILED
}

data class Scheduled(
    val taskID: String,//任务id
    val tipID: String,//提示任务id
    val readyDoTaskTipID: String,//准备执行任务提示任务id
    val taskOperation: Operation
)

data class ScheduledParams(
    val delayTimes: Long,//延迟时间
    val taskParams: TaskParams,//任务参数
    val isShowTip: Boolean,//是否显示提示
    val isShowReadyDoTaskTip: Boolean,//是否显示准备执行任务提示
    val startTimeStamp: Long//开始时间戳
)

@Serializable
data class ScheduledParamsJson(
    val delayTimes: Long,//延迟时间
    val vlmTaskParams: ScheduledVLMOperationTaskParamsData?,
    val isShowTip: Boolean,//是否显示提示
    val isShowReadyDoTaskTip: Boolean,//是否显示准备执行任务提示
    val startTimeStamp: Long,//开始nano时间戳
    val startCTimeStamp: Long//开始时间戳
)

@Serializable
data class ScheduledVLMOperationTaskParamsData(
    val goal: String,
    val name: String,
    val subTitle: String?,
    val extraJson: String?,
    val model: String?,
    val maxSteps: Int?,
    val packageName: String?,
    val needSummary: Boolean = false
)

fun ScheduledParams.toScheduledParamsJson(): ScheduledParamsJson = ScheduledParamsJson(
    delayTimes = this.delayTimes,
    vlmTaskParams = if (this.taskParams is TaskParams.ScheduledVLMOperationTaskParams) {
        this.taskParams.toScheduledVLMOperationTaskParamsData()
    } else {
        null
    },
    isShowTip = this.isShowTip,
    isShowReadyDoTaskTip = this.isShowReadyDoTaskTip,
    startTimeStamp = this.startTimeStamp,
    startCTimeStamp = System.currentTimeMillis() - (System.nanoTime() / 1_000_000 - this.startTimeStamp) * 1000
)

fun TaskParams.ScheduledVLMOperationTaskParams.toScheduledVLMOperationTaskParamsData():
        ScheduledVLMOperationTaskParamsData =
    ScheduledVLMOperationTaskParamsData(
        goal = this.goal,
        model = this.model,
        maxSteps = this.maxSteps,
        packageName = this.packageName,
        name = this.name,
        subTitle = this.subTitle,
        extraJson = this.extraJson,
        needSummary = this.needSummary
    )

fun ScheduledVLMOperationTaskParamsData.toScheduledVLMOperationTaskParams(id: String):
        TaskParams.ScheduledVLMOperationTaskParams =
    TaskParams.ScheduledVLMOperationTaskParams(
        name = this.name,
        subTitle = this.subTitle,
        extraJson = this.extraJson,
        goal = this.goal,
        model = this.model,
        maxSteps = this.maxSteps,
        packageName = this.packageName,
        scheduledTaskID = id,
        needSummary = this.needSummary,
        onMessagePushListener = null
    )
