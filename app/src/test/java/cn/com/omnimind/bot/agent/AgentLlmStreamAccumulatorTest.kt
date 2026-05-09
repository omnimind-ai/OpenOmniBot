package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        accumulator.consume("""{"choices":[{"delta":{"content":"先思考第一步"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"再思考第二步</think>最后回答"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("先思考第一步再思考第二步", turn.reasoning)
        assertEquals("最后回答", turn.message.contentText())
    }

    @Test
    fun `flushes pending text as normal content when no think tag appears`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"普通回答"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("", turn.reasoning)
        assertEquals("普通回答", turn.message.contentText())
    }

    @Test
    fun `handles closing think tag split across chunks`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"先思考</th"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"ink>最终回答"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("先思考", turn.reasoning)
        assertEquals("最终回答", turn.message.contentText())
    }

    @Test
    fun `handles opening think tag split across chunks`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = true
        )

        accumulator.consume("""{"choices":[{"delta":{"content":"先展示前言<th"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"ink>深度思考</think>最后回答"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("深度思考", turn.reasoning)
        assertEquals("先展示前言最后回答", turn.message.contentText())
    }

    @Test
    fun `reads tokens per second from usage performance payload`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"已完成。"}}]}""")
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
            """{"choices":[{"delta":{"reasoning_content":"需要查工具","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("需要查工具", turn.reasoning)
        assertEquals("需要查工具", turn.message.reasoningContent)
    }

    @Test
    fun `preserves surrogate pair split across chunks`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"前缀\uD83D"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"\uDE00后缀"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("前缀😀后缀", turn.message.contentText())
    }

    @Test
    fun `drops dangling surrogate from final content`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"前缀\uD83D后缀"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("前缀后缀", turn.message.contentText())
    }
}
