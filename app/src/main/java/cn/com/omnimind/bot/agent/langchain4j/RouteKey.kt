package cn.com.omnimind.bot.agent.langchain4j

import cn.com.omnimind.assists.controller.http.HttpController

/**
 * Cache key for resolved LangChain4j model instances.
 *
 * Two requests with identical [apiBase], [resolvedModel], [protocolType], [apiKeyHash]
 * and [forceHttp1] can share the same `OpenAiStreamingChatModel` / `OpenAiChatModel`
 * instance — they all hit the same wire endpoint with the same auth + transport.
 *
 * The apiKey itself is **not** kept in the key (only its hash) so the map doesn't
 * carry plaintext secrets in memory.
 */
internal data class RouteKey(
    val apiBase: String,
    val resolvedModel: String,
    val protocolType: String,
    val apiKeyHash: Int,
    val forceHttp1: Boolean,
    val streaming: Boolean
) {
    companion object {
        fun fromRoute(
            route: HttpController.ChatCompletionRouteInfo,
            forceHttp1: Boolean,
            streaming: Boolean
        ): RouteKey {
            return RouteKey(
                apiBase = route.apiBase.orEmpty(),
                resolvedModel = route.resolvedModel,
                protocolType = route.protocolType,
                apiKeyHash = route.apiKey?.hashCode() ?: 0,
                forceHttp1 = forceHttp1,
                streaming = streaming
            )
        }
    }
}
