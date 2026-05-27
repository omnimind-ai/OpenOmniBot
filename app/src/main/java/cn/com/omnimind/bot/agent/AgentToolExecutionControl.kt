package cn.com.omnimind.bot.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

data class AgentToolProgressSnapshot(
    val summary: String = "",
    val extras: Map<String, Any?> = emptyMap()
)

class ManualToolStopCancellationException(
    message: String = "Tool execution stopped manually"
) : CancellationException(message)

interface AgentRunControl {
    fun beginToolExecution(
        toolName: String,
        toolCallId: String
    ): AgentToolExecutionHandle
}

interface AgentToolExecutionHandle {
    val runId: String?
        get() = null

    val generation: Long
    val toolName: String
    val toolCallId: String

    fun bindCardId(cardId: String)

    fun currentCardId(): String?

    fun bindExecutionJob(job: Job)

    fun bindStopAction(action: (suspend () -> Unit)?)

    fun recordProgress(summary: String, extras: Map<String, Any?> = emptyMap())

    fun latestProgressSnapshot(): AgentToolProgressSnapshot

    fun isManualStopRequested(): Boolean

    fun throwIfStopRequested()

    fun complete()
}

object NoOpAgentRunControl : AgentRunControl {
    override fun beginToolExecution(
        toolName: String,
        toolCallId: String
    ): AgentToolExecutionHandle {
        return NoOpAgentToolExecutionHandle(toolName = toolName, toolCallId = toolCallId)
    }
}

private class NoOpAgentToolExecutionHandle(
    override val toolName: String,
    override val toolCallId: String
) : AgentToolExecutionHandle {
    override val generation: Long = 0L

    override fun bindCardId(cardId: String) = Unit

    override fun currentCardId(): String? = null

    override fun bindExecutionJob(job: Job) = Unit

    override fun bindStopAction(action: (suspend () -> Unit)?) = Unit

    override fun recordProgress(summary: String, extras: Map<String, Any?>) = Unit

    override fun latestProgressSnapshot(): AgentToolProgressSnapshot = AgentToolProgressSnapshot()

    override fun isManualStopRequested(): Boolean = false

    override fun throwIfStopRequested() = Unit

    override fun complete() = Unit
}
