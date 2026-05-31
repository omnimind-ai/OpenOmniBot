package cn.com.omnimind.bot.runlog

/**
 * Tracks phase timing for Function calls made through the OmniFlow toolkit.
 *
 * The toolkit decides which phases to measure; this class owns the timing
 * payload shape and merge behavior so facade code stays focused on routing.
 */
class OobFunctionCallTiming {
    private val startedAtNanos = System.nanoTime()
    val startedAtMs: Long = System.currentTimeMillis()
    private val phases = linkedMapOf<String, Long>()

    fun <T> measure(phaseName: String, block: () -> T): T {
        val phaseStartedAtNanos = System.nanoTime()
        return try {
            block()
        } finally {
            phases[phaseName] = elapsedMs(phaseStartedAtNanos)
        }
    }

    suspend fun <T> measureSuspend(phaseName: String, block: suspend () -> T): T {
        val phaseStartedAtNanos = System.nanoTime()
        return try {
            block()
        } finally {
            phases[phaseName] = elapsedMs(phaseStartedAtNanos)
        }
    }

    fun attachTo(payload: Map<String, Any?>): Map<String, Any?> {
        val callTiming = finish()
        val mergedTiming = linkedMapOf<String, Any?>().apply {
            putAll(mapArg(payload["timing"]))
            put("call_started_at_ms", callTiming["started_at_ms"])
            put("call_finished_at_ms", callTiming["finished_at_ms"])
            put("call_duration_ms", callTiming["duration_ms"])
            put("call_phase_ms", callTiming["phase_ms"])
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("timing", mergedTiming)
        }
    }

    private fun finish(): Map<String, Any?> {
        val finishedAtMs = System.currentTimeMillis()
        val completedPhases = linkedMapOf<String, Long>()
        listOf(
            "guard_check_ms",
            "execute_function_ms",
        ).forEach { phaseName ->
            completedPhases[phaseName] = phases[phaseName] ?: 0L
        }
        phases.forEach { (phaseName, durationMs) ->
            completedPhases.putIfAbsent(phaseName, durationMs)
        }
        return linkedMapOf(
            "source" to "oob_function_call",
            "started_at_ms" to startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "duration_ms" to elapsedMs(startedAtNanos),
            "phase_ms" to completedPhases,
        )
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }
}
