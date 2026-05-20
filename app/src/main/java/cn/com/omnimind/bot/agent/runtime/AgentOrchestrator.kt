package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.image.Image
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AgentOrchestrator(
    private val llmClient: AgentLlmClient,
    private val toolRegistry: AgentToolCatalog,
    private val toolRouter: AgentToolExecutor,
    private val eventAdapter: AgentEventAdapter,
    private val model: String
) {
    data class Input(
        val callback: AgentCallback,
        val initialMessages: List<ChatMessage>,
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
                val toolChoiceForRound: AgentToolChoice = if (
                    messages.lastOrNull() is ToolExecutionResultMessage
                ) {
                    AgentToolChoice.None
                } else {
                    AgentToolChoice.Auto
                }
                logInfo(
                    tag,
                    "round=$round request_tools=${toolRegistry.toolSpecifications.size}"
                )
                val turn = llmClient.streamTurn(
                    scene = model,
                    messages = messages.toList(),
                    toolSpecifications = toolRegistry.toolSpecifications,
                    reasoningEffort = input.executionEnv.reasoningEffort,
                    toolChoice = toolChoiceForRound,
                    parallelToolCalls = false,
                    maxCompletionTokens = 16384,
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
                lastPrefillTokensPerSecond = turn.prefillTokensPerSecond ?: lastPrefillTokensPerSecond
                lastDecodeTokensPerSecond = turn.decodeTokensPerSecond ?: lastDecodeTokensPerSecond
                val rawAssistantContent = turn.aiMessage.text().orEmpty().trim()
                lastAssistantContent = combineContinuationContent(
                    prefix = accumulatedAssistantContent,
                    content = rawAssistantContent
                )
                val toolRequests: List<ToolExecutionRequest> =
                    turn.aiMessage.toolExecutionRequests().orEmpty()
                logInfo(
                    tag,
                    "round=$round parsed_tool_calls=${toolRequests.size} finish_reason=${lastFinishReason.orEmpty()} assistant_content_len=${lastAssistantContent.length}"
                )

                messages.add(turn.aiMessage)
                latestPromptTokens = turn.promptTokens
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

                if (toolRequests.isEmpty()) {
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
                // Images from ContextResult tools are appended as follow-up
                // UserMessages, but they cannot be inlined between two
                // ToolExecutionResultMessages — OpenAI requires tool results
                // to be contiguous after the assistant turn that requested
                // them. Collect them and append after the tool loop.
                val pendingImageMessages = mutableListOf<ChatMessage>()
                for (toolRequest in toolRequests) {
                    val toolName = toolRequest.name()
                    val descriptor = toolRegistry.runtimeDescriptor(toolName)
                    val parsedArgs: JsonObject = try {
                        parseToolArguments(toolRequest.arguments())
                    } catch (error: Exception) {
                        val result = ToolExecutionResult.Error(
                            toolName,
                            error.message ?: "Invalid tool arguments JSON"
                        )
                        val failureLearning = buildFailureLearningPayload(
                            env = input.executionEnv,
                            toolName = toolName,
                            descriptor = descriptor,
                            argumentsJson = null,
                            result = result
                        )
                        executedTools.add(result)
                        callback.onToolCallComplete(toolName, result)
                        appendToolResultMessage(
                            messages = messages,
                            pendingImageMessages = pendingImageMessages,
                            toolRequest = toolRequest,
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
                        toolRegistry.validateArguments(toolName, parsedArgs)
                    }.exceptionOrNull()
                    if (validationError != null) {
                        val result = ToolExecutionResult.Error(
                            toolName,
                            validationError.message ?: "Tool arguments validation failed"
                        )
                        val failureLearning = buildFailureLearningPayload(
                            env = input.executionEnv,
                            toolName = toolName,
                            descriptor = descriptor,
                            argumentsJson = parsedArgs.toString(),
                            result = result
                        )
                        executedTools.add(result)
                        callback.onToolCallComplete(toolName, result)
                        appendToolResultMessage(
                            messages = messages,
                            pendingImageMessages = pendingImageMessages,
                            toolRequest = toolRequest,
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
                        toolName = toolName,
                        toolCallId = toolRequest.id()
                    )
                    callback.onToolCallStart(toolName, parsedArgs)
                    val result = try {
                        coroutineScope {
                            val deferred = async {
                                toolRouter.execute(
                                    toolRequest = toolRequest,
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
                                toolName = toolName,
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
                        toolName = toolName,
                        descriptor = descriptor,
                        argumentsJson = parsedArgs.toString(),
                        result = result
                    )
                    callback.onToolCallComplete(toolName, result)
                    appendToolResultMessage(
                        messages = messages,
                        pendingImageMessages = pendingImageMessages,
                        toolRequest = toolRequest,
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
                        toolName == "terminal_execute" ||
                        toolName == "android_privileged_action" ||
                        toolName == "android_privileged_session_start" ||
                        toolName == "android_privileged_session_exec" ||
                        toolName == "android_privileged_session_read" ||
                        toolName == "android_privileged_session_stop"
                    ) {
                        break
                    }
                }

                // Flush queued image attachments AFTER all tool result
                // messages from this round — OpenAI requires the tool result
                // sequence to be contiguous after the assistant turn that
                // emitted the tool calls.
                if (pendingImageMessages.isNotEmpty()) {
                    messages.addAll(pendingImageMessages)
                    pendingImageMessages.clear()
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

    /**
     * Append the tool result to the message list as a LangChain4j
     * [ToolExecutionResultMessage]. If the tool produced an image payload
     * (e.g. screenshot from `ContextToolHandler`), the image is queued in
     * [pendingImageMessages] for appending after the round's tool loop —
     * LangChain4j's `ToolExecutionResultMessage` only carries text, and OpenAI
     * requires the tool result messages following a multi-tool-call assistant
     * turn to be contiguous (no user messages interleaved).
     */
    private fun appendToolResultMessage(
        messages: MutableList<ChatMessage>,
        pendingImageMessages: MutableList<ChatMessage>,
        toolRequest: ToolExecutionRequest,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        result: ToolExecutionResult,
        failureLearning: FailureLearningHookPayload? = null
    ) {
        val textContent = eventAdapter.toolResultContent(
            descriptor = descriptor,
            result = result,
            extras = failureLearning?.toPayload() ?: emptyMap()
        )
        messages.add(
            ToolExecutionResultMessage.from(toolRequest.id().orEmpty(), toolRequest.name(), textContent)
        )

        val imageDataUrl = (result as? ToolExecutionResult.ContextResult)?.imageDataUrl
        if (!imageDataUrl.isNullOrBlank()) {
            pendingImageMessages.add(UserMessage.from(listOf<Content>(buildImageContent(imageDataUrl))))
        }
    }

    private fun buildImageContent(url: String): ImageContent {
        val trimmed = url.trim()
        if (trimmed.startsWith("data:")) {
            val commaIndex = trimmed.indexOf(',')
            if (commaIndex > 5) {
                val header = trimmed.substring(5, commaIndex)
                val payload = trimmed.substring(commaIndex + 1)
                val parts = header.split(';')
                val mime = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "image/png"
                val isBase64 = parts.any { it.equals("base64", ignoreCase = true) }
                if (isBase64) {
                    return ImageContent.from(Image.builder().base64Data(payload).mimeType(mime).build())
                }
            }
        }
        return ImageContent.from(trimmed)
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
        toolName: String,
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
            toolName = toolName,
            toolType = descriptor.toolType,
            argumentsJson = argumentsJson,
            result = result
        ) ?: return null
        return payload.copy(
            logShellPath = env.workspaceManager.shellPathForAndroid(payload.logFile)
        )
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

    private fun buildLengthContinuationMessage(): UserMessage {
        return UserMessage.from(
            "上一条 assistant 回复因为达到输出长度上限被截断。请从中断处继续完成原任务，不要重复已经输出的内容，不要重新开头，不要解释本提示。"
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
