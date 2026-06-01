package cn.com.omnimind.bot.agent.tool.handlers

import android.provider.Settings
import cn.com.omnimind.assists.AgentVlmUiSession
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
import java.util.UUID

class VlmToolHandler(
    private val helper: SharedHelper,
    private val scope: CoroutineScope
) : ToolHandler {
    override val toolNames: Set<String> = setOf(AgentToolNames.VLM_TASK)

    data class VlmExecutionArgs(
        val goal: String,
        val packageName: String?,
        val needSummary: Boolean,
        val startFromCurrent: Boolean,
        val maxSteps: Int?,
        val waitTimeoutMs: Long?,
        val model: String?,
        val disableOmniFlowRecall: Boolean,
        val allowOmniFlowFunctionAutoExecute: Boolean
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
        return executeVlmTask(args, env.userMessage, env.attachments, env.runtimeContextRepository, env.currentPackageName, env.resolvedSkills, callback, toolHandle)
    }

    private suspend fun executeVlmTask(
        args: JsonObject,
        userMessage: String,
        attachments: List<Map<String, Any?>>,
        runtimeContextRepository: AgentRuntimeContextRepository,
        currentPackageName: String?,
        resolvedSkills: List<ResolvedSkillContext>,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val vlmTaskId = UUID.randomUUID().toString()
        toolHandle.bindStopAction {
            VlmToolCoordinator.cancelTask(vlmTaskId, scope)
        }
        return try {
            helper.ensureRunActive()
            val goal = args["goal"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing goal")
            val packageName = firstString(args, "packageName", "package_name")
            val needSummary = firstBoolean(args, "needSummary", "need_summary") ?: false
            val startFromCurrent = firstBoolean(args, "startFromCurrent", "start_from_current", "skipGoHome", "skip_go_home") ?: false
            val maxSteps = firstInt(args, "maxSteps", "max_steps")?.coerceIn(1, 64)
            val waitTimeoutMs = firstWaitTimeoutMs(args)
            val model = firstString(args, "model", "modelId", "model_id")
            val disableOmniFlowRecall = firstBoolean(
                args,
                "disableOmniFlowRecall",
                "disable_omniflow_recall",
                "disableRecall",
                "disable_recall"
            ) ?: false
            val explicitFunctionAutoExecute = hasAnyKey(
                args,
                "allowOmniFlowFunctionAutoExecute",
                "allow_omniflow_function_auto_execute",
                "autoExecuteFunction",
                "auto_execute_function"
            )
            val allowOmniFlowFunctionAutoExecute = firstBoolean(
                args,
                "allowOmniFlowFunctionAutoExecute",
                "allow_omniflow_function_auto_execute",
                "autoExecuteFunction",
                "auto_execute_function"
            ) ?: shouldInferFunctionAutoExecute(
                userMessage = userMessage,
                goal = goal,
                resolvedSkills = resolvedSkills,
                disableOmniFlowRecall = disableOmniFlowRecall,
                explicitFunctionAutoExecute = explicitFunctionAutoExecute
            )
            val rawArgs = VlmExecutionArgs(
                goal = goal,
                packageName = packageName?.takeIf { it.isNotBlank() },
                needSummary = needSummary,
                startFromCurrent = startFromCurrent,
                maxSteps = maxSteps,
                waitTimeoutMs = waitTimeoutMs,
                model = model?.takeIf { it.isNotBlank() },
                disableOmniFlowRecall = disableOmniFlowRecall,
                allowOmniFlowFunctionAutoExecute = allowOmniFlowFunctionAutoExecute
            )
            val appNameToPackage = runtimeContextRepository.getAppNameToPackageMap()
            val detectedTargetPackage = detectTargetAppPackage(userMessage, appNameToPackage)
                ?: detectTargetAppPackage(goal, appNameToPackage)
            val uploadedImageOnly = hasUploadedImageAttachment(attachments) &&
                    !hasScreenAutomationIntent("$userMessage\n$goal", rawArgs, detectedTargetPackage)
            if (uploadedImageOnly) {
                val payloadJson = helper.encodeLocalizedPayload(
                    mapOf(
                        "status" to "SKIPPED",
                        "reason" to "uploaded_image_analysis_does_not_need_screen_task",
                        "message" to helper.localized(
                            "用户上传的图片已经在当前对话里可见。不要启动小万/VLM 屏幕任务；请直接基于图片内容回答。"
                        )
                    )
                )
                return ToolExecutionResult.ContextResult(
                    toolName = AgentToolNames.VLM_TASK,
                    summaryText = helper.localized(
                        "已跳过小万屏幕任务：上传图片分析应直接由当前多模态模型回答。"
                    ),
                    previewJson = payloadJson,
                    rawResultJson = payloadJson,
                    success = true
                )
            }
            val missing = checkExecutionPrerequisites()
            if (missing.isNotEmpty()) {
                return helper.permissionRequiredResult(callback, missing)
            }
            val sanitized = sanitizeVlmExecutionArgs(rawArgs = rawArgs, userMessage = userMessage, appNameToPackage = appNameToPackage, currentPackageName = currentPackageName)
            val safeArgs = sanitized.args
            if (sanitized.reasons.isNotEmpty()) {
                OmniLog.w("VlmToolHandler", "vlm_task args corrected: reasons=${sanitized.reasons.joinToString(",")}")
            }
            helper.ensureRunActive()
            AgentVlmUiSession.beginTask(toolHandle.runId, vlmTaskId)
            val outcome = VlmToolCoordinator.executeNewTask(
                context = helper.context,
                request = VlmTaskRequest(
                    goal = safeArgs.goal,
                    model = safeArgs.model,
                    maxSteps = safeArgs.maxSteps,
                    waitTimeoutMs = safeArgs.waitTimeoutMs,
                    packageName = if (safeArgs.startFromCurrent) null else safeArgs.packageName,
                    needSummary = safeArgs.needSummary,
                    skipGoHome = safeArgs.startFromCurrent,
                    stepSkillGuidance = resolvedSkills.joinToString("\n\n") { it.stepGuidance() },
                    disableOmniFlowRecall = safeArgs.disableOmniFlowRecall,
                    allowOmniFlowFunctionAutoExecute = safeArgs.allowOmniFlowFunctionAutoExecute
                ),
                scope = scope,
                taskIdOverride = vlmTaskId,
                returnOnWaitingInput = false,
                progressReporter = { progress, extras ->
                    val streamKind = (extras["agentStreamKind"] ?: extras["kind"])
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                    val parentCardId = toolHandle.currentCardId()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    val traceExtras = linkedMapOf<String, Any?>().apply {
                        putAll(extras)
                        put("progress", progress)
                        put("childRunId", vlmTaskId)
                        put("child_run_id", vlmTaskId)
                        put("parentSpanKind", AgentToolNames.VLM_TASK)
                        put("parent_span_kind", AgentToolNames.VLM_TASK)
                        parentCardId?.let {
                            put("parentCardId", it)
                            put("parent_card_id", it)
                        }
                    }
                    if (
                        streamKind == "tool_started" ||
                        streamKind == "tool_progress" ||
                        streamKind == "tool_completed"
                    ) {
                        callback.onToolCardEvent(
                            streamKind,
                            traceExtras + mapOf(
                                "spanKind" to "vlm_step",
                                "span_kind" to "vlm_step"
                            )
                        )
                    } else {
                        helper.reportToolProgress(
                            callback,
                            AgentToolNames.VLM_TASK,
                            progress,
                            traceExtras + mapOf(
                                "spanKind" to AgentToolNames.VLM_TASK,
                                "span_kind" to AgentToolNames.VLM_TASK
                            ),
                            toolHandle = toolHandle
                        )
                    }
                }
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
                    helper.errorResult(AgentToolNames.VLM_TASK, outcome.errorMessage ?: outcome.message, "视觉执行失败")
                }
                VlmToolOutcomeStatus.FINISHED -> {
                    ToolExecutionResult.ContextResult(
                        toolName = AgentToolNames.VLM_TASK,
                        summaryText = helper.localized(outcome.finishedContent ?: outcome.summaryText ?: outcome.message.ifBlank { "视觉任务已完成" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
                VlmToolOutcomeStatus.TIMEOUT -> {
                    ToolExecutionResult.ContextResult(
                        toolName = AgentToolNames.VLM_TASK,
                        summaryText = helper.localized("视觉任务超时，设备上可能仍在继续执行"),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
            }
        } catch (e: CancellationException) {
            VlmToolCoordinator.cancelTask(vlmTaskId, scope)
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(AgentToolNames.VLM_TASK, helper.localized(e.message ?: "Unknown error"))
        } finally {
            AgentVlmUiSession.endTask(vlmTaskId)
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
        var goal = rawArgs.goal
        val rawGoalOpenOnly = isLikelyOpenAppIntent(rawArgs.goal)
        val userHasFullDeviceGoal = hasScreenAutomationIntent(
            userMessage,
            rawArgs.copy(packageName = null, startFromCurrent = false),
            detectedTargetPackage = null
        )
        if (rawGoalOpenOnly && userHasFullDeviceGoal && !sameNormalizedIntent(userMessage, rawArgs.goal)) {
            goal = userMessage.trim().takeIf { it.isNotEmpty() } ?: rawArgs.goal
            reasons.add("open_app_goal_expanded_to_user_goal")
        }
        val openAppIntent = isLikelyOpenAppIntent(userMessage) || rawGoalOpenOnly
        val detectedTargetPackage = detectTargetAppPackage(userMessage, appNameToPackage) ?: detectTargetAppPackage(rawArgs.goal, appNameToPackage)
        if (packageName == null && openAppIntent && detectedTargetPackage != null) {
            packageName = detectedTargetPackage
            reasons.add("open_app_intent_autofill_package")
        }
        val currentPackage = currentPackageName?.trim()?.takeIf { it.isNotEmpty() }
        val assistantPackage = helper.context.packageName
        val targetPackage = packageName ?: detectedTargetPackage
        if (startFromCurrent && openAppIntent) { startFromCurrent = false; reasons.add("open_app_should_not_start_from_current") }
        if (startFromCurrent && targetPackage != null && currentPackage != null && targetPackage != currentPackage) { startFromCurrent = false; reasons.add("target_package_differs_from_current_package") }
        if (startFromCurrent && currentPackage == assistantPackage && targetPackage != null && targetPackage != assistantPackage) { startFromCurrent = false; reasons.add("assistant_page_cannot_start_external_app_from_current") }
        return VlmArgsSanitizeResult(
            args = rawArgs.copy(
                goal = goal,
                packageName = packageName,
                startFromCurrent = startFromCurrent
            ),
            reasons = reasons.distinct()
        )
    }

    private fun firstInt(args: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val primitive = args[key]?.jsonPrimitive ?: continue
            val raw = primitive.contentOrNull?.trim().orEmpty()
            val parsed = raw.toIntOrNull()
                ?: raw.toDoubleOrNull()?.toInt()
                ?: continue
            return parsed
        }
        return null
    }

    private fun firstLong(args: JsonObject, vararg keys: String): Long? {
        for (key in keys) {
            val primitive = args[key]?.jsonPrimitive ?: continue
            val raw = primitive.contentOrNull?.trim().orEmpty()
            val parsed = raw.toLongOrNull()
                ?: raw.toDoubleOrNull()?.toLong()
                ?: continue
            return parsed
        }
        return null
    }

    private fun firstWaitTimeoutMs(args: JsonObject): Long? {
        firstLong(args, "waitTimeoutMs", "wait_timeout_ms", "timeoutMs", "timeout_ms")
            ?.let { return it }
        return firstLong(args, "timeoutSeconds", "timeout_seconds")?.times(1000L)
    }

    private fun firstString(args: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val raw = args[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            if (raw != null) return raw
        }
        return null
    }

    private fun hasAnyKey(args: JsonObject, vararg keys: String): Boolean =
        keys.any { key -> args.containsKey(key) }

    private fun shouldInferFunctionAutoExecute(
        userMessage: String,
        goal: String,
        resolvedSkills: List<ResolvedSkillContext>,
        disableOmniFlowRecall: Boolean,
        explicitFunctionAutoExecute: Boolean
    ): Boolean {
        if (disableOmniFlowRecall || explicitFunctionAutoExecute) return false
        val text = "$userMessage\n$goal".lowercase()
        val hasReuseIntent = listOf(
            "复用",
            "function",
            "omniflow",
            "oob",
            "按之前",
            "之前那个",
            "上次",
            "已有",
            "保存的",
            "录制的",
            "replay",
            "reuse",
            "saved function"
        ).any { text.contains(it) }
        val hasFunctionSkill = resolvedSkills.any { skill ->
            skill.skillId == "omniflow" ||
                skill.skillId == "oob-function-management" ||
                skill.skillId == "omniflow-function-enhancer"
        }
        return hasReuseIntent || hasFunctionSkill
    }

    private fun firstBoolean(args: JsonObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val primitive = args[key]?.jsonPrimitive ?: continue
            primitive.booleanOrNull?.let { return it }
            primitive.contentOrNull?.trim()?.toBooleanStrictOrNull()?.let { return it }
        }
        return null
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
        val followUpActionWords = listOf(
            "搜索", "发送", "回复", "聊天", "下单", "支付", "付款", "购买", "浏览", "查看", "看看",
            "总结", "答题", "填写", "输入", "点击", "点一", "点杯", "点餐", "点单", "帮我", "替我",
            "订购", "预订", "预约", "买", "并", "然后", "再", "之后", "顺便"
        )
        return followUpActionWords.none { normalized.contains(it) }
    }

    private fun sameNormalizedIntent(left: String, right: String): Boolean {
        return normalizeIntentText(left) == normalizeIntentText(right)
    }

    private fun normalizeIntentText(text: String): String {
        return text.lowercase().replace(Regex("\\s+"), "")
            .replace("\u201c", "").replace("\u201d", "").replace("\"", "").replace("'", "")
            .replace("。", "").replace("，", "").replace(",", "").replace("！", "").replace("!", "")
            .replace("？", "").replace("?", "")
    }

    private fun hasUploadedImageAttachment(attachments: List<Map<String, Any?>>): Boolean {
        return attachments.any { item ->
            item["isImage"]?.toString()?.toBooleanStrictOrNull() == true ||
                    item["mimeType"]?.toString()?.trim()?.lowercase()?.startsWith("image/") == true ||
                    looksLikeImagePath(item["path"]?.toString()) ||
                    looksLikeImagePath(item["url"]?.toString()) ||
                    item["dataUrl"]?.toString()?.trim()?.lowercase()?.startsWith("data:image/") == true
        }
    }

    private fun looksLikeImagePath(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase()?.substringBefore("?").orEmpty()
        if (normalized.isBlank()) return false
        return normalized.endsWith(".png") ||
                normalized.endsWith(".jpg") ||
                normalized.endsWith(".jpeg") ||
                normalized.endsWith(".webp") ||
                normalized.endsWith(".gif") ||
                normalized.endsWith(".bmp") ||
                normalized.endsWith(".heic") ||
                normalized.endsWith(".heif")
    }

    private fun hasScreenAutomationIntent(text: String, args: VlmExecutionArgs, detectedTargetPackage: String?): Boolean {
        if (args.startFromCurrent || !args.packageName.isNullOrBlank() || !detectedTargetPackage.isNullOrBlank()) return true
        if (text.isBlank()) return false
        val normalized = normalizeIntentText(text)
        val screenMarkers = listOf(
            "当前屏幕", "当前页面", "当前界面", "当前应用", "手机屏幕", "这个页面", "这个界面",
            "onthisscreen", "currentscreen", "currentpage", "currentapp", "phoneui", "devicescreen"
        )
        val operationMarkers = listOf(
            "点击", "点一下", "点开", "滑动", "下滑", "上滑", "输入", "填写", "选择", "勾选",
            "返回", "发送", "回复", "发布", "下单", "支付", "购买",
            "操控", "操作", "控制", "自动执行", "帮我在手机", "帮我操作", "替我操作",
            "帮我", "替我", "点一", "点杯", "点餐", "点单", "订购", "预订", "预约", "买",
            "tap", "click", "swipe", "scroll", "type", "enter", "select",
            "send", "reply", "post", "pay", "buy", "control", "operate"
        )
        return screenMarkers.any { normalized.contains(normalizeIntentText(it)) } ||
                operationMarkers.any { normalized.contains(normalizeIntentText(it)) }
    }

    private fun checkExecutionPrerequisites(): List<String> {
        val missing = mutableListOf<String>()
        if (!AssistsUtil.Core.isAccessibilityServiceEnabled()) { missing.add("无障碍权限") }
        if (!Settings.canDrawOverlays(helper.context)) { missing.add("悬浮窗权限") }
        return missing
    }
}
