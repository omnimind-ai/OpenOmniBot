package cn.com.omnimind.bot.agent.langchain4j

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentLlmClient
import cn.com.omnimind.bot.agent.AgentLlmTurn
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentToolChoice
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ToolChoice as Lc4jToolChoice
import dev.langchain4j.model.openai.OpenAiChatRequestParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LangChain4j-backed implementation of [AgentLlmClient]. Replaces the
 * legacy `HttpAgentLlmClient` (hand-rolled OkHttp SSE) entirely.
 *
 * - Routes each call through [RouteResolvedModelFactory], which honors the
 *   scene-binding feature configurable in the Settings UI.
 * - HTTP/2 PROTOCOL_ERROR → retries with `forceHttp1 = true` via a fresh
 *   streaming-model handle.
 * - Inline `<think>` absorption and reasoning emit throttling live in
 *   [LangChain4jStreamHandler].
 */
class LangChain4jAgentLlmClient(
    private val scope: CoroutineScope,
    private val modelOverride: AgentModelOverride? = null
) : AgentLlmClient {

    private companion object {
        const val TAG = "LangChain4jAgentLlmClient"
    }

    override suspend fun streamTurn(
        scene: String,
        messages: List<ChatMessage>,
        toolSpecifications: List<ToolSpecification>,
        modelOverride: AgentModelOverride?,
        reasoningEffort: String?,
        toolChoice: AgentToolChoice,
        parallelToolCalls: Boolean,
        maxCompletionTokens: Int,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): AgentLlmTurn {
        val effectiveOverride = modelOverride ?: this.modelOverride
        return try {
            doStreamTurn(
                scene = scene,
                messages = messages,
                toolSpecifications = toolSpecifications,
                modelOverride = effectiveOverride,
                reasoningEffort = reasoningEffort,
                toolChoice = toolChoice,
                parallelToolCalls = parallelToolCalls,
                maxCompletionTokens = maxCompletionTokens,
                forceHttp1 = false,
                onReasoningUpdate = onReasoningUpdate,
                onContentUpdate = onContentUpdate
            )
        } catch (error: Throwable) {
            if (isHttp2ProtocolError(error)) {
                OmniLog.w(TAG, "HTTP/2 protocol error; retry with HTTP/1.1: ${error.message}")
                doStreamTurn(
                    scene = scene,
                    messages = messages,
                    toolSpecifications = toolSpecifications,
                    modelOverride = effectiveOverride,
                    reasoningEffort = reasoningEffort,
                    toolChoice = toolChoice,
                    parallelToolCalls = parallelToolCalls,
                    maxCompletionTokens = maxCompletionTokens,
                    forceHttp1 = true,
                    onReasoningUpdate = onReasoningUpdate,
                    onContentUpdate = onContentUpdate
                )
            } else {
                throw error
            }
        }
    }

    private suspend fun doStreamTurn(
        scene: String,
        messages: List<ChatMessage>,
        toolSpecifications: List<ToolSpecification>,
        modelOverride: AgentModelOverride?,
        reasoningEffort: String?,
        toolChoice: AgentToolChoice,
        parallelToolCalls: Boolean,
        maxCompletionTokens: Int,
        forceHttp1: Boolean,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): AgentLlmTurn = withContext(Dispatchers.IO) {
        val handle = RouteResolvedModelFactory.streamingModelFor(
            scene = scene,
            modelOverride = modelOverride,
            forceHttp1 = forceHttp1
        )
        // LangChain4j's ChatRequest rejects setting both `parameters` and the
        // top-level `toolSpecifications`. Tools must live inside the parameters.
        val includeTools = toolSpecifications.isNotEmpty() && toolChoice !is AgentToolChoice.None
        val params = OpenAiChatRequestParameters.builder()
            .maxCompletionTokens(maxCompletionTokens)
            .apply {
                reasoningEffort?.takeIf { it.isNotBlank() && it != "no" }?.let(::reasoningEffort)
                if (includeTools) {
                    toolSpecifications(toolSpecifications)
                    // `parallel_tool_calls` is only meaningful when tools are
                    // present — some OpenAI-compatible providers reject it
                    // otherwise.
                    parallelToolCalls(parallelToolCalls)
                    mapToolChoice(toolChoice)?.let(::toolChoice)
                }
            }
            .build()
        val builder = ChatRequest.builder().messages(messages).parameters(params)
        val handler = LangChain4jStreamHandler(
            scope = scope,
            onReasoningUpdate = onReasoningUpdate,
            onContentUpdate = onContentUpdate,
            preferInlineThinkTags = handle.isLocalProvider
        )
        handle.model.chat(builder.build(), handler)
        val response = handler.completion.await()
        handler.join()

        val ai: AiMessage = response.aiMessage() ?: AiMessage.from("")
        val finishReason = response.finishReason()?.name?.lowercase()
        val usage = response.tokenUsage()
        val reasoning = handler.accumulatedReasoning().ifBlank { ai.thinking().orEmpty() }
        AgentLlmTurn(
            aiMessage = ai,
            finishReason = finishReason,
            promptTokens = usage?.inputTokenCount(),
            completionTokens = usage?.outputTokenCount(),
            // MNN local-model TPS is not exposed by LangChain4j; documented gap.
            prefillTokensPerSecond = null,
            decodeTokensPerSecond = null,
            reasoning = reasoning
        )
    }

    private fun mapToolChoice(toolChoice: AgentToolChoice): Lc4jToolChoice? = when (toolChoice) {
        AgentToolChoice.Auto -> Lc4jToolChoice.AUTO
        AgentToolChoice.None -> null
        AgentToolChoice.Required -> Lc4jToolChoice.REQUIRED
        // Function-targeted tool choice is OpenAI-specific (no shared enum
        // for "use function X"). Falling back to REQUIRED is safe — the
        // orchestrator only forces a specific tool when one is in scope.
        is AgentToolChoice.Function -> Lc4jToolChoice.REQUIRED
    }

    private fun isHttp2ProtocolError(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            val message = current.message.orEmpty()
            if (message.contains("PROTOCOL_ERROR", ignoreCase = true) ||
                message.contains("stream was reset", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
