package cn.com.omnimind.bot.vlm

import cn.com.omnimind.bot.mcp.TaskState
import cn.com.omnimind.bot.mcp.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmToolCoordinatorRecallExecutionTest {
    @Test
    fun `direct recall hit returns finished outcome when function succeeds`() = runBlocking {
        val state = TaskState(
            taskId = "task-recall-hit",
            goal = "open settings",
            status = TaskStatus.RUNNING,
        )
        val events = mutableListOf<Map<String, Any?>>()

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow recall context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_segment",
            ),
            progressReporter = { _, extras -> events += extras },
            callFunction = { functionId ->
                mapOf(
                    "success" to true,
                    "fallback" to false,
                    "function_id" to functionId,
                    "run_id" to "omniflow_run_test",
                    "actions_executed" to 1,
                )
            },
        )

        assertNotNull(outcome)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertEquals(TaskStatus.FINISHED, state.status)
        assertEquals("omniflow_recall_hit:open_settings_segment", state.executionRoute)
        assertTrue(state.finishedContent?.contains("open_settings_segment") == true)
        assertTrue(state.summaryText?.contains("actions_executed=1") == true)
        assertEquals(2, events.size)
        assertEquals("FINISHED", events.last()["status"])
    }

    @Test
    fun `direct recall hit falls back to VLM when function needs fallback`() = runBlocking {
        val state = TaskState(
            taskId = "task-recall-fallback",
            goal = "open settings",
            status = TaskStatus.RUNNING,
        )
        val events = mutableListOf<Map<String, Any?>>()

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow recall context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_segment",
            ),
            progressReporter = { _, extras -> events += extras },
            callFunction = {
                mapOf(
                    "success" to false,
                    "fallback" to true,
                    "error" to "postcondition_failed",
                )
            },
        )

        assertNull(outcome)
        assertEquals(TaskStatus.RUNNING, state.status)
        assertEquals("vlm_with_omniflow_recall_fallback:hit", state.executionRoute)
        assertTrue(state.chatMessages.last().contains("continuing with VLM"))
        assertEquals(2, events.size)
        assertEquals("RUNNING", events.last()["status"])
    }

    @Test
    fun `non direct recall guidance does not attempt local execution`() = runBlocking {
        val state = TaskState(
            taskId = "task-recall-candidate",
            goal = "open settings",
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "recall",
                guidance = "OmniFlow recall context",
                payload = mapOf("success" to true),
                directHitFunctionId = null,
            ),
            progressReporter = { _, _ -> },
            callFunction = {
                called = true
                emptyMap()
            },
        )

        assertNull(outcome)
        assertEquals(false, called)
        assertEquals(TaskStatus.RUNNING, state.status)
    }
}
