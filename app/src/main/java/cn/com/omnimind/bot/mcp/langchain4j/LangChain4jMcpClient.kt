package cn.com.omnimind.bot.mcp.langchain4j

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.RemoteMcpCallResult
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.McpClient
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Production MCP client backed by LangChain4j's `DefaultMcpClient`.
 *
 * Transport selection:
 * - URLs ending with `/sse` use LangChain4j's built-in
 *   [HttpMcpTransport] (OkHttp + the legacy SSE handshake — `endpoint`
 *   event then POST-to-relative-URL flow).
 * - All other URLs use [StreamableHttpMcpOkHttpTransport], our OkHttp-based
 *   implementation of the modern *Streamable HTTP* MCP transport — equivalent
 *   to LangChain4j's `StreamableHttpMcpTransport` but without the Android-API-34
 *   `java.net.http.HttpClient` dependency.
 *
 * Mirrors the surface of the legacy `cn.com.omnimind.bot.mcp.RemoteMcpClient`
 * so callers in `McpToolHandler` and `RemoteMcpDiscoveryRegistry` need only
 * replace the type. Per-server `DefaultMcpClient` instances are cached so the
 * MCP session header survives across calls.
 */
object LangChain4jMcpClient {
    private const val TAG = "LangChain4jMcpClient"
    private val mapper = ObjectMapper()
    private val sessions = ConcurrentHashMap<String, DefaultMcpClient>()
    private val initLock = Mutex()

    suspend fun initialize(config: RemoteMcpServerConfig): Map<String, Any?> = withContext(Dispatchers.IO) {
        val client = obtainClient(config)
        // DefaultMcpClient performs the initialize handshake lazily on the
        // first method call; touch listTools to force-trigger it and surface
        // any handshake failures eagerly.
        runCatching { client.listTools() }
        emptyMap<String, Any?>()
    }

    suspend fun listTools(config: RemoteMcpServerConfig): List<RemoteMcpToolDescriptor> = withContext(Dispatchers.IO) {
        val client = obtainClient(config)
        val specs = client.listTools()
        specs.map { spec ->
            val params = spec.parameters()
            val schemaMap: Map<String, Any?> = if (params != null) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    mapper.convertValue(params, Map::class.java) as Map<String, Any?>
                }.getOrElse { emptyMap() }
            } else {
                emptyMap()
            }
            RemoteMcpToolDescriptor(
                serverId = config.id,
                serverName = config.name,
                toolName = spec.name(),
                description = spec.description().orEmpty(),
                inputSchema = schemaMap
            )
        }
    }

    suspend fun callTool(
        config: RemoteMcpServerConfig,
        toolName: String,
        arguments: Map<String, Any?>
    ): RemoteMcpCallResult = withContext(Dispatchers.IO) {
        val client = obtainClient(config)
        val request = ToolExecutionRequest.builder()
            .name(toolName)
            .arguments(mapper.writeValueAsString(arguments))
            .build()
        val result = runCatching {
            client.executeTool(request)
        }.getOrElse { error ->
            OmniLog.w(TAG, "MCP callTool failed: ${error.message}")
            return@withContext RemoteMcpCallResult(
                summaryText = error.message.orEmpty(),
                previewJson = "{}",
                rawResultJson = "{}",
                success = false
            )
        }
        // ToolExecutionResult exposes `resultText()` and (in newer versions)
        // `isError()`/`structuredContent()`. Be defensive about field
        // availability across MCP SDK versions.
        val text = runCatching { result.resultText().orEmpty() }
            .getOrElse { runCatching { result.toString() }.getOrDefault("") }
        val rawJson = runCatching { mapper.writeValueAsString(result) }
            .getOrDefault("\"$text\"")
        val isError = runCatching {
            val m = result.javaClass.getMethod("isError")
            (m.invoke(result) as? Boolean) == true
        }.getOrDefault(false)
        RemoteMcpCallResult(
            summaryText = text,
            previewJson = buildPreviewJson(text, rawJson),
            rawResultJson = rawJson,
            success = !isError
        )
    }

    fun invalidateSession(serverId: String? = null) {
        if (serverId == null) {
            val snapshot = sessions.toMap()
            sessions.clear()
            snapshot.values.forEach { runCatching { it.close() } }
            return
        }
        val client = sessions.remove(serverId)
        client?.let { runCatching { it.close() } }
    }

    private suspend fun obtainClient(config: RemoteMcpServerConfig): DefaultMcpClient {
        sessions[config.id]?.let { return it }
        return initLock.withLock {
            sessions[config.id]?.let { return@withLock it }
            val transport = buildTransport(config)
            val client = DefaultMcpClient.builder()
                .transport(transport)
                .clientName("omnibot-android")
                .clientVersion("1.0")
                .initializationTimeout(Duration.ofSeconds(30))
                .toolExecutionTimeout(Duration.ofSeconds(60))
                .build()
            sessions[config.id] = client
            client
        }
    }

    private fun buildTransport(config: RemoteMcpServerConfig): dev.langchain4j.mcp.client.transport.McpTransport {
        return if (looksLikeSseEndpoint(config.endpointUrl)) {
            val headers = if (config.bearerToken.isNotBlank()) {
                mapOf("Authorization" to "Bearer ${config.bearerToken}")
            } else {
                emptyMap()
            }
            HttpMcpTransport.Builder()
                .sseUrl(config.endpointUrl)
                .customHeaders(headers)
                .timeout(Duration.ofSeconds(60))
                .build()
        } else {
            StreamableHttpMcpOkHttpTransport(
                endpointUrl = config.endpointUrl,
                bearerToken = config.bearerToken.takeIf { it.isNotBlank() }
            )
        }
    }

    private fun looksLikeSseEndpoint(url: String): Boolean {
        val normalized = url.trim().trimEnd('/').lowercase()
        return normalized.endsWith("/sse")
    }

    private fun buildPreviewJson(summaryText: String, rawJson: String): String {
        if (summaryText.length <= 200) return rawJson
        return "{\"preview\":\"" +
            summaryText.take(200).replace("\\", "\\\\").replace("\"", "\\\"") +
            "...\"}"
    }
}
