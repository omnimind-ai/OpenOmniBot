package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.bot.agent.workspace.memory.TurnMemoryLoadTracker
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive

/**
 * Spawns and supervises real subagents.
 *
 * Each task gets:
 *  - its own AgentOrchestrator instance with a filtered tool catalog
 *    ([SubagentToolCatalogView]) so it can only use tools allowed by its profile
 *  - its own [TurnMemoryLoadTracker] so subagent loads don't leak into the
 *    parent's same-turn dedup
 *  - hard caps on rounds (per profile, default 12) and output tokens (4096)
 *
 * Parent cancellation propagates naturally through structured concurrency:
 * if the parent's tool call is cancelled, [supervisorScope] tears down every
 * in-flight subagent's coroutine.
 *
 * NOTE: We deliberately reuse the parent's existing [AgentToolExecutor]
 * router rather than constructing a new one — the router is stateless per
 * call, so this saves resources. The lazy provider is required because
 * SubagentToolHandler → SubagentDispatcher → router is a construction-time
 * cycle that we break with deferred lookup.
 */
class SubagentDispatcher(
    private val llmClient: AgentLlmClient,
    private val toolExecutorProvider: () -> AgentToolExecutor,
    private val parentCatalogProvider: () -> AgentToolCatalog,
    private val eventAdapter: AgentEventAdapter,
    private val model: String
) {

    data class SubagentTaskSpec(
        val profileId: String,
        val instruction: String,
        val budgetRounds: Int? = null
    )

    data class SubagentRunResult(
        val subagentId: String,
        val profileId: String,
        val taskIndex: Int,
        val status: String,
        val finalContent: String,
        val toolCallSummaries: List<String>,
        val errorMessage: String? = null
    )

    suspend fun dispatch(
        parentEnv: AgentExecutionEnvironment,
        tasks: List<SubagentTaskSpec>,
        concurrency: Int
    ): List<SubagentRunResult> {
        if (tasks.isEmpty()) return emptyList()
        val limit = concurrency.coerceIn(1, 6)
        val semaphore = Semaphore(limit)
        return supervisorScope {
            tasks.mapIndexed { index, spec ->
                async {
                    semaphore.withPermit {
                        runSingleSubagent(parentEnv, index, spec)
                    }
                }
            }.awaitAll().sortedBy { it.taskIndex }
        }
    }

    private suspend fun runSingleSubagent(
        parentEnv: AgentExecutionEnvironment,
        taskIndex: Int,
        spec: SubagentTaskSpec
    ): SubagentRunResult {
        val profile = SubagentProfileRegistry.get(spec.profileId)
        val subagentId = "subagent-${UUID.randomUUID().toString().take(8)}"
        return try {
            val filteredCatalog = SubagentToolCatalogView(
                parent = parentCatalogProvider(),
                allowed = profile.allowedTools
            )
            val systemMessage = ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive(profile.systemPrompt)
            )
            val userMessage = ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(spec.instruction)
            )
            val subEnv = DefaultAgentExecutionEnvironment(
                agentRunId = subagentId,
                userMessage = spec.instruction,
                currentPackageName = parentEnv.currentPackageName,
                runtimeContextRepository = parentEnv.runtimeContextRepository,
                workspaceDescriptor = parentEnv.workspaceDescriptor,
                resolvedSkills = emptyList(),
                failureLearningSkill = null,
                workspaceManager = parentEnv.workspaceManager,
                workspaceMemoryService = parentEnv.workspaceMemoryService,
                conversationMode = parentEnv.conversationMode,
                reasoningEffort = parentEnv.reasoningEffort,
                terminalEnvironment = parentEnv.terminalEnvironment,
                runControl = NoOpAgentRunControl,
                longTermMemoryIndex = parentEnv.longTermMemoryIndex,
                turnMemoryLoadTracker = TurnMemoryLoadTracker()
            )
            val silentCallback = SilentSubagentCallback()
            val orchestrator = AgentOrchestrator(
                llmClient = llmClient,
                toolRegistry = filteredCatalog,
                toolRouter = toolExecutorProvider(),
                eventAdapter = eventAdapter,
                model = model
            )
            val result = orchestrator.run(
                AgentOrchestrator.Input(
                    callback = silentCallback,
                    initialMessages = listOf(systemMessage, userMessage),
                    executionEnv = subEnv,
                    conversationId = null,
                    contextCompactor = null
                )
            )
            when (result) {
                is AgentResult.Success -> SubagentRunResult(
                    subagentId = subagentId,
                    profileId = profile.id,
                    taskIndex = taskIndex,
                    status = "completed",
                    finalContent = result.response.content,
                    toolCallSummaries = silentCallback.toolSummaries()
                )
                is AgentResult.Error -> SubagentRunResult(
                    subagentId = subagentId,
                    profileId = profile.id,
                    taskIndex = taskIndex,
                    status = "failed",
                    finalContent = "",
                    toolCallSummaries = silentCallback.toolSummaries(),
                    errorMessage = result.message
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            SubagentRunResult(
                subagentId = subagentId,
                profileId = profile.id,
                taskIndex = taskIndex,
                status = "failed",
                finalContent = "",
                toolCallSummaries = emptyList(),
                errorMessage = e.message ?: "subagent execution failed"
            )
        }
    }
}

/**
 * Callback that swallows subagent streaming output (we don't want subagent
 * intermediate text leaking into the parent's chat stream) while capturing
 * tool-call names so the dispatcher can summarize what the subagent did.
 */
private class SilentSubagentCallback : AgentCallback {
    private val tools = mutableListOf<String>()

    fun toolSummaries(): List<String> = tools.toList()

    override suspend fun onThinkingStart() = Unit
    override suspend fun onThinkingUpdate(thinking: String) = Unit
    override suspend fun onToolCallStart(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject
    ) {
        tools.add(toolName)
    }

    override suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?>
    ) = Unit

    override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) = Unit
    override suspend fun onChatMessage(message: String) = Unit
    override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
    override suspend fun onComplete(result: AgentResult) = Unit
    override suspend fun onError(error: String) = Unit
    override suspend fun onPermissionRequired(missing: List<String>) = Unit
}
