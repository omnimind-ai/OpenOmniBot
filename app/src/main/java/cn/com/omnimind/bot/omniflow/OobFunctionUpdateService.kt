package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.runlog.OobActionCodec

/**
 * Applies agent-provided updates to registered OOB Functions.
 *
 * This owns the update_function contract: analysis metadata persistence, safe
 * patch application, checker metadata, and structural step edits. Public tool
 * routing, execution, and RunLog evidence prompt packaging stay outside this
 * patching service.
 */
class OobFunctionUpdateService(
    private val context: Context,
    private val functionRepository: OobFunctionRepository,
    private val specBuilder: OobFunctionSpecBuilder = OobFunctionSpecBuilder(),
    private val checkerPatchService: OobFunctionCheckerPatchService = OobFunctionCheckerPatchService(),
    private val targetSourceMatcher: OobFunctionTargetSourceMatcher = OobFunctionTargetSourceMatcher(),
    private val evidencePackager: OobFunctionRunLogEvidencePackager = OobFunctionRunLogEvidencePackager(),
) {
    fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["runId"], request["run_id"])
        val runLogTimeline = if (runId.isNotEmpty()) {
            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            if (timeline["success"] != true) {
                return errorPayload(
                    code = "RUN_LOG_NOT_FOUND",
                    message = "RunLog not found: $runId"
                )
            }
            timeline
        } else {
            emptyMap()
        }
        val functionId = firstNonBlank(
            request["functionId"],
            request["function_id"],
            runLogTimeline["registered_function_id"],
            mapArg(runLogTimeline["registered_function_spec"])["function_id"],
        )
        if (functionId.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "update_function requires function_id"
            )
        }
        val original = functionRepository.get(functionId)
            ?: return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId
            )
        val requestedMode = firstNonBlank(request["mode"], request["operation"])
            .lowercase()
            .ifBlank { "enhance" }
        val dryRun = boolArg(request["dryRun"]) || boolArg(request["dry_run"])
        val instruction = firstNonBlank(
            request["instruction"],
            request["request"],
            request["user_instruction"],
            request["userInstruction"],
        )
        val analysis = mapArg(request["analysis"])
            .ifEmpty { mapArg(request["evidence_analysis"]) }
            .ifEmpty { mapArg(request["evidenceAnalysis"]) }
        val patch = mapArg(request["patch"])
            .ifEmpty { mapArg(request["functionPatch"]) }
            .ifEmpty { mapArg(request["function_patch"]) }
            .ifEmpty { mapArg(request["updates"]) }
            .ifEmpty { mapArg(analysis["recommended_patch"]) }
            .ifEmpty { mapArg(analysis["recommendedPatch"]) }
        if (runId.isNotEmpty() && analysis.isEmpty() && patch.isEmpty()) {
            val analysisContext = evidencePackager.analysisContext(
                functionId = functionId,
                functionSpec = original,
                runLogTimeline = runLogTimeline,
                instruction = instruction,
            )
            return linkedMapOf<String, Any?>(
                "success" to true,
                "function_id" to functionId,
                "run_id" to runId,
                "mode" to requestedMode,
                "changed" to false,
                "saved" to false,
                "dry_run" to dryRun,
                "requires_confirmation" to false,
                "needs_agent_analysis" to true,
                "analysis_context" to analysisContext,
                "agent_prompt" to evidencePackager.agentPrompt(analysisContext),
                "message" to "已读取 Function 和 RunLog，等待 agent 分析后再保存。",
                "source" to "oob_native_omniflow_toolkit"
            )
        }
        val updated = mutableJsonMap(original)
        val changes = mutableListOf<Map<String, Any?>>()
        val explicitOps = patchOperations(patch)
        val inferredOps = if (explicitOps.isEmpty()) {
            inferUpdateOperations(instruction)
        } else {
            emptyList()
        }
        val ops = explicitOps + inferredOps
        val inferredRepairIntent = requestedMode == "enhance" && ops.any(::isReplaceTargetOperation)
        val inferredStructuralIntent = requestedMode == "enhance" && ops.any(::isStructuralOperation)
        val mode = if (inferredRepairIntent || inferredStructuralIntent) "repair" else requestedMode
        val allowExecutionChange = boolArg(request["allowExecutionChange"]) ||
            boolArg(request["allow_execution_change"]) ||
            mode in setOf("repair", "fix", "correction")
        val allowStructuralChange = boolArg(request["allowStructuralChange"]) ||
            boolArg(request["allow_structural_change"])

        if (patch.isNotEmpty()) {
            applyFunctionMetadataPatch(updated, patch, changes)
        }
        if (analysis.isNotEmpty()) {
            changes += applyRunLogEvidenceAnalysis(
                spec = updated,
                runId = runId,
                analysis = analysis,
            )
        }

        val allCandidates = mutableListOf<Map<String, Any?>>()
        ops.forEach { op ->
            when (firstNonBlank(op["op"], op["type"], op["operation"]).lowercase()) {
                "replace_target", "replace_click_target", "retarget_action" -> {
                    if (!allowExecutionChange) {
                        return errorPayload(
                            code = "EXECUTION_CHANGE_NOT_ALLOWED",
                            message = "replace_target requires mode=repair or allowExecutionChange=true",
                            functionId = functionId
                        ) + linkedMapOf(
                            "mode" to mode,
                            "requires_confirmation" to true,
                            "operation" to op
                        )
                    }
                    val result = applyReplaceTargetOperation(updated, op)
                    allCandidates += result.candidates
                    if (result.requiresConfirmation) {
                        return linkedMapOf<String, Any?>(
                            "success" to true,
                            "function_id" to functionId,
                            "mode" to mode,
                            "changed" to false,
                            "saved" to false,
                            "dry_run" to dryRun,
                            "requires_confirmation" to true,
                            "reason" to result.reason,
                            "candidates" to allCandidates,
                            "message" to "需要确认要修改哪一步，Function 未保存。",
                            "source" to "oob_native_omniflow_toolkit"
                        )
                    }
                    changes += result.changes
                }
                "insert_step", "add_step", "insert_action", "add_action" -> {
                    if (!allowStructuralChange) {
                        return errorPayload(
                            code = "STRUCTURAL_CHANGE_NOT_ALLOWED",
                            message = "insert_step requires allowStructuralChange=true",
                            functionId = functionId
                        ) + linkedMapOf(
                            "mode" to mode,
                            "requires_confirmation" to true,
                            "operation" to op
                        )
                    }
                    changes += applyInsertStepOperation(updated, op)
                }
                "delete_step", "remove_step", "delete_action", "remove_action" -> {
                    if (!allowStructuralChange) {
                        return errorPayload(
                            code = "STRUCTURAL_CHANGE_NOT_ALLOWED",
                            message = "delete_step requires allowStructuralChange=true",
                            functionId = functionId
                        ) + linkedMapOf(
                            "mode" to mode,
                            "requires_confirmation" to true,
                            "operation" to op
                        )
                    }
                    changes += applyDeleteStepOperation(updated, op)
                }
            }
        }

        val changed = changes.isNotEmpty()
        appendFunctionUpdateAudit(
            spec = updated,
            mode = mode,
            instruction = instruction,
            changed = changed,
            dryRun = dryRun,
            changes = changes,
        )
        if (!changed) {
            return linkedMapOf<String, Any?>(
                "success" to true,
                "function_id" to functionId,
                "mode" to mode,
                "changed" to false,
                "saved" to false,
                "dry_run" to dryRun,
                "requires_confirmation" to false,
                "message" to "未找到可安全应用的 Function 更新。",
                "changes" to changes,
                "source" to "oob_native_omniflow_toolkit"
            )
        }
        if (dryRun) {
            return linkedMapOf<String, Any?>(
                "success" to true,
                "function_id" to functionId,
                "mode" to mode,
                "changed" to true,
                "saved" to false,
                "dry_run" to true,
                "requires_confirmation" to false,
                "changes" to changes,
                "updated_function" to updated,
                "message" to "已生成 Function 更新预览，未保存。",
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val save = functionRepository.register(updated)
        val saved = save["success"] == true
        return linkedMapOf<String, Any?>(
            "success" to saved,
            "function_id" to firstNonBlank(save["function_id"], functionId),
            "mode" to mode,
            "changed" to changed,
            "saved" to saved,
            "dry_run" to false,
            "requires_confirmation" to false,
            "changes" to changes,
            "save" to save,
            "message" to if (saved) {
                "Function 已更新并保存。"
            } else {
                save["error_message"]?.toString() ?: "Function 更新保存失败。"
            },
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    private fun applyRunLogEvidenceAnalysis(
        spec: MutableMap<String, Any?>,
        runId: String,
        analysis: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        val existingEvidence = mutableJsonMap(mapArg(metadata["oob_function_evidence"]))
        val sourceRunIds = listArg(existingEvidence["source_run_ids"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .toMutableList()
        if (runId.isNotBlank() && sourceRunIds.none { it == runId }) {
            sourceRunIds += runId
        }
        val evidence = linkedMapOf<String, Any?>().apply {
            putAll(existingEvidence)
            put("schema_version", "oob.function_evidence.v1")
            put("source", "update_function.runlog_analysis")
            put("latest_run_id", runId.takeIf { it.isNotBlank() })
            put("source_run_ids", sourceRunIds)
            put("latest_analysis", mutableJsonValue(analysis))
            put("updated_at_ms", System.currentTimeMillis())
        }.filterValues { it != null }
        if (existingEvidence == evidence) return emptyList()
        metadata["oob_function_evidence"] = evidence
        spec["metadata"] = metadata
        return listOf(
            changeMap(
                part = "metadata",
                field = "oob_function_evidence",
                old = existingEvidence.takeIf { it.isNotEmpty() },
                new = evidence,
            )
        )
    }

    private fun patchOperations(patch: Map<String, Any?>): List<Map<String, Any?>> {
        val direct = listArg(patch["ops"])
            .ifEmpty { listArg(patch["operations"]) }
            .ifEmpty { listArg(patch["repairs"]) }
            .mapNotNull { mapArg(it).takeIf { op -> op.isNotEmpty() } }
        if (direct.isNotEmpty()) return direct
        return mapArg(patch["replace_target"])
            .ifEmpty { mapArg(patch["replaceTarget"]) }
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(linkedMapOf<String, Any?>("op" to "replace_target").apply { putAll(it) }) }
            .orEmpty()
    }

    private fun isReplaceTargetOperation(op: Map<String, Any?>): Boolean =
        firstNonBlank(op["op"], op["type"], op["operation"])
            .lowercase() in setOf("replace_target", "replace_click_target", "retarget_action")

    private fun isStructuralOperation(op: Map<String, Any?>): Boolean =
        firstNonBlank(op["op"], op["type"], op["operation"])
            .lowercase() in setOf(
                "insert_step",
                "add_step",
                "insert_action",
                "add_action",
                "delete_step",
                "remove_step",
                "delete_action",
                "remove_action",
            )

    private fun inferUpdateOperations(instruction: String): List<Map<String, Any?>> {
        if (instruction.isBlank()) return emptyList()
        val quoted = Regex("[「“\\\"']([^」”\\\"']{1,80})[」”\\\"']")
            .findAll(instruction)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (quoted.size >= 2) {
            val first = quoted[0]
            val second = quoted[1]
            val firstIndex = instruction.indexOf(first)
            val secondIndex = instruction.indexOf(second, startIndex = (firstIndex + first.length).coerceAtLeast(0))
            val between = if (firstIndex >= 0 && secondIndex > firstIndex) {
                instruction.substring(firstIndex + first.length, secondIndex)
            } else {
                instruction
            }
            val prefix = if (firstIndex > 0) instruction.substring(0, firstIndex) else ""
            val desiredFirst = between.contains("而不是") ||
                between.contains("而非") ||
                between.contains("instead of", ignoreCase = true) ||
                (prefix.contains("应该") && between.contains("不是"))
            val wrongFirst = prefix.contains("不要") ||
                prefix.contains("别") ||
                prefix.contains("不该") ||
                between.contains("改成") ||
                between.contains("改为") ||
                between.contains("rather") && prefix.contains("not", ignoreCase = true)
            val desired = if (wrongFirst && !desiredFirst) second else first
            val wrong = if (wrongFirst && !desiredFirst) first else second
            return listOf(
                linkedMapOf(
                    "op" to "replace_target",
                    "action" to inferredActionFromInstruction(instruction),
                    "wrong_text" to wrong,
                    "desired_text" to desired,
                    "source" to "instruction_inference"
                )
            )
        }

        val patterns = listOf(
            Regex("(?:应该|应当|要|请)?(?:点击|点|选择|选|打开)\\s*(.{1,40}?)\\s*(?:而不是|而非|不是|不要)\\s*(?:点击|点|选择|选|打开)?\\s*(.{1,40})"),
            Regex("(?:不要|别|不该)(?:点击|点|选择|选|打开)?\\s*(.{1,40}?)\\s*(?:，|,|；|;|\\s)+(?:应该|应当|要|改成|改为|而是)(?:点击|点|选择|选|打开)?\\s*(.{1,40})"),
            Regex("把\\s*(?:点击|点|选择|选|打开)?\\s*(.{1,40}?)\\s*(?:改成|改为)\\s*(?:点击|点|选择|选|打开)?\\s*(.{1,40})"),
        )
        patterns.forEachIndexed { index, regex ->
            val match = regex.find(instruction) ?: return@forEachIndexed
            val first = cleanupInstructionTarget(match.groupValues[1])
            val second = cleanupInstructionTarget(match.groupValues[2])
            if (first.isBlank() || second.isBlank()) return@forEachIndexed
            val desired = if (index == 0) first else second
            val wrong = if (index == 0) second else first
            return listOf(
                linkedMapOf(
                    "op" to "replace_target",
                    "action" to inferredActionFromInstruction(instruction),
                    "wrong_text" to wrong,
                    "desired_text" to desired,
                    "source" to "instruction_inference"
                )
            )
        }
        return emptyList()
    }

    private fun cleanupInstructionTarget(value: String): String =
        value.trim()
            .trim('「', '」', '“', '”', '"', '\'', '，', ',', '。', '.', '；', ';', ' ')
            .replace(Regex("\\s+"), " ")

    private fun inferredActionFromInstruction(instruction: String): String =
        when {
            instruction.contains("长按") -> "long_press"
            instruction.contains("输入") || instruction.contains("填写") -> "input_text"
            instruction.contains("滑") || instruction.contains("滚") -> "swipe"
            else -> "click"
        }

    private fun applyFunctionMetadataPatch(
        spec: MutableMap<String, Any?>,
        patch: Map<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        setStringFieldIfChanged(spec, "name", patch["name"], changes, "header")
        setStringFieldIfChanged(spec, "description", patch["description"], changes, "header")

        val stepPatches = listArg(patch["steps"])
            .mapNotNull { mapArg(it).takeIf { stepPatch -> stepPatch.isNotEmpty() } }
        if (stepPatches.isNotEmpty()) {
            val execution = mutableJsonMap(mapArg(spec["execution"]))
            val steps = mutableJsonList(listArg(execution["steps"]))
            stepPatches.forEach { stepPatch ->
                val index = intArg(
                    stepPatch["index"],
                    stepPatch["step_index"],
                    stepPatch["stepIndex"],
                    defaultValue = -1
                )
                val stepIndex = if (index >= 0) {
                    index
                } else {
                    val stepId = firstNonBlank(stepPatch["id"], stepPatch["step_id"], stepPatch["stepId"])
                    steps.indexOfFirst { raw -> firstNonBlank(mapArg(raw)["id"]) == stepId }
                }
                if (stepIndex !in steps.indices) return@forEach
                val step = mutableJsonMap(mapArg(steps[stepIndex]))
                setStringFieldIfChanged(step, "title", stepPatch["title"], changes, "step_label", stepIndex)
                setStringFieldIfChanged(step, "summary", stepPatch["summary"], changes, "step_label", stepIndex)
                setStringFieldIfChanged(step, "description", stepPatch["description"], changes, "step_label", stepIndex)
                val cleanupAnnotation = mapArg(stepPatch["cleanup_annotation"])
                    .ifEmpty { mapArg(stepPatch["cleanupAnnotation"]) }
                if (cleanupAnnotation.isNotEmpty()) {
                    val old = mapArg(step["cleanup_annotation"])
                    if (old != cleanupAnnotation) {
                        step["cleanup_annotation"] = cleanupAnnotation
                        changes += changeMap(
                            part = "step_cleanup",
                            field = "cleanup_annotation",
                            old = old.takeIf { it.isNotEmpty() },
                            new = cleanupAnnotation,
                            stepIndex = stepIndex,
                        )
                    }
                }
                steps[stepIndex] = step
            }
            execution["steps"] = steps
            execution["step_count"] = steps.size
            spec["execution"] = execution
        }

        val parameters = listArg(patch["parameters"])
            .mapNotNull { mapArg(it).takeIf { parameter -> parameter.isNotEmpty() } }
            .filter(::isSafeParameterPatch)
        if (parameters.isNotEmpty() && spec["parameters"] != parameters) {
            val old = spec["parameters"]
            spec["parameters"] = parameters
            changes += changeMap("parameters", "parameters", old, parameters)
        }

        val agentReuse = mapArg(patch["agent_reuse"])
            .ifEmpty { mapArg(patch["agentReuse"]) }
        if (agentReuse.isNotEmpty()) {
            val old = mutableJsonMap(mapArg(spec["agent_reuse"]))
            val merged = linkedMapOf<String, Any?>().apply {
                putAll(old)
                putAll(agentReuse)
            }
            if (old != merged) {
                spec["agent_reuse"] = merged
                changes += changeMap("agent_reuse", "agent_reuse", old.takeIf { it.isNotEmpty() }, merged)
            }
        }

        val metadataPatch = mapArg(patch["metadata"])
        if (metadataPatch.isNotEmpty()) {
            val metadata = mutableJsonMap(mapArg(spec["metadata"]))
            metadataPatch.forEach { (key, value) ->
                if (key == "function_id" || key == "execution") return@forEach
                val metadataKey = if (key == "checkerRules") "checker_rules" else key
                if (metadataKey == "checker_rules") {
                    changes += checkerPatchService.applyCheckerRulesPatch(metadata, value)
                    return@forEach
                }
                val safeValue = mutableJsonValue(value)
                if (metadata[metadataKey] != safeValue) {
                    changes += changeMap("metadata", metadataKey, metadata[metadataKey], safeValue)
                    metadata[metadataKey] = safeValue
                }
            }
            spec["metadata"] = metadata
        }

        val topLevelCheckerRules = listArg(patch["checker_rules"])
            .ifEmpty { listArg(patch["checkerRules"]) }
        if (topLevelCheckerRules.isNotEmpty()) {
            val metadata = mutableJsonMap(mapArg(spec["metadata"]))
            changes += checkerPatchService.applyCheckerRulesPatch(metadata, topLevelCheckerRules)
            spec["metadata"] = metadata
        }

        changes += checkerPatchService.applyOptionalCheckerMetadataFromSteps(spec)
    }

    private fun isSafeParameterPatch(parameter: Map<String, Any?>): Boolean {
        val bindings = listArg(parameter["bindings"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (bindings.isEmpty()) return true
        return bindings.all { binding ->
            val normalized = binding.lowercase()
            val forbidden = listOf(
                ".x",
                ".y",
                "bounds",
                "center_x",
                "center_y",
                "width",
                "height",
                "screenshot",
                "xml",
                "source_context",
            )
            forbidden.none { token -> normalized.contains(token) }
        }
    }

    private data class ReplaceTargetResult(
        val changes: List<Map<String, Any?>>,
        val candidates: List<Map<String, Any?>>,
        val requiresConfirmation: Boolean,
        val reason: String = "",
    )

    private fun applyReplaceTargetOperation(
        spec: MutableMap<String, Any?>,
        op: Map<String, Any?>,
    ): ReplaceTargetResult {
        val desiredText = firstNonBlank(
            op["desired_text"],
            op["desiredText"],
            op["new_text"],
            op["newText"],
            op["prefer_text"],
            op["preferText"],
            op["target_text"],
            op["targetText"],
        )
        val wrongText = firstNonBlank(
            op["wrong_text"],
            op["wrongText"],
            op["old_text"],
            op["oldText"],
            op["avoid_text"],
            op["avoidText"],
        )
        if (desiredText.isBlank()) {
            return ReplaceTargetResult(
                changes = emptyList(),
                candidates = emptyList(),
                requiresConfirmation = true,
                reason = "desired_text_missing"
            )
        }
        val action = firstNonBlank(op["action"], op["tool"], op["omniflow_action"])
            .lowercase()
            .ifBlank { "click" }
        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        val explicitIndex = intArg(
            op["step_index"],
            op["stepIndex"],
            op["index"],
            defaultValue = -1
        )
        val candidates = targetReplacementCandidates(
            steps = steps,
            action = action,
            wrongText = wrongText,
            explicitIndex = explicitIndex,
        )
        val selected = candidates.firstOrNull()
        val ambiguous = selected == null ||
            (explicitIndex < 0 && candidates.size > 1 && targetCandidateScore(candidates[0]) == targetCandidateScore(candidates[1]))
        if (ambiguous) {
            return ReplaceTargetResult(
                changes = emptyList(),
                candidates = candidates.take(5),
                requiresConfirmation = true,
                reason = if (candidates.isEmpty()) "target_step_not_found" else "ambiguous_target_step"
            )
        }
        val stepIndex = intArg(selected["step_index"], defaultValue = -1)
        if (stepIndex !in steps.indices) {
            return ReplaceTargetResult(
                changes = emptyList(),
                candidates = candidates.take(5),
                requiresConfirmation = true,
                reason = "selected_step_out_of_range"
            )
        }

        val step = mutableJsonMap(mapArg(steps[stepIndex]))
        val args = mutableJsonMap(mapArg(step["args"]))
        val changes = mutableListOf<Map<String, Any?>>()
        val oldTarget = firstNonBlank(args["target_description"], args["targetDescription"])
        setArgIfChanged(args, "target_description", desiredText, changes, stepIndex)
        if (args.containsKey("targetDescription")) {
            setArgIfChanged(args, "targetDescription", desiredText, changes, stepIndex)
        }
        val selectorHints = mutableJsonMap(mapArg(args["selector_hints"]))
            .ifEmpty { mutableJsonMap(mapArg(args["selectorHints"])) }
        val updatedHints = linkedMapOf<String, Any?>().apply {
            putAll(selectorHints)
            put("strategy", "semantic_text_first")
            put("prefer_text", mergeStringList(selectorHints["prefer_text"], desiredText))
            if (wrongText.isNotBlank()) {
                put("avoid_text", mergeStringList(selectorHints["avoid_text"], wrongText))
            }
            put("updated_by", "update_function")
        }
        if (selectorHints != updatedHints) {
            args["selector_hints"] = updatedHints
            changes += changeMap(
                part = "step_args",
                field = "selector_hints",
                old = selectorHints.takeIf { it.isNotEmpty() },
                new = updatedHints,
                stepIndex = stepIndex,
            )
        }

        val desiredNode = targetSourceMatcher.match(step, args, desiredText, action)
        if (desiredNode != null) {
            setArgIfChanged(args, "x", desiredNode.bounds.centerX, changes, stepIndex)
            setArgIfChanged(args, "y", desiredNode.bounds.centerY, changes, stepIndex)
            setArgIfChanged(args, "bounds", desiredNode.bounds.raw, changes, stepIndex)
            if (desiredNode.resourceId.isNotBlank()) {
                setArgIfChanged(args, "node_resource_id", desiredNode.resourceId, changes, stepIndex)
            }
            args["target_resolution"] = linkedMapOf(
                "source" to "update_function.source_context_xml",
                "matched_text" to desiredNode.text.takeIf { it.isNotBlank() },
                "matched_content_desc" to desiredNode.contentDesc.takeIf { it.isNotBlank() },
                "resource_id" to desiredNode.resourceId.takeIf { it.isNotBlank() },
                "bounds" to desiredNode.bounds.raw,
                "score" to desiredNode.score,
            ).filterValues { it != null }
        } else {
            args["target_resolution"] = linkedMapOf(
                "source" to "update_function",
                "matched" to false,
                "reason" to "desired_text_not_found_in_source_context",
            )
        }

        updateStepTextFieldReplacingTarget(step, "title", wrongText, desiredText, action, changes, stepIndex)
        updateStepTextFieldReplacingTarget(step, "summary", wrongText, desiredText, action, changes, stepIndex)
        updateStepTextFieldReplacingTarget(step, "description", wrongText, desiredText, action, changes, stepIndex)
        step["args"] = args
        step["updated_by"] = "update_function"
        steps[stepIndex] = step
        execution["steps"] = steps
        execution["step_count"] = steps.size
        spec["execution"] = execution

        changes += linkedMapOf(
            "part" to "repair",
            "op" to "replace_target",
            "step_index" to stepIndex,
            "action" to action,
            "old_target" to oldTarget.takeIf { it.isNotBlank() },
            "wrong_text" to wrongText.takeIf { it.isNotBlank() },
            "desired_text" to desiredText,
            "coordinate_update_applied" to (desiredNode != null),
        ).filterValues { it != null }
        return ReplaceTargetResult(
            changes = changes,
            candidates = candidates.take(5),
            requiresConfirmation = false,
        )
    }

    private fun applyInsertStepOperation(
        spec: MutableMap<String, Any?>,
        op: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        val rawStep = mapArg(op["step"])
            .ifEmpty { mapArg(op["action_step"]) }
            .ifEmpty { mapArg(op["new_step"]) }
            .ifEmpty { structuralStepFromOperation(op) }
        if (rawStep.isEmpty()) return emptyList()
        val requestedIndex = intArg(
            op["step_index"],
            op["stepIndex"],
            op["index"],
            op["before_step_index"],
            op["beforeStepIndex"],
            defaultValue = -1
        )
        val afterIndex = intArg(op["after_step_index"], op["afterStepIndex"], defaultValue = -1)
        val insertIndex = when {
            requestedIndex >= 0 -> requestedIndex.coerceIn(0, steps.size)
            afterIndex >= 0 -> (afterIndex + 1).coerceIn(0, steps.size)
            else -> steps.size
        }
        val inheritedSourceContext = mapArg(rawStep["source_context"])
            .ifEmpty {
                if (insertIndex > 0) {
                    mapArg(mapArg(steps[insertIndex - 1])["source_context"])
                } else {
                    emptyMap()
                }
            }
        val normalizedStep = if (looksLikeCanonicalStep(rawStep)) {
            mutableJsonMap(rawStep)
        } else {
            mutableJsonMap(
                specBuilder.normalizeSimpleRegisteredStep(
                    raw = rawStep,
                    index = insertIndex,
                    inheritedSourceContext = inheritedSourceContext,
                )
            )
        }
        normalizedStep["index"] = insertIndex
        val existingIds = steps.mapNotNull { raw ->
            firstNonBlank(mapArg(raw)["id"]).takeIf { it.isNotBlank() }
        }.toSet()
        val requestedId = firstNonBlank(rawStep["id"], rawStep["step_id"])
        normalizedStep["id"] = when {
            requestedId.isNotBlank() && requestedId !in existingIds -> requestedId
            else -> uniqueStepId(existingIds, insertIndex)
        }
        steps.add(insertIndex, normalizedStep)
        replaceExecutionSteps(spec, execution, steps)
        return listOf(
            linkedMapOf(
                "part" to "execution",
                "op" to "insert_step",
                "step_index" to insertIndex,
                "step" to compactStepForChange(normalizedStep),
            )
        )
    }

    private fun applyDeleteStepOperation(
        spec: MutableMap<String, Any?>,
        op: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        if (steps.isEmpty()) return emptyList()
        val explicitIndex = intArg(
            op["step_index"],
            op["stepIndex"],
            op["index"],
            defaultValue = -1
        )
        val stepId = firstNonBlank(op["step_id"], op["stepId"], op["id"])
        val deleteIndex = when {
            explicitIndex in steps.indices -> explicitIndex
            stepId.isNotBlank() -> steps.indexOfFirst { raw -> firstNonBlank(mapArg(raw)["id"]) == stepId }
            else -> -1
        }
        if (deleteIndex !in steps.indices) return emptyList()
        val removed = mutableJsonMap(mapArg(steps.removeAt(deleteIndex)))
        replaceExecutionSteps(spec, execution, steps)
        return listOf(
            linkedMapOf(
                "part" to "execution",
                "op" to "delete_step",
                "step_index" to deleteIndex,
                "step" to compactStepForChange(removed),
                "reason" to firstNonBlank(op["reason"]).takeIf { it.isNotBlank() },
            ).filterValues { it != null }
        )
    }

    private fun structuralStepFromOperation(op: Map<String, Any?>): Map<String, Any?> {
        val action = firstNonBlank(
            op["step_action"],
            op["stepAction"],
            op["action"],
            op["tool"],
        )
        if (action.isBlank()) return emptyMap()
        return linkedMapOf<String, Any?>(
            "action" to action,
            "title" to firstNonBlank(op["title"], op["summary"], op["description"]).takeIf { it.isNotBlank() },
            "description" to firstNonBlank(op["description"]).takeIf { it.isNotBlank() },
            "args" to mapArg(op["args"]).ifEmpty { mapArg(op["arguments"]) }.takeIf { it.isNotEmpty() },
            "target_description" to firstNonBlank(op["target_description"], op["targetDescription"]).takeIf { it.isNotBlank() },
            "text" to firstNonBlank(op["text"], op["content"], op["value"]).takeIf { it.isNotBlank() },
            "x" to op["x"],
            "y" to op["y"],
            "direction" to firstNonBlank(op["direction"]).takeIf { it.isNotBlank() },
            "packageName" to firstNonBlank(op["packageName"], op["package_name"]).takeIf { it.isNotBlank() },
            "source_context" to mapArg(op["source_context"]).ifEmpty { mapArg(op["sourceContext"]) }.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun looksLikeCanonicalStep(step: Map<String, Any?>): Boolean =
        firstNonBlank(step["kind"]).isNotBlank() &&
            firstNonBlank(step["executor"]).isNotBlank() &&
            (step.containsKey("args") || step.containsKey("tool") || step.containsKey("callable_tool"))

    private fun replaceExecutionSteps(
        spec: MutableMap<String, Any?>,
        execution: MutableMap<String, Any?>,
        steps: MutableList<Any?>,
    ) {
        val seenIds = mutableSetOf<String>()
        val normalizedSteps = steps.mapIndexed { index, raw ->
            mutableJsonMap(mapArg(raw)).apply {
                put("index", index)
                val currentId = firstNonBlank(this["id"])
                val normalizedId = when {
                    currentId.isNotBlank() && currentId !in seenIds -> currentId
                    else -> uniqueStepId(seenIds, index)
                }
                put("id", normalizedId)
                seenIds += normalizedId
            }
        }
        val capabilities = specBuilder.simpleExecutionCapabilities(normalizedSteps)
        execution["steps"] = normalizedSteps
        execution["step_count"] = normalizedSteps.size
        execution["omniflow_step_count"] = capabilities["omniflow_step_count"]
        execution["agent_step_count"] = capabilities["agent_step_count"]
        execution["requires_agent_fallback"] = capabilities["requires_agent_fallback"]
        execution["capabilities"] = linkedMapOf<String, Any?>().apply {
            putAll(mapArg(execution["capabilities"]))
            putAll(capabilities)
        }
        spec["execution"] = execution
    }

    private fun compactStepForChange(step: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf(
            "id" to firstNonBlank(step["id"]),
            "index" to step["index"],
            "title" to firstNonBlank(step["title"], step["summary"]),
            "tool" to stepActionName(step),
            "executor" to firstNonBlank(step["executor"]),
        ).filterValues { it != null && it.toString().isNotBlank() }

    private fun uniqueStepId(existingIds: Set<String>, index: Int): String {
        val base = "step_${index + 1}"
        if (base !in existingIds) return base
        var suffix = 1
        while (true) {
            val candidate = "${base}_inserted_$suffix"
            if (candidate !in existingIds) return candidate
            suffix += 1
        }
    }

    private fun targetReplacementCandidates(
        steps: List<Any?>,
        action: String,
        wrongText: String,
        explicitIndex: Int,
    ): List<Map<String, Any?>> {
        return steps.mapIndexedNotNull { index, rawStep ->
            val step = mapArg(rawStep)
            val tool = stepActionName(step)
            val actionMatches = action.isBlank() || action == tool ||
                OobActionCodec.canonicalActionForName(action) == tool
            if (explicitIndex >= 0 && explicitIndex != index) return@mapIndexedNotNull null
            if (!actionMatches && explicitIndex < 0) return@mapIndexedNotNull null
            val args = mapArg(step["args"])
            val argsText = listOf(
                args["target_description"],
                args["targetDescription"],
                args["text"],
                args["content"],
                args["selector"],
                args["node_resource_id"],
                args["nodeResourceId"],
            ).joinToString(" ")
            val labelText = listOf(step["title"], step["summary"], step["description"]).joinToString(" ")
            val score = when {
                explicitIndex == index -> 100
                wrongText.isBlank() && actionMatches -> 10
                containsLoose(argsText, wrongText) -> 80
                containsLoose(labelText, wrongText) -> 55
                else -> 0
            } + if (actionMatches) 10 else 0
            if (score <= 0) return@mapIndexedNotNull null
            linkedMapOf(
                "step_index" to index,
                "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"], tool),
                "tool" to tool,
                "score" to score,
                "current_target" to firstNonBlank(
                    args["target_description"],
                    args["targetDescription"],
                    args["text"],
                    args["content"],
                ).takeIf { it.isNotBlank() },
            ).filterValues { it != null }
        }.sortedWith(
            compareByDescending<Map<String, Any?>> { targetCandidateScore(it) }
                .thenBy { intArg(it["step_index"], defaultValue = Int.MAX_VALUE) }
        )
    }

    private fun targetCandidateScore(candidate: Map<String, Any?>): Int =
        intArg(candidate["score"], defaultValue = 0)

    private fun stepActionName(step: Map<String, Any?>): String =
        OobActionCodec.actionNameForStep(step)

    private fun containsLoose(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        val normalizedHaystack = normalizeText(haystack)
        val normalizedNeedle = normalizeText(needle)
        return normalizedHaystack.contains(normalizedNeedle)
    }

    private fun setArgIfChanged(
        args: MutableMap<String, Any?>,
        field: String,
        value: Any?,
        changes: MutableList<Map<String, Any?>>,
        stepIndex: Int,
    ) {
        if (value == null || value.toString().isBlank()) return
        if (args[field] == value) return
        changes += changeMap("step_args", field, args[field], value, stepIndex)
        args[field] = value
    }

    private fun updateStepTextFieldReplacingTarget(
        step: MutableMap<String, Any?>,
        field: String,
        wrongText: String,
        desiredText: String,
        action: String,
        changes: MutableList<Map<String, Any?>>,
        stepIndex: Int,
    ) {
        val old = step[field]?.toString()?.takeIf { it.isNotBlank() }
        val next = when {
            old != null && wrongText.isNotBlank() && old.contains(wrongText) ->
                old.replace(wrongText, desiredText)
            old != null && containsLoose(old, desiredText) -> old
            field == "title" && old.isNullOrBlank() -> actionTitle(action, desiredText)
            field == "description" && old.isNullOrBlank() ->
                "${actionTitle(action, desiredText)}，避免误选其他相近目标。"
            else -> old
        } ?: return
        if (old == next) return
        step[field] = next
        changes += changeMap("step_label", field, old, next, stepIndex)
    }

    private fun actionTitle(action: String, target: String): String =
        when (action) {
            "input_text" -> "填写$target"
            "long_press" -> "长按$target"
            "swipe", "scroll" -> "滑动到$target"
            else -> "点击$target"
        }

    private fun mergeStringList(raw: Any?, value: String): List<String> {
        val merged = listArg(raw)
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .toMutableList()
        if (value.isNotBlank() && merged.none { it == value }) {
            merged += value
        }
        return merged
    }

    private fun setStringFieldIfChanged(
        target: MutableMap<String, Any?>,
        field: String,
        rawValue: Any?,
        changes: MutableList<Map<String, Any?>>,
        part: String,
        stepIndex: Int? = null,
    ) {
        val value = rawValue?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val old = target[field]?.toString()
        if (old == value) return
        target[field] = value
        changes += changeMap(part, field, old, value, stepIndex)
    }

    private fun changeMap(
        part: String,
        field: String,
        old: Any?,
        new: Any?,
        stepIndex: Int? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "part" to part,
        "field" to field,
        "step_index" to stepIndex,
        "old" to old,
        "new" to new,
    ).filterValues { it != null }

    private fun appendFunctionUpdateAudit(
        spec: MutableMap<String, Any?>,
        mode: String,
        instruction: String,
        changed: Boolean,
        dryRun: Boolean,
        changes: List<Map<String, Any?>>,
    ) {
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        metadata["oob_function_update"] = linkedMapOf(
            "schema_version" to "oob.function_update.v1",
            "tool" to "update_function",
            "mode" to mode,
            "status" to if (changed) "updated" else "unchanged",
            "changed" to changed,
            "dry_run" to dryRun,
            "instruction" to instruction.takeIf { it.isNotBlank() },
            "change_count" to changes.size,
            "updated_at_ms" to System.currentTimeMillis(),
        ).filterValues { it != null }
        if (mode == "enhance" || metadata["oob_enhancement"] != null) {
            metadata["oob_enhancement"] = linkedMapOf(
                "schema_version" to "oob.function_enhancement.v1",
                "source" to "update_function",
                "status" to if (changed) "enhanced" else "unchanged",
                "changed" to changed,
                "message" to if (changed) {
                    "Agent enhancement applied through update_function."
                } else {
                    "No safe useful enhancement was applied."
                },
                "updated_at_ms" to System.currentTimeMillis(),
            )
        }
        spec["metadata"] = metadata
    }

    private fun mutableJsonMap(value: Map<String, Any?>): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, mutableJsonValue(item))
            }
        }

    private fun mutableJsonList(value: List<Any?>): MutableList<Any?> =
        value.map { mutableJsonValue(it) }.toMutableList()

    private fun mutableJsonValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), mutableJsonValue(item))
                }
            }
            is List<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            is Array<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            else -> value
        }



    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = "",
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId,
    )

    private fun normalizeText(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }

    private fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }

    private fun intArg(vararg values: Any?, defaultValue: Int): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    private fun boolArg(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
            is Number -> value.toInt() != 0
            else -> false
        }

}
