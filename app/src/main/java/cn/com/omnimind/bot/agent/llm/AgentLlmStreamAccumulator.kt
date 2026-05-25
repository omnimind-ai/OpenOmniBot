package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.llm.decodeChatCompletionUsage
import java.util.SortedMap
import java.util.TreeMap

class AgentLlmStreamAccumulator(
    private val json: Json,
    private val preferInlineThinkTags: Boolean = false,
    private val includeReasoningInAssistantMessage: Boolean = false
) {
    companion object {
        private const val TAG = "AgentLlmStreamAccumulator"
        private const val THINK_OPEN_TAG = "<think>"
        private const val THINK_CLOSE_TAG = "</think>"
        private const val TOOL_CALL_OPEN_TAG = "<tool_call>"
        private const val TOOL_CALL_CLOSE_TAG = "</tool_call>"
        private const val FUNCTION_OPEN_MARKER = "<function="
        private const val FUNCTION_CLOSE_TAG = "</function>"
        private val INLINE_THINK_TAGS = listOf(THINK_OPEN_TAG, THINK_CLOSE_TAG)
        private val INLINE_TOOL_MARKERS = listOf(TOOL_CALL_OPEN_TAG, FUNCTION_OPEN_MARKER)
        private val FUNCTION_BLOCK_REGEX = Regex(
            "^<function\\s*=\\s*([A-Za-z0-9_.:-]+)\\s*>(.*)</function\\s*>$",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val PARAMETER_REGEX = Regex(
            "<parameter\\s*=\\s*([A-Za-z0-9_.:-]+)\\s*>(.*?)</parameter\\s*>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val JSON_TOOL_ARGUMENT_KEYS = listOf(
            "arguments",
            "argumentsJson",
            "arguments_json",
            "args",
            "argsJson",
            "args_json",
            "input",
            "inputJson",
            "input_json",
            "parameters"
        )
        private val JSON_TOOL_METADATA_KEYS = setOf(
            "actions",
            "artifacts",
            "cardid",
            "completed",
            "displayname",
            "function",
            "kind",
            "name",
            "previewjson",
            "progress",
            "rawresultjson",
            "resultpreviewjson",
            "status",
            "streammeta",
            "success",
            "summary",
            "terminaloutput",
            "terminaloutputdelta",
            "terminalsessionid",
            "tool",
            "toolcallid",
            "toolname",
            "tooltitle",
            "tooltype"
        )
        private val JSON_TOOL_RESULT_PAYLOAD_KEYS = setOf(
            "actions",
            "artifacts",
            "previewjson",
            "rawresultjson",
            "resultpreviewjson",
            "terminaloutput"
        )
        private val JSON_TOOL_RESULT_STATUS_KEYS = setOf(
            "completed",
            "progress",
            "status",
            "success",
            "summary"
        )
    }

    private val contentBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val inlineTextBuffer = StringBuilder()
    private val inlineToolMarkupBuffer = StringBuilder()
    private val toolCallBuilders: SortedMap<Int, MutableToolCallBuilder> = TreeMap()
    private var providerError: StreamProviderError? = null
    private var finishReason: String? = null
    private var usage: ChatCompletionUsage? = null
    private var decodeTokensPerSecond: Double? = null
    private var seenChunk = false
    private var seenDoneSignal = false
    private var lastChunkPreview: String = ""
    private var thinkSectionOpen = false
    private var inlineThinkTagObserved = false

    private var chunkIndex = 0

    fun consume(rawChunk: String): Boolean {
        val trimmed = rawChunk.trim()
        if (trimmed.isEmpty()) return false
        OmniLog.d(TAG, "[stream chunk#${chunkIndex++}] raw(${trimmed.length}): ${trimmed.take(500)}")
        lastChunkPreview = trimmed.take(500)
        splitCompositeChunk(trimmed)?.let { segments ->
            var done = false
            segments.forEach { segment ->
                done = consumeSingleChunk(segment) || done
            }
            return done
        }
        return consumeSingleChunk(trimmed)
    }

    private fun consumeSingleChunk(trimmed: String): Boolean {
        if (trimmed == "[DONE]") {
            seenDoneSignal = true
            return true
        }
        val root = parseJsonObject(trimmed)
        if (root == null) {
            seenChunk = true
            appendTextChunk(trimmed)
            return false
        }
        return consumeJsonChunk(root)
    }

    private fun consumeJsonChunk(root: JsonObject): Boolean {
        seenChunk = true
        val prevContentLen = contentBuffer.length
        val prevReasoningLen = reasoningBuffer.length
        val prevToolCallCount = toolCallBuilders.size

        extractProviderError(root)?.let { error ->
            providerError = error
        }
        usage = decodeUsage(root["usage"]) ?: usage
        decodeTokensPerSecond = decodeTimings(root["timings"]) ?: decodeTokensPerSecond

        var chunkHasPayload = false
        val choices = root["choices"] as? JsonArray
        choices?.forEach { choiceElement ->
            val choice = choiceElement as? JsonObject ?: return@forEach
            val thisChoiceHasPayload = consumeChoice(choice)
            chunkHasPayload = chunkHasPayload || thisChoiceHasPayload
        }

        // 兼容不同 Provider 的非标准 top-level text / message 回流
        if (!chunkHasPayload) {
            chunkHasPayload = appendTextPayload(root["text"]) || chunkHasPayload
            chunkHasPayload = appendTextPayload(root["message"]) || chunkHasPayload
            chunkHasPayload = appendTextPayload(root["output_text"]) || chunkHasPayload
            appendReasoningPayload(root["reasoning_content"])
            appendReasoningPayload(root["reasoning"])
            appendReasoningPayload(root["thinking"])

            val outputObj = root["output"] as? JsonObject
            if (outputObj != null) {
                chunkHasPayload = appendTextPayload(outputObj["text"]) || chunkHasPayload
                chunkHasPayload = appendTextPayload(outputObj["content"]) || chunkHasPayload
                appendReasoningPayload(outputObj["reasoning_content"])
                appendReasoningPayload(outputObj["reasoning"])
            }
        }

        // Log parsed deltas
        val contentDelta = contentBuffer.length - prevContentLen
        val reasoningDelta = reasoningBuffer.length - prevReasoningLen
        val newToolCalls = toolCallBuilders.size - prevToolCallCount
        if (contentDelta > 0 || reasoningDelta > 0 || newToolCalls > 0 || finishReason != null || usage != null) {
            val parts = mutableListOf<String>()
            if (contentDelta > 0) parts += "content+=$contentDelta"
            if (reasoningDelta > 0) parts += "reasoning+=$reasoningDelta"
            if (newToolCalls > 0) parts += "new_tool_calls=$newToolCalls"
            finishReason?.let { parts += "finish=$it" }
            usage?.let { u ->
                parts += "usage(prompt=${u.promptTokens},completion=${u.completionTokens},total=${u.totalTokens})"
            }
            OmniLog.d(TAG, "[stream parse] ${parts.joinToString(", ")}")
        }

        return false
    }

    private fun splitCompositeChunk(raw: String): List<String>? {
        if (
            raw.contains(TOOL_CALL_OPEN_TAG, ignoreCase = true) ||
            raw.contains(FUNCTION_OPEN_MARKER, ignoreCase = true)
        ) {
            return null
        }
        splitChunkByLines(raw)?.let { return it }
        splitTrailingJsonChunk(raw)?.let { return it }
        return null
    }

    private fun splitChunkByLines(raw: String): List<String>? {
        val lines = raw.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.size <= 1) return null
        if (!lines.any(::isStructuredChunk)) return null

        val segments = mutableListOf<String>()
        val textBuffer = StringBuilder()
        fun flushText() {
            if (textBuffer.isEmpty()) return
            segments += textBuffer.toString()
            textBuffer.setLength(0)
        }

        lines.forEach { line ->
            if (isStructuredChunk(line)) {
                flushText()
                segments += line
            } else {
                if (textBuffer.isNotEmpty()) {
                    textBuffer.append('\n')
                }
                textBuffer.append(line)
            }
        }
        flushText()
        return segments.takeIf { it.size > 1 }
    }

    private fun splitTrailingJsonChunk(raw: String): List<String>? {
        val markers = listOf(
            "{\"choices\":",
            "{\"object\":\"chat.completion.chunk\"",
            "{\"usage\":"
        )
        markers.forEach { marker ->
            val index = raw.indexOf(marker)
            if (index <= 0) return@forEach
            // Guard: if the marker lands inside a JSON string value (odd number of
            // unescaped quotes before it), the split point is invalid. This prevents
            // incorrectly splitting on content like {"text": "see the {\"choices\":…}"}.
            if (!isAtJsonTopLevel(raw, index)) return@forEach
            val prefix = raw.substring(0, index).trim()
            val suffix = raw.substring(index).trim()
            if (prefix.isEmpty() || suffix.isEmpty()) return@forEach
            val parsed = parseJsonObject(suffix) ?: return@forEach
            val objectType = parsed["object"]?.jsonPrimitive?.contentOrNull
            if (
                parsed.containsKey("choices") ||
                parsed.containsKey("usage") ||
                objectType == "chat.completion.chunk"
            ) {
                return listOf(prefix, suffix)
            }
        }
        return null
    }

    /** Returns true if [position] in [text] is outside a JSON quoted string. */
    private fun isAtJsonTopLevel(text: String, position: Int): Boolean {
        var unescapedQuotes = 0
        var i = 0
        while (i < position) {
            if (text[i] == '"') {
                // Count backslashes immediately before this quote to detect escaping.
                var backslashes = 0
                var j = i - 1
                while (j >= 0 && text[j] == '\\') { backslashes++; j-- }
                if (backslashes % 2 == 0) unescapedQuotes++
            }
            i++
        }
        return unescapedQuotes % 2 == 0
    }

    private fun isStructuredChunk(raw: String): Boolean {
        return raw == "[DONE]" || parseJsonObject(raw) != null
    }

    private fun parseJsonObject(raw: String): JsonObject? {
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    fun currentReasoning(): String = AgentTextSanitizer.sanitizeUtf16(reasoningBuffer.toString())

    fun currentReasoningLength(): Int = reasoningBuffer.length

    fun currentContent(): String = AgentTextSanitizer.sanitizeUtf16(contentBuffer.toString())

    fun currentToolCallSnapshots(): List<StreamingToolCallSnapshot> {
        return toolCallBuilders.entries.map { (index, builder) ->
            StreamingToolCallSnapshot(
                index = index,
                id = builder.id?.takeIf { it.isNotBlank() },
                name = builder.name?.takeIf { it.isNotBlank() },
                arguments = builder.arguments.toString()
            )
        }
    }

    fun hasDoneSignal(): Boolean = seenDoneSignal

    fun currentFinishReason(): String? = finishReason

    fun hasUsagePayload(): Boolean = usage != null

    fun hasAssistantPayload(): Boolean {
        return contentBuffer.isNotEmpty() ||
            reasoningBuffer.isNotEmpty() ||
            toolCallBuilders.isNotEmpty()
    }

    fun canFinalizeOnClosed(): Boolean {
        return hasDoneSignal() ||
            !finishReason.isNullOrBlank() ||
            (hasUsagePayload() && hasAssistantPayload())
    }

    fun buildTurn(): ChatCompletionTurn {
        if (!seenChunk) {
            throw IllegalStateException("chat completion stream ended without chunks")
        }
        flushInlineTextBuffer(final = true)
        flushInlineToolMarkupBuffer(final = true)
        val toolCalls = toolCallBuilders.entries.map { (index, builder) ->
            val name = builder.name?.trim().orEmpty()
            if (name.isBlank()) {
                throw IllegalStateException("tool_call[$index] missing function.name")
            }
            AssistantToolCall(
                id = builder.id?.takeIf { it.isNotBlank() } ?: "tool_call_$index",
                type = builder.type?.takeIf { it.isNotBlank() } ?: "function",
                function = AssistantToolCallFunction(
                    name = name,
                    arguments = builder.arguments.toString()
                )
            )
        }

        val content = AgentTextSanitizer.sanitizeUtf16(contentBuffer.toString())
        val reasoning = AgentTextSanitizer.sanitizeUtf16(reasoningBuffer.toString())
        if (finishReasonIndicatesToolCall(finishReason) && toolCalls.isEmpty()) {
            throw IllegalStateException(
                "finish_reason indicates tool call but no tool_calls parsed; finish_reason=${finishReason.orEmpty()}, last_chunk=$lastChunkPreview"
            )
        }
        if (content.isBlank() && toolCalls.isEmpty()) {
            providerError?.let { error ->
                throw IllegalStateException(buildProviderErrorMessage(error))
            }
            throw IllegalStateException(
                "assistant turn has neither content nor tool_calls; finish_reason=${finishReason.orEmpty()}, last_chunk=$lastChunkPreview"
            )
        }

        val turn = ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = content.ifBlank { null }?.let(::JsonPrimitive),
                toolCalls = toolCalls.ifEmpty { null },
                reasoningContent = reasoning.takeIf {
                    it.isNotBlank() && (
                        includeReasoningInAssistantMessage ||
                            toolCalls.isNotEmpty() ||
                            finishReasonIndicatesToolCall(finishReason)
                        )
                }
            ),
            reasoning = reasoning,
            finishReason = finishReason,
            usage = usageWithDecodedTiming()
        )

        OmniLog.i(
            TAG,
            "[stream done] chunks=$chunkIndex, content_len=${content.length}, " +
                "reasoning_len=${reasoningBuffer.length}, tool_calls=${toolCalls.size}, " +
                "finish=$finishReason, done=$seenDoneSignal" +
                (turn.usage?.let { u ->
                    ", usage(prompt=${u.promptTokens}, completion=${u.completionTokens}, total=${u.totalTokens}" +
                        (u.decodeTokensPerSecond?.let { ", decode=${it}tok/s" }.orEmpty()) + ")"
                }.orEmpty())
        )

        return turn
    }

    private data class MutableToolCallBuilder(
        var id: String? = null,
        var type: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )

    private data class InlineFunctionToolCall(
        val name: String,
        val arguments: JsonObject
    )

    private data class StreamProviderError(
        val statusCode: Int? = null,
        val code: String? = null,
        val type: String? = null,
        val message: String
    )

    private fun consumeChoice(choice: JsonObject): Boolean {
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
        var hasPayload = false

        val delta = choice["delta"] as? JsonObject
        if (delta != null) {
            hasPayload = consumeMessageLike(delta, isDelta = true) || hasPayload
        }

        val message = choice["message"] as? JsonObject
        if (message != null) {
            hasPayload = consumeMessageLike(message, isDelta = false) || hasPayload
        }

        val directToolCalls = choice["tool_calls"] as? JsonArray
        if (directToolCalls != null) {
            mergeToolCalls(
                directToolCalls,
                isDelta = delta != null || message == null
            )
            hasPayload = true
        }

        val directFunctionCall = choice["function_call"] as? JsonObject
        if (directFunctionCall != null) {
            mergeLegacyFunctionCall(
                directFunctionCall,
                isDelta = delta != null || message == null
            )
            hasPayload = true
        }

        // 某些 OpenAI-compat 实现会返回 completion 风格的 choice.text
        hasPayload = appendTextPayload(choice["text"]) || hasPayload
        return hasPayload
    }

    private fun consumeMessageLike(message: JsonObject, isDelta: Boolean): Boolean {
        var hasPayload = false
        hasPayload = appendTextPayload(message["content"]) || hasPayload
        appendReasoningPayload(message["reasoning_content"])
        appendReasoningPayload(message["reasoning"])
        appendReasoningPayload(message["thinking"])

        val toolCalls = message["tool_calls"] as? JsonArray
        if (toolCalls != null) {
            mergeToolCalls(toolCalls, isDelta = isDelta)
            hasPayload = true
        }

        val functionCall = message["function_call"] as? JsonObject
        if (functionCall != null) {
            mergeLegacyFunctionCall(functionCall, isDelta = isDelta)
            hasPayload = true
        }
        return hasPayload
    }

    private fun mergeToolCalls(toolCalls: JsonArray, isDelta: Boolean) {
        toolCalls.forEachIndexed { arrayIndex, callElement ->
            val call = callElement as? JsonObject ?: return@forEachIndexed
            val index = call["index"]?.jsonPrimitive?.intOrNull ?: arrayIndex
            val builder = toolCallBuilders.getOrPut(index) { MutableToolCallBuilder() }

            call["id"]?.jsonPrimitive?.contentOrNull?.let { builder.id = it }
            call["type"]?.jsonPrimitive?.contentOrNull?.let { builder.type = it }
            val function = call["function"] as? JsonObject
            function?.get("name")?.jsonPrimitive?.contentOrNull?.let { namePiece ->
                mergeToolName(builder, namePiece, isDelta)
            }

            val argumentsElement = function?.get("arguments")
            val argumentsPiece = when (argumentsElement) {
                null, JsonNull -> null
                is JsonPrimitive -> argumentsElement.contentOrNull
                else -> json.encodeToString(JsonElement.serializer(), argumentsElement)
            }

            if (!argumentsPiece.isNullOrEmpty()) {
                if (isDelta) {
                    builder.arguments.append(argumentsPiece)
                } else {
                    builder.arguments.setLength(0)
                    builder.arguments.append(argumentsPiece)
                }
            }
        }
    }

    private fun mergeLegacyFunctionCall(functionCall: JsonObject, isDelta: Boolean) {
        val builder = toolCallBuilders.getOrPut(0) { MutableToolCallBuilder() }
        builder.type = "function"

        functionCall["name"]?.jsonPrimitive?.contentOrNull?.let { namePiece ->
            mergeToolName(builder, namePiece, isDelta)
        }
        val argumentsElement = functionCall["arguments"]
        val argumentsPiece = when (argumentsElement) {
            null, JsonNull -> null
            is JsonPrimitive -> argumentsElement.contentOrNull
            else -> json.encodeToString(JsonElement.serializer(), argumentsElement)
        }
        if (!argumentsPiece.isNullOrEmpty()) {
            if (isDelta) {
                builder.arguments.append(argumentsPiece)
            } else {
                builder.arguments.setLength(0)
                builder.arguments.append(argumentsPiece)
            }
        }
    }

    private fun mergeToolName(
        builder: MutableToolCallBuilder,
        namePiece: String,
        isDelta: Boolean
    ) {
        if (namePiece.isBlank()) return
        if (!isDelta) {
            builder.name = namePiece
            return
        }
        val current = builder.name.orEmpty()
        builder.name = when {
            current.isEmpty() -> namePiece
            namePiece.startsWith(current) -> namePiece
            current.endsWith(namePiece) -> current
            else -> current + namePiece
        }
    }

    private fun decodeUsage(element: JsonElement?): ChatCompletionUsage? {
        return decodeChatCompletionUsage(element)
    }

    private fun usageWithDecodedTiming(): ChatCompletionUsage? {
        val decode = decodeTokensPerSecond
        val currentUsage = usage
        if (decode == null) {
            return currentUsage
        }
        if (currentUsage?.decodeTokensPerSecond == decode) {
            return currentUsage
        }
        return ChatCompletionUsage(
            promptTokens = currentUsage?.promptTokens,
            completionTokens = currentUsage?.completionTokens,
            totalTokens = currentUsage?.totalTokens,
            prefillTokensPerSecond = currentUsage?.prefillTokensPerSecond,
            decodeTokensPerSecond = decode,
            promptTokensDetails = currentUsage?.promptTokensDetails,
            completionTokensDetails = currentUsage?.completionTokensDetails
        )
    }

    private fun decodeTimings(element: JsonElement?): Double? {
        val obj = element as? JsonObject ?: return null
        return obj["predicted_per_second"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    }

    private fun extractProviderError(root: JsonObject): StreamProviderError? {
        val errorObj = root["error"] as? JsonObject ?: return null
        val message = extractText(errorObj["message"])
            ?: extractText(errorObj["detail"])
            ?: extractText(root["message"])
            ?: return null
        val statusCode = root["status_code"]?.jsonPrimitive?.intOrNull
            ?: errorObj["status_code"]?.jsonPrimitive?.intOrNull
        return StreamProviderError(
            statusCode = statusCode,
            code = extractText(errorObj["code"]),
            type = extractText(errorObj["type"]),
            message = message
        )
    }

    private fun buildProviderErrorMessage(error: StreamProviderError): String {
        val suffix = buildList {
            error.statusCode?.let { add("status=$it") }
            error.code?.takeIf { it.isNotBlank() }?.let { add("code=$it") }
            error.type?.takeIf { it.isNotBlank() }?.let { add("type=$it") }
        }.joinToString(", ")
        return if (suffix.isBlank()) {
            "provider stream returned error: ${error.message}"
        } else {
            "provider stream returned error ($suffix): ${error.message}"
        }
    }

    private fun appendReasoningPayload(element: JsonElement?) {
        extractText(element)?.let { appendReasoningText(it) }
    }

    private fun appendTextPayload(element: JsonElement?): Boolean {
        val text = extractText(element) ?: return false
        appendTextChunk(text)
        return text.isNotEmpty()
    }

    private fun appendTextChunk(text: String) {
        if (text.isEmpty()) {
            return
        }
        if (!preferInlineThinkTags) {
            appendVisibleText(text)
            return
        }
        inlineTextBuffer.append(text)
        flushInlineTextBuffer(final = false)
    }

    private fun flushInlineTextBuffer(final: Boolean) {
        if (!preferInlineThinkTags) {
            if (inlineTextBuffer.isNotEmpty()) {
                appendVisibleText(inlineTextBuffer.toString())
                inlineTextBuffer.setLength(0)
            }
            return
        }

        while (inlineTextBuffer.isNotEmpty()) {
            val bufferText = inlineTextBuffer.toString()
            if (thinkSectionOpen) {
                val closeIndex = bufferText.indexOf(THINK_CLOSE_TAG)
                if (closeIndex >= 0) {
                    appendReasoningText(bufferText.substring(0, closeIndex))
                    inlineTextBuffer.delete(0, closeIndex + THINK_CLOSE_TAG.length)
                    thinkSectionOpen = false
                    inlineThinkTagObserved = true
                    continue
                }

                if (final) {
                    appendReasoningText(bufferText)
                    inlineTextBuffer.setLength(0)
                    return
                }

                val retainedLength = partialInlineTagSuffixLength(bufferText)
                val safeLength = inlineTextBuffer.length - retainedLength
                if (safeLength <= 0) {
                    return
                }
                appendReasoningText(bufferText.substring(0, safeLength))
                inlineTextBuffer.delete(0, safeLength)
                return
            }

            val openIndex = bufferText.indexOf(THINK_OPEN_TAG)
            val closeIndex = bufferText.indexOf(THINK_CLOSE_TAG)

            if (openIndex >= 0 && (closeIndex < 0 || openIndex < closeIndex)) {
                appendVisibleText(bufferText.substring(0, openIndex))
                inlineTextBuffer.delete(0, openIndex + THINK_OPEN_TAG.length)
                thinkSectionOpen = true
                inlineThinkTagObserved = true
                continue
            }

            if (closeIndex >= 0 && contentBuffer.isEmpty()) {
                appendReasoningText(bufferText.substring(0, closeIndex))
                inlineTextBuffer.delete(0, closeIndex + THINK_CLOSE_TAG.length)
                inlineThinkTagObserved = true
                continue
            }

            if (final) {
                appendVisibleText(bufferText)
                inlineTextBuffer.setLength(0)
                return
            }

            if (!inlineThinkTagObserved && contentBuffer.isEmpty()) {
                return
            }

            val retainedLength = partialInlineTagSuffixLength(bufferText)
            val safeLength = inlineTextBuffer.length - retainedLength
            if (safeLength <= 0) {
                return
            }
            appendVisibleText(bufferText.substring(0, safeLength))
            inlineTextBuffer.delete(0, safeLength)
            return
        }
    }

    private fun partialInlineTagSuffixLength(text: String): Int {
        var longest = 0
        INLINE_THINK_TAGS.forEach { tag ->
            val upperBound = minOf(text.length, tag.length - 1)
            for (candidate in upperBound downTo 1) {
                if (text.endsWith(tag.substring(0, candidate))) {
                    longest = maxOf(longest, candidate)
                    break
                }
            }
        }
        return longest
    }

    private fun appendVisibleText(text: String) {
        if (text.isEmpty()) {
            return
        }
        inlineToolMarkupBuffer.append(text)
        flushInlineToolMarkupBuffer(final = false)
    }

    private fun appendPlainVisibleText(text: String) {
        if (text.isEmpty()) {
            return
        }
        contentBuffer.append(text)
    }

    private fun flushInlineToolMarkupBuffer(final: Boolean) {
        while (inlineToolMarkupBuffer.isNotEmpty()) {
            val bufferText = inlineToolMarkupBuffer.toString()
            val toolCallOpenIndex = bufferText.indexOf(TOOL_CALL_OPEN_TAG)
            val functionOpenIndex = bufferText.indexOf(FUNCTION_OPEN_MARKER)
            val markerIndex = firstNonNegative(toolCallOpenIndex, functionOpenIndex)

            if (markerIndex < 0) {
                val retainedLength = if (final) 0 else partialMarkerSuffixLength(
                    bufferText,
                    INLINE_TOOL_MARKERS
                )
                val safeLength = inlineToolMarkupBuffer.length - retainedLength
                if (safeLength <= 0) {
                    return
                }
                appendPlainVisibleText(bufferText.substring(0, safeLength))
                inlineToolMarkupBuffer.delete(0, safeLength)
                return
            }

            if (markerIndex > 0) {
                appendPlainVisibleText(bufferText.substring(0, markerIndex))
                inlineToolMarkupBuffer.delete(0, markerIndex)
                continue
            }

            if (bufferText.startsWith(TOOL_CALL_OPEN_TAG)) {
                val wrapperCloseIndex = bufferText.indexOf(
                    TOOL_CALL_CLOSE_TAG,
                    TOOL_CALL_OPEN_TAG.length
                )
                if (wrapperCloseIndex < 0) {
                    if (final) {
                        appendPlainVisibleText(bufferText)
                        inlineToolMarkupBuffer.setLength(0)
                    }
                    return
                }
                val wrappedEnd = wrapperCloseIndex + TOOL_CALL_CLOSE_TAG.length
                val wrappedBlock = bufferText.substring(0, wrappedEnd)
                val innerBlock = wrappedBlock
                    .removePrefix(TOOL_CALL_OPEN_TAG)
                    .removeSuffix(TOOL_CALL_CLOSE_TAG)
                    .trim()
                val parsed = parseInlineFunctionToolCall(innerBlock)
                if (parsed == null) {
                    val fallbackSummary = inlineJsonToolPayloadSummary(innerBlock)
                    if (fallbackSummary.isNullOrBlank()) {
                        appendPlainVisibleText(wrappedBlock)
                    } else {
                        appendPlainVisibleText(fallbackSummary)
                    }
                } else {
                    mergeInlineFunctionToolCall(parsed)
                    OmniLog.d(TAG, "parsed inline function tool call: ${parsed.name}")
                }
                inlineToolMarkupBuffer.delete(0, wrappedEnd)
                deleteLeadingWhitespace()
                continue
            }

            val closeIndex = bufferText.indexOf(FUNCTION_CLOSE_TAG)
            if (closeIndex < 0) {
                if (final) {
                    appendPlainVisibleText(bufferText)
                    inlineToolMarkupBuffer.setLength(0)
                }
                return
            }

            val functionEnd = closeIndex + FUNCTION_CLOSE_TAG.length
            val block = bufferText.substring(0, functionEnd)
            val parsed = parseInlineFunctionToolCall(block)
            if (parsed == null) {
                appendPlainVisibleText(block)
            } else {
                mergeInlineFunctionToolCall(parsed)
                OmniLog.d(TAG, "parsed inline function tool call: ${parsed.name}")
            }
            inlineToolMarkupBuffer.delete(0, functionEnd)
            deleteOptionalLeadingToolCallClose()
        }
    }

    private fun parseInlineFunctionToolCall(block: String): InlineFunctionToolCall? {
        val normalized = block.trim()
        parseInlineFunctionMarkupToolCall(normalized)?.let { return it }
        return parseInlineJsonToolCall(normalized)
    }

    private fun parseInlineFunctionMarkupToolCall(block: String): InlineFunctionToolCall? {
        val match = FUNCTION_BLOCK_REGEX.matchEntire(block) ?: return null
        val name = xmlUnescape(match.groupValues[1]).trim()
        if (name.isEmpty()) {
            return null
        }
        val parameterMatches = PARAMETER_REGEX.findAll(match.groupValues[2]).toList()
        // Keep pseudo/debug markup as normal assistant text. Real Qwen-style
        // tool calls include parameter tags, which are enough to build JSON args.
        if (parameterMatches.isEmpty()) {
            return null
        }
        val arguments = linkedMapOf<String, JsonElement>()
        parameterMatches.forEach { parameterMatch ->
            val key = xmlUnescape(parameterMatch.groupValues[1]).trim()
            if (key.isEmpty()) {
                return@forEach
            }
            arguments[key] = inlineParameterValueToJson(parameterMatch.groupValues[2])
        }
        if (arguments.isEmpty()) {
            return null
        }
        return InlineFunctionToolCall(name = name, arguments = JsonObject(arguments))
    }

    private fun parseInlineJsonToolCall(block: String): InlineFunctionToolCall? {
        val payload = parseJsonObject(block) ?: return null
        if (isLikelyInlineToolResultPayload(payload)) {
            return null
        }
        val functionPayload = payload["function"] as? JsonObject
        val name = firstJsonString(
            payload["toolName"],
            payload["tool_name"],
            payload["name"],
            functionPayload?.get("name"),
            (payload["tool"] as? JsonObject)?.get("name")
        )?.trim().orEmpty()
        if (name.isEmpty()) {
            return null
        }

        val explicitArguments = extractInlineJsonArguments(payload, functionPayload)
        val arguments = explicitArguments ?: inferInlineJsonArguments(payload)
        return InlineFunctionToolCall(name = name, arguments = arguments)
    }

    private fun extractInlineJsonArguments(
        payload: JsonObject,
        functionPayload: JsonObject?
    ): JsonObject? {
        functionPayload?.get("arguments")?.let { element ->
            jsonObjectArgumentValue(element)?.let { return it }
        }
        JSON_TOOL_ARGUMENT_KEYS.forEach { key ->
            payload[key]?.let { element ->
                jsonObjectArgumentValue(element)?.let { return it }
            }
        }
        return null
    }

    private fun jsonObjectArgumentValue(element: JsonElement): JsonObject? {
        return when (element) {
            is JsonObject -> element
            is JsonPrimitive -> {
                val text = element.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) {
                    JsonObject(emptyMap())
                } else {
                    parseJsonObject(text)
                }
            }
            else -> null
        }
    }

    private fun inferInlineJsonArguments(payload: JsonObject): JsonObject {
        val arguments = linkedMapOf<String, JsonElement>()
        payload.forEach { (key, value) ->
            val normalizedKey = normalizePayloadKey(key)
            if (JSON_TOOL_METADATA_KEYS.contains(normalizedKey)) {
                return@forEach
            }
            arguments[key] = value
        }
        return JsonObject(arguments)
    }

    private fun isLikelyInlineToolResultPayload(payload: JsonObject): Boolean {
        val keys = payload.keys.map(::normalizePayloadKey).toSet()
        return keys.any(JSON_TOOL_RESULT_PAYLOAD_KEYS::contains) &&
            keys.any(JSON_TOOL_RESULT_STATUS_KEYS::contains)
    }

    private fun inlineJsonToolPayloadSummary(block: String): String? {
        val payload = parseJsonObject(block) ?: return null
        if (!isLikelyInlineToolResultPayload(payload)) {
            return null
        }
        return firstJsonString(
            payload["summary"],
            payload["progress"],
            payload["message"],
            payload["displayName"],
            payload["display_name"],
            payload["toolTitle"],
            payload["tool_title"]
        )?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun firstJsonString(vararg elements: JsonElement?): String? {
        elements.forEach { element ->
            val value = (element as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun normalizePayloadKey(key: String): String {
        return key.replace("_", "").lowercase()
    }

    private fun mergeInlineFunctionToolCall(call: InlineFunctionToolCall) {
        val nextIndex = (toolCallBuilders.keys.maxOrNull() ?: -1) + 1
        val builder = toolCallBuilders.getOrPut(nextIndex) { MutableToolCallBuilder() }
        builder.id = "inline_tool_call_$nextIndex"
        builder.type = "function"
        builder.name = call.name
        builder.arguments.setLength(0)
        builder.arguments.append(json.encodeToString(JsonElement.serializer(), call.arguments))
    }

    private fun inlineParameterValueToJson(raw: String): JsonElement {
        val value = xmlUnescape(raw).trim()
        if (value.isEmpty()) {
            return JsonPrimitive("")
        }
        val shouldTryJson = value.startsWith("{") ||
            value.startsWith("[") ||
            value.startsWith("\"") ||
            value == "true" ||
            value == "false" ||
            value == "null" ||
            value.matches(Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?"))
        if (shouldTryJson) {
            runCatching { json.parseToJsonElement(value) }.getOrNull()?.let {
                return it
            }
        }
        return JsonPrimitive(value)
    }

    private fun deleteOptionalLeadingToolCallClose() {
        deleteLeadingWhitespace()
        if (inlineToolMarkupBuffer.startsWith(TOOL_CALL_CLOSE_TAG)) {
            inlineToolMarkupBuffer.delete(0, TOOL_CALL_CLOSE_TAG.length)
            deleteLeadingWhitespace()
        }
    }

    private fun deleteLeadingWhitespace() {
        var count = 0
        while (
            count < inlineToolMarkupBuffer.length &&
            inlineToolMarkupBuffer[count].isWhitespace()
        ) {
            count += 1
        }
        if (count > 0) {
            inlineToolMarkupBuffer.delete(0, count)
        }
    }

    private fun firstNonNegative(left: Int, right: Int): Int {
        return when {
            left < 0 -> right
            right < 0 -> left
            else -> minOf(left, right)
        }
    }

    private fun partialMarkerSuffixLength(text: String, markers: List<String>): Int {
        var longest = 0
        markers.forEach { marker ->
            val upperBound = minOf(text.length, marker.length - 1)
            for (candidate in upperBound downTo 1) {
                if (text.endsWith(marker.substring(0, candidate))) {
                    longest = maxOf(longest, candidate)
                    break
                }
            }
        }
        return longest
    }

    private fun xmlUnescape(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun appendReasoningText(text: String) {
        if (text.isEmpty()) {
            return
        }
        reasoningBuffer.append(text)
    }

    private fun extractText(element: JsonElement?): String? {
        return when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                if (element.isString) {
                    element.contentOrNull
                } else {
                    element.booleanOrNull?.toString()
                        ?: element.contentOrNull
                }
            }

            is JsonArray -> element.mapNotNull { extractTextFromArrayItem(it) }
                .joinToString("")
                .ifEmpty { null }

            is JsonObject -> {
                extractText(element["text"])
                    ?: extractText(element["content"])
                    ?: extractText(element["value"])
            }
        }
    }

    private fun extractTextFromArrayItem(item: JsonElement): String? {
        return when (item) {
            is JsonObject -> {
                val type = item["type"]?.jsonPrimitive?.contentOrNull
                if (type == "text") {
                    extractText(item["text"])
                } else {
                    extractText(item["content"]) ?: extractText(item["text"])
                }
            }

            else -> extractText(item)
        }
    }

    private fun finishReasonIndicatesToolCall(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase().orEmpty()
        return normalized == "tool_calls" ||
            normalized == "function_call" ||
            normalized == "tool_use"
    }

}
