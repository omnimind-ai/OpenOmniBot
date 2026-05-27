package cn.com.omnimind.assists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVlmUiSessionTest {
    @Test
    fun `requestStopSession matches child vlm task id`() {
        val runId = "run-${System.nanoTime()}"
        val childTaskId = "child-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        AgentVlmUiSession.registerRun(
            runId = runId,
            onStopRequested = { stopped += runId }
        )
        AgentVlmUiSession.beginTask(runId, childTaskId)

        assertTrue(AgentVlmUiSession.requestStopSession(childTaskId))
        assertEquals(listOf(runId), stopped)
        assertFalse(AgentVlmUiSession.requestStopSession(childTaskId))

        val end = AgentVlmUiSession.endRun(runId)
        assertTrue(end.wasActive)
        assertTrue(end.stopRequested)
    }

    @Test
    fun `requestStopSession without id stops every active vlm session`() {
        val firstRunId = "run-a-${System.nanoTime()}"
        val secondRunId = "run-b-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        AgentVlmUiSession.registerRun(
            runId = firstRunId,
            onStopRequested = { stopped += firstRunId }
        )
        AgentVlmUiSession.beginTask(firstRunId, "child-a-${System.nanoTime()}")
        AgentVlmUiSession.registerRun(
            runId = secondRunId,
            onStopRequested = { stopped += secondRunId }
        )
        AgentVlmUiSession.beginTask(secondRunId, "child-b-${System.nanoTime()}")

        assertTrue(AgentVlmUiSession.requestStopSession(null))
        assertEquals(setOf(firstRunId, secondRunId), stopped.toSet())

        AgentVlmUiSession.endRun(firstRunId)
        AgentVlmUiSession.endRun(secondRunId)
    }

    @Test
    fun `ended child task id cannot stop active run`() {
        val runId = "run-active-${System.nanoTime()}"
        val childTaskId = "child-active-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        AgentVlmUiSession.registerRun(
            runId = runId,
            onStopRequested = { stopped += runId }
        )
        AgentVlmUiSession.beginTask(runId, childTaskId)

        assertEquals(listOf(childTaskId), AgentVlmUiSession.activeTaskIdsForRun(runId))

        AgentVlmUiSession.endTask(childTaskId)

        assertTrue(AgentVlmUiSession.activeTaskIdsForRun(runId).isEmpty())
        assertFalse(AgentVlmUiSession.requestStopSession(childTaskId))
        assertTrue(stopped.isEmpty())

        assertTrue(AgentVlmUiSession.requestStopSession(runId))
        assertEquals(listOf(runId), stopped)

        AgentVlmUiSession.endRun(runId)
    }

    @Test
    fun `stale child task id does not resolve to another active run`() {
        val firstRunId = "run-stale-a-${System.nanoTime()}"
        val secondRunId = "run-stale-b-${System.nanoTime()}"
        val staleChildTaskId = "child-stale-a-${System.nanoTime()}"
        val activeChildTaskId = "child-stale-b-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        AgentVlmUiSession.registerRun(
            runId = firstRunId,
            onStopRequested = { stopped += firstRunId }
        )
        AgentVlmUiSession.beginTask(firstRunId, staleChildTaskId)
        AgentVlmUiSession.endTask(staleChildTaskId)

        AgentVlmUiSession.registerRun(
            runId = secondRunId,
            onStopRequested = { stopped += secondRunId }
        )
        AgentVlmUiSession.beginTask(secondRunId, activeChildTaskId)

        assertFalse(AgentVlmUiSession.requestStopSession(staleChildTaskId))
        assertTrue(stopped.isEmpty())
        assertEquals(listOf(activeChildTaskId), AgentVlmUiSession.activeTaskIdsForRun(secondRunId))

        AgentVlmUiSession.endRun(firstRunId)
        AgentVlmUiSession.endRun(secondRunId)
    }
}
