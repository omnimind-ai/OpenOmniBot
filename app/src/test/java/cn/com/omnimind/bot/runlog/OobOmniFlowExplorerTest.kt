package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobOmniFlowExplorerTest {
    @Test
    fun `snapshot parser creates stable UTG candidates from accessibility xml`() {
        val snapshot = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(
                xml = SOURCE_XML,
                packageName = "com.example.settings",
                activityName = "SettingsActivity",
            )
        )

        assertTrue(snapshot.nodeId.startsWith("utg_node_"))
        assertEquals("com.example.settings", snapshot.packageName)
        assertEquals(3, snapshot.candidates.size)
        assertTrue(snapshot.candidates.all { it.actionId.startsWith("utg_action_") })
        assertTrue(snapshot.candidates.any { it.label == "Network" })
    }

    @Test
    fun `candidate rank prefers goal matching safe action`() {
        val snapshot = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(SOURCE_XML, "com.example.settings")
        )

        val ranked = OobOmniFlowExplorer.rankCandidates(
            snapshot = snapshot,
            goal = "open network settings",
        )

        assertEquals("Network", ranked.first().label)
        assertTrue(ranked.none { it.label == "Delete account" })
    }

    @Test
    fun `snapshot diagnostics expose raw safe and skipped candidate counts`() {
        val snapshot = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(SOURCE_XML, "com.example.settings")
        )

        val safe = snapshot.safeCandidates(goal = "open network settings")
        val skipReasons = snapshot.skipReasonSummary()

        assertEquals(3, snapshot.rawActionableCount())
        assertEquals(1, safe.size)
        assertEquals(1, skipReasons["risky_label:delete"])
        assertEquals(1, skipReasons["checkable_control"])
    }

    @Test
    fun `UTG click card compiles into omniflow function with coordinate hook`() {
        val before = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(SOURCE_XML, "com.example.settings")
        )
        val candidate = OobOmniFlowExplorer.rankCandidates(before, "network").first()
        val after = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(AFTER_XML, "com.example.settings")
        )
        val edge = OobOmniFlowExplorer.edgeFor(before, after, candidate, stepIndex = 0)
        val card = OobOmniFlowExplorer.buildClickCard(
            stepIndex = 0,
            before = before,
            after = after,
            candidate = candidate,
            edge = edge,
        )
        val record = InternalRunLogRecord(
            runId = "utg-test-run",
            goal = "open network settings",
            source = "oob_native_omniflow_explorer",
            toolName = "omniflow.explore_replay",
            operationDescription = "open network settings",
            finishedAtMs = 1234L,
            success = true,
            cards = listOf(card),
        )

        val spec = requireNotNull(RunLogReusableFunctionCompiler.compile(record))
        val execution = spec["execution"] as Map<*, *>
        val steps = execution["steps"] as List<*>
        val step = steps.single() as Map<*, *>
        val sourceContext = step["source_context"] as? Map<*, *>

        assertEquals("oob.reusable_function.v1", spec["schema_version"])
        assertEquals("omniflow", step["executor"])
        assertEquals("click", step["omniflow_action"])
        assertEquals("omniflow", step["coordinate_hook"])
        assertEquals("omniflow_utg", step["replay_engine"])
        assertNotNull(step["utg"])
        assertNotNull(sourceContext)
        assertEquals("open network settings", (spec["source"] as Map<*, *>)["goal"])
        assertEquals(1, execution["omniflow_step_count"])
    }

    @Test
    fun `snapshot parser emits scroll action for scrollable node`() {
        val snapshot = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(SCROLL_XML, "com.example.feed")
        )

        val scroll = snapshot.candidates.single()

        assertEquals(OobOmniFlowExplorer.ACTION_SCROLL, scroll.action)
        assertTrue(scroll.scrollable)
        assertEquals("up", scroll.scrollDirection)
        assertTrue(scroll.scrollDistancePx > 0f)
    }

    @Test
    fun `UTG scroll card compiles into omniflow replay step`() {
        val before = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(SCROLL_XML, "com.example.feed")
        )
        val candidate = OobOmniFlowExplorer.rankCandidates(before, "read more").first()
        val after = requireNotNull(
            OobOmniFlowExplorer.parseSnapshot(SCROLL_AFTER_XML, "com.example.feed")
        )
        val edge = OobOmniFlowExplorer.edgeFor(before, after, candidate, stepIndex = 0)
        val card = OobOmniFlowExplorer.buildActionCard(
            stepIndex = 0,
            before = before,
            after = after,
            candidate = candidate,
            edge = edge,
        )
        val record = InternalRunLogRecord(
            runId = "utg-scroll-run",
            goal = "read more posts",
            source = "oob_native_omniflow_explorer",
            toolName = "omniflow.explore_replay",
            operationDescription = "read more posts",
            finishedAtMs = 1234L,
            success = true,
            cards = listOf(card),
        )

        val spec = requireNotNull(RunLogReusableFunctionCompiler.compile(record))
        val execution = spec["execution"] as Map<*, *>
        val steps = execution["steps"] as List<*>
        val step = steps.single() as Map<*, *>
        val args = step["args"] as Map<*, *>

        assertEquals("omniflow", step["executor"])
        assertEquals("scroll", step["omniflow_action"])
        assertEquals("omniflow", step["coordinate_hook"])
        assertEquals("omniflow_utg", step["replay_engine"])
        assertEquals("up", args["direction"])
        assertNotNull(args["x1"])
        assertNotNull(args["x2"])
        assertEquals(1, execution["omniflow_step_count"])
    }

    companion object {
        private const val SOURCE_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Network" content-desc="" resource-id="android:id/network" clickable="true" enabled="true" visible-to-user="true" bounds="[40,200][1040,320]" />
              <node index="1" package="com.example.settings" class="android.widget.TextView" text="Delete account" content-desc="" resource-id="android:id/delete" clickable="true" enabled="true" visible-to-user="true" bounds="[40,360][1040,480]" />
              <node index="2" package="com.example.settings" class="android.widget.Switch" text="Wi-Fi" content-desc="" resource-id="android:id/wifi" clickable="true" checkable="true" enabled="true" visible-to-user="true" bounds="[40,520][1040,640]" />
            </hierarchy>
        """

        private const val AFTER_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Internet" content-desc="" resource-id="android:id/internet" clickable="true" enabled="true" visible-to-user="true" bounds="[40,200][1040,320]" />
            </hierarchy>
        """

        private const val SCROLL_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.feed" class="androidx.recyclerview.widget.RecyclerView" text="" content-desc="Posts" resource-id="app:id/list" scrollable="true" clickable="false" enabled="true" visible-to-user="true" bounds="[0,180][1080,1820]" />
            </hierarchy>
        """

        private const val SCROLL_AFTER_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.feed" class="androidx.recyclerview.widget.RecyclerView" text="" content-desc="More posts" resource-id="app:id/list" scrollable="true" clickable="false" enabled="true" visible-to-user="true" bounds="[0,180][1080,1820]" />
            </hierarchy>
        """
    }
}
