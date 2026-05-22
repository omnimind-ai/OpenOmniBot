package cn.com.omnimind.bot.omniinfer

import android.content.Context
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpNetworkUtils
import com.tencent.mmkv.MMKV
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class OmniInferLanProxyState(
    val running: Boolean,
    val host: String,
    val port: Int,
    val baseUrl: String,
    val token: String,
    val targetBaseUrl: String,
    val error: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "lanProxyRunning" to running,
        "lanHost" to host,
        "lanProxyPort" to port,
        "lanBaseUrl" to baseUrl,
        "lanToken" to token,
        "lanTargetBaseUrl" to targetBaseUrl,
        "lanProxyError" to error,
    )
}

object OmniInferLanProxyManager {
    private const val TAG = "OmniInferLanProxy"
    private const val MMKV_ID = "omniinfer_config"
    private const val KEY_TOKEN = "omniinfer_lan_proxy_token"
    private const val KEY_PORT = "omniinfer_lan_proxy_port"
    private const val KEY_LAST_ERROR = "omniinfer_lan_proxy_last_error"
    private const val DEFAULT_PROXY_PORT = 9100

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
    private val lock = Any()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var running = false

    @Volatile
    private var activePort = 0

    @Volatile
    private var activeTargetPort = 0

    fun start(context: Context, targetPort: Int, requestedPort: Int? = null): OmniInferLanProxyState {
        val target = if (targetPort > 0) targetPort else OmniInferLocalRuntime.getPort()
        val proxyPort = requestedPort?.takeIf { it > 0 }
            ?: mmkv.decodeInt(KEY_PORT, 0).takeIf { it > 0 }
            ?: defaultProxyPort(target)
        synchronized(lock) {
            if (running && activePort == proxyPort && activeTargetPort == target) {
                clearError()
                return currentState(target)
            }
            stopLocked()
            return runCatching {
                val appContext = context.applicationContext
                val engine = buildServer(appContext, proxyPort, target)
                engine.start(wait = false)
                server = engine
                running = true
                activePort = proxyPort
                activeTargetPort = target
                mmkv.encode(KEY_PORT, proxyPort)
                clearError()
                OmniLog.i(TAG, "LAN proxy started at ${currentState(target).baseUrl}, target=127.0.0.1:$target")
                currentState(target)
            }.getOrElse { error ->
                server = null
                running = false
                activePort = 0
                activeTargetPort = 0
                val message = error.message ?: "Failed to start LAN proxy"
                mmkv.encode(KEY_LAST_ERROR, message)
                OmniLog.e(TAG, "start failed: $message")
                throw error
            }
        }
    }

    fun stop(): OmniInferLanProxyState {
        synchronized(lock) {
            stopLocked()
            clearError()
            return currentState(OmniInferLocalRuntime.getPort())
        }
    }

    fun setPort(context: Context, port: Int, targetPort: Int): OmniInferLanProxyState {
        if (port <= 0) {
            return currentState(targetPort)
        }
        mmkv.encode(KEY_PORT, port)
        return if (running) {
            start(context, targetPort, port)
        } else {
            currentState(targetPort)
        }
    }

    fun refreshToken(context: Context): OmniInferLanProxyState {
        mmkv.encode(KEY_TOKEN, generateToken())
        val target = activeTargetPort.takeIf { it > 0 } ?: OmniInferLocalRuntime.getPort()
        return if (running) {
            start(context, target, activePort.takeIf { it > 0 })
        } else {
            currentState(target)
        }
    }

    fun currentState(targetPort: Int = OmniInferLocalRuntime.getPort()): OmniInferLanProxyState {
        val port = activePort.takeIf { running && it > 0 }
            ?: mmkv.decodeInt(KEY_PORT, 0).takeIf { it > 0 }
            ?: defaultProxyPort(targetPort)
        val host = McpNetworkUtils.currentLanIp().orEmpty()
        val baseUrl = if (host.isNotBlank()) "http://$host:$port" else ""
        return OmniInferLanProxyState(
            running = running,
            host = host,
            port = port,
            baseUrl = baseUrl,
            token = ensureToken(),
            targetBaseUrl = "http://127.0.0.1:$targetPort",
            error = mmkv.decodeString(KEY_LAST_ERROR, "").orEmpty(),
        )
    }

    private fun buildServer(
        context: Context,
        proxyPort: Int,
        targetPort: Int,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        if (!McpNetworkUtils.isLanConnected(context)) {
            throw IllegalStateException("No available LAN IPv4 address")
        }
        return embeddedServer(CIO, host = "0.0.0.0", port = proxyPort) {
            routing {
                options("/{...}") { respondOptions(call) }
                get("/health") { forward(call, targetPort, "/health") }
                get("/v1/models") { forward(call, targetPort, "/v1/models") }
                post("/v1/chat/completions") { forward(call, targetPort, "/v1/chat/completions") }
                post("/v1/cancel") { forward(call, targetPort, "/v1/cancel") }
            }
        }
    }

    private suspend fun forward(call: ApplicationCall, targetPort: Int, path: String) {
        addCorsHeaders(call)
        if (!isAuthorized(call)) {
            call.respondText(
                "{\"error\":\"Authentication required\"}",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return
        }

        val bodyText = if (call.request.httpMethod.value.equals("GET", ignoreCase = true)) {
            null
        } else {
            call.receiveText()
        }
        val requestBuilder = Request.Builder()
            .url("http://127.0.0.1:$targetPort$path")
            .method(
                call.request.httpMethod.value,
                bodyText?.toRequestBody(
                    call.request.headers[HttpHeaders.ContentType]?.toMediaTypeOrNull()
                )
            )

        call.request.headers[HttpHeaders.Accept]?.let { requestBuilder.header(HttpHeaders.Accept, it) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        response.use { upstream ->
            val status = HttpStatusCode.fromValue(upstream.code)
            upstream.header(HttpHeaders.ContentType)?.let { raw ->
                val contentType = runCatching { ContentType.parse(raw) }
                    .getOrDefault(ContentType.Application.OctetStream)
                call.respondOutputStream(contentType = contentType, status = status) {
                    upstream.body?.byteStream()?.use { input -> input.copyTo(this) }
                }
            } ?: call.respondOutputStream(status = status) {
                upstream.body?.byteStream()?.use { input -> input.copyTo(this) }
            }
        }
    }

    private suspend fun respondOptions(call: ApplicationCall) {
        addCorsHeaders(call)
        call.respond(HttpStatusCode.NoContent)
    }

    private fun addCorsHeaders(call: ApplicationCall) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type, Accept")
        call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }

    private fun isAuthorized(call: ApplicationCall): Boolean {
        val raw = call.request.headers[HttpHeaders.Authorization]?.trim().orEmpty()
        val token = raw.removePrefix("Bearer").trim()
        return token.isNotEmpty() && timingSafeEquals(token, ensureToken())
    }

    private fun stopLocked() {
        runCatching { server?.stop(500, 1_500) }
            .onFailure { OmniLog.e(TAG, "stop failed: ${it.message}") }
        server = null
        running = false
        activePort = 0
        activeTargetPort = 0
    }

    private fun defaultProxyPort(targetPort: Int): Int {
        return if (targetPort > 0 && targetPort != DEFAULT_PROXY_PORT) {
            targetPort + 1
        } else {
            DEFAULT_PROXY_PORT
        }
    }

    private fun ensureToken(): String {
        val stored = mmkv.decodeString(KEY_TOKEN, "").orEmpty()
        if (stored.isNotBlank()) return stored
        val token = generateToken()
        mmkv.encode(KEY_TOKEN, token)
        return token
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun clearError() {
        mmkv.encode(KEY_LAST_ERROR, "")
    }

    private fun timingSafeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
            return false
        }
        return MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
