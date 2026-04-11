package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class AgentConversationHistorySupportTest {
    @Test
    fun `mergeToolPayload keeps args and final status across tool lifecycle`() {
        val startPayload = mapOf(
            "toolName" to "browser_use",
            "displayName" to "浏览器自动化",
            "toolType" to "builtin",
            "argsJson" to """{"url":"https://example.com","steps":2}""",
            "summary" to "打开页面"
        )
        val progressPayload = mapOf(
            "progress" to "正在分析页面",
            "summary" to "正在分析页面"
        )
        val completePayload = mapOf(
            "status" to AgentConversationHistoryRepository.STATUS_SUCCESS,
            "summary" to "已完成页面分析",
            "resultPreviewJson" to """{"message":"done"}""",
            "rawResultJson" to """{"message":"done","details":"very long raw"}""",
            "success" to true
        )

        val mergedProgress = AgentConversationHistorySupport.mergeToolPayload(
            existing = startPayload,
            incoming = progressPayload,
            fallbackStatus = AgentConversationHistoryRepository.STATUS_RUNNING,
            fallbackSummary = "正在调用工具"
        )
        val mergedComplete = AgentConversationHistorySupport.mergeToolPayload(
            existing = mergedProgress,
            incoming = completePayload,
            fallbackStatus = AgentConversationHistoryRepository.STATUS_SUCCESS,
            fallbackSummary = "已完成页面分析"
        )

        assertEquals(
            """{"url":"https://example.com","steps":2}""",
            mergedComplete["argsJson"]
        )
        assertEquals(
            AgentConversationHistoryRepository.STATUS_SUCCESS,
            mergedComplete["status"]
        )
        assertEquals("已完成页面分析", mergedComplete["summary"])
        assertEquals("""{"message":"done"}""", mergedComplete["resultPreviewJson"])
    }

    @Test
    fun `mergeToolPayload preserves timeout metadata`() {
        val runningPayload = mapOf(
            "toolName" to "terminal_execute",
            "displayName" to "终端执行",
            "toolType" to "terminal",
            "status" to AgentConversationHistoryRepository.STATUS_RUNNING,
            "terminalOutput" to "hello\n"
        )
        val timeoutPayload = mapOf(
            "status" to AgentConversationHistoryRepository.STATUS_TIMEOUT,
            "summary" to "终端命令等待超时，可能仍在后台继续运行。",
            "timedOut" to true,
            "terminalOutputDelta" to "world\n"
        )

        val merged = AgentConversationHistorySupport.mergeToolPayload(
            existing = runningPayload,
            incoming = timeoutPayload,
            fallbackStatus = AgentConversationHistoryRepository.STATUS_TIMEOUT,
            fallbackSummary = "终端命令等待超时，可能仍在后台继续运行。"
        )

        assertEquals(
            AgentConversationHistoryRepository.STATUS_TIMEOUT,
            merged["status"]
        )
        assertEquals(true, merged["timedOut"])
        assertEquals("hello\nworld\n", merged["terminalOutput"])
    }

    @Test
    fun `buildPromptSeedFromEntries replays per-tool summaries in chronological order`() {
        val userEntry = AgentConversationEntry(
            id = 1,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "u1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "查看 example",
            payloadJson = """
                {"id":"u1","type":1,"user":1,"content":{"text":"查看 example","id":"u1"},"createAt":"2026-03-27T00:00:00Z"}
            """.trimIndent(),
            createdAt = 1,
            updatedAt = 1
        )
        val assistantEntry = AgentConversationEntry(
            id = 2,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "a1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "assistant should start being replayed",
            payloadJson = """
                {"id":"a1","type":1,"user":2,"content":{"text":"assistant should start being replayed","id":"a1"},"createAt":"2026-03-27T00:00:01Z"}
            """.trimIndent(),
            createdAt = 2,
            updatedAt = 2
        )
        val toolEntry = AgentConversationEntry(
            id = 3,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "t1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "抓取成功",
            payloadJson = """
                {
                  "toolName":"browser_use",
                  "displayName":"浏览器自动化",
                  "toolType":"builtin",
                  "argsJson":"{\"url\":\"https://example.com\",\"query\":\"latest\"}",
                  "summary":"抓取成功",
                  "resultPreviewJson":"{\"title\":\"Example\"}",
                  "rawResultJson":"{\"title\":\"Example\",\"html\":\"<html>super long raw payload</html>\"}",
                  "success":true
                }
            """.trimIndent(),
            createdAt = 3,
            updatedAt = 3
        )

        val secondToolEntry = AgentConversationEntry(
            id = 4,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "t2",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_ERROR,
            summary = "执行命令失败",
            payloadJson = """
                {
                  "toolName":"terminal_execute",
                  "displayName":"执行命令",
                  "toolType":"terminal",
                  "argsJson":"{\"command\":\"pwd\"}",
                  "summary":"执行命令失败",
                  "resultPreviewJson":"{\"message\":\"permission denied\"}",
                  "rawResultJson":"{\"message\":\"permission denied\",\"trace\":\"super long raw payload terminal\"}",
                  "terminalOutput":"permission denied",
                  "success":false
                }
            """.trimIndent(),
            createdAt = 4,
            updatedAt = 4
        )

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(
            listOf(userEntry, assistantEntry, toolEntry, secondToolEntry)
        )

        assertEquals(6, seed.historyMessages.size)
        assertEquals(
            listOf("user", "assistant", "assistant", "tool", "assistant", "tool"),
            seed.historyMessages.map { it.role }
        )
        assertTrue(seed.historyMessages[0].content.toString().contains("查看 example"))
        assertEquals(1, seed.historyMessages[2].toolCalls?.size)
        assertEquals("browser_use", seed.historyMessages[2].toolCalls?.single()?.function?.name)
        assertTrue(
            seed.historyMessages[2].toolCalls
                ?.single()
                ?.function
                ?.arguments
                .orEmpty()
                .contains("\"url\":\"https://example.com\"")
        )
        assertEquals("terminal_execute", seed.historyMessages[4].toolCalls?.single()?.function?.name)

        val firstToolSummary = seed.historyMessages[3].content!!.jsonPrimitive.content
        assertTrue(firstToolSummary.contains("浏览器自动化"))
        assertTrue(firstToolSummary.contains("抓取成功"))
        assertTrue(firstToolSummary.contains("previewJson"))

        val secondToolSummary = seed.historyMessages[5].content!!.jsonPrimitive.content
        assertTrue(secondToolSummary.contains("执行命令"))
        assertTrue(secondToolSummary.contains("执行命令失败"))
        assertTrue(secondToolSummary.contains("terminalOutput"))

        val allReplayText = seed.historyMessages.joinToString("\n") {
            it.content?.toString().orEmpty()
        }
        assertTrue(allReplayText.contains("assistant should start being replayed"))
        assertFalse(allReplayText.contains("super long raw payload"))
    }

    @Test
    fun `normalizeInterruptedEntries converts running tools to interrupted`() {
        val runningEntry = AgentConversationEntry(
            id = 1,
            conversationId = 9,
            conversationMode = "subagent",
            entryId = "tool-running",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_RUNNING,
            summary = "",
            payloadJson = """
                {"toolName":"terminal_run","displayName":"执行命令","toolType":"terminal","status":"running","summary":"","terminalOutput":"hello"}
            """.trimIndent(),
            createdAt = 1,
            updatedAt = 1
        )

        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(
            listOf(runningEntry)
        )

        assertEquals(1, normalized.size)
        assertEquals(
            AgentConversationHistoryRepository.STATUS_INTERRUPTED,
            normalized.single().status
        )
        assertTrue(normalized.single().summary.isNotBlank())
        assertTrue(normalized.single().payloadJson.contains("\"status\":\"interrupted\""))
    }

    @Test
    fun `normalizeInterruptedEntries finalizes lone thinking card during restore`() {
        val thinkingEntry = AgentConversationEntry(
            id = 1,
            conversationId = 9,
            conversationMode = "normal",
            entryId = "task-1-thinking",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "",
            payloadJson = """
                {
                  "id":"task-1-thinking",
                  "type":2,
                  "user":3,
                  "content":{
                    "id":"task-1-thinking",
                    "cardData":{
                      "type":"deep_thinking",
                      "taskID":"task-1",
                      "thinkingContent":"正在分析",
                      "startTime":1000,
                      "endTime":null,
                      "stage":1,
                      "isLoading":true
                    }
                  },
                  "isLoading":false,
                  "isFirst":false,
                  "isError":false,
                  "isSummarizing":false,
                  "createAt":"2026-03-27T00:00:01Z"
                }
            """.trimIndent(),
            createdAt = 1000,
            updatedAt = 1500
        )

        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(
            entries = listOf(thinkingEntry),
            finalizeLatestThinkingEntries = true
        )

        assertEquals(1, normalized.size)
        assertTrue(normalized.single().payloadJson.contains("\"stage\":4"))
        assertTrue(normalized.single().payloadJson.contains("\"isLoading\":false"))
        assertTrue(normalized.single().payloadJson.contains("\"endTime\":1500"))
    }

    @Test
    fun `buildPromptSeedFromEntries prepends context summary and skips entries before cutoff`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "旧问题"),
            buildAssistantEntry(id = 2, entryId = "a1", text = "旧回答"),
            buildUserEntry(id = 3, entryId = "u2", text = "新问题"),
            buildAssistantEntry(id = 4, entryId = "a2", text = "新回答")
        )

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(
            entries = entries,
            contextSummary = """
                【用户目标与约束】
                - 保留旧需求
            """.trimIndent(),
            cutoffEntryDbId = 2
        )

        assertEquals(3, seed.historyMessages.size)
        assertEquals("system", seed.historyMessages.first().role)
        assertTrue(seed.historyMessages.first().content!!.jsonPrimitive.content.contains("压缩总结"))
        assertEquals("user", seed.historyMessages[1].role)
        assertEquals("新问题", seed.historyMessages[1].content!!.jsonPrimitive.content)
        assertEquals("assistant", seed.historyMessages[2].role)
        assertEquals("新回答", seed.historyMessages[2].content!!.jsonPrimitive.content)
    }

    @Test
    fun `buildPromptSeedFromEntries keeps all entries after cutoff without takeLast truncation`() {
        val entries = (1L..25L).map { index ->
            buildUserEntry(
                id = index,
                entryId = "u$index",
                text = "message-$index"
            )
        }

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(entries)

        assertEquals(25, seed.historyMessages.size)
        assertEquals("message-1", seed.historyMessages.first().content!!.jsonPrimitive.content)
        assertEquals("message-25", seed.historyMessages.last().content!!.jsonPrimitive.content)
    }

    @Test
    fun `selectEntriesToCompact stops before latest user message`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "第一轮问题"),
            buildAssistantEntry(id = 2, entryId = "a1", text = "第一轮回答"),
            buildToolEntry(id = 3, entryId = "t1", toolName = "browser_use", summary = "第一轮工具"),
            buildUserEntry(id = 4, entryId = "u2", text = "第二轮问题")
        )

        val selection = AgentConversationHistorySupport.selectEntriesToCompact(entries)

        assertEquals(listOf(1L, 2L, 3L), selection?.entriesToCompact?.map { it.id })
        assertEquals(3L, selection?.cutoffEntryDbId)
    }

    @Test
    fun `selectEntriesToCompact respects existing cutoff and skips when no complete previous round`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "第一轮问题"),
            buildAssistantEntry(id = 2, entryId = "a1", text = "第一轮回答"),
            buildUserEntry(id = 3, entryId = "u2", text = "第二轮问题")
        )

        val selectionAfterCutoff = AgentConversationHistorySupport.selectEntriesToCompact(
            entries = entries,
            cutoffEntryDbId = 2
        )
        val selectionWithoutOlderRound = AgentConversationHistorySupport.selectEntriesToCompact(
            entries = listOf(
                buildUserEntry(id = 11, entryId = "u11", text = "只有当前轮")
            )
        )

        assertNull(selectionAfterCutoff)
        assertNull(selectionWithoutOlderRound)
    }

    @Test
    fun `buildPromptRelevantMessages replays task assistant content after tool results in same round`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "task-1-user", text = "请检查页面"),
            buildAssistantEntry(
                id = 2,
                entryId = "task-1-assistant",
                text = "页面标题是 Example"
            ),
            buildToolEntry(
                id = 3,
                entryId = "task-1-tool-1",
                toolName = "browser_use",
                summary = "抓取成功"
            ),
            buildUserEntry(id = 4, entryId = "task-2-user", text = "继续下一步")
        )

        val messages = AgentConversationHistorySupport.buildPromptRelevantMessages(entries)

        assertEquals(
            listOf("user", "assistant", "tool", "assistant", "user"),
            messages.map { it.role }
        )
        assertEquals("browser_use", messages[1].toolCalls?.single()?.function?.name)
        assertEquals("页面标题是 Example", messages[3].content!!.jsonPrimitive.content)
        assertEquals("继续下一步", messages[4].content!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRuntimeCompactionWindow uses current summary and only compacts raw history before latest user`() {
        val messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive("main system")
            ),
            AgentConversationHistorySupport.buildContextSummarySystemMessage(
                "【用户目标与约束】\n旧总结"
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("旧问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("旧回答")
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("当前问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("当前轮中间输出")
            )
        )

        val window = AgentConversationHistorySupport.buildRuntimeCompactionWindow(messages)

        assertEquals("【用户目标与约束】\n旧总结", window?.existingSummary)
        assertEquals(listOf("user", "assistant"), window?.messagesToCompact?.map { it.role })
        assertEquals("旧问题", window?.messagesToCompact?.first()?.content?.jsonPrimitive?.content)
        assertEquals("旧回答", window?.messagesToCompact?.get(1)?.content?.jsonPrimitive?.content)
    }

    @Test
    fun `rebuildMessagesWithCompactedSummary keeps minimal runtime context plus pending tool calls`() {
        val pendingToolCalls = listOf(
            AssistantToolCall(
                id = "tool-call-1",
                function = AssistantToolCallFunction(
                    name = "browser_use",
                    arguments = """{"url":"https://example.com"}"""
                )
            )
        )
        val messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive("main system")
            ),
            AgentConversationHistorySupport.buildContextSummarySystemMessage(
                "【用户目标与约束】\n旧总结"
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("当前问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("不应保留的中间文本"),
                toolCalls = pendingToolCalls
            )
        )

        val rebuilt = AgentConversationHistorySupport.rebuildMessagesWithCompactedSummary(
            messages = messages,
            summary = "【用户目标与约束】\n新总结"
        )

        assertEquals(listOf("system", "system", "user", "assistant"), rebuilt.map { it.role })
        assertEquals("main system", rebuilt[0].content!!.jsonPrimitive.content)
        assertTrue(rebuilt[1].content!!.jsonPrimitive.content.contains("新总结"))
        assertEquals("当前问题", rebuilt[2].content!!.jsonPrimitive.content)
        assertNull(rebuilt[3].content)
        assertEquals("browser_use", rebuilt[3].toolCalls?.single()?.function?.name)
    }

    private fun buildUserEntry(id: Long, entryId: String, text: String): AgentConversationEntry {
        return AgentConversationEntry(
            id = id,
            conversationId = 1,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = text,
            payloadJson = """
                {"id":"$entryId","type":1,"user":1,"content":{"text":"$text","id":"$entryId"},"createAt":"2026-03-27T00:00:00Z"}
            """.trimIndent(),
            createdAt = id,
            updatedAt = id
        )
    }

    private fun buildAssistantEntry(id: Long, entryId: String, text: String): AgentConversationEntry {
        return AgentConversationEntry(
            id = id,
            conversationId = 1,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = text,
            payloadJson = """
                {"id":"$entryId","type":1,"user":2,"content":{"text":"$text","id":"$entryId"},"createAt":"2026-03-27T00:00:01Z"}
            """.trimIndent(),
            createdAt = id,
            updatedAt = id
        )
    }

    private fun buildToolEntry(
        id: Long,
        entryId: String,
        toolName: String,
        summary: String
    ): AgentConversationEntry {
        return AgentConversationEntry(
            id = id,
            conversationId = 1,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = summary,
            payloadJson = """
                {
                  "toolName":"$toolName",
                  "displayName":"$toolName",
                  "toolType":"builtin",
                  "argsJson":"{}",
                  "summary":"$summary",
                  "success":true
                }
            """.trimIndent(),
            createdAt = id,
            updatedAt = id
        )
    }
}
