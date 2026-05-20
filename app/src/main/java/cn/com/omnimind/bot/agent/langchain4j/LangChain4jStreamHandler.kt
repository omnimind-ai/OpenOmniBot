package cn.com.omnimind.bot.agent.langchain4j

import cn.com.omnimind.baselib.llm.ReasoningStreamUpdatePolicy
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.CompleteToolCall
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges LangChain4j's callback-style [StreamingChatResponseHandler] back into
 * the suspend-friendly world used by the orchestrator. Carries the reasoning
 * throttle (300ms interval) lifted from the legacy `HttpAgentLlmClient`.
 *
 * Lifecycle:
 * 1. Caller creates the handler, hands it to `streamingChatModel.chat(req, handler)`.
 * 2. Background LangChain4j threads invoke the on* callbacks.
 * 3. Caller `await()`s [completion], which resolves once `onCompleteResponse` or
 *    `onError` fires.
 * 4. Caller invokes [join] to flush the emission queue before reading the
 *    accumulated state.
 */
internal class LangChain4jStreamHandler(
    private val scope: CoroutineScope,
    private val onReasoningUpdate: (suspend (String) -> Unit)? = null,
    private val onContentUpdate: (suspend (String) -> Unit)? = null,
    private val preferInlineThinkTags: Boolean = false
) : StreamingChatResponseHandler {

    private companion object {
        const val REASONING_INTERVAL_MS = ReasoningStreamUpdatePolicy.DEFAULT_INTERVAL_MS
    }

    val completion: CompletableDeferred<ChatResponse> = CompletableDeferred()

    private val completedRef = AtomicBoolean(false)

    // Accumulated state (read by caller after completion).
    private val contentBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val toolCalls = mutableListOf<CompleteToolCall>()
    private var thinkSectionOpen = false

    fun accumulatedContent(): String = contentBuffer.toString()
    fun accumulatedReasoning(): String = reasoningBuffer.toString()
    fun completedToolCalls(): List<CompleteToolCall> = toolCalls.toList()

    // Reasoning throttle.
    private var lastReasoningEmitLength = 0
    private var lastReasoningEmitAt = 0L
    private var reasoningEmitJob: Job? = null
    private val reasoningLock = Any()

    // Content throttle is just "any non-empty new text"; the orchestrator already
    // debounces in the UI layer. We do, however, queue emissions in order to
    // preserve sequencing relative to reasoning.
    private val emissionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val emissionJob = scope.launch {
        for (block in emissionQueue) {
            runCatching { block.invoke() }
        }
    }

    /** Block until the emission queue is drained. Call only after completion. */
    suspend fun join() {
        emissionQueue.close()
        runCatching { emissionJob.join() }
    }

    private fun enqueue(block: suspend () -> Unit) {
        if (emissionQueue.isClosedForSend) return
        emissionQueue.trySend(block)
    }

    override fun onPartialResponse(partialResponse: String) {
        if (partialResponse.isEmpty()) return
        val text = if (preferInlineThinkTags) {
            absorbInlineThinkTags(partialResponse)
        } else {
            partialResponse
        }
        if (text.isEmpty()) return
        contentBuffer.append(text)
        val snapshot = contentBuffer.toString()
        val cb = onContentUpdate ?: return
        enqueue { cb(snapshot) }
    }

    override fun onPartialThinking(partialThinking: dev.langchain4j.model.chat.response.PartialThinking) {
        val text = partialThinking.text()
        if (text.isNullOrEmpty()) return
        reasoningBuffer.append(text)
        emitReasoningThrottled(force = false)
    }

    override fun onCompleteToolCall(completeToolCall: CompleteToolCall) {
        toolCalls.add(completeToolCall)
    }

    override fun onCompleteResponse(completeResponse: ChatResponse) {
        if (!completedRef.compareAndSet(false, true)) return
        // Force-flush any pending reasoning emit.
        emitReasoningThrottled(force = true)
        completion.complete(completeResponse)
    }

    override fun onError(error: Throwable) {
        if (!completedRef.compareAndSet(false, true)) return
        completion.completeExceptionally(error)
    }

    /**
     * Schedule a reasoning emission. We emit immediately on the first frame
     * (so the UI shows a thinking card without delay) and then throttle to
     * [REASONING_INTERVAL_MS] for subsequent updates.
     */
    private fun emitReasoningThrottled(force: Boolean) {
        val cb = onReasoningUpdate ?: return
        synchronized(reasoningLock) {
            val length = reasoningBuffer.length
            if (length == 0 || length == lastReasoningEmitLength) return
            if (force) {
                reasoningEmitJob?.cancel()
                reasoningEmitJob = null
                lastReasoningEmitLength = length
                lastReasoningEmitAt = System.currentTimeMillis()
                val snapshot = reasoningBuffer.toString()
                enqueue { cb(snapshot) }
                return
            }
            if (reasoningEmitJob?.isActive == true) return
            val delayMs = ReasoningStreamUpdatePolicy.nextDelayMs(
                hasEmittedBefore = lastReasoningEmitLength > 0,
                lastEmitAtMs = lastReasoningEmitAt,
                nowMs = System.currentTimeMillis(),
                intervalMs = REASONING_INTERVAL_MS
            )
            if (delayMs <= 0L) {
                lastReasoningEmitLength = length
                lastReasoningEmitAt = System.currentTimeMillis()
                val snapshot = reasoningBuffer.toString()
                enqueue { cb(snapshot) }
            } else {
                reasoningEmitJob = scope.launch {
                    delay(delayMs)
                    val emitSnapshot = synchronized(reasoningLock) {
                        reasoningEmitJob = null
                        val curLen = reasoningBuffer.length
                        if (curLen == 0 || curLen == lastReasoningEmitLength) {
                            null
                        } else {
                            lastReasoningEmitLength = curLen
                            lastReasoningEmitAt = System.currentTimeMillis()
                            reasoningBuffer.toString()
                        }
                    }
                    if (emitSnapshot != null) {
                        enqueue { cb(emitSnapshot) }
                    }
                }
            }
        }
    }

    /**
     * Local MNN models emit reasoning as `<think>...</think>` blocks inside the
     * normal content stream. Pull those blocks out before they hit
     * [contentBuffer], and route their text to [reasoningBuffer] instead.
     *
     * Handles fragments that straddle chunk boundaries — `<thi` / `nk>` etc.
     */
    private fun absorbInlineThinkTags(rawChunk: String): String {
        val cleaned = StringBuilder()
        var i = 0
        val s = rawChunk
        while (i < s.length) {
            if (!thinkSectionOpen && s.startsWith("<think>", i)) {
                thinkSectionOpen = true
                i += "<think>".length
                continue
            }
            if (thinkSectionOpen && s.startsWith("</think>", i)) {
                thinkSectionOpen = false
                i += "</think>".length
                emitReasoningThrottled(force = false)
                continue
            }
            if (thinkSectionOpen) {
                reasoningBuffer.append(s[i])
            } else {
                cleaned.append(s[i])
            }
            i++
        }
        if (thinkSectionOpen) {
            emitReasoningThrottled(force = false)
        }
        return cleaned.toString()
    }
}
