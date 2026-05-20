package cn.com.omnimind.bot.agent.langchain4j

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.LocalModelProviderBridge
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentModelOverride
import dev.langchain4j.http.client.okhttp.OkHttpClientBuilder
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import okhttp3.Protocol
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds LangChain4j chat/streaming-chat models from the existing scene routing layer
 * (`HttpController.resolveChatCompletionRouteInfo`) and the per-call [AgentModelOverride].
 *
 * Two callers with the same resolved route (apiBase + apiKey + model + protocol +
 * forceHttp1 + streaming-or-not) share the same model instance. The cache survives
 * across agent runs; call [invalidateAll] when MMKV provider profile bindings change.
 *
 * The underlying OkHttp transport mirrors `HttpController.openAIStreamClient` —
 * 60s connect, 0s read (streams can be arbitrarily long), 60s write, optionally
 * forced to HTTP/1.1 to recover from HTTP/2 PROTOCOL_ERROR.
 */
object RouteResolvedModelFactory {
    private const val TAG = "RouteResolvedModelFactory"

    private val streamingCache = ConcurrentHashMap<RouteKey, StreamingChatModel>()
    private val chatCache = ConcurrentHashMap<RouteKey, OpenAiChatModel>()

    /**
     * Resolved route + the OpenAI-compatible LangChain4j streaming model bound to it.
     *
     * Callers should rebuild via [streamingModelFor] when they observe HTTP/2 protocol
     * errors — pass `forceHttp1 = true` to get a separate cached instance with the
     * downgraded transport.
     */
    data class StreamingHandle(
        val route: HttpController.ChatCompletionRouteInfo,
        val model: StreamingChatModel,
        val isLocalProvider: Boolean,
        val forceHttp1: Boolean
    )

    data class ChatHandle(
        val route: HttpController.ChatCompletionRouteInfo,
        val model: OpenAiChatModel,
        val isLocalProvider: Boolean
    )

    fun streamingModelFor(
        scene: String,
        modelOverride: AgentModelOverride? = null,
        forceHttp1: Boolean = false
    ): StreamingHandle {
        val route = resolveRoute(scene, modelOverride)
        val key = RouteKey.fromRoute(route, forceHttp1 = forceHttp1, streaming = true)
        val model = streamingCache.computeIfAbsent(key) { buildStreamingModel(route, forceHttp1) }
        return StreamingHandle(
            route = route,
            model = model,
            isLocalProvider = LocalModelProviderBridge.isBuiltinLocalProvider(
                profileId = route.providerProfileId,
                apiBase = route.apiBase
            ),
            forceHttp1 = forceHttp1
        )
    }

    fun chatModelFor(
        scene: String,
        modelOverride: AgentModelOverride? = null
    ): ChatHandle {
        val route = resolveRoute(scene, modelOverride)
        val key = RouteKey.fromRoute(route, forceHttp1 = false, streaming = false)
        val model = chatCache.computeIfAbsent(key) { buildChatModel(route) }
        return ChatHandle(
            route = route,
            model = model,
            isLocalProvider = LocalModelProviderBridge.isBuiltinLocalProvider(
                profileId = route.providerProfileId,
                apiBase = route.apiBase
            )
        )
    }

    /**
     * Drop all cached model instances. Call after MMKV provider profile / scene
     * binding mutations so the next request picks up the new credentials.
     */
    fun invalidateAll() {
        streamingCache.clear()
        chatCache.clear()
        OmniLog.i(TAG, "invalidated all cached LangChain4j models")
    }

    private fun resolveRoute(
        scene: String,
        modelOverride: AgentModelOverride?
    ): HttpController.ChatCompletionRouteInfo {
        return HttpController.resolveChatCompletionRouteInfo(
            modelOrScene = scene,
            explicitApiBase = modelOverride?.apiBase,
            explicitApiKey = modelOverride?.apiKey,
            explicitModel = modelOverride?.modelId,
            explicitProtocolType = modelOverride?.protocolType
        )
    }

    private fun buildStreamingModel(
        route: HttpController.ChatCompletionRouteInfo,
        forceHttp1: Boolean
    ): StreamingChatModel {
        OmniLog.i(
            TAG,
            "build streaming model resolved=${route.resolvedModel} apiBase=${route.apiBase} protocol=${route.protocolType} forceHttp1=$forceHttp1"
        )
        return OpenAiStreamingChatModel.builder()
            .httpClientBuilder(okHttpClientBuilder(forceHttp1))
            .baseUrl(route.apiBase ?: "")
            .apiKey(route.apiKey.orEmpty())
            .modelName(route.resolvedModel)
            // returnThinking exposes reasoning_content deltas through onPartialThinking.
            .returnThinking(true)
            // sendThinking echoes reasoning_content back to providers that require it
            // (DeepSeek official path; our LLM client further sanitizes per-message
            // based on whether it's followed by tool messages).
            .sendThinking(route.requiresReasoningEcho)
            .timeout(STREAM_READ_TIMEOUT)
            .build()
    }

    private fun buildChatModel(
        route: HttpController.ChatCompletionRouteInfo
    ): OpenAiChatModel {
        OmniLog.i(
            TAG,
            "build chat model resolved=${route.resolvedModel} apiBase=${route.apiBase} protocol=${route.protocolType}"
        )
        return OpenAiChatModel.builder()
            .httpClientBuilder(okHttpClientBuilder(forceHttp1 = false))
            .baseUrl(route.apiBase ?: "")
            .apiKey(route.apiKey.orEmpty())
            .modelName(route.resolvedModel)
            .timeout(CHAT_TIMEOUT)
            .build()
    }

    /**
     * Mirrors `HttpController.openAIStreamClient` — 60s connect, unbounded read
     * (streams are long-lived), 60s write. When [forceHttp1] is true we pin the
     * protocol set to HTTP/1.1 to defeat HTTP/2 PROTOCOL_ERROR storms.
     */
    private fun okHttpClientBuilder(forceHttp1: Boolean): OkHttpClientBuilder {
        val builder = okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(60))
            .readTimeout(java.time.Duration.ZERO)
            .writeTimeout(java.time.Duration.ofSeconds(60))
        if (forceHttp1) {
            builder.protocols(listOf(Protocol.HTTP_1_1))
        }
        return OkHttpClientBuilder().okHttpClientBuilder(builder)
    }

    private val STREAM_READ_TIMEOUT: Duration = Duration.ofMinutes(10)
    private val CHAT_TIMEOUT: Duration = Duration.ofMinutes(5)
}
