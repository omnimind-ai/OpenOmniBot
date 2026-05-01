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

    fun buildSystemPrompt(
        sceneId: String? = null,
        actionProtocol: VlmActionProtocol = VlmActionProtocol.OPENAI_TOOL_CALLS
    ): String {
        val locale = currentLocale()
        if (actionProtocol == VlmActionProtocol.DO_TEXT) {
            return buildDoTextSystemPrompt(locale)
        }
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

    fun buildTurnUserPrompt(
        context: UIContext,
        sceneId: String? = null,
        actionProtocol: VlmActionProtocol = VlmActionProtocol.OPENAI_TOOL_CALLS
    ): String {
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
        val installedApps = if (context.installedApplications.isNotEmpty() && actionProtocol == VlmActionProtocol.DO_TEXT) {
            context.installedApplications.entries
                .take(80)
                .joinToString(", ") { (packageName, appName) -> "$appName($packageName)" }
        } else if (context.installedApplications.isNotEmpty()) {
            context.installedApplications.values.joinToString(", ")
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
                        if (actionProtocol == VlmActionProtocol.DO_TEXT) {
                            t(
                                locale,
                                "如果已经确认任务完成，请尽快输出 finish(message=\"...\") 结束任务。",
                                "If the task has already been confirmed complete, output finish(message=\"...\") as soon as possible."
                            )
                        } else {
                            t(
                                locale,
                                "如果已经确认任务完成，请尽快调用 finished 工具结束任务。",
                                "If the task has already been confirmed complete, call the finished tool as soon as possible."
                            )
                        }
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
            appendLine("${t(locale, "当前状态", "Current state")}: ${context.currentState.ifEmpty { t(locale, "未知", "Unknown") }}")
            appendLine("${t(locale, "建议下一步", "Suggested next step")}: ${context.nextStepHint.ifEmpty { t(locale, "无", "None") }}")
            appendLine("${t(locale, "已完成里程碑", "Completed milestones")}: $completedMilestones")
            appendLine("${t(locale, "关键记忆", "Key memory")}: $keyMemory")
            appendLine("${t(locale, "历史总结", "History summary")}: $summaryHistory")
            appendLine("${t(locale, "已安装应用", "Installed apps")}: $installedApps")
            appendLine()
            appendLine("${t(locale, "输出要求", "Output requirements")}:")
            if (actionProtocol == VlmActionProtocol.DO_TEXT) {
                appendLine(
                    t(
                        locale,
                        "1. 每轮只输出一个动作，格式必须是 do(action=\"...\") 或 finish(message=\"...\")。",
                        "1. Output exactly one action per turn. The format must be do(action=\"...\") or finish(message=\"...\")."
                    )
                )
                appendLine(
                    t(
                        locale,
                        "2. Tap/Long Press/Double Tap 使用 element=[x,y]；Swipe 使用 start=[x,y], end=[x,y]；坐标为 0-1000 相对坐标。",
                        "2. Use element=[x,y] for Tap/Long Press/Double Tap and start=[x,y], end=[x,y] for Swipe. Coordinates are relative 0-1000 values."
                    )
                )
                appendLine(
                    t(
                        locale,
                        "3. 支付、转账、删除、授权等敏感操作不要直接点击，使用 do(action=\"Take_over\", message=\"...\") 让用户接管。",
                        "3. For sensitive operations such as payment, transfer, deletion, or authorization, do not tap directly. Use do(action=\"Take_over\", message=\"...\")."
                    )
                )
            } else {
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
            }
        }.trim()
    }

    private fun buildDoTextSystemPrompt(locale: PromptLocale): String {
        return t(
            locale,
            """
你是一个手机 Agent，可以通过 Shizuku 操控用户当前手机屏幕来完成任务。每轮你会收到当前截图和任务上下文，你必须基于当前看到的屏幕选择下一步，只输出一步动作。

## 行为原则
1. 不要假设上一轮操作成功；每轮都重新观察当前截图。
2. 找不到目标元素时，可以滑动、返回或换一种路径。
3. 需要登录、验证码、支付、转账、删除、授权确认等敏感步骤时，使用 Take_over 请求用户接管。
4. 坐标采用相对坐标系，取值 0-1000；(0,0) 是左上角，(1000,1000) 是右下角。

## 可用动作
- Launch: do(action="Launch", app="微信")
- Tap: do(action="Tap", element=[500,500])
- Type: do(action="Type", text="你好")
- Swipe: do(action="Swipe", start=[500,800], end=[500,200])
- Back: do(action="Back")
- Home: do(action="Home")
- Long Press: do(action="Long Press", element=[500,500])
- Double Tap: do(action="Double Tap", element=[500,500])
- Wait: do(action="Wait", duration="2 seconds")
- Take_over: do(action="Take_over", message="需要登录或用户确认")
- finish: finish(message="已完成")

## 输出格式
可以先用极短自然语言说明判断，但最后必须包含且只包含一个 do(...) 或 finish(...) 动作。不要输出 JSON，不要输出 OpenAI tool_calls。
""".trimIndent(),
            """
You are a Phone Agent controlling the user's current Android screen through Shizuku. Each turn includes a screenshot and task context. Choose exactly one next step based on the current screen.

## Principles
1. Do not assume the previous action succeeded. Re-check the screenshot every turn.
2. If the target is missing, scroll, go back, or try another path.
3. For login, captcha, payment, transfer, deletion, or authorization confirmation, use Take_over so the user can handle it.
4. Coordinates are relative 0-1000 values. (0,0) is top-left and (1000,1000) is bottom-right.

## Actions
- Launch: do(action="Launch", app="WeChat")
- Tap: do(action="Tap", element=[500,500])
- Type: do(action="Type", text="Hello")
- Swipe: do(action="Swipe", start=[500,800], end=[500,200])
- Back: do(action="Back")
- Home: do(action="Home")
- Long Press: do(action="Long Press", element=[500,500])
- Double Tap: do(action="Double Tap", element=[500,500])
- Wait: do(action="Wait", duration="2 seconds")
- Take_over: do(action="Take_over", message="User confirmation is required")
- finish: finish(message="Done")

## Output Format
You may briefly state your observation, but the final answer must contain exactly one do(...) or finish(...) action. Do not output JSON or OpenAI tool_calls.
""".trimIndent()
        )
    }

    fun buildDoTextRetryPrompt(context: UIContext, retryState: VLMToolCallRetryState): String {
        val locale = currentLocale()
        return buildString {
            appendLine(
                t(
                    locale,
                    "系统无法解析你上一轮的 Shizuku 文本动作：${retryState.failureReason.orEmpty().ifBlank { "缺少 do(...) 或 finish(...)" }}",
                    "The system could not parse your previous Shizuku text action: ${retryState.failureReason.orEmpty().ifBlank { "missing do(...) or finish(...)" }}"
                )
            )
            appendLine(
                t(
                    locale,
                    "请本轮严格输出一个动作，例如 do(action=\"Tap\", element=[500,500]) 或 finish(message=\"已完成\")。",
                    "In this turn, output exactly one action, for example do(action=\"Tap\", element=[500,500]) or finish(message=\"Done\")."
                )
            )
            appendLine("${t(locale, "用户任务", "User task")}: ${context.overallTask}")
            appendLine("${t(locale, "当前子目标", "Current sub-goal")}: ${context.activeGoal()}")
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
                    "若你判断下一步是点击、输入、滑动、返回、等待或结束，请直接使用对应工具。",
                    "If the next step should be tap, type, scroll, go back, wait, or finish, call the matching tool directly."
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
