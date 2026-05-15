package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Routes OOB Workbench control tools and Project Tool calls through the native Project store.
 *
 * The handler keeps Workbench tools inside OOB; active Project Tools can also be exposed through MCP.
 * `env.workspaceDescriptor` is used only to stamp results with the active Agent workspace id.
 */
class WorkbenchToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "workbench_project_create",
        "workbench_project_list",
        "workbench_project_get",
        "workbench_project_update",
        "workbench_api_list",
        "workbench_api_call",
        "workbench_project_export",
        "workbench_project_open",
        "workbench_project_activate",
        "workbench_project_active_get",
        "workbench_project_deactivate",
        "workbench_project_delete",
        "workbench_project_hot_update",
        "workbench_project_ingest_android",
        "workbench_project_ingest_oss",
        "workbench_project_progress_get",
        // Run-log → 指令 (like Claude Code /project:xxx custom commands)
        "oob_command_save",
        "oob_command_list",
        "oob_command_delete",
        "oob_run_log_list",
        "oob_run_log_get"
    )

    private val workbenchProjectStore = WorkbenchProjectStore(helper.context)
    private val workspaceFunctionStore: WorkspaceFunctionStore by lazy {
        WorkspaceFunctionStore(AgentWorkspaceManager.rootDirectory(helper.context))
    }

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "workbench_project_create" -> executeWorkbenchProjectCreate(args, env, callback)
            "workbench_project_list" -> executeWorkbenchProjectList(env, callback)
            "workbench_project_get" -> executeWorkbenchProjectGet(args, env, callback)
            "workbench_project_update" -> executeWorkbenchProjectUpdate(args, env, callback)
            "workbench_api_list" -> executeWorkbenchApiList(args, env, callback)
            "workbench_api_call" -> executeWorkbenchApiCall(args, env, callback)
            "workbench_project_export" -> executeWorkbenchProjectExport(args, env, callback)
            "workbench_project_open" -> executeWorkbenchProjectOpen(args, env, callback)
            "workbench_project_activate" -> executeWorkbenchProjectActivate(args, env, callback)
            "workbench_project_active_get" -> executeWorkbenchProjectActiveGet(env, callback)
            "workbench_project_deactivate" -> executeWorkbenchProjectDeactivate(env, callback)
            "workbench_project_delete" -> executeWorkbenchProjectDelete(args, env, callback)
            "workbench_project_hot_update" -> executeWorkbenchProjectHotUpdate(args, env, callback)
            "workbench_project_ingest_android" -> executeWorkbenchProjectIngestAndroid(args, env, callback)
            "workbench_project_ingest_oss" -> executeWorkbenchProjectIngestOss(args, env, callback)
            "workbench_project_progress_get" -> executeWorkbenchProjectProgressGet(args, env, callback)
            "oob_command_save" -> executeOobFunctionSave(args, env)
            "oob_command_list" -> executeOobFunctionList(env)
            "oob_command_delete" -> executeOobFunctionDelete(args, env)
            "oob_run_log_list" -> executeOobRunLogList(env)
            "oob_run_log_get" -> executeOobRunLogGet(args, env)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown workbench tool")
        }
    }

    // ── oob_function_save ──────────────────────────────────────────────────────
    // Like Claude Code's /project:xxx custom commands — user manually saves a run
    // as a named, replayable function stored in workspace/functions/.

    private fun executeOobFunctionSave(
        args: JsonObject,
        env: AgentExecutionEnvironment
    ): ToolExecutionResult {
        val argsMap = helper.jsonObjectToMap(args)
        val runId = argsMap["run_id"]?.toString()?.trim()
            ?: return ToolExecutionResult.Error("oob_command_save", "run_id is required")

        val record = InternalRunLogStore.timelinePayload(helper.context, runId)
        if (record["success"] != true) {
            return ToolExecutionResult.Error(
                "oob_function_save",
                record["error_message"]?.toString() ?: "Run not found: $runId"
            )
        }

        // Reconstruct InternalRunLogRecord fields from the timeline payload
        val cards = (record["cards"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map { m -> m.entries.associate { (k, v) -> k.toString() to v } }
            ?: emptyList()

        val runRecord = cn.com.omnimind.baselib.runlog.InternalRunLogRecord(
            runId = runId,
            goal = record["goal"]?.toString().orEmpty(),
            source = record["source"]?.toString().orEmpty(),
            toolName = record["tool_name"]?.toString().orEmpty(),
            operationDescription = argsMap["name"]?.toString()?.trim()
                ?: record["operation_description"]?.toString().orEmpty(),
            success = record["done_reason"]?.toString() != "cancelled",
            cards = cards
        )

        // Override function_id if caller provides one
        val overrideFunctionId = argsMap["function_id"]?.toString()?.trim()
        val descriptionOverride = argsMap["description"]?.toString()?.trim()

        val result = workspaceFunctionStore.distillFromRun(helper.context, runRecord)

        // Apply caller overrides after distillation
        if (result["success"] == true) {
            val functionId = result["function_id"]?.toString() ?: ""
            if ((overrideFunctionId != null && overrideFunctionId != functionId)
                || descriptionOverride != null) {
                val spec = workspaceFunctionStore.get(functionId)
                if (spec != null) {
                    val updated = spec.toMutableMap()
                    if (overrideFunctionId != null) updated["function_id"] = overrideFunctionId
                    if (descriptionOverride != null) updated["description"] = descriptionOverride
                    workspaceFunctionStore.register(updated)
                    if (overrideFunctionId != null && overrideFunctionId != functionId) {
                        workspaceFunctionStore.delete(functionId)
                    }
                }
            }
        }

        val payload = result + mapOf(
            "message" to "已保存为指令，可通过 function_id 直接调用。",
            "usage" to "Agent 可以直接用 function_id 调用该指令。"
        )
        return contextResult("oob_command_save", "已保存指令", payload,
            result["success"] == true, env)
    }

    private fun executeOobFunctionList(env: AgentExecutionEnvironment): ToolExecutionResult {
        val functions = workspaceFunctionStore.list()
        val payload = mapOf(
            "success" to true,
            "count" to functions.size,
            "functions" to functions.map { spec ->
                mapOf(
                    "function_id" to spec["function_id"],
                    "name" to spec["name"],
                    "description" to spec["description"],
                    "step_count" to ((spec["execution"] as? Map<*, *>)
                        ?.get("step_count") as? Number)?.toInt()
                )
            }
        )
        val summary = if (helper.isEnglishLocale) "${functions.size} commands" else "${functions.size} 条指令"
        return contextResult("oob_command_list", summary, payload, true, env)
    }

    private fun executeOobFunctionDelete(
        args: JsonObject,
        env: AgentExecutionEnvironment
    ): ToolExecutionResult {
        val argsMap = helper.jsonObjectToMap(args)
        val functionId = argsMap["function_id"]?.toString()?.trim()
            ?: return ToolExecutionResult.Error("oob_command_delete", "function_id is required")
        val deleted = workspaceFunctionStore.delete(functionId)
        return contextResult("oob_command_delete",
            if (deleted) "已删除指令 $functionId" else "指令不存在",
            mapOf("success" to deleted, "function_id" to functionId), deleted, env)
    }

    private fun executeOobRunLogList(env: AgentExecutionEnvironment): ToolExecutionResult {
        val payload = InternalRunLogStore.listRuns(helper.context, limit = 50)
        return contextResult("oob_run_log_list", "${payload["count"]} runs", payload, true, env)
    }

    private fun executeOobRunLogGet(
        args: JsonObject,
        env: AgentExecutionEnvironment
    ): ToolExecutionResult {
        val argsMap = helper.jsonObjectToMap(args)
        val runId = argsMap["run_id"]?.toString()?.trim()
            ?: return ToolExecutionResult.Error("oob_run_log_get", "run_id is required")
        val payload = InternalRunLogStore.timelinePayload(helper.context, runId)
        val success = payload["success"] == true
        return contextResult("oob_run_log_get",
            if (success) "Run ${runId.take(16)}…" else "Not found",
            payload, success, env)
    }

    private suspend fun executeWorkbenchProjectCreate(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_create"
        return try {
            helper.reportToolProgress(callback, toolName, "Creating Workbench project")
            val project = workbenchProjectStore.createProject(helper.jsonObjectToMap(args))
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project created",
                payload = linkedMapOf(
                    "success" to true,
                    "project" to project,
                    "registryPath" to "/workspace/projects/registry.json",
                    "apiRegistryPath" to "/workspace/projects/api_registry.json"
                ),
                success = true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project create failed")
        }
    }

    private suspend fun executeWorkbenchProjectList(
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_list"
        return try {
            helper.reportToolProgress(callback, toolName, "Listing Workbench projects")
            val projects = workbenchProjectStore.listProjects()
            contextResult(
                toolName = toolName,
                summaryText = "Workbench projects listed",
                payload = linkedMapOf(
                    "success" to true,
                    "count" to projects.size,
                    "projects" to projects
                ),
                success = true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project list failed")
        }
    }

    private suspend fun executeWorkbenchProjectGet(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_get"
        return try {
            helper.reportToolProgress(callback, toolName, "Loading Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val project = workbenchProjectStore.getProject(projectId)
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project loaded",
                payload = linkedMapOf("success" to true, "project" to project),
                success = true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project get failed")
        }
    }

    private suspend fun executeWorkbenchProjectUpdate(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_update"
        return try {
            helper.reportToolProgress(callback, toolName, "Updating Workbench project contract")
            val payload = workbenchProjectStore.updateProject(
                args = helper.jsonObjectToMap(args),
                caller = "ai"
            )
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project updated",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project update failed")
        }
    }

    private suspend fun executeWorkbenchApiList(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_api_list"
        return try {
            helper.reportToolProgress(callback, toolName, "Listing Workbench Project Tools")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull
            val apis = workbenchProjectStore.listApis(projectId)
            contextResult(
                toolName = toolName,
                summaryText = "Workbench Project Tools listed",
                payload = linkedMapOf(
                    "success" to true,
                    "projectId" to projectId,
                    "count" to apis.size,
                    "apis" to apis
                ),
                success = true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench Project Tool list failed")
        }
    }

    private suspend fun executeWorkbenchApiCall(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_api_call"
        return try {
            helper.reportToolProgress(callback, toolName, "Calling Workbench Project Tool")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val apiId = args["apiId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val inputs = (args["inputs"] as? JsonObject)
                ?.let(helper::jsonObjectToMap)
                ?: emptyMap()
            val payload = workbenchProjectStore.callApi(
                projectId = projectId,
                apiId = apiId,
                inputs = inputs,
                caller = "ai"
            )
            contextResult(
                toolName = toolName,
                summaryText = if (payload["success"] == true) {
                    "Workbench Project Tool called"
                } else {
                    payload["errorMessage"]?.toString() ?: "Workbench Project Tool call failed"
                },
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench Project Tool call failed")
        }
    }

    private suspend fun executeWorkbenchProjectExport(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_export"
        return try {
            helper.reportToolProgress(callback, toolName, "Exporting Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val payload = workbenchProjectStore.exportProject(projectId)
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project exported",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project export failed")
        }
    }

    private suspend fun executeWorkbenchProjectOpen(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_open"
        return try {
            helper.reportToolProgress(callback, toolName, "Opening Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val route = workbenchProjectStore.routeForProject(projectId)
            TaskCompletionNavigator.navigateToMainRoute(helper.context, route, needClear = false)
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project opened",
                payload = linkedMapOf(
                    "success" to true,
                    "projectId" to projectId,
                    "route" to route
                ),
                success = true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project open failed")
        }
    }

    private suspend fun executeWorkbenchProjectDelete(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_delete"
        return try {
            helper.reportToolProgress(callback, toolName, "Deleting Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val payload = workbenchProjectStore.deleteProject(projectId)
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project deleted",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project delete failed")
        }
    }

    private suspend fun executeWorkbenchProjectActivate(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_activate"
        return try {
            helper.reportToolProgress(callback, toolName, "Activating Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val payload = workbenchProjectStore.activateProject(projectId)
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project activated",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project activate failed")
        }
    }

    private suspend fun executeWorkbenchProjectActiveGet(
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_active_get"
        return try {
            helper.reportToolProgress(callback, toolName, "Loading active Workbench project")
            val payload = workbenchProjectStore.getActiveProject()
            contextResult(
                toolName = toolName,
                summaryText = "Active Workbench project loaded",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench active project get failed")
        }
    }

    private suspend fun executeWorkbenchProjectDeactivate(
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_deactivate"
        return try {
            helper.reportToolProgress(callback, toolName, "Deactivating Workbench project")
            val payload = workbenchProjectStore.deactivateProject()
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project deactivated",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project deactivate failed")
        }
    }

    private suspend fun executeWorkbenchProjectHotUpdate(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_hot_update"
        return try {
            helper.reportToolProgress(callback, toolName, "Hot updating Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val frontendContext = (args["frontendContext"] as? JsonObject)
                ?.let(helper::jsonObjectToMap)
                ?: emptyMap()
            val payload = workbenchProjectStore.hotUpdateProject(
                projectId = projectId,
                prompt = prompt,
                caller = "ai",
                frontendContext = frontendContext
            )
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project hot updated",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project hot update failed")
        }
    }

    private suspend fun executeWorkbenchProjectIngestAndroid(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_ingest_android"
        return try {
            helper.reportToolProgress(callback, toolName, "Importing Android asset into Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val sourcePath = args["sourcePath"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val sourceKind = args["sourceKind"]?.jsonPrimitive?.contentOrNull?.trim()
            val displayName = args["displayName"]?.jsonPrimitive?.contentOrNull?.trim()
            val payload = workbenchProjectStore.ingestAndroidAsset(
                projectId = projectId,
                sourcePath = sourcePath,
                sourceKind = sourceKind,
                displayName = displayName,
                caller = "ai"
            )
            contextResult(
                toolName = toolName,
                summaryText = "Android asset imported into Workbench project",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench Android import failed")
        }
    }

    private suspend fun executeWorkbenchProjectIngestOss(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_ingest_oss"
        return try {
            helper.reportToolProgress(callback, toolName, "Importing OSS source into Workbench project")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val sourceUrl = args["sourceUrl"]?.jsonPrimitive?.contentOrNull?.trim()
            val sourcePath = args["sourcePath"]?.jsonPrimitive?.contentOrNull?.trim()
            val sourceKind = args["sourceKind"]?.jsonPrimitive?.contentOrNull?.trim()
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull?.trim()
            val displayName = args["displayName"]?.jsonPrimitive?.contentOrNull?.trim()
            val payload = workbenchProjectStore.ingestOssSource(
                projectId = projectId,
                sourceUrl = sourceUrl,
                sourcePath = sourcePath,
                sourceKind = sourceKind,
                ref = ref,
                displayName = displayName,
                caller = "ai"
            )
            contextResult(
                toolName = toolName,
                summaryText = if ((payload["source"] as? Map<*, *>)?.get("requiresFetch") == true) {
                    "OSS source registered and waiting for fetch"
                } else {
                    "OSS source imported into Workbench project"
                },
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench OSS import failed")
        }
    }

    private suspend fun executeWorkbenchProjectProgressGet(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "workbench_project_progress_get"
        return try {
            helper.reportToolProgress(callback, toolName, "Loading Workbench project progress")
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull?.trim()
            val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50
            val payload = workbenchProjectStore.getProjectProgress(projectId, limit)
            contextResult(
                toolName = toolName,
                summaryText = "Workbench project progress loaded",
                payload = payload,
                success = payload["success"] == true,
                env = env
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "Workbench project progress get failed")
        }
    }

    private fun contextResult(
        toolName: String,
        summaryText: String,
        payload: Map<String, Any?>,
        success: Boolean,
        env: AgentExecutionEnvironment
    ): ToolExecutionResult.ContextResult {
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized(summaryText),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = success,
            workspaceId = env.workspaceDescriptor.id
        )
    }
}
