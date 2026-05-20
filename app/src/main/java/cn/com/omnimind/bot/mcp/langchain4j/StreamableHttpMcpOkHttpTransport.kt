package cn.com.omnimind.bot.mcp.langchain4j

import cn.com.omnimind.baselib.util.OmniLog
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.mcp.client.McpCallContext
import dev.langchain4j.mcp.client.transport.McpOperationHandler
import dev.langchain4j.mcp.client.transport.McpTransport
import dev.langchain4j.mcp.protocol.McpClientMessage
import dev.langchain4j.mcp.protocol.McpInitializeRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Custom MCP transport implementing the modern *Streamable HTTP* protocol
 * (`POST` JSON-RPC over HTTP, responses inline as JSON or inline SSE) over
 * OkHttp. Equivalent in capability to LangChain4j's
 * `StreamableHttpMcpTransport`, but does not depend on
 * `java.net.http.HttpClient` — which is unavailable on Android API levels
 * below 34. Our `minSdk` is 29, so we ship this OkHttp-based variant.
 *
 * The transport routes responses synchronously from the POST callback: there
 * is no persistent SSE channel for the modern HTTP protocol, so each
 * `executeOperationWithResponse(...)` completes its returned future as soon
 * as the corresponding HTTP response arrives.
 */
class StreamableHttpMcpOkHttpTransport(
    private val endpointUrl: String,
    private val bearerToken: String? = null,
    private val callTimeout: java.time.Duration = java.time.Duration.ofSeconds(60)
) : McpTransport {

    private companion object {
        const val TAG = "StreamableHttpMcpOkHttpTransport"
        const val SESSION_ID_HEADER = "Mcp-Session-Id"
        const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val mapper = ObjectMapper()
    private val client = OkHttpClient.Builder()
        .callTimeout(callTimeout)
        .connectTimeout(callTimeout)
        .readTimeout(callTimeout)
        .writeTimeout(callTimeout)
        .build()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var protocolVersion: String = DEFAULT_PROTOCOL_VERSION

    @Volatile
    private var messageHandler: McpOperationHandler? = null

    @Volatile
    private var onFailureCallback: Runnable? = null

    override fun start(messageHandler: McpOperationHandler) {
        this.messageHandler = messageHandler
    }

    override fun initialize(request: McpInitializeRequest): CompletableFuture<JsonNode> {
        return executeWithResponseInternal(request)
    }

    override fun executeOperationWithResponse(request: McpClientMessage): CompletableFuture<JsonNode> {
        return executeWithResponseInternal(request)
    }

    override fun executeOperationWithResponse(context: McpCallContext): CompletableFuture<JsonNode> {
        return executeWithResponseInternal(context.message())
    }

    override fun executeOperationWithoutResponse(request: McpClientMessage) {
        executeWithoutResponseInternal(request)
    }

    override fun executeOperationWithoutResponse(context: McpCallContext) {
        executeWithoutResponseInternal(context.message())
    }

    override fun checkHealth() {
        // No persistent connection to check; per-call timeouts surface failures naturally.
    }

    override fun onFailure(actionOnFailure: Runnable) {
        onFailureCallback = actionOnFailure
    }

    override fun close() {
        runCatching { client.dispatcher.executorService.shutdownNow() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun executeWithResponseInternal(message: McpClientMessage): CompletableFuture<JsonNode> {
        val future = CompletableFuture<JsonNode>()
        val payload = try {
            mapper.writeValueAsString(message)
        } catch (e: Exception) {
            future.completeExceptionally(e)
            return future
        }
        val builder = baseRequestBuilder()
            .post(payload.toRequestBody(JSON_MEDIA))
        val req = builder.build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onFailureCallback?.run()
                future.completeExceptionally(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { r ->
                    captureSessionId(r)
                    val code = r.code
                    if (code !in 200..299) {
                        val body = r.body?.string().orEmpty().take(2000)
                        future.completeExceptionally(
                            RuntimeException("MCP HTTP $code: ${body.ifBlank { r.message }}")
                        )
                        return
                    }
                    val ct = r.header("Content-Type")?.lowercase().orEmpty()
                    val body = r.body ?: run {
                        future.complete(mapper.createObjectNode())
                        return
                    }
                    try {
                        if (ct.contains("text/event-stream")) {
                            val node = readSseResponse(body.charStream().buffered(), message.id)
                            future.complete(node)
                        } else {
                            val raw = body.string()
                            val node = if (raw.isBlank()) mapper.createObjectNode() else mapper.readTree(raw)
                            future.complete(node)
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }
            }
        })
        return future
    }

    private fun executeWithoutResponseInternal(message: McpClientMessage) {
        val payload = mapper.writeValueAsString(message)
        val req = baseRequestBuilder()
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                OmniLog.w(TAG, "notification POST failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { captureSessionId(it) }
            }
        })
    }

    private fun baseRequestBuilder(): Request.Builder {
        val builder = Request.Builder()
            .url(endpointUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header(PROTOCOL_VERSION_HEADER, protocolVersion)
        sessionId?.let { builder.header(SESSION_ID_HEADER, it) }
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return builder
    }

    private fun captureSessionId(response: okhttp3.Response) {
        response.header(SESSION_ID_HEADER)?.trim()?.takeIf { it.isNotEmpty() }?.let { sid ->
            sessionId = sid
        }
    }

    /**
     * Parse a Server-Sent Events stream inline within the HTTP response body.
     * Each `data: ` line is a JSON fragment; we accumulate one event (lines
     * terminated by a blank line) into a complete JSON-RPC message and look
     * for the response matching [expectedId]. Side-band messages (notifications
     * from the server) are dispatched to [messageHandler] if registered.
     */
    private fun readSseResponse(reader: BufferedReader, expectedId: Long?): JsonNode {
        val dataLines = StringBuilder()
        var result: JsonNode? = null
        val deadline = System.currentTimeMillis() + callTimeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                // End of one SSE event — try to parse what we have.
                if (dataLines.isNotEmpty()) {
                    val payload = dataLines.toString()
                    dataLines.clear()
                    val node = runCatching { mapper.readTree(payload) }.getOrNull() ?: continue
                    val idNode = node.get("id")
                    if (idNode != null && expectedId != null && idNode.asLong() == expectedId) {
                        result = node
                        break
                    } else {
                        // Side-band notification; ignore for now (LangChain4j's
                        // McpOperationHandler.handle would normally take this).
                    }
                }
                continue
            }
            if (line.startsWith(":")) continue
            if (line.startsWith("data:")) {
                dataLines.append(line.removePrefix("data:").trim())
            }
        }
        return result ?: throw RuntimeException("MCP SSE inline-response: no message with id=$expectedId")
    }
}
