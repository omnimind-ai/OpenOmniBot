package cn.com.omnimind.bot.omniflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.UUID
import kotlin.math.absoluteValue

class OmniFlowSimpleIngest(
    private val store: OmniFlowSimpleStore? = null,
    private val json: Json = OmniFlowSimpleStore.defaultJson
) {
    fun ingestRunLogJson(payload: String): Pair<OmniFlowSimpleRunLog, OmniFlowSimpleFunction> {
        val root = json.parseToJsonElement(payload).jsonObject
        return ingestRunLog(runLogFromJson(root))
    }

    fun ingestRunLogMap(payload: Map<String, Any?>): Pair<OmniFlowSimpleRunLog, OmniFlowSimpleFunction> {
        return ingestRunLog(runLogFromJson(payload.toJsonElement().jsonObject))
    }

    fun ingestRunLog(runLog: OmniFlowSimpleRunLog): Pair<OmniFlowSimpleRunLog, OmniFlowSimpleFunction> {
        val function = functionFromRunLog(runLog)
        store?.saveRunLog(runLog)
        store?.saveFunction(function)
        return runLog to function
    }

    fun runLogFromJson(root: JsonObject): OmniFlowSimpleRunLog {
        val now = System.currentTimeMillis()
        val startedAtMs = root.longValue("started_at_ms")
            ?: root.stringValue("started_at")?.parseIsoMillis()
            ?: now
        val finishedAtMs = root.longValue("finished_at_ms")
            ?: root.stringValue("finished_at")?.parseIsoMillis()
            ?: now
        val runId = root.stringValue("run_id")
            ?: root.stringValue("task_id")
            ?: "run_${UUID.randomUUID()}"
        val steps = root["steps"]?.jsonArray.orEmpty().mapIndexedNotNull { index, item ->
            stepFromJson(index, item.jsonObject)
        }
        return OmniFlowSimpleRunLog(
            runId = runId,
            goal = root.stringValue("goal").orEmpty(),
            success = root.booleanValue("success") ?: true,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            durationMs = root.longValue("duration_ms")
                ?: (finishedAtMs - startedAtMs).coerceAtLeast(0),
            finalPackageName = root.stringValue("final_package_name")
                ?: root.stringValue("package_name"),
            appName = root.stringValue("app_name"),
            source = root.stringValue("source") ?: "vlm",
            steps = steps,
            createdAtMs = root.longValue("created_at_ms") ?: now
        )
    }

    fun functionFromRunLog(runLog: OmniFlowSimpleRunLog): OmniFlowSimpleFunction {
        val replayableActions = runLog.steps
            .map { it.action.copy(type = it.action.normalizedType()) }
            .filter { it.isReplayable() }
        val functionId = "fn_${stableSlug(runLog.goal)}_${runLog.runId.hashCode().absoluteValue}"
        return OmniFlowSimpleFunction(
            functionId = functionId,
            name = functionId,
            description = runLog.goal.ifBlank { "OOB simple UTG function" },
            actions = replayableActions,
            sourceRunIds = listOf(runLog.runId),
            packageName = runLog.finalPackageName,
            appName = runLog.appName
        )
    }

    private fun stepFromJson(index: Int, step: JsonObject): OmniFlowSimpleRunLogStep? {
        val toolCall = step["tool_call"]?.jsonObject
        val actionObj = step["action"]?.jsonObject
        val rawType = toolCall?.stringValue("name")
            ?: step.stringValue("action_type")
            ?: actionObj?.stringValue("type")
            ?: actionObj?.stringValue("name")
            ?: return null
        val params = toolCall?.get("params")?.jsonObject
            ?: actionObj?.get("params")?.jsonObject
            ?: actionObj?.let { actionParamsFromFlatAction(normalizeActionType(rawType), it) }
            ?: JsonObject(emptyMap())
        val action = OmniFlowSimpleAction(
            type = normalizeActionType(rawType),
            params = params,
            result = step.stringValue("result"),
            success = step.booleanValue("success"),
            startedAtMs = step.longValue("started_at_ms")
                ?: step.stringValue("started_at")?.parseIsoMillis(),
            finishedAtMs = step.longValue("finished_at_ms")
                ?: step.stringValue("finished_at")?.parseIsoMillis()
        )
        return OmniFlowSimpleRunLogStep(
            index = step.intValue("index") ?: index,
            action = action,
            observation = step.stringValue("observation").orEmpty(),
            thought = step.stringValue("thought").orEmpty(),
            summary = step.stringValue("summary").orEmpty(),
            packageName = step.stringValue("package_name")
        )
    }

    private fun actionParamsFromFlatAction(type: String, action: JsonObject): JsonObject {
        return buildJsonObject {
            when (type) {
                "click", "long_press" -> {
                    action["x"]?.let { put("x", it) }
                    action["y"]?.let { put("y", it) }
                    action["target_description"]?.let { put("target_description", it) }
                }
                "input_text" -> {
                    action["text"]?.let { put("text", it) }
                    action["content"]?.let { put("text", it) }
                }
                "swipe" -> {
                    listOf("x1", "y1", "x2", "y2", "duration_ms", "duration").forEach { key ->
                        action[key]?.let { put(key, it) }
                    }
                    action["target_description"]?.let { put("target_description", it) }
                }
                "open_app" -> action["package_name"]?.let { put("package_name", it) }
                "press_key" -> {
                    action["key"]?.let { put("key", it) }
                    when (action.stringValue("name")) {
                        "press_home" -> put("key", JsonPrimitive("HOME"))
                        "press_back" -> put("key", JsonPrimitive("BACK"))
                    }
                }
                "wait" -> {
                    action["duration_ms"]?.let { put("duration_ms", it) }
                    action["duration"]?.let { put("duration", it) }
                }
                "finished" -> action["content"]?.let { put("content", it) }
            }
        }
    }

    private fun stableSlug(text: String): String {
        val normalized = text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "_")
            .trim('_')
        return normalized.take(32).ifBlank { "utg" }
    }
}

internal fun JsonObject.stringValue(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.booleanValue(key: String): Boolean? {
    return this[key]?.jsonPrimitive?.booleanOrNull
}

internal fun JsonObject.longValue(key: String): Long? {
    return this[key]?.jsonPrimitive?.longOrNull
}

internal fun JsonObject.intValue(key: String): Int? {
    return longValue(key)?.toInt()
}

internal fun String.parseIsoMillis(): Long? {
    return runCatching { java.time.Instant.parse(this).toEpochMilli() }.getOrNull()
        ?: toLongOrNull()
}

internal fun Map<String, Any?>.toJsonElement(): JsonElement {
    return JsonObject(entries.associate { (key, value) -> key to value.toJsonElement() })
}

internal fun Any?.toJsonElement(): JsonElement {
    return when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
            key.toString() to value.toJsonElement()
        })
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }
}
