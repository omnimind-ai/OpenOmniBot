package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.androidPrivilegedReplayAction
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.androidPrivilegedReplayArgs
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.asBoolean
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.asMap
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.beforeObservationForCard
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.extractArgs
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.extractResult
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.firstNonBlank
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.isEmptyJsonValue
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.jsonSafe
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.jsonSafeMap
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.nullableMap
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.toJson
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.toolNameForCard

object RunLogReusableFunctionCompiler {
    fun compile(record: InternalRunLogRecord): Map<String, Any?>? {
        val replayableCards = record.cards.filter(::isSuccessfulCard)
        val compileCards = RunLogStartupBridgeCleaner.dropTransientStartupBridgeCards(replayableCards)
        val droppedStartupBridgeCount = replayableCards.size - compileCards.size
        val hasRecordedReplayStep = compileCards.any(RunLogStartupBridgeCleaner::hasRecordedReplayStep)
        val rawSteps = compileCards
            .mapIndexedNotNull { index, card ->
                cardToStep(
                    card = card,
                    skipPerceptionTools = hasRecordedReplayStep,
                    nextReplayableCard = compileCards
                        .asSequence()
                        .drop(index + 1)
                        .firstOrNull(RunLogStartupBridgeCleaner::hasRecordedReplayStep),
                )
            }
        val replaySteps = RunLogReplayStepNoiseNormalizer.normalize(rawSteps)
        val initialStepCleanup = RunLogStartupBridgeCleaner.normalizeInitialOpenAppStep(
            replayableCards,
            replaySteps,
        )
        val injectedLaunchBridgeDroppedCount = initialStepCleanup.injectedLaunchBridgeDroppedCount
        val steps = initialStepCleanup.steps
            .mapIndexed { index, rawStep ->
                linkedMapOf<String, Any?>().apply {
                    putAll(rawStep)
                    put("id", "step_${index + 1}")
                    put("index", index)
                }
            }

        if (steps.isEmpty()) return null

        val parameterization = RunLogReusableFunctionParameterizer.parameterize(steps)
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
            "parameters" to parameterization.parameters,
            "actions" to parameterization.actions,
            "metadata" to linkedMapOf(
                "source" to "run_log_import",
                "run_id" to record.runId,
                "source_run_ids" to listOf(record.runId),
                "original_goal" to record.goal,
                "goal" to goal.ifBlank { name },
                "step_count" to parameterization.actions.size,
                "oob_parameter_bindings" to parameterization.parameterBindings,
                "oob_legacy_parameters" to parameterization.legacyParameters.takeIf { it.isNotEmpty() },
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
                    "parameter_count" to parameterization.legacyParameters.size,
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
            OobActionCodec.canonicalActionForName(normalizedToolName) != null -> {
                val replayAction = requireNotNull(
                    OobActionCodec.canonicalActionForName(normalizedToolName)
                )
                val replayArgs = OobActionCodec.argsForStep(
                    mapOf(
                        "tool" to toolName,
                        "args" to args,
                    )
                )
                omniflowStep(
                    title = cleanStepTitle(title, replayAction, replayArgs),
                    replayAction = replayAction,
                    sourceToolName = normalizedToolName,
                    args = replayArgs,
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
        val action = OobActionCodec.canonicalActionForName(toolName)
            ?: OobActionCodec.normalizeName(toolName)
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
            "input_text" -> if (target.isNotBlank()) "$action: $target" else action
            "swipe" -> {
                val direction = firstNonBlank(args["direction"], args["scroll_direction"])
                if (direction.isNotBlank()) "$action: $direction" else action
            }
            "press_key" -> {
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
                    OobActionCodec.canonicalActionForName(rawToolName)
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
            "(original tool: $toolName, args: ${toJson(args)})"
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

}
