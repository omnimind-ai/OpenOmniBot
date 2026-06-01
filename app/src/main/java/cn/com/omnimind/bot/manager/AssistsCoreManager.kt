package cn.com.omnimind.bot.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.accessibility.api.Constant
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.AgentVlmUiSession
import cn.com.omnimind.assists.AssistsCore
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.OmniFlowUiSession
import cn.com.omnimind.assists.task.vlmserver.ManualVlmRecordedAction
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledStates
import cn.com.omnimind.assists.task.scheduled.worker.ScheduledVLMOperationTaskParamsData
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.TokenUsageRecord
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.AiRequestLogStore
import cn.com.omnimind.baselib.llm.ModelProviderConfig
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.MnnLocalProviderStateStore
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelCatalogResolver
import cn.com.omnimind.baselib.llm.SceneCatalogItem
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneModelOverrideEntry
import cn.com.omnimind.baselib.llm.SceneModelOverrideStore
import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.RuntimeLogStore
import cn.com.omnimind.baselib.util.exception.PermissionException
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.ui.scheduled.ScheduledTaskReminderLoader
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.util.SchemeUtil
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import cn.com.omnimind.bot.agent.AgentImageAttachmentSupport
import cn.com.omnimind.bot.agent.AgentStreamEvent
import cn.com.omnimind.bot.agent.AgentTextSanitizer
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentRunControl
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolExposurePolicy
import cn.com.omnimind.bot.agent.AgentToolProgressSnapshot
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.agent.OmniAgentExecutor
import cn.com.omnimind.bot.agent.SkillIndexEntry
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.UserDialog
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.runlog.OobRunLogReplayService
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.bot.localmodel.LocalModelFeature
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.quicklog.QuickLogService
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcome
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.FlutterChatSyncBridge
import cn.com.omnimind.bot.webchat.RealtimeHub
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import cn.com.omnimind.bot.workbench.WorkbenchDisplayLayoutContext
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.loader.ManualRecordingControlOverlay
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import com.google.gson.Gson
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID
import kotlin.collections.mapOf
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val CHAT_ONLY_MODE = "chat_only"
private const val MAX_PERSISTED_THINKING_CHARS = 16 * 1024
private const val THINKING_TRUNCATION_NOTICE = "[Earlier reasoning omitted]\n"
private const val IDLE_CONSOLIDATION_DELAY_MS = 2 * 60 * 1000L  // 2 min after last task finishes

private val chatTaskPayloadJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun prepareChatTaskContent(
    content: List<Map<String, Any>>,
    conversationMode: String,
    chatPromptContent: String?
): List<Map<String, Any>> {
    val prompt = chatPromptContent?.takeIf { it.trim().isNotEmpty() } ?: return content
    if (!conversationMode.equals(CHAT_ONLY_MODE, ignoreCase = true)) {
        return content
    }
    return buildList {
        add(
            linkedMapOf<String, Any>(
                "role" to "system",
                "content" to prompt
            )
        )
        addAll(content)
    }
}

internal fun resolveChatTaskModelOverride(
    raw: Map<String, Any?>?,
    profileLookup: (String) -> ModelProviderProfile?
): TaskParams.ChatModelOverride? {
    if (raw.isNullOrEmpty()) {
        return null
    }
    val providerProfileId = raw["providerProfileId"]?.toString()?.trim().orEmpty()
    val modelId = raw["modelId"]?.toString()?.trim().orEmpty()
    if (providerProfileId.isEmpty() || modelId.isEmpty()) {
        return null
    }
    val providerProfile = profileLookup(providerProfileId)
    if (providerProfile == null || !providerProfile.isConfigured()) {
        return null
    }
    return TaskParams.ChatModelOverride(
        providerProfileId = providerProfile.id,
        modelId = modelId,
        apiBase = providerProfile.baseUrl,
        apiKey = providerProfile.apiKey,
        protocolType = providerProfile.protocolType.ifEmpty { "openai_compatible" }
    )
}

internal fun normalizeReasoningEffort(raw: String?): String? {
    val normalized = raw?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "no", "low", "high" -> normalized
        else -> null
    }
}

internal data class AgentFinalErrorResolution(
    val text: String,
    val persistAsError: Boolean
)

internal fun sanitizeAgentVisibleText(text: String): String {
    return AgentTextSanitizer.stripTextFunctionCalls(
        AgentTextSanitizer.sanitizeUtf16(text)
    ).trim()
}

internal fun resolveAgentFinalErrorResolution(
    streamed: String,
    error: String,
    localizedFallback: String
): AgentFinalErrorResolution {
    val normalizedStreamed = sanitizeAgentVisibleText(streamed)
    if (normalizedStreamed.isNotEmpty()) {
        return AgentFinalErrorResolution(
            text = normalizedStreamed,
            persistAsError = false
        )
    }

    val normalizedError = sanitizeAgentVisibleText(error)
    val finalText = normalizedError.ifEmpty {
        sanitizeAgentVisibleText(localizedFallback)
    }
    return AgentFinalErrorResolution(
        text = finalText,
        persistAsError = finalText.isNotEmpty()
    )
}

private fun sanitizeInteropValue(value: Any?): Any? {
    return when (value) {
        null -> null
        is String -> AgentTextSanitizer.sanitizeUtf16(value)
        is Map<*, *> -> linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) {
                    put(key.toString(), sanitizeInteropValue(item))
                }
            }
        }
        is List<*> -> value.map(::sanitizeInteropValue)
        else -> value
    }
}

private fun sanitizeInteropMap(payload: Map<String, Any?>): Map<String, Any?> {
    return linkedMapOf<String, Any?>().apply {
        payload.forEach { (key, value) ->
            put(key, sanitizeInteropValue(value))
        }
    }
}

internal const val AGENT_MANUAL_CANCELLATION_SEQUENCE = 1_000_000_000L
internal const val AGENT_MANUAL_CANCELLATION_ROUND = 1_000_000_000

internal fun buildAgentManualCancellationStreamMeta(
    taskId: String,
    entryId: String
): Map<String, Any?> {
    return linkedMapOf(
        "schema_version" to "oob.agent_event.v1",
        "trace_id" to taskId,
        "run_id" to taskId,
        "span_id" to entryId,
        "parent_span_id" to taskId,
        "seq" to AGENT_MANUAL_CANCELLATION_SEQUENCE,
        "roundIndex" to AGENT_MANUAL_CANCELLATION_ROUND,
        "kind" to "text_snapshot",
        "parentTaskId" to taskId,
        "entryId" to entryId,
        "isFinal" to true
    )
}

internal fun extractChatTaskTextPayload(content: String): String {
    val normalized = content.trim()
    if (normalized.isEmpty() || normalized == "[DONE]") {
        return ""
    }
    if (!normalized.startsWith("{") && !normalized.startsWith("[")) {
        return content
    }
    val parsed = runCatching {
        extractChatTaskTextValue(chatTaskPayloadJson.parseToJsonElement(normalized))
    }.getOrElse { "" }
    if (parsed.isNotEmpty()) {
        return parsed
    }

    val contentMatch = Regex(""""(?:content|text)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { raw ->
            runCatching {
                chatTaskPayloadJson.parseToJsonElement(""""$raw"""")
                    .jsonPrimitive
                    .content
            }.getOrDefault(raw)
        }
        .orEmpty()
    return contentMatch
}

private fun extractChatTaskTextValue(raw: JsonElement?): String {
    return when (raw) {
        null, JsonNull -> ""
        is JsonPrimitive -> raw.contentOrNull.orEmpty()
        is JsonArray -> raw.joinToString(separator = "") { item ->
            extractChatTaskTextValue(item)
        }
        is JsonObject -> {
            val directText = extractTextPayload(raw["text"])
            if (directText.isNotEmpty()) {
                return directText
            }

            val outputText = extractTextPayload(raw["output_text"])
            if (outputText.isNotEmpty()) {
                return outputText
            }

            val contentText = extractTextPayload(raw["content"])
            if (contentText.isNotEmpty()) {
                return contentText
            }

            val messageText = extractChatTaskTextValue(raw["message"])
            if (messageText.isNotEmpty()) {
                return messageText
            }

            val choices = raw["choices"] as? JsonArray
            if (choices != null && choices.isNotEmpty()) {
                val firstChoice = choices.firstOrNull() as? JsonObject
                if (firstChoice != null) {
                    val deltaText = extractChatTaskTextValue(firstChoice["delta"])
                    if (deltaText.isNotEmpty()) {
                        return deltaText
                    }

                    val choiceMessageText = extractChatTaskTextValue(firstChoice["message"])
                    if (choiceMessageText.isNotEmpty()) {
                        return choiceMessageText
                    }

                    val choiceText = extractTextPayload(
                        firstChoice["text"] ?: firstChoice["content"]
                    )
                    if (choiceText.isNotEmpty()) {
                        return choiceText
                    }
                }
            }

            val output = raw["output"] as? JsonArray
            if (output != null && output.isNotEmpty()) {
                val outputTextFromList = output.joinToString(separator = "") { item ->
                    extractChatTaskTextValue(item)
                }
                if (outputTextFromList.isNotEmpty()) {
                    return outputTextFromList
                }
            }

            ""
        }
        else -> ""
    }
}

internal fun extractChatTaskPromptTokens(content: String): Int? {
    val normalized = content.trim()
    if (normalized.isEmpty() || normalized == "[DONE]") {
        return null
    }
    return Regex("\"prompt_tokens\"\\s*:\\s*(\\d+)")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun chatModelOverrideToAgentModelOverride(
    modelOverride: TaskParams.ChatModelOverride?
): AgentModelOverride? {
    if (modelOverride == null) {
        return null
    }
    val providerProfileName = runCatching {
        ModelProviderConfigStore.getProfile(modelOverride.providerProfileId)?.name
    }.getOrNull()
    return AgentModelOverride(
        providerProfileId = modelOverride.providerProfileId,
        providerProfileName = providerProfileName,
        modelId = modelOverride.modelId,
        apiBase = modelOverride.apiBase,
        apiKey = modelOverride.apiKey,
        protocolType = modelOverride.protocolType.ifEmpty { "openai_compatible" }
    )
}

private fun extractTextPayload(raw: JsonElement?): String {
    return when (raw) {
        null, JsonNull -> ""
        is JsonPrimitive -> raw.contentOrNull.orEmpty()
        is JsonArray -> raw.joinToString(separator = "") { item ->
            extractTextPayload(item)
        }
        is JsonObject -> when {
            raw["type"]?.jsonPrimitive?.contentOrNull.equals("text", ignoreCase = true) ||
                raw["type"]?.jsonPrimitive?.contentOrNull.equals("output_text", ignoreCase = true) -> {
                extractTextPayload(raw["text"])
            }
            raw.containsKey("text") -> extractTextPayload(raw["text"])
            raw.containsKey("content") -> extractTextPayload(raw["content"])
            else -> ""
        }
        else -> ""
    }
}

internal const val OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL = "completed_local"
internal const val OOB_REUSABLE_EXECUTION_STATUS_STARTED_AGENT_FALLBACK =
    "started_agent_fallback"
internal const val OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_VLM_FALLBACK =
    "completed_vlm_fallback"
internal const val OOB_REUSABLE_EXECUTION_STATUS_FAILED = "failed"
private const val AGENT_STREAM_META_SCHEMA_VERSION = "oob.agent_event.v1"

internal fun isOobReusableFunctionPendingAgentStep(step: Map<*, *>): Boolean {
    return step["needs_agent"] == true ||
        (step["fallback_available"] == true && step["executor"]?.toString() == "agent") ||
        step["blocked_executor"]?.toString() == "tool" ||
        step["blocked_executor"]?.toString() == "omniflow"
}

internal fun buildOobReusableFunctionAgentFallbackPayload(
    functionId: String,
    taskId: String,
    conversationId: Long? = null,
    started: Boolean,
    startErrorCode: String?,
    startErrorMessage: String?,
    runPayload: Map<String, Any?>,
    stepResults: List<Map<*, *>>,
    completedStepCount: Int,
    pendingAgentStepCount: Int,
    argumentCount: Int,
): Map<String, Any?> {
    val executionStatus = if (started) {
        OOB_REUSABLE_EXECUTION_STATUS_STARTED_AGENT_FALLBACK
    } else {
        OOB_REUSABLE_EXECUTION_STATUS_FAILED
    }
    val stepCount = stepResults.size
    val successStepCount = stepResults.count { it["success"] != false }
    val timing = runPayload["timing"]
    val runner = runPayload["runner"] ?: "oob_mixed_runner"
    val sharedExecutionMeta = linkedMapOf<String, Any?>(
        "taskId" to taskId,
        "agent_task_id" to taskId,
        "conversationId" to conversationId,
        "conversation_id" to conversationId,
        "agent_task_started" to started,
        "source" to "omniflow_replay",
        "run_source" to "omniflow_replay",
        "runner" to runner,
        "local_steps_completed" to completedStepCount,
        "agent_steps_pending" to pendingAgentStepCount,
        "step_count" to stepCount,
        "success_step_count" to successStepCount,
        "arguments_applied" to true,
        "model_required" to (runPayload["model_required"] == true),
        "fallback_available" to (runPayload["fallback_available"] == true),
        "timing" to timing
    ).filterValues { it != null }

    return linkedMapOf(
        "success" to started,
        "goal" to "oob_reusable_function_run:$functionId",
        "function_id" to functionId,
        "execution_status" to executionStatus,
        "error_code" to startErrorCode,
        "error_message" to startErrorMessage,
        "timing" to timing,
        "terminal_state" to linkedMapOf<String, Any?>(
            "status" to if (started) {
                OOB_REUSABLE_EXECUTION_STATUS_STARTED_AGENT_FALLBACK
            } else {
                "error"
            },
            "execution_status" to executionStatus
        ).apply {
            putAll(sharedExecutionMeta)
        },
        "context" to linkedMapOf<String, Any?>(
            "source" to "oob_reusable_function",
            "function_id" to functionId,
            "execution_status" to executionStatus,
            "argument_count" to argumentCount,
            "step_results" to stepResults
        ).apply {
            putAll(sharedExecutionMeta)
        }
    )
}

internal fun buildOobReusableFunctionVlmFallbackPayload(
    functionId: String,
    runId: String,
    vlmTaskId: String,
    outcome: VlmToolOutcome,
    success: Boolean,
    runPayload: Map<String, Any?>,
    stepResults: List<Map<*, *>>,
    completedStepCount: Int,
    pendingAgentStepCount: Int,
    argumentCount: Int,
): Map<String, Any?> {
    val executionStatus = if (success) {
        OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_VLM_FALLBACK
    } else {
        OOB_REUSABLE_EXECUTION_STATUS_FAILED
    }
    val stepCount = stepResults.size
    val replaySuccessStepCount = stepResults.count { it["success"] != false }
    val successStepCount = if (success) stepCount else replaySuccessStepCount
    val timing = runPayload["timing"]
    val message = outcome.errorMessage?.trim()?.takeIf { it.isNotEmpty() }
        ?: outcome.message.trim().takeIf { it.isNotEmpty() }
    val sharedExecutionMeta = linkedMapOf<String, Any?>(
        "taskId" to runId,
        "run_id" to runId,
        "vlm_task_id" to vlmTaskId,
        "vlmTaskId" to vlmTaskId,
        "source" to "omniflow_replay",
        "run_source" to "omniflow_replay",
        "runner" to "oob_direct_vlm_fallback",
        "local_steps_completed" to completedStepCount,
        "agent_steps_pending" to pendingAgentStepCount,
        "step_count" to stepCount,
        "success_step_count" to successStepCount,
        "arguments_applied" to true,
        "model_used" to true,
        "model_required" to false,
        "delegated_tool_used" to true,
        "fallback_available" to (runPayload["fallback_available"] == true),
        "vlm_status" to outcome.status.name,
        "vlm_message" to outcome.message,
        "timing" to timing
    )

    return linkedMapOf(
        "success" to success,
        "goal" to "oob_reusable_function_run:$functionId",
        "function_id" to functionId,
        "execution_status" to executionStatus,
        "error_code" to if (success) null else outcome.errorCode ?: outcome.status.name,
        "error_message" to if (success) null else message,
        "timing" to timing,
        "terminal_state" to linkedMapOf<String, Any?>(
            "status" to if (success) {
                OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_VLM_FALLBACK
            } else {
                "error"
            },
            "execution_status" to executionStatus
        ).apply {
            putAll(sharedExecutionMeta)
        },
        "context" to linkedMapOf<String, Any?>(
            "source" to "oob_reusable_function",
            "function_id" to functionId,
            "execution_status" to executionStatus,
            "argument_count" to argumentCount,
            "step_results" to stepResults,
            "vlm_outcome" to outcome.toPayload()
        ).apply {
            putAll(sharedExecutionMeta)
        }
    )
}

internal fun buildOobReusableFunctionLocalPayload(
    functionId: String,
    localSuccess: Boolean,
    runPayload: Map<String, Any?>,
    stepResults: List<Map<*, *>>,
    argumentCount: Int,
): Map<String, Any?> {
    val executionStatus = if (localSuccess) {
        OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL
    } else {
        OOB_REUSABLE_EXECUTION_STATUS_FAILED
    }
    val stepCount = (runPayload["step_count"] as? Number)?.toInt() ?: stepResults.size
    val successStepCount = (runPayload["success_step_count"] as? Number)?.toInt()
        ?: stepResults.count { it["success"] != false }
    val timing = runPayload["timing"]
    val runner = runPayload["runner"] ?: "oob_mixed_runner"
    val sharedExecutionMeta = linkedMapOf<String, Any?>(
        "source" to "omniflow_replay",
        "run_source" to "omniflow_replay",
        "runner" to runner,
        "step_count" to stepCount,
        "success_step_count" to successStepCount,
        "model_used" to (runPayload["model_used"] == true),
        "model_required" to (runPayload["model_required"] == true),
        "delegated_tool_used" to (runPayload["delegated_tool_used"] == true),
        "arguments_applied" to true,
        "fallback_available" to (runPayload["fallback_available"] == true),
        "timing" to timing
    )

    return linkedMapOf(
        "success" to localSuccess,
        "goal" to "oob_reusable_function_run:$functionId",
        "function_id" to functionId,
        "execution_status" to executionStatus,
        "error_code" to if (localSuccess) {
            null
        } else {
            runPayload["error_code"] ?: "OOB_FUNCTION_STEP_FAILED"
        },
        "error_message" to runPayload["error_message"],
        "timing" to timing,
        "terminal_state" to linkedMapOf<String, Any?>(
            "status" to if (localSuccess) {
                OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL
            } else {
                "error"
            },
            "execution_status" to executionStatus
        ).apply {
            putAll(sharedExecutionMeta)
        },
        "context" to linkedMapOf<String, Any?>(
            "source" to "oob_reusable_function",
            "function_id" to functionId,
            "execution_status" to executionStatus,
            "argument_count" to argumentCount,
            "step_results" to stepResults
        ).apply {
            putAll(sharedExecutionMeta)
        }
    )
}

class AssistsCoreManager(private val context: Context) : OnMessagePushListener {
    private val TAG = "[AssistsCoreManager]"

    companion object {
        private const val SUMMARY_TASK_PREFIX_VLM = "vlm-summary-"
        private const val SUMMARY_TASK_PREFIX_TASK = "task-summary-"
        private const val MEMORY_GREETING_TOOL = "submit_memory_greeting"
        private const val DEFAULT_MEMORY_GREETING = "愿你今天也有温暖收获"
        private const val SUBAGENT_MODE = "subagent"
        private val TERMINAL_ENV_KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private const val SCHEDULED_SUBAGENT_NOTIFICATION_CHANNEL =
            "scheduled_subagent_tasks_v1"

        @Volatile
        private var mainEngineChannel: MethodChannel? = null

        @Volatile
        private var sharedInstance: AssistsCoreManager? = null

        @Volatile
        private var latestWorkbenchFrontendContext: Map<String, Any?>? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun bindMainEngineChannel(channel: MethodChannel) {
            mainEngineChannel = channel
            FlutterChatSyncBridge.bindMainChannel(channel)
        }

        private fun registerSharedInstance(instance: AssistsCoreManager) {
            sharedInstance = instance
        }

        fun sharedInstanceOrCreate(context: Context): AssistsCoreManager {
            val existing = sharedInstance
            if (existing != null) {
                return existing
            }
            return synchronized(this) {
                sharedInstance ?: AssistsCoreManager(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }

        fun dispatchAgentAiConfigChanged(source: String, path: String) {
            val payload = mapOf(
                "source" to source,
                "path" to path
            )
            runCatching {
                mainHandler.post {
                    runCatching {
                        mainEngineChannel?.invokeMethod("onAgentAiConfigChanged", payload)
                    }.onFailure {
                        OmniLog.w(
                            "[AssistsCoreManager]",
                            "dispatchAgentAiConfigChanged failed: ${it.message}"
                        )
                    }
                }
            }.onFailure {
                OmniLog.w("[AssistsCoreManager]", "dispatchAgentAiConfigChanged failed: ${it.message}")
            }
        }

        private fun isSummaryTask(taskId: String): Boolean {
            return taskId.startsWith(SUMMARY_TASK_PREFIX_VLM) ||
                taskId.startsWith(SUMMARY_TASK_PREFIX_TASK)
        }
    }

    /**
     * Coalesces agent stream events into per-frame batches so the Flutter side
     * receives at most one MethodChannel call per vsync (~60/sec) instead of
     * one per LLM token (~100+/sec).
     */
    private val agentStreamEventBatcher = AgentStreamEventBatcher { batch ->
        invokeFlutterEventSafely("onAgentStreamEventBatch", batch)
    }
    private val vlmStreamSeqByTask = mutableMapOf<String, Long>()

    init {
        registerSharedInstance(this)
    }

    private fun currentLocale(): PromptLocale = AppLocaleManager.resolvePromptLocale(context)

    private fun t(zh: String, en: String): String {
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    private fun defaultMemoryGreeting(): String =
        t("愿你今天也有温暖收获", "Hope today brings you something warm and worthwhile.")

    private fun localizedPermissionName(name: String): String {
        val trimmed = name.trim()
        return when (trimmed) {
            "无障碍权限", "Accessibility", "Accessibility Permission" ->
                t("无障碍权限", "Accessibility")
            "悬浮窗权限", "Overlay", "Overlay Permission" ->
                t("悬浮窗权限", "Overlay")
            "应用列表读取权限", "Installed Apps Access", "Installed Apps Permission" ->
                t("应用列表读取权限", "Installed Apps Access")
            "Shizuku 权限", "Shizuku Permission" ->
                t("Shizuku 权限", "Shizuku Permission")
            "公共文件访问", "Public Storage Access" ->
                t("公共文件访问", "Public Storage Access")
            else -> trimmed
        }
    }

    private data class ScheduledSubagentRunMeta(
        val scheduleTaskId: String,
        val scheduleTaskTitle: String,
        val notificationEnabled: Boolean,
        val conversationId: Long
    )

    private data class ChatTaskPersistenceState(
        val conversationId: Long,
        val conversationMode: String,
        val userEntryId: String,
        val assistantEntryId: String,
        val modelOverride: TaskParams.ChatModelOverride? = null,
        val reasoningEffort: String? = null,
        val assistantBuffer: StringBuilder = StringBuilder(),
        var isError: Boolean = false,
        var latestPromptTokens: Int? = null,
        var promptTokenThreshold: Int? = null
    )

    private class ActiveAgentRunContext(
        val taskId: String,
        val job: Job,
        val startedAtMillis: Long,
        val conversationId: Long?,
        val conversationMode: String,
        val userMessage: String
    ) : AgentRunControl {
        private val lock = Any()
        private var generationCounter = 0L
        private var activeTool: ManagedToolExecutionHandle? = null

        override fun beginToolExecution(
            toolName: String,
            toolCallId: String
        ): AgentToolExecutionHandle {
            return synchronized(lock) {
                ManagedToolExecutionHandle(
                    owner = this,
                    generation = ++generationCounter,
                    toolName = toolName,
                    toolCallId = toolCallId
                ).also { handle ->
                    activeTool = handle
                }
            }
        }

        fun bindActiveToolCardId(cardId: String) {
            synchronized(lock) {
                activeTool?.bindCardId(cardId)
            }
        }

        suspend fun requestManualToolStop(cardId: String): Boolean {
            val handle = synchronized(lock) {
                activeTool?.takeIf { it.matchesCardId(cardId) }
            } ?: return false
            return handle.requestManualStop()
        }

        fun clearTool(handle: ManagedToolExecutionHandle) {
            synchronized(lock) {
                if (activeTool === handle) {
                    activeTool = null
                }
            }
        }

        fun snapshot(now: Long = System.currentTimeMillis()): Map<String, Any?> {
            return synchronized(lock) {
                linkedMapOf(
                    "taskId" to taskId,
                    "conversationId" to conversationId,
                    "conversationMode" to conversationMode,
                    "userMessage" to userMessage,
                    "startedAtMillis" to startedAtMillis,
                    "elapsedMillis" to (now - startedAtMillis).coerceAtLeast(0L),
                    "isActive" to job.isActive,
                    "activeTool" to activeTool?.snapshot()
                )
            }
        }
    }

    private class ManagedToolExecutionHandle(
        private val owner: ActiveAgentRunContext,
        override val generation: Long,
        override val toolName: String,
        override val toolCallId: String
    ) : AgentToolExecutionHandle {
        override val runId: String = owner.taskId
        private val lock = Any()
        private var cardId: String? = null
        private var job: Job? = null
        private var stopAction: (suspend () -> Unit)? = null
        private var latestSnapshot = AgentToolProgressSnapshot()
        private var completed = false
        private var manualStopRequested = false

        override fun bindCardId(cardId: String) {
            synchronized(lock) {
                this.cardId = cardId.trim().ifEmpty { null }
            }
        }

        fun matchesCardId(expectedCardId: String): Boolean {
            val normalized = expectedCardId.trim()
            if (normalized.isEmpty()) {
                return false
            }
            return synchronized(lock) {
                cardId == normalized && !completed
            }
        }

        override fun currentCardId(): String? {
            return synchronized(lock) { cardId }
        }

        override fun bindExecutionJob(job: Job) {
            synchronized(lock) {
                this.job = job
            }
        }

        override fun bindStopAction(action: (suspend () -> Unit)?) {
            synchronized(lock) {
                stopAction = action
            }
        }

        override fun recordProgress(summary: String, extras: Map<String, Any?>) {
            synchronized(lock) {
                if (!manualStopRequested) {
                    latestSnapshot = AgentToolProgressSnapshot(
                        summary = summary,
                        extras = LinkedHashMap(extras)
                    )
                }
            }
        }

        override fun latestProgressSnapshot(): AgentToolProgressSnapshot {
            return synchronized(lock) { latestSnapshot }
        }

        fun snapshot(): Map<String, Any?> {
            return synchronized(lock) {
                linkedMapOf(
                    "toolName" to toolName,
                    "toolCallId" to toolCallId,
                    "cardId" to cardId,
                    "summary" to latestSnapshot.summary,
                    "extras" to latestSnapshot.extras,
                    "manualStopRequested" to manualStopRequested,
                    "completed" to completed
                )
            }
        }

        override fun isManualStopRequested(): Boolean {
            return synchronized(lock) { manualStopRequested }
        }

        override fun throwIfStopRequested() {
            if (isManualStopRequested()) {
                throw ManualToolStopCancellationException()
            }
        }

        suspend fun requestManualStop(): Boolean {
            val currentStopAction: (suspend () -> Unit)?
            val currentJob: Job?
            synchronized(lock) {
                if (completed) {
                    return false
                }
                if (manualStopRequested) {
                    return true
                }
                manualStopRequested = true
                currentStopAction = stopAction
                currentJob = job
            }
            runCatching {
                currentStopAction?.invoke()
            }.onFailure {
                OmniLog.w("[AssistsCoreManager]", "manual tool stop action failed: ${it.message}")
            }
            currentJob?.cancel(ManualToolStopCancellationException())
            return true
        }

        override fun complete() {
            synchronized(lock) {
                completed = true
                stopAction = null
                job = null
            }
            owner.clearTool(this)
        }
    }

    // 用于存储需要等待用户操作的回调结果
    private lateinit var channel: MethodChannel
    private var mainJob: CoroutineScope = CoroutineScope(Dispatchers.Main)
    private var workJob: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val activeAgentLock = Any()

    private val activeAgentRuns: MutableMap<String, ActiveAgentRunContext> = mutableMapOf()
    private val chatTaskPersistenceStates: MutableMap<String, ChatTaskPersistenceState> =
        mutableMapOf()
    private val conversationDomainService by lazy { ConversationDomainService(context) }
    private val workbenchProjectStore by lazy { WorkbenchProjectStore(context) }

    // 当前活跃的对话ID
    private var currentConversationId: Long? = null
    private var currentConversationMode: String = "normal"

    // Scheduled while the system is idle (no active agent runs). Cancelled and
    // rescheduled whenever a new task starts, so consolidation only runs when
    // the engine has been quiet long enough for the work to be non-disruptive.
    @Volatile private var idleConsolidationJob: Job? = null

    private fun registerActiveAgentRun(taskId: String, context: ActiveAgentRunContext) {
        idleConsolidationJob?.cancel()
        idleConsolidationJob = null
        AgentVlmUiSession.registerRun(
            runId = taskId,
            onStopRequested = {
                val activeVlmTaskIds = AgentVlmUiSession.activeTaskIdsForRun(taskId)
                cancelActiveAgentRun(taskId, "agent_vlm_ui_stop")
                if (activeVlmTaskIds.isEmpty()) {
                    AssistsUtil.Core.cancelRunningTask(taskId)
                } else {
                    activeVlmTaskIds.forEach { childTaskId ->
                        AssistsUtil.Core.cancelRunningTask(childTaskId)
                    }
                }
            },
            onCompleteRequested = {
                completeActiveAgentRun(taskId, "agent_vlm_ui_completed")
            }
        )
        synchronized(activeAgentLock) {
            activeAgentRuns[taskId] = context
        }
        dispatchAgentRunStateChanged("agent_run_started")
    }

    private fun registerChatTaskPersistenceState(taskId: String, state: ChatTaskPersistenceState) {
        synchronized(activeAgentLock) {
            chatTaskPersistenceStates[taskId] = state
        }
    }

    private fun getChatTaskPersistenceState(taskId: String): ChatTaskPersistenceState? {
        return synchronized(activeAgentLock) {
            chatTaskPersistenceStates[taskId]
        }
    }

    private fun removeChatTaskPersistenceState(taskId: String): ChatTaskPersistenceState? {
        return synchronized(activeAgentLock) {
            chatTaskPersistenceStates.remove(taskId)
        }
    }

    private fun clearActiveAgentJob(taskId: String, job: Job) {
        val (removed, nowIdle) = synchronized(activeAgentLock) {
            val wasRemoved = activeAgentRuns[taskId]?.job == job &&
                activeAgentRuns.remove(taskId) != null
            wasRemoved to (wasRemoved && activeAgentRuns.isEmpty())
        }
        if (removed) {
            dispatchAgentRunStateChanged("agent_run_finished")
            if (nowIdle) scheduleIdleConsolidation()
        }
    }

    private suspend fun finishAgentVlmUiSessionIfNeeded(runId: String, message: String) {
        val sessionEnd = AgentVlmUiSession.endRun(runId)
        if (!sessionEnd.wasActive || sessionEnd.stopRequested || sessionEnd.completeRequested) {
            return
        }
        runCatching {
            withContext(Dispatchers.Main) {
                UIKit.uiTaskEvent?.finishDoingTask(message)
            }
            if (sessionEnd.shouldFinishCompanion) {
                delay(500)
                withContext(Dispatchers.Main) {
                    UIKit.uiBaseEvent?.finishCompanion()
                }
            }
        }.onFailure {
            OmniLog.w(TAG, "finish agent VLM UI session failed: ${it.message}")
        }
    }

    private fun scheduleIdleConsolidation() {
        idleConsolidationJob?.cancel()
        idleConsolidationJob = workJob.launch {
            delay(IDLE_CONSOLIDATION_DELAY_MS)
            val isStillIdle = synchronized(activeAgentLock) { activeAgentRuns.isEmpty() }
            if (isStillIdle) {
                runCatching { consolidateIdleRunLogs() }
                    .onFailure { OmniLog.w(TAG, "idle consolidation failed: ${it.message}") }
            }
        }
    }

    private suspend fun consolidateIdleRunLogs() {
        @Suppress("UNCHECKED_CAST")
        val runs = (InternalRunLogStore.listRuns(context, limit = 50)["runs"]
            as? List<Map<String, Any?>>) ?: return

        // Seal any run that never received finishRun() — i.e. the process was killed
        // or crashed before the finally block could execute. By the time this runs
        // (2 min after the last active task), all runs from this session have already
        // been closed by their own finally blocks, so any still-open run is an orphan.
        var sealed = 0
        for (run in runs) {
            val finishedAt = run["finished_at"]?.toString()?.trim().orEmpty()
            if (finishedAt.isNotEmpty()) continue                    // already finished
            val runId = run["run_id"]?.toString()?.trim() ?: continue
            runCatching {
                InternalRunLogStore.finishRun(
                    context = context,
                    runId = runId,
                    success = false,
                    doneReason = "orphaned"
                )
                sealed++
            }.onFailure { OmniLog.w(TAG, "seal orphan run failed runId=$runId: ${it.message}") }
        }
        if (sealed > 0) {
            OmniLog.i(TAG, "idle consolidation: sealed $sealed orphaned run log(s)")
        }
        val autoRegisterResult = OobRunLogReplayService(context)
            .autoRegisterRecentRunLogs(limit = 50)
        val registered = (autoRegisterResult["registered_count"] as? Number)?.toInt() ?: 0
        val alreadyExists = (autoRegisterResult["already_exists_count"] as? Number)?.toInt() ?: 0
        if (registered > 0 || alreadyExists > 0) {
            OmniLog.i(
                TAG,
                "idle consolidation: registered=$registered already_exists=$alreadyExists"
            )
        }
    }

    private fun syncAgentAiCapabilityConfigFile() {
        runCatching {
            AgentAiCapabilityConfigSync.get(context).syncFileFromStores()
            val workspaceManager = AgentWorkspaceManager(context)
            val configFile = workspaceManager.agentConfigFile()
            dispatchAgentAiConfigChanged(
                source = "store",
                path = workspaceManager.shellPathForAndroid(configFile)
                    ?: configFile.absolutePath
            )
        }.onFailure {
            OmniLog.w(TAG, "sync agent ai config file failed: ${it.message}")
        }
    }

    private fun cancelActiveAgentRun(taskId: String?, reason: String): Boolean {
        val runsToCancel = synchronized(activeAgentLock) {
            if (taskId.isNullOrBlank()) {
                val snapshot = activeAgentRuns.values.toList()
                activeAgentRuns.clear()
                snapshot
            } else {
                val current = activeAgentRuns.remove(taskId)
                if (current == null) emptyList() else listOf(current)
            }
        }
        if (runsToCancel.isNotEmpty()) {
            OmniLog.i(TAG, "Cancelling active agent run(s): $reason taskId=$taskId")
            runsToCancel.forEach { run ->
                publishManualAgentCancellation(run)
                run.job.cancel(CancellationException(reason))
            }
        }
        return runsToCancel.isNotEmpty()
    }

    private fun completeActiveAgentRun(taskId: String?, reason: String) {
        val runsToComplete = synchronized(activeAgentLock) {
            if (taskId.isNullOrBlank()) {
                activeAgentRuns.values.toList()
            } else {
                activeAgentRuns[taskId]?.let(::listOf).orEmpty()
            }
        }
        if (runsToComplete.isNotEmpty()) {
            OmniLog.i(TAG, "Completing active agent run(s): $reason taskId=$taskId")
            runsToComplete.forEach { run ->
                publishManualAgentCompletion(run)
                run.job.cancel(CancellationException(reason))
            }
        }
    }

    private fun publishManualAgentCancellation(run: ActiveAgentRunContext) {
        val conversationId = run.conversationId ?: return
        val cancelledText = when (AppLocaleManager.resolvePromptLocale(context)) {
            PromptLocale.EN_US -> "Task canceled"
            PromptLocale.ZH_CN -> "任务已取消"
        }
        val entryId = "${run.taskId}-cancelled"
        val now = System.currentTimeMillis()
        val streamMeta = buildAgentManualCancellationStreamMeta(run.taskId, entryId)
        workJob.launch {
            runCatching {
                val repository = conversationHistoryRepository()
                repository.upsertAssistantMessage(
                    conversationId = conversationId,
                    conversationMode = run.conversationMode,
                    entryId = entryId,
                    text = cancelledText,
                    isError = false,
                    streamMeta = streamMeta,
                    createdAt = now
                )
                withContext(Dispatchers.Main) {
                    invokeFlutterEventSafely(
                        "onAgentStreamEvent",
                        sanitizeInteropMap(
                            mapOf(
                                "taskId" to run.taskId,
                                "seq" to AGENT_MANUAL_CANCELLATION_SEQUENCE,
                                "kind" to "text_snapshot",
                                "entryId" to entryId,
                                "roundIndex" to AGENT_MANUAL_CANCELLATION_ROUND,
                                "isFinal" to true,
                                "text" to cancelledText,
                                "createdAt" to now,
                                "streamMeta" to streamMeta
                            )
                        )
                    )
                }
            }.onFailure {
                OmniLog.w(TAG, "publish manual agent cancellation failed: ${it.message}", it)
            }
            dispatchAgentRunStateChanged("agent_run_cancelled")
        }
    }

    private fun publishManualAgentCompletion(run: ActiveAgentRunContext) {
        val conversationId = run.conversationId ?: return
        val completedText = when (AppLocaleManager.resolvePromptLocale(context)) {
            PromptLocale.EN_US -> "Task completed"
            PromptLocale.ZH_CN -> "任务已完成"
        }
        val entryId = "${run.taskId}-completed"
        val now = System.currentTimeMillis()
        val streamMeta = buildAgentManualCancellationStreamMeta(run.taskId, entryId) +
            mapOf("doneReason" to "user_completed")
        workJob.launch {
            runCatching {
                val repository = conversationHistoryRepository()
                repository.upsertAssistantMessage(
                    conversationId = conversationId,
                    conversationMode = run.conversationMode,
                    entryId = entryId,
                    text = completedText,
                    isError = false,
                    streamMeta = streamMeta,
                    createdAt = now
                )
                withContext(Dispatchers.Main) {
                    invokeFlutterEventSafely(
                        "onAgentStreamEvent",
                        sanitizeInteropMap(
                            mapOf(
                                "taskId" to run.taskId,
                                "seq" to AGENT_MANUAL_CANCELLATION_SEQUENCE,
                                "kind" to "text_snapshot",
                                "entryId" to entryId,
                                "roundIndex" to AGENT_MANUAL_CANCELLATION_ROUND,
                                "isFinal" to true,
                                "text" to completedText,
                                "createdAt" to now,
                                "streamMeta" to streamMeta
                            )
                        )
                    )
                }
            }.onFailure {
                OmniLog.w(TAG, "publish manual agent completion failed: ${it.message}", it)
            }
            dispatchAgentRunStateChanged("agent_run_completed_by_user")
        }
    }

    private fun ModelProviderConfig.toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "baseUrl" to baseUrl,
            "apiKey" to apiKey,
            "source" to source,
            "configured" to isConfigured()
        )
    }

    private fun ModelProviderProfile.toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "baseUrl" to baseUrl,
            "apiKey" to apiKey,
            "configured" to isConfigured(),
            "protocolType" to protocolType
        )
    }

    private fun ProviderModelOption.toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "displayName" to displayName,
            "ownedBy" to ownedBy,
            "contextLimit" to contextLimit,
            "inputLimit" to inputLimit,
            "outputLimit" to outputLimit,
            "inputModalities" to inputModalities,
            "outputModalities" to outputModalities,
            "modelsDevProviderId" to modelsDevProviderId,
            "modelsDevProviderName" to modelsDevProviderName,
            "providerLogoUrl" to providerLogoUrl,
            "family" to family,
            "group" to group,
            "attachment" to attachment,
            "reasoning" to reasoning,
            "toolCall" to toolCall,
            "structuredOutput" to structuredOutput,
            "temperature" to temperature
        )
    }

    private fun SceneCatalogItem.toMap(): Map<String, Any?> {
        return mapOf(
            "sceneId" to sceneId,
            "description" to description,
            "defaultModel" to defaultModel,
            "effectiveModel" to effectiveModel,
            "effectiveProviderProfileId" to effectiveProviderProfileId,
            "effectiveProviderProfileName" to effectiveProviderProfileName,
            "boundProviderProfileId" to boundProviderProfileId,
            "boundProviderProfileName" to boundProviderProfileName,
            "transport" to transport,
            "configSource" to configSource,
            "overrideApplied" to overrideApplied,
            "overrideModel" to overrideModel,
            "providerConfigured" to providerConfigured,
            "bindingExists" to bindingExists,
            "bindingProfileMissing" to bindingProfileMissing
        )
    }

    private fun SceneModelOverrideEntry.toMap(): Map<String, Any?> {
        return mapOf(
            "sceneId" to sceneId,
            "model" to model
        )
    }

    private fun SceneModelBindingEntry.toMap(): Map<String, Any?> {
        return mapOf(
            "sceneId" to sceneId,
            "providerProfileId" to providerProfileId,
            "modelId" to modelId
        )
    }

    private fun SceneVoiceConfig.toMap(): Map<String, Any?> {
        return mapOf(
            "autoPlay" to autoPlay,
            "voiceId" to voiceId,
            "stylePreset" to stylePreset,
            "customStyle" to customStyle
        )
    }

    fun setChannel(_channel: MethodChannel) {
        OmniLog.e(TAG, "setChannel")
        this.channel = _channel
        FlutterChatSyncBridge.bindCurrentChannel(_channel)
    }

    fun captureWorkbenchAnnotationAttachment(call: MethodCall, result: MethodChannel.Result) {
        val canvasWidth = call.argument<Number>("canvasWidth")?.toFloat() ?: 0f
        val canvasHeight = call.argument<Number>("canvasHeight")?.toFloat() ?: 0f
        val drawingPaths = call.argument<List<Map<String, Any?>>>("drawingPaths")
            ?.map(::sanitizeInteropMap)
            ?: emptyList()
        val source = call.argument<String>("source")?.trim().orEmpty()
            .ifEmpty { "xiaowan_floating_annotation_canvas" }

        workJob.launch {
            var bitmapToRecycle: Bitmap? = null
            try {
                val capture = AccessibilityController.captureScreenshotImage(
                    isBitmap = true,
                    isBase64 = false,
                    isFile = false,
                    isFilterOverlay = true,
                    compressQuality = null
                )
                val capturedBitmap = capture.imageBitmap
                if (!capture.isSuccess || capturedBitmap == null) {
                    withContext(Dispatchers.Main) {
                        result.error("SCREENSHOT_FAILED", "current screen screenshot is empty", null)
                    }
                    return@launch
                }

                val bitmap = if (capturedBitmap.isMutable &&
                    capturedBitmap.config != Bitmap.Config.HARDWARE
                ) {
                    capturedBitmap
                } else {
                    capturedBitmap.copy(Bitmap.Config.ARGB_8888, true).also {
                        if (!capturedBitmap.isRecycled) capturedBitmap.recycle()
                    }
                }
                bitmapToRecycle = bitmap
                drawWorkbenchAnnotationPaths(
                    bitmap = bitmap,
                    drawingPaths = drawingPaths,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight
                )

                val workspaceManager = AgentWorkspaceManager(context)
                val target = File(
                    workspaceManager.attachmentsDirectory(),
                    "xiaowan_annotation_${System.currentTimeMillis()}.jpg"
                )
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)
                }
                val payload = linkedMapOf<String, Any?>(
                    "id" to "xiaowan_annotation_${target.nameWithoutExtension}",
                    "name" to "xiaowan_annotation.jpg",
                    "fileName" to target.name,
                    "path" to target.absolutePath,
                    "androidPath" to target.absolutePath,
                    "uri" to workspaceManager.uriForFile(target),
                    "shellPath" to workspaceManager.shellPathForAndroid(target),
                    "mimeType" to "image/jpeg",
                    "isImage" to true,
                    "size" to target.length(),
                    "width" to bitmap.width,
                    "height" to bitmap.height,
                    "source" to source,
                    "annotationKind" to "current_screen_with_red_strokes",
                    "canvasWidth" to canvasWidth,
                    "canvasHeight" to canvasHeight,
                    "isFilterOverlay" to capture.isFilterOverlay
                ).filterValues { it != null }
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: PermissionException) {
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_DENIED", e.message, null)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "captureWorkbenchAnnotationAttachment failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    result.error("SCREENSHOT_FAILED", e.message, null)
                }
            } finally {
                bitmapToRecycle?.let { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    fun workbenchFrontendContextSet(call: MethodCall, result: MethodChannel.Result) {
        val raw = call.argument<Map<String, Any?>>("context") ?: emptyMap()
        val contextPayload = sanitizeInteropMap(raw).toMutableMap().apply {
            put("nativeUpdatedAtMillis", System.currentTimeMillis())
        }
        WorkbenchDisplayLayoutContext.updateFromFrontendContext(contextPayload)
        latestWorkbenchFrontendContext = contextPayload
        result.success(
            mapOf(
                "success" to true,
                "context" to contextPayload
            )
        )
    }

    fun workbenchFrontendContextGet(call: MethodCall, result: MethodChannel.Result) {
        result.success(latestWorkbenchFrontendContext ?: emptyMap<String, Any?>())
    }

    private fun drawWorkbenchAnnotationPaths(
        bitmap: Bitmap,
        drawingPaths: List<Map<String, Any?>>,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        if (canvasWidth <= 0f || canvasHeight <= 0f || drawingPaths.isEmpty()) {
            return
        }
        val scaleX = bitmap.width / canvasWidth
        val scaleY = bitmap.height / canvasHeight
        val strokeScale = (scaleX + scaleY) / 2f
        val canvas = Canvas(bitmap)
        drawingPaths.forEach { stroke ->
            val points = stroke["points"] as? List<*> ?: return@forEach
            if (points.size < 2) return@forEach
            val first = points.first() as? Map<*, *> ?: return@forEach
            val startX = numberAsFloat(first["x"]) ?: return@forEach
            val startY = numberAsFloat(first["y"]) ?: return@forEach
            val path = Path().apply {
                moveTo(startX * scaleX, startY * scaleY)
            }
            points.drop(1).forEach { rawPoint ->
                val point = rawPoint as? Map<*, *> ?: return@forEach
                val x = numberAsFloat(point["x"]) ?: return@forEach
                val y = numberAsFloat(point["y"]) ?: return@forEach
                path.lineTo(x * scaleX, y * scaleY)
            }
            val strokeWidth = numberAsFloat(stroke["strokeWidth"]) ?: 4f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseAnnotationColor(stroke["color"])
                style = Paint.Style.STROKE
                this.strokeWidth = (strokeWidth * strokeScale).coerceAtLeast(4f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun numberAsFloat(value: Any?): Float? {
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull()
            else -> null
        }
    }

    private fun parseAnnotationColor(value: Any?): Int {
        val raw = value?.toString()?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            runCatching { return Color.parseColor(raw) }
        }
        return Color.rgb(225, 61, 86)
    }

    private fun currentChannelOrNull(): MethodChannel? {
        return if (this::channel.isInitialized) channel else null
    }

    /**
     * 统一的 Flutter 事件派发：
     * 1) 始终在主线程调用；
     * 2) 同时投递到当前通道和主引擎通道；
     * 3) 避免事件派发异常导致进程崩溃。
     */
    private fun invokeFlutterEventSafely(method: String, arguments: Any? = null) {
        val current = currentChannelOrNull()
        val main = mainEngineChannel
        val channels = listOfNotNull(current, main).distinct()
        if (channels.isEmpty()) {
            OmniLog.w(TAG, "skip invoke $method: flutter channel unavailable")
            return
        }

        var lastError: Exception? = null
        var delivered = false
        for (target in channels) {
            try {
                target.invokeMethod(method, arguments)
                delivered = true
            } catch (e: Exception) {
                lastError = e
                OmniLog.e(TAG, "invoke $method failed on one channel: ${e.message}")
            }
        }
        if (!delivered) {
            OmniLog.e(TAG, "invoke $method failed on all channels: ${lastError?.message}")
        }
    }

    fun hasActiveAgentRuns(): Boolean {
        return synchronized(activeAgentLock) {
            activeAgentRuns.isNotEmpty()
        }
    }

    fun activeAgentTaskIds(): List<String> {
        return synchronized(activeAgentLock) {
            activeAgentRuns.keys.toList()
        }
    }

    private fun activeAgentRunsPayload(reason: String? = null): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val runs = synchronized(activeAgentLock) {
            activeAgentRuns.values
                .sortedBy { it.startedAtMillis }
                .map { it.snapshot(now) }
        }
        return linkedMapOf(
            "success" to true,
            "reason" to reason.orEmpty(),
            "count" to runs.size,
            "running" to runs.isNotEmpty(),
            "runs" to runs
        )
    }

    private fun dispatchAgentRunStateChanged(reason: String) {
        val payload = activeAgentRunsPayload(reason)
        mainJob.launch(Dispatchers.Main) {
            invokeFlutterEventSafely("onAgentRunStateChanged", payload)
        }
    }

    fun agentRunList(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            try {
                val payload = activeAgentRunsPayload("agent_run_list")
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "agentRunList error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("AGENT_RUN_LIST_ERROR", e.message, null)
                }
            }
        }
    }

    suspend fun invokeFlutterMethodForAgent(method: String, arguments: Map<String, Any?>): Any? {
        val targetChannel = mainEngineChannel ?: if (this::channel.isInitialized) channel else null
        if (targetChannel == null) {
            throw IllegalStateException("Flutter channel unavailable for $method")
        }
        return suspendCancellableCoroutine { continuation ->
            mainJob.launch(Dispatchers.Main) {
                try {
                    targetChannel.invokeMethod(method, arguments, object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            if (!continuation.isCompleted) {
                                continuation.resume(result)
                            }
                        }

                        override fun error(
                            errorCode: String,
                            errorMessage: String?,
                            errorDetails: Any?
                        ) {
                            if (!continuation.isCompleted) {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        "$errorCode: ${errorMessage ?: "Flutter bridge error"}"
                                    )
                                )
                            }
                        }

                        override fun notImplemented() {
                            if (!continuation.isCompleted) {
                                continuation.resumeWithException(
                                    NotImplementedError("Flutter method not implemented: $method")
                                )
                            }
                        }
                    })
                } catch (e: Exception) {
                    if (!continuation.isCompleted) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
    }

    private fun toStringAnyMap(value: Any?): Map<String, Any?> {
        return (value as? Map<*, *>)?.entries?.associate { (key, rawValue) ->
            key.toString() to normalizeChannelValue(rawValue)
        } ?: emptyMap()
    }

    private fun toListOfStringAnyMap(value: Any?): List<Map<String, Any?>> {
        return (value as? List<*>)?.map { toStringAnyMap(it) } ?: emptyList()
    }

    private fun normalizeChannelValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> toStringAnyMap(value)
            is List<*> -> value.map { normalizeChannelValue(it) }
            else -> value
        }
    }

    private data class AgentToolMeta(
        val toolType: String,
        val displayName: String,
        val serverName: String? = null
    )

    private fun resolveAgentToolMeta(toolName: String): AgentToolMeta {
        return when (toolName) {
            "context_apps_query" -> AgentToolMeta("builtin", t("查询已安装应用", "Query Installed Apps"))
            "context_time_now" -> AgentToolMeta("builtin", t("查询当前时间", "Query Current Time"))
            "vlm_task" -> AgentToolMeta("vlm", t("视觉执行", "Visual Task"))
            RunLogReplayPolicy.TOOL_CALL_FUNCTION, "omniflow.call_function" -> AgentToolMeta(
                "oob_function",
                t("复用指令", "Reusable Command")
            )
            RunLogReplayPolicy.TOOL_CALL_TOOL,
            RunLogReplayPolicy.TOOL_OOB_TOOL_CALL -> AgentToolMeta("builtin", t("工具调用", "Tool Call"))
            "web_search" -> AgentToolMeta("research", t("网页搜索", "Web Search"))
            "browser_use" -> AgentToolMeta("browser", t("浏览器操作", "Browser Action"))
            "android_privileged_action" -> AgentToolMeta("privileged", t("安卓高级动作", "Android Privileged Action"))
            "android_privileged_session_start" -> AgentToolMeta("privileged", t("启动高权限会话", "Start Privileged Session"))
            "android_privileged_session_exec" -> AgentToolMeta("privileged", t("执行高权限命令", "Run Privileged Command"))
            "android_privileged_session_read" -> AgentToolMeta("privileged", t("读取高权限输出", "Read Privileged Output"))
            "android_privileged_session_stop" -> AgentToolMeta("privileged", t("结束高权限会话", "Stop Privileged Session"))
            "terminal_execute" -> AgentToolMeta("terminal", t("终端执行", "Run Terminal Command"))
            "terminal_session_start" -> AgentToolMeta("terminal", t("启动终端会话", "Start Terminal Session"))
            "terminal_session_exec" -> AgentToolMeta("terminal", t("执行会话命令", "Run Session Command"))
            "terminal_session_read" -> AgentToolMeta("terminal", t("读取会话输出", "Read Session Output"))
            "terminal_session_stop" -> AgentToolMeta("terminal", t("结束终端会话", "Stop Terminal Session"))
            "file_read" -> AgentToolMeta("workspace", t("读取文件", "Read File"))
            "file_write" -> AgentToolMeta("workspace", t("写入文件", "Write File"))
            "file_edit" -> AgentToolMeta("workspace", t("编辑文件", "Edit File"))
            "file_list" -> AgentToolMeta("workspace", t("列出文件", "List Files"))
            "file_search" -> AgentToolMeta("workspace", t("搜索文件", "Search Files"))
            "file_stat" -> AgentToolMeta("workspace", t("查看文件信息", "Inspect File"))
            "file_move" -> AgentToolMeta("workspace", t("移动文件", "Move File"))
            "schedule_task_create" -> AgentToolMeta("schedule", t("创建定时任务", "Create Scheduled Task"))
            "schedule_task_list" -> AgentToolMeta("schedule", t("查看定时任务", "List Scheduled Tasks"))
            "schedule_task_update" -> AgentToolMeta("schedule", t("修改定时任务", "Update Scheduled Task"))
            "schedule_task_delete" -> AgentToolMeta("schedule", t("删除定时任务", "Delete Scheduled Task"))
            "alarm_reminder_create" -> AgentToolMeta("alarm", t("创建提醒闹钟", "Create Reminder Alarm"))
            "alarm_reminder_list" -> AgentToolMeta("alarm", t("查看提醒闹钟", "List Reminder Alarms"))
            "alarm_reminder_delete" -> AgentToolMeta("alarm", t("删除提醒闹钟", "Delete Reminder Alarm"))
            "calendar_list" -> AgentToolMeta("calendar", t("查看日历列表", "List Calendars"))
            "calendar_event_create" -> AgentToolMeta("calendar", t("创建日程", "Create Calendar Event"))
            "calendar_event_list" -> AgentToolMeta("calendar", t("查询日程", "List Calendar Events"))
            "calendar_event_update" -> AgentToolMeta("calendar", t("修改日程", "Update Calendar Event"))
            "calendar_event_delete" -> AgentToolMeta("calendar", t("删除日程", "Delete Calendar Event"))
            "memory_search" -> AgentToolMeta("memory", t("检索记忆", "Search Memory"))
            "memory_write_daily" -> AgentToolMeta("memory", t("写入当日记忆", "Write Daily Memory"))
            "memory_upsert_longterm" -> AgentToolMeta("memory", t("沉淀长期记忆", "Upsert Long-Term Memory"))
            "memory_rollup_day" -> AgentToolMeta("memory", t("整理当日记忆", "Roll Up Daily Memory"))
            "subagent_dispatch" -> AgentToolMeta("subagent", t("分派子任务", "Dispatch Subtasks"))
            "workbench_project_create" -> AgentToolMeta("workbench", t("创建 Project", "Create Project"))
            "workbench_project_list" -> AgentToolMeta("workbench", t("查看 Project 列表", "List Projects"))
            "workbench_project_get" -> AgentToolMeta("workbench", t("读取 Project", "Load Project"))
            "workbench_project_update" -> AgentToolMeta("workbench", t("更新 Project", "Update Project"))
            "workbench_api_list" -> AgentToolMeta("workbench", t("查看 Project Tool", "List Project Tools"))
            "workbench_api_call" -> AgentToolMeta("workbench", t("调用 Project Tool", "Call Project Tool"))
            "workbench_project_export" -> AgentToolMeta("workbench", t("导出 Project", "Export Project"))
            "workbench_project_open" -> AgentToolMeta("workbench", t("打开 Project", "Open Project"))
            "workbench_project_activate" -> AgentToolMeta("workbench", t("激活 Project", "Activate Project"))
            "workbench_project_active_get" -> AgentToolMeta("workbench", t("查看当前 Project", "Get Active Project"))
            "workbench_project_deactivate" -> AgentToolMeta("workbench", t("停用 Project", "Deactivate Project"))
            "workbench_project_delete" -> AgentToolMeta("workbench", t("删除 Project", "Delete Project"))
            "workbench_project_hot_update" -> AgentToolMeta("workbench", t("热更新 Project", "Hot Update Project"))
            "workbench_project_ingest_android" -> AgentToolMeta("workbench", t("导入安卓资产", "Import Android Asset"))
            "workbench_project_ingest_oss" -> AgentToolMeta("workbench", t("导入 OSS 资源", "Import OSS Asset"))
            "workbench_project_progress_get" -> AgentToolMeta("workbench", t("查看 Project 进度", "Get Project Progress"))
            else -> {
                val match = Regex("^mcp__(.+?)__(.+)$").find(toolName)
                if (match != null) {
                    val serverId = match.groupValues[1]
                    val rawToolName = match.groupValues[2]
                    val serverName = RemoteMcpConfigStore.getServer(serverId)?.name
                    AgentToolMeta("mcp", prettifyToolName(rawToolName), serverName)
                } else {
                    AgentToolMeta("builtin", prettifyToolName(toolName))
                }
            }
        }
    }

    private fun prettifyToolName(rawToolName: String): String {
        val trimmed = rawToolName.trim()
        if (trimmed.isBlank()) {
            return t("工具调用", "Tool Call")
        }
        val normalized = trimmed
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("[_\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) {
            return t("工具调用", "Tool Call")
        }
        return when (normalized.lowercase()) {
            "calltool", "call tool" -> t("工具调用", "Tool Call")
            else -> normalized
        }
    }

    private fun buildToolStartPayload(toolName: String, argsJson: String): Map<String, Any?> {
        val meta = resolveAgentToolMeta(toolName)
        return linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "displayName" to meta.displayName,
            "toolType" to meta.toolType,
            "serverName" to meta.serverName,
            "args" to argsJson,
            "argsJson" to argsJson
        ).apply {
            extractToolTitle(argsJson)?.let { toolTitle ->
                put("toolTitle", toolTitle)
                put("summary", toolTitle)
            }
        }
    }

    private fun extractToolTitle(argsJson: String): String? {
        if (argsJson.isBlank()) return null
        return runCatching {
            JSONObject(argsJson).optString("tool_title").trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun buildToolProgressPayload(
        toolName: String,
        progress: String,
        argsJson: String = "",
        extras: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val meta = resolveAgentToolMeta(toolName)
        val payload = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "displayName" to meta.displayName,
            "toolType" to meta.toolType,
            "serverName" to meta.serverName,
            "progress" to progress,
            "args" to argsJson,
            "argsJson" to argsJson
        )
        extractToolTitle(argsJson)?.let { toolTitle ->
            payload["toolTitle"] = toolTitle
            if ((payload["summary"]?.toString() ?: "").isBlank()) {
                payload["summary"] = toolTitle
            }
        }
        payload.putAll(extras)
        return payload
    }

    private fun buildToolCompletePayload(
        toolName: String,
        result: ToolExecutionResult,
        argsJson: String = ""
    ): Map<String, Any?> {
        val meta = resolveAgentToolMeta(toolName)
        val summary: String
        val previewJson: String
        val rawResultJson: String
        val success: Boolean
        val status: String
        var interruptedBy: String? = null
        var interruptionReason: String? = null
        when (result) {
            is ToolExecutionResult.ChatMessage -> {
                summary = result.message
                previewJson = JSONObject(mapOf("message" to result.message)).toString()
                rawResultJson = previewJson
                success = true
                status = "success"
            }
            is ToolExecutionResult.Clarify -> {
                summary = result.question
                previewJson = JSONObject(
                    mapOf(
                        "question" to result.question,
                        "missingFields" to (result.missingFields ?: emptyList<String>())
                    )
                ).toString()
                rawResultJson = previewJson
                success = true
                status = "success"
            }
            is ToolExecutionResult.VlmTaskStarted -> {
                summary = t("已启动视觉执行任务", "Started the vision task.")
                previewJson = JSONObject(
                    mapOf("taskId" to result.taskId, "goal" to result.goal)
                ).toString()
                rawResultJson = previewJson
                success = true
                status = "success"
            }
            is ToolExecutionResult.PermissionRequired -> {
                val names = result.missing.map(::localizedPermissionName)
                summary = t(
                    "缺少权限：${names.joinToString("、")}",
                    "Missing permissions: ${names.joinToString(", ")}"
                )
                previewJson = JSONObject(mapOf("missing" to names)).toString()
                rawResultJson = previewJson
                success = false
                status = "interrupted"
            }
            is ToolExecutionResult.ScheduleResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.previewJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.McpResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.MemoryResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.TerminalResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = when {
                    result.timedOut -> AgentConversationHistoryRepository.STATUS_TIMEOUT
                    result.success -> AgentConversationHistoryRepository.STATUS_SUCCESS
                    else -> AgentConversationHistoryRepository.STATUS_ERROR
                }
            }
            is ToolExecutionResult.Interrupted -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = false
                status = "interrupted"
                interruptedBy = result.interruptedBy
                interruptionReason = result.interruptionReason
            }
            is ToolExecutionResult.ContextResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.Error -> {
                summary = result.message
                previewJson = JSONObject(
                    mapOf("toolName" to result.toolName, "message" to result.message)
                ).toString()
                rawResultJson = previewJson
                success = false
                status = "error"
            }
        }

        val payload = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "displayName" to meta.displayName,
            "toolType" to meta.toolType,
            "serverName" to meta.serverName,
            "status" to status,
            "summary" to summary,
            "args" to argsJson,
            "argsJson" to argsJson,
            "resultPreviewJson" to previewJson,
            "rawResultJson" to rawResultJson,
            "success" to success
        )
        extractToolTitle(argsJson)?.let { payload["toolTitle"] = it }
        if (result is ToolExecutionResult.TerminalResult) {
            payload["timedOut"] = result.timedOut
            payload["terminalOutput"] = result.terminalOutput
            payload["terminalSessionId"] = result.terminalSessionId
            payload["terminalStreamState"] = result.terminalStreamState
        }
        if (result is ToolExecutionResult.Interrupted) {
            payload["interruptedBy"] = interruptedBy
            payload["interruptionReason"] = interruptionReason
            payload["terminalOutput"] = result.terminalOutput
            payload["terminalSessionId"] = result.terminalSessionId
            payload["terminalStreamState"] = result.terminalStreamState
        }
        if (result.artifacts.isNotEmpty()) {
            payload["artifacts"] = result.artifacts.map { it.toPayload() }
        }
        result.workspaceId?.let { payload["workspaceId"] = it }
        if (result.actions.isNotEmpty()) {
            payload["actions"] = result.actions.map { it.toPayload() }
        }
        if (toolName.equals("vlm_task", ignoreCase = true)) {
            listOf(previewJson, rawResultJson)
                .firstNotNullOfOrNull { json ->
                    runCatching {
                        JSONObject(json).optString("taskId").trim()
                            .takeIf { it.isNotEmpty() }
                    }.getOrNull()
                }
                ?.let { childRunId ->
                    payload["childRunId"] = childRunId
                    payload["child_run_id"] = childRunId
                }
            payload["spanKind"] = "vlm_task"
            payload["span_kind"] = "vlm_task"
        }
        return payload
    }

    private fun buildAgentToolRunLogCard(
        entryId: String,
        toolName: String,
        argsJson: String,
        payload: Map<String, Any?>,
        status: String,
        startedAtMillis: Long,
        finishedAtMillis: Long? = null
    ): Map<String, Any?> {
        val meta = resolveAgentToolMeta(toolName)
        val isVlmWrapper = toolName.trim().equals("vlm_task", ignoreCase = true)
        val durationMs = finishedAtMillis?.let { (it - startedAtMillis).coerceAtLeast(0L) }
        val success = when (val raw = payload["success"]) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> when (status) {
                AgentConversationHistoryRepository.STATUS_SUCCESS, "success", "running" -> true
                else -> false
            }
        }
        val title = listOf(
            payload["toolTitle"],
            payload["summary"],
            payload["displayName"],
            meta.displayName,
            toolName
        ).firstNotNullOfOrNull { raw ->
            raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        val resultValue = payload["resultPreviewJson"]
            ?: payload["rawResultJson"]
            ?: payload["progress"]?.let { progress ->
                JSONObject(
                    mapOf(
                        "status" to status,
                        "progress" to progress.toString()
                    )
                ).toString()
            }
            ?: JSONObject(mapOf("status" to status)).toString()
        val resultChildRunId = if (isVlmWrapper) {
            listOf(payload["resultPreviewJson"], payload["rawResultJson"])
                .firstNotNullOfOrNull { raw ->
                    raw?.toString()
                        ?.trim()
                        ?.takeIf { it.startsWith("{") }
                        ?.let { json ->
                            runCatching {
                                JSONObject(json).optString("taskId").trim()
                                    .takeIf { it.isNotEmpty() }
                            }.getOrNull()
                        }
                }
                .orEmpty()
        } else {
            ""
        }
        val childRunId = firstNonBlankString(
            payload["childRunId"],
            payload["child_run_id"],
            resultChildRunId
        )
        val parentCardId = firstNonBlankString(
            payload["parentCardId"],
            payload["parent_card_id"]
        )
        val spanKind = firstNonBlankString(
            payload["spanKind"],
            payload["span_kind"]
        ).ifBlank {
            if (isVlmWrapper) "vlm_task" else ""
        }
        val compileKind = firstNonBlankString(
            payload["compile_kind"],
            payload["compileKind"]
        ).ifBlank { "agent_tool" }
        val toolType = firstNonBlankString(
            payload["toolType"],
            payload["tool_type"],
            meta.toolType
        )
        val stepIndex = entryId.substringAfterLast('-')
            .toIntOrNull()
            ?.minus(1)
            ?.coerceAtLeast(0)
            ?: 0
        val header = linkedMapOf<String, Any?>(
            "step_index" to stepIndex,
            "title" to title,
            "tool_name" to toolName,
            "status" to status,
            "success" to success
        )
        durationMs?.let { header["duration_ms"] = it }
        val replaySource = agentToolReplaySource(toolName, argsJson, payload)
        if (replaySource.isNotBlank()) {
            header["source"] = replaySource
            header["run_source"] = replaySource
            header["runner"] = firstNonBlankString(
                payload["runner"],
                payload["runRunner"],
                payload["run_runner"]
            ).ifBlank { "oob_fixed_replay" }
        }
        return linkedMapOf(
            "card_id" to entryId,
            "tool_call_id" to entryId,
            "header" to header,
            "title" to title,
            "summary" to payload["summary"],
            "tool_name" to toolName,
            "toolName" to toolName,
            "tool_type" to toolType,
            "server_name" to meta.serverName,
            "status" to status,
            "success" to success,
            "duration_ms" to durationMs,
            "started_at_ms" to startedAtMillis,
            "finished_at_ms" to finishedAtMillis,
            "tool_call" to linkedMapOf(
                "id" to entryId,
                "name" to toolName,
                "arguments" to argsJson
            ),
            "params" to argsJson,
            "arguments" to argsJson,
            "result" to resultValue,
            "raw_result_json" to payload["rawResultJson"],
            "compile_kind" to compileKind
        ).apply {
            if (childRunId.isNotBlank()) {
                put("child_run_id", childRunId)
                put("childRunId", childRunId)
            }
            if (parentCardId.isNotBlank()) {
                put("parent_card_id", parentCardId)
                put("parentCardId", parentCardId)
            }
            if (spanKind.isNotBlank()) {
                put("span_kind", spanKind)
                put("spanKind", spanKind)
            }
            if (compileKind == "vlm_step") {
                put("source", "vlm")
                payload["token_usage"]?.let { put("token_usage", it) }
                payload["tokenUsage"]?.let { if (!containsKey("token_usage")) put("token_usage", it) }
            }
            if (replaySource.isNotBlank()) {
                put("source", replaySource)
                put("run_source", replaySource)
                put("selection_source", replaySource)
                put("runner", header["runner"])
            }
        }
    }

    private fun agentToolReplaySource(
        toolName: String,
        argsJson: String,
        payload: Map<String, Any?>
    ): String {
        val evidence = linkedSetOf<String>()
        var hasFunctionIdArgument = false
        fun collect(raw: Any?, depth: Int = 0) {
            if (raw == null || depth > 4) return
            when (raw) {
                is Map<*, *> -> raw.forEach { (key, value) ->
                    val normalizedKey = key?.toString()?.trim()?.lowercase().orEmpty()
                    if (normalizedKey in setOf("function_id", "functionid", "oob_function_id")) {
                        if (value?.toString()?.trim().orEmpty().isNotBlank()) {
                            hasFunctionIdArgument = true
                        }
                    }
                    if (normalizedKey in setOf(
                            "source",
                            "run_source",
                            "runsource",
                            "runner",
                            "executor",
                            "execution_status",
                            "executionstatus"
                        )
                    ) {
                        collect(value, depth + 1)
                    } else if (value is Map<*, *> || value is List<*>) {
                        collect(value, depth + 1)
                    }
                }
                is List<*> -> raw.forEach { collect(it, depth + 1) }
                else -> raw.toString().trim().lowercase()
                    .takeIf { it.isNotBlank() }
                    ?.let(evidence::add)
            }
        }
        collect(payload)
        if (argsJson.trim().startsWith("{")) {
            runCatching { collect(jsonObjectToMap(JSONObject(argsJson))) }
        }
        listOf("resultPreviewJson", "rawResultJson").forEach { key ->
            val jsonText = payload[key]?.toString()?.trim().orEmpty()
            if (jsonText.startsWith("{")) {
                runCatching { collect(jsonObjectToMap(JSONObject(jsonText))) }
            }
        }
        val normalizedToolName = toolName.trim().lowercase()
        val isReplay = normalizedToolName == OobFunctionToolNames.FUNCTION_RUN ||
            (normalizedToolName in setOf(
                RunLogReplayPolicy.TOOL_CALL_TOOL,
                RunLogReplayPolicy.TOOL_OOB_TOOL_CALL,
            ) && hasFunctionIdArgument) ||
            evidence.any { value ->
                value.contains("oob_omniflow_replay") ||
                    value.contains("oob_fixed_replay") ||
                    value.contains("omniflow_replay") ||
                    value.contains("oob_function_runner") ||
                    value == OOB_REUSABLE_EXECUTION_STATUS_COMPLETED_LOCAL
            }
        return if (isReplay) "omniflow_replay" else ""
    }

    private fun firstNonBlankString(vararg values: Any?): String {
        return values.firstNotNullOfOrNull { raw ->
            raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValueToKotlin(json.opt(key))
        }
        return result
    }

    private fun jsonValueToKotlin(value: Any?): Any? {
        return when (value) {
            JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> (0 until value.length()).map { index ->
                jsonValueToKotlin(value.opt(index))
            }
            else -> value
        }
    }

    private fun buildAssistantResponseRunLogCard(
        entryId: String,
        text: String,
        isError: Boolean,
        startedAtMillis: Long,
        finishedAtMillis: Long
    ): Map<String, Any?> {
        val normalizedText = AgentTextSanitizer.sanitizeUtf16(text).trim()
        val durationMs = (finishedAtMillis - startedAtMillis).coerceAtLeast(0L)
        val status = if (isError) {
            AgentConversationHistoryRepository.STATUS_ERROR
        } else {
            AgentConversationHistoryRepository.STATUS_SUCCESS
        }
        val title = t("最终回复", "Final response")
        val result = JSONObject(
            mapOf(
                "text" to normalizedText,
                "status" to status
            )
        ).toString()
        return linkedMapOf(
            "card_id" to entryId,
            "header" to linkedMapOf<String, Any?>(
                "step_index" to 0,
                "title" to title,
                "tool_name" to "assistant_response",
                "status" to status,
                "success" to !isError,
                "duration_ms" to durationMs
            ),
            "title" to title,
            "summary" to normalizedText.take(160),
            "tool_name" to "assistant_response",
            "toolName" to "assistant_response",
            "tool_type" to "message",
            "status" to status,
            "success" to !isError,
            "duration_ms" to durationMs,
            "started_at_ms" to startedAtMillis,
            "finished_at_ms" to finishedAtMillis,
            "params" to JSONObject(mapOf("kind" to "assistant_response")).toString(),
            "arguments" to JSONObject(mapOf("kind" to "assistant_response")).toString(),
            "result" to result,
            "output" to normalizedText,
            "compile_kind" to "assistant_text"
        )
    }

    private fun conversationHistoryRepository(): AgentConversationHistoryRepository {
        return AgentConversationHistoryRepository(context)
    }

    private fun normalizeConversationMode(mode: String?): String {
        return mode?.trim()?.ifEmpty { null } ?: "normal"
    }

    private fun resolveRequiredPermissionIds(missing: List<String>): List<String> {
        val nameToId = linkedMapOf(
            "无障碍权限" to "accessibility",
            "Accessibility" to "accessibility",
            "悬浮窗权限" to "overlay",
            "Overlay" to "overlay",
            "应用列表读取权限" to "installed_apps",
            "Installed Apps Access" to "installed_apps",
            "Shizuku 权限" to "shizuku",
            "Shizuku Permission" to "shizuku",
            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME to "workspace_storage",
            PublicStorageAccess.REQUIRED_PERMISSION_NAME to "public_storage",
            "Public Storage Access" to "public_storage"
        )
        return missing.mapNotNull { raw ->
            nameToId[raw.trim()]
        }.distinct()
    }

    private fun buildPermissionCardData(requiredPermissionIds: List<String>): Map<String, Any?> {
        return linkedMapOf(
            "type" to "permission_section",
            "requiredPermissionIds" to requiredPermissionIds
        )
    }

    private fun extractChatTaskText(content: String): String = extractChatTaskTextPayload(content)


    /**
     * 执行陪伴模式
     */
    fun createCompanionTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        val listener = this;
        mainJob.launch {
            try {
                AssistsUtil.Core.createCompanionTask(
                    context, listener
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: PermissionException) {
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_ERROR", e.message, null);
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("DO_TASK_ERROR", e.message, null)
                }
            }
        }

    }

    /**
     * 取消陪伴模式
     */
    fun cancelTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                AgentVlmUiSession.requestStopActiveSession()
                OmniFlowUiSession.requestStopActiveSession()
                cancelActiveAgentRun(null, "cancelTask")
                AssistsUtil.Core.cancelRunningTask()
                AssistsUtil.Core.finishTask(context)
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("CANCEL_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 取消正在运行的任务，不影响陪伴模式
     */
    fun cancelRunningTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")?.trim()?.takeIf { it.isNotEmpty() }
                val stoppedVlmSession = taskId?.let {
                    AgentVlmUiSession.requestStopSession(it)
                } ?: AgentVlmUiSession.requestStopActiveSession()
                val stoppedOmniFlowSession = taskId?.let {
                    OmniFlowUiSession.requestStopSession(it)
                } ?: OmniFlowUiSession.requestStopActiveSession()
                val cancelledAgentRun = taskId?.let {
                    cancelActiveAgentRun(it, "cancelRunningTask")
                } ?: false
                val stoppedNativeTask = if (taskId == null) {
                    AssistsUtil.Core.cancelRunningTask()
                } else {
                    AssistsUtil.Core.cancelRunningTask(taskId)
                }
                if (
                    taskId != null &&
                    !stoppedVlmSession &&
                    !stoppedOmniFlowSession &&
                    !cancelledAgentRun &&
                    !stoppedNativeTask
                ) {
                    OmniLog.w(
                        TAG,
                        "cancelRunningTask target not found; ignoring stale targeted cancel: taskId=$taskId"
                    )
                }
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "cancelRunningTask error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CANCEL_RUNNING_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    fun stopAgentToolCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")?.trim().orEmpty()
                val cardId = call.argument<String>("cardId")?.trim().orEmpty()
                if (taskId.isBlank() || cardId.isBlank()) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "INVALID_ARGUMENTS",
                            "taskId and cardId are required",
                            null
                        )
                    }
                    return@launch
                }
                val runContext = synchronized(activeAgentLock) {
                    activeAgentRuns[taskId]
                }
                val stopped = runContext?.requestManualToolStop(cardId) == true
                withContext(Dispatchers.Main) {
                    if (stopped) {
                        result.success("SUCCESS")
                    } else {
                        result.error(
                            "NO_MATCHING_ACTIVE_TOOL",
                            "No running tool matches cardId=$cardId",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "stopAgentToolCall error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("STOP_AGENT_TOOL_CALL_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 提供用户输入给VLM任务（响应INFO动作）
     */
    fun provideUserInputToVLMTask(call: MethodCall, result: MethodChannel.Result) {
        try {
            val userInput = call.argument<String>("userInput")!!
            val success = AssistsUtil.Core.provideUserInputToVLMTask(userInput)
            mainJob.launch(Dispatchers.Main) {
                result.success(success)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "提供用户输入失败: ${e.message}")
            mainJob.launch(Dispatchers.Main) {
                result.error("PROVIDE_USER_INPUT_ERROR", e.message, null)
            }
        }
    }

    fun pauseVLMTask(call: MethodCall, result: MethodChannel.Result) {
        try {
            val success = AssistsUtil.Core.pauseVLMTask()
            mainJob.launch(Dispatchers.Main) {
                result.success(success)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "暂停VLM任务失败: ${e.message}")
            mainJob.launch(Dispatchers.Main) {
                result.error("PAUSE_VLM_TASK_ERROR", e.message, null)
            }
        }
    }

    fun resumeVLMTask(call: MethodCall, result: MethodChannel.Result) {
        try {
            val success = AssistsUtil.Core.resumeVLMTask()
            mainJob.launch(Dispatchers.Main) {
                result.success(success)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "恢复VLM任务失败: ${e.message}")
            mainJob.launch(Dispatchers.Main) {
                result.error("RESUME_VLM_TASK_ERROR", e.message, null)
            }
        }
    }

    /**
     * 通知VLM任务总结Sheet已准备就绪
     */
    fun notifySummarySheetReady(call: MethodCall, result: MethodChannel.Result) {
        try {
            val success = AssistsUtil.Core.notifySummarySheetReady()
            mainJob.launch(Dispatchers.Main) {
                if (success) {
                    result.success("SUCCESS")
                } else {
                    result.error("NO_RUNNING_VLM_TASK", "没有正在运行的VLM任务", null)
                }
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "通知总结Sheet准备就绪失败: ${e.message}")
            mainJob.launch(Dispatchers.Main) {
                result.error("NOTIFY_SUMMARY_SHEET_READY_ERROR", e.message, null)
            }
        }
    }

    /**
     * 取消聊天任务
     */
    fun cancelChatTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")
                cancelActiveAgentRun(taskId, "cancelChatTask")
                AssistsUtil.Core.cancelChatTask(taskId)
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("CANCEL_MESSAGE_ERROR", e.message, null)
                }
            }
        }
    }

    fun isCompanionTaskRunning(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                var isRunning = AssistsUtil.Core.isCompanionTaskRunning()
                withContext(Dispatchers.Main) {
                    result.success(isRunning)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.success(false)
                }
            }
        }
    }

    /**
     * 取消陪伴任务的回到桌面操作
     */
    fun cancelCompanionGoHome(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                AssistsUtil.Core.cancelCompanionGoHome()
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("CANCEL_GO_HOME_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * Trigger the system Home action.
     */
    fun pressHome(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                if (!AssistsCore.isAccessibilityServiceEnabled()) {
                    throw PermissionException("Accessibility service is not enabled")
                }
                AccessibilityController.initController()
                AccessibilityController.goHome()
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: PermissionException) {
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_ERROR", e.message, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("PRESS_HOME_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 创建聊天任务
     */
    fun createChatTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        val taskID = call.argument<String>("taskID") ?: ""
        val content = call.argument<List<Map<String, Any>>>("content") ?: emptyList()
        val provider = call.argument<String>("provider")
        val conversationId = call.argument<Number>("conversationId")?.toLong()
        val conversationMode = normalizeConversationMode(call.argument<String>("conversationMode"))
        val userMessage = call.argument<String>("userMessage")?.trim().orEmpty()
        val userAttachments = call.argument<List<Map<String, Any?>>>("userAttachments") ?: emptyList()
        val modelOverride = resolveChatTaskModelOverride(
            call.argument<Map<String, Any?>>("modelOverride"),
            ModelProviderConfigStore::getProfile
        )
        val reasoningEffort = normalizeReasoningEffort(
            call.argument<String>("reasoningEffort")
        )
        val openClawConfigMap = call.argument<Map<String, Any>>("openClawConfig")
        val openClawConfig = openClawConfigMap?.let { map ->
            val baseUrl = map["baseUrl"] as? String ?: ""
            if (baseUrl.isBlank()) {
                null
            } else {
                cn.com.omnimind.assists.api.bean.TaskParams.OpenClawConfig(
                    baseUrl = baseUrl,
                    token = map["token"] as? String,
                    userId = map["userId"] as? String,
                    sessionKey = map["sessionKey"] as? String
                )
            }
        }

        mainJob.launch {
            try {
                TaskRuntimeSettings.onTaskStarted(context)
                val workspaceMemoryService = WorkspaceMemoryService(context)
                val preparedContent = prepareChatTaskContent(
                    content = content,
                    conversationMode = conversationMode,
                    chatPromptContent = workspaceMemoryService.readChatPrompt()
                )
                val normalizedConversationId = conversationId?.takeIf { it > 0L }
                if (normalizedConversationId != null) {
                    val repository = conversationHistoryRepository()
                    if (userMessage.isNotBlank() || userAttachments.isNotEmpty()) {
                        repository.upsertUserMessage(
                            conversationId = normalizedConversationId,
                            conversationMode = conversationMode,
                            entryId = "$taskID-user",
                            text = userMessage,
                            attachments = userAttachments
                        )
                    }
                    registerChatTaskPersistenceState(
                        taskID,
                        ChatTaskPersistenceState(
                            conversationId = normalizedConversationId,
                            conversationMode = conversationMode,
                            userEntryId = "$taskID-user",
                            assistantEntryId = "$taskID-assistant",
                            modelOverride = modelOverride,
                            reasoningEffort = reasoningEffort,
                            promptTokenThreshold = repository
                                .getConversation(normalizedConversationId)
                                ?.promptTokenThreshold
                                ?.coerceAtLeast(1)
                        )
                    )
                }
                AssistsUtil.Core.createChatTask(
                    taskID,
                    preparedContent,
                    this@AssistsCoreManager,
                    provider,
                    openClawConfig,
                    modelOverride,
                    reasoningEffort
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: PermissionException) {
                removeChatTaskPersistenceState(taskID)
                TaskRuntimeSettings.onTaskFinished(context)
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_ERROR", e.message, null)
                }
            } catch (e: Exception) {
                removeChatTaskPersistenceState(taskID)
                TaskRuntimeSettings.onTaskFinished(context)
                withContext(Dispatchers.Main) {
                    result.error("DO_TASK_ERROR", e.message, null)
                }
            }
        }

    }


    override suspend fun onChatMessage(taskID: String, content: String, type: String?) {
        getChatTaskPersistenceState(taskID)?.let { state ->
            val repository = conversationHistoryRepository()
            val normalizedType = type?.trim()?.lowercase().orEmpty()
            if (
                state.conversationMode.equals(CHAT_ONLY_MODE, ignoreCase = true) &&
                normalizedType != "error" &&
                normalizedType != "rate_limited"
            ) {
                extractChatTaskPromptTokens(content)?.let { promptTokens ->
                    val promptTokenThreshold =
                        state.promptTokenThreshold?.coerceAtLeast(1)
                            ?: AgentConversationContextCompactor.DEFAULT_PROMPT_TOKEN_THRESHOLD
                    state.latestPromptTokens = promptTokens
                    state.promptTokenThreshold = promptTokenThreshold
                    repository.updatePromptTokenUsage(
                        conversationId = state.conversationId,
                        promptTokens = promptTokens,
                        threshold = promptTokenThreshold
                    )
                    withContext(Dispatchers.Main) {
                        invokeFlutterEventSafely(
                            "onAgentPromptTokenUsageChanged",
                            mapOf(
                                "taskId" to taskID,
                                "conversationId" to state.conversationId,
                                "conversationMode" to state.conversationMode,
                                "latestPromptTokens" to promptTokens,
                                "promptTokenThreshold" to promptTokenThreshold
                            )
                        )
                    }
                }
            }
            when (normalizedType) {
                "summary_start",
                "openclaw_attachment" -> Unit
                "error",
                "rate_limited" -> {
                    val message = extractChatTaskText(content).ifBlank {
                        content.trim().ifBlank {
                            if (normalizedType == "rate_limited") {
                                "请求过于频繁，请稍后重试。"
                            } else {
                                "网络异常，请稍后重试。"
                            }
                        }
                    }
                    state.assistantBuffer.setLength(0)
                    state.assistantBuffer.append(message)
                    state.isError = true
                }
                else -> {
                    val message = extractChatTaskText(content)
                    if (message.isNotEmpty()) {
                        state.assistantBuffer.append(message)
                    }
                    state.isError = false
                }
            }
            val snapshot = state.assistantBuffer.toString().trim()
            if (snapshot.isNotEmpty()) {
                repository.upsertAssistantMessage(
                    conversationId = state.conversationId,
                    conversationMode = state.conversationMode,
                    entryId = state.assistantEntryId,
                    text = snapshot,
                    isError = state.isError
                )
            }
        }
        withContext(Dispatchers.Main) {
            try {
                val isSummary = isSummaryTask(taskID)
                val mainChannel = mainEngineChannel

                if (isSummary && mainChannel != null && mainChannel != channel) {
                    mainChannel.invokeMethod(
                        "onChatMessage", mapOf(
                            "taskID" to taskID, "content" to content, "type" to type
                        )
                    )
                    // 如果当前不是主引擎通道，避免在半屏重复展示
                    return@withContext
                }

                channel.invokeMethod(
                    "onChatMessage", mapOf(
                        "taskID" to taskID, "content" to content, "type" to type
                    )
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "onChatMessage error: ${e.message}")
            }

        }
    }

    override suspend fun onChatMessageEnd(taskID: String) {
        val persistenceState = removeChatTaskPersistenceState(taskID)
        persistenceState?.let { state ->
            val snapshot = state.assistantBuffer.toString().trim()
            if (snapshot.isNotEmpty()) {
                conversationHistoryRepository().upsertAssistantMessage(
                    conversationId = state.conversationId,
                    conversationMode = state.conversationMode,
                    entryId = state.assistantEntryId,
                    text = snapshot,
                    isError = state.isError
                )
            }
        }
        val compactedConversationPayload = maybeAutoCompactChatOnlyConversation(
            taskID,
            persistenceState
        )
        withContext(Dispatchers.Main) {
            try {
                val isSummary = isSummaryTask(taskID)
                val mainChannel = mainEngineChannel

                if (isSummary && mainChannel != null && mainChannel != channel) {
                    mainChannel.invokeMethod(
                        "onChatMessageEnd", mapOf(
                            "taskID" to taskID
                        )
                    )
                    // 如果当前不是主引擎通道，避免在半屏重复展示
                    return@withContext
                }

                channel.invokeMethod(
                    "onChatMessageEnd", mapOf(
                        "taskID" to taskID
                    )
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "onChatMessageEnd error: ${e.message}")
            }

        }
        compactedConversationPayload?.let { payload ->
            FlutterChatSyncBridge.dispatchConversationListChanged(
                reason = "conversation_updated",
                conversation = payload
            )
        }
        TaskRuntimeSettings.onTaskFinished(context)
        if (persistenceState?.isError != true) {
            TaskRuntimeSettings.notifyTaskFinished(
                context = context,
                title = "小万回复已完成",
                message = "纯聊天回复已完成，点击查看详情",
                conversationId = persistenceState?.conversationId,
                conversationMode = persistenceState?.conversationMode
            )
        }
    }

    private suspend fun maybeAutoCompactChatOnlyConversation(
        taskId: String,
        state: ChatTaskPersistenceState?
    ): Map<String, Any?>? {
        if (state == null || state.isError) {
            return null
        }
        if (!state.conversationMode.equals(CHAT_ONLY_MODE, ignoreCase = true)) {
            return null
        }
        val latestPromptTokens = state.latestPromptTokens ?: return null
        val promptTokenThreshold =
            state.promptTokenThreshold?.coerceAtLeast(1)
                ?: AgentConversationContextCompactor.DEFAULT_PROMPT_TOKEN_THRESHOLD
        if (latestPromptTokens <= promptTokenThreshold) {
            return null
        }

        val repository = conversationHistoryRepository()
        val candidate = repository.getContextCompactionCandidate(
            conversationId = state.conversationId,
            conversationMode = state.conversationMode
        ) ?: return null
        if (candidate.entriesToCompact.isEmpty()) {
            return null
        }

        withContext(Dispatchers.Main) {
            invokeFlutterEventSafely(
                "onAgentContextCompactionStateChanged",
                mapOf(
                    "taskId" to taskId,
                    "conversationId" to state.conversationId,
                    "conversationMode" to state.conversationMode,
                    "isCompacting" to true,
                    "latestPromptTokens" to latestPromptTokens,
                    "promptTokenThreshold" to promptTokenThreshold
                )
            )
        }

        var conversationPayload: Map<String, Any?>? = null
        try {
            val payload = ConversationDomainService(context).compactConversationContext(
                conversationId = state.conversationId,
                conversationMode = state.conversationMode,
                modelOverride = chatModelOverrideToAgentModelOverride(state.modelOverride),
                reasoningEffort = state.reasoningEffort
            )
            @Suppress("UNCHECKED_CAST")
            conversationPayload = payload["conversation"] as? Map<String, Any?>
        } catch (e: Exception) {
            OmniLog.w(TAG, "纯聊天自动压缩失败: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) {
                invokeFlutterEventSafely(
                    "onAgentContextCompactionStateChanged",
                    mapOf(
                        "taskId" to taskId,
                        "conversationId" to state.conversationId,
                        "conversationMode" to state.conversationMode,
                        "isCompacting" to false,
                        "latestPromptTokens" to latestPromptTokens,
                        "promptTokenThreshold" to promptTokenThreshold
                    )
                )
            }
        }
        return conversationPayload
    }


    override fun onTaskFinish() {
        mainJob.launch(Dispatchers.Main) {
            invokeFlutterEventSafely("onTaskFinish", HashMap<String, String>())
        }
    }

    override fun onVLMTaskFinish() {
        handleVlmTaskFinished("assists_core_listener")
    }

    private fun handleVlmTaskFinished(source: String, taskId: String? = null) {
        mainJob.launch(Dispatchers.Main) {
            OmniLog.d(TAG, "收到 VLM 任务完成回调: source=$source")
            TaskRuntimeSettings.onTaskFinished(context)
            TaskRuntimeSettings.notifyTaskFinished(
                context = context,
                title = "小万任务已完成",
                message = "任务已完成，点击查看详情",
                conversationId = currentConversationId,
                conversationMode = currentConversationMode
            )
            navigateBackToChatIfNeeded()
            invokeFlutterEventSafely(
                "onVLMTaskFinish",
                taskId?.let { mapOf("taskId" to it) } ?: HashMap<String, String>()
            )
        }
    }

    override fun onVLMRequestUserInput(question: String) {
        mainJob.launch(Dispatchers.Main) {
            invokeFlutterEventSafely(
                "onVLMRequestUserInput", mapOf(
                    "question" to question
                )
            )
            OmniLog.d(TAG, "已通知Flutter层VLM请求用户输入：$question")
        }
    }

    override fun onVlmToolEvent(event: Map<String, Any?>) {
        dispatchVlmToolStreamEvent(event)
    }

    private fun nextVlmStreamSeq(taskId: String): Long {
        return synchronized(vlmStreamSeqByTask) {
            val next = (vlmStreamSeqByTask[taskId] ?: 0L) + 1L
            vlmStreamSeqByTask[taskId] = next
            next
        }
    }

    private fun dispatchVlmToolStreamEvent(
        event: Map<String, Any?>,
        boundTaskId: String? = null
    ) {
        val taskId = boundTaskId?.trim()?.takeIf { it.isNotEmpty() }
            ?: event["taskId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: event["runLogId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        val cardId = listOf(
            event["cardId"],
            event["toolCallId"],
            event["tool_call_id"]
        ).firstNotNullOfOrNull { raw ->
            raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return
        val streamKind = event["agentStreamKind"]?.toString()?.trim()
            ?: event["kind"]?.toString()?.trim()
            ?: "tool_progress"
        val normalizedKind = when (streamKind) {
            "tool_started", "tool_progress", "tool_completed" -> streamKind
            else -> "tool_progress"
        }
        val roundIndex = event["stepIndex"]?.toString()?.toIntOrNull()?.plus(1) ?: 1
        val success = when (val raw = event["success"]) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> null
        }
        val extras = linkedMapOf<String, Any?>(
            "cardId" to cardId,
            "toolCallId" to cardId,
            "toolName" to (event["toolName"] ?: "vlm_task"),
            "displayName" to (event["displayName"] ?: event["toolName"] ?: "vlm_task"),
            "toolType" to "vlm",
            "spanKind" to (event["spanKind"] ?: event["span_kind"] ?: "vlm_step"),
            "span_kind" to (event["span_kind"] ?: event["spanKind"] ?: "vlm_step"),
            "parentSpanKind" to (event["parentSpanKind"] ?: event["parent_span_kind"] ?: "vlm_task"),
            "parent_span_kind" to (event["parent_span_kind"] ?: event["parentSpanKind"] ?: "vlm_task"),
            "runLogId" to (event["runLogId"] ?: event["run_id"] ?: taskId),
            "run_id" to (event["run_id"] ?: event["runLogId"] ?: taskId),
            "status" to (event["status"] ?: if (normalizedKind == "tool_completed") "success" else "running")
        ).apply {
            putAll(event)
            put("cardId", cardId)
            put("toolCallId", cardId)
            put("toolType", "vlm")
        }
        val seq = nextVlmStreamSeq(taskId)
        val payload = sanitizeInteropMap(
            AgentStreamEvent(
                taskId = taskId,
                seq = seq,
                kind = normalizedKind,
                createdAt = System.currentTimeMillis(),
                entryId = cardId,
                roundIndex = roundIndex,
                isFinal = normalizedKind == "tool_completed",
                success = success,
                extras = extras
            ).toPayload(
                conversationId = null,
                conversationMode = "agent"
            ) + mapOf(
                "streamMeta" to linkedMapOf<String, Any?>(
                    "schema_version" to AGENT_STREAM_META_SCHEMA_VERSION,
                    "trace_id" to taskId,
                    "run_id" to taskId,
                    "span_id" to cardId,
                    "parent_span_id" to taskId,
                    "seq" to seq,
                    "roundIndex" to roundIndex,
                    "kind" to normalizedKind,
                    "parentTaskId" to taskId,
                    "runLogId" to (event["runLogId"] ?: event["run_id"] ?: taskId),
                    "entryId" to cardId,
                    "isFinal" to (normalizedKind == "tool_completed")
                )
            )
        )
        agentStreamEventBatcher.enqueue(payload)
        if (normalizedKind == "tool_completed") {
            agentStreamEventBatcher.flushNow()
        }
    }

    fun createVLMOperationTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {


        val taskId = call.argument<String>("taskId")?.trim().orEmpty()
        val needSummary = call.argument<Boolean>("needSummary") ?: false
        val skipGoHome = call.argument<Boolean>("skipGoHome") ?: false
        val vlmListener = if (taskId.isEmpty()) {
            this@AssistsCoreManager
        } else {
            object : OnMessagePushListener by this@AssistsCoreManager {
                override fun onVLMTaskFinish() {
                    handleVlmTaskFinished("create_vlm_operation_task", taskId)
                }

                override fun onVLMRequestUserInput(question: String) {
                    mainJob.launch(Dispatchers.Main) {
                        invokeFlutterEventSafely(
                            "onVLMRequestUserInput",
                            mapOf(
                                "question" to question,
                                "taskId" to taskId
                            )
                        )
                    }
                }

                override fun onVlmToolEvent(event: Map<String, Any?>) {
                    dispatchVlmToolStreamEvent(event, boundTaskId = taskId)
                }
            }
        }
        mainJob.launch {
            try {
                TaskRuntimeSettings.onTaskStarted(context)
                AssistsUtil.Core.createVLMOperationTask(
                    context,
                    call.argument<String>("goal")!!,
                    call.argument<String>("model"),
                    call.argument<Int>("maxSteps"),
                    call.argument<String>("packageName"),
                    vlmListener,
                    needSummary,
                    skipGoHome,
                    call.argument<String>("stepSkillGuidance").orEmpty(),
                    taskId.takeIf { it.isNotBlank() }
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: PermissionException) {
                TaskRuntimeSettings.onTaskFinished(context)
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_ERROR", e.message, null)
                }
            } catch (e: Exception) {
                TaskRuntimeSettings.onTaskFinished(context)
                withContext(Dispatchers.Main) {
                    result.error("DO_TASK_ERROR", e.message, null)
                }
            }
        }

    }

    fun getInternalRunLogs(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val limit = call.argument<Number>("limit")?.toInt() ?: 50
            val offset = call.argument<Number>("offset")?.toInt() ?: 0
            val payload = withContext(Dispatchers.IO) {
                InternalRunLogStore.listRuns(context, limit, offset)
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun getInternalRunLogTimeline(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val runId = call.argument<String>("runId")?.trim().orEmpty()
            val payload = withContext(Dispatchers.IO) {
                InternalRunLogStore.timelinePayload(context, runId)
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun getAgentToolFeatures(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val payload = AgentToolFeatureStore.getFeatures(context)
            withContext(Dispatchers.Main) { result.success(payload) }
        }
    }

    fun setAgentToolFeatures(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val oobEnabled = when (val raw = args["oobFunctionAsToolEnabled"]) {
                is Boolean -> raw
                is String -> raw.equals("true", ignoreCase = true)
                else -> null
            }
            if (oobEnabled != null) {
                AgentToolFeatureStore.setOobFunctionAsToolEnabled(context, oobEnabled)
            }
            val payload = AgentToolFeatureStore.getFeatures(context)
            withContext(Dispatchers.Main) { result.success(payload) }
        }
    }

    fun registerOobReusableFunction(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val directSpec = normalizeMethodCallMap(args["functionSpec"])
            val legacySpec = normalizeMethodCallMap(args["spec"])
            val functionSpec = when {
                directSpec.isNotEmpty() -> directSpec
                legacySpec.isNotEmpty() -> legacySpec
                args.containsKey("function_id") -> args
                else -> emptyMap()
            }
            val payload = runCatching {
                OobFunctionRepository(context).register(functionSpec)
            }.getOrElse { error ->
                linkedMapOf(
                    "success" to false,
                    "error_code" to "OOB_FUNCTION_REGISTER_FAILED",
                    "error_message" to error.message.orEmpty(),
                    "function_kind" to "oob_reusable_function",
                    "asset_state" to "native_local",
                    "oob_function_as_tool_enabled" to
                        AgentToolFeatureStore.isOobFunctionAsToolEnabled(context)
                )
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun convertInternalRunLogToOobFunction(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val runId = (
                args["runId"] ?: args["run_id"] ?: call.argument<String>("runId")
            )?.toString()?.trim().orEmpty()
            val register = when (val raw = args["register"]) {
                is Boolean -> raw
                is String -> raw.equals("true", ignoreCase = true)
                else -> true
            }
            val payload = runCatching {
                OobRunLogReplayService(context).convertRunLog(
                    runId = runId,
                    register = register,
                    functionIdOverride = args["functionId"]?.toString()
                        ?: args["function_id"]?.toString(),
                    nameOverride = args["name"]?.toString(),
                    descriptionOverride = args["description"]?.toString()
                )
            }.getOrElse { error ->
                linkedMapOf(
                    "success" to false,
                    "error_code" to "OOB_RUN_LOG_CONVERT_FAILED",
                    "error_message" to error.message.orEmpty(),
                    "run_id" to runId,
                    "function_kind" to "oob_reusable_function",
                    "asset_state" to "native_local"
                )
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun startHumanTrajectoryLearning(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val name = (
                args["name"] ?: args["title"] ?: call.argument<String>("name")
            )?.toString()?.trim().orEmpty()
            val description = (
                args["description"] ?: args["goal"] ?: call.argument<String>("description")
            )?.toString()?.trim().orEmpty()
            val enableDebugScreenshots = BuildConfig.DEBUG && (
                booleanMethodCallValue(args["enableDebugScreenshots"]) ||
                    booleanMethodCallValue(args["debugScreenshots"]) ||
                    booleanMethodCallValue(args["recordDebugScreenshots"])
                )
            val learningResult = runCatching {
                val sessionResult = withContext(Dispatchers.Default) {
                    if (!awaitHumanTrajectoryRecordingBackend()) {
                        throw IllegalStateException("无障碍服务未就绪，无法开始手动录制")
                    }
                    val startedSession = HumanTrajectoryLearningSession.start(
                        context = context,
                        name = name,
                        description = description,
                        enableDebugScreenshots = enableDebugScreenshots
                    )
                    if (startedSession.isCompleted) {
                        startedSession.await()
                    }
                    if (!HumanTrajectoryLearningSession.pauseActive()) {
                        throw IllegalStateException("手动录制初始化失败，无法进入待机状态")
                    }
                    startedSession
                }
                withContext(Dispatchers.Main) {
                    val controlShown = ManualRecordingControlOverlay.show(
                        context,
                        ManualRecordingControlOverlay.State.READY,
                        onCaptureState = {
                            captureCurrentUdegStateForManualRecording(description)
                        }
                    )
                    ManualRecordingControlOverlay.markReady()
                    OmniLog.d(TAG, "manual recording control overlay shown=$controlShown")
                }
                sessionResult.await()
            }.getOrElse { error ->
                OmniLog.e(TAG, "start human trajectory learning failed: ${error.message}", error)
                withContext(Dispatchers.Main) {
                    ManualRecordingControlOverlay.dismiss()
                    DraggableBallInstance.finishDoingTask("手动录制失败")
                    result.success(
                        linkedMapOf(
                            "success" to false,
                            "error_code" to "HUMAN_TRAJECTORY_LEARNING_FAILED",
                            "error_message" to error.message.orEmpty(),
                            "function_kind" to "oob_reusable_function",
                            "asset_state" to "native_local"
                        )
                    )
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                ManualRecordingControlOverlay.dismiss()
            }
            val payload = if (!learningResult.success) {
                val errorMessage = learningResult.errorMessage.ifBlank { "未记录到可复用的人类操作" }
                val runLog = withContext(Dispatchers.Default) {
                    runCatching {
                        InternalRunLogStore.timelinePayload(context, learningResult.runId)
                    }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    ManualRecordingControlOverlay.dismiss()
                    DraggableBallInstance.finishDoingTask(
                        if (errorMessage.contains("取消")) "学习已取消" else "学习失败"
                    )
                }
                linkedMapOf(
                    "success" to false,
                    "error_code" to if (errorMessage.contains("取消")) {
                        "HUMAN_TRAJECTORY_CANCELLED"
                    } else if (errorMessage.contains("raw touch") || errorMessage.contains("遗漏")) {
                        "HUMAN_TRAJECTORY_INCOMPLETE"
                    } else {
                        "HUMAN_TRAJECTORY_EMPTY"
                    },
                    "error_message" to errorMessage,
                    "run_id" to learningResult.runId,
                    "action_count" to learningResult.actionCount,
                    "summary" to learningResult.summary,
                    "diagnostics" to learningResult.diagnostics.takeIf { it.isNotEmpty() },
                    "run_log" to runLog,
                    "function_kind" to "oob_reusable_function",
                    "asset_state" to "native_local"
                )
            } else {
                withContext(Dispatchers.Main) {
                    DraggableBallInstance.setDoing(
                        message = "正在整理复用指令",
                        isShowTakeOver = false,
                        subMessage = "请稍候",
                        isShowStop = false
                    )
                }
                val conversion = withContext(Dispatchers.Default) {
                    runCatching {
                        OobRunLogReplayService(context).convertRunLog(
                            runId = learningResult.runId,
                            register = true,
                            nameOverride = learningResult.name,
                            descriptionOverride = learningResult.description
                        )
                    }.getOrElse { error ->
                        OmniLog.e(TAG, "human trajectory conversion failed: ${error.fullCauseMessage()}", error)
                        linkedMapOf(
                            "success" to false,
                            "error_code" to "HUMAN_TRAJECTORY_CONVERT_FAILED",
                            "error_message" to error.fullCauseMessage(),
                            "error_type" to error.javaClass.name,
                            "error_cause_chain" to error.causeChainPayload(),
                            "run_id" to learningResult.runId,
                            "function_kind" to "oob_reusable_function",
                            "asset_state" to "native_local"
                        )
                    }
                }
                val conversionSuccess = conversion["success"] == true
                val runLog = withContext(Dispatchers.Default) {
                    runCatching {
                        InternalRunLogStore.timelinePayload(context, learningResult.runId)
                    }.getOrNull()
                }
                val actionList = learningResult.actions.mapIndexed { index, action ->
                    manualRecordedActionPayload(index + 1, action)
                }
                val recordingWarning = learningResult.errorMessage
                    .takeIf { learningResult.success && it.isNotBlank() }
                withContext(Dispatchers.Main) {
                    ManualRecordingControlOverlay.dismiss()
                    DraggableBallInstance.finishDoingTask(
                        if (conversionSuccess) "学习完成" else "录制完成"
                    )
                }
                val recordingSuccess = learningResult.actionCount > 0
                linkedMapOf<String, Any?>(
                    "success" to recordingSuccess,
                    "recording_success" to recordingSuccess,
                    "conversion_success" to conversionSuccess,
                    "error_code" to if (conversionSuccess) null else {
                        conversion["error_code"] ?: "HUMAN_TRAJECTORY_CONVERT_FAILED"
                    },
                    "error_message" to if (conversionSuccess) null else {
                        conversion["error_message"] ?: "录制已完成，但生成复用指令失败"
                    },
                    "warning_message" to recordingWarning,
                    "recording_warning" to recordingWarning,
                    "run_id" to learningResult.runId,
                    "name" to learningResult.name,
                    "description" to learningResult.description,
                    "action_count" to learningResult.actionCount,
                    "actions" to actionList,
                    "editable_actions" to actionList,
                    "summary" to learningResult.summary,
                    "diagnostics" to learningResult.diagnostics.takeIf { it.isNotEmpty() },
                    "run_log" to runLog,
                    "function_id" to conversion["function_id"],
                    "created_function_id" to conversion["created_function_id"],
                    "function_spec" to conversion["function_spec"],
                    "conversion" to conversion,
                    "function_kind" to "oob_reusable_function",
                    "asset_state" to "native_local"
                )
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun pauseHumanTrajectoryLearning(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val (paused, status) = withContext(Dispatchers.Default) {
                HumanTrajectoryLearningSession.pauseActive() to HumanTrajectoryLearningSession.status().asMap()
            }
            withContext(Dispatchers.Main) {
                if (paused) {
                    ManualRecordingControlOverlay.markPaused()
                }
                result.success(
                    linkedMapOf(
                        "success" to paused,
                        "recording_active" to status["recording_active"],
                        "recording_paused" to status["recording_paused"],
                        "action_count" to status["action_count"],
                        "latest_action_summary" to status["latest_action_summary"],
                        "status" to status,
                        "error_code" to if (paused) null else "NO_ACTIVE_RECORDING",
                        "error_message" to if (paused) null else "No active human recording session",
                        "source" to "human_trajectory_learning"
                    ).filterValues { it != null }
                )
            }
        }
    }

    fun resumeHumanTrajectoryLearning(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val (resumed, status) = withContext(Dispatchers.Default) {
                HumanTrajectoryLearningSession.resumeActive() to HumanTrajectoryLearningSession.status().asMap()
            }
            withContext(Dispatchers.Main) {
                if (resumed) {
                    ManualRecordingControlOverlay.markRecording()
                }
                result.success(
                    linkedMapOf(
                        "success" to resumed,
                        "recording_active" to status["recording_active"],
                        "recording_paused" to status["recording_paused"],
                        "action_count" to status["action_count"],
                        "latest_action_summary" to status["latest_action_summary"],
                        "status" to status,
                        "error_code" to if (resumed) null else "NO_ACTIVE_RECORDING",
                        "error_message" to if (resumed) null else "No active human recording session",
                        "source" to "human_trajectory_learning"
                    ).filterValues { it != null }
                )
            }
        }
    }

    fun getHumanTrajectoryLearningStatus(call: MethodCall, result: MethodChannel.Result) {
        result.success(
            linkedMapOf(
                "success" to true,
                "status" to HumanTrajectoryLearningSession.status().asMap(),
                "source" to "human_trajectory_learning"
            )
        )
    }

    fun saveCurrentUdegState(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val goal = firstNonBlankString(
                args["goal"],
                args["description"],
                call.argument<String>("goal")
            )
            val payload = withContext(Dispatchers.Default) {
                runCatching {
                    captureCurrentUdegState(goal = goal)
                }.getOrElse { error ->
                    OmniLog.e(TAG, "save current UDEG state failed: ${error.fullCauseMessage()}", error)
                    udegStateCaptureErrorPayload(error)
                }
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    /**
     * Captures the current screen as a persisted UDEG get-state artifact during
     * manual recording and appends a non-replay RunLog evidence card.
     *
     * @param goal Current recording goal used to annotate the observed page.
     * @return Capture payload with state artifact paths, node id, page analysis,
     * decision context, node skill, and error fields when capture fails.
     */
    private suspend fun captureCurrentUdegStateForManualRecording(goal: String): Map<String, Any?> {
        val activeRunId = HumanTrajectoryLearningSession.activeRunId()
        val payload = runCatching {
            captureCurrentUdegState(goal = goal)
        }.getOrElse { error ->
            OmniLog.e(TAG, "manual recording UDEG state capture failed: ${error.fullCauseMessage()}", error)
            udegStateCaptureErrorPayload(error)
        }
        if (!activeRunId.isNullOrBlank()) {
            InternalRunLogStore.appendCard(
                context = context,
                runId = activeRunId,
                card = buildManualUdegStateCaptureCard(
                    runId = activeRunId,
                    payload = payload,
                )
            )
        }
        return payload
    }

    /**
     * Captures the live accessibility state and persists the UDEG node inputs.
     *
     * @param goal Optional task goal stored with the page observation.
     * @return A `oob.udeg.capture_result.v1` payload containing the captured
     * XML/screenshot manifest, UDEG node id, PageVector observation, page
     * analysis, decision context, node skill, and artifact paths.
     */
    private suspend fun captureCurrentUdegState(goal: String?): Map<String, Any?> {
        if (!awaitHumanTrajectoryRecordingBackend(timeoutMs = 8_000L)) {
            throw IllegalStateException("无障碍服务未就绪，无法保存当前页面状态")
        }
        val capturedAtMs = System.currentTimeMillis()
        val pageXml = AccessibilityController.getCaptureScreenShotXml(true)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("当前页面 XML 为空")
        val packageName = AccessibilityController.getPackageName().orEmpty()
        val activityName = AccessibilityController.getCurrentActivity().orEmpty()
        val screenshot = AccessibilityController.captureScreenshotImage(
            isBitmap = false,
            isBase64 = true,
            isFilterOverlay = true,
            compressQuality = ImageQuality.MEDIUM
        )
        val screenshotBase64 = screenshot.imageBase64
            ?.takeIf { screenshot.isSuccess && it.isNotBlank() }
        val store = OobUdegNodeStore(context)
        val observedPage = OobUdegNodeStore.ObservedPage(
            pageXml = pageXml,
            packageName = packageName,
            activityName = activityName,
            screenshotBase64 = screenshotBase64,
            goal = goal.orEmpty(),
            stepIndex = 0,
        )
        val observation = store.observePage(observedPage)
            ?: throw IllegalStateException("当前页面无法生成 UDEG PageVector")
        val stateArtifact = store.saveCapturedState(
            observedPage = observedPage,
            observation = observation,
            screenshotBytes = screenshotBase64?.let(::decodeDataUrlBase64),
            capturedAtMs = capturedAtMs,
        )
        return linkedMapOf<String, Any?>(
            "success" to true,
            "schema_version" to "oob.udeg.capture_result.v1",
            "kind" to "oob_udeg_state_capture",
            "captured_at_ms" to capturedAtMs,
            "node_id" to observation.node["node_id"],
            "page_similarity" to observation.pageSimilarity,
            "first_seen" to observation.firstSeen,
            "reason" to observation.reason,
            "package_name" to packageName,
            "activity_name" to activityName.takeIf { it.isNotBlank() },
            "xml_chars" to pageXml.length,
            "screenshot_present" to (screenshotBase64 != null),
            "screenshot_original_width" to screenshot.originalWidth,
            "screenshot_original_height" to screenshot.originalHeight,
            "screenshot_width" to screenshot.compressedWidth,
            "screenshot_height" to screenshot.compressedHeight,
            "state_artifact" to stateArtifact.toMap(),
            "page_observation" to observation.toMap(),
            "page_analysis" to OobUdegNodeStore.mapArg(observation.node["page_analysis"])
                .takeIf { it.isNotEmpty() },
            "decision_context" to OobUdegNodeStore.mapArg(observation.node["decision_context"])
                .takeIf { it.isNotEmpty() },
            "node_skill" to OobUdegNodeStore.mapArg(observation.node["skill"])
                .takeIf { it.isNotEmpty() },
            "skill_artifact" to OobUdegNodeStore.mapArg(observation.node["skill_artifact"])
                .takeIf { it.isNotEmpty() },
            "decision_path" to OobUdegNodeStore.UDEG_DECISION_PATH,
            "source" to "oob_udeg_manual_capture",
        ).filterValues { it != null }
    }

    /**
     * Builds the MethodChannel-compatible failure payload for UDEG state capture.
     *
     * @param error Failure raised while reading screen state or persisting assets.
     * @return Structured error payload that matches `oob.udeg.capture_result.v1`.
     */
    private fun udegStateCaptureErrorPayload(error: Throwable): Map<String, Any?> {
        return linkedMapOf(
            "success" to false,
            "schema_version" to "oob.udeg.capture_result.v1",
            "kind" to "oob_udeg_state_capture",
            "error_code" to "OOB_UDEG_STATE_CAPTURE_FAILED",
            "error_message" to error.fullCauseMessage(),
            "error_type" to error.javaClass.name,
            "error_cause_chain" to error.causeChainPayload(),
            "source" to "oob_udeg_manual_capture",
        )
    }

    /**
     * Creates a RunLog evidence card for manual get-state capture.
     *
     * @param runId Active manual recording run id.
     * @param payload Capture result produced by [captureCurrentUdegState].
     * @return A successful or failed `get_state` card that is persisted in the
     * RunLog but skipped by reusable-function conversion.
     */
    private fun buildManualUdegStateCaptureCard(
        runId: String,
        payload: Map<String, Any?>
    ): Map<String, Any?> {
        val capturedAtMs = (payload["captured_at_ms"] as? Number)?.toLong()
            ?: System.currentTimeMillis()
        val cardId = "$runId-udeg-state-$capturedAtMs"
        val stateArtifact = normalizeMethodCallMap(payload["state_artifact"])
        val success = payload["success"] == true
        val title = if (success) "人工截图" else "人工截图失败"
        val summary = if (success) {
            "已保存当前屏幕 get state / UDEG node 信息"
        } else {
            firstNonBlankString(payload["error_message"], "保存当前屏幕状态失败")
        }
        val artifactRefs = linkedMapOf<String, Any?>(
            "state_artifact" to stateArtifact.takeIf { it.isNotEmpty() },
            "manifest_path" to stateArtifact["manifest_path"],
            "xml_path" to stateArtifact["xml_path"],
            "screenshot_path" to stateArtifact["screenshot_path"],
        ).filterValues { it != null }
        return linkedMapOf<String, Any?>(
            "card_id" to cardId,
            "tool_call_id" to cardId,
            "header" to linkedMapOf<String, Any?>(
                "step_index" to 0,
                "title" to title,
                "tool_name" to "get_state",
                "status" to if (success) "success" else "error",
                "success" to success,
            ),
            "step_index" to 0,
            "title" to title,
            "summary" to summary,
            "tool_name" to "get_state",
            "toolName" to "get_state",
            "tool_type" to "manual_recording_evidence",
            "toolType" to "manual_recording_evidence",
            "status" to if (success) "success" else "error",
            "success" to success,
            "started_at_ms" to capturedAtMs,
            "finished_at_ms" to capturedAtMs,
            "package_name" to payload["package_name"],
            "activity_name" to payload["activity_name"],
            "compile_kind" to "manual_recording_evidence",
            "source" to "human_trajectory",
            "asset_refs" to artifactRefs.takeIf { it.isNotEmpty() },
            "tool_call" to linkedMapOf(
                "id" to cardId,
                "name" to "get_state",
                "arguments" to linkedMapOf(
                    "capture_kind" to "manual_udeg_state_capture",
                    "node_id" to payload["node_id"],
                    "state_artifact" to stateArtifact.takeIf { it.isNotEmpty() },
                    "decision_path" to payload["decision_path"],
                ).filterValues { it != null }
            ),
            "params" to linkedMapOf(
                "capture_kind" to "manual_udeg_state_capture",
                "node_id" to payload["node_id"],
                "state_artifact" to stateArtifact.takeIf { it.isNotEmpty() },
                "decision_path" to payload["decision_path"],
                "recording_backend" to "manual_recording_control"
            ).filterValues { it != null },
            "result" to payload,
            "_oob_meta" to linkedMapOf(
                "mode" to "manual_operation_recording",
                "recording_backend" to "manual_recording_control",
                "replayable" to false,
                "state_capture_kind" to "get_state_udeg_node",
            )
        ).filterValues { it != null }
    }

    fun exportOobUdeg(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val limit = ((args["limit"] ?: call.argument<Number>("limit")) as? Number)?.toInt()
                ?: 1_000
            val payload = runCatching {
                OobUdegNodeStore(context).exportBundle(limit = limit)
            }.getOrElse { error ->
                OmniLog.e(TAG, "export OOB UDEG failed: ${error.fullCauseMessage()}", error)
                linkedMapOf(
                    "success" to false,
                    "schema_version" to "oob.udeg.export.v1",
                    "kind" to "oob_udeg_export",
                    "error_code" to "OOB_UDEG_EXPORT_FAILED",
                    "error_message" to error.fullCauseMessage(),
                    "error_type" to error.javaClass.name,
                    "error_cause_chain" to error.causeChainPayload(),
                    "source" to "oob_udeg_node_store",
                )
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    private suspend fun awaitHumanTrajectoryRecordingBackend(
        timeoutMs: Long = 30_000L,
        intervalMs: Long = 150L
    ): Boolean {
        runCatching {
            if (!AssistsUtil.Core.isInitialized()) {
                AssistsUtil.Core.initCore(context)
            }
        }.onFailure {
            OmniLog.w(TAG, "manual recording core init failed: ${it.message}")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            if (AssistsService.instance != null && AccessibilityController.initController()) {
                return true
            }
            delay(intervalMs)
        }
        return false
    }

    private fun manualRecordedActionPayload(
        index: Int,
        action: ManualVlmRecordedAction
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "index" to index,
            "action" to action.actionName,
            "title" to action.title,
            "summary" to action.summary,
            "params" to action.params,
            "package_name" to action.packageName,
            "started_at_ms" to action.startedAtMs,
            "finished_at_ms" to action.finishedAtMs,
            "duration_ms" to (action.finishedAtMs - action.startedAtMs).coerceAtLeast(0L),
            "event_context" to action.eventContext.takeIf { it.isNotEmpty() },
            "before" to linkedMapOf(
                "observation_xml" to action.beforeXml,
                "package_name" to action.packageName
            ).filterValues { it != null },
            "after" to linkedMapOf(
                "observation_xml" to action.afterXml,
                "package_name" to action.packageName
            ).filterValues { it != null },
            "source_context" to linkedMapOf(
                "src_ctx" to linkedMapOf(
                    "page" to action.beforeXml,
                    "package_name" to action.packageName,
                    "require_unique_action_signature" to false
                ).filterValues { it != null },
                "dst_ctx" to linkedMapOf(
                    "page" to action.afterXml,
                    "package_name" to action.packageName
                ).filterValues { it != null },
                "action" to linkedMapOf<String, Any?>("tool" to action.actionName).apply {
                    putAll(action.params)
                },
                "_oob_meta" to linkedMapOf(
                    "mode" to "manual_operation_recording",
                    "recording_backend" to "accessibility_event",
                    "event_context" to action.eventContext.takeIf { it.isNotEmpty() },
                    "editable" to true
                ).filterValues { it != null }
            )
        ).filterValues { it != null }
    }

    private fun Throwable.fullCauseMessage(): String {
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

    private fun Throwable.causeChainPayload(): List<Map<String, String>> {
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

    private fun decodeDataUrlBase64(dataUrl: String): ByteArray? {
        val normalized = dataUrl.trim()
        if (normalized.isBlank()) return null
        val encoded = normalized.substringAfter(",", normalized).trim()
        return runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    fun getOobReusableFunction(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val functionId = (
                args["functionId"] ?: args["function_id"] ?: call.argument<String>("functionId")
            )?.toString()?.trim().orEmpty()
            val payload = OobFunctionRepository(context).get(functionId)
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun listOobReusableFunctions(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val limit = call.argument<Number>("limit")?.toInt() ?: 100
            val offset = call.argument<Number>("offset")?.toInt() ?: 0
            val autoRegister = call.argument<Boolean>("autoRegister") ?: true
            val payload = withContext(Dispatchers.IO) {
                if (autoRegister) {
                    runCatching { OobRunLogReplayService(context).autoRegisterRecentRunLogs(limit = 50) }
                        .onFailure { OmniLog.w(TAG, "list OOB functions auto-register failed: ${it.message}") }
                }
                OobFunctionRepository(context).list(limit = limit, offset = offset)
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun deleteOobReusableFunction(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val functionId = (
                args["functionId"] ?: args["function_id"] ?: call.argument<String>("functionId")
            )?.toString()?.trim().orEmpty()
            val payload = if (functionId.isEmpty()) {
                linkedMapOf(
                    "success" to false,
                    "function_id" to functionId,
                    "deleted" to false,
                    "error_code" to "OOB_FUNCTION_ID_EMPTY",
                    "error_message" to "functionId is empty"
                )
            } else {
                OobFunctionRepository(context).delete(functionId)
            }
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    fun runOobReusableFunction(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            val args = normalizeMethodCallMap(call.arguments)
            val functionId = (
                args["functionId"] ?: args["function_id"] ?: call.argument<String>("functionId")
            )?.toString()?.trim().orEmpty()
            if (functionId.isEmpty()) {
                withContext(Dispatchers.Main) {
                    result.success(
                        linkedMapOf(
                            "success" to false,
                            "goal" to "oob_reusable_function_run",
                            "function_id" to functionId,
                            "execution_status" to OOB_REUSABLE_EXECUTION_STATUS_FAILED,
                            "error_code" to "OOB_FUNCTION_ID_EMPTY",
                            "error_message" to "functionId is empty",
                            "terminal_state" to mapOf(
                                "status" to "error",
                                "execution_status" to OOB_REUSABLE_EXECUTION_STATUS_FAILED
                            )
                        )
                    )
                }
                return@launch
            }

            val workspaceFunctionStore = cn.com.omnimind.bot.workbench.WorkspaceFunctionStore(
                AgentWorkspaceManager.rootDirectory(context)
            )
            val functionRepository = OobFunctionRepository(context, workspaceFunctionStore)
            val spec = functionRepository.get(functionId)
            if (spec == null) {
                withContext(Dispatchers.Main) {
                    result.success(
                        linkedMapOf(
                            "success" to false,
                            "goal" to "oob_reusable_function_run:$functionId",
                            "function_id" to functionId,
                            "execution_status" to OOB_REUSABLE_EXECUTION_STATUS_FAILED,
                            "error_code" to "OOB_FUNCTION_NOT_FOUND",
                            "error_message" to "OOB reusable function not found: $functionId",
                            "terminal_state" to mapOf(
                                "status" to "error",
                                "execution_status" to OOB_REUSABLE_EXECUTION_STATUS_FAILED
                            )
                        )
                    )
                }
                return@launch
            }

            val callArguments = normalizeCallArgumentMap(args["arguments"])
            val missingRequired = OobReusableFunctionStore.missingRequiredArguments(
                functionSpec = spec,
                arguments = callArguments
            )
            if (missingRequired.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    result.success(
                        linkedMapOf(
                            "success" to false,
                            "goal" to "oob_reusable_function_run:$functionId",
                            "function_id" to functionId,
                            "execution_status" to OOB_REUSABLE_EXECUTION_STATUS_FAILED,
                            "error_code" to "OOB_FUNCTION_ARGUMENTS_MISSING",
                            "error_message" to "Missing required arguments: ${missingRequired.joinToString(", ")}",
                            "terminal_state" to mapOf(
                                "status" to "error",
                                "execution_status" to OOB_REUSABLE_EXECUTION_STATUS_FAILED,
                                "runner" to "oob_agent_reusable_function"
                            ),
                            "context" to mapOf(
                                "source" to "oob_reusable_function",
                                "function_id" to functionId,
                                "missing_required_arguments" to missingRequired
                            )
                        )
                    )
                }
                return@launch
            }
            val materializedSpec = OobReusableFunctionStore.materialize(spec, callArguments)
            val allowVlmFallback = booleanMethodCallValue(
                args["allowVlmFallback"] ?: args["allow_vlm_fallback"]
            )
            val providedLocalReplayResult = normalizeProvidedLocalReplayResult(
                args["localReplayResult"] ?: args["local_replay_result"]
            )
            val runner = OobFunctionToolHandler(
                context = context,
                helper = SharedHelper(context, chatTaskPayloadJson)
            ).apply {
                this.workspaceFunctionStore = workspaceFunctionStore
            }

            // Phase 1 for direct UI calls: execute the deterministic local prefix only.
            // Tool/data-flow/agent steps need the full Agent runtime, so the runner marks
            // the first such step as needs_agent instead of failing a synthetic tool call.
            val runPayload = providedLocalReplayResult ?: runCatching {
                    withContext(Dispatchers.Default) {
                        runner.runMaterializedFunction(
                            functionId = functionId,
                            spec = spec,
                            materializedSpec = materializedSpec,
                            allowAgentFallback = false,
                            allowToolDelegationWithoutRouter = false
                        )
                    }
                }.getOrElse { error ->
                    linkedMapOf(
                        "success" to false,
                        "function_id" to functionId,
                        "runner" to "oob_mixed_runner",
                        "step_count" to 0,
                        "success_step_count" to 0,
                        "model_used" to false,
                        "error_message" to error.message.orEmpty(),
                        "step_results" to emptyList<Map<String, Any?>>()
                    )
                }

            val stepResults = (runPayload["step_results"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>() ?: emptyList()
            val pendingAgentSteps = stepResults.filter(::isOobReusableFunctionPendingAgentStep)

            // Phase 2 default: direct UI replay executes the deterministic
            // local prefix, then hands remaining tool/agent steps to the full
            // Agent runtime so it can use router-backed tools.
            if (pendingAgentSteps.isNotEmpty() && !allowVlmFallback) {
                val completedCount = stepResults.indexOfFirst(::isOobReusableFunctionPendingAgentStep)
                val taskId = firstNonBlankString(args["taskId"], args["task_id"])
                    .takeIf { it.isNotEmpty() }
                    ?: "oob-agent-${System.currentTimeMillis()}-${UUID.randomUUID()}"
                val payload = executeOobReusableFunctionAgentFallback(
                    functionId = functionId,
                    functionSpec = materializedSpec,
                    arguments = callArguments,
                    runPayload = runPayload,
                    stepResults = stepResults,
                    completedStepCount = completedCount,
                    pendingAgentStepCount = pendingAgentSteps.size,
                    argumentCount = callArguments.size,
                    taskId = taskId,
                    requestedConversationId = positiveLongMethodCallValue(
                        args["conversationId"] ?: args["conversation_id"]
                    ),
                    conversationMode = normalizeConversationMode(
                        firstNonBlankString(args["conversationMode"], args["conversation_mode"])
                    ),
                )
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
                return@launch
            }

            // Backward-compatible escape hatch: callers that explicitly request
            // VLM fallback still get the old direct VLM continuation path.
            if (pendingAgentSteps.isNotEmpty() && allowVlmFallback) {
                val completedCount = stepResults.indexOfFirst(::isOobReusableFunctionPendingAgentStep)
                val taskId = firstNonBlankString(args["taskId"], args["task_id"])
                    .takeIf { it.isNotEmpty() }
                    ?: "${System.currentTimeMillis()}-vlm"
                val payload = executeOobReusableFunctionVlmFallback(
                    functionId = functionId,
                    functionSpec = materializedSpec,
                    arguments = callArguments,
                    runPayload = runPayload,
                    stepResults = stepResults,
                    completedStepCount = completedCount,
                    pendingAgentStepCount = pendingAgentSteps.size,
                    argumentCount = callArguments.size,
                    runId = taskId,
                )
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
                return@launch
            }

            // All steps executed locally, or local replay stopped with an
            // explicit failure that the UI may offer to continue with Agent.
            val localSuccess = runPayload["success"] != false
            OobReusableFunctionStore.recordRun(
                context = context,
                functionId = functionId,
                success = localSuccess,
                runId = runPayload["run_id"]?.toString(),
                runner = runPayload["runner"]?.toString() ?: "oob_mixed_runner",
                stepCount = (runPayload["step_count"] as? Number)?.toInt()
                    ?: stepResults.size,
                errorMessage = runPayload["error_message"]?.toString()
            )
            val payload = buildOobReusableFunctionLocalPayload(
                functionId = functionId,
                localSuccess = localSuccess,
                runPayload = runPayload,
                stepResults = stepResults,
                argumentCount = callArguments.size
            )
            withContext(Dispatchers.Main) {
                result.success(payload)
            }
        }
    }

    /**
     * 获取已安装应用（包名与应用名）
     */
    fun getInstalledApplications(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val pm = context.packageManager
                val applications = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString() }

                val list = applications.map { appInfo ->
                    mapOf(
                        "package_name" to appInfo.packageName,
                        "app_name" to pm.getApplicationLabel(appInfo).toString()
                    )
                }
                OmniLog.v(TAG, "getInstalledApplications size=${list.size}")

                withContext(Dispatchers.Main) {
                    result.success(list)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "获取已安装应用失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_INSTALLED_APPS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 获取已安装应用（包名与应用名，附带图标更新）
     */
    fun getInstalledApplicationsWithIconUpdate(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val pm = context.packageManager
                val applications = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString() }

                val list = applications.map { appInfo ->
                    val packageName = appInfo.packageName
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    var iconPath = ""
                    
                    // 查询数据库中是否已有该应用的图标
                    var appIcon = DatabaseHelper.getAppIconByPackageName(packageName)
                    
                    // 如果数据库中没有图标，则获取并保存
                    if (appIcon == null && appName.isNotEmpty()) {
                        val iconBase64 = APPPackageUtil.getAppIconBase64(context, packageName)
                        iconPath = APPPackageUtil.getAppIconFilePath(context, packageName)
                        
                        if (iconBase64.isNotEmpty()) {
                            DatabaseHelper.insertAppIcon(
                                appName = appName,
                                packageName = packageName,
                                iconBase64 = iconBase64,
                                iconPath = iconPath
                            )
                        }
                    }
                    
                    mapOf(
                        "package_name" to packageName,
                        "app_name" to appName,
                        "app_icon" to iconPath
                    )
                }
                OmniLog.v(TAG, "getInstalledApplications size=${list.size}")

                withContext(Dispatchers.Main) {
                    result.success(list)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "获取已安装应用失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_INSTALLED_APPS_ERROR", e.message, null)
                }
            }
        }
    }

    fun isPackageAuthorized(call: MethodCall, result: MethodChannel.Result) {
        val packageName = call.argument<String>("packageName") ?: ""
        mainJob.launch(Dispatchers.Main) {
            result.success(AssistsUtil.Core.isPackageAuthorized(packageName))
        }
    }

    fun scheduleVLMOperationTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {

        try {
            val needSummary = call.argument<Boolean>("needSummary") ?: false
            mainJob.launch {
                AssistsUtil.Core.scheduleVLMOperationTask(
                    context,
                    call.argument<String>("goal")!!,
                    call.argument<String>("model"),
                    call.argument<Int>("maxSteps"),
                    call.argument<String>("packageName"),
                    call.argument<Int>("times")!!.toLong(),
                    call.argument<String>("title")!!,
                    call.argument<String>("subTitle"),
                    call.argument<String>("extraJson"),
                    this@AssistsCoreManager,
                    needSummary
                )
                withContext(Dispatchers.Main){
                    result.success("SUCCESS")
                }
            }


        } catch (e: PermissionException) {
            mainJob.launch(Dispatchers.Main) {
                result.error("PERMISSION_ERROR", e.message, null);
            }
        }

    }

    fun getScheduleInfo(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        try {
            val status = AssistsUtil.Core.getScheduleStatus()
            val scheduleStatus = status.toString()
            val hasScheduleTask = status != null
            val canCreateScheduleTask =
                status == null || (status != ScheduledStates.SCHEDULED && status != ScheduledStates.RUNNING)
            val scheduleTaskParams = AssistsUtil.Core.getScheduleParams()
            val taskParamsJson = when (scheduleTaskParams?.taskParams) {
                is TaskParams.ScheduledVLMOperationTaskParams -> {
                    val scheduledParams =
                        scheduleTaskParams.taskParams as TaskParams.ScheduledVLMOperationTaskParams
                    val params = ScheduledVLMOperationTaskParamsData(
                        goal = scheduledParams.goal,
                        name = scheduledParams.name,
                        subTitle = scheduledParams.subTitle,
                        extraJson = scheduledParams.extraJson,
                        model = scheduledParams.model,
                        maxSteps = scheduledParams.maxSteps,
                        packageName = scheduledParams.packageName,
                        needSummary = scheduledParams.needSummary
                    )
                    Gson().toJson(params)
                }

                else -> {
                    ""
                }
            }
            val map = mapOf(
                "scheduleStatus" to scheduleStatus,
                "hasScheduleTask" to hasScheduleTask,
                "canCreateScheduleTask" to canCreateScheduleTask,
                "taskParamsJson" to taskParamsJson,
                "delayTimes" to scheduleTaskParams?.delayTimes,
                "startTimeStamp" to scheduleTaskParams?.startTimeStamp


            )
            mainJob.launch(Dispatchers.Main) {
                result.success(map)
            }
        } catch (e: Error) {
            mainJob.launch(Dispatchers.Main) {
                result.error("GET_SCHEDULEINFO_ERROR", e.message, null);
            }
        }
    }


    fun clearScheduleTask(call: MethodCall, result: MethodChannel.Result) {
        try {
            AssistsUtil.Core.clearScheduleTask()
            mainJob.launch(Dispatchers.Main) {
                result.success("SUCCESS")
            }
        } catch (e: Error) {
            mainJob.launch(Dispatchers.Main) {
                result.error("CLEAR_SCHEDULE_TASK_ERROR", e.message, null);
            }
        }
    }

    fun doScheduleNow(call: MethodCall, result: MethodChannel.Result) {
        try {
            AssistsUtil.Core.doScheduleNow()
            mainJob.launch(Dispatchers.Main) {
                result.success("SUCCESS")
            }
        } catch (e: Error) {
            mainJob.launch(Dispatchers.Main) {
                result.error("DO_SCHEDULE_NOW_ERROR", e.message, null);
            }
        }
    }

    fun cancelScheduleTask(call: MethodCall, result: MethodChannel.Result) {
        try {
            AssistsUtil.Core.cancelScheduleTask()
            mainJob.launch(Dispatchers.Main) {
                result.success("SUCCESS")
            }
        } catch (e: Error) {
            mainJob.launch(Dispatchers.Main) {
                result.error("CANCEL_SCHEDULE_TASK_ERROR", e.message, null);
            }
        }
    }

    /**
     * 查询统一 Agent 创建的 exact alarm 提醒列表
     */
    fun listAgentExactAlarms(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val alarms = AgentAlarmToolService(context).listExactReminders()
                withContext(Dispatchers.Main) {
                    result.success(alarms)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listAgentExactAlarms error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_AGENT_EXACT_ALARMS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 删除统一 Agent 创建的 exact alarm 提醒
     */
    fun deleteAgentExactAlarm(call: MethodCall, result: MethodChannel.Result) {
        val alarmId = call.argument<String>("alarmId")?.trim().orEmpty()
        workJob.launch {
            try {
                val alarmToolService = AgentAlarmToolService(context)
                val payload = if (alarmId.isEmpty()) {
                    alarmToolService.deleteAllExactReminders()
                } else {
                    alarmToolService.deleteExactReminder(alarmId)
                }
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: IllegalArgumentException) {
                OmniLog.e(TAG, "deleteAgentExactAlarm not found: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("AGENT_EXACT_ALARM_NOT_FOUND", e.message, null)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "deleteAgentExactAlarm error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("DELETE_AGENT_EXACT_ALARM_ERROR", e.message, null)
                }
            }
        }
    }

    fun getAlarmSettings(call: MethodCall, result: MethodChannel.Result) {
        try {
            val payload = AgentAlarmToolService(context).getAlarmSettings()
            result.success(payload)
        } catch (e: Exception) {
            OmniLog.e(TAG, "getAlarmSettings error: ${e.message}")
            result.error("GET_ALARM_SETTINGS_ERROR", e.message, null)
        }
    }

    fun saveAlarmSettings(call: MethodCall, result: MethodChannel.Result) {
        try {
            val source = call.argument<String>("source")?.trim().orEmpty()
            if (source.isEmpty()) {
                result.error("INVALID_ARGUMENTS", "source is empty", null)
                return
            }
            val localPath = call.argument<String>("localPath")
            val remoteUrl = call.argument<String>("remoteUrl")
            val payload = AgentAlarmToolService(context).saveAlarmSettings(
                source = source,
                localPath = localPath,
                remoteUrl = remoteUrl
            )
            result.success(payload)
        } catch (e: IllegalArgumentException) {
            OmniLog.e(TAG, "saveAlarmSettings invalid: ${e.message}")
            result.error("INVALID_ARGUMENTS", e.message, null)
        } catch (e: Exception) {
            OmniLog.e(TAG, "saveAlarmSettings error: ${e.message}")
            result.error("SAVE_ALARM_SETTINGS_ERROR", e.message, null)
        }
    }

    /**
     * 显示定时任务执行前提醒（支持取消/立即执行）
     */
    fun showScheduledTaskReminder(call: MethodCall, result: MethodChannel.Result) {
        val taskId = call.argument<String>("taskId")?.trim().orEmpty()
        val taskName = call.argument<String>("taskName")?.trim().orEmpty()
        val countdownSeconds = call.argument<Int>("countdownSeconds") ?: 5

        if (taskId.isEmpty()) {
            result.error("INVALID_ARGUMENTS", "taskId is empty", null)
            return
        }
        if (taskName.isEmpty()) {
            result.error("INVALID_ARGUMENTS", "taskName is empty", null)
            return
        }

        mainJob.launch(Dispatchers.Main) {
            try {
                val success = ScheduledTaskReminderLoader.show(
                    taskId = taskId,
                    taskName = taskName,
                    countdownSeconds = countdownSeconds,
                    onCancel = { id ->
                        notifyScheduledTaskEvent("onScheduledTaskCancelled", id)
                    },
                    onExecuteNow = { id ->
                        notifyScheduledTaskEvent("onScheduledTaskExecuteNow", id)
                    }
                )
                if (success) {
                    result.success("SUCCESS")
                } else {
                    result.error("SERVICE_NOT_READY", "Accessibility service not ready", null)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "showScheduledTaskReminder failed: ${e.message}")
                result.error("SHOW_SCHEDULED_TASK_REMINDER_ERROR", e.message, null)
            }
        }
    }

    /**
     * 隐藏定时任务提醒
     */
    fun hideScheduledTaskReminder(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch(Dispatchers.Main) {
            try {
                ScheduledTaskReminderLoader.hide()
                result.success("SUCCESS")
            } catch (e: Exception) {
                OmniLog.e(TAG, "hideScheduledTaskReminder failed: ${e.message}")
                result.error("HIDE_SCHEDULED_TASK_REMINDER_ERROR", e.message, null)
            }
        }
    }

    private fun notifyScheduledTaskEvent(method: String, taskId: String) {
        mainJob.launch(Dispatchers.Main) {
            val payload = mapOf("taskId" to taskId)
            try {
                channel.invokeMethod(method, payload)
            } catch (e: Exception) {
                OmniLog.e(TAG, "notifyScheduledTaskEvent via current channel failed: ${e.message}")
                try {
                    val mainChannel = mainEngineChannel
                    if (mainChannel != null && mainChannel != channel) {
                        mainChannel.invokeMethod(method, payload)
                    }
                } catch (fallbackError: Exception) {
                    OmniLog.e(TAG, "notifyScheduledTaskEvent fallback failed: ${fallbackError.message}")
                }
            }
        }
    }

    fun copyToClipboard(call: MethodCall, result: MethodChannel.Result) {
        try {
            val text = call.argument<String>("text") ?: ""
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("label", text)
            clipboard.setPrimaryClip(clip)
            mainJob.launch(Dispatchers.Main) {
                result.success("SUCCESS")
            }
        } catch (e: Exception) {
            mainJob.launch(Dispatchers.Main) {
                result.error("COPY_TO_CLIPBOARD_ERROR", e.message, null)
            }
        }
    }

    fun getClipboardText(call: MethodCall, result: MethodChannel.Result) {
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
            } else {
                ""
            }
            mainJob.launch(Dispatchers.Main) {
                result.success(text)
            }
        } catch (e: Exception) {
            mainJob.launch(Dispatchers.Main) {
                result.error("GET_CLIPBOARD_ERROR", e.message, null)
            }
        }
    }

    fun startFirstUse(call: MethodCall, result: MethodChannel.Result) {
        val listener = this;
        val packageName = call.argument<String>("packageName")
        if (packageName.isNullOrEmpty()) {
            result.error("PARAMS_ERROR", "packageName不能为空", null)
            return
        }
        mainJob.launch {
            try {
                AssistsUtil.Core.startFirstUse(
                    context,
                    listener,
                    packageName
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: PermissionException) {
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_ERROR", e.message, null);
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("DO_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 调用LLM chat接口（非流式）
     * 用于修复JSON格式等场景
     */
    fun postLLMChat(call: MethodCall, result: MethodChannel.Result) {
        val text = call.argument<String>("text") ?: ""
        val model = call.argument<String>("model") ?: "scene.dispatch.model"
        val responseJsonObject = call.argument<Boolean>("responseJsonObject") ?: false

        workJob.launch {
            try {
                val response = HttpController.postLLMRequest(
                    model = model,
                    text = text,
                    responseJsonObject = responseJsonObject
                )

                withContext(Dispatchers.Main) {
                    result.success(response.message)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "postLLMChat error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("POST_LLM_CHAT_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 生成记忆中心问候语（优先走标准 tool_calls，失败时回退纯文本）
     */
    fun generateMemoryGreeting(call: MethodCall, result: MethodChannel.Result) {
        val model = call.argument<String>("model")?.trim().orEmpty()
            .ifEmpty { "scene.compactor.context" }
        val records = (call.argument<List<Map<String, Any?>>>("records") ?: emptyList())
            .map { entry ->
                entry.mapKeys { it.key.toString() }
            }

        workJob.launch {
            try {
                val greeting = inferMemoryGreeting(model = model, records = records)
                withContext(Dispatchers.Main) {
                    result.success(greeting)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "generateMemoryGreeting error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GENERATE_MEMORY_GREETING_ERROR", e.message, null)
                }
            }
        }
    }

    fun getModelProviderConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val config = ModelProviderConfigStore.getConfig()
                withContext(Dispatchers.Main) {
                    result.success(config.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getModelProviderConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_MODEL_PROVIDER_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    private suspend fun inferMemoryGreeting(
        model: String,
        records: List<Map<String, Any?>>
    ): String {
        val recordBlock = buildMemoryGreetingRecordsBlock(records)
        val request = buildMemoryGreetingToolRequest(model, recordBlock)
        val toolResponse = runCatching { HttpController.postSceneChatCompletion(request) }
            .onFailure { OmniLog.w(TAG, "memory greeting tool-call failed: ${it.message}") }
            .getOrNull()

        if (toolResponse != null && toolResponse.success) {
            parseMemoryGreetingFromToolCalls(toolResponse.toolCalls)?.let { parsed ->
                val normalized = sanitizeMemoryGreeting(parsed)
                if (normalized.isNotEmpty()) {
                    return normalized
                }
            }
            val contentCandidate = sanitizeMemoryGreeting(toolResponse.content)
            if (contentCandidate.isNotEmpty()) {
                return contentCandidate
            }
        }

        val fallbackPrompt = buildMemoryGreetingLegacyPrompt(recordBlock)
        val legacyResponse = runCatching {
            HttpController.postLLMRequest(model, fallbackPrompt).message
        }.onFailure {
            OmniLog.w(TAG, "memory greeting legacy request failed: ${it.message}")
        }.getOrNull().orEmpty()

        return sanitizeMemoryGreeting(legacyResponse).ifEmpty { defaultMemoryGreeting() }
    }

    private fun buildMemoryGreetingRecordsBlock(records: List<Map<String, Any?>>): String {
        if (records.isEmpty()) {
            return t("（暂无可用记忆）", "(No memory available yet)")
        }
        return records.joinToString(separator = "\n") { record ->
            val title = record["title"]?.toString()?.trim().orEmpty().ifEmpty { t("无标题", "Untitled") }
            val description = record["description"]?.toString()?.trim().orEmpty().ifEmpty { t("无描述", "No description") }
            val appName = record["appName"]?.toString()?.trim().orEmpty().ifEmpty { t("未知来源", "Unknown source") }
            t(
                "标题: $title, 描述: $description, 来源应用: $appName",
                "Title: $title, Description: $description, Source App: $appName"
            )
        }
    }

    private fun buildMemoryGreetingToolRequest(
        model: String,
        recordBlock: String
    ): ChatCompletionRequest {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "greeting",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    t(
                                        "给用户的一句简短温暖问候语，不超过30字。",
                                        "A short, warm greeting for the user, within 30 words."
                                    )
                                )
                            )
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("greeting"))
                }
            )
        }
        return ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatCompletionMessage(
                    role = "system",
                    content = JsonPrimitive(
                        when (currentLocale()) {
                            PromptLocale.ZH_CN -> """
                                你是小万，一个温暖的AI助手。
                                请根据用户记忆生成一句简短、温馨、个性化的问候语。
                                要求：
                                1. 问候语不超过30个字。
                                2. 语气温暖友好。
                                3. 禁止使用“你好呀”开头。
                                4. 必须通过工具 $MEMORY_GREETING_TOOL 返回结果，不要输出普通文本。
                            """.trimIndent()
                            PromptLocale.EN_US -> """
                                You are Omnibot, a warm AI assistant.
                                Generate one short, warm, personalized greeting based on the user's memory.
                                Requirements:
                                1. Keep the greeting within 30 words.
                                2. Use a warm and friendly tone.
                                3. Do not begin with "Hi there".
                                4. You must return the result through the $MEMORY_GREETING_TOOL tool instead of plain text.
                            """.trimIndent()
                        }
                    )
                ),
                ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive(
                        t(
                            """
                            用户的记忆内容：
                            $recordBlock
                            """.trimIndent(),
                            """
                            User memory:
                            $recordBlock
                            """.trimIndent()
                        )
                    )
                )
            ),
            maxCompletionTokens = 128,
            temperature = 0.7,
            tools = listOf(
                ChatCompletionTool(
                    function = ChatCompletionFunction(
                        name = MEMORY_GREETING_TOOL,
                        description = t("提交记忆中心问候语。", "Submit the memory-center greeting."),
                        parameters = parameters
                    )
                )
            ),
            parallelToolCalls = false
        )
    }

    private fun buildMemoryGreetingLegacyPrompt(recordBlock: String): String {
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> """
                你是小万，一个温暖的AI助手。根据用户的记忆内容（包含本地记忆和长期记忆），生成一句简短、温馨的问候语。

                要求：
                1. 问候语要简短（不超过30个字）
                2. 结合用户记忆内容特点，体现个性化
                3. 语气温暖友好
                4. 不要使用"你好呀"开头
                5. 只输出问候语本身，不要加引号或其他说明

                用户的记忆内容：
                $recordBlock
            """.trimIndent()
            PromptLocale.EN_US -> """
                You are Omnibot, a warm AI assistant. Based on the user's memory content, including local memory and long-term memory, generate one short and warm greeting.

                Requirements:
                1. Keep the greeting short, within 30 words.
                2. Personalize it based on the user's memory.
                3. Keep the tone warm and friendly.
                4. Do not begin with "Hi there".
                5. Output only the greeting itself, without quotes or extra explanation.

                User memory:
                $recordBlock
            """.trimIndent()
        }
    }

    private fun parseMemoryGreetingFromToolCalls(toolCalls: List<AssistantToolCall>): String? {
        if (toolCalls.isEmpty()) {
            return null
        }
        val selected = toolCalls.firstOrNull {
            it.function.name.trim().equals(MEMORY_GREETING_TOOL, ignoreCase = true)
        } ?: toolCalls.first()
        val argsRaw = selected.function.arguments.trim()
        if (argsRaw.isEmpty()) {
            return null
        }
        val jsonText = extractFirstJsonObject(argsRaw) ?: argsRaw
        val payload = runCatching { JSONObject(jsonText) }
            .onFailure { OmniLog.w(TAG, "parse memory greeting tool args failed: ${it.message}") }
            .getOrNull() ?: return null
        return payload.optString("greeting").trim().ifEmpty {
            payload.optString("message").trim()
        }.ifEmpty {
            payload.optString("content").trim()
        }.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeMemoryGreeting(raw: String): String {
        var value = raw.trim()
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '"', '\'', '“', '”', '‘', '’')
        if (value.startsWith("你好呀")) {
            value = value.removePrefix("你好呀").trimStart('，', ',', '。', '！', '!', '～', '~', ' ')
        }
        if (value.startsWith("Hi there", ignoreCase = true)) {
            value = value.removePrefix("Hi there").trimStart(',', '.', '!', '~', ' ')
        }
        if (value.length > 30) {
            value = value.take(30)
        }
        return value.trim()
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fence.isNullOrBlank()) {
            return extractFirstJsonObject(fence)
        }
        val start = trimmed.indexOf('{')
        if (start < 0) {
            return null
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until trimmed.length) {
            val ch = trimmed[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return trimmed.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    fun listModelProviderProfiles(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val profiles = ModelProviderConfigStore.listProfiles()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "profiles" to profiles.map { it.toMap() },
                            "editingProfileId" to ModelProviderConfigStore.getEditingProfileId()
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listModelProviderProfiles error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_MODEL_PROVIDER_PROFILES_ERROR", e.message, null)
                }
            }
        }
    }

    fun listRecentAiRequestLogs(call: MethodCall, result: MethodChannel.Result) {
        val limit = call.argument<Int>("limit") ?: 10
        workJob.launch {
            try {
                val logs = AiRequestLogStore.listRecent(limit)
                withContext(Dispatchers.Main) {
                    result.success(logs.map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listRecentAiRequestLogs error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_RECENT_AI_REQUEST_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun listRuntimeLogs(call: MethodCall, result: MethodChannel.Result) {
        val limit = call.argument<Int>("limit") ?: 100
        workJob.launch {
            try {
                val logs = RuntimeLogStore.listRecent(limit)
                withContext(Dispatchers.Main) {
                    result.success(logs.map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listRuntimeLogs error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_RUNTIME_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearRuntimeLogs(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                RuntimeLogStore.clear()
                withContext(Dispatchers.Main) {
                    result.success(true)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearRuntimeLogs error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_RUNTIME_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveModelProviderProfile(call: MethodCall, result: MethodChannel.Result) {
        val profileId = call.argument<String>("id")?.trim()
        val name = call.argument<String>("name")?.trim().orEmpty()
        val baseUrl = call.argument<String>("baseUrl")?.trim().orEmpty()
        val apiKey = call.argument<String>("apiKey")?.trim().orEmpty()
        val protocolType = call.argument<String>("protocolType")?.trim() ?: "openai_compatible"

        workJob.launch {
            try {
                val saved = ModelProviderConfigStore.saveProfile(
                    id = profileId,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    protocolType = protocolType
                )
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(saved.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveModelProviderProfile error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MODEL_PROVIDER_PROFILE_ERROR", e.message, null)
                }
            }
        }
    }

    fun deleteModelProviderProfile(call: MethodCall, result: MethodChannel.Result) {
        val profileId = call.argument<String>("profileId")?.trim().orEmpty()

        workJob.launch {
            try {
                val profiles = ModelProviderConfigStore.deleteProfile(profileId)
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "profiles" to profiles.map { it.toMap() },
                            "editingProfileId" to ModelProviderConfigStore.getEditingProfileId()
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "deleteModelProviderProfile error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("DELETE_MODEL_PROVIDER_PROFILE_ERROR", e.message, null)
                }
            }
        }
    }

    fun setEditingModelProviderProfile(call: MethodCall, result: MethodChannel.Result) {
        val profileId = call.argument<String>("profileId")?.trim().orEmpty()

        workJob.launch {
            try {
                val selected = ModelProviderConfigStore.setEditingProfile(profileId)
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(selected.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "setEditingModelProviderProfile error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SET_EDITING_MODEL_PROVIDER_PROFILE_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveModelProviderConfig(call: MethodCall, result: MethodChannel.Result) {
        val baseUrl = call.argument<String>("baseUrl")?.trim() ?: ""
        val apiKey = call.argument<String>("apiKey")?.trim() ?: ""

        workJob.launch {
            try {
                ModelProviderConfigStore.saveConfig(baseUrl, apiKey)
                val saved = ModelProviderConfigStore.getConfig()
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(saved.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveModelProviderConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MODEL_PROVIDER_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearModelProviderConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                ModelProviderConfigStore.clearConfig()
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(ModelProviderConfigStore.getConfig().toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearModelProviderConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_MODEL_PROVIDER_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun fetchProviderModels(call: MethodCall, result: MethodChannel.Result) {
        val baseUrlArg = call.argument<String>("apiBase")?.trim().orEmpty()
        val apiKeyArg = call.argument<String>("apiKey")?.trim().orEmpty()
        val profileId = call.argument<String>("profileId")?.trim()

        workJob.launch {
            try {
                val currentConfig = ModelProviderConfigStore.getConfig()
                val isBuiltinLocalRequest = isBuiltinLocalProviderRequest(
                    profileId = profileId,
                    apiBase = baseUrlArg.ifBlank { currentConfig.baseUrl },
                    fallbackConfigId = currentConfig.id
                )
                val models = if (isBuiltinLocalRequest) {
                    LocalModelFeature.listBuiltinProviderModels()
                        .mapNotNull { item ->
                            val modelId = item["id"]?.toString()?.trim().orEmpty()
                            if (modelId.isEmpty()) {
                                null
                            } else {
                                ProviderModelOption(
                                    id = modelId,
                                    displayName = item["name"]?.toString()?.trim().ifNullOrBlank { modelId },
                                    ownedBy = item["backend"]?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
                                        ?: item["category"]?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
                                )
                            }
                        }
                        .distinctBy { it.id }
                        .sortedBy { it.id.lowercase() }
                } else {
                    val apiBase = if (baseUrlArg.isNotEmpty()) baseUrlArg else currentConfig.baseUrl
                    val apiKey = if (baseUrlArg.isNotEmpty()) apiKeyArg else currentConfig.apiKey
                    val profile = profileId?.let(ModelProviderConfigStore::getProfile)
                        ?: ModelProviderConfigStore.getEditingProfile()
                    HttpController.fetchProviderModels(apiBase, apiKey, profile.protocolType)
                }
                withContext(Dispatchers.Main) {
                    result.success(models.map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "fetchProviderModels error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("FETCH_PROVIDER_MODELS_ERROR", e.message, null)
                }
            }
        }
    }

    fun checkProviderModelAvailability(call: MethodCall, result: MethodChannel.Result) {
        val model = call.argument<String>("model")?.trim() ?: ""
        val baseUrlArg = call.argument<String>("apiBase")?.trim().orEmpty()
        val apiKeyArg = call.argument<String>("apiKey")?.trim().orEmpty()
        val profileId = call.argument<String>("profileId")?.trim()

        workJob.launch {
            try {
                val currentConfig = ModelProviderConfigStore.getConfig()
                val isBuiltinLocalRequest = isBuiltinLocalProviderRequest(
                    profileId = profileId,
                    apiBase = baseUrlArg.ifBlank { currentConfig.baseUrl },
                    fallbackConfigId = currentConfig.id
                )
                val checkResult = if (isBuiltinLocalRequest) {
                    val installed = LocalModelFeature.listBuiltinProviderModels()
                    val exists = installed.any { item ->
                        item["id"]?.toString()?.trim() == model
                    }
                    HttpController.ModelAvailabilityCheckResult(
                        available = exists,
                        code = if (exists) 200 else 404,
                        message = if (exists) "OK" else "本地模型未安装"
                    )
                } else {
                    val apiBase = if (baseUrlArg.isNotEmpty()) baseUrlArg else currentConfig.baseUrl
                    val apiKey = if (baseUrlArg.isNotEmpty()) apiKeyArg else currentConfig.apiKey
                    HttpController.checkProviderModelAvailability(
                        model = model,
                        apiBase = apiBase,
                        apiKey = apiKey
                    )
                }

                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "available" to checkResult.available,
                            "code" to checkResult.code,
                            "message" to checkResult.message
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "checkProviderModelAvailability error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "available" to false,
                            "code" to null,
                            "message" to (e.message ?: "检测失败")
                        )
                    )
                }
            }
        }
    }

    private fun isBuiltinLocalProviderRequest(
        profileId: String?,
        apiBase: String?,
        fallbackConfigId: String?
    ): Boolean {
        if (!MnnLocalProviderStateStore.isEnabled()) {
            return false
        }
        if (
            MnnLocalProviderStateStore.isBuiltinProfileId(profileId) ||
            MnnLocalProviderStateStore.isBuiltinProfileId(fallbackConfigId)
        ) {
            return true
        }
        val builtinBase = ModelProviderConfigStore.normalizeBaseUrl(
            MnnLocalProviderStateStore.getProfile().baseUrl
        )
        val requestBase = ModelProviderConfigStore.normalizeBaseUrl(apiBase ?: "")
        return builtinBase != null && builtinBase == requestBase
    }

    private fun String?.ifNullOrBlank(fallback: () -> String): String {
        val normalized = this?.trim().orEmpty()
        return if (normalized.isEmpty()) fallback() else normalized
    }

    fun getSceneModelCatalog(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val catalog = SceneModelCatalogResolver.listCatalogItems()
                withContext(Dispatchers.Main) {
                    result.success(catalog.map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneModelCatalog error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_MODEL_CATALOG_ERROR", e.message, null)
                }
            }
        }
    }

    fun getSceneModelBindings(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                withContext(Dispatchers.Main) {
                    result.success(SceneModelBindingStore.getBindingEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneModelBindings error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_MODEL_BINDINGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveSceneModelBinding(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim().orEmpty()
        val providerProfileId = call.argument<String>("providerProfileId")?.trim().orEmpty()
        val modelId = call.argument<String>("modelId")?.trim().orEmpty()

        workJob.launch {
            try {
                SceneModelBindingStore.saveBinding(sceneId, providerProfileId, modelId)
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(SceneModelBindingStore.getBindingEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveSceneModelBinding error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_SCENE_MODEL_BINDING_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearSceneModelBinding(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim().orEmpty()

        workJob.launch {
            try {
                SceneModelBindingStore.clearBinding(sceneId)
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(SceneModelBindingStore.getBindingEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearSceneModelBinding error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_SCENE_MODEL_BINDING_ERROR", e.message, null)
                }
            }
        }
    }

    fun getSceneVoiceConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                withContext(Dispatchers.Main) {
                    result.success(SceneVoiceConfigStore.getConfig().toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneVoiceConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_VOICE_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveSceneVoiceConfig(call: MethodCall, result: MethodChannel.Result) {
        val autoPlay = call.argument<Boolean>("autoPlay") == true
        val voiceId = call.argument<String>("voiceId")?.trim().orEmpty()
        val stylePreset = call.argument<String>("stylePreset")?.trim().orEmpty()
        val customStyle = call.argument<String>("customStyle")?.trim().orEmpty()

        workJob.launch {
            try {
                val saved = SceneVoiceConfigStore.saveConfig(
                    SceneVoiceConfig(
                        autoPlay = autoPlay,
                        voiceId = voiceId,
                        stylePreset = stylePreset,
                        customStyle = customStyle
                    )
                )
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(saved.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveSceneVoiceConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_SCENE_VOICE_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun getSceneModelOverrides(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                withContext(Dispatchers.Main) {
                    result.success(SceneModelOverrideStore.getOverrideEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneModelOverrides error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_MODEL_OVERRIDES_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveSceneModelOverride(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim() ?: ""
        val model = call.argument<String>("model")?.trim() ?: ""

        workJob.launch {
            try {
                SceneModelOverrideStore.saveOverride(sceneId, model)
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(SceneModelOverrideStore.getOverrideEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveSceneModelOverride error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_SCENE_MODEL_OVERRIDE_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearSceneModelOverride(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim() ?: ""

        workJob.launch {
            try {
                SceneModelOverrideStore.clearOverride(sceneId)
                syncAgentAiCapabilityConfigFile()
                withContext(Dispatchers.Main) {
                    result.success(SceneModelOverrideStore.getOverrideEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearSceneModelOverride error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_SCENE_MODEL_OVERRIDE_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 检测自定义 VLM 模型可用性（OpenAI-compatible）
     */
    fun checkVlmModelAvailability(call: MethodCall, result: MethodChannel.Result) {
        checkProviderModelAvailability(call, result)
    }

    fun getWorkspaceSoul(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val content = service.readSoul()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to content
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_WORKSPACE_SOUL_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceChatPrompt(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val content = service.readChatPrompt()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to content
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_WORKSPACE_CHAT_PROMPT_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveWorkspaceSoul(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                service.writeSoul(content)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to service.readSoul()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_WORKSPACE_SOUL_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveWorkspaceChatPrompt(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                service.writeChatPrompt(content)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to service.readChatPrompt()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_WORKSPACE_CHAT_PROMPT_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceLongMemory(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val content = service.readLongTermMemory()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to content
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_WORKSPACE_MEMORY_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceShortMemories(call: MethodCall, result: MethodChannel.Result) {
        val days = (call.argument<Int>("days") ?: 14).coerceIn(1, 90)
        val limit = (call.argument<Int>("limit") ?: 240).coerceIn(1, 1000)
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val payload = service.listShortMemoryEntries(days = days, limit = limit)
                    .map { entry ->
                        mapOf(
                            "id" to entry.id,
                            "date" to entry.date,
                            "time" to entry.time,
                            "content" to entry.content,
                            "timestampMillis" to entry.timestampMillis,
                            "quickLogId" to entry.quickLogId
                        )
                    }
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "items" to payload
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_WORKSPACE_SHORT_MEMORY_ERROR", e.message, null)
                }
            }
        }
    }

    fun listQuickLogs(call: MethodCall, result: MethodChannel.Result) {
        val limit = (call.argument<Int>("limit") ?: 200).coerceIn(1, 500)
        workJob.launch {
            try {
                val service = QuickLogService(context)
                val items = service.listLogs(limit).map { it.toMap() }
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "items" to items,
                            "totalCount" to service.countLogs()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("LIST_QUICK_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun addQuickLog(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        val source = call.argument<String>("source") ?: QuickLogService.SOURCE_APP
        workJob.launch {
            try {
                val item = QuickLogService(context).addLog(
                    content = content,
                    source = source
                )
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "item" to item.toMap()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("ADD_QUICK_LOG_ERROR", e.message, null)
                }
            }
        }
    }

    fun updateQuickLog(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("id") ?: ""
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val item = QuickLogService(context).updateLog(id, content)
                withContext(Dispatchers.Main) {
                    if (item == null) {
                        result.error("UPDATE_QUICK_LOG_NOT_FOUND", "quick log not found", null)
                    } else {
                        result.success(
                            mapOf(
                                "item" to item.toMap()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_QUICK_LOG_ERROR", e.message, null)
                }
            }
        }
    }

    fun deleteQuickLog(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("id") ?: ""
        workJob.launch {
            try {
                val deleted = QuickLogService(context).deleteLog(id)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "deleted" to deleted
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("DELETE_QUICK_LOG_ERROR", e.message, null)
                }
            }
        }
    }

    private fun isWorkspaceRollupMetadataLine(item: String): Boolean {
        val lower = item.lowercase()
        return lower.startsWith("source:") ||
            lower.startsWith("inputlines:") ||
            (item.startsWith("已整理") && item.contains("条短期记忆")) ||
            (item.contains("沉淀") && item.contains("长期记忆"))
    }

    fun saveWorkspaceLongMemory(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                service.writeLongTermMemory(content)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to service.readLongTermMemory()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_WORKSPACE_MEMORY_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceMemoryEmbeddingConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val config = WorkspaceMemoryService(context).getEmbeddingConfigForUi()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to config.enabled,
                            "configured" to config.configured,
                            "sceneId" to config.sceneId,
                            "providerProfileId" to config.providerProfileId,
                            "providerProfileName" to config.providerProfileName,
                            "modelId" to config.modelId,
                            "apiBase" to config.apiBase,
                            "hasApiKey" to config.hasApiKey
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_MEMORY_EMBEDDING_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveWorkspaceMemoryEmbeddingConfig(call: MethodCall, result: MethodChannel.Result) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        val providerProfileId = call.argument<String>("providerProfileId")
        val modelId = call.argument<String>("modelId")
        workJob.launch {
            try {
                val config = WorkspaceMemoryService(context).saveEmbeddingConfigForUi(
                    enabled = enabled,
                    providerProfileId = providerProfileId,
                    modelId = modelId
                )
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to config.enabled,
                            "configured" to config.configured,
                            "sceneId" to config.sceneId,
                            "providerProfileId" to config.providerProfileId,
                            "providerProfileName" to config.providerProfileName,
                            "modelId" to config.modelId,
                            "apiBase" to config.apiBase,
                            "hasApiKey" to config.hasApiKey
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MEMORY_EMBEDDING_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceMemoryRollupStatus(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val status = service.getRollupStatusForUi()
                val scheduler = WorkspaceMemoryRollupScheduler(context)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to status.enabled,
                            "lastRunAtMillis" to status.lastRunAtMillis,
                            "lastRunSummary" to status.lastRunSummary,
                            "nextRunAtMillis" to scheduler.getNextRunAtMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_MEMORY_ROLLUP_STATUS_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveWorkspaceMemoryRollupEnabled(call: MethodCall, result: MethodChannel.Result) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        workJob.launch {
            try {
                val scheduler = WorkspaceMemoryRollupScheduler(context)
                val status = scheduler.setEnabled(enabled)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to status.enabled,
                            "lastRunAtMillis" to status.lastRunAtMillis,
                            "lastRunSummary" to status.lastRunSummary,
                            "nextRunAtMillis" to scheduler.getNextRunAtMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MEMORY_ROLLUP_STATUS_ERROR", e.message, null)
                }
            }
        }
    }

    fun runWorkspaceMemoryRollupNow(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val payload = WorkspaceMemoryService(context).rollupDay().toMutableMap()
                runCatching {
                    WorkspaceMemoryRollupScheduler(context).ensureScheduledIfEnabled()
                }.onFailure { throwable ->
                    OmniLog.w(
                        TAG,
                        "runWorkspaceMemoryRollupNow schedule failed: ${throwable.message}"
                    )
                    payload["scheduleWarning"] = throwable.message
                }
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("RUN_MEMORY_ROLLUP_ERROR", e.message, null)
                }
            }
        }
    }

    fun upsertWorkspaceScheduledTask(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val rawTask = toStringAnyMap(call.argument<Any?>("task"))
                val payload = WorkspaceScheduledTaskScheduler(context).upsertTask(rawTask)
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("UPSERT_WORKSPACE_SCHEDULED_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    fun deleteWorkspaceScheduledTask(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val taskId = call.argument<String>("taskId")?.trim().orEmpty()
                val deleted = WorkspaceScheduledTaskScheduler(context).deleteTask(taskId)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "taskId" to taskId,
                            "deleted" to deleted
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("DELETE_WORKSPACE_SCHEDULED_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    fun syncWorkspaceScheduledTasks(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val rawTasks = toListOfStringAnyMap(call.argument<Any?>("tasks"))
                val payload = WorkspaceScheduledTaskScheduler(context).syncTasks(rawTasks)
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SYNC_WORKSPACE_SCHEDULED_TASKS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 打开APP市场
     */
    fun openAPPMarket(call: MethodCall, result: MethodChannel.Result) {
        val packageName = call.argument<String>("packageName") ?: ""
        try {
            if (packageName.isNotEmpty()) {
                SchemeUtil.jumpToMarket(context, packageName)
                result.success("SUCCESS")
            } else {
                result.error("OPEN_APP_MARKET_ERROR", "packageName is empty", null)
            }

        } catch (e: Exception) {
            result.error("OPEN_APP_MARKET_ERROR", e.message, null)
        }
    }

    /**
     * 是否在桌面
     */
    fun isDesktop(call: MethodCall, result: MethodChannel.Result) {
        try {
            result.success(AssistsCore.isInDesktop())
        } catch (e: Exception) {
            result.error("IS_DESKTOP_ERROR", e.message, null)
        }
    }

    /**
     * 获取桌面包名
     */
    fun getDeskTopPackageName(call: MethodCall, result: MethodChannel.Result){
        try {
            result.success(Constant.LAUNCHER_PACKAGES.toList())
        } catch (e: Exception) {
            result.error("GET_DESK_TOP_PACKAGE_NAME_ERROR", e.message, null)
        }
    }

    /**
     * 获取当前应用包名
     * 用于从当前页面开始执行任务
     */
    fun getCurrentPackageName(call: MethodCall, result: MethodChannel.Result) {
        try {
            val packageName = AssistsCore.getCurrentPackageName()
            result.success(packageName)
        } catch (e: Exception) {
            result.error("GET_CURRENT_PACKAGE_NAME_ERROR", e.message, null)
        }
    }

    fun workbenchProjectCreate(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val args = normalizeMethodCallMap(call.arguments)
                val config = normalizeMethodCallMap(args["config"]).ifEmpty { args }
                workbenchProjectStore.createProject(config)
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_CREATE_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectGet(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                val includeSources = call.argument<Boolean>("includeSources") ?: true
                val includeRuntimeState =
                    call.argument<Boolean>("includeRuntimeState") ?: true
                val includeFrontendPayloads =
                    call.argument<Boolean>("includeFrontendPayloads") ?: true
                workbenchProjectStore.getProject(
                    projectId = projectId,
                    includeSources = includeSources,
                    includeRuntimeState = includeRuntimeState,
                    includeFrontendPayloads = includeFrontendPayloads
                )
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_GET_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectUpdate(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                workbenchProjectStore.updateProject(
                    args = normalizeMethodCallMap(call.arguments),
                    caller = "ui"
                )
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_UPDATE_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectList(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                workbenchProjectStore.listProjects()
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_LIST_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectOpen(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                val route = workbenchProjectStore.routeForProject(projectId)
                withContext(Dispatchers.Main) {
                    TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
                }
                mapOf("success" to true, "projectId" to projectId, "route" to route)
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_OPEN_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectActivate(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                workbenchProjectStore.activateProject(projectId)
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_ACTIVATE_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectActiveGet(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val includeSources = call.argument<Boolean>("includeSources") ?: false
                val compact = call.argument<Boolean>("compact") ?: false
                workbenchProjectStore.getActiveProject(
                    includeSources = includeSources,
                    includeManifest = !compact
                )
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_ACTIVE_GET_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectDeactivate(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                workbenchProjectStore.deactivateProject()
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_DEACTIVATE_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectDelete(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                workbenchProjectStore.deleteProject(projectId)
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_DELETE_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectExport(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                workbenchProjectStore.exportProject(projectId)
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_EXPORT_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectHotUpdate(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                val prompt = call.argument<String>("prompt")?.trim().orEmpty()
                val caller = call.argument<String>("caller")?.trim().orEmpty().ifBlank { "ui" }
                val frontendContext =
                    call.argument<Map<String, Any?>>("frontendContext") ?: emptyMap()
                workbenchProjectStore.hotUpdateProject(
                    projectId = projectId,
                    prompt = prompt,
                    caller = caller,
                    frontendContext = frontendContext
                )
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_HOT_UPDATE_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectIngestAndroid(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                val sourcePath = call.argument<String>("sourcePath")?.trim().orEmpty()
                val sourceKind = call.argument<String>("sourceKind")?.trim()
                val displayName = call.argument<String>("displayName")?.trim()
                val caller = call.argument<String>("caller")?.trim().orEmpty().ifBlank { "ui" }
                workbenchProjectStore.ingestAndroidAsset(
                    projectId = projectId,
                    sourcePath = sourcePath,
                    sourceKind = sourceKind,
                    displayName = displayName,
                    caller = caller
                )
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_INGEST_ANDROID_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectIngestOss(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim().orEmpty()
                val sourceUrl = call.argument<String>("sourceUrl")?.trim()
                val sourcePath = call.argument<String>("sourcePath")?.trim()
                val sourceKind = call.argument<String>("sourceKind")?.trim()
                val ref = call.argument<String>("ref")?.trim()
                val displayName = call.argument<String>("displayName")?.trim()
                val caller = call.argument<String>("caller")?.trim().orEmpty().ifBlank { "ui" }
                workbenchProjectStore.ingestOssSource(
                    projectId = projectId,
                    sourceUrl = sourceUrl,
                    sourcePath = sourcePath,
                    sourceKind = sourceKind,
                    ref = ref,
                    displayName = displayName,
                    caller = caller
                )
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_INGEST_OSS_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchProjectProgressGet(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val projectId = call.argument<String>("projectId")?.trim()
                val limit = call.argument<Int>("limit") ?: 50
                workbenchProjectStore.getProjectProgress(projectId, limit)
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_PROJECT_PROGRESS_GET_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchApiList(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                workbenchProjectStore.listApis(call.argument<String>("projectId"))
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_API_LIST_ERROR", error.message, null)
                }
            }
        }
    }

    fun workbenchApiCall(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            runCatching {
                val requestedProjectId = call.argument<String>("projectId")?.trim().orEmpty()
                val projectId = requestedProjectId.ifBlank {
                    val activeProject = workbenchProjectStore.getActiveProject()["project"] as? Map<*, *>
                    activeProject?.get("projectId")?.toString()?.trim().orEmpty()
                }
                val apiId = call.argument<String>("apiId")?.trim()
                    ?: call.argument<String>("toolId")?.trim().orEmpty()
                val inputs = normalizeMethodCallMap(call.argument<Any>("inputs"))
                val caller = call.argument<String>("caller")?.trim().orEmpty().ifBlank { "ui" }
                workbenchProjectStore.callApi(projectId, apiId, inputs, caller)
            }.onSuccess { payload ->
                withContext(Dispatchers.Main) { result.success(payload) }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    result.error("WORKBENCH_API_CALL_ERROR", error.message, null)
                }
            }
        }
    }

    /**
     * 跳转到主引擎路由
     */
    fun navigateToMainEngineRoute(call: MethodCall, result: MethodChannel.Result) {
        val route = call.argument<String>("route") ?: ""
        if (route.isNotEmpty()) {
            try {
                TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
                mainJob.launch(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "navigateToMainEngineRoute failed: ${e.message}")
                mainJob.launch(Dispatchers.Main) {
                    result.error("NAVIGATE_ERROR", e.message, null)
                }
            }
        } else {
            result.error("NAVIGATE_ERROR", "Route is empty", null)
        }
    }

    private fun normalizeMethodCallMap(value: Any?): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        return raw.entries.associate { (key, item) ->
            key.toString() to normalizeMethodCallValue(item)
        }
    }

    private fun normalizeMethodCallValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> normalizeMethodCallMap(value)
            is List<*> -> value.map(::normalizeMethodCallValue)
            else -> value
        }
    }

    private fun normalizeCallArgumentMap(value: Any?): Map<String, Any?> {
        return normalizeMethodCallMap(value)
    }

    private fun normalizeProvidedLocalReplayResult(value: Any?): Map<String, Any?>? {
        val raw = normalizeMethodCallMap(value)
        if (raw.isEmpty()) return null
        val contextPayload = normalizeMethodCallMap(raw["context"])
        val stepResults = normalizeStepResultList(
            contextPayload["step_results"] ?: raw["step_results"]
        )
        val runPayload = LinkedHashMap<String, Any?>().apply {
            putAll(raw)
            if (stepResults.isNotEmpty()) {
                put("step_results", stepResults)
            }
            putIfAbsent("function_id", raw["function_id"])
            putIfAbsent("runner", raw["runner"] ?: contextPayload["runner"] ?: "oob_mixed_runner")
            putIfAbsent("model_used", raw["model_used"] ?: contextPayload["model_used"] ?: false)
            putIfAbsent(
                "model_required",
                raw["model_required"] ?: contextPayload["model_required"]
            )
            putIfAbsent(
                "fallback_available",
                raw["fallback_available"] ?: contextPayload["fallback_available"]
            )
            putIfAbsent("timing", raw["timing"] ?: contextPayload["timing"])
            putIfAbsent(
                "step_count",
                raw["step_count"] ?: contextPayload["step_count"] ?: stepResults.size
            )
            putIfAbsent(
                "success_step_count",
                raw["success_step_count"] ?: contextPayload["success_step_count"]
                    ?: stepResults.count { it["success"] != false }
            )
            putIfAbsent(
                "error_message",
                raw["error_message"] ?: raw["errorMessage"] ?: contextPayload["error_message"]
            )
            putIfAbsent("error_code", raw["error_code"] ?: contextPayload["error_code"])
        }
        return runPayload
    }

    private fun normalizeStepResultList(value: Any?): List<Map<String, Any?>> {
        val rawList = value as? List<*> ?: return emptyList()
        return rawList.mapNotNull { item ->
            normalizeMethodCallMap(item).takeIf { it.isNotEmpty() }
        }
    }

    private fun booleanMethodCallValue(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.trim().lowercase().let { normalized ->
                normalized == "true" || normalized == "1" || normalized == "yes"
            }
            else -> false
        }
    }

    private fun positiveLongMethodCallValue(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }?.takeIf { it > 0L }
    }

    private suspend fun executeOobReusableFunctionAgentFallback(
        functionId: String,
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>,
        runPayload: Map<String, Any?>,
        stepResults: List<Map<*, *>>,
        completedStepCount: Int,
        pendingAgentStepCount: Int,
        argumentCount: Int,
        taskId: String,
        requestedConversationId: Long?,
        conversationMode: String,
    ): Map<String, Any?> {
        var conversationId = requestedConversationId
        return try {
            if (hasActiveAgentRuns()) {
                throw IllegalStateException("设备当前已有运行中的 Agent 任务，请稍后重试")
            }
            val titleBase = firstNonBlankString(
                functionSpec["name"],
                functionSpec["description"],
                functionId,
            )
            if (conversationId == null) {
                val conversation = withContext(Dispatchers.Default) {
                    conversationDomainService.createConversation(
                        title = "复用指令：$titleBase",
                        mode = conversationMode
                    )
                }
                conversationId = positiveLongMethodCallValue(conversation["id"])
                    ?: throw IllegalStateException("Conversation id is invalid")
            }
            val resolvedConversationId: Long = conversationId
            val accepted = AgentRunService(context).startConversationRun(
                conversationId = resolvedConversationId,
                request = linkedMapOf(
                    "taskId" to taskId,
                    "conversationMode" to conversationMode,
                    "title" to "复用指令：$titleBase",
                    "userMessage" to buildOobReusableFunctionRunPrompt(
                        functionId = functionId,
                        functionSpec = functionSpec,
                        arguments = arguments,
                        completedStepCount = completedStepCount
                    )
                )
            )
            val acceptedTaskId = firstNonBlankString(accepted["taskId"], taskId)
            OobReusableFunctionStore.recordRun(
                context = context,
                functionId = functionId,
                success = true,
                runId = acceptedTaskId,
                runner = "oob_agent_fallback",
                stepCount = stepResults.size,
                errorMessage = null
            )
            buildOobReusableFunctionAgentFallbackPayload(
                functionId = functionId,
                taskId = acceptedTaskId,
                conversationId = resolvedConversationId,
                started = true,
                startErrorCode = null,
                startErrorMessage = null,
                runPayload = runPayload + mapOf(
                    "model_required" to true,
                    "fallback_available" to true
                ),
                stepResults = stepResults,
                completedStepCount = completedStepCount,
                pendingAgentStepCount = pendingAgentStepCount,
                argumentCount = argumentCount
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val code = if (error.message?.contains("已有运行中的 Agent") == true) {
                "AGENT_RUN_ALREADY_ACTIVE"
            } else {
                "AGENT_FALLBACK_START_FAILED"
            }
            OobReusableFunctionStore.recordRun(
                context = context,
                functionId = functionId,
                success = false,
                runId = taskId,
                runner = "oob_agent_fallback",
                stepCount = stepResults.size,
                errorMessage = error.message
            )
            buildOobReusableFunctionAgentFallbackPayload(
                functionId = functionId,
                taskId = taskId,
                conversationId = conversationId,
                started = false,
                startErrorCode = code,
                startErrorMessage = error.message ?: "Agent fallback start failed",
                runPayload = runPayload + mapOf(
                    "model_required" to true,
                    "fallback_available" to true
                ),
                stepResults = stepResults,
                completedStepCount = completedStepCount,
                pendingAgentStepCount = pendingAgentStepCount,
                argumentCount = argumentCount
            )
        }
    }

    private suspend fun executeOobReusableFunctionVlmFallback(
        functionId: String,
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>,
        runPayload: Map<String, Any?>,
        stepResults: List<Map<*, *>>,
        completedStepCount: Int,
        pendingAgentStepCount: Int,
        argumentCount: Int,
        runId: String,
    ): Map<String, Any?> {
        val vlmTaskId = "oob-vlm-${UUID.randomUUID()}"
        val goal = buildOobReusableFunctionVlmFallbackGoal(
            functionId = functionId,
            functionSpec = functionSpec,
            arguments = arguments,
            completedStepCount = completedStepCount,
        )
        val targetPackage = if (completedStepCount <= 0) {
            oobReusableFunctionInitialPackage(functionSpec)
        } else {
            ""
        }
        AgentVlmUiSession.registerRun(
            runId = runId,
            onStopRequested = {
                VlmToolCoordinator.cancelTask(vlmTaskId, mainJob, "任务已结束")
                AssistsUtil.Core.cancelRunningTask(vlmTaskId)
            },
            onCompleteRequested = {
                // The overlay callback completes the active VLM task; this
                // session flag only prevents the UI from being destroyed twice.
            }
        )
        AgentVlmUiSession.beginTask(runId, vlmTaskId)

        var finishMessage = "任务已完成"
        return try {
            val outcome = try {
                withContext(Dispatchers.Default) {
                    VlmToolCoordinator.executeNewTask(
                        context = context,
                        request = VlmTaskRequest(
                            goal = goal,
                            maxSteps = 64,
                            waitTimeoutMs = 600_000L,
                            packageName = targetPackage.takeIf { it.isNotBlank() },
                            needSummary = false,
                            skipGoHome = targetPackage.isBlank(),
                            disableOmniFlowRecall = true,
                            allowOmniFlowFunctionAutoExecute = false,
                        ),
                        scope = mainJob,
                        taskIdOverride = vlmTaskId,
                        returnOnWaitingInput = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                VlmToolOutcome(
                    taskId = vlmTaskId,
                    goal = goal,
                    status = VlmToolOutcomeStatus.ERROR,
                    message = error.message ?: "VLM fallback failed",
                    needSummary = false,
                    errorMessage = error.message ?: "VLM fallback failed",
                )
            }
            val success = outcome.status == VlmToolOutcomeStatus.FINISHED
            finishMessage = when {
                success -> "任务已完成"
                outcome.status == VlmToolOutcomeStatus.CANCELLED -> "任务已结束"
                else -> "任务执行失败"
            }
            OobReusableFunctionStore.recordRun(
                context = context,
                functionId = functionId,
                success = success,
                runId = vlmTaskId,
                runner = "oob_direct_vlm_fallback",
                stepCount = stepResults.size,
                errorMessage = if (success) null else {
                    outcome.errorMessage ?: outcome.message
                }
            )
            buildOobReusableFunctionVlmFallbackPayload(
                functionId = functionId,
                runId = runId,
                vlmTaskId = vlmTaskId,
                outcome = outcome,
                success = success,
                runPayload = runPayload,
                stepResults = stepResults,
                completedStepCount = completedStepCount,
                pendingAgentStepCount = pendingAgentStepCount,
                argumentCount = argumentCount,
            )
        } finally {
            AgentVlmUiSession.endTask(vlmTaskId)
            finishAgentVlmUiSessionIfNeeded(runId, finishMessage)
        }
    }

    private fun buildOobReusableFunctionVlmFallbackGoal(
        functionId: String,
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>,
        completedStepCount: Int,
    ): String {
        val goal = firstNonBlankString(
            functionSpec["description"],
            functionSpec["name"],
            functionId,
        )
        val steps = oobReusableFunctionSteps(functionSpec)
        val remainingSteps = steps.drop(completedStepCount.coerceAtLeast(0)).take(12)
        val lines = mutableListOf<String>()
        lines += "请从当前手机屏幕完成这个复用指令：$goal"
        if (completedStepCount > 0) {
            lines += "前 $completedStepCount 步已经由本地回放完成，请不要从头重复，直接继续剩余流程。"
        }
        if (arguments.isNotEmpty()) {
            lines += "调用参数：${OobReusableFunctionStore.prettyJson(arguments)}"
        }
        if (remainingSteps.isNotEmpty()) {
            lines += "剩余步骤参考："
            remainingSteps.forEachIndexed { index, step ->
                lines += "${completedStepCount + index + 1}. ${oobReusableFunctionStepSummary(step)}"
            }
        }
        lines += "你需要实际点击、输入、滑动或打开应用，直到任务完成；不要只回复文字。"
        return lines.joinToString("\n").trim()
    }

    private fun oobReusableFunctionSteps(functionSpec: Map<String, Any?>): List<Map<*, *>> {
        val execution = functionSpec["execution"] as? Map<*, *> ?: return emptyList()
        return (execution["steps"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?: emptyList()
    }

    private fun oobReusableFunctionInitialPackage(functionSpec: Map<String, Any?>): String {
        return oobReusableFunctionSteps(functionSpec).asSequence()
            .mapNotNull { step ->
                val args = step["args"] as? Map<*, *> ?: return@mapNotNull null
                val action = firstNonBlankString(
                    step["omniflow_action"],
                    step["local_action"],
                    step["tool"],
                    step["callable_tool"],
                ).lowercase()
                if (action != "open_app") return@mapNotNull null
                firstNonBlankString(args["package_name"], args["packageName"])
            }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun oobReusableFunctionStepSummary(step: Map<*, *>): String {
        val title = firstNonBlankString(step["title"], step["name"])
        val agentPrompt = ((step["agent_call"] as? Map<*, *>)?.get("args") as? Map<*, *>)
            ?.let { firstNonBlankString(it["prompt"], it["goal"]) }
            .orEmpty()
        val tool = firstNonBlankString(
            step["omniflow_action"],
            step["local_action"],
            step["tool"],
            step["callable_tool"],
        )
        val args = step["args"] as? Map<*, *>
        val argsText = args?.entries
            ?.take(3)
            ?.joinToString(", ") { (key, value) -> "$key=$value" }
            .orEmpty()
        return listOf(title, agentPrompt, tool, argsText)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "继续执行剩余操作"
    }

    private fun buildOobReusableFunctionRunPrompt(
        functionId: String,
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>,
        completedStepCount: Int = 0,
    ): String {
        val goal = functionSpec["description"]?.toString()?.trim()
            ?: functionSpec["name"]?.toString()?.trim()
            ?: functionId
        val constraints = functionSpec["constraints"] as? Map<*, *>
        val packageName = constraints?.get("package_name")?.toString()?.trim().orEmpty()
        val steps = ((functionSpec["execution"] as? Map<*, *>)?.get("steps") as? List<*>)
            ?.filterIsInstance<Map<*, *>>() ?: emptyList()

        val lines = mutableListOf<String>()
        lines += "执行任务：$goal"
        if (packageName.isNotEmpty()) lines += "目标应用：$packageName"
        if (arguments.isNotEmpty()) {
            lines += "调用参数：${OobReusableFunctionStore.prettyJson(arguments)}"
        }
        if (completedStepCount > 0) {
            lines += "（前 $completedStepCount 步已由本地执行完毕，从第 ${completedStepCount + 1} 步继续）"
        }
        lines += ""
        lines += "你必须调用可用工具实际执行，不要只回复文字。需要屏幕操作时可以调用 vlm_task，让小万从当前手机屏幕完成剩余步骤。"
        lines += "如果调用 vlm_task，goal 应该概括剩余任务，不要逐字解释步骤。"
        lines += ""
        lines += "步骤（按顺序执行）："

        steps.forEachIndexed { i, rawStep ->
            val step = rawStep.entries.associate { (k, v) -> k.toString() to v }
            val num = i + 1
            val title = step["title"]?.toString()?.trim().orEmpty()
            val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
            val done = i < completedStepCount

            when {
                done -> lines += "$num. [已完成] $title"
                executor == "omniflow" -> {
                    val action = step["omniflow_action"]?.toString()
                        ?: step["tool"]?.toString() ?: "?"
                    val args = step["args"]
                    val argsLine = when {
                        args is Map<*, *> && args.isNotEmpty() ->
                            args.entries.take(4).joinToString(", ") { (k, v) -> "$k=$v" }
                        else -> ""
                    }
                    val coordNote = if (step["coordinate_hook"] != null) "（有录制坐标，优先 remap）" else ""
                    lines += "$num. [直接执行] $title"
                    lines += "  → $action${if (argsLine.isNotEmpty()) " $argsLine" else ""}$coordNote"
                }
                executor == "agent" -> {
                    val prompt = ((step["agent_call"] as? Map<*, *>)
                        ?.get("args") as? Map<*, *>)
                        ?.get("prompt")?.toString()?.trim() ?: title
                    lines += "$num. [重新规划] $title"
                    lines += "  → $prompt"
                }
                else -> {
                    val tool = step["callable_tool"]?.toString()
                        ?: step["tool"]?.toString() ?: "?"
                    lines += "$num. [工具调用] $title"
                    lines += "  → $tool"
                }
            }
        }

        lines += ""
        lines += "规则：[直接执行] / executor=omniflow/model_free 的步骤使用已记录参数本地重放，不重新规划；[工具调用] 使用 step.callable_tool；[重新规划] / executor=agent 的步骤以 step.agent_call 或 fallback prompt 从当前屏幕继续。"
        return lines.joinToString("\n")
    }

    private fun parseScheduledSubagentRunMeta(
        conversationMode: String,
        conversationId: Long?,
        call: MethodCall
    ): ScheduledSubagentRunMeta? {
        if (!conversationMode.equals(SUBAGENT_MODE, ignoreCase = true)) {
            return null
        }
        val normalizedConversationId = conversationId?.takeIf { it > 0 } ?: return null
        val scheduleTaskId = call.argument<String>("scheduledTaskId")?.trim().orEmpty()
        if (scheduleTaskId.isEmpty()) {
            return null
        }
        val title = call.argument<String>("scheduledTaskTitle")?.trim().orEmpty()
        val notificationEnabled = call.argument<Boolean>("scheduleNotificationEnabled") != false
        return ScheduledSubagentRunMeta(
            scheduleTaskId = scheduleTaskId,
            scheduleTaskTitle = title.ifBlank { t("SubAgent 定时任务", "SubAgent Scheduled Task") },
            notificationEnabled = notificationEnabled,
            conversationId = normalizedConversationId
        )
    }

    private fun enrichScheduledSubagentParent(
        arguments: Map<String, Any?>,
        parentConversationId: Long?,
        parentConversationMode: String
    ): Map<String, Any?> {
        val targetKind = arguments["targetKind"]?.toString()?.trim().orEmpty()
        if (!targetKind.equals(SUBAGENT_MODE, ignoreCase = true) || parentConversationId == null) {
            return arguments
        }
        if (
            arguments["parentConversationId"] != null ||
            arguments["subagentParentConversationId"] != null
        ) {
            return arguments
        }
        return LinkedHashMap(arguments).apply {
            put("parentConversationId", parentConversationId)
            put("parentConversationMode", parentConversationMode)
        }
    }

    private fun normalizeNotificationBody(text: String): String {
        val normalized = AgentTextSanitizer.sanitizeUtf16(text)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) {
            return t("任务已完成，点击查看详情。", "Task completed. Tap to view details.")
        }
        return if (normalized.length <= 120) {
            normalized
        } else {
            normalized.take(117) + "..."
        }
    }

    private fun notifyScheduledSubagentCompletion(
        meta: ScheduledSubagentRunMeta,
        message: String
    ) {
        if (!meta.notificationEnabled) return
        val notificationManagerCompat = NotificationManagerCompat.from(context)
        if (!notificationManagerCompat.areNotificationsEnabled()) {
            OmniLog.w(TAG, "skip scheduled subagent notification: app notifications disabled")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            OmniLog.w(TAG, "skip scheduled subagent notification: permission denied")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SCHEDULED_SUBAGENT_NOTIFICATION_CHANNEL,
                    t("SubAgent 定时任务", "SubAgent Scheduled Task"),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = t("SubAgent 定时任务执行完成通知", "Notifications for completed scheduled SubAgent runs")
                }
            )
        }
        val route = TaskCompletionNavigator.buildChatRoute(meta.conversationId, SUBAGENT_MODE)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra("route", route)
            putExtra("needClear", false)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            ("scheduled_subagent_" + meta.scheduleTaskId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val iconRes = context.applicationInfo.icon.takeIf { it != 0 } ?: R.mipmap.ic_launcher
        val notification = NotificationCompat.Builder(
            context,
            SCHEDULED_SUBAGENT_NOTIFICATION_CHANNEL
        )
            .setSmallIcon(iconRes)
            .setContentTitle(meta.scheduleTaskTitle.ifBlank { t("SubAgent 定时任务", "SubAgent Scheduled Task") })
            .setContentText(normalizeNotificationBody(message))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(normalizeNotificationBody(message))
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val notificationId =
            "${meta.scheduleTaskId}_${System.currentTimeMillis()}".hashCode()
        notificationManagerCompat.notify(notificationId, notification)
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    /**
     * 创建 Agent 任务
     */
    private fun parseTerminalEnvironmentMap(raw: Map<String, Any?>?): Map<String, String> {
        if (raw.isNullOrEmpty()) {
            return emptyMap()
        }
        val normalized = linkedMapOf<String, String>()
        raw.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (key.isEmpty() || !TERMINAL_ENV_KEY_PATTERN.matches(key)) {
                return@forEach
            }
            normalized.remove(key)
            normalized[key] = rawValue?.toString() ?: ""
        }
        return normalized
    }

    private fun resolveAgentModelOverride(raw: Map<String, Any?>?): AgentModelOverride? {
        if (raw.isNullOrEmpty()) {
            return null
        }
        val providerProfileId = raw["providerProfileId"]?.toString()?.trim().orEmpty()
        val modelId = raw["modelId"]?.toString()?.trim().orEmpty()
        val providerProfile = ModelProviderConfigStore.getProfile(providerProfileId)
        if (
            providerProfileId.isEmpty() ||
            modelId.isEmpty() ||
            providerProfile == null ||
            !providerProfile.isConfigured()
        ) {
            return null
        }
        return AgentModelOverride(
            providerProfileId = providerProfile.id,
            providerProfileName = providerProfile.name,
            modelId = modelId,
            apiBase = providerProfile.baseUrl,
            apiKey = providerProfile.apiKey,
            protocolType = providerProfile.protocolType.ifEmpty { "openai_compatible" }
        )
    }

    fun createAgentTask(call: MethodCall, result: MethodChannel.Result) {
        val taskId = (call.argument<String>("taskId") ?: "").trim()
        val userMessage = AgentTextSanitizer.sanitizeUtf16(
            (call.argument<String>("userMessage") ?: "").toString()
        )
        val legacyConversationHistory =
            call.argument<List<Map<String, Any?>>>("conversationHistory") ?: emptyList()
        val attachments = (call.argument<List<Map<String, Any?>>>("attachments") ?: emptyList())
            .map(::sanitizeInteropMap)
        val modelAttachments = AgentImageAttachmentSupport
            .prepareAttachments(attachments)
            .modelAttachments
        val userMessageCreatedAt = call.argument<Number>("userMessageCreatedAt")?.toLong()
        val conversationId = call.argument<Number>("conversationId")?.toLong()?.takeIf { it > 0L }
        val requestedConversationMode =
            call.argument<String>("conversationMode")?.trim()?.ifEmpty { null }
        val resolvedConversationMode = normalizeConversationMode(
            requestedConversationMode ?: currentConversationMode
        )
        val scheduledSubagentMeta = parseScheduledSubagentRunMeta(
            conversationMode = resolvedConversationMode,
            conversationId = conversationId,
            call = call
        )
        val modelOverride = resolveAgentModelOverride(
            call.argument<Map<String, Any?>>("modelOverride")
        )
        val reasoningEffort = normalizeReasoningEffort(
            call.argument<String>("reasoningEffort")
        )
        val terminalEnvironment = parseTerminalEnvironmentMap(
            call.argument<Map<String, Any?>>("terminalEnvironment")
        )
        val toolExposurePolicy = AgentToolExposurePolicy.fromRaw(
            profile = call.argument<String>("toolProfile")
                ?: call.argument<String>("tool_profile"),
            allowedTools = call.argument<List<Any?>>("allowedTools")
                ?: call.argument<List<Any?>>("allowed_tools"),
        )
        if (taskId.isBlank()) {
            result.error("INVALID_ARGUMENTS", "taskId is empty", null)
            return
        }
        if (legacyConversationHistory.isNotEmpty()) {
            OmniLog.d(
                TAG,
                "Ignoring legacy conversationHistory for createAgentTask taskId=$taskId size=${legacyConversationHistory.size}"
            )
        }
        runCatching {
            InternalRunLogStore.beginRun(
                context = context,
                runId = taskId,
                goal = userMessage,
                source = "agent",
                toolName = "agent",
                operationDescription = userMessage
            )
        }.onFailure {
            OmniLog.w(TAG, "begin internal agent run log failed: ${it.message}")
        }
        val agentRunJob = SupervisorJob()
        val agentRunScope = CoroutineScope(agentRunJob + Dispatchers.Default)
        val agentRunContext = ActiveAgentRunContext(
            taskId = taskId,
            job = agentRunJob,
            startedAtMillis = System.currentTimeMillis(),
            conversationId = conversationId,
            conversationMode = resolvedConversationMode,
            userMessage = userMessage
        )
        registerActiveAgentRun(taskId, agentRunContext)
        TaskRuntimeSettings.onTaskStarted(context)

        agentRunScope.launch {
            var historyRepository: AgentConversationHistoryRepository? = null
            var agentVlmUiFinishMessage = "任务已完成"
            try {
                // 1. 获取当前包名
                val currentPackageName = AssistsCore.getCurrentPackageName()
                val runtimeContextRepository = AgentRuntimeContextRepository(context)
                historyRepository = conversationHistoryRepository()
                val repository = historyRepository ?: return@launch

                val scheduleBridge = object : AgentScheduleToolBridge {
                    override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> {
                        val enrichedArguments = enrichScheduledSubagentParent(
                            arguments = arguments,
                            parentConversationId = conversationId,
                            parentConversationMode = resolvedConversationMode
                        )
                        return toStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleCreate", enrichedArguments)
                        )
                    }

                    override suspend fun listTasks(): List<Map<String, Any?>> {
                        return toListOfStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleList", emptyMap())
                        )
                    }

                    override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> {
                        val enrichedArguments = enrichScheduledSubagentParent(
                            arguments = arguments,
                            parentConversationId = conversationId,
                            parentConversationMode = resolvedConversationMode
                        )
                        return toStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleUpdate", enrichedArguments)
                        )
                    }

                    override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> {
                        return toStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleDelete", arguments)
                        )
                    }
                }

                // 2. 初始化 Executor
                val executor = OmniAgentExecutor(context, agentRunScope, scheduleBridge)

                // 3. 创建事件桥
                val bridge = AgentTaskEventBridge(
                    taskId = taskId,
                    conversationId = conversationId,
                    resolvedConversationMode = resolvedConversationMode,
                    scheduledSubagentMeta = scheduledSubagentMeta,
                    historyRepository = historyRepository,
                    repository = repository,
                    agentRunContext = agentRunContext,
                )

                conversationId?.let { normalizedConversationId ->
                    if (userMessage.isNotBlank() || attachments.isNotEmpty()) {
                        bridge.persistConversationMutation("upsert user message") {
                            repository.upsertUserMessage(
                                conversationId = normalizedConversationId,
                                conversationMode = resolvedConversationMode,
                                entryId = "$taskId-user",
                                text = userMessage,
                                attachments = attachments,
                                createdAt = userMessageCreatedAt ?: System.currentTimeMillis()
                            )
                        }
                    }
                }


                // 4. 执行任务
                executor.processUserMessage(
                    userMessage,
                    legacyConversationHistory,
                    runtimeContextRepository,
                    currentPackageName,
                    modelAttachments,
                    conversationId,
                    resolvedConversationMode,
                    modelOverride,
                    reasoningEffort,
                    terminalEnvironment,
                    bridge,
                    toolExposurePolicy = toolExposurePolicy,
                    runControl = agentRunContext
                )
            } catch (e: CancellationException) {
                val completedByUser = e.message == "agent_vlm_ui_completed"
                agentVlmUiFinishMessage = if (completedByUser) "任务已完成" else "任务已结束"
                OmniLog.i(TAG, "createAgentTask cancelled: ${e.message}")
                runCatching {
                    InternalRunLogStore.finishRun(
                        context = context,
                        runId = taskId,
                        success = completedByUser,
                        doneReason = if (completedByUser) "user_completed" else "cancelled",
                        errorMessage = if (completedByUser) null else e.message
                    )
                }.onFailure {
                    OmniLog.w(TAG, "finish internal agent cancelled run log failed: ${it.message}")
                }
            } catch (e: Exception) {
                agentVlmUiFinishMessage = "任务执行失败"
                OmniLog.e(TAG, "createAgentTask error: ${e.message}")
                runCatching {
                    InternalRunLogStore.finishRun(
                        context = context,
                        runId = taskId,
                        success = false,
                        doneReason = "error",
                        errorMessage = e.message
                    )
                }.onFailure {
                    OmniLog.w(TAG, "finish internal agent exception run log failed: ${it.message}")
                }
                val errorMessage = e.message?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    "Agent execution failed: $it"
                } ?: "Agent execution failed"
                runCatching {
                    val normalizedConversationId = conversationId ?: return@runCatching
                    val failureRepository =
                        historyRepository ?: conversationHistoryRepository().also {
                            historyRepository = it
                        }
                    val roundIndex = 1
                    val entryId = "$taskId-text"
                    failureRepository.upsertAssistantMessage(
                        conversationId = normalizedConversationId,
                        conversationMode = resolvedConversationMode,
                        entryId = entryId,
                        text = errorMessage,
                        isError = true,
                        streamMeta = linkedMapOf(
                            "seq" to 1L,
                            "roundIndex" to roundIndex,
                            "kind" to "error",
                            "parentTaskId" to taskId
                        ),
                        createdAt = System.currentTimeMillis()
                    )
                    val messages = failureRepository.listConversationMessages(
                        conversationId = normalizedConversationId,
                        conversationMode = resolvedConversationMode
                    )
                    RealtimeHub.publish(
                        "messages_replaced",
                        mapOf(
                            "conversationId" to normalizedConversationId,
                            "mode" to resolvedConversationMode,
                            "messages" to messages
                        )
                    )
                    FlutterChatSyncBridge.dispatchConversationMessagesChanged(
                        conversationId = normalizedConversationId,
                        mode = resolvedConversationMode,
                        reason = "messages_replaced"
                    )
                }.onFailure {
                    OmniLog.w(TAG, "persist agent startup failure failed: ${it.message}")
                }
                scheduledSubagentMeta?.let { meta ->
                    runCatching {
                        notifyScheduledSubagentCompletion(meta, errorMessage)
                    }.onFailure {
                        OmniLog.w(TAG, "notify scheduled subagent failure failed: ${it.message}")
                    }
                }
                runCatching {
                    val failureEntryId = "$taskId-text"
                    val failureRoundIndex = 1
                    val textPayload = sanitizeInteropMap(
                        AgentStreamEvent(
                            taskId = taskId,
                            seq = 1L,
                            kind = "text_snapshot",
                            createdAt = System.currentTimeMillis(),
                            entryId = failureEntryId,
                            roundIndex = failureRoundIndex,
                            isFinal = true,
                            text = errorMessage
                        ).toPayload(
                            conversationId = conversationId,
                            conversationMode = resolvedConversationMode
                        )
                    )
                    val errorPayload = sanitizeInteropMap(
                        AgentStreamEvent(
                            taskId = taskId,
                            seq = 2L,
                            kind = "error",
                            createdAt = System.currentTimeMillis(),
                            entryId = failureEntryId,
                            roundIndex = failureRoundIndex,
                            error = errorMessage,
                            extras = mapOf("persistAsError" to true)
                        ).toPayload(
                            conversationId = conversationId,
                            conversationMode = resolvedConversationMode
                        )
                    )
                    RealtimeHub.publish("agent_stream_event", textPayload)
                    RealtimeHub.publish("agent_stream_event", errorPayload)
                    withContext(Dispatchers.Main) {
                        invokeFlutterEventSafely("onAgentStreamEvent", textPayload)
                        invokeFlutterEventSafely("onAgentStreamEvent", errorPayload)
                    }
                }.onFailure {
                    OmniLog.w(TAG, "dispatch agent startup failure failed: ${it.message}")
                }
            } finally {
                finishAgentVlmUiSessionIfNeeded(taskId, agentVlmUiFinishMessage)
                TaskRuntimeSettings.onTaskFinished(context)
                clearActiveAgentJob(taskId, agentRunJob)
            }
        }
        result.success("SUCCESS")
    }

    fun agentSkillList(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val payload = skillIndexService.listSkillsForManagement().map(::skillEntryPayload)
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_LIST_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    private fun skillEntryPayload(entry: SkillIndexEntry): Map<String, Any?> {
        return mapOf(
            "id" to entry.id,
            "name" to entry.name,
            "description" to entry.description,
            "compatibility" to entry.compatibility,
            "metadata" to entry.metadata,
            "rootPath" to entry.rootPath,
            "shellRootPath" to entry.shellRootPath,
            "skillFilePath" to entry.skillFilePath,
            "shellSkillFilePath" to entry.shellSkillFilePath,
            "hasScripts" to entry.hasScripts,
            "hasReferences" to entry.hasReferences,
            "hasAssets" to entry.hasAssets,
            "hasEvals" to entry.hasEvals,
            "enabled" to entry.enabled,
            "source" to entry.source,
            "installed" to entry.installed
        )
    }

    fun agentSkillInstall(call: MethodCall, result: MethodChannel.Result) {
        val sourcePath = call.argument<String>("sourcePath")?.trim().orEmpty()
        if (sourcePath.isBlank()) {
            result.error("INVALID_ARGS", "sourcePath is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val entry = skillIndexService.installSkillFromDirectory(sourcePath)
                withContext(Dispatchers.Main) {
                    result.success(skillEntryPayload(entry))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_INSTALL_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillSetEnabled(call: MethodCall, result: MethodChannel.Result) {
        val skillId = call.argument<String>("skillId")?.trim().orEmpty()
        val enabled = call.argument<Boolean>("enabled") ?: true
        if (skillId.isBlank()) {
            result.error("INVALID_ARGS", "skillId is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val entry = skillIndexService.setSkillEnabled(skillId, enabled)
                withContext(Dispatchers.Main) {
                    result.success(skillEntryPayload(entry))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_SET_ENABLED_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillDelete(call: MethodCall, result: MethodChannel.Result) {
        val skillId = call.argument<String>("skillId")?.trim().orEmpty()
        if (skillId.isBlank()) {
            result.error("INVALID_ARGS", "skillId is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val deleted = skillIndexService.deleteSkill(skillId)
                withContext(Dispatchers.Main) {
                    result.success(mapOf("deleted" to deleted, "id" to skillId))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_DELETE_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillInstallBuiltin(call: MethodCall, result: MethodChannel.Result) {
        val skillId = call.argument<String>("skillId")?.trim().orEmpty()
        if (skillId.isBlank()) {
            result.error("INVALID_ARGS", "skillId is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val entry = skillIndexService.installBuiltinSkill(skillId)
                withContext(Dispatchers.Main) {
                    result.success(skillEntryPayload(entry))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_INSTALL_BUILTIN_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillSyncOfficialRepository(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val syncResult = skillIndexService.syncOfficialSkillsRepository()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "action" to syncResult.action,
                            "repositoryUrl" to syncResult.repositoryUrl,
                            "rootPath" to syncResult.rootPath,
                            "shellRootPath" to syncResult.shellRootPath,
                            "skillCount" to syncResult.skillCount,
                            "skills" to syncResult.skills.map(::skillEntryPayload),
                            "output" to syncResult.output
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_SYNC_OFFICIAL_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun getTokenUsageRecords(call: MethodCall, result: MethodChannel.Result) {
        val sinceMs = call.argument<Number>("since")?.toLong() ?: 0L
        workJob.launch {
            try {
                val records = DatabaseHelper.getTokenUsageRecordsSince(sinceMs)
                val jsonList = records.map { record ->
                    mapOf(
                        "id" to record.id,
                        "conversationId" to record.conversationId,
                        "isLocal" to record.isLocal,
                        "model" to record.model,
                        "promptTokens" to record.promptTokens,
                        "completionTokens" to record.completionTokens,
                        "reasoningTokens" to record.reasoningTokens,
                        "textTokens" to record.textTokens,
                        "cachedTokens" to record.cachedTokens,
                        "createdAt" to record.createdAt
                    )
                }
                withContext(Dispatchers.Main) {
                    result.success(jsonList)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to get token usage records: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_TOKEN_USAGE_RECORDS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 获取所有对话列表
     */
    fun getConversations(call: MethodCall, result: MethodChannel.Result) {
        OmniLog.d(TAG, "[getConversations] 开始获取对话列表...")
        workJob.launch {
            try {
                val jsonList = conversationDomainService.listConversationPayloads(
                    includeArchived = true
                )
                OmniLog.d(TAG, "[getConversations] 从数据库获取到 ${jsonList.size} 条对话记录")
                withContext(Dispatchers.Main) {
                    OmniLog.d(TAG, "[getConversations] 返回 Flutter: $jsonList")
                    result.success(jsonList)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "[getConversations] 获取对话列表失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATIONS_ERROR", e.message, null)
                }
            }
        }
    }

    fun getConversationMessages(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                val messages = conversationDomainService.listConversationMessages(
                    conversationId = conversationId,
                    conversationMode = mode
                )
                withContext(Dispatchers.Main) {
                    result.success(messages)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "获取对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATION_MESSAGES_ERROR", e.message, null)
                }
            }
        }
    }

    fun getConversationMessagesPaged(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        val limit = call.argument<Number>("limit")?.toInt() ?: 20
        val offset = call.argument<Number>("offset")?.toInt() ?: 0
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                val pagedResult = conversationDomainService.listConversationMessagesPaged(
                    conversationId = conversationId,
                    conversationMode = mode,
                    limit = limit,
                    offset = offset
                )
                withContext(Dispatchers.Main) {
                    result.success(pagedResult)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "分页获取对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATION_MESSAGES_PAGED_ERROR", e.message, null)
                }
            }
        }
    }

    fun replaceConversationMessages(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        val messages = call.argument<List<Map<String, Any?>>>("messages") ?: emptyList()
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                conversationDomainService.replaceConversationMessages(
                    conversationId = conversationId,
                    conversationMode = mode,
                    messages = messages
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "替换对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("REPLACE_CONVERSATION_MESSAGES_ERROR", e.message, null)
                }
            }
        }
    }

    fun upsertConversationUiCard(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        val entryId = call.argument<String>("entryId")?.trim().orEmpty()
        val cardData = call.argument<Map<String, Any?>>("cardData") ?: emptyMap()
        val createdAt = call.argument<Number>("createdAt")?.toLong()
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        if (entryId.isEmpty()) {
            result.error("INVALID_ARGUMENTS", "entryId is invalid", null)
            return
        }
        workJob.launch {
            try {
                conversationDomainService.upsertConversationUiCard(
                    conversationId = conversationId,
                    conversationMode = mode,
                    entryId = entryId,
                    cardData = cardData,
                    createdAt = createdAt ?: System.currentTimeMillis()
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "保存 UI 卡片失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPSERT_CONVERSATION_UI_CARD_ERROR", e.message, null)
                }
            }
        }
    }

    fun compactConversationContext(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        val modelOverride = resolveAgentModelOverride(
            call.argument<Map<String, Any?>>("modelOverride")
        )
        val reasoningEffort = normalizeReasoningEffort(
            call.argument<String>("reasoningEffort")
        )
        workJob.launch {
            try {
                val payload = conversationDomainService.compactConversationContext(
                    conversationId = conversationId,
                    conversationMode = mode,
                    modelOverride = modelOverride,
                    reasoningEffort = reasoningEffort
                )
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "手动压缩上下文失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("COMPACT_CONVERSATION_CONTEXT_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearConversationMessages(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                conversationDomainService.clearConversationMessages(
                    conversationId = conversationId,
                    conversationMode = mode
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "清理对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_CONVERSATION_MESSAGES_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 分页获取对话列表
     */
    fun getConversationsByPage(call: MethodCall, result: MethodChannel.Result) {
        val offset = call.argument<Int>("offset") ?: 0
        val limit = call.argument<Int>("limit") ?: 20

        workJob.launch {
            try {
                val all = conversationDomainService.listConversationPayloads(
                    includeArchived = true
                )
                val jsonList = if (offset >= all.size) {
                    emptyList()
                } else {
                    all.subList(offset.coerceAtLeast(0), (offset + limit).coerceAtMost(all.size))
                }
                withContext(Dispatchers.Main) {
                    result.success(jsonList)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "分页获取对话列表失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATIONS_BY_PAGE_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 创建新对话
     */
    fun createConversation(call: MethodCall, result: MethodChannel.Result) {
        val title = call.argument<String>("title") ?: "新对话"
        val mode = normalizeConversationMode(call.argument<String>("mode"))
        val summary = call.argument<String>("summary")
        val parentConversationId = call.argument<Number>("parentConversationId")
            ?.toLong()
            ?.takeIf { it > 0L }
        val parentConversationMode = call.argument<String>("parentConversationMode")
        val scheduledTaskId = call.argument<String>("scheduledTaskId")

        workJob.launch {
            try {
                val conversation = conversationDomainService.createConversation(
                    title = title,
                    mode = mode,
                    summary = summary,
                    parentConversationId = parentConversationId,
                    parentConversationMode = parentConversationMode,
                    scheduledTaskId = scheduledTaskId
                )
                withContext(Dispatchers.Main) {
                    result.success((conversation["id"] as? Number)?.toLong())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "创建对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CREATE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 更新对话
     */
    fun updateConversation(call: MethodCall, result: MethodChannel.Result) {
        val conversationMap = call.argument<Map<String, Any>>("conversation")

        workJob.launch {
            try {
                if (conversationMap != null) {
                    conversationDomainService.updateConversationFromPayload(
                        conversationMap.mapValues { it.value }
                    )
                    withContext(Dispatchers.Main) {
                        result.success("SUCCESS")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        result.error("INVALID_ARGUMENTS", "conversation is null", null)
                    }
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "更新对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 删除对话
     */
    fun deleteConversation(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()

        workJob.launch {
            try {
                conversationDomainService.deleteConversation(conversationId)
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "删除对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("DELETE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    fun updateConversationPromptTokenThreshold(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Number>("conversationId"))?.toLong()
        val promptTokenThreshold = (call.argument<Number>("promptTokenThreshold"))?.toInt()

        workJob.launch {
            try {
                if (conversationId == null || conversationId <= 0L || promptTokenThreshold == null) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "INVALID_ARGUMENTS",
                            "conversationId or promptTokenThreshold is invalid",
                            null
                        )
                    }
                    return@launch
                }
                conversationDomainService.updateConversationPromptTokenThreshold(
                    conversationId = conversationId,
                    promptTokenThreshold = promptTokenThreshold
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "更新对话压缩阈值失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_CONVERSATION_THRESHOLD_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 更新对话标题
     */
    fun updateConversationTitle(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()
        val newTitle = call.argument<String>("newTitle") ?: ""

        workJob.launch {
            try {
                conversationDomainService.updateConversationTitle(
                    conversationId = conversationId,
                    newTitle = newTitle
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "更新对话标题失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_CONVERSATION_TITLE_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 生成对话摘要
     * 使用云端 qwen-flash 模型生成 10 字左右的摘要
     */
    fun generateConversationSummary(call: MethodCall, result: MethodChannel.Result) {
        val conversationHistory = call.argument<String>("conversationHistory") ?: ""

        workJob.launch {
            try {
                // 构建提示词，要求生成10字左右的摘要
                val prompt = """
                    你是一个聊天总结助手，请根据以下用户发送的对话内容，生成一个简洁的摘要标题，要求：
                    1. 摘要标题长度控制在10个字左右
                    2. 摘要标题应该体现对话的主要内容
                    3. 不要包含特殊字符和表情符号
                    4. 不要包含任何的人称用词

                    对话内容：
                    $conversationHistory

                    请直接返回摘要标题，不要包含其他内容。
                """.trimIndent()

                // 调用 LLM 生成摘要
                val llmResult = HttpController.postLLMRequest("scene.compactor.context.chat", prompt)
                val summary = llmResult.message
                    .trim()
                    .take(10)
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Conversation summary is empty")

                withContext(Dispatchers.Main) {
                    result.success(summary)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "生成对话摘要失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GENERATE_SUMMARY_ERROR", e.message, null)
                }
            }
        }
    }

    private fun conversationToMap(conversation: Conversation): Map<String, Any?> {
        return conversationDomainService.conversationToPayload(conversation)
    }

    private fun Map<String, Any>.readLong(key: String): Long? {
        return (this[key] as? Number)?.toLong()
    }

    private fun Map<String, Any>.readInt(key: String): Int? {
        return (this[key] as? Number)?.toInt()
    }

    /**
     * 跳转回聊天页面
     */
    private fun navigateBackToChatIfNeeded() {
        if (TaskCompletionNavigator.isAutoBackToChatAfterTaskEnabled(context)) {
            TaskCompletionNavigator.navigateBackToChat(
                context,
                currentConversationId,
                currentConversationMode
            )
        } else {
            OmniLog.d(TAG, "任务完成后停留当前页面（已关闭自动返回聊天）")
        }
    }

    fun setAutoBackToChatAfterTaskEnabled(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        try {
            val success = TaskCompletionNavigator.setAutoBackToChatAfterTaskEnabled(
                context,
                enabled
            )

            if (success) {
                OmniLog.d(TAG, "自动返回聊天设置已同步到原生: $enabled")
                result.success("SUCCESS")
            } else {
                result.error("SAVE_AUTO_BACK_SETTING_FAILED", "保存自动返回聊天设置失败", null)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "保存自动返回聊天设置失败: ${e.message}")
            result.error("SAVE_AUTO_BACK_SETTING_FAILED", e.message, null)
        }
    }

    fun setPreventScreenSleepDuringTasksEnabled(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        try {
            val success = TaskRuntimeSettings.setPreventSleepEnabled(context, enabled)
            if (success) {
                result.success("SUCCESS")
            } else {
                result.error("SAVE_PREVENT_SLEEP_SETTING_FAILED", "Failed to save prevent sleep setting", null)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "save prevent sleep setting failed: ${e.message}")
            result.error("SAVE_PREVENT_SLEEP_SETTING_FAILED", e.message, null)
        }
    }

    fun setTaskCompletionNotificationEnabled(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        try {
            val success = TaskRuntimeSettings.setTaskCompletionNotificationEnabled(context, enabled)
            if (success) {
                result.success("SUCCESS")
            } else {
                result.error("SAVE_TASK_NOTIFICATION_SETTING_FAILED", "Failed to save task notification setting", null)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "save task notification setting failed: ${e.message}")
            result.error("SAVE_TASK_NOTIFICATION_SETTING_FAILED", e.message, null)
        }
    }

    fun showTaskCompletionNotification(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        try {
            val title = call.argument<String>("title") ?: "Task completed"
            val message = call.argument<String>("message") ?: "Tap to view details."
            val conversationId = when (val raw = call.argument<Any>("conversationId")) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            val conversationMode = call.argument<String>("conversationMode")
            TaskRuntimeSettings.notifyTaskFinished(
                context = context,
                title = title,
                message = message,
                conversationId = conversationId,
                conversationMode = conversationMode
            )
            result.success("SUCCESS")
        } catch (e: Exception) {
            OmniLog.e(TAG, "show task completion notification failed: ${e.message}")
            result.error("SHOW_TASK_NOTIFICATION_FAILED", e.message, null)
        }
    }

    fun setVisibleChatConversation(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val visible = call.argument<Boolean>("visible") ?: true
        val conversationId = when (val raw = call.argument<Any>("conversationId")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }?.takeIf { it > 0 }
        val mode = (call.argument<String>("mode") ?: "normal").trim().ifEmpty { "normal" }
        TaskRuntimeSettings.setVisibleConversation(context, conversationId, mode, visible)
        mainJob.launch(Dispatchers.Main) {
            result.success("SUCCESS")
        }
    }

    /**
     * 完成对话
     */
    fun completeConversation(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()

        workJob.launch {
            try {
                conversationDomainService.completeConversation(conversationId)
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "完成对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("COMPLETE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 设置当前活跃的对话ID
     */
    fun setCurrentConversationId(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()
        val mode = (call.argument<String>("mode") ?: "normal").trim().ifEmpty { "normal" }
        currentConversationId = if (conversationId > 0) conversationId else null
        currentConversationMode = mode
        mainJob.launch(Dispatchers.Main) {
            result.success("SUCCESS")
        }
    }

    /**
     * 授权完成后重新打开ChatBot半屏
     */
    fun reopenChatBotAfterAuth(result: MethodChannel.Result) {
        mainJob.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.Main) {
                    ScreenMaskLoader.loadLockScreenMask()
                }
                // delay(500)
                UIKit.uiChatEvent?.showChatBotHalfScreen("resume_after_auth")
                result.success("SUCCESS")
            } catch (e: Exception) {
                OmniLog.e(TAG, "reopenChatBotAfterAuth failed: ${e.message}")
                result.error("REOPEN_ERROR", e.message, null)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Unified Execution Kernel — named class for stack traces and testability
    // ---------------------------------------------------------------------------

    /**
     * Bridges the [OmniAgentExecutor] callback stream to the conversation
     * repository and Flutter event channel for a single agent task run.
     *
     * All mutable state that was previously scattered as local variables inside
     * the `agentRunScope.launch { }` block now lives here as named member vars,
     * making ownership explicit and enabling future unit testing.
     */
    private inner class AgentTaskEventBridge(
        val taskId: String,
        val conversationId: Long?,
        val resolvedConversationMode: String,
        val scheduledSubagentMeta: ScheduledSubagentRunMeta?,
        var historyRepository: AgentConversationHistoryRepository?,
        val repository: AgentConversationHistoryRepository,
        val agentRunContext: ActiveAgentRunContext,
    ) : AgentCallback {

        // -----------------------------------------------------------------------
        // Task-local state vars
        // -----------------------------------------------------------------------

        val activeToolArgs = mutableMapOf<String, ArrayDeque<String>>()
        val activeToolEntryIds = mutableMapOf<String, ArrayDeque<String>>()
        val activeToolStartTimes = mutableMapOf<String, Long>()
        val thinkingCardStartTimes = mutableMapOf<String, Long>()
        val entryCreatedAtTimes = mutableMapOf<String, Long>()
        val entryOrderSeqs = mutableMapOf<String, Long>()
        val scheduledAssistantBuffer = StringBuilder()
        var toolSequence = 0
        var eventSequence = 0L
        var entrySequence = 0L
        var activeThinkingEntryId: String? = null
        var activeAssistantEntryId: String? = null
        var thinkingSequence = 0
        var assistantRound = 0
        var latestThinkingContent = ""
        var latestAssistantVisibleText = ""
        var shouldStartNewThinkingSegment = false
        var shouldStartNewAssistantRound = false
        // toolCallId (LLM-assigned, e.g. "toolu_01xxx") → our stable entryId.
        // Populated by onToolCallPreview during streaming; consumed by onToolCallStart
        // so both phases write to the same Flutter card.
        val previewEntryIdByToolCallId = mutableMapOf<String, String>()
        val previewEntryIdsByToolName = mutableMapOf<String, ArrayDeque<String>>()

        // -----------------------------------------------------------------------
        // Helper methods
        // -----------------------------------------------------------------------

        fun pushToolValue(
            store: MutableMap<String, ArrayDeque<String>>,
            toolName: String,
            value: String
        ) {
            store.getOrPut(toolName) { ArrayDeque() }.addLast(value)
        }

        fun peekToolValue(
            store: MutableMap<String, ArrayDeque<String>>,
            toolName: String
        ): String {
            return store[toolName]?.lastOrNull().orEmpty()
        }

        fun popToolValue(
            store: MutableMap<String, ArrayDeque<String>>,
            toolName: String
        ): String {
            val queue = store[toolName] ?: return ""
            val value = if (queue.isEmpty()) "" else queue.removeLast()
            if (queue.isEmpty()) {
                store.remove(toolName)
            }
            return value
        }

        fun rememberPreviewToolEntry(
            toolName: String,
            toolCallId: String,
            entryId: String
        ) {
            previewEntryIdByToolCallId[toolCallId] = entryId
            previewEntryIdsByToolName.getOrPut(toolName) { ArrayDeque() }.addLast(entryId)
        }

        fun removePreviewToolEntry(entryId: String) {
            previewEntryIdByToolCallId.entries.removeIf { it.value == entryId }
            val emptyToolNames = mutableListOf<String>()
            previewEntryIdsByToolName.forEach { (name, queue) ->
                queue.remove(entryId)
                if (queue.isEmpty()) {
                    emptyToolNames.add(name)
                }
            }
            emptyToolNames.forEach(previewEntryIdsByToolName::remove)
        }

        fun takePreviewToolEntry(toolName: String, toolCallId: String): String? {
            previewEntryIdByToolCallId.remove(toolCallId)?.let { entryId ->
                removePreviewToolEntry(entryId)
                return entryId
            }
            val queue = previewEntryIdsByToolName[toolName] ?: return null
            val entryId = if (queue.isEmpty()) null else queue.removeFirst()
            if (queue.isEmpty()) {
                previewEntryIdsByToolName.remove(toolName)
            }
            entryId?.let { removePreviewToolEntry(it) }
            return entryId
        }

        suspend fun publishConversationMessagesSync() {
            val normalizedConversationId = conversationId ?: return
            val repo = historyRepository ?: return
            try {
                val messages = repo.listConversationMessages(
                    conversationId = normalizedConversationId,
                    conversationMode = resolvedConversationMode
                )
                RealtimeHub.publish(
                    "messages_replaced",
                    mapOf(
                        "conversationId" to normalizedConversationId,
                        "mode" to resolvedConversationMode,
                        "messages" to messages
                    )
                )
                FlutterChatSyncBridge.dispatchConversationMessagesChanged(
                    conversationId = normalizedConversationId,
                    mode = resolvedConversationMode,
                    reason = "messages_replaced"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OmniLog.w(
                    TAG,
                    "publish conversation messages failed: ${error.message}",
                    error
                )
            }
        }

        suspend fun persistConversationMutation(
            description: String,
            publish: Boolean = true,
            block: suspend () -> Unit
        ) {
            val persisted = try {
                block()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OmniLog.w(TAG, "$description failed: ${error.message}", error)
                false
            }
            if (persisted && publish) {
                publishConversationMessagesSync()
            }
        }

        fun resolveAssistantEntryId(round: Int): String {
            return if (round <= 1) {
                "$taskId-text"
            } else {
                "$taskId-text-$round"
            }
        }

        fun nextEventSeq(): Long {
            eventSequence += 1
            return eventSequence
        }

        fun resolveEntryOrderSeq(entryId: String): Long {
            return entryOrderSeqs.getOrPut(entryId) {
                entrySequence += 1
                entrySequence
            }
        }

        fun streamMeta(
            entryId: String,
            roundIndex: Int,
            kind: String,
            isFinal: Boolean = false
        ): Map<String, Any?> {
            return linkedMapOf(
                "schema_version" to "oob.agent_event.v1",
                "trace_id" to taskId,
                "run_id" to taskId,
                "span_id" to entryId,
                "parent_span_id" to taskId,
                "seq" to resolveEntryOrderSeq(entryId),
                "roundIndex" to roundIndex,
                "kind" to kind,
                "parentTaskId" to taskId,
                "runLogId" to taskId,
                "run_id" to taskId,
                "entryId" to entryId,
                "isFinal" to isFinal
            )
        }

        fun markAssistantRoundBoundary() {
            if (activeAssistantEntryId != null || assistantRound > 0) {
                shouldStartNewAssistantRound = true
                activeAssistantEntryId = null
                scheduledAssistantBuffer.setLength(0)
            }
        }

        fun ensureAssistantEntry(forceNewRound: Boolean = false): Pair<Int, String> {
            if (activeAssistantEntryId == null || shouldStartNewAssistantRound || forceNewRound) {
                assistantRound = (assistantRound + 1).coerceAtLeast(1)
                activeAssistantEntryId = resolveAssistantEntryId(assistantRound)
                shouldStartNewAssistantRound = false
                scheduledAssistantBuffer.setLength(0)
            }
            val entryId = activeAssistantEntryId!!
            entryCreatedAtTimes.putIfAbsent(entryId, System.currentTimeMillis())
            return assistantRound to entryId
        }

        fun currentToolRoundIndex(): Int {
            return maxOf(thinkingSequence, assistantRound, 1)
        }

        fun buildDeepThinkingCardData(
            thinkingContent: String,
            isLoading: Boolean,
            stage: Int,
            startTime: Long,
            endTime: Long?
        ): Map<String, Any?> {
            val sanitizedThinking = AgentTextSanitizer.sanitizeUtf16(thinkingContent)
            val originalLength = sanitizedThinking.length
            val persistedThinking = if (originalLength <= MAX_PERSISTED_THINKING_CHARS) {
                sanitizedThinking
            } else {
                val bodyLimit = (MAX_PERSISTED_THINKING_CHARS - THINKING_TRUNCATION_NOTICE.length)
                    .coerceAtLeast(0)
                AgentTextSanitizer.sanitizeUtf16(
                    THINKING_TRUNCATION_NOTICE + sanitizedThinking.takeLast(bodyLimit)
                )
            }
            val truncated = persistedThinking.length < originalLength
            return linkedMapOf(
                "type" to "deep_thinking",
                "isLoading" to isLoading,
                "thinkingContent" to persistedThinking,
                "thinkingContentTruncated" to truncated,
                "thinkingOriginalLength" to originalLength,
                "thinkingTruncateMode" to if (truncated) "head_omitted" else "none",
                "stage" to stage,
                "taskID" to taskId,
                "startTime" to startTime,
                "endTime" to endTime,
                "isCollapsible" to true
            )
        }

        suspend fun upsertThinkingCard(
            entryId: String,
            roundIndex: Int,
            thinkingContent: String,
            isLoading: Boolean,
            stage: Int,
            createdAt: Long = thinkingCardStartTimes[entryId] ?: System.currentTimeMillis(),
            streamKind: String = "thinking_snapshot",
            endTime: Long? = null,
            publish: Boolean = true
        ) {
            val normalizedConversationId = conversationId ?: return
            if (entryId.isBlank()) return
            val startTime = thinkingCardStartTimes.getOrPut(entryId) { createdAt }
            persistConversationMutation(
                description = "upsert thinking card",
                publish = publish
            ) {
                repository.upsertUiCard(
                    conversationId = normalizedConversationId,
                    conversationMode = resolvedConversationMode,
                    entryId = entryId,
                    cardData = buildDeepThinkingCardData(
                        thinkingContent = thinkingContent,
                        isLoading = isLoading,
                        stage = stage,
                        startTime = startTime,
                        endTime = endTime
                    ),
                    streamMeta = streamMeta(
                        entryId = entryId,
                        roundIndex = roundIndex,
                        kind = streamKind
                    ),
                    createdAt = startTime
                )
            }
        }

        suspend fun finalizeThinkingCardIfNeeded(publish: Boolean = true) {
            val entryId = activeThinkingEntryId ?: return
            if (latestThinkingContent.isBlank()) return
            upsertThinkingCard(
                entryId = entryId,
                roundIndex = thinkingSequence.coerceAtLeast(1),
                thinkingContent = latestThinkingContent,
                isLoading = false,
                stage = 4,
                streamKind = "thinking_snapshot",
                endTime = System.currentTimeMillis(),
                publish = publish
            )
        }

        suspend fun upsertAssistantSnapshot(
            entryId: String,
            roundIndex: Int,
            text: String,
            isError: Boolean,
            isFinal: Boolean = false,
            streamKind: String = "text_snapshot"
        ) {
            val normalizedConversationId = conversationId ?: return
            val normalizedText = sanitizeAgentVisibleText(text)
            if (normalizedText.isEmpty()) return
            val createdAt = entryCreatedAtTimes.getOrPut(entryId) {
                System.currentTimeMillis()
            }
            persistConversationMutation("upsert assistant snapshot") {
                repository.upsertAssistantMessage(
                    conversationId = normalizedConversationId,
                    conversationMode = resolvedConversationMode,
                    entryId = entryId,
                    text = normalizedText,
                    isError = isError,
                    streamMeta = streamMeta(
                        entryId = entryId,
                        roundIndex = roundIndex,
                        kind = streamKind,
                        isFinal = isFinal
                    ),
                    createdAt = createdAt
                )
            }
            if (isFinal) {
                val finishedAt = System.currentTimeMillis()
                runCatching {
                    InternalRunLogStore.upsertCard(
                        context = this@AssistsCoreManager.context,
                        runId = taskId,
                        cardId = entryId,
                        card = this@AssistsCoreManager.buildAssistantResponseRunLogCard(
                            entryId = entryId,
                            text = normalizedText,
                            isError = isError,
                            startedAtMillis = createdAt,
                            finishedAtMillis = finishedAt
                        )
                    )
                }.onFailure {
                    OmniLog.w(TAG, "upsert assistant response run log failed: ${it.message}")
                }
            }
        }

        suspend fun upsertClarifyMessage(
            entryId: String,
            roundIndex: Int,
            question: String
        ) {
            val normalizedConversationId = conversationId ?: return
            val normalizedQuestion = AgentTextSanitizer.sanitizeUtf16(question).trim()
            if (normalizedQuestion.isEmpty()) return
            val createdAt = entryCreatedAtTimes.getOrPut(entryId) {
                System.currentTimeMillis()
            }
            persistConversationMutation("upsert clarify message") {
                repository.upsertAssistantMessage(
                    conversationId = normalizedConversationId,
                    conversationMode = resolvedConversationMode,
                    entryId = entryId,
                    text = normalizedQuestion,
                    isError = false,
                    streamMeta = streamMeta(
                        entryId = entryId,
                        roundIndex = roundIndex,
                        kind = "clarify_required",
                        isFinal = true
                    ),
                    createdAt = createdAt
                )
            }
        }

        fun buildPermissionRequiredMessage(missing: List<String>): String {
            val names = missing.map(::localizedPermissionName).filter { it.isNotEmpty() }
            return if (names.isEmpty()) {
                this@AssistsCoreManager.t(
                    "执行任务前需要先开启权限",
                    "Enable the required permissions before running the task."
                )
            } else {
                this@AssistsCoreManager.t(
                    "执行任务前，请先开启：${names.joinToString("、")}",
                    "Enable these permissions before running the task: ${names.joinToString(", ")}"
                )
            }
        }

        suspend fun upsertPermissionState(
            textEntryId: String,
            roundIndex: Int,
            missing: List<String>
        ) {
            val normalizedConversationId = conversationId ?: return
            val names = missing.map(::localizedPermissionName).filter { it.isNotEmpty() }
            val message = buildPermissionRequiredMessage(missing)
            persistConversationMutation("upsert permission state") {
                repository.upsertAssistantMessage(
                    conversationId = normalizedConversationId,
                    conversationMode = resolvedConversationMode,
                    entryId = textEntryId,
                    text = AgentTextSanitizer.sanitizeUtf16(message),
                    isError = false,
                    streamMeta = streamMeta(
                        entryId = textEntryId,
                        roundIndex = roundIndex,
                        kind = "permission_required",
                        isFinal = true
                    ),
                    createdAt = entryCreatedAtTimes.getOrPut(textEntryId) {
                        System.currentTimeMillis()
                    }
                )
                val permissionIds = resolveRequiredPermissionIds(names)
                if (permissionIds.isNotEmpty()) {
                    repository.upsertUiCard(
                        conversationId = normalizedConversationId,
                        conversationMode = resolvedConversationMode,
                        entryId = "$taskId-permission",
                        cardData = buildPermissionCardData(permissionIds),
                        streamMeta = streamMeta(
                            entryId = "$taskId-permission",
                            roundIndex = roundIndex,
                            kind = "permission_required",
                            isFinal = true
                        ),
                        createdAt = entryCreatedAtTimes.getOrPut("$taskId-permission") {
                            System.currentTimeMillis()
                        }
                    )
                }
            }
        }

        suspend fun upsertToolEvent(
            entryId: String,
            roundIndex: Int,
            payload: Map<String, Any?>,
            streamKind: String,
            fallbackStatus: String,
            fallbackSummary: String
        ) {
            val normalizedConversationId = conversationId ?: return
            if (entryId.isBlank()) return
            persistConversationMutation("upsert tool event") {
                val sanitizedPayload = sanitizeInteropMap(
                    linkedMapOf<String, Any?>("taskId" to taskId).apply {
                        putAll(payload)
                        put(
                            "streamMeta",
                            streamMeta(
                                entryId = entryId,
                                roundIndex = roundIndex,
                                kind = streamKind
                            )
                        )
                    }
                )
                repository.upsertToolEvent(
                    conversationId = normalizedConversationId,
                    conversationMode = resolvedConversationMode,
                    entryId = entryId,
                    payload = sanitizedPayload,
                    fallbackStatus = fallbackStatus,
                    fallbackSummary = AgentTextSanitizer.sanitizeUtf16(fallbackSummary)
                )
            }
        }

        suspend fun sendStreamEvent(
            kind: String,
            entryId: String? = null,
            roundIndex: Int = 0,
            isFinal: Boolean = false,
            text: String? = null,
            thinking: String? = null,
            stage: Int? = null,
            prefillTokensPerSecond: Double? = null,
            decodeTokensPerSecond: Double? = null,
            success: Boolean? = null,
            outputKind: String? = null,
            hasUserVisibleOutput: Boolean? = null,
            latestPromptTokens: Int? = null,
            promptTokenThreshold: Int? = null,
            error: String? = null,
            question: String? = null,
            missingFields: List<String>? = null,
            missing: List<String>? = null,
            extras: Map<String, Any?> = emptyMap()
        ) {
            val basePayload = AgentStreamEvent(
                taskId = taskId,
                seq = nextEventSeq(),
                kind = kind,
                createdAt = System.currentTimeMillis(),
                entryId = entryId,
                roundIndex = roundIndex,
                isFinal = isFinal,
                text = text,
                thinking = thinking,
                stage = stage,
                prefillTokensPerSecond = prefillTokensPerSecond,
                decodeTokensPerSecond = decodeTokensPerSecond,
                success = success,
                outputKind = outputKind,
                hasUserVisibleOutput = hasUserVisibleOutput,
                latestPromptTokens = latestPromptTokens,
                promptTokenThreshold = promptTokenThreshold,
                error = error,
                question = question,
                missingFields = missingFields,
                missing = missing,
                extras = extras
            ).toPayload(
                conversationId = conversationId,
                conversationMode = resolvedConversationMode
            )
            val payload = sanitizeInteropMap(
                entryId?.takeIf { it.isNotBlank() }?.let { resolvedEntryId ->
                    basePayload + mapOf(
                        "streamMeta" to streamMeta(
                            entryId = resolvedEntryId,
                            roundIndex = roundIndex,
                            kind = kind,
                            isFinal = isFinal
                        )
                    )
                } ?: basePayload
            )
            RealtimeHub.publish("agent_stream_event", payload)
            // Route through the per-frame batcher: coalesces up to ~60 calls/sec
            // regardless of LLM token rate, eliminating IPC overhead spikes.
            this@AssistsCoreManager.agentStreamEventBatcher.enqueue(payload)
        }

        // -----------------------------------------------------------------------
        // AgentCallback overrides
        // -----------------------------------------------------------------------

        override suspend fun onToolCallPreview(
            toolName: String,
            argumentsJson: String,
            toolCallId: String?,
            toolCallIndex: Int
        ) {
            if (toolName.isBlank()) return
            val resolvedId = toolCallId?.takeIf { it.isNotBlank() } ?: return
            // Each toolCallId is unique within a turn — fire only once per ID.
            if (previewEntryIdByToolCallId.containsKey(resolvedId)) return

            // Allocate the entry ID now; onToolCallStart will reuse it via the map.
            val entryId = "$taskId-tool-${++toolSequence}"
            rememberPreviewToolEntry(toolName, resolvedId, entryId)

            val roundIndex = currentToolRoundIndex()
            val earlyPayload = this@AssistsCoreManager.buildToolStartPayload(toolName, "{}").toMutableMap().apply {
                put("cardId", entryId)
            }
            upsertToolEvent(
                entryId = entryId,
                roundIndex = roundIndex,
                payload = earlyPayload,
                streamKind = "tool_started",
                fallbackStatus = AgentConversationHistoryRepository.STATUS_RUNNING,
                fallbackSummary = this@AssistsCoreManager.t("正在准备工具调用...", "Preparing tool call...")
            )
            sendStreamEvent(
                kind = "tool_started",
                entryId = entryId,
                roundIndex = roundIndex,
                extras = earlyPayload
            )
        }

        override suspend fun onThinkingStart() {
            val startsNewSegment =
                activeThinkingEntryId == null || shouldStartNewThinkingSegment
            if (startsNewSegment) {
                finalizeThinkingCardIfNeeded(publish = false)
                thinkingSequence += 1
                activeThinkingEntryId = "$taskId-thinking-$thinkingSequence"
                latestThinkingContent = ""
                shouldStartNewThinkingSegment = false
            } else if (thinkingSequence <= 0) {
                thinkingSequence = 1
                activeThinkingEntryId = "$taskId-thinking-$thinkingSequence"
            }
            val entryId = activeThinkingEntryId
                ?: run { thinkingSequence += 1; "$taskId-thinking-$thinkingSequence".also { activeThinkingEntryId = it } }
            val startTime = System.currentTimeMillis()
            thinkingCardStartTimes.putIfAbsent(entryId, startTime)
            markAssistantRoundBoundary()
            if (startsNewSegment) {
                sendStreamEvent(
                    kind = "thinking_started",
                    entryId = entryId,
                    roundIndex = thinkingSequence,
                    thinking = "",
                    stage = 1
                )
            }
        }

        override suspend fun onThinkingUpdate(thinking: String) {
            val normalizedThinking = AgentTextSanitizer.sanitizeUtf16(thinking).trim()
            if (normalizedThinking.isBlank()) return
            if (activeThinkingEntryId == null || shouldStartNewThinkingSegment) {
                thinkingSequence += 1
                val generated = "$taskId-thinking-$thinkingSequence"
                activeThinkingEntryId = generated
                latestThinkingContent = ""
                shouldStartNewThinkingSegment = false
                thinkingCardStartTimes.putIfAbsent(
                    generated,
                    System.currentTimeMillis()
                )
            }
            if (shouldIgnoreRegressiveSnapshot(latestThinkingContent, normalizedThinking)) {
                OmniLog.d(
                    TAG,
                    "ignore stale thinking snapshot: incoming=${normalizedThinking.length}, current=${latestThinkingContent.length}"
                )
                return
            }
            val entryId = activeThinkingEntryId
            latestThinkingContent = normalizedThinking
            entryId?.let { thinkingEntryId ->
                upsertThinkingCard(
                    entryId = thinkingEntryId,
                    roundIndex = thinkingSequence.coerceAtLeast(1),
                    thinkingContent = normalizedThinking,
                    isLoading = true,
                    stage = 1,
                    streamKind = "thinking_snapshot",
                    publish = false
                )
            }
            sendStreamEvent(
                kind = "thinking_snapshot",
                entryId = entryId,
                roundIndex = thinkingSequence.coerceAtLeast(1),
                thinking = normalizedThinking,
                stage = 1
            )
        }

        override suspend fun onToolCallStart(
            toolName: String,
            toolCallId: String,
            arguments: JsonObject
        ) {
            val argsJson = arguments.toString()
            pushToolValue(activeToolArgs, toolName, argsJson)
            // Look up the entry ID allocated during onToolCallPreview using the
            // LLM-assigned toolCallId as the stable key. This guarantees the
            // confirmed event lands on the same Flutter card as the preview,
            // with a tool-name fallback for providers that rewrite streamed IDs.
            val entryId = takePreviewToolEntry(toolName, toolCallId)
                ?: "$taskId-tool-${++toolSequence}"
            val startedAtMillis = System.currentTimeMillis()
            activeToolStartTimes[entryId] = startedAtMillis
            val roundIndex = currentToolRoundIndex()
            pushToolValue(activeToolEntryIds, toolName, entryId)
            agentRunContext.bindActiveToolCardId(entryId)
            activeThinkingEntryId?.takeIf { latestThinkingContent.isNotBlank() }?.let { thinkingEntryId ->
                upsertThinkingCard(
                    entryId = thinkingEntryId,
                    roundIndex = thinkingSequence.coerceAtLeast(roundIndex),
                    thinkingContent = latestThinkingContent,
                    isLoading = true,
                    stage = 2,
                    streamKind = "thinking_snapshot",
                    publish = false
                )
            }
            shouldStartNewThinkingSegment = true
            markAssistantRoundBoundary()
            val payload = this@AssistsCoreManager.buildToolStartPayload(toolName, argsJson).toMutableMap().apply {
                put("cardId", entryId)
            }
            runCatching {
                InternalRunLogStore.upsertCard(
                    context = this@AssistsCoreManager.context,
                    runId = taskId,
                    cardId = entryId,
                    card = buildAgentToolRunLogCard(
                        entryId = entryId,
                        toolName = toolName,
                        argsJson = argsJson,
                        payload = payload,
                        status = AgentConversationHistoryRepository.STATUS_RUNNING,
                        startedAtMillis = startedAtMillis
                    )
                )
            }.onFailure {
                OmniLog.w(TAG, "upsert agent tool run log start failed: ${it.message}")
            }
            upsertToolEvent(
                entryId = entryId,
                roundIndex = roundIndex,
                payload = payload,
                streamKind = "tool_started",
                fallbackStatus = AgentConversationHistoryRepository.STATUS_RUNNING,
                fallbackSummary = payload["summary"]?.toString()?.ifBlank {
                    this@AssistsCoreManager.t("正在调用工具", "Calling tool")
                } ?: this@AssistsCoreManager.t("正在调用工具", "Calling tool")
            )
            sendStreamEvent(
                kind = "tool_started",
                entryId = entryId,
                roundIndex = roundIndex,
                extras = payload
            )
        }

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>
        ) {
            val entryId = peekToolValue(activeToolEntryIds, toolName)
            val roundIndex = currentToolRoundIndex()
            val payload = this@AssistsCoreManager.buildToolProgressPayload(
                toolName,
                progress,
                peekToolValue(activeToolArgs, toolName),
                extras
            ).toMutableMap().apply {
                if (entryId.isNotBlank()) {
                    put("cardId", entryId)
                }
            }
            if (entryId.isNotBlank()) {
                val startedAtMillis = activeToolStartTimes[entryId]
                    ?: System.currentTimeMillis()
                runCatching {
                    InternalRunLogStore.upsertCard(
                        context = this@AssistsCoreManager.context,
                        runId = taskId,
                        cardId = entryId,
                        card = buildAgentToolRunLogCard(
                            entryId = entryId,
                            toolName = toolName,
                            argsJson = peekToolValue(activeToolArgs, toolName),
                            payload = payload,
                            status = AgentConversationHistoryRepository.STATUS_RUNNING,
                            startedAtMillis = startedAtMillis
                        )
                    )
                }.onFailure {
                    OmniLog.w(TAG, "upsert agent tool run log progress failed: ${it.message}")
                }
            }
            upsertToolEvent(
                entryId = entryId,
                roundIndex = roundIndex,
                payload = payload,
                streamKind = "tool_progress",
                fallbackStatus = AgentConversationHistoryRepository.STATUS_RUNNING,
                fallbackSummary = payload["summary"]?.toString()?.ifBlank {
                    this@AssistsCoreManager.t("正在调用工具", "Calling tool")
                } ?: this@AssistsCoreManager.t("正在调用工具", "Calling tool")
            )
            sendStreamEvent(
                kind = "tool_progress",
                entryId = entryId.takeIf { it.isNotBlank() },
                roundIndex = roundIndex,
                extras = payload
            )
        }

        override suspend fun onToolCardEvent(
            kind: String,
            payload: Map<String, Any?>
        ) {
            val streamKind = when (kind.trim()) {
                "tool_started", "tool_progress", "tool_completed" -> kind.trim()
                else -> "tool_progress"
            }
            val entryId = listOf(
                payload["cardId"],
                payload["toolCallId"],
                payload["tool_call_id"],
                payload["callId"],
                payload["call_id"]
            ).firstNotNullOfOrNull { raw ->
                raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } ?: return
            val roundIndex = currentToolRoundIndex()
            val normalizedPayload = sanitizeInteropMap(
                linkedMapOf<String, Any?>().apply {
                    putAll(payload)
                    put("cardId", entryId)
                    put("toolCallId", entryId)
                    put("toolType", payload["toolType"] ?: "vlm")
                }
            )
            val status = normalizedPayload["status"]?.toString()?.trim()
                ?: if (streamKind == "tool_completed") {
                    if (normalizedPayload["success"] == false) {
                        AgentConversationHistoryRepository.STATUS_ERROR
                    } else {
                        AgentConversationHistoryRepository.STATUS_SUCCESS
                    }
                } else {
                    AgentConversationHistoryRepository.STATUS_RUNNING
                }
            val success = normalizedPayload["success"] != false
            fun payloadMillis(vararg keys: String): Long? {
                return keys.firstNotNullOfOrNull { key ->
                    when (val raw = normalizedPayload[key]) {
                        is Number -> raw.toLong()
                        is String -> raw.trim().toLongOrNull()
                        else -> null
                    }?.takeIf { it > 0L }
                }
            }
            val now = System.currentTimeMillis()
            val startedAtMillis = payloadMillis("startedAtMs", "started_at_ms")
                ?: activeToolStartTimes[entryId]
                ?: now
            val finishedAtMillis = if (streamKind == "tool_completed") {
                payloadMillis("finishedAtMs", "finished_at_ms") ?: now
            } else {
                null
            }
            if (streamKind == "tool_completed") {
                activeToolStartTimes.remove(entryId)
            } else {
                activeToolStartTimes.putIfAbsent(entryId, startedAtMillis)
            }
            val toolName = normalizedPayload["toolName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: normalizedPayload["tool_name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "tool"
            val argsJson = listOf(
                normalizedPayload["argsJson"],
                normalizedPayload["args_json"],
                normalizedPayload["args"]
            ).firstNotNullOfOrNull { raw ->
                raw?.toString()?.takeIf { it.isNotBlank() }
            }.orEmpty()
            runCatching {
                InternalRunLogStore.upsertCard(
                    context = this@AssistsCoreManager.context,
                    runId = taskId,
                    cardId = entryId,
                    card = buildAgentToolRunLogCard(
                        entryId = entryId,
                        toolName = toolName,
                        argsJson = argsJson,
                        payload = normalizedPayload,
                        status = status,
                        startedAtMillis = startedAtMillis,
                        finishedAtMillis = finishedAtMillis
                    )
                )
            }.onFailure {
                OmniLog.w(TAG, "upsert explicit tool card run log failed: ${it.message}")
            }
            upsertToolEvent(
                entryId = entryId,
                roundIndex = roundIndex,
                payload = normalizedPayload,
                streamKind = streamKind,
                fallbackStatus = status,
                fallbackSummary = normalizedPayload["summary"]?.toString()
                    ?: normalizedPayload["progress"]?.toString()
                    ?: this@AssistsCoreManager.t("正在执行手机操作", "Running device action")
            )
            sendStreamEvent(
                kind = streamKind,
                entryId = entryId,
                roundIndex = roundIndex,
                isFinal = streamKind == "tool_completed",
                success = if (streamKind == "tool_completed") success else null,
                extras = normalizedPayload
            )
        }

        override suspend fun onToolCallComplete(
            toolName: String,
            result: ToolExecutionResult
        ) {
            val argsJson = popToolValue(activeToolArgs, toolName)
            val entryId = popToolValue(activeToolEntryIds, toolName).ifBlank {
                "$taskId-tool-${++toolSequence}"
            }
            val roundIndex = currentToolRoundIndex()
            activeThinkingEntryId?.takeIf { latestThinkingContent.isNotBlank() }?.let { thinkingEntryId ->
                upsertThinkingCard(
                    entryId = thinkingEntryId,
                    roundIndex = thinkingSequence.coerceAtLeast(roundIndex),
                    thinkingContent = latestThinkingContent,
                    isLoading = true,
                    stage = 2,
                    streamKind = "thinking_snapshot",
                    publish = false
                )
            }
            markAssistantRoundBoundary()
            val payload = this@AssistsCoreManager.buildToolCompletePayload(toolName, result, argsJson)
                .toMutableMap().apply {
                    put("cardId", entryId)
                }
            val success = payload["success"] != false
            val finishedAtMillis = System.currentTimeMillis()
            val startedAtMillis = activeToolStartTimes.remove(entryId)
                ?: finishedAtMillis
            runCatching {
                InternalRunLogStore.upsertCard(
                    context = this@AssistsCoreManager.context,
                    runId = taskId,
                    cardId = entryId,
                    card = buildAgentToolRunLogCard(
                        entryId = entryId,
                        toolName = toolName,
                        argsJson = argsJson,
                        payload = payload,
                        status = payload["status"]?.toString()
                            ?: if (success) {
                                AgentConversationHistoryRepository.STATUS_SUCCESS
                            } else {
                                AgentConversationHistoryRepository.STATUS_ERROR
                            },
                        startedAtMillis = startedAtMillis,
                        finishedAtMillis = finishedAtMillis
                    )
                )
            }.onFailure {
                OmniLog.w(TAG, "upsert agent tool run log complete failed: ${it.message}")
            }
            upsertToolEvent(
                entryId = entryId,
                roundIndex = roundIndex,
                payload = payload,
                streamKind = "tool_completed",
                fallbackStatus = if (success) {
                    AgentConversationHistoryRepository.STATUS_SUCCESS
                } else {
                    AgentConversationHistoryRepository.STATUS_ERROR
                },
                fallbackSummary = payload["summary"]?.toString().orEmpty()
            )
            sendStreamEvent(
                kind = "tool_completed",
                entryId = entryId,
                roundIndex = roundIndex,
                extras = payload
            )
            if (payload["toolType"]?.toString() == "browser") {
                val snapshot = LiveAgentBrowserSessionManager.currentSnapshot()
                RealtimeHub.publish(
                    "browser_snapshot_updated",
                    mapOf("snapshot" to snapshot)
                )
                FlutterChatSyncBridge.dispatchBrowserSnapshotUpdated(snapshot)
            }
            if (toolName == "workbench_project_create" && success) {
                runCatching { injectWorkbenchProjectCard(entryId, roundIndex, result) }
                    .onFailure { OmniLog.w(TAG, "workbench project card failed: ${it.message}") }
            }
        }

        private suspend fun injectWorkbenchProjectCard(
            toolEntryId: String,
            roundIndex: Int,
            result: ToolExecutionResult
        ) {
            val previewJson = (result as? ToolExecutionResult.ContextResult)?.previewJson ?: return
            val root = runCatching { JSONObject(previewJson) }.getOrNull() ?: return
            val project = root.optJSONObject("project") ?: return
            val projectId = project.optString("projectId").trim().takeIf { it.isNotEmpty() } ?: return
            val name = project.optString("name").trim().ifEmpty { projectId }
            val route = project.optString("route").trim()
            val pageSpec = project.optJSONObject("pageSpec")
            val description = pageSpec?.optString("description")?.trim().orEmpty()
            val displaysArray = project.optJSONArray("displays")
            var displayRoute = ""
            var displayId = ""
            if (displaysArray != null) {
                for (i in 0 until displaysArray.length()) {
                    val display = displaysArray.optJSONObject(i) ?: continue
                    val candidateRoute = display.optString("route").trim()
                    if (candidateRoute.isEmpty()) continue
                    if (display.optBoolean("isDefault") || displayRoute.isEmpty()) {
                        displayRoute = candidateRoute
                        displayId = display.optString("id").trim()
                    }
                    if (display.optBoolean("isDefault")) break
                }
            }
            val apisArray = project.optJSONArray("apis")
            val apis = mutableListOf<Map<String, Any?>>()
            if (apisArray != null) {
                for (i in 0 until apisArray.length()) {
                    val api = apisArray.optJSONObject(i) ?: continue
                    val toolId = api.optString("toolId").trim().takeIf { it.isNotEmpty() } ?: continue
                    val displayName = api.optString("displayName").trim().ifEmpty { toolId }
                    val inputSchema = api.optJSONObject("inputSchema")
                    val required = inputSchema?.optJSONArray("required")
                    val hasInputs = required != null && required.length() > 0
                    apis += linkedMapOf("toolId" to toolId, "displayName" to displayName, "hasInputs" to hasInputs)
                }
            }
            val frontendType = when {
                (project.optJSONObject("frontendHtml")?.optJSONObject("sources")?.length() ?: 0) > 0 -> "html"
                (project.optJSONObject("frontendMarkdown")?.optJSONObject("sources")?.length() ?: 0) > 0 -> "markdown"
                (project.optJSONObject("frontendFlutter")?.optJSONObject("sources")?.length() ?: 0) > 0 -> "flutter"
                else -> "default"
            }
            val cardEntryId = "$toolEntryId-project"
            val cardData = linkedMapOf<String, Any?>(
                "type" to "workbench_project",
                "projectId" to projectId,
                "name" to name,
                "description" to description,
                "route" to route.ifEmpty { displayRoute },
                "displayRoute" to displayRoute,
                "displayId" to displayId,
                "frontendType" to frontendType,
                "apis" to apis,
                "createdAt" to System.currentTimeMillis()
            )
            persistConversationMutation("inject workbench project card", publish = true) {
                repository.upsertUiCard(
                    conversationId = conversationId ?: return@persistConversationMutation,
                    conversationMode = resolvedConversationMode,
                    entryId = cardEntryId,
                    cardData = sanitizeInteropMap(cardData),
                    streamMeta = streamMeta(
                        entryId = cardEntryId,
                        roundIndex = roundIndex,
                        kind = "workbench_project_card",
                        isFinal = true
                    ),
                    createdAt = System.currentTimeMillis()
                )
            }
            sendStreamEvent(
                kind = "workbench_project_card",
                entryId = cardEntryId,
                roundIndex = roundIndex,
                isFinal = true,
                extras = cardData
            )
        }

        override suspend fun onChatMessage(message: String) {
            dispatchAgentChatMessage(message, isFinal = true)
        }

        override suspend fun onChatMessage(message: String, isFinal: Boolean) {
            dispatchAgentChatMessage(message, isFinal)
        }

        override suspend fun onChatMessage(
            message: String,
            isFinal: Boolean,
            prefillTokensPerSecond: Double?,
            decodeTokensPerSecond: Double?
        ) {
            dispatchAgentChatMessage(
                message = message,
                isFinal = isFinal,
                prefillTokensPerSecond = prefillTokensPerSecond,
                decodeTokensPerSecond = decodeTokensPerSecond
            )
        }

        override suspend fun onPromptTokenUsageChanged(
            latestPromptTokens: Int,
            promptTokenThreshold: Int?
        ) {
            sendFlutterEvent(
                "onAgentPromptTokenUsageChanged",
                mapOf(
                    "latestPromptTokens" to latestPromptTokens,
                    "promptTokenThreshold" to promptTokenThreshold
                )
            )
        }

        override suspend fun onContextCompactionStateChanged(
            isCompacting: Boolean,
            latestPromptTokens: Int?,
            promptTokenThreshold: Int?
        ) {
            sendFlutterEvent(
                "onAgentContextCompactionStateChanged",
                mapOf(
                    "isCompacting" to isCompacting,
                    "latestPromptTokens" to latestPromptTokens,
                    "promptTokenThreshold" to promptTokenThreshold
                )
            )
        }

        override suspend fun onClarifyRequired(
            question: String,
            missingFields: List<String>?,
            dialog: UserDialog?
        ) {
            finalizeThinkingCardIfNeeded()
            val normalizedQuestion = AgentTextSanitizer.sanitizeUtf16(question).trim()
            val (roundIndex, entryId) = ensureAssistantEntry(
                forceNewRound = latestAssistantVisibleText.isNotEmpty() || assistantRound > 0
            )
            latestAssistantVisibleText = normalizedQuestion
            if (normalizedQuestion.isNotEmpty()) {
                upsertClarifyMessage(
                    entryId = entryId,
                    roundIndex = roundIndex,
                    question = normalizedQuestion
                )
            }
            sendStreamEvent(
                kind = "clarify_required",
                entryId = entryId,
                roundIndex = roundIndex,
                text = normalizedQuestion,
                question = normalizedQuestion,
                missingFields = missingFields,
                extras = if (dialog != null) mapOf("dialog" to dialog.toPayload()) else emptyMap()
            )
        }

        override suspend fun onComplete(result: AgentResult) {
            val isSuccess = result is AgentResult.Success
            runCatching {
                InternalRunLogStore.finishRun(
                    context = this@AssistsCoreManager.context,
                    runId = taskId,
                    success = isSuccess,
                    doneReason = if (isSuccess) "finished" else "error"
                )
            }.onFailure {
                OmniLog.w(TAG, "finish internal agent run log failed: ${it.message}")
            }
            val outputKind = (result as? AgentResult.Success)?.outputKind ?: "none"
            val hasUserVisibleOutput =
                (result as? AgentResult.Success)?.hasUserVisibleOutput == true
            val latestPromptTokens = (result as? AgentResult.Success)?.latestPromptTokens
            val promptTokenThreshold =
                (result as? AgentResult.Success)?.promptTokenThreshold
            val streamed = scheduledAssistantBuffer.toString().trim()
            val fallback = (result as? AgentResult.Success)
                ?.response
                ?.content
                ?.trim()
                .orEmpty()
            val finalText = resolveAssistantFinalText(
                streamed = streamed.ifEmpty { latestAssistantVisibleText },
                fallback = fallback
            ).ifEmpty {
                if (isSuccess && outputKind == "none" && !hasUserVisibleOutput) {
                    this@AssistsCoreManager.t(
                        "暂时无法生成回复，请重试。",
                        "I can't generate a reply right now. Please try again."
                    )
                } else {
                    ""
                }
            }
            finalizeThinkingCardIfNeeded(publish = finalText.isBlank())
            var completedEntryId: String? = activeAssistantEntryId
            var completedRoundIndex = assistantRound
            if (finalText.isNotBlank()) {
                val shouldCreateAssistantEntry =
                    activeAssistantEntryId != null || latestAssistantVisibleText.isBlank()
                if (shouldCreateAssistantEntry) {
                    val (roundIndex, entryId) = if (activeAssistantEntryId != null) {
                        assistantRound.coerceAtLeast(1) to activeAssistantEntryId!!
                    } else {
                        ensureAssistantEntry(forceNewRound = assistantRound > 0)
                    }
                    completedEntryId = entryId
                    completedRoundIndex = roundIndex
                    latestAssistantVisibleText = finalText
                    upsertAssistantSnapshot(
                        entryId = entryId,
                        roundIndex = roundIndex,
                        text = finalText,
                        isError = !isSuccess,
                        isFinal = true,
                        streamKind = "text_snapshot"
                    )
                    sendStreamEvent(
                        kind = "text_snapshot",
                        entryId = entryId,
                        roundIndex = roundIndex,
                        isFinal = true,
                        text = finalText
                    )
                }
            }
            scheduledSubagentMeta?.let { meta ->
                val notificationText = finalText.ifEmpty {
                    if (isSuccess) {
                        this@AssistsCoreManager.t("任务已完成，点击查看详情。", "Task completed. Tap to view details.")
                    } else {
                        this@AssistsCoreManager.t("任务已结束，请点击查看详情。", "Task ended. Tap to view details.")
                    }
                }
                runCatching {
                    this@AssistsCoreManager.notifyScheduledSubagentCompletion(meta, notificationText)
                }.onFailure {
                    OmniLog.w(
                        TAG,
                        "notify scheduled subagent completion failed: ${it.message}",
                        it
                    )
                }
            }
            if (scheduledSubagentMeta == null) {
                TaskRuntimeSettings.notifyTaskFinished(
                    context = this@AssistsCoreManager.context,
                    title = if (isSuccess) "Agent 任务已完成" else "Agent 任务已结束",
                    message = finalText.ifBlank {
                        if (isSuccess) "任务已完成，点击查看详情" else "任务已结束，点击查看详情"
                    },
                    conversationId = conversationId ?: currentConversationId,
                    conversationMode = resolvedConversationMode
                )
            }
            sendStreamEvent(
                kind = "completed",
                entryId = completedEntryId,
                roundIndex = completedRoundIndex,
                success = isSuccess,
                outputKind = outputKind,
                hasUserVisibleOutput = hasUserVisibleOutput,
                latestPromptTokens = latestPromptTokens,
                promptTokenThreshold = promptTokenThreshold
            )
            // Terminal event — bypass vsync and deliver immediately so the UI
            // drops the "running" state on the same frame. (cc-haha pattern:
            // content_block_stop yields directly without buffering.)
            agentStreamEventBatcher.flushNow()
        }

        override suspend fun onError(error: String) {
            runCatching {
                InternalRunLogStore.finishRun(
                    context = this@AssistsCoreManager.context,
                    runId = taskId,
                    success = false,
                    doneReason = "error",
                    errorMessage = error
                )
            }.onFailure {
                OmniLog.w(TAG, "finish internal agent error run log failed: ${it.message}")
            }
            val resolution = resolveAgentFinalErrorResolution(
                streamed = scheduledAssistantBuffer.toString().ifBlank {
                    latestAssistantVisibleText
                },
                error = error,
                localizedFallback = this@AssistsCoreManager.t(
                    "暂时无法生成回复，请重试。",
                    "I can't generate a reply right now. Please try again."
                )
            )
            val finalText = resolution.text
            finalizeThinkingCardIfNeeded(publish = finalText.isBlank())
            var errorEntryId: String? = activeAssistantEntryId
            var errorRoundIndex = assistantRound
            if (finalText.isNotBlank()) {
                val shouldCreateAssistantEntry =
                    activeAssistantEntryId != null || latestAssistantVisibleText.isBlank()
                if (shouldCreateAssistantEntry) {
                    val (roundIndex, entryId) = if (activeAssistantEntryId != null) {
                        assistantRound.coerceAtLeast(1) to activeAssistantEntryId!!
                    } else {
                        ensureAssistantEntry(forceNewRound = assistantRound > 0)
                    }
                    errorEntryId = entryId
                    errorRoundIndex = roundIndex
                    latestAssistantVisibleText = finalText
                    upsertAssistantSnapshot(
                        entryId = entryId,
                        roundIndex = roundIndex,
                        text = finalText,
                        isError = resolution.persistAsError,
                        isFinal = true,
                        streamKind = "text_snapshot"
                    )
                    sendStreamEvent(
                        kind = "text_snapshot",
                        entryId = entryId,
                        roundIndex = roundIndex,
                        isFinal = true,
                        text = finalText
                    )
                }
            }
            scheduledSubagentMeta?.let { meta ->
                runCatching {
                    this@AssistsCoreManager.notifyScheduledSubagentCompletion(meta, finalText)
                }.onFailure {
                    OmniLog.w(
                        TAG,
                        "notify scheduled subagent error failed: ${it.message}",
                        it
                    )
                }
            }
            sendStreamEvent(
                kind = "error",
                entryId = errorEntryId,
                roundIndex = errorRoundIndex,
                error = error,
                extras = mapOf("persistAsError" to resolution.persistAsError)
            )
            agentStreamEventBatcher.flushNow()
        }

        override suspend fun onPermissionRequired(missing: List<String>) {
            finalizeThinkingCardIfNeeded()
            val (roundIndex, entryId) = ensureAssistantEntry(
                forceNewRound = latestAssistantVisibleText.isNotEmpty() || assistantRound > 0
            )
            val permissionMessage = buildPermissionRequiredMessage(missing)
            latestAssistantVisibleText = AgentTextSanitizer.sanitizeUtf16(permissionMessage).trim()
            upsertPermissionState(
                textEntryId = entryId,
                roundIndex = roundIndex,
                missing = missing
            )
            sendStreamEvent(
                kind = "permission_required",
                entryId = entryId,
                roundIndex = roundIndex,
                text = permissionMessage,
                missing = missing,
                extras = mapOf("permissionCardId" to "$taskId-permission")
            )
        }

        override suspend fun onVlmTaskFinished() {
            handleVlmTaskFinished("unified_agent_listener", taskId = taskId)
        }

        override suspend fun onSkillsResolved(skills: List<Map<String, Any?>>) {
            if (skills.isEmpty()) return
            val names = skills.joinToString(", ") { it["skillId"]?.toString() ?: "" }
            val card = linkedMapOf<String, Any?>(
                "card_id" to "$taskId-skills",
                "type" to "skills_loaded",
                "title" to "Skills: $names",
                "header" to linkedMapOf(
                    "step_index" to 0,
                    "title" to "Skills: $names",
                    "tool_name" to "skills_loaded",
                    "status" to "success",
                    "success" to true
                ),
                "skills" to skills,
                "skill_names" to names
            )
            runCatching {
                InternalRunLogStore.upsertCard(
                    context = this@AssistsCoreManager.context,
                    runId = taskId,
                    cardId = "$taskId-skills",
                    card = card
                )
            }.onFailure {
                OmniLog.w(TAG, "onSkillsResolved run log failed: ${it.message}")
            }
        }

        // -----------------------------------------------------------------------
        // Private helpers
        // -----------------------------------------------------------------------

        private suspend fun dispatchAgentChatMessage(
            message: String,
            isFinal: Boolean,
            prefillTokensPerSecond: Double? = null,
            decodeTokensPerSecond: Double? = null
        ) {
            val normalizedMessage = sanitizeAgentVisibleText(message)
            var entryId: String? = activeAssistantEntryId
            var roundIndex = assistantRound
            if (normalizedMessage.isNotEmpty()) {
                val resolvedEntry = ensureAssistantEntry()
                roundIndex = resolvedEntry.first
                entryId = resolvedEntry.second
                val resolvedEntryId = resolvedEntry.second
                val currentSnapshot =
                    sanitizeAgentVisibleText(scheduledAssistantBuffer.toString())
                if (shouldIgnoreRegressiveSnapshot(currentSnapshot, normalizedMessage)) {
                    OmniLog.d(
                        TAG,
                        "ignore stale agent snapshot: incoming=${normalizedMessage.length}, current=${currentSnapshot.length}, final=$isFinal"
                    )
                    return
                }
                scheduledAssistantBuffer.setLength(0)
                scheduledAssistantBuffer.append(normalizedMessage)
                latestAssistantVisibleText = normalizedMessage
                upsertAssistantSnapshot(
                    entryId = resolvedEntryId,
                    roundIndex = roundIndex,
                    text = normalizedMessage,
                    isError = false,
                    isFinal = isFinal,
                    streamKind = "text_snapshot"
                )
            }
            val snapshotText = entryId?.let {
                sanitizeAgentVisibleText(scheduledAssistantBuffer.toString())
            }.orEmpty()
            if (entryId != null && snapshotText.isNotEmpty()) {
                sendStreamEvent(
                    kind = "text_snapshot",
                    entryId = entryId,
                    roundIndex = roundIndex.coerceAtLeast(1),
                    isFinal = isFinal,
                    text = snapshotText.ifEmpty { normalizedMessage },
                    prefillTokensPerSecond = prefillTokensPerSecond,
                    decodeTokensPerSecond = decodeTokensPerSecond
                )
            }
        }

        private fun shouldIgnoreRegressiveSnapshot(
            current: String,
            incoming: String
        ): Boolean {
            if (current.isEmpty() || incoming.isEmpty()) {
                return false
            }
            return incoming.length < current.length && current.startsWith(incoming)
        }

        private fun resolveAssistantFinalText(
            streamed: String,
            fallback: String
        ): String {
            val normalizedStreamed = sanitizeAgentVisibleText(streamed)
            val normalizedFallback = sanitizeAgentVisibleText(fallback)
            if (normalizedFallback.isEmpty()) {
                return normalizedStreamed
            }
            if (normalizedStreamed.isEmpty()) {
                return normalizedFallback
            }
            return when {
                normalizedFallback.length >= normalizedStreamed.length &&
                    normalizedFallback.startsWith(normalizedStreamed) -> normalizedFallback
                normalizedStreamed.length > normalizedFallback.length &&
                    normalizedStreamed.startsWith(normalizedFallback) -> normalizedStreamed
                else -> normalizedFallback
            }
        }

        private suspend fun sendFlutterEvent(
            method: String,
            args: Map<String, Any?>
        ) {
            val payload = sanitizeInteropMap(
                mapOf(
                    "taskId" to taskId,
                    "conversationId" to conversationId,
                    "conversationMode" to resolvedConversationMode
                ) + args
            )
            withContext(Dispatchers.Main) {
                this@AssistsCoreManager.invokeFlutterEventSafely(method, payload)
            }
        }
    }
}
