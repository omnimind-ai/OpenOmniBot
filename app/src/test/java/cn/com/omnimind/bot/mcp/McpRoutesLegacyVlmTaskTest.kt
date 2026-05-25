package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpRoutesLegacyVlmTaskTest {
    @Test
    fun `legacy vlm endpoint preserves execution options`() {
        val args = McpRoutes.legacyVlmRequestToToolArgs(
            VlmTaskRequest(
                goal = "Open Android Settings, open Display settings, then finish.",
                model = "vlm-test",
                maxSteps = 6,
                waitTimeoutMs = 180_000L,
                packageName = "com.android.settings",
                needSummary = false,
                skipGoHome = true,
                disableOmniFlowRecall = true,
                allowOmniFlowFunctionAutoExecute = true,
            )
        )

        assertEquals("Open Android Settings, open Display settings, then finish.", args["goal"])
        assertEquals("vlm-test", args["model"])
        assertEquals(6, args["maxSteps"])
        assertEquals(180_000L, args["waitTimeoutMs"])
        assertEquals("com.android.settings", args["packageName"])
        assertEquals(false, args["needSummary"])
        assertEquals(true, args["skipGoHome"])
        assertEquals(true, args["disableOmniFlowRecall"])
        assertEquals(true, args["allowOmniFlowFunctionAutoExecute"])
    }
}
