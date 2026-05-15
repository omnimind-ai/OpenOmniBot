package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun qwenInlineFunctionMarkupBuildsToolCallsAndSuppressesAssistantText() {
        val accumulator = AgentLlmStreamAccumulator(json)

        val done = accumulator.consume(
            """
            <tool_call>
            <function=workbench_project_delete>
            <parameter=tool_title> 删除项目 expense-tracker </parameter>
            <parameter=projectId> expense-tracker </parameter>
            </function>
            </tool_call>
            <tool_call>
            <function=workbench_project_delete>
            <parameter=tool_title> 删除项目 fitness-planner </parameter>
            <parameter=projectId> fitness-planner </parameter>
            </function>
            </tool_call>
            """.trimIndent()
        )

        assertFalse(done)
        val turn = accumulator.buildTurn()
        assertEquals("", turn.message.contentText())
        val toolCalls = turn.message.toolCalls.orEmpty()
        assertEquals(2, toolCalls.size)
        assertEquals("workbench_project_delete", toolCalls[0].function.name)
        assertTrue(toolCalls[0].function.arguments.contains("expense-tracker"))
        assertEquals("workbench_project_delete", toolCalls[1].function.name)
        assertTrue(toolCalls[1].function.arguments.contains("fitness-planner"))
    }

    @Test
    fun pseudoToolMarkupWithoutParametersRemainsAssistantText() {
        val accumulator = AgentLlmStreamAccumulator(json)
        val raw = "<tool_call><function=name>terminal_execute</function></tool_call>"

        accumulator.consume(raw)

        val turn = accumulator.buildTurn()
        assertEquals(raw, turn.message.contentText())
        assertTrue(turn.message.toolCalls.orEmpty().isEmpty())
    }

    @Test
    fun `tool rounds retain reasoning content even without full deepseek adapter mode`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"继续调用工具前要回传思考","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("继续调用工具前要回传思考", turn.reasoning)
        assertEquals("继续调用工具前要回传思考", turn.message.reasoningContent)
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
