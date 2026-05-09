package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult
import cn.com.omnimind.assists.api.bean.VlmTaskTerminalStatus
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.api.enums.toStatus
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.task.Task
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.omniintelligence.models.AgentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 视觉模型执行任务
 */
open class VLMOperationTask(
    open val executionTaskEventApi: ExecutionTaskEventApi?,
    override val taskChangeListener: TaskChangeListener,
    private val onMessagePushListener: OnMessagePushListener? = null,
    private val needSummary: Boolean = false,
    override val taskManager: TaskManager
) : Task(taskChangeListener,taskManager), DeviceOperator {
    private val Tag = "VLMOperationTask"
    private companion object {
        private const val SUMMARY_GENERATION_TIMEOUT_MS = 20_000L
    }

    private lateinit var vlmOperationService: VLMOperationService
    private lateinit var androidDeviceOperator: AndroidDeviceOperator
    private lateinit var onTaskFinishListener: () -> Unit?
    
    /** 
     * 取消请求标记，用于在 delay 期间检查取消状态
     * 公开属性，供 ExecutionUIImpl 在 delay 循环中检查
     */
    @Volatile
    var isCancellationRequested: Boolean = false
        private set
    
    private var executionRecordId: Long = -1L // 执行记录 ID，用于任务结束时更新状态
    private var isSubTask: Boolean = false // 标识当前任务是否为子任务

    @Volatile
    private var pauseRequested: Boolean = false
    private lateinit var streamClient: VLMStreamClient

    private var taskContext: Context? = null

    // INFO动作等待通道：用于挂起任务等待用户回复
    private val userInputChannel = Channel<String>(Channel.Factory.UNLIMITED)

    // 用户主动暂停通道：用于用户点击"接管"按钮时暂停任务
    private val userPauseChannel = Channel<Unit>(Channel.Factory.CONFLATED)

    // 总结Sheet准备就绪通道：用于等待ChatBotSheet加载完成后再推送总结
    private val summarySheetReadyChannel = Channel<Unit>(Channel.Factory.CONFLATED)

    private var goal: String? = null
    private var taskStartTime = 0L//任务开始时间
    private var setStartWithNotShowReadFlag = false

    fun appendExternalMemory(memory: String): Boolean {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return false
        if (!this::vlmOperationService.isInitialized) return false
        vlmOperationService.addExternalMemory(trimmed)
        return true
    }

    /**
     * Append a priority event to the VLM task
     * @param memory The event message
     * @param eventType The event type (e.g., "file_received")
     * @param suggestCompletion Whether to suggest VLM complete the task
     */
    fun appendPriorityEvent(memory: String, eventType: String, suggestCompletion: Boolean = false): Boolean {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) return false
        if (!this::vlmOperationService.isInitialized) return false
        vlmOperationService.addPriorityEvent(trimmed, eventType, suggestCompletion)
        return true
    }

    override suspend fun onTaskCreated() {
        super.onTaskCreated()
        streamClient = HttpVLMStreamClient(scope = taskScope)
        vlmOperationService = VLMOperationService(
            this,
            streamClient,
            onInfoAction = { question ->
                handleInfoAction(question)
            },
            onPauseCheck = {
                checkAndHandlePause()
            },
            isSubTask = isSubTask

        )
        androidDeviceOperator = AndroidDeviceOperator(executionTaskEventApi, taskContext)
    }

    /**
     * 处理INFO动作：小猫显示提示信息，用户在当前页面操作，操作完成后点击小猫继续
     */
    private suspend fun handleInfoAction(question: String): String {
        OmniLog.d(Tag, "INFO动作触发，向用户推送问题：$question")
        var mQuestion = if (question.isNotEmpty()) {
            "\n${question}"
        } else {
            question
        }
        val infoMessage = "小万需要你的帮助：$mQuestion"
        AccessibilityController.restoreKeyboard()

        onTaskStop(TaskFinishType.WAITING_INPUT, infoMessage)
        notifyTerminalResult(
            VlmTaskTerminalResult(
                status = VlmTaskTerminalStatus.WAITING_INPUT,
                message = infoMessage,
                needSummary = needSummary || hasSummaryIntent(goal),
                waitingQuestion = infoMessage
            )
        )

        if (onMessagePushListener != null) {
            try {
                onMessagePushListener.onVLMRequestUserInput(infoMessage)
                OmniLog.d(Tag, "已通知Flutter层")
            } catch (e: Exception) {
                OmniLog.e(Tag, "通知UI层失败: ${e.message}")
            }
        }

        OmniLog.d(Tag, "等待用户完成操作并点击继续...")
        val userConfirmation = userInputChannel.receive()
        OmniLog.d(Tag, "收到用户确认：$userConfirmation")

        AccessibilityController.hideKeyboard()
        setStartWithNotShowReadFlag = true
        onTaskStarted()
        taskStartTime = System.currentTimeMillis()
        return "用户已完成操作：$userConfirmation"
    }

    /**
     * 接收用户回复（公开方法，供外部调用）
     */
    fun provideUserInput(input: String) {
        OmniLog.d(Tag, "接收用户输入：$input")
        taskScope.launch {
            userInputChannel.send(input)
        }
    }

    /**
     * 检查并处理用户暂停请求（VLMOperationService每步执行前调用）
     */
    private suspend fun checkAndHandlePause() {
        if (pauseRequested) {
            OmniLog.d(Tag, "检测到用户暂停请求，进入暂停状态")
            pauseRequested = false // 重置标志
            handleUserPause()
        }
    }

    /**
     * 用户主动暂停任务：不推送按钮卡片，直接切换小猫状态为"继续"
     */
    private suspend fun handleUserPause() {
        onTaskStop(TaskFinishType.USER_PAUSED, "")
        executionTaskEventApi?.onVlmTaskPaused(this)
        // 不推送按钮卡片，直接通知UI层切换小猫状态
        AccessibilityController.Companion.restoreKeyboard()
        if (onMessagePushListener != null) {
            try {
                onMessagePushListener.onVLMRequestUserInput("已接管控制，完成操作后点击继续")
            } catch (e: Exception) {
                OmniLog.e(Tag, "通知UI层失败: ${e.message}")
            }
        }
        userPauseChannel.receive() // 阻塞等待用户点击继续
        AccessibilityController.Companion.hideKeyboard()
        setStartWithNotShowReadFlag = true
        onTaskStarted()
        taskStartTime = System.currentTimeMillis()
    }

    /**
     * 请求暂停任务（公开方法，供UI调用）
     */
    fun requestPause() {
        OmniLog.d(Tag, "收到暂停请求")
        pauseRequested = true
    }

    /**
     * 从暂停状态恢复（公开方法，供UI调用）
     */
    fun resumeFromPause() {
        OmniLog.d(Tag, "收到继续请求")
        taskScope.launch {
            userPauseChannel.send(Unit)
        }
    }

    /**
     * 通知总结Sheet已准备就绪（公开方法，供外部调用）
     * ChatBotSheet加载上下文后会调用此方法
     */
    fun notifySummarySheetReady() {
        OmniLog.d(Tag, "收到总结Sheet准备就绪通知")
        taskScope.launch {
            summarySheetReadyChannel.send(Unit)
        }
    }

    private fun notifyTerminalResult(result: VlmTaskTerminalResult) {
        try {
            onMessagePushListener?.onVlmTaskResult(result)
        } catch (e: Exception) {
            OmniLog.e(Tag, "通知VLM终态结果失败: ${e.message}")
        }
    }

    private fun extractFinishedContent(report: TaskExecutionReport): String {
        val finishedStep = report.executionTrace.lastOrNull { it.action is FinishedAction }
        val fromResult = finishedStep?.result?.trim().orEmpty()
        if (fromResult.isNotEmpty()) return fromResult

        val fromAction = (finishedStep?.action as? FinishedAction)?.content?.trim().orEmpty()
        if (fromAction.isNotEmpty()) return fromAction

        val lastResult = report.executionTrace.asReversed()
            .mapNotNull { it.result?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .firstOrNull()
        if (!lastResult.isNullOrEmpty()) return lastResult

        return "任务完成"
    }

    fun start(
        context: Context,
        goal: String,
        model: String?,
        maxSteps: Int?,
        packageName: String?,
        onTaskFinishListener: () -> Unit,
        skipGoHome: Boolean = false,  // 是否跳过回到主页，从当前页面开始执行
        stepSkillGuidance: String = ""
    ) {
        this.goal = goal;
        this.taskContext = context
        this.onTaskFinishListener = onTaskFinishListener
        super.start {
            AccessibilityController.Companion.hideKeyboard()
            val currentPackageName = packageName ?: (AccessibilityController.Companion.getPackageName() ?: "")
            val installedApps = AccessibilityController.Companion.mapInstalledApplications()
            val shouldSummary = (needSummary || hasSummaryIntent(goal))

            executionRecordId = DatabaseHelper.saveExecutionRecord(
                context,
                goal,
                currentPackageName,
                "vlm",
                // 总结任务使用时间戳作为 suggestionId，确保每次总结独立记录不会聚合
                if (shouldSummary) "${System.currentTimeMillis()}" else goal,
                null,
                if (shouldSummary) "summary" else "vlm"
            )
            OmniLog.d(
                Tag,
                "VLM Summary Decision: needSummary=$needSummary shouldSummary=${
                    (needSummary || hasSummaryIntent(goal))
                }"
            )
            OmniLog.d(Tag, "VLM Operation Task Is Running ! skipGoHome=$skipGoHome")
            try {
                taskStartTime = System.currentTimeMillis()
                val taskExecutionReport = if (!isSubTask) {
                    executeOpenAppFastPath(
                        goal = goal,
                        installedApps = installedApps,
                        packageName = packageName
                    ) ?: vlmOperationService.executeTask(
                        goal = goal,
                        installedApps = installedApps,
                        model = model ?: "scene.vlm.operation.primary",
                        maxSteps = maxSteps,
                        packageName = packageName,
                        skipGoHome = skipGoHome,
                        summary = shouldSummary,
                        currentStepGoal = goal,
                        stepSkillGuidance = stepSkillGuidance
                    )
                } else {
                    vlmOperationService.executeTask(
                        goal = goal,
                        installedApps = installedApps,
                        model = model ?: "scene.vlm.operation.primary",
                        maxSteps = maxSteps,
                        packageName = packageName,
                        skipGoHome = skipGoHome,  // 使用传入的 skipGoHome 参数
                        summary = shouldSummary,
                        currentStepGoal = goal,
                        stepSkillGuidance = stepSkillGuidance

                    )
                }
                OmniLog.d(Tag, "VLM Operation Task Finished: $taskExecutionReport")
                val finishType = when {
                    taskExecutionReport.success -> TaskFinishType.FINISH
                    else -> TaskFinishType.ERROR
                }
                val finishMessage = taskExecutionReport.error.orEmpty()
                OmniLog.i(
                    Tag,
                    "VLM task terminal state: finishType=$finishType success=${taskExecutionReport.success} error=${taskExecutionReport.error.orEmpty()}"
                )

                val summaryResult = if (shouldSummary && taskExecutionReport.summaryScreenshotList != null) {
                    pushSummary(
                        goal = goal,
                        model = model,
                        report = taskExecutionReport
                    )
                } else {
                    SummaryPushResult()
                }

                if (taskExecutionReport.success) {
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.FINISHED,
                            message = extractFinishedContent(taskExecutionReport),
                            finishedContent = extractFinishedContent(taskExecutionReport),
                            summaryText = summaryResult.summaryText,
                            needSummary = shouldSummary,
                            feedback = taskExecutionReport.feedback,
                            summaryUnavailable = summaryResult.summaryUnavailable
                        )
                    )
                } else {
                    val errorMessage = finishMessage.ifBlank { "任务执行失败" }
                    notifyTerminalResult(
                        VlmTaskTerminalResult(
                            status = VlmTaskTerminalStatus.ERROR,
                            message = errorMessage,
                            finishedContent = null,
                            summaryText = summaryResult.summaryText,
                            errorMessage = errorMessage,
                            needSummary = shouldSummary,
                            feedback = taskExecutionReport.feedback,
                            summaryUnavailable = summaryResult.summaryUnavailable
                        )
                    )
                }
                onTaskStop(finishType, finishMessage)
                onTaskDestroy()
            } catch (e: PrivacyBlockedException) {
                notifyTerminalResult(
                    VlmTaskTerminalResult(
                        status = VlmTaskTerminalStatus.ERROR,
                        message = e.message ?: "应用未授权，已被隐私设置限制",
                        errorMessage = e.message ?: "应用未授权，已被隐私设置限制",
                        needSummary = needSummary || hasSummaryIntent(goal)
                    )
                )
                onTaskStop(TaskFinishType.ERROR, e.message ?: "应用未授权，已被隐私设置限制")
                onTaskDestroy()
            } catch (e: Http429Exception) {
                notifyTerminalResult(
                    VlmTaskTerminalResult(
                        status = VlmTaskTerminalStatus.ERROR,
                        message = e.message ?: "请求过于频繁",
                        errorMessage = e.message ?: "请求过于频繁",
                        needSummary = needSummary || hasSummaryIntent(goal)
                    )
                )
                onTaskStop(TaskFinishType.ERROR, e.message)
                onTaskDestroy()
            } catch (e: CancellationException) {
                OmniLog.i(Tag, "VLM Operation Task cancelled")
            } catch (e: Exception) {
                OmniLog.e(Tag, "VLM Operation Task Error: ${e.message}")
                notifyTerminalResult(
                    VlmTaskTerminalResult(
                        status = VlmTaskTerminalStatus.ERROR,
                        message = e.message ?: "任务执行异常",
                        errorMessage = e.message ?: "任务执行异常",
                        needSummary = needSummary || hasSummaryIntent(goal)
                    )
                )
                onTaskStop(TaskFinishType.ERROR, e.message ?: "任务执行异常")
                onTaskDestroy()
            }

        }
    }

    override suspend fun onTaskStarted() {
        if (setStartWithNotShowReadFlag) {
            setStartWithNotShowReadFlag = false
        } else if (!isSubTask) {  // 子任务时不显示"小万即将为您执行任务..."提示
            executionTaskEventApi?.onReadyStartVLMTask(this)
        }

        super.onTaskStarted()

    }

    /**
     * 专门用于sequence执行的启动方法，完全不操作UI状态
     */
    fun startAsSequenceSubTask(
        goal: String,
        model: String?,
        maxSteps: Int?,
        onTaskFinishListener: () -> Unit
    ) {
        this.onTaskFinishListener = onTaskFinishListener
        this.isSubTask = true  // 标记为子任务
        this.taskContext = BaseApplication.instance

        super.start {
            taskStartTime = System.currentTimeMillis()
            AccessibilityController.Companion.hideKeyboard()
            val installedApps = AccessibilityController.Companion.mapInstalledApplications()
            OmniLog.d(Tag, "VLM Operation Sequence Sub Task Is Running !")
            try {
                val report = vlmOperationService.executeTask(
                    goal = goal,
                    installedApps = installedApps,
                    model = model ?: "scene.vlm.operation.primary",
                    maxSteps = maxSteps,
                    skipGoHome = true  // 作为子任务执行时，不回退到桌面
                )
                OmniLog.d(Tag, "VLM Operation Sequence Sub Task Finished")
                onTaskStop(
                    if (report.success) TaskFinishType.FINISH else TaskFinishType.ERROR,
                    report.error.orEmpty()
                )
                onTaskDestroy()
            } catch (e: PrivacyBlockedException) {
                onTaskStop(TaskFinishType.ERROR, e.message ?: "应用未授权，已被隐私设置限制")
                onTaskDestroy()
            } catch (e: Exception) {
                onTaskStop(TaskFinishType.ERROR, e.message ?: "任务执行异常")
                onTaskDestroy()
            }
        }
    }

    override suspend fun onTaskStop(finishType: TaskFinishType, message: String) {
        super.onTaskStop(finishType, message)
        // 更新执行记录的状态
        if (finishType != TaskFinishType.WAITING_INPUT && finishType != TaskFinishType.USER_PAUSED && taskContext != null) {
            DatabaseHelper.updateExecutionRecordStatus(executionRecordId, finishType.toStatus())
        }
    }

    private fun hasSummaryIntent(goal: String?): Boolean {
        if (goal.isNullOrBlank()) return false
        val keywords = listOf("总结", "汇总", "整理", "要点", "概括", "归纳", "提炼", "总结一下")
        return keywords.any { goal.contains(it) }
    }

    private suspend fun executeOpenAppFastPath(
        goal: String,
        installedApps: Map<String, String>,
        packageName: String?
    ): TaskExecutionReport? {
        if (packageName.isNullOrBlank()) return null
        if (!shouldUseOpenAppFastPath(goal, packageName, installedApps)) {
            OmniLog.i(
                Tag,
                "Skip open-app fast path: goal requires more than opening app. goal=$goal package=$packageName"
            )
            return null
        }

        val currentPackage = AccessibilityController.getPackageName().orEmpty()
        if (currentPackage == packageName) {
            OmniLog.i(Tag, "Open-app fast path hit: already in target package=$packageName")
            return TaskExecutionReport(
                success = true,
                goal = goal,
                totalSteps = 0,
                executionTrace = emptyList(),
                finalContext = UIContext(
                    overallTask = goal,
                    currentStepGoal = goal,
                    installedApplications = installedApps
                ),
                error = null
            )
        }

        val launchResult = androidDeviceOperator.launchApplication(packageName)
        val afterLaunchPackage = AccessibilityController.getPackageName().orEmpty()
        if (launchResult.success && afterLaunchPackage == packageName) {
            OmniLog.i(Tag, "Open-app fast path hit: launched target package=$packageName")
            return TaskExecutionReport(
                success = true,
                goal = goal,
                totalSteps = 0,
                executionTrace = emptyList(),
                finalContext = UIContext(
                    overallTask = goal,
                    currentStepGoal = goal,
                    installedApplications = installedApps
                ),
                error = null
            )
        }

        OmniLog.w(
            Tag,
            "Open-app fast path failed: pkg=$packageName, success=${launchResult.success}, current=$afterLaunchPackage"
        )
        return null
    }

    private fun shouldUseOpenAppFastPath(
        goal: String,
        packageName: String,
        installedApps: Map<String, String>
    ): Boolean {
        val normalizedGoal = normalizeGoalForIntentMatching(goal)
        if (normalizedGoal.isBlank()) {
            return false
        }

        val openVerbs = listOf("打开", "启动", "进入", "点开").map(::normalizeGoalForIntentMatching)
        val openVerbCount = openVerbs.sumOf { verb ->
            Regex(Regex.escape(verb)).findAll(normalizedGoal).count()
        }
        if (openVerbCount != 1 || openVerbs.none { normalizedGoal.contains(it) }) {
            return false
        }

        val appName = installedApps[packageName].orEmpty()
        val targetTokens = buildList {
            normalizeGoalForIntentMatching(appName).takeIf { it.isNotBlank() }?.let(::add)
            packageName.substringAfterLast('.')
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeGoalForIntentMatching)
                ?.takeIf { it.length >= 3 }
                ?.let(::add)
        }.distinct()
        if (targetTokens.isEmpty() || targetTokens.none { normalizedGoal.contains(it) }) {
            return false
        }

        var remainder = normalizedGoal
        listOf("请帮我", "帮我", "请", "麻烦你", "麻烦", "帮忙").forEach { prefix ->
            remainder = remainder.removePrefix(normalizeGoalForIntentMatching(prefix))
        }
        openVerbs.forEach { verb ->
            remainder = remainder.replaceFirst(verb, "")
        }
        listOf("一下", "下", "app", "应用", "软件", "客户端").forEach { filler ->
            remainder = remainder.replaceFirst(normalizeGoalForIntentMatching(filler), "")
        }
        targetTokens.sortedByDescending { it.length }.forEach { token ->
            remainder = remainder.replaceFirst(token, "")
        }

        val trailingPoliteWords = listOf("即可", "就行", "就可以", "就好", "好了", "吧", "呀", "哈", "啦")
        trailingPoliteWords.forEach { word ->
            remainder = remainder.removePrefix(normalizeGoalForIntentMatching(word))
            remainder = remainder.removeSuffix(normalizeGoalForIntentMatching(word))
        }
        return remainder.isBlank()
    }

    private fun normalizeGoalForIntentMatching(text: String): String {
        if (text.isBlank()) return ""
        return text.lowercase()
            .replace(Regex("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》·`~@#%^&*_+=|<>/\\\\-]+"), "")
    }

    private data class SummaryPushResult(
        val summaryText: String? = null,
        val summaryUnavailable: Boolean = false
    )

    private suspend fun pushSummary(goal: String, model: String?, report: TaskExecutionReport): SummaryPushResult {
        val listener = onMessagePushListener ?: return SummaryPushResult(summaryUnavailable = true)
        var summaryTaskId: String? = null
        var summaryStarted = false

        try {
            val steps = report.executionTrace.takeLast(20)
            val finishedFromTrace = steps.lastOrNull { it.action.name == "finished" }
            val traceSummary = finishedFromTrace?.result
                ?: (finishedFromTrace?.action as? FinishedAction)?.content.orEmpty()
            val prompt = """# Role: 智能视觉信息整合与决策专家

# Task
你将收到用户的**原始目标**以及一组**按时间顺序排列的屏幕截图**（Agent 的执行过程）。
你的任务是：**忽略操作过程中的无关细节（如点击位置、加载状态），像人类浏览网页一样，从截图中“阅读”并提取关键信息，最终为用户生成一份直接响应其目标的交付物。**

# Input Data
## 1. 用户原始目标 (User Goal)
$goal

## 2. 视觉证据 (Visual Evidence)
*（附带了一组连续的屏幕截图，记录了搜索和浏览的全过程）*
请仔细阅读附带的图片序列。图片内容可能包含：搜索引擎结果、具体网页详情、地图路线、表格数据等。

# Thinking Process (CoT)
1. **目标拆解**：明确用户到底想要什么？（是攻略、表格、代码、还是摘要？）
2. **视觉信息提取**：
   - 按顺序浏览图片。
   - **过滤噪点**：忽略浏览器的地址栏、侧边栏广告、弹窗关闭按钮等 UI 元素。
   - **抓取干货**：重点识别图片中的正文文本、价格数字、时间表、景点介绍、优缺点评价等。
   - **关联上下文**：如果图1是搜索列表，图2是详情页，则以图2的详情为准。
3. **逻辑重组**：将从多张图片中提取的碎片信息整合成一个连贯的整体。
4. **交付生成**：根据目标类型，输出最终结果。

# Constraints
- **直接回答**：不要包含“根据搜索结果”、“我整理了以下内容”等开场白，直接给出融合相关的浏览结果。
- **禁止流水账**：不要描述图片（例如不要说“第1张图显示了百度首页...”），直接使用图里的信息回答问题。
- **事实准确**：严禁编造图片中不存在的数值（如价格、时间），如果图片中未展示关键信息，请注明“未知”。
- **格式规范**：确保易读性。

# Final Answer
(请直接输出针对用户目标的最终整理结果...)
""".trimIndent()

            val modelToUse = "scene.compactor.context"
            val vlmPayload = AgentRequest.Payload.VLMChatPayload(
                model = modelToUse, text = prompt, images = report.summaryScreenshotList!!
            )

            val summaryText = withTimeoutOrNull(SUMMARY_GENERATION_TIMEOUT_MS) {
                // 1. 等待主聊天页面准备就绪的回调
                OmniLog.d(Tag, "等待主聊天页面准备就绪通知...")
                summarySheetReadyChannel.receive()
                OmniLog.d(Tag, "主聊天页面已准备就绪，开始推送总结...")

                // 2. 先推送"总结开始"消息，让前端显示"总结中"状态
                summaryTaskId = "vlm-summary-${System.currentTimeMillis()}"
                summaryStarted = true
                listener.onChatMessage(summaryTaskId!!, "", "summary_start")
                OmniLog.d(Tag, "已推送 summary_start，前端应显示'总结中'状态")

                // 3. 调用VLM API获取总结（这一步可能需要较长时间）
                OmniLog.d(Tag, "开始调用VLM API生成总结...")
                val response = HttpController.postVLMRequest(vlmPayload)
                response.message.ifBlank { traceSummary }
            }

            if (summaryText == null) {
                OmniLog.w(Tag, "pushSummary timeout after ${SUMMARY_GENERATION_TIMEOUT_MS}ms")
                return SummaryPushResult(summaryUnavailable = true)
            }

            if (summaryText.isBlank()) {
                OmniLog.w(Tag, "pushSummary: empty summaryText, skip.")
                return SummaryPushResult(summaryUnavailable = true)
            }
            OmniLog.d(Tag, "VLM API返回总结内容，长度: ${summaryText.length}")

            // 4. 推送总结消息内容
            val payload = JSONObject().apply { put("text", summaryText) }.toString()
            listener.onChatMessage(summaryTaskId!!, payload, null)

            // 5. 更新执行记录的总结内容（使用记录 ID 精确更新，避免覆盖历史记录）
            if (executionRecordId > 0) {
                DatabaseHelper.updateExecutionRecordContentById(
                    id = executionRecordId,
                    content = summaryText
                )
                OmniLog.d(Tag, "总结已更新到数据库 (id=$executionRecordId)")
            } else {
                OmniLog.w(Tag, "无效的记录ID (id=$executionRecordId)，跳过总结更新")
            }

            // 6. 保存到Message表，包含在聊天上下文中
            if (summaryText.isNotBlank()) {
                DatabaseHelper.insertTaskResultMessage(
                    messageId = summaryTaskId!!,
                    taskType = "vlm_summary",
                    content = summaryText,
                    executionRecordId = executionRecordId,
                    metadata = mapOf("goal" to goal)
                )
                OmniLog.d(Tag, "VLM总结已保存到Message表")
            }
            return SummaryPushResult(summaryText = summaryText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OmniLog.e(Tag, "pushSummary error: ${e.message}")
            return SummaryPushResult(summaryUnavailable = true)
        } finally {
            if (summaryStarted && summaryTaskId != null) {
                try {
                    listener.onChatMessageEnd(summaryTaskId!!)
                } catch (e: Exception) {
                    OmniLog.e(Tag, "pushSummary end callback error: ${e.message}")
                }
            }
        }
    }

    override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
        return androidDeviceOperator.clickCoordinate(x, y)
    }

    override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult {
        return androidDeviceOperator.longClickCoordinate(x, y, duration)
    }

    override suspend fun inputText(text: String): OperationResult {
        return androidDeviceOperator.inputText(text)
    }

    override suspend fun pressHotKey(key: String): OperationResult {
        return androidDeviceOperator.pressHotKey(key)
    }

    suspend fun inputTextViaShell(text: String): OperationResult {
        return androidDeviceOperator.inputTextViaShell(text)
    }

    override suspend fun copyToClipboard(text: String): OperationResult {
        return androidDeviceOperator.copyToClipboard(text)
    }

    override suspend fun getClipboard(): String? {
        return androidDeviceOperator.getClipboard()
    }

    override suspend fun slideCoordinate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long
    ): OperationResult {
        return androidDeviceOperator.slideCoordinate(x1, y1, x2, y2, duration)
    }

    override suspend fun goHome(): OperationResult {
        return androidDeviceOperator.goHome()
    }

    override suspend fun goBack(): OperationResult {
        return androidDeviceOperator.goBack()
    }

    override suspend fun launchApplication(packageName: String): OperationResult {
        return androidDeviceOperator.launchApplication(packageName)
    }

    override suspend fun captureScreenshot(): String {
        return androidDeviceOperator.captureScreenshot()
    }

    override fun getLastScreenshotWidth(): Int {
        return androidDeviceOperator.getLastScreenshotWidth()
    }

    override fun getLastScreenshotHeight(): Int {
        return androidDeviceOperator.getLastScreenshotHeight()
    }

    override fun getDisplayWidth(): Int {
        return androidDeviceOperator.getDisplayWidth()
    }

    override fun getDisplayHeight(): Int {
        return androidDeviceOperator.getDisplayHeight()
    }

    override suspend fun showInfo(message: String) {
        androidDeviceOperator.showInfo(message)
    }

    fun finishTask() {
        OmniLog.d(Tag, "Finishing VLM Operation Task")
        isCancellationRequested = true
        notifyTerminalResult(
            VlmTaskTerminalResult(
                status = VlmTaskTerminalStatus.CANCELLED,
                message = "任务已取消",
                needSummary = needSummary || hasSummaryIntent(goal)
            )
        )
        super.finishTask {
        }
        taskScope.cancel()
    }

    fun cancelTask() {
        OmniLog.d(Tag, "Cancelling VLM Operation Task - cancelling taskScope immediately")
        isCancellationRequested = true
        notifyTerminalResult(
            VlmTaskTerminalResult(
                status = VlmTaskTerminalStatus.CANCELLED,
                message = "任务已取消",
                needSummary = needSummary || hasSummaryIntent(goal)
            )
        )
        taskScope.cancel("Task cancelled by user")
    }

    override suspend fun onTaskDestroy() {
        AccessibilityController.Companion.restoreKeyboard()
        onTaskFinishListener.invoke()
        super.onTaskDestroy()
    }

    override fun getTaskType(): TaskType {
        return TaskType.VLM_OPERATION_EXECUTION
    }
}
