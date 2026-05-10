package cn.com.omnimind.bot.workbench

private const val TOOLBOX_API_VERSION = "v0.1"

/**
 * Builds the MCP-facing Toolbox contract for an active OOB Workbench Project.
 *
 * The builder is intentionally pure: callers pass the Project record, API records, and execution
 * counts, and it derives MCP tool descriptors plus Project-local contract metadata without reading
 * or writing Display/UI files.
 */
object WorkbenchToolboxBuilder {
    /**
     * Converts a Project id into the stable Toolbox namespace used by MCP dynamic tools.
     *
     * @param projectId OOB Project id, usually prefixed with `oob-workbench-`.
     * @return Lowercase MCP-safe namespace such as `quick_note` or `customer_tracker`.
     */
    fun toolboxId(projectId: String): String {
        val stripped = projectId
            .removePrefix("oob-workbench-")
            .replace(Regex("^v[0-9]+-"), "")
        return slug(stripped).ifBlank { "project" }
    }

    /**
     * Builds the dynamic MCP tool name for one Project API.
     *
     * @param projectId OOB Project id that owns the API.
     * @param apiId Business API id, for example `capture.ingest`.
     * @return MCP tool name in `<toolbox>.<api>` form.
     */
    fun toolName(projectId: String, apiId: String): String {
        return "${toolboxId(projectId)}.${apiSlug(apiId)}"
    }

    /**
     * Enriches a Project API registry record with v0.1 Tool Contract fields.
     *
     * @param api Business API record from the Project API registry.
     * @param executionCount Number of successful or failed executions observed in the API log.
     * @return JSON-safe API contract payload used by `project.json` and `backend/api_spec.json`.
     */
    fun apiContract(api: WorkbenchApiRecord, executionCount: Int = 0): Map<String, Any?> {
        return linkedMapOf(
            "apiId" to api.apiId,
            "projectId" to api.projectId,
            "toolId" to api.toolId,
            "toolName" to toolName(api.projectId, api.apiId),
            "apiVersion" to TOOLBOX_API_VERSION,
            "displayName" to api.displayName,
            "description" to api.description,
            "inputSchema" to api.inputSchema,
            "outputSchema" to api.outputSchema,
            "executorKind" to api.executorKind,
            "kind" to api.executorKind,
            "capabilities" to capabilitiesFor(api),
            "sideEffects" to sideEffectsFor(api),
            "dataFiles" to dataFilesFor(api),
            "logFiles" to logFilesFor(api),
            "examples" to examplesFor(api),
            "inputKeys" to api.inputSchema.keys.toList(),
            "outputKeys" to api.outputSchema.keys.toList(),
            "executionCount" to executionCount
        )
    }

    /**
     * Builds the active Project Toolbox manifest.
     *
     * @param record Project registry record currently being exposed.
     * @param apis Business API records owned by the Project.
     * @param executionCounts Per-API execution count summary.
     * @return JSON-safe Toolbox manifest for Project payloads and MCP resources.
     */
    fun toolboxPayload(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        executionCounts: Map<String, Int> = emptyMap()
    ): Map<String, Any?> {
        val id = toolboxId(record.projectId)
        return linkedMapOf(
            "projectId" to record.projectId,
            "toolboxId" to id,
            "displayName" to "${record.name} Toolbox",
            "apiVersion" to TOOLBOX_API_VERSION,
            "tools" to apis.map { api -> toolboxTool(record, api, executionCounts[api.apiId] ?: 0) }
        )
    }

    /**
     * Builds MCP tool descriptors for Project business APIs.
     *
     * @param record Active Project record.
     * @param apis Business APIs from the Project API registry.
     * @param executionCounts Per-API execution count summary.
     * @return MCP `tools/list` descriptors. Workbench control APIs are never accepted here.
     */
    fun mcpTools(
        record: WorkbenchProjectRecord,
        apis: List<WorkbenchApiRecord>,
        executionCounts: Map<String, Int> = emptyMap()
    ): List<Map<String, Any?>> {
        return apis.map { api -> mcpTool(record, api, executionCounts[api.apiId] ?: 0) }
    }

    /**
     * Returns true when the MCP tool name belongs to this Project API.
     *
     * @param projectId Project id used to derive the Toolbox namespace.
     * @param api Business API candidate.
     * @param toolName External MCP tool name.
     * @return Whether `toolName` resolves to `api` under this Project's Toolbox.
     */
    fun matchesTool(projectId: String, api: WorkbenchApiRecord, toolName: String): Boolean {
        return toolName(projectId, api.apiId) == toolName.trim()
    }

    private fun toolboxTool(
        record: WorkbenchProjectRecord,
        api: WorkbenchApiRecord,
        executionCount: Int
    ): Map<String, Any?> {
        return apiContract(api, executionCount) + linkedMapOf(
            "name" to toolName(record.projectId, api.apiId),
            "dataFiles" to dataFilesFor(api),
            "logs" to logFilesFor(api)
        )
    }

    private fun mcpTool(
        record: WorkbenchProjectRecord,
        api: WorkbenchApiRecord,
        executionCount: Int
    ): Map<String, Any?> {
        return linkedMapOf(
            "name" to toolName(record.projectId, api.apiId),
            "description" to buildString {
                append(api.description.ifBlank { api.displayName })
                append("\n\nOOB Project: ")
                append(record.projectId)
                append("; API: ")
                append(api.apiId)
                append("; executor: ")
                append(api.executorKind)
                append("; executionCount: ")
                append(executionCount)
            },
            "inputSchema" to mcpInputSchema(api),
            "outputSchema" to mcpOutputSchema(api),
            "projectId" to record.projectId,
            "apiId" to api.apiId,
            "toolboxId" to toolboxId(record.projectId),
            "apiVersion" to TOOLBOX_API_VERSION,
            "executorKind" to api.executorKind,
            "sideEffects" to sideEffectsFor(api),
            "dataFiles" to dataFilesFor(api),
            "logFiles" to logFilesFor(api)
        )
    }

    private fun capabilitiesFor(api: WorkbenchApiRecord): List<String> {
        return when (api.executorKind) {
            WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND -> listOf(
                "persistent_project_data",
                "script_audit_log",
                "future_bridge_tools",
                "future_alpine_execution"
            )
            WORKBENCH_SCHEMA_EXECUTOR_KIND -> listOf(
                "persistent_project_data",
                "native_collection_crud"
            )
            else -> listOf("persistent_project_data", "native_template")
        }
    }

    private fun sideEffectsFor(api: WorkbenchApiRecord): List<String> {
        return when (api.apiId) {
            WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID -> listOf("data_write", "log_write")
            else -> listOf("data_write", "log_write")
        }
    }

    private fun dataFilesFor(api: WorkbenchApiRecord): List<String> {
        return when {
            api.executorKind == WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND ->
                listOf("data/items.json")
            api.executorKind == WORKBENCH_SCHEMA_EXECUTOR_KIND ->
                listOf("data/items.json")
            else -> listOf("data/todos.json")
        }
    }

    private fun logFilesFor(api: WorkbenchApiRecord): List<String> {
        return when (api.executorKind) {
            WORKBENCH_WORKSPACE_PYTHON_EXECUTOR_KIND -> listOf(
                "logs/api_calls.jsonl",
                "logs/script_runs.jsonl"
            )
            else -> listOf("logs/api_calls.jsonl")
        }
    }

    private fun examplesFor(api: WorkbenchApiRecord): List<Map<String, Any?>> {
        val inputs = when (api.apiId) {
            WORKBENCH_CAPTURE_INGEST_TOOL_ID -> linkedMapOf("text" to "记一下：明天 3 点开会")
            WORKBENCH_CAPTURE_ARCHIVE_TOOL_ID,
            WORKBENCH_CAPTURE_PROMOTE_TOOL_ID,
            WORKBENCH_CAPTURE_SUMMARIZE_TOOL_ID -> linkedMapOf("item_id" to "capture-example")
            else -> if (api.apiId.endsWith(".archive")) {
                linkedMapOf("item_id" to "item-example")
            } else {
                linkedMapOf("title" to "Example item")
            }
        }
        return listOf(
            linkedMapOf(
                "description" to "Minimal valid call for ${api.apiId}.",
                "inputs" to inputs
            )
        )
    }

    private fun mcpInputSchema(api: WorkbenchApiRecord): Map<String, Any?> {
        val required = api.inputSchema.mapNotNull { (key, spec) ->
            if (isRequired(spec)) key else null
        }
        val properties = api.inputSchema.mapValues { (_, spec) -> schemaProperty(spec) }
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }

    private fun mcpOutputSchema(api: WorkbenchApiRecord): Map<String, Any?> {
        return linkedMapOf(
            "type" to "object",
            "properties" to api.outputSchema.mapValues { (_, spec) -> schemaProperty(spec) }
        )
    }

    private fun schemaProperty(spec: Any?): Map<String, Any?> {
        if (spec is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return spec.entries.associate { it.key.toString() to it.value } as Map<String, Any?>
        }
        val raw = spec?.toString()?.trim().orEmpty()
        val normalized = raw.removeSuffix("?").ifBlank { "string" }
        val type = when (normalized) {
            "int", "integer", "long" -> "integer"
            "float", "double", "number" -> "number"
            "bool", "boolean" -> "boolean"
            "array", "list" -> "array"
            "object", "map" -> "object"
            else -> "string"
        }
        return linkedMapOf("type" to type, "description" to raw.ifBlank { type })
    }

    private fun isRequired(spec: Any?): Boolean {
        if (spec is Map<*, *>) {
            val required = spec["required"]
            if (required is Boolean) return required
            if (required != null) return required.toString().equals("true", ignoreCase = true)
            val nullable = spec["nullable"]
            if (nullable is Boolean) return !nullable
            if (nullable != null) return !nullable.toString().equals("true", ignoreCase = true)
        }
        return !spec?.toString().orEmpty().trim().endsWith("?")
    }

    private fun apiSlug(apiId: String): String = slug(apiId.replace('.', '_'))

    private fun slug(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }
}
