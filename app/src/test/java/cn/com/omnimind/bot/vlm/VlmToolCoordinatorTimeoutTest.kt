package cn.com.omnimind.bot.vlm

import cn.com.omnimind.bot.mcp.McpTaskManager
import org.junit.Assert.assertEquals
import org.junit.Test

class VlmToolCoordinatorTimeoutTest {
    @Test
    fun `wait timeout defaults to mcp task manager budget`() {
        assertEquals(
            McpTaskManager.MAX_WAIT_TIME_MS,
            VlmToolCoordinator.resolveWaitTimeoutMs(null)
        )
        assertEquals(
            McpTaskManager.MAX_WAIT_TIME_MS,
            VlmToolCoordinator.resolveWaitTimeoutMs(0L)
        )
    }

    @Test
    fun `wait timeout is clamped for acceptance runs`() {
        assertEquals(30_000L, VlmToolCoordinator.resolveWaitTimeoutMs(1_000L))
        assertEquals(420_000L, VlmToolCoordinator.resolveWaitTimeoutMs(420_000L))
        assertEquals(600_000L, VlmToolCoordinator.resolveWaitTimeoutMs(900_000L))
    }
}
