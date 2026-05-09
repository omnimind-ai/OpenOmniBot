package cn.com.omnimind.bot.mcp

import android.content.Context
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.BrowserMirrorService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.RealtimeHub
import cn.com.omnimind.bot.webchat.WorkspaceFileService
import cn.com.omnimind.bot.util.AssistsUtil
import com.tencent.mmkv.MMKV
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID

/**
 * 简单的局域网 MCP 服务管理器，负责启动、鉴权、停止与状态查询。
 */
object McpServerManager {
    private const val TAG = "[McpServerManager]"
    private const val PREF_ENABLE = "mcp_server_enabled"
    private const val PREF_HOST = "mcp_server_host"
    private const val PREF_TOKEN = "mcp_server_token"
    private const val PREF_PORT = "mcp_server_port"
    private const val DEFAULT_PORT = 8899
    private const val WEBCHAT_SESSION_COOKIE = "omnibot_webchat_session"
    private const val WEBCHAT_ASSET_DIR = "flutter_web"
    private const val WEBCHAT_SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    private val mmkv by lazy { MMKV.defaultMMKV() }
    private val gson by lazy { Gson() }
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverLock = Any()
    private val webChatSessionLock = Any()
    private val webChatSessions: MutableMap<String, Long> = mutableMapOf()

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var isRunning: Boolean = false

    @Volatile
    private var activeHost: String? = null

    // ==================== 公共 API ====================

    fun restoreIfEnabled(context: Context) {
        if (!mmkv.decodeBool(PREF_ENABLE, false)) return
        val port = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
        serverScope.launch {
            runCatching { startServer(context, port) }
                .onFailure { OmniLog.e(TAG, "restoreIfEnabled failed: ${it.message}") }
        }
    }

    fun setEnabled(context: Context, enable: Boolean, port: Int? = null): McpServerState {
        if (enable) {
            val targetPort = port ?: mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
            return startServer(context, targetPort)
        } else {
            stopServer()
            mmkv.encode(PREF_ENABLE, false)
        }
        return currentState()
    }

    fun refreshToken(context: Context): McpServerState {
        val newToken = generateToken()
        mmkv.encode(PREF_TOKEN, newToken)
        if (isRunning || mmkv.decodeBool(PREF_ENABLE, false)) {
            return restart(context)
        }
        return currentState()
    }

    fun currentState(): McpServerState {
        val resolvedHost = resolveAdvertisedHost()
        return McpServerState(
            enabled = mmkv.decodeBool(PREF_ENABLE, false) && isRunning,
            running = isRunning,
            host = resolvedHost,
            port = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT,
            token = ensureToken(),
        )
    }

    fun stopServer() {
        synchronized(serverLock) {
            stopServerLocked()
        }
    }

    fun getActiveTasks(): List<Map<String, Any?>> = McpTaskManager.getActiveTasks()

    fun cleanupExpiredTasks(maxAgeMs: Long = 600_000) = McpTaskManager.cleanupExpiredTasks(maxAgeMs)

    // ==================== 私有方法 ====================

    private fun restart(context: Context): McpServerState {
        val port = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
        synchronized(serverLock) {
            stopServerLocked()
        }
        return startServer(context, port)
    }

    private fun startServer(context: Context, port: Int): McpServerState {
        synchronized(serverLock) {
            try {
                val lanIp = resolveLanIp()
                    ?: throw IllegalStateException("未检测到可用的局域网 IPv4 地址，请确认设备已连接可访问局域网的网络")
                if (isRunning) {
                    val currentPort = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
                    if (currentPort == port) {
                        activeHost = lanIp
                        mmkv.encode(PREF_HOST, lanIp)
                        return currentState()
                    }
                    stopServerLocked()
                }
                val engine = buildServer(context, port)
                engine.start(wait = false)

                server = engine
                isRunning = true
                activeHost = lanIp
                mmkv.encode(PREF_ENABLE, true)
                mmkv.encode(PREF_PORT, port)
                mmkv.encode(PREF_HOST, lanIp)
                OmniLog.i(TAG, "MCP server started at http://$lanIp:$port")
                return currentState()
            } catch (t: Throwable) {
                server = null
                isRunning = false
                activeHost = null
                OmniLog.e(TAG, "startServer failed: ${t.message}")
                throw t
            }
        }
    }

    private fun buildServer(
        context: Context,
        port: Int
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        val token = ensureToken()
        val appContext = context.applicationContext
        val conversationService = ConversationDomainService(appContext)
        val workspaceFileService = WorkspaceFileService(appContext)
        val browserMirrorService = BrowserMirrorService(appContext)
        val agentRunService = AgentRunService(appContext)
        return embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(CallLogging)
            install(ContentNegotiation) { gson() }
            install(Authentication) {
                bearer("bearer-auth") {
                    authenticate { credential ->
                        if (credential.token == token) UserIdPrincipal("mcp-client") else null
                    }
                }
            }
            routing {
                get("/") {
                    call.respondRedirect("/webchat/")
                }

                post("/webchat/api/session/bootstrap") {
                    handleWebChatSessionBootstrap(call)
                }

                // 健康检查（无需认证）
                get("/mcp/health") {
                    call.respond(mapOf("status" to "ok"))
                }
                // 文件下载（使用文件token或Bearer token）
                get("/mcp/file/{fileId}") { handleFileDownload(call) }

                route("/webchat/api") {
                    get("/bootstrap") {
                        if (!requireWebChatAuth(call)) return@get
                        call.respond(
                            mapOf(
                                "server" to currentState().toMap(),
                                "capabilities" to mapOf(
                                    "conversations" to true,
                                    "streaming" to true,
                                    "workspace" to true,
                                    "browserMirror" to true
                                ),
                                "routes" to mapOf(
                                    "events" to "/webchat/api/events",
                                    "browserFrame" to "/webchat/api/browser/frame",
                                    "workspaceDownload" to "/webchat/api/workspaces/download"
                                ),
                                "workspace" to workspaceFileService.bootstrapPayload(),
                                "browser" to browserMirrorService.snapshot()
                            )
                        )
                    }

                    get("/conversations") {
                        if (!requireWebChatAuth(call)) return@get
                        val includeArchived = call.request.queryParameters.boolean("includeArchived", true)
                        val archivedOnly = call.request.queryParameters.boolean("archivedOnly", false)
                        call.respond(
                            conversationService.listConversationPayloads(
                                includeArchived = includeArchived,
                                archivedOnly = archivedOnly
                            )
                        )
                    }

                    post("/conversations") {
                        if (!requireWebChatAuth(call)) return@post
                        val body = call.receive<Map<String, Any?>>()
                        call.respond(
                            HttpStatusCode.Created,
                            conversationService.createConversation(
                                title = body["title"]?.toString() ?: "新对话",
                                mode = body["mode"]?.toString() ?: "normal",
                                summary = body["summary"]?.toString()
                            )
                        )
                    }

                    patch("/conversations/{conversationId}") {
                        if (!requireWebChatAuth(call)) return@patch
                        val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                        if (conversationId == null || conversationId <= 0L) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_CONVERSATION_ID"))
                            return@patch
                        }
                        val body = call.receive<Map<String, Any?>>().toMutableMap()
                        body["id"] = conversationId
                        call.respond(
                            conversationService.updateConversationFromPayload(body)
                        )
                    }

                    delete("/conversations/{conversationId}") {
                        if (!requireWebChatAuth(call)) return@delete
                        val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                        if (conversationId == null || conversationId <= 0L) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_CONVERSATION_ID"))
                            return@delete
                        }
                        conversationService.deleteConversation(conversationId)
                        call.respond(mapOf("success" to true))
                    }

                    get("/conversations/{conversationId}/messages") {
                        if (!requireWebChatAuth(call)) return@get
                        val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                        if (conversationId == null || conversationId <= 0L) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_CONVERSATION_ID"))
                            return@get
                        }
                        val mode = call.request.queryParameters["mode"] ?: "normal"
                        call.respond(
                            conversationService.listConversationMessages(
                                conversationId = conversationId,
                                conversationMode = mode
                            )
                        )
                    }

                    post("/conversations/{conversationId}/runs") {
                        if (!requireWebChatAuth(call)) return@post
                        val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                        if (conversationId == null || conversationId <= 0L) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_CONVERSATION_ID"))
                            return@post
                        }
                        val body = call.receive<Map<String, Any?>>()
                        val accepted = runCatching {
                            agentRunService.startConversationRun(conversationId, body)
                        }.getOrElse { error ->
                            call.respond(HttpStatusCode.Conflict, mapOf("error" to (error.message ?: "RUN_START_FAILED")))
                            return@post
                        }
                        call.respond(HttpStatusCode.Accepted, accepted)
                    }

                    post("/tasks/{taskId}/cancel") {
                        if (!requireWebChatAuth(call)) return@post
                        val taskId = call.parameters["taskId"]?.trim().takeUnless { it.isNullOrEmpty() }
                        call.respond(agentRunService.cancelTask(taskId))
                    }

                    post("/tasks/{taskId}/clarify") {
                        if (!requireWebChatAuth(call)) return@post
                        val taskId = call.parameters["taskId"]?.trim().takeUnless { it.isNullOrEmpty() }
                        val body = call.receive<Map<String, Any?>>()
                        val reply = body["reply"]?.toString() ?: body["userInput"]?.toString().orEmpty()
                        if (reply.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "EMPTY_REPLY"))
                            return@post
                        }
                        call.respond(agentRunService.clarifyTask(taskId, reply))
                    }

                    get("/events") {
                        if (!requireWebChatAuth(call)) return@get
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header(HttpHeaders.Connection, "keep-alive")
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            write(": connected\n\n")
                            flush()
                            RealtimeHub.stream().collect { event ->
                                write("id: ${event.id}\n")
                                write("event: ${event.event}\n")
                                write("data: ${gson.toJson(event.data)}\n\n")
                                flush()
                            }
                        }
                    }

                    route("/workspaces") {
                        get {
                            if (!requireWebChatAuth(call)) return@get
                            val path = call.request.queryParameters["path"]
                            val recursive = call.request.queryParameters.boolean("recursive", false)
                            val maxDepth = call.request.queryParameters["maxDepth"]?.toIntOrNull() ?: 2
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                            call.respond(
                                if (path.isNullOrBlank()) {
                                    workspaceFileService.bootstrapPayload()
                                } else {
                                    workspaceFileService.list(
                                        path = path,
                                        recursive = recursive,
                                        maxDepth = maxDepth,
                                        limit = limit
                                    )
                                }
                            )
                        }

                        get("/file") {
                            if (!requireWebChatAuth(call)) return@get
                            val path = call.request.queryParameters["path"]
                            if (path.isNullOrBlank()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_PATH"))
                                return@get
                            }
                            val maxChars = call.request.queryParameters["maxChars"]?.toIntOrNull() ?: 64_000
                            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                            val lineStart = call.request.queryParameters["lineStart"]?.toIntOrNull()
                            val lineCount = call.request.queryParameters["lineCount"]?.toIntOrNull()
                            call.respond(
                                workspaceFileService.readFile(
                                    path = path,
                                    maxChars = maxChars,
                                    offset = offset,
                                    lineStart = lineStart,
                                    lineCount = lineCount
                                )
                            )
                        }

                        put("/file") {
                            if (!requireWebChatAuth(call)) return@put
                            val body = call.receive<Map<String, Any?>>()
                            val path = body["path"]?.toString().orEmpty()
                            if (path.isBlank()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_PATH"))
                                return@put
                            }
                            call.respond(
                                workspaceFileService.writeFile(
                                    path = path,
                                    content = body["content"]?.toString() ?: "",
                                    append = body["append"] == true
                                )
                            )
                        }

                        post("/move") {
                            if (!requireWebChatAuth(call)) return@post
                            val body = call.receive<Map<String, Any?>>()
                            val sourcePath = body["sourcePath"]?.toString().orEmpty()
                            val targetPath = body["targetPath"]?.toString().orEmpty()
                            if (sourcePath.isBlank() || targetPath.isBlank()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_PATH"))
                                return@post
                            }
                            call.respond(
                                workspaceFileService.move(
                                    sourcePath = sourcePath,
                                    targetPath = targetPath,
                                    overwrite = body["overwrite"] == true
                                )
                            )
                        }

                        delete("/file") {
                            if (!requireWebChatAuth(call)) return@delete
                            val path = call.request.queryParameters["path"]
                            if (path.isNullOrBlank()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_PATH"))
                                return@delete
                            }
                            workspaceFileService.delete(
                                path = path,
                                recursive = call.request.queryParameters.boolean("recursive", false)
                            )
                            call.respond(mapOf("success" to true))
                        }

                        get("/download") {
                            if (!requireWebChatAuth(call)) return@get
                            val path = call.request.queryParameters["path"]
                            if (path.isNullOrBlank()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_PATH"))
                                return@get
                            }
                            val (file, mimeType) = workspaceFileService.resolveDownloadFile(path)
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(
                                    ContentDisposition.Parameters.FileName,
                                    file.name
                                ).toString()
                            )
                            call.respondFile(file)
                        }
                    }

                    route("/browser") {
                        get("/snapshot") {
                            if (!requireWebChatAuth(call)) return@get
                            call.respond(browserMirrorService.snapshot())
                        }

                        get("/frame") {
                            if (!requireWebChatAuth(call)) return@get
                            val frame = browserMirrorService.frameBytes()
                            if (frame == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "BROWSER_FRAME_UNAVAILABLE"))
                            } else {
                                call.respondBytes(frame, ContentType.Image.PNG)
                            }
                        }

                        post("/action") {
                            if (!requireWebChatAuth(call)) return@post
                            val body = call.receive<Map<String, Any?>>()
                            call.respond(browserMirrorService.executeAction(body))
                        }
                    }
                }

                get("/webchat") {
                    call.respondRedirect("/webchat/")
                }
                get("/webchat/") {
                    handleWebChatStatic(call, appContext)
                }
                get("/webchat/{...}") {
                    if (call.request.path().startsWith("/webchat/api/")) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    handleWebChatStatic(call, appContext)
                }
                
                authenticate("bearer-auth") {
                    // 服务状态
                    get("/mcp/state") {
                        call.respond(currentState().toMap())
                    }
                    
                    // MCP JSON-RPC 端点
                    post("/mcp") { handleJsonRpc(call, context) }
                    
                    // 工具发现
                    get("/mcp/list_tools") { call.respond(mapOf("tools" to McpToolDefinitions.allTools)) }
                    post("/mcp/list_tools") { call.respond(mapOf("tools" to McpToolDefinitions.allTools)) }
                    
                    // REST 风格工具调用
                    post("/mcp/call_tool") {
                        val params = call.receive<Map<String, Any?>>()
                        val result = executeTool(context, params["name"] as? String, params["arguments"] as? Map<String, Any?>)
                        call.respond(result)
                    }
                    
                    // 传统 VLM 任务端点（保持兼容）
                    post("/mcp/v1/task/vlm") { handleLegacyVlmTask(call, context) }
                    
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
                    post("/mcp/v1/task/{taskId}/reply") { handleLegacyTaskReply(call) }
                }
            }
        }
    }

    private fun stopServerLocked() {
        runCatching {
            server?.stop(500, 1_500)
        }.onFailure {
            OmniLog.e(TAG, "stopServer error: ${it.message}")
        }
        server = null
        isRunning = false
        activeHost = null
        synchronized(webChatSessionLock) {
            webChatSessions.clear()
        }
    }

    private fun resolveLanIp(): String? {
        return runCatching { McpNetworkUtils.currentLanIp() }
            .onFailure { OmniLog.e(TAG, "resolveLanIp failed: ${it.message}") }
            .getOrNull()
    }

    private fun resolveAdvertisedHost(): String? {
        val currentHost = resolveLanIp()
        if (currentHost != null && isRunning) {
            synchronized(serverLock) {
                if (isRunning && activeHost != currentHost) {
                    activeHost = currentHost
                    mmkv.encode(PREF_HOST, currentHost)
                }
            }
        }
        if (currentHost != null) return currentHost
        return if (isRunning) {
            activeHost ?: mmkv.decodeString(PREF_HOST)
        } else {
            null
        }
    }

    // ==================== JSON-RPC 处理 ====================

    private suspend fun handleJsonRpc(call: io.ktor.server.application.ApplicationCall, context: Context) {
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
                val execResult = executeTool(context, name, args)
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

    // ==================== 文件下载 ====================

    private suspend fun handleFileDownload(call: io.ktor.server.application.ApplicationCall) {
        val fileId = call.parameters["fileId"]
        if (fileId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileId"))
            return
        }

        val record = McpFileInbox.getFile(fileId)
        if (record == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            return
        }

        val token = call.request.queryParameters["token"]
        val authHeader = call.request.headers["Authorization"]
        val bearerToken = authHeader?.removePrefix("Bearer ")?.trim()
        val bearerOk = bearerToken == currentState().token
        val tokenOk = McpFileInbox.isTokenValid(record, token)

        if (!tokenOk && !bearerOk) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Invalid token"))
            return
        }

        val file = File(record.path)
        if (!file.exists()) {
            McpFileInbox.removeFile(fileId)
            call.respond(HttpStatusCode.Gone, mapOf("error" to "File expired"))
            return
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, record.fileName).toString()
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondFile(file)
    }

    private suspend fun handleWebChatSessionBootstrap(
        call: io.ktor.server.application.ApplicationCall
    ) {
        if (!isLanRequest(call)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "LAN_ONLY"))
            return
        }
        val body = runCatching { call.receive<Map<String, Any?>>() }.getOrDefault(emptyMap())
        val token = body["token"]?.toString()
            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank() || token != currentState().token) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "INVALID_TOKEN"))
            return
        }
        pruneExpiredWebChatSessions()
        val sessionId = UUID.randomUUID().toString()
        synchronized(webChatSessionLock) {
            webChatSessions[sessionId] = System.currentTimeMillis() + WEBCHAT_SESSION_TTL_MS
        }
        call.response.cookies.append(
            Cookie(
                name = WEBCHAT_SESSION_COOKIE,
                value = sessionId,
                httpOnly = true,
                path = "/",
                maxAge = (WEBCHAT_SESSION_TTL_MS / 1000L).toInt()
            )
        )
        call.respond(
            mapOf(
                "success" to true,
                "server" to currentState().toMap()
            )
        )
    }

    private suspend fun requireWebChatAuth(
        call: io.ktor.server.application.ApplicationCall
    ): Boolean {
        if (!isLanRequest(call)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "LAN_ONLY"))
            return false
        }
        val bearerToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?.trim()
        if (!bearerToken.isNullOrBlank() && bearerToken == currentState().token) {
            return true
        }
        pruneExpiredWebChatSessions()
        val sessionId = call.request.cookies[WEBCHAT_SESSION_COOKIE]
        val valid = synchronized(webChatSessionLock) {
            val expiresAt = sessionId?.let { webChatSessions[it] }
            expiresAt != null && expiresAt > System.currentTimeMillis()
        }
        if (valid) {
            return true
        }
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "UNAUTHORIZED"))
        return false
    }

    private fun pruneExpiredWebChatSessions() {
        val now = System.currentTimeMillis()
        synchronized(webChatSessionLock) {
            webChatSessions.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
        }
    }

    private fun isLanRequest(call: io.ktor.server.application.ApplicationCall): Boolean {
        val remoteHost = call.request.headers["X-Forwarded-For"]
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?: call.request.headers["X-Real-IP"]
            ?: call.request.host()
        return McpNetworkUtils.isLanAddress(remoteHost)
    }

    private suspend fun handleWebChatStatic(
        call: io.ktor.server.application.ApplicationCall,
        context: Context
    ) {
        val requestPath = call.request.path()
            .removePrefix("/webchat")
            .trimStart('/')
        val normalizedPath = requestPath
            .takeIf { it.isNotBlank() && !it.contains("..") }
            ?: "index.html"
        val assetPath = if (normalizedPath.contains('.')) {
            "$WEBCHAT_ASSET_DIR/$normalizedPath"
        } else {
            "$WEBCHAT_ASSET_DIR/index.html"
        }
        val assetBytes = openAssetBytes(
            context,
            assetPath,
            normalizedPath
        )
        if (assetBytes != null) {
            call.respondBytes(
                bytes = assetBytes,
                contentType = contentTypeForPath(assetPath)
            )
            return
        }
        if (!normalizedPath.endsWith("index.html")) {
            val fallbackIndex = openAssetBytes(
                context,
                "$WEBCHAT_ASSET_DIR/index.html",
                "index.html"
            )
            if (fallbackIndex != null) {
                call.respondBytes(
                    bytes = fallbackIndex,
                    contentType = ContentType.Text.Html
                )
                return
            }
        }
        call.respondText(
            """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Omnibot Web Chat</title>
              <style>
                body { font-family: sans-serif; margin: 0; padding: 32px; background: #f7f9fc; color: #24324a; }
                .card { max-width: 680px; margin: 8vh auto 0; background: white; border-radius: 20px; padding: 28px; box-shadow: 0 16px 48px rgba(19, 38, 72, 0.12); }
                code { background: #eef3fb; padding: 2px 6px; border-radius: 6px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h2>Web Chat Bundle Missing</h2>
                <p>尚未找到 Flutter Web 构建产物，请重新构建并安装最新 APK，确保 <code>flutter build web --base-href /webchat/</code> 的产物已被打包进应用。</p>
              </div>
            </body>
            </html>
            """.trimIndent(),
            contentType = ContentType.Text.Html
        )
    }

    private fun openAssetBytes(
        context: Context,
        vararg assetPaths: String
    ): ByteArray? {
        assetPaths.forEach { candidate ->
            val bytes = runCatching {
                context.assets.open(candidate).use { input ->
                    input.readBytes()
                }
            }.getOrNull()
            if (bytes != null) {
                return bytes
            }
        }
        return null
    }

    private fun contentTypeForPath(path: String): ContentType {
        return when {
            path.endsWith(".html") -> ContentType.Text.Html
            path.endsWith(".js") -> ContentType.Application.JavaScript
            path.endsWith(".css") -> ContentType.Text.CSS
            path.endsWith(".json") -> ContentType.Application.Json
            path.endsWith(".png") -> ContentType.Image.PNG
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> ContentType.Image.JPEG
            path.endsWith(".svg") -> ContentType.parse("image/svg+xml")
            path.endsWith(".wasm") -> ContentType.parse("application/wasm")
            path.endsWith(".ico") -> ContentType.parse("image/x-icon")
            else -> ContentType.Application.OctetStream
        }
    }

    // ==================== 工具执行 ====================

    private suspend fun executeTool(context: Context, name: String?, args: Map<String, Any?>?): Map<String, Any?> {
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

    private suspend fun handleLegacyVlmTask(call: io.ktor.server.application.ApplicationCall, context: Context) {
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
        
        val taskId = UUID.randomUUID().toString()
        val args = mapOf(
            "goal" to payload.goal,
            "model" to payload.model,
            "packageName" to payload.packageName,
            "needSummary" to payload.needSummary
        )
        
        val result = McpToolExecutors.executeVlmTask(context, args, serverScope)
        call.respond(HttpStatusCode.OK, result)
    }

    private suspend fun handleLegacyTaskReply(call: io.ktor.server.application.ApplicationCall) {
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
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Task is not waiting for input", "status" to state.status.name))
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

    // ==================== Token 管理 ====================

    private fun ensureToken(): String {
        val saved = mmkv.decodeString(PREF_TOKEN)
        if (!saved.isNullOrBlank()) return saved
        val token = generateToken()
        mmkv.encode(PREF_TOKEN, token)
        return token
    }

    private fun generateToken(): String {
        val random = SecureRandom()
        val buffer = ByteArray(32)
        random.nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun io.ktor.http.Parameters.boolean(
        key: String,
        defaultValue: Boolean
    ): Boolean {
        return when (this[key]?.trim()?.lowercase()) {
            "1",
            "true",
            "yes" -> true
            "0",
            "false",
            "no" -> false
            else -> defaultValue
        }
    }
}
