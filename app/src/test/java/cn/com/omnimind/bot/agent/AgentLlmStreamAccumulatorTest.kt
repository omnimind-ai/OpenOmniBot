package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLlmStreamAccumulatorTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `treats leading text before closing think tag as reasoning for local models`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"first thought "}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"second thought</think>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("first thought second thought", turn.reasoning)
        assertEquals("final answer", turn.message.contentText())
    }

    @Test
    fun `flushes pending text as normal content when no think tag appears`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"normal answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("", turn.reasoning)
        assertEquals("normal answer", turn.message.contentText())
    }

    @Test
    fun `handles closing think tag split across chunks`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"first thought</th"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"ink>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("first thought", turn.reasoning)
        assertEquals("final answer", turn.message.contentText())
    }

    @Test
    fun `handles opening think tag split across chunks`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"visible prefix<th"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"ink>deep thought</think>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("deep thought", turn.reasoning)
        assertEquals("visible prefixfinal answer", turn.message.contentText())
    }

    @Test
    fun `reads tokens per second from usage performance payload`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"done"}}]}""")
        accumulator.consume(
            """
            {"id":"chatcmpl-test","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":15,"completion_tokens":100,"total_tokens":115,"performance":{"prefill_tokens_per_second":36.6,"decode_tokens_per_second":12.4}}}
            """.trimIndent()
        )

        val turn = accumulator.buildTurn()

        assertNotNull(turn.usage)
        assertEquals(36.6, turn.usage?.prefillTokensPerSecond ?: 0.0, 0.0)
        assertEquals(12.4, turn.usage?.decodeTokensPerSecond ?: 0.0, 0.0)
    }

    @Test
    fun `can retain reasoning content on assistant message for deepseek tool rounds`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            includeReasoningInAssistantMessage = true
        )

        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"need tool","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("need tool", turn.reasoning)
        assertEquals("need tool", turn.message.reasoningContent)
    }

    @Test
    fun `tool rounds retain reasoning content even without full deepseek adapter mode`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"need echo reasoning for tool","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("need echo reasoning for tool", turn.reasoning)
        assertEquals("need echo reasoning for tool", turn.message.reasoningContent)
    }

    @Test
    fun `surfaces top level provider error instead of empty assistant turn`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"error":{"code":"upstream_unavailable","message":"Upstream service is unavailable and returned no output.","param":null,"type":"service_unavailable_error"},"status_code":503}"""
        )
        accumulator.consume(
            """{"id":"","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":0,"total_tokens":10}}"""
        )
        accumulator.consume("[DONE]")

        val error = runCatching { accumulator.buildTurn() }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message.orEmpty().contains("provider stream returned error"))
        assertTrue(error.message.orEmpty().contains("status=503"))
        assertTrue(error.message.orEmpty().contains("upstream_unavailable"))
    }

    @Test
    fun `preserves surrogate pair split across chunks`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"prefix\uD83D"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"\uDE00suffix"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("prefix😀suffix", turn.message.contentText())
    }

    @Test
    fun `drops dangling surrogate from final content`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"prefix\uD83Dsuffix"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("prefixsuffix", turn.message.contentText())
    }

    @Test
    fun `auto strips inline think close tags for non local providers`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = false
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"inner reasoning</think>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("inner reasoning", turn.reasoning)
        assertEquals("final answer", turn.message.contentText())
    }

    @Test
    fun `auto strips inline think close tags split across chunks for non local providers`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = false
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"inner reasoning</th"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"ink>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("", turn.reasoning)
        assertEquals("inner reasoningfinal answer", turn.message.contentText())
    }

    @Test
    fun `route-gated leading buffer reclassifies text before close tag for non local providers`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = false,
            bufferLeadingTextUntilInlineThinkTag = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"inner reasoning</think>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("inner reasoning", turn.reasoning)
        assertEquals("final answer", turn.message.contentText())
    }

    @Test
    fun `route-gated leading buffer reclassifies split close tag for non local providers`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = false,
            bufferLeadingTextUntilInlineThinkTag = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"inner reasoning</th"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"ink>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("inner reasoning", turn.reasoning)
        assertEquals("final answer", turn.message.contentText())
    }

    @Test
    fun `route-gated leading buffer flushes normal content when no think tag appears`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = false,
            bufferLeadingTextUntilInlineThinkTag = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"normal answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("", turn.reasoning)
        assertEquals("normal answer", turn.message.contentText())
    }
}
