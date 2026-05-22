package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VLMTokenUsageMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `maps provider usage details into vlm token usage`() {
        val turn = SceneChatCompletionTurn(
            parser = ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS,
            route = "scene.vlm.operation.primary",
            resolvedModel = "vlm-test-model",
            turn = ChatCompletionTurn(
                message = ChatCompletionMessage(role = "assistant"),
                usage = ChatCompletionUsage(
                    promptTokens = 120,
                    completionTokens = 35,
                    totalTokens = 155,
                    promptTokensDetails = json.parseToJsonElement("""{"cached_tokens":80}"""),
                    completionTokensDetails = json.parseToJsonElement(
                        """{"reasoning_tokens":12,"text_tokens":23}"""
                    )
                )
            )
        )

        val usage = VLMTokenUsageMapper.fromTurn(
            turn = turn,
            attemptIndex = 2,
            stabilityAttempt = 1,
            toolRetryIndex = 0
        )

        assertNotNull(usage)
        assertEquals(120, usage?.promptTokens)
        assertEquals(35, usage?.completionTokens)
        assertEquals(155, usage?.totalTokens)
        assertEquals(12, usage?.reasoningTokens)
        assertEquals(23, usage?.textTokens)
        assertEquals(80, usage?.cachedTokens)
        assertEquals(2, usage?.attemptIndex)
    }

    @Test
    fun `aggregates attempts for one vlm step`() {
        val aggregate = VLMTokenUsageMapper.aggregate(
            listOf(
                VLMTokenUsage(promptTokens = 10, completionTokens = 3, totalTokens = 13, attemptCount = 1),
                VLMTokenUsage(promptTokens = 20, completionTokens = 7, totalTokens = 27, attemptCount = 1)
            )
        )

        assertNotNull(aggregate)
        assertEquals(30, aggregate?.promptTokens)
        assertEquals(10, aggregate?.completionTokens)
        assertEquals(40, aggregate?.totalTokens)
        assertEquals(2, aggregate?.attemptCount)
    }
}
