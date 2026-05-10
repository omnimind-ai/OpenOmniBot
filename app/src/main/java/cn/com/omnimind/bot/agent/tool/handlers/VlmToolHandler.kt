package cn.com.omnimind.bot.agent.tool.handlers

import android.provider.Settings
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class VlmToolHandler(
    private val helper: SharedHelper,
    private val scope: CoroutineScope
) : ToolHandler {
    override val toolNames: Set<String> = setOf("vlm_task")

    data class VlmExecutionArgs(
        val goal: String,
        val packageName: String?,
        val needSummary: Boolean,
        val startFromCurrent: Boolean
    )

    data class VlmArgsSanitizeResult(
        val args: VlmExecutionArgs,
        val reasons: List<String>
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return executeVlmTask(args, env.userMessage, env.runtimeContextRepository, env.currentPackageName, env.resolvedSkills, callback)
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
            helper.ensureRunActive()
            val missing = checkExecutionPrerequisites()
            if (missing.isNotEmpty()) {
                return helper.permissionRequiredResult(callback, missing)
            }
            val goal = args["goal"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing goal")
            val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
            val needSummary = args["needSummary"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val startFromCurrent = args["startFromCurrent"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val rawArgs = VlmExecutionArgs(goal = goal, packageName = packageName?.takeIf { it.isNotBlank() }, needSummary = needSummary, startFromCurrent = startFromCurrent)
            val appNameToPackage = runtimeContextRepository.getAppNameToPackageMap()
            val sanitized = sanitizeVlmExecutionArgs(rawArgs = rawArgs, userMessage = userMessage, appNameToPackage = appNameToPackage, currentPackageName = currentPackageName)
            val safeArgs = sanitized.args
            if (sanitized.reasons.isNotEmpty()) {
                OmniLog.w("VlmToolHandler", "vlm_task args corrected: reasons=${sanitized.reasons.joinToString(",")}")
            }
            helper.ensureRunActive()
            val outcome = VlmToolCoordinator.executeNewTask(
                context = helper.context,
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
                progressReporter = { progress, extras -> helper.reportToolProgress(callback, "vlm_task", progress, extras) }
            )
            val payloadJson = helper.encodeLocalizedPayload(outcome.toPayload())
            when (outcome.status) {
                VlmToolOutcomeStatus.WAITING_INPUT -> {
                    val question = outcome.waitingQuestion ?: outcome.message.ifBlank { "请提供继续执行所需的信息。" }
                    val localizedQuestion = helper.localized(question)
                    callback.onClarifyRequired(localizedQuestion, null)
                    ToolExecutionResult.Clarify(localizedQuestion, null)
                }
                VlmToolOutcomeStatus.SCREEN_LOCKED -> {
                    val localizedQuestion = helper.localized(outcome.message)
                    callback.onClarifyRequired(localizedQuestion, null)
                    ToolExecutionResult.Clarify(localizedQuestion, null)
                }
                VlmToolOutcomeStatus.ERROR, VlmToolOutcomeStatus.CANCELLED -> {
                    helper.errorResult("vlm_task", outcome.errorMessage ?: outcome.message, "视觉执行失败")
                }
                VlmToolOutcomeStatus.FINISHED -> {
                    ToolExecutionResult.ContextResult(
                        toolName = "vlm_task",
                        summaryText = helper.localized(outcome.finishedContent ?: outcome.summaryText ?: outcome.message.ifBlank { "视觉任务已完成" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
                VlmToolOutcomeStatus.TIMEOUT -> {
                    ToolExecutionResult.ContextResult(
                        toolName = "vlm_task",
                        summaryText = helper.localized("视觉任务超时，设备上可能仍在继续执行"),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error("vlm_task", helper.localized(e.message ?: "Unknown error")) }
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
        val explicitCurrentIntent = isExplicitCurrentPageIntent(userMessage) || isExplicitCurrentPageIntent(rawArgs.goal)
        val openAppIntent = isLikelyOpenAppIntent(userMessage) || isLikelyOpenAppIntent(rawArgs.goal)
        val detectedTargetPackage = detectTargetAppPackage(userMessage, appNameToPackage) ?: detectTargetAppPackage(rawArgs.goal, appNameToPackage)
        if (packageName == null && openAppIntent && detectedTargetPackage != null) {
            packageName = detectedTargetPackage
            reasons.add("open_app_intent_autofill_package")
        }
        val currentPackage = currentPackageName?.trim()?.takeIf { it.isNotEmpty() }
        val assistantPackage = helper.context.packageName
        val targetPackage = packageName ?: detectedTargetPackage
        if (startFromCurrent && !explicitCurrentIntent) { startFromCurrent = false; reasons.add("start_from_current_without_current_intent") }
        if (startFromCurrent && openAppIntent) { startFromCurrent = false; reasons.add("open_app_should_not_start_from_current") }
        if (startFromCurrent && targetPackage != null && currentPackage != null && targetPackage != currentPackage) { startFromCurrent = false; reasons.add("target_package_differs_from_current_package") }
        if (startFromCurrent && currentPackage == assistantPackage && targetPackage != null && targetPackage != assistantPackage) { startFromCurrent = false; reasons.add("assistant_page_cannot_start_external_app_from_current") }
        return VlmArgsSanitizeResult(args = rawArgs.copy(packageName = packageName, startFromCurrent = startFromCurrent), reasons = reasons.distinct())
    }

    private fun detectTargetAppPackage(text: String, appNameToPackage: Map<String, String>): String? {
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
        val markers = listOf("当前页面", "当前应用", "当前界面", "这个页面", "这个界面", "这里", "在这", "正在看的", "继续刚才", "继续之前", "从当前")
        return markers.any { normalized.contains(normalizeIntentText(it)) }
    }

    private fun isLikelyOpenAppIntent(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = normalizeIntentText(text)
        val openVerbs = listOf("打开", "启动", "进入", "点开")
        val hasOpenVerb = openVerbs.any { normalized.contains(it) }
        if (!hasOpenVerb) return false
        val followUpActionWords = listOf("搜索", "发送", "回复", "聊天", "下单", "支付", "付款", "购买", "浏览", "查看", "看看", "总结", "答题", "填写", "输入", "点击", "并", "然后", "再", "之后", "顺便")
        return followUpActionWords.none { normalized.contains(it) }
    }

    private fun normalizeIntentText(text: String): String {
        return text.lowercase().replace(Regex("\\s+"), "")
            .replace("\u201c", "").replace("\u201d", "").replace("\"", "").replace("'", "")
            .replace("。", "").replace("，", "").replace(",", "").replace("！", "").replace("!", "")
            .replace("？", "").replace("?", "")
    }

    private fun checkExecutionPrerequisites(): List<String> {
        val missing = mutableListOf<String>()
        if (!AssistsUtil.Core.isAccessibilityServiceEnabled()) { missing.add("无障碍权限") }
        if (!Settings.canDrawOverlays(helper.context)) { missing.add("悬浮窗权限") }
        return missing
    }
}
