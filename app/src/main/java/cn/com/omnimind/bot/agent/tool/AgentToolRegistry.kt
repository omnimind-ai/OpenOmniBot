package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.shizuku.PrivilegedActionPolicy
import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveredServer
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import cn.com.omnimind.bot.runlog.OobFunctionSchemaBuilder
import cn.com.omnimind.bot.runlog.OobRunLogReplayService
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class AgentToolRegistry(
    private val context: Context,
    discoveredServers: List<RemoteMcpDiscoveredServer>,
    conversationMode: String = AgentConversationModePolicy.NORMAL_MODE,
    dynamicDefinitions: List<JsonObject> = emptyList(),
    toolExposurePolicy: AgentToolExposurePolicy = AgentToolExposurePolicy.DEFAULT,
) : AgentToolCatalog {
    data class RuntimeToolDescriptor(
        val name: String,
        val displayName: String,
        val toolType: String,
        val serverName: String? = null,
        val remoteTool: RemoteMcpToolDescriptor? = null
    )

    private val tag = "AgentToolRegistry"
    private val toolSchemas = linkedMapOf<String, JsonObject>()
    private val runtimeDescriptors = linkedMapOf<String, RuntimeToolDescriptor>()
    override val toolsForModel: List<ChatCompletionTool>

    init {
        val locale = runCatching { AppLocaleManager.resolvePromptLocale(context) }
            .getOrDefault(AppLocaleManager.currentPromptLocale())
        val shizukuStatus = runCatching { ShizukuCapabilityManager.get(context).getStatus() }
            .onFailure { OmniLog.w(tag, "resolve shizuku status failed: ${it.message}") }
            .getOrNull()
        val projectCapabilityEnabled = runCatching {
            WorkbenchProjectStore(context).isProjectCapabilityEnabled()
        }.onFailure {
            OmniLog.w(tag, "resolve project capability failed: ${it.message}")
        }.getOrDefault(false)
        val runtimeDefinitions = mutableListOf<JsonObject>()
        runtimeDefinitions.addAll(AgentToolDefinitions.staticTools(locale))
        if (shizukuStatus?.isGranted() == true) {
            val privilegedVisibleActions = shizukuStatus.availableActions.ifEmpty {
                PrivilegedActionPolicy.visibleAgentActions(
                    if (shizukuStatus.backend == ShizukuBackend.ROOT) {
                        ShizukuBackend.ROOT
                    } else {
                        ShizukuBackend.ADB
                    }
                )
            }
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedActionTool(
                    visibleActions = privilegedVisibleActions,
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionStartTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionExecTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionReadTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionStopTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
        }
        runtimeDefinitions.addAll(AgentToolDefinitions.memoryTools(locale))
        runtimeDefinitions.addAll(AgentToolDefinitions.subagentTools(locale))
        discoveredServers.flatMap { it.tools }.forEach { tool ->
            runtimeDefinitions.add(toDynamicMcpToolDefinition(tool, locale))
        }
        runtimeDefinitions.addAll(oobFunctionToolDefinitions(locale))

        runtimeDefinitions.addAll(dynamicDefinitions)

        val projectFilteredDefinitions = filterProjectToolDefinitionsForCapability(
            definitions = runtimeDefinitions,
            projectCapabilityEnabled = projectCapabilityEnabled
        )
        val conversationFilteredDefinitions = AgentConversationModePolicy
            .filterToolDefinitionsForConversationMode(projectFilteredDefinitions, conversationMode)
        val allowedToolNames = toolExposurePolicy.effectiveAllowedTools()
        val filteredDefinitions = filterToolDefinitionsForExposurePolicy(
            definitions = conversationFilteredDefinitions,
            allowedToolNames = allowedToolNames,
        )

        val toolsByName = linkedMapOf<String, ChatCompletionTool>()
        filteredDefinitions.forEach { definition ->
            val function = definition["function"] as? JsonObject ?: return@forEach
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@forEach
            if (!MODEL_TOOL_NAME_REGEX.matches(name)) {
                OmniLog.w(tag, "skip invalid model tool name: $name")
                return@forEach
            }
            val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val parameters = (function["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
            val displayName = function["displayName"]?.jsonPrimitive?.contentOrNull?.trim()
                .takeUnless { it.isNullOrBlank() } ?: name
            val toolType = function["toolType"]?.jsonPrimitive?.contentOrNull?.trim()
                .takeUnless { it.isNullOrBlank() } ?: "builtin"
            val serverName = function["serverName"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }

            toolSchemas[name] = parameters
            runtimeDescriptors[name] = RuntimeToolDescriptor(
                name = name,
                displayName = displayName,
                toolType = toolType,
                serverName = serverName,
                remoteTool = findRemoteTool(name, discoveredServers)
            )
            toolsByName[name] = ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = name,
                    description = description,
                    parameters = parameters
                )
            )
        }
        toolsForModel = toolsByName.values.toList()

        // Debug dump: full registered tool list to verify which ones the LLM actually receives.
        OmniLog.i(
            tag,
                "registered_tools count=${toolsForModel.size} " +
                "conversationMode=$conversationMode " +
                "tool_profile=${toolExposurePolicy.profile.orEmpty()} " +
                "tool_allowlist_size=${allowedToolNames?.size ?: 0} " +
                "project_capability=$projectCapabilityEnabled " +
                "subagent_present=${"subagent_dispatch" in runtimeDescriptors.keys} " +
                "memory_load_present=${"memory_load" in runtimeDescriptors.keys} " +
                "names=[${runtimeDescriptors.keys.joinToString(",")}]"
        )
    }

    private fun filterToolDefinitionsForExposurePolicy(
        definitions: List<JsonObject>,
        allowedToolNames: Set<String>?,
    ): List<JsonObject> {
        if (allowedToolNames.isNullOrEmpty()) {
            return definitions
        }
        return definitions.filter { definition ->
            val toolName = (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            toolName in allowedToolNames
        }
    }

    private fun filterProjectToolDefinitionsForCapability(
        definitions: List<JsonObject>,
        projectCapabilityEnabled: Boolean
    ): List<JsonObject> {
        if (projectCapabilityEnabled) {
            return definitions
        }
        return definitions.filterNot(::isProjectToolDefinition)
    }

    private fun isProjectToolDefinition(definition: JsonObject): Boolean {
        val toolName = (definition["function"] as? JsonObject)
            ?.get("name")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        return toolName.startsWith("workbench_project_") ||
            toolName.startsWith("workbench_api_")
    }

    override fun runtimeDescriptor(toolName: String): RuntimeToolDescriptor {
        return runtimeDescriptors[toolName] ?: RuntimeToolDescriptor(
            name = toolName,
            displayName = toolName,
            toolType = "builtin"
        )
    }

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        val schema = toolSchemas[toolName] ?: return
        validateWithSchema(toolName, schema, arguments)
    }

    private fun validateWithSchema(
        toolName: String,
        schema: JsonObject,
        arguments: JsonObject
    ) {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (type.isNotBlank() && type != "object") {
            throw IllegalArgumentException("Tool $toolName schema type must be object")
        }
        val properties = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val requiredFields = (schema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        requiredFields.forEach { field ->
            if (arguments[field] == null || arguments[field] is JsonNull) {
                throw IllegalArgumentException("Tool $toolName missing required argument: $field")
            }
        }
        arguments.entries.forEach { (field, value) ->
            val propertySchema = properties[field] as? JsonObject ?: return@forEach
            validateFieldType(toolName, field, value, propertySchema)
        }
    }

    private fun validateFieldType(
        toolName: String,
        field: String,
        value: JsonElement,
        propertySchema: JsonObject
    ) {
        val expectedType = propertySchema["type"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!expectedType.isNullOrBlank() && !matchesType(expectedType, value)) {
            throw IllegalArgumentException(
                "Tool $toolName argument $field expected $expectedType but got ${describeType(value)}"
            )
        }
        val enumValues = (propertySchema["enum"] as? JsonArray).orEmpty()
        if (enumValues.isNotEmpty()) {
            val raw = (value as? JsonPrimitive)?.contentOrNull
            if (raw == null || enumValues.none { it.jsonPrimitive.contentOrNull == raw }) {
                throw IllegalArgumentException(
                    "Tool $toolName argument $field must be one of ${
                        enumValues.joinToString(",") { it.toString() }
                    }"
                )
            }
        }
    }

    private fun matchesType(expectedType: String, value: JsonElement): Boolean {
        return when (expectedType) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> true
        }
    }

    private fun describeType(value: JsonElement): String {
        return when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonNull -> "null"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.booleanOrNull != null -> "boolean"
                value.intOrNull != null -> "integer"
                value.doubleOrNull != null -> "number"
                else -> "primitive"
            }
        }
    }

    private fun findRemoteTool(
        toolName: String,
        discoveredServers: List<RemoteMcpDiscoveredServer>
    ): RemoteMcpToolDescriptor? {
        return discoveredServers.asSequence()
            .flatMap { it.tools.asSequence() }
            .firstOrNull { it.encodedToolName == toolName }
    }

    private fun toDynamicMcpToolDefinition(
        tool: RemoteMcpToolDescriptor,
        locale: PromptLocale
    ): JsonObject {
        return AgentToolDefinitions.decorateToolDefinition(buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(tool.encodedToolName))
                put("displayName", JsonPrimitive(tool.toolName))
                put("toolType", JsonPrimitive("mcp"))
                put("serverName", JsonPrimitive(tool.serverName))
                put(
                    "description",
                    JsonPrimitive(
                        tool.description.ifBlank {
                            LocalizedText(
                                zhCN = "调用远端 MCP 工具。",
                                enUS = "Call a remote MCP tool."
                            ).resolve(locale)
                        }
                    )
                )
                put("parameters", mapToJsonElement(tool.inputSchema))
            })
        }, locale)
    }

    private fun oobFunctionToolDefinitions(locale: PromptLocale): List<JsonObject> {
        if (!AgentToolFeatureStore.isOobFunctionAsToolEnabled(context)) {
            return emptyList()
        }
        return runCatching {
            OobRunLogReplayService(context)
                .listFunctionSpecs(MAX_OOB_FUNCTION_TOOLS)
                .mapNotNull { spec -> toOobFunctionToolDefinition(spec, locale) }
        }.onFailure {
            OmniLog.w(tag, "load oob function tools failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun toOobFunctionToolDefinition(
        spec: Map<String, Any?>,
        locale: PromptLocale
    ): JsonObject? {
        val functionId = spec["function_id"]?.toString()?.trim().orEmpty()
        if (functionId.isEmpty()) return null
        if (!MODEL_TOOL_NAME_REGEX.matches(functionId)) {
            OmniLog.w(tag, "skip invalid oob function tool name: $functionId")
            return null
        }
        val displayName = spec["name"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: functionId
        val description = spec["description"]?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: displayName
        val parameters = mapToJsonElement(
            OobFunctionSchemaBuilder.inputSchema(spec)
        ) as? JsonObject ?: JsonObject(emptyMap())

        return AgentToolDefinitions.decorateToolDefinition(buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(functionId))
                put("displayName", JsonPrimitive(displayName))
                put("toolType", JsonPrimitive("oob_function"))
                put("description", JsonPrimitive(description))
                put("parameters", parameters)
            })
        }, locale)
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
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private companion object {
        const val MAX_OOB_FUNCTION_TOOLS = 50
        val MODEL_TOOL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
