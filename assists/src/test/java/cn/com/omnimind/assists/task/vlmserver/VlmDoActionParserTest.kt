package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmDoActionParserTest {

    @Test
    fun parsesDoAndFinish() {
        val tap = VlmDoActionParser.parse("""do(action="Tap", element=[500, 600])""")
        assertTrue(tap.success)
        assertTrue(tap.step?.action is ClickAction)

        val finish = VlmDoActionParser.parse("""finish(message="已完成")""")
        assertTrue(finish.success)
        assertTrue(finish.step?.action is FinishedAction)
        assertEquals("已完成", (finish.step?.action as FinishedAction).content)
    }

    @Test
    fun stripsThinkAnswerAndCodeFence() {
        val response = """
            <think>先观察一下</think>
            <answer>
            ```text
            do(action="Double Tap", element=[100, 200])
            ```
            </answer>
        """.trimIndent()
        val parsed = VlmDoActionParser.parse(response)
        assertTrue(parsed.success)
        assertTrue(parsed.step?.action is DoubleTapAction)
    }

    @Test
    fun parsesLaunchTypeSwipeAndTakeOver() {
        val launch = VlmDoActionParser.parse("""do(action="Launch", app="微信")""")
        assertTrue(launch.step?.action is OpenAppAction)
        assertEquals("微信", (launch.step?.action as OpenAppAction).packageName)

        val type = VlmDoActionParser.parse("""do(action="Type", text="你好，world")""")
        assertTrue(type.step?.action is TypeAction)

        val swipe = VlmDoActionParser.parse("""do(action="Swipe", start=[10,20], end=[30,40], duration="2 seconds")""")
        assertTrue(swipe.step?.action is ScrollAction)
        assertEquals(2.0f, (swipe.step?.action as ScrollAction).duration, 0.001f)

        val takeover = VlmDoActionParser.parse("""do(action="Take_over", message="需要用户确认")""")
        assertTrue(takeover.step?.action is InfoAction)
        assertEquals("需要用户确认", (takeover.step?.action as InfoAction).value)
    }

    @Test
    fun rejectsUnknownAction() {
        val parsed = VlmDoActionParser.parse("""do(action="Fly", element=[1,2])""")
        assertFalse(parsed.success)
        assertTrue(parsed.error.orEmpty().isNotBlank())
    }
}
