package cn.com.omnimind.bot.agent.koog

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolCatalog
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Phase 2 bridge: wraps an existing [AgentToolExecutor]-backed tool as a Koog [Tool] so it can be
 * registered into Koog's [ToolRegistry] and invoked from `AIAgent`.
 *
 * Mirrors the pattern used by `agents-mcp/McpTool` in Koog 0.8.0: dynamic `Tool<JSONObject, String>`
 * with a runtime-built [ToolDescriptor] and a `JsonObject` payload pulled apart inside [execute].
 *
 * The bindings (env / callback) come from the orchestrator run that creates this tool; the
 * `tool_call` id and run-control handle are synthesized per invocation so legacy cancellation /
 * progress reporting still works through [AgentExecutionEnvironment.runControl].
 */
@OptIn(InternalAgentToolsApi::class)
class KoogProxyTool(
    descriptor: ToolDescriptor,
    private val toolName: String,
    private val executor: AgentToolExecutor,
    private val catalog: AgentToolCatalog,
    private val env: AgentExecutionEnvironment,
    private val callback: AgentCallback,
    private val json: Json = DEFAULT_JSON
) : Tool<JSONObject, String>(
    argsType = typeToken<JSONObject>(),
    resultType = typeToken<String>(),
    descriptor = descriptor
) {

    override suspend fun execute(args: JSONObject): String {
        val kotlinxArgs = koogJsonObjectToKotlinx(args)
        val argsJsonText = json.encodeToString(KxJsonObject.serializer(), kotlinxArgs)
        val toolCallId = "koog-${UUID.randomUUID()}"
        val toolCall = AssistantToolCall(
            id = toolCallId,
            type = "function",
            function = AssistantToolCallFunction(name = toolName, arguments = argsJsonText)
        )
        val runtimeDescriptor = catalog.runtimeDescriptor(toolName)
        // Validate against the original ChatCompletionTool schema so semantics match the legacy path.
        catalog.validateArguments(toolName, kotlinxArgs)
        val handle = (env.runControl).beginToolExecution(toolName = toolName, toolCallId = toolCallId)
        return try {
            val result = executor.execute(
                toolCall = toolCall,
                args = kotlinxArgs,
                runtimeDescriptor = runtimeDescriptor,
                env = env,
                callback = callback,
                toolHandle = handle
            )
            serializeResultForLlm(result)
        } finally {
            runCatching { handle.complete() }
        }
    }

    companion object {
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * Renders a [ToolExecutionResult] back into a single string that the LLM can consume as a
         * `tool` role message. Mirrors what `AgentOrchestrator` would have stuffed into the
         * legacy `ChatCompletionMessage(role = "tool", content = ...)`.
         */
        fun serializeResultForLlm(result: ToolExecutionResult): String {
            return when (result) {
                is ToolExecutionResult.ChatMessage -> result.message
                is ToolExecutionResult.Clarify -> buildJsonObject {
                    put("kind", "clarify")
                    put("question", result.question)
                    result.missingFields?.let { put("missingFields", json(it)) }
                }.toString()
                is ToolExecutionResult.Error -> buildJsonObject {
                    put("kind", "error")
                    put("tool", result.toolName)
                    put("message", result.message)
                }.toString()
                is ToolExecutionResult.PermissionRequired -> buildJsonObject {
                    put("kind", "permission_required")
                    put("missing", json(result.missing))
                }.toString()
                is ToolExecutionResult.ScheduleResult -> result.previewJson.ifBlank { result.summaryText }
                is ToolExecutionResult.McpResult -> result.rawResultJson.ifBlank { result.summaryText }
                is ToolExecutionResult.MemoryResult -> result.rawResultJson.ifBlank { result.summaryText }
                is ToolExecutionResult.TerminalResult -> result.rawResultJson.ifBlank { result.summaryText }
                is ToolExecutionResult.ContextResult -> result.rawResultJson.ifBlank { result.summaryText }
                is ToolExecutionResult.Interrupted -> result.rawResultJson.ifBlank { result.summaryText }
                is ToolExecutionResult.VlmTaskStarted -> buildJsonObject {
                    put("kind", "vlm_task_started")
                    put("taskId", result.taskId)
                    put("goal", result.goal)
                }.toString()
            }
        }

        private fun json(strings: List<String>): kotlinx.serialization.json.JsonArray =
            kotlinx.serialization.json.JsonArray(strings.map { kotlinx.serialization.json.JsonPrimitive(it) })
    }
}

/**
 * Builds a Koog [ToolRegistry] from the project's [AgentToolCatalog], wrapping each
 * `ChatCompletionTool` as a [KoogProxyTool] bound to the supplied execution context.
 *
 * Must be called per agent run because [env] and [callback] are session-scoped. The legacy
 * [executor] and [catalog] are typically stable for a session as well.
 */
object KoogToolRegistryBuilder {
    fun build(
        catalog: AgentToolCatalog,
        executor: AgentToolExecutor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
    ): ToolRegistry {
        return ToolRegistry {
            catalog.toolsForModel.forEach { ccTool ->
                val descriptor = KoogToolSchemaMapper.convert(ccTool)
                tool(
                    KoogProxyTool(
                        descriptor = descriptor,
                        toolName = ccTool.function.name,
                        executor = executor,
                        catalog = catalog,
                        env = env,
                        callback = callback
                    )
                )
            }
        }
    }
}

/**
 * Converts a Koog-serialization [JSONObject] (used in Tool.execute signatures) into a kotlinx
 * [KxJsonObject] (used everywhere else in this project). Lossy for `JsonUnquotedLiteral` corner
 * cases but covers everything the LLM emits as tool-call arguments in practice.
 */
private fun koogJsonObjectToKotlinx(obj: JSONObject): KxJsonObject {
    return kotlinx.serialization.json.JsonObject(
        obj.entries.mapValues { (_, v) -> koogJsonElementToKotlinx(v) }
    )
}

private fun koogJsonElementToKotlinx(
    element: ai.koog.serialization.JSONElement
): kotlinx.serialization.json.JsonElement {
    return when (element) {
        is JSONObject -> koogJsonObjectToKotlinx(element)
        is ai.koog.serialization.JSONArray -> kotlinx.serialization.json.JsonArray(
            element.elements.map { koogJsonElementToKotlinx(it) }
        )
        ai.koog.serialization.JSONNull -> kotlinx.serialization.json.JsonNull
        is ai.koog.serialization.JSONPrimitive -> when {
            element.isString -> kotlinx.serialization.json.JsonPrimitive(element.content)
            element.booleanOrNull != null -> kotlinx.serialization.json.JsonPrimitive(element.booleanOrNull!!)
            element.longOrNull != null -> kotlinx.serialization.json.JsonPrimitive(element.longOrNull!!)
            element.doubleOrNull != null -> kotlinx.serialization.json.JsonPrimitive(element.doubleOrNull!!)
            else -> kotlinx.serialization.json.JsonPrimitive(element.content)
        }
        else -> kotlinx.serialization.json.JsonNull
    }
}
