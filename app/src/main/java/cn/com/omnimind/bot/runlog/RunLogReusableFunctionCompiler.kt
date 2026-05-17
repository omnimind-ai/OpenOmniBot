package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import com.google.gson.GsonBuilder

object RunLogReusableFunctionCompiler {
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    fun compile(record: InternalRunLogRecord): Map<String, Any?>? {
        val hasRecordedReplayStep = record.cards.any(::hasRecordedReplayStep)
        val steps = record.cards
            .filter { card -> card["success"] != false }
            .mapNotNull { card ->
                cardToStep(card, skipPerceptionTools = hasRecordedReplayStep)
            }
            .mapIndexed { index, rawStep ->
                linkedMapOf<String, Any?>().apply {
                    putAll(rawStep)
                    put("id", "step_${index + 1}")
                    put("index", index)
                }
            }

        if (steps.isEmpty()) return null

        val now = System.currentTimeMillis().toString()
        val functionId = deriveFunctionId(record)
        val goal = record.goal.ifBlank { record.operationDescription }
        val name = record.operationDescription.ifBlank { goal }.ifBlank { functionId }.take(80)
        val capabilities = executionCapabilities(steps)
        return linkedMapOf<String, Any?>(
            "schema_version" to "oob.reusable_function.v1",
            "function_id" to functionId,
            "name" to name,
            "description" to goal.ifBlank { name },
            "parameters" to emptyList<Any>(),
            "source" to linkedMapOf(
                "kind" to "run_log",
                "run_id" to record.runId,
                "goal" to record.goal,
                "tool_name" to record.toolName,
                "converted_at" to now,
                "converter" to "native_run_log_reusable_function_compiler",
            ),
            "execution" to linkedMapOf(
                "kind" to "tool_sequence",
                "runner" to "oob_tool_sequence",
                "entrypoint" to "execute",
                "capabilities" to capabilities,
                "fallback_runner" to "oob.agent.run",
                "steps" to steps,
                "step_count" to steps.size,
                "omniflow_step_count" to capabilities["omniflow_step_count"],
                "agent_step_count" to capabilities["agent_step_count"],
                "requires_agent_fallback" to capabilities["requires_agent_fallback"],
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to now,
                "updated_at" to now,
                "runner" to "oob_agent_reusable_function",
                "storage" to "workspace",
            ),
        )
    }

    private fun hasRecordedReplayStep(card: Map<String, Any?>): Boolean {
        val toolName = toolNameForCard(card)
        if (RunLogReplayPolicy.omniflowActionForToolName(toolName) != null) {
            return true
        }
        if (RunLogReplayPolicy.normalizeToolName(toolName) != "android_privileged_action") {
            return false
        }
        val args = asMap(extractArgs(card))
        val action = firstNonBlank(args["action"], args["omniflow_action"])
        return RunLogReplayPolicy.omniflowActionForToolName(action) != null
    }

    private fun cardToStep(
        card: Map<String, Any?>,
        skipPerceptionTools: Boolean,
    ): Map<String, Any?>? {
        val toolName = toolNameForCard(card).ifBlank { "unknown_tool" }
        val normalizedToolName = RunLogReplayPolicy.normalizeToolName(toolName)
        val title = firstNonBlank(
            asMap(card["header"])["title"],
            card["title"],
            card["summary"],
            card["operation_description"],
            card["operationDescription"],
            toolName,
        )
        val args = jsonSafeMap(extractArgs(card))
        val result = jsonSafe(extractResult(card))
        val sourceContext = sourceContextForCard(card, args)

        return when {
            RunLogReplayPolicy.shouldSkipTool(normalizedToolName) -> null
            RunLogReplayPolicy.isPerceptionTool(normalizedToolName) && skipPerceptionTools -> null
            normalizedToolName == "android_privileged_action" -> {
                val action = firstNonBlank(args["action"], args["omniflow_action"])
                    .trim()
                    .lowercase()
                if (action !in RunLogReplayPolicy.omniflowActions) return null
                omniflowStep(
                    title = title,
                    toolName = action,
                    action = action,
                    args = args.filterKeys { it != "action" && it != "omniflow_action" },
                    sourceContext = sourceContext,
                )
            }
            normalizedToolName in RunLogReplayPolicy.omniflowActions -> {
                omniflowStep(
                    title = title,
                    toolName = normalizedToolName,
                    action = normalizedToolName,
                    args = args,
                    sourceContext = sourceContext,
                )
            }
            RunLogReplayPolicy.isAgentTool(normalizedToolName) -> {
                val fallbackPrompt = agentFallbackPrompt(title, toolName, args)
                nullableMap(
                    "title" to title,
                    "kind" to "agent_call",
                    "tool" to toolName,
                    "callable_tool" to "oob.agent.run",
                    "executor" to "agent",
                    "scriptable" to false,
                    "args" to args,
                    "tool_binding" to linkedMapOf(
                        "kind" to "agent_replan",
                        "name" to toolName,
                        "callable_tool" to "oob.agent.run",
                    ),
                    "agent_call" to linkedMapOf(
                        "tool" to "oob.agent.run",
                        "args" to linkedMapOf(
                            "prompt" to fallbackPrompt,
                            "original_tool" to toolName,
                            "original_args" to args,
                        ),
                        "reason" to RunLogReplayPolicy.agentStepReason(normalizedToolName),
                    ),
                    "fallback" to linkedMapOf(
                        "kind" to "agent_replan",
                        "tool" to "oob.agent.run",
                        "prompt" to fallbackPrompt,
                    ),
                    "observed_result" to result.takeUnless(::isEmptyJsonValue),
                )
            }
            else -> {
                nullableMap(
                    "title" to title,
                    "kind" to "tool_call",
                    "executor" to "tool",
                    "scriptable" to true,
                    "callable_tool" to toolName,
                    "tool" to toolName,
                    "args" to args,
                    "observed_result" to result.takeUnless(::isEmptyJsonValue),
                )
            }
        }
    }

    private fun omniflowStep(
        title: String,
        toolName: String,
        action: String,
        args: Map<String, Any?>,
        sourceContext: Map<String, Any?>,
    ): Map<String, Any?> {
        val usesCoordinateHook =
            action in RunLogReplayPolicy.coordinateActions && sourceContext.isNotEmpty()
        return nullableMap(
            "title" to title,
            "kind" to "omniflow_action",
            "executor" to "omniflow",
            "omniflow_action" to action,
            "local_action" to action,
            "model_free" to true,
            "scriptable" to true,
            "tool" to toolName,
            "callable_tool" to action,
            "args" to args,
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "coordinate_hook" to if (usesCoordinateHook) "omniflow" else null,
        )
    }

    private fun toolNameForCard(card: Map<String, Any?>): String {
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

    private fun extractArgs(card: Map<String, Any?>): Any? {
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

    private fun extractResult(card: Map<String, Any?>): Any? {
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

    private fun sourceContextForCard(
        card: Map<String, Any?>,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val explicit = asMap(args["source_context"]).ifEmpty { asMap(card["source_context"]) }
        if (explicit.isNotEmpty()) return explicit
        val before = asMap(card["before"])
        val sourceXml = firstNonBlank(before["observation_xml"], before["observationXml"])
        if (sourceXml.isEmpty()) return emptyMap()
        val sourceAction = linkedMapOf<String, Any?>("tool" to toolNameForCard(card))
        for (key in listOf(
            "target_description",
            "targetDescription",
            "x",
            "y",
            "x1",
            "y1",
            "x2",
            "y2",
            "duration",
            "duration_ms",
            "durationMs",
        )) {
            if (args.containsKey(key) && args[key] != null) {
                sourceAction[key] = args[key]
            }
        }
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to sourceXml,
                "require_unique_action_signature" to false,
            ),
            "action" to sourceAction,
        )
    }

    private fun agentFallbackPrompt(
        title: String,
        toolName: String,
        args: Map<String, Any?>,
    ): String {
        return "Re-plan this step from the current screen: $title " +
            "(original tool: $toolName, args: ${gson.toJson(args)})"
    }

    private fun executionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> {
        return linkedMapOf(
            "scriptable_step_count" to steps.count { it["scriptable"] == true },
            "model_free_step_count" to steps.count { it["model_free"] == true },
            "omniflow_step_count" to steps.count { it["executor"] == "omniflow" },
            "agent_step_count" to steps.count { it["executor"] == "agent" },
            "requires_agent_fallback" to steps.any { it["executor"] == "agent" },
        )
    }

    private fun deriveFunctionId(record: InternalRunLogRecord): String {
        val base = record.toolName.ifBlank { record.goal }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "cmd" }
        val suffix = record.runId.takeLast(8).replace(Regex("[^A-Za-z0-9]"), "")
        return "oob_cmd_${base}_$suffix"
    }

    private fun asMap(value: Any?): Map<String, Any?> {
        val decoded = decodeJsonIfNeeded(value)
        if (decoded !is Map<*, *>) return emptyMap()
        return decoded.entries.associate { (key, item) -> key.toString() to item }
    }

    private fun jsonSafeMap(value: Any?): Map<String, Any?> = asMap(jsonSafe(value))

    private fun jsonSafe(value: Any?): Any? {
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

    private fun decodeJsonIfNeeded(value: Any?): Any? {
        if (value !is String) return value
        val text = value.trim()
        if (text.isEmpty()) return value
        if (!text.startsWith("{") && !text.startsWith("[")) return value
        return runCatching { gson.fromJson(text, Any::class.java) }.getOrElse { value }
    }

    private fun firstPresent(vararg values: Any?): Any? {
        for (value in values) {
            if (value == null) continue
            if (value is String && value.trim().isEmpty()) continue
            return value
        }
        return null
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun nullableMap(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            pairs.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
        }
    }

    private fun isEmptyJsonValue(value: Any?): Boolean {
        return when (value) {
            null -> true
            is String -> value.trim().isEmpty()
            is Map<*, *> -> value.isEmpty()
            is Iterable<*> -> !value.iterator().hasNext()
            else -> false
        }
    }
}
