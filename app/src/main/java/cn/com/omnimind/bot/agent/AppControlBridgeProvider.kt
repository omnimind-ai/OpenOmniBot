package cn.com.omnimind.bot.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import cn.com.omnimind.bot.agent.tool.handlers.AppControlToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

class AppControlBridgeProvider : ContentProvider() {
    companion object {
        private const val METHOD_CONTROL = "control"
        private const val ARG_PAYLOAD = "payload"
        private const val ARG_RESPONSE_PATH = "responsePath"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_CONTROL) {
            return super.call(method, arg, extras)
        }
        val appContext = context?.applicationContext ?: return errorBundle("App context unavailable")
        var responsePath: String? = null
        return try {
            val payloadText = arg?.trim().takeUnless { it.isNullOrBlank() }
                ?: extras?.getString(ARG_PAYLOAD)?.trim().takeUnless { it.isNullOrBlank() }
                ?: throw IllegalArgumentException("payload 不能为空")
            val payload = parsePayload(payloadText)
            responsePath = stringValue(payload, ARG_RESPONSE_PATH)
            val sanitizedPayload = if (responsePath.isNullOrBlank()) {
                payload
            } else {
                JsonObject(payload.filterKeys { it != ARG_RESPONSE_PATH })
            }
            val result = runBlocking {
                val helper = SharedHelper(appContext, json)
                val handler = AppControlToolHandler(helper)
                handler.executeControl(
                    args = sanitizedPayload,
                    env = standaloneEnvironment(appContext)
                )
            }
            val resultJson = json.encodeToString(mapToJsonElement(result))
            if (!responsePath.isNullOrBlank()) {
                writeResponseFile(appContext, responsePath, resultJson)
            }
            Bundle().apply {
                putBoolean("success", result["success"] != false)
                putString("summary", result["summary"]?.toString())
                if (responsePath.isNullOrBlank()) {
                    putString("resultJson", resultJson)
                } else {
                    putString("resultJsonPreview", resultJson.take(2000))
                    putString("responsePath", responsePath)
                }
            }
        } catch (e: Exception) {
            if (!responsePath.isNullOrBlank()) {
                val errorJson = json.encodeToString(
                    mapToJsonElement(
                        mapOf(
                            "success" to false,
                            "error" to (e.message ?: "app control failed"),
                            "responsePath" to responsePath
                        )
                    )
                )
                runCatching { writeResponseFile(appContext, responsePath, errorJson) }
            }
            errorBundle(e.message ?: "app control failed")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun standaloneEnvironment(context: android.content.Context): DefaultAgentExecutionEnvironment {
        val appContext = context.applicationContext
        val agentRunId = "app-control-${UUID.randomUUID()}"
        val workspaceManager = AgentWorkspaceManager(appContext)
        val workspaceDescriptor = workspaceManager.buildWorkspaceDescriptor(
            conversationId = null,
            agentRunId = agentRunId
        )
        return DefaultAgentExecutionEnvironment(
            agentRunId = agentRunId,
            userMessage = "app_control_bridge",
            currentPackageName = appContext.packageName,
            runtimeContextRepository = AgentRuntimeContextRepository(appContext),
            workspaceDescriptor = workspaceDescriptor,
            resolvedSkills = emptyList(),
            workspaceManager = workspaceManager,
            workspaceMemoryService = WorkspaceMemoryService(appContext, workspaceManager),
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            terminalEnvironment = emptyMap(),
            runControl = NoOpAgentRunControl
        )
    }

    private fun parsePayload(payloadText: String): JsonObject {
        val parsed = json.decodeFromString<JsonElement>(payloadText)
        return parsed as? JsonObject
            ?: throw IllegalArgumentException("payload 必须是 JSON object")
    }

    private fun stringValue(map: JsonObject, key: String): String? {
        return (map[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun writeResponseFile(context: android.content.Context, responsePath: String, content: String) {
        val file = File(responsePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun errorBundle(message: String): Bundle {
        return Bundle().apply {
            putBoolean("success", false)
            putString("error", message)
        }
    }
}
