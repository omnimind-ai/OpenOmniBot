package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.workspace.memory.LongTermMemoryIndex
import cn.com.omnimind.bot.agent.workspace.memory.TurnMemoryLoadTracker
import kotlinx.serialization.json.JsonObject

interface AgentExecutionEnvironment {
    val agentRunId: String
    val userMessage: String
    val currentPackageName: String?
    val runtimeContextRepository: AgentRuntimeContextRepository
    val workspaceDescriptor: AgentWorkspaceDescriptor
    val resolvedSkills: List<ResolvedSkillContext>
    val failureLearningSkill: ResolvedSkillContext?
    val workspaceManager: AgentWorkspaceManager
    val workspaceMemoryService: WorkspaceMemoryService
    val conversationMode: String
    val reasoningEffort: String?
    val terminalEnvironment: Map<String, String>
    val runControl: AgentRunControl

    /** Long-term memory slug index. Null when unavailable; tools handle gracefully. */
    val longTermMemoryIndex: LongTermMemoryIndex? get() = null

    /**
     * Tracks which memory ids/slugs have been loaded in the current turn so we
     * don't re-attach the same content twice. Null = treat all loads as new.
     */
    val turnMemoryLoadTracker: TurnMemoryLoadTracker? get() = null
}

data class DefaultAgentExecutionEnvironment(
    override val agentRunId: String,
    override val userMessage: String,
    override val currentPackageName: String?,
    override val runtimeContextRepository: AgentRuntimeContextRepository,
    override val workspaceDescriptor: AgentWorkspaceDescriptor,
    override val resolvedSkills: List<ResolvedSkillContext>,
    override val failureLearningSkill: ResolvedSkillContext? = null,
    override val workspaceManager: AgentWorkspaceManager,
    override val workspaceMemoryService: WorkspaceMemoryService,
    override val conversationMode: String,
    override val reasoningEffort: String? = null,
    override val terminalEnvironment: Map<String, String> = emptyMap(),
    override val runControl: AgentRunControl = NoOpAgentRunControl,
    override val longTermMemoryIndex: LongTermMemoryIndex? = null,
    override val turnMemoryLoadTracker: TurnMemoryLoadTracker? = null
) : AgentExecutionEnvironment

interface AgentToolCatalog {
    val toolsForModel: List<ChatCompletionTool>

    fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor

    fun validateArguments(toolName: String, arguments: JsonObject)
}

interface AgentToolExecutor {
    suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult

    suspend fun dispose() = Unit
}
