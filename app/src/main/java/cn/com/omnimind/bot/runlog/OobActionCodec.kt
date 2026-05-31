package cn.com.omnimind.bot.runlog

/**
 * Canonical action parser for OOB Function steps.
 *
 * This is the single place where legacy action/tool names are mapped into the
 * compact action vocabulary consumed by replay, UDEG, and update_function.
 */
object OobActionCodec {
    const val ACTION_CLICK = "click"
    const val ACTION_LONG_PRESS = "long_press"
    const val ACTION_INPUT_TEXT = "input_text"
    const val ACTION_SWIPE = "swipe"
    const val ACTION_OPEN_APP = "open_app"
    const val ACTION_PRESS_KEY = "press_key"
    const val ACTION_FINISHED = "finished"

    val executableActions: Set<String> = setOf(
        ACTION_CLICK,
        ACTION_LONG_PRESS,
        ACTION_INPUT_TEXT,
        ACTION_SWIPE,
        ACTION_OPEN_APP,
        ACTION_PRESS_KEY,
        ACTION_FINISHED,
    )

    val coordinateActions: Set<String> = setOf(
        ACTION_CLICK,
        ACTION_LONG_PRESS,
        ACTION_INPUT_TEXT,
        ACTION_SWIPE,
    )

    val actionAliases: Map<String, String> = mapOf(
        "tap" to ACTION_CLICK,
        "click_at" to ACTION_CLICK,
        "click_element" to ACTION_CLICK,
        "clickelement" to ACTION_CLICK,
        "longclick" to ACTION_LONG_PRESS,
        "long_click" to ACTION_LONG_PRESS,
        "longpress" to ACTION_LONG_PRESS,
        "type" to ACTION_INPUT_TEXT,
        "type_text" to ACTION_INPUT_TEXT,
        "set_text" to ACTION_INPUT_TEXT,
        "settext" to ACTION_INPUT_TEXT,
        "inputtext" to ACTION_INPUT_TEXT,
        "scroll" to ACTION_SWIPE,
        "scroll_down" to ACTION_SWIPE,
        "scroll_up" to ACTION_SWIPE,
        "scroll_left" to ACTION_SWIPE,
        "scroll_right" to ACTION_SWIPE,
        "back" to ACTION_PRESS_KEY,
        "press_back" to ACTION_PRESS_KEY,
        "pressback" to ACTION_PRESS_KEY,
        "press_back_button" to ACTION_PRESS_KEY,
        "home" to ACTION_PRESS_KEY,
        "press_home" to ACTION_PRESS_KEY,
        "presshome" to ACTION_PRESS_KEY,
        "press_home_button" to ACTION_PRESS_KEY,
        "hot_key" to ACTION_PRESS_KEY,
        "hotkey" to ACTION_PRESS_KEY,
        "presskey" to ACTION_PRESS_KEY,
        "key_event" to ACTION_PRESS_KEY,
        "keyevent" to ACTION_PRESS_KEY,
        "openapp" to ACTION_OPEN_APP,
        "launch_app" to ACTION_OPEN_APP,
        "launchapp" to ACTION_OPEN_APP,
        "finish" to ACTION_FINISHED,
        "done" to ACTION_FINISHED,
        "complete" to ACTION_FINISHED,
    )

    fun normalizeName(raw: String): String =
        raw.trim().lowercase()

    fun canonicalActionForName(raw: String): String? {
        val normalized = normalizeName(raw)
        return normalized.takeIf { it in executableActions } ?: actionAliases[normalized]
    }

    fun actionNameForStep(step: Map<String, Any?>): String {
        val raw = rawActionNameForStep(step)
        return canonicalActionForName(raw)
            ?: normalizeName(raw).ifBlank { "unknown" }
    }

    fun argsForStep(step: Map<String, Any?>): Map<String, Any?> {
        val args = mapArg(step["args"]).toMutableMap()
        val rawAction = rawActionNameForStep(step)
        when (normalizeName(rawAction)) {
            "back", "press_back", "pressback", "press_back_button" ->
                args.putIfAbsent("key", "back")
            "home", "press_home", "presshome", "press_home_button" ->
                args.putIfAbsent("key", "home")
        }
        return args
    }

    fun sourceContextForStep(step: Map<String, Any?>): Map<String, Any?> =
        mapArg(step["source_context"]).ifEmpty { mapArg(mapArg(step["args"])["source_context"]) }

    fun sourceActionForStep(step: Map<String, Any?>): Map<String, Any?> =
        mapArg(sourceContextForStep(step)["action"])

    fun pageXmlFromContext(context: Map<String, Any?>): String =
        firstNonBlank(
            context["page"],
            context["xml"],
            context["observation_xml"],
            context["observationXml"],
        )

    private fun rawActionNameForStep(step: Map<String, Any?>): String {
        val sourceAction = sourceActionForStep(step)
        return firstNonBlankActionName(
            step["action"],
            step["omniflow_action"],
            step["local_action"],
            step["tool"],
            step["callable_tool"],
            step["type"],
            sourceAction["tool"],
            sourceAction["action"],
            sourceAction["type"],
        )
    }

    private fun firstNonBlankActionName(vararg values: Any?): String {
        values.forEach { value ->
            val text = when (value) {
                is String -> value.trim()
                is CharSequence -> value.toString().trim()
                else -> ""
            }
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    fun actionArgsSummary(
        step: Map<String, Any?>,
        maxValueChars: Int = DEFAULT_ACTION_SUMMARY_VALUE_CHARS,
    ): Map<String, Any?> =
        actionArgsSummary(
            actionType = actionNameForStep(step),
            args = argsForStep(step),
            sourceAction = sourceActionForStep(step),
            maxValueChars = maxValueChars,
        )

    fun actionArgsSummary(
        actionType: String,
        args: Map<String, Any?>,
        sourceAction: Map<String, Any?>,
        maxValueChars: Int = DEFAULT_ACTION_SUMMARY_VALUE_CHARS,
    ): Map<String, Any?> {
        val summary = linkedMapOf<String, Any?>()
        summary.putFirstPresent("target_description", args["target_description"], args["targetDescription"], sourceAction["target_description"], sourceAction["targetDescription"])
        summary.putFirstPresent("selector", args["selector"], sourceAction["selector"])
        summary.putFirstPresent("node_resource_id", args["node_resource_id"], args["nodeResourceId"], args["resource_id"], args["resourceId"], sourceAction["node_resource_id"], sourceAction["nodeResourceId"], sourceAction["resource_id"], sourceAction["resourceId"])
        summary.putFirstPresent("bounds", args["bounds"], sourceAction["bounds"])
        summary.putFirstPresent("node_class", args["node_class"], args["nodeClass"], sourceAction["node_class"], sourceAction["nodeClass"])
        summary.putFirstPresent("x", args["x"], sourceAction["x"])
        summary.putFirstPresent("y", args["y"], sourceAction["y"])
        summary.putFirstPresent("x1", args["x1"], sourceAction["x1"])
        summary.putFirstPresent("y1", args["y1"], sourceAction["y1"])
        summary.putFirstPresent("x2", args["x2"], sourceAction["x2"])
        summary.putFirstPresent("y2", args["y2"], sourceAction["y2"])
        summary.putFirstPresent("end_x", args["end_x"], args["endX"], sourceAction["end_x"], sourceAction["endX"])
        summary.putFirstPresent("end_y", args["end_y"], args["endY"], sourceAction["end_y"], sourceAction["endY"])
        summary.putFirstPresent("direction", args["direction"], args["scroll_direction"], sourceAction["direction"], sourceAction["scroll_direction"])
        summary.putFirstPresent("distance", args["distance"], args["scroll_distance"], sourceAction["distance"], sourceAction["scroll_distance"])
        summary.putFirstPresent("duration_ms", args["duration_ms"], args["durationMs"], sourceAction["duration_ms"], sourceAction["durationMs"])
        summary.putFirstPresent("package_name", args["package_name"], args["packageName"], sourceAction["package_name"], sourceAction["packageName"])
        summary.putFirstPresent("key", args["key"], args["hotkey"], args["hot_key"], sourceAction["key"], sourceAction["hotkey"], sourceAction["hot_key"])
        summary.putFirstPresent("clear", args["clear"], sourceAction["clear"])
        if (actionType == ACTION_INPUT_TEXT) {
            val text = firstNonBlank(args["text"], args["content"], args["value"], sourceAction["text"], sourceAction["content"], sourceAction["value"])
            if (text.isNotBlank()) {
                summary["text_present"] = true
                summary["text_length"] = text.length
                summary["text_redacted"] = true
            }
        }
        return summary.filterValues { value ->
            value != null && value.toString().trim().isNotEmpty()
        }.mapValues { (_, value) ->
            if (value is String) value.take(maxValueChars) else value
        }
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

    fun intArg(vararg values: Any?, defaultValue: Int): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    fun firstNonBlank(vararg values: Any?): String {
        values.forEach { value ->
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun MutableMap<String, Any?>.putFirstPresent(key: String, vararg values: Any?) {
        values.firstOrNull { value ->
            value != null && value.toString().trim().isNotEmpty()
        }?.let { put(key, it) }
    }

    private const val DEFAULT_ACTION_SUMMARY_VALUE_CHARS = 160
}
