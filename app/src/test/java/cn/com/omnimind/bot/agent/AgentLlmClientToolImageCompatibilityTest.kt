package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLlmClientToolImageCompatibilityTest {
    @Test
    fun `rewriteToolImageMessagesForUserInput moves tool images after contiguous tool results`() {
        val request = toolImageRequest()

        val rewritten = rewriteToolImageMessagesForUserInput(request)

        assertNotNull(rewritten)
        assertEquals(
            listOf("user", "assistant", "tool", "tool", "user"),
            rewritten!!.messages.map { it.role }
        )
        assertEquals("tool json", rewritten.messages[2].content!!.jsonPrimitive.content)
        assertEquals("plain tool result", rewritten.messages[3].content!!.jsonPrimitive.content)

        val userImageContent = rewritten.messages[4].content as JsonArray
        assertEquals("text", userImageContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(
            userImageContent[0].jsonObject["text"]
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
                .contains("tool_call_id=call-image")
        )
        assertEquals("image_url", userImageContent[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/jpeg;base64,abc",
            userImageContent[1]
                .jsonObject["image_url"]
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `stripToolImageMessages keeps text tool results without adding image messages`() {
        val request = toolImageRequest()

        val stripped = stripToolImageMessages(request)

        assertNotNull(stripped)
        assertEquals(
            listOf("user", "assistant", "tool", "tool"),
            stripped!!.messages.map { it.role }
        )
        assertEquals("tool json", stripped.messages[2].content!!.jsonPrimitive.content)
        assertEquals("plain tool result", stripped.messages[3].content!!.jsonPrimitive.content)
        assertNull((stripped.messages[2].content as? JsonArray))
    }

    @Test
    fun `tool image compatibility rewrite is skipped when no tool image blocks exist`() {
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive("hello")),
                ChatCompletionMessage(
                    role = "tool",
                    toolCallId = "call-text",
                    content = JsonPrimitive("plain tool result")
                )
            )
        )

        assertNull(rewriteToolImageMessagesForUserInput(request))
        assertNull(stripToolImageMessages(request))
    }

    private fun toolImageRequest(): ChatCompletionRequest {
        return ChatCompletionRequest(
            model = "mimo-v2.5",
            stream = true,
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive("inspect page")),
                ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(""),
                    toolCalls = listOf(
                        AssistantToolCall(
                            id = "call-image",
                            function = AssistantToolCallFunction(
                                name = "browser_use",
                                arguments = "{}"
                            )
                        ),
                        AssistantToolCall(
                            id = "call-text",
                            function = AssistantToolCallFunction(
                                name = "file_read",
                                arguments = "{}"
                            )
                        )
                    )
                ),
                ChatCompletionMessage(
                    role = "tool",
                    toolCallId = "call-image",
                    content = JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("text"),
                                    "text" to JsonPrimitive("tool json")
                                )
                            ),
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("image_url"),
                                    "image_url" to JsonObject(
                                        mapOf(
                                            "url" to JsonPrimitive("data:image/jpeg;base64,abc")
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                ChatCompletionMessage(
                    role = "tool",
                    toolCallId = "call-text",
                    content = JsonPrimitive("plain tool result")
                )
            )
        )
    }
}
