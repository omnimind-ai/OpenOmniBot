package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.mcp.RemoteMcpClient
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

class McpToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = emptySet()

    override fun canHandle(toolName: String): Boolean = true

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val remoteTool = runtimeDescriptor.remoteTool
            ?: return ToolExecutionResult.Error(toolCall.function.name, "Unknown tool: ${toolCall.function.name}")
        return executeMcpTool(remoteTool, args, callback)
    }

    private suspend fun executeMcpTool(
        remoteTool: cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor,
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            val toolTitle = args["tool_title"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
            }.orEmpty()
            helper.reportToolProgress(
                callback,
                remoteTool.encodedToolName,
                toolTitle.ifBlank { "正在调用 ${remoteTool.serverName} 的 ${remoteTool.toolName}" }
            )
            val config = RemoteMcpConfigStore.getServer(remoteTool.serverId)
                ?: throw IllegalStateException("Remote MCP server not found")
            val result = RemoteMcpClient.callTool(
                config = config,
                toolName = remoteTool.toolName,
                arguments = helper.jsonObjectToMap(args).filterKeys { it != "tool_title" }
            )
            ToolExecutionResult.McpResult(
                toolName = remoteTool.encodedToolName,
                serverName = remoteTool.serverName,
                summaryText = result.summaryText,
                previewJson = result.previewJson,
                rawResultJson = result.rawResultJson,
                success = result.success
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ToolExecutionResult.Error(remoteTool.encodedToolName, helper.localized(e.message ?: "MCP tool call failed"))
        }
    }
}
