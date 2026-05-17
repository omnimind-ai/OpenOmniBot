package cn.com.omnimind.bot.manager

import cn.com.omnimind.bot.agent.AgentStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistsCoreManagerAgentFinalizationTest {
    @Test
    fun `agent stream event payload includes v1 trace envelope`() {
        val payload = AgentStreamEvent(
            taskId = "agent-task",
            seq = 3L,
            kind = "text_snapshot",
            createdAt = 1234L,
            entryId = "agent-task-text",
            roundIndex = 1,
            text = "hello"
        ).toPayload(
            conversationId = 42L,
            conversationMode = "normal"
        )

        assertEquals("oob.agent_event.v1", payload["schema_version"])
        assertEquals("agent-task", payload["trace_id"])
        assertEquals("agent-task", payload["run_id"])
        assertEquals("agent-task-text", payload["span_id"])
        assertEquals("agent-task", payload["parent_span_id"])
        assertEquals("agent_stream", payload["channel"])
        assertEquals("text_snapshot", payload["event"])
        assertEquals(1234L, payload["timestamp_ms"])
        assertEquals("running", payload["status"])
        assertEquals("text_snapshot", payload["kind"])
    }

    @Test
    fun `keeps streamed assistant text when agent errors after visible output`() {
        val resolution = resolveAgentFinalErrorResolution(
            streamed = "已生成正文😀",
            error = "Agent execution failed: length=140; regionStart=0; bytePairLength=138",
            localizedFallback = "暂时无法生成回复，请重试。"
        )

        assertEquals("已生成正文😀", resolution.text)
        assertFalse(resolution.persistAsError)
    }

    @Test
    fun `falls back to error details when no assistant text was streamed`() {
        val resolution = resolveAgentFinalErrorResolution(
            streamed = "",
            error = "Agent execution failed: length=140; regionStart=0; bytePairLength=138",
            localizedFallback = "暂时无法生成回复，请重试。"
        )

        assertEquals(
            "Agent execution failed: length=140; regionStart=0; bytePairLength=138",
            resolution.text
        )
        assertTrue(resolution.persistAsError)
    }

    @Test
    fun `uses localized fallback when streamed text and error details are blank`() {
        val resolution = resolveAgentFinalErrorResolution(
            streamed = "",
            error = "",
            localizedFallback = "暂时无法生成回复，请重试。"
        )

        assertEquals("暂时无法生成回复，请重试。", resolution.text)
        assertTrue(resolution.persistAsError)
    }

    @Test
    fun `manual cancellation stream metadata sorts after run trace entries`() {
        val meta = buildAgentManualCancellationStreamMeta(
            taskId = "agent-task",
            entryId = "agent-task-cancelled"
        )

        assertEquals(1_000_000_000L, meta["seq"])
        assertEquals(1_000_000_000, meta["roundIndex"])
        assertEquals("text_snapshot", meta["kind"])
        assertEquals("agent-task", meta["parentTaskId"])
        assertEquals("agent-task-cancelled", meta["entryId"])
        assertEquals(true, meta["isFinal"])
    }
}
