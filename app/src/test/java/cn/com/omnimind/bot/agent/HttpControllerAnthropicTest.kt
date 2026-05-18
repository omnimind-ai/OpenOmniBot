package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class HttpControllerAnthropicTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `automatic anthropic cache control is added for regular payloads`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "messages": [
                    {
                      "role": "user",
                      "content": [{"type": "text", "text": "hello"}]
                    }
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals(
            "ephemeral",
            root["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `automatic anthropic cache control is skipped when breakpoint slots are full`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "system": [
                    {"type": "text", "text": "system-a", "cache_control": {"type": "ephemeral"}},
                    {"type": "text", "text": "system-b", "cache_control": {"type": "ephemeral"}}
                  ],
                  "messages": [
                    {
                      "role": "user",
                      "content": [{"type": "text", "text": "user-a", "cache_control": {"type": "ephemeral"}}]
                    },
                    {
                      "role": "assistant",
                      "content": [{"type": "text", "text": "assistant-a", "cache_control": {"type": "ephemeral"}}]
                    }
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertFalse(root.containsKey("cache_control"))
    }

    @Test
    fun `anthropic tool history includes assistant thinking block`() {
        val payload = HttpController.convertToAnthropicRequestJson(
            ChatCompletionRequest(
                model = "claude-sonnet",
                messages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = JsonPrimitive("Check the weather")
                    ),
                    ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("I will check."),
                        reasoningContent = "Need to call the weather tool",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call_weather",
                                function = AssistantToolCallFunction(
                                    name = "get_weather",
                                    arguments = """{"city":"Beijing"}"""
                                )
                            )
                        )
                    ),
                    ChatCompletionMessage(
                        role = "tool",
                        toolCallId = "call_weather",
                        content = JsonPrimitive("""{"temperature":"21C"}""")
                    )
                )
            )
        )

        val root = json.parseToJsonElement(payload).jsonObject
        val assistantContent = root["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray

        assertEquals("thinking", assistantContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Need to call the weather tool",
            assistantContent[0].jsonObject["thinking"]!!.jsonPrimitive.content
        )
        assertEquals("text", assistantContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", assistantContent[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_weather", assistantContent[2].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fetchProviderModels supports anthropic models endpoint`() = runBlocking {
        val requestLines = mutableListOf<String>()
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            serverSocket.use { socketServer ->
                val socket = socketServer.accept()
                socket.use { client ->
                    val reader = BufferedReader(
                        InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
                    )
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) {
                            break
                        }
                        requestLines += line
                    }

                    val body = """
                        {
                          "data": [
                            {"id": "claude-sonnet-4-5", "display_name": "Claude Sonnet 4.5", "type": "model"},
                            {"id": "claude-haiku-4-5", "display_name": "Claude Haiku 4.5", "type": "model"}
                          ]
                        }
                    """.trimIndent()
                    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                    val writer = BufferedWriter(
                        OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)
                    )
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: application/json\r\n")
                    writer.write("Content-Length: ${bodyBytes.size}\r\n")
                    writer.write("Connection: close\r\n")
                    writer.write("\r\n")
                    writer.write(body)
                    writer.flush()
                }
            }
        }

        try {
            val models = HttpController.fetchProviderModels(
                apiBase = "http://127.0.0.1:${serverSocket.localPort}",
                apiKey = "sk-ant-test",
                protocolType = "anthropic"
            )
            serverThread.join()

            assertEquals(listOf("claude-haiku-4-5", "claude-sonnet-4-5"), models.map { it.id })
            assertEquals(
                listOf("Claude Haiku 4.5", "Claude Sonnet 4.5"),
                models.map { it.displayName }
            )
            assertEquals("GET /v1/models HTTP/1.1", requestLines.first())
            assertEquals(
                "x-api-key: sk-ant-test",
                requestLines.firstOrNull { it.startsWith("x-api-key:", ignoreCase = true) }
            )
            assertEquals(
                "anthropic-version: 2023-06-01",
                requestLines.firstOrNull { it.startsWith("anthropic-version:", ignoreCase = true) }
            )
            assertNotNull(models.first().ownedBy)
        } finally {
            serverSocket.close()
        }
    }
}
