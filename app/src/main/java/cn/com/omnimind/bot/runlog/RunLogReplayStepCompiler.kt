package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.androidPrivilegedReplayAction
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.androidPrivilegedReplayArgs
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

/**
 * Translates a successful RunLog card into one executable reusable Function step.
 */
internal object RunLogReplayStepCompiler {
    private const val SOURCE_CONTEXT_MODE_COORDINATE_ONLY_NO_XML = "coordinate_only_no_xml"

    fun compileCard(
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
            normalizedToolName == AgentToolNames.ANDROID_PRIVILEGED_ACTION -> {
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
                    "callable_tool" to RunLogReplayPolicy.TOOL_AGENT_RUN,
                    "executor" to RunLogReplayPolicy.EXECUTOR_AGENT,
                    "scriptable" to false,
                    "args" to args,
                    "tool_binding" to linkedMapOf(
                        "kind" to "agent_replan",
                        "name" to toolName,
                        "callable_tool" to RunLogReplayPolicy.TOOL_AGENT_RUN,
                    ),
                    "agent_call" to linkedMapOf(
                        "tool" to RunLogReplayPolicy.TOOL_AGENT_RUN,
                        "args" to linkedMapOf(
                            "prompt" to fallbackPrompt,
                            "original_tool" to toolName,
                            "original_args" to args,
                        ),
                        "reason" to RunLogReplayPolicy.agentStepReason(normalizedToolName),
                    ),
                    "fallback" to linkedMapOf(
                        "kind" to "agent_replan",
                        "tool" to RunLogReplayPolicy.TOOL_AGENT_RUN,
                        "prompt" to fallbackPrompt,
                    ),
                    "observed_result" to result.takeUnless(::isEmptyJsonValue),
                )
            }
            else -> {
                nullableMap(
                    "title" to title,
                    "kind" to "tool_call",
                    "executor" to RunLogReplayPolicy.EXECUTOR_TOOL,
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
        val canonicalToolName = if (isFunctionTool || isCallTool) {
            RunLogReplayPolicy.TOOL_CALL_TOOL
        } else {
            toolName
        }
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
            RunLogReplayPolicy.EXECUTOR_OMNIFLOW
        } else {
            RunLogReplayPolicy.EXECUTOR_TOOL
        }
        return nullableMap(
            "title" to title,
            "kind" to when {
                isGraphTool -> "omniflow_graph"
                isFunctionTool || hasFunctionId -> "omniflow_function"
                else -> "tool_call"
            },
            "executor" to executor,
            "model_free" to true.takeIf { executor == RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
            "scriptable" to true,
            "tool" to canonicalToolName,
            "callable_tool" to canonicalToolName,
            "source_tool" to toolName.takeIf { it != canonicalToolName },
            "args" to canonicalArgs,
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "replay_engine" to if (isGraphTool) RunLogReplayPolicy.REPLAY_ENGINE_OMNIFLOW_UTG else null,
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
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "omniflow_action" to replayAction,
            "local_action" to replayAction,
            "model_free" to true,
            "scriptable" to true,
            "tool" to replayAction,
            "callable_tool" to replayAction,
            "source_tool" to sourceToolName.takeIf { it != replayAction },
            "args" to args,
            "source_context" to sourceContext.takeIf { it.isNotEmpty() },
            "coordinate_hook" to if (usesCoordinateHook) RunLogReplayPolicy.EXECUTOR_OMNIFLOW else null,
            "replay_engine" to if (utg.isNotEmpty()) RunLogReplayPolicy.REPLAY_ENGINE_OMNIFLOW_UTG else null,
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
        val rawToolName = toolNameForCard(card)
        val normalizedToolName = RunLogReplayPolicy.normalizeToolName(rawToolName)
        val actionArgs = if (normalizedToolName == AgentToolNames.ANDROID_PRIVILEGED_ACTION) {
            androidPrivilegedReplayArgs(args)
        } else {
            args
        }
        val sourceAction = linkedMapOf<String, Any?>(
            "tool" to (
                if (normalizedToolName == AgentToolNames.ANDROID_PRIVILEGED_ACTION) {
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
        val beforeScreenshot = asMap(before["screenshot"])
        val beforeScreenshotPath = firstNonBlank(
            before["screenshot_path"],
            before["screenshotPath"],
            before["screenshot_file"],
            before["screenshotFile"],
            beforeScreenshot["path"],
            beforeScreenshot["screenshot_path"],
            beforeScreenshot["screenshotPath"],
            beforeScreenshot["absolute_path"],
            beforeScreenshot["absolutePath"],
            beforeScreenshot["relative_path"],
            beforeScreenshot["relativePath"],
        )
        val sourcePackage = RunLogPagePackageInference.effectivePackage(
            firstNonBlank(
                before["package_name"],
                before["packageName"],
                actionArgs["package_name"],
                actionArgs["packageName"],
                card["package_name"],
                card["packageName"],
            ),
            sourceXml,
        )
        val srcCtx = sourceContextSourcePage(
            sourceXml = sourceXml,
            beforeScreenshot = beforeScreenshot,
            beforeScreenshotPath = beforeScreenshotPath,
            sourcePackage = sourcePackage,
        )
        if (sourceXml.isEmpty()) {
            return coordinateOnlySourceContext(
                normalizedToolName = normalizedToolName,
                srcCtx = srcCtx,
                sourceAction = sourceAction,
            )
        }
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
        return linkedMapOf(
            "src_ctx" to srcCtx,
            "dst_ctx" to linkedMapOf(
                "page" to repairedAfterXml,
                "package_name" to afterPackage,
                "repair_source" to "next_before_observation".takeIf {
                    repairedAfterXml == nextBeforeXml && nextBeforeXml.isNotBlank() && repairedAfterXml != afterXml
                },
            ).filterValues { value ->
                !value.isNullOrBlank()
            }.takeIf { it.isNotEmpty() },
            "action" to sourceAction,
        ).filterValues { it != null }
    }

    private fun sourceContextSourcePage(
        sourceXml: String,
        beforeScreenshot: Map<String, Any?>,
        beforeScreenshotPath: String,
        sourcePackage: String,
    ): Map<String, Any?> = linkedMapOf(
        "page" to sourceXml.takeIf { it.isNotBlank() },
        "screenshot" to beforeScreenshot.takeIf { it.isNotEmpty() },
        "screenshot_path" to beforeScreenshotPath,
        "package_name" to sourcePackage,
        "require_unique_action_signature" to false,
    ).filterValues { value ->
        value != null && value.toString().trim().isNotEmpty()
    }

    private fun coordinateOnlySourceContext(
        normalizedToolName: String,
        srcCtx: Map<String, Any?>,
        sourceAction: Map<String, Any?>,
    ): Map<String, Any?> {
        val action = OobActionCodec.canonicalActionForName(normalizedToolName)
            ?: OobActionCodec.canonicalActionForName(sourceAction["tool"]?.toString().orEmpty())
            ?: return emptyMap()
        if (action !in OobActionCodec.coordinateActions || !hasCoordinateEvidence(sourceAction)) {
            return emptyMap()
        }
        return linkedMapOf(
            "src_ctx" to srcCtx,
            "action" to sourceAction,
            "_oob_meta" to linkedMapOf(
                "source_context_mode" to SOURCE_CONTEXT_MODE_COORDINATE_ONLY_NO_XML,
                "missing_source_xml" to true,
            ),
        )
    }

    private fun hasCoordinateEvidence(sourceAction: Map<String, Any?>): Boolean {
        val hasPoint = firstNonBlank(
            sourceAction["x"],
            sourceAction["center_x"],
            sourceAction["centerX"],
        ).isNotBlank() && firstNonBlank(
            sourceAction["y"],
            sourceAction["center_y"],
            sourceAction["centerY"],
        ).isNotBlank()
        val hasSwipe = firstNonBlank(sourceAction["x1"]).isNotBlank() &&
            firstNonBlank(sourceAction["y1"]).isNotBlank() &&
            firstNonBlank(sourceAction["x2"]).isNotBlank() &&
            firstNonBlank(sourceAction["y2"]).isNotBlank()
        return hasPoint || hasSwipe
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
}
