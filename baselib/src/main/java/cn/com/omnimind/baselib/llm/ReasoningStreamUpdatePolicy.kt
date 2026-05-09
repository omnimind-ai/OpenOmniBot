package cn.com.omnimind.baselib.llm

object ReasoningStreamUpdatePolicy {
    const val DEFAULT_INTERVAL_MS = 300L

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
        val remaining = intervalMs - elapsed
        return if (remaining > 0L) remaining else 0L
    }
}
