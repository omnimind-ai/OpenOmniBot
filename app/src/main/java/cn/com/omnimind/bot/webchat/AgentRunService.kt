package cn.com.omnimind.bot.webchat

import android.content.Context
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import cn.com.omnimind.bot.manager.AssistsCoreManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NormalizedAgentRunPayload(
    val userMessage: String,
    val attachments: List<Map<String, Any?>>
)

internal object AgentRunRequestNormalizer {
    fun normalize(request: Map<String, Any?>): NormalizedAgentRunPayload {
        val explicitUserMessage = request["userMessage"]?.toString().orEmpty()
        val explicitAttachments = normalizeListOfMaps(request["attachments"])
        if (explicitUserMessage.isNotBlank() || explicitAttachments.isNotEmpty()) {
            return NormalizedAgentRunPayload(
                userMessage = explicitUserMessage,
                attachments = explicitAttachments
            )
        }

        val directContent = normalizeContentBlocks(request["content"])
        if (directContent != null) {
            return directContent
        }

        val messages = request["messages"] as? List<*> ?: emptyList<Any?>()
        for (index in messages.indices.reversed()) {
            val message = normalizeMap(messages[index]) ?: continue
            val role = message["role"]?.toString()?.trim()?.lowercase().orEmpty()
            if (role != "user") continue
            val content = message["content"]
            if (content is String) {
                return NormalizedAgentRunPayload(
                    userMessage = content,
                    attachments = emptyList()
                )
            }
            normalizeContentBlocks(content)?.let { return it }
        }

        return NormalizedAgentRunPayload(
            userMessage = "",
            attachments = emptyList()
        )
    }

    private fun normalizeContentBlocks(raw: Any?): NormalizedAgentRunPayload? {
        val blocks = raw as? List<*> ?: return null
        val texts = mutableListOf<String>()
        val attachments = mutableListOf<Map<String, Any?>>()
        blocks.forEachIndexed { index, item ->
            val block = normalizeMap(item) ?: return@forEachIndexed
            val type = block["type"]?.toString()?.trim()?.lowercase().orEmpty().ifEmpty {
                when {
                    block.containsKey("image_url") ||
                        block.containsKey("imageUrl") ||
                        block.containsKey("url") -> "image_url"
                    block.containsKey("text") -> "text"
                    else -> ""
                }
            }
            when (type) {
                "text", "input_text" -> {
                    val text = block["text"]?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        texts += text
                    }
                }

                "image_url", "input_image", "image" -> {
                    val imageUrl = extractImageUrl(block)
                    if (imageUrl.isBlank()) {
                        return@forEachIndexed
                    }
                    val attachment = linkedMapOf<String, Any?>(
                        "isImage" to true
                    )
                    val fileName = block["fileName"]?.toString()?.trim().orEmpty()
                    if (fileName.isNotBlank()) {
                        attachment["fileName"] = fileName
                        attachment["name"] = fileName
                    } else {
                        attachment["fileName"] = "image_$index"
                        attachment["name"] = "image_$index"
                    }
                    val mimeType = extractMimeType(imageUrl, block["mimeType"]?.toString())
                    if (mimeType.isNotBlank()) {
                        attachment["mimeType"] = mimeType
                    }
                    if (imageUrl.startsWith("data:", ignoreCase = true)) {
                        attachment["dataUrl"] = imageUrl
                    } else {
                        attachment["url"] = imageUrl
                    }
                    attachments += attachment
                }
            }
        }
        return NormalizedAgentRunPayload(
            userMessage = texts.joinToString("\n").trim(),
            attachments = attachments
        )
    }

    private fun extractImageUrl(block: Map<String, Any?>): String {
        val imageUrlField = block["image_url"]
        val nested = when (imageUrlField) {
            is Map<*, *> -> imageUrlField["url"]?.toString()
            else -> imageUrlField?.toString()
        }
        return sequenceOf(
            nested,
            block["url"]?.toString(),
            block["imageUrl"]?.toString()
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun extractMimeType(imageUrl: String, explicit: String?): String {
        val normalizedExplicit = explicit?.trim().orEmpty()
        if (normalizedExplicit.isNotBlank()) {
            return normalizedExplicit
        }
        if (imageUrl.startsWith("data:", ignoreCase = true)) {
            return imageUrl
                .substringAfter("data:", "")
                .substringBefore(';')
                .trim()
        }
        return ""
    }

    internal fun normalizeMap(value: Any?): Map<String, Any?>? {
        return (value as? Map<*, *>)?.entries?.associate { entry ->
            entry.key.toString() to normalizeValue(entry.value)
        }
    }

    internal fun normalizeListOfMaps(value: Any?): List<Map<String, Any?>> {
        return (value as? List<*>)?.mapNotNull { entry ->
            normalizeMap(entry)
        } ?: emptyList()
    }

    private fun normalizeValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> normalizeMap(value)
            is List<*> -> value.map { normalizeValue(it) }
            else -> value
        }
    }
}

class AgentRunService(
    private val context: Context
) {
    suspend fun startConversationRun(
        conversationId: Long,
        request: Map<String, Any?>
    ): Map<String, Any?> {
        val manager = AssistsCoreManager.sharedInstanceOrCreate(context)
        if (manager.hasActiveAgentRuns()) {
            throw IllegalStateException("设备当前已有运行中的 Agent 任务，请稍后重试")
        }
        val taskId = request["taskId"]?.toString()?.trim()?.ifEmpty { null }
            ?: UUID.randomUUID().toString()
        val normalizedPayload = AgentRunRequestNormalizer.normalize(request)
        val arguments = linkedMapOf<String, Any?>(
            "taskId" to taskId,
            "conversationId" to conversationId,
            "conversationMode" to normalizeConversationMode(
                request["conversationMode"]?.toString()
            ),
            "userMessage" to normalizedPayload.userMessage,
            "attachments" to normalizedPayload.attachments,
            "terminalEnvironment" to AgentRunRequestNormalizer.normalizeMap(request["terminalEnvironment"]),
            "modelOverride" to AgentRunRequestNormalizer.normalizeMap(request["modelOverride"])
        )
        invokeManager("createAgentTask", arguments) {
            manager.createAgentTask(it, this)
        }
        return mapOf(
            "taskId" to taskId,
            "status" to "accepted"
        )
    }

    suspend fun cancelTask(taskId: String?): Map<String, Any?> {
        val manager = AssistsCoreManager.sharedInstanceOrCreate(context)
        invokeManager(
            method = "cancelRunningTask",
            arguments = taskId?.let { mapOf("taskId" to it) }
        ) {
            manager.cancelRunningTask(it, this)
        }
        return mapOf(
            "taskId" to taskId,
            "status" to "cancelled"
        )
    }

    suspend fun clarifyTask(taskId: String?, reply: String): Map<String, Any?> {
        val manager = AssistsCoreManager.sharedInstanceOrCreate(context)
        invokeManager(
            method = "provideUserInputToVLMTask",
            arguments = mapOf("taskId" to taskId, "userInput" to reply)
        ) {
            manager.provideUserInputToVLMTask(it, this)
        }
        return mapOf(
            "taskId" to taskId,
            "status" to "submitted"
        )
    }

    private suspend fun invokeManager(
        method: String,
        arguments: Map<String, Any?>?,
        block: MethodChannel.Result.(MethodCall) -> Unit
    ): Any? {
        return suspendCancellableCoroutine { continuation ->
            val call = MethodCall(method, arguments)
            val result = object : MethodChannel.Result {
                override fun success(result: Any?) {
                    if (!continuation.isCompleted) {
                        continuation.resume(result)
                    }
                }

                override fun error(
                    errorCode: String,
                    errorMessage: String?,
                    errorDetails: Any?
                ) {
                    if (!continuation.isCompleted) {
                        continuation.resumeWithException(
                            IllegalStateException(
                                "$errorCode: ${errorMessage ?: "native bridge error"}"
                            )
                        )
                    }
                }

                override fun notImplemented() {
                    if (!continuation.isCompleted) {
                        continuation.resumeWithException(
                            NotImplementedError("Method not implemented: $method")
                        )
                    }
                }
            }
            result.block(call)
        }
    }

    private fun normalizeConversationMode(rawMode: String?): String {
        val normalized = rawMode?.trim()?.lowercase().orEmpty()
        return if (normalized.isEmpty()) "normal" else normalized
    }
}
