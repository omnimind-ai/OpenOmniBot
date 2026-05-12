package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.webchat.FlutterChatSyncBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add

class ImagePickerToolHandler(
    private val helper: SharedHelper
) : ToolHandler {

    override val toolNames: Set<String> = setOf("image_picker")

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val source = args["source"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "gallery"
        val multiple = args["multiple"]?.jsonPrimitive?.booleanOrNull ?: false
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 9

        return try {
            if (multiple) {
                val result = FlutterChatSyncBridge.invokeForResult(
                    "agentImagePickMultiple",
                    mapOf("limit" to limit)
                )
                val files = result as? List<*> ?: emptyList<Any>()
                val paths = files.mapNotNull { (it as? Map<*, *>)?.get("path")?.toString() }
                if (paths.isEmpty()) {
                    ToolExecutionResult.Error("image_picker", "用户未选择图片")
                } else {
                    val preview = buildJsonObject {
                        putJsonArray("paths") { paths.forEach { add(it) } }
                        put("count", paths.size)
                    }
                    val summary = "已选择 ${paths.size} 张图片：${paths.joinToString(", ")}"
                    ToolExecutionResult.ContextResult(
                        toolName = "image_picker",
                        summaryText = summary,
                        previewJson = preview.toString(),
                        rawResultJson = preview.toString(),
                        success = true
                    )
                }
            } else {
                val result = FlutterChatSyncBridge.invokeForResult(
                    "agentImagePick",
                    mapOf("source" to source)
                )
                val file = result as? Map<*, *>
                val path = file?.get("path")?.toString()
                if (path.isNullOrBlank()) {
                    ToolExecutionResult.Error("image_picker", "用户未选择图片")
                } else {
                    val preview = buildJsonObject {
                        put("path", path)
                        put("name", file["name"]?.toString() ?: "")
                    }
                    ToolExecutionResult.ContextResult(
                        toolName = "image_picker",
                        summaryText = "已选择图片：$path",
                        previewJson = preview.toString(),
                        rawResultJson = preview.toString(),
                        success = true
                    )
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult.Error("image_picker", e.message ?: "图片选择失败")
        }
    }
}
