package cn.com.omnimind.bot.vlm

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.accessibility.util.ScreenStateUtil
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpTaskManager
import cn.com.omnimind.bot.mcp.TaskState
import cn.com.omnimind.bot.mcp.TaskStatus
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.util.AssistsUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

enum class VlmToolOutcomeStatus {
    FINISHED,
    WAITING_INPUT,
    SCREEN_LOCKED,
    ERROR,
    TIMEOUT,
    CANCELLED
}

data class VlmToolOutcome(
    val taskId: String,
    val goal: String,
    val status: VlmToolOutcomeStatus,
    val message: String,
    val needSummary: Boolean,
    val finishedContent: String? = null,
    val summaryText: String? = null,
    val waitingQuestion: String? = null,
    val errorMessage: String? = null,
    val feedback: String? = null,
    val summaryUnavailable: Boolean = false,
    val recentActivity: List<String> = emptyList(),
    val executionRoute: String = "",
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "taskId" to taskId,
        "goal" to goal,
        "status" to status.name,
        "message" to message,
        "needSummary" to needSummary,
        "finishedContent" to finishedContent,
        "summary" to summaryText,
        "waitingQuestion" to waitingQuestion,
        "errorMessage" to errorMessage,
        "feedback" to feedback,
        "summaryUnavailable" to summaryUnavailable,
        "recentActivity" to recentActivity,
        "executionRoute" to executionRoute,
    )
}

typealias VlmToolProgressReporter = suspend (progress: String, extras: Map<String, Any?>) -> Unit

object VlmToolCoordinator {
    private const val TAG = "[VlmToolCoordinator]"
    private const val SUMMARY_WAIT_GRACE_MS = 20_000L
    private const val PRELAUNCH_OBSERVE_DELAY_MS = 700L
    private const val RECALL_OBSERVE_ATTEMPTS = 8
    private const val RECALL_OBSERVE_INTERVAL_MS = 250L
    private const val MIN_WAIT_TIMEOUT_MS = 30_000L
    private const val MAX_WAIT_TIMEOUT_MS = 600_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun executeNewTask(
        context: Context,
        request: VlmTaskRequest,
        scope: CoroutineScope,
        progressReporter: VlmToolProgressReporter = { _, _ -> }
    ): VlmToolOutcome = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val needSummary = request.needSummary == true

        if (!ScreenStateUtil.isOperable()) {
            val taskState = McpTaskManager.createTask(
                taskId = taskId,
                goal = request.goal,
                status = TaskStatus.SCREEN_LOCKED,
                needSummary = needSummary
            )
            taskState.message = "屏幕锁定，等待解锁"
            taskState.addChatMessage("[SYSTEM] Screen locked, waiting for unlock...")
            emitProgress(
                progressReporter,
                taskId,
                taskState.status,
                "等待解锁",
                mapOf("summary" to "等待用户解锁设备")
            )
            return@withContext taskState.toOutcome(
                status = VlmToolOutcomeStatus.SCREEN_LOCKED,
                message = buildScreenLockedPrompt(taskState, isInitial = true)
            )
        }

        val taskState = McpTaskManager.createTask(
            taskId = taskId,
            goal = request.goal,
            status = TaskStatus.RUNNING,
            needSummary = needSummary
        )
        taskState.vlmRequest = request
        taskState.message = "任务启动中"

        emitProgress(
            progressReporter,
            taskId,
            taskState.status,
            "启动中",
            mapOf("summary" to "正在启动视觉执行任务")
        )

        val (recallGuidance, recallBaseRequest) = buildRecallGuidanceAfterOptionalPrelaunch(
            context = context,
            request = request,
        )
        val augmentedRequest = recallBaseRequest.withRecallGuidance(recallGuidance.guidance)
        taskState.vlmRequest = augmentedRequest
        if (recallGuidance.guidance.isNotBlank()) {
            taskState.executionRoute = "vlm_with_omniflow_recall:${recallGuidance.decision}"
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskId,
                taskState.status,
                "召回增强",
                mapOf(
                    "summary" to "已注入 OmniFlow 历史轨迹提示",
                    "omniflowRecallDecision" to recallGuidance.decision,
                    "omniflowRecall" to recallGuidance.payload,
                )
            )
        }
        tryExecuteRecallHit(
            taskState = taskState,
            goal = request.goal,
            recallGuidance = recallGuidance,
            progressReporter = progressReporter,
            callFunction = { functionId, startStepIndex ->
                OobOmniFlowToolkitService(context).callFunction(
                    mapOf(
                        "function_id" to functionId,
                        "goal" to request.goal,
                        "arguments" to emptyMap<String, Any?>(),
                        "start_step_index" to startStepIndex,
                    )
                )
            },
        )?.let { outcome ->
            McpTaskManager.scheduleTaskCleanup(taskId, scope)
            return@withContext outcome
        }

        val startResult = startVlmTaskInternal(
            context,
            augmentedRequest,
            taskId,
            taskState,
            scope,
            progressReporter
        )
        if (startResult.isFailure) {
            val error = startResult.exceptionOrNull()?.message ?: "Unknown error"
            taskState.status = TaskStatus.ERROR
            taskState.message = error
            taskState.markStateChanged()
            McpTaskManager.scheduleTaskCleanup(taskId, scope)
            emitProgress(
                progressReporter,
                taskId,
                taskState.status,
                "执行失败",
                mapOf("summary" to error)
            )
            return@withContext taskState.toOutcome(
                status = VlmToolOutcomeStatus.ERROR,
                message = error,
                errorMessage = error
            )
        }

        if (needSummary) {
            val notified = notifySummarySheetReadyWithRetry()
            OmniLog.d(TAG, "Summary sheet ready notify(taskId=$taskId) => $notified")
        }

        emitProgress(
            progressReporter,
            taskId,
            taskState.status,
            "执行中",
            mapOf("summary" to "视觉任务执行中")
        )
        return@withContext awaitTask(
            taskId = taskId,
            goal = request.goal,
            progressReporter = progressReporter,
            waitTimeoutMs = request.waitTimeoutMs
        )
    }

    suspend fun waitForTask(
        taskId: String,
        goal: String,
        progressReporter: VlmToolProgressReporter = { _, _ -> }
    ): VlmToolOutcome = withContext(Dispatchers.IO) {
        val waitTimeoutMs = McpTaskManager.getTask(taskId)?.vlmRequest?.waitTimeoutMs
        awaitTask(taskId, goal, progressReporter, waitTimeoutMs)
    }

    suspend fun resumeAfterUnlock(
        context: Context,
        taskId: String,
        taskState: TaskState,
        scope: CoroutineScope,
        progressReporter: VlmToolProgressReporter = { _, _ -> }
    ): VlmToolOutcome = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        emitProgress(
            progressReporter,
            taskId,
            TaskStatus.SCREEN_LOCKED,
            "等待解锁",
            mapOf("summary" to "等待用户解锁设备")
        )
        val waitTimeoutMs = resolveWaitTimeoutMs(taskState.vlmRequest?.waitTimeoutMs)
        while (System.currentTimeMillis() - startTime < waitTimeoutMs) {
            if (ScreenStateUtil.isOperable()) {
                taskState.addChatMessage("[SYSTEM] Screen unlocked, starting task...")
                taskState.status = TaskStatus.RUNNING
                taskState.message = "屏幕已解锁，任务启动中"
                val request = taskState.vlmRequest ?: VlmTaskRequest(
                    goal = taskState.goal,
                    needSummary = taskState.needSummary
                )
                val (recallGuidance, recallBaseRequest) = buildRecallGuidanceAfterOptionalPrelaunch(
                    context = context,
                    request = request,
                )
                val augmentedRequest = recallBaseRequest.withRecallGuidance(recallGuidance.guidance)
                taskState.vlmRequest = augmentedRequest
                if (recallGuidance.guidance.isNotBlank()) {
                    taskState.executionRoute = "vlm_with_omniflow_recall:${recallGuidance.decision}"
                    taskState.markStateChanged()
                }
                tryExecuteRecallHit(
                    taskState = taskState,
                    goal = request.goal,
                    recallGuidance = recallGuidance,
                    progressReporter = progressReporter,
                    callFunction = { functionId, startStepIndex ->
                        OobOmniFlowToolkitService(context).callFunction(
                            mapOf(
                                "function_id" to functionId,
                                "goal" to request.goal,
                                "arguments" to emptyMap<String, Any?>(),
                                "start_step_index" to startStepIndex,
                            )
                        )
                    },
                )?.let { outcome ->
                    McpTaskManager.scheduleTaskCleanup(taskId, scope)
                    return@withContext outcome
                }
                val startResult = startVlmTaskInternal(
                    context,
                    augmentedRequest,
                    taskId,
                    taskState,
                    scope,
                    progressReporter
                )
                if (startResult.isFailure) {
                    val error = startResult.exceptionOrNull()?.message ?: "Unknown error"
                    taskState.status = TaskStatus.ERROR
                    taskState.message = error
                    taskState.markStateChanged()
                    McpTaskManager.scheduleTaskCleanup(taskId, scope)
                    return@withContext taskState.toOutcome(
                        status = VlmToolOutcomeStatus.ERROR,
                        message = error,
                        errorMessage = error
                    )
                }
                if (taskState.needSummary) {
                    val notified = notifySummarySheetReadyWithRetry()
                    OmniLog.d(TAG, "Summary sheet ready notify after unlock(taskId=$taskId) => $notified")
                }
                emitProgress(
                    progressReporter,
                    taskId,
                    TaskStatus.RUNNING,
                    "执行中",
                    mapOf("summary" to "视觉任务执行中")
                )
                return@withContext awaitTask(
                    taskId = taskId,
                    goal = taskState.goal,
                    progressReporter = progressReporter,
                    waitTimeoutMs = request.waitTimeoutMs
                )
            }
            delay(McpTaskManager.POLL_INTERVAL_MS)
        }

        return@withContext taskState.toOutcome(
            status = VlmToolOutcomeStatus.TIMEOUT,
            message = "屏幕未在等待时间内解锁，请用户解锁后重试。"
        )
    }

    private suspend fun awaitTask(
        taskId: String,
        goal: String,
        progressReporter: VlmToolProgressReporter,
        waitTimeoutMs: Long? = null
    ): VlmToolOutcome {
        val startWaitTime = System.currentTimeMillis()
        val resolvedWaitTimeoutMs = resolveWaitTimeoutMs(waitTimeoutMs)
        var lastScreenState = ScreenStateUtil.isOperable()
        var summaryWaitStart: Long? = null
        var lastProgress = ""

        while (System.currentTimeMillis() - startWaitTime < resolvedWaitTimeoutMs) {
            val state = McpTaskManager.getTask(taskId)
                ?: return VlmToolOutcome(
                    taskId = taskId,
                    goal = goal,
                    status = VlmToolOutcomeStatus.ERROR,
                    message = "Task not found: $taskId",
                    needSummary = false,
                    errorMessage = "Task not found: $taskId"
                )

            val currentScreenState = ScreenStateUtil.isOperable()
            if (!currentScreenState && lastScreenState && state.status == TaskStatus.RUNNING) {
                state.status = TaskStatus.SCREEN_LOCKED
                state.message = "屏幕锁定，等待解锁"
                state.addChatMessage("[SYSTEM] Screen locked, waiting for unlock...")
                state.markStateChanged()
            } else if (currentScreenState && !lastScreenState && state.status == TaskStatus.SCREEN_LOCKED) {
                state.status = TaskStatus.RUNNING
                state.message = "屏幕解锁，任务继续"
                state.addChatMessage("[SYSTEM] Screen unlocked, task resuming")
                state.markStateChanged()
            }
            lastScreenState = currentScreenState

            val progress = when (state.status) {
                TaskStatus.RUNNING -> when {
                    state.message.contains("总结", ignoreCase = false) -> "总结生成中"
                    else -> "执行中"
                }
                TaskStatus.WAITING_INPUT -> "等待用户输入"
                TaskStatus.SCREEN_LOCKED -> "等待解锁"
                TaskStatus.FINISHED -> "已完成"
                TaskStatus.ERROR -> "执行失败"
                TaskStatus.CANCELLED -> "已取消"
                TaskStatus.USER_PAUSED -> "等待用户继续"
            }
            if (progress != lastProgress) {
                emitProgress(
                    progressReporter,
                    taskId,
                    state.status,
                    progress,
                    mapOf(
                        "summary" to state.message.ifBlank { progress },
                        "waitingQuestion" to state.waitingQuestion,
                        "finishedContent" to state.finishedContent,
                        "summaryUnavailable" to state.summaryUnavailable
                    )
                )
                lastProgress = progress
            }

            when (state.status) {
                TaskStatus.FINISHED -> {
                    if (state.needSummary && state.summaryText.isNullOrBlank() && !state.summaryUnavailable) {
                        if (summaryWaitStart == null) {
                            summaryWaitStart = System.currentTimeMillis()
                        }
                        if (System.currentTimeMillis() - summaryWaitStart < SUMMARY_WAIT_GRACE_MS) {
                            delay(McpTaskManager.POLL_INTERVAL_MS)
                            continue
                        }
                        state.summaryUnavailable = true
                        state.markStateChanged()
                    }
                    return state.toOutcome(VlmToolOutcomeStatus.FINISHED)
                }
                TaskStatus.ERROR -> {
                    return state.toOutcome(
                        status = VlmToolOutcomeStatus.ERROR,
                        message = state.message.ifBlank { "任务执行失败" },
                        errorMessage = state.message.ifBlank { "任务执行失败" }
                    )
                }
                TaskStatus.CANCELLED -> {
                    return state.toOutcome(
                        status = VlmToolOutcomeStatus.CANCELLED,
                        message = state.message.ifBlank { "任务已取消" },
                        errorMessage = state.message.ifBlank { "任务已取消" }
                    )
                }
                TaskStatus.WAITING_INPUT, TaskStatus.USER_PAUSED -> {
                    return state.toOutcome(
                        status = VlmToolOutcomeStatus.WAITING_INPUT,
                        message = state.waitingQuestion ?: state.message.ifBlank { "请提供继续执行所需的信息。" },
                        waitingQuestion = state.waitingQuestion ?: state.message
                    )
                }
                TaskStatus.SCREEN_LOCKED -> {
                    return state.toOutcome(
                        status = VlmToolOutcomeStatus.SCREEN_LOCKED,
                        message = buildScreenLockedPrompt(state, isInitial = false)
                    )
                }
                TaskStatus.RUNNING -> delay(McpTaskManager.POLL_INTERVAL_MS)
            }
        }

        val state = McpTaskManager.getTask(taskId)
        if (state?.status == TaskStatus.FINISHED) {
            return state.toOutcome(VlmToolOutcomeStatus.FINISHED)
        }
        return (state ?: TaskState(taskId = taskId, goal = goal, status = TaskStatus.RUNNING)).toOutcome(
            status = VlmToolOutcomeStatus.TIMEOUT,
            message = "任务在等待时间内仍未结束，仍在设备上继续执行。"
        )
    }

    internal fun resolveWaitTimeoutMs(requestedWaitTimeoutMs: Long?): Long {
        val requested = requestedWaitTimeoutMs?.takeIf { it > 0L }
            ?: return McpTaskManager.MAX_WAIT_TIME_MS
        return requested.coerceIn(MIN_WAIT_TIMEOUT_MS, MAX_WAIT_TIMEOUT_MS)
    }

    private suspend fun startVlmTaskInternal(
        context: Context,
        payload: VlmTaskRequest,
        taskId: String,
        taskState: TaskState,
        scope: CoroutineScope,
        progressReporter: VlmToolProgressReporter
    ): Result<Unit> {
        val deferred = CompletableDeferred<Result<Unit>>()
        mainHandler.post {
            scope.launch(Dispatchers.Main) {
                try {
                    AssistsUtil.Core.createVLMOperationTask(
                        context = context,
                        goal = payload.goal,
                        model = payload.model,
                        maxSteps = payload.maxSteps,
                        packageName = payload.packageName,
                        onMessagePushListener = buildListener(
                            taskId,
                            taskState,
                            scope,
                            progressReporter
                        ),
                        needSummary = payload.needSummary ?: false,
                        skipGoHome = payload.skipGoHome,
                        stepSkillGuidance = payload.stepSkillGuidance,
                        taskId = taskId,
                    )
                    deferred.complete(Result.success(Unit))
                } catch (e: Exception) {
                    taskState.status = TaskStatus.ERROR
                    taskState.message = e.message ?: "Unknown error"
                    taskState.markStateChanged()
                    deferred.complete(Result.failure(e))
                }
            }
        }
        return deferred.await()
    }

    private fun buildListener(
        taskId: String,
        taskState: TaskState,
        scope: CoroutineScope,
        progressReporter: VlmToolProgressReporter
    ): OnMessagePushListener {
        return object : OnMessagePushListener {
            override suspend fun onChatMessage(taskID: String, content: String, type: String?) {
                if (type == "summary_start" || isSummaryMessage(taskID)) {
                    taskState.message = if (type == "summary_start") "总结生成中" else taskState.message
                    val summary = extractSummaryText(content) ?: content
                    if (summary.isNotBlank()) {
                        taskState.updateSummary(summary)
                        taskState.message = "总结已生成"
                    }
                    taskState.markStateChanged()
                    return
                }
                if (content.isNotBlank()) {
                    taskState.addChatMessage(content)
                    taskState.markStateChanged()
                }
            }

            override suspend fun onChatMessageEnd(taskID: String) {
                if (isSummaryMessage(taskID) && taskState.needSummary && taskState.summaryText.isNullOrBlank()) {
                    taskState.summaryUnavailable = true
                    taskState.markStateChanged()
                }
            }

            override fun onTaskFinish() {
                fallbackMarkFinished(taskState)
                McpTaskManager.scheduleTaskCleanup(taskId, scope)
            }

            override fun onVLMTaskFinish() {
                fallbackMarkFinished(taskState)
                McpTaskManager.scheduleTaskCleanup(taskId, scope)
            }

            override fun onVLMRequestUserInput(question: String) {
                taskState.status = TaskStatus.WAITING_INPUT
                taskState.waitingQuestion = question
                taskState.message = "等待用户输入"
                taskState.addChatMessage("[AGENT QUESTION] $question")
                taskState.markStateChanged()
            }

            override fun onVlmToolEvent(event: Map<String, Any?>) {
                val summary = listOf(
                    event["summary"],
                    event["progress"],
                    event["toolTitle"],
                    event["toolName"]
                ).firstNotNullOfOrNull { raw ->
                    raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }.orEmpty()
                if (summary.isNotBlank()) {
                    taskState.message = summary
                    taskState.markStateChanged()
                }
                scope.launch {
                    progressReporter(
                        summary.ifBlank { "视觉任务执行中" },
                        linkedMapOf<String, Any?>().apply {
                            putAll(event)
                            put(
                                "agentStreamKind",
                                event["agentStreamKind"]?.toString()
                                    ?: event["kind"]?.toString()
                                    ?: "tool_progress"
                            )
                            put("vlmTaskId", taskId)
                            put("runLogId", event["runLogId"] ?: taskId)
                        }
                    )
                }
            }

            override fun onVlmTaskResult(result: VlmTaskTerminalResult) {
                taskState.applyTerminalResult(result)
                if (result.status != cn.com.omnimind.assists.api.bean.VlmTaskTerminalStatus.WAITING_INPUT) {
                    McpTaskManager.scheduleTaskCleanup(taskId, scope)
                }
            }
        }
    }

    internal suspend fun buildRecallGuidanceAfterOptionalPrelaunch(
        context: Context,
        request: VlmTaskRequest,
    ): Pair<VlmRecallGuidance, VlmTaskRequest> {
        val targetPackage = request.packageName?.trim().orEmpty()
        if (request.disableOmniFlowRecall) {
            return VlmRecallGuidance(
                decision = "disabled",
                guidance = "",
                payload = mapOf(
                    "success" to false,
                    "recall_disabled" to true,
                    "reason" to "request_disable_omniflow_recall",
                ),
            ) to request
        }
        val shouldPrelaunchForRecall = targetPackage.isNotEmpty() && !request.skipGoHome
        val observedRequest = if (shouldPrelaunchForRecall) {
            val launched = runCatching {
                AccessibilityController.launchApplication(targetPackage) { _, _ -> }
            }.onFailure { error ->
                OmniLog.w(TAG, "Recall prelaunch failed target=$targetPackage error=${error.message}")
            }.isSuccess
            if (launched) {
                delay(PRELAUNCH_OBSERVE_DELAY_MS)
                request.copy(skipGoHome = true)
            } else {
                request
            }
        } else {
            request
        }
        val observation = waitForRecallObservation(
            targetPackage = targetPackage.takeIf { shouldPrelaunchForRecall }
        )
        val currentPackage = observation.packageName
        val guidance = VlmRecallGuidanceBuilder.build(
            context = context,
            goal = request.goal,
            targetPackageName = request.packageName,
            currentPackageName = currentPackage,
            currentXml = observation.xml,
        )
        return guidance to observedRequest
    }

    private data class RecallObservation(
        val packageName: String?,
        val xml: String?,
    )

    private suspend fun waitForRecallObservation(targetPackage: String?): RecallObservation {
        var lastPackage: String? = null
        var lastXml: String? = null
        repeat(RECALL_OBSERVE_ATTEMPTS) { attempt ->
            val currentPackage = runCatching { AccessibilityController.getPackageName() }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val currentXml = runCatching { AccessibilityController.getCaptureScreenShotXml(true) }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (currentPackage != null) lastPackage = currentPackage
            if (currentXml != null) lastXml = currentXml
            val packageReady = targetPackage.isNullOrBlank() || currentPackage == targetPackage
            if (packageReady && currentXml != null) {
                return RecallObservation(currentPackage, currentXml)
            }
            if (attempt < RECALL_OBSERVE_ATTEMPTS - 1) {
                delay(RECALL_OBSERVE_INTERVAL_MS)
            }
        }
        return RecallObservation(lastPackage, lastXml)
    }

    private fun fallbackMarkFinished(taskState: TaskState) {
        if (taskState.status == TaskStatus.RUNNING) {
            taskState.status = TaskStatus.FINISHED
            taskState.message = taskState.finishedContent ?: "任务完成"
            taskState.finishedContent = taskState.finishedContent ?: taskState.message
            taskState.markStateChanged()
        }
    }

    private suspend fun emitProgress(
        reporter: VlmToolProgressReporter,
        taskId: String,
        status: TaskStatus,
        progress: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        reporter(
            progress,
            linkedMapOf(
                "taskId" to taskId,
                "status" to status.name,
                "summary" to progress
            ) + extras
        )
    }

    internal suspend fun tryExecuteRecallHit(
        taskState: TaskState,
        goal: String,
        recallGuidance: VlmRecallGuidance,
        progressReporter: VlmToolProgressReporter,
        callFunction: suspend (String, Int) -> Map<String, Any?>,
    ): VlmToolOutcome? {
        val functionId = recallGuidance.directHitFunctionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        emitProgress(
            progressReporter,
            taskState.taskId,
            taskState.status,
            "召回执行",
            mapOf(
                "summary" to "命中可直接执行的 OmniFlow Function",
                "omniflowRecallDecision" to recallGuidance.decision,
                "functionId" to functionId,
                "segmentStartStepIndex" to recallGuidance.directHitStartStepIndex.takeIf { it > 0 },
            )
        )
        val result = runCatching { callFunction(functionId, recallGuidance.directHitStartStepIndex) }.getOrElse { error ->
            linkedMapOf<String, Any?>(
                "success" to false,
                "fallback" to true,
                "error" to error.message.orEmpty(),
                "error_type" to error.javaClass.name,
            )
        }
        val success = result["success"] == true && result["fallback"] != true
        if (!success) {
            taskState.executionRoute = "vlm_with_omniflow_recall_fallback:${recallGuidance.decision}"
            taskState.addChatMessage(
                "[SYSTEM] OmniFlow recall hit $functionId could not complete locally; continuing with VLM. reason=${recallFallbackReason(result)}"
            )
            taskState.markStateChanged()
            emitProgress(
                progressReporter,
                taskState.taskId,
                taskState.status,
                "召回回退",
                mapOf(
                    "summary" to "召回 Function 未能本地完成，继续视觉执行",
                    "functionId" to functionId,
                    "omniflowRecallResult" to result,
                )
            )
            return null
        }

        val runId = result["run_id"]?.toString()?.trim().orEmpty()
        val actionsExecuted = result["actions_executed"]?.toString()?.trim().orEmpty()
        val message = buildString {
            append("已通过召回 Function 完成: ")
            append(functionId)
            if (runId.isNotEmpty()) append(" (run_id=$runId)")
        }
        taskState.status = TaskStatus.FINISHED
        taskState.message = message
        taskState.finishedContent = message
        taskState.summaryText = listOfNotNull(
            "OmniFlow recall hit executed successfully.",
            "function_id=$functionId",
            recallGuidance.directHitStartStepIndex.takeIf { it > 0 }?.let { "segment_start_step_index=$it" },
            runId.takeIf { it.isNotEmpty() }?.let { "run_id=$it" },
            actionsExecuted.takeIf { it.isNotEmpty() }?.let { "actions_executed=$it" },
        ).joinToString("\n")
        taskState.executionRoute = if (recallGuidance.directHitStartStepIndex > 0) {
            "omniflow_recall_segment_hit:$functionId:${recallGuidance.directHitStartStepIndex}"
        } else {
            "omniflow_recall_hit:$functionId"
        }
        taskState.addChatMessage("[SYSTEM] $message")
        taskState.markStateChanged()
        emitProgress(
            progressReporter,
            taskState.taskId,
            taskState.status,
            "执行完成",
            mapOf(
                "summary" to message,
                "functionId" to functionId,
                "segmentStartStepIndex" to recallGuidance.directHitStartStepIndex.takeIf { it > 0 },
                "omniflowRecallResult" to result,
            )
        )
        return taskState.toOutcome(VlmToolOutcomeStatus.FINISHED)
    }

    private fun recallFallbackReason(result: Map<String, Any?>): String =
        listOf(
            result["error"],
            result["error_message"],
            (result["control"] as? Map<*, *>)?.get("fallback_reason"),
            result["phase"],
        ).firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } ?: "unknown"

    private fun TaskState.toOutcome(
        status: VlmToolOutcomeStatus,
        message: String = this.message,
        waitingQuestion: String? = this.waitingQuestion,
        errorMessage: String? = null
    ): VlmToolOutcome {
        return VlmToolOutcome(
            taskId = taskId,
            goal = goal,
            status = status,
            message = message.ifBlank {
                waitingQuestion
                    ?: finishedContent
                    ?: errorMessage
                    ?: "任务状态: ${this.status.name}"
            },
            needSummary = needSummary,
            finishedContent = finishedContent,
            summaryText = summaryText,
            waitingQuestion = waitingQuestion,
            errorMessage = errorMessage,
            feedback = feedback,
            summaryUnavailable = summaryUnavailable,
            recentActivity = chatMessages.takeLast(5),
            executionRoute = executionRoute,
        )
    }

    private fun isSummaryMessage(taskId: String): Boolean {
        return taskId.lowercase().startsWith("vlm-summary-")
    }

    private fun extractSummaryText(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null
        }
        return try {
            val json = JSONObject(trimmed)
            json.optString("text", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildScreenLockedPrompt(state: TaskState, isInitial: Boolean): String {
        return if (isInitial) {
            """设备当前处于锁屏或熄屏状态，VLM 任务暂时无法开始。请先让用户解锁手机，然后重新继续任务。""".trimIndent()
        } else {
            """设备在执行过程中进入锁屏或熄屏状态。请先让用户解锁手机，然后继续当前任务。""".trimIndent()
        }
    }

    private suspend fun notifySummarySheetReadyWithRetry(): Boolean {
        var notified = AssistsUtil.Core.notifySummarySheetReady()
        if (notified) return true
        repeat(3) {
            delay(300L)
            notified = AssistsUtil.Core.notifySummarySheetReady()
            if (notified) return true
        }
        return false
    }

    private fun VlmTaskRequest.withRecallGuidance(guidance: String): VlmTaskRequest {
        val trimmed = guidance.trim()
        if (trimmed.isEmpty()) return this
        val existing = stepSkillGuidance.trim()
        return copy(
            stepSkillGuidance = listOf(existing, trimmed)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        )
    }
}
