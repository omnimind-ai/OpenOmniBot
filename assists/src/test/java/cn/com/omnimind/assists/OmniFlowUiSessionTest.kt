package cn.com.omnimind.assists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowUiSessionTest {
    @Test
    fun `requestStopSession matches child omniflow task id`() {
        val runId = "omniflow-run-${System.nanoTime()}"
        val childTaskId = "omniflow-child-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            OmniFlowUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId }
            )
            OmniFlowUiSession.beginTask(runId, childTaskId)

            assertTrue(OmniFlowUiSession.requestStopSession(childTaskId))
            assertEquals(listOf(runId), stopped)
            assertFalse(OmniFlowUiSession.requestStopSession(childTaskId))

            val end = OmniFlowUiSession.endRun(runId)
            assertTrue(end.wasActive)
            assertTrue(end.stopRequested)
            assertFalse(end.completeRequested)
        } finally {
            OmniFlowUiSession.endRun(runId)
        }
    }

    @Test
    fun `requestStopSession without id stops every active omniflow session`() {
        val firstRunId = "omniflow-run-a-${System.nanoTime()}"
        val secondRunId = "omniflow-run-b-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            OmniFlowUiSession.registerRun(
                runId = firstRunId,
                onStopRequested = { stopped += firstRunId }
            )
            OmniFlowUiSession.beginTask(firstRunId, "omniflow-child-a-${System.nanoTime()}")
            OmniFlowUiSession.registerRun(
                runId = secondRunId,
                onStopRequested = { stopped += secondRunId }
            )
            OmniFlowUiSession.beginTask(secondRunId, "omniflow-child-b-${System.nanoTime()}")

            assertTrue(OmniFlowUiSession.requestStopSession(null))
            assertEquals(setOf(firstRunId, secondRunId), stopped.toSet())
        } finally {
            OmniFlowUiSession.endRun(firstRunId)
            OmniFlowUiSession.endRun(secondRunId)
        }
    }

    @Test
    fun `ended child task id cannot stop active omniflow run`() {
        val runId = "omniflow-run-active-${System.nanoTime()}"
        val childTaskId = "omniflow-child-active-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            OmniFlowUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId }
            )
            OmniFlowUiSession.beginTask(runId, childTaskId)
            OmniFlowUiSession.endTask(childTaskId)

            assertFalse(OmniFlowUiSession.requestStopSession(childTaskId))
            assertTrue(stopped.isEmpty())

            assertTrue(OmniFlowUiSession.requestStopSession(runId))
            assertEquals(listOf(runId), stopped)
        } finally {
            OmniFlowUiSession.endRun(runId)
        }
    }

    @Test
    fun `requestCompleteActiveSession invokes completion callback once`() {
        val runId = "omniflow-run-complete-${System.nanoTime()}"
        val completed = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        try {
            OmniFlowUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId },
                onCompleteRequested = { completed += runId }
            )
            OmniFlowUiSession.beginTask(runId, "omniflow-child-complete-${System.nanoTime()}")

            assertTrue(OmniFlowUiSession.requestCompleteActiveSession())
            assertEquals(listOf(runId), completed)
            assertTrue(stopped.isEmpty())
            assertFalse(OmniFlowUiSession.requestCompleteActiveSession())
            assertFalse(OmniFlowUiSession.requestStopSession(runId))

            val end = OmniFlowUiSession.endRun(runId)
            assertTrue(end.wasActive)
            assertTrue(end.completeRequested)
            assertFalse(end.stopRequested)
        } finally {
            OmniFlowUiSession.endRun(runId)
        }
    }

    @Test
    fun `requestCompleteActiveSession falls back to stop callback`() {
        val runId = "omniflow-run-complete-fallback-${System.nanoTime()}"
        val stopped = mutableListOf<String>()

        try {
            OmniFlowUiSession.registerRun(
                runId = runId,
                onStopRequested = { stopped += runId }
            )
            OmniFlowUiSession.beginTask(runId, "omniflow-child-complete-fallback-${System.nanoTime()}")

            assertTrue(OmniFlowUiSession.requestCompleteActiveSession())
            assertEquals(listOf(runId), stopped)
            assertFalse(OmniFlowUiSession.requestStopSession(runId))

            val end = OmniFlowUiSession.endRun(runId)
            assertTrue(end.wasActive)
            assertTrue(end.completeRequested)
            assertFalse(end.stopRequested)
        } finally {
            OmniFlowUiSession.endRun(runId)
        }
    }
}
