package cn.com.omnimind.bot.runlog

/**
 * Builds the JSON-schema shaped argument contract used when an OOB reusable
 * Function is exposed as an agent tool.
 */
object OobFunctionSchemaBuilder {
    fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(spec["inputSchema"]).ifEmpty { mapArg(spec["input_schema"]) }
        if (explicit.isNotEmpty()) return explicit

        val canonical = mapArg(spec["parameters"])
        if (canonical.isNotEmpty() && firstNonBlank(canonical["type"]).equals("object", ignoreCase = true)) {
            return canonical
        }

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

    fun functionId(spec: Map<String, Any?>): String =
        firstNonBlank(spec["function_id"], spec["functionId"], spec["name"])

    fun parameterNames(spec: Map<String, Any?>): List<String> {
        val canonical = mapArg(spec["parameters"])
        if (canonical.isNotEmpty()) {
            return mapArg(canonical["properties"]).keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return listArg(spec["parameters"]).mapNotNull { raw ->
            mapArg(raw)["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mapArg(spec["execution"])
        val legacySteps = listArg(execution["steps"]).mapNotNull { raw ->
            mapArg(raw).takeIf { it.isNotEmpty() }
        }
        if (legacySteps.isNotEmpty()) return legacySteps

        return listArg(spec["actions"]).mapIndexedNotNull { index, raw ->
            canonicalActionToStep(index, mapArg(raw))
        }
    }

    fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        materializedSteps(spec).mapIndexed { index, step ->
            val tool = firstNonBlank(
                step["omniflow_action"],
                step["local_action"],
                step["callable_tool"],
                step["tool"],
                step["type"],
            )
            linkedMapOf(
                "index" to index,
                "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"], tool),
                "kind" to step["kind"],
                "executor" to step["executor"],
                "tool" to tool,
            )
        }

    private fun canonicalActionToStep(
        index: Int,
        action: Map<String, Any?>,
    ): Map<String, Any?>? {
        val rawType = firstNonBlank(action["type"], action["name"], action["tool"])
        if (rawType.isEmpty()) return null
        val normalizedType = RunLogReplayPolicy.omniflowActionForToolName(rawType)
            ?: RunLogReplayPolicy.normalizeToolName(rawType)
        val params = mapArg(action["params"])
        val target = mapArg(action["target"])
        val sourceContext = mapArg(params["source_context"])
            .ifEmpty { mapArg(action["source_context"]) }
        val title = firstNonBlank(action["description"], action["prompt"], rawType)
            .ifBlank { normalizedType }
        val stepId = firstNonBlank(action["id"], action["step_id"], "step_${index + 1}")

        return when (normalizedType) {
            "click" -> {
                val targetKind = firstNonBlank(target["kind"])
                if (targetKind == "node_ref") {
                    graphStep(
                        stepId = stepId,
                        index = index,
                        title = title,
                        args = linkedMapOf(
                            "node_id" to firstNonBlank(target["nodeId"], target["node_id"]),
                        ).filterValues { it != null && it.toString().isNotBlank() },
                    )
                } else {
                    localActionStep(
                        stepId = stepId,
                        index = index,
                        title = title,
                        action = "click",
                        args = linkedMapOf<String, Any?>().apply {
                            putFirstPresent("x", target["x"], params["x"], action["x"])
                            putFirstPresent("y", target["y"], params["y"], action["y"])
                            putFirstPresent(
                                "target_description",
                                action["prompt"],
                                target["prompt"],
                                params["clickPrompt"],
                                params["target_description"],
                                params["targetDescription"],
                            )
                            putFirstPresent("selector", params["selector"], action["selector"])
                            if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                        },
                        sourceContext = sourceContext,
                    )
                }
            }
            "long_press" -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = "long_press",
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("x", target["x"], params["x"], action["x"])
                    putFirstPresent("y", target["y"], params["y"], action["y"])
                    putFirstPresent("duration_ms", target["duration_ms"], params["duration_ms"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                },
                sourceContext = sourceContext,
            )
            "type", "input_text" -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = "input_text",
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("text", action["text"], params["text"], action["content"], params["content"])
                    putFirstPresent("selector", params["selector"], action["selector"])
                    putFirstPresent("clear", params["clear"], action["clear"])
                },
                sourceContext = sourceContext,
            )
            "scroll", "swipe" -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = "swipe",
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("x", target["x"], params["x"], action["x"])
                    putFirstPresent("y", target["y"], params["y"], action["y"])
                    putFirstPresent("end_x", target["end_x"], target["endX"], params["end_x"], params["endX"])
                    putFirstPresent("end_y", target["end_y"], target["endY"], params["end_y"], params["endY"])
                    putFirstPresent("direction", action["direction"], target["direction"], params["direction"])
                    putFirstPresent("distance", action["distance"], target["distance"], params["distance"])
                    putFirstPresent("duration_ms", action["duration_ms"], action["durationMs"], params["duration_ms"], params["durationMs"])
                    if (sourceContext.isNotEmpty()) put("source_context", sourceContext)
                },
                sourceContext = sourceContext,
            )
            "open_app" -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = "open_app",
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("package_name", action["packageName"], action["package_name"], params["package_name"])
                    putFirstPresent("reset_task", action["reset_task"], params["reset_task"])
                    putFirstPresent("launch_mode", action["launch_mode"], params["launch_mode"])
                },
                sourceContext = emptyMap(),
            )
            "press_home" -> localActionStep(stepId, index, title, "press_key", mapOf("key" to "home"), emptyMap())
            "press_back" -> localActionStep(stepId, index, title, "press_key", mapOf("key" to "back"), emptyMap())
            "hot_key", "press_key" -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = "press_key",
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("key", action["key"], params["key"], action["hotkey"], params["hotkey"])
                },
                sourceContext = emptyMap(),
            )
            "wait" -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = "wait",
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("time_ms", action["timeMs"], action["time_ms"], params["timeMs"], params["time_ms"])
                    putFirstPresent("time_s", action["time_s"], params["time_s"], params["seconds"])
                    putFirstPresent("selector", params["selector"], action["selector"])
                    putFirstPresent("url", params["url"], action["url"])
                },
                sourceContext = emptyMap(),
            )
            "finished" -> localActionStep(
                stepId = stepId,
                index = index,
                title = title,
                action = "finished",
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("content", action["content"], params["content"])
                    putFirstPresent("enable_summary", action["enableSummary"], params["enable_summary"])
                    putFirstPresent("summary_prompt", action["summaryPrompt"], params["summary_prompt"])
                },
                sourceContext = emptyMap(),
            )
            "click_node", "go_to_node", "node_click", "navigate_to_node", "gotonode", "goto_node" -> graphStep(
                stepId = stepId,
                index = index,
                title = title,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent("node_id", params["node_id"], params["nodeId"], action["node_id"], action["nodeId"])
                    putFirstPresent("path", params["path"], action["path"])
                    putFirstPresent("utg", params["utg"], action["utg"])
                },
            )
            "call_function", "run_function", "execute_function", "omniflow.call_function" -> functionStep(
                stepId = stepId,
                index = index,
                title = title,
                args = linkedMapOf<String, Any?>().apply {
                    putFirstPresent(
                        "function_id",
                        params["function_id"],
                        params["functionId"],
                        params["function_name"],
                        params["functionName"],
                        action["function_id"],
                        action["functionId"],
                    )
                    putFirstPresent("function_name", params["function_name"], params["functionName"])
                    putFirstPresent("node_id", params["node_id"], params["nodeId"])
                    val arguments = mapArg(params["arguments"]).ifEmpty { mapArg(action["arguments"]) }
                    if (arguments.isNotEmpty()) put("arguments", arguments)
                },
            )
            "external_tool" -> externalToolStep(
                stepId = stepId,
                index = index,
                title = title,
                toolName = firstNonBlank(
                    action["toolName"],
                    action["tool_name"],
                    params["tool_name"],
                    params["toolName"],
                ),
                args = mapArg(action["arguments"]).ifEmpty { mapArg(params["arguments"]) },
            )
            else -> externalToolStep(
                stepId = stepId,
                index = index,
                title = title,
                toolName = normalizedType,
                args = params.ifEmpty { action },
            )
        }
    }

    private fun localActionStep(
        stepId: String,
        index: Int,
        title: String,
        action: String,
        args: Map<String, Any?>,
        sourceContext: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "omniflow_action" to action,
        "local_action" to action,
        "model_free" to true,
        "scriptable" to true,
        "tool" to action,
        "callable_tool" to action,
        "args" to args.filterValues { it != null },
        "source_context" to sourceContext.takeIf { it.isNotEmpty() },
        "coordinate_hook" to if (action in RunLogReplayPolicy.coordinateActions && sourceContext.isNotEmpty()) {
            "omniflow"
        } else {
            null
        },
    ).filterValues { it != null }

    private fun graphStep(
        stepId: String,
        index: Int,
        title: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "omniflow_graph",
        "executor" to "omniflow",
        "model_free" to true,
        "scriptable" to true,
        "tool" to "go_to_node",
        "callable_tool" to "go_to_node",
        "args" to args.filterValues { it != null },
        "replay_engine" to "omniflow_utg",
    )

    private fun functionStep(
        stepId: String,
        index: Int,
        title: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "omniflow_function",
        "executor" to "omniflow",
        "model_free" to true,
        "scriptable" to true,
        "tool" to "call_function",
        "callable_tool" to "call_function",
        "args" to args.filterValues { it != null },
    )

    private fun externalToolStep(
        stepId: String,
        index: Int,
        title: String,
        toolName: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to stepId,
        "index" to index,
        "title" to title,
        "kind" to "tool_call",
        "executor" to "tool",
        "scriptable" to true,
        "tool" to toolName,
        "callable_tool" to toolName,
        "args" to args,
    )

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

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
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
}
