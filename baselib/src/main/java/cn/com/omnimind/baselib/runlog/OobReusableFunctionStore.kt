package cn.com.omnimind.baselib.runlog

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import org.json.JSONArray

object OobReusableFunctionStore {
    private const val TAG = "OobReusableFunctionStore"
    private const val PREFS_NAME = "oob_reusable_functions"
    private const val INDEX_KEY = "oob_reusable_function_index_v1"
    private const val SPEC_PREFIX = "oob_reusable_function_spec_v1:"
    private const val RUNNER = "oob_agent_reusable_function"

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    @Synchronized
    fun register(
        context: Context,
        functionSpec: Map<String, Any?>
    ): Map<String, Any?> {
        val spec = sanitizeMap(functionSpec)
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        require(functionId.isNotEmpty()) { "function_id is empty" }

        val prefs = prefs(context)
        val key = "$SPEC_PREFIX$functionId"
        val alreadyExists = prefs.contains(key)
        val existing = get(context, functionId)
        val now = System.currentTimeMillis().toString()
        val existingRegistry = existing?.get("_oob_registry") as? Map<*, *>
        val stored = linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put(
                "_oob_registry",
                linkedMapOf(
                    "registered_at" to (
                        existingRegistry?.get("registered_at")?.toString()?.takeIf {
                            it.isNotBlank()
                        } ?: now
                    ),
                    "updated_at" to now,
                    "runner" to RUNNER
                )
            )
        }

        prefs.edit().putString(key, gson.toJson(stored)).apply()
        val index = readIndex(prefs).toMutableList()
        if (!index.contains(functionId)) {
            index.add(0, functionId)
            writeIndex(prefs, index)
        }

        return linkedMapOf(
            "success" to true,
            "function_id" to functionId,
            "created_function_id" to functionId,
            "imported" to true,
            "already_exists" to alreadyExists,
            "count" to index.size,
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source_run_ids" to sourceRunIds(stored),
            "runner" to RUNNER
        )
    }

    @Synchronized
    fun get(context: Context, functionId: String): Map<String, Any?>? {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) return null
        val raw = prefs(context).getString("$SPEC_PREFIX$normalized", null)
            ?.takeIf { it.trim().isNotEmpty() }
            ?: return null
        return runCatching { decodeMap(raw) }
            .onFailure { OmniLog.w(TAG, "decode function failed: ${it.message}") }
            .getOrNull()
    }

    @Synchronized
    fun list(context: Context, limit: Int = 100): Map<String, Any?> {
        val prefs = prefs(context)
        val safeLimit = limit.coerceIn(1, 500)
        val functions = readIndex(prefs)
            .take(safeLimit)
            .mapNotNull { functionId -> get(context, functionId) }
        return linkedMapOf(
            "success" to true,
            "count" to functions.size,
            "functions" to functions.map(::summaryMap)
        )
    }

    @Synchronized
    fun delete(context: Context, functionId: String): Boolean {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) return false
        val prefs = prefs(context)
        val key = "$SPEC_PREFIX$normalized"
        if (!prefs.contains(key)) return false
        prefs.edit().remove(key).apply()
        val index = readIndex(prefs).toMutableList()
        index.remove(normalized)
        writeIndex(prefs, index)
        return true
    }

    fun materialize(
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>
    ): Map<String, Any?> {
        val spec = mutableJsonMap(sanitizeMap(functionSpec))
        val resolvedArguments = linkedMapOf<String, Any?>()
        val bindingResults = mutableListOf<Map<String, Any?>>()
        val missingRequired = mutableListOf<String>()
        val parameters = spec["parameters"] as? List<*> ?: emptyList<Any?>()
        parameters.forEach { rawParameter ->
            val parameter = rawParameter as? Map<*, *> ?: return@forEach
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val type = parameter["type"]?.toString()?.trim().orEmpty()
            val hasCallArgument = arguments.containsKey(name)
            val hasDefault = parameter.containsKey("default")
            val rawValue = if (hasCallArgument) {
                arguments[name]
            } else {
                parameter["default"]
            }
            val value = coerceParameterValue(rawValue, type)
            if (value == null) {
                if (isRequired(parameter["required"]) && !hasCallArgument && !hasDefault) {
                    missingRequired += name
                }
                return@forEach
            }
            resolvedArguments[name] = value
            val bindings = parameter["bindings"] as? List<*> ?: emptyList<Any?>()
            bindings.forEach { rawBinding ->
                val binding = rawBinding?.toString()?.trim().orEmpty()
                if (binding.isEmpty()) return@forEach
                bindingResults += linkedMapOf(
                    "parameter" to name,
                    "binding" to binding,
                    "applied" to setJsonPathValue(spec, binding, value)
                )
            }
        }

        val existingRuntime = spec["runtime"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        spec["runtime"] = linkedMapOf<String, Any?>().apply {
            existingRuntime.forEach { (key, value) ->
                if (key != null) put(key.toString(), sanitizeValue(value))
            }
            put("arguments", sanitizeMap(arguments))
            put("resolved_arguments", resolvedArguments)
            put("binding_results", bindingResults)
            put("missing_required_arguments", missingRequired)
            put("materialized_at", System.currentTimeMillis().toString())
            put("runner", RUNNER)
        }
        val execution = spec["execution"] as? MutableMap<String, Any?>
        execution?.put("arguments_applied", true)
        execution?.put("argument_application", "native_materialized_before_agent_run")
        return spec
    }

    fun missingRequiredArguments(
        functionSpec: Map<String, Any?>,
        arguments: Map<String, Any?>
    ): List<String> {
        val parameters = functionSpec["parameters"] as? List<*> ?: return emptyList()
        return parameters.mapNotNull { rawParameter ->
            val parameter = rawParameter as? Map<*, *> ?: return@mapNotNull null
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty() || !isRequired(parameter["required"])) {
                return@mapNotNull null
            }
            val hasArgument = arguments.containsKey(name) && arguments[name] != null
            val hasDefault = parameter.containsKey("default") && parameter["default"] != null
            if (hasArgument || hasDefault) null else name
        }
    }

    fun prettyJson(value: Any?): String = gson.toJson(sanitizeValue(value))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readIndex(prefs: android.content.SharedPreferences): List<String> {
        val raw = prefs.getString(INDEX_KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrElse {
            OmniLog.w(TAG, "read index failed: ${it.message}")
            emptyList()
        }
    }

    private fun writeIndex(
        prefs: android.content.SharedPreferences,
        index: List<String>
    ) {
        val array = JSONArray()
        index.distinct().forEach { functionId -> array.put(functionId) }
        prefs.edit().putString(INDEX_KEY, array.toString()).apply()
    }

    private fun decodeMap(raw: String): Map<String, Any?> {
        val decoded = gson.fromJson(raw, Map::class.java) ?: return emptyMap()
        return sanitizeMap(decoded.mapKeys { it.key.toString() })
    }

    private fun sanitizeMap(value: Map<*, *>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) {
                    put(key.toString(), sanitizeValue(item))
                }
            }
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String, is Number, is Boolean -> value
            is Map<*, *> -> sanitizeMap(value)
            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }
    }

    private fun mutableJsonMap(value: Map<String, Any?>): MutableMap<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, mutableJsonValue(item))
            }
        }
    }

    private fun mutableJsonValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), mutableJsonValue(item))
                }
            }
            is Iterable<*> -> value.map(::mutableJsonValue).toMutableList()
            is Array<*> -> value.map(::mutableJsonValue).toMutableList()
            else -> value
        }
    }

    private fun coerceParameterValue(value: Any?, type: String): Any? {
        if (value == null) return null
        return when (type.trim().lowercase()) {
            "number", "integer", "int" -> when (value) {
                is Number -> value
                is String -> value.trim().let { text ->
                    text.toLongOrNull() ?: text.toDoubleOrNull() ?: value
                }
                else -> value
            }
            "boolean", "bool" -> when (value) {
                is Boolean -> value
                is String -> when (value.trim().lowercase()) {
                    "true", "1", "yes", "y" -> true
                    "false", "0", "no", "n" -> false
                    else -> value
                }
                else -> value
            }
            else -> value
        }
    }

    private fun isRequired(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true)
            else -> false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setJsonPathValue(
        root: MutableMap<String, Any?>,
        path: String,
        value: Any?
    ): Boolean {
        val normalized = path.trim().removePrefix("$.")
        if (normalized.isEmpty()) return false
        val parts = normalized.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        var current: Any? = root
        parts.forEachIndexed { index, part ->
            val token = parsePathToken(part) ?: return false
            val isLast = index == parts.lastIndex
            val next = when (current) {
                is MutableMap<*, *> -> {
                    val map = current as MutableMap<String, Any?>
                    if (isLast && token.index == null) {
                        map[token.key] = value
                        return true
                    }
                    val child = map[token.key] ?: return false
                    if (token.index == null) {
                        child
                    } else {
                        val list = child as? MutableList<Any?> ?: return false
                        if (token.index !in list.indices) return false
                        if (isLast) {
                            list[token.index] = value
                            return true
                        }
                        list[token.index]
                    }
                }
                is MutableList<*> -> {
                    val list = current as MutableList<Any?>
                    val listIndex = token.key.toIntOrNull() ?: token.index ?: return false
                    if (listIndex !in list.indices) return false
                    if (isLast) {
                        list[listIndex] = value
                        return true
                    }
                    list[listIndex]
                }
                else -> return false
            }
            current = next
        }
        return false
    }

    private data class JsonPathToken(val key: String, val index: Int?)

    private fun parsePathToken(part: String): JsonPathToken? {
        val match = Regex("""^([A-Za-z0-9_]+)(?:\[(\d+)])?$""").matchEntire(part)
            ?: return null
        return JsonPathToken(
            key = match.groupValues[1],
            index = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        )
    }

    private fun sourceRunIds(spec: Map<String, Any?>): List<String> {
        val source = spec["source"] as? Map<*, *>
        return source?.get("run_id")
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { listOf(it) }
            ?: emptyList()
    }

    private fun summaryMap(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = spec["execution"] as? Map<*, *>
        val steps = execution?.get("steps") as? List<*>
        val parameters = spec["parameters"] as? List<*> ?: emptyList<Any?>()
        val registry = spec["_oob_registry"] as? Map<*, *>
        return linkedMapOf(
            "function_id" to spec["function_id"],
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (steps?.size ?: 0),
            "parameter_names" to parameters.mapNotNull { raw ->
                (raw as? Map<*, *>)?.get("name")?.toString()?.takeIf { it.isNotBlank() }
            },
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "runner" to RUNNER,
            "registered_at" to registry?.get("registered_at"),
            "updated_at" to registry?.get("updated_at"),
            "source_run_ids" to sourceRunIds(spec)
        )
    }
}
