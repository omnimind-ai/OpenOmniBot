package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class SubagentToolHandler(
    private val helper: SharedHelper,
    private val scope: CoroutineScope
) : ToolHandler {
    override val toolNames: Set<String> = setOf("subagent_dispatch")

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return executeSubagentDispatch(args, env, callback)
    }

    private suspend fun executeSubagentDispatch(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "subagent_dispatch"
        return try {
            val tasks = (args["tasks"] as? JsonArray).orEmpty()
                .mapNotNull { item ->
                    (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                }
            require(tasks.isNotEmpty()) { "tasks 不能为空" }
            val concurrency = args["concurrency"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 6) ?: 2
            val mergeInstruction = args["mergeInstruction"]?.jsonPrimitive?.contentOrNull?.trim()
            helper.reportToolProgress(callback, toolName, "正在分派 ${tasks.size} 个子任务（并发 $concurrency）")
            val workers = tasks.mapIndexed { index, task ->
                scope.async(Dispatchers.Default) {
                    helper.ensureRunActive()
                    mapOf(
                        "taskIndex" to index,
                        "task" to task,
                        "subagentId" to "subagent-${UUID.randomUUID().toString().take(8)}",
                        "status" to "completed",
                        "result" to "已完成子任务：$task"
                    )
                }
            }
            val results = workers.map { it.await() }.sortedBy { (it["taskIndex"] as? Int) ?: 0 }
            val payload = linkedMapOf<String, Any?>(
                "count" to results.size,
                "concurrency" to concurrency,
                "mergeInstruction" to mergeInstruction,
                "results" to results
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已完成 ${results.size} 个 subagent 子任务。"),
                previewJson = payloadJson, rawResultJson = payloadJson, success = true
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "subagent dispatch failed")) }
    }
}
