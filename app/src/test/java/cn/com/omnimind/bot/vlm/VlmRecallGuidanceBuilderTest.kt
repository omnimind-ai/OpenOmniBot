package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMCurrentPageSnapshot
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
                "decision_policy" to mapOf(
                    "mode" to "direct_execution_allowed",
                    "direct_hit_requested" to true,
                ),
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
        assertTrue(guidance.contains("function_execution_policy=direct_execution_requested_by_caller"))
        assertTrue(guidance.contains("function_id=open_network_settings"))
        assertTrue(guidance.contains("step: 1. open_app"))
        assertFalse(guidance.contains("任务已完成"))
        assertFalse(guidance.contains("current task is complete"))
    }

    @Test
    fun `legacy direct hit payload is rendered as optional candidate unless direct execution was requested`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "hit",
                "hit" to mapOf(
                    "function_id" to "open_network_settings",
                    "score" to 1.0,
                    "page_similarity" to 1.0,
                    "text_score" to 1.0,
                    "description" to "open network settings",
                    "step_summaries" to listOf(
                        mapOf("tool" to "click", "title" to "click: Network"),
                    ),
                ),
            )
        )

        assertTrue(guidance.contains("function_execution_policy=optional_candidates_only"))
        assertTrue(guidance.contains("do_not_auto_execute=true"))
        assertTrue(guidance.contains("function_id=open_network_settings"))
        assertFalse(guidance.contains("function_execution_policy=direct_execution_requested_by_caller"))
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

        assertTrue(guidance.contains("decision=recall"))
        assertTrue(guidance.contains("node 1: node_id=udeg_node_settings"))
        assertTrue(guidance.contains("Use Settings node context before choosing actions."))
    }

    @Test
    fun `render guidance exposes node attached capabilities for VLM decision`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "decision_policy" to mapOf(
                    "mode" to "node_skill_context_only",
                    "requires_vlm_or_tool_decision" to true,
                ),
                "node_candidates" to listOf(
                    mapOf(
                        "node_id" to "udeg_node_settings",
                        "page_similarity" to 0.94,
                    )
                ),
                "capability_candidates" to listOf(
                    mapOf(
                        "capability_type" to "function",
                        "recall_scope" to "udeg_node",
                        "function_id" to "open_network_settings",
                        "score" to 0.98,
                        "description" to "Open Network settings from the Settings page.",
                        "step_summaries" to listOf(
                            mapOf("tool" to "click", "title" to "click: Network & internet"),
                        ),
                    )
                ),
            )
        )

        assertTrue(guidance.contains("decision_policy: mode=node_skill_context_only"))
        assertTrue(guidance.contains("capability 1: type=function scope=udeg_node function_id=open_network_settings"))
        assertTrue(guidance.contains("capability_step: 1. click"))
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "recall",
                    "capability_candidates" to listOf(mapOf("function_id" to "open_network_settings")),
                )
            )
        )
    }

    @Test
    fun `context only recall renders function candidates without making them direct calls`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "decision_policy" to mapOf(
                    "mode" to "node_skill_context_only",
                    "requires_vlm_or_tool_decision" to true,
                ),
                "node_candidates" to listOf(
                    mapOf(
                        "node_id" to "udeg_node_settings",
                        "page_similarity" to 0.93,
                    )
                ),
                "candidates" to listOf(
                    mapOf(
                        "function_id" to "open_network_settings",
                        "score" to 0.91,
                        "description" to "Open Network settings from Settings.",
                        "step_summaries" to listOf(
                            mapOf("tool" to "click", "title" to "click: Network & internet"),
                        ),
                    )
                ),
            )
        )

        assertTrue(guidance.contains("function_execution_policy=optional_candidates_only"))
        assertTrue(guidance.contains("do_not_auto_execute=true"))
        assertTrue(guidance.contains("1. function_id=open_network_settings"))
        assertFalse(guidance.contains("segment"))
        assertFalse(guidance.contains("start_step_index"))
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "recall",
                    "candidates" to listOf(mapOf("function_id" to "open_network_settings")),
                )
            )
        )
    }

    @Test
    fun `strict direct hit payload is candidate only unless caller enables auto execution`() {
        val payload = mapOf(
            "success" to true,
            "decision" to "hit",
            "hit" to mapOf(
                "function_id" to "open_network_settings",
                "score" to 1.0,
                "page_similarity" to 1.0,
                "text_score" to 1.0,
                "strict_direct_hit" to true,
                "requires_arguments" to false,
            ),
        )

        val contextOnly = VlmRecallGuidanceBuilder.fromAgentPayload(
            payload = payload,
            allowDirectExecutionDecision = false,
        )
        val directAllowed = VlmRecallGuidanceBuilder.fromAgentPayload(
            payload = payload,
            allowDirectExecutionDecision = true,
        )

        assertNull(contextOnly.directHitFunctionId)
        assertEquals("open_network_settings", directAllowed.directHitFunctionId)
    }

    @Test
    fun `weak recall candidates without page matched node are kept out of online VLM step guidance`() {
        val guidance = VlmRecallGuidanceBuilder.renderGuidance(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "candidates" to listOf(
                    mapOf(
                        "function_id" to "open_settings_from_history",
                        "score" to 0.71,
                        "description" to "Historical Settings path",
                        "step_summaries" to listOf(
                            mapOf("tool" to "click", "title" to "click: Network"),
                        ),
                    )
                ),
            )
        )

        assertEquals("", guidance)
    }

    @Test
    fun `agent safe payload removes recall timing fields`() {
        val safe = VlmRecallGuidanceBuilder.agentSafePayload(
            mapOf(
                "success" to true,
                "decision" to "recall",
                "timing" to mapOf(
                    "duration_ms" to 12,
                    "phase_ms" to mapOf("page_match_ms" to 3),
                ),
                "candidates" to listOf(
                    mapOf(
                        "function_id" to "open_settings",
                        "started_at_ms" to 100,
                        "finished_at_ms" to 120,
                        "step_summaries" to listOf(
                            mapOf("tool" to "click", "duration_ms" to 5),
                        ),
                    )
                ),
            )
        )

        assertFalse(safe.containsKey("timing"))
        val candidate = (safe["candidates"] as List<*>).single() as Map<*, *>
        assertEquals("open_settings", candidate["function_id"])
        assertFalse(candidate.containsKey("started_at_ms"))
        assertFalse(candidate.containsKey("finished_at_ms"))
        val step = (candidate["step_summaries"] as List<*>).single() as Map<*, *>
        assertFalse(step.containsKey("duration_ms"))
    }

    @Test
    fun `direct hit function id requires strict page and text evidence`() {
        assertEquals(
            "open_settings",
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "open_settings",
                        "score" to 1.0,
                        "page_similarity" to 1.0,
                        "text_score" to 1.0,
                    ),
                )
            )
        )
        assertNull(
            VlmRecallGuidanceBuilder.directHitFunctionId(
                mapOf(
                    "success" to true,
                    "decision" to "hit",
                    "hit" to mapOf(
                        "function_id" to "open_settings",
                        "score" to 0.999,
                        "page_similarity" to 0.998,
                        "text_score" to 1.0,
                    ),
                )
            )
        )
        assertNull(
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
    fun `online page context provider records first seen page without injecting node skill`() = runBlocking {
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
                    snapshot = VLMCurrentPageSnapshot(
                        packageName = "com.example.settings",
                        xml = SETTINGS_XML,
                        screenshotBase64 = "fake-screenshot",
                        displayWidth = 1080,
                        displayHeight = 1920,
                        capturedAtMs = 1234L,
                    )
                )
            )

            assertFalse(enriched.currentPageSummary.contains("UDEG page skill context"))
            assertEquals("true", enriched.pageDiagnostics["udeg_first_seen"])
            assertEquals("false", enriched.pageDiagnostics["udeg_context_injected"])
            assertEquals("1234", enriched.pageDiagnostics["snapshot_timestamp"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `online page context provider injects structured node skill after page match`() = runBlocking {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            OobUdegNodeStore(context).upsertFunction(
                "open_network_settings",
                mapOf(
                    "name" to "Open Network settings",
                    "description" to "Open the Network row from Settings",
                    "execution" to mapOf(
                        "steps" to listOf(
                            mapOf(
                                "tool" to "click",
                                "title" to "Click Network",
                                "source_context" to mapOf(
                                    "src_ctx" to mapOf(
                                        "observation_xml" to SETTINGS_XML,
                                        "package_name" to "com.example.settings",
                                    )
                                )
                            )
                        )
                    )
                )
            )
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
                    stepIndex = 1,
                )
            )

            assertTrue(enriched.currentPageSummary.contains("UDEG page skill context"))
            assertTrue(enriched.currentPageSummary.contains(OobUdegNodeStore.UDEG_DECISION_PATH))
            assertTrue(enriched.currentPageSummary.contains("UDEG attached Functions"))
            assertTrue(enriched.currentPageSummary.contains("function_id=open_network_settings"))
            assertTrue(enriched.currentPageSummary.contains("动作仍必须基于本轮 live screenshot/XML/indexed evidence"))
            assertEquals("false", enriched.pageDiagnostics["udeg_first_seen"])
            assertEquals("true", enriched.pageDiagnostics["udeg_context_injected"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `online page context provider uses snapshot xml instead of stale request xml`() = runBlocking {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            OobUdegNodeStore(context).upsertFunction(
                "open_network_settings",
                mapOf(
                    "name" to "Open Network settings",
                    "description" to "Open the Network row from Settings",
                    "execution" to mapOf(
                        "steps" to listOf(
                            mapOf(
                                "tool" to "click",
                                "title" to "Click Network",
                                "source_context" to mapOf(
                                    "src_ctx" to mapOf(
                                        "observation_xml" to SETTINGS_XML,
                                        "package_name" to "com.example.settings",
                                    )
                                )
                            )
                        )
                    )
                )
            )
            val provider = OobVlmPageContextProvider(context)
            val enriched = provider.enrich(
                VLMPageContextRequest(
                    context = UIContext(
                        overallTask = "Open Network settings",
                        targetPackageName = "com.example.settings",
                    ),
                    currentXml = SETTINGS_XML,
                    currentPackageName = "com.example.settings",
                    screenshotBase64 = "stale-screenshot",
                    stepIndex = 2,
                    snapshot = VLMCurrentPageSnapshot(
                        packageName = "com.example.settings",
                        xml = DISPLAY_XML,
                        screenshotBase64 = "fresh-screenshot",
                        displayWidth = 1080,
                        displayHeight = 1920,
                        capturedAtMs = 5678L,
                    )
                )
            )

            assertFalse(enriched.currentPageSummary.contains("function_id=open_network_settings"))
            assertFalse(enriched.currentPageSummary.contains("UDEG page skill context"))
            assertEquals("true", enriched.pageDiagnostics["udeg_first_seen"])
            assertEquals("false", enriched.pageDiagnostics["udeg_context_injected"])
            assertEquals("5678", enriched.pageDiagnostics["snapshot_timestamp"])
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

        const val DISPLAY_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Display" bounds="[32,64][400,160]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Brightness level" clickable="true" enabled="true" bounds="[32,240][1048,360]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Dark theme" clickable="true" enabled="true" bounds="[32,380][1048,500]" />
              </node>
            </hierarchy>
        """
    }
}
