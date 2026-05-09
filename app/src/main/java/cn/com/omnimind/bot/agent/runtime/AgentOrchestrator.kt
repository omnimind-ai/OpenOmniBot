package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentOrchestrator(
    private val llmClient: AgentLlmClient,
    private val toolRegistry: AgentToolCatalog,
    private val toolRouter: AgentToolExecutor,
    private val eventAdapter: AgentEventAdapter,
    private val model: String
) {
    data class Input(
        val callback: AgentCallback,
        val initialMessages: List<ChatCompletionMessage>,
        val executionEnv: AgentExecutionEnvironment,
        val conversationId: Long? = null,
        val contextCompactor: AgentConversationContextCompactor? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val tag = "AgentOrchestrator"
    private val maxLengthContinuationRounds = 3

    private fun t(zh: String, en: String): String {
        return if (AppLocaleManager.isEnglish()) en else zh
    }

    suspend fun run(input: Input): AgentResult {
        val callback = input.callback
        var messages = input.initialMessages.toMutableList()
        val executedTools = mutableListOf<ToolExecutionResult>()
        var outputKind = AgentOutputKind.NONE
        var hasUserFacingOutput = false
        var lastAssistantContent = ""
        var accumulatedAssistantContent = ""
        var lastFinishReason: String? = null
        var latestPromptTokens: Int? = null
        var latestPromptTokenThreshold: Int? = null
        var lastPrefillTokensPerSecond: Double? = null
        var lastDecodeTokensPerSecond: Double? = null
        var completedModelRounds = 0
        var lengthContinuationRounds = 0
        var terminated = false

        try {
            roundLoop@ while (true) {
                completedModelRounds += 1
                val round = completedModelRounds
                val assistantContentPrefix = accumulatedAssistantContent
                callback.onThinkingStart()
                val toolChoiceForRound = if (messages.lastOrNull()?.role == "tool") {
                    null
                } else {
                    JsonPrimitive("auto")
                }
                logInfo(
                    tag,
                    "round=$round request_tools=${toolRegistry.toolsForModel.size}"
                )
                val disableThinking = input.executionEnv.reasoningEffort == "no"
                val turn = llmClient.streamTurn(
                    request = ChatCompletionRequest(
                        messages = messages.toList(),
                        model = model,
                        maxCompletionTokens = 16384,
                        stream = true,
                        streamOptions = ChatCompletionStreamOptions(includeUsage = true),
                        enableThinking = if (disableThinking) false else null,
                        reasoningEffort = if (disableThinking) null else input.executionEnv.reasoningEffort,
                        tools = toolRegistry.toolsForModel,
                        toolChoice = toolChoiceForRound,
                        parallelToolCalls = false
                    ),
                    onReasoningUpdate = { reasoning ->
                        if (reasoning.isNotBlank()) {
                            callback.onThinkingUpdate(normalizeThinkingText(reasoning))
                        }
                    },
                    onContentUpdate = { content ->
                        if (content.isNotBlank()) {
                            callback.onChatMessage(
                                combineContinuationContent(
                                    prefix = assistantContentPrefix,
                                    content = content
                                ),
                                false
                            )
                        }
                    }
                )

                lastFinishReason = turn.finishReason
                lastPrefillTokensPerSecond =
                    turn.usage?.prefillTokensPerSecond ?: lastPrefillTokensPerSecond
                lastDecodeTokensPerSecond =
                    turn.usage?.decodeTokensPerSecond ?: lastDecodeTokensPerSecond
                val rawAssistantContent = turn.message.contentText().trim()
                lastAssistantContent = combineContinuationContent(
                    prefix = accumulatedAssistantContent,
                    content = rawAssistantContent
                )
                val toolCalls = turn.message.toolCalls.orEmpty()
                logInfo(
                    tag,
                    "round=$round parsed_tool_calls=${toolCalls.size} finish_reason=${lastFinishReason.orEmpty()} assistant_content_len=${lastAssistantContent.length}"
                )

                messages.add(
                    ChatCompletionMessage(
                        role = "assistant",
                        content = normalizeAssistantContentForNextRound(
                            content = turn.message.content,
                            toolCalls = toolCalls
                        ),
                        toolCalls = toolCalls.ifEmpty { null },
                        reasoningContent = turn.message.reasoningContent
                            ?.takeIf { it.isNotBlank() }
                    )
                )
                latestPromptTokens = turn.usage?.promptTokens
                latestPromptTokenThreshold =
                    input.contextCompactor?.resolvePromptTokenThreshold(input.conversationId)
                latestPromptTokens?.let { promptTokens ->
                    callback.onPromptTokenUsageChanged(
                        latestPromptTokens = promptTokens,
                        promptTokenThreshold = latestPromptTokenThreshold
                    )
                }
                input.contextCompactor?.let { compactor ->
                    messages = compactor.compactIfNeeded(
                        conversationId = input.conversationId,
                        conversationMode = input.executionEnv.conversationMode,
                        promptTokens = latestPromptTokens,
                        messages = messages,
                        promptTokenThresholdOverride = latestPromptTokenThreshold,
                        callback = callback
                    ).toMutableList()
                }

                if (toolCalls.isEmpty()) {
                    if (
                        isLengthFinishReason(lastFinishReason) &&
                        rawAssistantContent.isNotBlank() &&
                        lengthContinuationRounds < maxLengthContinuationRounds
                    ) {
                        lengthContinuationRounds += 1
                        accumulatedAssistantContent = lastAssistantContent
                        messages.add(buildLengthContinuationMessage())
                        logInfo(
                            tag,
                            "round=$round finish_reason=${lastFinishReason.orEmpty()} auto_continue=$lengthContinuationRounds/${maxLengthContinuationRounds} accumulated_content_len=${accumulatedAssistantContent.length}"
                        )
                        continue@roundLoop
                    }
                    val fallbackMessage = lastAssistantContent.ifBlank {
                        "我已完成思考，但暂时无法生成回复，请重试。"
                    }
                    callback.onChatMessage(
                        fallbackMessage,
                        true,
                        lastPrefillTokensPerSecond,
                        lastDecodeTokensPerSecond
                    )
                    executedTools.add(ToolExecutionResult.ChatMessage(fallbackMessage))
                    outputKind = AgentOutputKind.CHAT_MESSAGE
                    hasUserFacingOutput = true
                    terminated = true
                    break
                }
                accumulatedAssistantContent = ""
                lengthContinuationRounds = 0

                var advanceToNextRound = false
                for (toolCall in toolCalls) {
                    val descriptor = toolRegistry.runtimeDescriptor(toolCall.function.name)
                    val parsedArgs: JsonObject = try {
                        parseToolArguments(toolCall.function.arguments)
                    } catch (error: Exception) {
                        val result = ToolExecutionResult.Error(
                            toolCall.function.name,
                            error.message ?: "Invalid tool arguments JSON"
                        )
                        val failureLearning = buildFailureLearningPayload(
                            env = input.executionEnv,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            argumentsJson = null,
                            result = result
                        )
                        executedTools.add(result)
                        callback.onToolCallComplete(toolCall.function.name, result)
                        appendToolResultMessage(
                            messages = messages,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            result = result,
                            failureLearning = failureLearning
                        )
                        hasUserFacingOutput =
                            hasUserFacingOutput || eventAdapter.hasUserVisibleOutput(result)
                        advanceToNextRound = true
                        break
                    }

                    val validationError = runCatching {
                        toolRegistry.validateArguments(toolCall.function.name, parsedArgs)
                    }.exceptionOrNull()
                    if (validationError != null) {
                        val result = ToolExecutionResult.Error(
                            toolCall.function.name,
                            validationError.message ?: "Tool arguments validation failed"
                        )
                        val failureLearning = buildFailureLearningPayload(
                            env = input.executionEnv,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            argumentsJson = parsedArgs.toString(),
                            result = result
                        )
                        executedTools.add(result)
                        callback.onToolCallComplete(toolCall.function.name, result)
                        appendToolResultMessage(
                            messages = messages,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            result = result,
                            failureLearning = failureLearning
                        )
                        hasUserFacingOutput =
                            hasUserFacingOutput || eventAdapter.hasUserVisibleOutput(result)
                        advanceToNextRound = true
                        break
                    }

                    val toolHandle = input.executionEnv.runControl.beginToolExecution(
                        toolName = toolCall.function.name,
                        toolCallId = toolCall.id
                    )
                    callback.onToolCallStart(toolCall.function.name, parsedArgs)
                    val result = try {
                        coroutineScope {
                            val deferred = async {
                                toolRouter.execute(
                                    toolCall = toolCall,
                                    args = parsedArgs,
                                    runtimeDescriptor = descriptor,
                                    env = input.executionEnv,
                                    callback = callback,
                                    toolHandle = toolHandle
                                )
                            }
                            toolHandle.bindExecutionJob(deferred)
                            deferred.await()
                        }
                    } catch (error: CancellationException) {
                        if (toolHandle.isManualStopRequested()) {
                            buildInterruptedToolResult(
                                toolName = toolCall.function.name,
                                toolHandle = toolHandle
                            )
                        } else {
                            throw error
                        }
                    } finally {
                        toolHandle.complete()
                    }

                    executedTools.add(result)
                    val failureLearning = buildFailureLearningPayload(
                        env = input.executionEnv,
                        toolCall = toolCall,
                        descriptor = descriptor,
                        argumentsJson = parsedArgs.toString(),
                        result = result
                    )
                    callback.onToolCallComplete(toolCall.function.name, result)
                    appendToolResultMessage(
                        messages = messages,
                        toolCall = toolCall,
                        descriptor = descriptor,
                        result = result,
                        failureLearning = failureLearning
                    )

                    if (eventAdapter.hasUserVisibleOutput(result)) {
                        hasUserFacingOutput = true
                    }
                    val mappedKind = eventAdapter.mapOutputKind(result)
                    if (mappedKind != AgentOutputKind.NONE) {
                        outputKind = mappedKind
                    }

                    if (eventAdapter.isConversationStoppingResult(result)) {
                        terminated = true
                        break@roundLoop
                    }
                    if (
                        toolCall.function.name == "terminal_execute" ||
                        toolCall.function.name == "android_privileged_action" ||
                        toolCall.function.name == "android_privileged_session_start" ||
                        toolCall.function.name == "android_privileged_session_exec" ||
                        toolCall.function.name == "android_privileged_session_read" ||
                        toolCall.function.name == "android_privileged_session_stop"
                    ) {
                        break
                    }
                }

                if (terminated) {
                    break
                }
                if (advanceToNextRound) {
                    continue@roundLoop
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callback.onError("Agent execution failed: ${e.message}")
            return AgentResult.Error("Agent execution failed", e as? Exception)
        } finally {
            runCatching { toolRouter.dispose() }
        }

        if (!hasUserFacingOutput) {
            val fallbackMessage = lastAssistantContent.ifBlank {
                t(
                    "我已完成思考，但暂时无法生成回复，请重试。",
                    "I finished reasoning, but I couldn't produce a reply just now. Please try again."
                )
            }
            callback.onChatMessage(
                fallbackMessage,
                true,
                lastPrefillTokensPerSecond,
                lastDecodeTokensPerSecond
            )
            executedTools.add(ToolExecutionResult.ChatMessage(fallbackMessage))
            outputKind = AgentOutputKind.CHAT_MESSAGE
            hasUserFacingOutput = true
        }

        val finalResult = AgentResult.Success(
            response = AgentFinalResponse(
                content = lastAssistantContent,
                finishReason = lastFinishReason,
                latestPromptTokens = latestPromptTokens,
                promptTokenThreshold = latestPromptTokens?.let { latestPromptTokenThreshold }
            ),
            executedTools = executedTools,
            outputKind = outputKind.value,
            hasUserVisibleOutput = hasUserFacingOutput,
            latestPromptTokens = latestPromptTokens,
            promptTokenThreshold = latestPromptTokens?.let { latestPromptTokenThreshold }
        )
        callback.onComplete(finalResult)
        return finalResult
    }

    private fun appendToolResultMessage(
        messages: MutableList<ChatCompletionMessage>,
        toolCall: AssistantToolCall,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        result: ToolExecutionResult,
        failureLearning: FailureLearningHookPayload? = null
    ) {
        val textContent = eventAdapter.toolResultContent(
            descriptor = descriptor,
            result = result,
            extras = failureLearning?.toPayload() ?: emptyMap()
        )
        val imageDataUrl = (result as? ToolExecutionResult.ContextResult)?.imageDataUrl

        val content: JsonElement = if (imageDataUrl != null) {
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", textContent)
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", imageDataUrl)
                    })
                })
            }
        } else {
            JsonPrimitive(textContent)
        }

        messages.add(
            ChatCompletionMessage(
                role = "tool",
                toolCallId = toolCall.id,
                content = content
            )
        )
    }

    private fun buildInterruptedToolResult(
        toolName: String,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult.Interrupted {
        val snapshot = toolHandle.latestProgressSnapshot()
        val interruptedSummary = t(
            "工具调用已被用户手动停止",
            "Tool call was stopped manually by the user."
        )
        val rawPayload = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "status" to "interrupted",
            "summary" to interruptedSummary,
            "interruptedBy" to "user",
            "interruptionReason" to "manual_stop"
        ).apply {
            if (snapshot.summary.isNotBlank()) {
                put("lastProgress", snapshot.summary)
            }
            snapshot.extras.forEach { (key, value) ->
                put(key, value)
            }
        }
        val encodedPayload = json.encodeToString(mapToJsonElement(rawPayload))
        return ToolExecutionResult.Interrupted(
            toolName = toolName,
            summaryText = interruptedSummary,
            previewJson = encodedPayload,
            rawResultJson = encodedPayload,
            terminalOutput = snapshot.extras["terminalOutput"]?.toString().orEmpty().ifBlank {
                snapshot.extras["terminalOutputDelta"]?.toString().orEmpty()
            },
            terminalSessionId = snapshot.extras["terminalSessionId"]?.toString(),
            terminalStreamState = snapshot.extras["terminalStreamState"]?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: "interrupted"
        )
    }

    private fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> kotlinx.serialization.json.JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun buildFailureLearningPayload(
        env: AgentExecutionEnvironment,
        toolCall: AssistantToolCall,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        argumentsJson: String?,
        result: ToolExecutionResult
    ): FailureLearningHookPayload? {
        if (!SelfImprovingSkillFailureHook.shouldHandle(result)) {
            return null
        }
        val skill = env.failureLearningSkill ?: return null
        val payload = SelfImprovingSkillFailureHook.capture(
            skillsRoot = env.workspaceManager.skillsRoot(),
            skill = skill,
            userMessage = env.userMessage,
            toolName = toolCall.function.name,
            toolType = descriptor.toolType,
            argumentsJson = argumentsJson,
            result = result
        ) ?: return null
        return payload.copy(
            logShellPath = env.workspaceManager.shellPathForAndroid(payload.logFile)
        )
    }

    private fun normalizeAssistantContentForNextRound(
        content: JsonElement?,
        toolCalls: List<AssistantToolCall>
    ): JsonElement? {
        if (toolCalls.isEmpty()) {
            return content
        }
        return when (content) {
            null -> JsonPrimitive("")
            is JsonPrimitive -> {
                if (content.isString && content.content.isBlank()) {
                    JsonPrimitive("")
                } else {
                    content
                }
            }

            else -> content
        }
    }

    private fun parseToolArguments(argumentsJson: String): JsonObject {
        val normalized = argumentsJson.trim()
        if (normalized.isEmpty()) return JsonObject(emptyMap())
        val parsed = json.decodeFromString<JsonElement>(normalized)
        return parsed as? JsonObject
            ?: throw IllegalArgumentException("tool arguments must be a JSON object")
    }

    private fun normalizeThinkingText(text: String): String {
        val normalized = if ('\r' in text) {
            text.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            text
        }
        return normalized.trim()
    }

    private fun isLengthFinishReason(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase().orEmpty()
        return normalized == "length" ||
            normalized == "max_tokens" ||
            normalized == "max_completion_tokens"
    }

    private fun buildLengthContinuationMessage(): ChatCompletionMessage {
        return ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(
                "上一条 assistant 回复因为达到输出长度上限被截断。请从中断处继续完成原任务，不要重复已经输出的内容，不要重新开头，不要解释本提示。"
            )
        )
    }

    private fun combineContinuationContent(prefix: String, content: String): String {
        val normalizedPrefix = AgentTextSanitizer.sanitizeUtf16(prefix).trim()
        val normalizedContent = AgentTextSanitizer.sanitizeUtf16(content).trim()
        if (normalizedPrefix.isEmpty()) return normalizedContent
        if (normalizedContent.isEmpty()) return normalizedPrefix
        if (normalizedContent.startsWith(normalizedPrefix)) return normalizedContent
        if (normalizedPrefix.startsWith(normalizedContent)) return normalizedPrefix

        val maxOverlap = minOf(
            normalizedPrefix.length,
            normalizedContent.length,
            2048
        )
        for (overlap in maxOverlap downTo 1) {
            val prefixStart = normalizedPrefix.length - overlap
            if (
                normalizedPrefix.regionMatches(
                    thisOffset = prefixStart,
                    other = normalizedContent,
                    otherOffset = 0,
                    length = overlap,
                    ignoreCase = false
                )
            ) {
                return normalizedPrefix + normalizedContent.substring(overlap)
            }
        }
        return normalizedPrefix + normalizedContent
    }

    private fun logInfo(tag: String, message: String) {
        runCatching { OmniLog.i(tag, message) }
    }
}
