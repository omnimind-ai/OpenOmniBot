package cn.com.omnimind.bot.agent

import android.content.Context
import android.net.Uri
import android.provider.Settings
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.RemoteMcpClient
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.terminal.EmbeddedTerminalSessionRegistry
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import cn.com.omnimind.bot.termux.TermuxCommandResult
import cn.com.omnimind.bot.termux.TermuxCommandSpec
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import cn.com.omnimind.bot.termux.TermuxCommandRunner
import cn.com.omnimind.bot.utg.UtgBridge
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class AgentToolRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val workspaceManager: AgentWorkspaceManager
) : AgentToolExecutor {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val tag = "AgentToolRouter"
    private val alarmToolService = AgentAlarmToolService(context)
    private val calendarToolService = AgentCalendarToolService(context)
    private val musicToolService = AgentMusicToolService(context, workspaceManager)
    private val skillIndexService = SkillIndexService(context, workspaceManager)
    private val skillLoader = SkillLoader(workspaceManager)
    private val workbenchProjectStore = WorkbenchProjectStore(context)
    private val terminalSessionRegistry = EmbeddedTerminalSessionRegistry(context)
    private val terminalEnvKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    companion object {
        private const val DIRECT_TERMINAL_WORKSPACE_ID = "__direct_terminal__"
        private const val DEFAULT_CONTEXT_QUERY_LIMIT = 20
        private const val DEFAULT_FILE_READ_MAX_CHARS = 8000
        private const val DEFAULT_FILE_LIST_LIMIT = 200
        private const val DEFAULT_FILE_SEARCH_LIMIT = 50
        private const val DEFAULT_TERMINAL_SESSION_READ_MAX_CHARS = 4000
        private const val DEFAULT_SKILLS_LIST_LIMIT = 50
        private const val DEFAULT_SKILL_READ_MAX_CHARS = 16_000
    }

    private val isEnglishLocale: Boolean
        get() = AppLocaleManager.isEnglish(context)

    private val englishTextMap: Map<String, String> = mapOf(
        "应用列表读取权限" to "Installed Apps Access",
        "无障碍权限" to "Accessibility",
        "悬浮窗权限" to "Overlay",
        "精确闹钟权限(SCHEDULE_EXACT_ALARM)" to "Exact alarm permission (SCHEDULE_EXACT_ALARM)",
        "通知权限(POST_NOTIFICATIONS)" to "Notification permission (POST_NOTIFICATIONS)",
        "日历权限(READ/WRITE_CALENDAR)" to "Calendar permission (READ/WRITE_CALENDAR)",
        "正在查询已安装应用" to "Querying installed apps",
        "未找到匹配的已安装应用。" to "No matching installed apps found.",
        "查询已安装应用失败" to "Failed to query installed apps",
        "浏览器操作失败" to "Browser action failed",
        "正在查询当前时间" to "Querying current time",
        "查询当前时间失败" to "Failed to query current time",
        "请提供继续执行所需的信息。" to "Please provide the information required to continue.",
        "视觉执行失败" to "Vision task failed",
        "视觉任务已完成" to "Vision task completed",
        "视觉任务超时，设备上可能仍在继续执行" to
            "Vision task timed out; execution may still be continuing on the device.",
        "正在调用内嵌 Alpine 终端执行命令" to "Running a command in the embedded Alpine terminal",
        "终端输出更新中" to "Terminal output is updating",
        "终端命令执行失败" to "Terminal command failed",
        "正在启动内嵌终端会话" to "Starting embedded terminal session",
        "打开工作区" to "Open Workspace",
        "终端会话启动失败" to "Failed to start terminal session",
        "正在向终端会话发送命令" to "Sending command to terminal session",
        "会话命令仍在运行，请先读取输出确认状态" to
            "The session command is still running. Read the output first to confirm its state.",
        "会话命令执行完成" to "Session command completed",
        "会话命令执行失败" to "Session command failed",
        "终端会话命令执行失败" to "Failed to execute terminal session command",
        "终端会话暂无输出" to "Terminal session has no output yet",
        "已读取终端会话输出" to "Read terminal session output",
        "读取终端会话失败" to "Failed to read terminal session output",
        "正在结束终端会话" to "Stopping terminal session",
        "缺少 sessionId" to "Missing sessionId",
        "结束终端会话失败" to "Failed to stop terminal session",
        "读取文件失败" to "Failed to read file",
        "正在写入文件" to "Writing file",
        "缺少 content" to "Missing content",
        "写入文件失败" to "Failed to write file",
        "正在编辑文件" to "Editing file",
        "缺少 oldText" to "Missing oldText",
        "文件中未找到 oldText" to "oldText was not found in the file",
        "编辑文件失败" to "Failed to edit file",
        "打开目录" to "Open Directory",
        "列目录失败" to "Failed to list directory",
        "缺少 query" to "Missing query",
        "未找到匹配结果" to "No matching results found",
        "搜索文件失败" to "Failed to search files",
        "查看文件信息失败" to "Failed to inspect file info",
        "正在移动文件" to "Moving file",
        "移动文件失败" to "Failed to move file",
        "当前没有匹配的 skills" to "No matching skills found",
        "列出 skills 失败" to "Failed to list skills",
        "缺少 skillId" to "Missing skillId",
        "当前环境不可用" to "Current environment unavailable",
        "读取 skill 失败" to "Failed to read skill",
        "正在创建定时任务" to "Creating scheduled task",
        "targetKind 仅支持 vlm 或 subagent" to "`targetKind` only supports `vlm` or `subagent`",
        "vlm 定时任务缺少 goal" to "Missing `goal` for the VLM scheduled task",
        "subagent 定时任务缺少 subagentPrompt" to
            "Missing `subagentPrompt` for the subagent scheduled task",
        "定时任务已创建" to "Scheduled task created",
        "正在读取定时任务列表" to "Loading scheduled tasks",
        "当前没有定时任务。" to "There are no scheduled tasks.",
        "正在更新定时任务" to "Updating scheduled task",
        "定时任务已更新" to "Scheduled task updated",
        "正在删除定时任务" to "Deleting scheduled task",
        "定时任务已删除" to "Scheduled task deleted",
        "正在创建提醒闹钟" to "Creating reminder alarm",
        "title 不能为空" to "`title` cannot be empty",
        "triggerAt 不能为空" to "`triggerAt` cannot be empty",
        "提醒闹钟已创建" to "Reminder alarm created",
        "正在读取提醒闹钟列表" to "Loading reminder alarms",
        "当前没有提醒闹钟。" to "There are no reminder alarms.",
        "正在删除提醒闹钟" to "Deleting reminder alarm",
        "alarmId 不能为空" to "`alarmId` cannot be empty",
        "提醒闹钟已删除" to "Reminder alarm deleted",
        "正在请求日历权限" to "Requesting calendar permission",
        "正在读取日历列表" to "Loading calendars",
        "未找到符合条件的日历。" to "No calendars matched the criteria.",
        "正在创建日程" to "Creating calendar event",
        "startAt 不能为空" to "`startAt` cannot be empty",
        "endAt 不能为空" to "`endAt` cannot be empty",
        "日程已创建" to "Calendar event created",
        "正在查询日程" to "Querying calendar events",
        "正在修改日程" to "Updating calendar event",
        "eventId 不能为空" to "`eventId` cannot be empty",
        "日程已更新" to "Calendar event updated",
        "正在删除日程" to "Deleting calendar event",
        "日程已删除" to "Calendar event deleted",
        "action 不能为空" to "`action` cannot be empty",
        "正在发送系统播放命令" to "Sending system play command",
        "正在准备播放音频" to "Preparing audio playback",
        "正在暂停播放" to "Pausing playback",
        "正在恢复播放" to "Resuming playback",
        "正在停止播放" to "Stopping playback",
        "正在调整播放进度" to "Seeking playback",
        "正在读取播放状态" to "Reading playback status",
        "正在切换到下一首" to "Skipping to the next track",
        "正在切换到上一首" to "Going back to the previous track",
        "正在执行音乐播放控制" to "Running music playback control",
        "seek 动作需要提供 positionSeconds" to "`seek` requires `positionSeconds`",
        "音乐播放控制已执行" to "Music playback control executed",
        "正在检索 workspace 记忆" to "Searching workspace memory",
        "query 不能为空" to "`query` cannot be empty",
        "未命中相关记忆。" to "No relevant memory hits found.",
        "正在写入当日记忆" to "Writing daily memory",
        "text 不能为空" to "`text` cannot be empty",
        "已写入当日记忆" to "Daily memory written",
        "已写入当日短期记忆。" to "Short-term memory for today has been written.",
        "正在沉淀长期记忆" to "Writing long-term memory",
        "已写入长期记忆" to "Long-term memory written",
        "检测到重复，已跳过" to "Duplicate detected; skipped",
        "已沉淀一条长期记忆。" to "One long-term memory entry has been stored.",
        "长期记忆已存在同类条目，跳过写入。" to
            "A similar long-term memory entry already exists, so writing was skipped.",
        "正在整理当日记忆" to "Rolling up daily memory",
        "记忆整理完成" to "Memory rollup completed",
        "tasks 不能为空" to "`tasks` cannot be empty",
        "当前会话仍有命令在执行，请先读取输出或停止会话。" to
            "A command is still running in the current session. Read its output or stop the session first.",
        "终端会话初始化超时，可能仍在后台继续运行。" to
            "Terminal session initialization timed out and may still be running in the background.",
        "终端命令等待超时，可能仍在后台继续运行。" to
            "Terminal command timed out and may still be running in the background.",
        "command 不能为空" to "`command` cannot be empty",
        "terminal_execute 缺少 command" to "`terminal_execute` is missing `command`",
        "executionMode 仅支持 termux 或 proot" to
            "`executionMode` only supports `termux` or `proot`",
        "缺少 command" to "Missing command"
    )

    private suspend fun ensureRunActive() {
        currentCoroutineContext().ensureActive()
    }

    private fun localized(text: String?): String {
        if (text == null || !isEnglishLocale) {
            return text.orEmpty()
        }
        englishTextMap[text]?.let { return it }

        when {
            text.startsWith("当前时间：") ->
                return "Current time: ${text.removePrefix("当前时间：")}"
            text.startsWith("终端会话已启动：") ->
                return "Terminal session started: ${text.removePrefix("终端会话已启动：")}"
            text.startsWith("终端会话不存在或不属于当前 workspace：") ->
                return "Terminal session does not exist or does not belong to the current workspace: ${text.removePrefix("终端会话不存在或不属于当前 workspace：")}"
            text.startsWith("终端会话不存在或已结束：") ->
                return "Terminal session does not exist or has already ended: ${text.removePrefix("终端会话不存在或已结束：")}"
            text.startsWith("文件不存在：") ->
                return "File does not exist: ${text.removePrefix("文件不存在：")}"
            text.startsWith("目标不是文件：") ->
                return "Target is not a file: ${text.removePrefix("目标不是文件：")}"
            text.startsWith("已读取文件：") ->
                return "Read file: ${text.removePrefix("已读取文件：")}"
            text.startsWith("已追加写入文件：") ->
                return "Appended to file: ${text.removePrefix("已追加写入文件：")}"
            text.startsWith("已写入文件：") ->
                return "Wrote file: ${text.removePrefix("已写入文件：")}"
            text.startsWith("目标文件不存在：") ->
                return "Target file does not exist: ${text.removePrefix("目标文件不存在：")}"
            text.startsWith("目录不存在：") ->
                return "Directory does not exist: ${text.removePrefix("目录不存在：")}"
            text.startsWith("路径不存在：") ->
                return "Path does not exist: ${text.removePrefix("路径不存在：")}"
            text.startsWith("已读取路径信息：") ->
                return "Read path info: ${text.removePrefix("已读取路径信息：")}"
            text.startsWith("源文件不存在：") ->
                return "Source file does not exist: ${text.removePrefix("源文件不存在：")}"
            text.startsWith("目标已存在：") ->
                return "Target already exists: ${text.removePrefix("目标已存在：")}"
            text.startsWith("已移动到：") ->
                return "Moved to: ${text.removePrefix("已移动到：")}"
            text.startsWith("未找到 skill：") ->
                return "Skill not found: ${text.removePrefix("未找到 skill：")}"
            text.startsWith("读取 SKILL.md 失败：") ->
                return "Failed to read SKILL.md: ${text.removePrefix("读取 SKILL.md 失败：")}"
            text.startsWith("已读取 skill：") ->
                return "Read skill: ${text.removePrefix("已读取 skill：")}"
            text.startsWith("终端会话不存在或不属于当前 agent：") ->
                return "Terminal session does not exist or does not belong to the current agent: ${text.removePrefix("终端会话不存在或不属于当前 agent：")}"
            text.startsWith("终端会话不存在：") ->
                return "Terminal session does not exist: ${text.removePrefix("终端会话不存在：")}"
            text.startsWith("不支持的 action：") ->
                return "Unsupported action: ${text.removePrefix("不支持的 action：")}"
            text.startsWith("正在调用 ") && text.contains(" 的 ") -> {
                val remainder = text.removePrefix("正在调用 ")
                val parts = remainder.split(" 的 ", limit = 2)
                if (parts.size == 2) {
                    return "Calling ${parts[0]} / ${parts[1]}"
                }
            }
        }

        Regex("^找到 (\\d+) 个已安装应用。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} installed apps."
        }
        Regex("^共找到 (\\d+) 项$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} items."
        }
        Regex("^找到 (\\d+) 个匹配结果$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} matching results."
        }
        Regex("^共找到 (\\d+) 个 skill$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} skills."
        }
        Regex("^当前共有 (\\d+) 个定时任务。$").matchEntire(text)?.let {
            return "There are currently ${it.groupValues[1]} scheduled tasks."
        }
        Regex("^当前共有 (\\d+) 个提醒闹钟。$").matchEntire(text)?.let {
            return "There are currently ${it.groupValues[1]} reminder alarms."
        }
        Regex("^找到 (\\d+) 个日历。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} calendars."
        }
        Regex("^找到 (\\d+) 条日程。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} calendar events."
        }
        Regex("^命中 (\\d+) 条记忆（词法检索）。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} memory hits (lexical fallback)."
        }
        Regex("^命中 (\\d+) 条记忆。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} memory hits."
        }
        Regex("^正在分派 (\\d+) 个子任务（并发 (\\d+)）$").matchEntire(text)?.let {
            return "Dispatching ${it.groupValues[1]} subtasks (concurrency ${it.groupValues[2]})"
        }
        Regex("^已完成子任务：(.*)$").matchEntire(text)?.let {
            return "Completed subtask: ${it.groupValues[1]}"
        }
        Regex("^已完成 (\\d+) 个 subagent 子任务。$").matchEntire(text)?.let {
            return "Completed ${it.groupValues[1]} subagent subtasks."
        }
        return text
    }

    private fun localizePayloadValue(value: Any?, key: String? = null): Any? {
        return when (value) {
            is String -> if (
                key in setOf("summary", "message", "error", "errorMessage", "result", "lastProgress")
            ) {
                localized(value)
            } else {
                value
            }
            is Map<*, *> -> value.entries.associate { (entryKey, item) ->
                entryKey.toString() to localizePayloadValue(item, entryKey.toString())
            }
            is Iterable<*> -> value.map { localizePayloadValue(it, key) }
            else -> value
        }
    }

    private fun localizeExtras(extras: Map<String, Any?>): Map<String, Any?> {
        return extras.mapValues { (key, value) -> localizePayloadValue(value, key) }
    }

    private fun encodeLocalizedPayload(payload: Any?): String {
        return json.encodeToString(mapToJsonElement(localizePayloadValue(payload)))
    }

    private fun localizeMissingPermissions(missing: List<String>): List<String> {
        return if (isEnglishLocale) missing.map(::localized) else missing
    }

    private suspend fun permissionRequiredResult(
        callback: AgentCallback,
        missing: List<String>
    ): ToolExecutionResult.PermissionRequired {
        val localizedMissing = localizeMissingPermissions(missing)
        callback.onPermissionRequired(localizedMissing)
        return ToolExecutionResult.PermissionRequired(localizedMissing)
    }

    private fun errorResult(
        toolName: String,
        message: String?,
        fallbackMessage: String
    ): ToolExecutionResult.Error {
        return ToolExecutionResult.Error(toolName, localized(message ?: fallbackMessage))
    }

    private suspend fun reportToolProgress(
        callback: AgentCallback,
        toolName: String,
        progress: String,
        extras: Map<String, Any?> = emptyMap(),
        toolHandle: AgentToolExecutionHandle? = null
    ) {
        val localizedProgress = localized(progress)
        val localizedExtras = localizeExtras(extras)
        toolHandle?.throwIfStopRequested()
        toolHandle?.recordProgress(localizedProgress, localizedExtras)
        callback.onToolCallProgress(toolName, localizedProgress, localizedExtras)
        toolHandle?.throwIfStopRequested()
        ensureRunActive()
    }

    private data class VlmExecutionArgs(
        val goal: String,
        val packageName: String?,
        val needSummary: Boolean,
        val startFromCurrent: Boolean
    )

    private data class VlmArgsSanitizeResult(
        val args: VlmExecutionArgs,
        val reasons: List<String>
    )

    private data class TerminalExecuteArgs(
        val command: String,
        val executionMode: String,
        val prootDistro: String?,
        val workingDirectory: String?,
        val timeoutSeconds: Int
    )

    private data class TerminalSessionStartArgs(
        val sessionName: String?,
        val workingDirectory: String?
    )

    private data class TerminalSessionExecArgs(
        val sessionId: String,
        val command: String,
        val workingDirectory: String?,
        val timeoutSeconds: Int
    )

    private data class TerminalSessionReadArgs(
        val sessionId: String,
        val maxChars: Int
    )

    private data class DirectTerminalSessionSnapshot(
        val sessionId: String,
        val transcript: String,
        val currentDirectory: String,
        val commandRunning: Boolean
    )

    private data class DirectTerminalCommandResult(
        val sessionId: String,
        val completed: Boolean,
        val timedOut: Boolean,
        val output: String,
        val transcript: String,
        val currentDirectory: String,
        val commandRunning: Boolean,
        val errorMessage: String? = null
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        ensureRunActive()
        return when (toolCall.function.name) {
            "context_apps_query" -> executeContextAppsQuery(
                args = args,
                runtimeContextRepository = env.runtimeContextRepository,
                callback = callback
            )

            "context_time_now" -> executeContextTimeNow(
                args = args,
                callback = callback
            )

            "vlm_task" -> executeVlmTask(
                args = args,
                userMessage = env.userMessage,
                runtimeContextRepository = env.runtimeContextRepository,
                currentPackageName = env.currentPackageName,
                resolvedSkills = env.resolvedSkills,
                callback = callback
            )
            "terminal_execute" -> executeTerminalTool(
                args = args,
                workspace = env.workspaceDescriptor,
                terminalEnvironment = env.terminalEnvironment,
                callback = callback,
                toolHandle = toolHandle
            )
            "terminal_session_start" -> executeTerminalSessionStart(
                args = args,
                workspace = env.workspaceDescriptor,
                terminalEnvironment = env.terminalEnvironment,
                callback = callback
            )
            "terminal_session_exec" -> executeTerminalSessionExec(
                args = args,
                workspace = env.workspaceDescriptor,
                terminalEnvironment = env.terminalEnvironment,
                callback = callback,
                toolHandle = toolHandle
            )
            "terminal_session_read" -> executeTerminalSessionRead(
                args = args,
                workspace = env.workspaceDescriptor,
                callback = callback
            )
            "terminal_session_stop" -> executeTerminalSessionStop(
                args = args,
                workspace = env.workspaceDescriptor,
                callback = callback
            )
            "browser_use" -> executeBrowserUse(
                args = args,
                env = env,
                callback = callback,
                toolHandle = toolHandle
            )
            "file_read" -> executeFileRead(args, env.workspaceDescriptor, callback)
            "file_write" -> executeFileWrite(args, env.workspaceDescriptor, callback)
            "file_edit" -> executeFileEdit(args, env.workspaceDescriptor, callback)
            "file_list" -> executeFileList(args, env.workspaceDescriptor, callback)
            "file_search" -> executeFileSearch(args, env.workspaceDescriptor, callback)
            "file_stat" -> executeFileStat(args, env.workspaceDescriptor, callback)
            "file_move" -> executeFileMove(args, env.workspaceDescriptor, callback)
            "skills_list" -> executeSkillsList(args, env.workspaceDescriptor, callback)
            "skills_read" -> executeSkillsRead(args, env.workspaceDescriptor, callback)
            "workbench_project_create" -> executeWorkbenchProjectCreate(args, env.workspaceDescriptor)
            "workbench_project_list" -> executeWorkbenchProjectList(args, env.workspaceDescriptor)
            "workbench_project_get" -> executeWorkbenchProjectGet(args, env.workspaceDescriptor)
            "workbench_api_list" -> executeWorkbenchApiList(args, env.workspaceDescriptor)
            "workbench_api_call" -> executeWorkbenchApiCall(args, env.workspaceDescriptor)
            "workbench_project_export" -> executeWorkbenchProjectExport(args, env.workspaceDescriptor)
            "workbench_project_open" -> executeWorkbenchProjectOpen(args, env.workspaceDescriptor)
            "workbench_project_delete" -> executeWorkbenchProjectDelete(args, env.workspaceDescriptor)
            "workbench_project_hot_update" -> executeWorkbenchProjectHotUpdate(args, env.workspaceDescriptor)
            "workbench_project_ingest_android" -> executeWorkbenchProjectIngestAndroid(args, env.workspaceDescriptor)
            "schedule_task_create",
            "schedule_task_list",
            "schedule_task_update",
            "schedule_task_delete" -> executeScheduleTool(
                toolCall.function.name,
                args,
                env.runtimeContextRepository,
                callback
            )

            "alarm_reminder_create",
            "alarm_reminder_list",
            "alarm_reminder_delete" -> executeAlarmTool(
                toolName = toolCall.function.name,
                args = args,
                callback = callback
            )

            "calendar_list",
            "calendar_event_create",
            "calendar_event_list",
            "calendar_event_update",
            "calendar_event_delete" -> executeCalendarTool(
                toolName = toolCall.function.name,
                args = args,
                callback = callback
            )
            "music_playback_control" -> executeMusicTool(
                args = args,
                workspace = env.workspaceDescriptor,
                callback = callback
            )

            "memory_search",
            "memory_write_daily",
            "memory_upsert_longterm",
            "memory_rollup_day" -> executeMemoryTool(
                toolName = toolCall.function.name,
                args = args,
                env = env,
                callback = callback
            )
            "subagent_dispatch" -> executeSubagentDispatch(
                args = args,
                env = env,
                callback = callback
            )

            else -> {
                val remoteTool = runtimeDescriptor.remoteTool
                if (remoteTool != null) {
                    executeMcpTool(remoteTool, args, callback)
                } else {
                    ToolExecutionResult.Error(toolCall.function.name, "Unknown tool: ${toolCall.function.name}")
                }
            }
        }
    }

    override suspend fun dispose() {
        closeOwnedDirectTerminalSessions()
        LiveAgentBrowserSessionManager.releaseRunOwnership()
    }

    private suspend fun executeContextAppsQuery(
        args: JsonObject,
        runtimeContextRepository: AgentRuntimeContextRepository,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "context_apps_query"
        return try {
            if (!AssistsUtil.Setting.isInstalledAppsPermissionGranted(context)) {
                val missing = listOf("应用列表读取权限")
                return permissionRequiredResult(callback, missing)
            }
            reportToolProgress(callback, toolName, "正在查询已安装应用")
            ensureRunActive()
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            val limit = parseContextQueryLimit(args["limit"]?.jsonPrimitive?.intOrNull)
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
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized(if (items.isEmpty()) {
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
            errorResult(toolName, error.message, "查询已安装应用失败")
        }
    }

    private suspend fun executeBrowserUse(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "browser_use"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            val request = BrowserUseRequest.fromJson(args)
            val engine = LiveAgentBrowserSessionManager.acquireEngine(
                context = context,
                workspaceManager = workspaceManager,
                agentRunId = env.agentRunId,
                workspace = env.workspaceDescriptor
            )
            toolHandle.bindStopAction {
                engine.requestInterruptCurrentAction()
            }
            reportToolProgress(
                callback,
                toolName,
                request.toolTitle,
                mapOf("summary" to request.toolTitle),
                toolHandle = toolHandle
            )
            val outcome = engine.execute(request)
            val payload = linkedMapOf<String, Any?>(
                "toolTitle" to request.toolTitle
            ).apply {
                putAll(outcome.payload)
            }
            val encoded = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized(outcome.summaryText),
                previewJson = encoded,
                rawResultJson = encoded,
                success = true,
                imageDataUrl = outcome.imageDataUrl,
                artifacts = outcome.artifacts,
                workspaceId = env.workspaceDescriptor.id,
                actions = outcome.actions
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "浏览器操作失败")
        }
    }

    private suspend fun executeContextTimeNow(
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "context_time_now"
        return try {
            reportToolProgress(callback, toolName, "正在查询当前时间")
            ensureRunActive()
            val timezoneArg = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim()
            val zoneId = timezoneArg
                ?.takeIf { it.isNotEmpty() }
                ?.let { value ->
                    runCatching { ZoneId.of(value) }.getOrElse {
                        throw IllegalArgumentException("Invalid timezone: $value")
                    }
                } ?: ZoneId.systemDefault()
            val now = ZonedDateTime.now(zoneId)
            val payload = linkedMapOf<String, Any?>(
                "timezone" to zoneId.id,
                "epochMillis" to now.toInstant().toEpochMilli(),
                "iso8601" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "date" to now.toLocalDate().toString(),
                "time" to now.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME),
                "dayOfWeek" to now.dayOfWeek.name
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("当前时间：${payload["iso8601"]}"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            errorResult(toolName, error.message, "查询当前时间失败")
        }
    }

    private suspend fun executeVlmTask(
        args: JsonObject,
        userMessage: String,
        runtimeContextRepository: AgentRuntimeContextRepository,
        currentPackageName: String?,
        resolvedSkills: List<ResolvedSkillContext>,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            ensureRunActive()
            val missing = checkExecutionPrerequisites()
            if (missing.isNotEmpty()) {
                return permissionRequiredResult(callback, missing)
            }

            val goal = args["goal"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing goal")
            val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
            val needSummary = args["needSummary"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            val startFromCurrent = args["startFromCurrent"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false

            val rawArgs = VlmExecutionArgs(
                goal = goal,
                packageName = packageName?.takeIf { it.isNotBlank() },
                needSummary = needSummary,
                startFromCurrent = startFromCurrent
            )
            val appNameToPackage = runtimeContextRepository.getAppNameToPackageMap()
            val sanitized = sanitizeVlmExecutionArgs(
                rawArgs = rawArgs,
                userMessage = userMessage,
                appNameToPackage = appNameToPackage,
                currentPackageName = currentPackageName
            )
            val safeArgs = sanitized.args

            if (sanitized.reasons.isNotEmpty()) {
                OmniLog.w(
                    tag,
                    "vlm_task args corrected: reasons=${sanitized.reasons.joinToString(",")}"
                )
            }

            ensureRunActive()
            val taskId = UUID.randomUUID().toString()
            val outcome = VlmToolCoordinator.executeNewTask(
                context = context,
                request = VlmTaskRequest(
                    goal = safeArgs.goal,
                    model = "scene.vlm.operation.primary",
                    maxSteps = null,
                    packageName = if (safeArgs.startFromCurrent) null else safeArgs.packageName,
                    needSummary = safeArgs.needSummary,
                    skipGoHome = safeArgs.startFromCurrent,
                    stepSkillGuidance = resolvedSkills.joinToString("\n\n") { it.stepGuidance() }
                ),
                scope = scope,
                taskId = taskId,
                onTaskRunLogReady = { payload ->
                    UtgBridge.cacheVlmTaskRunLog(taskId = taskId, payload = payload)
                },
                progressReporter = { progress, extras ->
                    reportToolProgress(callback, "vlm_task", progress, extras)
                }
            )
            val payloadJson = encodeLocalizedPayload(outcome.toPayload())
            // 阻塞式 vlm_task 需要等 Agent 自己产出后续自然语言回复；
            // 这里不能再触发旧的 onVlmTaskFinished 回调，否则 Flutter 会提前清理当前会话，
            // 导致随后到达的 onAgentChatMessage / onAgentComplete 被丢弃。
            when (outcome.status) {
                VlmToolOutcomeStatus.WAITING_INPUT -> {
                    val question = outcome.waitingQuestion
                        ?: outcome.message.ifBlank { "请提供继续执行所需的信息。" }
                    val localizedQuestion = localized(question)
                    callback.onClarifyRequired(localizedQuestion, null)
                    ToolExecutionResult.Clarify(localizedQuestion, null)
                }
                VlmToolOutcomeStatus.SCREEN_LOCKED -> {
                    val localizedQuestion = localized(outcome.message)
                    callback.onClarifyRequired(localizedQuestion, null)
                    ToolExecutionResult.Clarify(localizedQuestion, null)
                }
                VlmToolOutcomeStatus.ERROR,
                VlmToolOutcomeStatus.CANCELLED -> {
                    errorResult(
                        "vlm_task",
                        outcome.errorMessage ?: outcome.message,
                        "视觉执行失败"
                    )
                }
                VlmToolOutcomeStatus.FINISHED -> {
                    ToolExecutionResult.ContextResult(
                        toolName = "vlm_task",
                        summaryText = localized(outcome.finishedContent
                            ?: outcome.summaryText
                            ?: outcome.message.ifBlank { "视觉任务已完成" }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = true
                    )
                }
                VlmToolOutcomeStatus.TIMEOUT -> {
                    ToolExecutionResult.ContextResult(
                        toolName = "vlm_task",
                        summaryText = localized("视觉任务超时，设备上可能仍在继续执行"),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = true
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error("vlm_task", localized(e.message ?: "Unknown error"))
        }
    }

    private suspend fun executeTerminalTool(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "terminal_execute"
        return try {
            var runningProcess: Process? = null
            toolHandle.bindStopAction {
                runCatching { runningProcess?.destroyForcibly() }
            }
            reportToolProgress(
                callback,
                toolName,
                "正在调用内嵌 Alpine 终端执行命令",
                mapOf(
                    "summary" to "正在调用内嵌 Alpine 终端执行命令",
                    "terminalStreamState" to "starting"
                ),
                toolHandle = toolHandle
            )
            val rawArgs = parseTerminalExecuteArgs(args)
            val parsedArgs = rawArgs.copy(
                workingDirectory = rawArgs.workingDirectory
                    ?.let { workspaceManager.resolveShellPath(it, workspace, allowRootDirectories = true) }
                    ?: workspace.currentCwd
            )
            val commandResult = TermuxCommandRunner.execute(
                context = context,
                spec = TermuxCommandSpec(
                    command = parsedArgs.command,
                    executionMode = parsedArgs.executionMode,
                    prootDistro = parsedArgs.prootDistro,
                    workingDirectory = parsedArgs.workingDirectory,
                    timeoutSeconds = parsedArgs.timeoutSeconds,
                    environment = terminalEnvironment
                ),
                onProcessStarted = { process ->
                    runningProcess = process
                    if (toolHandle.isManualStopRequested()) {
                        runCatching { process.destroyForcibly() }
                    }
                },
                onLiveUpdate = { update ->
                    reportToolProgress(
                        callback,
                        toolName,
                        if (update.outputDelta.isBlank()) {
                            "正在调用内嵌 Alpine 终端执行命令"
                        } else {
                            "终端输出更新中"
                        },
                        mapOf<String, Any?>(
                            "summary" to if (update.outputDelta.isBlank()) {
                                "正在调用内嵌 Alpine 终端执行命令"
                            } else {
                                "终端输出更新中"
                            },
                            "terminalSessionId" to update.sessionId,
                            "terminalOutputDelta" to update.outputDelta,
                            "terminalStreamState" to update.streamState
                        ),
                        toolHandle = toolHandle
                    )
                }
            )
            buildTerminalToolResult(
                toolName = toolName,
                args = parsedArgs,
                result = commandResult,
                workspace = workspace,
                sourceTool = toolName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMessage = localized(e.message ?: "终端命令执行失败")
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = errorMessage,
                previewJson = encodeLocalizedPayload(mapOf("error" to errorMessage)),
                rawResultJson = encodeLocalizedPayload(mapOf("error" to errorMessage)),
                success = false,
                timedOut = false,
                terminalOutput = errorMessage,
                terminalStreamState = "error",
                workspaceId = workspace.id
            )
        }
    }

    private suspend fun executeTerminalSessionStart(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "terminal_session_start"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            reportToolProgress(callback, toolName, "正在启动内嵌终端会话")
            val parsedArgs = parseTerminalSessionStartArgs(args)
            val workingDirectory = resolveShellWorkingDirectory(parsedArgs.workingDirectory, workspace)
            val result = EmbeddedTerminalRuntime.startSession(
                context = context,
                requestedSessionId = null,
                sessionTitle = parsedArgs.sessionName,
                workingDirectory = workingDirectory,
                environment = terminalEnvironment
            )
            val sessionId = result.sessionId
            rememberOwnedTerminalSession(
                workspaceId = workspace.id,
                sessionId = sessionId,
                sessionName = parsedArgs.sessionName
            )
            terminalSessionDirectory(workspace, sessionId).mkdirs()
            val logArtifact = persistTerminalSessionTranscript(workspace, sessionId, result.transcript, toolName)
            val payload = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "workingDirectory" to workingDirectory,
                "currentDirectory" to result.currentDirectory,
                "success" to true,
                "logPath" to logArtifact.androidPath,
                "logUri" to logArtifact.uri
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = localized("终端会话已启动：$sessionId"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                timedOut = false,
                terminalOutput = "",
                terminalSessionId = sessionId,
                terminalStreamState = "ready",
                artifacts = listOf(logArtifact),
                workspaceId = workspace.id,
                actions = listOf(
                    ArtifactAction(
                        type = "workspace",
                        label = localized("打开工作区"),
                        target = workspace.uriRoot,
                        payload = mapOf(
                            "workspaceId" to workspace.id,
                            "workspacePath" to workspace.androidRootPath,
                            "workspaceShellPath" to workspace.rootPath
                        )
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "终端会话启动失败")
        }
    }

    private suspend fun executeTerminalSessionExec(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "terminal_session_exec"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            val parsedArgs = parseTerminalSessionExecArgs(args)
            val sessionId = parsedArgs.sessionId.trim()
            require(isOwnedTerminalSession(workspace.id, sessionId)) { "终端会话不存在或不属于当前 workspace：$sessionId" }
            require(EmbeddedTerminalRuntime.hasSession(context, sessionId)) {
                forgetOwnedTerminalSession(sessionId)
                "终端会话不存在或已结束：$sessionId"
            }
            val shellWorkingDirectory = parsedArgs.workingDirectory?.let {
                resolveShellWorkingDirectory(it, workspace)
            }
            reportToolProgress(
                callback,
                toolName,
                "正在向终端会话发送命令",
                mapOf(
                    "summary" to "正在向终端会话发送命令",
                    "terminalSessionId" to sessionId,
                    "terminalStreamState" to "starting"
                ),
                toolHandle = toolHandle
            )
            toolHandle.bindStopAction {
                EmbeddedTerminalRuntime.stopSession(context, sessionId)
            }
            val result = EmbeddedTerminalRuntime.executeSessionCommand(
                context = context,
                sessionId = sessionId,
                command = parsedArgs.command,
                workingDirectory = shellWorkingDirectory,
                timeoutSeconds = parsedArgs.timeoutSeconds,
                environment = terminalEnvironment,
                onLiveUpdate = { update ->
                    val summary = update.summary.ifBlank { "终端输出更新中" }
                    reportToolProgress(
                        callback,
                        toolName,
                        summary,
                        mapOf<String, Any?>(
                            "summary" to summary,
                            "terminalSessionId" to update.sessionId,
                            "terminalOutputDelta" to update.outputDelta,
                            "terminalStreamState" to update.streamState
                        ),
                        toolHandle = toolHandle
                    )
                }
            )
            val terminalStreamState = when {
                !result.completed -> "running"
                result.errorMessage != null -> "error"
                else -> "completed"
            }
            val logArtifact = persistTerminalSessionTranscript(workspace, sessionId, result.transcript, toolName)
            val rawResult = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "workingDirectory" to shellWorkingDirectory,
                "currentDirectory" to result.currentDirectory,
                "command" to parsedArgs.command,
                "exitCode" to result.exitCode,
                "completed" to result.completed,
                "timedOut" to result.timedOut,
                "logPath" to logArtifact.workspacePath,
                "androidLogPath" to logArtifact.androidPath,
                "logUri" to logArtifact.uri,
                "stdout" to truncateTerminalTail(result.output, 12000),
                "terminalOutput" to truncateTerminalTail(
                    if (result.completed) result.output else result.transcript,
                    12000
                ),
                "success" to (result.completed && result.success && result.errorMessage == null),
                "errorMessage" to result.errorMessage,
                "terminalStreamState" to terminalStreamState
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = localized(if (!result.completed) {
                    result.errorMessage ?: "会话命令仍在运行，请先读取输出确认状态"
                } else if (result.errorMessage == null && result.success) {
                    "会话命令执行完成"
                } else {
                    result.errorMessage ?: "会话命令执行失败"
                }),
                previewJson = encodeLocalizedPayload(rawResult),
                rawResultJson = encodeLocalizedPayload(rawResult),
                success = result.completed && result.success && result.errorMessage == null,
                timedOut = result.timedOut,
                terminalOutput = if (result.completed) result.output else result.transcript,
                terminalSessionId = sessionId,
                terminalStreamState = terminalStreamState,
                artifacts = listOf(logArtifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "终端会话命令执行失败")
        }
    }

    private suspend fun executeTerminalSessionRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "terminal_session_read"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            val parsedArgs = parseTerminalSessionReadArgs(args)
            val sessionId = parsedArgs.sessionId.trim()
            require(isOwnedTerminalSession(workspace.id, sessionId)) { "终端会话不存在或不属于当前 workspace：$sessionId" }
            require(EmbeddedTerminalRuntime.hasSession(context, sessionId)) {
                forgetOwnedTerminalSession(sessionId)
                "终端会话不存在或已结束：$sessionId"
            }
            val readResult = EmbeddedTerminalRuntime.readSession(context, sessionId)
            val artifact = persistTerminalSessionTranscript(workspace, sessionId, readResult.transcript, toolName)
            val content = truncateTerminalTail(
                EmbeddedTerminalRuntime.trimTerminalOutput(
                    EmbeddedTerminalRuntime.sanitizeTerminalNoise(readResult.transcript)
                ),
                parsedArgs.maxChars
            )
            val payload = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "content" to content,
                "contentLength" to content.length,
                "currentDirectory" to readResult.currentDirectory,
                "commandRunning" to readResult.commandRunning,
                "logPath" to artifact.workspacePath,
                "androidLogPath" to artifact.androidPath,
                "logUri" to artifact.uri
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = localized(if (content.isBlank()) "终端会话暂无输出" else "已读取终端会话输出"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                timedOut = false,
                terminalOutput = content,
                terminalSessionId = sessionId,
                terminalStreamState = if (readResult.commandRunning) "running" else "completed",
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "读取终端会话失败")
        }
    }

    private suspend fun executeTerminalSessionStop(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "terminal_session_stop"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            reportToolProgress(callback, toolName, "正在结束终端会话")
            val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(sessionId.isNotEmpty()) { "缺少 sessionId" }
            val owned = isOwnedTerminalSession(workspace.id, sessionId)
            val result = if (owned) {
                EmbeddedTerminalRuntime.stopSession(context, sessionId)
            } else {
                false
            }
            if (owned) {
                forgetOwnedTerminalSession(sessionId)
            }
            val payload = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "success" to result
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = localized(if (result) "终端会话已结束：$sessionId" else "终端会话不存在或已结束：$sessionId"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = result,
                timedOut = false,
                terminalOutput = if (result) "session_stopped:$sessionId" else "session_not_found:$sessionId",
                terminalSessionId = sessionId,
                terminalStreamState = if (result) "stopped" else "error",
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "结束终端会话失败")
        }
    }

    private suspend fun executeFileRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_read"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists()) { "文件不存在：${file.absolutePath}" }
            require(file.isFile) { "目标不是文件：${file.absolutePath}" }
            val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(128, 64_000)
                ?: DEFAULT_FILE_READ_MAX_CHARS
            val offset = args["offset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
            val lineStart = args["lineStart"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val lineCount = args["lineCount"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val content = file.readText()
            val sliced = when {
                lineStart != null -> {
                    val lines = content.lines()
                    val from = (lineStart - 1).coerceAtMost(lines.size)
                    val until = if (lineCount != null) {
                        (from + lineCount).coerceAtMost(lines.size)
                    } else {
                        lines.size
                    }
                    lines.subList(from, until).joinToString("\n")
                }
                offset > 0 -> content.drop(offset)
                else -> content
            }
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "content" to truncateText(sliced, maxChars),
                "size" to file.length(),
                "mimeType" to workspaceManager.guessMimeType(file)
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("已读取文件：${file.name}"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "读取文件失败")
        }
    }

    private suspend fun executeFileWrite(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_write"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            reportToolProgress(callback, toolName, "正在写入文件")
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            val content = args["content"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 content")
            val append = args["append"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "append" to append
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized(if (append) "已追加写入文件：${file.name}" else "已写入文件：${file.name}"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "写入文件失败")
        }
    }

    private suspend fun executeFileEdit(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_edit"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            reportToolProgress(callback, toolName, "正在编辑文件")
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists() && file.isFile) { "目标文件不存在：${file.absolutePath}" }
            val oldText = args["oldText"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 oldText")
            val newText = args["newText"]?.jsonPrimitive?.content ?: ""
            val replaceAll = args["replaceAll"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val original = file.readText()
            require(original.contains(oldText)) { "文件中未找到 oldText" }
            val updated = if (replaceAll) {
                original.replace(oldText, newText)
            } else {
                original.replaceFirst(oldText, newText)
            }
            file.writeText(updated)
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "replaceAll" to replaceAll
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("已更新文件：${file.name}"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "编辑文件失败")
        }
    }

    private suspend fun executeFileList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_list"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val recursive = args["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxDepth = args["maxDepth"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 6) ?: 2
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1000) ?: DEFAULT_FILE_LIST_LIMIT
            val files = if (recursive) {
                directory.walkTopDown().maxDepth(maxDepth).drop(1).take(limit).toList()
            } else {
                directory.listFiles()?.sortedBy { it.name.lowercase() }?.take(limit) ?: emptyList()
            }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to files.size,
                "items" to files.map { entry ->
                    mapOf(
                        "name" to entry.name,
                        "path" to (workspaceManager.shellPathForAndroid(entry) ?: entry.absolutePath),
                        "androidPath" to entry.absolutePath,
                        "isDirectory" to entry.isDirectory,
                        "size" to if (entry.isFile) entry.length() else 0L
                    )
                }
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("共找到 ${files.size} 项"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id,
                actions = listOf(
                    buildOpenDirectoryAction(
                        workspace = workspace,
                        directory = directory,
                        label = "打开目录"
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "列目录失败")
        }
    }

    private suspend fun executeFileSearch(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_search"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(query.isNotEmpty()) { "缺少 query" }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val caseSensitive = args["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: DEFAULT_FILE_SEARCH_LIMIT
            val searchNeedle = if (caseSensitive) query else query.lowercase()
            val results = mutableListOf<Map<String, Any?>>()
            directory.walkTopDown().forEach { file ->
                if (results.size >= maxResults) return@forEach
                if (!file.isFile) return@forEach
                val normalizedName = if (caseSensitive) file.name else file.name.lowercase()
                if (normalizedName.contains(searchNeedle)) {
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "file_name",
                            "snippet" to file.name
                        )
                    )
                    return@forEach
                }
                if (file.length() > 512 * 1024) return@forEach
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val haystack = if (caseSensitive) text else text.lowercase()
                val index = haystack.indexOf(searchNeedle)
                if (index >= 0) {
                    val start = (index - 40).coerceAtLeast(0)
                    val end = (index + query.length + 120).coerceAtMost(text.length)
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "content",
                            "snippet" to text.substring(start, end)
                        )
                    )
                }
            }
            val payload = linkedMapOf<String, Any?>(
                "query" to query,
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to results.size,
                "items" to results
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized(if (results.isEmpty()) "未找到匹配结果" else "找到 ${results.size} 个匹配结果"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "搜索文件失败")
        }
    }

    private suspend fun executeFileStat(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_stat"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowRootDirectories = true,
                allowPublicStorage = true
            )
            require(file.exists()) { "路径不存在：${file.absolutePath}" }
            val artifact = file.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "name" to file.name,
                "exists" to file.exists(),
                "isDirectory" to file.isDirectory,
                "isFile" to file.isFile,
                "size" to if (file.isFile) file.length() else 0L,
                "lastModified" to file.lastModified(),
                "mimeType" to if (file.isFile) workspaceManager.guessMimeType(file) else "inode/directory",
                "uri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("已读取路径信息：${file.name.ifBlank { workspaceManager.shellPathForAndroid(file) ?: file.absolutePath }}"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id,
                actions = if (file.isDirectory) {
                    listOf(buildOpenDirectoryAction(workspace, file))
                } else {
                    emptyList()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "查看文件信息失败")
        }
    }

    private suspend fun executeFileMove(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_move"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            requirePublicStorageAccessIfNeeded(
                callback,
                args["sourcePath"]?.jsonPrimitive?.contentOrNull,
                args["targetPath"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            reportToolProgress(callback, toolName, "正在移动文件")
            val source = workspaceManager.resolvePath(
                args["sourcePath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            val target = workspaceManager.resolvePath(
                args["targetPath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            require(source.exists()) { "源文件不存在：${source.absolutePath}" }
            val overwrite = args["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            require(overwrite || !target.exists()) { "目标已存在：${target.absolutePath}" }
            target.parentFile?.mkdirs()
            if (overwrite && target.exists()) {
                target.deleteRecursively()
            }
            source.copyRecursively(target, overwrite = overwrite)
            source.deleteRecursively()
            val artifact = target.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "sourcePath" to (workspaceManager.shellPathForAndroid(source) ?: source.absolutePath),
                "androidSourcePath" to source.absolutePath,
                "targetPath" to (workspaceManager.shellPathForAndroid(target) ?: target.absolutePath),
                "androidTargetPath" to target.absolutePath,
                "overwrite" to overwrite,
                "targetUri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("已移动到：${target.name}"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "移动文件失败")
        }
    }

    private suspend fun executeSkillsList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "skills_list"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200)
                ?: DEFAULT_SKILLS_LIST_LIMIT
            val normalizedQuery = query.lowercase()
            val entries = skillIndexService.listInstalledSkills()
                .filter { entry ->
                    val compatibility = SkillCompatibilityChecker.evaluate(entry)
                    if (!compatibility.available) {
                        return@filter false
                    }
                    if (normalizedQuery.isBlank()) {
                        true
                    } else {
                        listOf(
                            entry.id,
                            entry.name,
                            entry.description,
                            entry.shellSkillFilePath,
                            entry.shellRootPath
                        ).any { field ->
                            field.lowercase().contains(normalizedQuery)
                        }
                    }
                }
                .take(limit)

            val items = entries.map { entry ->
                mapOf(
                    "id" to entry.id,
                    "name" to entry.name,
                    "description" to entry.description,
                    "enabled" to entry.enabled,
                    "source" to entry.source,
                    "installed" to entry.installed,
                    "rootPath" to entry.shellRootPath,
                    "androidRootPath" to entry.rootPath,
                    "skillFilePath" to entry.shellSkillFilePath,
                    "androidSkillFilePath" to entry.skillFilePath,
                    "capabilities" to buildList {
                        if (entry.hasScripts) add("scripts")
                        if (entry.hasReferences) add("references")
                        if (entry.hasAssets) add("assets")
                        if (entry.hasEvals) add("evals")
                    },
                    "metadata" to entry.metadata
                )
            }

            val payload = linkedMapOf<String, Any?>(
                "query" to query,
                "count" to items.size,
                "skillsRoot" to workspaceManager.shellPathForAndroid(workspaceManager.skillsRoot()),
                "androidSkillsRoot" to workspaceManager.skillsRoot().absolutePath,
                "items" to items
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized(if (items.isEmpty()) {
                    "当前没有匹配的 skills"
                } else {
                    "共找到 ${items.size} 个 skill"
                }),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "列出 skills 失败")
        }
    }

    private suspend fun executeSkillsRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "skills_read"
        return try {
            requireWorkspaceStorageAccess(callback)?.let { return it }
            val skillId = args["skillId"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(skillId.isNotEmpty()) { "缺少 skillId" }
            val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull?.coerceIn(512, 64_000)
                ?: DEFAULT_SKILL_READ_MAX_CHARS
            val entry = skillIndexService.findInstalledSkill(skillId)
                ?: throw IllegalArgumentException("未找到 skill：$skillId")
            val compatibility = SkillCompatibilityChecker.evaluate(entry)
            require(compatibility.available) {
                compatibility.reason ?: "当前环境不可用"
            }
            val resolved = skillLoader.load(entry, "agent 主动读取 skill")
                ?: throw IllegalStateException("读取 SKILL.md 失败：${entry.shellSkillFilePath}")
            val skillFile = File(entry.skillFilePath)
            val artifact = workspaceManager.buildArtifactForFile(skillFile, toolName)
            val payload = linkedMapOf<String, Any?>(
                "id" to entry.id,
                "name" to entry.name,
                "description" to entry.description,
                "enabled" to entry.enabled,
                "source" to entry.source,
                "installed" to entry.installed,
                "rootPath" to entry.shellRootPath,
                "androidRootPath" to entry.rootPath,
                "skillFilePath" to entry.shellSkillFilePath,
                "androidSkillFilePath" to entry.skillFilePath,
                "scriptsDir" to resolved.scriptsDir,
                "assetsDir" to resolved.assetsDir,
                "references" to resolved.loadedReferences,
                "metadata" to resolved.metadata,
                "frontmatter" to resolved.frontmatter,
                "bodyMarkdown" to truncateText(resolved.bodyMarkdown, maxChars),
                "uri" to artifact.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("已读取 skill：${entry.name}"),
                previewJson = encodeLocalizedPayload(payload),
                rawResultJson = encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            workspacePermissionResult(e, callback)?.let { return it }
            errorResult(toolName, e.message, "读取 skill 失败")
        }
    }

    private fun executeWorkbenchProjectCreate(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_create"
        return try {
            val config = jsonObjectToMap(args)
            val project = workbenchProjectStore.createProject(config)
            val payload = linkedMapOf<String, Any?>(
                "success" to true,
                "project" to project,
                "registryPath" to "/workspace/projects/registry.json",
                "apiRegistryPath" to "/workspace/projects/api_registry.json"
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench project created",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench project create failed")
        }
    }

    private fun executeWorkbenchApiList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_api_list"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.contentOrNull
            val apis = workbenchProjectStore.listApis(projectId)
            val payload = linkedMapOf<String, Any?>(
                "success" to true,
                "projectId" to projectId,
                "count" to apis.size,
                "apis" to apis
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench APIs listed",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench API list failed")
        }
    }

    private fun executeWorkbenchProjectList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_list"
        return try {
            val projects = workbenchProjectStore.listProjects()
            val payload = linkedMapOf<String, Any?>(
                "success" to true,
                "count" to projects.size,
                "projects" to projects
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench projects listed",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench project list failed")
        }
    }

    private fun executeWorkbenchProjectGet(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_get"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val project = workbenchProjectStore.getProject(projectId)
            val payload = linkedMapOf<String, Any?>(
                "success" to true,
                "project" to project
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench project loaded",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench project get failed")
        }
    }

    private fun executeWorkbenchApiCall(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_api_call"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val apiId = args["apiId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val inputs = (args["inputs"] as? JsonObject)
                ?.let(::jsonObjectToMap)
                ?: emptyMap()
            val payload = workbenchProjectStore.callApi(
                projectId = projectId,
                apiId = apiId,
                inputs = inputs,
                caller = "ai"
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (payload["success"] == true) {
                    "Workbench API called"
                } else {
                    payload["errorMessage"]?.toString() ?: "Workbench API call failed"
                },
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = payload["success"] == true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench API call failed")
        }
    }

    private fun executeWorkbenchProjectExport(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_export"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val payload = workbenchProjectStore.exportProject(projectId)
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench project exported",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = payload["success"] == true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench project export failed")
        }
    }

    private fun executeWorkbenchProjectOpen(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_open"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val route = workbenchProjectStore.routeForProject(projectId)
            TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
            val payload = linkedMapOf<String, Any?>(
                "success" to true,
                "projectId" to projectId,
                "route" to route
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench project opened",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench project open failed")
        }
    }

    private fun executeWorkbenchProjectDelete(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_delete"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val payload = workbenchProjectStore.deleteProject(projectId)
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench project deleted",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = payload["success"] == true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench project delete failed")
        }
    }

    private fun executeWorkbenchProjectHotUpdate(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_hot_update"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val prompt = args["prompt"]?.jsonPrimitive?.content?.trim().orEmpty()
            val payload = workbenchProjectStore.hotUpdateProject(
                projectId = projectId,
                prompt = prompt,
                caller = "ai"
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Workbench project hot updated",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = payload["success"] == true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench project hot update failed")
        }
    }

    private fun executeWorkbenchProjectIngestAndroid(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor
    ): ToolExecutionResult {
        val toolName = "workbench_project_ingest_android"
        return try {
            val projectId = args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val sourcePath = args["sourcePath"]?.jsonPrimitive?.content?.trim().orEmpty()
            val sourceKind = args["sourceKind"]?.jsonPrimitive?.contentOrNull?.trim()
            val displayName = args["displayName"]?.jsonPrimitive?.contentOrNull?.trim()
            val payload = workbenchProjectStore.ingestAndroidAsset(
                projectId = projectId,
                sourcePath = sourcePath,
                sourceKind = sourceKind,
                displayName = displayName,
                caller = "ai"
            )
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "Android asset imported into Workbench project",
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = payload["success"] == true,
                workspaceId = workspace.id
            )
        } catch (e: Exception) {
            errorResult(toolName, e.message, "Workbench Android import failed")
        }
    }

    private suspend fun executeScheduleTool(
        toolName: String,
        args: JsonObject,
        runtimeContextRepository: AgentRuntimeContextRepository,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            when (toolName) {
                "schedule_task_create" -> {
                    reportToolProgress(callback, toolName, "正在创建定时任务")
                    val payload = jsonObjectToMap(args).toMutableMap()
                    val targetKind = payload["targetKind"]?.toString()?.trim().orEmpty()
                    if (targetKind != "vlm" && targetKind != "subagent") {
                        throw IllegalArgumentException("targetKind 仅支持 vlm 或 subagent")
                    }
                    if (targetKind == "vlm") {
                        val goal = payload["goal"]?.toString()?.trim().orEmpty()
                        if (goal.isEmpty()) {
                            throw IllegalArgumentException("vlm 定时任务缺少 goal")
                        }
                        val missing = checkExecutionPrerequisites()
                        if (missing.isNotEmpty()) {
                            return permissionRequiredResult(callback, missing)
                        }
                    }
                    if (targetKind == "subagent") {
                        val prompt = payload["subagentPrompt"]?.toString()?.trim().orEmpty()
                        if (prompt.isEmpty()) {
                            throw IllegalArgumentException("subagent 定时任务缺少 subagentPrompt")
                        }
                        if (!payload.containsKey("notificationEnabled")) {
                            payload["notificationEnabled"] = true
                        }
                    }
                    if (!payload.containsKey("enabled")) {
                        payload["enabled"] = true
                    }
                    val result = scheduleToolBridge.createTask(payload)
                    ToolExecutionResult.ScheduleResult(
                        toolName = toolName,
                        summaryText = localized(result["summary"]?.toString()
                            ?: "定时任务已创建"),
                        previewJson = encodeLocalizedPayload(result),
                        success = result["success"] != false,
                        taskId = result["taskId"]?.toString()
                    )
                }

                "schedule_task_list" -> {
                    reportToolProgress(callback, toolName, "正在读取定时任务列表")
                    val result = scheduleToolBridge.listTasks()
                    val preview = encodeLocalizedPayload(result)
                    val summary = if (result.isEmpty()) {
                        "当前没有定时任务。"
                    } else {
                        "当前共有 ${result.size} 个定时任务。"
                    }
                    ToolExecutionResult.ScheduleResult(
                        toolName = toolName,
                        summaryText = localized(summary),
                        previewJson = preview,
                        success = true
                    )
                }

                "schedule_task_update" -> {
                    reportToolProgress(callback, toolName, "正在更新定时任务")
                    val payload = jsonObjectToMap(args).toMutableMap()
                    val targetKind = payload["targetKind"]?.toString()?.trim()
                    if (targetKind != null && targetKind != "vlm" && targetKind != "subagent") {
                        throw IllegalArgumentException("targetKind 仅支持 vlm 或 subagent")
                    }
                    val result = scheduleToolBridge.updateTask(payload)
                    ToolExecutionResult.ScheduleResult(
                        toolName = toolName,
                        summaryText = localized(result["summary"]?.toString()
                            ?: "定时任务已更新"),
                        previewJson = encodeLocalizedPayload(result),
                        success = result["success"] != false,
                        taskId = result["taskId"]?.toString()
                    )
                }

                "schedule_task_delete" -> {
                    reportToolProgress(callback, toolName, "正在删除定时任务")
                    val result = scheduleToolBridge.deleteTask(jsonObjectToMap(args))
                    ToolExecutionResult.ScheduleResult(
                        toolName = toolName,
                        summaryText = localized(result["summary"]?.toString()
                            ?: "定时任务已删除"),
                        previewJson = encodeLocalizedPayload(result),
                        success = result["success"] != false,
                        taskId = result["taskId"]?.toString()
                    )
                }

                else -> ToolExecutionResult.Error(toolName, "Unknown schedule tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, localized(e.message ?: "Schedule bridge failed"))
        }
    }

    private suspend fun executeAlarmTool(
        toolName: String,
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            when (toolName) {
                "alarm_reminder_create" -> {
                    reportToolProgress(callback, toolName, "正在创建提醒闹钟")
                    val mode = args["mode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val triggerAt = args["triggerAt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val message = args["message"]?.jsonPrimitive?.contentOrNull?.trim()
                    val timezone = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim()
                    val allowWhileIdle = args["allowWhileIdle"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: true
                    val skipUi = args["skipUi"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: false

                    if (title.isBlank()) {
                        throw IllegalArgumentException("title 不能为空")
                    }
                    if (triggerAt.isBlank()) {
                        throw IllegalArgumentException("triggerAt 不能为空")
                    }

                    if (mode == "exact_alarm" && !alarmToolService.hasExactAlarmPermission()) {
                        alarmToolService.openExactAlarmPermissionSettings()
                        val missing = listOf("精确闹钟权限(SCHEDULE_EXACT_ALARM)")
                        return permissionRequiredResult(callback, missing)
                    }
                    if (mode == "exact_alarm" && !alarmToolService.hasNotificationPermission()) {
                        val granted = alarmToolService.requestNotificationPermission()
                        if (!granted) {
                            val missing = listOf("通知权限(POST_NOTIFICATIONS)")
                            return permissionRequiredResult(callback, missing)
                        }
                    }

                    val payload = alarmToolService.createReminder(
                        AgentAlarmCreateRequest(
                            mode = mode,
                            title = title,
                            triggerAt = triggerAt,
                            message = message,
                            timezone = timezone,
                            allowWhileIdle = allowWhileIdle,
                            skipUi = skipUi
                        )
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(payload["summary"]?.toString().orEmpty().ifBlank { "提醒闹钟已创建" }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = payload["success"] != false
                    )
                }

                "alarm_reminder_list" -> {
                    reportToolProgress(callback, toolName, "正在读取提醒闹钟列表")
                    val items = alarmToolService.listExactReminders()
                    val payload = mapOf(
                        "count" to items.size,
                        "items" to items
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(if (items.isEmpty()) "当前没有提醒闹钟。" else "当前共有 ${items.size} 个提醒闹钟。"),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = true
                    )
                }

                "alarm_reminder_delete" -> {
                    reportToolProgress(callback, toolName, "正在删除提醒闹钟")
                    val alarmId = args["alarmId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (alarmId.isBlank()) {
                        throw IllegalArgumentException("alarmId 不能为空")
                    }
                    val payload = alarmToolService.deleteExactReminder(alarmId)
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(payload["summary"]?.toString().orEmpty().ifBlank { "提醒闹钟已删除" }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = payload["success"] != false
                    )
                }

                else -> ToolExecutionResult.Error(toolName, "Unknown alarm tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, localized(e.message ?: "Alarm tool failed"))
        }
    }

    private suspend fun executeCalendarTool(
        toolName: String,
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            if (!calendarToolService.hasCalendarPermissions()) {
                reportToolProgress(callback, toolName, "正在请求日历权限")
                val granted = calendarToolService.requestCalendarPermissions()
                if (!granted) {
                    val missing = listOf("日历权限(READ/WRITE_CALENDAR)")
                    return permissionRequiredResult(callback, missing)
                }
            }

            when (toolName) {
                "calendar_list" -> {
                    reportToolProgress(callback, toolName, "正在读取日历列表")
                    val writableOnly = args["writableOnly"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: true
                    val visibleOnly = args["visibleOnly"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: true
                    val items = calendarToolService.listCalendars(
                        writableOnly = writableOnly,
                        visibleOnly = visibleOnly
                    )
                    val payload = mapOf(
                        "count" to items.size,
                        "items" to items
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(if (items.isEmpty()) {
                            "未找到符合条件的日历。"
                        } else {
                            "找到 ${items.size} 个日历。"
                        }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = true
                    )
                }

                "calendar_event_create" -> {
                    reportToolProgress(callback, toolName, "正在创建日程")
                    val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val startAt = args["startAt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val endAt = args["endAt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (title.isBlank()) throw IllegalArgumentException("title 不能为空")
                    if (startAt.isBlank()) throw IllegalArgumentException("startAt 不能为空")
                    if (endAt.isBlank()) throw IllegalArgumentException("endAt 不能为空")

                    val payload = calendarToolService.createEvent(
                        CalendarEventCreateRequest(
                            title = title,
                            startAt = startAt,
                            endAt = endAt,
                            calendarId = args["calendarId"]?.jsonPrimitive?.contentOrNull?.trim(),
                            description = args["description"]?.jsonPrimitive?.contentOrNull?.trim(),
                            location = args["location"]?.jsonPrimitive?.contentOrNull?.trim(),
                            timezone = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim(),
                            allDay = args["allDay"]?.jsonPrimitive?.contentOrNull
                                ?.toBooleanStrictOrNull() ?: false,
                            reminderMinutes = parseIntegerArray(args["reminderMinutes"] as? JsonArray)
                        )
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(payload["summary"]?.toString().orEmpty().ifBlank { "日程已创建" }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = payload["success"] != false
                    )
                }

                "calendar_event_list" -> {
                    reportToolProgress(callback, toolName, "正在查询日程")
                    val payload = calendarToolService.listEvents(
                        CalendarEventListRequest(
                            calendarId = args["calendarId"]?.jsonPrimitive?.contentOrNull?.trim(),
                            startAt = args["startAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            endAt = args["endAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            query = args["query"]?.jsonPrimitive?.contentOrNull?.trim(),
                            limit = calendarToolService.normalizeListLimit(
                                args["limit"]?.jsonPrimitive?.intOrNull
                            )
                        )
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized("找到 ${payload["count"] ?: 0} 条日程。"),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = payload["success"] != false
                    )
                }

                "calendar_event_update" -> {
                    reportToolProgress(callback, toolName, "正在修改日程")
                    val eventId = args["eventId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (eventId.isBlank()) throw IllegalArgumentException("eventId 不能为空")
                    val payload = calendarToolService.updateEvent(
                        CalendarEventUpdateRequest(
                            eventId = eventId,
                            title = args["title"]?.jsonPrimitive?.contentOrNull?.trim(),
                            startAt = args["startAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            endAt = args["endAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            description = args["description"]?.jsonPrimitive?.contentOrNull?.trim(),
                            location = args["location"]?.jsonPrimitive?.contentOrNull?.trim(),
                            timezone = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim(),
                            allDay = args["allDay"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                            reminderMinutes = if (args.containsKey("reminderMinutes")) {
                                parseIntegerArray(args["reminderMinutes"] as? JsonArray)
                            } else {
                                null
                            }
                        )
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(payload["summary"]?.toString().orEmpty().ifBlank { "日程已更新" }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = payload["success"] != false
                    )
                }

                "calendar_event_delete" -> {
                    reportToolProgress(callback, toolName, "正在删除日程")
                    val eventId = args["eventId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (eventId.isBlank()) throw IllegalArgumentException("eventId 不能为空")
                    val payload = calendarToolService.deleteEvent(eventId)
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(payload["summary"]?.toString().orEmpty().ifBlank { "日程已删除" }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = payload["success"] != false
                    )
                }

                else -> ToolExecutionResult.Error(toolName, "Unknown calendar tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, localized(e.message ?: "Calendar tool failed"))
        }
    }

    private suspend fun executeMusicTool(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "music_playback_control"
        return try {
            val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            val source = args["source"]?.jsonPrimitive?.contentOrNull?.trim()
            val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()
            val loop = args["loop"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            val positionSeconds = args["positionSeconds"]?.jsonPrimitive?.intOrNull

            if (action.isBlank()) {
                throw IllegalArgumentException("action 不能为空")
            }

            if (!source.isNullOrBlank()) {
                val needsWorkspaceResolution = source.startsWith("omnibot://", ignoreCase = true) ||
                    source.startsWith(AgentWorkspaceManager.SHELL_ROOT_PATH) ||
                    source.startsWith("/") ||
                    !source.contains("://")
                if (needsWorkspaceResolution) {
                    requireWorkspaceStorageAccess(callback)?.let { return it }
                }

                val publicCandidates = buildList {
                    add(source)
                    if (source.startsWith("file://", ignoreCase = true)) {
                        Uri.parse(source).path?.let { add(it) }
                    }
                }
                requirePublicStorageAccessIfNeeded(
                    callback,
                    *publicCandidates.toTypedArray()
                )?.let { return it }
            }

            reportToolProgress(
                callback,
                toolName,
                when (action) {
                    "play" -> if (source.isNullOrBlank()) {
                        "正在发送系统播放命令"
                    } else {
                        "正在准备播放音频"
                    }

                    "pause" -> "正在暂停播放"
                    "resume" -> "正在恢复播放"
                    "stop" -> "正在停止播放"
                    "seek" -> "正在调整播放进度"
                    "status" -> "正在读取播放状态"
                    "next" -> "正在切换到下一首"
                    "previous" -> "正在切换到上一首"
                    else -> "正在执行音乐播放控制"
                }
            )

            val payload = when (action) {
                "play" -> musicToolService.play(
                    AgentMusicPlayRequest(
                        source = source,
                        title = title,
                        loop = loop
                    ),
                    workspace
                )

                "pause" -> musicToolService.pause()
                "resume" -> musicToolService.resume()
                "stop" -> musicToolService.stop()
                "seek" -> {
                    if (positionSeconds == null) {
                        throw IllegalArgumentException("seek 动作需要提供 positionSeconds")
                    }
                    musicToolService.seek(positionSeconds)
                }

                "status" -> musicToolService.status()
                "next" -> musicToolService.next()
                "previous" -> musicToolService.previous()
                else -> throw IllegalArgumentException("不支持的 action：$action")
            }

            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized(payload["summary"]?.toString().orEmpty().ifBlank {
                    "音乐播放控制已执行"
                }),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = payload["success"] != false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, localized(e.message ?: "Music tool failed"))
        }
    }

    private suspend fun executeMcpTool(
        remoteTool: RemoteMcpToolDescriptor,
        args: JsonObject,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            val toolTitle = args["tool_title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            reportToolProgress(
                callback,
                remoteTool.encodedToolName,
                toolTitle.ifBlank { "正在调用 ${remoteTool.serverName} 的 ${remoteTool.toolName}" }
            )
            val config = RemoteMcpConfigStore.getServer(remoteTool.serverId)
                ?: throw IllegalStateException("Remote MCP server not found")
            val result = RemoteMcpClient.callTool(
                config = config,
                toolName = remoteTool.toolName,
                arguments = jsonObjectToMap(args).filterKeys { it != "tool_title" }
            )
            ToolExecutionResult.McpResult(
                toolName = remoteTool.encodedToolName,
                serverName = remoteTool.serverName,
                summaryText = result.summaryText,
                previewJson = result.previewJson,
                rawResultJson = result.rawResultJson,
                success = result.success
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(
                remoteTool.encodedToolName,
                localized(e.message ?: "MCP tool call failed")
            )
        }
    }

    private suspend fun executeMemoryTool(
        toolName: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            when (toolName) {
                "memory_search" -> {
                    reportToolProgress(callback, toolName, "正在检索 workspace 记忆")
                    val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(query.isNotEmpty()) { "query 不能为空" }
                    val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 20) ?: 8
                    val result = env.workspaceMemoryService.searchMemory(query, limit)
                    val payload = linkedMapOf<String, Any?>(
                        "query" to result.query,
                        "usedEmbedding" to result.usedEmbedding,
                        "fallbackLexical" to result.fallbackLexical,
                        "count" to result.hits.size,
                        "hits" to result.hits.map { hit ->
                            mapOf(
                                "id" to hit.id,
                                "text" to hit.text,
                                "source" to hit.source,
                                "date" to hit.date,
                                "score" to hit.score
                            )
                        }
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(when {
                            result.hits.isEmpty() -> "未命中相关记忆。"
                            result.fallbackLexical -> "命中 ${result.hits.size} 条记忆（词法检索）。"
                            else -> "命中 ${result.hits.size} 条记忆。"
                        }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = true
                    )
                }

                "memory_write_daily" -> {
                    reportToolProgress(callback, toolName, "正在写入当日记忆")
                    val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(text.isNotEmpty()) { "text 不能为空" }
                    val file = env.workspaceMemoryService.appendDailyMemory(text)
                    val payload = mapOf(
                        "path" to file.absolutePath,
                        "summary" to "已写入当日记忆"
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized("已写入当日短期记忆。"),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = true
                    )
                }

                "memory_upsert_longterm" -> {
                    reportToolProgress(callback, toolName, "正在沉淀长期记忆")
                    val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(text.isNotEmpty()) { "text 不能为空" }
                    val inserted = env.workspaceMemoryService.upsertLongTermMemory(text)
                    val payload = mapOf(
                        "inserted" to inserted,
                        "summary" to if (inserted) "已写入长期记忆" else "检测到重复，已跳过"
                    )
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(if (inserted) {
                            "已沉淀一条长期记忆。"
                        } else {
                            "长期记忆已存在同类条目，跳过写入。"
                        }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = true
                    )
                }

                "memory_rollup_day" -> {
                    reportToolProgress(callback, toolName, "正在整理当日记忆")
                    val dateRaw = args["date"]?.jsonPrimitive?.contentOrNull?.trim()
                    val date = dateRaw?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                        ?: LocalDate.now()
                    val payload = env.workspaceMemoryService.rollupDay(date)
                    val payloadJson = encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = localized(payload["summary"]?.toString().orEmpty().ifBlank { "记忆整理完成" }),
                        previewJson = payloadJson,
                        rawResultJson = payloadJson,
                        success = payload["success"] != false
                    )
                }

                else -> ToolExecutionResult.Error(toolName, "Unknown memory tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, localized(e.message ?: "memory tool failed"))
        }
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

            reportToolProgress(
                callback,
                toolName,
                "正在分派 ${tasks.size} 个子任务（并发 $concurrency）"
            )

            val workers = tasks.mapIndexed { index, task ->
                scope.async(Dispatchers.Default) {
                    ensureRunActive()
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
            val payloadJson = encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = localized("已完成 ${results.size} 个 subagent 子任务。"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, localized(e.message ?: "subagent dispatch failed"))
        }
    }

    private fun sanitizeVlmExecutionArgs(
        rawArgs: VlmExecutionArgs,
        userMessage: String,
        appNameToPackage: Map<String, String>,
        currentPackageName: String?
    ): VlmArgsSanitizeResult {
        var startFromCurrent = rawArgs.startFromCurrent
        var packageName = rawArgs.packageName?.trim()?.takeIf { it.isNotEmpty() }
        val reasons = mutableListOf<String>()

        val explicitCurrentIntent =
            isExplicitCurrentPageIntent(userMessage) || isExplicitCurrentPageIntent(rawArgs.goal)
        val openAppIntent =
            isLikelyOpenAppIntent(userMessage) || isLikelyOpenAppIntent(rawArgs.goal)
        val detectedTargetPackage =
            detectTargetAppPackage(userMessage, appNameToPackage)
                ?: detectTargetAppPackage(rawArgs.goal, appNameToPackage)

        if (packageName == null && openAppIntent && detectedTargetPackage != null) {
            packageName = detectedTargetPackage
            reasons.add("open_app_intent_autofill_package")
        }

        val currentPackage = currentPackageName?.trim()?.takeIf { it.isNotEmpty() }
        val assistantPackage = context.packageName
        val targetPackage = packageName ?: detectedTargetPackage

        if (startFromCurrent && !explicitCurrentIntent) {
            startFromCurrent = false
            reasons.add("start_from_current_without_current_intent")
        }
        if (startFromCurrent && openAppIntent) {
            startFromCurrent = false
            reasons.add("open_app_should_not_start_from_current")
        }
        if (startFromCurrent && targetPackage != null && currentPackage != null && targetPackage != currentPackage) {
            startFromCurrent = false
            reasons.add("target_package_differs_from_current_package")
        }
        if (startFromCurrent && currentPackage == assistantPackage &&
            targetPackage != null && targetPackage != assistantPackage
        ) {
            startFromCurrent = false
            reasons.add("assistant_page_cannot_start_external_app_from_current")
        }

        return VlmArgsSanitizeResult(
            args = rawArgs.copy(
                packageName = packageName,
                startFromCurrent = startFromCurrent
            ),
            reasons = reasons.distinct()
        )
    }

    private fun detectTargetAppPackage(
        text: String,
        appNameToPackage: Map<String, String>
    ): String? {
        if (text.isBlank() || appNameToPackage.isEmpty()) return null
        val normalizedText = normalizeIntentText(text)
        if (normalizedText.isBlank()) return null

        var bestMatchLength = -1
        var bestPackage: String? = null
        appNameToPackage.forEach { (appName, packageName) ->
            val normalizedName = normalizeIntentText(appName)
            if (normalizedName.isBlank()) return@forEach
            if (normalizedText.contains(normalizedName) && normalizedName.length > bestMatchLength) {
                bestMatchLength = normalizedName.length
                bestPackage = packageName
            }
        }
        return bestPackage
    }

    private fun isExplicitCurrentPageIntent(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = normalizeIntentText(text)
        val markers = listOf(
            "当前页面", "当前应用", "当前界面", "这个页面", "这个界面",
            "这里", "在这", "正在看的", "继续刚才", "继续之前", "从当前"
        )
        return markers.any { normalized.contains(normalizeIntentText(it)) }
    }

    private fun isLikelyOpenAppIntent(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = normalizeIntentText(text)
        val openVerbs = listOf("打开", "启动", "进入", "点开")
        val hasOpenVerb = openVerbs.any { normalized.contains(it) }
        if (!hasOpenVerb) return false
        val followUpActionWords = listOf(
            "搜索", "发送", "回复", "聊天", "下单", "支付", "付款", "购买",
            "浏览", "查看", "看看", "总结", "答题", "填写", "输入", "点击",
            "并", "然后", "再", "之后", "顺便"
        )
        return followUpActionWords.none { normalized.contains(it) }
    }

    private fun normalizeIntentText(text: String): String {
        return text.lowercase()
            .replace(Regex("\\s+"), "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("'", "")
            .replace("。", "")
            .replace("，", "")
            .replace(",", "")
            .replace("！", "")
            .replace("!", "")
            .replace("？", "")
            .replace("?", "")
    }

    private fun parseIntegerArray(raw: JsonArray?): List<Int> {
        if (raw == null) return emptyList()
        return raw.mapNotNull { item ->
            (item as? JsonPrimitive)?.intOrNull
        }
    }

    private fun parseContextQueryLimit(rawLimit: Int?): Int {
        return rawLimit?.coerceIn(1, 100) ?: DEFAULT_CONTEXT_QUERY_LIMIT
    }

    private fun rememberOwnedTerminalSession(
        workspaceId: String,
        sessionId: String,
        sessionName: String?
    ) {
        terminalSessionRegistry.rememberSession(
            workspaceId = workspaceId,
            sessionId = sessionId,
            sessionName = sessionName
        )
    }

    private fun isOwnedTerminalSession(workspaceId: String, sessionId: String): Boolean {
        return terminalSessionRegistry.ownsSession(
            workspaceId = workspaceId,
            sessionId = sessionId
        )
    }

    private fun rememberOwnedTerminalSession(sessionId: String) {
        rememberOwnedTerminalSession(
            workspaceId = DIRECT_TERMINAL_WORKSPACE_ID,
            sessionId = sessionId,
            sessionName = null
        )
    }

    private fun isOwnedTerminalSession(sessionId: String): Boolean {
        return isOwnedTerminalSession(
            workspaceId = DIRECT_TERMINAL_WORKSPACE_ID,
            sessionId = sessionId
        )
    }

    private fun forgetOwnedTerminalSession(sessionId: String) {
        terminalSessionRegistry.forgetSession(sessionId)
    }

    private suspend fun closeOwnedDirectTerminalSessions() {
        terminalSessionRegistry.listSessionIds(DIRECT_TERMINAL_WORKSPACE_ID).forEach { sessionId ->
            runCatching {
                stopDirectTerminalSession(sessionId)
            }
            forgetOwnedTerminalSession(sessionId)
        }
    }

    private suspend fun <T> withLocalTerminalManager(
        block: suspend (TerminalManager) -> T
    ): T {
        val manager = TerminalManager.getInstance(context)
        val previousType = manager.getPreferredTerminalType()
        manager.setPreferredTerminalType(TerminalType.LOCAL)
        return try {
            block(manager)
        } finally {
            manager.setPreferredTerminalType(previousType)
        }
    }

    private suspend fun executeDirectTerminalCommand(
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Int,
        environment: Map<String, String>,
        onLiveUpdate: suspend (sessionId: String, outputDelta: String, streamState: String) -> Unit = { _, _, _ -> }
    ): TermuxCommandResult {
        val createdSession = withLocalTerminalManager { manager ->
            createLocalTerminalSession(manager, "Agent Terminal")
        }
        rememberOwnedTerminalSession(createdSession.id)
        onLiveUpdate(createdSession.id, "", "running")

        return try {
            val execution = withLocalTerminalManager { manager ->
                executeDirectCommandInSession(
                    manager = manager,
                    sessionId = createdSession.id,
                    command = buildDirectShellCommand(command, workingDirectory, environment),
                    timeoutSeconds = timeoutSeconds,
                    onLiveOutput = { outputDelta ->
                        onLiveUpdate(createdSession.id, outputDelta, "running")
                    }
                )
            }
            if (!execution.timedOut) {
                stopDirectTerminalSession(createdSession.id)
            }

            val terminalOutput = execution.transcript.ifBlank { execution.output }
            val completedSuccessfully =
                execution.completed && !execution.timedOut && execution.errorMessage.isNullOrBlank()
            TermuxCommandResult(
                success = completedSuccessfully,
                timedOut = execution.timedOut,
                resultCode = null,
                errorCode = null,
                errorMessage = execution.errorMessage,
                stdout = if (completedSuccessfully) execution.output else "",
                stderr = if (completedSuccessfully) "" else execution.output.ifBlank { terminalOutput },
                rawExtras = mapOf(
                    "executionPath" to "terminal_manager_session",
                    "currentDirectory" to execution.currentDirectory
                ),
                terminalOutput = terminalOutput,
                liveSessionId = createdSession.id,
                liveStreamState = when {
                    execution.timedOut || execution.commandRunning -> "running"
                    execution.errorMessage != null -> "error"
                    else -> "completed"
                },
                liveFallbackReason = null
            )
        } catch (error: Exception) {
            val fallbackSnapshot = runCatching {
                withLocalTerminalManager { manager ->
                    captureDirectTerminalSessionSnapshot(manager, createdSession.id)
                }
            }.getOrNull()
            runCatching { stopDirectTerminalSession(createdSession.id) }
            TermuxCommandResult(
                success = false,
                timedOut = false,
                resultCode = null,
                errorCode = null,
                errorMessage = localized(error.message ?: "终端命令执行失败"),
                stdout = "",
                stderr = fallbackSnapshot?.transcript.orEmpty(),
                rawExtras = mapOf("executionPath" to "terminal_manager_session"),
                terminalOutput = fallbackSnapshot?.transcript.orEmpty(),
                liveSessionId = createdSession.id,
                liveStreamState = "error",
                liveFallbackReason = null
            )
        }
    }

    private suspend fun startDirectTerminalSession(
        sessionTitle: String?,
        workingDirectory: String?,
        environment: Map<String, String>
    ): DirectTerminalSessionSnapshot {
        val safeTitle = sanitizeTerminalSessionId(sessionTitle)
        val session = withLocalTerminalManager { manager ->
            createLocalTerminalSession(manager, safeTitle)
        }
        rememberOwnedTerminalSession(session.id)

        return try {
            val setupCommand = buildSessionSetupCommand(
                workingDirectory = workingDirectory,
                environment = environment
            )
            if (setupCommand.isNotBlank()) {
                val setupResult = withLocalTerminalManager { manager ->
                    executeDirectCommandInSession(
                        manager = manager,
                        sessionId = session.id,
                        command = setupCommand,
                        timeoutSeconds = 30
                    )
                }
                if (!setupResult.completed || setupResult.timedOut || !setupResult.errorMessage.isNullOrBlank()) {
                    stopDirectTerminalSession(session.id)
                    throw IllegalStateException(
                        localized(setupResult.errorMessage ?: "终端会话初始化超时，可能仍在后台继续运行。")
                    )
                }
            }

            withLocalTerminalManager { manager ->
                captureDirectTerminalSessionSnapshot(manager, session.id)
            }
        } catch (error: Exception) {
            runCatching { stopDirectTerminalSession(session.id) }
            throw error
        }
    }

    private suspend fun executeDirectTerminalSessionCommand(
        sessionId: String,
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Int,
        environment: Map<String, String>,
        onLiveUpdate: suspend (String) -> Unit = {}
    ): DirectTerminalCommandResult {
        require(sessionId.isNotBlank()) { "缺少 sessionId" }
        require(isOwnedTerminalSession(sessionId)) { "终端会话不存在或不属于当前 agent：$sessionId" }
        return withLocalTerminalManager { manager ->
            val preSnapshot = captureDirectTerminalSessionSnapshot(manager, sessionId)
            if (preSnapshot.commandRunning) {
                return@withLocalTerminalManager DirectTerminalCommandResult(
                    sessionId = sessionId,
                    completed = false,
                    timedOut = false,
                    output = "",
                    transcript = preSnapshot.transcript,
                    currentDirectory = preSnapshot.currentDirectory,
                    commandRunning = true,
                    errorMessage = localized("当前会话仍有命令在执行，请先读取输出或停止会话。")
                )
            }
            executeDirectCommandInSession(
                manager = manager,
                sessionId = sessionId,
                command = buildDirectShellCommand(command, workingDirectory, environment),
                timeoutSeconds = timeoutSeconds,
                onLiveOutput = onLiveUpdate
            )
        }
    }

    private suspend fun readDirectTerminalSession(sessionId: String): DirectTerminalSessionSnapshot {
        require(sessionId.isNotBlank()) { "缺少 sessionId" }
        require(isOwnedTerminalSession(sessionId)) { "终端会话不存在或不属于当前 agent：$sessionId" }
        return withLocalTerminalManager { manager ->
            captureDirectTerminalSessionSnapshot(manager, sessionId)
        }
    }

    private suspend fun stopDirectTerminalSession(sessionId: String): Boolean {
        if (sessionId.isBlank() || !isOwnedTerminalSession(sessionId)) {
            return false
        }
        return withLocalTerminalManager { manager ->
            val exists = findTerminalSession(manager, sessionId) != null
            if (exists) {
                manager.closeSession(sessionId)
            }
            forgetOwnedTerminalSession(sessionId)
            exists
        }
    }

    private suspend fun createLocalTerminalSession(
        manager: TerminalManager,
        title: String
    ): TerminalSessionData {
        val previousSessionId = manager.terminalState.value.currentSessionId
        val session = manager.createNewSession(title, TerminalType.LOCAL)
        if (!previousSessionId.isNullOrBlank() && previousSessionId != session.id) {
            runCatching { manager.switchToSession(previousSessionId) }
        }
        return session
    }

    private suspend fun executeDirectCommandInSession(
        manager: TerminalManager,
        sessionId: String,
        command: String,
        timeoutSeconds: Int,
        onLiveOutput: suspend (String) -> Unit = {}
    ): DirectTerminalCommandResult = coroutineScope {
        val session = findTerminalSession(manager, sessionId)
            ?: throw IllegalStateException("终端会话不存在：$sessionId")
        if (session.currentExecutingCommand?.isExecuting == true) {
            val snapshot = captureDirectTerminalSessionSnapshot(manager, sessionId)
            return@coroutineScope DirectTerminalCommandResult(
                sessionId = sessionId,
                completed = false,
                timedOut = false,
                output = "",
                transcript = snapshot.transcript,
                currentDirectory = snapshot.currentDirectory,
                commandRunning = true,
                errorMessage = "当前会话仍有命令在执行，请先读取输出或停止会话。"
            )
        }

        val commandId = UUID.randomUUID().toString()
        val completionOutput = CompletableDeferred<String?>()
        val collectorReady = CompletableDeferred<Unit>()
        val collectorJob = launch {
            manager.commandExecutionEvents
                .filter { event ->
                    event.sessionId == sessionId && event.commandId == commandId
                }
                .onStart { collectorReady.complete(Unit) }
                .collect { event ->
                    if (event.isCompleted) {
                        if (!completionOutput.isCompleted) {
                            completionOutput.complete(event.outputChunk)
                        }
                        return@collect
                    }
                    val normalizedDelta = normalizeTerminalOutputDelta(event.outputChunk)
                    if (normalizedDelta.isNotBlank()) {
                        onLiveOutput(normalizedDelta)
                    }
                }
        }

        collectorReady.await()
        manager.sendCommandToSession(
            sessionId = sessionId,
            command = command,
            commandId = commandId
        )

        val completedOutput = withTimeoutOrNull(timeoutSeconds * 1000L) {
            completionOutput.await()
        }
        collectorJob.cancelAndJoin()

        val snapshot = captureDirectTerminalSessionSnapshot(manager, sessionId)
        val normalizedOutput = EmbeddedTerminalRuntime.trimTerminalOutput(
            EmbeddedTerminalRuntime.sanitizeTerminalNoise(completedOutput.orEmpty())
        )
        if (completedOutput == null) {
            return@coroutineScope DirectTerminalCommandResult(
                sessionId = sessionId,
                completed = false,
                timedOut = true,
                output = normalizedOutput.ifBlank { snapshot.transcript },
                transcript = snapshot.transcript,
                currentDirectory = snapshot.currentDirectory,
                commandRunning = snapshot.commandRunning,
                errorMessage = localized("终端命令等待超时，可能仍在后台继续运行。")
            )
        }

        DirectTerminalCommandResult(
            sessionId = sessionId,
            completed = true,
            timedOut = false,
            output = normalizedOutput,
            transcript = snapshot.transcript,
            currentDirectory = snapshot.currentDirectory,
            commandRunning = snapshot.commandRunning,
            errorMessage = null
        )
    }

    private fun buildDirectShellCommand(
        command: String,
        workingDirectory: String?,
        environment: Map<String, String>
    ): String {
        val normalizedCommand = command.trim()
        require(normalizedCommand.isNotEmpty()) { "command 不能为空" }
        val segments = buildSessionSetupSegments(workingDirectory, environment).toMutableList()
        segments += normalizedCommand
        return segments.joinToString(separator = " && ")
    }

    private fun buildSessionSetupCommand(
        workingDirectory: String?,
        environment: Map<String, String>
    ): String {
        return buildSessionSetupSegments(workingDirectory, environment)
            .joinToString(separator = " && ")
    }

    private fun buildSessionSetupSegments(
        workingDirectory: String?,
        environment: Map<String, String>
    ): List<String> {
        val segments = mutableListOf<String>()
        environment.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (key.isEmpty() || !terminalEnvKeyPattern.matches(key)) {
                return@forEach
            }
            segments += "export $key=${quoteShell(rawValue)}"
        }
        if (!workingDirectory.isNullOrBlank()) {
            segments += "cd ${quoteShell(workingDirectory)}"
        }
        return segments
    }

    private fun normalizeTerminalOutputDelta(outputChunk: String): String {
        val cleaned = EmbeddedTerminalRuntime.sanitizeTerminalNoise(outputChunk)
        if (cleaned.isBlank()) {
            return ""
        }
        return if (cleaned.endsWith("\n")) cleaned else "$cleaned\n"
    }

    private fun captureDirectTerminalSessionSnapshot(
        manager: TerminalManager,
        sessionId: String
    ): DirectTerminalSessionSnapshot {
        val session = findTerminalSession(manager, sessionId)
            ?: throw IllegalStateException("终端会话不存在：$sessionId")
        return DirectTerminalSessionSnapshot(
            sessionId = sessionId,
            transcript = buildDirectTerminalTranscript(session),
            currentDirectory = normalizeTerminalCurrentDirectory(session.currentDirectory),
            commandRunning = session.currentExecutingCommand?.isExecuting == true
        )
    }

    private fun findTerminalSession(
        manager: TerminalManager,
        sessionId: String
    ): TerminalSessionData? {
        return manager.terminalState.value.sessions.find { session ->
            session.id == sessionId
        }
    }

    private fun buildDirectTerminalTranscript(session: TerminalSessionData): String {
        return EmbeddedTerminalRuntime.trimTerminalOutput(
            EmbeddedTerminalRuntime.sanitizeTerminalNoise(session.transcript.trim('\n'))
        )
    }

    private fun normalizeTerminalCurrentDirectory(prompt: String): String {
        val cleaned = prompt.trim().replace(Regex("""\s+[#$]\s*$"""), "")
        return if (cleaned.isBlank() || cleaned == "$") {
            "~"
        } else {
            cleaned
        }
    }

    private fun resolveShellWorkingDirectory(
        requestedPath: String?,
        workspace: AgentWorkspaceDescriptor
    ): String {
        return if (requestedPath.isNullOrBlank()) {
            workspace.currentCwd
        } else {
            workspaceManager.resolveShellPath(
                requestedPath,
                workspace,
                allowRootDirectories = true
            )
        }
    }

    private fun resolveAndroidWorkingDirectory(
        requestedPath: String?,
        workspace: AgentWorkspaceDescriptor
    ): File {
        return if (requestedPath.isNullOrBlank()) {
            File(workspace.androidCurrentCwd)
        } else {
            workspaceManager.resolvePath(requestedPath, workspace, allowRootDirectories = true)
        }
    }

    private fun sanitizeTerminalSessionId(raw: String?): String {
        val normalized = raw.orEmpty().trim()
        val base = normalized
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        return if (base.isBlank()) {
            "session_${UUID.randomUUID().toString().take(8)}"
        } else {
            base.take(48)
        }
    }

    private fun terminalSessionDirectory(
        workspace: AgentWorkspaceDescriptor,
        sessionId: String
    ): File {
        return File(
            File(workspaceManager.offloadsDirectory(workspace.id), "terminal_sessions"),
            sessionId
        )
    }

    private fun persistTerminalSessionTranscript(
        workspace: AgentWorkspaceDescriptor,
        sessionId: String,
        transcript: String,
        sourceTool: String
    ): ArtifactRef {
        val logFile = File(terminalSessionDirectory(workspace, sessionId), "latest.log")
        logFile.parentFile?.mkdirs()
        logFile.writeText(transcript)
        return workspaceManager.buildArtifactForFile(logFile, sourceTool)
    }

    private fun buildTerminalArtifacts(
        workspace: AgentWorkspaceDescriptor,
        sourceTool: String,
        terminalOutput: String
    ): List<ArtifactRef> {
        if (terminalOutput.length <= 4000) return emptyList()
        return try {
            listOf(
                workspaceManager.writeOffload(
                    agentRunId = workspace.id,
                    extension = "log",
                    content = terminalOutput
                ).copy(sourceTool = sourceTool)
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun requireWorkspaceStorageAccess(
        callback: AgentCallback
    ): ToolExecutionResult.PermissionRequired? {
        if (WorkspaceStorageAccess.isGranted(context)) {
            return null
        }
        val missing = WorkspaceStorageAccess.requiredPermissionNames()
        return permissionRequiredResult(callback, missing)
    }

    private suspend fun requirePublicStorageAccessIfNeeded(
        callback: AgentCallback,
        vararg inputPaths: String?
    ): ToolExecutionResult.PermissionRequired? {
        val needsPublicStorage = inputPaths.any { PublicStorageAccess.isPublicStorageInput(it) }
        if (!needsPublicStorage || PublicStorageAccess.isGranted()) {
            return null
        }
        val missing = PublicStorageAccess.requiredPermissionNames()
        return permissionRequiredResult(callback, missing)
    }

    private fun buildOpenDirectoryAction(
        workspace: AgentWorkspaceDescriptor,
        directory: File,
        label: String = "打开目录"
    ): ArtifactAction {
        val target = workspaceManager.uriForFile(directory) ?: directory.absolutePath
        return ArtifactAction(
            type = "workspace",
            label = localized(label),
            target = target,
            payload = mapOf(
                "workspaceId" to workspace.id,
                "workspacePath" to directory.absolutePath,
                "workspaceShellPath" to (workspaceManager.shellPathForAndroid(directory)
                    ?: directory.absolutePath)
            )
        )
    }

    private suspend fun workspacePermissionResult(
        error: Exception,
        callback: AgentCallback
    ): ToolExecutionResult.PermissionRequired? {
        if (!WorkspaceStorageAccess.looksLikePermissionError(error)) {
            return null
        }
        val missing = WorkspaceStorageAccess.requiredPermissionNames()
        return permissionRequiredResult(callback, missing)
    }

    private fun quoteShell(value: String): String = TermuxCommandBuilder.quoteForShell(value)

    private fun parseTerminalExecuteArgs(args: JsonObject): TerminalExecuteArgs {
        val command = args["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(command.isNotEmpty()) { "terminal_execute 缺少 command" }

        val requestedMode = args["executionMode"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        if (requestedMode != null) {
            require(
                requestedMode == TermuxCommandSpec.EXECUTION_MODE_TERMUX ||
                    requestedMode == TermuxCommandSpec.EXECUTION_MODE_PROOT
            ) { "executionMode 仅支持 termux 或 proot" }
        }

        // 终端能力固定在 Alpine proot 运行，避免模型误传 termux 导致偏离预期环境。
        val executionMode = TermuxCommandSpec.EXECUTION_MODE_PROOT
        val prootDistro = TermuxCommandSpec.DEFAULT_PROOT_DISTRO

        val workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val timeoutSeconds = args["timeoutSeconds"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(5, 300)
            ?: TermuxCommandSpec.DEFAULT_TIMEOUT_SECONDS

        return TerminalExecuteArgs(
            command = command,
            executionMode = executionMode,
            prootDistro = prootDistro,
            workingDirectory = workingDirectory,
            timeoutSeconds = timeoutSeconds
        )
    }

    private fun parseTerminalSessionStartArgs(args: JsonObject): TerminalSessionStartArgs {
        return TerminalSessionStartArgs(
            sessionName = args["sessionName"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun parseTerminalSessionExecArgs(args: JsonObject): TerminalSessionExecArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val command = args["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        require(command.isNotEmpty()) { "缺少 command" }
        return TerminalSessionExecArgs(
            sessionId = sessionId,
            command = command,
            workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            timeoutSeconds = args["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.coerceIn(5, 600) ?: 120
        )
    }

    private fun parseTerminalSessionReadArgs(args: JsonObject): TerminalSessionReadArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        return TerminalSessionReadArgs(
            sessionId = sessionId,
            maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull?.coerceIn(256, 64_000)
                ?: DEFAULT_TERMINAL_SESSION_READ_MAX_CHARS
        )
    }

    private fun buildTerminalToolResult(
        toolName: String,
        args: TerminalExecuteArgs,
        result: TermuxCommandResult,
        workspace: AgentWorkspaceDescriptor,
        sourceTool: String
    ): ToolExecutionResult.TerminalResult {
        val previewMap = buildTerminalResultMap(args, result, outputLimit = 2000)
        val rawResultMap = buildTerminalResultMap(args, result, outputLimit = 12000)
        val artifacts = buildTerminalArtifacts(
            workspace = workspace,
            sourceTool = sourceTool,
            terminalOutput = result.terminalOutput.ifBlank { result.stdout + result.stderr }
        )
        return ToolExecutionResult.TerminalResult(
            toolName = toolName,
            summaryText = buildTerminalSummary(result),
            previewJson = encodeLocalizedPayload(previewMap),
            rawResultJson = encodeLocalizedPayload(rawResultMap),
            success = result.success,
            timedOut = result.timedOut,
            terminalOutput = result.terminalOutput,
            terminalSessionId = result.liveSessionId,
            terminalStreamState = result.liveStreamState,
            artifacts = artifacts,
            workspaceId = workspace.id
        )
    }

    private fun buildTerminalResultMap(
        args: TerminalExecuteArgs,
        result: TermuxCommandResult,
        outputLimit: Int
    ): Map<String, Any?> {
        return linkedMapOf(
            "executionMode" to args.executionMode,
            "prootDistro" to args.prootDistro,
            "workingDirectory" to args.workingDirectory,
            "timeoutSeconds" to args.timeoutSeconds,
            "command" to args.command,
            "success" to result.success,
            "timedOut" to result.timedOut,
            "resultCode" to result.resultCode,
            "errorCode" to result.errorCode,
            "errorMessage" to result.errorMessage,
            "stdout" to truncateText(result.stdout, outputLimit),
            "stderr" to truncateText(result.stderr, outputLimit),
            "stdoutLength" to result.stdout.length,
            "stderrLength" to result.stderr.length,
            "terminalOutput" to truncateText(result.terminalOutput, outputLimit),
            "terminalOutputLength" to result.terminalOutput.length,
            "liveSessionId" to result.liveSessionId,
            "liveStreamState" to result.liveStreamState,
            "liveFallbackReason" to result.liveFallbackReason,
            "rawExtras" to sanitizeTerminalRawExtras(result.rawExtras, outputLimit)
        )
    }

    private fun buildTerminalSummary(result: TermuxCommandResult): String {
        val liveNote = if (result.liveFallbackReason.isNullOrBlank()) {
            ""
        } else if (isEnglishLocale) {
            ", falling back to showing the result after completion"
        } else {
            "，已回退为结束后展示结果"
        }
        if (result.timedOut) {
            return if (isEnglishLocale) {
                "Terminal command timed out and may still be running in the background$liveNote"
            } else {
                "终端命令等待超时，可能仍在后台继续运行$liveNote"
            }
        }

        val headline = firstUsefulLine(
            if (result.success) result.stdout else result.stderr.ifBlank { result.stdout }
        )
        val suffix = headline?.let {
            if (isEnglishLocale) ": $it" else "：$it"
        }.orEmpty()

        return when {
            result.success && result.resultCode == 0 ->
                if (isEnglishLocale) {
                    "Terminal command succeeded (exit=0)$suffix$liveNote"
                } else {
                    "终端命令执行成功（exit=0）$suffix$liveNote"
                }

            result.success ->
                if (isEnglishLocale) {
                    "Terminal command completed$suffix$liveNote"
                } else {
                    "终端命令执行完成$suffix$liveNote"
                }

            result.resultCode != null ->
                if (isEnglishLocale) {
                    "Terminal command failed (exit=${result.resultCode})$suffix$liveNote"
                } else {
                    "终端命令执行失败（exit=${result.resultCode}）$suffix$liveNote"
                }

            !result.errorMessage.isNullOrBlank() ->
                localized(result.errorMessage) + liveNote

            else -> if (isEnglishLocale) {
                "Terminal command failed$liveNote"
            } else {
                "终端命令执行失败$liveNote"
            }
        }
    }

    private fun truncateText(text: String, limit: Int): String {
        if (text.length <= limit) return text
        return text.take(limit) + "\n...[truncated]"
    }

    private fun truncateTerminalTail(text: String, limit: Int): String {
        if (text.length <= limit) return text
        return "...[earlier output truncated]\n" + text.takeLast(limit)
    }

    private fun firstUsefulLine(text: String): String? {
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length <= 120) it else it.take(120) + "..." }
    }

    private fun sanitizeTerminalRawExtras(
        rawExtras: Map<String, Any?>,
        outputLimit: Int
    ): Map<String, Any?> {
        if (rawExtras.isEmpty()) return emptyMap()
        return rawExtras.entries.associate { (key, value) ->
            key to when (value) {
                is String -> truncateText(EmbeddedTerminalRuntime.sanitizeTerminalNoise(value), outputLimit)
                is List<*> -> value.map { item ->
                    if (item is String) {
                        truncateText(EmbeddedTerminalRuntime.sanitizeTerminalNoise(item), outputLimit)
                    } else {
                        item
                    }
                }
                else -> value
            }
        }
    }

    private fun checkExecutionPrerequisites(): List<String> {
        val missing = mutableListOf<String>()
        if (!AssistsUtil.Core.isAccessibilityServiceEnabled()) {
            missing.add("无障碍权限")
        }
        if (!Settings.canDrawOverlays(context)) {
            missing.add("悬浮窗权限")
        }
        return missing
    }

    private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> {
        return jsonObject.entries.associate { (key, value) ->
            key to jsonElementToAny(value)
        }
    }

    private fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonObject -> jsonObjectToMap(element)
            is JsonArray -> element.map { jsonElementToAny(it) }
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" || element.content == "false" -> element.content.toBooleanStrict()
                element.longOrNull != null -> element.longOrNull
                element.doubleOrNull != null -> element.doubleOrNull
                else -> element.content
            }
        }
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
}
