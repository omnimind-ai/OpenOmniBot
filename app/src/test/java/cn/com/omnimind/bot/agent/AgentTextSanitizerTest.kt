package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTextSanitizerTest {
    @Test
    fun `preserves complete surrogate pairs`() {
        assertEquals(
            "前缀😀后缀",
            AgentTextSanitizer.sanitizeUtf16("前缀\uD83D\uDE00后缀")
        )
    }

    @Test
    fun `drops dangling high surrogate`() {
        assertEquals(
            "前缀后缀",
            AgentTextSanitizer.sanitizeUtf16("前缀\uD83D后缀")
        )
    }

    @Test
    fun `drops dangling low surrogate`() {
        assertEquals(
            "前缀后缀",
            AgentTextSanitizer.sanitizeUtf16("前缀\uDE00后缀")
        )
    }

    @Test
    fun `strips complete text tool call blocks`() {
        assertEquals(
            "前缀  后缀",
            AgentTextSanitizer.stripTextFunctionCalls(
                """前缀 <tool_call>{"toolName":"web_search","summary":"done"}</tool_call> 后缀"""
            )
        )
    }

    @Test
    fun `trims incomplete text tool call blocks`() {
        assertEquals(
            "前缀",
            AgentTextSanitizer.stripTextFunctionCalls(
                """前缀 <tool_call>{"toolName":"web_search","previewJson":"""
            )
        )
    }
}
