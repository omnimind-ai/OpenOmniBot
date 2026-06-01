package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank
import cn.com.omnimind.bot.runlog.OobActionCodec.listArg
import cn.com.omnimind.bot.runlog.OobActionCodec.mapArg

/**
 * Builds reusable Function parameters and canonical action specs from compiled replay steps.
 *
 * RunLogReusableFunctionCompiler owns card-to-step conversion and top-level
 * Function assembly. This object owns deterministic input_text parameter
 * inference and the legacy actions/parameters compatibility surface.
 */
object RunLogReusableFunctionParameterizer {
    data class Result(
        val legacyParameters: List<Map<String, Any?>>,
        val parameters: Map<String, Any?>,
        val actions: List<Map<String, Any?>>,
        val parameterBindings: List<Map<String, Any?>>,
    )

    fun parameterize(steps: List<Map<String, Any?>>): Result {
        val legacyParameters = inferParameters(steps)
        return Result(
            legacyParameters = legacyParameters,
            parameters = canonicalParameterSchema(legacyParameters),
            actions = canonicalActionsForSteps(steps, legacyParameters),
            parameterBindings = parameterBindingsMetadata(legacyParameters),
        )
    }

    private fun inferParameters(steps: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val parameters = mutableListOf<Map<String, Any?>>()
        val usedNames = mutableSetOf<String>()
        steps.forEachIndexed { index, step ->
            val tool = OobActionCodec.actionNameForStep(step)
            if (tool !in INPUT_TEXT_ACTIONS) return@forEachIndexed
            val args = OobActionCodec.argsForStep(step)
            val inputKey = INPUT_TEXT_ARG_KEYS.firstOrNull { key ->
                args[key]?.toString()?.trim()?.isNotEmpty() == true
            } ?: return@forEachIndexed
            val defaultValue = args[inputKey]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val baseName = parameterNameForInputStep(step, index)
            val name = uniqueParameterName(baseName, usedNames)
            usedNames += name
            parameters += linkedMapOf(
                "name" to name,
                "type" to "string",
                "required" to false,
                "default" to defaultValue,
                "description" to "Text to input for step ${index + 1}: ${step["title"] ?: tool}",
                "source" to linkedMapOf(
                    "kind" to "run_log_argument",
                    "step_id" to step["id"],
                    "tool" to tool,
                    "arg_key" to inputKey,
                ),
                "bindings" to listOf("$.execution.steps[$index].args.$inputKey"),
            )
        }
        return parameters
    }

    private fun canonicalParameterSchema(
        legacyParameters: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        legacyParameters.forEach { parameter ->
            val name = firstNonBlank(parameter["name"])
            if (name.isBlank()) return@forEach
            val bindings = listArg(parameter["bindings"]) +
                listArg(parameter["bindings"]).mapNotNull(::actionBindingForExecutionBinding)
            val property = linkedMapOf<String, Any?>(
                "type" to jsonSchemaType(firstNonBlank(parameter["type"]).ifBlank { "string" }),
            )
            firstNonBlank(parameter["description"]).takeIf { it.isNotBlank() }?.let {
                property["description"] = it
            }
            if (parameter.containsKey("default")) {
                property["default"] = parameter["default"]
            }
            listArg(parameter["enum"]).ifEmpty { listArg(parameter["values"]) }
                .takeIf { it.isNotEmpty() }
                ?.let { property["enum"] = it }
            if (bindings.isNotEmpty()) {
                property["x_oob_bindings"] = bindings.distinct()
            }
            properties[name] = property
            if (isTruthy(parameter["required"])) {
                required += name
            }
        }
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        )
    }

    private fun canonicalActionsForSteps(
        steps: List<Map<String, Any?>>,
        legacyParameters: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val templates = parameterTemplatesByStepArg(legacyParameters)
        return steps.mapIndexedNotNull { index, step ->
            canonicalActionForStep(step, index, templates)
        }
    }

    private fun parameterBindingsMetadata(
        legacyParameters: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        return legacyParameters.mapNotNull { parameter ->
            val nameValue = firstNonBlank(parameter["name"])
            val bindings = parameter["bindings"] as? List<*> ?: emptyList<Any?>()
            if (nameValue.isBlank() || bindings.isEmpty()) return@mapNotNull null
            linkedMapOf(
                "name" to nameValue,
                "bindings" to bindings,
            )
        }
    }

    private fun parameterTemplatesByStepArg(
        legacyParameters: List<Map<String, Any?>>,
    ): Map<Pair<Int, String>, String> {
        val output = linkedMapOf<Pair<Int, String>, String>()
        legacyParameters.forEach { parameter ->
            val name = firstNonBlank(parameter["name"])
            if (name.isBlank()) return@forEach
            listArg(parameter["bindings"]).forEach { rawBinding ->
                val binding = rawBinding?.toString()?.trim().orEmpty()
                val match = EXECUTION_ARG_BINDING_REGEX.matchEntire(binding) ?: return@forEach
                val stepIndex = match.groupValues[1].toIntOrNull() ?: return@forEach
                val argKey = match.groupValues[2]
                output[stepIndex to argKey] = "\${$name}"
            }
        }
        return output
    }

    private fun actionBindingForExecutionBinding(rawBinding: Any?): String? {
        val binding = rawBinding?.toString()?.trim().orEmpty()
        val match = EXECUTION_ARG_BINDING_REGEX.matchEntire(binding) ?: return null
        val stepIndex = match.groupValues[1].toIntOrNull() ?: return null
        val argKey = match.groupValues[2]
        val actionPath = when (argKey) {
            "text", "content", "value" -> "text"
            else -> argKey
        }
        return "$.actions[$stepIndex].$actionPath"
    }

    private fun canonicalActionForStep(
        step: Map<String, Any?>,
        index: Int,
        parameterTemplates: Map<Pair<Int, String>, String>,
    ): Map<String, Any?>? {
        val args = OobActionCodec.argsForStep(step)
        val action = OobActionCodec.actionNameForStep(step)
        val description = firstNonBlank(step["title"], step["summary"]).takeIf { it.isNotBlank() }
        val sourceContext = mapArg(step["source_context"]).ifEmpty { mapArg(args["source_context"]) }
        return when {
            action == OobActionCodec.ACTION_CLICK -> canonicalPointAction(
                type = OobActionCodec.ACTION_CLICK,
                args = args,
                description = description,
                sourceContext = sourceContext,
            )
            action == OobActionCodec.ACTION_LONG_PRESS -> canonicalPointAction(
                type = OobActionCodec.ACTION_LONG_PRESS,
                args = args,
                description = description,
                sourceContext = sourceContext,
            )
            action == OobActionCodec.ACTION_INPUT_TEXT -> nullableMap(
                "type" to OobActionCodec.ACTION_INPUT_TEXT,
                "text" to inputTextValue(args, index, parameterTemplates),
                "target" to coordinateTarget(args).takeIf {
                    it["x"] != null && it["y"] != null
                },
                "prompt" to firstNonBlank(
                    args["target_description"],
                    args["targetDescription"],
                    args["label"],
                    args["selector"],
                ).takeIf { it.isNotBlank() },
                "params" to nullableMap(
                    "selector" to firstNonBlank(args["selector"]).takeIf { it.isNotBlank() },
                    "clear" to args["clear"],
                    "target_description" to firstNonBlank(
                        args["target_description"],
                        args["targetDescription"],
                        args["label"],
                    ).takeIf { it.isNotBlank() },
                    "node_resource_id" to firstNonBlank(
                        args["node_resource_id"],
                        args["nodeResourceId"],
                        args["resource_id"],
                        args["resourceId"],
                    ).takeIf { it.isNotBlank() },
                    "bounds" to firstNonBlank(args["bounds"]).takeIf { it.isNotBlank() },
                    "node_class" to firstNonBlank(args["node_class"], args["nodeClass"]).takeIf { it.isNotBlank() },
                    "source_context" to sourceContext.takeIf { it.isNotEmpty() },
                ).takeIf { it.isNotEmpty() },
                "description" to description,
            )
            action == OobActionCodec.ACTION_SWIPE -> {
                val target = coordinateTarget(args)
                nullableMap(
                    "type" to OobActionCodec.ACTION_SWIPE,
                    "target" to target,
                    "direction" to firstNonBlank(args["direction"], args["scroll_direction"]).ifBlank { "down" },
                    "distance" to firstPresent(args["distance"], args["scroll_distance"]),
                    "end_x" to firstPresent(args["end_x"], args["endX"]),
                    "end_y" to firstPresent(args["end_y"], args["endY"]),
                    "duration_ms" to firstPresent(args["duration_ms"], args["durationMs"]),
                    "params" to nullableMap(
                        "source_context" to sourceContext.takeIf { it.isNotEmpty() },
                    ).takeIf { it.isNotEmpty() },
                    "description" to description,
                )
            }
            action == OobActionCodec.ACTION_OPEN_APP -> nullableMap(
                "type" to OobActionCodec.ACTION_OPEN_APP,
                "packageName" to firstNonBlank(args["package_name"], args["packageName"]),
                "description" to description,
            )
            action == OobActionCodec.ACTION_PRESS_KEY -> nullableMap(
                "type" to OobActionCodec.ACTION_PRESS_KEY,
                "key" to firstNonBlank(args["key"], args["hotkey"], args["hot_key"]),
                "description" to description,
            )
            action == OobActionCodec.ACTION_FINISHED -> nullableMap(
                "type" to OobActionCodec.ACTION_FINISHED,
                "content" to firstPresent(args["content"], args["summary"]),
                "enableSummary" to firstPresent(args["enable_summary"], args["enableSummary"]),
                "summaryPrompt" to firstPresent(args["summary_prompt"], args["summaryPrompt"]),
                "description" to description,
            )
            RunLogReplayPolicy.isOmniflowFunctionTool(action) ||
                RunLogReplayPolicy.isOmniflowToolCallTool(action) -> {
                val functionId = firstNonBlank(
                    args["function_id"],
                    args["functionId"],
                    args["oob_function_id"],
                    args["oobFunctionId"],
                    args["function_name"],
                    args["functionName"],
                )
                nullableMap(
                    "type" to RunLogReplayPolicy.TOOL_CALL_FUNCTION,
                    "params" to nullableMap(
                        "node_id" to firstNonBlank(args["node_id"], args["nodeId"]),
                        "function_name" to functionId,
                        "function_id" to functionId,
                        "arguments" to mapArg(args["arguments"]).ifEmpty { mapArg(args["args"]) },
                    ),
                    "description" to description,
                )
            }
            RunLogReplayPolicy.isOmniflowGraphTool(action) -> nullableMap(
                "type" to RunLogReplayPolicy.TOOL_CLICK_NODE,
                "params" to nullableMap(
                    "node_id" to firstNonBlank(args["node_id"], args["nodeId"]),
                    "path" to listArg(args["path"]).takeIf { it.isNotEmpty() },
                ),
                "description" to description,
            )
            action == "wait" -> nullableMap(
                "type" to "wait",
                "timeMs" to firstPresent(args["timeMs"], args["time_ms"]),
                "params" to nullableMap(
                    "time_s" to firstPresent(args["time_s"], args["seconds"]),
                    "selector" to firstNonBlank(args["selector"]).takeIf { it.isNotBlank() },
                    "url" to firstNonBlank(args["url"]).takeIf { it.isNotBlank() },
                ).takeIf { it.isNotEmpty() },
                "description" to description,
            )
            else -> nullableMap(
                "type" to "external_tool",
                "toolName" to firstNonBlank(step["callable_tool"], step["tool"], step["action"], action),
                "arguments" to args,
                "description" to description,
            )
        }
    }

    private fun canonicalPointAction(
        type: String,
        args: Map<String, Any?>,
        description: String?,
        sourceContext: Map<String, Any?>,
    ): Map<String, Any?> {
        val target = coordinateTarget(args).takeIf {
            it["x"] != null && it["y"] != null
        } ?: nullableMap(
            "kind" to "prompt",
            "prompt" to firstNonBlank(
                args["target_description"],
                args["targetDescription"],
                args["clickPrompt"],
                args["label"],
            )
        )
        return nullableMap(
            "type" to type,
            "target" to target,
            "prompt" to firstNonBlank(
                args["target_description"],
                args["targetDescription"],
                args["clickPrompt"],
                args["label"],
            ).takeIf { it.isNotBlank() },
            "params" to nullableMap(
                "source_context" to sourceContext.takeIf { it.isNotEmpty() },
                "selector" to firstNonBlank(args["selector"]).takeIf { it.isNotBlank() },
            ).takeIf { it.isNotEmpty() },
            "description" to description,
        )
    }

    private fun coordinateTarget(args: Map<String, Any?>): Map<String, Any?> =
        nullableMap(
            "kind" to "coords",
            "x" to firstPresent(args["x"], args["center_x"], args["centerX"]),
            "y" to firstPresent(args["y"], args["center_y"], args["centerY"]),
            "xmlRef" to firstPresent(args["xml_ref"], args["xmlRef"]),
        )

    private fun inputTextValue(
        args: Map<String, Any?>,
        stepIndex: Int,
        parameterTemplates: Map<Pair<Int, String>, String>,
    ): Any? {
        INPUT_TEXT_ARG_KEYS.forEach { key ->
            parameterTemplates[stepIndex to key]?.let { return it }
        }
        return firstPresent(args["text"], args["content"], args["value"])
    }

    private fun jsonSchemaType(type: String): String =
        when (type.lowercase()) {
            "int", "integer" -> "integer"
            "number", "float", "double" -> "number"
            "bool", "boolean" -> "boolean"
            "array", "object" -> type.lowercase()
            else -> "string"
        }

    private fun isTruthy(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.trim().equals("true", ignoreCase = true)
            else -> false
        }

    private fun parameterNameForInputStep(step: Map<String, Any?>, index: Int): String {
        val rawLabel = firstNonBlank(
            step["parameter_name"],
            step["input_name"],
            step["title"],
        )
        val label = rawLabel
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(32)
        return when {
            label.endsWith("_text") -> label
            label.isNotBlank() -> "${label}_text"
            index == 0 -> "input_text"
            else -> "input_text_${index + 1}"
        }
    }

    private fun uniqueParameterName(baseName: String, usedNames: Set<String>): String {
        val normalized = baseName
            .ifBlank { "input_text" }
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "input_text" }
        if (normalized !in usedNames) return normalized
        var suffix = 2
        while ("${normalized}_$suffix" in usedNames) {
            suffix += 1
        }
        return "${normalized}_$suffix"
    }

    private fun firstPresent(vararg values: Any?): Any? {
        for (value in values) {
            if (value == null) continue
            if (value is String && value.trim().isEmpty()) continue
            return value
        }
        return null
    }

    private fun nullableMap(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            pairs.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
        }
    }

    private val INPUT_TEXT_ACTIONS = setOf(OobActionCodec.ACTION_INPUT_TEXT)
    private val INPUT_TEXT_ARG_KEYS = listOf("text", "content", "value")
    private val EXECUTION_ARG_BINDING_REGEX = Regex("""^\$\.execution\.steps\[(\d+)]\.args\.([A-Za-z0-9_]+)$""")
}
