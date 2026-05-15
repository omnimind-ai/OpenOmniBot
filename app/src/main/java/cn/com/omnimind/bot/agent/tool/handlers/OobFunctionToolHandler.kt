package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OobFunctionToolHandler(
    private val context: android.content.Context,
    private val helper: SharedHelper,
) : ToolHandler {
    override val toolNames: Set<String> = emptySet()

    internal var router: cn.com.omnimind.bot.agent.AgentToolExecutor? = null

    /** Workspace-backed function store; injected by WorkbenchProjectStore on init. */
    var workspaceFunctionStore: cn.com.omnimind.bot.workbench.WorkspaceFunctionStore? = null

    override fun canHandle(toolName: String): Boolean =
        cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, toolName) != null
            || workspaceFunctionStore?.canHandle(toolName) == true

    fun canRunFullyWithOmniflow(materializedSpec: Map<String, Any?>): Boolean {
        val steps = materializedSteps(materializedSpec)
        return steps.isNotEmpty() && steps.all { isOmniflowStep(it) }
    }

    /** Returns the function spec from SharedPreferences or workspace, whichever has it. */
    private fun getSpec(functionId: String): Map<String, Any?>? =
        cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.get(context, functionId)
            ?: workspaceFunctionStore?.get(functionId)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle
    ): cn.com.omnimind.bot.agent.ToolExecutionResult {
        val toolName = toolCall.function.name
        val spec = getSpec(toolName)
            ?: return cn.com.omnimind.bot.agent.ToolExecutionResult.Error(
                toolName, "OOB function not found: $toolName"
            )

        val argsMap = helper.jsonObjectToMap(args)
        val materializedSpec = cn.com.omnimind.baselib.runlog.OobReusableFunctionStore.materialize(spec, argsMap)

        val runPayload = runMaterializedFunction(
            functionId = toolName,
            spec = spec,
            materializedSpec = materializedSpec,
            callback = callback,
            toolHandle = toolHandle,
            env = env,
            parentToolCallId = toolCall.id,
            toolName = toolName
        )
        val steps = materializedSteps(materializedSpec)
        val description = spec["description"]?.toString().orEmpty()
        val stepResults = (runPayload["step_results"] as? List<*>) ?: emptyList<Any?>()
        val allSuccess = runPayload["success"] != false
        val summary = buildString {
            append(description.ifBlank { toolName })
            append(" — ")
            append("${runPayload["success_step_count"] ?: stepResults.size}/${steps.size} 步完成")
        }
        val payload = helper.encodeLocalizedPayload(runPayload)
        return cn.com.omnimind.bot.agent.ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = summary,
            previewJson = payload,
            rawResultJson = payload,
            success = allSuccess
        )
    }

    suspend fun runMaterializedFunction(
        functionId: String,
        spec: Map<String, Any?>,
        materializedSpec: Map<String, Any?>,
        callback: cn.com.omnimind.bot.agent.AgentCallback? = null,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle? = null,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment? = null,
        parentToolCallId: String? = null,
        toolName: String = functionId,
    ): Map<String, Any?> {
        val steps = materializedSteps(materializedSpec)
        val stepResults = mutableListOf<Map<String, Any?>>()
        var modelUsed = false
        var failureReason: String? = null

        for ((index, step) in steps.withIndex()) {
            toolHandle?.throwIfStopRequested()
            val stepIndex = index + 1
            val stepId = step["id"]?.toString() ?: "step_$stepIndex"
            val stepTitle = step["title"]?.toString() ?: stepId
            val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
                .ifEmpty { "agent" }
            val callableTool = step["callable_tool"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: step["tool"]?.toString()?.trim().orEmpty()

            if (callback != null) {
                helper.reportToolProgress(
                    callback = callback,
                    toolName = toolName,
                    progress = "$stepIndex/${steps.size} $stepTitle",
                    toolHandle = toolHandle
                )
            }

            val stepResult: Map<String, Any?> = when {
                isOmniflowStep(step) -> {
                    try {
                        executeOmniflowStep(step, stepId, stepTitle)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Coordinate remap failed — try vlm_task fallback if possible
                        val fallbackResult = tryOmniflowVlmFallback(
                            step = step,
                            stepId = stepId,
                            stepTitle = stepTitle,
                            failReason = e.message ?: "omniflow step failed",
                            env = env,
                            callback = callback,
                            toolHandle = toolHandle,
                            parentToolCallId = parentToolCallId ?: toolName
                        )
                        fallbackResult ?: linkedMapOf(
                            "step_id" to stepId,
                            "tool" to actionNameForStep(step),
                            "executor" to "omniflow",
                            "model_free" to true,
                            "success" to false,
                            "summary" to (e.message ?: "omniflow step failed")
                        )
                    }
                }

                executor == "tool" && callableTool.isNotEmpty() && router != null &&
                    env != null && callback != null && toolHandle != null -> {
                    executeToolStep(
                        step = step,
                        stepId = stepId,
                        stepTitle = stepTitle,
                        callableTool = callableTool,
                        env = env,
                        callback = callback,
                        toolHandle = toolHandle,
                        syntheticCallId = "${parentToolCallId ?: toolName}_$stepId"
                    )
                }

                else -> {
                    // executor=agent: call the actual recorded tool inline via router.
                    // This keeps execution in the same context without spawning a new
                    // agent conversation. Data flow from previous steps is sequential
                    // but args are from the original recording (static replay).
                    val agentTool = callableTool
                        .ifEmpty { step["tool"]?.toString()?.trim().orEmpty() }
                    if (agentTool.isNotEmpty() &&
                        router != null && env != null &&
                        callback != null && toolHandle != null) {
                        modelUsed = true
                        executeToolStep(
                            step = step,
                            stepId = stepId,
                            stepTitle = stepTitle,
                            callableTool = agentTool,
                            env = env,
                            callback = callback,
                            toolHandle = toolHandle,
                            syntheticCallId = "${parentToolCallId ?: toolName}_$stepId"
                        ).also { result ->
                            // Annotate so caller knows a tool was used
                            (result as? LinkedHashMap<String, Any?>)
                                ?.put("executor", "agent_tool")
                        }
                    } else {
                        // No router — skill was triggered externally (not from within
                        // a live agent). Mark as unexecuted so caller can decide.
                        linkedMapOf(
                            "step_id" to stepId,
                            "tool" to agentTool.ifEmpty { "?" },
                            "executor" to "agent",
                            "success" to false,
                            "needs_agent" to true,
                            "summary" to "agent step requires live execution context: $stepTitle"
                        )
                    }
                }
            }
            stepResults += stepResult
            if (stepResult["success"] == false) {
                failureReason = stepResult["summary"]?.toString()
                break
            }
        }

        val successCount = stepResults.count { it["success"] != false }
        val allSuccess = stepResults.size == steps.size && stepResults.none { it["success"] == false }
        val description = spec["description"]?.toString().orEmpty()
        return linkedMapOf(
            "success" to allSuccess,
            "function_id" to (spec["function_id"] ?: functionId),
            "description" to description,
            "runner" to if (modelUsed) "oob_function_mixed_runner" else "oob_omniflow_replay",
            "step_count" to steps.size,
            "success_step_count" to successCount,
            "model_used" to modelUsed,
            "fallback_available" to !allSuccess,
            "error_message" to failureReason,
            "step_results" to stepResults
        )
    }

    /**
     * When omniflow coordinate remapping fails, fall back to vlm_task using
     * target_description from the step args. Returns null if fallback is not
     * possible (no router, no target_description, or vlm_task itself fails).
     */
    private suspend fun tryOmniflowVlmFallback(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        failReason: String,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment?,
        callback: cn.com.omnimind.bot.agent.AgentCallback?,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle?,
        parentToolCallId: String,
    ): Map<String, Any?>? {
        if (router == null || env == null || callback == null || toolHandle == null) return null
        val args = normalizeArgsMap(step["args"])
        val targetDesc = stringArg(args, "target_description", "targetDescription").orEmpty()
        if (targetDesc.isEmpty()) return null
        val action = actionNameForStep(step)
        val goal = when (action) {
            "click", "long_press" -> "找到并点击「$targetDesc」"
            "scroll" -> "在「$targetDesc」区域滚动"
            else -> "执行 $action 操作：$targetDesc"
        }
        val vlmArgs = helper.mapToJsonElement(
            mapOf("goal" to goal, "startFromCurrent" to true)
        ) as? kotlinx.serialization.json.JsonObject
            ?: return null
        val syntheticCall = cn.com.omnimind.baselib.llm.AssistantToolCall(
            id = "${parentToolCallId}_${stepId}_fallback",
            type = "function",
            function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                name = "vlm_task",
                arguments = vlmArgs.toString()
            )
        )
        val subDescriptor = cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor(
            name = "vlm_task",
            displayName = stepTitle,
            toolType = "omniflow_fallback"
        )
        return try {
            val subResult = router!!.execute(
                syntheticCall, vlmArgs, subDescriptor, env, callback, toolHandle
            )
            val succeeded = subResult !is cn.com.omnimind.bot.agent.ToolExecutionResult.Error
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to "vlm_task",
                "executor" to "omniflow_vlm_fallback",
                "success" to succeeded,
                "omniflow_fail_reason" to failReason,
                "summary" to "omniflow remap failed → vlm fallback: $goal"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun executeToolStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        callableTool: String,
        env: cn.com.omnimind.bot.agent.AgentExecutionEnvironment,
        callback: cn.com.omnimind.bot.agent.AgentCallback,
        toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle,
        syntheticCallId: String,
    ): Map<String, Any?> {
        val remapResult = remapOmniflowStepArgs(step)
        val stepArgsMap = remapResult.args
        val stepArgs = when (stepArgsMap) {
            is Map<*, *> -> helper.mapToJsonElement(
                stepArgsMap.entries.associate { (k, v) -> k.toString() to v }
            ) as? JsonObject ?: JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
        val syntheticCall = cn.com.omnimind.baselib.llm.AssistantToolCall(
            id = syntheticCallId,
            type = "function",
            function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(
                name = callableTool,
                arguments = stepArgs.toString()
            )
        )
        val subDescriptor = cn.com.omnimind.bot.agent.AgentToolRegistry.RuntimeToolDescriptor(
            name = callableTool,
            displayName = stepTitle,
            toolType = "oob_function_step"
        )
        return try {
            val subResult = router!!.execute(
                syntheticCall, stepArgs, subDescriptor, env, callback, toolHandle
            )
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to callableTool,
                "executor" to "tool",
                "success" to (subResult !is cn.com.omnimind.bot.agent.ToolExecutionResult.Error),
                "summary" to when (subResult) {
                    is cn.com.omnimind.bot.agent.ToolExecutionResult.ContextResult -> subResult.summaryText
                    is cn.com.omnimind.bot.agent.ToolExecutionResult.Error -> subResult.message
                    else -> stepTitle
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            linkedMapOf<String, Any?>(
                "step_id" to stepId,
                "tool" to callableTool,
                "executor" to "tool",
                "success" to false,
                "summary" to (e.message ?: "step failed")
            )
        }
    }

    private suspend fun executeOmniflowStep(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
    ): Map<String, Any?> {
        val action = actionNameForStep(step)
        if (action !in OMNIFLOW_ACTIONS) {
            throw IllegalArgumentException("Unsupported omniflow action: $action")
        }
        val remapResult = remapOmniflowStepArgs(step)
        if (action in COORDINATE_ACTIONS && shouldUseOmniflowCoordinateHook(step)) {
            val applied = remapResult.meta["applied"] as? Boolean
            if (applied == false) {
                val reason = remapResult.meta["reason"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "coordinate_remap_unavailable"
                throw IllegalStateException("Coordinate remap failed: $reason")
            }
        }
        val args = normalizeArgsMap(remapResult.args)
        val summary = when (action) {
            "click" -> {
                val x = numberArg(args, "x", "center_x", "centerX")?.toFloat()
                    ?: throw IllegalArgumentException("click requires x")
                val y = numberArg(args, "y", "center_y", "centerY")?.toFloat()
                    ?: throw IllegalArgumentException("click requires y")
                AccessibilityController.clickCoordinate(x, y)
                "click"
            }

            "long_press" -> {
                val x = numberArg(args, "x", "center_x", "centerX")?.toFloat()
                    ?: throw IllegalArgumentException("long_press requires x")
                val y = numberArg(args, "y", "center_y", "centerY")?.toFloat()
                    ?: throw IllegalArgumentException("long_press requires y")
                AccessibilityController.longClickCoordinate(
                    x = x,
                    y = y,
                    duration = durationMs(args, defaultMs = 1000L)
                )
                "long_press"
            }

            "scroll" -> {
                val x1 = numberArg(args, "x1")?.toFloat()
                    ?: throw IllegalArgumentException("scroll requires x1")
                val y1 = numberArg(args, "y1")?.toFloat()
                    ?: throw IllegalArgumentException("scroll requires y1")
                val x2 = numberArg(args, "x2")?.toFloat()
                    ?: throw IllegalArgumentException("scroll requires x2")
                val y2 = numberArg(args, "y2")?.toFloat()
                    ?: throw IllegalArgumentException("scroll requires y2")
                val dx = x2 - x1
                val dy = y2 - y1
                val direction = if (abs(dy) > abs(dx)) {
                    if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
                } else {
                    if (dx > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT
                }
                AccessibilityController.scrollCoordinate(
                    x = x1,
                    y = y1,
                    direction = direction,
                    distance = hypot(dx, dy),
                    duration = durationMs(args, defaultMs = 1500L)
                )
                "scroll"
            }

            "type" -> {
                val text = stringArg(args, "content", "text", "value")
                    ?: throw IllegalArgumentException("type requires content")
                AccessibilityController.inputTextToFocusedNode(text)
                "type"
            }

            "open_app" -> {
                val packageName = stringArg(args, "package_name", "packageName")
                    ?: throw IllegalArgumentException("open_app requires package_name")
                AccessibilityController.launchApplication(packageName) { x, y ->
                    AccessibilityController.clickCoordinate(x, y)
                }
                "open_app"
            }

            "press_home" -> {
                AccessibilityController.goHome()
                "press_home"
            }

            "press_back" -> {
                AccessibilityController.goBack()
                "press_back"
            }

            "hot_key" -> {
                val key = stringArg(args, "key", "hotkey", "hot_key")
                    ?: throw IllegalArgumentException("hot_key requires key")
                AccessibilityController.pressHotKey(key)
                "hot_key"
            }

            "wait" -> {
                delay(durationMs(args, defaultMs = 1000L))
                "wait"
            }

            else -> throw IllegalArgumentException("Unsupported omniflow action: $action")
        }
        delay(OMNIFLOW_POST_STEP_DELAY_MS)
        return linkedMapOf(
            "step_id" to stepId,
            "tool" to action,
            "executor" to "omniflow",
            "model_free" to true,
            "success" to true,
            "summary" to (stepTitle.takeIf { it.isNotBlank() } ?: summary)
        )
    }

    private data class OmniflowStepArgsResult(
        val args: Any?,
        val meta: Map<String, Any?> = emptyMap(),
    )

    private fun materializedSteps(materializedSpec: Map<String, Any?>): List<Map<String, Any?>> {
        val rawSteps = (materializedSpec["execution"] as? Map<*, *>)?.get("steps") as? List<*>
            ?: return emptyList()
        return rawSteps.mapNotNull { rawStep ->
            (rawStep as? Map<*, *>)?.entries?.associate { (key, value) ->
                key.toString() to value
            }
        }
    }

    private fun isOmniflowStep(step: Map<String, Any?>): Boolean {
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val modelFree = step["model_free"] == true ||
            step["modelFree"] == true ||
            step["model_free"]?.toString()?.equals("true", ignoreCase = true) == true
        return executor == "omniflow" || (modelFree && actionNameForStep(step) in OMNIFLOW_ACTIONS)
    }

    private fun actionNameForStep(step: Map<String, Any?>): String =
        firstNonBlank(
            step["omniflow_action"],
            step["local_action"],
            step["tool"],
            step["callable_tool"]
        ).lowercase()

    private fun shouldUseOmniflowCoordinateHook(step: Map<String, Any?>): Boolean {
        val coordinateHook = step["coordinate_hook"]?.toString()?.trim()?.lowercase().orEmpty()
        val replayEngine = step["replay_engine"]?.toString()?.trim()?.lowercase().orEmpty()
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        return coordinateHook == "omniflow" ||
            step["omniflow"] == true ||
            replayEngine == "omniflow_utg" ||
            executor == "omniflow"
    }

    private fun normalizeArgsMap(rawArgs: Any?): Map<String, Any?> =
        when (rawArgs) {
            is Map<*, *> -> rawArgs.entries.associate { (key, value) -> key.toString() to value }
            else -> emptyMap()
        }

    private fun numberArg(args: Map<String, Any?>, vararg keys: String): Number? {
        for (key in keys) {
            val value = args[key] ?: continue
            when (value) {
                is Number -> return value
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun stringArg(args: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = args[key] ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) {
                return text
            }
        }
        return null
    }

    private fun durationMs(args: Map<String, Any?>, defaultMs: Long): Long {
        numberArg(args, "duration_ms", "durationMs")?.toLong()?.let {
            return it.coerceAtLeast(0L)
        }
        numberArg(args, "duration")?.toDouble()?.let { seconds ->
            return (seconds * 1000.0).toLong().coerceAtLeast(0L)
        }
        return defaultMs
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return text
            }
        }
        return ""
    }

    private data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom

        fun clampX(x: Float): Float = min(max(x, left), right)

        fun clampY(y: Float): Float = min(max(y, top), bottom)
    }

    private data class UiNode(
        val index: Int,
        val bounds: Rect,
        val className: String,
        val classSuffix: String,
        val resourceId: String,
        val resourceTail: String,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val packageName: String,
        val clickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val selected: Boolean,
        val checkable: Boolean,
    ) {
        val centerX: Float get() = bounds.centerX
        val centerY: Float get() = bounds.centerY
        val area: Float get() = bounds.area
        val interactive: Boolean get() = clickable || focusable || editable || scrollable
    }

    private data class PageModel(
        val rootBounds: Rect,
        val nodes: List<UiNode>,
    )

    private data class AnchorPair(
        val source: UiNode,
        val target: UiNode,
        val similarity: Float,
    )

    private data class TargetMatch(
        val node: UiNode,
        val confidence: Float,
        val anchorCount: Int,
        val mode: String,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private data class PointMapping(
        val newX: Float,
        val newY: Float,
        val sourceNode: UiNode,
        val targetNode: UiNode,
        val confidence: Float,
        val anchorCount: Int,
        val mode: String,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private fun remapOmniflowStepArgs(step: Map<String, Any?>): OmniflowStepArgsResult {
        val rawArgs = step["args"]
        val args = (rawArgs as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
            ?: return OmniflowStepArgsResult(rawArgs)
        if (!shouldUseOmniflowCoordinateHook(step)) {
            return OmniflowStepArgsResult(args)
        }
        val tool = actionNameForStep(step)
        if (tool !in setOf("click", "long_press", "scroll")) {
            return OmniflowStepArgsResult(args)
        }
        val sourceContext = step["source_context"] as? Map<*, *> ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_source_context", "algorithm" to "anchor_projection")
        )
        val srcCtx = sourceContext["src_ctx"] as? Map<*, *> ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_src_ctx", "algorithm" to "anchor_projection")
        )
        val sourceXml = srcCtx["page"]?.toString()?.trim().orEmpty()
        if (sourceXml.isEmpty()) {
            return OmniflowStepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_source_xml", "algorithm" to "anchor_projection")
            )
        }
        val currentXml = AccessibilityController.getCaptureScreenShotXml(true)?.trim().orEmpty()
        if (currentXml.isEmpty()) {
            return OmniflowStepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_current_xml", "algorithm" to "anchor_projection")
            )
        }
        return when (tool) {
            "click", "long_press" -> remapPointActionArgs(tool, args, sourceXml, currentXml)
            "scroll" -> remapScrollActionArgs(args, sourceXml, currentXml)
            else -> OmniflowStepArgsResult(args)
        }
    }

    private fun remapPointActionArgs(
        tool: String,
        args: Map<String, Any?>,
        sourceXml: String,
        currentXml: String,
    ): OmniflowStepArgsResult {
        val x = (args["x"] as? Number)?.toFloat() ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x", "algorithm" to "anchor_projection")
        )
        val y = (args["y"] as? Number)?.toFloat() ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y", "algorithm" to "anchor_projection")
        )
        val sourcePage = parsePageModel(sourceXml) ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_source_page", "algorithm" to "anchor_projection")
        )
        val targetPage = parsePageModel(currentXml) ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_current_page", "algorithm" to "anchor_projection")
        )
        val mapped = remapPointWithinPages(sourcePage, targetPage, x, y) ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
        )
        return OmniflowStepArgsResult(
            args = args + mapOf("x" to mapped.newX, "y" to mapped.newY),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to mapped.mode,
                "algorithm" to "anchor_projection",
                "confidence" to mapped.confidence,
                "anchor_count" to mapped.anchorCount,
                "old" to mapOf("x" to x, "y" to y),
                "new" to mapOf("x" to mapped.newX, "y" to mapped.newY),
                "source_element" to summarizeNode(mapped.sourceNode),
                "target_element" to summarizeNode(mapped.targetNode),
                "debug" to mapped.debug,
            )
        )
    }

    private fun remapScrollActionArgs(
        args: Map<String, Any?>,
        sourceXml: String,
        currentXml: String,
    ): OmniflowStepArgsResult {
        val x1 = (args["x1"] as? Number)?.toFloat() ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x1", "algorithm" to "anchor_projection")
        )
        val y1 = (args["y1"] as? Number)?.toFloat() ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y1", "algorithm" to "anchor_projection")
        )
        val x2 = (args["x2"] as? Number)?.toFloat() ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x2", "algorithm" to "anchor_projection")
        )
        val y2 = (args["y2"] as? Number)?.toFloat() ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y2", "algorithm" to "anchor_projection")
        )
        val sourcePage = parsePageModel(sourceXml) ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_source_page", "algorithm" to "anchor_projection")
        )
        val targetPage = parsePageModel(currentXml) ?: return OmniflowStepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_current_page", "algorithm" to "anchor_projection")
        )

        val sourceContainer = selectScrollSourceNode(sourcePage, x1, y1, x2, y2)
            ?: return OmniflowStepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_scroll_source_element", "algorithm" to "anchor_projection")
            )
        val targetMatch = matchTargetNode(sourcePage, targetPage, sourceContainer)
            ?: return OmniflowStepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
            )

        val start = projectPoint(sourceContainer.bounds, targetMatch.node.bounds, x1, y1)
        val end = projectPoint(sourceContainer.bounds, targetMatch.node.bounds, x2, y2)
        return OmniflowStepArgsResult(
            args = args + mapOf(
                "x1" to start.first,
                "y1" to start.second,
                "x2" to end.first,
                "y2" to end.second,
            ),
            meta = mapOf(
                "applied" to true,
                "tool" to "scroll",
                "mode" to targetMatch.mode,
                "algorithm" to "anchor_projection",
                "confidence" to targetMatch.confidence,
                "anchor_count" to targetMatch.anchorCount,
                "old" to mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2),
                "new" to mapOf(
                    "x1" to start.first,
                    "y1" to start.second,
                    "x2" to end.first,
                    "y2" to end.second,
                ),
                "source_element" to summarizeNode(sourceContainer),
                "target_element" to summarizeNode(targetMatch.node),
                "debug" to targetMatch.debug,
            )
        )
    }

    private fun remapPointWithinPages(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceX: Float,
        sourceY: Float,
    ): PointMapping? {
        val sourceNode = selectPointSourceNode(sourcePage, sourceX, sourceY) ?: return null
        val targetMatch = matchTargetNode(sourcePage, targetPage, sourceNode) ?: return null
        val mapped = projectPoint(sourceNode.bounds, targetMatch.node.bounds, sourceX, sourceY)
        return PointMapping(
            newX = mapped.first,
            newY = mapped.second,
            sourceNode = sourceNode,
            targetNode = targetMatch.node,
            confidence = targetMatch.confidence,
            anchorCount = targetMatch.anchorCount,
            mode = targetMatch.mode,
            debug = targetMatch.debug,
        )
    }

    private fun matchTargetNode(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? {
        val anchors = buildAnchors(sourcePage, targetPage)
        if (anchors.isEmpty()) {
            val fallback = directSimilarityFallback(sourcePage, targetPage, sourceNode) ?: return null
            return fallback.copy(
                debug = fallback.debug + mapOf(
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to 0,
                )
            )
        }

        val pageDiagonal = hypot(targetPage.rootBounds.width, targetPage.rootBounds.height).coerceAtLeast(1f)
        val scaleX = targetPage.rootBounds.width / (sourcePage.rootBounds.width + 1e-6f)
        val scaleY = targetPage.rootBounds.height / (sourcePage.rootBounds.height + 1e-6f)

        var bestNode: UiNode? = null
        var bestDirect = 0f
        var bestSpatial = 0f
        var bestScore = 0f
        var bestVotes: List<Map<String, Any?>> = emptyList()

        for (candidate in targetPage.nodes) {
            val directSimilarity = nodeSimilarity(sourceNode, candidate)
            if (directSimilarity <= 0f) continue

            val votes = mutableListOf<Map<String, Any?>>()
            var contributionSum = 0f
            for ((anchorIndex, anchor) in anchors.withIndex()) {
                val predictedX = anchor.target.centerX + (sourceNode.centerX - anchor.source.centerX) * scaleX
                val predictedY = anchor.target.centerY + (sourceNode.centerY - anchor.source.centerY) * scaleY
                val distance = hypot(predictedX - candidate.centerX, predictedY - candidate.centerY)
                val geometryScore = max(0f, 1f - (distance / pageDiagonal))
                val contribution = anchor.similarity * geometryScore
                contributionSum += contribution
                votes += mapOf(
                    "anchor_index" to anchorIndex,
                    "anchor_similarity" to anchor.similarity,
                    "geometry_score" to geometryScore,
                    "contribution" to contribution,
                    "predicted_point" to mapOf("x" to predictedX, "y" to predictedY),
                )
            }
            if (votes.isEmpty()) continue

            val spatialScore = contributionSum / votes.size.toFloat()
            val matchScore = directSimilarity * spatialScore
            if (matchScore > bestScore) {
                bestNode = candidate
                bestDirect = directSimilarity
                bestSpatial = spatialScore
                bestScore = matchScore
                bestVotes = votes.sortedByDescending { (it["contribution"] as? Number)?.toFloat() ?: 0f }.take(5)
            }
        }

        if (bestNode == null || bestScore < MIN_ANCHOR_MATCH_SCORE) {
            val fallback = directSimilarityFallback(sourcePage, targetPage, sourceNode) ?: return null
            return fallback.copy(
                debug = fallback.debug + mapOf(
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to anchors.size,
                    "anchor_fallback" to true,
                )
            )
        }

        return TargetMatch(
            node = bestNode,
            confidence = bestScore,
            anchorCount = anchors.size,
            mode = "anchor_projection",
            debug = mapOf(
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(bestNode),
                "anchor_count" to anchors.size,
                "direct_similarity" to bestDirect,
                "spatial_score" to bestSpatial,
                "match_score" to bestScore,
                "anchors" to anchors.take(5).map {
                    mapOf(
                        "source" to summarizeNode(it.source),
                        "target" to summarizeNode(it.target),
                        "similarity" to it.similarity,
                    )
                },
                "top_votes" to bestVotes,
            )
        )
    }

    private fun directSimilarityFallback(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? {
        val best = targetPage.nodes
            .map { candidate -> candidate to nodeSimilarity(sourceNode, candidate) }
            .maxByOrNull { it.second }
            ?: return null
        if (best.second < MIN_DIRECT_FALLBACK_SIMILARITY) {
            return null
        }
        return TargetMatch(
            node = best.first,
            confidence = best.second,
            anchorCount = 0,
            mode = "direct_similarity_fallback",
            debug = mapOf(
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(best.first),
                "direct_similarity" to best.second,
                "anchor_count" to 0,
            )
        )
    }

    private fun buildAnchors(
        sourcePage: PageModel,
        targetPage: PageModel,
        maxAnchorCount: Int = MAX_ANCHOR_COUNT,
    ): List<AnchorPair> {
        val sourceNodes = sourcePage.nodes.filter { isAnchorCandidate(it, sourcePage.rootBounds) }
        val targetNodes = targetPage.nodes.filter { isAnchorCandidate(it, targetPage.rootBounds) }
        if (sourceNodes.isEmpty() || targetNodes.isEmpty()) {
            return emptyList()
        }

        val bestSourceByTarget = mutableMapOf<Int, Pair<UiNode, Float>>()
        for (target in targetNodes) {
            var bestSource: UiNode? = null
            var bestSimilarity = 0f
            for (source in sourceNodes) {
                val similarity = nodeSimilarity(source, target)
                if (similarity > bestSimilarity) {
                    bestSource = source
                    bestSimilarity = similarity
                }
            }
            if (bestSource != null) {
                bestSourceByTarget[target.index] = bestSource to bestSimilarity
            }
        }

        val anchors = mutableListOf<AnchorPair>()
        for (source in sourceNodes) {
            var bestTarget: UiNode? = null
            var bestSimilarity = 0f
            for (target in targetNodes) {
                val similarity = nodeSimilarity(source, target)
                if (similarity > bestSimilarity) {
                    bestTarget = target
                    bestSimilarity = similarity
                }
            }
            val reciprocal = bestTarget?.let { target ->
                bestSourceByTarget[target.index]?.first?.index == source.index
            } == true
            if (bestTarget != null && reciprocal && bestSimilarity >= MIN_ANCHOR_SIMILARITY) {
                anchors += AnchorPair(source, bestTarget, bestSimilarity)
            }
        }

        return anchors
            .sortedByDescending { it.similarity }
            .take(maxAnchorCount)
    }

    private fun selectPointSourceNode(
        page: PageModel,
        x: Float,
        y: Float,
    ): UiNode? {
        val containing = page.nodes
            .filter { it.bounds.contains(x, y) }
            .sortedBy { it.area }
        if (containing.isEmpty()) {
            return null
        }
        return containing.firstOrNull { it.interactive } ?: containing.first()
    }

    private fun selectScrollSourceNode(
        page: PageModel,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): UiNode? {
        val containingBoth = page.nodes
            .filter { it.bounds.contains(x1, y1) && it.bounds.contains(x2, y2) }
            .sortedBy { it.area }
        containingBoth.firstOrNull { it.scrollable }?.let { return it }
        containingBoth.firstOrNull { it.interactive }?.let { return it }
        containingBoth.firstOrNull()?.let { return it }
        return selectPointSourceNode(page, (x1 + x2) / 2f, (y1 + y2) / 2f)
    }

    private fun projectPoint(
        sourceBounds: Rect,
        targetBounds: Rect,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val relativeX = if (sourceBounds.width <= 1e-3f) {
            0.5f
        } else {
            ((x - sourceBounds.left) / sourceBounds.width).coerceIn(0f, 1f)
        }
        val relativeY = if (sourceBounds.height <= 1e-3f) {
            0.5f
        } else {
            ((y - sourceBounds.top) / sourceBounds.height).coerceIn(0f, 1f)
        }
        val newX = targetBounds.clampX(targetBounds.left + targetBounds.width * relativeX)
        val newY = targetBounds.clampY(targetBounds.top + targetBounds.height * relativeY)
        return newX to newY
    }

    private fun parsePageModel(xml: String): PageModel? {
        val root = parseXmlRoot(xml) ?: return null
        val nodes = mutableListOf<UiNode>()
        val elements = root.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            val bounds = parseBounds(element.getAttribute("bounds")) ?: continue
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            nodes += UiNode(
                index = i,
                bounds = bounds,
                className = element.stringAttr("class-name").ifEmpty { element.stringAttr("class") },
                classSuffix = classSuffix(element.stringAttr("class-name").ifEmpty { element.stringAttr("class") }),
                resourceId = element.stringAttr("resource-id"),
                resourceTail = resourceTail(element.stringAttr("resource-id")),
                text = normalizeText(element.getAttribute("text")),
                contentDesc = normalizeText(element.getAttribute("content-desc")),
                hintText = normalizeText(element.getAttribute("hint-text")),
                packageName = normalizeText(element.getAttribute("package")),
                clickable = element.boolAttr("clickable"),
                focusable = element.boolAttr("focusable"),
                editable = element.boolAttr("editable"),
                scrollable = element.boolAttr("scrollable"),
                enabled = element.boolAttr("enabled", defaultValue = true),
                visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                    element.boolAttr("displayed", defaultValue = true),
                selected = element.boolAttr("selected"),
                checkable = element.boolAttr("checkable"),
            )
        }
        if (nodes.isEmpty()) {
            return null
        }
        val rootBounds = parseBounds(root.getAttribute("bounds")) ?: inferRootBounds(nodes)
        return PageModel(rootBounds = rootBounds, nodes = nodes)
    }

    private fun parseXmlRoot(xml: String): Element? {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val builder = factory.newDocumentBuilder()
            builder.parse(InputSource(StringReader(xml))).documentElement
        }.getOrNull()
    }

    private fun inferRootBounds(nodes: List<UiNode>): Rect {
        val left = nodes.minOf { it.bounds.left }
        val top = nodes.minOf { it.bounds.top }
        val right = nodes.maxOf { it.bounds.right }
        val bottom = nodes.maxOf { it.bounds.bottom }
        return Rect(left, top, right, bottom)
    }

    private fun nodeSimilarity(source: UiNode, target: UiNode): Float {
        var score = 0f
        var total = 0f

        fun add(weight: Float, contribution: Float) {
            total += weight
            score += weight * contribution.coerceIn(0f, 1f)
        }

        if (source.resourceId.isNotBlank()) {
            add(
                6f,
                when {
                    source.resourceId == target.resourceId -> 1f
                    source.resourceTail.isNotBlank() && source.resourceTail == target.resourceTail -> 0.72f
                    else -> 0f
                }
            )
        }
        if (source.text.isNotBlank()) {
            add(4.5f, textAffinity(source.text, target.text))
        }
        if (source.contentDesc.isNotBlank()) {
            add(3.5f, textAffinity(source.contentDesc, target.contentDesc))
        }
        if (source.hintText.isNotBlank()) {
            add(2.5f, textAffinity(source.hintText, target.hintText))
        }
        add(2f, classAffinity(source.className, target.className, source.classSuffix, target.classSuffix))
        add(1.5f, interactionAffinity(source, target))
        add(1f, geometryAffinity(source.bounds, target.bounds))

        if (total <= 1e-6f) {
            return 0f
        }
        return (score / total).coerceIn(0f, 1f)
    }

    private fun textAffinity(source: String, target: String): Float {
        if (source.isBlank() || target.isBlank()) {
            return 0f
        }
        if (source == target) {
            return 1f
        }
        if (source.contains(target) || target.contains(source)) {
            val shorter = min(source.length, target.length).toFloat()
            val longer = max(source.length, target.length).toFloat().coerceAtLeast(1f)
            return (0.72f + 0.28f * (shorter / longer)).coerceIn(0f, 1f)
        }
        val sourceTokens = source.split(' ').filter { it.isNotBlank() }.toSet()
        val targetTokens = target.split(' ').filter { it.isNotBlank() }.toSet()
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0f
        }
        val intersect = sourceTokens.intersect(targetTokens).size.toFloat()
        val union = sourceTokens.union(targetTokens).size.toFloat().coerceAtLeast(1f)
        return (intersect / union).coerceIn(0f, 1f)
    }

    private fun classAffinity(
        sourceClass: String,
        targetClass: String,
        sourceSuffix: String,
        targetSuffix: String,
    ): Float {
        if (sourceClass.isBlank() || targetClass.isBlank()) {
            return 0f
        }
        return when {
            sourceClass == targetClass -> 1f
            sourceSuffix.isNotBlank() && sourceSuffix == targetSuffix -> 0.85f
            else -> 0f
        }
    }

    private fun interactionAffinity(source: UiNode, target: UiNode): Float {
        val signals = listOf(
            source.clickable to target.clickable,
            source.focusable to target.focusable,
            source.editable to target.editable,
            source.scrollable to target.scrollable,
            source.checkable to target.checkable,
        )
        val expected = signals.count { it.first }
        if (expected == 0) {
            return if (source.interactive == target.interactive) 0.5f else 0f
        }
        val matched = signals.count { it.first && it.second }
        return matched.toFloat() / expected.toFloat()
    }

    private fun geometryAffinity(source: Rect, target: Rect): Float {
        val sourceAspect = source.width / source.height.coerceAtLeast(1e-3f)
        val targetAspect = target.width / target.height.coerceAtLeast(1e-3f)
        val aspect = min(sourceAspect, targetAspect) / max(sourceAspect, targetAspect).coerceAtLeast(1e-3f)
        val sourceArea = source.area.coerceAtLeast(1f)
        val targetArea = target.area.coerceAtLeast(1f)
        val area = min(sourceArea, targetArea) / max(sourceArea, targetArea)
        return ((aspect + area) / 2f).coerceIn(0f, 1f)
    }

    private fun isAnchorCandidate(node: UiNode, rootBounds: Rect): Boolean {
        if (!node.visible || !node.enabled || node.area <= 1f) {
            return false
        }
        val rootArea = rootBounds.area.coerceAtLeast(1f)
        val fullScreenLike = node.area / rootArea >= 0.96f
        if (fullScreenLike && node.resourceId.isBlank() && node.text.isBlank() && node.contentDesc.isBlank()) {
            return false
        }
        return node.interactive || node.resourceId.isNotBlank() || node.text.isNotBlank() || node.contentDesc.isNotBlank()
    }

    private fun summarizeNode(node: UiNode): Map<String, Any?> = mapOf(
        "index" to node.index,
        "bounds" to listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom),
        "class" to node.className,
        "resource_id" to node.resourceId,
        "text" to node.text,
        "content_desc" to node.contentDesc,
        "scrollable" to node.scrollable,
        "clickable" to node.clickable,
        "editable" to node.editable,
    )

    private fun parseBounds(bounds: String?): Rect? {
        val text = bounds?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val match = BOUNDS_REGEX.find(text) ?: return null
        val left = match.groupValues[1].toFloatOrNull() ?: return null
        val top = match.groupValues[2].toFloatOrNull() ?: return null
        val right = match.groupValues[3].toFloatOrNull() ?: return null
        val bottom = match.groupValues[4].toFloatOrNull() ?: return null
        if (right <= left || bottom <= top) {
            return null
        }
        return Rect(left, top, right, bottom)
    }

    private fun Element.stringAttr(name: String): String = getAttribute(name).trim()

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean {
        val value = getAttribute(name)?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) {
            return defaultValue
        }
        return value == "true" || value == "1" || value == "yes"
    }

    private fun normalizeText(value: String?): String =
        value.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    private fun classSuffix(className: String): String =
        className.substringAfterLast('.').lowercase()

    private fun resourceTail(resourceId: String): String {
        if (resourceId.isBlank()) {
            return ""
        }
        return resourceId.substringAfterLast('/').substringAfterLast(':').lowercase()
    }

    companion object {
        private val OMNIFLOW_ACTIONS = setOf(
            "click",
            "long_press",
            "scroll",
            "type",
            "open_app",
            "press_home",
            "press_back",
            "hot_key",
            "wait",
        )
        private val COORDINATE_ACTIONS = setOf("click", "long_press", "scroll")
        private const val OMNIFLOW_POST_STEP_DELAY_MS = 1000L
        private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
        private const val MAX_ANCHOR_COUNT = 5
        private const val MIN_ANCHOR_SIMILARITY = 0.45f
        private const val MIN_ANCHOR_MATCH_SCORE = 0.12f
        private const val MIN_DIRECT_FALLBACK_SIMILARITY = 0.58f
    }
}
