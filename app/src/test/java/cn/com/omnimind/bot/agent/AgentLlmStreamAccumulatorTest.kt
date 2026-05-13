package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
