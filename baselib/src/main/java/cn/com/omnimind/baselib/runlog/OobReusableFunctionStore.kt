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
        val rawSpec = sanitizeMap(functionSpec)
        val functionId = functionIdFromSpec(rawSpec)
        require(functionId.isNotEmpty()) { "function_id is empty" }
        val spec = linkedMapOf<String, Any?>().apply {
            putAll(rawSpec)
            putIfAbsent("function_id", functionId)
            putIfAbsent("name", functionId)
        }

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
    fun functionIds(context: Context): List<String> =
        readIndex(prefs(context))

    @Synchronized
    fun functionsForSourceRunId(
        context: Context,
        runId: String,
        limit: Int = 20
    ): List<Map<String, Any?>> {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 100)
        return readIndex(prefs(context))
            .asSequence()
            .mapNotNull { functionId -> get(context, functionId) }
            .filter { spec -> normalizedRunId in sourceRunIds(spec) }
            .take(safeLimit)
            .map { spec ->
                linkedMapOf<String, Any?>(
                    "summary" to summaryMap(spec),
                    "function_spec" to sanitizeMap(spec)
                )
            }
            .toList()
    }

    @Synchronized
    fun clear(context: Context): Map<String, Any?> {
        val prefs = prefs(context)
        val indexedIds = readIndex(prefs)
        val orphanKeys = prefs.all.keys.filter { it.startsWith(SPEC_PREFIX) }
        val deletedIds = (indexedIds + orphanKeys.map { it.removePrefix(SPEC_PREFIX) })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        prefs.edit().apply {
            indexedIds.forEach { functionId -> remove("$SPEC_PREFIX$functionId") }
            orphanKeys.forEach(::remove)
            remove(INDEX_KEY)
        }.apply()
        return linkedMapOf(
            "success" to true,
            "deleted_count" to deletedIds.size,
            "function_ids" to deletedIds,
            "source" to "oob_reusable_function_store"
        )
    }

    @Synchronized
    fun recordRun(
        context: Context,
        functionId: String,
        success: Boolean,
        runId: String? = null,
        runner: String? = null,
        stepCount: Int? = null,
        errorMessage: String? = null
    ): Map<String, Any?> {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) {
            return linkedMapOf("success" to false, "error_message" to "function_id is empty")
        }
        val existing = get(context, normalized)
            ?: return linkedMapOf(
                "success" to false,
                "function_id" to normalized,
                "error_message" to "function not found"
            )
        val key = "$SPEC_PREFIX$normalized"
        val now = System.currentTimeMillis().toString()
        val existingRegistry = existing["_oob_registry"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val existingStats = existingRegistry["run_stats"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val runCount = intValue(existingStats["run_count"]) + 1
        val successCount = intValue(existingStats["success_count"]) + if (success) 1 else 0
        val failCount = intValue(existingStats["fail_count"]) + if (success) 0 else 1
        val lastRun = linkedMapOf<String, Any?>(
            "run_id" to runId?.trim().orEmpty(),
            "success" to success,
            "runner" to runner?.trim().orEmpty(),
            "step_count" to stepCount,
            "error_message" to errorMessage?.trim().orEmpty(),
            "created_at" to now
        )
        val runStats = linkedMapOf<String, Any?>(
            "run_count" to runCount,
            "success_count" to successCount,
            "fail_count" to failCount,
            "last_run_at" to now,
            "last_success" to success,
            "last_run" to lastRun
        )
        val updatedRegistry = linkedMapOf<String, Any?>().apply {
            existingRegistry.forEach { (key, value) ->
                if (key != null) put(key.toString(), sanitizeValue(value))
            }
            put("updated_at", now)
            put("runner", existingRegistry["runner"] ?: RUNNER)
            put("run_stats", runStats)
        }
        val updated = linkedMapOf<String, Any?>().apply {
            putAll(existing)
            put("_oob_registry", updatedRegistry)
        }
        prefs(context).edit().putString(key, gson.toJson(updated)).apply()
        return linkedMapOf(
            "success" to true,
            "function_id" to normalized,
            "run_stats" to runStats,
            "last_run" to lastRun
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
        val legacyParameters = spec["parameters"] as? List<*> ?: emptyList<Any?>()
        if (legacyParameters.isNotEmpty()) {
            legacyParameters.forEach { rawParameter ->
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
                val bindings = listArg(parameter["bindings"])
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
        } else {
            val parameterSchema = mapArg(spec["parameters"])
            val properties = mapArg(parameterSchema["properties"])
            val requiredNames = listArg(parameterSchema["required"])
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .toSet()
            properties.forEach { (name, rawProperty) ->
                val parameter = mapArg(rawProperty)
                if (name.isBlank()) return@forEach
                val type = firstJsonSchemaType(parameter["type"])
                val hasCallArgument = arguments.containsKey(name)
                val hasDefault = parameter.containsKey("default")
                val rawValue = if (hasCallArgument) {
                    arguments[name]
                } else {
                    parameter["default"]
                }
                val value = coerceParameterValue(rawValue, type)
                if (value == null) {
                    if (name in requiredNames && !hasCallArgument && !hasDefault) {
                        missingRequired += name
                    }
                    return@forEach
                }
                resolvedArguments[name] = value
                parameterBindings(name, parameter, spec).forEach { binding ->
                    bindingResults += linkedMapOf(
                        "parameter" to name,
                        "binding" to binding,
                        "applied" to setJsonPathValue(spec, binding, value)
                    )
                }
            }
        }

        if (resolvedArguments.isNotEmpty()) {
            val rendered = renderParameterTemplates(spec, resolvedArguments)
            if (rendered is Map<*, *>) {
                val renderedSpec = mutableJsonMap(sanitizeMap(rendered))
                spec.clear()
                spec.putAll(renderedSpec)
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
        val legacyParameters = functionSpec["parameters"] as? List<*>
        if (legacyParameters != null) {
            return legacyParameters.mapNotNull { rawParameter ->
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
        val schema = mapArg(functionSpec["parameters"])
        val required = listArg(schema["required"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (required.isEmpty()) return emptyList()
        val properties = mapArg(schema["properties"])
        return required.mapNotNull { name ->
            val property = mapArg(properties[name])
            val hasArgument = arguments.containsKey(name) && arguments[name] != null
            val hasDefault = property.containsKey("default") && property["default"] != null
            if (hasArgument || hasDefault) null else name
        }
    }

    fun functionIdFromSpec(functionSpec: Map<String, Any?>): String =
        firstNonBlank(functionSpec["function_id"], functionSpec["functionId"], functionSpec["name"])

    fun parameterNames(functionSpec: Map<String, Any?>): List<String> {
        val legacyParameters = functionSpec["parameters"] as? List<*>
        if (legacyParameters != null) {
            return legacyParameters.mapNotNull { raw ->
                (raw as? Map<*, *>)?.get("name")?.toString()?.trim()?.takeIf(String::isNotEmpty)
            }
        }
        val schema = mapArg(functionSpec["parameters"])
        return mapArg(schema["properties"]).keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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

    private fun mapArg(value: Any?): Map<String, Any?> {
        return when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), sanitizeValue(item))
                }
            }
            else -> emptyMap()
        }
    }

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
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

    private fun firstJsonSchemaType(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is List<*> -> value.firstNotNullOfOrNull {
                it?.toString()?.trim()?.takeIf { type -> type != "null" && type.isNotEmpty() }
            }.orEmpty()
            else -> ""
        }
    }

    private fun parameterBindings(
        name: String,
        property: Map<String, Any?>,
        spec: Map<String, Any?>,
    ): List<String> {
        val output = linkedSetOf<String>()
        listArg(property["bindings"]).forEach { raw ->
            raw?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
        }
        listArg(property["x_oob_bindings"]).forEach { raw ->
            raw?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
        }
        listArg(property["x-oob-bindings"]).forEach { raw ->
            raw?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
        }

        val metadata = mapArg(spec["metadata"])
        val bindingEntries = listArg(spec["x_oob_parameter_bindings"]) +
            listArg(spec["parameter_bindings"]) +
            listArg(metadata["oob_parameter_bindings"])
        bindingEntries.forEach { rawEntry ->
            val entry = mapArg(rawEntry)
            if (firstNonBlank(entry["name"], entry["parameter"]) != name) return@forEach
            listArg(entry["bindings"]).forEach { raw ->
                raw?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
            }
            firstNonBlank(entry["binding"]).takeIf(String::isNotEmpty)?.let(output::add)
        }
        return output.toList()
    }

    private fun renderParameterTemplates(
        value: Any?,
        arguments: Map<String, Any?>,
    ): Any? {
        if (arguments.isEmpty()) return value
        return when (value) {
            is String -> renderTemplateString(value, arguments)
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), renderParameterTemplates(item, arguments))
                }
            }
            is List<*> -> value.map { renderParameterTemplates(it, arguments) }
            is Array<*> -> value.map { renderParameterTemplates(it, arguments) }
            else -> value
        }
    }

    private fun renderTemplateString(
        text: String,
        arguments: Map<String, Any?>,
    ): Any? {
        val exact = PARAMETER_TOKEN_REGEX.matchEntire(text.trim())
        if (exact != null) {
            val name = exact.groupValues[1]
            if (arguments.containsKey(name)) return arguments[name]
        }
        return PARAMETER_TOKEN_REGEX.replace(text) { match ->
            val name = match.groupValues[1]
            arguments[name]?.toString() ?: match.value
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
        val metadata = spec["metadata"] as? Map<*, *>
        return buildList {
            source?.get("run_id")
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::add)
            metadata?.get("run_id")
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::add)
            (metadata?.get("source_run_ids") as? List<*>)?.forEach { raw ->
                raw?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.distinct()
    }

    private fun summaryMap(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = spec["execution"] as? Map<*, *>
        val steps = execution?.get("steps") as? List<*>
        val actions = spec["actions"] as? List<*>
        val stepLikeItems = steps ?: actions ?: emptyList<Any?>()
        val registry = spec["_oob_registry"] as? Map<*, *>
        val source = spec["source"] as? Map<*, *>
        val runStats = registry?.get("run_stats") as? Map<*, *>
        return linkedMapOf(
            "function_id" to functionIdFromSpec(spec),
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (execution?.get("step_count") ?: stepLikeItems.size),
            "card_count" to (
                intValue(source?.get("card_count"))
                    .takeIf { it > 0 }
                    ?: intValue(source?.get("replayable_card_count"))
                    .takeIf { it > 0 }
                    ?: stepLikeItems.size
                ),
            "parameter_names" to parameterNames(spec),
            "step_summaries" to stepLikeItems.mapIndexedNotNull { index, rawStep ->
                val step = rawStep as? Map<*, *> ?: return@mapIndexedNotNull null
                linkedMapOf(
                    "index" to index,
                    "id" to step["id"],
                    "title" to step["title"],
                    "kind" to (step["kind"] ?: step["type"]),
                    "executor" to step["executor"],
                    "tool" to (
                        step["omniflow_action"]
                            ?: step["local_action"]
                            ?: step["callable_tool"]
                            ?: step["tool"]
                            ?: step["type"]
                        )
                )
            },
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "runner" to RUNNER,
            "registered_at" to registry?.get("registered_at"),
            "updated_at" to registry?.get("updated_at"),
            "source_run_ids" to sourceRunIds(spec),
            "run_stats" to sanitizeValue(runStats ?: emptyMap<Any?, Any?>()),
            "last_run" to sanitizeValue(runStats?.get("last_run") ?: emptyMap<Any?, Any?>())
        )
    }

    private fun intValue(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private val PARAMETER_TOKEN_REGEX by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    }
}
