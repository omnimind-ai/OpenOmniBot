package cn.com.omnimind.bot.vlm

import cn.com.omnimind.bot.mcp.TaskState
import cn.com.omnimind.bot.mcp.TaskStatus
import cn.com.omnimind.bot.mcp.VlmTaskRequest
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
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_segment",
            ),
            progressReporter = { _, extras -> events += extras },
            callFunction = { functionId, startStepIndex ->
                assertEquals(0, startStepIndex)
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
    fun `segment recall hit passes suffix start index to function runner`() = runBlocking {
        val state = TaskState(
            taskId = "task-segment-recall-hit",
            goal = "continue settings flow",
            status = TaskStatus.RUNNING,
        )
        var capturedFunctionId = ""

        val outcome = VlmToolCoordinator.tryExecuteRecallHit(
            taskState = state,
            goal = state.goal,
            recallGuidance = VlmRecallGuidance(
                decision = "segment_hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_segment",
                directHitStartStepIndex = 2,
            ),
            progressReporter = { _, _ -> },
            callFunction = { functionId, startStepIndex ->
                capturedFunctionId = functionId
                assertEquals(2, startStepIndex)
                mapOf(
                    "success" to true,
                    "fallback" to false,
                    "function_id" to functionId,
                    "run_id" to "omniflow_segment_run_test",
                    "actions_executed" to 2,
                )
            },
        )

        assertNotNull(outcome)
        assertEquals("open_settings_segment", capturedFunctionId)
        assertEquals(VlmToolOutcomeStatus.FINISHED, outcome?.status)
        assertEquals("omniflow_recall_segment_hit:open_settings_segment:2", state.executionRoute)
        assertTrue(state.summaryText?.contains("segment_start_step_index=2") == true)
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
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_segment",
            ),
            progressReporter = { _, extras -> events += extras },
            callFunction = { _, _ ->
                mapOf(
                    "success" to false,
                    "fallback" to true,
                    "error" to "execution_failed",
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
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = null,
            ),
            progressReporter = { _, _ -> },
            callFunction = { _, _ ->
                called = true
                emptyMap()
            },
        )

        assertNull(outcome)
        assertEquals(false, called)
        assertEquals(TaskStatus.RUNNING, state.status)
    }

    @Test
    fun `request can disable omniflow recall for fresh VLM validation`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
            disableOmniFlowRecall = true,
        )

        val result = VlmToolCoordinator.buildRecallGuidanceAfterOptionalPrelaunch(
            context = cn.com.omnimind.bot.runlog.OobOmniFlowLoopAcceptanceTest.TempFilesContext(),
            request = request,
        )

        assertEquals("disabled", result.first.decision)
        assertTrue(result.first.guidance.isBlank())
        assertEquals(true, result.first.payload["recall_disabled"])
        assertEquals(request, result.second)
    }

    @Test
    fun `vlm requests default to recall context without function auto execution`() {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
        )

        assertEquals(false, request.allowOmniFlowFunctionAutoExecute)
    }

    @Test
    fun `recall hit is not executed unless request explicitly allows auto execution`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
        )
        val state = TaskState(
            taskId = "task-default-context-only",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_segment",
            ),
            progressReporter = { _, _ -> },
            callFunction = { _, _ ->
                called = true
                mapOf("success" to true)
            },
        )

        assertNull(outcome)
        assertEquals(false, called)
        assertEquals(TaskStatus.RUNNING, state.status)
    }

    @Test
    fun `recall hit executes when request explicitly allows auto execution`() = runBlocking {
        val request = VlmTaskRequest(
            goal = "open settings",
            packageName = "com.android.settings",
            allowOmniFlowFunctionAutoExecute = true,
        )
        val state = TaskState(
            taskId = "task-explicit-auto-execute",
            goal = request.goal,
            status = TaskStatus.RUNNING,
        )
        var called = false

        val outcome = VlmToolCoordinator.tryExecuteRecallHitIfAllowed(
            request = request,
            taskState = state,
            recallGuidance = VlmRecallGuidance(
                decision = "hit",
                guidance = "OmniFlow UDEG node skill-like decision context",
                payload = mapOf("success" to true),
                directHitFunctionId = "open_settings_segment",
            ),
            progressReporter = { _, _ -> },
            callFunction = { _, _ ->
                called = true
                mapOf(
                    "success" to true,
                    "fallback" to false,
                    "function_id" to "open_settings_segment",
                    "run_id" to "omniflow_run_test",
                    "actions_executed" to 1,
                )
            },
        )

        assertNotNull(outcome)
        assertEquals(true, called)
        assertEquals(TaskStatus.FINISHED, state.status)
    }
}
