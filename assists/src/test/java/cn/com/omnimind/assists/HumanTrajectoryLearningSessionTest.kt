package cn.com.omnimind.assists

import cn.com.omnimind.assists.task.vlmserver.ManualVlmRecordedAction
import cn.com.omnimind.assists.task.vlmserver.ManualRecordingDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTrajectoryLearningSessionTest {
    @Test
    fun `manual recording diagnostics require active raw touch for no-miss guarantee`() {
        val semanticOnly = ManualRecordingDiagnostics.completeness(
            rawTouchAvailable = false,
            rawTouchActiveAtStop = null,
        )
        val rawInterrupted = ManualRecordingDiagnostics.completeness(
            rawTouchAvailable = true,
            rawTouchActiveAtStop = false,
        )
        val rawComplete = ManualRecordingDiagnostics.completeness(
            rawTouchAvailable = true,
            rawTouchActiveAtStop = true,
        )

        assertEquals(ManualRecordingDiagnostics.MISSING_RAW_TOUCH, semanticOnly)
        assertEquals(ManualRecordingDiagnostics.RAW_TOUCH_INTERRUPTED, rawInterrupted)
        assertEquals(ManualRecordingDiagnostics.COMPLETE_RAW_TOUCH, rawComplete)
        assertFalse(ManualRecordingDiagnostics.guaranteesNoMissingClicks(false, null))
        assertFalse(ManualRecordingDiagnostics.guaranteesNoMissingClicks(true, false))
        assertTrue(ManualRecordingDiagnostics.guaranteesNoMissingClicks(true, true))
        assertTrue(ManualRecordingDiagnostics.warningMessage(semanticOnly)!!.contains("raw touch"))
    }

    @Test
    fun `manual recording run log card keeps replay context and timing`() {
        val beforeXml = "<hierarchy package=\"com.android.settings\"><node text=\"Bluetooth\" bounds=\"[0,0][100,100]\" /></hierarchy>"
        val afterXml = "<hierarchy package=\"com.android.settings\"><node text=\"Connected devices\" bounds=\"[0,0][100,100]\" /></hierarchy>"
        val action = ManualVlmRecordedAction(
            actionName = "click",
            title = "人工点击 Bluetooth",
            params = linkedMapOf(
                "target_description" to "Bluetooth",
                "x" to 42f,
                "y" to 84f,
                "bounds" to "[0,0][100,100]",
                "recording_backend" to "accessibility_event",
                "target_resolution" to "event_source",
            ),
            packageName = "com.android.settings",
            beforeXml = beforeXml,
            afterXml = afterXml,
            startedAtMs = 1000L,
            finishedAtMs = 1250L,
            summary = "人工点击 Bluetooth",
            eventContext = linkedMapOf(
                "event_type" to "TYPE_VIEW_CLICKED",
                "event_has_source" to true,
                "recording_backend" to "accessibility_event",
                "target_resolution" to "event_source",
            ),
        )

        val card = buildRunLogCardForTest("human-test-run", 1, action)
        val params = card["params"] as Map<*, *>
        val before = card["before"] as Map<*, *>
        val after = card["after"] as Map<*, *>
        val sourceContext = card["source_context"] as Map<*, *>
        val srcCtx = sourceContext["src_ctx"] as Map<*, *>
        val dstCtx = sourceContext["dst_ctx"] as Map<*, *>
        val sourceAction = sourceContext["action"] as Map<*, *>
        val meta = sourceContext["_oob_meta"] as Map<*, *>

        assertEquals("manual_recording", card["compile_kind"])
        assertEquals("human_trajectory", card["source"])
        assertEquals("com.android.settings", card["package_name"])
        assertEquals(250L, card["duration_ms"])
        assertEquals(1000L, card["started_at_ms"])
        assertEquals(1250L, card["finished_at_ms"])
        assertEquals(42f, params["x"])
        assertEquals(84f, params["y"])
        assertEquals(beforeXml, before["observation_xml"])
        assertEquals(afterXml, after["observation_xml"])
        assertEquals(beforeXml, srcCtx["page"])
        assertEquals(afterXml, dstCtx["page"])
        assertEquals("click", sourceAction["tool"])
        assertEquals(42f, sourceAction["x"])
        assertEquals(84f, sourceAction["y"])
        assertEquals("manual_operation_recording", meta["mode"])
        assertEquals("accessibility_event", meta["recording_backend"])
        assertEquals("accessibility_event", meta["action_source"])
        assertEquals(action.eventContext, card["event_context"])
        assertEquals(action.eventContext, meta["event_context"])
        assertNotNull(card["tool_call"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildRunLogCardForTest(
        runId: String,
        index: Int,
        action: ManualVlmRecordedAction,
    ): Map<String, Any?> {
        val method = HumanTrajectoryLearningSession::class.java.getDeclaredMethod(
            "buildRunLogCard",
            String::class.java,
            Int::class.javaPrimitiveType,
            ManualVlmRecordedAction::class.java,
        )
        method.isAccessible = true
        return method.invoke(HumanTrajectoryLearningSession, runId, index, action) as Map<String, Any?>
    }
}
