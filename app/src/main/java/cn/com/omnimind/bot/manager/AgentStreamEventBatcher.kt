package cn.com.omnimind.bot.manager

import android.os.Handler
import android.os.Looper
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
        synchronized(pending) { pending.add(payload) }
        if (scheduled.compareAndSet(false, true)) {
            mainHandler.post {
                Choreographer.getInstance().postFrameCallback { flush() }
            }
        }
    }

    private fun flush() {
        scheduled.set(false)
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        onFlush(batch)
    }

    /** Flush any buffered events immediately without waiting for the next frame. */
    fun flushNow() {
        mainHandler.post { flush() }
    }
}
