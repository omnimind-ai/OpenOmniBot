package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionStackTest {
    @Test
    fun `key action windows are driven by canonical actions not semantic roles`() {
        val stack = PendingActionStack.fromSteps(
            steps = listOf(
                mapOf(
                    "action" to "open_app",
                    "args" to mapOf("packageName" to "com.example"),
                ),
                mapOf(
                    "action" to "click",
                    "args" to mapOf("target_description" to "外卖"),
                ),
                mapOf(
                    "action" to "input_text",
                    "args" to mapOf("target_description" to "搜索框"),
                ),
            ),
            functionSpec = emptyMap(),
        )

        val window = stack.windowUntilNextKey()

        assertEquals(2, window.size)
        assertFalse(window[0].isKeyAction)
        assertEquals(OobStepRoleClassifier.ROLE_NAVIGATION, window[0].role)
        assertTrue(window[1].isKeyAction)
        assertEquals(OobStepRoleClassifier.ROLE_UNKNOWN, window[1].role)
    }
}
