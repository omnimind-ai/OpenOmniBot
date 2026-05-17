package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
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
                message = record.errorMessage.ifBlank {
                    "Only successful RunLogs can be registered for deterministic replay"
                },
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
        val functionId = functionIdFromSpec(spec)
        if (!register) {
            return linkedMapOf(
                "success" to true,
                "registered" to false,
                "run_id" to normalizedRunId,
                "function_id" to functionId,
                "created_function_id" to functionId,
                "function_spec" to spec,
                "summary" to summaryMap(spec),
                "function_kind" to "oob_reusable_function",
                "asset_state" to "native_local",
                "source" to "oob_run_log_replay_service"
            )
        }

        workspaceFunctionStore.mirrorRunLog(record)
        return registerFunctionSpec(spec).toMutableMap().apply {
            put("registered", this["success"] == true)
            put("run_id", normalizedRunId)
            put("function_spec", spec)
            put("summary", summaryMap(spec))
            put("source", "oob_run_log_replay_service")
        }
    }

    fun registerFunctionSpec(functionSpec: Map<String, Any?>): Map<String, Any?> {
        val spec = sanitizeMap(functionSpec)
        val functionId = functionIdFromSpec(spec)
        if (functionId.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "function_id is required"
            )
        }
        val alreadyExists = workspaceFunctionStore.canHandle(functionId) ||
            OobReusableFunctionStore.get(context, functionId) != null

        val workspaceResult = workspaceFunctionStore.register(spec)
        val registryResult = runCatching {
            OobReusableFunctionStore.register(context, spec)
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "error_message" to error.message.orEmpty()
            )
        }
        val success = registryResult["success"] == true
        if (success) {
            AgentToolFeatureStore.setOobFunctionAsToolEnabled(context, true)
        }
        return linkedMapOf(
            "success" to success,
            "function_id" to functionId,
            "created_function_id" to functionId,
            "imported" to success,
            "already_exists" to alreadyExists,
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "runner" to "oob_agent_reusable_function",
            "oob_function_as_tool_enabled" to
                AgentToolFeatureStore.isOobFunctionAsToolEnabled(context),
            "workspace" to workspaceResult,
            "registry" to registryResult,
            "source_run_ids" to sourceRunIds(spec)
        )
    }

    fun listFunctions(limit: Int = 100): Map<String, Any?> {
        val specs = listFunctionSpecs(limit)
        return linkedMapOf(
            "success" to true,
            "count" to specs.size,
            "functions" to specs.map(::summaryMap),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "oob_run_log_replay_service"
        )
    }

    fun listFunctionSpecs(limit: Int = 100): List<Map<String, Any?>> {
        val safeLimit = limit.coerceIn(1, 500)
        syncWorkspaceFunctionsToSharedPrefs(limit = safeLimit)
        val byId = linkedMapOf<String, Map<String, Any?>>()
        workspaceFunctionStore.list(safeLimit).forEach { spec ->
            val functionId = functionIdFromSpec(spec)
            if (functionId.isNotEmpty()) byId[functionId] = sanitizeMap(spec)
        }
        val summaries = (OobReusableFunctionStore.list(context, safeLimit)["functions"] as? List<*>)
            ?: emptyList<Any?>()
        summaries.forEach { rawSummary ->
            val summary = rawSummary as? Map<*, *> ?: return@forEach
            val functionId = summary["function_id"]?.toString()?.trim().orEmpty()
            if (functionId.isEmpty() || byId.containsKey(functionId)) return@forEach
            OobReusableFunctionStore.get(context, functionId)?.let { spec ->
                byId[functionId] = sanitizeMap(spec)
            }
        }
        return byId.values.take(safeLimit)
    }

    fun getFunctionSpec(functionId: String): Map<String, Any?>? {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) return null
        val workspaceSpec = workspaceFunctionStore.get(normalized)
        if (workspaceSpec != null) {
            if (OobReusableFunctionStore.get(context, normalized) == null) {
                runCatching { OobReusableFunctionStore.register(context, workspaceSpec) }
                    .onFailure { OmniLog.w(TAG, "sync workspace function failed: ${it.message}") }
            }
            return sanitizeMap(workspaceSpec)
        }
        return OobReusableFunctionStore.get(context, normalized)?.let(::sanitizeMap)
    }

    fun deleteFunction(functionId: String): Map<String, Any?> {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "function_id is required"
            )
        }
        val deletedWorkspace = workspaceFunctionStore.delete(normalized)
        val deletedPrefs = OobReusableFunctionStore.delete(context, normalized)
        val deleted = deletedWorkspace || deletedPrefs
        return linkedMapOf(
            "success" to deleted,
            "function_id" to normalized,
            "deleted" to deleted,
            "deleted_workspace" to deletedWorkspace,
            "deleted_registry" to deletedPrefs
        )
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
            val functionId = functionIdFromSpec(spec)
            val existsInWorkspace = workspaceFunctionStore.canHandle(functionId)
            val existsInRegistry = OobReusableFunctionStore.get(context, functionId) != null
            workspaceFunctionStore.mirrorRunLog(record)
            if (existsInWorkspace && existsInRegistry) {
                alreadyExists++
                continue
            }
            val result = registerFunctionSpec(spec)
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

    private fun syncWorkspaceFunctionsToSharedPrefs(limit: Int) {
        var synced = 0
        workspaceFunctionStore.list(limit).forEach { spec ->
            val functionId = functionIdFromSpec(spec)
            if (functionId.isEmpty()) return@forEach
            if (OobReusableFunctionStore.get(context, functionId) != null) return@forEach
            runCatching {
                OobReusableFunctionStore.register(context, spec)
                synced++
            }.onFailure {
                OmniLog.w(TAG, "sync workspace function failed: $functionId, ${it.message}")
            }
        }
        if (synced > 0) {
            AgentToolFeatureStore.setOobFunctionAsToolEnabled(context, true)
        }
    }

    private fun applyOverrides(
        spec: Map<String, Any?>,
        functionIdOverride: String?,
        nameOverride: String?,
        descriptionOverride: String?,
    ): Map<String, Any?> {
        val functionId = functionIdOverride?.trim()?.takeIf { it.isNotEmpty() }
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

    private fun functionIdFromSpec(spec: Map<String, Any?>): String =
        spec["function_id"]?.toString()?.trim().orEmpty()

    private fun sourceRunIds(spec: Map<String, Any?>): List<String> {
        val source = spec["source"] as? Map<*, *>
        return source?.get("run_id")
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { listOf(it) }
            ?: emptyList()
    }

    private fun summaryMap(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = spec["execution"] as? Map<*, *>
        val steps = execution?.get("steps") as? List<*> ?: emptyList<Any?>()
        val parameters = spec["parameters"] as? List<*> ?: emptyList<Any?>()
        val registry = spec["_oob_registry"] as? Map<*, *>
        return linkedMapOf(
            "function_id" to spec["function_id"],
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (execution?.get("step_count") ?: steps.size),
            "omniflow_step_count" to execution?.get("omniflow_step_count"),
            "agent_step_count" to execution?.get("agent_step_count"),
            "requires_agent_fallback" to execution?.get("requires_agent_fallback"),
            "parameter_names" to parameters.mapNotNull { raw ->
                (raw as? Map<*, *>)?.get("name")?.toString()?.takeIf { it.isNotBlank() }
            },
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "runner" to "oob_agent_reusable_function",
            "registered_at" to registry?.get("registered_at"),
            "updated_at" to registry?.get("updated_at"),
            "source_run_ids" to sourceRunIds(spec),
            "source" to spec["source"]
        )
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

    private fun sanitizeMap(value: Map<*, *>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) put(key.toString(), sanitizeValue(item))
            }
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String, is Number, is Boolean -> value
            is Map<*, *> -> sanitizeMap(value)
            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }
    }

    companion object {
        private const val TAG = "OobRunLogReplayService"
    }
}
