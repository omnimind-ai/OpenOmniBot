package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.HumanTrajectoryLearningResult
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.util.AssistsUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class DebugHumanRunRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val op = intent?.getStringExtra("op")?.trim()?.lowercase().orEmpty()
            .ifBlank { "status" }
        val description = intent?.getStringExtra("description")?.trim().orEmpty()
        val name = intent?.getStringExtra("name")?.trim().orEmpty()
            .ifBlank { description.ifBlank { "人工录制轨迹" } }
        val enableRawTouch = intent?.getBooleanExtra("enableRawTouch", false) == true ||
            intent?.getBooleanExtra("rawTouch", false) == true

        scope.launch {
            val payload = runCatching {
                when (op) {
                    "start" -> startRecording(appContext, name, description, enableRawTouch)
                    "pause" -> pauseRecording()
                    "resume" -> resumeRecording()
                    "finish", "stop", "complete" -> finishRecording(appContext)
                    "cancel" -> cancelRecording(appContext)
                    "status" -> statusPayload()
                    else -> errorPayload("UNKNOWN_OP", "Unsupported op: $op")
                }
            }.getOrElse { error ->
                OmniLog.e(TAG, "debug human run recording failed: ${error.fullMessage()}", error)
                errorPayload(
                    code = "EXCEPTION",
                    message = error.fullMessage(),
                    extra = linkedMapOf(
                        "error_type" to error.javaClass.name,
                        "error_cause_chain" to error.causeChain()
                    )
                )
            }
            writeJson(appContext, resultFileFor(op), payload)
            if (op != "status") {
                writeJson(appContext, STATUS_FILE, statusPayload())
            }
            OmniLog.i(TAG, gson.toJson(payload))
        }
    }

    private suspend fun startRecording(
        context: Context,
        name: String,
        description: String,
        enableRawTouch: Boolean
    ): Map<String, Any?> {
        if (activeResult != null || HumanTrajectoryLearningSession.isActive()) {
            return errorPayload("ALREADY_RECORDING", "A human recording session is already active")
        }
        waitForAccessibility(context)
        val startedAtMs = System.currentTimeMillis()
        val result = HumanTrajectoryLearningSession.start(
            context = context,
            name = name,
            description = description.ifBlank { name },
            enableRawTouch = enableRawTouch
        )
        activeResult = result
        activeStartedAtMs = startedAtMs
        activeName = name
        activeDescription = description.ifBlank { name }
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf(
            "success" to true,
            "phase" to "started",
            "recording_active" to true,
            "name" to activeName,
            "description" to activeDescription,
            "raw_touch_enabled" to enableRawTouch,
            "started_at_ms" to startedAtMs,
            "status" to status,
            "source" to "oob_debug_human_run_recording"
        )
    }

    private suspend fun finishRecording(context: Context): Map<String, Any?> {
        val result = activeResult
            ?: return errorPayload("NO_ACTIVE_RECORDING", "No active human recording session")
        val completed = HumanTrajectoryLearningSession.completeActive()
        if (!completed) {
            activeResult = null
            return errorPayload("NO_ACTIVE_RECORDING", "No active human recording session")
        }
        return awaitResult(context, result, "finished")
    }

    private fun pauseRecording(): Map<String, Any?> {
        val paused = HumanTrajectoryLearningSession.pauseActive()
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf(
            "success" to paused,
            "phase" to "paused",
            "recording_active" to status["recording_active"],
            "recording_paused" to status["recording_paused"],
            "status" to status,
            "error_code" to if (paused) null else "NO_ACTIVE_RECORDING",
            "error_message" to if (paused) null else "No active human recording session",
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private fun resumeRecording(): Map<String, Any?> {
        val resumed = HumanTrajectoryLearningSession.resumeActive()
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf(
            "success" to resumed,
            "phase" to "recording",
            "recording_active" to status["recording_active"],
            "recording_paused" to status["recording_paused"],
            "status" to status,
            "error_code" to if (resumed) null else "NO_ACTIVE_RECORDING",
            "error_message" to if (resumed) null else "No active human recording session",
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private suspend fun cancelRecording(context: Context): Map<String, Any?> {
        val result = activeResult
        val cancelled = HumanTrajectoryLearningSession.cancelActive("人工轨迹学习已取消")
        if (!cancelled || result == null) {
            activeResult = null
            return errorPayload("NO_ACTIVE_RECORDING", "No active human recording session")
        }
        return awaitResult(context, result, "cancelled")
    }

    private suspend fun awaitResult(
        context: Context,
        result: CompletableDeferred<HumanTrajectoryLearningResult>,
        phase: String
    ): Map<String, Any?> {
        val learningResult = withTimeoutOrNull(RESULT_TIMEOUT_MS) {
            result.await()
        } ?: run {
            activeResult = null
            return errorPayload("RESULT_TIMEOUT", "Timed out waiting for human recording result")
        }
        activeResult = null
        activeStartedAtMs = 0L
        activeName = ""
        activeDescription = ""
        val runLog = InternalRunLogStore.timelinePayload(context, learningResult.runId)
        return linkedMapOf(
            "success" to learningResult.success,
            "phase" to phase,
            "recording_active" to false,
            "run_id" to learningResult.runId,
            "name" to learningResult.name,
            "description" to learningResult.description,
            "action_count" to learningResult.actionCount,
            "summary" to learningResult.summary,
            "diagnostics" to learningResult.diagnostics.takeIf { it.isNotEmpty() },
            "error_message" to learningResult.errorMessage,
            "run_log" to runLog,
            "token_usage_total" to 0,
            "source" to "oob_debug_human_run_recording"
        )
    }

    private suspend fun waitForAccessibility(context: Context) {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        repeat(50) {
            if (AssistsService.instance != null && AccessibilityController.initController()) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    private fun statusPayload(): Map<String, Any?> {
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf(
            "success" to true,
            "phase" to "status",
            "recording_active" to (
                activeResult != null ||
                    (status["recording_active"] as? Boolean == true)
                ),
            "recording_paused" to status["recording_paused"],
            "run_id" to status["run_id"],
            "name" to (status["name"] ?: activeName),
            "description" to (status["description"] ?: activeDescription),
            "started_at_ms" to (status["started_at_ms"] ?: activeStartedAtMs.takeIf { it > 0L }),
            "action_count" to status["action_count"],
            "latest_action_summary" to status["latest_action_summary"],
            "pending_action_summary" to status["pending_action_summary"],
            "recording_backend" to status["recording_backend"],
            "raw_touch_enabled" to status["raw_touch_enabled"],
            "raw_touch_available" to status["raw_touch_available"],
            "status" to status,
            "source" to "oob_debug_human_run_recording"
        ).filterValues { it != null }
    }

    private fun errorPayload(
        code: String,
        message: String,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "recording_active" to (activeResult != null || HumanTrajectoryLearningSession.isActive()),
        "source" to "oob_debug_human_run_recording"
    ).apply {
        putAll(extra)
    }

    private fun resultFileFor(op: String): String = when (op) {
        "start" -> START_FILE
        "status" -> STATUS_FILE
        else -> RESULT_FILE
    }

    private fun writeJson(context: Context, fileName: String, payload: Map<String, Any?>) {
        File(context.filesDir, fileName).writeText(gson.toJson(payload))
    }

    private fun Throwable.fullMessage(): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            parts += current.message?.takeIf(String::isNotBlank)
                ?.let { "${current.javaClass.name}: $it" }
                ?: current.javaClass.name
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    private fun Throwable.causeChain(): List<Map<String, String>> {
        val output = mutableListOf<Map<String, String>>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            output += linkedMapOf(
                "type" to current.javaClass.name,
                "message" to current.message.orEmpty()
            )
            current = current.cause
        }
        return output
    }

    companion object {
        private const val TAG = "DebugHumanRunRecordingReceiver"
        const val START_FILE = "debug-human-run-recording-start.json"
        const val RESULT_FILE = "debug-human-run-recording-result.json"
        const val STATUS_FILE = "debug-human-run-recording-status.json"
        private const val RESULT_TIMEOUT_MS = 15_000L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var activeResult: CompletableDeferred<HumanTrajectoryLearningResult>? = null
        @Volatile private var activeStartedAtMs: Long = 0L
        @Volatile private var activeName: String = ""
        @Volatile private var activeDescription: String = ""
    }
}
