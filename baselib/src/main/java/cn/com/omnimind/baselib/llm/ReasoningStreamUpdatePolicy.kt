package cn.com.omnimind.baselib.llm

object ReasoningStreamUpdatePolicy {
    const val DEFAULT_INTERVAL_MS = 300L

    /**
     * Computes the next delay (in ms) before re-emitting reasoning content during streaming.
     * Returns 0 if the snapshot may be emitted immediately, otherwise a positive delay.
     */
    fun nextDelayMs(
        hasEmittedBefore: Boolean,
        lastEmitAtMs: Long,
        nowMs: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ): Long {
        if (!hasEmittedBefore) {
            return 0L
        }
        val elapsed = nowMs - lastEmitAtMs
        if (elapsed >= intervalMs) {
            return 0L
        }
        return intervalMs - elapsed
    }
}
