package cn.com.omnimind.bot.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        assertTrue(guidance.contains("OmniFlow UDEG recall context"))
        assertTrue(guidance.contains("path=page_match -> UDEG node -> node skill-like decision context"))
        assertTrue(guidance.contains("function_id=open_network_settings"))
        assertTrue(guidance.contains("step: 1. open_app"))
        assertFalse(guidance.contains("任务已完成"))
        assertFalse(guidance.contains("current task is complete"))
    }

    @Test
    fun `render guidance exposes node skill even without function candidates`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "node_candidates" to listOf(
                    mapOf(
                        "node_id" to "udeg_node_settings",
                        "page_similarity" to 0.91,
                        "skill" to mapOf(
                            "decision_guidance" to "Use Settings node context before choosing actions."
                        ),
                    )
                ),
                "candidates" to emptyList<Map<String, Any?>>(),
            )
        )

        assertTrue(guidance.contains("node_id=udeg_node_settings"))
        assertTrue(guidance.contains("node_skill_decision_context: Use Settings node context"))
    }

    @Test
    fun `direct hit function id is exposed only for recall hit`() {
        assertEquals(
            "open_settings",
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf("function_id" to "open_settings"),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "recall",
                    "candidates" to listOf(mapOf("function_id" to "open_settings")),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to false,
                    "decision" to "hit",
                    "hit" to mapOf("function_id" to "open_settings"),
                )
            )
        )
    }
}
