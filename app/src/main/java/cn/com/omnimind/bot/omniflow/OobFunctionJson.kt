package cn.com.omnimind.bot.omniflow

/**
 * Small JSON/value coercion helpers shared by Function services.
 *
 * Keep these helpers mechanical: they should normalize public tool payload
 * shapes, not encode Function policy.
 */
internal object OobFunctionJson {
    fun mutableJsonMap(value: Map<String, Any?>): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, mutableJsonValue(item))
            }
        }

    fun mutableJsonList(value: List<Any?>): MutableList<Any?> =
        value.map { mutableJsonValue(it) }.toMutableList()

    fun mutableJsonValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), mutableJsonValue(item))
                }
            }
            is List<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            is Array<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            else -> value
        }

    fun sanitizeMap(value: Map<*, *>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) put(key.toString(), sanitizeValue(item))
            }
        }

    fun sanitizeValue(value: Any?): Any? =
        when (value) {
            null -> null
            is String, is Number, is Boolean -> value
            is Map<*, *> -> sanitizeMap(value)
            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }

    fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }

    fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }

    fun boolArg(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
            is Number -> value.toInt() != 0
            else -> false
        }

    fun boolArgOrDefault(value: Any?, defaultValue: Boolean): Boolean =
        when (value) {
            null -> defaultValue
            is Boolean -> value
            is String -> {
                val text = value.trim().lowercase()
                when (text) {
                    "true", "1", "yes", "y", "on" -> true
                    "false", "0", "no", "n", "off" -> false
                    else -> defaultValue
                }
            }
            is Number -> value.toInt() != 0
            else -> defaultValue
        }

    fun intArg(vararg values: Any?, defaultValue: Int): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    fun longArg(vararg values: Any?, defaultValue: Long): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.trim().toLongOrNull()?.let { return it }
            }
        }
        return defaultValue
    }
}
