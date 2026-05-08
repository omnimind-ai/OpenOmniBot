package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AgentToolDefinitions {
    private const val TOOL_TITLE_FIELD = "tool_title"
    
    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun toolTitleRule(locale: PromptLocale): String = when (locale) {
        PromptLocale.ZH_CN ->
            "调用时必须提供 tool_title，作为展示给用户的简洁标题，建议 4-12 个字并使用与用户相同的语言。"
        PromptLocale.EN_US ->
            "Every tool call must include `tool_title`, a short user-visible title for the chat UI. Keep it concise, roughly 4-12 words, and use the same language as the user."
    }

    private fun toolTitlePropertySchema(locale: PromptLocale): JsonObject = buildJsonObject {
        put("type", "string")
        put(
            "description",
            when (locale) {
                PromptLocale.ZH_CN ->
                    "本次工具调用要做什么的简洁标题，展示给用户，建议 4-12 个字并使用与用户相同的语言。"
                PromptLocale.EN_US ->
                    "A concise title describing what this tool call is doing. It is shown to the user, should stay short, and should use the same language as the user."
            }
        )
    }

    private fun ensureToolTitleDescription(
        description: String,
        locale: PromptLocale
    ): String {
        val trimmed = description.trim()
        if (trimmed.contains(TOOL_TITLE_FIELD)) {
            return trimmed
        }
        if (trimmed.isEmpty()) {
            return toolTitleRule(locale)
        }
        return "$trimmed ${toolTitleRule(locale)}"
    }

    fun decorateParameterSchema(
        parameters: JsonObject,
        locale: PromptLocale = currentLocale()
    ): JsonObject {
        val properties = (parameters["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val required = (parameters["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toMutableList()
            ?: mutableListOf()

        val updatedProperties = buildJsonObject {
            put(TOOL_TITLE_FIELD, toolTitlePropertySchema(locale))
            properties.forEach { (key, value) ->
                if (key != TOOL_TITLE_FIELD) {
                    put(key, value)
                }
            }
        }

        if (!required.contains(TOOL_TITLE_FIELD)) {
            required.add(0, TOOL_TITLE_FIELD)
        }

        return buildJsonObject {
            parameters.forEach { (key, value) ->
                when (key) {
                    "properties" -> put("properties", updatedProperties)
                    "required" -> {
                        put(
                            "required",
                            buildJsonArray {
                                required.forEach { add(JsonPrimitive(it)) }
                            }
                        )
                    }

                    else -> put(key, value)
                }
            }
            if (parameters["properties"] == null) {
                put("properties", updatedProperties)
            }
            if (parameters["required"] == null) {
                put(
                    "required",
                    buildJsonArray {
                        required.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
        }
    }

    fun decorateToolDefinition(
        definition: JsonObject,
        locale: PromptLocale = currentLocale()
    ): JsonObject {
        val function = definition["function"] as? JsonObject ?: return definition
        val parameters = (function["parameters"] as? JsonObject) ?: buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
        }

        val decorated = buildJsonObject {
            definition.forEach { (key, value) ->
                if (key != "function") {
                    put(key, value)
                }
            }
            put(
                "function",
                buildJsonObject {
                    function.forEach { (key, value) ->
                        when (key) {
                            "description" -> put(
                                "description",
                                ensureToolTitleDescription(
                                    value.jsonPrimitive.contentOrNull.orEmpty(),
                                    locale
                                )
                            )

                            "parameters" -> put(
                                "parameters",
                                decorateParameterSchema(parameters, locale)
                            )

                            else -> put(key, value)
                        }
                    }
                    if (function["description"] == null) {
                        put("description", toolTitleRule(locale))
                    }
                    if (function["parameters"] == null) {
                        put("parameters", decorateParameterSchema(parameters, locale))
                    }
                }
            )
        }
        return localizeJsonObject(decorated, locale)
    }

    private fun localizeJsonObject(
        value: JsonObject,
        locale: PromptLocale
    ): JsonObject {
        if (locale == PromptLocale.ZH_CN) {
            return value
        }
        return JsonObject(
            value.mapValues { (_, element) ->
                localizeJsonElement(element, locale)
            }
        )
    }

    private fun localizeJsonElement(
        value: JsonElement,
        locale: PromptLocale
    ): JsonElement {
        if (locale == PromptLocale.ZH_CN) {
            return value
        }
        return when (value) {
            is JsonObject -> localizeJsonObject(value, locale)
            is JsonArray -> JsonArray(value.map { localizeJsonElement(it, locale) })
            is JsonPrimitive -> if (value.isString) {
                JsonPrimitive(localizeLeaf(value.content, locale))
            } else {
                value
            }
        }
    }

    private fun localizeLeaf(
        text: String,
        locale: PromptLocale
    ): String {
        if (locale == PromptLocale.ZH_CN || text.isBlank()) {
            return text
        }
        return englishStringMap[text] ?: text
    }

    private val englishStringMap: Map<String, String> = mapOf(
        "查询已安装应用" to "Query Installed Apps",
        "查询当前时间" to "Query Current Time",
        "视觉执行" to "Vision Task",
        "终端执行" to "Run Terminal Command",
        "启动终端会话" to "Start Terminal Session",
        "执行会话命令" to "Run Session Command",
        "读取会话输出" to "Read Session Output",
        "结束终端会话" to "Stop Terminal Session",
        "浏览器操作" to "Browser Action",
        "读取文件" to "Read File",
        "写入文件" to "Write File",
        "编辑文件" to "Edit File",
        "列出文件" to "List Files",
        "搜索文件" to "Search Files",
        "查看文件信息" to "Inspect File",
        "移动文件" to "Move File",
        "列出 Skills" to "List Skills",
        "读取 Skill" to "Read Skill",
        "创建定时任务" to "Create Scheduled Task",
        "查看定时任务" to "List Scheduled Tasks",
        "修改定时任务" to "Update Scheduled Task",
        "删除定时任务" to "Delete Scheduled Task",
        "创建提醒闹钟" to "Create Reminder Alarm",
        "查看提醒闹钟" to "List Reminder Alarms",
        "删除提醒闹钟" to "Delete Reminder Alarm",
        "查看日历列表" to "List Calendars",
        "创建日程" to "Create Calendar Event",
        "查询日程" to "List Calendar Events",
        "修改日程" to "Update Calendar Event",
        "删除日程" to "Delete Calendar Event",
        "音乐播放控制" to "Music Playback Control",
        "检索记忆" to "Search Memory",
        "写入当日记忆" to "Write Daily Memory",
        "沉淀长期记忆" to "Upsert Long-Term Memory",
        "整理当日记忆" to "Roll Up Daily Memory",
        "分派子任务" to "Dispatch Subtasks",
        "查询设备已安装应用列表。需要应用包名或确认应用是否已安装时优先调用。" to
            "Query the list of apps installed on the device. Prefer this when you need an app package name or need to confirm whether an app is installed.",
        "可选关键词，可匹配应用名或包名。" to
            "Optional keyword filter. Matches app names or package names.",
        "可选，返回数量上限，默认 20，范围 1-100。" to
            "Optional maximum number of results to return. Default 20, range 1-100.",
        "查询当前时间信息。需要日期、时间、时区或星期信息时调用。" to
            "Query current time information. Use this when you need the date, time, timezone, or weekday.",
        "可选 IANA 时区，例如 Asia/Shanghai、America/Los_Angeles。默认使用系统时区。" to
            "Optional IANA timezone, for example Asia/Shanghai or America/Los_Angeles. Uses the system timezone by default.",
        "使用视觉语言模型执行手机屏幕操作任务。该工具会阻塞等待到任务完成、需要用户输入、屏幕锁定或超时，再把终态结果返回给模型。若需要最终整理文本，必须设置 needSummary=true。" to
            "Use a vision-language model to execute an on-device screen task. This tool blocks until the task finishes, needs user input, encounters a locked screen, or times out, and then returns the terminal state. Set `needSummary=true` when you need a final summarized result.",
        "任务目标，使用第一人称描述。" to
            "Task goal written in the first person.",
        "目标应用包名。" to
            "Target app package name.",
        "是否在结束后生成总结。设为 true 时，工具结果会尽量直接返回最终整理文本。" to
            "Whether to generate a summary after completion. When true, the tool result tries to return a final polished summary directly.",
        "仅在用户明确要求从当前页面继续时设为 true。" to
            "Only set this to true when the user explicitly asks to continue from the current screen.",
        "通过应用内置的 Alpine（proot）环境执行一次性的非交互终端命令。这是默认首选的终端工具，适合文件处理、脚本、网络诊断、git、python、包管理等绝大多数 CLI 任务；不用于手机界面操作，也不用于交互式 TUI。只有明确需要跨多轮保留 cwd、环境或后台进程时，才改用 terminal_session_*。" to
            "Run a one-shot non-interactive terminal command inside the app's built-in Alpine (proot) environment. This is the default terminal tool for most CLI work such as file operations, scripts, network diagnostics, git, Python, and package management. It is not for phone UI actions or interactive TUIs. Only switch to `terminal_session_*` when you truly need to preserve cwd, environment, or background state across turns.",
        "terminal_execute 应单独占据当前 tool_calls。该工具会固定在 executionMode=proot（prootDistro=alpine）执行，传入 termux/debian 等参数会被忽略。若执行失败，可在下一轮基于 stdout/stderr/errorMessage 自行决定是否再次显式调用 terminal_execute；不要在同一个 tool_calls 中串联其他结果依赖型工具。" to
            "`terminal_execute` should occupy the current `tool_calls` by itself. It always runs with `executionMode=proot` and `prootDistro=alpine`; values such as termux or debian are ignored. If execution fails, inspect stdout, stderr, or errorMessage in the next turn and decide whether to call it again explicitly. Do not chain other result-dependent tools in the same `tool_calls`.",
        "要执行的单次 shell 命令，必须非交互。" to
            "Single shell command to execute. It must be non-interactive.",
        "可选。兼容字段，当前固定在 proot Alpine 执行，传入 termux 也会被自动忽略。" to
            "Optional compatibility field. Execution is currently always in proot Alpine, and `termux` is ignored.",
        "可选。兼容字段，当前固定使用 alpine，传入其他 distro 会被自动忽略。" to
            "Optional compatibility field. Alpine is always used right now, and other distros are ignored.",
        "可选工作目录，建议使用绝对路径。" to
            "Optional working directory. Prefer an absolute path.",
        "等待结果的超时时间，默认 60 秒，范围 5-300。" to
            "Timeout in seconds while waiting for the result. Default 60, range 5-300.",
        "启动一个可复用的 Alpine 终端会话，仅用于确实需要在后续多轮中保留 cwd、shell 环境、中间文件状态或后台进程的任务。返回的 sessionId 由底层 ReTerminal 原生生成并持久托管，后续必须显式传给 terminal_session_exec/read/stop。不要为了运行单条命令、检查工具是否存在、读取单个文件或执行一次性脚本而使用它，这些场景应优先用 terminal_execute。" to
            "Start a reusable Alpine terminal session. Use it only when later turns truly need to preserve cwd, shell environment, intermediate file state, or background processes. The returned sessionId is generated and managed by the native ReTerminal layer and must be passed explicitly to `terminal_session_exec`, `terminal_session_read`, and `terminal_session_stop`. Do not use it for one-off commands, tool existence checks, reading a single file, or one-shot scripts; prefer `terminal_execute` for those.",
        "启动后等待工具结果，再决定是否继续向该 session 发送命令。" to
            "Wait for the tool result after starting the session before deciding whether to send more commands.",
        "可选，会话名称。未传时自动生成。" to
            "Optional session name. Generated automatically when omitted.",
        "可选，会话初始工作目录。默认使用当前 workspace cwd。" to
            "Optional initial working directory for the session. Defaults to the current workspace cwd.",
        "向已有终端 session 发送一条非交互命令，并等待该命令完成。只在你明确想复用同一个 session 的 cwd、环境变量、后台任务或中间状态时使用。若命令会持续运行很久（例如启动 node/python 服务），应设置较短 timeoutSeconds，让工具尽快返回，再用 terminal_session_read 追踪输出，并在不再需要时调用 terminal_session_stop。" to
            "Send a non-interactive command to an existing terminal session and wait for that command to finish. Use this only when you explicitly want to reuse the same session's cwd, environment variables, background jobs, or intermediate state. If the command may run for a long time, such as starting a node or Python service, use a shorter timeout so the tool returns quickly, then monitor output with `terminal_session_read` and stop the session with `terminal_session_stop` when finished.",
        "执行后等待结果，再判断是否继续读取日志、再次执行或结束 session。" to
            "Wait for the result after execution, then decide whether to read logs, run another command, or stop the session.",
        "terminal_session_start 返回的 sessionId。" to
            "The sessionId returned by `terminal_session_start`.",
        "要执行的单次非交互 shell 命令。" to
            "Single non-interactive shell command to execute.",
        "可选，本次命令执行前要切换到的目录。" to
            "Optional directory to switch into before running this command.",
        "等待该命令完成的超时时间，默认 120 秒，范围 5-600。" to
            "Timeout in seconds while waiting for this command to finish. Default 120, range 5-600.",
        "读取终端 session 最近一次命令日志或最近的终端输出。默认应把它视为读取该 session 最新尾部输出，而不是重新查看最早的历史。只在已经启动并复用了 terminal_session_* 的前提下使用。" to
            "Read the latest command log or most recent terminal output from a terminal session. Treat it as reading the newest tail output for that session, not replaying the oldest history. Use it only after you have already started and are reusing `terminal_session_*`.",
        "读取结果后再决定是否继续执行命令。" to
            "After reading the result, decide whether to run more commands.",
        "最多返回多少字符，默认 4000，范围 256-64000。" to
            "Maximum number of characters to return. Default 4000, range 256-64000.",
        "停止已有终端 session，并清理对应 tmux 会话。完成状态化终端任务后再调用。" to
            "Stop an existing terminal session and clean up the corresponding tmux session. Call this after the stateful terminal task is complete.",
        "结束后等待工具结果，再回复用户。" to
            "Wait for the tool result after stopping the session before replying to the user.",
        "控制一个最多 3 个标签页的离屏浏览器。不要用它打开 App deep link、omnibot:// 非 browser 资源或应用内路由。浏览器只支持访问 http(s) 页面，以及 omnibot://browser/... 资源文件。使用 navigate 打开页面，screenshot 查看当前视口截图（传 read_image=true 可让模型直接看到截图内容），click/type/hover 与元素交互，get_text/get_readable 抽取内容，scroll 导航长页面，scroll_and_collect 在一次调用中滚动并收集无限列表内容，find_elements 发现可交互元素，get_page_info 获取页面元信息，get_backbone 获取 DOM 骨架，execute_js 执行脚本，fetch 复用当前页面 session 下载资源并返回 omnibot://browser/... 产物，new_tab/close_tab/list_tabs 管理标签页，go_back/go_forward 浏览器前进后退，press_key 模拟键盘按键，wait_for_selector 等待元素出现，get_cookies 返回 cookie 摘要与可复用的 offload env 脚本路径，set_user_agent 切换 desktop_safari 或 mobile_safari。tool_title 必须是 5-10 个字的简洁摘要，并使用与用户相同的语言。" to
            "Control an off-screen browser with up to 3 tabs. Do not use it for app deep links, non-browser `omnibot://` resources, or in-app routes. The browser supports http(s) pages and `omnibot://browser/...` resources. Use navigate to open pages, screenshot to capture the current viewport (set read_image=true if the model should inspect the screenshot directly), click/type/hover for interaction, get_text/get_readable for extraction, scroll for long-page navigation, scroll_and_collect to collect infinite-list content in one call, find_elements to discover interactable elements, get_page_info for metadata, get_backbone for a DOM skeleton, execute_js for scripting, fetch to download resources with the current page session and return `omnibot://browser/...` artifacts, new_tab/close_tab/list_tabs for tab management, go_back/go_forward for navigation history, press_key to simulate keys, wait_for_selector to wait for elements, get_cookies for cookie summaries plus a reusable offload env script path, and set_user_agent to switch between desktop_safari and mobile_safari. `tool_title` must be a concise 5-10 word summary in the same language as the user.",
        "本次工具调用要做什么的简洁摘要，5-10 个字，展示给用户。" to
            "A concise summary of what this tool call is doing. Keep it to about 5-10 words and show it to the user.",
        "浏览器动作。" to "Browser action.",
        "navigate 打开的 URL，或 fetch 下载的资源 URL。" to
            "URL to open with navigate, or the resource URL to download with fetch.",
        "CSS selector。适用于 click/type/get_text/scroll/hover/find_elements。" to
            "CSS selector. Used by click, type, get_text, scroll, hover, and find_elements.",
        "type 动作要输入的文本。" to "Text to input for the type action.",
        "execute_js 动作要执行的 JavaScript 代码。" to
            "JavaScript code to execute for the execute_js action.",
        "点击或输入目标的 X 坐标，可替代 selector。" to
            "X coordinate of the click or input target. Can be used instead of selector.",
        "点击或输入目标的 Y 坐标，可替代 selector。" to
            "Y coordinate of the click or input target. Can be used instead of selector.",
        "滚动像素量，默认 500。" to "Scroll amount in pixels. Default 500.",
        "滚动方向。" to "Scroll direction.",
        "目标标签页 ID；不传时默认使用最近活跃标签页。" to
            "Target tab ID. Uses the most recently active tab by default.",
        "scroll_and_collect 的内容项 selector；不传时自动探测。" to
            "Item selector for scroll_and_collect. Auto-detected when omitted.",
        "scroll_and_collect 的滚动次数，默认 10，最大 20。" to
            "Number of scrolls for scroll_and_collect. Default 10, maximum 20.",
        "get_backbone 的最大深度，默认 5。" to
            "Maximum depth for get_backbone. Default 5.",
        "要切换到的 user agent profile。" to
            "User agent profile to switch to.",
        "get_cookies 的 cookie 名过滤关键词。可传空格分隔字符串，兼容数组字符串输入。fuzzy=true 时要求所有关键词都包含在 cookie 名中；fuzzy=false 时要求精确命中任一 cookie 名。" to
            "Keyword filter for cookie names in get_cookies. Accepts a space-separated string and also tolerates array-like string input. When fuzzy=true, every keyword must appear in the cookie name; when fuzzy=false, an exact match of any cookie name is required.",
        "get_cookies 的关键词匹配模式，默认 true。" to
            "Keyword matching mode for get_cookies. Default true.",
        "仅 screenshot 时生效。设为 true 时，截图会以 base64 图片嵌入工具结果，供模型直接分析页面内容。默认 false。" to
            "Only applies to screenshot. When true, the screenshot is embedded as a base64 image in the tool result so the model can analyze the page content directly. Default false.",
        "press_key 动作要模拟的按键名，例如 Enter、Escape、Tab、ArrowDown。" to
            "Key name to simulate for the press_key action, such as Enter, Escape, Tab, or ArrowDown.",
        "wait_for_selector 的超时毫秒数，默认 5000，范围 500-30000。" to
            "Timeout in milliseconds for wait_for_selector. Default 5000, range 500-30000.",
        "读取 workspace 或 Omnibot 白名单目录中的文件内容。" to
            "Read file contents from the workspace or Omnibot allowlisted directories.",
        "文件路径，可使用相对 workspace 路径或 omnibot:// uri。" to
            "File path. May use a workspace-relative path or an `omnibot://` URI.",
        "最多读取字符数，默认 8000，范围 128-64000。" to
            "Maximum number of characters to read. Default 8000, range 128-64000.",
        "可选，从指定字符偏移开始读取。" to
            "Optional character offset to start reading from.",
        "可选，从第几行开始读取，1-based。" to
            "Optional starting line number, 1-based.",
        "可选，读取多少行。" to "Optional number of lines to read.",
        "创建或覆盖 workspace 内文件。新建文件优先使用此工具。" to
            "Create or overwrite a file inside the workspace. Prefer this tool for new files.",
        "写入后等待结果，再决定是否继续读取或修改。" to
            "Wait for the result after writing, then decide whether to keep reading or editing.",
        "目标文件路径。" to "Target file path.",
        "要写入的完整文本内容。" to "Full text content to write.",
        "是否追加写入，默认 false。" to "Whether to append instead of overwrite. Default false.",
        "对已有文件做精确字符串替换。修改现有文件优先使用此工具。" to
            "Perform exact string replacement inside an existing file. Prefer this tool when modifying existing files.",
        "编辑后等待结果，再判断是否继续读取验证。" to
            "Wait for the result after editing, then decide whether to read again for verification.",
        "要替换的原始文本。" to "Original text to replace.",
        "替换后的文本。" to "Replacement text.",
        "是否替换全部匹配，默认 false。" to "Whether to replace all matches. Default false.",
        "列出某个目录下的文件和子目录。" to
            "List files and subdirectories under a directory.",
        "目录路径。默认当前 workspace。" to
            "Directory path. Defaults to the current workspace.",
        "是否递归列出。默认 false。" to "Whether to list recursively. Default false.",
        "递归时最大深度，默认 2，范围 1-6。" to
            "Maximum recursion depth. Default 2, range 1-6.",
        "最多返回多少项，默认 200，范围 1-1000。" to
            "Maximum number of items to return. Default 200, range 1-1000.",
        "在目录中递归搜索文件名或文本内容。" to
            "Recursively search file names or text contents in a directory.",
        "搜索起始目录，默认当前 workspace。" to
            "Search root directory. Defaults to the current workspace.",
        "要搜索的关键词。" to "Keyword to search for.",
        "是否区分大小写，默认 false。" to "Whether the search is case-sensitive. Default false.",
        "最多返回结果数，默认 50，范围 1-200。" to
            "Maximum number of results to return. Default 50, range 1-200.",
        "查看文件或目录的元信息。" to
            "Inspect metadata for a file or directory.",
        "目标路径。" to "Target path.",
        "移动或重命名 workspace 中的文件。" to
            "Move or rename a file inside the workspace.",
        "移动后等待结果，再决定是否继续读取。" to
            "Wait for the result after moving, then decide whether to continue reading.",
        "源路径。" to "Source path.",
        "是否覆盖目标文件，默认 false。" to
            "Whether to overwrite the destination file. Default false.",
        "列出当前可用的 skills 索引，包括 id、名称、路径和能力目录。用户询问有哪些 skills、某类 skill 是否已安装，或你想先查目录再决定读取 SKILL.md 时优先调用。" to
            "List the currently available skills index, including each skill's id, name, path, and capability directories. Prefer this when the user asks what skills are installed, whether a category of skill exists, or when you want to inspect the catalog before deciding whether to read a SKILL.md file.",
        "可选关键词，匹配 skill id、名称、描述或路径。" to
            "Optional keyword filter matching skill id, name, description, or path.",
        "返回数量上限，默认 50，范围 1-200。" to
            "Maximum number of results to return. Default 50, range 1-200.",
        "按 skill id、名称或路径读取某个已安装 skill 的 SKILL.md 正文和相关目录信息。当你知道某个 skill 可能相关，但本轮只掌握索引信息时调用。" to
            "Read the SKILL.md body and related directory information for an installed skill by skill id, name, or path. Use this when a skill looks relevant but you currently know only its index metadata.",
        "读取 skill 后等待结果，再根据返回的正文、scripts、references、assets 路径决定下一步。" to
            "Wait for the result after reading the skill, then decide the next step based on the returned body plus any scripts, references, or asset paths.",
        "skill 的 id、名称、SKILL.md 路径或 skill 根目录路径。建议先用 skills_list 查看。" to
            "Skill id, skill name, SKILL.md path, or the skill root directory path. Prefer checking with skills_list first.",
        "最多返回多少字符的正文，默认 16000，范围 512-64000。" to
            "Maximum number of body characters to return. Default 16000, range 512-64000.",
        "创建新的定时任务。执行后等待工具结果，再决定是否回复用户。" to
            "Create a new scheduled task. Wait for the tool result before deciding how to reply to the user.",
        "创建完成后不要在同一轮继续调用其他工具；请等待工具结果，并通过 response 输出最终答复。" to
            "After creating the task, do not call more tools in the same turn. Wait for the tool result and then provide the final response.",
        "查看当前已有的定时任务列表。执行后等待工具结果。" to
            "View the current list of scheduled tasks. Wait for the tool result after calling it.",
        "查看结果后再决定是否需要修改、删除或向用户总结。" to
            "Review the result first, then decide whether to update, delete, or summarize for the user.",
        "修改已有定时任务的时间、标题、每日重复或启停状态。" to
            "Update the time, title, daily repeat rule, or enabled state of an existing scheduled task.",
        "修改完成后不要同轮回复，等待工具结果。" to
            "Do not reply in the same turn after updating. Wait for the tool result.",
        "删除已有定时任务。执行后等待工具结果。" to
            "Delete an existing scheduled task. Wait for the tool result after calling it.",
        "删除完成后等待工具结果，再输出最终回复。" to
            "Wait for the tool result after deleting, then produce the final reply.",
        "创建提醒闹钟。exact_alarm 模式使用 AlarmManager 精确提醒；clock_app 模式调用系统闹钟应用创建闹钟；若用户未明确指定，优先使用 exact_alarm。用于单纯提醒，不执行自动化任务。" to
            "Create a reminder alarm. The exact_alarm mode uses AlarmManager for precise reminders, while clock_app uses the system clock app to create an alarm. If the user does not specify a mode, prefer exact_alarm. This is for reminders only and does not execute automation tasks.",
        "创建后等待工具结果，再决定是否继续。" to
            "Wait for the tool result after creation before deciding what to do next.",
        "闹钟模式：exact_alarm=应用内精确提醒；clock_app=系统闹钟。" to
            "Alarm mode: exact_alarm = precise in-app reminder; clock_app = system alarm.",
        "提醒标题。" to "Reminder title.",
        "触发时间，ISO-8601 格式，例如 2026-03-17T21:30:00+08:00。" to
            "Trigger time in ISO-8601 format, for example 2026-03-17T21:30:00+08:00.",
        "可选提醒内容。" to "Optional reminder content.",
        "可选 IANA 时区，未传默认系统时区。" to
            "Optional IANA timezone. Uses the system timezone when omitted.",
        "仅 exact_alarm 模式生效，是否在待机时也精确触发。默认 true。" to
            "Only applies in exact_alarm mode. Whether the reminder should remain precise while idle. Default true.",
        "仅 clock_app 模式生效，是否尝试跳过系统闹钟界面。默认 false。" to
            "Only applies in clock_app mode. Whether to try skipping the system alarm UI. Default false.",
        "查看由本应用创建并托管的 exact_alarm 提醒闹钟列表。" to
            "List exact_alarm reminders created and managed by this app.",
        "查看结果后再决定是否删除或继续创建。" to
            "Review the result before deciding whether to delete or create more reminders.",
        "按 alarmId 删除本应用创建并托管的 exact_alarm 提醒闹钟。" to
            "Delete an exact_alarm reminder created and managed by this app by alarmId.",
        "删除后等待工具结果，再向用户确认。" to
            "Wait for the tool result after deleting, then confirm with the user.",
        "闹钟 ID。" to "Alarm ID.",
        "查询设备日历账户列表，可用于选择 calendarId。" to
            "Query the device's calendar accounts so the agent can choose a calendarId.",
        "查看结果后再决定新建或管理日程。" to
            "Review the result before deciding whether to create or manage events.",
        "是否仅返回可写日历。默认 true。" to
            "Whether to return only writable calendars. Default true.",
        "是否仅返回可见日历。默认 true。" to
            "Whether to return only visible calendars. Default true.",
        "创建日历事件。用于管理日程，不触发自动化任务。" to
            "Create a calendar event. This manages schedules and does not trigger automation tasks.",
        "创建后等待工具结果，再向用户确认。" to
            "Wait for the tool result after creating, then confirm with the user.",
        "开始时间，ISO-8601。" to "Start time in ISO-8601 format.",
        "结束时间，ISO-8601。" to "End time in ISO-8601 format.",
        "可选，目标日历 ID。" to "Optional target calendar ID.",
        "提醒分钟列表，例如 [10, 30]。" to
            "Reminder minute offsets, for example [10, 30].",
        "按时间范围、关键字、calendarId 查询日历事件。" to
            "Query calendar events by time range, keyword, and calendarId.",
        "查看结果后再决定是否更新或删除。" to
            "Review the result first, then decide whether to update or delete.",
        "可选，查询起始时间，ISO-8601。" to "Optional query start time in ISO-8601 format.",
        "可选，查询结束时间，ISO-8601。" to "Optional query end time in ISO-8601 format.",
        "可选关键词，匹配标题或地点。" to
            "Optional keyword matching title or location.",
        "可选返回上限，默认 50，范围 1-200。" to
            "Optional maximum number of results to return. Default 50, range 1-200.",
        "按 eventId 修改日历事件。" to "Update a calendar event by eventId.",
        "修改后等待工具结果，再向用户同步。" to
            "Wait for the tool result after updating, then sync the result back to the user.",
        "事件 ID。" to "Event ID.",
        "按 eventId 删除日历事件。" to "Delete a calendar event by eventId.",
        "控制安卓系统级音乐播放。action=play 且提供 source 时，会由应用前台媒体会话播放本地文件、omnibot workspace/public 文件、file/content Uri 或 http(s) 直链音频；play 不提供 source 时，退化为向系统当前播放器发送播放媒体键。pause/resume/stop/next/previous 会优先控制当前由本应用托管的音频播放，若没有本地会话则退化为发送系统媒体键；seek 和 status 仅针对本应用托管的播放会话。" to
            "Control Android system-level music playback. When action=play and source is provided, the app's foreground media session plays local files, Omnibot workspace/public files, file/content URIs, or direct http(s) audio links. If play is called without a source, it falls back to sending a play media key to the current system player. pause/resume/stop/next/previous prefer the playback session hosted by this app and fall back to system media keys when no local session exists. seek and status only apply to playback sessions hosted by this app.",
        "执行后等待工具结果，再决定是否继续调整播放。" to
            "Wait for the tool result after execution before deciding whether to keep adjusting playback.",
        "要执行的播放控制动作。" to "Playback control action to perform.",
        "仅 play 时可选。支持 omnibot://、/workspace、/storage、相对 workspace 路径、file://、content://、http(s) 直链。留空表示只向系统发送播放媒体键。" to
            "Optional for play only. Supports omnibot://, /workspace, /storage, workspace-relative paths, file://, content://, and direct http(s) links. Leave empty to send only the play media key to the system.",
        "仅 play 时可选，前台通知与系统媒体会话里显示的标题。" to
            "Optional for play only. Title shown in the foreground notification and the system media session.",
        "仅 play 时可选，是否循环播放。默认 false。" to
            "Optional for play only. Whether to loop playback. Default false.",
        "仅 seek 时使用，目标播放秒数。" to
            "Used only for seek. Target playback position in seconds.",
        "在 workspace 记忆中检索与当前问题相关的长期/短期记忆。优先使用向量召回，配置缺失时自动降级词法检索。" to
            "Search long-term and short-term workspace memory relevant to the current question. Prefer vector retrieval and automatically fall back to lexical retrieval when configuration is missing.",
        "读取结果后再决定是否写入新的短期或长期记忆。" to
            "Review the result first, then decide whether to write new short-term or long-term memory.",
        "检索语句。" to "Search query.",
        "返回条数上限，默认 8，范围 1-20。" to
            "Maximum number of hits to return. Default 8, range 1-20.",
        "将当轮过程性信息写入 `.omnibot/memory/short-memories/YY-MM-DD.md`。" to
            "Write short-term process information from this turn into `.omnibot/memory/short-memories/YY-MM-DD.md`.",
        "写入成功后再继续执行其他步骤。" to
            "Continue with later steps only after the write succeeds.",
        "要写入的短期记忆文本。" to "Short-term memory text to write.",
        "将稳定偏好、长期约束、身份事实写入 `.omnibot/memory/MEMORY.md`。自动去重相同条目。" to
            "Write stable preferences, long-term constraints, and identity facts into `.omnibot/memory/MEMORY.md`. Duplicate entries are removed automatically.",
        "要沉淀的长期记忆内容。" to "Long-term memory content to preserve.",
        "写入后等待工具结果，再向用户确认。" to
            "Wait for the tool result after writing, then confirm with the user.",
        "整理某一天短期记忆并按策略沉淀到长期记忆。默认整理今天。" to
            "Roll up one day's short-term memory and promote selected items into long-term memory according to policy. Defaults to today.",
        "整理后等待工具结果，再决定是否补充长期记忆。" to
            "Wait for the tool result after the rollup, then decide whether to add more long-term memory.",
        "可选日期，格式 YYYY-MM-DD。" to "Optional date in YYYY-MM-DD format.",
        "把多个可并行的小任务分派给 subagent 集群执行，并返回聚合结果。" to
            "Dispatch multiple parallelizable subtasks to the subagent cluster and return the aggregated result.",
        "分派后等待工具结果，再汇总给用户。" to
            "Wait for the tool result after dispatching, then summarize it for the user.",
        "需要并行执行的子任务列表。" to "List of subtasks to execute in parallel.",
        "并发度，默认 2，范围 1-6。" to "Concurrency level. Default 2, range 1-6.",
        "结果聚合要求，可选。" to "Optional instructions for result aggregation."
    )

    val contextAppsQueryTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "context_apps_query")
            put("displayName", "查询已安装应用")
            put("toolType", "builtin")
            put("description", "查询设备已安装应用列表。需要应用包名或确认应用是否已安装时优先调用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "可选关键词，可匹配应用名或包名。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选，返回数量上限，默认 20，范围 1-100。")
                    }
                }
            }
        }
    }

    val contextTimeNowTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "context_time_now")
            put("displayName", "查询当前时间")
            put("toolType", "builtin")
            put("description", "查询当前时间信息。需要日期、时间、时区或星期信息时调用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("timezone") {
                        put("type", "string")
                        put("description", "可选 IANA 时区，例如 Asia/Shanghai、America/Los_Angeles。默认使用系统时区。")
                    }
                }
            }
        }
    }

    val vlmTaskTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "vlm_task")
            put("displayName", "视觉执行")
            put("toolType", "builtin")
            put(
                "description",
                "使用视觉语言模型执行手机屏幕操作任务。该工具会阻塞等待到任务完成、需要用户输入、屏幕锁定或超时，再把终态结果返回给模型。若需要最终整理文本，必须设置 needSummary=true。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("goal") {
                        put("type", "string")
                        put("description", "任务目标，使用第一人称描述。")
                    }
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "目标应用包名。")
                    }
                    putJsonObject("needSummary") {
                        put("type", "boolean")
                        put("description", "是否在结束后生成总结。设为 true 时，工具结果会尽量直接返回最终整理文本。")
                    }
                    putJsonObject("startFromCurrent") {
                        put("type", "boolean")
                        put("description", "仅在用户明确要求从当前页面继续时设为 true。")
                    }
                }
                putJsonArray("required") {
                    add("goal")
                }
            }
        }
    }

    val terminalExecuteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_execute")
            put("displayName", "终端执行")
            put("toolType", "terminal")
            put(
                "description",
                "通过应用内置的 Alpine（proot）环境执行一次性的非交互终端命令。这是默认首选的终端工具，适合文件处理、脚本、网络诊断、git、python、包管理等绝大多数 CLI 任务；不用于手机界面操作，也不用于交互式 TUI。只有明确需要跨多轮保留 cwd、环境或后台进程时，才改用 terminal_session_*。"
            )
            put(
                "postToolRule",
                "terminal_execute 应单独占据当前 tool_calls。该工具会固定在 executionMode=proot（prootDistro=alpine）执行，传入 termux/debian 等参数会被忽略。若执行失败，可在下一轮基于 stdout/stderr/errorMessage 自行决定是否再次显式调用 terminal_execute；不要在同一个 tool_calls 中串联其他结果依赖型工具。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "要执行的单次 shell 命令，必须非交互。")
                    }
                    putJsonObject("executionMode") {
                        put("type", "string")
                        put("description", "可选。兼容字段，当前固定在 proot Alpine 执行，传入 termux 也会被自动忽略。")
                        putJsonArray("enum") {
                            add("proot")
                            add("termux")
                        }
                    }
                    putJsonObject("prootDistro") {
                        put("type", "string")
                        put("description", "可选。兼容字段，当前固定使用 alpine，传入其他 distro 会被自动忽略。")
                    }
                    putJsonObject("workingDirectory") {
                        put("type", "string")
                        put("description", "可选工作目录，建议使用绝对路径。")
                    }
                    putJsonObject("timeoutSeconds") {
                        put("type", "integer")
                        put("description", "等待结果的超时时间，默认 60 秒，范围 5-300。")
                    }
                }
                putJsonArray("required") {
                    add("command")
                }
            }
        }
    }

    val terminalSessionStartTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_start")
            put("displayName", "启动终端会话")
            put("toolType", "terminal")
            put("description", "启动一个可复用的 Alpine 终端会话，仅用于确实需要在后续多轮中保留 cwd、shell 环境、中间文件状态或后台进程的任务。返回的 sessionId 由底层 ReTerminal 原生生成并持久托管，后续必须显式传给 terminal_session_exec/read/stop。不要为了运行单条命令、检查工具是否存在、读取单个文件或执行一次性脚本而使用它，这些场景应优先用 terminal_execute。")
            put("postToolRule", "启动后等待工具结果，再决定是否继续向该 session 发送命令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionName") {
                        put("type", "string")
                        put("description", "可选，会话名称。未传时自动生成。")
                    }
                    putJsonObject("workingDirectory") {
                        put("type", "string")
                        put("description", "可选，会话初始工作目录。默认使用当前 workspace cwd。")
                    }
                }
            }
        }
    }

    val terminalSessionExecTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_exec")
            put("displayName", "执行会话命令")
            put("toolType", "terminal")
            put("description", "向已有终端 session 发送一条非交互命令，并等待该命令完成。只在你明确想复用同一个 session 的 cwd、环境变量、后台任务或中间状态时使用。若命令会持续运行很久（例如启动 node/python 服务），应设置较短 timeoutSeconds，让工具尽快返回，再用 terminal_session_read 追踪输出，并在不再需要时调用 terminal_session_stop。")
            put("postToolRule", "执行后等待结果，再判断是否继续读取日志、再次执行或结束 session。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", "terminal_session_start 返回的 sessionId。")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "要执行的单次非交互 shell 命令。")
                    }
                    putJsonObject("workingDirectory") {
                        put("type", "string")
                        put("description", "可选，本次命令执行前要切换到的目录。")
                    }
                    putJsonObject("timeoutSeconds") {
                        put("type", "integer")
                        put("description", "等待该命令完成的超时时间，默认 120 秒，范围 5-600。")
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                    add("command")
                }
            }
        }
    }

    val terminalSessionReadTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_read")
            put("displayName", "读取会话输出")
            put("toolType", "terminal")
            put("description", "读取终端 session 最近一次命令日志或最近的终端输出。默认应把它视为读取该 session 最新尾部输出，而不是重新查看最早的历史。只在已经启动并复用了 terminal_session_* 的前提下使用。")
            put("postToolRule", "读取结果后再决定是否继续执行命令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", "terminal session id。")
                    }
                    putJsonObject("maxChars") {
                        put("type", "integer")
                        put("description", "最多返回多少字符，默认 4000，范围 256-64000。")
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                }
            }
        }
    }

    val terminalSessionStopTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_stop")
            put("displayName", "结束终端会话")
            put("toolType", "terminal")
            put("description", "停止已有终端 session，并清理对应 tmux 会话。完成状态化终端任务后再调用。")
            put("postToolRule", "结束后等待工具结果，再回复用户。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", "terminal session id。")
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                }
            }
        }
    }

    val browserUseTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "browser_use")
            put("displayName", "浏览器操作")
            put("toolType", "browser")
            put(
                "description",
                "控制一个最多 3 个标签页的离屏浏览器。不要用它打开 App deep link、omnibot:// 非 browser 资源或应用内路由。浏览器只支持访问 http(s) 页面，以及 omnibot://browser/... 资源文件。使用 navigate 打开页面，screenshot 查看当前视口截图（传 read_image=true 可让模型直接看到截图内容），click/type/hover 与元素交互，get_text/get_readable 抽取内容，scroll 导航长页面，scroll_and_collect 在一次调用中滚动并收集无限列表内容，find_elements 发现可交互元素，get_page_info 获取页面元信息，get_backbone 获取 DOM 骨架，execute_js 执行脚本，fetch 复用当前页面 session 下载资源并返回 omnibot://browser/... 产物，new_tab/close_tab/list_tabs 管理标签页，go_back/go_forward 浏览器前进后退，press_key 模拟键盘按键，wait_for_selector 等待元素出现，get_cookies 返回 cookie 摘要与可复用的 offload env 脚本路径，set_user_agent 切换 desktop_safari 或 mobile_safari。tool_title 必须是 5-10 个字的简洁摘要，并使用与用户相同的语言。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tool_title") {
                        put("type", "string")
                        put("description", "本次工具调用要做什么的简洁摘要，5-10 个字，展示给用户。")
                    }
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "浏览器动作。")
                        putJsonArray("enum") {
                            add("navigate")
                            add("screenshot")
                            add("click")
                            add("type")
                            add("get_text")
                            add("scroll")
                            add("get_page_info")
                            add("execute_js")
                            add("find_elements")
                            add("hover")
                            add("get_readable")
                            add("set_user_agent")
                            add("get_backbone")
                            add("fetch")
                            add("new_tab")
                            add("close_tab")
                            add("list_tabs")
                            add("get_cookies")
                            add("scroll_and_collect")
                            add("go_back")
                            add("go_forward")
                            add("press_key")
                            add("wait_for_selector")
                        }
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "navigate 打开的 URL，或 fetch 下载的资源 URL。")
                    }
                    putJsonObject("selector") {
                        put("type", "string")
                        put("description", "CSS selector。适用于 click/type/get_text/scroll/hover/find_elements。")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "type 动作要输入的文本。")
                    }
                    putJsonObject("script") {
                        put("type", "string")
                        put("description", "execute_js 动作要执行的 JavaScript 代码。")
                    }
                    putJsonObject("coordinate_x") {
                        put("type", "integer")
                        put("description", "点击或输入目标的 X 坐标，可替代 selector。")
                    }
                    putJsonObject("coordinate_y") {
                        put("type", "integer")
                        put("description", "点击或输入目标的 Y 坐标，可替代 selector。")
                    }
                    putJsonObject("amount") {
                        put("type", "integer")
                        put("description", "滚动像素量，默认 500。")
                    }
                    putJsonObject("direction") {
                        put("type", "string")
                        put("description", "滚动方向。")
                        putJsonArray("enum") {
                            add("up")
                            add("down")
                        }
                    }
                    putJsonObject("tab_id") {
                        put("type", "integer")
                        put("description", "目标标签页 ID；不传时默认使用最近活跃标签页。")
                    }
                    putJsonObject("item_selector") {
                        put("type", "string")
                        put("description", "scroll_and_collect 的内容项 selector；不传时自动探测。")
                    }
                    putJsonObject("scroll_count") {
                        put("type", "integer")
                        put("description", "scroll_and_collect 的滚动次数，默认 10，最大 20。")
                    }
                    putJsonObject("max_depth") {
                        put("type", "integer")
                        put("description", "get_backbone 的最大深度，默认 5。")
                    }
                    putJsonObject("user_agent") {
                        put("type", "string")
                        put("description", "要切换到的 user agent profile。")
                        putJsonArray("enum") {
                            add("desktop_safari")
                            add("mobile_safari")
                        }
                    }
                    putJsonObject("keywords") {
                        put(
                            "description",
                            "get_cookies 的 cookie 名过滤关键词。可传空格分隔字符串，兼容数组字符串输入。fuzzy=true 时要求所有关键词都包含在 cookie 名中；fuzzy=false 时要求精确命中任一 cookie 名。"
                        )
                    }
                    putJsonObject("fuzzy") {
                        put("type", "boolean")
                        put("description", "get_cookies 的关键词匹配模式，默认 true。")
                    }
                    putJsonObject("read_image") {
                        put("type", "boolean")
                        put("description", "仅 screenshot 时生效。设为 true 时，截图会以 base64 图片嵌入工具结果，供模型直接分析页面内容。默认 false。")
                    }
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "press_key 动作要模拟的按键名，例如 Enter、Escape、Tab、ArrowDown。")
                    }
                    putJsonObject("timeout_ms") {
                        put("type", "integer")
                        put("description", "wait_for_selector 的超时毫秒数，默认 5000，范围 500-30000。")
                    }
                }
                putJsonArray("required") {
                    add("tool_title")
                    add("action")
                }
            }
        }
    }

    val fileReadTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_read")
            put("displayName", "读取文件")
            put("toolType", "workspace")
            put("description", "读取 workspace 或 Omnibot 白名单目录中的文件内容。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "文件路径，可使用相对 workspace 路径或 omnibot:// uri。")
                    }
                    putJsonObject("maxChars") {
                        put("type", "integer")
                        put("description", "最多读取字符数，默认 8000，范围 128-64000。")
                    }
                    putJsonObject("offset") {
                        put("type", "integer")
                        put("description", "可选，从指定字符偏移开始读取。")
                    }
                    putJsonObject("lineStart") {
                        put("type", "integer")
                        put("description", "可选，从第几行开始读取，1-based。")
                    }
                    putJsonObject("lineCount") {
                        put("type", "integer")
                        put("description", "可选，读取多少行。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                }
            }
        }
    }

    val fileWriteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_write")
            put("displayName", "写入文件")
            put("toolType", "workspace")
            put("description", "创建或覆盖 workspace 内文件。新建文件优先使用此工具。")
            put("postToolRule", "写入后等待结果，再决定是否继续读取或修改。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目标文件路径。")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "要写入的完整文本内容。")
                    }
                    putJsonObject("append") {
                        put("type", "boolean")
                        put("description", "是否追加写入，默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                    add("content")
                }
            }
        }
    }

    val fileEditTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_edit")
            put("displayName", "编辑文件")
            put("toolType", "workspace")
            put("description", "对已有文件做精确字符串替换。修改现有文件优先使用此工具。")
            put("postToolRule", "编辑后等待结果，再判断是否继续读取验证。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目标文件路径。")
                    }
                    putJsonObject("oldText") {
                        put("type", "string")
                        put("description", "要替换的原始文本。")
                    }
                    putJsonObject("newText") {
                        put("type", "string")
                        put("description", "替换后的文本。")
                    }
                    putJsonObject("replaceAll") {
                        put("type", "boolean")
                        put("description", "是否替换全部匹配，默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                    add("oldText")
                    add("newText")
                }
            }
        }
    }

    val fileListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_list")
            put("displayName", "列出文件")
            put("toolType", "workspace")
            put("description", "列出某个目录下的文件和子目录。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目录路径。默认当前 workspace。")
                    }
                    putJsonObject("recursive") {
                        put("type", "boolean")
                        put("description", "是否递归列出。默认 false。")
                    }
                    putJsonObject("maxDepth") {
                        put("type", "integer")
                        put("description", "递归时最大深度，默认 2，范围 1-6。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "最多返回多少项，默认 200，范围 1-1000。")
                    }
                }
            }
        }
    }

    val fileSearchTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_search")
            put("displayName", "搜索文件")
            put("toolType", "workspace")
            put("description", "在目录中递归搜索文件名或文本内容。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "搜索起始目录，默认当前 workspace。")
                    }
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "要搜索的关键词。")
                    }
                    putJsonObject("caseSensitive") {
                        put("type", "boolean")
                        put("description", "是否区分大小写，默认 false。")
                    }
                    putJsonObject("maxResults") {
                        put("type", "integer")
                        put("description", "最多返回结果数，默认 50，范围 1-200。")
                    }
                }
                putJsonArray("required") {
                    add("query")
                }
            }
        }
    }

    val fileStatTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_stat")
            put("displayName", "查看文件信息")
            put("toolType", "workspace")
            put("description", "查看文件或目录的元信息。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目标路径。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                }
            }
        }
    }

    val fileMoveTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_move")
            put("displayName", "移动文件")
            put("toolType", "workspace")
            put("description", "移动或重命名 workspace 中的文件。")
            put("postToolRule", "移动后等待结果，再决定是否继续读取。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sourcePath") {
                        put("type", "string")
                        put("description", "源路径。")
                    }
                    putJsonObject("targetPath") {
                        put("type", "string")
                        put("description", "目标路径。")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "是否覆盖目标文件，默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("sourcePath")
                    add("targetPath")
                }
            }
        }
    }

    val skillsListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "skills_list")
            put("displayName", "列出 Skills")
            put("toolType", "skill")
            put("description", "列出当前可用的 skills 索引，包括 id、名称、路径和能力目录。用户询问有哪些 skills、某类 skill 是否已安装，或你想先查目录再决定读取 SKILL.md 时优先调用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "可选关键词，匹配 skill id、名称、描述或路径。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "返回数量上限，默认 50，范围 1-200。")
                    }
                }
            }
        }
    }

    val skillsReadTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "skills_read")
            put("displayName", "读取 Skill")
            put("toolType", "skill")
            put("description", "按 skill id、名称或路径读取某个已安装 skill 的 SKILL.md 正文和相关目录信息。当你知道某个 skill 可能相关，但本轮只掌握索引信息时调用。")
            put("postToolRule", "读取 skill 后等待结果，再根据返回的正文、scripts、references、assets 路径决定下一步。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("skillId") {
                        put("type", "string")
                        put("description", "skill 的 id、名称、SKILL.md 路径或 skill 根目录路径。建议先用 skills_list 查看。")
                    }
                    putJsonObject("maxChars") {
                        put("type", "integer")
                        put("description", "最多返回多少字符的正文，默认 16000，范围 512-64000。")
                    }
                }
                putJsonArray("required") {
                    add("skillId")
                }
            }
        }
    }

    val workbenchProjectCreateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_project_create")
            put("displayName", "创建 Workbench Project")
            put("toolType", "workbench")
            put("description", "调用 OOB 内置 Workbench Project 创建接口。Project 创建是控制面能力，不会出现在 Project 自己的业务 API 列表里。v1 支持 templateId=todo_log_demo；不要直接写 registry 文件，也不要生成 HTML/WebView。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Project id，例如 oob-workbench-todo-log。只能包含字母、数字、下划线和连字符。")
                    }
                    putJsonObject("templateId") {
                        put("type", "string")
                        put("description", "Project 模板 id。v1 只支持 todo_log_demo。")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Project 展示名称。")
                    }
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "用户原始需求。Workbench 会把它写入 Project 源码规格，用于后续编辑和拆分复盘。")
                    }
                    putJsonObject("initialTodos") {
                        put("type", "array")
                        put("description", "可选初始 todo 标题数组，仅 todo_log_demo 使用。")
                    }
                }
                putJsonArray("required") {
                    add("projectId")
                    add("templateId")
                }
            }
        }
    }

    val workbenchApiListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_api_list")
            put("displayName", "列出 Workbench API")
            put("toolType", "workbench")
            put("description", "列出已注册的 Project 业务 API。返回的是 Project API Registry，不包含 workbench_project_create 这类 OOB 控制面接口。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "可选 Project id；为空时返回所有 Workbench API。")
                    }
                }
            }
        }
    }

    val workbenchProjectListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_project_list")
            put("displayName", "列出 Workbench Project")
            put("toolType", "workbench")
            put("description", "列出 OOB Workbench 已注册 Project。它是 OOB 控制面能力，用于管理已有 Project，不属于 Project API Registry。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    val workbenchProjectGetTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_project_get")
            put("displayName", "读取 Workbench Project")
            put("toolType", "workbench")
            put("description", "读取某个 OOB Workbench Project 的注册信息、业务 API 和当前持久化状态。打开或管理已有 Project 前应先调用它。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Project id。")
                    }
                }
                putJsonArray("required") {
                    add("projectId")
                }
            }
        }
    }

    val workbenchApiCallTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_api_call")
            put("displayName", "调用 Workbench API")
            put("toolType", "workbench")
            put("description", "调用某个 Project 已注册的业务 API。AI 层必须通过这个接口调用 Project API，和前端 Tool List 使用同一个 native executor。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Project id。")
                    }
                    putJsonObject("apiId") {
                        put("type", "string")
                        put("description", "业务 API id，例如 todo.add 或 todo.finish。")
                    }
                    putJsonObject("inputs") {
                        put("type", "object")
                        put("description", "API 输入对象。例如 todo.add 需要 {title}，todo.finish 需要 {todo_id}。")
                    }
                }
                putJsonArray("required") {
                    add("projectId")
                    add("apiId")
                    add("inputs")
                }
            }
        }
    }

    val workbenchProjectExportTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_project_export")
            put("displayName", "导出 Workbench Project")
            put("toolType", "workbench")
            put("description", "把某个 Workbench Project 注册成可分发包并导出 zip。导出内容包括 Project 记录、业务 API、Workspace 项目文件、持久化数据、API 调用日志和内置 skill 契约。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Project id。")
                    }
                }
                putJsonArray("required") {
                    add("projectId")
                }
            }
        }
    }

    val workbenchProjectOpenTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_project_open")
            put("displayName", "打开 Workbench Project")
            put("toolType", "workbench")
            put("description", "打开某个 Workbench Project 的 OOB 原生页面。用于完成 Project 创建和 API 调用后把结果展示给用户。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Project id。")
                    }
                }
                putJsonArray("required") {
                    add("projectId")
                }
            }
        }
    }

    val workbenchProjectDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "workbench_project_delete")
            put("displayName", "删除 Workbench Project")
            put("toolType", "workbench")
            put("description", "删除某个 Workbench Project 的 OOB 注册记录、业务 API 注册记录和 Workspace 项目文件。它是 OOB 控制面能力，不属于 Project API Registry。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Project id。")
                    }
                }
                putJsonArray("required") {
                    add("projectId")
                }
            }
        }
    }

    val scheduleTaskCreateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_create")
            put("displayName", "创建定时任务")
            put("toolType", "schedule")
            put("description", "创建新的定时任务。执行后等待工具结果，再决定是否回复用户。")
            put("postToolRule", "创建完成后不要在同一轮继续调用其他工具；请等待工具结果，并通过 response 输出最终答复。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("targetKind") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("vlm")
                            add("subagent")
                        }
                    }
                    putJsonObject("goal") { put("type", "string") }
                    putJsonObject("packageName") { put("type", "string") }
                    putJsonObject("subagentConversationId") { put("type", "string") }
                    putJsonObject("subagentPrompt") { put("type", "string") }
                    putJsonObject("notificationEnabled") { put("type", "boolean") }
                    putJsonObject("scheduleType") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("fixed_time")
                            add("countdown")
                        }
                    }
                    putJsonObject("fixedTime") { put("type", "string") }
                    putJsonObject("countdownMinutes") { put("type", "integer") }
                    putJsonObject("repeatDaily") { put("type", "boolean") }
                    putJsonObject("enabled") { put("type", "boolean") }
                }
                putJsonArray("required") {
                    add("title")
                    add("targetKind")
                    add("scheduleType")
                    add("repeatDaily")
                }
            }
        }
    }

    val scheduleTaskListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_list")
            put("displayName", "查看定时任务")
            put("toolType", "schedule")
            put("description", "查看当前已有的定时任务列表。执行后等待工具结果。")
            put("postToolRule", "查看结果后再决定是否需要修改、删除或向用户总结。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    val scheduleTaskUpdateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_update")
            put("displayName", "修改定时任务")
            put("toolType", "schedule")
            put("description", "修改已有定时任务的时间、标题、每日重复或启停状态。")
            put("postToolRule", "修改完成后不要同轮回复，等待工具结果。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("taskId") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("targetKind") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("vlm")
                            add("subagent")
                        }
                    }
                    putJsonObject("fixedTime") { put("type", "string") }
                    putJsonObject("countdownMinutes") { put("type", "integer") }
                    putJsonObject("repeatDaily") { put("type", "boolean") }
                    putJsonObject("enabled") { put("type", "boolean") }
                    putJsonObject("subagentConversationId") { put("type", "string") }
                    putJsonObject("subagentPrompt") { put("type", "string") }
                    putJsonObject("notificationEnabled") { put("type", "boolean") }
                }
                putJsonArray("required") {
                    add("taskId")
                }
            }
        }
    }

    val scheduleTaskDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_delete")
            put("displayName", "删除定时任务")
            put("toolType", "schedule")
            put("description", "删除已有定时任务。执行后等待工具结果。")
            put("postToolRule", "删除完成后等待工具结果，再输出最终回复。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("taskId") { put("type", "string") }
                }
                putJsonArray("required") {
                    add("taskId")
                }
            }
        }
    }

    val alarmReminderCreateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "alarm_reminder_create")
            put("displayName", "创建提醒闹钟")
            put("toolType", "alarm")
            put(
                "description",
                "创建提醒闹钟。exact_alarm 模式使用 AlarmManager 精确提醒；clock_app 模式调用系统闹钟应用创建闹钟；若用户未明确指定，优先使用 exact_alarm。用于单纯提醒，不执行自动化任务。"
            )
            put("postToolRule", "创建后等待工具结果，再决定是否继续。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("mode") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("exact_alarm")
                            add("clock_app")
                        }
                        put("description", "闹钟模式：exact_alarm=应用内精确提醒；clock_app=系统闹钟。")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "提醒标题。")
                    }
                    putJsonObject("triggerAt") {
                        put("type", "string")
                        put("description", "触发时间，ISO-8601 格式，例如 2026-03-17T21:30:00+08:00。")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "可选提醒内容。")
                    }
                    putJsonObject("timezone") {
                        put("type", "string")
                        put("description", "可选 IANA 时区，未传默认系统时区。")
                    }
                    putJsonObject("allowWhileIdle") {
                        put("type", "boolean")
                        put("description", "仅 exact_alarm 模式生效，是否在待机时也精确触发。默认 true。")
                    }
                    putJsonObject("skipUi") {
                        put("type", "boolean")
                        put("description", "仅 clock_app 模式生效，是否尝试跳过系统闹钟界面。默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("mode")
                    add("title")
                    add("triggerAt")
                }
            }
        }
    }

    val alarmReminderListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "alarm_reminder_list")
            put("displayName", "查看提醒闹钟")
            put("toolType", "alarm")
            put("description", "查看由本应用创建并托管的 exact_alarm 提醒闹钟列表。")
            put("postToolRule", "查看结果后再决定是否删除或继续创建。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    val alarmReminderDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "alarm_reminder_delete")
            put("displayName", "删除提醒闹钟")
            put("toolType", "alarm")
            put("description", "按 alarmId 删除本应用创建并托管的 exact_alarm 提醒闹钟。")
            put("postToolRule", "删除后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("alarmId") {
                        put("type", "string")
                        put("description", "闹钟 ID。")
                    }
                }
                putJsonArray("required") {
                    add("alarmId")
                }
            }
        }
    }

    val calendarListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_list")
            put("displayName", "查看日历列表")
            put("toolType", "calendar")
            put("description", "查询设备日历账户列表，可用于选择 calendarId。")
            put("postToolRule", "查看结果后再决定新建或管理日程。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("writableOnly") {
                        put("type", "boolean")
                        put("description", "是否仅返回可写日历。默认 true。")
                    }
                    putJsonObject("visibleOnly") {
                        put("type", "boolean")
                        put("description", "是否仅返回可见日历。默认 true。")
                    }
                }
            }
        }
    }

    val calendarEventCreateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_create")
            put("displayName", "创建日程")
            put("toolType", "calendar")
            put("description", "创建日历事件。用于管理日程，不触发自动化任务。")
            put("postToolRule", "创建后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("startAt") {
                        put("type", "string")
                        put("description", "开始时间，ISO-8601。")
                    }
                    putJsonObject("endAt") {
                        put("type", "string")
                        put("description", "结束时间，ISO-8601。")
                    }
                    putJsonObject("calendarId") {
                        put("type", "string")
                        put("description", "可选，目标日历 ID。")
                    }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("location") { put("type", "string") }
                    putJsonObject("timezone") {
                        put("type", "string")
                        put("description", "可选 IANA 时区，未传默认系统时区。")
                    }
                    putJsonObject("allDay") { put("type", "boolean") }
                    putJsonObject("reminderMinutes") {
                        put("type", "array")
                        put("description", "提醒分钟列表，例如 [10, 30]。")
                        putJsonObject("items") {
                            put("type", "integer")
                        }
                    }
                }
                putJsonArray("required") {
                    add("title")
                    add("startAt")
                    add("endAt")
                }
            }
        }
    }

    val calendarEventListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_list")
            put("displayName", "查询日程")
            put("toolType", "calendar")
            put("description", "按时间范围、关键字、calendarId 查询日历事件。")
            put("postToolRule", "查看结果后再决定是否更新或删除。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("calendarId") { put("type", "string") }
                    putJsonObject("startAt") {
                        put("type", "string")
                        put("description", "可选，查询起始时间，ISO-8601。")
                    }
                    putJsonObject("endAt") {
                        put("type", "string")
                        put("description", "可选，查询结束时间，ISO-8601。")
                    }
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "可选关键词，匹配标题或地点。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选返回上限，默认 50，范围 1-200。")
                    }
                }
            }
        }
    }

    val calendarEventUpdateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_update")
            put("displayName", "修改日程")
            put("toolType", "calendar")
            put("description", "按 eventId 修改日历事件。")
            put("postToolRule", "修改后等待工具结果，再向用户同步。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("eventId") {
                        put("type", "string")
                        put("description", "事件 ID。")
                    }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("startAt") { put("type", "string") }
                    putJsonObject("endAt") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("location") { put("type", "string") }
                    putJsonObject("timezone") { put("type", "string") }
                    putJsonObject("allDay") { put("type", "boolean") }
                    putJsonObject("reminderMinutes") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "integer")
                        }
                    }
                }
                putJsonArray("required") {
                    add("eventId")
                }
            }
        }
    }

    val calendarEventDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_delete")
            put("displayName", "删除日程")
            put("toolType", "calendar")
            put("description", "按 eventId 删除日历事件。")
            put("postToolRule", "删除后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("eventId") { put("type", "string") }
                }
                putJsonArray("required") {
                    add("eventId")
                }
            }
        }
    }

    val musicPlaybackControlTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "music_playback_control")
            put("displayName", "音乐播放控制")
            put("toolType", "music")
            put(
                "description",
                "控制安卓系统级音乐播放。action=play 且提供 source 时，会由应用前台媒体会话播放本地文件、omnibot workspace/public 文件、file/content Uri 或 http(s) 直链音频；play 不提供 source 时，退化为向系统当前播放器发送播放媒体键。pause/resume/stop/next/previous 会优先控制当前由本应用托管的音频播放，若没有本地会话则退化为发送系统媒体键；seek 和 status 仅针对本应用托管的播放会话。"
            )
            put("postToolRule", "执行后等待工具结果，再决定是否继续调整播放。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "要执行的播放控制动作。")
                        putJsonArray("enum") {
                            add("play")
                            add("pause")
                            add("resume")
                            add("stop")
                            add("seek")
                            add("status")
                            add("next")
                            add("previous")
                        }
                    }
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "仅 play 时可选。支持 omnibot://、/workspace、/storage、相对 workspace 路径、file://、content://、http(s) 直链。留空表示只向系统发送播放媒体键。")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "仅 play 时可选，前台通知与系统媒体会话里显示的标题。")
                    }
                    putJsonObject("loop") {
                        put("type", "boolean")
                        put("description", "仅 play 时可选，是否循环播放。默认 false。")
                    }
                    putJsonObject("positionSeconds") {
                        put("type", "integer")
                        put("description", "仅 seek 时使用，目标播放秒数。")
                    }
                }
                putJsonArray("required") {
                    add("action")
                }
            }
        }
    }

    val memorySearchTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_search")
            put("displayName", "检索记忆")
            put("toolType", "memory")
            put("description", "在 workspace 记忆中检索与当前问题相关的长期/短期记忆。优先使用向量召回，配置缺失时自动降级词法检索。")
            put("postToolRule", "读取结果后再决定是否写入新的短期或长期记忆。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "检索语句。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "返回条数上限，默认 8，范围 1-20。")
                    }
                }
                putJsonArray("required") {
                    add("query")
                }
            }
        }
    }

    val memoryWriteDailyTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_write_daily")
            put("displayName", "写入当日记忆")
            put("toolType", "memory")
            put("description", "将当轮过程性信息写入 `.omnibot/memory/short-memories/YY-MM-DD.md`。")
            put("postToolRule", "写入成功后再继续执行其他步骤。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "要写入的短期记忆文本。")
                    }
                }
                putJsonArray("required") {
                    add("text")
                }
            }
        }
    }

    val memoryUpsertLongTermTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_upsert_longterm")
            put("displayName", "沉淀长期记忆")
            put("toolType", "memory")
            put("description", "将稳定偏好、长期约束、身份事实写入 `.omnibot/memory/MEMORY.md`。自动去重相同条目。")
            put("postToolRule", "写入后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "要沉淀的长期记忆内容。")
                    }
                }
                putJsonArray("required") {
                    add("text")
                }
            }
        }
    }

    val memoryRollupDayTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_rollup_day")
            put("displayName", "整理当日记忆")
            put("toolType", "memory")
            put("description", "整理某一天短期记忆并按策略沉淀到长期记忆。默认整理今天。")
            put("postToolRule", "整理后等待工具结果，再决定是否补充长期记忆。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("date") {
                        put("type", "string")
                        put("description", "可选日期，格式 YYYY-MM-DD。")
                    }
                }
            }
        }
    }

    val subagentDispatchTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "subagent_dispatch")
            put("displayName", "分派子任务")
            put("toolType", "subagent")
            put("description", "把多个可并行的小任务分派给 subagent 集群执行，并返回聚合结果。")
            put("postToolRule", "分派后等待工具结果，再汇总给用户。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tasks") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put("description", "需要并行执行的子任务列表。")
                    }
                    putJsonObject("concurrency") {
                        put("type", "integer")
                        put("description", "并发度，默认 2，范围 1-6。")
                    }
                    putJsonObject("mergeInstruction") {
                        put("type", "string")
                        put("description", "结果聚合要求，可选。")
                    }
                }
                putJsonArray("required") {
                    add("tasks")
                }
            }
        }
    }

    private val builtinToolDefinitions: List<JsonObject> = listOf(
        contextAppsQueryTool,
        contextTimeNowTool,
        vlmTaskTool,
        terminalExecuteTool,
        terminalSessionStartTool,
        terminalSessionExecTool,
        terminalSessionReadTool,
        terminalSessionStopTool,
        browserUseTool,
        fileReadTool,
        fileWriteTool,
        fileEditTool,
        fileListTool,
        fileSearchTool,
        fileStatTool,
        fileMoveTool,
        skillsListTool,
        skillsReadTool,
        workbenchProjectCreateTool,
        workbenchProjectListTool,
        workbenchProjectGetTool,
        workbenchApiListTool,
        workbenchApiCallTool,
        workbenchProjectExportTool,
        workbenchProjectOpenTool,
        workbenchProjectDeleteTool
    )

    private val scheduleToolDefinitions: List<JsonObject> = listOf(
        scheduleTaskCreateTool,
        scheduleTaskListTool,
        scheduleTaskUpdateTool,
        scheduleTaskDeleteTool
    )

    private val alarmToolDefinitions: List<JsonObject> = listOf(
        alarmReminderCreateTool,
        alarmReminderListTool,
        alarmReminderDeleteTool
    )

    private val calendarToolDefinitions: List<JsonObject> = listOf(
        calendarListTool,
        calendarEventCreateTool,
        calendarEventListTool,
        calendarEventUpdateTool,
        calendarEventDeleteTool
    )

    private val musicToolDefinitions: List<JsonObject> = listOf(
        musicPlaybackControlTool
    )

    private val memoryToolDefinitions: List<JsonObject> = listOf(
        memorySearchTool,
        memoryWriteDailyTool,
        memoryUpsertLongTermTool,
        memoryRollupDayTool
    )

    private val subagentToolDefinitions: List<JsonObject> = listOf(
        subagentDispatchTool
    )

    fun builtinTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        builtinToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun scheduleTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        scheduleToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun alarmTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        alarmToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun calendarTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        calendarToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun musicTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        musicToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun memoryTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        memoryToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun subagentTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        subagentToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun staticTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        builtinTools(locale) + scheduleTools(locale) + alarmTools(locale) + calendarTools(locale) + musicTools(locale)
}
