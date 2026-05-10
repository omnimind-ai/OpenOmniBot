package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import cn.com.omnimind.baselib.llm.decodeChatCompletionUsage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.SortedMap
import java.util.TreeMap

class VLMStreamAccumulator(
    private val json: Json
) {
    private val contentBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val toolCallBuilders: SortedMap<Int, MutableToolCallBuilder> = TreeMap()
    private var finishReason: String? = null
    private var usage: ChatCompletionUsage? = null
    private var seenChunk = false
    private var lastChunkPreview: String = ""

    fun consume(rawChunk: String): Boolean {
        val trimmed = rawChunk.trim()
        if (trimmed.isEmpty()) return false
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
        if (trimmed == "[DONE]") return true
        val root = parseJsonObject(trimmed)
        if (root == null) {
            seenChunk = true
            contentBuffer.append(trimmed)
            return false
        }
        return consumeJsonChunk(root)
    }

    private fun consumeJsonChunk(root: JsonObject): Boolean {
        seenChunk = true
        usage = decodeUsage(root["usage"]) ?: usage

        var chunkHasPayload = false
        val choices = root["choices"] as? JsonArray
        choices?.forEach { choiceElement ->
            val choice = choiceElement as? JsonObject ?: return@forEach
            val thisChoiceHasPayload = consumeChoice(choice)
            chunkHasPayload = chunkHasPayload || thisChoiceHasPayload
        }

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
                appendReasoningPayload(outputObj["thinking"])
            }
        }
        return false
    }

    private fun splitCompositeChunk(raw: String): List<String>? {
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

    private fun isStructuredChunk(raw: String): Boolean {
        return raw == "[DONE]" || parseJsonObject(raw) != null
    }

    private fun parseJsonObject(raw: String): JsonObject? {
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    fun currentReasoning(): String = reasoningBuffer.toString()

    fun currentReasoningLength(): Int = reasoningBuffer.length

    fun buildTurn(): ChatCompletionTurn {
        if (!seenChunk) {
            throw IllegalStateException("chat completion stream ended without chunks")
        }
        val toolCalls = toolCallBuilders.entries.mapNotNull { (index, builder) ->
            val name = builder.name?.trim().orEmpty()
            if (name.isBlank()) {
                return@mapNotNull null
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

        val content = contentBuffer.toString()
        val reasoning = reasoningBuffer.toString()
        if (content.isBlank() && toolCalls.isEmpty() && reasoning.isBlank()) {
            throw IllegalStateException(
                "assistant turn has neither reasoning/content/tool_calls; finish_reason=${finishReason.orEmpty()}, last_chunk=$lastChunkPreview"
            )
        }

        return ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = content.ifBlank { null }?.let(::JsonPrimitive),
                toolCalls = toolCalls.ifEmpty { null }
            ),
            reasoning = reasoning,
            finishReason = finishReason,
            usage = usage
        )
    }

    private data class MutableToolCallBuilder(
        var id: String? = null,
        var type: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
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

    private fun appendTextPayload(element: JsonElement?): Boolean {
        val text = extractTextPayload(element)
        if (text.isEmpty()) return false
        contentBuffer.append(text)
        return true
    }

    private fun appendReasoningPayload(element: JsonElement?) {
        val text = extractTextPayload(element)
        if (text.isEmpty()) return
        reasoningBuffer.append(text)
    }

    private fun extractTextPayload(element: JsonElement?): String {
        return when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonArray -> element.joinToString(separator = "") { item ->
                val obj = item as? JsonObject
                if (obj != null) {
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull
                    when (type) {
                        "text", "output_text", "input_text" ->
                            obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        else ->
                            obj["content"]?.jsonPrimitive?.contentOrNull
                                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                    }
                } else {
                    item.toString()
                }
            }
            is JsonObject -> {
                extractTextPayload(element["text"])
                    .ifBlank { extractTextPayload(element["content"]) }
                    .ifBlank { extractTextPayload(element["message"]) }
            }
            else -> ""
        }
    }

    private fun decodeUsage(element: JsonElement?): ChatCompletionUsage? {
        return decodeChatCompletionUsage(element)
    }
}
