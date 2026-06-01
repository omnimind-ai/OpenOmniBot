package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore

/**
 * Single owner for OOB Function storage and index synchronization.
 *
 * RunLog services should compile evidence into Function specs; toolkit services should expose
 * agent/MCP APIs. All Function CRUD, workspace/registry mirroring, UDEG references, and tool
 * exposure cleanup should go through this repository.
 */
class OobFunctionRepository(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    )
) {
    fun register(functionSpec: Map<String, Any?>): Map<String, Any?> {
        val rawSpec = OobFunctionJson.sanitizeMap(functionSpec)
        val rawFunctionId = functionIdFromSpec(rawSpec)
        if (rawFunctionId.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "function_id is required"
            )
        }
        val functionId = normalizeFunctionId(rawFunctionId)
        val spec = linkedMapOf<String, Any?>().apply {
            putAll(rawSpec)
            put("function_id", functionId)
            putIfAbsent("name", functionId)
        }
        val alreadyExists = contains(functionId)
        val udegResult = runCatching {
            OobUdegNodeStore(context).upsertFunction(functionId, spec)
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "indexed" to false,
                "error_message" to error.message.orEmpty()
            )
        }

        val workspaceResult = runCatching {
            workspaceFunctionStore.register(spec)
        }.getOrElse { error ->
            OmniLog.w(TAG, "workspace function register failed: $functionId, ${error.message}")
            linkedMapOf(
                "success" to false,
                "error_code" to "WORKSPACE_REGISTER_FAILED",
                "error_message" to error.message.orEmpty()
            )
        }
        val registryResult = runCatching {
            OobReusableFunctionStore.register(context, spec)
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "error_code" to "REGISTRY_REGISTER_FAILED",
                "error_message" to error.message.orEmpty()
            )
        }
        val success = registryResult["success"] == true
        val sourceRunIds = sourceRunIds(spec)
        val runLogBindings = if (success) {
            sourceRunIds.mapNotNull { runId ->
                runCatching {
                    InternalRunLogStore.bindRegisteredFunction(
                        context = context,
                        runId = runId,
                        functionId = functionId,
                        functionSpec = spec
                    )
                }.onFailure { error ->
                    OmniLog.w(
                        TAG,
                        "bind registered function to runlog failed: $runId -> $functionId, ${error.message}"
                    )
                }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { binding ->
                        linkedMapOf(
                            "run_id" to runId,
                            "function_id" to functionId,
                            "success" to true,
                            "binding" to binding
                        )
                    }
            }
        } else {
            emptyList()
        }
        return linkedMapOf(
            "success" to success,
            "function_id" to functionId,
            "created_function_id" to functionId,
            "imported" to success,
            "already_exists" to alreadyExists,
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "runner" to OobFunctionSpecVocabulary.REGISTRY_RUNNER_AGENT_REUSABLE_FUNCTION,
            "oob_function_as_tool_enabled" to
                AgentToolFeatureStore.isOobFunctionAsToolEnabled(context),
            "workspace" to workspaceResult,
            "registry" to registryResult,
            "udeg" to udegResult,
            "normalized_from_function_id" to rawFunctionId.takeIf { it != functionId },
            "source_run_ids" to sourceRunIds,
            "run_log_bindings" to runLogBindings,
            "run_log_binding_count" to runLogBindings.size
        )
    }

    fun list(limit: Int = 100, offset: Int = 0): Map<String, Any?> {
        val page = listSpecsPage(limit = limit, offset = offset)
        return linkedMapOf(
            "success" to true,
            "count" to page.specs.size,
            "limit" to page.limit,
            "offset" to page.offset,
            "next_offset" to (page.offset + page.specs.size),
            "has_more" to page.hasMore,
            "functions" to page.specs.map(::summaryMap),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "source" to "oob_function_repository"
        )
    }

    fun listSpecs(limit: Int = 100): List<Map<String, Any?>> {
        return listSpecsPage(limit = limit, offset = 0).specs
    }

    fun get(functionId: String): Map<String, Any?>? {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) return null
        OobReusableFunctionStore.get(context, normalized)?.let { registrySpec ->
            return OobFunctionJson.sanitizeMap(registrySpec)
        }
        val workspaceSpec = workspaceFunctionStore.get(normalized)
        if (workspaceSpec != null) {
            runCatching { OobReusableFunctionStore.register(context, workspaceSpec) }
                .onFailure { OmniLog.w(TAG, "sync workspace function failed: ${it.message}") }
            return OobFunctionJson.sanitizeMap(workspaceSpec)
        }
        return null
    }

    fun delete(functionId: String): Map<String, Any?> {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) {
            return errorPayload(
                code = "FUNCTION_ID_EMPTY",
                message = "function_id is required"
            )
        }
        val deletedWorkspace = workspaceFunctionStore.delete(normalized)
        val deletedPrefs = OobReusableFunctionStore.delete(context, normalized)
        val udegResult = OobUdegNodeStore(context).removeFunctionReferences(setOf(normalized))
        val deleted = deletedWorkspace || deletedPrefs
        if (listSpecs(limit = 1).isEmpty()) {
            AgentToolFeatureStore.clearOobFunctionAsToolEnabled(context)
        }
        return linkedMapOf(
            "success" to deleted,
            "function_id" to normalized,
            "deleted" to deleted,
            "deleted_workspace" to deletedWorkspace,
            "deleted_registry" to deletedPrefs,
            "udeg" to udegResult,
            "source" to "oob_function_repository",
        )
    }

    fun clear(): Map<String, Any?> {
        val workspaceIds = workspaceFunctionStore.functionIds()
        val registryIds = OobReusableFunctionStore.functionIds(context)
        val functionIds = (workspaceIds + registryIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val workspaceResult = workspaceFunctionStore.clear()
        val registryResult = OobReusableFunctionStore.clear(context)
        val udegResult = OobUdegNodeStore(context).clearFunctionReferences()
        AgentToolFeatureStore.clearOobFunctionAsToolEnabled(context)
        return linkedMapOf(
            "success" to true,
            "deleted" to true,
            "deleted_count" to functionIds.size,
            "function_ids" to functionIds,
            "workspace" to workspaceResult,
            "registry" to registryResult,
            "udeg" to udegResult,
            "oob_function_as_tool_enabled" to false,
            "source" to "oob_function_repository",
        )
    }

    fun contains(functionId: String): Boolean {
        val normalized = functionId.trim()
        return normalized.isNotEmpty() &&
            (workspaceFunctionStore.canHandle(normalized) ||
                OobReusableFunctionStore.get(context, normalized) != null)
    }

    fun summaryMap(spec: Map<String, Any?>): Map<String, Any?> {
        val execution = spec["execution"] as? Map<*, *>
        val steps = OobFunctionSchemaBuilder.materializedSteps(spec)
        val registry = spec["_oob_registry"] as? Map<*, *>
        val source = spec["source"] as? Map<*, *>
        val runStats = registry?.get("run_stats") as? Map<*, *>
        return linkedMapOf(
            "function_id" to functionIdFromSpec(spec),
            "name" to spec["name"],
            "description" to spec["description"],
            "step_count" to (execution?.get("step_count") ?: steps.size),
            "card_count" to (
                OobFunctionJson.intArg(source?.get("card_count"), defaultValue = 0)
                    .takeIf { it > 0 }
                    ?: OobFunctionJson.intArg(source?.get("replayable_card_count"), defaultValue = 0)
                    .takeIf { it > 0 }
                    ?: steps.size
                ),
            "omniflow_step_count" to execution?.get("omniflow_step_count"),
            "agent_step_count" to execution?.get("agent_step_count"),
            "requires_agent_fallback" to execution?.get("requires_agent_fallback"),
            "parameter_names" to OobFunctionSchemaBuilder.parameterNames(spec),
            "step_summaries" to OobFunctionSchemaBuilder.stepSummaries(spec),
            "function_kind" to "oob_reusable_function",
            "asset_state" to "native_local",
            "runner" to OobFunctionSpecVocabulary.REGISTRY_RUNNER_AGENT_REUSABLE_FUNCTION,
            "registered_at" to registry?.get("registered_at"),
            "updated_at" to registry?.get("updated_at"),
            "source_run_ids" to sourceRunIds(spec),
            "source" to spec["source"],
            "run_stats" to OobFunctionJson.sanitizeValue(
                runStats ?: emptyMap<Any?, Any?>()
            ),
            "last_run" to OobFunctionJson.sanitizeValue(
                runStats?.get("last_run") ?: emptyMap<Any?, Any?>()
            )
        )
    }

    private fun listSpecsPage(
        limit: Int = 100,
        offset: Int = 0
    ): FunctionSpecPage {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val scanLimit = (safeOffset + safeLimit + 1).coerceIn(1, 500)
        syncWorkspaceToRegistry(limit = scanLimit)
        val byId = linkedMapOf<String, Map<String, Any?>>()
        workspaceFunctionStore.list(scanLimit).forEach { spec ->
            val functionId = functionIdFromSpec(spec)
            if (functionId.isNotEmpty()) {
                val merged = linkedMapOf<String, Any?>().apply {
                    putAll(OobFunctionJson.sanitizeMap(spec))
                    val registry = OobReusableFunctionStore.get(context, functionId)
                        ?.get("_oob_registry")
                    if (registry is Map<*, *>) {
                        put("_oob_registry", OobFunctionJson.sanitizeMap(registry))
                    }
                }
                byId[functionId] = OobFunctionJson.sanitizeMap(merged)
            }
        }
        val summaries = (OobReusableFunctionStore.list(context, scanLimit)["functions"] as? List<*>)
            ?: emptyList<Any?>()
        summaries.forEach { rawSummary ->
            val summary = rawSummary as? Map<*, *> ?: return@forEach
            val functionId = summary["function_id"]?.toString()?.trim().orEmpty()
            if (functionId.isEmpty() || byId.containsKey(functionId)) return@forEach
            OobReusableFunctionStore.get(context, functionId)?.let { spec ->
                byId[functionId] = OobFunctionJson.sanitizeMap(spec)
            }
        }
        val window = byId.values.drop(safeOffset).take(safeLimit + 1).toList()
        return FunctionSpecPage(
            specs = window.take(safeLimit),
            limit = safeLimit,
            offset = safeOffset,
            hasMore = window.size > safeLimit
        )
    }

    private fun syncWorkspaceToRegistry(limit: Int) {
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
            OmniLog.i(
                TAG,
                "synced workspace functions to local registry; oob function model-tool exposure remains user-controlled"
            )
        }
    }

    private data class FunctionSpecPage(
        val specs: List<Map<String, Any?>>,
        val limit: Int,
        val offset: Int,
        val hasMore: Boolean
    )

    companion object {
        private const val MAX_FUNCTION_ID_LENGTH = 64
        private val FUNCTION_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,$MAX_FUNCTION_ID_LENGTH}$")
        private const val TAG = "OobFunctionRepository"

        fun functionIdFromSpec(spec: Map<String, Any?>): String =
            OobReusableFunctionStore.functionIdFromSpec(spec)

        fun normalizeFunctionId(value: String): String {
            val trimmed = value.trim()
            if (FUNCTION_ID_REGEX.matches(trimmed)) return trimmed
            val normalized = trimmed
                .lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "_")
                .replace(Regex("_+"), "_")
                .trim('_', '-')
            val prefixed = when {
                normalized.isEmpty() -> "oob_function"
                normalized.first().isLetter() -> normalized
                else -> "oob_$normalized"
            }
            return prefixed.take(MAX_FUNCTION_ID_LENGTH).trim('_', '-').ifBlank {
                "oob_function"
            }
        }

        fun sourceRunIds(spec: Map<String, Any?>): List<String> {
            val source = spec["source"] as? Map<*, *>
            val metadata = spec["metadata"] as? Map<*, *>
            return buildList {
                source?.get("run_id")
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::add)
                metadata?.get("run_id")
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::add)
                (metadata?.get("source_run_ids") as? List<*>)?.forEach { raw ->
                    raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }.distinct()
        }

        fun errorPayload(
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
}
