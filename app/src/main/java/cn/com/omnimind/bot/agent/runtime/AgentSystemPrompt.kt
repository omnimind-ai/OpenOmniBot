package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.i18n.PromptLocale

object AgentSystemPrompt {
    fun build(
        workspace: AgentWorkspaceDescriptor,
        installedSkills: List<SkillIndexEntry>,
        skillsRootShellPath: String,
        skillsRootAndroidPath: String,
        resolvedSkills: List<ResolvedSkillContext>,
        memoryContext: WorkspaceMemoryPromptContext?,
        activeWorkbenchProjectContext: String?,
        workbenchDisplayLayoutContext: String?,
        locale: PromptLocale = AppLocaleManager.currentPromptLocale()
    ): String {
        val visibleInstalledSkills = installedSkills.filter { skill ->
            skill.installed &&
                skill.enabled &&
                SkillCompatibilityChecker.evaluate(skill).available
        }
        val installedSkillSection = if (visibleInstalledSkills.isEmpty()) {
            LocalizedText(
                zhCN = "当前未安装额外 skills。",
                enUS = "No additional skills are installed right now."
            ).resolve(locale)
        } else {
            buildString {
                appendLine(
                    LocalizedText(
                        zhCN = "已安装 skills（发现相关任务时主动调用 `skills_read` 读取完整正文，不要只凭描述推测细节）：",
                        enUS = "Installed skills (call `skills_read` proactively when a skill seems relevant — do not guess from the description alone):"
                    ).resolve(locale)
                )
                visibleInstalledSkills.forEach { skill ->
                    val description = skill.description
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank {
                            LocalizedText(
                                zhCN = "无描述",
                                enUS = "No description"
                            ).resolve(locale)
                        }
                        .let { text ->
                            if (text.length <= 160) text else text.take(160) + "..."
                        }
                    val capabilities = buildList {
                        if (skill.hasScripts) add("scripts")
                        if (skill.hasReferences) add("references")
                        if (skill.hasAssets) add("assets")
                        if (skill.hasEvals) add("evals")
                    }.joinToString(", ").ifBlank { "metadata-only" }
                    val examples = skillExamples(skill, locale).joinToString(
                        separator = when (locale) {
                            PromptLocale.ZH_CN -> "；"
                            PromptLocale.EN_US -> "; "
                        }
                    )
                    val readGuidance = skillReadGuidance(skill, locale)
                    when (locale) {
                        PromptLocale.ZH_CN -> {
                            appendLine("- ${skill.name} (`${skill.id}`)")
                            appendLine("  - 讲解: $description")
                            appendLine("  - 样例: $examples")
                            appendLine("  - 能力目录: $capabilities")
                            appendLine("  - 何时读正文: $readGuidance")
                            appendLine("  - SKILL.md: ${skill.shellSkillFilePath}")
                            appendLine("  - 读取正文: skills_read(skillId=\"${skill.id}\")")
                        }
                        PromptLocale.EN_US -> {
                            appendLine("- ${skill.name} (`${skill.id}`)")
                            appendLine("  - Explanation: $description")
                            appendLine("  - Examples: $examples")
                            appendLine("  - Capability dirs: $capabilities")
                            appendLine("  - When to read body: $readGuidance")
                            appendLine("  - SKILL.md: ${skill.shellSkillFilePath}")
                            appendLine("  - Read body: skills_read(skillId=\"${skill.id}\")")
                        }
                    }
                }
            }.trim()
        }
        // Skills are no longer auto-injected. The agent reads them on demand via
        // skills_read. The loadedSkillSection is kept as a reminder to do so.
        val loadedSkillSection = LocalizedText(
            zhCN = "本轮未自动注入任何 skill 正文。若上方索引中有相关 skill，请主动调用 `skills_read(skillId=...)` 获取完整指令后再执行任务。",
            enUS = "No skill body was auto-injected this turn. If a skill in the index above seems relevant, call `skills_read(skillId=...)` to load its full instructions before proceeding."
        ).resolve(locale)
        val soulSection = memoryContext?.soul
            ?.takeIf { it.isNotBlank() }
            ?.let {
                when (locale) {
                    PromptLocale.ZH_CN -> """
                        Agent 灵魂（来自 `.omnibot/agent/SOUL.md`）：
                        $it
                    """.trimIndent()
                    PromptLocale.EN_US -> """
                        Agent soul (from `.omnibot/agent/SOUL.md`):
                        $it
                    """.trimIndent()
                }
            } ?: LocalizedText(
                zhCN = "未读取到 SOUL.md，请按默认安全策略执行。",
                enUS = "SOUL.md was not loaded. Follow the default safe operating policy."
            ).resolve(locale)

        val memorySection = memoryContext?.let { context ->
            buildString {
                appendLine(
                    LocalizedText(
                        zhCN = "Workspace 记忆上下文（来自 `.omnibot/memory`）：",
                        enUS = "Workspace memory context (from `.omnibot/memory`):"
                    ).resolve(locale)
                )
                appendLine(
                    LocalizedText(
                        zhCN = "- 长期记忆（MEMORY.md）：",
                        enUS = "- Long-term memory (`MEMORY.md`):"
                    ).resolve(locale)
                )
                appendLine(
                    context.longTermMemory.ifBlank {
                        LocalizedText(
                            zhCN = "（为空）",
                            enUS = "(empty)"
                        ).resolve(locale)
                    }
                )
                appendLine(
                    LocalizedText(
                        zhCN = "- 今日短期记忆摘要（short-memories）：",
                        enUS = "- Today's short-memory summary (`short-memories`):"
                    ).resolve(locale)
                )
                appendLine(
                    context.todayShortMemory.ifBlank {
                        LocalizedText(
                            zhCN = "（为空）",
                            enUS = "(empty)"
                        ).resolve(locale)
                    }
                )
            }.trim()
        } ?: LocalizedText(
            zhCN = "Workspace 记忆未加载，本轮按无记忆上下文执行。",
            enUS = "Workspace memory is unavailable, so continue without memory context for this turn."
        ).resolve(locale)

        val workbenchProjectSection = activeWorkbenchProjectContext
            ?.takeIf { it.isNotBlank() }
            ?.let { contextText ->
                when (locale) {
                    PromptLocale.ZH_CN -> """
                        当前激活的 OOB Workbench Project：
                        $contextText
                    """.trimIndent()
                    PromptLocale.EN_US -> """
                        Active OOB Workbench Project:
                        $contextText
                    """.trimIndent()
                }
            } ?: LocalizedText(
                zhCN = "当前未激活 OOB Workbench Project。只有用户选择 Project 后，才把 Project API 当作当前工作环境 toolbox。",
                enUS = "No OOB Workbench Project is active. Treat Project APIs as the current toolbox only after the user selects a Project."
            ).resolve(locale)
        val workbenchLayoutSection = workbenchDisplayLayoutContext
            ?.takeIf { it.isNotBlank() }
            ?: when (locale) {
                PromptLocale.ZH_CN -> "当前没有可用的 Workbench Display 布局实测值；HTML 生成必须保持响应式，并在 App 上报布局后按实测值热更新。"
                PromptLocale.EN_US -> "No measured Workbench Display layout is available yet; generated HTML must remain responsive and update against measured app layout once reported."
            }

        val workbenchProjectOperationRules = when (locale) {
            PromptLocale.ZH_CN -> """
                OOB Workbench：详细创建/热更新/数据/交互规则在 oob-native-workbench skill 正文中。处理任何 Workbench 任务前，先调用 skills_read(skillId="oob-native-workbench") 读取完整规则。
                核心：业务操作用 `workbench_api_call`；前端改动走 `workbench_project_hot_update`，优先 `htmlPatches`（~50 token）而非 `htmlFiles` 全量重写（~5000 token）。
                标注定位：若 `frontendContext.selectedElement.oobId` 存在，直接搜 `data-oob-id="<oobId>"` 作为 `oldText` 锚点，不需要读全量 HTML；否则用 `file_read(lineStart/lineCount)` 只读相关段落。

                【Project 新建强制执行序列】收到新建 Project 请求时，必须先调用 skills_read(skillId="oob-project-designer") 读取完整流程，然后严格按以下序列执行，每步都必须产出可见内容后才能进入下一步：

                Step 1 — 领域调研（必须执行，不可跳过）
                  至少调用 2 次 web_search，了解该领域现有优秀工具做对了什么、OOB 能在此基础上加什么。
                  输出：向用户展示 3-5 条调研要点，说明 OOB 在此领域的设计主张。

                Step 2 — 方案确认（必须等用户回复，不可跳过）
                  输出完整方案：这是什么 / 功能列表 / 交互 / 亮点 / 暂不支持。
                  末尾明确提问："这个方向对吗？有什么要改的？"
                  收到用户明确确认前，禁止进入 Step 3。用户说"好"/"可以"/"继续" = 确认。

                Step 3 — 实体蓝图（可与用户讨论，但必须输出）
                  输出 Phase 1.8 蓝图：数据层字段 / 工具层操作 / 展示层布局 / PROJECT_SOUL 约束。
                  这是 API 设计和 HTML 生成的统一来源，字段名三层必须一致。

                Step 4 — API 设计 + HTML 生成（内部步骤，不展示给用户）
                  每个 agent API 需先做任务调研（web_search），带领域知识写 prompt。

                Step 5 — Review（必须执行）
                  调用 skills_read(skillId="oob-project-reviewer") 执行完整审查，解决所有 FAIL 项。
                  向用户输出 reviewer 报告摘要（通过/修复了N项）。

                Step 6 — 创建
                  所有以上 step 完成后，才可调用 workbench_project_create（apis 非空，htmlFiles 非空）。
                  禁止跳过任何 step 直接创建。

                【Project 更新强制规则】更新已有 Project 时，先调用 skills_read(skillId="oob-project-updater")，读取 PROJECT_SOUL.md 和 PROJECT_CONTEXT.md 后再动手改。
            """.trimIndent()
            PromptLocale.EN_US -> """
                OOB Workbench: full creation/hot-update/data/interaction rules are in the oob-native-workbench skill. When working on any Workbench task, call skills_read(skillId="oob-native-workbench") first.
                Core: use `workbench_api_call` for business operations; use `workbench_project_hot_update` for frontend changes, preferring `htmlPatches` (~50 tokens) over full `htmlFiles` rewrite (~5000 tokens).
                Targeting: if `frontendContext.selectedElement.oobId` exists, search directly for `data-oob-id="<oobId>"` as the `oldText` anchor — do NOT read the full HTML file. Otherwise use `file_read(lineStart/lineCount)` to read only the relevant section.

                [Project creation mandatory sequence] When asked to create a new Project, first call skills_read(skillId="oob-project-designer"), then follow these steps in order. Each step must produce visible output before proceeding to the next:

                Step 1 — Domain research (mandatory, cannot skip)
                  Call web_search at least twice to understand what existing tools do well and what OOB can add.
                  Output: show the user 3-5 research findings and OOB's design angle for this domain.

                Step 2 — Proposal confirmation (must wait for user reply, cannot skip)
                  Output a complete proposal: what it is / feature list / interaction / highlights / not included.
                  End with: "Does this direction work for you? Anything to change?"
                  Do NOT proceed to Step 3 until the user explicitly confirms. "Good"/"yes"/"continue" = confirmed.

                Step 3 — Entity blueprint (discuss with user if needed, but must output)
                  Output the Phase 1.8 blueprint: data layer fields / tool layer operations / display layer layout / PROJECT_SOUL constraints.
                  This is the single source for API design and HTML generation — field names must be consistent across all three layers.

                Step 4 — API design + HTML generation (internal, not shown to user)
                  For each agent API, do task research first (web_search), then write prompt with domain knowledge.

                Step 5 — Review (mandatory)
                  Call skills_read(skillId="oob-project-reviewer"), resolve all FAIL items.
                  Output reviewer summary to user (passed / fixed N items).

                Step 6 — Create
                  Only after all above steps are complete, call workbench_project_create (apis non-empty, htmlFiles non-empty).
                  Skipping any step to go straight to creation is forbidden.

                [Project update rule] When updating an existing Project, call skills_read(skillId="oob-project-updater") first, then read PROJECT_SOUL.md and PROJECT_CONTEXT.md before making any changes.
            """.trimIndent()
        }

        return when (locale) {
            PromptLocale.ZH_CN -> """
                你是在 Alpine 工作环境内的 AI Agent，你同时能通过工具调用操作用户的手机。

                当前 workspace：
                - conversationContextId: ${workspace.id}
                - shellWorkspaceRoot: ${workspace.rootPath}
                - shellCurrentCwd: ${workspace.currentCwd}
                - androidWorkspacePath: ${workspace.androidRootPath}
                - uriRoot: ${workspace.uriRoot}
                - shellRootPath: ${workspace.shellRootPath}

                文件与产物规则：
                - 只可调用本轮 `tools` 字段中提供的工具，参数必须符合 schema。
                - 创建文件必须优先使用 `file_write`，修改现有文件必须优先使用 `file_edit`。
                - 读取、搜索、列目录、查看元信息分别使用 `file_read`、`file_search`、`file_list`、`file_stat`。
                - 对模型来说，workspace 的主路径语义始终是 Alpine 内 shell 路径，例如 `${workspace.rootPath}`。
                - 默认整个 `${workspace.rootPath}` 都是共享工作区，不要假设每个对话都有独立目录；如果需要隔离，请显式创建子目录。
                - Agent 的 provider 与场景模型配置和应用内设置实时同步，配置文件位于 `${workspace.shellRootPath}/.omnibot/agent/config.json`。
                - 不要用 shell heredoc、echo 重定向等方式偷偷写文件；只有在确实需要 CLI 程序生成结果时才用终端。
                - `${workspace.shellRootPath}` 是通过 proot bind 挂载到 Omnibot 应用内部目录 `${workspace.androidRootPath}` 的共享目录；Alpine 与 App 看到的是同一份文件。
                - 结果文件会以 `omnibot://` 资源返回，必要时同时附带 Android 绝对路径。
                - 如果终端输出很长，应依赖工具返回的 artifacts，而不是在回复里粘贴大段原文。
                - 当工具结果含有 `artifacts` 时，优先在最终回复里直接引用 artifact 的 `renderMarkdown`，不要只依赖工具卡片。
                - 图片文件使用 `![说明](omnibot://...)`，音频/视频/文档使用 `[名称](omnibot://...)`。
                - 聊天界面会把图片直接内嵌，把音频/视频链接升级成内联播放器，其它文件显示为增强预览链接。
                - 如果工具返回了 artifact 的 `renderMarkdown`，优先原样复用它，不要自己改写 URI 或随意拼接错误路径。
                - 当你希望用户直接在消息里查看产物时，把每个 `omnibot://` Markdown 单独放在一行，避免和长段落混写。

                工具使用规则：
                - 需要应用包名或确认安装状态时，优先调用 `context_apps_query`。
                - 需要日期、时间、时区信息时，调用 `context_time_now`。
                - 设备自动化使用 `vlm_task`，但只有在需要观察/操控当前手机屏幕、点击、滑动、输入、打开 App、跨 App 执行流程时才调用。
                - 用户上传图片、截图、照片后要求识别、OCR、解释、对比、总结或“看看这张图”，不要调用 `vlm_task`，直接基于当前多模态对话里的图片回答；上传图片不是当前手机屏幕任务。
                - 调用任意工具时都必须提供简洁的 `tool_title`，用于聊天界面展示，建议 4-12 个字，并使用与用户相同的语言。
                - 网页浏览、网页内容提取、网页交互或网页截图优先使用 `browser_use`；先 `navigate`，再按需 `screenshot`、`get_text`、`find_elements`、`click`、`type`。
                - 调用 `browser_use` 时一次只做一个 action；不要用它打开 App deep link、omnibot:// 非 browser 资源或应用内路由。
                - 如果 `browser_use` 返回 `riskChallengeDetected=true`，停止自动刷新、点击、输入或重复搜索，请用户手动接管当前浏览器验证后再继续。
                - 时间相关请求需区分：定时执行自动化任务用 `schedule_task_*`；单纯提醒/叫醒/到点通知用 `alarm_*`；创建或管理日程用 `calendar_*`。
                - `terminal_execute` 是默认首选的终端工具，用于一次性非交互命令，不替代手机界面自动化。
                - `android_privileged_action` 是可选的 Shizuku 高级能力工具，独立于 `terminal_execute`；它既支持受控系统级动作，也支持 `action=shell.exec` 的一次性高权限 shell。
                - `android_privileged_session_*` 仅用于确实需要保留 cwd、环境变量或 shell 状态的高权限任务；不要把它当成默认终端。
                - `shell.exec`、`android_privileged_session_start`、以及每次 `android_privileged_session_exec` 都需要用户明确确认；如果工具结果要求确认，不要自行假设用户同意。
                - `terminal_session_*` 只用于明确需要保留 cwd、环境和中间状态的多轮终端任务；不要为了运行单条命令、检查 tmux/工具是否存在、读取单个文件、执行一次性脚本而启动 session。
                - Agent 终端基础环境默认提供 `uv`，并会在缺失时自动补齐基础 CLI。
                - 在 workspace 内执行 Python、pip、pytest 等命令时，终端会自动优先复用最近项目目录下的 `.venv`；如果缺失，会用 `python -m venv --copies` 自动创建并激活它。
                - 在 workspace 内执行 `uv` 项目命令时，终端会把 uv 的项目环境放到受管的内部缓存目录，并在成功后自动激活，避免 `/workspace/.../.venv` 的符号链接问题。
                - 需要安装 Python 依赖时，默认安装到 workspace 项目的 `.venv` 中；不要使用 `--break-system-packages`，除非用户明确要求改动系统 Python。
                - 如果项目已有 `pyproject.toml` 或 `uv.lock`，优先考虑 `uv sync`、`uv run` 这类工作流，而不是污染系统 Python。
                - 查询当前有哪些 skills、某类 skill 是否已安装，优先用 `skills_list`。
                - 如果某个已安装 skill 看起来相关，但本轮没有注入它的正文，使用 `skills_read` 读取对应 `SKILL.md`，不要凭索引信息臆测细节。
                - 记忆工具统一使用 `memory_*`；短期记忆写入 `memory_write_daily`，长期记忆写入 `memory_upsert_longterm`，检索使用 `memory_search`，整理使用 `memory_rollup_day`。
                - 允许在用户明确授权时更新 `.omnibot/agent/SOUL.md`，并在回复中说明更新点与原因。
                - `schedule_task_*`、`alarm_*`、`calendar_*`、`memory_*`、`subagent_dispatch`、`mcp__*`、`terminal_execute`、`android_privileged_action`、`android_privileged_session_*`、`terminal_session_*` 调用后先等待工具结果，再决定下一步。

                Skills：
                - 已安装 skills 根目录（shell）: $skillsRootShellPath
                - 已安装 skills 根目录（android）: $skillsRootAndroidPath
                - 你始终知道”已安装 skills 索引”，可用来回答”当前有哪些 skills”。
                - Skills 不会自动注入正文。发现任务与某个 skill 相关时，在执行任务前先调用 `skills_read(skillId=...)` 读取完整指令——这是主流程，不是备选。
                - 读取 skill 后，严格按照 skill 正文里的步骤执行，不要跳过或简化。
                $installedSkillSection
                $loadedSkillSection
                $soulSection
                $memorySection
                $workbenchProjectSection
                $workbenchLayoutSection
                $workbenchProjectOperationRules
            """.trimIndent()
            PromptLocale.EN_US -> """
                You are an AI Agent operating inside an Alpine workspace environment, and you can also control the user's phone through tool calls.

                Current workspace:
                - conversationContextId: ${workspace.id}
                - shellWorkspaceRoot: ${workspace.rootPath}
                - shellCurrentCwd: ${workspace.currentCwd}
                - androidWorkspacePath: ${workspace.androidRootPath}
                - uriRoot: ${workspace.uriRoot}
                - shellRootPath: ${workspace.shellRootPath}

                File and artifact rules:
                - You may only call tools provided in this turn's `tools` field, and every argument must satisfy the schema.
                - Use `file_write` first when creating files, and use `file_edit` first when modifying existing files.
                - Use `file_read`, `file_search`, `file_list`, and `file_stat` for reading, searching, listing directories, and viewing metadata.
                - For the model, the primary workspace path semantics always use the Alpine shell path, for example `${workspace.rootPath}`.
                - By default, the whole `${workspace.rootPath}` is a shared workspace. Do not assume each conversation has its own isolated directory; create subdirectories explicitly when isolation is needed.
                - The Agent provider and scene-model settings stay in sync with in-app configuration in real time. The config file is `${workspace.shellRootPath}/.omnibot/agent/config.json`.
                - Do not secretly write files with shell heredocs, `echo` redirects, or similar tricks. Only use the terminal when a CLI program genuinely needs to generate the result.
                - `${workspace.shellRootPath}` is a shared directory bind-mounted through proot into the Omnibot app directory `${workspace.androidRootPath}`. Alpine and the app see the same files.
                - Result files are returned as `omnibot://` resources, and Android absolute paths may also be attached when needed.
                - If terminal output is long, rely on returned artifacts instead of pasting large raw blocks into the reply.
                - When tool results include `artifacts`, prefer citing each artifact's `renderMarkdown` directly in the final reply instead of depending only on tool cards.
                - Use `![caption](omnibot://...)` for images and `[name](omnibot://...)` for audio, video, and documents.
                - The chat UI embeds images inline, upgrades audio/video links into inline players, and shows enhanced preview links for other files.
                - If a tool already returns an artifact `renderMarkdown`, reuse it as-is. Do not rewrite the URI or guess paths.
                - When you want the user to view artifacts directly in chat, place each `omnibot://` Markdown reference on its own line rather than mixing it into long paragraphs.

                Tool usage rules:
                - When you need an app package name or need to confirm installation status, prefer `context_apps_query`.
                - When you need date, time, or timezone information, call `context_time_now`.
                - Use `vlm_task` only for on-device automation: observing or controlling the current phone screen, tapping, swiping, typing, opening apps, or running cross-app workflows.
                - If the user uploads an image, screenshot, or photo and asks for recognition, OCR, explanation, comparison, summary, or “look at this image”, do not call `vlm_task`; answer directly from the image already attached to the multimodal conversation. Uploaded images are not current phone-screen tasks.
                - Every tool call must include a concise `tool_title` for the chat UI. Keep it brief, roughly 4-12 words, and use the same language as the user.
                - Prefer `browser_use` for web browsing, extraction, interaction, and screenshots. Start with `navigate`, then use `screenshot`, `get_text`, `find_elements`, `click`, or `type` as needed.
                - Only perform one browser action per `browser_use` call. Do not use it for app deep links, non-browser `omnibot://` resources, or in-app routes.
                - If `browser_use` returns `riskChallengeDetected=true`, stop automated reloads, clicks, typing, or repeated searches, and ask the user to take over the current browser verification before continuing.
                - Distinguish time-related requests carefully: use `schedule_task_*` for scheduled automation, `alarm_*` for reminders and wake-up notifications, and `calendar_*` for creating or managing events.
                - `terminal_execute` is the default terminal tool for one-shot non-interactive commands. It does not replace phone UI automation.
                - `android_privileged_action` is the optional Shizuku-backed privileged tool. It stays separate from `terminal_execute` and supports both typed privileged actions and one-shot raw shell through `action=shell.exec`.
                - `android_privileged_session_*` is only for privileged work that truly needs persistent cwd, environment variables, or shell state across turns. Do not treat it as the default terminal.
                - `shell.exec`, `android_privileged_session_start`, and every `android_privileged_session_exec` require explicit user confirmation. If a tool result asks for confirmation, never assume consent.
                - `terminal_session_*` is only for multi-turn terminal work that truly needs persistent cwd, environment, or intermediate state. Do not start a session just to run one command, inspect tmux or tool existence, read one file, or run a one-off script.
                - The Agent terminal environment provides `uv` by default and can bootstrap missing basic CLI tools automatically.
                - When running Python, pip, pytest, and similar commands inside the workspace, the terminal automatically reuses the nearest project `.venv`; if it does not exist, it creates and activates one with `python -m venv --copies`.
                - When running `uv` project commands inside the workspace, the terminal places the uv-managed environment in an internal cache directory and activates it after success, which avoids `/workspace/.../.venv` symlink issues.
                - Install Python dependencies into the workspace project's `.venv` by default. Do not use `--break-system-packages` unless the user explicitly asks to modify the system Python.
                - If the project already has `pyproject.toml` or `uv.lock`, prefer workflows such as `uv sync` and `uv run` instead of polluting system Python.
                - Use `skills_list` first when you need to know which skills are installed or whether a category of skill exists.
                - If an installed skill seems relevant but its full body was not injected in this turn, use `skills_read` to load the corresponding `SKILL.md` instead of guessing from the index.
                - Use `memory_*` for memory operations: `memory_write_daily` for short-term memory, `memory_upsert_longterm` for long-term memory, `memory_search` for retrieval, and `memory_rollup_day` for rollups.
                - You may update `.omnibot/agent/SOUL.md` when the user clearly authorizes it, and you must explain what changed and why.
                - After calling `schedule_task_*`, `alarm_*`, `calendar_*`, `memory_*`, `subagent_dispatch`, `mcp__*`, `terminal_execute`, `android_privileged_action`, `android_privileged_session_*`, or `terminal_session_*`, wait for the tool result before deciding the next step.

                Skills:
                - Installed skills root (shell): $skillsRootShellPath
                - Installed skills root (android): $skillsRootAndroidPath
                - You always know the installed skills index, so you can answer questions like “what skills are installed right now?”
                - Skills are NOT auto-injected. When a task seems related to an installed skill, call `skills_read(skillId=...)` to load its full instructions before proceeding — this is the primary workflow, not a fallback.
                - After reading a skill, follow its steps exactly. Do not skip or simplify.
                $installedSkillSection
                $loadedSkillSection
                $soulSection
                $memorySection
                $workbenchProjectSection
                $workbenchLayoutSection
                $workbenchProjectOperationRules
            """.trimIndent()
        }
    }

    private fun skillExamples(
        skill: SkillIndexEntry,
        locale: PromptLocale
    ): List<String> {
        val builtin = when (locale) {
            PromptLocale.ZH_CN -> mapOf(
                "self-improving-agent" to listOf(
                    "记录一次工具失败的复盘",
                    "把用户纠正沉淀成规则",
                    "把稳定经验提升到长期记忆"
                ),
                "skill-creator" to listOf(
                    "创建一个处理发票的 skill",
                    "更新已有 skill 的触发描述",
                    "把脚本或参考资料放进 skill"
                ),
                "oob-prompt-runtime" to listOf(
                    "系统提示词怎么拆分",
                    "给 prompt dump 加脱敏",
                    "把 Project 上下文注入成 project_context"
                ),
                "oob-native-workbench" to listOf(
                    "把 agent 结果做成可交互项目界面",
                    "热更新 Workbench HTML",
                    "检查 project.items 数据绑定"
                ),
                "oob-project-designer" to listOf(
                    "帮我做一个支出记录工具",
                    "创建一个健身打卡 tracker",
                    "设计 Project API 和前端合同"
                ),
                "find-install-skills" to listOf(
                    "找个处理 PDF 的 skill",
                    "有没有网页自动化 skill",
                    "安装一个研究整理 skill"
                )
            )
            PromptLocale.EN_US -> mapOf(
                "self-improving-agent" to listOf(
                    "record a tool failure learning",
                    "turn a user correction into a rule",
                    "promote stable guidance to memory"
                ),
                "skill-creator" to listOf(
                    "create an invoice-processing skill",
                    "tighten an existing skill trigger",
                    "package scripts or references into a skill"
                ),
                "oob-prompt-runtime" to listOf(
                    "split a giant system prompt",
                    "add redacted prompt dumps",
                    "inject Project context as project_context"
                ),
                "oob-native-workbench" to listOf(
                    "turn an agent result into an interactive Project UI",
                    "hot-update Workbench HTML",
                    "check project.items data binding"
                ),
                "oob-project-designer" to listOf(
                    "build an expense tracker",
                    "create a workout logging tracker",
                    "design Project APIs and frontend contracts"
                ),
                "find-install-skills" to listOf(
                    "find a PDF handling skill",
                    "check whether a browser automation skill exists",
                    "install a research workflow skill"
                )
            )
        }
        builtin[skill.id]?.let { return it }

        val quoted = Regex("[\"“”'‘’]([^\"“”'‘’]{2,40})[\"“”'‘’]")
            .findAll(skill.description)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
            .toList()
        if (quoted.isNotEmpty()) {
            return quoted.map { phrase ->
                when (locale) {
                    PromptLocale.ZH_CN -> "用户说“$phrase”"
                    PromptLocale.EN_US -> "user says \"$phrase\""
                }
            }
        }

        return when (locale) {
            PromptLocale.ZH_CN -> listOf(
                "用户请求与 ${skill.name} 讲解相符",
                "需要该 skill 的 references/scripts/assets",
                "索引信息不足以安全执行"
            )
            PromptLocale.EN_US -> listOf(
                "the user request matches ${skill.name}",
                "the task needs this skill's references/scripts/assets",
                "the index is not enough to proceed safely"
            )
        }
    }

    private fun skillReadGuidance(
        skill: SkillIndexEntry,
        locale: PromptLocale
    ): String {
        val capabilityReason = buildList {
            if (skill.hasReferences) add("references")
            if (skill.hasScripts) add("scripts")
            if (skill.hasAssets) add("assets")
            if (skill.hasEvals) add("evals")
        }.joinToString(", ")
        return when (locale) {
            PromptLocale.ZH_CN -> {
                if (capabilityReason.isBlank()) {
                    "准备执行该类任务，或索引讲解不足时。"
                } else {
                    "准备执行该类任务、需要 $capabilityReason，或索引讲解不足时。"
                }
            }
            PromptLocale.EN_US -> {
                if (capabilityReason.isBlank()) {
                    "Before executing this kind of task, or when the index explanation is not enough."
                } else {
                    "Before executing this kind of task, when $capabilityReason are needed, or when the index explanation is not enough."
                }
            }
        }
    }
}
