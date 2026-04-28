package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ConversationSnapshotOrderingTest {

    @Test
    fun `prepareForStorage sorts flutter local iso timestamps from oldest to newest`() {
        val messages = listOf(
            assistantMessage(
                id = "assistant",
                createAt = "2026-03-31T18:00:03.300",
                text = "assistant"
            ),
            deepThinkingMessage(
                id = "thinking",
                createAt = "2026-03-31T18:00:02.200",
                taskId = "task-1",
                startTime = localMillis("2026-03-31T18:00:02.200"),
                thinking = "thinking"
            ),
            userMessage(
                id = "user",
                createAt = "2026-03-31T18:00:01.100",
                text = "user"
            )
        )

        val orderedIds = ConversationSnapshotOrdering.prepareForStorage(messages)
            .map { it.payload["id"] }

        assertEquals(listOf("user", "thinking", "assistant"), orderedIds)
    }

    @Test
    fun `prepareForStorage keeps newest-first snapshot tie order reversible when timestamps match`() {
        val messages = listOf(
            assistantMessage(
                id = "1711872000300-text",
                createAt = "invalid",
                text = "assistant"
            ),
            deepThinkingMessage(
                id = "1711872000300-thinking",
                createAt = "invalid",
                taskId = "1711872000300-ai",
                startTime = 1711872000300L,
                thinking = "thinking"
            ),
            userMessage(
                id = "1711872000300-user",
                createAt = "invalid",
                text = "user"
            )
        )

        val orderedIds = ConversationSnapshotOrdering.prepareForStorage(messages)
            .map { it.payload["id"] }

        assertEquals(
            listOf(
                "1711872000300-user",
                "1711872000300-thinking",
                "1711872000300-text"
            ),
            orderedIds
        )
    }

    @Test
    fun `sortForDisplay keeps deep thinking between user and assistant after retry restore`() {
        val messages = listOf(
            userMessage(
                id = "1711872001200-user",
                createAt = "2026-03-31T18:00:01.200",
                text = "第二轮用户"
            ),
            assistantMessage(
                id = "1711872001200-text",
                createAt = "2026-03-31T18:00:01.202",
                text = "第二轮助手"
            ),
            deepThinkingMessage(
                id = "1711872001200-thinking",
                createAt = "2026-03-31T18:00:01.201",
                taskId = "1711872001200-ai",
                startTime = localMillis("2026-03-31T18:00:01.201"),
                thinking = "第二轮思考"
            ),
            userMessage(
                id = "1711872000100-user",
                createAt = "2026-03-31T18:00:00.100",
                text = "第一轮用户"
            ),
            assistantMessage(
                id = "1711872000100-text",
                createAt = "2026-03-31T18:00:00.102",
                text = "第一轮助手"
            ),
            deepThinkingMessage(
                id = "1711872000100-thinking",
                createAt = "2026-03-31T18:00:00.101",
                taskId = "1711872000100-ai",
                startTime = localMillis("2026-03-31T18:00:00.101"),
                thinking = "第一轮思考"
            )
        )

        val orderedIds = ConversationSnapshotOrdering.sortForDisplay(messages)
            .map { it["id"] }

        assertEquals(
            listOf(
                "1711872001200-text",
                "1711872001200-thinking",
                "1711872001200-user",
                "1711872000100-text",
                "1711872000100-thinking",
                "1711872000100-user"
            ),
            orderedIds
        )
    }

    @Test
    fun `prepareForStorage keeps logical phase order when persisted timestamps drift inside one task`() {
        val messages = listOf(
            assistantMessage(
                id = "1711872000100-ai-assistant",
                createAt = "2026-03-31T18:00:00.300",
                text = "助手回答"
            ),
            userMessage(
                id = "1711872000100-ai-user",
                createAt = "2026-03-31T18:00:00.200",
                text = "用户提问"
            ),
            deepThinkingMessage(
                id = "1711872000100-ai-thinking",
                createAt = "2026-03-31T18:00:00.100",
                taskId = "1711872000100-ai",
                startTime = localMillis("2026-03-31T18:00:00.100"),
                thinking = "思考过程"
            )
        )

        val orderedIds = ConversationSnapshotOrdering.prepareForStorage(messages)
            .map { it.payload["id"] }

        assertEquals(
            listOf(
                "1711872000100-ai-user",
                "1711872000100-ai-thinking",
                "1711872000100-ai-assistant"
            ),
            orderedIds
        )
    }

    @Test
    fun `prepareForStorage prefers stream meta sequence for new agent entries`() {
        val messages = listOf(
            toolMessage(
                id = "1711872000100-ai-tool-1",
                createAt = "2026-03-31T18:00:00.140",
                taskId = "1711872000100-ai",
                summary = "工具执行",
                streamSeq = 3L,
                roundIndex = 1,
                kind = "tool_completed"
            ),
            assistantMessage(
                id = "1711872000100-ai-text",
                createAt = "2026-03-31T18:00:00.150",
                text = "正文",
                streamSeq = 2L,
                roundIndex = 1,
                kind = "text_snapshot"
            ),
            deepThinkingMessage(
                id = "1711872000100-ai-thinking",
                createAt = "2026-03-31T18:00:00.160",
                taskId = "1711872000100-ai",
                startTime = localMillis("2026-03-31T18:00:00.100"),
                thinking = "思考",
                streamSeq = 1L,
                roundIndex = 1,
                kind = "thinking_snapshot"
            )
        )

        val orderedIds = ConversationSnapshotOrdering.prepareForStorage(messages)
            .map { it.payload["id"] }

        assertEquals(
            listOf(
                "1711872000100-ai-thinking",
                "1711872000100-ai-text",
                "1711872000100-ai-tool-1"
            ),
            orderedIds
        )
    }

    @Test
    fun `sortForDisplay preserves interleaved thinking rounds by timestamp within one reply`() {
        val messages = listOf(
            deepThinkingMessage(
                id = "1711872000100-ai-thinking-2",
                createAt = "2026-03-31T18:00:00.130",
                taskId = "1711872000100-ai",
                startTime = localMillis("2026-03-31T18:00:00.130"),
                thinking = "第二段思考"
            ),
            assistantMessage(
                id = "1711872000100-ai-assistant",
                createAt = "2026-03-31T18:00:00.120",
                text = "开始输出回答"
            ),
            deepThinkingMessage(
                id = "1711872000100-ai-thinking",
                createAt = "2026-03-31T18:00:00.110",
                taskId = "1711872000100-ai",
                startTime = localMillis("2026-03-31T18:00:00.110"),
                thinking = "第一段思考"
            ),
            userMessage(
                id = "1711872000100-ai-user",
                createAt = "2026-03-31T18:00:00.100",
                text = "用户提问"
            )
        )

        val orderedIds = ConversationSnapshotOrdering.sortForDisplay(messages)
            .map { it["id"] }

        assertEquals(
            listOf(
                "1711872000100-ai-thinking-2",
                "1711872000100-ai-assistant",
                "1711872000100-ai-thinking",
                "1711872000100-ai-user"
            ),
            orderedIds
        )
    }

    private fun userMessage(
        id: String,
        createAt: String,
        text: String
    ): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "type" to 1,
            "user" to 1,
            "content" to linkedMapOf(
                "id" to id,
                "text" to text
            ),
            "createAt" to createAt
        )
    }

    private fun assistantMessage(
        id: String,
        createAt: String,
        text: String,
        streamSeq: Long? = null,
        roundIndex: Int? = null,
        kind: String? = null
    ): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "type" to 1,
            "user" to 2,
            "content" to linkedMapOf(
                "id" to id,
                "text" to text
            ),
            "streamMeta" to streamMeta(streamSeq, roundIndex, kind, id),
            "createAt" to createAt
        )
    }

    private fun deepThinkingMessage(
        id: String,
        createAt: String,
        taskId: String,
        startTime: Long,
        thinking: String,
        streamSeq: Long? = null,
        roundIndex: Int? = null,
        kind: String? = null
    ): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "type" to 2,
            "user" to 3,
            "content" to linkedMapOf(
                "id" to id,
                "cardData" to linkedMapOf(
                    "type" to "deep_thinking",
                    "taskID" to taskId,
                    "thinkingContent" to thinking,
                    "startTime" to startTime,
                    "endTime" to (startTime + 1),
                    "stage" to 4,
                    "isLoading" to false
                )
            ),
            "streamMeta" to streamMeta(streamSeq, roundIndex, kind, taskId),
            "createAt" to createAt
        )
    }

    private fun toolMessage(
        id: String,
        createAt: String,
        taskId: String,
        summary: String,
        streamSeq: Long,
        roundIndex: Int,
        kind: String
    ): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "type" to 2,
            "user" to 3,
            "content" to linkedMapOf(
                "id" to id,
                "cardData" to linkedMapOf(
                    "type" to "agent_tool_summary",
                    "taskId" to taskId,
                    "summary" to summary
                )
            ),
            "streamMeta" to streamMeta(streamSeq, roundIndex, kind, taskId),
            "createAt" to createAt
        )
    }

    private fun streamMeta(
        streamSeq: Long?,
        roundIndex: Int?,
        kind: String?,
        taskId: String
    ): Map<String, Any?>? {
        if (streamSeq == null && roundIndex == null && kind == null) {
            return null
        }
        return linkedMapOf(
            "seq" to streamSeq,
            "roundIndex" to roundIndex,
            "kind" to kind,
            "parentTaskId" to taskId
        )
    }

    private fun localMillis(value: String): Long {
        return LocalDateTime.parse(value)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
