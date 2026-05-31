package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mutableJsonMap

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
    private val metadataPatchApplier: OobFunctionMetadataPatchApplier = OobFunctionMetadataPatchApplier(checkerPatchService),
    private val targetSourceMatcher: OobFunctionTargetSourceMatcher = OobFunctionTargetSourceMatcher(),
    private val structuralPatchApplier: OobFunctionStructuralPatchApplier = OobFunctionStructuralPatchApplier(specBuilder, targetSourceMatcher),
    private val evidencePackager: OobFunctionRunLogEvidencePackager = OobFunctionRunLogEvidencePackager(),
    private val intentParser: OobFunctionUpdateIntentParser = OobFunctionUpdateIntentParser(),
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
        val explicitOps = intentParser.operationsFromPatch(patch)
        val inferredOps = if (explicitOps.isEmpty()) {
            intentParser.operationsFromInstruction(instruction)
        } else {
            emptyList()
        }
        val ops = explicitOps + inferredOps
        val inferredRepairIntent = requestedMode == "enhance" && ops.any(intentParser::isReplaceTargetOperation)
        val inferredStructuralIntent = requestedMode == "enhance" && ops.any(intentParser::isStructuralOperation)
        val mode = if (inferredRepairIntent || inferredStructuralIntent) "repair" else requestedMode
        val allowExecutionChange = boolArg(request["allowExecutionChange"]) ||
            boolArg(request["allow_execution_change"]) ||
            mode in setOf("repair", "fix", "correction")
        val allowStructuralChange = boolArg(request["allowStructuralChange"]) ||
            boolArg(request["allow_structural_change"])

        if (patch.isNotEmpty()) {
            changes += metadataPatchApplier.applyPatch(updated, patch)
        }
        if (analysis.isNotEmpty()) {
            changes += metadataPatchApplier.applyRunLogEvidenceAnalysis(
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
                    val result = structuralPatchApplier.applyReplaceTargetOperation(updated, op)
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
                    changes += structuralPatchApplier.applyInsertStepOperation(updated, op)
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
                    changes += structuralPatchApplier.applyDeleteStepOperation(updated, op)
                }
            }
        }

        val changed = changes.isNotEmpty()
        metadataPatchApplier.appendUpdateAudit(
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

}
