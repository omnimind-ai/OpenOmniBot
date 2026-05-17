package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniflowStepExecutorTest {
    @Test
    fun `detects explicit omniflow steps`() {
        val step = mapOf(
            "executor" to "omniflow",
            "omniflow_action" to "click",
            "args" to mapOf("x" to 10, "y" to 20),
        )

        assertTrue(OmniflowStepExecutor.isOmniflowStep(step))
        assertEquals("click", OmniflowStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `detects model free local actions without explicit executor`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "press_back",
            "args" to emptyMap<String, Any?>(),
        )

        assertTrue(OmniflowStepExecutor.isOmniflowStep(step))
        assertEquals("press_back", OmniflowStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `does not classify unknown model free tool as omniflow`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "browser_use",
            "args" to emptyMap<String, Any?>(),
        )

        assertFalse(OmniflowStepExecutor.isOmniflowStep(step))
    }

    @Test
    fun `normalizes omniflow canonical aliases`() {
        assertEquals(
            "click",
            OmniflowStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "tap")
            )
        )
        assertEquals(
            "input_text",
            OmniflowStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "type_text")
            )
        )
        assertEquals(
            "finished",
            OmniflowStepExecutor.actionNameForStep(
                mapOf("model_free" to true, "tool" to "done")
            )
        )
    }

    @Test
    fun `detects omniflow canonical action names`() {
        val step = mapOf(
            "model_free" to true,
            "tool" to "input_text",
            "args" to mapOf("text" to "hello"),
        )

        assertTrue(OmniflowStepExecutor.isOmniflowStep(step))
        assertEquals("input_text", OmniflowStepExecutor.actionNameForStep(step))
    }

    @Test
    fun `remap keeps non coordinate action args unchanged`() {
        val args = mapOf("content" to "hello")
        val result = OmniflowStepExecutor.remapStepArgs(
            mapOf(
                "executor" to "omniflow",
                "omniflow_action" to "type",
                "args" to args,
            )
        )

        assertEquals(args, result.args)
        assertTrue(result.meta.isEmpty())
    }
}
