package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject

interface AgentExecutionEnvironment {
    val agentRunId: String
    val userMessage: String
    val attachments: List<Map<String, Any?>>
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
}

data class DefaultAgentExecutionEnvironment(
    override val agentRunId: String,
    override val userMessage: String,
    override val attachments: List<Map<String, Any?>> = emptyList(),
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
    override val runControl: AgentRunControl = NoOpAgentRunControl
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
