package cn.com.omnimind.bot.agent.langchain4j

import cn.com.omnimind.baselib.util.OmniLog
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatRequestParameters

/**
 * Routes the LLM calls inside `WorkspaceMemoryService.inferRollupByLlm` through
 * LangChain4j. The agent stack no longer touches `HttpController` for these
 * round-trips; [RouteResolvedModelFactory] resolves the same scene → provider
 * routing layer in front of LangChain4j's [dev.langchain4j.model.openai.OpenAiChatModel].
 */
internal object WorkspaceMemoryLlmBridge {
    private const val TAG = "WorkspaceMemoryLlmBridge"

    /** Result of a tool-call style chat invocation. */
    data class ToolCallResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val content: String = "",
        val toolCalls: List<ToolExecutionRequest> = emptyList()
    )

    /**
     * Submit a tool-call style chat request (used by the memory rollup path).
     * Returns LangChain4j-typed [ToolExecutionRequest]s directly so the caller
     * doesn't need to round-trip through the legacy `AssistantToolCall` shape.
     */
    suspend fun submitToolCallRequest(
        scene: String,
        messages: List<ChatMessage>,
        toolSpecification: ToolSpecification?,
        maxCompletionTokens: Int?,
        temperature: Double?
    ): ToolCallResult {
        val handle = RouteResolvedModelFactory.chatModelFor(scene = scene)
        val params = OpenAiChatRequestParameters.builder().apply {
            maxCompletionTokens?.let(::maxCompletionTokens)
            temperature?.let(::temperature)
            // Tools must be set inside parameters; ChatRequest rejects setting
            // both top-level `toolSpecifications` and `parameters`.
            if (toolSpecification != null) {
                toolSpecifications(toolSpecification)
            }
        }.build()
        val builder = ChatRequest.builder().messages(messages).parameters(params)
        return runCatching {
            val response = handle.model.chat(builder.build())
            val aiMessage = response.aiMessage()
            ToolCallResult(
                success = true,
                content = aiMessage.text().orEmpty(),
                toolCalls = aiMessage.toolExecutionRequests().orEmpty()
            )
        }.getOrElse { error ->
            OmniLog.w(TAG, "tool-call submission failed: ${error.message}")
            ToolCallResult(
                success = false,
                errorMessage = error.message
            )
        }
    }

    /**
     * Submit a simple `user`-only chat request. Used by the legacy-prompt
     * fallback in WorkspaceMemoryService.
     */
    suspend fun submitTextPrompt(scene: String, prompt: String): String {
        val handle = RouteResolvedModelFactory.chatModelFor(scene = scene)
        return runCatching {
            handle.model
                .chat(ChatRequest.builder().messages(UserMessage.from(prompt)).build())
                .aiMessage()
                .text()
                .orEmpty()
        }.getOrElse { error ->
            OmniLog.w(TAG, "text-prompt submission failed: ${error.message}")
            ""
        }
    }
}
