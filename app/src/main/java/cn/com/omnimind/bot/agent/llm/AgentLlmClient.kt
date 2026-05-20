package cn.com.omnimind.bot.agent

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage

/**
 * Agent-facing LLM client interface. Typed on LangChain4j primitives
 * (`ChatMessage`, `ToolSpecification`, `AiMessage`) — the legacy
 * `ChatCompletionRequest` / `ChatCompletionTurn` shapes are no longer part
 * of this contract.
 *
 * Production implementation:
 * [cn.com.omnimind.bot.agent.langchain4j.LangChain4jAgentLlmClient].
 */
interface AgentLlmClient {
    suspend fun streamTurn(
        scene: String,
        messages: List<ChatMessage>,
        toolSpecifications: List<ToolSpecification> = emptyList(),
        modelOverride: AgentModelOverride? = null,
        reasoningEffort: String? = null,
        toolChoice: AgentToolChoice = AgentToolChoice.Auto,
        parallelToolCalls: Boolean = false,
        maxCompletionTokens: Int = 16384,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
        onContentUpdate: (suspend (String) -> Unit)? = null
    ): AgentLlmTurn
}

/**
 * The result of one streaming chat-completion turn — equivalent to the
 * pre-LangChain4j `ChatCompletionTurn` but typed on LangChain4j's
 * [AiMessage] (which carries text + tool execution requests + thinking).
 */
data class AgentLlmTurn(
    val aiMessage: AiMessage,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
    val reasoning: String
)

/**
 * `tool_choice` selector for [AgentLlmClient.streamTurn]. Maps onto
 * LangChain4j's [dev.langchain4j.model.chat.request.ToolChoice]
 * (`AUTO` / `REQUIRED`) or — for [Function] — a wire-level forced-function
 * tool choice via [dev.langchain4j.model.openai.OpenAiChatRequestParameters].
 */
sealed class AgentToolChoice {
    data object Auto : AgentToolChoice()
    data object None : AgentToolChoice()
    data object Required : AgentToolChoice()
    data class Function(val name: String) : AgentToolChoice()
}
