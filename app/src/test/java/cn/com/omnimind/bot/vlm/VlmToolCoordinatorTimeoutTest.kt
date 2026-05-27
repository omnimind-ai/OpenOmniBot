package cn.com.omnimind.bot.vlm

import cn.com.omnimind.bot.mcp.McpTaskManager
import cn.com.omnimind.bot.mcp.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class VlmToolCoordinatorTimeoutTest {
    @Test
    fun `wait timeout defaults to long running vlm budget`() {
        assertEquals(
            600_000L,
            VlmToolCoordinator.resolveWaitTimeoutMs(null)
        )
        assertEquals(
            600_000L,
            VlmToolCoordinator.resolveWaitTimeoutMs(0L)
        )
    }

    @Test
    fun `wait timeout is clamped for acceptance runs`() {
        assertEquals(30_000L, VlmToolCoordinator.resolveWaitTimeoutMs(1_000L))
        assertEquals(420_000L, VlmToolCoordinator.resolveWaitTimeoutMs(420_000L))
        assertEquals(600_000L, VlmToolCoordinator.resolveWaitTimeoutMs(900_000L))
    }

    @Test
    fun `manual cancel marks active vlm task cancelled`() {
        val taskId = "test-vlm-cancel-${System.nanoTime()}"
        val state = McpTaskManager.createTask(
            taskId = taskId,
            goal = "open settings",
            status = TaskStatus.RUNNING,
        )
        try {
            VlmToolCoordinator.cancelTask(taskId, message = "stopped by test")

            assertEquals(TaskStatus.CANCELLED, state.status)
            assertEquals("stopped by test", state.message)
        } finally {
            McpTaskManager.removeTask(taskId)
        }
    }
}
