package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.bot.util.AssistsUtil
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
                call.respond(mapOf("tools" to McpToolDefinitions.allTools))
            }
            post("/mcp/list_tools") {
                call.respond(mapOf("tools" to McpToolDefinitions.allTools))
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
                    "capabilities" to mapOf("tools" to mapOf<String, Any>()),
                    "serverInfo" to mapOf("name" to "小万Mcp", "version" to "1.0")
                )
            )
            "notifications/initialized" -> null
            "tools/list" -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to mapOf("tools" to McpToolDefinitions.allTools)
            )
            "tools/call" -> {
                val params = request["params"] as? Map<String, Any?>
                val name = params?.get("name") as? String
                val args = params?.get("arguments") as? Map<String, Any?>
                val execResult = executeTool(context, serverScope, name, args)
                mapOf("jsonrpc" to "2.0", "id" to id, "result" to execResult)
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
        return when (name) {
            "vlm_task" -> McpToolExecutors.executeVlmTask(context, args, serverScope)
            "task_status" -> McpToolExecutors.executeTaskStatus(args)
            "task_reply" -> McpToolExecutors.executeTaskReply(args)
            "task_wait_unlock" -> McpToolExecutors.executeTaskWaitUnlock(context, args, serverScope)
            "file_transfer" -> McpToolExecutors.executeFileTransfer(args)
            else -> McpResponseBuilder.buildErrorText("Unknown tool: $name")
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
