package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.tool.AgentToolConcurrencyPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private val model: String,
    private val toolImageContinuationPolicy: AgentToolImageContinuationPolicy =
        AgentToolImageContinuationPolicy.DEFAULT
) {
    private data class RetryDecision(
        val retryable: Boolean,
        val reason: String
    )

    private class ExhaustedRetryableTurnFailure(
        val errorMessage: String,
        cause: Throwable
    ) : RuntimeException(errorMessage, cause)

    private data class TextOnlyStopDecision(
        val allowFinish: Boolean,
        val shouldRecover: Boolean,
        val taskStillExecuting: Boolean,
        val completeFinalAnswer: Boolean,
        val reason: String
    )

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
    private val maxMissingToolCallRecoveryRounds = 1
    private val maxTurnRequestRetries = 3
    private val turnRetryDelaysMs = listOf(500L, 1500L, 3000L)

    private fun t(zh: String, en: String): String {
        return if (AppLocaleManager.isEnglish()) en else zh
    }

    suspend fun run(input: Input): AgentResult {
        val callback = input.callback
        val memory: AgentChatMemory = MutableListChatMemory(input.initialMessages)
        val primaryUserGoal = resolvePrimaryUserGoal(input)
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
        var missingToolCallRecoveryRounds = 0
        var terminated = false
        var terminalError: AgentResult.Error? = null

        try {
            roundLoop@ while (true) {
                completedModelRounds += 1
                val round = completedModelRounds
                val assistantContentPrefix = accumulatedAssistantContent
                callback.onThinkingStart()
                val roundStartsAfterToolResult = memory.lastRole() == "tool"
                val toolChoiceForRound = if (roundStartsAfterToolResult) {
                    null
                } else {
                    JsonPrimitive("auto")
                }
                logInfo(
                    tag,
                    "round=$round request_tools=${toolRegistry.toolsForModel.size}"
                )
                val disableThinking = input.executionEnv.reasoningEffort == "no"
                val turn = try {
                    streamTurnWithRetry(
                        callback = callback,
                        request = ChatCompletionRequest(
                            messages = memory.snapshot(),
                            model = model,
                            maxCompletionTokens = 16384,
                            stream = true,
                            streamOptions = ChatCompletionStreamOptions(includeUsage = true),
                            enableThinking = if (disableThinking) false else null,
                            reasoningEffort = if (disableThinking) null else input.executionEnv.reasoningEffort,
                            tools = toolRegistry.toolsForModel,
                            toolChoice = toolChoiceForRound,
                            parallelToolCalls = true
                        ),
                        assistantContentPrefix = assistantContentPrefix
                    )
                } catch (e: ExhaustedRetryableTurnFailure) {
                    callback.onError(e.errorMessage, true)
                    terminalError = AgentResult.Error(e.errorMessage, e)
                    terminated = true
                    break@roundLoop
                }

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

                memory.add(
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
                    val compacted = compactor.compactIfNeeded(
                        conversationId = input.conversationId,
                        conversationMode = input.executionEnv.conversationMode,
                        promptTokens = latestPromptTokens,
                        messages = memory.snapshot(),
                        promptTokenThresholdOverride = latestPromptTokenThreshold,
                        callback = callback
                    )
                    memory.replaceAll(compacted)
                }

                if (toolCalls.isEmpty()) {
                    if (
                        isLengthFinishReason(lastFinishReason) &&
                        rawAssistantContent.isNotBlank() &&
                        lengthContinuationRounds < maxLengthContinuationRounds
                    ) {
                        lengthContinuationRounds += 1
                        accumulatedAssistantContent = lastAssistantContent
                        memory.add(buildLengthContinuationMessage())
                        logInfo(
                            tag,
                            "round=$round finish_reason=${lastFinishReason.orEmpty()} auto_continue=$lengthContinuationRounds/${maxLengthContinuationRounds} accumulated_content_len=${accumulatedAssistantContent.length}"
                        )
                        continue@roundLoop
                    }
                    val textOnlyStopDecision = evaluateTextOnlyStopDecision(
                        finishReason = lastFinishReason,
                        assistantContent = lastAssistantContent,
                        userGoal = primaryUserGoal,
                        roundStartsAfterToolResult = roundStartsAfterToolResult,
                        hasPriorToolCall = executedTools.any { it !is ToolExecutionResult.ChatMessage }
                    )
                    logInfo(
                        tag,
                        "round=$round text_only_stop allow_finish=${textOnlyStopDecision.allowFinish} " +
                            "should_recover=${textOnlyStopDecision.shouldRecover} " +
                            "task_still_executing=${textOnlyStopDecision.taskStillExecuting} " +
                            "complete_final_answer=${textOnlyStopDecision.completeFinalAnswer} " +
                            "reason=${textOnlyStopDecision.reason}"
                    )
                    if (
                        textOnlyStopDecision.shouldRecover &&
                        missingToolCallRecoveryRounds < maxMissingToolCallRecoveryRounds
                    ) {
                        missingToolCallRecoveryRounds += 1
                        memory.add(buildMissingToolCallRecoveryMessage())
                        logInfo(
                            tag,
                            "round=$round no_tool_call_with_action_intent " +
                                "auto_recover=$missingToolCallRecoveryRounds/$maxMissingToolCallRecoveryRounds"
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
                missingToolCallRecoveryRounds = 0

                var advanceToNextRound = false
                val descriptorMap = mutableMapOf<String, AgentToolRegistry.RuntimeToolDescriptor>()
                val parsedArgsMap = mutableMapOf<String, JsonObject>()
                val validatedCalls = mutableListOf<AssistantToolCall>()

                // Phase A — parse + validate all tool arguments synchronously.
                // Any parse/validation failure aborts the current turn's tool execution
                // (matching pre-refactor semantics: write the error tool message,
                // skip remaining calls, and advance to the next LLM round).
                parsePhase@ for (toolCall in toolCalls) {
                    val descriptor = toolRegistry.runtimeDescriptor(toolCall.function.name)
                    descriptorMap[toolCall.id] = descriptor
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
                            memory = memory,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            result = result,
                            failureLearning = failureLearning
                        )
                        hasUserFacingOutput =
                            hasUserFacingOutput || eventAdapter.hasUserVisibleOutput(result)
                        advanceToNextRound = true
                        break@parsePhase
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
                            memory = memory,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            result = result,
                            failureLearning = failureLearning
                        )
                        hasUserFacingOutput =
                            hasUserFacingOutput || eventAdapter.hasUserVisibleOutput(result)
                        advanceToNextRound = true
                        break@parsePhase
                    }
                    parsedArgsMap[toolCall.id] = parsedArgs
                    validatedCalls.add(toolCall)
                }

                // Phase B — partition validated calls into batches and execute.
                if (validatedCalls.isNotEmpty()) {
                    val batches = AgentToolConcurrencyPolicy.partitionToolCalls(
                        validatedCalls,
                        parsedArgsMap
                    )
                    logInfo(
                        tag,
                        "round=$round batches=${batches.size} " +
                            batches.joinToString(separator = ",") { batch ->
                                "${if (batch.parallel) "P" else "S"}${batch.calls.size}"
                            }
                    )

                    batchLoop@ for (batch in batches) {
                        val batchResults: List<Pair<AssistantToolCall, ToolExecutionResult>>
                        if (batch.parallel && batch.calls.size > 1) {
                            // Parallel batch: launch async per call. callback.onToolCallStart /
                            // onToolCallComplete fire from inside each async (lets UI update each
                            // card independently). State mutation + memory append happen serially
                            // below to preserve ToolCall ↔ ToolMessage pairing order.
                            batchResults = coroutineScope {
                                batch.calls.map { call ->
                                    async {
                                        val desc = descriptorMap.getValue(call.id)
                                        val args = parsedArgsMap.getValue(call.id)
                                        val result = executeSingleTool(
                                            env = input.executionEnv,
                                            callback = callback,
                                            toolCall = call,
                                            descriptor = desc,
                                            parsedArgs = args
                                        )
                                        callback.onToolCallComplete(call.function.name, result)
                                        call to result
                                    }
                                }.awaitAll()
                            }
                        } else {
                            // Serial batch (single call or barrier).
                            val singles = mutableListOf<Pair<AssistantToolCall, ToolExecutionResult>>()
                            for (call in batch.calls) {
                                val desc = descriptorMap.getValue(call.id)
                                val args = parsedArgsMap.getValue(call.id)
                                val result = executeSingleTool(
                                    env = input.executionEnv,
                                    callback = callback,
                                    toolCall = call,
                                    descriptor = desc,
                                    parsedArgs = args
                                )
                                callback.onToolCallComplete(call.function.name, result)
                                singles.add(call to result)
                            }
                            batchResults = singles
                        }

                        var breakBatchLoopAfterPost = false
                        // Phase C — serial post-process: write results back to memory in
                        // original call order, accumulate UI state, honor stop conditions.
                        for ((call, result) in batchResults) {
                            val desc = descriptorMap.getValue(call.id)
                            val args = parsedArgsMap.getValue(call.id)
                            executedTools.add(result)
                            val failureLearning = buildFailureLearningPayload(
                                env = input.executionEnv,
                                toolCall = call,
                                descriptor = desc,
                                argumentsJson = args.toString(),
                                result = result
                            )
                            appendToolResultMessage(
                                memory = memory,
                                toolCall = call,
                                descriptor = desc,
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
                                call.function.name == "terminal_execute" ||
                                call.function.name == "android_privileged_action" ||
                                call.function.name == "android_privileged_session_start" ||
                                call.function.name == "android_privileged_session_exec" ||
                                call.function.name == "android_privileged_session_read" ||
                                call.function.name == "android_privileged_session_stop"
                            ) {
                                breakBatchLoopAfterPost = true
                            }
                        }
                        if (breakBatchLoopAfterPost) {
                            break@batchLoop
                        }
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

        terminalError?.let { return it }

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

    private suspend fun streamTurnWithRetry(
        callback: AgentCallback,
        request: ChatCompletionRequest,
        assistantContentPrefix: String
    ): ChatCompletionTurn {
        var retryCount = 0
        while (true) {
            try {
                return llmClient.streamTurn(
                    request = request,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val decision = classifyRetryableTurnFailure(e)
                val canRetry = decision.retryable && retryCount < maxTurnRequestRetries
                if (!canRetry) {
                    if (decision.retryable) {
                        throw ExhaustedRetryableTurnFailure(
                            errorMessage = decision.reason,
                            cause = e
                        )
                    }
                    throw e
                }

                retryCount += 1
                val retryDelayMs = turnRetryDelaysMs
                    .getOrElse(retryCount - 1) { turnRetryDelaysMs.last() }
                callback.onRetrying(
                    retryCount = retryCount,
                    maxRetries = maxTurnRequestRetries,
                    retryDelayMs = retryDelayMs,
                    message = buildRetryingStatusMessage(retryCount, maxTurnRequestRetries),
                    retryReason = decision.reason
                )
                delay(retryDelayMs)
            }
        }
    }

    private suspend fun executeSingleTool(
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolCall: AssistantToolCall,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        parsedArgs: JsonObject
    ): ToolExecutionResult {
        val toolHandle = env.runControl.beginToolExecution(
            toolName = toolCall.function.name,
            toolCallId = toolCall.id
        )
        callback.onToolCallStart(toolCall.function.name, parsedArgs)
        return try {
            coroutineScope {
                val deferred = async {
                    toolRouter.execute(
                        toolCall = toolCall,
                        args = parsedArgs,
                        runtimeDescriptor = descriptor,
                        env = env,
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
    }

    private fun appendToolResultMessage(
        memory: AgentChatMemory,
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
        val imageDataUrl = (result as? ToolExecutionResult.ContextResult)
            ?.imageDataUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { toolImageContinuationPolicy.supportsToolImageContinuation }
        if (
            result is ToolExecutionResult.ContextResult &&
            !result.imageDataUrl.isNullOrBlank() &&
            imageDataUrl == null
        ) {
            logInfo(
                tag,
                "skip_tool_image_continuation tool=${toolCall.function.name} route=${toolImageContinuationPolicy.routeLabel}"
            )
        }

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

        memory.add(
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

    private fun isStopFinishReason(reason: String?): Boolean {
        return reason?.trim()?.lowercase() == "stop"
    }

    private fun classifyRetryableTurnFailure(error: Throwable): RetryDecision {
        if (error is AgentStreamRequestException) {
            val statusCode = error.statusCode
            val retryableStatus = statusCode == 408 ||
                statusCode == 429 ||
                statusCode == 502 ||
                statusCode == 503 ||
                statusCode == 504
            val retryableReason = looksLikeTransientTransportFailure(error.reason)
            if (retryableStatus || retryableReason) {
                return RetryDecision(
                    retryable = true,
                    reason = formatTurnFailureReason(statusCode, error.reason)
                )
            }
            return RetryDecision(
                retryable = false,
                reason = error.reason
            )
        }

        val message = error.message?.trim().orEmpty()
        if (looksLikeTransientTransportFailure(message)) {
            return RetryDecision(
                retryable = true,
                reason = formatTurnFailureReason(null, message)
            )
        }
        return RetryDecision(
            retryable = false,
            reason = message.ifEmpty { error::class.java.simpleName }
        )
    }

    private fun looksLikeTransientTransportFailure(message: String): Boolean {
        if (message.isBlank()) return false
        val normalized = message.lowercase()
        return normalized.contains("idle timeout") ||
            normalized.contains("closed before completion signal") ||
            normalized.contains("unknown stream failure") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out") ||
            normalized.contains("connection reset") ||
            normalized.contains("unexpected end of stream") ||
            normalized.contains("software caused connection abort") ||
            normalized.contains("broken pipe") ||
            normalized.contains("connection refused") ||
            normalized.contains("connection aborted") ||
            normalized.contains("eofexception") ||
            normalized.contains("protocol_error") ||
            normalized.contains("stream was reset") ||
            normalized.contains("network") ||
            normalized.contains("temporarily unavailable")
    }

    private fun formatTurnFailureReason(statusCode: Int?, reason: String): String {
        val normalizedReason = reason.trim().ifEmpty {
            t("请求失败，请稍后重试。", "Request failed. Please try again later.")
        }
        return statusCode?.let { "HTTP $it: $normalizedReason" } ?: normalizedReason
    }

    private fun buildRetryingStatusMessage(retryCount: Int, maxRetries: Int): String {
        return t(
            "连接中断，正在重试 $retryCount/$maxRetries…",
            "Connection interrupted. Retrying $retryCount/$maxRetries..."
        )
    }

    private fun resolvePrimaryUserGoal(input: Input): String {
        val envUserMessage = AgentTextSanitizer.sanitizeUtf16(input.executionEnv.userMessage).trim()
        if (envUserMessage.isNotEmpty()) {
            return envUserMessage
        }
        return input.initialMessages
            .asReversed()
            .firstOrNull { it.role == "user" }
            ?.contentText()
            ?.let(AgentTextSanitizer::sanitizeUtf16)
            ?.trim()
            .orEmpty()
    }

    private fun evaluateTextOnlyStopDecision(
        finishReason: String?,
        assistantContent: String,
        userGoal: String,
        roundStartsAfterToolResult: Boolean,
        hasPriorToolCall: Boolean
    ): TextOnlyStopDecision {
        if (!isStopFinishReason(finishReason)) {
            return TextOnlyStopDecision(
                allowFinish = true,
                shouldRecover = false,
                taskStillExecuting = false,
                completeFinalAnswer = true,
                reason = "non_stop_finish_reason"
            )
        }
        val normalized = AgentTextSanitizer.sanitizeUtf16(assistantContent).trim()
        if (normalized.isEmpty()) {
            return TextOnlyStopDecision(
                allowFinish = true,
                shouldRecover = false,
                taskStillExecuting = false,
                completeFinalAnswer = false,
                reason = "blank_text_reply"
            )
        }
        val actionGoal = userGoalRequiresExternalAction(userGoal)
        val actionIntent = containsActionIntentWithoutToolCall(normalized)
        val intermediateUpdate = looksLikeIntermediateExecutionUpdate(normalized)
        val explicitFinalCue = containsExplicitFinalAnswerCue(normalized)
        val answerTooThinForActionGoal =
            actionGoal &&
                normalized.length < 18 &&
                !explicitFinalCue &&
                !roundStartsAfterToolResult
        val completeFinalAnswer =
            looksLikeCompleteFinalAnswer(
                content = normalized,
                explicitFinalCue = explicitFinalCue,
                actionIntent = actionIntent,
                intermediateUpdate = intermediateUpdate,
                answerTooThinForActionGoal = answerTooThinForActionGoal
            )
        val taskStillExecuting =
            roundStartsAfterToolResult ||
                (hasPriorToolCall && (actionIntent || intermediateUpdate)) ||
                actionIntent ||
                intermediateUpdate ||
                answerTooThinForActionGoal
        val shouldRecover = taskStillExecuting && !completeFinalAnswer
        val reason = when {
            roundStartsAfterToolResult && !completeFinalAnswer -> "pending_tool_chain"
            actionIntent && !completeFinalAnswer -> "action_intent_without_tool_call"
            intermediateUpdate && !completeFinalAnswer -> "intermediate_status_without_result"
            answerTooThinForActionGoal -> "action_goal_reply_too_thin"
            completeFinalAnswer -> "complete_final_answer"
            else -> "plain_text_terminal_reply"
        }
        return TextOnlyStopDecision(
            allowFinish = !shouldRecover,
            shouldRecover = shouldRecover,
            taskStillExecuting = taskStillExecuting,
            completeFinalAnswer = completeFinalAnswer,
            reason = reason
        )
    }

    private fun containsActionIntentWithoutToolCall(content: String): Boolean {
        val normalized = content.lowercase()
        val chineseActionCues = listOf(
            "让我查一下",
            "让我查一查",
            "让我查询一下",
            "我先查一下",
            "我先查询一下",
            "让我检查一下",
            "我先检查一下",
            "让我搜一下",
            "让我搜索一下",
            "我先搜一下",
            "我先搜索一下",
            "让我看一下",
            "让我看一看",
            "我先看一下",
            "我先看一看",
            "我去查一下",
            "我去看一下"
        )
        if (chineseActionCues.any(normalized::contains)) {
            return true
        }
        val chineseActionIntent = Regex(
            """(?:让我|我)(?:先|再|再一次|最后一次|最后再|去)?(?:查找|寻找|查询|检查|搜索|搜|查看|看|核实|确认|回到|返回|回去|尝试|试着|筛选)""",
            RegexOption.IGNORE_CASE
        )
        if (chineseActionIntent.containsMatchIn(content)) {
            return true
        }
        val chineseDeferredActionIntent = Regex(
            """(?:让我|我来|我会|我将|我先|我再|我去|我来为您|我来帮您|我帮您)(?:[^。！？；，,\n]{0,16})?(?:查找|寻找|查询|检查|搜索|搜|查看|看|核实|确认|回到|返回|回去|尝试|试着|筛选|过滤|定位|打开|点击|进入|读取|执行|运行)""",
            RegexOption.IGNORE_CASE
        )
        if (chineseDeferredActionIntent.containsMatchIn(content)) {
            return true
        }
        val englishActionIntent = Regex(
            """\b(?:let me|i(?:'ll| will)|first,\s*let me)\s+(?:check|look|search|verify|see|find|try|return)\b""",
            RegexOption.IGNORE_CASE
        )
        return englishActionIntent.containsMatchIn(content)
    }

    private fun userGoalRequiresExternalAction(content: String): Boolean {
        val normalized = AgentTextSanitizer.sanitizeUtf16(content).trim()
        if (normalized.isEmpty()) return false
        val chineseActionGoal = Regex(
            """(?:打开|查找|查询|搜索|搜一下|查看|看一下|点击|进入|导航|跳转|返回|回到|筛选|过滤|定位|读取|执行|运行|安装|下载|提交|填写|选择|勾选|切换|创建|删除|修改|重试)""",
            RegexOption.IGNORE_CASE
        )
        if (chineseActionGoal.containsMatchIn(normalized)) {
            return true
        }
        val englishActionGoal = Regex(
            """\b(?:open|search|find|look up|check|click|navigate|go to|return|filter|read|run|install|download|submit|fill|select|toggle|create|delete|edit|retry)\b""",
            RegexOption.IGNORE_CASE
        )
        return englishActionGoal.containsMatchIn(normalized)
    }

    private fun looksLikeIntermediateExecutionUpdate(content: String): Boolean {
        val normalized = content.lowercase()
        val chineseProgressCues = listOf(
            "接下来",
            "随后",
            "稍后",
            "稍等",
            "等我",
            "准备",
            "正在",
            "然后继续",
            "继续筛选",
            "继续查找",
            "继续搜索",
            "继续尝试",
            "最后一次尝试",
            "尝试返回",
            "回到首页",
            "返回首页"
        )
        if (chineseProgressCues.any(normalized::contains)) {
            return true
        }
        val englishProgressCue = Regex(
            """\b(?:next|then|after that|let me continue|continuing|continue to|continue with|still trying|one more try|trying to return)\b""",
            RegexOption.IGNORE_CASE
        )
        return englishProgressCue.containsMatchIn(content)
    }

    private fun containsExplicitFinalAnswerCue(content: String): Boolean {
        val normalized = content.lowercase()
        val chineseFinalCues = listOf(
            "建议",
            "结论",
            "总结",
            "如下",
            "步骤",
            "推荐",
            "答案",
            "综合现有信息",
            "已完成",
            "处理完成",
            "已经完成",
            "已查到",
            "已找到",
            "读取失败",
            "校验失败",
            "当前限制",
            "无法直接",
            "不能直接",
            "用户手动停止"
        )
        if (chineseFinalCues.any(normalized::contains)) {
            return true
        }
        val structuredAnswer = Regex(
            """(?:^|\n)\s*(?:\d+\.\s|-\s|•\s|一、|二、|三、)""",
            RegexOption.MULTILINE
        )
        if (structuredAnswer.containsMatchIn(content)) {
            return true
        }
        val englishFinalCue = Regex(
            """\b(?:recommend|summary|conclusion|steps|result|answer|done|completed|cannot directly|can't directly)\b""",
            RegexOption.IGNORE_CASE
        )
        return englishFinalCue.containsMatchIn(content)
    }

    private fun looksLikeCompleteFinalAnswer(
        content: String,
        explicitFinalCue: Boolean,
        actionIntent: Boolean,
        intermediateUpdate: Boolean,
        answerTooThinForActionGoal: Boolean
    ): Boolean {
        if (content.isBlank()) return false
        if (answerTooThinForActionGoal) return false
        if (explicitFinalCue) return true
        if (actionIntent) return false
        if (intermediateUpdate) return false
        return true
    }

    private fun buildLengthContinuationMessage(): ChatCompletionMessage {
        return ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(
                "上一条 assistant 回复因为达到输出长度上限被截断。请从中断处继续完成原任务，不要重复已经输出的内容，不要重新开头，不要解释本提示。"
            )
        )
    }

    private fun buildMissingToolCallRecoveryMessage(): ChatCompletionMessage {
        return ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(
                t(
                    "你上一条回复还停留在执行中间态，但没有真正发起 tool_call，也没有给出完整最终答案。请继续同一任务：如果还需要操作、查询、点击、筛选或导航，必须返回标准 tool_call；如果不需要工具，请直接给出完整最终答案。不要只回复“我先查一下”“接下来继续处理”这类过渡语。",
                    "Your previous reply was still in an in-progress execution state, but you did not emit a tool_call or provide a complete final answer. Continue the same task: if any action, lookup, click, filter, or navigation is still needed, you must return a standard tool_call; if no tool is needed, reply with the complete final answer directly. Do not answer with transitional text such as 'let me check' or 'next I will continue' only."
                )
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
