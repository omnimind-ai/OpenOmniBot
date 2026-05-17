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

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("runlog-store-test").toFile()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root
    }
}
