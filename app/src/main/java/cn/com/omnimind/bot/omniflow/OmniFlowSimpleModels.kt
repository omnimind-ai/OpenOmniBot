package cn.com.omnimind.bot.omniflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal val SUPPORTED_ACTION_TYPES = setOf(
    "click",
    "long_press",
    "input_text",
    "swipe",
    "open_app",
    "press_key",
    "wait",
    "finished"
)

@Serializable
data class OmniFlowSimpleAction(
    val type: String,
    val params: Map<String, JsonElement> = emptyMap(),
    val result: String? = null,
    val success: Boolean? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null
) {
    fun normalizedType(): String = normalizeActionType(type)
    fun isReplayable(): Boolean = normalizedType() in SUPPORTED_ACTION_TYPES

    fun toFunctionMap(): Map<String, Any?> = linkedMapOf(
        "type" to normalizedType(),
        "params" to params.toInteropMap()
    )

    fun toToolCallMap(): Map<String, Any?> = linkedMapOf(
        "name" to normalizedType(),
        "params" to params.toInteropMap()
    )
}

@Serializable
data class OmniFlowSimpleRunLogStep(
    val index: Int,
    val action: OmniFlowSimpleAction,
    val observation: String = "",
    val thought: String = "",
    val summary: String = "",
    val packageName: String? = null
) {
    fun toMap(): Map<String, Any?> {
        val durationMs = when {
            action.startedAtMs != null && action.finishedAtMs != null ->
                (action.finishedAtMs - action.startedAtMs).coerceAtLeast(0)
            else -> null
        }
        return linkedMapOf(
            "index" to index,
            "tool_call" to action.toToolCallMap(),
            "action_type" to action.normalizedType(),
            "observation" to observation,
            "thought" to thought,
            "summary" to summary,
            "success" to action.success,
            "started_at" to action.startedAtMs?.toIsoString(),
            "finished_at" to action.finishedAtMs?.toIsoString(),
            "duration_ms" to durationMs,
            "package_name" to packageName,
            "selection_source" to "vlm"
        )
    }
}

@Serializable
data class OmniFlowSimpleRunLog(
    val runId: String,
    val goal: String,
    val success: Boolean,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val durationMs: Long,
    val finalPackageName: String? = null,
    val appName: String? = null,
    val source: String = "vlm",
    val steps: List<OmniFlowSimpleRunLogStep> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "run_id" to runId,
        "goal" to goal,
        "success" to success,
        "started_at" to startedAtMs.toIsoString(),
        "finished_at" to finishedAtMs.toIsoString(),
        "duration_ms" to durationMs,
        "final_package_name" to finalPackageName,
        "app_name" to appName,
        "source" to source,
        "steps" to steps.map { it.toMap() },
        "created_at" to createdAtMs.toIsoString()
    )
}

@Serializable
data class OmniFlowSimpleRunStats(
    val callCount: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastRunId: String? = null,
    val lastRunAtMs: Long? = null,
    val lastSuccess: Boolean? = null
) {
    fun record(runId: String, success: Boolean): OmniFlowSimpleRunStats {
        return copy(
            callCount = callCount + 1,
            successCount = successCount + if (success) 1 else 0,
            failCount = failCount + if (success) 0 else 1,
            lastRunId = runId,
            lastRunAtMs = System.currentTimeMillis(),
            lastSuccess = success
        )
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "call_count" to callCount,
        "success_count" to successCount,
        "fail_count" to failCount,
        "last_run_id" to lastRunId,
        "last_run_at" to lastRunAtMs?.toIsoString(),
        "last_success" to lastSuccess
    )
}

@Serializable
data class OmniFlowSimpleFunction(
    val functionId: String,
    val name: String,
    val description: String,
    val actions: List<OmniFlowSimpleAction>,
    val sourceRunIds: List<String> = emptyList(),
    val packageName: String? = null,
    val appName: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val runStats: OmniFlowSimpleRunStats = OmniFlowSimpleRunStats()
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "function_id" to functionId,
        "name" to name,
        "description" to description,
        "executor_kind" to "oob_simple_utg",
        "project_id" to OMNIFLOW_SIMPLE_PROJECT_ID,
        "actions" to actions.map { it.toFunctionMap() },
        "metadata" to linkedMapOf(
            "source_run_ids" to sourceRunIds,
            "created_at" to createdAtMs.toIsoString(),
            "updated_at" to updatedAtMs.toIsoString()
        ),
        "run_stats" to runStats.toMap(),
        "asset_refs" to linkedMapOf(
            "xml_refs" to emptyList<String>(),
            "screenshot_refs" to emptyList<String>()
        ),
        "package_name" to packageName,
        "app_name" to appName
    )
}

@Serializable
data class OmniFlowSimpleState(
    val schemaVersion: Int = 1,
    val runLogs: List<OmniFlowSimpleRunLog> = emptyList(),
    val functions: List<OmniFlowSimpleFunction> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class OmniFlowSimpleExecutionResult(
    val success: Boolean,
    val route: String,
    val functionId: String,
    val runId: String,
    val message: String,
    val providerResponse: Map<String, Any?>? = null,
    val steps: List<Map<String, Any?>> = emptyList()
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "success" to success,
        "route" to route,
        "function_id" to functionId,
        "run_id" to runId,
        "message" to message,
        "provider_response" to providerResponse,
        "steps" to steps
    )
}

internal fun normalizeActionType(raw: String): String {
    return when (raw.trim().lowercase()) {
        "type", "input", "text" -> "input_text"
        "long_click" -> "long_press"
        "slide", "scroll" -> "swipe"
        "press_hotkey", "hot_key", "press_home", "press_back" -> "press_key"
        "launch_application" -> "open_app"
        else -> raw.trim().lowercase()
    }
}

internal fun Map<String, JsonElement>.toInteropMap(): Map<String, Any?> {
    return entries.associate { (key, value) -> key to value.toInteropValue() }
}

internal fun JsonElement.toInteropValue(): Any? {
    return when (this) {
        JsonNull -> null
        is JsonPrimitive -> {
            if (isString) {
                content
            } else {
                booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
            }
        }
        is JsonArray -> map { it.toInteropValue() }
        is JsonObject -> entries.associate { (key, value) -> key to value.toInteropValue() }
    }
}

internal fun Long.toIsoString(): String {
    return java.time.Instant.ofEpochMilli(this).toString()
}
