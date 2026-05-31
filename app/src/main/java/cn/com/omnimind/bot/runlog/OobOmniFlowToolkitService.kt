package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * OOB-native implementation of the public OmniFlow agent toolkit surface.
 *
 * The service deliberately keeps the first version fixed and local: Functions
 * are registered in OOB stores, recall is deterministic, and execution runs
 * through the existing OOB replay dispatcher. External OmniFlow can replace this
 * class later behind the same `recall -> call_tool(function_id) -> ingest_run_log`
 * contract.
 */
class OobOmniFlowToolkitService(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    )
) {
    private val replayService = OobRunLogReplayService(context, workspaceFunctionStore)
    private val explorer = OobOmniFlowExplorer(context)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun recall(args: Map<String, Any?>?): Map<String, Any?> {
        val timing = RecallTiming()
        val request = timing.measure("parse_request_ms") { args ?: emptyMap() }
        val goal = firstNonBlank(request["goal"], request["query"], request["task"])
        val includeDebug = boolArg(request["include_debug"]) ||
            boolArg(request["includeDebug"]) ||
            boolArg(request["debug"])
        val currentPackage = timing.measure("read_current_package_ms") {
            firstNonBlank(
                request["current_package"],
                request["currentPackage"],
                runCatching { OmniflowActionRuntime.backend.currentPackageName() }.getOrNull(),
            )
        }
        val currentNodeId = firstNonBlank(request["current_node_id"], request["currentNodeId"])
        val k = intArg(request["k"], defaultValue = 8).coerceIn(1, 50)
        val allowDirectExecutionDecision = boolArg(request["auto_execute"]) ||
            boolArg(request["autoExecute"]) ||
            boolArg(request["allow_direct_hit"]) ||
            boolArg(request["allowDirectHit"]) ||
            firstNonBlank(
                request["decision_mode"],
                request["decisionMode"],
                request["execution_policy"],
                request["executionPolicy"],
            ).lowercase() in setOf("direct", "auto_execute", "auto-execute")
        val currentXml = timing.measure("read_current_page_ms") {
            firstNonBlank(
                request["current_xml"],
                request["currentXml"],
                request["xml"],
                request["page"],
                request["observation_xml"],
                request["observationXml"],
            ).ifBlank {
                runCatching { OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty() }
                    .getOrDefault("")
            }
        }
        if (currentXml.isBlank()) {
            val payload = linkedMapOf<String, Any?>(
                "success" to true,
                "decision" to "miss",
                "decision_path" to OobUdegNodeStore.UDEG_DECISION_PATH,
                "hit" to null,
                "candidates" to emptyList<Map<String, Any?>>(),
                "node_candidates" to emptyList<Map<String, Any?>>(),
                "count" to 0,
                "reason" to "missing_current_page_for_udeg_page_match",
                "current_package" to currentPackage.takeIf { it.isNotEmpty() },
                "current_node_id" to currentNodeId.takeIf { it.isNotEmpty() },
                "timing" to timing.finish(
                    decision = "miss",
                    counts = linkedMapOf(
                        "node_candidates" to 0,
                        "function_candidates" to 0,
                    )
                ),
                "source" to "oob_native_udeg_page_match",
            )
            return compactRecallPayload(payload, includeDebug)
        }

        val nodeStore = OobUdegNodeStore(context)
        val nodeMatches = timing.measure("page_match_ms") {
            nodeStore.recall(
                currentXml = currentXml,
                currentPackage = currentPackage,
                topK = k,
            )
        }
        val nodeCandidates = nodeMatches.map { it.toMap() }
        val decisionNodeMatches = nodeMatches.take(1)
        val nodeCapabilityRanking = timing.measure("rank_functions_ms") {
            rankNodeCapabilities(
                nodeMatches = decisionNodeMatches,
                goal = goal,
                currentPackage = currentPackage,
                topK = k,
            )
        }
        val ranked = nodeCapabilityRanking.functions

        val candidates = ranked.map { rankedFunction ->
            candidateMap(
                spec = rankedFunction.spec,
                score = rankedFunction.score,
                reason = rankedFunction.reason,
                extras = linkedMapOf(
                    "text_score" to roundScore(rankedFunction.textScore),
                    "page_similarity" to roundScore(rankedFunction.pageScore),
                    "udeg_node" to rankedFunction.node,
                    "node_skill_context" to rankedFunction.node["node_skill_context"],
                    "recall_scope" to "udeg_node",
                )
            )
        }
        val directHit = ranked.takeIf { it.size == 1 }?.firstOrNull {
            it.score >= DIRECT_HIT_SCORE &&
                it.pageScore >= DIRECT_HIT_SCORE &&
                it.textScore >= DIRECT_HIT_SCORE &&
                isNoArgumentFunction(it.spec)
        }.takeIf { allowDirectExecutionDecision }
        val decision = when {
            directHit != null -> "hit"
            nodeCandidates.isNotEmpty() -> "recall"
            else -> "miss"
        }

        val payload = linkedMapOf<String, Any?>(
            "success" to true,
            "decision" to decision,
            "decision_path" to OobUdegNodeStore.UDEG_DECISION_PATH,
            "hit" to directHit?.let {
                linkedMapOf(
                    "function_id" to it.functionId,
                    "inputSchema" to inputSchema(it.spec),
                    "score" to it.score,
                    "reason" to it.reason,
                    "text_score" to roundScore(it.textScore),
                    "page_similarity" to roundScore(it.pageScore),
                    "strict_direct_hit" to true,
                    "udeg_node" to it.node,
                    "node_skill_context" to it.node["node_skill_context"],
                    "step_summaries" to stepSummaries(it.spec)
                )
            },
            "candidates" to if (directHit == null) candidates else emptyList<Map<String, Any?>>(),
            "capability_candidates" to nodeCapabilityRanking.capabilities,
            "node_capabilities" to nodeCapabilityRanking.capabilities,
            "node_function_capabilities" to nodeCapabilityRanking.functionCapabilities,
            "node_candidates" to nodeCandidates,
            "current_node" to nodeCandidates.firstOrNull(),
            "node_skill" to (nodeCandidates.firstOrNull()?.get("skill")),
            "node_skill_context" to (nodeCandidates.firstOrNull()?.get("node_skill_context")),
            "decision_context" to (nodeCandidates.firstOrNull()?.get("decision_context")),
            "decision_policy" to linkedMapOf(
                "mode" to if (allowDirectExecutionDecision) "direct_execution_allowed" else "node_skill_context_only",
                "requires_vlm_or_tool_decision" to !allowDirectExecutionDecision,
                "direct_hit_requested" to allowDirectExecutionDecision,
                "direct_hit_min_score" to DIRECT_HIT_SCORE,
                "direct_hit_requires_single_candidate" to true,
                "direct_hit_requires_no_arguments" to true,
            ),
            "count" to candidates.size,
            "reason" to when {
                directHit != null -> "udeg_page_match_direct_function_hit"
                nodeCandidates.isEmpty() -> "no_udeg_node_page_match"
                nodeCapabilityRanking.capabilities.isEmpty() -> "udeg_node_match_without_attached_capability"
                candidates.isEmpty() -> "udeg_node_match_without_attached_function"
                else -> "udeg_node_skill_context_recall"
            },
            "current_package" to currentPackage.takeIf { it.isNotEmpty() },
            "current_node_id" to currentNodeId.takeIf { it.isNotEmpty() },
            "timing" to timing.finish(
                decision = decision,
                counts = linkedMapOf(
                    "node_candidates" to nodeCandidates.size,
                    "decision_node_candidates" to decisionNodeMatches.size,
                    "function_candidates" to ranked.size,
                    "node_capabilities" to nodeCapabilityRanking.capabilities.size,
                    "node_function_capabilities" to nodeCapabilityRanking.functionCapabilities.size,
                )
            ),
            "source" to "oob_native_udeg_page_match"
        )
        return compactRecallPayload(payload, includeDebug)
    }

    suspend fun callFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = FunctionCallTiming()
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val goal = firstNonBlank(request["goal"])
        val callArguments = mapArg(request["arguments"])
        if (functionId.isEmpty()) {
            return canonicalCallError(
                functionId = functionId,
                error = "call_function requires function_id",
                fallbackReason = "invalid_request"
            )
        }

        val guard = callTiming.measure("guard_check_ms") {
            guardCheck(
                linkedMapOf(
                    "functionId" to functionId,
                    "arguments" to callArguments,
                )
            )
        }
        val decision = guard["decision"]?.toString().orEmpty()
        if (decision == "block") {
            return canonicalCallError(
                functionId = functionId,
                error = guard["reason"]?.toString() ?: "Function blocked by guard",
                fallbackReason = "guard_blocked",
                guard = guard
            )
        }
        if (decision == "needs_confirmation") {
            return canonicalCallError(
                functionId = functionId,
                error = guard["reason"]?.toString() ?: "Function requires confirmation",
                fallbackReason = "confirmation_required",
                guard = guard
            )
        }

        var runPayload = callTiming.measureSuspend("execute_function_ms") {
            executeFunction(
                functionId = functionId,
                arguments = callArguments,
                allowAgentFallback = false
            )
        }
        runPayload = attachFunctionCallTiming(runPayload, callTiming)
        val success = runPayload["success"] == true && runPayload["model_required"] != true
        OobReusableFunctionStore.recordRun(
            context = context,
            functionId = functionId,
            success = success,
            runId = runPayload["run_id"]?.toString(),
            runner = runPayload["runner"]?.toString(),
            stepCount = intArg(runPayload["step_count"], defaultValue = 0),
            errorMessage = runPayload["error_message"]?.toString()
        )
        val fallback = false
        val fallbackReason = when {
            runPayload["error_code"] != null -> runPayload["error_code"]?.toString()
            decision == "needs_agent" -> "agent_fallback_required"
            !success -> runPayload["error_message"]?.toString()?.ifBlank { "execution_failed" }
            else -> ""
        }.orEmpty()

        return linkedMapOf<String, Any?>(
            "success" to success,
            "fallback" to fallback,
            "error" to if (success) null else runPayload["error_message"],
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "goal" to goal.takeIf { it.isNotEmpty() },
            "actions_executed" to (runPayload["success_step_count"] ?: 0),
            "step_results" to runPayload["step_results"],
            "timing" to runPayload["timing"],
            "control" to linkedMapOf(
                "fallback_reason" to fallbackReason,
                "guard_decision" to decision,
                "runner" to runPayload["runner"]
            ),
            "oob_result" to runPayload,
            "guard" to guard,
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    fun ingestRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["run_id"], request["runId"])
        val rawRunLog = mapArg(request["run_log"]).ifEmpty { mapArg(request["runLog"]) }
        val result = if (runId.isNotEmpty()) {
            replayService.convertRunLog(runId = runId, register = true)
        } else if (rawRunLog.isNotEmpty()) {
            ingestInlineRunLog(rawRunLog)
        } else {
            linkedMapOf(
                "success" to false,
                "error_code" to "RUN_LOG_EMPTY",
                "error_message" to "ingest_run_log requires run_id or run_log"
            )
        }
        val success = result["success"] == true
        return linkedMapOf<String, Any?>(
            "accepted" to success,
            "success" to success,
            "function_id" to result["function_id"],
            "created_function_id" to result["created_function_id"],
            "status" to when {
                !success -> "rejected"
                result["already_exists"] == true -> "updated"
                else -> "created"
            },
            "reason" to (result["error_message"] ?: ""),
            "result" to result,
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    suspend fun explore(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val register = boolArg(request["register"])
        val functionId = firstNonBlank(request["function_id"], request["functionId"])
        val name = firstNonBlank(request["name"])
        val description = firstNonBlank(request["description"])
        val exploreResult = explorer.explore(request)
        val success = exploreResult["success"] == true
        if (!success) {
            return linkedMapOf(
                "success" to false,
                "phase" to "explore",
                "explore" to exploreResult,
                "error_code" to exploreResult["error_code"],
                "error_message" to exploreResult["error_message"],
                "source" to "oob_native_omniflow_toolkit"
            )
        }
        if (!register) {
            return linkedMapOf(
                "success" to true,
                "phase" to "explore",
                "run_id" to exploreResult["run_id"],
                "utg" to exploreResult["utg"],
                "explore" to exploreResult,
                "registered" to false,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val convertResult = replayService.convertRunLog(
            runId = exploreResult["run_id"]?.toString().orEmpty(),
            register = true,
            functionIdOverride = functionId.takeIf { it.isNotEmpty() },
            nameOverride = name.takeIf { it.isNotEmpty() },
            descriptionOverride = description.takeIf { it.isNotEmpty() },
        )
        val converted = convertResult["success"] == true
        return linkedMapOf(
            "success" to converted,
            "phase" to if (converted) "registered" else "convert",
            "run_id" to exploreResult["run_id"],
            "function_id" to convertResult["function_id"],
            "created_function_id" to convertResult["created_function_id"],
            "registered" to converted,
            "utg" to exploreResult["utg"],
            "explore" to exploreResult,
            "convert" to convertResult,
            "error_code" to convertResult["error_code"],
            "error_message" to convertResult["error_message"],
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    suspend fun exploreAndReplay(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val exploreArgs = linkedMapOf<String, Any?>().apply {
            putAll(request)
            put("register", true)
        }
        val exploreResult = explore(exploreArgs)
        if (exploreResult["success"] != true) {
            return linkedMapOf(
                "success" to false,
                "phase" to (exploreResult["phase"] ?: "explore"),
                "explore" to exploreResult,
                "error_code" to exploreResult["error_code"],
                "error_message" to exploreResult["error_message"],
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val functionId = firstNonBlank(exploreResult["function_id"], exploreResult["created_function_id"])
        val packageName = firstNonBlank(
            request["package_name"],
            request["packageName"],
            request["target_package"],
            request["targetPackage"],
        )
        val shouldReplay = request["replay"] != false &&
            !request["replay"]?.toString().equals("false", ignoreCase = true)
        if (!shouldReplay) {
            return linkedMapOf(
                "success" to true,
                "phase" to "registered",
                "run_id" to exploreResult["run_id"],
                "function_id" to functionId,
                "utg" to exploreResult["utg"],
                "replay_skipped" to true,
                "explore" to exploreResult,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val settleDelayMs = longArg(
            request["settle_delay_ms"],
            request["settleDelayMs"],
            defaultValue = 800L
        ).coerceIn(100L, 5_000L)
        val resetBackSteps = intArg(
            request["reset_back_steps"],
            request["resetBackSteps"],
            defaultValue = 1
        ).coerceIn(0, 8)
        if (boolArg(request["reset_before_replay"]) || boolArg(request["resetBeforeReplay"])) {
            explorer.resetBeforeReplay(
                targetPackageName = packageName,
                backSteps = resetBackSteps,
                settleDelayMs = settleDelayMs,
            )
        }

        val replayResult = callFunction(
            linkedMapOf(
                "function_id" to functionId,
                "arguments" to mapArg(request["arguments"]),
                "goal" to firstNonBlank(request["goal"], request["query"], request["task"])
            )
        )
        val replaySuccess = replayResult["success"] == true
        return linkedMapOf(
            "success" to replaySuccess,
            "phase" to if (replaySuccess) "replayed" else "replay",
            "run_id" to exploreResult["run_id"],
            "function_id" to functionId,
            "explore" to exploreResult,
            "replay" to replayResult,
            "utg" to exploreResult["utg"],
            "error" to replayResult["error"],
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> =
        replayService.listFunctions(
            limit = intArg(args?.get("limit"), defaultValue = 100),
            offset = intArg(args?.get("offset"), defaultValue = 0)
        )

    fun getFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("functionId"), args?.get("function_id"))
        val spec = replayService.getFunctionSpec(functionId)
        if (spec == null) {
            return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId
            )
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("success", true)
            put("function", spec)
            put("function_id", firstNonBlank(OobFunctionSchemaBuilder.functionId(spec), functionId))
            put("summary", functionAgentSummary(spec))
            put("response_source", "oob_native_function_store")
        }
    }

    fun deleteFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("functionId"), args?.get("function_id"))
        return replayService.deleteFunction(functionId)
    }

    fun clearFunctions(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val confirmed = boolArg(request["confirm"]) ||
            boolArg(request["confirmed"]) ||
            firstNonBlank(request["action"]).equals("clear_all", ignoreCase = true)
        if (!confirmed) {
            return errorPayload(
                code = "OOB_FUNCTION_CLEAR_CONFIRMATION_REQUIRED",
                message = "Set confirm=true to clear all registered OOB Functions"
            )
        }
        return replayService.clearFunctions()
    }

    fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionSpec = functionSpecForRegistration(request)
        if (functionSpec.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_SPEC_EMPTY",
                message = "functionSpec or steps are required"
            )
        }
        val mode = if (hasExplicitFunctionSpec(request)) "function_spec" else "simple"
        return replayService.registerFunctionSpec(functionSpec) + linkedMapOf(
            "registration_input_mode" to mode,
            "simple_schema_supported" to true,
        )
    }

    fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        if (functionId.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "update_function requires function_id"
            )
        }
        val original = replayService.getFunctionSpec(functionId)
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
        val patch = mapArg(request["patch"])
            .ifEmpty { mapArg(request["functionPatch"]) }
            .ifEmpty { mapArg(request["function_patch"]) }
            .ifEmpty { mapArg(request["updates"]) }
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

        val save = replayService.registerFunctionSpec(updated)
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
                    applyCheckerRulesPatch(metadata, value, changes)
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
            applyCheckerRulesPatch(metadata, topLevelCheckerRules, changes)
            spec["metadata"] = metadata
        }

        applyOptionalCheckerMetadataFromSteps(spec, changes)
    }

    private fun applyCheckerRulesPatch(
        metadata: MutableMap<String, Any?>,
        rawRules: Any?,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val additions = listArg(rawRules)
            .mapNotNull { sanitizeCheckerRule(mapArg(it)) }
        if (additions.isEmpty()) return
        val existing = listArg(metadata["checker_rules"])
        val merged = mergeCheckerRules(existing, additions)
        if (existing != merged) {
            changes += changeMap("metadata", "checker_rules", existing.takeIf { it.isNotEmpty() }, merged)
            metadata["checker_rules"] = merged
        }
    }

    private fun applyOptionalCheckerMetadataFromSteps(
        spec: MutableMap<String, Any?>,
        changes: MutableList<Map<String, Any?>>,
    ) {
        val execution = mapArg(spec["execution"])
        val steps = listArg(execution["steps"])
            .mapNotNull { mapArg(it).takeIf { step -> step.isNotEmpty() } }
        if (steps.isEmpty()) return

        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        val existingRules = listArg(metadata["checker_rules"])
        val mergedRules = mergeCheckerRules(
            existingRules,
            steps.mapIndexedNotNull { index, step -> optionalCheckerRuleForStep(step, index) }
        )
        val signatureToId = checkerRuleSignatureToId(mergedRules)
        val checkerAssets = steps.mapIndexedNotNull { index, step ->
            val rule = optionalCheckerRuleForStep(step, index) ?: return@mapIndexedNotNull null
            val checkerId = signatureToId[checkerRuleSignature(rule)] ?: firstNonBlank(rule["id"])
            checkerAssetForStep(checkerId, step, index)
        }

        if (existingRules != mergedRules) {
            changes += changeMap(
                "metadata",
                "checker_rules",
                existingRules.takeIf { it.isNotEmpty() },
                mergedRules,
            )
            metadata["checker_rules"] = mergedRules
            spec["metadata"] = metadata
        }

        if (checkerAssets.isNotEmpty()) {
            val agentReuse = mutableJsonMap(mapArg(spec["agent_reuse"]))
            val existingAssets = listArg(agentReuse["checker_assets"])
            val mergedAssets = mergeCheckerAssets(existingAssets, checkerAssets)
            if (existingAssets != mergedAssets) {
                changes += changeMap(
                    "agent_reuse",
                    "checker_assets",
                    existingAssets.takeIf { it.isNotEmpty() },
                    mergedAssets,
                )
                agentReuse["checker_assets"] = mergedAssets
                spec["agent_reuse"] = agentReuse
            }
        }
    }

    private fun optionalCheckerRuleForStep(
        step: Map<String, Any?>,
        stepIndex: Int,
    ): Map<String, Any?>? {
        val annotation = mapArg(step["cleanup_annotation"])
        if (!isOptionalCheckerAnnotation(annotation)) return null
        val text = checkerInferenceText(step, annotation)
        val condition = when {
            containsAny(text, listOf("keyboard", "ime", "键盘", "输入法")) ->
                OmniflowCheckerRule.COND_KEYBOARD_OBSCURING
            containsAny(text, listOf("permission", "allow", "authorize", "grant", "权限", "授权", "允许")) ->
                OmniflowCheckerRule.COND_PERMISSION_DIALOG
            else -> OmniflowCheckerRule.COND_OVERLAY_BLOCKING
        }
        val action = checkerActionForCondition(condition)
        return linkedMapOf(
            "id" to "optional_checker_step_${stepIndex}_$condition",
            "phase" to checkerPhaseForCondition(condition),
            "condition" to condition,
            "action" to action,
            "enabled" to true,
            "params" to emptyMap<String, Any?>(),
        )
    }

    private fun isOptionalCheckerAnnotation(annotation: Map<String, Any?>): Boolean {
        val action = normalizeCleanupAction(
            firstNonBlank(
                annotation["cleanup_action"],
                annotation["cleanupAction"],
                annotation["action"],
            )
        )
        val usefulness = firstNonBlank(annotation["usefulness"]).lowercase()
        val category = firstNonBlank(annotation["category"]).lowercase()
        return action == "optional_checker" ||
            usefulness == "conditional_checker" ||
            category in setOf("conditional_obstruction", "runtime_checker", "checker_candidate")
    }

    private fun normalizeCleanupAction(raw: String): String =
        when (raw.trim().lowercase().replace('-', '_')) {
            "optional",
            "optional_checker",
            "conditional",
            "conditional_checker",
            "conditional_obstruction",
            "popup_checker",
            "ad_checker" -> "optional_checker"
            else -> raw.trim().lowercase().replace('-', '_')
        }

    private fun checkerInferenceText(
        step: Map<String, Any?>,
        annotation: Map<String, Any?>,
    ): String {
        val args = mapArg(step["args"])
        return listOf(
            step["title"],
            step["summary"],
            step["description"],
            annotation["optional_condition"],
            annotation["optionalCondition"],
            annotation["reason"],
            annotation["action_purpose"],
            args["target_description"],
            args["text"],
            args["content"],
        ).joinToString(" ") { it?.toString().orEmpty() }.lowercase()
    }

    private fun sanitizeCheckerRule(raw: Map<String, Any?>): Map<String, Any?>? {
        if (raw.isEmpty()) return null
        val condition = normalizeCheckerCondition(firstNonBlank(raw["condition"], raw["when"], raw["type"]))
        if (condition.isBlank()) return null
        val action = normalizeCheckerAction(firstNonBlank(raw["action"], raw["then"], raw["effect"]), condition)
        if (action.isBlank() || !isSupportedCheckerPair(condition, action)) return null
        val params = mutableMapOf<String, Any?>()
        val rawParams = mapArg(raw["params"])
        val packageName = firstNonBlank(
            rawParams["package_name"],
            rawParams["packageName"],
            raw["package_name"],
            raw["packageName"],
        )
        if (condition == OmniflowCheckerRule.COND_PACKAGE_MISMATCH &&
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName)
        ) {
            params["package_name"] = packageName
        }
        return linkedMapOf(
            "id" to safeCheckerRuleId(firstNonBlank(raw["id"], "function_checker")),
            "phase" to checkerPhaseForCondition(condition),
            "condition" to condition,
            "action" to action,
            "enabled" to boolArgOrDefault(raw["enabled"], true),
            "params" to params,
        )
    }

    private fun normalizeCheckerCondition(raw: String): String =
        when (raw.trim().lowercase().replace('-', '_')) {
            "overlay_blocking",
            "blocking_overlay",
            "popup_blocking",
            "popup",
            "ad_popup",
            "ad",
            "banner",
            "coupon",
            "obstruction",
            "conditional_obstruction" -> OmniflowCheckerRule.COND_OVERLAY_BLOCKING
            "permission_dialog",
            "permission",
            "permission_prompt",
            "permission_nudge" -> OmniflowCheckerRule.COND_PERMISSION_DIALOG
            "keyboard_obscuring",
            "keyboard",
            "ime_obscuring",
            "soft_keyboard" -> OmniflowCheckerRule.COND_KEYBOARD_OBSCURING
            "package_mismatch",
            "wrong_app",
            "app_mismatch",
            "foreground_package_mismatch" -> OmniflowCheckerRule.COND_PACKAGE_MISMATCH
            else -> ""
        }

    private fun normalizeCheckerAction(raw: String, condition: String): String {
        val text = raw.trim().lowercase().replace('-', '_')
        if (text.isBlank()) return checkerActionForCondition(condition)
        return when (text) {
            "dismiss",
            "close",
            "close_popup",
            "click_close",
            "click_dismiss",
            "skip" -> OmniflowCheckerRule.ACTION_DISMISS
            "allow",
            "grant",
            "grant_permission",
            "click_allow" -> OmniflowCheckerRule.ACTION_ALLOW
            "hide_keyboard",
            "dismiss_keyboard",
            "close_keyboard" -> OmniflowCheckerRule.ACTION_HIDE_KEYBOARD
            "open_app",
            "launch_app",
            "start_app" -> OmniflowCheckerRule.ACTION_OPEN_APP
            "click" -> when (condition) {
                OmniflowCheckerRule.COND_OVERLAY_BLOCKING -> OmniflowCheckerRule.ACTION_DISMISS
                OmniflowCheckerRule.COND_PERMISSION_DIALOG -> OmniflowCheckerRule.ACTION_ALLOW
                else -> ""
            }
            else -> ""
        }
    }

    private fun checkerActionForCondition(condition: String): String =
        when (condition) {
            OmniflowCheckerRule.COND_KEYBOARD_OBSCURING -> OmniflowCheckerRule.ACTION_HIDE_KEYBOARD
            OmniflowCheckerRule.COND_PERMISSION_DIALOG -> OmniflowCheckerRule.ACTION_ALLOW
            OmniflowCheckerRule.COND_PACKAGE_MISMATCH -> OmniflowCheckerRule.ACTION_OPEN_APP
            else -> OmniflowCheckerRule.ACTION_DISMISS
        }

    private fun checkerPhaseForCondition(condition: String): String =
        if (condition == OmniflowCheckerRule.COND_KEYBOARD_OBSCURING) {
            OmniflowCheckerRule.PHASE_PRE_ACTION
        } else {
            OmniflowCheckerRule.PHASE_PRE_TRANSFER
        }

    private fun isSupportedCheckerPair(condition: String, action: String): Boolean =
        (condition == OmniflowCheckerRule.COND_OVERLAY_BLOCKING &&
            action == OmniflowCheckerRule.ACTION_DISMISS) ||
            (condition == OmniflowCheckerRule.COND_PERMISSION_DIALOG &&
                action == OmniflowCheckerRule.ACTION_ALLOW) ||
            (condition == OmniflowCheckerRule.COND_KEYBOARD_OBSCURING &&
                action == OmniflowCheckerRule.ACTION_HIDE_KEYBOARD) ||
            (condition == OmniflowCheckerRule.COND_PACKAGE_MISMATCH &&
                action == OmniflowCheckerRule.ACTION_OPEN_APP)

    private fun mergeCheckerRules(
        existing: List<Any?>,
        additions: List<Map<String, Any?>>,
    ): List<Any?> {
        if (additions.isEmpty()) return existing
        val output = existing.map { mutableJsonValue(it) }.toMutableList()
        val signatures = existing.mapNotNull { raw ->
            sanitizeCheckerRule(mapArg(raw))?.let(::checkerRuleSignature)
        }.toMutableSet()
        val usedIds = existing.mapNotNull {
            firstNonBlank(mapArg(it)["id"]).takeIf(String::isNotBlank)
        }.toMutableSet()
        additions.forEach { rawRule ->
            val signature = checkerRuleSignature(rawRule)
            if (!signatures.add(signature)) return@forEach
            val rule = mutableJsonMap(rawRule)
            val id = uniqueCheckerRuleId(firstNonBlank(rule["id"], "function_checker"), usedIds)
            rule["id"] = id
            output += rule
        }
        return output
    }

    private fun checkerRuleSignatureToId(rules: List<Any?>): Map<String, String> =
        rules.mapNotNull { raw ->
            val sanitized = sanitizeCheckerRule(mapArg(raw)) ?: return@mapNotNull null
            checkerRuleSignature(sanitized) to firstNonBlank(mapArg(raw)["id"], sanitized["id"])
        }.toMap()

    private fun checkerRuleSignature(rule: Map<String, Any?>): String {
        val params = mapArg(rule["params"])
        return listOf(
            rule["phase"],
            rule["condition"],
            rule["action"],
            firstNonBlank(params["package_name"], params["packageName"]),
        ).joinToString("|") { it?.toString().orEmpty() }
    }

    private fun checkerAssetForStep(
        checkerId: String,
        step: Map<String, Any?>,
        stepIndex: Int,
    ): Map<String, Any?>? {
        if (checkerId.isBlank()) return null
        val annotation = mapArg(step["cleanup_annotation"])
        val reason = firstNonBlank(
            annotation["optional_condition"],
            annotation["reason"],
            annotation["action_purpose"],
            step["description"],
            step["summary"],
            step["title"],
        )
        return linkedMapOf(
            "checker_id" to checkerId,
            "step_index" to stepIndex,
            "step_id" to firstNonBlank(step["id"], "step_${stepIndex + 1}"),
            "role" to "checker_candidate",
            "materialization" to "metadata_checker_rule",
            "reason" to reason.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
    }

    private fun mergeCheckerAssets(
        existing: List<Any?>,
        additions: List<Map<String, Any?>>,
    ): List<Any?> {
        if (additions.isEmpty()) return existing
        val output = existing.map { mutableJsonValue(it) }.toMutableList()
        val seen = existing.mapNotNull { raw ->
            val asset = mapArg(raw)
            checkerAssetSignature(asset).takeIf { it.isNotBlank() }
        }.toMutableSet()
        additions.forEach { asset ->
            val signature = checkerAssetSignature(asset)
            if (signature.isNotBlank() && seen.add(signature)) {
                output += mutableJsonMap(asset)
            }
        }
        return output
    }

    private fun checkerAssetSignature(asset: Map<String, Any?>): String {
        val checkerId = firstNonBlank(asset["checker_id"], asset["checkerId"])
        val stepIndex = intArg(asset["step_index"], asset["stepIndex"], asset["index"], defaultValue = -1)
        val stepId = firstNonBlank(asset["step_id"], asset["stepId"])
        if (checkerId.isBlank() || stepIndex < 0) return ""
        return "$checkerId|$stepIndex|$stepId"
    }

    private fun safeCheckerRuleId(raw: String): String {
        val normalized = raw
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .lowercase()
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(80)
            .trim('_')
        return normalized.ifBlank { "function_checker" }
    }

    private fun uniqueCheckerRuleId(raw: String, usedIds: MutableSet<String>): String {
        val base = safeCheckerRuleId(raw)
        var candidate = base
        var suffix = 2
        while (candidate in usedIds) {
            val suffixText = "_$suffix"
            candidate = base.take((80 - suffixText.length).coerceAtLeast(1)).trimEnd('_') + suffixText
            suffix += 1
        }
        usedIds += candidate
        return candidate
    }

    private fun containsAny(text: String, needles: List<String>): Boolean =
        needles.any { text.contains(it) }

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

        val sourceXml = sourceXmlForStep(step, args)
        val desiredNode = findNodeByText(sourceXml, desiredText, action)
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
                normalizeSimpleRegisteredStep(
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
        val capabilities = simpleExecutionCapabilities(normalizedSteps)
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

    private fun sourceXmlForStep(step: Map<String, Any?>, args: Map<String, Any?>): String {
        val sourceContext = mapArg(step["source_context"])
            .ifEmpty { mapArg(args["source_context"]) }
        val srcCtx = mapArg(sourceContext["src_ctx"])
        return firstNonBlank(
            srcCtx["page"],
            srcCtx["xml"],
            sourceContext["page"],
            sourceContext["xml"],
        )
    }

    private data class XmlBounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val raw: String,
    ) {
        val centerX: Double get() = (left + right) / 2.0
        val centerY: Double get() = (top + bottom) / 2.0
        val area: Double get() = (right - left).coerceAtLeast(0.0) * (bottom - top).coerceAtLeast(0.0)
    }

    private data class XmlNodeMatch(
        val text: String,
        val contentDesc: String,
        val resourceId: String,
        val bounds: XmlBounds,
        val clickable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val score: Int,
    )

    private fun findNodeByText(xml: String, desiredText: String, action: String): XmlNodeMatch? {
        if (xml.isBlank() || desiredText.isBlank()) return null
        return parseXmlElements(xml).mapNotNull { element ->
            val bounds = parseXmlBounds(element.getAttribute("bounds")) ?: return@mapNotNull null
            val text = element.getAttribute("text").trim()
            val contentDesc = element.getAttribute("content-desc").trim()
            val resourceId = element.getAttribute("resource-id").trim()
            val label = listOf(text, contentDesc, resourceId).joinToString(" ")
            if (!containsLoose(label, desiredText)) return@mapNotNull null
            val clickable = element.boolAttr("clickable") || element.boolAttr("long-clickable")
            val enabled = !element.hasAttribute("enabled") || element.boolAttr("enabled")
            val visible = !element.hasAttribute("visible-to-user") || element.boolAttr("visible-to-user")
            val score = nodeTextScore(
                desiredText = desiredText,
                text = text,
                contentDesc = contentDesc,
                resourceId = resourceId,
                clickable = clickable,
                enabled = enabled,
                visible = visible,
                action = action,
                area = bounds.area,
            )
            XmlNodeMatch(
                text = text,
                contentDesc = contentDesc,
                resourceId = resourceId,
                bounds = bounds,
                clickable = clickable,
                enabled = enabled,
                visible = visible,
                score = score,
            )
        }.maxWithOrNull(
            compareBy<XmlNodeMatch> { it.score }
                .thenByDescending { it.clickable }
                .thenBy { it.bounds.area }
        )
    }

    private fun nodeTextScore(
        desiredText: String,
        text: String,
        contentDesc: String,
        resourceId: String,
        clickable: Boolean,
        enabled: Boolean,
        visible: Boolean,
        action: String,
        area: Double,
    ): Int {
        val desired = normalizeText(desiredText)
        var score = when {
            normalizeText(text) == desired -> 100
            normalizeText(contentDesc) == desired -> 96
            normalizeText(text).contains(desired) -> 85
            normalizeText(contentDesc).contains(desired) -> 80
            normalizeText(resourceId).contains(desired) -> 50
            else -> 0
        }
        if (score == 0) return 0
        if (visible) score += 8
        if (enabled) score += 6
        if (clickable && action in setOf("click", "long_press")) score += 10
        if (area <= 0.0) score -= 20
        return score
    }

    private fun parseXmlElements(xml: String): List<Element> {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            val document = factory.newDocumentBuilder()
                .parse(InputSource(StringReader(xml.trim())))
            val result = mutableListOf<Element>()
            collectElements(document.documentElement, result)
            result
        }.getOrDefault(emptyList())
    }

    private fun collectElements(node: Node?, result: MutableList<Element>) {
        if (node == null) return
        if (node is Element) result += node
        val children = node.childNodes ?: return
        for (index in 0 until children.length) {
            collectElements(children.item(index), result)
        }
    }

    private fun Element.boolAttr(name: String): Boolean {
        val value = getAttribute(name).trim().lowercase()
        return value == "true" || value == "1"
    }

    private fun parseXmlBounds(raw: String?): XmlBounds? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        val values = Regex("-?\\d+(?:\\.\\d+)?")
            .findAll(text)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (values.size < 4) return null
        return XmlBounds(
            left = values[0],
            top = values[1],
            right = values[2],
            bottom = values[3],
            raw = text,
        )
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

    private fun functionSpecForRegistration(request: Map<String, Any?>): Map<String, Any?> {
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

    private fun hasExplicitFunctionSpec(request: Map<String, Any?>): Boolean =
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

    private fun normalizeSimpleRegisteredStep(
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
                step["executor"] = "omniflow"
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
                step["executor"] = "omniflow"
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
                step["executor"] = "omniflow"
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
                step["executor"] = "tool"
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

    private fun simpleExecutionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "scriptable_step_count" to steps.count { it["scriptable"] == true },
            "model_free_step_count" to steps.count { it["model_free"] == true },
            "omniflow_step_count" to steps.count { it["executor"] == "omniflow" },
            "agent_step_count" to steps.count { it["executor"] == "agent" },
            "requires_agent_fallback" to steps.any { it["executor"] == "agent" },
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

    fun guardCheck(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val spec = replayService.getFunctionSpec(functionId)
            ?: return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId,
                decision = "block",
                riskLevel = "high"
            )
        val arguments = mapArg(request["arguments"])
        val missing = OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
        if (missing.isNotEmpty()) {
            return errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId,
                decision = "block",
                riskLevel = "high"
            ) + linkedMapOf("missing_required_arguments" to missing)
        }

        val materialized = OobReusableFunctionStore.materialize(spec, arguments)
        val stepDecisions = materializedSteps(materialized).map(::guardStep)
        val decision = aggregateDecision(stepDecisions)
        val riskLevel = aggregateRisk(stepDecisions)
        val reason = when (decision) {
            "allow" -> "All steps are deterministic local replay or registered tool calls."
            "needs_agent" -> "At least one step needs live Agent planning."
            "needs_confirmation" -> "At least one step can affect device state outside local UI replay."
            "block" -> "At least one step is blocked by OOB safety policy."
            else -> "Guard decision unavailable."
        }
        return linkedMapOf<String, Any?>(
            "success" to true,
            "function_id" to functionId,
            "decision" to decision,
            "risk_level" to riskLevel,
            "reason" to reason,
            "requires_confirmation" to (decision == "needs_confirmation"),
            "requires_root" to stepDecisions.any { it["requires_root"] == true },
            "step_decisions" to stepDecisions,
            "source" to "oob_native_omniflow_toolkit"
        ).filterValues { it != null }
    }

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = FunctionCallTiming()
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val arguments = mapArg(request["arguments"])
        val dryRun = boolArg(request["dryRun"]) || boolArg(request["dry_run"])
        val confirmed = boolArg(request["confirmed"]) || boolArg(request["userConfirmed"])
        val executionMode = firstNonBlank(request["executionMode"], request["execution_mode"])
            .ifBlank { "foreground" }

        val guard = callTiming.measure("guard_check_ms") {
            guardCheck(
                linkedMapOf(
                    "functionId" to functionId,
                    "arguments" to arguments,
                )
            )
        }
        val decision = guard["decision"]?.toString().orEmpty()
        if (dryRun) {
            return guard + linkedMapOf(
                "dry_run" to true,
                "execution_mode" to executionMode,
                "run_skipped" to true
            )
        }
        if (decision == "block") {
            return guard + linkedMapOf(
                "success" to false,
                "guard_decision" to decision,
                "run_skipped" to true
            )
        }
        if (decision == "needs_confirmation" && !confirmed) {
            return guard + linkedMapOf(
                "success" to false,
                "guard_decision" to decision,
                "needs_confirmation" to true,
                "run_skipped" to true
            )
        }

        var runPayload = callTiming.measureSuspend("execute_function_ms") {
            executeFunction(
                functionId = functionId,
                arguments = arguments,
                allowAgentFallback = false
            )
        }
        runPayload = attachFunctionCallTiming(runPayload, callTiming)
        OobReusableFunctionStore.recordRun(
            context = context,
            functionId = functionId,
            success = runPayload["success"] == true,
            runId = runPayload["run_id"]?.toString(),
            runner = runPayload["runner"]?.toString(),
            stepCount = intArg(runPayload["step_count"], defaultValue = 0),
            errorMessage = runPayload["error_message"]?.toString()
        )
        val stepResults = listArg(runPayload["step_results"])
        val timing = mapArg(runPayload["timing"])
        val startedAtMs = longArg(timing["started_at_ms"])
        val finishedAtMs = longArg(timing["finished_at_ms"])
        val durationMs = longArg(timing["call_duration_ms"], timing["duration_ms"], timing["runner_duration_ms"])
            .takeIf { it > 0L }
            ?: (finishedAtMs - startedAtMs).takeIf { startedAtMs > 0L && finishedAtMs >= startedAtMs }
            ?: stepResults.sumOf { raw ->
                longArg(mapArg(raw)["duration_ms"]).coerceAtLeast(0L)
            }
        val successStepCount = intArg(
            runPayload["success_step_count"],
            defaultValue = stepResults.count { raw -> mapArg(raw)["success"] != false },
        )
        return linkedMapOf<String, Any?>(
            "success" to (runPayload["success"] == true),
            "run_id" to runPayload["run_id"],
            "audit_run_id" to runPayload["audit_run_id"],
            "function_id" to functionId,
            "runner" to runPayload["runner"],
            "step_count" to intArg(runPayload["step_count"], defaultValue = stepResults.size),
            "success_step_count" to successStepCount,
            "actions_executed" to successStepCount,
            "guard_decision" to decision,
            "risk_level" to guard["risk_level"],
            "execution_mode" to executionMode,
            "step_results" to stepResults,
            "started_at_ms" to startedAtMs.takeIf { it > 0L },
            "finished_at_ms" to finishedAtMs.takeIf { it > 0L },
            "duration_ms" to durationMs,
            "runner_duration_ms" to durationMs,
            "timing" to timing,
            "needs_agent" to (runPayload["model_required"] == true),
            "needs_confirmation" to false,
            "error_message" to runPayload["error_message"],
            "guard" to guard,
            "result" to runPayload
        ).filterValues { it != null }
    }

    fun listRunLogs(args: Map<String, Any?>?): Map<String, Any?> {
        val limit = intArg(args?.get("limit"), defaultValue = 50).coerceIn(1, 200)
        val offset = intArg(args?.get("offset"), defaultValue = 0).coerceAtLeast(0)
        return InternalRunLogStore.listRuns(context, limit = limit, offset = offset)
    }

    fun getRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val runId = firstNonBlank(args?.get("runId"), args?.get("run_id"))
        if (runId.isEmpty()) {
            return errorPayload(code = "RUN_LOG_ID_EMPTY", message = "runId is required")
        }
        return InternalRunLogStore.timelinePayload(context, runId)
    }

    fun convertRunLog(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["runId"], request["run_id"])
        return replayService.convertRunLog(
            runId = runId,
            register = boolArgOrDefault(request["register"], defaultValue = true),
            functionIdOverride = firstNonBlank(request["functionId"], request["function_id"])
                .takeIf { it.isNotEmpty() },
            nameOverride = firstNonBlank(request["name"]).takeIf { it.isNotEmpty() },
            descriptionOverride = firstNonBlank(request["description"]).takeIf { it.isNotEmpty() }
        )
    }

    private fun ingestInlineRunLog(runLog: Map<String, Any?>): Map<String, Any?> {
        val runId = firstNonBlank(runLog["run_id"], runLog["runId"])
            .ifBlank { "inline_${System.currentTimeMillis()}" }
        val resultMap = mapArg(runLog["result"])
        val success = boolArg(runLog["success"]) || boolArg(resultMap["success"])
        val cards = listArg(runLog["cards"]).ifEmpty {
            listArg(runLog["steps"])
        }.mapNotNull { it as? Map<*, *> }.map(::stringMap)
        val record = InternalRunLogRecord(
            runId = runId,
            goal = firstNonBlank(runLog["goal"], runLog["task"]),
            source = firstNonBlank(runLog["source"]).ifBlank { "external_agent" },
            toolName = firstNonBlank(runLog["tool_name"], runLog["toolName"]),
            operationDescription = firstNonBlank(
                runLog["operation_description"],
                runLog["operationDescription"],
                runLog["goal"],
            ),
            startedAtMs = longArg(runLog["started_at_ms"], defaultValue = System.currentTimeMillis()),
            finishedAtMs = longArg(runLog["finished_at_ms"], defaultValue = System.currentTimeMillis()),
            success = success,
            doneReason = firstNonBlank(resultMap["done_reason"], runLog["done_reason"]),
            errorMessage = firstNonBlank(resultMap["error"], runLog["error_message"]),
            cards = cards,
        )
        if (record.success != true) {
            return errorPayload(
                code = "RUN_LOG_NOT_SUCCESSFUL",
                message = "RunLog did not finish successfully: ${record.runId}"
            )
        }
        val spec = RunLogReusableFunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps"
            )
        workspaceFunctionStore.mirrorRunLog(record)
        return replayService.registerFunctionSpec(spec) + linkedMapOf(
            "run_id" to record.runId,
            "function_spec" to spec
        )
    }

    private suspend fun executeFunction(
        functionId: String,
        arguments: Map<String, Any?>,
        allowAgentFallback: Boolean,
    ): Map<String, Any?> = withContext(Dispatchers.Default) {
        val timing = FunctionExecutionTiming()
        val spec = timing.measure("load_function_spec_ms") {
            replayService.getFunctionSpec(functionId)
        }
            ?: return@withContext errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId
            ).let { attachFunctionExecutionTiming(it, timing) }
        val missing = timing.measure("check_arguments_ms") {
            OobReusableFunctionStore.missingRequiredArguments(spec, arguments)
        }
        if (missing.isNotEmpty()) {
            return@withContext errorPayload(
                code = "OOB_FUNCTION_ARGUMENTS_MISSING",
                message = "Missing required arguments: ${missing.joinToString(", ")}",
                functionId = functionId
            ).let { attachFunctionExecutionTiming(it + linkedMapOf("missing_required_arguments" to missing), timing) }
        }
        val materialized = timing.measure("materialize_function_ms") {
            OobReusableFunctionStore.materialize(spec, arguments)
        }
        val runner = timing.measure("create_runner_ms") {
            OobFunctionToolHandler(
                context = context,
                helper = SharedHelper(context, json)
            ).apply {
                this.workspaceFunctionStore = workspaceFunctionStore
            }
        }
        val payload = runCatching {
            timing.measureSuspend("run_materialized_function_ms") {
                runner.runMaterializedFunction(
                    functionId = functionId,
                    spec = spec,
                    materializedSpec = materialized,
                    allowAgentFallback = allowAgentFallback,
                    allowToolDelegationWithoutRouter = false
                )
            }
        }.getOrElse { error ->
            errorPayload(
                code = "OOB_FUNCTION_RUN_FAILED",
                message = error.message.orEmpty(),
                functionId = functionId
            )
        }
        attachFunctionExecutionTiming(payload, timing)
    }

    private fun rankNodeCapabilities(
        nodeMatches: List<OobUdegNodeStore.RecallMatch>,
        goal: String,
        currentPackage: String,
        topK: Int,
    ): NodeCapabilityRanking {
        val rankedFunctions = mutableListOf<RankedFunction>()
        val functionCapabilities = mutableListOf<Map<String, Any?>>()
        nodeMatches.forEach { nodeMatch ->
            val node = nodeMatch.toMap()
            val pageScore = nodeMatch.pageSimilarity.toDouble()
            OobUdegNodeStore.functionSummaries(nodeMatch.node).forEach { functionSummary ->
                val functionId = firstNonBlank(functionSummary["function_id"])
                if (functionId.isBlank()) return@forEach
                val spec = replayService.getFunctionSpec(functionId) ?: return@forEach
                val textScore = scoreNodeFunctionText(
                    spec = spec,
                    function = functionSummary,
                    goal = goal,
                    currentPackage = currentPackage,
                )
                val combinedScore = (
                    PAGE_MATCH_WEIGHT * pageScore +
                        GOAL_MATCH_WEIGHT * textScore.score
                    ).coerceIn(0.0, 1.0)
                val rankedFunction = RankedFunction(
                    spec = spec,
                    functionId = functionId,
                    score = roundScore(combinedScore),
                    reason = "udeg_${nodeMatch.reason};${textScore.reason}",
                    textScore = textScore.score,
                    pageScore = pageScore,
                    node = node,
                    recallScope = "udeg_node",
                )
                rankedFunctions += rankedFunction
                functionCapabilities += nodeCapabilityMap(
                    node = node,
                    functionId = functionId,
                    capabilityType = "function",
                    score = rankedFunction.score,
                    textScore = textScore.score,
                    pageScore = pageScore,
                    reason = rankedFunction.reason,
                    spec = spec,
                    nodeCapability = functionSummary,
                )
            }
        }
        val limit = topK.coerceIn(1, 50)
        val sortedFunctionCapabilities = functionCapabilities.sortedWith(capabilityComparator())
        return NodeCapabilityRanking(
            functions = rankedFunctions
                .sortedWith(
                    compareByDescending<RankedFunction> { it.score }
                        .thenBy { it.functionId }
                )
                .take(limit),
            capabilities = sortedFunctionCapabilities.take(limit),
            functionCapabilities = sortedFunctionCapabilities.take(limit),
        )
    }

    private fun capabilityComparator(): Comparator<Map<String, Any?>> =
        compareByDescending<Map<String, Any?>> { numberDouble(it["score"]) }
            .thenByDescending { numberDouble(it["page_similarity"]) }
            .thenBy { it["function_id"]?.toString().orEmpty() }

    private fun nodeCapabilityMap(
        node: Map<String, Any?>,
        functionId: String,
        capabilityType: String,
        score: Double,
        textScore: Double,
        pageScore: Double,
        reason: String,
        spec: Map<String, Any?>,
        nodeCapability: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val functionSteps = stepSummaries(spec)
        val call = linkedMapOf<String, Any?>(
            "tool" to "call_tool",
            "function_id" to functionId,
            "arguments" to emptyMap<String, Any?>(),
        )
        return linkedMapOf(
            "capability_type" to capabilityType,
            "recall_scope" to "udeg_node",
            "function_id" to functionId,
            "name" to firstNonBlank(nodeCapability["name"], spec["name"], functionId),
            "description" to firstNonBlank(
                nodeCapability["description"],
                spec["description"],
                spec["name"],
                functionId
            ),
            "score" to roundScore(score),
            "text_score" to roundScore(textScore),
            "page_similarity" to roundScore(pageScore),
            "strict_direct_hit" to (
                score >= DIRECT_HIT_SCORE &&
                    pageScore >= DIRECT_HIT_SCORE &&
                    textScore >= DIRECT_HIT_SCORE
                ),
            "reason" to reason,
            "node_id" to firstNonBlank(node["node_id"]),
            "udeg_node" to node.takeIf { it.isNotEmpty() },
            "node_skill_context" to node["node_skill_context"],
            "node_capability" to nodeCapability.takeIf { it.isNotEmpty() },
            "inputSchema" to inputSchema(spec),
            "remaining_step_count" to functionSteps.size,
            "execution_scope" to "function",
            "call" to call,
            "step_summaries" to functionSteps,
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "oob_udeg_node_capability",
        ).filterValues { it != null }
    }

    private fun compactRecallPayload(
        payload: Map<String, Any?>,
        includeDebug: Boolean,
    ): Map<String, Any?> {
        if (includeDebug) {
            return linkedMapOf<String, Any?>().apply {
                putAll(payload)
                put("payload_mode", "debug_full")
            }
        }
        return linkedMapOf<String, Any?>().apply {
            put("success", payload["success"])
            put("decision", payload["decision"])
            put("decision_path", payload["decision_path"])
            put("hit", compactRecallCandidate(payload["hit"]))
            put(
                "candidates",
                listArg(payload["candidates"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "capability_candidates",
                listArg(payload["capability_candidates"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "node_capabilities",
                listArg(payload["node_capabilities"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "node_function_capabilities",
                listArg(payload["node_function_capabilities"]).mapNotNull { compactRecallCandidate(it) }
            )
            put(
                "node_candidates",
                listArg(payload["node_candidates"]).mapNotNull { compactRecallNode(it) }
            )
            put("current_node", compactRecallNode(payload["current_node"]))
            put("node_skill_context", compactNodeSkillContext(payload["node_skill_context"]))
            put("decision_context", compactDecisionContext(payload["decision_context"]))
            put("decision_policy", payload["decision_policy"])
            put("count", payload["count"])
            put("reason", payload["reason"])
            put("current_package", payload["current_package"])
            put("current_node_id", payload["current_node_id"])
            put("source", payload["source"])
            put("payload_mode", "agent_compact")
            put("debug_available", true)
        }.filterValues { it != null }
    }

    private fun compactRecallCandidate(value: Any?): Map<String, Any?>? {
        val candidate = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "capability_type" to candidate["capability_type"],
            "function_id" to candidate["function_id"],
            "description" to candidate["description"],
            "name" to candidate["name"],
            "inputSchema" to candidate["inputSchema"],
            "score" to candidate["score"],
            "text_score" to candidate["text_score"],
            "page_similarity" to candidate["page_similarity"],
            "strict_direct_hit" to candidate["strict_direct_hit"],
            "reason" to candidate["reason"],
            "node_id" to firstNonBlank(
                candidate["node_id"],
                mapArg(candidate["udeg_node"])["node_id"],
            ).takeIf { it.isNotBlank() },
            "node_skill_context" to compactNodeSkillContext(candidate["node_skill_context"]),
            "recall_scope" to candidate["recall_scope"],
            "remaining_step_count" to candidate["remaining_step_count"],
            "requires_arguments" to candidate["requires_arguments"],
            "execution_scope" to candidate["execution_scope"],
            "call" to candidate["call"],
            "step_count" to candidate["step_count"],
            "requires_agent_fallback" to candidate["requires_agent_fallback"],
            "step_summaries" to listArg(candidate["step_summaries"]).mapNotNull {
                compactStepSummary(it)
            },
            "function_kind" to candidate["function_kind"],
            "asset_state" to candidate["asset_state"],
            "source" to candidate["source"],
        ).filterValues { it != null }
    }

    private fun compactRecallNode(value: Any?): Map<String, Any?>? {
        val node = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "node_id" to node["node_id"],
            "package_name" to node["package_name"],
            "page_similarity" to node["page_similarity"],
            "reason" to node["reason"],
            "decision_context" to compactDecisionContext(node["decision_context"]),
            "node_skill_context" to compactNodeSkillContext(node["node_skill_context"]),
            "function_ids" to listArg(node["function_ids"]).mapNotNull {
                it?.toString()?.trim()?.takeIf(String::isNotEmpty)
            },
            "source" to node["source"],
        ).filterValues { it != null }
    }

    private fun compactNodeSkillContext(value: Any?): Map<String, Any?>? {
        val context = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        val pageMatch = mapArg(context["page_match"])
        val udegNode = mapArg(context["udeg_node"])
        return linkedMapOf<String, Any?>(
            "schema_version" to context["schema_version"],
            "role" to context["role"],
            "context_kind" to context["context_kind"],
            "decision_path" to context["decision_path"],
            "entry_policy" to context["entry_policy"],
            "page_match" to linkedMapOf(
                "node_id" to pageMatch["node_id"],
                "page_similarity" to pageMatch["page_similarity"],
                "reason" to pageMatch["reason"],
            ).filterValues { it != null }.takeIf { it.isNotEmpty() },
            "udeg_node" to linkedMapOf(
                "node_id" to udegNode["node_id"],
                "package_name" to udegNode["package_name"],
                "activity_name" to udegNode["activity_name"],
            ).filterValues { it != null }.takeIf { it.isNotEmpty() },
            "decision_context" to compactDecisionContext(context["decision_context"]),
            "attached_function_count" to listArg(context["attached_functions"]).size,
        ).filterValues { it != null }
    }

    private fun compactDecisionContext(value: Any?): Map<String, Any?>? {
        val context = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "schema_version" to context["schema_version"],
            "role" to context["role"],
            "entry_policy" to context["entry_policy"],
            "skill_id" to context["skill_id"],
            "decision_path" to context["decision_path"],
            "function_count" to context["function_count"],
            "page_analysis" to compactPageAnalysis(context["page_analysis"]),
        ).filterValues { it != null }
    }

    private fun compactPageAnalysis(value: Any?): Map<String, Any?>? {
        val analysis = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "package_name" to analysis["package_name"],
            "activity_name" to analysis["activity_name"],
            "page_title" to analysis["page_title"],
            "summary" to analysis["summary"],
            "visible_text" to listArg(analysis["visible_text"]).take(8),
            "primary_actions" to listArg(analysis["primary_actions"]).take(8),
        ).filterValues { it != null }
    }

    private fun compactStepSummary(value: Any?): Map<String, Any?>? {
        val step = mapArg(value).takeIf { it.isNotEmpty() } ?: return null
        return linkedMapOf<String, Any?>(
            "index" to step["index"],
            "id" to step["id"],
            "title" to step["title"],
            "kind" to step["kind"],
            "tool" to step["tool"],
        ).filterValues { it != null }
    }

    private fun scoreNodeFunctionText(
        spec: Map<String, Any?>,
        function: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): FunctionTextScore {
        val base = scoreFunctionText(spec, goal, currentPackage)
        val capabilityCorpus = listOf(
            function["function_id"],
            function["name"],
            function["description"],
            listArg(function["step_summaries"]).joinToString(" ") { raw ->
                val step = mapArg(raw)
                listOf(step["title"], step["tool"], step["id"])
                    .joinToString(" ")
            },
        ).joinToString(" ").trim()
        val capabilityScore = if (goal.isBlank() || capabilityCorpus.isBlank()) {
            0.0
        } else {
            tokenOverlapScore(goal, capabilityCorpus)
        }
        return if (capabilityScore > base.score) {
            FunctionTextScore(capabilityScore, "node_function_capability_match")
        } else {
            base
        }
    }

    private fun scoreFunctionText(
        spec: Map<String, Any?>,
        goal: String,
        currentPackage: String,
    ): FunctionTextScore {
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
        val name = spec["name"]?.toString()?.trim().orEmpty()
        val description = spec["description"]?.toString()?.trim().orEmpty()
        val source = mapArg(spec["source"])
        val corpus = listOf(
            functionId,
            name,
            description,
            source["goal"]?.toString().orEmpty(),
            source["tool_name"]?.toString().orEmpty(),
        ).joinToString(" ").trim()
        if (goal.isBlank()) {
            return FunctionTextScore(0.25, "empty_goal")
        }

        val normalizedGoal = normalizeText(goal)
        val normalizedId = normalizeText(functionId)
        val normalizedName = normalizeText(name)
        val normalizedDescription = normalizeText(description)
        val normalizedSourceGoal = normalizeText(source["goal"]?.toString().orEmpty())
        var score = when {
            normalizedGoal == normalizedId -> 1.0
            normalizedGoal == normalizedName && normalizedName.isNotEmpty() -> 0.99
            normalizedGoal == normalizedDescription && normalizedDescription.isNotEmpty() -> 0.96
            normalizedGoal == normalizedSourceGoal && normalizedSourceGoal.isNotEmpty() -> 0.98
            normalizedId.contains(normalizedGoal) || normalizedGoal.contains(normalizedId) -> 0.92
            normalizedName.contains(normalizedGoal) || normalizedGoal.contains(normalizedName) -> 0.90
            normalizedSourceGoal.contains(normalizedGoal) || normalizedGoal.contains(normalizedSourceGoal) -> 0.90
            else -> tokenOverlapScore(goal, corpus)
        }
        var reason = when {
            score >= 0.97 -> "exact_match"
            score >= 0.85 -> "text_match"
            else -> "token_overlap"
        }
        if (currentPackage.isNotEmpty() && packageScopeMatches(spec, currentPackage)) {
            score = (score + 0.05).coerceAtMost(1.0)
            reason = "${reason}_package_scope"
        }
        return FunctionTextScore(roundScore(score), reason)
    }

    private fun tokenOverlapScore(goal: String, corpus: String): Double {
        val goalTokens = tokenize(goal)
        if (goalTokens.isEmpty()) return 0.0
        val corpusTokens = tokenize(corpus).toSet()
        val overlap = goalTokens.count { it in corpusTokens }
        return if (overlap == 0) 0.0 else 0.30 + 0.55 * (overlap.toDouble() / goalTokens.size)
    }

    private fun packageScopeMatches(spec: Map<String, Any?>, currentPackage: String): Boolean {
        val constraints = mapArg(spec["constraints"])
        val source = mapArg(spec["source"])
        val candidates = listOf(
            constraints["package_name"],
            constraints["packageName"],
            source["package_name"],
            source["packageName"],
        ).map { it?.toString()?.trim().orEmpty() }
        return candidates.any { it.isNotEmpty() && it == currentPackage }
    }

    private fun candidateMap(
        spec: Map<String, Any?>,
        score: Double,
        reason: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val execution = mapArg(spec["execution"])
        val steps = materializedSteps(spec)
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
        return linkedMapOf<String, Any?>(
            "function_id" to functionId,
            "description" to (spec["description"] ?: spec["name"] ?: functionId),
            "name" to spec["name"],
            "inputSchema" to inputSchema(spec),
            "score" to score,
            "reason" to reason,
            "step_count" to (execution["step_count"] ?: steps.size),
            "requires_agent_fallback" to execution["requires_agent_fallback"],
            "step_summaries" to stepSummaries(spec),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local"
        ).apply { putAll(extras) }
    }

    private fun functionAgentSummary(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = mapArg(spec["execution"])
        val steps = materializedSteps(spec)
        val functionId = OobFunctionSchemaBuilder.functionId(spec)
        return linkedMapOf(
            "function_id" to functionId,
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (execution["step_count"] ?: steps.size),
            "omniflow_step_count" to execution["omniflow_step_count"],
            "agent_step_count" to execution["agent_step_count"],
            "requires_agent_fallback" to execution["requires_agent_fallback"],
            "parameter_names" to OobFunctionSchemaBuilder.parameterNames(spec),
            "step_summaries" to stepSummaries(spec),
            "source" to spec["source"],
            "constraints" to spec["constraints"],
        ).filterValues { it != null }
    }

    private fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.stepSummaries(spec)
    }

    private fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> {
        return OobFunctionSchemaBuilder.inputSchema(spec)
    }

    private fun isNoArgumentFunction(spec: Map<String, Any?>): Boolean {
        val schema = inputSchema(spec)
        val required = listArg(schema["required"])
        val properties = mapArg(schema["properties"])
        return required.isEmpty() && properties.isEmpty()
    }

    private fun guardStep(step: Map<String, Any?>): Map<String, Any?> {
        val stepId = firstNonBlank(step["id"], step["step_id"])
        val tool = firstNonBlank(step["tool"], step["omniflow_action"], step["callable_tool"], step["type"])
        val normalizedTool = RunLogReplayPolicy.normalizeToolName(tool)
        val action = OobActionCodec.canonicalActionForName(normalizedTool) ?: normalizedTool
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val decision: String
        val risk: String
        val reason: String
        val requiresRoot: Boolean
        when {
            RunLogReplayPolicy.shouldSkipTool(tool) || RunLogReplayPolicy.shouldSkipTool(action) -> {
                decision = "allow"
                risk = "low"
                reason = "$action is an observation-only or legacy non-semantic replay step"
                requiresRoot = false
            }
            action == "finished" -> {
                decision = "allow"
                risk = "low"
                reason = "finished is a terminal marker"
                requiresRoot = false
            }
            isBlockedAction(action, step) -> {
                decision = "block"
                risk = "high"
                reason = "$action is blocked by OOB policy"
                requiresRoot = true
            }
            isConfirmationAction(action, step) -> {
                decision = "needs_confirmation"
                risk = "high"
                reason = "$action requires user confirmation"
                requiresRoot = action.contains("root") || action.contains("shizuku")
            }
            OmniflowStepExecutor.isOmniflowStep(step) -> {
                decision = "allow"
                risk = "low"
                reason = "$action is a deterministic local replay action"
                requiresRoot = false
            }
            RunLogReplayPolicy.isOmniflowExecutionTool(action) -> {
                decision = "allow"
                risk = if (RunLogReplayPolicy.isOmniflowFunctionTool(action)) "medium" else "low"
                reason = "$action is handled by OOB native OmniFlow execution"
                requiresRoot = false
            }
            executor == "agent" || RunLogReplayPolicy.isAgentTool(action) -> {
                decision = "needs_agent"
                risk = "medium"
                reason = "$action requires live Agent planning"
                requiresRoot = false
            }
            else -> {
                decision = "needs_agent"
                risk = "medium"
                reason = "$action is not a fixed local replay action"
                requiresRoot = false
            }
        }
        return linkedMapOf(
            "step_id" to stepId,
            "tool" to tool,
            "decision" to decision,
            "risk_level" to risk,
            "reason" to reason,
            "requires_root" to requiresRoot
        )
    }

    private fun aggregateDecision(stepDecisions: List<Map<String, Any?>>): String {
        val decisions = stepDecisions.map { it["decision"]?.toString().orEmpty() }
        return when {
            decisions.contains("block") -> "block"
            decisions.contains("needs_confirmation") -> "needs_confirmation"
            decisions.contains("needs_agent") -> "needs_agent"
            else -> "allow"
        }
    }

    private fun aggregateRisk(stepDecisions: List<Map<String, Any?>>): String {
        val risks = stepDecisions.map { it["risk_level"]?.toString().orEmpty() }
        return when {
            risks.contains("high") -> "high"
            risks.contains("medium") -> "medium"
            else -> "low"
        }
    }

    private fun isBlockedAction(action: String, step: Map<String, Any?>): Boolean {
        val text = guardScanText(step)
        return BLOCKED_ACTION_TOKENS.any { action.contains(it) || text.contains(it) }
    }

    private fun isConfirmationAction(action: String, step: Map<String, Any?>): Boolean {
        val text = guardScanText(step)
        return CONFIRMATION_ACTION_TOKENS.any { action.contains(it) || text.contains(it) }
    }

    private fun guardScanText(step: Map<String, Any?>): String {
        val parts = mutableListOf<String>()
        for (key in GUARD_SCAN_KEYS) {
            val value = step[key] ?: continue
            parts += when (key) {
                "args" -> sanitizedGuardArgs(value).toString()
                else -> value.toString()
            }
        }
        return parts.joinToString(" ").lowercase()
    }

    private fun sanitizedGuardArgs(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (rawKey, rawItem) ->
                    val key = rawKey?.toString() ?: return@forEach
                    if (key in GUARD_CONTEXT_KEYS) return@forEach
                    put(key, sanitizedGuardArgs(rawItem))
                }
            }
            is List<*> -> value.map(::sanitizedGuardArgs)
            is Array<*> -> value.map(::sanitizedGuardArgs)
            else -> value
        }
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.materializedSteps(spec)
    }

    private fun canonicalCallError(
        functionId: String,
        error: String,
        fallbackReason: String,
        guard: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "fallback" to true,
        "error" to error,
        "run_id" to null,
        "function_id" to functionId,
        "actions_executed" to 0,
        "control" to linkedMapOf(
            "fallback_reason" to fallbackReason
        ),
        "guard" to guard,
        "source" to "oob_native_omniflow_toolkit"
    )

    private fun errorPayload(
        code: String,
        message: String,
        functionId: String = "",
        decision: String? = null,
        riskLevel: String? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "function_id" to functionId
    ).apply {
        decision?.let { put("decision", it) }
        riskLevel?.let { put("risk_level", it) }
    }

    private fun normalizeText(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun tokenize(value: String): List<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(value.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toList()

    private fun roundScore(value: Double): Double =
        ((value.coerceIn(0.0, 1.0) * 1000.0).roundToInt() / 1000.0)

    private fun numberDouble(value: Any?): Double =
        when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun mapArg(value: Any?): Map<String, Any?> {
        return when (value) {
            is Map<*, *> -> stringMap(value)
            else -> emptyMap()
        }
    }

    private fun stringMap(value: Map<*, *>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) put(key.toString(), item)
            }
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

    private fun longArg(vararg values: Any?, defaultValue: Long = 0L): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.trim().toLongOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    private fun boolArg(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true) ||
                value.trim() == "1"
            is Number -> value.toInt() != 0
            else -> false
        }

    private fun boolArgOrDefault(value: Any?, defaultValue: Boolean): Boolean =
        when (value) {
            null -> defaultValue
            is Boolean -> value
            is String -> {
                val text = value.trim().lowercase()
                when (text) {
                    "true", "1", "yes", "y", "on" -> true
                    "false", "0", "no", "n", "off" -> false
                    else -> defaultValue
                }
            }
            is Number -> value.toInt() != 0
            else -> defaultValue
        }

    private data class RankedFunction(
        val spec: Map<String, Any?>,
        val functionId: String,
        val score: Double,
        val reason: String,
        val textScore: Double = score,
        val pageScore: Double = 0.0,
        val node: Map<String, Any?> = emptyMap(),
        val recallScope: String = "udeg_node",
    )

    private data class FunctionTextScore(
        val score: Double,
        val reason: String,
    )

    private data class NodeCapabilityRanking(
        val functions: List<RankedFunction>,
        val capabilities: List<Map<String, Any?>>,
        val functionCapabilities: List<Map<String, Any?>>,
    )

    private fun attachFunctionExecutionTiming(
        payload: Map<String, Any?>,
        timing: FunctionExecutionTiming,
    ): Map<String, Any?> {
        val toolkitTiming = timing.finish()
        val toolkitPhaseMs = mapArg(toolkitTiming["phase_ms"])
        val existingTiming = mapArg(payload["timing"])
        val runnerSource = existingTiming["source"]?.toString().orEmpty()
        val runnerPhaseMs = mapArg(existingTiming["phase_ms"])
        val runnerStartedAtMs = longArg(existingTiming["started_at_ms"])
        val runnerFinishedAtMs = longArg(existingTiming["finished_at_ms"])
        val runnerDurationMs = longArg(
            existingTiming["runner_duration_ms"],
            existingTiming["duration_ms"],
        )
        val startupPhaseMs = linkedMapOf<String, Any?>()
        listOf(
            "load_function_spec_ms",
            "check_arguments_ms",
            "materialize_function_ms",
            "create_runner_ms",
        ).forEach { phaseName ->
            startupPhaseMs[phaseName] = longArg(toolkitPhaseMs[phaseName])
        }
        if (runnerPhaseMs.isNotEmpty()) {
            startupPhaseMs["runner_pre_step_loop_ms"] = longArg(runnerPhaseMs["pre_step_loop_ms"])
        }
        val startupDurationMs = startupPhaseMs.values.sumOf { longArg(it) }
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            if (runnerSource.isNotBlank()) put("runner_source", runnerSource)
            if (runnerStartedAtMs > 0L) put("runner_started_at_ms", runnerStartedAtMs)
            if (runnerFinishedAtMs > 0L) put("runner_finished_at_ms", runnerFinishedAtMs)
            if (runnerDurationMs > 0L) put("runner_duration_ms", runnerDurationMs)
            if (runnerPhaseMs.isNotEmpty()) put("runner_phase_ms", runnerPhaseMs)
            put("source", "oob_function_execute")
            put("started_at_ms", toolkitTiming["started_at_ms"])
            put("finished_at_ms", toolkitTiming["finished_at_ms"])
            put("duration_ms", toolkitTiming["duration_ms"])
            put("phase_ms", toolkitPhaseMs)
            put("startup_phase_ms", startupPhaseMs)
            put("startup_duration_ms", startupDurationMs)
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
    }

    private fun attachFunctionCallTiming(
        payload: Map<String, Any?>,
        timing: FunctionCallTiming,
    ): Map<String, Any?> {
        val callTiming = timing.finish()
        val existingTiming = mapArg(payload["timing"])
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(existingTiming)
            put("call_started_at_ms", callTiming["started_at_ms"])
            put("call_finished_at_ms", callTiming["finished_at_ms"])
            put("call_duration_ms", callTiming["duration_ms"])
            put("call_phase_ms", callTiming["phase_ms"])
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
    }

    private class FunctionExecutionTiming {
        private val startedAtNanos = System.nanoTime()
        val startedAtMs: Long = System.currentTimeMillis()
        private val phases = linkedMapOf<String, Long>()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        suspend fun <T> measureSuspend(phaseName: String, block: suspend () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        fun finish(): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "load_function_spec_ms",
                "check_arguments_ms",
                "materialize_function_ms",
                "create_runner_ms",
                "run_materialized_function_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            return linkedMapOf(
                "source" to "oob_function_execute",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to completedPhases,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private class FunctionCallTiming {
        private val startedAtNanos = System.nanoTime()
        val startedAtMs: Long = System.currentTimeMillis()
        private val phases = linkedMapOf<String, Long>()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        suspend fun <T> measureSuspend(phaseName: String, block: suspend () -> T): T {
            val phaseStartedAtNanos = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAtNanos)
            }
        }

        fun finish(): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "guard_check_ms",
                "execute_function_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            return linkedMapOf(
                "source" to "oob_function_call",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to completedPhases,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private class RecallTiming {
        private val startedAtNanos = System.nanoTime()
        private val phases = linkedMapOf<String, Long>()
        val startedAtMs: Long = System.currentTimeMillis()

        fun <T> measure(phaseName: String, block: () -> T): T {
            val phaseStartedAt = System.nanoTime()
            return try {
                block()
            } finally {
                phases[phaseName] = elapsedMs(phaseStartedAt)
            }
        }

        fun finish(
            decision: String,
            counts: Map<String, Any?>,
        ): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val completedPhases = linkedMapOf<String, Long>()
            listOf(
                "parse_request_ms",
                "read_current_package_ms",
                "read_current_page_ms",
                "page_match_ms",
                "rank_functions_ms",
            ).forEach { phaseName ->
                completedPhases[phaseName] = phases[phaseName] ?: 0L
            }
            phases.forEach { (phaseName, durationMs) ->
                completedPhases.putIfAbsent(phaseName, durationMs)
            }
            return linkedMapOf(
                "source" to "oob_omniflow_recall",
                "decision" to decision,
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to elapsedMs(startedAtNanos),
                "phase_ms" to completedPhases,
                "counts" to counts,
            )
        }

        private fun elapsedMs(startedAtNanos: Long): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    companion object {
        private const val MIN_RECALL_SCORE = 0.30
        private const val DIRECT_HIT_SCORE = 0.999
        private const val PAGE_MATCH_WEIGHT = 0.70
        private const val GOAL_MATCH_WEIGHT = 0.30
        private val CONFIRMATION_ACTION_TOKENS = setOf(
            "shell",
            "terminal",
            "settings_write",
            "permission_grant",
            "permission_revoke",
            "package.install",
            "install_apk",
            "force_stop",
            "shizuku",
            "root",
        )
        private val BLOCKED_ACTION_TOKENS = setOf(
            "reboot",
            "shutdown",
            "fastboot",
            "format",
            "block_device",
            "system_partition",
            "dd if=",
            "mkfs",
        )
        private val GUARD_SCAN_KEYS = setOf(
            "title",
            "kind",
            "executor",
            "tool",
            "callable_tool",
            "omniflow_action",
            "local_action",
            "type",
            "args",
        )
        private val GUARD_CONTEXT_KEYS = setOf(
            "source_context",
            "src_ctx",
            "page",
            "xml",
            "observation_xml",
            "screenshot",
            "image",
        )
    }
}
