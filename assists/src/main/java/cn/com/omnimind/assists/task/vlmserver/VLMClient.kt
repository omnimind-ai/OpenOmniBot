package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class VLMClient {
    companion object {
        private const val TAG = "VLMClient"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun buildUIOperationRequest(
        context: UIContext,
        screenshot: String,
        conversationState: VLMConversationState,
        model: String = "scene.vlm.operation.primary",
        retryState: VLMToolCallRetryState? = null,
        actionProtocol: VlmActionProtocol = VlmActionProtocol.OPENAI_TOOL_CALLS
    ): VLMRequestEnvelope {
        val systemPrompt = PromptTemplate.buildSystemPrompt(
            sceneId = model,
            actionProtocol = actionProtocol
        )
        val currentUserText = PromptTemplate.buildTurnUserPrompt(
            context = context,
            sceneId = model,
            actionProtocol = actionProtocol
        )
        val historyMessages = conversationState.historyMessages()
        val messages = buildMessages(
            systemPrompt = systemPrompt,
            historyMessages = historyMessages,
            currentUserText = currentUserText,
            screenshot = screenshot,
            context = context,
            retryState = retryState,
            actionProtocol = actionProtocol
        )

        OmniLog.i(
            TAG,
            "buildUIOperationRequest scene=$model protocol=$actionProtocol historyRounds=${conversationState.roundCount()} historyMessages=${historyMessages.size} totalMessages=${messages.size} currentImages=1 retry=${retryState?.retryIndex ?: 0}"
        )
        val useTools = actionProtocol == VlmActionProtocol.OPENAI_TOOL_CALLS

        return VLMRequestEnvelope(
            request = ChatCompletionRequest(
                model = model,
                messages = messages,
                maxCompletionTokens = 2048,
                temperature = 0.2,
                stream = true,
                streamOptions = ChatCompletionStreamOptions(includeUsage = true),
                tools = if (useTools) VLMToolDefinitions.tools() else emptyList(),
                toolChoice = if (useTools) JsonPrimitive("required") else null,
                parallelToolCalls = if (useTools) false else null
            ),
            currentUserText = currentUserText
        )
    }

    fun parseVLMResponse(
        response: SceneChatCompletionTurn,
        modelOrScene: String,
        actionProtocol: VlmActionProtocol = VlmActionProtocol.OPENAI_TOOL_CALLS
    ): VLMResult {
        if (actionProtocol == VlmActionProtocol.DO_TEXT) {
            return parseDoTextResponse(response)
        }
        return when (response.parser) {
            ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS -> parseToolActionResponse(response)
            ModelSceneRegistry.ResponseParser.JSON_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 JSON_CONTENT: $modelOrScene")
            ModelSceneRegistry.ResponseParser.TEXT_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 TEXT_CONTENT: $modelOrScene")
        }
    }

    fun buildConversationRound(
        currentUserText: String,
        assistantTurn: SceneChatCompletionTurn,
        executedStep: UIStep,
        actionProtocol: VlmActionProtocol = VlmActionProtocol.OPENAI_TOOL_CALLS
    ): VLMConversationRound {
        val assistantMessage = ChatCompletionMessage(
            role = "assistant",
            content = assistantTurn.turn.message.content,
            toolCalls = assistantTurn.turn.message.toolCalls
        )
        val toolPayload = buildJsonObject {
            put("success", JsonPrimitive(!(executedStep.result?.startsWith("执行失败") == true)))
            put("action", JsonPrimitive(executedStep.action.name))
            put("result", JsonPrimitive(executedStep.result.orEmpty()))
            if (executedStep.observation.isNotBlank()) {
                put("observation", JsonPrimitive(executedStep.observation))
            }
            if (executedStep.summary.isNotBlank()) {
                put("summary", JsonPrimitive(executedStep.summary))
            }
        }.toString()
        return VLMConversationRound(
            userMessage = ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(currentUserText)
            ),
            assistantMessage = assistantMessage,
            toolMessage = if (actionProtocol == VlmActionProtocol.DO_TEXT) {
                ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive("上一轮动作执行结果: $toolPayload")
                )
            } else {
                val toolCallId = assistantTurn.turn.message.toolCalls?.firstOrNull()?.id.orEmpty()
                ChatCompletionMessage(
                    role = "tool",
                    content = JsonPrimitive(toolPayload),
                    toolCallId = toolCallId.ifBlank { null }
                )
            }
        )
    }

    private fun parseToolActionResponse(response: SceneChatCompletionTurn): VLMResult {
        val content = response.turn.message.contentText()
        val metadata = parseStepMetadata(content, response.turn.reasoning)
        val thinking = buildThinkingContext(
            content = content,
            reasoning = response.turn.reasoning,
            finishReason = response.turn.finishReason,
            metadata = metadata
        )
        val toolCalls = response.turn.message.toolCalls.orEmpty()
        if (toolCalls.isEmpty()) {
            return VLMResult(
                success = false,
                step = null,
                error = buildMissingToolCallMessage(response.turn.finishReason, thinking),
                thinking = thinking,
                shouldRetryForToolCall = shouldRetryForMissingToolCall(thinking)
            )
        }
        if (toolCalls.size > 1) {
            return VLMResult(
                success = false,
                step = null,
                error = "主 VLM 每轮只能返回一个 tool_call，实际收到 ${toolCalls.size} 个"
            )
        }

        return try {
            val action = parseActionFromToolCall(toolCalls.first())
            val thought = metadata.thought.ifBlank { response.turn.reasoning.ifBlank { content } }
            VLMResult(
                success = true,
                step = VLMStep(
                    observation = metadata.observation,
                    thought = thought,
                    action = action,
                    summary = metadata.summary
                ),
                error = null,
                thinking = thinking
            )
        } catch (e: Exception) {
            VLMResult(
                success = false,
                step = null,
                error = "Failed to parse tool_calls response: ${e.message}",
                thinking = thinking,
                shouldRetryForToolCall = true
            )
        }
    }

    private fun parseDoTextResponse(response: SceneChatCompletionTurn): VLMResult {
        val content = response.turn.message.contentText()
        val responseText = listOf(content, response.turn.reasoning)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .ifBlank { content }
        val parseResult = VlmDoActionParser.parse(responseText)
        val thinking = VLMThinkingContext(
            observation = "",
            thought = parseResult.step?.thought.orEmpty().ifBlank { response.turn.reasoning.trim() },
            summary = "",
            reasoning = response.turn.reasoning.trim(),
            rawContent = parseResult.normalizedText.ifBlank { responseText.trim() },
            finishReason = response.turn.finishReason?.trim()?.takeIf { it.isNotEmpty() }
        )

        val step = parseResult.step
        return if (parseResult.success && step != null) {
            VLMResult(
                success = true,
                step = step,
                error = null,
                thinking = thinking
            )
        } else {
            VLMResult(
                success = false,
                step = null,
                error = parseResult.error ?: "DO_TEXT 动作解析失败",
                thinking = thinking,
                shouldRetryForToolCall = true
            )
        }
    }

    private fun buildMessages(
        systemPrompt: String,
        historyMessages: List<ChatCompletionMessage>,
        currentUserText: String,
        screenshot: String,
        context: UIContext,
        retryState: VLMToolCallRetryState?,
        actionProtocol: VlmActionProtocol
    ): List<ChatCompletionMessage> {
        val messages = mutableListOf<ChatCompletionMessage>()
        messages += ChatCompletionMessage(
            role = "system",
            content = JsonPrimitive(systemPrompt)
        )
        messages += historyMessages
        messages += buildCurrentUserMessage(currentUserText, screenshot)

        if (retryState != null) {
            buildRetryAssistantContent(retryState.thinking)?.let { assistantContent ->
                messages += ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(assistantContent)
                )
            }
            messages += ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(
                    if (actionProtocol == VlmActionProtocol.DO_TEXT) {
                        PromptTemplate.buildDoTextRetryPrompt(context, retryState)
                    } else {
                        PromptTemplate.buildToolCallRetryPrompt(context, retryState)
                    }
                )
            )
        }
        return messages
    }

    private fun buildCurrentUserMessage(
        currentUserText: String,
        screenshot: String
    ): ChatCompletionMessage {
        return ChatCompletionMessage(
            role = "user",
            content = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(currentUserText))
                    }
                )
                add(buildImageContent(screenshot))
            }
        )
    }

    private fun buildRetryAssistantContent(thinking: VLMThinkingContext): String? {
        val content = thinking.rawContent.trim()
        if (content.isNotEmpty()) {
            return content
        }

        val fallback = buildList {
            thinking.observation.takeIf { it.isNotBlank() }?.let { add("observation: $it") }
            thinking.thought.takeIf { it.isNotBlank() }?.let { add("thought: $it") }
            thinking.summary.takeIf { it.isNotBlank() }?.let { add("summary: $it") }
        }.joinToString(separator = "\n")

        return fallback.takeIf { it.isNotBlank() }
    }

    private fun buildThinkingContext(
        content: String,
        reasoning: String,
        finishReason: String?,
        metadata: StepMetadataPayload
    ): VLMThinkingContext {
        return VLMThinkingContext(
            observation = metadata.observation.trim(),
            thought = metadata.thought.trim().ifBlank { reasoning.trim() },
            summary = metadata.summary.trim(),
            reasoning = reasoning.trim(),
            rawContent = content.trim(),
            finishReason = finishReason?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildMissingToolCallMessage(
        finishReason: String?,
        thinking: VLMThinkingContext
    ): String {
        val suffix = finishReason?.takeIf { it.isNotBlank() }?.let { "（finish_reason=$it）" }.orEmpty()
        return if (shouldRetryForMissingToolCall(thinking)) {
            "模型本轮尚未返回标准 tool_calls$suffix"
        } else {
            "模型未返回标准 tool_calls$suffix"
        }
    }

    private fun shouldRetryForMissingToolCall(thinking: VLMThinkingContext): Boolean {
        return thinking.reasoning.isNotBlank() ||
            thinking.rawContent.isNotBlank() ||
            thinking.observation.isNotBlank() ||
            thinking.thought.isNotBlank() ||
            thinking.summary.isNotBlank()
    }

    private fun parseStepMetadata(content: String, reasoning: String): StepMetadataPayload {
        val normalized = content.trim()
        if (normalized.isEmpty()) {
            return StepMetadataPayload(thought = reasoning)
        }
        return runCatching {
            val jsonStart = normalized.indexOf('{')
            val jsonEnd = normalized.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json.decodeFromString<StepMetadataPayload>(normalized.substring(jsonStart, jsonEnd + 1))
            } else {
                StepMetadataPayload(thought = normalized)
            }
        }.getOrElse {
            StepMetadataPayload(thought = normalized.ifBlank { reasoning })
        }
    }

    private fun parseActionFromToolCall(toolCall: AssistantToolCall): UIAction {
        val toolName = toolCall.function.name
        val args = parseArguments(toolName, toolCall.function.arguments)
        return when (toolName) {
            "click" -> ClickAction(
                targetDescription = requireString(args, "target_description"),
                x = requireFloat(args, "x"),
                y = requireFloat(args, "y")
            )
            "type" -> TypeAction(
                content = requireString(args, "content")
            )
            "scroll" -> ScrollAction(
                targetDescription = requireString(args, "target_description"),
                x1 = requireFloat(args, "x1"),
                y1 = requireFloat(args, "y1"),
                x2 = requireFloat(args, "x2"),
                y2 = requireFloat(args, "y2"),
                duration = optionalFloat(args, "duration") ?: 1.5f
            )
            "long_press" -> LongPressAction(
                targetDescription = requireString(args, "target_description"),
                x = requireFloat(args, "x"),
                y = requireFloat(args, "y")
            )
            "open_app" -> OpenAppAction(
                packageName = requireString(args, "package_name")
            )
            "press_home" -> PressHomeAction()
            "press_back" -> PressBackAction()
            "wait" -> buildWaitAction(args)
            "hot_key" -> HotKeyAction(
                key = requireString(args, "key").uppercase()
            )
            "finished" -> FinishedAction(
                content = optionalString(args, "content").orEmpty()
            )
            "info" -> InfoAction(
                value = requireString(args, "value")
            )
            "feedback" -> FeedbackAction(
                value = requireString(args, "value")
            )
            "abort" -> AbortAction(
                value = optionalString(args, "value").orEmpty()
            )
            "require_user_choice" -> RequireUserChoiceAction(
                options = requireStringList(args, "options"),
                prompt = requireString(args, "prompt")
            )
            "require_user_confirmation" -> RequireUserConfirmationAction(
                prompt = requireString(args, "prompt")
            )
            else -> throw IllegalArgumentException("Unsupported tool call: ${toolCall.function.name}")
        }
    }

    private fun buildWaitAction(args: JsonObject): WaitAction {
        val durationMs = optionalLong(args, "duration_ms")
            ?: optionalFloat(args, "duration")?.let { seconds ->
                (seconds * 1000f).toLong()
            }
        return if (durationMs != null) {
            WaitAction(durationMs = durationMs)
        } else {
            throw IllegalArgumentException("Missing or invalid 'duration_ms'")
        }
    }

    private fun parseArguments(toolName: String, rawArguments: String): JsonObject {
        return VLMToolArgumentParser.parse(toolName, rawArguments)
    }

    private fun requireString(obj: JsonObject, key: String): String {
        return obj[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing or empty '$key'")
    }

    private fun optionalString(obj: JsonObject, key: String): String? {
        return obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun requireFloat(obj: JsonObject, key: String): Float {
        return obj[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            ?: throw IllegalArgumentException("Missing or invalid '$key'")
    }

    private fun optionalFloat(obj: JsonObject, key: String): Float? {
        return obj[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
    }

    private fun optionalLong(obj: JsonObject, key: String): Long? {
        return obj[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: obj[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun requireStringList(obj: JsonObject, key: String): List<String> {
        val raw = obj[key] ?: throw IllegalArgumentException("Missing '$key'")
        return when (raw) {
            is JsonArray -> raw.mapNotNull {
                it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            }
            else -> throw IllegalArgumentException("Field '$key' must be an array of strings")
        }.ifEmpty {
            throw IllegalArgumentException("Field '$key' must contain at least one option")
        }
    }

    private fun buildImageContent(rawImage: String): JsonObject {
        val imageUrl = if (
            rawImage.startsWith("http://", ignoreCase = true) ||
            rawImage.startsWith("https://", ignoreCase = true) ||
            rawImage.startsWith("data:", ignoreCase = true)
        ) {
            rawImage
        } else {
            "data:image/png;base64,$rawImage"
        }
        return buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            put(
                "image_url",
                buildJsonObject {
                    put("url", JsonPrimitive(imageUrl))
                }
            )
        }
    }
}
