package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy

/**
 * Builds canonical OOB reusable Function specs from the simple public register
 * shape and normalizes inserted steps for update_function.
 */
class OobFunctionSpecBuilder {
    fun functionSpecForRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = explicitFunctionSpec(request)
        if (explicit.isNotEmpty()) return explicit
        val steps = simpleRegistrationSteps(request)
        if (steps.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis().toString()
        val rawFunctionId = firstNonBlank(
            request["functionId"],
            request["function_id"],
            request["id"],
        )
        val name = firstNonBlank(request["name"], request["title"], rawFunctionId)
            .ifBlank { "OOB reusable function" }
        val description = firstNonBlank(
            request["description"],
            request["goal"],
            request["summary"],
            name,
        )
        val functionId = rawFunctionId.ifBlank {
            simpleFunctionIdFrom(name = name, description = description, now = now)
        }
        val sourceContext = sourceContextFromRegistration(request)
        val sourcePackageName = firstNonBlank(
            mapArg(sourceContext["src_ctx"])["package_name"],
            mapArg(sourceContext["src_ctx"])["packageName"],
        )
        val packageName = firstNonBlank(
            request["packageName"],
            request["package_name"],
            request["current_package"],
            request["currentPackage"],
            mapArg(request["sourcePage"])["package_name"],
            mapArg(request["sourcePage"])["packageName"],
            mapArg(request["source_page"])["package_name"],
            mapArg(request["source_page"])["packageName"],
            sourcePackageName,
        )
        val normalizedSteps = steps.mapIndexed { index, raw ->
            normalizeSimpleRegisteredStep(
                raw = raw,
                index = index,
                inheritedSourceContext = sourceContext.takeIf { index == 0 }.orEmpty(),
            )
        }
        val capabilities = simpleExecutionCapabilities(normalizedSteps)
        return linkedMapOf<String, Any?>(
            "schema_version" to "oob.reusable_function.v1",
            "function_id" to functionId,
            "name" to name,
            "description" to description,
            "parameters" to listArg(request["parameters"]).mapNotNull { raw ->
                mapArg(raw).takeIf { it.isNotEmpty() }
            },
            "constraints" to linkedMapOf(
                "package_name" to packageName.takeIf { it.isNotBlank() },
            ).filterValues { it != null },
            "source" to linkedMapOf(
                "kind" to "agent_registered_function",
                "goal" to firstNonBlank(request["goal"], description),
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "registered_via" to "oob_function_register.simple",
                "source_context_mode" to firstNonBlank(
                    mapArg(sourceContext["_oob_meta"])["mode"],
                    "none"
                ).takeIf { sourceContext.isNotEmpty() },
                "registered_at" to now,
            ).filterValues { it != null },
            "execution" to linkedMapOf(
                "kind" to "tool_sequence",
                "runner" to "oob_tool_sequence",
                "entrypoint" to "execute",
                "capabilities" to capabilities,
                "steps" to normalizedSteps,
                "step_count" to normalizedSteps.size,
                "omniflow_step_count" to capabilities["omniflow_step_count"],
                "agent_step_count" to capabilities["agent_step_count"],
                "requires_agent_fallback" to capabilities["requires_agent_fallback"],
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to now,
                "updated_at" to now,
                "runner" to "oob_agent_reusable_function",
                "storage" to "workspace",
                "registration_input_mode" to "simple",
            ),
        )
    }

    fun hasExplicitFunctionSpec(request: Map<String, Any?>): Boolean =
        explicitFunctionSpec(request).isNotEmpty()

    private fun explicitFunctionSpec(request: Map<String, Any?>): Map<String, Any?> =
        mapArg(request["functionSpec"])
            .ifEmpty { mapArg(request["function_spec"]) }
            .ifEmpty {
                if ((request.containsKey("function_id") || request.containsKey("name")) &&
                    (mapArg(request["execution"]).isNotEmpty() || listArg(request["actions"]).isNotEmpty())
                ) {
                    request
                } else {
                    emptyMap()
                }
            }

    private fun simpleRegistrationSteps(request: Map<String, Any?>): List<Map<String, Any?>> =
        listArg(request["steps"])
            .ifEmpty { listArg(request["execution_steps"]) }
            .ifEmpty { listArg(request["executionSteps"]) }
            .mapNotNull { raw -> mapArg(raw).takeIf { it.isNotEmpty() } }

    fun normalizeSimpleRegisteredStep(
        raw: Map<String, Any?>,
        index: Int,
        inheritedSourceContext: Map<String, Any?>,
    ): Map<String, Any?> {
        val rawTool = firstNonBlank(
            raw["action"],
            raw["tool"],
            raw["tool_name"],
            raw["toolName"],
            raw["omniflow_action"],
            raw["local_action"],
            raw["type"],
        ).ifBlank {
            if (firstNonBlank(raw["function_id"], raw["functionId"]).isNotBlank()) {
                "call_tool"
            } else {
                "finished"
            }
        }
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(rawTool)
        val action = OobActionCodec.canonicalActionForName(rawTool)
        val sourceContext = mapArg(raw["source_context"])
            .ifEmpty { mapArg(raw["sourceContext"]) }
            .ifEmpty { inheritedSourceContext }
        val title = firstNonBlank(raw["title"], raw["summary"], raw["description"])
            .ifBlank { simpleStepTitle(action ?: normalizedTool, raw, index) }
        val stepArgs = normalizeSimpleStepArgs(raw, rawTool)

        val step = linkedMapOf<String, Any?>(
            "id" to firstNonBlank(raw["id"], raw["step_id"], "step_${index + 1}"),
            "index" to index,
            "title" to title,
        )
        when {
            action != null -> {
                step["kind"] = "omniflow_action"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_OMNIFLOW
                step["omniflow_action"] = action
                step["local_action"] = action
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = action
                step["callable_tool"] = action
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) {
                    step["source_context"] = sourceContext
                    if (RunLogReplayPolicy.isCoordinateAction(action)) {
                        step["coordinate_hook"] = "omniflow"
                    }
                }
            }
            RunLogReplayPolicy.isOmniflowGraphTool(normalizedTool) -> {
                step["kind"] = "omniflow_graph"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_OMNIFLOW
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = normalizedTool
                step["callable_tool"] = normalizedTool
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
            RunLogReplayPolicy.isOmniflowFunctionTool(normalizedTool) ||
                RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool) ||
                firstNonBlank(raw["function_id"], raw["functionId"]).isNotBlank() -> {
                step["kind"] = "omniflow_function"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_OMNIFLOW
                step["model_free"] = true
                step["scriptable"] = true
                step["tool"] = "call_tool"
                step["callable_tool"] = "call_tool"
                step["source_tool"] = normalizedTool.takeIf { it != "call_tool" }
                step["args"] = canonicalSimpleCallToolArgs(raw, stepArgs)
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
            else -> {
                step["kind"] = "tool_call"
                step["executor"] = RunLogReplayPolicy.EXECUTOR_TOOL
                step["scriptable"] = true
                step["tool"] = normalizedTool
                step["callable_tool"] = normalizedTool
                step["args"] = stepArgs
                if (sourceContext.isNotEmpty()) step["source_context"] = sourceContext
            }
        }
        return step.filterValues { it != null }
    }

    private fun normalizeSimpleStepArgs(
        raw: Map<String, Any?>,
        rawTool: String,
    ): Map<String, Any?> {
        val action = OobActionCodec.canonicalActionForName(rawTool)
            ?: OobActionCodec.normalizeName(rawTool)
        val args = linkedMapOf<String, Any?>()
        args.putAll(mapArg(raw["args"]))
        args.putAll(mapArg(raw["arguments"]).filterKeys { it !in args })
        putIfPresent(args, "package_name", raw["package_name"], raw["packageName"])
        putIfPresent(args, "target_description", raw["target_description"], raw["targetDescription"], raw["label"])
        putIfPresent(args, "text", raw["text"])
        putIfPresent(args, "content", raw["content"], raw["value"])
        putIfPresent(args, "key", raw["key"], raw["hotkey"], raw["hot_key"])
        putIfPresent(args, "direction", raw["direction"], raw["scroll_direction"], raw["scrollDirection"])
        putIfPresent(args, "x", raw["x"], raw["center_x"], raw["centerX"])
        putIfPresent(args, "y", raw["y"], raw["center_y"], raw["centerY"])
        putIfPresent(args, "x1", raw["x1"])
        putIfPresent(args, "y1", raw["y1"])
        putIfPresent(args, "x2", raw["x2"])
        putIfPresent(args, "y2", raw["y2"])
        putIfPresent(args, "distance", raw["distance"], raw["distance_px"], raw["distancePx"])
        putIfPresent(args, "duration_ms", raw["duration_ms"], raw["durationMs"])
        putIfPresent(args, "reset_task", raw["reset_task"], raw["resetTask"])
        putIfPresent(args, "launch_mode", raw["launch_mode"], raw["launchMode"])
        putIfPresent(args, "function_id", raw["function_id"], raw["functionId"])
        val nestedArguments = mapArg(raw["function_arguments"])
            .ifEmpty { mapArg(raw["functionArguments"]) }
            .ifEmpty { mapArg(raw["input"]) }
        if (nestedArguments.isNotEmpty() && !args.containsKey("arguments")) {
            args["arguments"] = nestedArguments
        }
        if (action == OobActionCodec.ACTION_INPUT_TEXT &&
            firstNonBlank(args["content"], args["text"], args["value"]).isBlank()
        ) {
            putIfPresent(args, "content", raw["input_text"], raw["inputText"])
        }
        if (action == "open_app" && args["reset_task"] == null) {
            args["reset_task"] = true
            args["launch_mode"] = firstNonBlank(args["launch_mode"], "fresh_task")
        }
        if (action == "finished" && args.isEmpty()) {
            args["content"] = firstNonBlank(raw["content"], raw["summary"], "Done")
        }
        return OobActionCodec.argsForStep(
            mapOf(
                "tool" to rawTool,
                "args" to args.filterValues { it != null },
            )
        )
    }

    private fun canonicalSimpleCallToolArgs(
        raw: Map<String, Any?>,
        normalizedArgs: Map<String, Any?>,
    ): Map<String, Any?> {
        val functionId = firstNonBlank(
            normalizedArgs["function_id"],
            raw["function_id"],
            raw["functionId"],
            raw["oob_function_id"],
            raw["oobFunctionId"],
        )
        val targetTool = firstNonBlank(
            raw["tool_name"],
            raw["toolName"],
            raw["target_tool"],
            raw["targetTool"],
            normalizedArgs["tool_name"],
            normalizedArgs["toolName"],
        )
        val nestedArguments = mapArg(normalizedArgs["arguments"])
            .ifEmpty { mapArg(raw["arguments"]) }
            .ifEmpty { mapArg(raw["args"]) }
        return linkedMapOf<String, Any?>().apply {
            putAll(normalizedArgs)
            if (functionId.isNotBlank()) put("function_id", functionId)
            if (targetTool.isNotBlank()) put("tool_name", targetTool)
            if (nestedArguments.isNotEmpty()) put("arguments", nestedArguments)
        }.filterValues { it != null }
    }

    private fun sourceContextFromRegistration(request: Map<String, Any?>): Map<String, Any?> {
        val explicit = mapArg(request["source_context"])
            .ifEmpty { mapArg(request["sourceContext"]) }
        if (explicit.isNotEmpty()) return explicit
        val sourcePage = mapArg(request["sourcePage"])
            .ifEmpty { mapArg(request["source_page"]) }
            .ifEmpty { mapArg(request["currentPage"]) }
            .ifEmpty { mapArg(request["current_page"]) }
        val pageXmlFromRequest = firstNonBlank(
            sourcePage["page"],
            sourcePage["xml"],
            sourcePage["observation_xml"],
            sourcePage["observationXml"],
            request["current_xml"],
            request["currentXml"],
            request["source_xml"],
            request["sourceXml"],
            request["xml"],
        )
        val requestPackageName = firstNonBlank(
            sourcePage["package_name"],
            sourcePage["packageName"],
            request["package_name"],
            request["packageName"],
            request["current_package"],
            request["currentPackage"],
        )
        val requestActivityName = firstNonBlank(
            sourcePage["activity_name"],
            sourcePage["activityName"],
            request["activity_name"],
            request["activityName"],
        )
        val autoCaptureDisabled = boolArg(request["disable_current_page_capture"]) ||
            boolArg(request["disableCurrentPageCapture"]) ||
            boolArg(request["no_current_page_capture"]) ||
            boolArg(request["noCurrentPageCapture"])
        val autoCaptureAllowed = !autoCaptureDisabled
        val capturedPage = if (pageXmlFromRequest.isBlank() && autoCaptureAllowed) {
            currentPageSourceContext()
        } else {
            emptyMap()
        }
        val capturedSrcCtx = mapArg(capturedPage["src_ctx"])
        val pageXml = firstNonBlank(pageXmlFromRequest, capturedSrcCtx["page"])
        if (pageXml.isBlank()) return emptyMap()
        val packageName = firstNonBlank(
            requestPackageName,
            capturedSrcCtx["package_name"],
            capturedSrcCtx["packageName"],
        )
        val activityName = firstNonBlank(
            requestActivityName,
            capturedSrcCtx["activity_name"],
            capturedSrcCtx["activityName"],
        )
        val mode = if (pageXmlFromRequest.isBlank()) "current_page_capture" else "explicit_request"
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to pageXml,
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "activity_name" to activityName.takeIf { it.isNotBlank() },
                "require_unique_action_signature" to false,
            ).filterValues { it != null },
            "_oob_meta" to linkedMapOf(
                "mode" to mode,
                "captured_current_page" to (mode == "current_page_capture"),
            ),
        )
    }

    private fun currentPageSourceContext(): Map<String, Any?> {
        val pageXml = runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        if (pageXml.isBlank()) return emptyMap()
        val packageName = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        val activityName = runCatching {
            OmniflowActionRuntime.backend.currentActivityName()?.trim().orEmpty()
        }.getOrDefault("")
        return linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to pageXml,
                "package_name" to packageName.takeIf { it.isNotBlank() },
                "activity_name" to activityName.takeIf { it.isNotBlank() },
                "require_unique_action_signature" to false,
            ).filterValues { it != null }
        )
    }

    private fun simpleStepTitle(action: String, raw: Map<String, Any?>, index: Int): String {
        val target = firstNonBlank(
            raw["target_description"],
            raw["targetDescription"],
            raw["label"],
            raw["text"],
            raw["content"],
            raw["value"],
        )
        return when {
            target.isNotBlank() -> "$action: $target"
            else -> "$action step ${index + 1}"
        }
    }

    private fun simpleFunctionIdFrom(name: String, description: String, now: String): String {
        val seed = "$name $description"
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "registered_function" }
        return "oob_fn_${seed}_${now.takeLast(6)}"
    }

    fun simpleExecutionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "scriptable_step_count" to steps.count { it["scriptable"] == true },
            "model_free_step_count" to steps.count { it["model_free"] == true },
            "omniflow_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
            "agent_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
            "requires_agent_fallback" to steps.any { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
        )

    private fun putIfPresent(
        target: MutableMap<String, Any?>,
        key: String,
        vararg values: Any?,
    ) {
        if (target.containsKey(key)) return
        values.firstOrNull { value ->
            value != null && value.toString().trim().isNotEmpty()
        }?.let { target[key] = it }
    }

}
