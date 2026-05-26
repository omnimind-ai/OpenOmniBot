package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class VLMClient(
    private val systemPromptBuilder: (sceneId: String) -> String = { sceneId ->
        PromptTemplate.buildSystemPrompt(sceneId = sceneId)
    },
    private val turnPromptBuilder: (context: UIContext, sceneId: String) -> String = { context, sceneId ->
        PromptTemplate.buildTurnUserPrompt(context, sceneId = sceneId)
    }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun buildUIOperationRequest(
        context: UIContext,
        screenshot: String?,
        markedScreenshot: String? = null,
        conversationState: VLMConversationState,
        model: String = "scene.vlm.operation.primary",
        retryState: VLMToolCallRetryState? = null,
        includeMarkedScreenshot: Boolean = false
    ): VLMRequestEnvelope {
        val sceneId = resolveVlmSceneId(model)
        val modelOverride = resolveVlmModelOverride(model)
        val systemPrompt = systemPromptBuilder(sceneId)
        val currentUserText = turnPromptBuilder(context, sceneId)
        val historyMessages = conversationState.historyMessages()
        val effectiveMarkedScreenshot = markedScreenshot.takeIf { includeMarkedScreenshot }
        val messages = buildMessages(
            systemPrompt = systemPrompt,
            historyMessages = historyMessages,
            currentUserText = currentUserText,
            screenshot = screenshot,
            markedScreenshot = effectiveMarkedScreenshot,
            context = context,
            retryState = retryState
        )
        val imageCount = listOf(screenshot, effectiveMarkedScreenshot).count { !it.isNullOrBlank() }

        OmniLog.i(
            TAG,
            "buildUIOperationRequest scene=$model historyRounds=${conversationState.roundCount()} historyMessages=${historyMessages.size} totalMessages=${messages.size} currentImages=$imageCount visualPolicy=screenshot+a11_tree marked=${includeMarkedScreenshot && !markedScreenshot.isNullOrBlank()} retry=${retryState?.retryIndex ?: 0}"
        )

        return VLMRequestEnvelope(
            request = ChatCompletionRequest(
                model = sceneId,
                modelOverride = modelOverride,
                messages = messages,
                maxCompletionTokens = 1024,
                temperature = 0.2,
                stream = true,
                streamOptions = ChatCompletionStreamOptions(includeUsage = true),
                tools = VLMToolDefinitions.tools(),
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false
            ),
            currentUserText = currentUserText
        )
    }

    fun parseVLMResponse(response: SceneChatCompletionTurn, modelOrScene: String): VLMResult {
        return when (response.parser) {
            ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS -> parseToolActionResponse(response)
            ModelSceneRegistry.ResponseParser.JSON_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 JSON_CONTENT: $modelOrScene")
            ModelSceneRegistry.ResponseParser.TEXT_CONTENT ->
                VLMResult(false, null, "主 VLM parser 不支持 TEXT_CONTENT: $modelOrScene")
        }
    }

    fun resolveVlmSceneId(modelOrScene: String?): String {
        val normalized = modelOrScene?.trim().orEmpty()
        return if (isSceneId(normalized)) {
            normalized
        } else {
            "scene.vlm.operation.primary"
        }
    }

    fun resolveVlmModelOverride(modelOrScene: String?): String? {
        val normalized = modelOrScene?.trim().orEmpty()
        return normalized.takeIf {
            it.isNotEmpty() && !isSceneId(it)
        }
    }

    private fun isSceneId(value: String): Boolean {
        return value.startsWith("scene.")
    }

    fun buildConversationRound(
        currentUserText: String,
        assistantTurn: SceneChatCompletionTurn,
        executedStep: UIStep
    ): VLMConversationRound {
        val assistantMessage = ChatCompletionMessage(
            role = "assistant",
            content = assistantTurn.turn.message.content,
            toolCalls = assistantTurn.turn.message.toolCalls
        )
        val toolCallId = assistantTurn.turn.message.toolCalls?.firstOrNull()?.id.orEmpty()
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
            VLMPostActionObservation.summarize(executedStep)?.let { post ->
                put("screen_changed", JsonPrimitive(post.screenChanged))
                put("package_changed", JsonPrimitive(post.packageChanged))
                post.beforePackageName?.takeIf { it.isNotBlank() }?.let {
                    put("before_package", JsonPrimitive(it))
                }
                post.afterPackageName?.takeIf { it.isNotBlank() }?.let {
                    put("after_package", JsonPrimitive(it))
                }
                if (post.afterVisibleTexts.isNotEmpty()) {
                    put(
                        "after_visible_texts",
                        buildJsonArray {
                            post.afterVisibleTexts.forEach { add(JsonPrimitive(it)) }
                        }
                    )
                }
                if (post.appearedTexts.isNotEmpty()) {
                    put(
                        "appeared_texts",
                        buildJsonArray {
                            post.appearedTexts.forEach { add(JsonPrimitive(it)) }
                        }
                    )
                }
                if (post.disappearedTexts.isNotEmpty()) {
                    put(
                        "disappeared_texts",
                        buildJsonArray {
                            post.disappearedTexts.forEach { add(JsonPrimitive(it)) }
                        }
                    )
                }
                post.afterFocusedEditable?.takeIf { it.isNotBlank() }?.let {
                    put("after_focused_editable", JsonPrimitive(it))
                }
                put("post_action_observation", JsonPrimitive(post.summaryText))
            }
        }.toString()
        return VLMConversationRound(
            userMessage = ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(buildCompactHistoryUserMessage(currentUserText, executedStep))
            ),
            assistantMessage = assistantMessage,
            toolMessage = ChatCompletionMessage(
                role = "tool",
                content = JsonPrimitive(toolPayload),
                toolCallId = toolCallId.ifBlank { null }
            )
        )
    }

    internal fun buildCompactHistoryUserMessage(currentUserText: String, executedStep: UIStep): String {
        val actionSummary = when (val action = executedStep.action) {
            is ClickAction -> "click ${action.targetDescription} @(${action.x},${action.y})"
            is InputTextAction -> "input_text ${action.targetDescription} @(${action.x},${action.y})"
            is TypeAction -> "type ${action.content.take(MAX_HISTORY_ACTION_CHARS)}"
            is ScrollAction -> "scroll ${action.targetDescription} ${action.direction.orEmpty()} @(${action.x1},${action.y1})->(${action.x2},${action.y2})"
            is LongPressAction -> "long_press ${action.targetDescription} @(${action.x},${action.y})"
            is OpenAppAction -> "open_app ${action.packageName}"
            is PressHomeAction -> "press_home"
            is PressBackAction -> "press_back"
            is GetStateAction -> "get_state ${action.reason.take(MAX_HISTORY_ACTION_CHARS)}"
            is FinishedAction -> "finished"
            is RequireUserChoiceAction -> "require_user_choice"
            is RequireUserConfirmationAction -> "require_user_confirmation"
            is InfoAction -> "info"
            is FeedbackAction -> "feedback"
            is AbortAction -> "abort"
            is HotKeyAction -> "hot_key ${action.key}"
            is WaitAction -> "wait"
            is RecordAction -> "record"
        }.take(MAX_HISTORY_ACTION_CHARS)
        val pageResult = VLMPostActionObservation.summarize(executedStep)?.summaryText
            ?.take(MAX_HISTORY_OBSERVATION_CHARS)
        return buildString {
            append("Previous turn compact context. ")
            append("Do not use this as current page evidence; use the latest user message and screenshot for grounding. ")
            append("Prior action: ")
            append(actionSummary)
            executedStep.result?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(". Result: ")
                append(it.take(MAX_HISTORY_RESULT_CHARS))
            }
            pageResult?.takeIf { it.isNotBlank() }?.let {
                append(". Post-action observation: ")
                append(it)
            }
            if (currentUserText.contains("用户任务") || currentUserText.contains("User task")) {
                append(". The full previous prompt was intentionally compacted to control tokens.")
            }
        }
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
            val fallbackToolCall = parseTextToolCall(content)
            if (fallbackToolCall != null) {
                return parseSingleToolCall(
                    toolCall = fallbackToolCall,
                    metadata = metadata,
                    thinking = thinking,
                    content = content,
                    reasoning = response.turn.reasoning,
                    source = "text_tool_call"
                )
            }
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

        return parseSingleToolCall(
            toolCall = toolCalls.first(),
            metadata = metadata,
            thinking = thinking,
            content = content,
            reasoning = response.turn.reasoning,
            source = "tool_calls"
        )
    }

    private fun parseSingleToolCall(
        toolCall: AssistantToolCall,
        metadata: StepMetadataPayload,
        thinking: VLMThinkingContext,
        content: String,
        reasoning: String,
        source: String
    ): VLMResult {
        return try {
            val action = parseActionFromToolCall(toolCall)
            val thought = metadata.thought.ifBlank { reasoning.ifBlank { content } }
            if (source != "tool_calls") {
                OmniLog.w(
                    TAG,
                    "Parsed VLM fallback $source as ${toolCall.function.name}: ${preview(content)}"
                )
            }
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
                error = "Failed to parse $source response: ${e.message}",
                thinking = thinking,
                shouldRetryForToolCall = true
            )
        }
    }

    private fun buildMessages(
        systemPrompt: String,
        historyMessages: List<ChatCompletionMessage>,
        currentUserText: String,
        screenshot: String?,
        markedScreenshot: String?,
        context: UIContext,
        retryState: VLMToolCallRetryState?
    ): List<ChatCompletionMessage> {
        val messages = mutableListOf<ChatCompletionMessage>()
        messages += ChatCompletionMessage(
            role = "system",
            content = JsonPrimitive(systemPrompt)
        )
        messages += historyMessages
        messages += buildCurrentUserMessage(currentUserText, screenshot, markedScreenshot)

        if (retryState != null) {
            buildRetryAssistantContent(retryState.thinking)?.let { assistantContent ->
                messages += ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(assistantContent)
                )
            }
            messages += ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(PromptTemplate.buildToolCallRetryPrompt(context, retryState))
            )
        }
        return messages
    }

    private fun buildCurrentUserMessage(
        currentUserText: String,
        screenshot: String?,
        markedScreenshot: String?
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
                if (!screenshot.isNullOrBlank()) {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("Current screenshot."))
                        }
                    )
                    add(buildImageContent(screenshot))
                }
                if (!markedScreenshot.isNullOrBlank()) {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("Marked screenshot with indexes matching OOB indexed page evidence."))
                        }
                    )
                    add(buildImageContent(markedScreenshot))
                }
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
                y = requireFloat(args, "y"),
                elementIndex = optionalInt(args, "element_index")
            )
            "type" -> TypeAction(
                content = requireString(args, "content")
            )
            "input_text" -> InputTextAction(
                targetDescription = requireString(args, "target_description"),
                content = requireString(args, "content", "text", "value"),
                x = requireFloat(args, "x"),
                y = requireFloat(args, "y"),
                elementIndex = optionalInt(args, "element_index")
            )
            "scroll" -> ScrollAction(
                targetDescription = requireString(args, "target_description"),
                x1 = requireFloat(args, "x1"),
                y1 = requireFloat(args, "y1"),
                x2 = requireFloat(args, "x2"),
                y2 = requireFloat(args, "y2"),
                duration = optionalFloat(args, "duration") ?: 1.5f,
                scrollableIndex = optionalInt(args, "scrollable_index"),
                direction = optionalString(args, "direction")?.lowercase()
            )
            "long_press" -> LongPressAction(
                targetDescription = requireString(args, "target_description"),
                x = requireFloat(args, "x"),
                y = requireFloat(args, "y"),
                elementIndex = optionalInt(args, "element_index")
            )
            "open_app" -> OpenAppAction(
                packageName = requireString(args, "package_name")
            )
            "press_home" -> PressHomeAction()
            "press_back" -> PressBackAction()
            "get_state" -> GetStateAction(
                reason = optionalString(args, "reason").orEmpty()
            )
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

    private fun parseArguments(toolName: String, rawArguments: String): JsonObject {
        return VLMToolArgumentParser.parse(toolName, rawArguments)
    }

    private fun parseTextToolCall(content: String): AssistantToolCall? {
        val normalized = content.trim()
        if (normalized.isEmpty()) return null
        return parseJsonTextToolCall(normalized)
            ?: parseTaggedJsonTextToolCall(normalized)
            ?: parseHtmlArgTextToolCall(normalized)
    }

    private fun parseJsonTextToolCall(content: String): AssistantToolCall? {
        val candidates = buildList {
            val toolCallBody = TOOL_CALL_TAG_REGEX.find(content)?.groups?.get(1)?.value?.trim()
            if (!toolCallBody.isNullOrEmpty()) add(toolCallBody)
            extractTopLevelObject(content)?.let(::add)
        }
        candidates.forEach { candidate ->
            val parsed = runCatching {
                json.parseToJsonElement(candidate) as? JsonObject
            }.getOrNull() ?: return@forEach
            val name = parsed["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: parsed["function"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@forEach
            val args = parsed["arguments"]?.let { arguments ->
                when (arguments) {
                    is JsonObject -> arguments.toString()
                    is JsonPrimitive -> arguments.contentOrNull.orEmpty()
                    else -> arguments.toString()
                }
            } ?: "{}"
            return buildFallbackToolCall(name, args)
        }
        return null
    }

    private fun parseTaggedJsonTextToolCall(content: String): AssistantToolCall? {
        val name = Regex("""(?i)<(?:function|tool|name)\s*=\s*name>\s*([^<\s]+)""")
            .find(content)
            ?.groups
            ?.get(1)
            ?.value
            ?.trim()
            ?: return null
        val args = Regex("""(?is)<(?:function|tool|arguments)\s*=\s*arguments>\s*(.*?)\s*</(?:function|tool|arguments)>""")
            .find(content)
            ?.groups
            ?.get(1)
            ?.value
            ?.trim()
            ?: "{}"
        return buildFallbackToolCall(name, args)
    }

    private fun parseHtmlArgTextToolCall(content: String): AssistantToolCall? {
        val closeTagIndex = content.indexOf("</tool_call>", ignoreCase = true)
        if (closeTagIndex < 0) return null
        val prefix = content.take(closeTagIndex)
        val name = toolNames()
            .firstOrNull { toolName ->
                Regex("""(?<![A-Za-z0-9_])${Regex.escape(toolName)}(?![A-Za-z0-9_])""")
                    .containsMatchIn(prefix)
            }
            ?: return null
        val args = buildJsonObject {
            ARG_PAIR_REGEX.findAll(content).forEach { match ->
                val key = match.groups[1]?.value?.trim().orEmpty()
                val value = match.groups[2]?.value?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    put(key, JsonPrimitive(value))
                }
            }
        }
        return buildFallbackToolCall(name, args.toString())
    }

    private fun buildFallbackToolCall(name: String, arguments: String): AssistantToolCall? {
        val normalizedName = normalizeToolName(name) ?: return null
        return AssistantToolCall(
            id = "text_tool_call",
            function = AssistantToolCallFunction(
                name = normalizedName,
                arguments = arguments.trim().ifEmpty { "{}" }
            )
        )
    }

    private fun normalizeToolName(name: String): String? {
        val normalized = name.trim().removePrefix("functions.").removePrefix("function.").trim()
        return toolNames().firstOrNull { it == normalized }
    }

    private fun toolNames(): Set<String> = setOf(
        "click",
        "input_text",
        "type",
        "scroll",
        "long_press",
        "open_app",
        "press_home",
        "press_back",
        "get_state",
        "hot_key",
        "finished",
        "info",
        "feedback",
        "abort",
        "require_user_choice",
        "require_user_confirmation"
    )

    private fun extractTopLevelObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var stringQuote = '\u0000'
        var escaped = false

        for (index in start until raw.length) {
            val ch = raw[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                when (ch) {
                    '\\' -> escaped = true
                    stringQuote -> inString = false
                }
                continue
            }

            when (ch) {
                '"', '\'' -> {
                    inString = true
                    stringQuote = ch
                }
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return raw.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun preview(raw: String, maxLen: Int = 240): String {
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
    }

    private fun requireString(obj: JsonObject, key: String, vararg aliases: String): String {
        val keys = listOf(key) + aliases
        keys.forEach { candidate ->
            val value = obj[candidate]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        throw IllegalArgumentException("Missing or empty '${keys.first()}'")
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

    private fun optionalInt(obj: JsonObject, key: String): Int? {
        val raw = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
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

    private companion object {
        private const val TAG = "VLMClient"
        private const val MAX_HISTORY_ACTION_CHARS = 160
        private const val MAX_HISTORY_RESULT_CHARS = 220
        private const val MAX_HISTORY_OBSERVATION_CHARS = 360
        private val TOOL_CALL_TAG_REGEX = Regex("""(?is)<tool_call>\s*(.*?)\s*</tool_call>""")
        private val ARG_PAIR_REGEX = Regex(
            """(?is)<arg_key>\s*([^<]+?)\s*</arg_key>\s*<arg_value>\s*(.*?)\s*(?=</arg_value>|</tool_call>|```|$)(?:</arg_value>)?"""
        )
    }
}
