package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore

/**
 * OOB-owned replay facade for RunLog-derived reusable Functions.
 *
 * This is intentionally smaller than the external OmniFlow provider: OOB keeps a
 * fixed registry, fixed dispatch, and deterministic local replay runner, while
 * preserving the Function schema fields external OmniFlow can import later.
 */
class OobRunLogReplayService(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    )
) {
    private val functionRepository = OobFunctionRepository(context, workspaceFunctionStore)

    fun convertRunLog(
        runId: String,
        register: Boolean = true,
        functionIdOverride: String? = null,
        nameOverride: String? = null,
        descriptionOverride: String? = null,
    ): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return errorPayload(
                code = "RUN_LOG_ID_EMPTY",
                message = "run_id is required",
                runId = normalizedRunId
            )
        }
        val record = InternalRunLogStore.getRun(context, normalizedRunId)
            ?: return errorPayload(
                code = "RUN_LOG_NOT_FOUND",
                message = "RunLog not found: $normalizedRunId",
                runId = normalizedRunId
            )
        if (record.finishedAtMs == null) {
            return errorPayload(
                code = "RUN_LOG_NOT_FINISHED",
                message = "RunLog is not finished yet: $normalizedRunId",
                runId = normalizedRunId
            )
        }
        if (record.success != true) {
            return errorPayload(
                code = "RUN_LOG_NOT_SUCCESSFUL",
                message = "RunLog did not finish successfully: $normalizedRunId",
                runId = normalizedRunId
            )
        }
        val compiled = RunLogReusableFunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps",
                runId = normalizedRunId
            )
        val spec = applyOverrides(
            spec = compiled,
            functionIdOverride = functionIdOverride,
            nameOverride = nameOverride,
            descriptionOverride = descriptionOverride
        )
        val functionId = OobFunctionRepository.functionIdFromSpec(spec)
        if (!register) {
            return linkedMapOf(
                "success" to true,
                "registered" to false,
                "run_id" to normalizedRunId,
                "function_id" to functionId,
                "created_function_id" to functionId,
                "function_spec" to spec,
                "summary" to functionRepository.summaryMap(spec),
                "function_kind" to "oob_reusable_function",
                "asset_state" to "native_local",
                "source" to "oob_run_log_replay_service"
            )
        }

        workspaceFunctionStore.mirrorRunLog(record)
        return functionRepository.register(spec).toMutableMap().apply {
            put("registered", this["success"] == true)
            put("run_id", normalizedRunId)
            put("function_spec", spec)
            put("summary", functionRepository.summaryMap(spec))
            put("source", "oob_run_log_replay_service")
        }
    }

    fun autoRegisterRecentRunLogs(limit: Int = 50): Map<String, Any?> {
        val records = InternalRunLogStore.listRunRecords(context, limit)
        var eligible = 0
        var registered = 0
        var alreadyExists = 0
        var skipped = 0
        val results = mutableListOf<Map<String, Any?>>()

        for (record in records) {
            val skipReason = autoRegisterSkipReason(record)
            if (skipReason != null) {
                skipped++
                continue
            }
            val spec = RunLogReusableFunctionCompiler.compile(record)
            if (spec == null) {
                skipped++
                results += linkedMapOf(
                    "run_id" to record.runId,
                    "success" to false,
                    "reason" to "no_replayable_steps"
                )
                continue
            }
            eligible++
            val functionId = OobFunctionRepository.functionIdFromSpec(spec)
            val exists = functionRepository.contains(functionId)
            workspaceFunctionStore.mirrorRunLog(record)
            if (exists) {
                alreadyExists++
                continue
            }
            val result = functionRepository.register(spec)
            results += linkedMapOf(
                "run_id" to record.runId,
                "function_id" to functionId,
                "success" to (result["success"] == true),
                "already_exists" to result["already_exists"]
            )
            if (result["success"] == true) {
                if (result["already_exists"] == true) alreadyExists++ else registered++
            }
        }

        return linkedMapOf(
            "success" to true,
            "record_count" to records.size,
            "eligible_count" to eligible,
            "registered_count" to registered,
            "already_exists_count" to alreadyExists,
            "skipped_count" to skipped,
            "results" to results
        )
    }

    private fun autoRegisterSkipReason(record: InternalRunLogRecord): String? {
        if (record.finishedAtMs == null) return "unfinished"
        if (record.success != true) return "not_successful"
        if (record.cards.isEmpty()) return "empty"
        return null
    }

    private fun applyOverrides(
        spec: Map<String, Any?>,
        functionIdOverride: String?,
        nameOverride: String?,
        descriptionOverride: String?,
    ): Map<String, Any?> {
        val functionId = functionIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { OobFunctionRepository.normalizeFunctionId(it) }
        val name = nameOverride?.trim()?.takeIf { it.isNotEmpty() }
        val description = descriptionOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (functionId == null && name == null && description == null) return spec
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            functionId?.let { put("function_id", it) }
            name?.let { put("name", it) }
            description?.let { put("description", it) }
        }
    }

    private fun errorPayload(
        code: String,
        message: String,
        runId: String = ""
    ): Map<String, Any?> = linkedMapOf(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "run_id" to runId,
        "function_kind" to "oob_reusable_function",
        "asset_state" to "native_local"
    )
}
