package cn.com.omnimind.bot.agent.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentModelOverride
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Streams a single chat-completion turn through Koog's [OpenAILLMClient] against an OpenAI-compatible
 * endpoint (custom `baseUrl` resolved from [ModelProviderConfigStore]). Used by the conversation
 * context compactor and the Koog scene-completion bridge; the main agent loop uses
 * [KoogAgentRunner] which wraps `AIAgent` end-to-end.
 *
 * Stream frames are folded back into a [ChatCompletionTurn] so callers consuming the original
 * project chat-completion shape work unchanged. Optional `onReasoningUpdate` / `onContentUpdate`
 * callbacks receive cumulative reasoning / content text as frames arrive.
 */
class KoogAgentLlmClient(
    private val modelOverride: AgentModelOverride? = null,
    private val clock: Clock = Clock.System
) {

    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
        onContentUpdate: (suspend (String) -> Unit)? = null
    ): ChatCompletionTurn {
        val (baseUrl, apiKey) = resolveProvider()
        val client = OpenAILLMClient(
            apiKey = apiKey,
            settings = OpenAIClientSettings(baseUrl = baseUrl)
        )
        try {
            val prompt = convertToPrompt(request)
            val model = buildLLModel(modelOverride?.modelId?.takeIf { it.isNotBlank() } ?: request.model)
            val tools = request.tools.map(::convertTool)

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolCalls = mutableListOf<AssistantToolCall>()
            var finishReason: String? = null
            var emittedReasoning = false
            var emittedContent = false

            client.executeStreaming(prompt, model, tools).collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        content.append(frame.text)
                        emittedContent = true
                        onContentUpdate?.invoke(content.toString())
                    }
                    is StreamFrame.ReasoningDelta -> {
                        frame.text?.let { reasoning.append(it) }
                        emittedReasoning = true
                        onReasoningUpdate?.invoke(reasoning.toString())
                    }
                    is StreamFrame.ToolCallComplete -> {
                        toolCalls += AssistantToolCall(
                            id = frame.id.orEmpty(),
                            type = "function",
                            function = AssistantToolCallFunction(
                                name = frame.name.orEmpty(),
                                arguments = frame.content.orEmpty()
                            )
                        )
                    }
                    is StreamFrame.End -> {
                        finishReason = frame.finishReason
                    }
                    else -> Unit
                }
            }

            val assistantMessage = ChatCompletionMessage(
                role = "assistant",
                content = if (emittedContent) JsonPrimitive(content.toString()) else null,
                toolCalls = toolCalls.takeIf { it.isNotEmpty() },
                reasoningContent = if (emittedReasoning) reasoning.toString() else null
            )
            return ChatCompletionTurn(
                message = assistantMessage,
                reasoning = reasoning.toString(),
                finishReason = finishReason,
                usage = null
            )
        } finally {
            runCatching { client.close() }.onFailure {
                OmniLog.w(TAG, "close koog llm client failed: ${it.message}")
            }
        }
    }

    private fun resolveProvider(): Pair<String, String> {
        val config = ModelProviderConfigStore.getConfig()
        val rawBaseUrl = modelOverride?.apiBase?.takeIf { it.isNotBlank() }
            ?: config.baseUrl.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Koog adapter: no LLM base URL configured")
        val sanitizedBaseUrl = ModelProviderConfigStore.stripDirectRequestUrlMarker(rawBaseUrl)
            .ifBlank { rawBaseUrl }
        val apiKey = modelOverride?.apiKey?.takeIf { it.isNotBlank() } ?: config.apiKey
        return sanitizedBaseUrl to apiKey
    }

    private fun convertToPrompt(request: ChatCompletionRequest): Prompt {
        val messages = mutableListOf<Message>()
        request.messages.forEach { msg -> appendMessage(messages, msg) }
        val params = LLMParams(
            temperature = request.temperature,
            maxTokens = request.maxCompletionTokens ?: request.maxTokens
        )
        return Prompt(
            messages = messages,
            id = "agent-turn-${clock.now().toEpochMilliseconds()}",
            params = params
        )
    }

    private fun appendMessage(target: MutableList<Message>, msg: ChatCompletionMessage) {
        when (msg.role) {
            "system" -> {
                val text = systemTextContent(msg.content)
                if (text.isNotEmpty()) {
                    target += Message.System(text, RequestMetaInfo.create(clock))
                }
            }
            "user" -> {
                val parts = userContentParts(msg.content)
                if (parts.isNotEmpty()) {
                    target += Message.User(parts, RequestMetaInfo.create(clock))
                }
            }
            "assistant" -> {
                val text = msg.contentText()
                if (text.isNotEmpty()) {
                    target += Message.Assistant(text, ResponseMetaInfo.create(clock))
                }
                msg.toolCalls?.forEach { tc ->
                    target += Message.Tool.Call(
                        id = tc.id,
                        tool = tc.function.name,
                        content = tc.function.arguments,
                        metaInfo = ResponseMetaInfo.create(clock)
                    )
                }
            }
            "tool" -> {
                // The tool name is not carried by OpenAI-style tool messages — we look it up
                // from the most recent matching Tool.Call so Koog's history adaptation stays correct.
                val resolvedName = msg.toolCallId
                    ?.let { id -> findToolNameForCallId(target, id) }
                    ?: msg.name.orEmpty()
                target += Message.Tool.Result(
                    id = msg.toolCallId,
                    tool = resolvedName,
                    content = msg.contentText(),
                    metaInfo = RequestMetaInfo.create(clock)
                )
            }
            else -> OmniLog.w(TAG, "drop unknown message role=${msg.role}")
        }
    }

    /**
     * Flattens system-message content to plain text. The legacy code wraps the system prompt
     * in a single-element array carrying `cache_control = ephemeral` (see
     * [OmniAgentExecutor.buildCachedSystemPromptContent]); Koog 0.8.0's [CacheControl] interface
     * doesn't yet expose an Anthropic-style ephemeral marker for OpenAI-compatible endpoints,
     * so we strip it here. This is a known fidelity gap — see project memory `koog-migration`.
     */
    private fun systemTextContent(content: JsonElement?): String {
        return when (content) {
            null -> ""
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString(separator = "\n") { item ->
                val obj = item as? JsonObject ?: return@joinToString item.toString()
                (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
            else -> content.toString()
        }
    }

    /**
     * Converts a user message's [content] into Koog [ContentPart]s, preserving OpenAI Vision-style
     * `image_url` parts as [ContentPart.Image]. data: URLs become [AttachmentContent.Base64]; http(s)
     * URLs become [AttachmentContent.URL].
     */
    private fun userContentParts(content: JsonElement?): List<ContentPart> {
        if (content == null) return emptyList()
        if (content is JsonPrimitive) {
            val text = content.contentOrNull.orEmpty()
            return if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text))
        }
        if (content !is JsonArray) {
            return listOf(ContentPart.Text(content.toString()))
        }
        val parts = mutableListOf<ContentPart>()
        content.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            when (type) {
                "text" -> {
                    val text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (text.isNotEmpty()) parts += ContentPart.Text(text)
                }
                "image_url" -> {
                    val url = extractImageUrl(obj)
                    if (url.isNotBlank()) {
                        imagePartFromUrl(url)?.let { parts += it }
                    }
                }
                else -> {
                    val fallback = (obj["text"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["content"] as? JsonPrimitive)?.contentOrNull
                    if (!fallback.isNullOrEmpty()) parts += ContentPart.Text(fallback)
                }
            }
        }
        return parts
    }

    private fun extractImageUrl(part: JsonObject): String {
        val raw = part["image_url"] ?: return ""
        return when (raw) {
            is JsonPrimitive -> raw.contentOrNull.orEmpty()
            is JsonObject -> (raw["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> ""
        }.trim()
    }

    private fun imagePartFromUrl(url: String): ContentPart.Image? {
        if (url.startsWith("data:", ignoreCase = true)) {
            val header = url.substringBefore(',', missingDelimiterValue = "")
            val payload = url.substringAfter(',', missingDelimiterValue = "")
            if (payload.isEmpty()) return null
            val mimeType = header.removePrefix("data:")
                .substringBefore(';')
                .ifBlank { "image/jpeg" }
            val format = mimeType.removePrefix("image/").ifBlank { "jpeg" }
            return ContentPart.Image(
                content = AttachmentContent.Binary.Base64(payload),
                format = format,
                mimeType = mimeType
            )
        }
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            val lower = url.lowercase()
            val format = when {
                lower.contains(".png") -> "png"
                lower.contains(".jpg") || lower.contains(".jpeg") -> "jpeg"
                lower.contains(".webp") -> "webp"
                lower.contains(".gif") -> "gif"
                else -> "jpeg"
            }
            return ContentPart.Image(
                content = AttachmentContent.URL(url),
                format = format,
                mimeType = "image/$format"
            )
        }
        return null
    }

    private fun findToolNameForCallId(messages: List<Message>, toolCallId: String): String? {
        return messages.asReversed()
            .firstOrNull { it is Message.Tool.Call && it.id == toolCallId }
            ?.let { (it as Message.Tool.Call).tool }
    }

    private fun buildLLModel(modelId: String): LLModel {
        // Declare permissive capabilities for arbitrary OpenAI-compatible endpoints — Koog's
        // OpenAILLMClient gates per-capability before serializing, so unsupported features just
        // get dropped from the request rather than failing.
        return LLModel(
            provider = LLMProvider.OpenAI,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.Completion,
                LLMCapability.Vision.Image,
                LLMCapability.Thinking
            ),
            contextLength = DEFAULT_CONTEXT_LENGTH
        )
    }

    private fun convertTool(tool: ChatCompletionTool): ToolDescriptor = KoogToolSchemaMapper.convert(tool)

    companion object {
        private const val TAG = "KoogAgentLlmClient"
        private const val DEFAULT_CONTEXT_LENGTH = 128_000L
    }
}

/**
 * Pure-logic converter from OpenAI-style `ChatCompletionTool` JSON schemas to Koog [ToolDescriptor].
 * Extracted as a top-level `internal` object so unit tests can exercise it without instantiating
 * the full [KoogAgentLlmClient] (which depends on MMKV / Android).
 */
internal object KoogToolSchemaMapper {
    fun convert(tool: ChatCompletionTool): ToolDescriptor {
        val parameters = tool.function.parameters
        val requiredNames = (parameters["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()
        val properties = (parameters["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val descriptors = properties.entries.map { (name, schema) ->
            val obj = schema as? JsonObject ?: JsonObject(emptyMap())
            val description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            ToolParameterDescriptor(
                name = name,
                description = description,
                type = parameterTypeFromSchema(obj)
            )
        }
        return ToolDescriptor(
            name = tool.function.name,
            description = tool.function.description,
            requiredParameters = descriptors.filter { it.name in requiredNames },
            optionalParameters = descriptors.filterNot { it.name in requiredNames }
        )
    }

    fun parameterTypeFromSchema(schema: JsonObject): ToolParameterType {
        val rawType = (schema["type"] as? JsonPrimitive)?.contentOrNull
        return when (rawType) {
            "string" -> {
                val enums = (schema["enum"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (!enums.isNullOrEmpty()) {
                    ToolParameterType.Enum(enums.toTypedArray())
                } else {
                    ToolParameterType.String
                }
            }
            "integer" -> ToolParameterType.Integer
            "number" -> ToolParameterType.Float
            "boolean" -> ToolParameterType.Boolean
            "array" -> {
                val items = (schema["items"] as? JsonObject) ?: JsonObject(emptyMap())
                ToolParameterType.List(parameterTypeFromSchema(items))
            }
            "object" -> objectType(schema)
            null -> ToolParameterType.String
            else -> ToolParameterType.String
        }
    }

    private fun objectType(schema: JsonObject): ToolParameterType.Object {
        val props = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        val descriptors = props.entries.map { (name, child) ->
            val obj = child as? JsonObject ?: JsonObject(emptyMap())
            val desc = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            ToolParameterDescriptor(name, desc, parameterTypeFromSchema(obj))
        }
        return ToolParameterType.Object(
            properties = descriptors,
            requiredProperties = required
        )
    }
}
