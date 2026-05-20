package cn.com.omnimind.bot.agent.koog

import cn.com.omnimind.assists.task.vlmserver.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentModelOverride
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 5 bridge: provides a non-streaming chat-completion entry point backed by
 * [KoogAgentLlmClient] for callers that historically used
 * `HttpController.postSceneChatCompletion` / `HttpController.postLLMRequest`.
 *
 * Specifically used by [cn.com.omnimind.bot.agent.workspace.memory.WorkspaceMemoryService] for
 * its rollup / long-term memory inference LLM calls when the Koog flag is on. The legacy
 * `HttpController` path stays available as the default until the flag flips.
 *
 * The "non-streaming" facade is implemented by running the existing streaming
 * [KoogAgentLlmClient.streamTurn] without callbacks and folding the resulting [ChatCompletionTurn]
 * back into the [SceneChatCompletionResponse] shape that the legacy callers parse.
 */
object KoogSceneChatCompletionBridge {

    suspend fun postSceneChatCompletion(
        request: ChatCompletionRequest,
        modelOverride: AgentModelOverride? = null
    ): SceneChatCompletionResponse {
        return runCatching {
            val client = KoogAgentLlmClient(modelOverride = modelOverride)
            val effective = request.copy(
                stream = true,
                streamOptions = ChatCompletionStreamOptions(includeUsage = false)
            )
            val turn = client.streamTurn(effective)
            SceneChatCompletionResponse(
                success = true,
                code = "200",
                message = "ok",
                parser = cn.com.omnimind.baselib.llm.ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
                route = null,
                content = turn.message.contentText(),
                reasoning = turn.reasoning,
                finishReason = turn.finishReason,
                toolCalls = turn.message.toolCalls.orEmpty(),
                rawResponseBody = null
            )
        }.getOrElse { error ->
            OmniLog.w(TAG, "KoogSceneChatCompletionBridge failed: ${error.message}")
            SceneChatCompletionResponse(
                success = false,
                code = "ERR_KOOG_BRIDGE",
                message = error.message.orEmpty().ifBlank { "Koog scene completion failed" },
                parser = cn.com.omnimind.baselib.llm.ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
                route = null,
                content = "",
                reasoning = "",
                finishReason = null,
                toolCalls = emptyList(),
                rawResponseBody = null
            )
        }
    }

    suspend fun postLLMRequest(model: String, text: String): String {
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive(text))
            ),
            model = model,
            stream = true
        )
        val response = postSceneChatCompletion(request)
        return if (response.success) {
            response.content.ifBlank { response.message }
        } else {
            ""
        }
    }

    private const val TAG = "KoogSceneChatCompletionBridge"
}
