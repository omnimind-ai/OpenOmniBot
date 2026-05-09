package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ReasoningStreamUpdatePolicy
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

interface VLMStreamClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null
    ): SceneChatCompletionTurn
}

class HttpVLMStreamClient(
    private val scope: CoroutineScope,
    private val requestOp: suspend (ChatCompletionRequest, EventSourceListener) -> SceneChatCompletionStreamHandle =
        { request, listener -> HttpController.postSceneChatCompletionStream(request, listener) },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
) : VLMStreamClient {
    private val tag = "HttpVLMStreamClient"

    private companion object {
        const val REASONING_UPDATE_INTERVAL_MS =
            ReasoningStreamUpdatePolicy.DEFAULT_INTERVAL_MS
    }

    private data class StreamRequestVariant(
        val name: String,
        val request: ChatCompletionRequest
    )

    private class StreamRequestFailure(
        val statusCode: Int?,
        val reason: String,
        val responseBody: String?
    ) : RuntimeException(
        "scene stream request failed${statusCode?.let { "($it)" }.orEmpty()}: $reason"
    )

    override suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)?
    ): SceneChatCompletionTurn {
        val variants = buildRequestVariants(request)
        var lastFailure: StreamRequestFailure? = null

        for ((index, variant) in variants.withIndex()) {
            try {
                if (index > 0) {
                    OmniLog.w(tag, "retry scene stream variant=${variant.name} model=${request.model}")
                }
                return streamTurnOnce(
                    request = variant.request,
                    onReasoningUpdate = onReasoningUpdate
                )
            } catch (error: StreamRequestFailure) {
                lastFailure = error
                val canRetryVariant = error.statusCode == 400 && index < variants.lastIndex
                if (canRetryVariant) {
                    OmniLog.w(tag, "scene stream variant=${variant.name} failed with 400: ${error.reason}")
                    continue
                }
                throw error
            }
        }

        throw lastFailure ?: IllegalStateException("scene stream failed with unknown reason")
    }

    private suspend fun streamTurnOnce(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)?
    ): SceneChatCompletionTurn {
        val streamDone = CompletableDeferred<SceneChatCompletionTurn>()
        val completed = AtomicBoolean(false)
        val accumulator = VLMStreamAccumulator(json)
        var lastReasoning = ""
        var lastReasoningEmitLength = 0
        var lastReasoningEmitAt = 0L
        var reasoningEmitJob: Job? = null
        val reasoningLock = Any()
        var eventSource: EventSource? = null
        var handle: SceneChatCompletionStreamHandle? = null

        fun dispatchReasoningSnapshot(reasoning: String) {
            lastReasoning = reasoning
            if (onReasoningUpdate != null) {
                scope.launch {
                    runCatching { onReasoningUpdate.invoke(reasoning) }
                        .onFailure { OmniLog.w(tag, "emit reasoning update failed: ${it.message}") }
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

        fun completeStream(eventSource: EventSource? = null) {
            if (!completed.compareAndSet(false, true)) return
            runCatching {
                val resolvedHandle = handle
                    ?: throw IllegalStateException("scene stream handle not initialized")
                emitReasoning(force = true)
                SceneChatCompletionTurn(
                    parser = resolvedHandle.parser,
                    route = resolvedHandle.route,
                    resolvedModel = resolvedHandle.resolvedModel,
                    turn = accumulator.buildTurn()
                )
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
                    if (done) {
                        completeStream(eventSource)
                    }
                }.onFailure { error ->
                    if (completed.compareAndSet(false, true)) {
                        streamDone.completeExceptionally(
                            IllegalStateException("invalid scene stream chunk: ${error.message}", error)
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
            handle = requestOp.invoke(request.copy(stream = true), listener)
            eventSource = handle?.eventSource
            return streamDone.await()
        } finally {
            reasoningEmitJob?.cancel()
            eventSource?.cancel()
        }
    }

    private fun buildRequestVariants(request: ChatCompletionRequest): List<StreamRequestVariant> {
        val variants = mutableListOf<StreamRequestVariant>()
        val seenPayloads = LinkedHashSet<String>()

        fun add(name: String, candidate: ChatCompletionRequest) {
            val normalized = candidate.copy(stream = true)
            val encoded = json.encodeToString(normalized)
            if (seenPayloads.add(encoded)) {
                variants.add(StreamRequestVariant(name = name, request = normalized))
            }
        }

        add("default", request)
        add(
            "no_parallel_tool_calls",
            request.copy(parallelToolCalls = null)
        )
        add(
            "no_tool_choice",
            request.copy(
                parallelToolCalls = null,
                toolChoice = null
            )
        )

        val normalizedMaxCompletionTokens = request.maxCompletionTokens ?: request.maxTokens
        add(
            "minimal_tools",
            request.copy(
                streamOptions = null,
                parallelToolCalls = null,
                toolChoice = null,
                temperature = null,
                topP = null,
                maxCompletionTokens = normalizedMaxCompletionTokens,
                maxTokens = null
            )
        )
        return variants
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
}
