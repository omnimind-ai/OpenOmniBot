package cn.com.omnimind.assists

/**
 * Tracks the native floating UI lifetime for local OmniFlow replay.
 *
 * OmniFlow replay does not create a VLMOperationTask, but users still need the
 * same visible execution surface and a reliable stop signal while local actions
 * are being dispatched.
 */
object OmniFlowUiSession {
    data class EndResult(
        val wasActive: Boolean,
        val stopRequested: Boolean,
        val completeRequested: Boolean
    )

    private data class SessionState(
        val runId: String,
        var active: Boolean = false,
        var stopRequested: Boolean = false,
        var completeRequested: Boolean = false,
        var onStopRequested: (() -> Unit)? = null,
        var onCompleteRequested: (() -> Unit)? = null,
        val taskIds: LinkedHashSet<String> = linkedSetOf(),
        val activeTaskIds: LinkedHashSet<String> = linkedSetOf()
    )

    private val lock = Any()
    private val sessionsByRunId = linkedMapOf<String, SessionState>()
    private val runIdByTaskId = linkedMapOf<String, String>()

    fun registerRun(
        runId: String,
        onStopRequested: () -> Unit,
        onCompleteRequested: (() -> Unit)? = null
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        synchronized(lock) {
            val state = sessionsByRunId.getOrPut(normalizedRunId) {
                SessionState(runId = normalizedRunId)
            }
            state.onStopRequested = onStopRequested
            state.onCompleteRequested = onCompleteRequested
        }
    }

    fun beginTask(runId: String?, taskId: String?) {
        val normalizedRunId = runId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val state = sessionsByRunId.getOrPut(normalizedRunId) {
                SessionState(runId = normalizedRunId)
            }
            state.active = true
            state.taskIds.add(normalizedTaskId)
            state.activeTaskIds.add(normalizedTaskId)
            runIdByTaskId[normalizedTaskId] = normalizedRunId
        }
    }

    fun endTask(taskId: String?) {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val state = runIdByTaskId[normalizedTaskId]?.let { sessionsByRunId[it] } ?: return
            state.activeTaskIds.remove(normalizedTaskId)
        }
    }

    fun hasActiveSession(): Boolean {
        return synchronized(lock) {
            sessionsByRunId.values.any { it.active && !it.stopRequested && !it.completeRequested }
        }
    }

    fun requestStopActiveSession(): Boolean = requestStopSession(null)

    fun requestStopSession(runOrTaskId: String?): Boolean {
        val normalizedId = runOrTaskId?.trim().orEmpty()
        val callbacks = synchronized(lock) {
            val candidates = if (normalizedId.isEmpty()) {
                sessionsByRunId.values.toList()
            } else {
                val runId = if (sessionsByRunId.containsKey(normalizedId)) {
                    normalizedId
                } else {
                    val ownerRunId = runIdByTaskId[normalizedId]
                    ownerRunId?.takeIf { runId ->
                        sessionsByRunId[runId]?.activeTaskIds?.contains(normalizedId) == true
                    }
                }
                runId?.let { sessionsByRunId[it] }?.let(::listOf).orEmpty()
            }
            candidates
                .filter { it.active && !it.completeRequested && !it.stopRequested }
                .onEach { it.stopRequested = true }
                .mapNotNull { it.onStopRequested }
        }
        callbacks.forEach { runCatching { it.invoke() } }
        return callbacks.isNotEmpty()
    }

    fun requestCompleteActiveSession(): Boolean {
        val callbacks = synchronized(lock) {
            sessionsByRunId.values
                .filter { it.active && !it.stopRequested && !it.completeRequested }
                .onEach { it.completeRequested = true }
                .mapNotNull { it.onCompleteRequested ?: it.onStopRequested }
        }
        callbacks.forEach { runCatching { it.invoke() } }
        return callbacks.isNotEmpty()
    }

    fun endRun(runId: String): EndResult {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return EndResult(
                wasActive = false,
                stopRequested = false,
                completeRequested = false
            )
        }
        return synchronized(lock) {
            val state = sessionsByRunId.remove(normalizedRunId)
                ?: return@synchronized EndResult(
                    wasActive = false,
                    stopRequested = false,
                    completeRequested = false
                )
            state.taskIds.forEach { runIdByTaskId.remove(it) }
            EndResult(
                wasActive = state.active,
                stopRequested = state.stopRequested,
                completeRequested = state.completeRequested
            )
        }
    }
}
