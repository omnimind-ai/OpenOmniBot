package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import com.google.gson.GsonBuilder

object RunLogReusableFunctionCompiler {
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    fun compile(record: InternalRunLogRecord): Map<String, Any?>? {
        val replayableCards = record.cards.filter(::isSuccessfulCard)
        val hasRecordedReplayStep = replayableCards.any(::hasRecordedReplayStep)
        val rawSteps = replayableCards
            .mapIndexedNotNull { index, card ->
                cardToStep(
                    card = card,
                    skipPerceptionTools = hasRecordedReplayStep,
                    nextReplayableCard = replayableCards.getOrNull(index + 1),
                )
            }
        val stepsWithStart = prependInitialOpenAppStepIfNeeded(replayableCards, rawSteps)
        val steps = stepsWithStart
            .mapIndexed { index, rawStep ->
                linkedMapOf<String, Any?>().apply {
                    putAll(rawStep)
                    put("id", "step_${index + 1}")
                    put("index", index)
                }
            }

        if (steps.isEmpty()) return null

        val parameters = inferParameters(steps)
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
            "parameters" to parameters,
            "source" to linkedMapOf(
                "kind" to "run_log",
                "run_id" to record.runId,
                "goal" to record.goal,
                "tool_name" to record.toolName,
                "card_count" to record.cards.size,
                "replayable_card_count" to replayableCards.size,
                "converted_at" to now,
                "converter" to "native_run_log_reusable_function_builder",
                "parameter_inference" to linkedMapOf(
                    "strategy" to "deterministic_input_text_bindings",
                    "parameter_count" to parameters.size,
                ),
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

    private fun prependInitialOpenAppStepIfNeeded(
        replayableCards: List<Map<String, Any?>>,
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.isEmpty()) return steps
        val firstAction = RunLogReplayPolicy.omniflowActionForToolName(
            firstNonBlank(
                steps.first()["omniflow_action"],
                steps.first()["local_action"],
                steps.first()["tool"],
                steps.first()["callable_tool"],
            )
        ) ?: RunLogReplayPolicy.normalizeToolName(
            firstNonBlank(steps.first()["tool"], steps.first()["callable_tool"])
        )
        if (firstAction == "open_app") return steps

        val packageName = initialReplayPackage(steps, replayableCards) ?: return steps
        val openAppStep = nullableMap(
            "title" to "open_app: $packageName",
            "kind" to "omniflow_action",
            "executor" to "omniflow",
            "omniflow_action" to "open_app",
            "local_action" to "open_app",
            "model_free" to true,
            "scriptable" to true,
            "tool" to "open_app",
            "callable_tool" to "open_app",
            "args" to linkedMapOf(
                "package_name" to packageName,
                "reset_task" to true,
                "launch_mode" to "fresh_task",
            ),
            "route_note" to "injected_initial_package_from_runlog",
        )
        return listOf(openAppStep) + steps
    }

    private fun initialReplayPackage(
        steps: List<Map<String, Any?>>,
        replayableCards: List<Map<String, Any?>>,
    ): String? {
        initialReplayPackageFromSteps(steps)?.let { return it }
        val packageName = replayableCards.asSequence()
            .mapNotNull { card ->
                firstNonBlank(
                    card["package_name"],
                    card["packageName"],
                    asMap(card["before"])["package_name"],
                    asMap(card["before"])["packageName"],
                ).takeIf { it.isNotBlank() }
            }
            .firstOrNull()
            ?: return null
        return packageName.takeIf(::isLaunchableInitialPackageCandidate)
    }

    private fun initialReplayPackageFromSteps(steps: List<Map<String, Any?>>): String? {
        return steps.asSequence()
            .mapNotNull { step ->
                val sourceContext = asMap(step["source_context"])
                val srcCtx = asMap(sourceContext["src_ctx"])
                firstNonBlank(
                    srcCtx["package_name"],
                    srcCtx["packageName"],
                ).takeIf { it.isNotBlank() }
            }
            .firstOrNull(::isLaunchableInitialPackageCandidate)
    }

    private fun isLaunchableInitialPackageCandidate(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (!PACKAGE_NAME_PATTERN.matches(normalized)) return false
        if (normalized.startsWith("cn.com.omnimind.")) return false
        if (normalized == "android") return false
        if (normalized == "com.android.systemui") return false
        if (normalized.startsWith("com.android.inputmethod")) return false
        if (normalized.startsWith("com.google.android.inputmethod")) return false
        if (normalized.contains("launcher", ignoreCase = true)) return false
        if (normalized.startsWith("com.example")) return false
        return true
    }

    private fun inferParameters(steps: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val parameters = mutableListOf<Map<String, Any?>>()
        val usedNames = mutableSetOf<String>()
        steps.forEachIndexed { index, step ->
            val tool = RunLogReplayPolicy.omniflowActionForToolName(
                firstNonBlank(step["omniflow_action"], step["local_action"], step["tool"], step["callable_tool"])
            ) ?: RunLogReplayPolicy.normalizeToolName(firstNonBlank(step["tool"], step["callable_tool"]))
            if (tool !in INPUT_TEXT_ACTIONS) return@forEachIndexed
            val args = asMap(step["args"])
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

    private fun hasRecordedReplayStep(card: Map<String, Any?>): Boolean {
        val toolName = toolNameForCard(card)
        if (RunLogReplayPolicy.omniflowActionForToolName(toolName) != null) {
            return true
        }
        if (RunLogReplayPolicy.normalizeToolName(toolName) != "android_privileged_action") {
            return false
        }
        val args = asMap(extractArgs(card))
        return androidPrivilegedReplayAction(args) != null
    }

    private fun cardToStep(
        card: Map<String, Any?>,
        skipPerceptionTools: Boolean,
        nextReplayableCard: Map<String, Any?>? = null,
    ): Map<String, Any?>? {
        val toolName = toolNameForCard(card).ifBlank { "unknown_tool" }
        val normalizedToolName = RunLogReplayPolicy.normalizeToolName(toolName)
        val args = jsonSafeMap(extractArgs(card))
        val title = cleanStepTitle(
            rawTitle = firstNonBlank(
                asMap(card["header"])["title"],
                card["title"],
                card["summary"],
                card["operation_description"],
                card["operationDescription"],
                toolName,
            ),
            toolName = normalizedToolName,
            args = args,
        )
        val result = jsonSafe(extractResult(card))
        val sourceContext = sourceContextForCard(card, args, nextReplayableCard)
        val utg = jsonSafeMap(card["utg"])

        return when {
            RunLogReplayPolicy.shouldSkipTool(normalizedToolName) -> null
            RunLogReplayPolicy.isPerceptionTool(normalizedToolName) && skipPerceptionTools -> null
            normalizedToolName == "android_privileged_action" -> {
                val action = androidPrivilegedReplayAction(args) ?: return null
                omniflowStep(
                    title = cleanStepTitle(title, action, androidPrivilegedReplayArgs(args)),
                    replayAction = action,
                    sourceToolName = normalizedToolName,
                    args = androidPrivilegedReplayArgs(args),
                    sourceContext = sourceContext,
                    utg = utg,
                )
            }
            RunLogReplayPolicy.omniflowActionForToolName(normalizedToolName) != null -> {
                val replayAction = requireNotNull(
                    RunLogReplayPolicy.omniflowActionForToolName(normalizedToolName)
                )
                omniflowStep(
                    title = cleanStepTitle(title, replayAction, args),
                    replayAction = replayAction,
                    sourceToolName = normalizedToolName,
                    args = args,
                    sourceContext = sourceContext,
                    utg = utg,
                )
            }
            RunLogReplayPolicy.isOmniflowExecutionTool(normalizedToolName) -> {
                omniflowExecutionStep(
                    title = title,
                    toolName = normalizedToolName,
                    args = args,
                    result = result,
                    sourceContext = sourceContext,
                    utg = utg,
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

    private fun cleanStepTitle(
        rawTitle: String,
        toolName: String,
        args: Map<String, Any?>,
    ): String {
        val compactRaw = rawTitle.trim().replace(Regex("\\s+"), " ")
        val fallback = conciseActionTitle(toolName, args)
        val isPseudoToolDump = rawTitle.contains("```") ||
            rawTitle.contains("<arg_key>") ||
            rawTitle.contains("</tool_call>") ||
            rawTitle.contains("<tool_call") ||
            rawTitle.lines().size > 2
        return when {
            isPseudoToolDump -> fallback
            compactRaw.isNotBlank() -> compactRaw.take(120)
            else -> fallback
        }
    }

    private fun conciseActionTitle(
        toolName: String,
        args: Map<String, Any?>,
    ): String {
        val action = RunLogReplayPolicy.omniflowActionForToolName(toolName)
            ?: RunLogReplayPolicy.normalizeToolName(toolName)
        val target = firstNonBlank(
            args["target_description"],
            args["targetDescription"],
            args["label"],
            args["text"],
            args["content"],
            args["value"],
        ).take(80)
        return when (action) {
            "open_app" -> {
                val packageName = firstNonBlank(args["package_name"], args["packageName"])
                if (packageName.isNotBlank()) "open_app: $packageName" else "open_app"
            }
            "click", "long_press" -> {
                if (target.isNotBlank()) {
                    "$action: $target"
                } else {
                    val x = firstNonBlank(args["x"], args["center_x"], args["centerX"])
                    val y = firstNonBlank(args["y"], args["center_y"], args["centerY"])
                    if (x.isNotBlank() && y.isNotBlank()) "$action: ($x, $y)" else action
                }
            }
            "type", "input_text" -> if (target.isNotBlank()) "$action: $target" else action
            "scroll", "swipe" -> {
                val direction = firstNonBlank(args["direction"], args["scroll_direction"])
                if (direction.isNotBlank()) "$action: $direction" else action
            }
            "press_key", "hot_key" -> {
                val key = firstNonBlank(args["key"], args["hotkey"], args["hot_key"])
                if (key.isNotBlank()) "$action: $key" else action
            }
            "finished" -> "finished"
            else -> if (target.isNotBlank()) "$action: $target" else action.ifBlank { "step" }
        }
    }

    private fun omniflowExecutionStep(
        title: String,
        toolName: String,
        args: Map<String, Any?>,
        result: Any?,
        sourceContext: Map<String, Any?>,
        utg: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val isGraphTool = RunLogReplayPolicy.isOmniflowGraphTool(toolName)
        val isFunctionTool = RunLogReplayPolicy.isOmniflowFunctionTool(toolName)
        val isCallTool = RunLogReplayPolicy.isOmniflowToolCallTool(toolName)
        val canonicalToolName = if (isFunctionTool || isCallTool) "call_tool" else toolName
        val canonicalArgs = if (isFunctionTool || isCallTool) {
            canonicalCallToolArgs(toolName, args)
        } else {
            args
        }
        val hasFunctionId = firstNonBlank(
            canonicalArgs["function_id"],
            canonicalArgs["functionId"],
            canonicalArgs["oob_function_id"],
            canonicalArgs["oobFunctionId"],
        ).isNotEmpty()
        val executor = if (isGraphTool || isFunctionTool || hasFunctionId) {
            "omniflow"
        } else {
            "tool"
        }
        return nullableMap(
            "title" to title,
            "kind" to when {
                isGraphTool -> "omniflow_graph"
                isFunctionTool || hasFunctionId -> "omniflow_function"
                else -> "tool_call"
            },
            "executor" to executor,
            "model_free" to true.takeIf { executor == "omniflow" },
            "scriptable" to true,
            "tool" to canonicalToolName,
            "callable_tool" to canonicalToolName,
            "source_tool" to toolName.takeIf { it != canonicalToolName },
            "args" to canonicalArgs,
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "replay_engine" to if (isGraphTool) "omniflow_utg" else null,
            "utg" to utg.takeIf { it.isNotEmpty() },
            "observed_result" to result.takeUnless(::isEmptyJsonValue),
        )
    }

    private fun canonicalCallToolArgs(
        toolName: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(toolName)
        return linkedMapOf<String, Any?>().apply {
            putAll(args)
            val functionId = firstNonBlank(
                args["function_id"],
                args["functionId"],
                args["oob_function_id"],
                args["oobFunctionId"],
            )
            if (functionId.isNotEmpty()) {
                put("function_id", functionId)
            }
            val targetTool = firstNonBlank(
                args["tool_name"],
                args["toolName"],
                args["target_tool"],
                args["targetTool"],
                args["tool"],
            )
            if (targetTool.isNotEmpty() && !RunLogReplayPolicy.isOmniflowFunctionTool(normalizedTool)) {
                put("tool_name", targetTool)
            }
        }
    }

    private fun omniflowStep(
        title: String,
        replayAction: String,
        sourceToolName: String,
        args: Map<String, Any?>,
        sourceContext: Map<String, Any?>,
        utg: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val usesCoordinateHook =
            RunLogReplayPolicy.isCoordinateAction(replayAction) && sourceContext.isNotEmpty()
        val hasRecordedAfterPage = asMap(sourceContext["dst_ctx"])["page"]
            ?.toString()
            ?.trim()
            ?.isNotEmpty() == true
        val postcondition = postconditionFor(
            replayAction = replayAction,
            title = title,
            args = args,
            sourceContext = sourceContext,
            hasRecordedAfterPage = hasRecordedAfterPage,
        )
        return nullableMap(
            "title" to title,
            "kind" to "omniflow_action",
            "executor" to "omniflow",
            "omniflow_action" to replayAction,
            "local_action" to replayAction,
            "model_free" to true,
            "scriptable" to true,
            "tool" to replayAction,
            "callable_tool" to replayAction,
            "source_tool" to sourceToolName.takeIf { it != replayAction },
            "args" to args,
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "coordinate_hook" to if (usesCoordinateHook) "omniflow" else null,
            "postcondition" to postcondition,
            "replay_engine" to if (utg.isNotEmpty()) "omniflow_utg" else null,
            "utg" to utg.takeIf { it.isNotEmpty() },
        )
    }

    private fun postconditionFor(
        replayAction: String,
        title: String,
        args: Map<String, Any?>,
        sourceContext: Map<String, Any?>,
        hasRecordedAfterPage: Boolean,
    ): Map<String, Any?>? {
        if (!hasRecordedAfterPage || replayAction == "finished" || replayAction == "open_app") return null
        val postcondition = linkedMapOf<String, Any?>(
            "kind" to "recorded_after_page_similarity",
            "source" to "run_log_after_observation",
            "min_score" to 0.12,
            "fallback" to "agent",
        )
        if (isSettingsSearchTransition(replayAction, title, args, sourceContext)) {
            postcondition["allow_package_only_for_transient_search"] = true
            postcondition["semantic_fallback"] = "settings_search_transition"
        }
        return postcondition
    }

    private fun isSettingsSearchTransition(
        replayAction: String,
        title: String,
        args: Map<String, Any?>,
        sourceContext: Map<String, Any?>,
    ): Boolean {
        if (replayAction != "click") return false
        val dstCtx = asMap(sourceContext["dst_ctx"])
        val expectedPackage = firstNonBlank(dstCtx["package_name"], dstCtx["packageName"])
        if (expectedPackage != SETTINGS_SEARCH_PACKAGE) return false
        val action = asMap(sourceContext["action"])
        val haystack = listOf(
            title,
            args["target_description"],
            args["targetDescription"],
            args["label"],
            action["target_description"],
            action["targetDescription"],
        ).joinToString(" ").lowercase()
        return haystack.contains("search settings") ||
            haystack.contains("search_action_bar") ||
            haystack.contains("settings search")
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
        nextReplayableCard: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val explicit = asMap(args["source_context"]).ifEmpty { asMap(card["source_context"]) }
        if (explicit.isNotEmpty()) return explicit
        val before = asMap(card["before"])
            .ifEmpty { asMap(card["observation_before_act"]) }
            .ifEmpty { asMap(card["before_observation"]) }
            .ifEmpty { asMap(card["observation"]) }
        val after = asMap(card["after"])
            .ifEmpty { asMap(card["observation_after_act"]) }
            .ifEmpty { asMap(card["after_observation"]) }
        val sourceXml = firstNonBlank(
            before["observation_xml"],
            before["observationXml"],
            before["xml"],
            before["page"],
        )
        if (sourceXml.isEmpty()) return emptyMap()
        val afterXml = firstNonBlank(
            after["observation_xml"],
            after["observationXml"],
            after["xml"],
            after["page"],
        )
        val nextBefore = nextReplayableCard?.let(::beforeObservationForCard).orEmpty()
        val nextBeforeXml = firstNonBlank(
            nextBefore["observation_xml"],
            nextBefore["observationXml"],
            nextBefore["xml"],
            nextBefore["page"],
        )
        val sourcePackage = RunLogPagePackageInference.effectivePackage(
            firstNonBlank(before["package_name"], before["packageName"]),
            sourceXml,
        )
        val repairedAfterXml = if (shouldUseNextBeforeAsAfter(afterXml, nextBeforeXml)) {
            nextBeforeXml
        } else {
            afterXml
        }
        val rawAfterPackage = if (repairedAfterXml == nextBeforeXml && nextBeforeXml.isNotBlank()) {
            firstNonBlank(nextBefore["package_name"], nextBefore["packageName"])
        } else {
            firstNonBlank(after["package_name"], after["packageName"])
        }
        val afterPackage = RunLogPagePackageInference.effectivePackage(rawAfterPackage, repairedAfterXml)
        val rawToolName = toolNameForCard(card)
        val normalizedToolName = RunLogReplayPolicy.normalizeToolName(rawToolName)
        val actionArgs = if (normalizedToolName == "android_privileged_action") {
            androidPrivilegedReplayArgs(args)
        } else {
            args
        }
        val sourceAction = linkedMapOf<String, Any?>(
            "tool" to (
                if (normalizedToolName == "android_privileged_action") {
                    androidPrivilegedReplayAction(args)
                } else {
                    RunLogReplayPolicy.omniflowActionForToolName(rawToolName)
                } ?: rawToolName
                )
        )
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
            if (actionArgs.containsKey(key) && actionArgs[key] != null) {
                sourceAction[key] = actionArgs[key]
            }
        }
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to sourceXml,
                "package_name" to sourcePackage,
                "require_unique_action_signature" to false,
            ),
            "dst_ctx" to linkedMapOf(
                "page" to repairedAfterXml,
                "package_name" to afterPackage,
                "repair_source" to "next_before_observation".takeIf {
                    repairedAfterXml == nextBeforeXml && nextBeforeXml.isNotBlank() && repairedAfterXml != afterXml
                },
            ).filterValues { value ->
                value != null && value.toString().trim().isNotEmpty()
            }.takeIf { it.isNotEmpty() },
            "action" to sourceAction,
        ).filterValues { it != null }
    }

    private fun beforeObservationForCard(card: Map<String, Any?>): Map<String, Any?> {
        return asMap(card["before"])
            .ifEmpty { asMap(card["observation_before_act"]) }
            .ifEmpty { asMap(card["before_observation"]) }
            .ifEmpty { asMap(card["observation"]) }
    }

    private fun shouldUseNextBeforeAsAfter(afterXml: String, nextBeforeXml: String): Boolean {
        if (nextBeforeXml.isBlank()) return false
        if (afterXml.isBlank()) return true
        val afterVector = OobPageVectorSet.encode(afterXml)
        val nextVector = OobPageVectorSet.encode(nextBeforeXml) ?: return false
        if (afterVector == null) return true
        if (!isWeakObservation(afterVector)) return false
        return observationStrength(nextVector) > observationStrength(afterVector)
    }

    private fun isWeakObservation(vector: OobPageVectorSet.PageVector): Boolean {
        return vector.elementCount <= 1 ||
            (vector.actionableCount == 0 && vector.displayTextCount == 0 && vector.focusTargetCount == 0)
    }

    private fun observationStrength(vector: OobPageVectorSet.PageVector): Int {
        return vector.displayTextCount * 4 +
            vector.actionableCount * 3 +
            vector.focusTargetCount * 2 +
            vector.elementCount
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

    private fun isSuccessfulCard(card: Map<String, Any?>): Boolean {
        val header = asMap(card["header"])
        return asBoolean(card["success"]) != false && asBoolean(header["success"]) != false
    }

    private fun asBoolean(value: Any?): Boolean? =
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

    private fun androidPrivilegedReplayAction(args: Map<String, Any?>): String? {
        return RunLogReplayPolicy.omniflowActionForToolName(
            firstNonBlank(args["action"], args["omniflow_action"])
        )
    }

    private fun androidPrivilegedReplayArgs(args: Map<String, Any?>): Map<String, Any?> {
        val nestedArguments = asMap(args["arguments"])
        val flattened = LinkedHashMap<String, Any?>()
        for ((key, value) in args) {
            if (key == "action" || key == "omniflow_action" || key == "arguments") continue
            flattened[key] = value
        }
        flattened.putAll(nestedArguments)
        return flattened
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

    private val INPUT_TEXT_ACTIONS = setOf("type", "input_text")
    private val INPUT_TEXT_ARG_KEYS = listOf("text", "content", "value")
    private val PACKAGE_NAME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
    private const val SETTINGS_SEARCH_PACKAGE = "com.google.android.settings.intelligence"
}
