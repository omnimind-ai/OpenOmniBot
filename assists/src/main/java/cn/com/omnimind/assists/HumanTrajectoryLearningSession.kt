package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.assists.task.vlmserver.ManualVlmRecordedAction
import cn.com.omnimind.assists.task.vlmserver.ManualVlmTraceRecorder
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
    val actions: List<ManualVlmRecordedAction> = emptyList(),
    val diagnostics: Map<String, Any?> = emptyMap()
)

data class HumanTrajectoryLearningStatus(
    val active: Boolean,
    val paused: Boolean,
    val runId: String? = null,
    val name: String = "",
    val description: String = "",
    val startedAtMs: Long? = null,
    val actionCount: Int = 0,
    val latestActionSummary: String? = null,
    val pendingActionSummary: String? = null,
    val accessibilityEventCount: Int = 0,
    val rawTouchEnabled: Boolean = false,
    val rawTouchAvailable: Boolean = false,
    val overlayTouchRecordedCount: Int = 0,
    val recordingBackend: String = "accessibility_event"
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "active" to active,
        "paused" to paused,
        "recording_active" to active,
        "recording_paused" to paused,
        "run_id" to runId,
        "name" to name,
        "description" to description,
        "started_at_ms" to startedAtMs,
        "action_count" to actionCount,
        "latest_action_summary" to latestActionSummary,
        "pending_action_summary" to pendingActionSummary,
        "accessibility_event_count" to accessibilityEventCount,
        "raw_touch_enabled" to rawTouchEnabled,
        "raw_touch_available" to rawTouchAvailable,
        "overlay_touch_recorded_count" to overlayTouchRecordedCount,
        "recording_backend" to recordingBackend
    ).filterValues { it != null }
}

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
        val startedAtMs: Long,
        val recorder: ManualVlmTraceRecorder,
        val result: CompletableDeferred<HumanTrajectoryLearningResult>
    )

    private val lock = Any()
    private var activeSession: ActiveSession? = null
    private var activePaused: Boolean = false

    fun isActive(): Boolean = synchronized(lock) { activeSession != null }

    fun isPaused(): Boolean = synchronized(lock) { activePaused }

    fun status(): HumanTrajectoryLearningStatus {
        val session = synchronized(lock) { activeSession } ?: return HumanTrajectoryLearningStatus(
            active = false,
            paused = false
        )
        val recorderSnapshot = runCatching { session.recorder.snapshot() }
            .getOrElse { error ->
                OmniLog.w(TAG, "human trajectory status snapshot failed: ${error.message}")
                return HumanTrajectoryLearningStatus(
                    active = true,
                    paused = synchronized(lock) { activePaused },
                    runId = session.runId,
                    name = session.name,
                    description = session.description,
                    startedAtMs = session.startedAtMs
                )
            }
        return HumanTrajectoryLearningStatus(
            active = true,
            paused = synchronized(lock) { activePaused } || recorderSnapshot.isPaused,
            runId = session.runId,
            name = session.name,
            description = session.description,
            startedAtMs = session.startedAtMs,
            actionCount = recorderSnapshot.actionCount,
            latestActionSummary = recorderSnapshot.latestActionSummary,
            pendingActionSummary = recorderSnapshot.pendingActionSummary,
            accessibilityEventCount = recorderSnapshot.accessibilityEventCount,
            rawTouchEnabled = recorderSnapshot.rawTouchEnabled,
            rawTouchAvailable = recorderSnapshot.rawTouchAvailable,
            overlayTouchRecordedCount = recorderSnapshot.overlayTouchRecordedCount,
            recordingBackend = recorderSnapshot.recordingBackend
        )
    }

    /**
     * Returns the active manual recording RunLog id for app-layer evidence writes.
     *
     * The assists module does not own app-only assets such as UDEG nodes; callers
     * use this id only to attach non-replay evidence to the current manual run.
     */
    fun activeRunId(): String? = synchronized(lock) { activeSession?.runId }

    fun start(
        context: Context,
        name: String,
        description: String,
        enableRawTouch: Boolean = false
    ): CompletableDeferred<HumanTrajectoryLearningResult> {
        val normalizedName = name.trim().ifEmpty { "人工学习轨迹" }
        val normalizedDescription = description.trim().ifEmpty { normalizedName }
        val appContext = context.applicationContext ?: context
        val runId = "human_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val startedAtMs = System.currentTimeMillis()
        val deferred = CompletableDeferred<HumanTrajectoryLearningResult>()
        val recorder = ManualVlmTraceRecorder(
            context = appContext,
            sessionLabel = "human_trajectory:$runId",
            enableRawTouch = enableRawTouch
        )
        synchronized(lock) {
            if (activeSession != null) {
                throw IllegalStateException("已有人工轨迹学习正在进行")
            }
            activePaused = false
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
                startedAtMs = startedAtMs,
                recorder = recorder,
                result = deferred
            )
        }
        OmniLog.d(TAG, "human trajectory learning started: $runId")
        return deferred
    }

    /**
     * Suspends action capture for the active manual session.
     *
     * @return True when an active session remains available in paused state.
     */
    fun pauseActive(): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        val paused = session.recorder.pause()
        if (paused) {
            synchronized(lock) { activePaused = true }
        }
        return paused
    }

    /**
     * Resumes capture after refreshing the active recorder's page baseline.
     *
     * @return True when an active session remains available for recording.
     */
    fun resumeActive(): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        val resumed = session.recorder.resume()
        if (resumed) {
            synchronized(lock) { activePaused = false }
        }
        return resumed
    }

    suspend fun recordOverlayGesture(gesture: ManualOverlayTouchGesture): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        if (synchronized(lock) { activePaused }) return false
        return runCatching { session.recorder.recordOverlayGesture(gesture) }
            .getOrElse { error ->
                OmniLog.w(TAG, "manual overlay gesture failed: ${error.message}")
                false
            }
    }

    fun completeActive(): Boolean {
        val session = synchronized(lock) {
            activePaused = false
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
        if (trace.diagnostics.isNotEmpty()) {
            InternalRunLogStore.updateDiagnostics(
                context = session.context,
                runId = session.runId,
                diagnostics = trace.diagnostics
            )
        }
        val hasActions = trace.actions.isNotEmpty()
        val success = hasActions
        val doneReason = if (success) "user_completed" else "empty_recording"
        val errorMessage = when {
            !hasActions -> "未记录到可复用的人类操作"
            else -> null
        }
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
                actions = trace.actions,
                diagnostics = trace.diagnostics
            )
        )
        OmniLog.d(TAG, "human trajectory learning completed: ${session.runId} actions=${trace.actionCount}")
        return true
    }

    fun cancelActive(message: String = "人工轨迹学习已取消"): Boolean {
        val session = synchronized(lock) {
            activePaused = false
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
            "event_context" to action.eventContext.takeIf { it.isNotEmpty() },
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
                "screenshot" to action.beforeScreenshot?.asMap(),
                "screenshot_path" to action.beforeScreenshot?.path,
                "package_name" to action.packageName
            ).filterValues { it != null },
            "after" to linkedMapOf(
                "observation_xml" to action.afterXml,
                "screenshot" to action.afterScreenshot?.asMap(),
                "screenshot_path" to action.afterScreenshot?.path,
                "summary" to action.summary,
                "package_name" to action.packageName
            ).filterValues { it != null }
        ).filterValues { it != null }
    }

    private fun sourceContextForAction(action: ManualVlmRecordedAction): Map<String, Any?> {
        val beforeXml = action.beforeXml?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val recordingBackend = action.params["recording_backend"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val actionMap = linkedMapOf<String, Any?>("tool" to action.actionName)
        action.params.forEach { (key, value) ->
            if (value != null) actionMap[key] = value
        }
        val dstCtx = linkedMapOf<String, Any?>(
            "page" to action.afterXml?.takeIf { it.isNotBlank() },
            "screenshot" to action.afterScreenshot?.asMap(),
            "screenshot_path" to action.afterScreenshot?.path,
            "package_name" to action.packageName
        ).filterValues { it != null && it.toString().isNotBlank() }
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to beforeXml,
                "screenshot" to action.beforeScreenshot?.asMap(),
                "screenshot_path" to action.beforeScreenshot?.path,
                "package_name" to action.packageName,
                "require_unique_action_signature" to false
            ).filterValues { it != null && it.toString().isNotBlank() },
            "dst_ctx" to dstCtx.takeIf { it.isNotEmpty() },
            "action" to actionMap,
            "_oob_meta" to linkedMapOf(
                "mode" to "manual_operation_recording",
                "recording_backend" to recordingBackend,
                "action_source" to recordingBackend,
                "event_context" to action.eventContext.takeIf { it.isNotEmpty() }
            ).filterValues { it != null }
        ).filterValues { it != null }
    }

}
