package cn.com.omnimind.bot.mcp.koog

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.RemoteMcpCallResult
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP client built on top of the official [io.modelcontextprotocol.kotlin.sdk] (the same SDK Koog
 * uses internally). Replaces the hand-rolled JSON-RPC / SSE protocol code in
 * `cn.com.omnimind.bot.mcp.RemoteMcpClient`, exposing the same surface so it can be swapped in
 * behind the [isEnabled] MMKV feature flag without touching callers.
 *
 * Transport selection mirrors the legacy logic:
 *  - URLs ending in `/sse` use [SseClientTransport] (legacy MCP SSE);
 *  - everything else uses [StreamableHttpClientTransport] (the modern MCP HTTP transport that
 *    handles both inline-JSON and inline-SSE responses).
 *
 * Connections are cached per `serverId` so back-to-back `listTools` / `callTool` reuse the same
 * MCP session, matching the session-header behavior of the legacy client.
 */
object KoogMcpClient {
    private const val TAG = "[KoogMcpClient]"

    private val clientImplementation = Implementation(
        name = "omnibot-android",
        version = "1.0",
        title = null,
        websiteUrl = null,
        icons = emptyList()
    )

    private data class ConnectedSession(
        val client: Client,
        val ktorClient: HttpClient
    )

    private val sessions = ConcurrentHashMap<String, ConnectedSession>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun listTools(config: RemoteMcpServerConfig): List<RemoteMcpToolDescriptor> {
        val client = ensureConnected(config)
        val result = client.listTools()
            ?: throw IllegalStateException("MCP listTools returned null for ${config.name}")
        return result.tools.map { tool ->
            RemoteMcpToolDescriptor(
                serverId = config.id,
                serverName = config.name,
                toolName = tool.name,
                description = tool.description.orEmpty(),
                inputSchema = toolSchemaToMap(tool.inputSchema)
            )
        }
    }

    suspend fun callTool(
        config: RemoteMcpServerConfig,
        toolName: String,
        arguments: Map<String, Any?>
    ): RemoteMcpCallResult {
        val client = ensureConnected(config)
        val result = client.callTool(
            name = toolName,
            arguments = arguments,
            meta = emptyMap(),
            options = null
        ) ?: throw IllegalStateException("MCP callTool returned null for $toolName")
        return buildCallResult(result)
    }

    fun invalidateSession(serverId: String? = null) {
        if (serverId == null) {
            sessions.values.toList().forEach { closeSession(it) }
            sessions.clear()
            return
        }
        sessions.remove(serverId)?.let(::closeSession)
    }

    private fun closeSession(session: ConnectedSession) {
        runCatching { runBlocking { session.client.close() } }
            .onFailure { OmniLog.w(TAG, "close MCP client failed: ${it.message}") }
        runCatching { session.ktorClient.close() }
            .onFailure { OmniLog.w(TAG, "close Ktor client failed: ${it.message}") }
    }

    private suspend fun ensureConnected(config: RemoteMcpServerConfig): Client {
        val mutex = locks.computeIfAbsent(config.id) { Mutex() }
        mutex.withLock {
            sessions[config.id]?.let { return it.client }
            val session = openSession(config)
            sessions[config.id] = session
            return session.client
        }
    }

    private suspend fun openSession(config: RemoteMcpServerConfig): ConnectedSession {
        val ktor = HttpClient {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000L
                connectTimeoutMillis = 20_000L
                socketTimeoutMillis = 60_000L
            }
        }
        val transport: Transport = if (looksLikeSseEndpoint(config.endpointUrl)) {
            SseClientTransport(ktor, urlString = config.endpointUrl) {
                if (config.bearerToken.isNotBlank()) {
                    header("Authorization", "Bearer ${config.bearerToken}")
                }
            }
        } else {
            StreamableHttpClientTransport(ktor, url = config.endpointUrl) {
                if (config.bearerToken.isNotBlank()) {
                    header("Authorization", "Bearer ${config.bearerToken}")
                }
            }
        }
        val client = Client(
            clientInfo = clientImplementation,
            options = ClientOptions(capabilities = ClientCapabilities())
        )
        try {
            client.connect(transport)
        } catch (t: Throwable) {
            runCatching { ktor.close() }
            throw t
        }
        return ConnectedSession(client, ktor)
    }

    private fun looksLikeSseEndpoint(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        return path.endsWith("/sse") || path.endsWith("/sse/")
    }

    private fun toolSchemaToMap(schema: io.modelcontextprotocol.kotlin.sdk.types.ToolSchema): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>("type" to schema.type)
        map["properties"] = jsonObjectToMap(schema.properties)
        schema.required?.takeIf { it.isNotEmpty() }?.let { map["required"] = it.toList() }
        schema.defs?.let { map["\$defs"] = jsonObjectToMap(it) }
        return map
    }

    private fun buildCallResult(result: CallToolResult): RemoteMcpCallResult {
        val textBlocks = result.content
            .filterIsInstance<TextContent>()
            .mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
        val rawJson = buildJsonObject {
            put("isError", JsonPrimitive(result.isError == true))
            put("content", encodeContentBlocks(result.content))
            result.structuredContent?.let { put("structuredContent", it) }
        }.toString()
        val summaryText = if (textBlocks.isNotEmpty()) {
            textBlocks.joinToString("\n").take(600)
        } else {
            rawJson.take(600)
        }
        val previewJson = if (rawJson.length <= 1200) rawJson else rawJson.take(1200) + "..."
        return RemoteMcpCallResult(
            summaryText = summaryText,
            previewJson = previewJson,
            rawResultJson = rawJson,
            success = result.isError != true
        )
    }

    private fun encodeContentBlocks(blocks: List<io.modelcontextprotocol.kotlin.sdk.types.ContentBlock>): JsonArray {
        return JsonArray(
            blocks.map { block ->
                when (block) {
                    is TextContent -> buildJsonObject {
                        put("type", "text")
                        block.text?.let { put("text", it) }
                    }
                    else -> buildJsonObject {
                        put("type", block.type.toString())
                    }
                }
            }
        )
    }

    private fun jsonObjectToMap(obj: JsonObject?): Map<String, Any?> {
        if (obj == null) return emptyMap()
        return obj.entries.associate { (key, value) -> key to jsonElementToAny(value) }
    }

    private fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.longOrNull != null -> element.long
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
            is JsonObject -> jsonObjectToMap(element)
            is JsonArray -> element.map { jsonElementToAny(it) }
        }
    }
}

private val JsonPrimitive.long: Long get() = content.toLong()
private val JsonPrimitive.double: Double get() = content.toDouble()
