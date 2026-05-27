package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.ManualVlmRecordedAction
import cn.com.omnimind.assists.task.vlmserver.ManualVlmTraceRecorder
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

data class HumanTrajectoryLearningResult(
    val success: Boolean,
    val runId: String,
    val name: String,
    val description: String,
    val actionCount: Int,
    val summary: String,
    val errorMessage: String = "",
    val actions: List<ManualVlmRecordedAction> = emptyList()
)

/**
 * Records a full human-operated trajectory and stores it as an Internal RunLog.
 *
 * The app layer owns conversion from this RunLog into an OOB reusable function;
 * this assists-level session only records, completes, and exposes the result so
 * UIKit can finish the session from the floating UI without depending on app.
 */
object HumanTrajectoryLearningSession {
    private const val TAG = "HumanTrajectoryLearningSession"

    private data class ActiveSession(
        val context: Context,
        val runId: String,
        val name: String,
        val description: String,
        val recorder: ManualVlmTraceRecorder,
        val result: CompletableDeferred<HumanTrajectoryLearningResult>
    )

    private val lock = Any()
    private var activeSession: ActiveSession? = null

    fun isActive(): Boolean = synchronized(lock) { activeSession != null }

    fun start(
        context: Context,
        name: String,
        description: String
    ): CompletableDeferred<HumanTrajectoryLearningResult> {
        val normalizedName = name.trim().ifEmpty { "人工学习轨迹" }
        val normalizedDescription = description.trim().ifEmpty { normalizedName }
        val appContext = context.applicationContext ?: context
        val runId = "human_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<HumanTrajectoryLearningResult>()
        val recorder = ManualVlmTraceRecorder(appContext, "human_trajectory:$runId")
        synchronized(lock) {
            if (activeSession != null) {
                throw IllegalStateException("已有人工轨迹学习正在进行")
            }
            InternalRunLogStore.beginRun(
                context = appContext,
                runId = runId,
                goal = normalizedDescription,
                source = "human_trajectory",
                toolName = "human_trajectory",
                operationDescription = normalizedName
            )
            if (!recorder.start()) {
                InternalRunLogStore.finishRun(
                    context = appContext,
                    runId = runId,
                    success = false,
                    doneReason = "recorder_unavailable",
                    errorMessage = "无障碍服务未就绪，无法学习轨迹"
                )
                deferred.completeExceptionally(
                    IllegalStateException("无障碍服务未就绪，无法学习轨迹")
                )
                return deferred
            }
            activeSession = ActiveSession(
                context = appContext,
                runId = runId,
                name = normalizedName,
                description = normalizedDescription,
                recorder = recorder,
                result = deferred
            )
        }
        OmniLog.d(TAG, "human trajectory learning started: $runId")
        return deferred
    }

    fun completeActive(): Boolean {
        val session = synchronized(lock) {
            activeSession.also { activeSession = null }
        } ?: return false
        val trace = runCatching { session.recorder.stop() }
            .getOrElse { error ->
                InternalRunLogStore.finishRun(
                    context = session.context,
                    runId = session.runId,
                    success = false,
                    doneReason = "recording_failed",
                    errorMessage = error.message.orEmpty()
                )
                session.result.complete(
                    HumanTrajectoryLearningResult(
                        success = false,
                        runId = session.runId,
                        name = session.name,
                        description = session.description,
                        actionCount = 0,
                        summary = "",
                        errorMessage = error.message.orEmpty(),
                        actions = emptyList()
                    )
                )
                OmniLog.w(TAG, "human trajectory learning failed: ${error.message}")
                return true
            }

        val cards = trace.actions.mapIndexed { index, action ->
            buildRunLogCard(session.runId, index + 1, action)
        }
        if (cards.isNotEmpty()) {
            InternalRunLogStore.appendCards(session.context, session.runId, cards)
        }
        val success = trace.actions.isNotEmpty()
        val doneReason = if (success) "user_completed" else "empty_recording"
        val errorMessage = if (success) null else "未记录到可复用的人类操作"
        InternalRunLogStore.finishRun(
            context = session.context,
            runId = session.runId,
            success = success,
            doneReason = doneReason,
            errorMessage = errorMessage
        )
        session.result.complete(
            HumanTrajectoryLearningResult(
                success = success,
                runId = session.runId,
                name = session.name,
                description = session.description,
                actionCount = trace.actionCount,
                summary = trace.summary,
                errorMessage = errorMessage.orEmpty(),
                actions = trace.actions
            )
        )
        OmniLog.d(TAG, "human trajectory learning completed: ${session.runId} actions=${trace.actionCount}")
        return true
    }

    fun cancelActive(message: String = "人工轨迹学习已取消"): Boolean {
        val session = synchronized(lock) {
            activeSession.also { activeSession = null }
        } ?: return false
        runCatching { session.recorder.stop() }
        InternalRunLogStore.finishRun(
            context = session.context,
            runId = session.runId,
            success = false,
            doneReason = "cancelled",
            errorMessage = message
        )
        session.result.complete(
            HumanTrajectoryLearningResult(
                success = false,
                runId = session.runId,
                name = session.name,
                description = session.description,
                actionCount = 0,
                summary = "",
                errorMessage = message,
                actions = emptyList()
            )
        )
        return true
    }

    private fun buildRunLogCard(
        runId: String,
        index: Int,
        action: ManualVlmRecordedAction
    ): Map<String, Any?> {
        val cardId = "$runId-human-$index"
        val durationMs = (action.finishedAtMs - action.startedAtMs).coerceAtLeast(0L)
        val sourceContext = sourceContextForAction(action)
        return linkedMapOf(
            "card_id" to cardId,
            "tool_call_id" to cardId,
            "header" to linkedMapOf<String, Any?>(
                "step_index" to index,
                "title" to action.title,
                "tool_name" to action.actionName,
                "status" to "success",
                "success" to true,
                "duration_ms" to durationMs
            ),
            "step_index" to index,
            "title" to action.title,
            "summary" to action.summary,
            "tool_name" to action.actionName,
            "toolName" to action.actionName,
            "tool_type" to "manual_recording",
            "toolType" to "manual_recording",
            "status" to "success",
            "action_type" to action.actionName,
            "success" to true,
            "duration_ms" to durationMs,
            "started_at_ms" to action.startedAtMs,
            "finished_at_ms" to action.finishedAtMs,
            "package_name" to action.packageName,
            "compile_kind" to "manual_recording",
            "source" to "human_trajectory",
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "tool_call" to linkedMapOf(
                "id" to cardId,
                "name" to action.actionName,
                "arguments" to action.params
            ),
            "params" to action.params,
            "result" to linkedMapOf(
                "message" to action.summary,
                "summary" to action.summary,
                "source" to "human_trajectory",
                "source_context" to sourceContext.takeIf { it.isNotEmpty() }
            ),
            "before" to linkedMapOf(
                "observation_xml" to action.beforeXml,
                "package_name" to action.packageName
            ),
            "after" to linkedMapOf(
                "observation_xml" to action.afterXml,
                "summary" to action.summary,
                "package_name" to action.packageName
            )
        ).filterValues { it != null }
    }

    private fun sourceContextForAction(action: ManualVlmRecordedAction): Map<String, Any?> {
        val beforeXml = action.beforeXml?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val actionMap = linkedMapOf<String, Any?>("tool" to action.actionName)
        action.params.forEach { (key, value) ->
            if (value != null) actionMap[key] = value
        }
        val dstCtx = linkedMapOf<String, Any?>(
            "page" to action.afterXml?.takeIf { it.isNotBlank() },
            "package_name" to action.packageName
        ).filterValues { it != null && it.toString().isNotBlank() }
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to beforeXml,
                "package_name" to action.packageName,
                "require_unique_action_signature" to false
            ).filterValues { it != null && it.toString().isNotBlank() },
            "dst_ctx" to dstCtx.takeIf { it.isNotEmpty() },
            "action" to actionMap,
            "_oob_meta" to linkedMapOf(
                "mode" to "manual_operation_recording",
                "recording_backend" to "accessibility_event"
            )
        ).filterValues { it != null }
    }
}
