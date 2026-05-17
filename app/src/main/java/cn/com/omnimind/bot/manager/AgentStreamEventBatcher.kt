package cn.com.omnimind.bot.manager

import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects agent stream events and flushes them as a single batch on the next
 * display frame (vsync), aligned via [Choreographer].
 *
 * Each event that arrives between two vsync signals is coalesced into one
 * MethodChannel call, reducing IPC round-trips from O(n_events) to O(n_frames)
 * — at most 60 calls/sec regardless of LLM token rate.
 *
 * Thread-safe: [enqueue] may be called from any thread.
 */
class AgentStreamEventBatcher(
    private val onFlush: (List<Map<String, Any?>>) -> Unit,
) {
    private val pending = mutableListOf<Map<String, Any?>>()
    private val scheduled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun enqueue(payload: Map<String, Any?>) {
        synchronized(pending) { enqueueLocked(payload) }
        if (scheduled.compareAndSet(false, true)) {
            mainHandler.post {
                Choreographer.getInstance().postFrameCallback { flush() }
            }
        }
    }

    private fun enqueueLocked(payload: Map<String, Any?>) {
        if (!isCoalescableSnapshot(payload)) {
            pending.add(payload)
            return
        }
        val replaceIndex = pending.indexOfLast { existing ->
            isSameCoalescingKey(existing, payload)
        }
        if (replaceIndex >= 0) {
            pending.removeAt(replaceIndex)
        }
        pending.add(payload)
    }

    private fun flush() {
        scheduled.set(false)
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        Trace.beginSection("AgentStreamEventBatcher.flush")
        try {
            onFlush(batch)
        } finally {
            Trace.endSection()
        }
    }

    /** Flush any buffered events immediately without waiting for the next frame. */
    fun flushNow() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            flush()
        } else {
            mainHandler.post { flush() }
        }
    }

    private fun isCoalescableSnapshot(payload: Map<String, Any?>): Boolean {
        return when (payload["kind"]?.toString()) {
            "text_snapshot",
            "thinking_snapshot" -> true
            "tool_progress" -> payload["terminalOutputDelta"]?.toString().orEmpty().isEmpty()
            else -> false
        }
    }

    private fun isSameCoalescingKey(
        left: Map<String, Any?>,
        right: Map<String, Any?>
    ): Boolean {
        val leftEntryId = coalescingEntryId(left)
        val rightEntryId = coalescingEntryId(right)
        if (leftEntryId.isEmpty() || rightEntryId.isEmpty()) {
            return false
        }
        return left["kind"]?.toString() == right["kind"]?.toString() &&
            left["taskId"]?.toString() == right["taskId"]?.toString() &&
            leftEntryId == rightEntryId
    }

    private fun coalescingEntryId(payload: Map<String, Any?>): String {
        return listOf(
            payload["entryId"],
            payload["cardId"],
            payload["toolCallId"],
            payload["tool_call_id"],
            payload["callId"],
            payload["call_id"]
        ).firstNotNullOfOrNull { raw ->
            raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }
}
