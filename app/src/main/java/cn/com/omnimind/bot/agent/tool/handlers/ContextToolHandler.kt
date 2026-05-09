package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.util.AssistsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ContextToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = setOf("context_apps_query", "context_time_now")

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "context_apps_query" -> executeContextAppsQuery(args, env.runtimeContextRepository, callback)
            "context_time_now" -> executeContextTimeNow(args, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown context tool")
        }
    }

    private suspend fun executeContextAppsQuery(
        args: JsonObject,
        runtimeContextRepository: AgentRuntimeContextRepository,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "context_apps_query"
        return try {
            if (!AssistsUtil.Setting.isInstalledAppsPermissionGranted(helper.context)) {
                val missing = listOf("应用列表读取权限")
                return helper.permissionRequiredResult(callback, missing)
            }
            helper.reportToolProgress(callback, toolName, "正在查询已安装应用")
            helper.ensureRunActive()
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            val limit = helper.parseContextQueryLimit(args["limit"]?.jsonPrimitive?.intOrNull)
            val items = runtimeContextRepository.queryInstalledApps(query = query, limit = limit)
            val payload = linkedMapOf<String, Any?>(
                "query" to query.orEmpty(),
                "limit" to limit,
                "count" to items.size,
                "items" to items.map { item ->
                    mapOf(
                        "appName" to item.appName,
                        "packageName" to item.packageName
                    )
                }
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (items.isEmpty()) {
                    "未找到匹配的已安装应用。"
                } else {
                    "找到 ${items.size} 个已安装应用。"
                }),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            helper.errorResult(toolName, error.message, "查询已安装应用失败")
        }
    }

    private suspend fun executeContextTimeNow(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "context_time_now"
        return try {
            helper.reportToolProgress(callback, toolName, "正在查询当前时间")
            helper.ensureRunActive()
            val timezoneArg = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim()
            val zoneId = timezoneArg
                ?.takeIf { it.isNotEmpty() }
                ?.let { value ->
                    runCatching { java.time.ZoneId.of(value) }.getOrElse {
                        throw IllegalArgumentException("Invalid timezone: $value")
                    }
                } ?: java.time.ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zoneId)
            val payload = linkedMapOf<String, Any?>(
                "timezone" to zoneId.id,
                "epochMillis" to now.toInstant().toEpochMilli(),
                "iso8601" to now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "date" to now.toLocalDate().toString(),
                "time" to now.toLocalTime().format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME),
                "dayOfWeek" to now.dayOfWeek.name
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("当前时间：${payload["iso8601"]}"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            helper.errorResult(toolName, error.message, "查询当前时间失败")
        }
    }
}
