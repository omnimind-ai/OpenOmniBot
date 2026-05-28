package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.vlmserver.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerStructuredResponseTest {
    @Test
    fun `structured parser reads parsed json when content is null`() {
        val response = parseStructured(
            """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "parsed": {
                          "name": "Parsed enhanced command",
                          "steps": [{"index": 0, "title": "Open app"}]
                        }
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(response.message, true, response.success)
        assertTrue(response.content.contains("\"name\":\"Parsed enhanced command\""))
        assertTrue(response.content.contains("\"steps\""))
    }

    @Test
    fun `structured parser falls back to output text sibling fields`() {
        val response = parseStructured(
            """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "output_text": "{\"name\":\"Output text command\",\"steps\":[{\"index\":0,\"title\":\"Tap\"}]}"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(response.message, true, response.success)
        assertTrue(response.content.contains("\"name\":\"Output text command\""))
    }

    @Test
    fun `structured parser falls back from null message content to choice text`() {
        val response = parseStructured(
            """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null
                      },
                      "text": "{\"name\":\"Choice text command\",\"steps\":[{\"index\":0,\"title\":\"Tap\"}]}",
                      "finish_reason": "stop"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(response.message, true, response.success)
        assertTrue(response.content.contains("\"name\":\"Choice text command\""))
    }

    private fun parseStructured(body: String): SceneChatCompletionResponse {
        val method = HttpController::class.java.getDeclaredMethod(
            "parseStructuredSceneResponse",
            String::class.java,
            ModelSceneRegistry.ResponseParser::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(
            HttpController,
            body,
            ModelSceneRegistry.ResponseParser.JSON_CONTENT,
            "test"
        ) as SceneChatCompletionResponse
    }
}
