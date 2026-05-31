package cn.com.omnimind.bot.runlog

import com.google.gson.GsonBuilder

internal object RunLogCardAccessors {
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    fun toolNameForCard(card: Map<String, Any?>): String {
        val header = asMap(card["header"])
        val toolCall = asMap(card["tool_call"]).ifEmpty { asMap(card["toolCall"]) }
        val function = asMap(toolCall["function"])
        return firstNonBlank(
            toolCall["name"],
            toolCall["tool_name"],
            toolCall["toolName"],
            function["name"],
            card["tool_name"],
            card["toolName"],
            card["action_type"],
            card["actionType"],
            header["tool_name"],
            header["toolName"],
        )
    }

    fun extractArgs(card: Map<String, Any?>): Any? {
        val toolCall = asMap(card["tool_call"]).ifEmpty { asMap(card["toolCall"]) }
        val function = asMap(toolCall["function"])
        return firstPresent(
            toolCall["params"],
            toolCall["arguments"],
            toolCall["args"],
            function["arguments"],
            card["params"],
            card["arguments"],
            card["args"],
        ) ?: emptyMap<String, Any?>()
    }

    fun extractResult(card: Map<String, Any?>): Any? {
        return firstPresent(
            card["result"],
            card["raw_result_json"],
            card["rawResultJson"],
            card["resultPreviewJson"],
            card["tool_result"],
            card["toolResult"],
            card["execution_result"],
            card["executionResult"],
            card["output"],
            card["error"],
            card["error_message"],
            card["errorMessage"],
        )
    }

    fun beforeObservationForCard(card: Map<String, Any?>): Map<String, Any?> {
        return asMap(card["before"])
            .ifEmpty { asMap(card["observation_before_act"]) }
            .ifEmpty { asMap(card["before_observation"]) }
            .ifEmpty { asMap(card["observation"]) }
    }

    fun afterObservationForCard(card: Map<String, Any?>): Map<String, Any?> {
        return asMap(card["after"])
            .ifEmpty { asMap(card["observation_after_act"]) }
            .ifEmpty { asMap(card["after_observation"]) }
    }

    fun observationXml(observation: Map<String, Any?>): String {
        return firstNonBlank(
            observation["observation_xml"],
            observation["observationXml"],
            observation["xml"],
            observation["page"],
        )
    }

    fun androidPrivilegedReplayAction(args: Map<String, Any?>): String? {
        return OobActionCodec.canonicalActionForName(
            firstNonBlank(args["action"], args["omniflow_action"])
        )
    }

    fun androidPrivilegedReplayArgs(args: Map<String, Any?>): Map<String, Any?> {
        val nestedArguments = asMap(args["arguments"])
        val flattened = LinkedHashMap<String, Any?>()
        for ((key, value) in args) {
            if (key == "action" || key == "omniflow_action" || key == "arguments") continue
            flattened[key] = value
        }
        flattened.putAll(nestedArguments)
        return OobActionCodec.argsForStep(
            mapOf(
                "tool" to firstNonBlank(args["action"], args["omniflow_action"]),
                "args" to flattened,
            )
        )
    }

    fun asMap(value: Any?): Map<String, Any?> {
        val decoded = decodeJsonIfNeeded(value)
        if (decoded !is Map<*, *>) return emptyMap()
        return decoded.entries.associate { (key, item) -> key.toString() to item }
    }

    fun jsonSafeMap(value: Any?): Map<String, Any?> = asMap(jsonSafe(value))

    fun jsonSafe(value: Any?): Any? {
        val decoded = decodeJsonIfNeeded(value)
        return when (decoded) {
            null -> null
            is String, is Number, is Boolean -> decoded
            is Map<*, *> -> decoded.entries.associate { (key, item) ->
                key.toString() to jsonSafe(item)
            }
            is Iterable<*> -> decoded.map(::jsonSafe)
            is Array<*> -> decoded.map(::jsonSafe)
            else -> decoded.toString()
        }
    }

    fun firstPresent(vararg values: Any?): Any? {
        for (value in values) {
            if (value == null) continue
            if (value is String && value.trim().isEmpty()) continue
            return value
        }
        return null
    }

    fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    fun nullableMap(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            pairs.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
        }
    }

    fun isEmptyJsonValue(value: Any?): Boolean {
        return when (value) {
            null -> true
            is String -> value.trim().isEmpty()
            is Map<*, *> -> value.isEmpty()
            is Iterable<*> -> !value.iterator().hasNext()
            else -> false
        }
    }

    fun asBoolean(value: Any?): Boolean? =
        when (value) {
            is Boolean -> value
            is String -> value.trim().lowercase().let { text ->
                when (text) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            else -> null
        }

    fun toJson(value: Any?): String = gson.toJson(value)

    private fun decodeJsonIfNeeded(value: Any?): Any? {
        if (value !is String) return value
        val text = value.trim()
        if (text.isEmpty()) return value
        if (!text.startsWith("{") && !text.startsWith("[")) return value
        return runCatching { gson.fromJson(text, Any::class.java) }.getOrElse { value }
    }
}
