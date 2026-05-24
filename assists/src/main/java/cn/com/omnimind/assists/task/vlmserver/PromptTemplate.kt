package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.assists.util.TimeUtil
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ModelSceneRegistry

/**
 * 主 VLM prompt 构造器：
 * - system: 稳定规则、工具协议、GUI 操作规范
 * - user: 当前轮动态上下文 + 当前截图
 */
object PromptTemplate {
    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    private fun t(locale: PromptLocale, zh: String, en: String): String {
        return when (locale) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    fun getPrompt(context: UIContext, sceneId: String? = null): String {
        return buildTurnUserPrompt(context, sceneId)
    }

    fun buildSystemPrompt(sceneId: String? = null): String {
        val locale = currentLocale()
        val resolvedSceneId = if (sceneId.isNullOrBlank()) {
            "scene.vlm.operation.primary"
        } else {
            sceneId
        }
        val runtimeProfile = ModelSceneRegistry.getRuntimeProfile(resolvedSceneId)
        val parser = runtimeProfile?.responseParser ?: ModelSceneRegistry.ResponseParser.TEXT_CONTENT
        val template = ModelSceneRegistry.getPrompt(resolvedSceneId)
            ?: ModelSceneRegistry.getPrompt("scene.vlm.operation.primary")
            ?: throw IllegalStateException("scene.vlm.operation.primary prompt not found")

        val responseContract = if (parser == ModelSceneRegistry.ResponseParser.OPENAI_TOOL_ACTIONS) {
            VLMToolDefinitions.responseContract(locale)
        } else {
            ""
        }

        return ModelSceneRegistry.renderPrompt(
            template,
            mapOf(
                "priorityEvent" to t(locale, "若后续 user 消息包含紧急事件，请优先处理。", "If later user messages contain urgent events, prioritize them."),
                "overallTask" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentStepGoal" to t(locale, "见后续 user 消息", "See the following user message"),
                "stepSkillGuidance" to t(locale, "见后续 user 消息", "See the following user message"),
                "summaryHistory" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentState" to t(locale, "见后续 user 消息", "See the following user message"),
                "nextStepHint" to t(locale, "见后续 user 消息", "See the following user message"),
                "completedMilestones" to t(locale, "见后续 user 消息", "See the following user message"),
                "keyMemory" to t(locale, "见后续 user 消息", "See the following user message"),
                "installedApps" to t(locale, "见后续 user 消息", "See the following user message"),
                "currentTime" to t(locale, "见后续 user 消息", "See the following user message"),
                "responseContract" to responseContract
            )
        )
    }

    fun buildTurnUserPrompt(context: UIContext, sceneId: String? = null): String {
        val locale = currentLocale()
        val resolvedSceneId = if (sceneId.isNullOrBlank()) {
            "scene.vlm.operation.primary"
        } else {
            sceneId
        }
        val summaryHistory = if (context.runningSummary.isNotEmpty()) {
            context.runningSummary
        } else if (context.trace.isNotEmpty()) {
            context.trace.last().summary
        } else {
            t(locale, "暂无历史操作", "No prior execution history yet")
        }
        val installedApps = if (context.installedApplications.isNotEmpty()) {
            context.installedApplications.entries.joinToString("\n") { (packageName, appName) ->
                "- ${packageName} -> ${appName}"
            }
        } else {
            t(locale, "暂无数据", "No data")
        }
        val completedMilestones = if (context.completedMilestones.isNotEmpty()) {
            context.completedMilestones.joinToString(
                separator = if (locale == PromptLocale.ZH_CN) "、" else ", "
            )
        } else {
            t(locale, "暂无", "None yet")
        }
        val keyMemory = if (context.keyMemory.isNotEmpty()) {
            context.keyMemory.joinToString(
                separator = if (locale == PromptLocale.ZH_CN) "；" else "; "
            )
        } else {
            t(locale, "暂无", "None yet")
        }
        val priorityEventSection = if (context.priorityEvent != null) {
            buildString {
                appendLine(t(locale, "【紧急事件】", "[Urgent Event]"))
                appendLine(context.priorityEvent)
                if (context.suggestCompletion) {
                    appendLine(
                        t(
                            locale,
                            "如果已经确认任务完成，请尽快调用 finished 工具结束任务。",
                            "If the task has already been confirmed complete, call the finished tool as soon as possible."
                        )
                    )
                }
                appendLine()
            }.trim()
        } else {
            ""
        }

        return buildString {
            appendLine(
                t(
                    locale,
                    "以下是当前这一轮的动态上下文，请结合当前截图选择下一步动作。",
                    "Below is the dynamic context for the current turn. Use it together with the current screenshot to choose the next action."
                )
            )
            appendLine("${t(locale, "场景", "Scene")}: $resolvedSceneId")
            appendLine("${t(locale, "当前时间", "Current time")}: ${TimeUtil.getCurrentTimeString()}")
            appendLine("${t(locale, "用户任务", "User task")}: ${context.overallTask}")
            appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
            appendLine(
                "${t(locale, "技能提示", "Skill guidance")}: ${context.stepSkillGuidance.ifEmpty { t(locale, "无", "None") }}"
            )
            if (priorityEventSection.isNotBlank()) {
                appendLine(priorityEventSection)
            }
            if (context.currentPageSummary.isNotBlank() || context.firstStepGuidance.isNotBlank()) {
                appendLine("${t(locale, "首步页面上下文", "First-step page context")}:")
                if (context.currentPageSummary.isNotBlank()) {
                    appendLine(context.currentPageSummary)
                }
                if (context.firstStepGuidance.isNotBlank()) {
                    appendLine(context.firstStepGuidance)
                }
            }
            appendLine("${t(locale, "当前状态", "Current state")}: ${context.currentState.ifEmpty { t(locale, "未知", "Unknown") }}")
            appendLine("${t(locale, "建议下一步", "Suggested next step")}: ${context.nextStepHint.ifEmpty { t(locale, "无", "None") }}")
            appendLine("${t(locale, "已完成里程碑", "Completed milestones")}: $completedMilestones")
            appendLine("${t(locale, "关键记忆", "Key memory")}: $keyMemory")
            appendLine("${t(locale, "历史总结", "History summary")}: $summaryHistory")
            appendLine("${t(locale, "已安装应用", "Installed apps")}: $installedApps")
            appendLine()
            appendLine("${t(locale, "输出要求", "Output requirements")}:")
            appendLine(
                t(
                    locale,
                    "1. 直接从 tools 列表中选择下一步动作，每轮只调用一个工具。",
                    "1. Pick the next action directly from the tools list, and call exactly one tool per turn."
                )
            )
            appendLine(
                t(
                    locale,
                    "2. click/long_press 只填 x、y；scroll 只填 x1、y1、x2、y2；每个坐标字段都必须是单个数值。",
                    "2. For click and long_press, only fill x and y. For scroll, only fill x1, y1, x2, and y2. Every coordinate field must be a single numeric scalar."
                )
            )
            appendLine(
                t(
                    locale,
                    "3. assistant.content 只写 observation/thought/summary 元信息；只有真正完成任务时才调用 finished。",
                    "3. assistant.content may only contain observation / thought / summary metadata. Call finished only when the task is truly complete."
                )
            )
            appendLine(
                t(
                    locale,
                    "4. 只要返回 package_name 或 open_app.package_name，必须从上面的已安装应用列表中原样选择一个 package name；禁止猜测常见默认包名。",
                    "4. When returning a package_name or open_app.package_name, you must select it verbatim from the installed apps list above. Never guess common default package names."
                )
            )
            appendLine(
                t(
                    locale,
                    "5. 如果目标应用有多个候选，优先使用已安装列表里的精确 package；例如联系人若存在 com.google.android.contacts，就必须使用它，不要改成 com.android.contacts。",
                    "5. If the target app has multiple candidates, prefer the exact package from the installed list; e.g., if com.google.android.contacts exists, use it rather than com.android.contacts."
                )
            )
            appendLine(
                t(
                    locale,
                    "6. 如果当前任务需要打开某个应用，但已安装应用列表里没有明确 package，就不要猜；改用 info/feedback 请求更多信息或先通过界面观察确认。",
                    "6. If the current task requires opening an app but the installed list lacks a clear package, do not guess; instead, use info/feedback to request more information or confirm by observing the UI."
                )
            )
            appendLine(
                t(
                    locale,
                    "7. 像 M3A 一样每轮只推进一个可验证变化：先用 raw screenshot、marked screenshot、OOB indexed page evidence 和上一轮 tool 结果判断当前页；动作后根据 appeared/disappeared/visible 文本继续，不要忽略失败或无变化反馈。",
                    "7. Follow an M3A-style one-change loop: use the raw screenshot, marked screenshot, OOB indexed page evidence, and previous tool result to judge the current page; after each action, continue from appeared/disappeared/visible text feedback and do not ignore failed or unchanged actions."
                )
            )
            appendLine(
                t(
                    locale,
                    "8. 不要输出停留、延时或空操作类动作；页面停留、加载和 XML 稳定判断由系统内部完成。若页面已经稳定，请选择一个可推进任务的语义动作：click、input_text、type、scroll、press_back、press_home、open_app、info、abort 或 finished。",
                    "8. Do not output any idle, delay, or no-op action. Page settling, loading delay, and XML stability checks are handled internally. Once the page is stable, choose a semantic task-progressing action: click, input_text, type, scroll, press_back, press_home, open_app, info, abort, or finished."
                )
            )
        }.trim()
    }

    fun buildToolCallRetryPrompt(context: UIContext, retryState: VLMToolCallRetryState): String {
        val locale = currentLocale()
        val thinking = retryState.thinking
        return buildString {
            val failureReason = retryState.failureReason?.trim().orEmpty()
            if (failureReason.isNotEmpty()) {
                appendLine(
                    t(
                        locale,
                        "系统检查到你上一轮的 tool_call 参数不合规：$failureReason",
                        "The system detected that the tool_call arguments from your previous turn were invalid: $failureReason"
                    )
                )
            } else {
                appendLine(
                    t(
                        locale,
                        "系统检查到你上一轮没有返回标准 tool_calls，但当前任务仍是执行型 GUI 自动化。",
                        "The system detected that your previous turn did not return standard tool_calls, but the current task is still an execution-oriented GUI automation task."
                    )
                )
            }
            appendLine(
                t(
                    locale,
                    "请在本轮严格返回一个原生 tool_call，并从 tools 列表中选择下一步动作。",
                    "In this turn, return exactly one native tool_call and choose the next action from the tools list."
                )
            )
            appendLine(
                t(
                    locale,
                    "不要只输出 observation/thought/summary JSON，不要在 assistant.content 中写动作参数，也不要提前宣布任务完成。",
                    "Do not output only observation/thought/summary JSON, do not put action arguments in assistant.content, and do not announce completion prematurely."
                )
            )
            appendLine(
                t(
                    locale,
                    "只有当用户目标已经真正完成时，才能调用 finished。",
                    "Call finished only when the user's goal is truly complete."
                )
            )
            appendLine(
                t(
                    locale,
                    "若你判断下一步是点击、输入、滑动、返回或结束，请直接使用对应工具；不要使用停留、延时或空操作类动作，稳定停留由系统内部处理。",
                    "If the next step should be tap, type, scroll, go back, or finish, call the matching tool directly. Do not use idle, delay, or no-op actions; stable settling is handled internally."
                )
            )
            appendLine(
                t(
                    locale,
                    "若需要坐标，必须分别写入 x/y 或 x1/y1/x2/y2；每个字段都只能是单个数值，不要返回 [x,y]、coordinates 或对象。",
                    "If coordinates are needed, write them separately into x/y or x1/y1/x2/y2. Each field must be a single numeric scalar; do not return [x,y], coordinates, or objects."
                )
            )
            appendLine(
                t(
                    locale,
                    "本次为第 ${retryState.retryIndex} 次协议纠偏。",
                    "This is protocol correction attempt #${retryState.retryIndex}."
                )
            )
            appendLine("${t(locale, "用户原始任务", "Original user task")}: ${context.overallTask}")
            appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
            thinking.finishReason?.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 finish_reason", "Previous finish_reason")}: $it")
            }
            thinking.observation.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 observation", "Previous observation")}: ${truncateForRetry(it)}")
            }
            thinking.thought.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 thought", "Previous thought")}: ${truncateForRetry(it)}")
            }
            thinking.summary.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 summary", "Previous summary")}: ${truncateForRetry(it)}")
            }
            thinking.reasoning.takeIf { it.isNotBlank() }?.let {
                appendLine("${t(locale, "上一轮 reasoning_content", "Previous reasoning_content")}: ${truncateForRetry(it, maxLen = 900)}")
            }
        }.trim()
    }

    private fun truncateForRetry(text: String, maxLen: Int = 280): String {
        val normalized = text.replace("\r\n", "\n").trim()
        return if (normalized.length <= maxLen) normalized else normalized.take(maxLen) + "..."
    }
}
