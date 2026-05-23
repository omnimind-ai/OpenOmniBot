package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMPageContextRequest
import cn.com.omnimind.bot.runlog.OobOmniFlowLoopAcceptanceTest
import cn.com.omnimind.bot.runlog.OobUdegNodeStore
import kotlinx.coroutines.runBlocking
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

        assertTrue(guidance.contains("OmniFlow UDEG node skill-like decision context"))
        assertTrue(guidance.contains("path=page match -> UDEG node -> node skill-like decision context -> VLM/tool decision"))
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
                        "decision_context" to mapOf(
                            "role" to "decision",
                            "entry_policy" to "page_match_to_udeg_node",
                            "skill_id" to "udeg_node_skill_udeg_node_settings",
                            "decision_path" to "page match -> UDEG node -> node skill-like decision context -> VLM/tool decision",
                        ),
                        "node_skill_context" to mapOf(
                            "role" to "decision",
                            "context_kind" to "udeg_node_skill_like_decision_context",
                            "entry_policy" to "page_match_to_udeg_node",
                            "decision_path" to "page match -> UDEG node -> node skill-like decision context -> VLM/tool decision",
                            "page_match" to mapOf(
                                "node_id" to "udeg_node_settings",
                                "page_similarity" to 0.91,
                                "reason" to "page_vector_strong_match",
                            ),
                        ),
                        "skill" to mapOf(
                            "decision_guidance" to "Use Settings node context before choosing actions.",
                            "body" to """
                                # UDEG Node Skill
                                
                                ## Decision Context
                                Use Settings node context before choosing actions.
                            """.trimIndent(),
                        ),
                    )
                ),
                "candidates" to emptyList<Map<String, Any?>>(),
            )
        )

        assertTrue(guidance.contains("node_id=udeg_node_settings"))
        assertTrue(guidance.contains("node_decision_path: page match -> UDEG node -> node skill-like decision context -> VLM/tool decision"))
        assertTrue(guidance.contains("node_decision_context: role=decision entry_policy=page_match_to_udeg_node"))
        assertTrue(guidance.contains("node_skill_context: role=decision context_kind=udeg_node_skill_like_decision_context entry_policy=page_match_to_udeg_node node_id=udeg_node_settings page_similarity=0.91"))
        assertTrue(guidance.contains("node_skill_decision_context: Use Settings node context"))
        assertTrue(guidance.contains("node_skill_body:"))
        assertTrue(guidance.contains("# UDEG Node Skill"))
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

    @Test
    fun `online page context provider observes page and injects udeg node context`() = runBlocking {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val provider = OobVlmPageContextProvider(context)
            val enriched = provider.enrich(
                VLMPageContextRequest(
                    context = UIContext(
                        overallTask = "Open Network settings",
                        targetPackageName = "com.example.settings",
                    ),
                    currentXml = SETTINGS_XML,
                    currentPackageName = "com.example.settings",
                    screenshotBase64 = "fake-screenshot",
                    stepIndex = 0,
                )
            )

            assertTrue(enriched.currentPageSummary.contains("UDEG page-match context"))
            assertTrue(enriched.currentPageSummary.contains(OobUdegNodeStore.UDEG_DECISION_PATH))
            assertTrue(enriched.currentPageSummary.contains("Network"))
            assertTrue(enriched.currentPageSummary.contains("live screenshot/XML page match"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    private companion object {
        const val SETTINGS_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Settings" bounds="[32,64][400,160]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Network" clickable="true" enabled="true" bounds="[32,240][1048,360]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Display" clickable="true" enabled="true" bounds="[32,380][1048,500]" />
              </node>
            </hierarchy>
        """
    }
}
