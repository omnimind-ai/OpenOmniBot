package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.LocalModelProviderBridge
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ReasoningStreamUpdatePolicy
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.util.concurrent.atomic.AtomicBoolean

interface AgentLlmClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
        onContentUpdate: (suspend (String) -> Unit)? = null
    ): ChatCompletionTurn
}

class HttpAgentLlmClient(
    private val scope: CoroutineScope,
    private val modelOverride: AgentModelOverride? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
) : AgentLlmClient {
    private val tag = "HttpAgentLlmClient"

    private companion object {
        const val REASONING_UPDATE_INTERVAL_MS =
            ReasoningStreamUpdatePolicy.DEFAULT_INTERVAL_MS
    }

    private data class StreamRequestVariant(
        val name: String,
        val requestJson: String
    )

    private class StreamRequestFailure(
        val statusCode: Int?,
        val reason: String,
        val responseBody: String?
    ) : RuntimeException(
        "chat completion stream request failed${
            statusCode?.let { "($it)" }.orEmpty()
        }: $reason"
    )

    override suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): ChatCompletionTurn {
        val modelCandidates = buildModelCandidates(request.model)
        val variants = buildRequestVariants(
            sanitizeRequestForTarget(request)
        )
        var lastFailure: StreamRequestFailure? = null

        for (modelIndex in modelCandidates.indices) {
            val candidateModel = modelCandidates[modelIndex]
            for (variantIndex in variants.indices) {
                val variant = variants[variantIndex]
                try {
                    if (modelIndex > 0 || variantIndex > 0) {
                        OmniLog.w(
                            tag,
                            "retry stream request model=$candidateModel variant=${variant.name}"
                        )
                    }
                    return streamTurnOnce(
                        model = candidateModel,
                        requestJson = variant.requestJson,
                        onReasoningUpdate = onReasoningUpdate,
                        onContentUpdate = onContentUpdate
                    )
                } catch (error: StreamRequestFailure) {
                    lastFailure = error
                    val canRetryVariant =
                        error.statusCode == 400 && variantIndex < variants.lastIndex
                    if (canRetryVariant) {
                        OmniLog.w(
                            tag,
                            "stream variant=${variant.name} failed with 400: ${error.reason}"
                        )
                        continue
                    }

                    val canFallbackModel =
                        modelIndex < modelCandidates.lastIndex && isModelNotSupported(error)
                    if (canFallbackModel) {
                        val nextModel = modelCandidates[modelIndex + 1]
                        OmniLog.w(
                            tag,
                            "model=$candidateModel not supported, fallback to model=$nextModel; reason=${error.reason}"
                        )
                        break
                    }
                    throw error
                }
            }
        }

        throw lastFailure ?: IllegalStateException("chat completion stream failed with unknown reason")
    }

    private suspend fun streamTurnOnce(
        model: String,
        requestJson: String,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): ChatCompletionTurn {
        return try {
            doStreamTurnOnce(model, requestJson, onReasoningUpdate, onContentUpdate, forceHttp1 = false)
        } catch (e: StreamRequestFailure) {
            if (isHttp2ProtocolError(e)) {
                OmniLog.w(tag, "HTTP/2 stream PROTOCOL_ERROR, retrying with HTTP/1.1")
                doStreamTurnOnce(model, requestJson, onReasoningUpdate, onContentUpdate, forceHttp1 = true)
            } else {
                throw e
            }
        }
    }

    private fun isHttp2ProtocolError(error: StreamRequestFailure): Boolean {
        return error.reason.contains("PROTOCOL_ERROR", ignoreCase = true)
                || error.reason.contains("stream was reset", ignoreCase = true)
    }

    private suspend fun doStreamTurnOnce(
        model: String,
        requestJson: String,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        forceHttp1: Boolean
    ): ChatCompletionTurn {
        val streamDone = CompletableDeferred<ChatCompletionTurn>()
        val completed = AtomicBoolean(false)
        val routeInfo = HttpController.resolveChatCompletionRouteInfo(
            modelOrScene = model,
            explicitApiBase = modelOverride?.apiBase,
            explicitApiKey = modelOverride?.apiKey,
            explicitModel = modelOverride?.modelId,
            explicitProtocolType = modelOverride?.protocolType
        )
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            preferInlineThinkTags = LocalModelProviderBridge.isBuiltinLocalProvider(
                modelOverride?.providerProfileId,
                modelOverride?.apiBase
            ),
            includeReasoningInAssistantMessage = routeInfo?.requiresReasoningEcho == true
        )
        var lastReasoning = ""
        var lastReasoningEmitLength = 0
        var lastReasoningEmitAt = 0L
        var reasoningEmitJob: Job? = null
        val reasoningLock = Any()
        var lastContent = ""
        var eventSource: EventSource? = null
        val emissionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val emissionJob = scope.launch {
            for (block in emissionQueue) {
                runCatching { block.invoke() }
                    .onFailure { OmniLog.w(tag, "stream emission failed: ${it.message}") }
            }
        }
        var hasPublishedReasoningForTurn = false

        fun enqueueEmission(block: suspend () -> Unit) {
            if (emissionQueue.isClosedForSend) {
                return
            }
            emissionQueue.trySend(block)
        }

        fun dispatchReasoningSnapshot(reasoning: String) {
            lastReasoning = reasoning
            hasPublishedReasoningForTurn = true
            if (onReasoningUpdate != null) {
                enqueueEmission {
                    onReasoningUpdate.invoke(reasoning)
                }
            }
        }

        fun collectReasoningSnapshotLocked(): String? {
            val length = accumulator.currentReasoningLength()
            if (length <= 0 || length == lastReasoningEmitLength) return null
            val reasoning = accumulator.currentReasoning()
            lastReasoningEmitLength = length
            if (reasoning.isBlank() || reasoning == lastReasoning) return null
            lastReasoning = reasoning
            lastReasoningEmitAt = System.currentTimeMillis()
            return reasoning
        }

        fun scheduleReasoningSnapshotLocked(delayMs: Long) {
            reasoningEmitJob = scope.launch {
                delay(delayMs)
                val snapshot = synchronized(reasoningLock) {
                    reasoningEmitJob = null
                    collectReasoningSnapshotLocked()
                }
                if (snapshot != null) {
                    dispatchReasoningSnapshot(snapshot)
                }
            }
        }

        fun emitReasoning(force: Boolean = false) {
            var snapshot: String? = null
            synchronized(reasoningLock) {
                val length = accumulator.currentReasoningLength()
                if (length <= 0 || length == lastReasoningEmitLength) return
                if (force) {
                    reasoningEmitJob?.cancel()
                    reasoningEmitJob = null
                    snapshot = collectReasoningSnapshotLocked()
                    return@synchronized
                }
                if (reasoningEmitJob?.isActive == true) return
                val delayMs = ReasoningStreamUpdatePolicy.nextDelayMs(
                    hasEmittedBefore = lastReasoningEmitLength > 0,
                    lastEmitAtMs = lastReasoningEmitAt,
                    nowMs = System.currentTimeMillis(),
                    intervalMs = REASONING_UPDATE_INTERVAL_MS
                )
                if (delayMs <= 0L) {
                    snapshot = collectReasoningSnapshotLocked()
                } else {
                    scheduleReasoningSnapshotLocked(delayMs)
                }
            }
            if (snapshot != null) {
                dispatchReasoningSnapshot(snapshot!!)
            }
        }

        fun emitContent() {
            val content = accumulator.currentContent()
            if (content.isEmpty() || content == lastContent) return
            if (!hasPublishedReasoningForTurn && accumulator.currentReasoningLength() > 0) {
                emitReasoning(force = true)
            }
            lastContent = content
            if (onContentUpdate != null) {
                enqueueEmission {
                    onContentUpdate.invoke(content)
                }
            }
        }

        fun completeStream(eventSource: EventSource? = null) {
            if (!completed.compareAndSet(false, true)) return
            runCatching {
                val turn = accumulator.buildTurn()
                enforceReasoningEchoIfRequired(turn, routeInfo)
                emitReasoning(force = true)
                emitContent()
                turn
            }.onSuccess { turn ->
                streamDone.complete(turn)
            }.onFailure { error ->
                streamDone.completeExceptionally(error)
            }
            eventSource?.cancel()
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (completed.get()) return
                runCatching {
                    val done = accumulator.consume(data)
                    emitReasoning()
                    emitContent()
                    if (done) {
                        completeStream(eventSource)
                    }
                }.onFailure { error ->
                    if (completed.compareAndSet(false, true)) {
                        streamDone.completeExceptionally(
                            IllegalStateException("invalid chat completion stream chunk: ${error.message}", error)
                        )
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                completeStream()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!completed.compareAndSet(false, true)) return
                val responseBody = extractResponseBody(response)
                val reason = extractErrorReason(responseBody)
                    ?: sanitizeReason(t?.message)
                    ?: "unknown stream failure"
                streamDone.completeExceptionally(
                    StreamRequestFailure(
                        statusCode = response?.code,
                        reason = reason,
                        responseBody = responseBody
                    )
                )
            }
        }

        try {
            eventSource = HttpController.postChatCompletionsStreamRequest(
                model = model,
                requestBodyJson = requestJson,
                event = listener,
                explicitApiBase = modelOverride?.apiBase,
                explicitApiKey = modelOverride?.apiKey,
                explicitModel = modelOverride?.modelId,
                explicitProtocolType = modelOverride?.protocolType,
                forceHttp1 = forceHttp1
            )
            return streamDone.await()
        } finally {
            reasoningEmitJob?.cancel()
            eventSource?.cancel()
            emissionQueue.close()
            runCatching { emissionJob.join() }
        }
    }

    private fun enforceReasoningEchoIfRequired(
        turn: ChatCompletionTurn,
        routeInfo: HttpController.ChatCompletionRouteInfo
    ) {
        if (!routeInfo.requiresReasoningEcho) {
            return
        }
        if (turn.reasoning.isBlank()) {
            return
        }
        if (!turn.message.reasoningContent.isNullOrBlank()) {
            return
        }
        throw IllegalStateException(
            "assistant turn is missing reasoning_content for route=${routeInfo.resolvedModel} " +
                "protocol=${routeInfo.protocolType} despite non-empty reasoning output"
        )
    }

    private fun buildRequestVariants(request: ChatCompletionRequest): List<StreamRequestVariant> {
        val variants = mutableListOf<StreamRequestVariant>()
        val seenPayloads = LinkedHashSet<String>()
        fun add(name: String, candidate: ChatCompletionRequest) {
            val encoded = json.encodeToString(candidate)
            if (seenPayloads.add(encoded)) {
                variants.add(StreamRequestVariant(name = name, requestJson = encoded))
            }
        }

        add("default", request)
        add(
            "no_stream_options",
            request.copy(streamOptions = null)
        )
        add(
            "minimal",
            request.copy(
                streamOptions = null,
                parallelToolCalls = null,
                toolChoice = null
            )
        )

        val legacyFunctions = request.tools.map { it.function }
        if (legacyFunctions.isNotEmpty()) {
            add(
                "legacy_functions",
                request.copy(
                    streamOptions = null,
                    parallelToolCalls = null,
                    toolChoice = null,
                    tools = emptyList(),
                    functions = legacyFunctions,
                    functionCall = toLegacyFunctionCall(request.toolChoice)
                )
            )
        }
        return variants
    }

    private fun sanitizeRequestForTarget(request: ChatCompletionRequest): ChatCompletionRequest {
        if (shouldPreserveAllAssistantReasoning()) {
            return request
        }
        val sanitizedMessages = request.messages.mapIndexed { index, message ->
            if (
                message.role != "assistant" ||
                message.reasoningContent.isNullOrBlank() ||
                shouldRetainAssistantReasoning(index, request.messages)
            ) {
                message
            } else {
                message.copy(reasoningContent = null)
            }
        }
        return if (sanitizedMessages == request.messages) {
            request
        } else {
            request.copy(messages = sanitizedMessages)
        }
    }

    private fun shouldPreserveAllAssistantReasoning(): Boolean {
        if (isOfficialDeepSeekTarget()) {
            return true
        }
        return resolvedProtocolType() == DeepSeekProvider.PROTOCOL_TYPE
    }

    private fun shouldRetainAssistantReasoning(
        assistantIndex: Int,
        messages: List<ChatCompletionMessage>
    ): Boolean {
        val message = messages.getOrNull(assistantIndex) ?: return false
        if (message.toolCalls?.isNotEmpty() == true) {
            return true
        }
        for (index in assistantIndex + 1 until messages.size) {
            when (messages[index].role) {
                "tool" -> return true
                "user" -> return false
            }
        }
        return false
    }

    private fun isOfficialDeepSeekTarget(): Boolean {
        if (modelOverride != null) {
            return DeepSeekProvider.shouldUseOfficialAdapter(
                protocolType = modelOverride.protocolType,
                apiBase = modelOverride.apiBase
            )
        }
        val profile = runCatching { ModelProviderConfigStore.getEditingProfile() }
            .getOrNull()
        return DeepSeekProvider.shouldUseOfficialAdapter(
            protocolType = profile?.protocolType,
            apiBase = profile?.baseUrl
        )
    }

    private fun resolvedProtocolType(): String {
        modelOverride?.protocolType
            ?.let(DeepSeekProvider::normalizeProtocolType)
            ?.let { return it }
        return runCatching { ModelProviderConfigStore.getEditingProfile().protocolType }
            .map(DeepSeekProvider::normalizeProtocolType)
            .getOrDefault(DeepSeekProvider.normalizeProtocolType(null))
    }

    private fun toLegacyFunctionCall(toolChoice: JsonElement?): JsonElement? {
        if (toolChoice == null) return null
        return when (toolChoice) {
            is JsonPrimitive -> {
                val raw = toolChoice.contentOrNull?.trim().orEmpty()
                when {
                    raw.isEmpty() || raw.equals("none", ignoreCase = true) -> null
                    raw.equals("required", ignoreCase = true) -> JsonPrimitive("auto")
                    else -> JsonPrimitive(raw)
                }
            }

            is JsonObject -> {
                val functionName =
                    extractJsonText((toolChoice["function"] as? JsonObject)?.get("name"))
                if (functionName.isNullOrBlank()) {
                    JsonPrimitive("auto")
                } else {
                    JsonObject(mapOf("name" to JsonPrimitive(functionName)))
                }
            }

            else -> JsonPrimitive("auto")
        }
    }

    private fun extractResponseBody(response: Response?): String? {
        val body = runCatching { response?.body?.string() }.getOrNull()?.trim().orEmpty()
        return body.takeIf { it.isNotEmpty() }?.take(4000)
    }

    private fun extractErrorReason(responseBody: String?): String? {
        val raw = responseBody?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return sanitizeReason(raw)
        val errorObj = parsed["error"] as? JsonObject

        val candidates = listOf(
            extractJsonText(errorObj?.get("message")),
            extractJsonText(errorObj?.get("detail")),
            extractJsonText(parsed["message"]),
            extractJsonText(parsed["detail"]),
            extractJsonText(parsed["error_description"]),
            extractJsonText(parsed["error"])
        )
        return candidates.firstOrNull { !it.isNullOrBlank() } ?: sanitizeReason(raw)
    }

    private fun extractJsonText(element: JsonElement?): String? {
        return when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> {
                extractJsonText(element["message"])
                    ?: extractJsonText(element["detail"])
                    ?: extractJsonText(element["code"])
            }

            else -> sanitizeReason(element.toString())
        }
    }

    private fun sanitizeReason(raw: String?, maxLen: Int = 240): String? {
        val normalized = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return if (normalized.length <= maxLen) normalized else "${normalized.take(maxLen)}..."
    }

    private fun buildModelCandidates(baseModel: String): List<String> {
        val normalized = baseModel.trim().ifEmpty { baseModel }
        val candidates = linkedSetOf(normalized)
        if (normalized.startsWith("scene.")) {
            candidates.add("scene.dispatch.model")
        }
        return candidates.toList()
    }

    private fun isModelNotSupported(error: StreamRequestFailure): Boolean {
        val code = error.statusCode
        if (code != 400 && code != 404) return false
        val haystack = buildString {
            append(error.reason)
            append(' ')
            append(error.responseBody.orEmpty())
        }.lowercase()
        if (!haystack.contains("model")) return false
        return haystack.contains("not supported") ||
            haystack.contains("unsupported model") ||
            haystack.contains("model_not_supported") ||
            haystack.contains("invalid model") ||
            haystack.contains("unknown model") ||
            haystack.contains("model does not exist") ||
            haystack.contains("no such model") ||
            haystack.contains("not found")
    }
}
