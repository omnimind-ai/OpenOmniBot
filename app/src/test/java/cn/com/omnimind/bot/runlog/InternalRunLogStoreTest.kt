package cn.com.omnimind.bot.runlog

import android.content.Context
import android.content.ContextWrapper
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalRunLogStoreTest {
    @Test
    fun `timeline applies append only running card events after snapshot`() {
        val context = TempFilesContext()
        try {
            val runId = "run-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Replay append events",
                source = "agent",
                toolName = "agent"
            )
            InternalRunLogStore.upsertCard(
                context = context,
                runId = runId,
                cardId = "card-1",
                card = runningCard("card-1", "first")
            )

            val firstTimeline = InternalRunLogStore.timelinePayload(context, runId)
            val eventLog = File(firstTimeline["event_log_path"]?.toString().orEmpty())
            val firstEventCount = eventLog.readLines().size
            assertTrue(firstEventCount >= 2)

            InternalRunLogStore.upsertCard(
                context = context,
                runId = runId,
                cardId = "card-1",
                card = runningCard("card-1", "second")
            )

            val secondTimeline = InternalRunLogStore.timelinePayload(context, runId)
            val cards = secondTimeline["cards"] as List<*>
            val card = cards.single() as Map<*, *>
            assertEquals("second", card["summary"])
            assertTrue(eventLog.readLines().size > firstEventCount)
            assertTrue((secondTimeline["event_seq"] as Number).toLong() >= 3L)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `timeline reports aggregate token usage`() {
        val context = TempFilesContext()
        try {
            val runId = "run-token-${System.nanoTime()}"
            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "Token usage",
                source = "vlm",
                toolName = "vlm"
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = tokenCard("card-1", prompt = 10, completion = 4, total = 14)
            )
            InternalRunLogStore.appendCard(
                context = context,
                runId = runId,
                card = tokenCard("card-2", prompt = 20, completion = 6, total = 26)
            )

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            val usage = timeline["token_usage"] as Map<*, *>
            val byStep = timeline["token_usage_by_step"] as List<*>

            assertEquals(30L, usage["prompt_tokens"])
            assertEquals(10L, usage["completion_tokens"])
            assertEquals(40L, usage["total_tokens"])
            assertEquals(40L, timeline["token_usage_total"])
            assertEquals(2, byStep.size)
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun runningCard(cardId: String, summary: String): Map<String, Any?> {
        return linkedMapOf(
            "card_id" to cardId,
            "tool_name" to "browser_use",
            "summary" to summary,
            "status" to "running",
            "header" to linkedMapOf(
                "status" to "running",
                "success" to true
            )
        )
    }

    private fun tokenCard(
        cardId: String,
        prompt: Int,
        completion: Int,
        total: Int
    ): Map<String, Any?> {
        return linkedMapOf(
            "card_id" to cardId,
            "tool_name" to "click",
            "step_index" to 0,
            "token_usage" to linkedMapOf(
                "prompt_tokens" to prompt,
                "completion_tokens" to completion,
                "total_tokens" to total,
                "attempt_count" to 1
            )
        )
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("runlog-store-test").toFile()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root
    }
}
