package cn.com.omnimind.bot.vlm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmRecallGuidanceBuilderTest {
    @Test
    fun `render guidance exposes recall facts without declaring completion`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "hit",
                "hit" to mapOf(
                    "function_id" to "open_network_settings",
                    "score" to 0.99,
                    "description" to "open network settings",
                    "step_summaries" to listOf(
                        mapOf("tool" to "open_app", "title" to "open_app: com.android.settings"),
                        mapOf("tool" to "click", "title" to "click: Network"),
                    ),
                ),
            )
        )

        assertTrue(guidance.contains("OmniFlow recall context"))
        assertTrue(guidance.contains("function_id=open_network_settings"))
        assertTrue(guidance.contains("step: 1. open_app"))
        assertFalse(guidance.contains("任务已完成"))
        assertFalse(guidance.contains("current task is complete"))
    }
}
