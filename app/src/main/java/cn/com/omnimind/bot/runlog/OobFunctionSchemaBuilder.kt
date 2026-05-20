package cn.com.omnimind.bot.runlog

/**
 * Builds the JSON-schema shaped argument contract used when an OOB reusable
 * Function is exposed as an agent tool.
 */
object OobFunctionSchemaBuilder {
    fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(spec["inputSchema"]).ifEmpty { mapArg(spec["input_schema"]) }
        if (explicit.isNotEmpty()) return explicit

        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        listArg(spec["parameters"]).forEach { raw ->
            val parameter = mapArg(raw)
            val name = parameter["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@forEach

            val type = parameter["type"]?.toString()?.trim()?.ifEmpty { "string" } ?: "string"
            val property = linkedMapOf<String, Any?>(
                "type" to jsonSchemaType(type)
            )
            parameter["description"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                property["description"] = it
            }
            if (parameter.containsKey("default")) {
                property["default"] = parameter["default"]
            }
            val enumValues = listArg(parameter["enum"]).ifEmpty { listArg(parameter["values"]) }
            if (enumValues.isNotEmpty()) {
                property["enum"] = enumValues
            }

            properties[name] = property
            if (boolArg(parameter["required"])) {
                required += name
            }
        }

        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }

    private fun jsonSchemaType(type: String): String =
        when (type.lowercase()) {
            "int", "integer" -> "integer"
            "number", "float", "double" -> "number"
            "bool", "boolean" -> "boolean"
            "array", "object" -> type.lowercase()
            else -> "string"
        }

    private fun mapArg(value: Any?): Map<String, Any?> {
        return when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
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

    private fun boolArg(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> false
        }
    }
}
