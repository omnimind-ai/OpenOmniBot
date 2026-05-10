package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP 端点路由注册。
 *
 * 从 McpServerManager 拆分而来，包含 JSON-RPC、工具发现/调用、传统 VLM 任务端点。
 */
object McpRoutes {

    fun Route.registerMcpRoutes(
        context: Context,
        serverScope: CoroutineScope
    ) {
        // 健康检查（无需认证）
        get("/mcp/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // 文件下载（使用文件token或Bearer token）
        get("/mcp/file/{fileId}") {
            McpServerManager.handleFileDownload(call)
        }

        authenticate("bearer-auth") {
            // 服务状态
            get("/mcp/state") {
                call.respond(McpServerManager.currentState().toMap())
            }

            // MCP JSON-RPC 端点
            post("/mcp") {
                handleJsonRpc(call, context, serverScope)
            }

            // 工具发现
            get("/mcp/list_tools") {
                call.respond(mapOf("tools" to listMcpTools(context)))
            }
            post("/mcp/list_tools") {
                call.respond(mapOf("tools" to listMcpTools(context)))
            }

            // REST 风格工具调用
            post("/mcp/call_tool") {
                val params = call.receive<Map<String, Any?>>()
                val result = executeTool(
                    context,
                    serverScope,
                    params["name"] as? String,
                    params["arguments"] as? Map<String, Any?>
                )
                call.respond(result)
            }

            // Authenticated local Dashboard/debug transport for Workbench control-plane E2E.
            post("/mcp/workbench/call") {
                val body = call.receive<Map<String, Any?>>()
                val result = executeWorkbenchDebugCall(
                    context = context,
                    name = body["name"]?.toString(),
                    args = mapArg(body["arguments"])
                        ?: mapArg(body["args"])
                        ?: emptyMap()
                )
                call.respond(result)
            }

            // 传统 VLM 任务端点（保持兼容）
            post("/mcp/v1/task/vlm") {
                handleLegacyVlmTask(call, context, serverScope)
            }

            // 任务状态查询
            get("/mcp/v1/task/{taskId}/status") {
                val taskId = call.parameters["taskId"]
                val state = taskId?.let { McpTaskManager.getTask(it) }
                if (state == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
                } else {
                    call.respond(state.toResponseMap())
                }
            }

            // 任务回复
            post("/mcp/v1/task/{taskId}/reply") {
                handleLegacyTaskReply(call)
            }
        }
    }

    // ==================== JSON-RPC 处理 ====================

    private suspend fun handleJsonRpc(
        call: io.ktor.server.application.ApplicationCall,
        context: Context,
        serverScope: CoroutineScope
    ) {
        val request = runCatching { call.receive<Map<String, Any?>>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
            return
        }
        val id = request["id"]
        val method = request["method"] as? String

        val response = when (method) {
            "initialize" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to mapOf(
                        "tools" to mapOf<String, Any>(),
                        "resources" to mapOf<String, Any>(),
                        "prompts" to mapOf<String, Any>()
                    ),
                    "serverInfo" to mapOf("name" to "小万Mcp", "version" to "1.0")
                )
            )
            "notifications/initialized" -> null
            "tools/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("tools" to listMcpTools(context))
            )
            "tools/call" -> {
                val params = request["params"] as? Map<String, Any?>
                val name = params?.get("name") as? String
                val args = params?.get("arguments") as? Map<String, Any?>
                val execResult = executeTool(context, serverScope, name, args)
                mapOf("jsonrpc" to "2.0", "id" to id, "result" to execResult)
            }
            "resources/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("resources" to WorkbenchProjectStore(context).listMcpResources())
            )
            "resources/read" -> {
                val params = request["params"] as? Map<String, Any?>
                val uri = params?.get("uri")?.toString()?.trim().orEmpty()
                val limit = params?.get("limit")?.toString()?.toIntOrNull() ?: 50
                runCatching {
                    WorkbenchProjectStore(context).readMcpResource(uri, limit)
                }.fold(
                    onSuccess = { result ->
                        mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
                    },
                    onFailure = { error ->
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to id,
                            "error" to mapOf(
                                "code" to -32602,
                                "message" to (error.message ?: "Invalid resource request")
                            )
                        )
                    }
                )
            }
            "prompts/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("prompts" to McpPromptDefinitions.prompts)
            )
            "prompts/get" -> {
                val params = request["params"] as? Map<String, Any?>
                val name = params?.get("name")?.toString()?.trim().orEmpty()
                runCatching {
                    McpPromptDefinitions.getPrompt(name)
                }.fold(
                    onSuccess = { result ->
                        mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
                    },
                    onFailure = { error ->
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to id,
                            "error" to mapOf(
                                "code" to -32602,
                                "message" to (error.message ?: "Invalid prompt request")
                            )
                        )
                    }
                )
            }
            else -> {
                if (method?.startsWith("$/") == true || method?.startsWith("notifications/") == true) null
                else mapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "error" to mapOf("code" to -32601, "message" to "Method not found: $method")
                )
            }
        }

        if (response != null) {
            call.respond(response)
        } else {
            call.respond(HttpStatusCode.OK)
        }
    }

    // ==================== 工具执行 ====================

    private suspend fun executeTool(
        context: Context,
        serverScope: CoroutineScope,
        name: String?,
        args: Map<String, Any?>?
    ): Map<String, Any?> {
        return runCatching {
            when (name) {
            "vlm_task" -> McpToolExecutors.executeVlmTask(context, args, serverScope)
            "task_status" -> McpToolExecutors.executeTaskStatus(args)
            "task_reply" -> McpToolExecutors.executeTaskReply(args)
            "task_wait_unlock" -> McpToolExecutors.executeTaskWaitUnlock(context, args, serverScope)
            "file_transfer" -> McpToolExecutors.executeFileTransfer(args)
            "agent_run" -> McpToolExecutors.executeAgentRun(context, args)
            "oob_project_create" -> mcpProjectCreate(context, args)
            "oob_project_activate" -> mcpProjectActivate(context, args)
            "oob_project_open" -> mcpProjectOpen(context, args)
            "oob_project_progress_get" -> mcpProjectProgressGet(context, args)
            else -> {
                if (name.isNullOrBlank()) {
                    McpResponseBuilder.buildErrorText("Missing tool name")
                } else {
                    val result = WorkbenchProjectStore(context).callMcpTool(
                        toolName = name,
                        inputs = args ?: emptyMap(),
                        caller = "mcp_toolbox"
                    )
                    mcpProjectToolResult(name, result)
                }
            }
            }
        }.getOrElse { error ->
            McpResponseBuilder.buildErrorText(error.message ?: "Tool execution failed")
        }
    }

    /**
     * Lists fixed OOB MCP tools plus active Project Toolbox dynamic tools.
     *
     * @param context Android context used to load the active Project registry.
     * @return MCP tool descriptors.
     */
    private fun listMcpTools(context: Context): List<Map<String, Any?>> {
        val dynamicTools = runCatching { WorkbenchProjectStore(context).activeMcpTools() }
            .getOrElse { emptyList() }
        return McpToolDefinitions.fixedTools + dynamicTools
    }

    private fun mcpProjectCreate(context: Context, args: Map<String, Any?>?): Map<String, Any?> {
        return mcpProjectToolResult(
            "oob_project_create",
            WorkbenchProjectStore(context).createProject(args ?: emptyMap())
        )
    }

    private fun mcpProjectActivate(context: Context, args: Map<String, Any?>?): Map<String, Any?> {
        val projectId = stringArg(args ?: emptyMap(), "projectId")
        return mcpProjectToolResult(
            "oob_project_activate",
            WorkbenchProjectStore(context).activateProject(projectId)
        )
    }

    private suspend fun mcpProjectOpen(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> {
        val projectId = stringArg(args ?: emptyMap(), "projectId")
        val store = WorkbenchProjectStore(context)
        val route = store.routeForProject(projectId)
        withContext(Dispatchers.Main) {
            TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
        }
        return mcpProjectToolResult(
            "oob_project_open",
            linkedMapOf(
                "success" to true,
                "projectId" to projectId,
                "route" to route
            )
        )
    }

    private fun mcpProjectProgressGet(context: Context, args: Map<String, Any?>?): Map<String, Any?> {
        val requestArgs = args ?: emptyMap()
        return mcpProjectToolResult(
            "oob_project_progress_get",
            WorkbenchProjectStore(context).getProjectProgress(
                projectId = requestArgs["projectId"]?.toString(),
                limit = requestArgs["limit"]?.toString()?.toIntOrNull() ?: 50
            )
        )
    }

    private fun mcpProjectToolResult(toolName: String, result: Map<String, Any?>): Map<String, Any?> {
        val success = result["success"] != false
        val text = if (success) {
            "OOB MCP tool `$toolName` completed."
        } else {
            "OOB MCP tool `$toolName` failed: ${result["errorMessage"] ?: result["errorCode"] ?: "unknown error"}"
        }
        return result + linkedMapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "isError" to if (success) null else true
        )
    }

    /**
     * Executes Workbench control-plane calls through the authenticated MCP/Dashboard debug route.
     *
     * @param context Android application context used to resolve the shared OOB workspace.
     * @param name Workbench control/API tool name, for example `workbench_project_create` or
     * `workbench_api_call`. The route is intentionally not part of MCP tool discovery.
     * @param args JSON request arguments. `workbench_api_call` expects nested `inputs`.
     * @return JSON-safe transport payload. The outer `success` is route execution status; the
     * nested `result` is the native Workbench runtime payload.
     */
    private suspend fun executeWorkbenchDebugCall(
        context: Context,
        name: String?,
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val store = WorkbenchProjectStore(context)
        return runCatching {
            val result: Any? = when (name) {
                "workbench_project_create" -> store.createProject(args)
                "workbench_project_list" -> store.listProjects()
                "workbench_project_get" -> store.getProject(stringArg(args, "projectId"))
                "workbench_project_open" -> {
                    val projectId = stringArg(args, "projectId")
                    val route = store.routeForProject(projectId)
                    withContext(Dispatchers.Main) {
                        TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
                    }
                    linkedMapOf(
                        "success" to true,
                        "projectId" to projectId,
                        "route" to route
                    )
                }
                "workbench_project_activate" -> store.activateProject(stringArg(args, "projectId"))
                "workbench_project_active_get" -> store.getActiveProject()
                "workbench_project_deactivate" -> store.deactivateProject()
                "workbench_project_delete" -> store.deleteProject(stringArg(args, "projectId"))
                "workbench_project_export" -> store.exportProject(stringArg(args, "projectId"))
                "workbench_project_hot_update" -> store.hotUpdateProject(
                    projectId = stringArg(args, "projectId"),
                    prompt = stringArg(args, "prompt"),
                    caller = "mcp_dashboard",
                    frontendContext = mapArg(args["frontendContext"]) ?: emptyMap()
                )
                "workbench_project_ingest_android" -> store.ingestAndroidAsset(
                    projectId = stringArg(args, "projectId"),
                    sourcePath = stringArg(args, "sourcePath"),
                    sourceKind = args["sourceKind"]?.toString(),
                    displayName = args["displayName"]?.toString(),
                    caller = "mcp_dashboard"
                )
                "workbench_project_ingest_oss" -> store.ingestOssSource(
                    projectId = stringArg(args, "projectId"),
                    sourceUrl = args["sourceUrl"]?.toString(),
                    sourcePath = args["sourcePath"]?.toString(),
                    sourceKind = args["sourceKind"]?.toString(),
                    ref = args["ref"]?.toString(),
                    displayName = args["displayName"]?.toString(),
                    caller = "mcp_dashboard"
                )
                "workbench_project_progress_get" -> store.getProjectProgress(
                    projectId = args["projectId"]?.toString(),
                    limit = args["limit"]?.toString()?.toIntOrNull() ?: 50
                )
                "workbench_api_list" -> store.listApis(args["projectId"]?.toString())
                "workbench_api_call" -> store.callApi(
                    projectId = stringArg(args, "projectId"),
                    apiId = stringArg(args, "apiId"),
                    inputs = mapArg(args["inputs"]) ?: emptyMap(),
                    caller = "mcp_dashboard"
                )
                "debug_model_provider_configure" -> configureModelProviderForDebug(
                    context = context,
                    args = args
                )
                "debug_model_provider_get" -> getModelProviderDebugState(context)
                else -> throw IllegalArgumentException("Unknown Workbench debug call: $name")
            }
            linkedMapOf(
                "success" to true,
                "name" to name,
                "result" to result
            )
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "name" to name,
                "error" to (error.message ?: "Workbench debug call failed")
            )
        }
    }

    /**
     * Configures the active model provider through the authenticated local debug route.
     *
     * @param context Android application context used to sync the agent config file.
     * @param args JSON-style arguments. Required keys are `baseUrl`, `apiKey`, and `modelId`;
     * optional keys are `profileId`, `name`, `protocolType`, and `sceneIds`.
     * @return JSON-safe provider and scene binding summary for E2E setup.
     */
    private fun configureModelProviderForDebug(
        context: Context,
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val profileId = args["profileId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "dashboard-e2e"
        val name = args["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Dashboard E2E"
        val baseUrl = stringArg(args, "baseUrl")
        val apiKey = stringArg(args, "apiKey")
        val modelId = stringArg(args, "modelId")
        val protocolType = args["protocolType"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "openai_compatible"
        val sceneIds = listArg(args["sceneIds"]).ifEmpty {
            listOf(
                "scene.dispatch.model",
                "scene.vlm.operation.primary",
                "scene.compactor.context",
                "scene.compactor.context.chat"
            )
        }

        val profile = ModelProviderConfigStore.saveProfile(
            id = profileId,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            protocolType = protocolType
        )
        sceneIds.forEach { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profile.id,
                modelId = modelId
            )
        }
        AgentAiCapabilityConfigSync.get(context).syncFileFromStores()
        AssistsCoreManager.dispatchAgentAiConfigChanged(
            source = "mcp_dashboard",
            path = "model_provider_debug_config"
        )

        return linkedMapOf(
            "profile" to profileMap(profile),
            "sceneBindings" to SceneModelBindingStore.getBindingEntries().map(::bindingMap)
        )
    }

    /**
     * Returns current model provider and scene binding state for local E2E debugging.
     *
     * @param context Android application context used to ensure the config sync singleton exists.
     * @return JSON-safe state summary without exposing any route through MCP tool discovery.
     */
    private fun getModelProviderDebugState(context: Context): Map<String, Any?> {
        AgentAiCapabilityConfigSync.get(context).syncFileFromStores()
        return linkedMapOf(
            "editingProfileId" to ModelProviderConfigStore.getEditingProfileId(),
            "profiles" to ModelProviderConfigStore.listProfiles().map(::profileMap),
            "sceneBindings" to SceneModelBindingStore.getBindingEntries().map(::bindingMap)
        )
    }

    /**
     * Converts a provider profile to a JSON-safe debug response map.
     *
     * @param profile Model provider profile from the OOB provider store.
     * @return Public profile fields required to audit local E2E setup.
     */
    private fun profileMap(profile: ModelProviderProfile): Map<String, Any?> {
        return mapOf(
            "id" to profile.id,
            "name" to profile.name,
            "baseUrl" to profile.baseUrl,
            "apiKeyConfigured" to profile.apiKey.isNotBlank(),
            "configured" to profile.isConfigured(),
            "protocolType" to profile.protocolType
        )
    }

    /**
     * Converts one scene binding to a JSON-safe debug response map.
     *
     * @param binding Scene-to-provider/model binding stored in OOB.
     * @return Scene binding fields needed to verify VLM/Agent model routing.
     */
    private fun bindingMap(binding: SceneModelBindingEntry): Map<String, Any?> {
        return mapOf(
            "sceneId" to binding.sceneId,
            "providerProfileId" to binding.providerProfileId,
            "modelId" to binding.modelId
        )
    }

    /**
     * Reads one string argument from a dynamic request body.
     *
     * @param args JSON-style argument map received by the debug route.
     * @param key Required key whose value must be nonblank.
     * @return Trimmed argument value.
     */
    private fun stringArg(args: Map<String, Any?>, key: String): String {
        val value = args[key]?.toString()?.trim().orEmpty()
        require(value.isNotEmpty()) { "Missing required argument: $key" }
        return value
    }

    /**
     * Converts a dynamic JSON object into a string-keyed map.
     *
     * @param value Value decoded by Ktor/Gson from request JSON.
     * @return String-keyed map, or null when the value is not an object.
     */
    private fun mapArg(value: Any?): Map<String, Any?>? {
        val raw = value as? Map<*, *> ?: return null
        return raw.entries.associate { entry -> entry.key.toString() to entry.value }
    }

    /**
     * Converts a dynamic JSON list or comma-separated value into normalized strings.
     *
     * @param value Dynamic value decoded from the debug request body.
     * @return Nonblank string values in caller-provided order.
     */
    private fun listArg(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> value.split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            else -> emptyList()
        }
    }

    // ==================== 传统端点处理（保持兼容） ====================

    private suspend fun handleLegacyVlmTask(
        call: io.ktor.server.application.ApplicationCall,
        context: Context,
        serverScope: CoroutineScope
    ) {
        val remoteHost = call.request.headers["X-Forwarded-For"]
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?: call.request.headers["X-Real-IP"]
            ?: call.request.host()

        if (!McpNetworkUtils.isLanAddress(remoteHost)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "LAN_ONLY"))
            return
        }

        val payload = runCatching { call.receive<VlmTaskRequest>() }
            .getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_BODY"))
                return
            }

        if (payload.goal.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "EMPTY_GOAL"))
            return
        }

        val args = mapOf(
            "goal" to payload.goal,
            "model" to payload.model,
            "packageName" to payload.packageName,
            "needSummary" to payload.needSummary
        )

        val result = McpToolExecutors.executeVlmTask(context, args, serverScope)
        call.respond(HttpStatusCode.OK, result)
    }

    private suspend fun handleLegacyTaskReply(
        call: io.ktor.server.application.ApplicationCall
    ) {
        val taskId = call.parameters["taskId"]
        val body = call.receive<Map<String, Any?>>()
        val reply = body["reply"] as? String ?: body["input"] as? String

        if (taskId == null || reply == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing taskId or reply"))
            return
        }

        val state = McpTaskManager.getTask(taskId)
        if (state == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            return
        }

        if (state.status != TaskStatus.WAITING_INPUT) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "Task is not waiting for input", "status" to state.status.name)
            )
            return
        }

        val success = AssistsUtil.Core.provideUserInputToVLMTask(reply)
        if (success) {
            state.status = TaskStatus.RUNNING
            state.waitingQuestion = null
            call.respond(mapOf("success" to true, "taskId" to taskId, "status" to "RUNNING"))
        } else {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to provide input"))
        }
    }
}
