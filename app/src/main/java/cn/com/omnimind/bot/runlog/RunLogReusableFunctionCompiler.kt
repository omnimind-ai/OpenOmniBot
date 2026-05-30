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
        val compileCards = dropTransientStartupBridgeCards(replayableCards)
        val droppedStartupBridgeCount = replayableCards.size - compileCards.size
        val hasRecordedReplayStep = compileCards.any(::hasRecordedReplayStep)
        val rawSteps = compileCards
            .mapIndexedNotNull { index, card ->
                cardToStep(
                    card = card,
                    skipPerceptionTools = hasRecordedReplayStep,
                    nextReplayableCard = compileCards
                        .asSequence()
                        .drop(index + 1)
                        .firstOrNull(::hasRecordedReplayStep),
                )
            }
        val replaySteps = dropDuplicateTextInputSteps(rawSteps)
        val stepsWithStart = prepareInitialOpenAppStep(
            prependInitialOpenAppStepIfNeeded(replayableCards, replaySteps)
        )
        val stepsAfterInitialLaunchBridgeDrop = dropRedundantInjectedLaunchBridgeStep(stepsWithStart)
        val injectedLaunchBridgeDroppedCount =
            stepsWithStart.size - stepsAfterInitialLaunchBridgeDrop.size
        val steps = stepsAfterInitialLaunchBridgeDrop
            .mapIndexed { index, rawStep ->
                linkedMapOf<String, Any?>().apply {
                    putAll(rawStep)
                    put("id", "step_${index + 1}")
                    put("index", index)
                }
            }

        if (steps.isEmpty()) return null

        val legacyParameters = inferParameters(steps)
        val parameters = canonicalParameterSchema(legacyParameters)
        val actions = canonicalActionsForSteps(steps, legacyParameters)
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
            "actions" to actions,
            "metadata" to linkedMapOf(
                "source" to "run_log_import",
                "run_id" to record.runId,
                "source_run_ids" to listOf(record.runId),
                "original_goal" to record.goal,
                "goal" to goal.ifBlank { name },
                "step_count" to actions.size,
                "oob_parameter_bindings" to legacyParameters.mapNotNull { parameter ->
                    val nameValue = firstNonBlank(parameter["name"])
                    val bindings = parameter["bindings"] as? List<*> ?: emptyList<Any?>()
                    if (nameValue.isBlank() || bindings.isEmpty()) return@mapNotNull null
                    linkedMapOf(
                        "name" to nameValue,
                        "bindings" to bindings,
                    )
                },
                "oob_legacy_parameters" to legacyParameters.takeIf { it.isNotEmpty() },
                "oob_compat_execution" to true,
            ).filterValues { it != null },
            "source" to linkedMapOf(
                "kind" to "run_log",
                "run_id" to record.runId,
                "goal" to record.goal,
                "tool_name" to record.toolName,
                "card_count" to record.cards.size,
                "replayable_card_count" to replayableCards.size,
                "compiled_replayable_card_count" to compileCards.size,
                "transient_startup_bridge_dropped_count" to droppedStartupBridgeCount,
                "injected_launch_bridge_step_dropped_count" to injectedLaunchBridgeDroppedCount,
                "converted_at" to now,
                "converter" to "native_run_log_reusable_function_builder",
                "parameter_inference" to linkedMapOf(
                    "strategy" to "deterministic_input_text_bindings",
                    "parameter_count" to legacyParameters.size,
                ),
            ),
            "execution" to linkedMapOf(
                "kind" to "tool_sequence",
                "runner" to "oob_tool_sequence",
                "entrypoint" to "execute",
                "capabilities" to capabilities,
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

    /**
     * Drops repeated text-input steps emitted by noisy accessibility text events.
     *
     * @param steps Already canonicalized replay steps in source order.
     * @return A step list where consecutive duplicate text input on the same
     * target is represented once.
     */
    private fun dropDuplicateTextInputSteps(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val output = mutableListOf<Map<String, Any?>>()
        steps.forEach { step ->
            val previous = output.lastOrNull()
            if (previous != null && isDuplicateTextInputStep(previous, step)) {
                return@forEach
            }
            output += step
        }
        return output
    }

    /**
     * Checks whether two adjacent steps describe the same final text value.
     *
     * @param previous Earlier kept step.
     * @param current Candidate step immediately after [previous].
     * @return True when both are input-text actions with identical text and a
     * compatible target signature.
     */
    private fun isDuplicateTextInputStep(
        previous: Map<String, Any?>,
        current: Map<String, Any?>,
    ): Boolean {
        if (replayActionForStep(previous) !in INPUT_TEXT_ACTIONS) return false
        if (replayActionForStep(current) !in INPUT_TEXT_ACTIONS) return false
        val previousText = textInputValue(previous)
        val currentText = textInputValue(current)
        if (previousText.isBlank() || previousText != currentText) return false
        val previousTarget = textInputTargetSignature(previous)
        val currentTarget = textInputTargetSignature(current)
        return previousTarget.isNotBlank() && previousTarget == currentTarget
    }

    /**
     * Resolves the canonical replay action carried by a compiled step.
     *
     * @param step Compiled execution step.
     * @return Canonical action name, preserving unknown tool names for callers
     * that need to compare non-OmniFlow steps.
     */
    private fun replayActionForStep(step: Map<String, Any?>): String {
        val rawAction = firstNonBlank(
            step["omniflow_action"],
            step["local_action"],
            step["tool"],
            step["callable_tool"],
        )
        return RunLogReplayPolicy.omniflowActionForToolName(rawAction)
            ?: RunLogReplayPolicy.normalizeToolName(rawAction)
    }

    /**
     * Reads the text payload from a compiled input step.
     *
     * @param step Compiled execution step with an args map.
     * @return The final text value that replay would input.
     */
    private fun textInputValue(step: Map<String, Any?>): String {
        val args = asMap(step["args"])
        return firstNonBlank(args["text"], args["content"], args["value"])
    }

    /**
     * Builds a stable target signature for de-duplicating adjacent input steps.
     *
     * @param step Compiled execution step with args/source_context metadata.
     * @return A compact target signature, or blank when the target is unknown.
     */
    private fun textInputTargetSignature(step: Map<String, Any?>): String {
        val args = asMap(step["args"])
        val sourceContext = asMap(step["source_context"])
        val action = asMap(sourceContext["action"])
        return firstNonBlank(
            args["node_resource_id"],
            action["node_resource_id"],
            args["selector"],
            action["selector"],
            args["bounds"],
            action["bounds"],
            args["target_description"],
            args["targetDescription"],
            action["target_description"],
            action["targetDescription"],
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

    private fun prepareInitialOpenAppStep(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.isEmpty()) return steps
        val first = steps.first()
        val firstAction = RunLogReplayPolicy.omniflowActionForToolName(
            firstNonBlank(
                first["omniflow_action"],
                first["local_action"],
                first["tool"],
                first["callable_tool"],
            )
        ) ?: RunLogReplayPolicy.normalizeToolName(
            firstNonBlank(first["tool"], first["callable_tool"])
        )
        if (firstAction != "open_app") return steps
        val args = jsonSafeMap(first["args"])
        val packageName = firstNonBlank(args["package_name"], args["packageName"])
        if (!isLaunchableInitialPackageCandidate(packageName)) return steps
        val preparedFirst = linkedMapOf<String, Any?>().apply {
            putAll(first)
            put(
                "args",
                linkedMapOf<String, Any?>().apply {
                    putAll(args)
                    put("package_name", packageName)
                    putIfAbsent("reset_task", true)
                    putIfAbsent("launch_mode", "fresh_task")
                }
            )
            putIfAbsent("route_note", "initial_open_app_fresh_launch")
        }
        return listOf(preparedFirst) + steps.drop(1)
    }

    private fun dropRedundantInjectedLaunchBridgeStep(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val first = steps.first()
        if (replayActionForStep(first) != "open_app") return steps
        if (first["route_note"] != "injected_initial_package_from_runlog") return steps

        val packageName = firstNonBlank(
            asMap(first["args"])["package_name"],
            asMap(first["args"])["packageName"],
        )
        if (!isLaunchableInitialPackageCandidate(packageName)) return steps

        val candidate = steps[1]
        if (replayActionForStep(candidate) != "click") return steps
        if (!isInjectedLaunchBridgeClick(candidate, packageName, steps.drop(2))) {
            return steps
        }
        return listOf(first) + steps.drop(2)
    }

    private fun isInjectedLaunchBridgeClick(
        step: Map<String, Any?>,
        launchedPackage: String,
        followingSteps: List<Map<String, Any?>>,
    ): Boolean {
        val sourceContext = asMap(step["source_context"])
        val srcCtx = asMap(sourceContext["src_ctx"])
        val rawSourcePackage = firstNonBlank(
            srcCtx["package_name"],
            srcCtx["packageName"],
        )
        val sourceXml = firstNonBlank(srcCtx["page"], srcCtx["xml"])
        val effectiveSourcePackage = RunLogPagePackageInference.effectivePackage(
            rawSourcePackage,
            sourceXml,
        )
        val followingUsesLaunchedPackage = followingSteps.any { following ->
            stepMentionsPackage(following, launchedPackage)
        }
        if (!followingUsesLaunchedPackage) return false

        if (isLauncherPackage(rawSourcePackage)) {
            return true
        }
        return effectiveSourcePackage == launchedPackage &&
            isSparseLaunchSurface(sourceXml, launchedPackage)
    }

    private fun stepMentionsPackage(
        step: Map<String, Any?>,
        packageName: String,
    ): Boolean {
        val args = asMap(step["args"])
        val sourceContext = asMap(step["source_context"])
        val srcCtx = asMap(sourceContext["src_ctx"])
        val dstCtx = asMap(sourceContext["dst_ctx"])
        return listOf(
            firstNonBlank(args["package_name"], args["packageName"]),
            effectivePackageForContext(srcCtx),
            effectivePackageForContext(dstCtx),
        ).any { it == packageName }
    }

    private fun effectivePackageForContext(context: Map<String, Any?>): String {
        val rawPackage = firstNonBlank(context["package_name"], context["packageName"])
        val xml = firstNonBlank(context["page"], context["xml"])
        return RunLogPagePackageInference.effectivePackage(rawPackage, xml)
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (normalized.contains("launcher")) return true
        return normalized in setOf(
            "com.bbk.launcher2",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.sec.android.app.launcher",
        )
    }

    private fun isSparseLaunchSurface(xml: String, packageName: String): Boolean {
        if (xml.isBlank()) return false
        val vector = OobPageVectorSet.encode(xml, packageName) ?: return false
        return vector.packageName == packageName &&
            vector.displayTextCount == 0 &&
            vector.elementCount <= 4 &&
            vector.actionableCount <= 2
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
        val args = asMap(step["args"])
        val rawTool = firstNonBlank(
            step["omniflow_action"],
            step["local_action"],
            step["tool"],
            step["callable_tool"],
        )
        val action = RunLogReplayPolicy.omniflowActionForToolName(rawTool)
            ?: RunLogReplayPolicy.normalizeToolName(rawTool)
        val description = firstNonBlank(step["title"], step["summary"]).takeIf { it.isNotBlank() }
        val sourceContext = asMap(step["source_context"]).ifEmpty { asMap(args["source_context"]) }
        return when {
            action == "click" -> canonicalPointAction(
                type = "click",
                args = args,
                description = description,
                sourceContext = sourceContext,
            )
            action == "long_press" -> canonicalPointAction(
                type = "long_press",
                args = args,
                description = description,
                sourceContext = sourceContext,
            )
            action == "type" || action == "input_text" -> nullableMap(
                "type" to "input_text",
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
            action == "scroll" || action == "swipe" -> {
                val target = coordinateTarget(args)
                nullableMap(
                    "type" to "swipe",
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
            action == "open_app" -> nullableMap(
                "type" to "open_app",
                "packageName" to firstNonBlank(args["package_name"], args["packageName"]),
                "description" to description,
            )
            action == "press_home" -> nullableMap(
                "type" to "press_key",
                "key" to "home",
                "description" to description,
            )
            action == "press_back" -> nullableMap(
                "type" to "press_key",
                "key" to "back",
                "description" to description,
            )
            action == "hot_key" || action == "press_key" -> nullableMap(
                "type" to "press_key",
                "key" to firstNonBlank(args["key"], args["hotkey"], args["hot_key"]),
                "description" to description,
            )
            action == "finished" -> nullableMap(
                "type" to "finished",
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
                    "type" to "call_function",
                    "params" to nullableMap(
                        "node_id" to firstNonBlank(args["node_id"], args["nodeId"]),
                        "function_name" to functionId,
                        "function_id" to functionId,
                        "arguments" to asMap(args["arguments"]).ifEmpty { asMap(args["args"]) },
                    ),
                    "description" to description,
                )
            }
            RunLogReplayPolicy.isOmniflowGraphTool(action) -> nullableMap(
                "type" to "click_node",
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
                "toolName" to firstNonBlank(step["callable_tool"], step["tool"], rawTool),
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

    private fun dropTransientStartupBridgeCards(
        replayableCards: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (replayableCards.size < 2) return replayableCards
        val output = mutableListOf<Map<String, Any?>>()
        var concreteActionIndex = 0
        replayableCards.forEachIndexed { index, card ->
            val isConcrete = hasRecordedReplayStep(card)
            if (isConcrete &&
                shouldDropTransientStartupBridgeCard(
                    cards = replayableCards,
                    cardIndex = index,
                    concreteActionIndex = concreteActionIndex,
                )
            ) {
                concreteActionIndex += 1
                return@forEachIndexed
            }
            output += card
            if (isConcrete) {
                concreteActionIndex += 1
            }
        }
        return output
    }

    private fun shouldDropTransientStartupBridgeCard(
        cards: List<Map<String, Any?>>,
        cardIndex: Int,
        concreteActionIndex: Int,
    ): Boolean {
        if (concreteActionIndex > 1) return false
        val card = cards[cardIndex]
        if (isManualRecordingCard(card)) return false
        val action = replayActionForCard(card) ?: return false
        if (action != "click") return false

        val args = jsonSafeMap(extractArgs(card))
        val target = firstNonBlank(
            args["target_description"],
            args["targetDescription"],
            args["label"],
            asMap(card["header"])["title"],
            card["title"],
            card["summary"],
        )
        if (target.isBlank()) return false

        val sourceXml = observationXml(beforeObservationForCard(card))
        val afterXml = observationXml(afterObservationForCard(card))
        if (sourceXml.isBlank() || afterXml.isBlank()) return false

        val nextCard = cards.asSequence()
            .drop(cardIndex + 1)
            .firstOrNull(::hasRecordedReplayStep)
            ?: return false
        val nextBeforeXml = observationXml(beforeObservationForCard(nextCard))
        if (nextBeforeXml.isBlank()) return false

        if (!pagesAreSameTransition(afterXml, nextBeforeXml)) return false
        if (xmlContainsTarget(sourceXml, target)) return false
        if (!xmlContainsTarget(afterXml, target) && !xmlContainsTarget(nextBeforeXml, target)) {
            return false
        }
        if (!hasCompatibleTransitionPackage(card, nextCard, afterXml, nextBeforeXml)) return false
        return isTransientStartupSource(sourceXml, nextBeforeXml)
    }

    private fun isManualRecordingCard(card: Map<String, Any?>): Boolean {
        return firstNonBlank(card["compile_kind"], card["compileKind"]) == "manual_recording" ||
            firstNonBlank(card["source"], asMap(card["header"])["source"]) == "human_takeover"
    }

    private fun replayActionForCard(card: Map<String, Any?>): String? {
        val toolName = toolNameForCard(card)
        val normalizedToolName = RunLogReplayPolicy.normalizeToolName(toolName)
        val args = jsonSafeMap(extractArgs(card))
        return if (normalizedToolName == "android_privileged_action") {
            androidPrivilegedReplayAction(args)
        } else {
            RunLogReplayPolicy.omniflowActionForToolName(toolName)
        }
    }

    private fun afterObservationForCard(card: Map<String, Any?>): Map<String, Any?> {
        return asMap(card["after"])
            .ifEmpty { asMap(card["observation_after_act"]) }
            .ifEmpty { asMap(card["after_observation"]) }
    }

    private fun observationXml(observation: Map<String, Any?>): String {
        return firstNonBlank(
            observation["observation_xml"],
            observation["observationXml"],
            observation["xml"],
            observation["page"],
        )
    }

    private fun pagesAreSameTransition(firstXml: String, secondXml: String): Boolean {
        if (firstXml == secondXml) return true
        val firstVector = OobPageVectorSet.encode(firstXml) ?: return false
        val secondVector = OobPageVectorSet.encode(secondXml) ?: return false
        return OobPageVectorSet.cosine(firstVector.vector, secondVector.vector) >=
            TRANSIENT_BRIDGE_SAME_PAGE_SCORE
    }

    private fun xmlContainsTarget(xml: String, target: String): Boolean {
        val tokens = meaningfulTargetTokens(target)
        if (tokens.isEmpty()) return false
        val haystack = xml.lowercase()
        return tokens.any { token -> haystack.contains(token) }
    }

    private fun meaningfulTargetTokens(target: String): List<String> {
        return target
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { token ->
                token.length >= 3 && token !in GENERIC_TARGET_TOKENS
            }
            .distinct()
    }

    private fun hasCompatibleTransitionPackage(
        card: Map<String, Any?>,
        nextCard: Map<String, Any?>,
        afterXml: String,
        nextBeforeXml: String,
    ): Boolean {
        val afterObservation = afterObservationForCard(card)
        val nextBefore = beforeObservationForCard(nextCard)
        val afterPackage = RunLogPagePackageInference.effectivePackage(
            firstNonBlank(afterObservation["package_name"], afterObservation["packageName"]),
            afterXml,
        )
        val nextPackage = RunLogPagePackageInference.effectivePackage(
            firstNonBlank(nextBefore["package_name"], nextBefore["packageName"]),
            nextBeforeXml,
        )
        return afterPackage.isBlank() ||
            nextPackage.isBlank() ||
            afterPackage == nextPackage
    }

    private fun isTransientStartupSource(sourceXml: String, nextSourceXml: String): Boolean {
        val sourceVector = OobPageVectorSet.encode(sourceXml) ?: return false
        val nextVector = OobPageVectorSet.encode(nextSourceXml) ?: return false
        val sourceStrength = observationStrength(sourceVector)
        val nextStrength = observationStrength(nextVector)
        val compactPromptLike = sourceVector.elementCount <= TRANSIENT_BRIDGE_SOURCE_MAX_ELEMENTS &&
            sourceVector.displayTextCount <= TRANSIENT_BRIDGE_SOURCE_MAX_TEXTS &&
            sourceVector.actionableCount <= TRANSIENT_BRIDGE_SOURCE_MAX_ACTIONABLES
        val nextIsRicher = nextStrength >= sourceStrength + TRANSIENT_BRIDGE_MIN_STRENGTH_GAIN
        return compactPromptLike && nextIsRicher
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
            "replay_engine" to if (utg.isNotEmpty()) "omniflow_utg" else null,
            "utg" to utg.takeIf { it.isNotEmpty() },
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
            "node_resource_id",
            "nodeResourceId",
            "resource_id",
            "resourceId",
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
                value != null && value.trim().isNotEmpty()
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

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
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
    private val EXECUTION_ARG_BINDING_REGEX = Regex("""^\$\.execution\.steps\[(\d+)]\.args\.([A-Za-z0-9_]+)$""")
    private val PACKAGE_NAME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
    private const val TRANSIENT_BRIDGE_SAME_PAGE_SCORE = 0.92f
    private const val TRANSIENT_BRIDGE_SOURCE_MAX_ELEMENTS = 8
    private const val TRANSIENT_BRIDGE_SOURCE_MAX_TEXTS = 3
    private const val TRANSIENT_BRIDGE_SOURCE_MAX_ACTIONABLES = 3
    private const val TRANSIENT_BRIDGE_MIN_STRENGTH_GAIN = 8
    private const val SPARSE_LAUNCH_SURFACE_MAX_ELEMENTS = 4
    private const val SPARSE_LAUNCH_SURFACE_MAX_ACTIONABLES = 2
    private val KNOWN_LAUNCHER_PACKAGES = setOf(
        "com.bbk.launcher2",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.sec.android.app.launcher",
    )
    private val GENERIC_TARGET_TOKENS = setOf(
        "click",
        "clicked",
        "tap",
        "tapped",
        "press",
        "button",
        "tab",
        "view",
        "viewgroup",
        "textview",
        "imageview",
        "imagebutton",
        "layout",
        "frame",
        "item",
        "row",
        "list",
        "menu",
        "icon",
        "the",
        "and",
    )
}
