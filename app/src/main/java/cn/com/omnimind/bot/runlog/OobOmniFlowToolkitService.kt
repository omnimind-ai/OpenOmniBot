package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.omniflow.OobFunctionRecallService
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.omniflow.OobFunctionRunPolicy
import cn.com.omnimind.bot.omniflow.OobFunctionSpecBuilder
import cn.com.omnimind.bot.omniflow.OobFunctionUpdateService
import cn.com.omnimind.bot.omniflow.OobFunctionRunner
import cn.com.omnimind.bot.runlog.OobActionCodec.boolArg
import cn.com.omnimind.bot.runlog.OobActionCodec.boolArgOrDefault
import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank
import cn.com.omnimind.bot.runlog.OobActionCodec.intArg
import cn.com.omnimind.bot.runlog.OobActionCodec.listArg
import cn.com.omnimind.bot.runlog.OobActionCodec.mapArg
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore

/**
 * OOB-native implementation of the public OmniFlow agent toolkit surface.
 *
 * The service deliberately keeps the first version fixed and local: Functions
 * are registered in OOB stores, recall is deterministic, and execution runs
 * through the existing OOB replay dispatcher. External OmniFlow can replace this
 * class later behind the same Function lifecycle shape:
 * `list/get or recall -> guard_check -> run -> run_log_convert/update_function`.
 * Legacy `omniflow.*` adapters remain compatibility routes, not the preferred
 * in-app agent path.
 */
class OobOmniFlowToolkitService(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    )
) {
    private val functionRepository = OobFunctionRepository(context, workspaceFunctionStore)
    private val replayService = OobRunLogReplayService(context, workspaceFunctionStore, functionRepository)
    private val functionRecallService = OobFunctionRecallService(context, functionRepository)
    private val functionRunner = OobFunctionRunner(context, workspaceFunctionStore, functionRepository)
    private val functionRunPolicy = OobFunctionRunPolicy(functionRepository)
    private val functionSpecBuilder = OobFunctionSpecBuilder()
    private val functionUpdateService = OobFunctionUpdateService(context, functionRepository, functionSpecBuilder)
    private val explorer = OobOmniFlowExplorer(context)

    fun recall(args: Map<String, Any?>?): Map<String, Any?> =
        functionRecallService.recall(args)

    @Deprecated("Use runFunction/oob_function_run. call_function is a compatibility alias.")
    suspend fun callFunction(args: Map<String, Any?>?): Map<String, Any?> = runFunction(args)

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

        val settleDelayMs = OobActionCodec.longArg(
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

        val replayResult = runFunction(
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
            "error" to firstNonBlank(replayResult["error"], replayResult["error_message"]).takeIf { it.isNotEmpty() },
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    fun listFunctions(args: Map<String, Any?>?): Map<String, Any?> =
        functionRepository.list(
            limit = intArg(args?.get("limit"), defaultValue = 100),
            offset = intArg(args?.get("offset"), defaultValue = 0)
        )

    fun getFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val functionId = firstNonBlank(args?.get("functionId"), args?.get("function_id"))
        val spec = functionRepository.get(functionId)
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
        return functionRepository.delete(functionId)
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
        return functionRepository.clear()
    }

    fun registerFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionSpec = functionSpecBuilder.functionSpecForRegistration(request)
        if (functionSpec.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_SPEC_EMPTY",
                message = "functionSpec or steps are required"
            )
        }
        val mode = if (functionSpecBuilder.hasExplicitFunctionSpec(request)) "function_spec" else "simple"
        return functionRepository.register(functionSpec) + linkedMapOf(
            "registration_input_mode" to mode,
            "simple_schema_supported" to true,
        )
    }

    fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> =
        functionUpdateService.updateFunction(args)

    fun guardCheck(args: Map<String, Any?>?): Map<String, Any?> {
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val arguments = mapArg(request["arguments"])
        return functionRunPolicy.guardCheck(functionId = functionId, arguments = arguments)
    }

    suspend fun runFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val callTiming = OobFunctionCallTiming()
        val request = args ?: emptyMap()
        val functionId = firstNonBlank(request["functionId"], request["function_id"])
        val arguments = mapArg(request["arguments"])
        val dryRun = boolArg(request["dryRun"]) || boolArg(request["dry_run"])
        val confirmed = boolArg(request["confirmed"]) || boolArg(request["userConfirmed"])
        val resumeFromStep = intArg(
            request["resume_from_step"],
            request["resumeFromStep"],
            request["start_step_index"],
            request["startStepIndex"],
            defaultValue = 0
        ).coerceAtLeast(0)
        val fallbackSessionId = firstNonBlank(
            request["fallback_session_id"],
            request["fallbackSessionId"]
        )
        val fallbackAttempt = intArg(
            request["fallback_attempt"],
            request["fallbackAttempt"],
            defaultValue = 0
        ).coerceAtLeast(0)
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
            functionRunner.execute(
                functionId = functionId,
                arguments = arguments,
                allowAgentFallback = true,
                resumeFromStep = resumeFromStep,
                fallbackSessionId = fallbackSessionId,
                fallbackAttempt = fallbackAttempt
            )
        }
        runPayload = callTiming.attachTo(runPayload)
        val fallbackMetadata = functionRunPolicy.fallbackMetadata(
            functionId = functionId,
            arguments = arguments,
            runPayload = runPayload,
            guard = guard,
            requestedResumeFromStep = resumeFromStep,
            fallbackSessionId = fallbackSessionId,
            fallbackAttempt = fallbackAttempt,
        )
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
        val startedAtMs = OobActionCodec.longArg(timing["started_at_ms"], defaultValue = 0L)
        val finishedAtMs = OobActionCodec.longArg(timing["finished_at_ms"], defaultValue = 0L)
        val durationMs = OobActionCodec.longArg(
            timing["call_duration_ms"],
            timing["duration_ms"],
            timing["runner_duration_ms"],
            defaultValue = 0L,
        )
            .takeIf { it > 0L }
            ?: (finishedAtMs - startedAtMs).takeIf { startedAtMs > 0L && finishedAtMs >= startedAtMs }
            ?: stepResults.sumOf { raw ->
                OobActionCodec.longArg(mapArg(raw)["duration_ms"], defaultValue = 0L).coerceAtLeast(0L)
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
            "fallback_session_id" to fallbackMetadata["fallback_session_id"],
            "resume_from_step" to fallbackMetadata["resume_from_step"],
            "fallback_attempt" to fallbackMetadata["fallback_attempt"],
            "fallback_unavailable_reason" to fallbackMetadata["fallback_unavailable_reason"],
            "fallback_context" to fallbackMetadata["fallback_context"],
            "agent_prompt" to fallbackMetadata["agent_prompt"],
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
        }.map { mapArg(it) }.filter { it.isNotEmpty() }
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
            startedAtMs = OobActionCodec.longArg(
                runLog["started_at_ms"],
                defaultValue = System.currentTimeMillis(),
            ),
            finishedAtMs = OobActionCodec.longArg(
                runLog["finished_at_ms"],
                defaultValue = System.currentTimeMillis(),
            ),
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
        return functionRepository.register(spec) + linkedMapOf(
            "run_id" to record.runId,
            "function_spec" to spec
        )
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
            "has_agent_steps" to (
                execution["has_agent_steps"]
                    ?: execution["requires_agent_fallback"]
                ),
            "parameter_names" to OobFunctionSchemaBuilder.parameterNames(spec),
            "step_summaries" to stepSummaries(spec),
            "source" to spec["source"],
            "constraints" to spec["constraints"],
        ).filterValues { it != null }
    }

    private fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.stepSummaries(spec)
    }

    private fun materializedSteps(spec: Map<String, Any?>): List<Map<String, Any?>> {
        return OobFunctionSchemaBuilder.materializedSteps(spec)
    }

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
}
