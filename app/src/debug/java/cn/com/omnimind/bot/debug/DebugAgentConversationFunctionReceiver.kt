package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentToolExposurePolicy
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DebugAgentConversationFunctionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val functionId = intent?.getStringExtra("functionId")
            ?: intent?.getStringExtra("function_id")
            ?: DEFAULT_FUNCTION_ID
        val targetPackage = intent?.getStringExtra("targetPackage")
            ?: intent?.getStringExtra("target_package")
            ?: DEFAULT_TARGET_PACKAGE
        val profileId = intent?.getStringExtra("profileId")
            ?: intent?.getStringExtra("profile_id")
        val modelId = intent?.getStringExtra("modelId")
            ?: intent?.getStringExtra("model_id")
        val waitMs = intent?.getLongExtra("waitMs", DEFAULT_WAIT_MS)
            ?.coerceIn(1_000L, MAX_WAIT_MS)
            ?: DEFAULT_WAIT_MS
        val userMessage = intent.decodeBase64Extra("userMessageBase64")
            ?: buildDefaultUserMessage(functionId, targetPackage)

        scope.launch {
            val result = runCatching {
                runAgentConversationValidation(
                    context = appContext,
                    functionId = functionId,
                    targetPackage = targetPackage,
                    userMessage = userMessage,
                    profileId = profileId?.trim().orEmpty(),
                    modelId = modelId?.trim().orEmpty(),
                    waitMs = waitMs,
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "function_id" to functionId,
                    "target_package" to targetPackage,
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun runAgentConversationValidation(
        context: Context,
        functionId: String,
        targetPackage: String,
        userMessage: String,
        profileId: String,
        modelId: String,
        waitMs: Long,
    ): Map<String, Any?> {
        val service = AgentRunService(context)
        val conversation = ConversationDomainService(context).createConversation(
            title = "Debug Agent Function Conversation",
            mode = "normal",
        )
        val conversationId = (conversation["id"] as? Number)?.toLong()
            ?: conversation["id"]?.toString()?.toLongOrNull()
            ?: error("conversation id is invalid")
        val taskId = "debug-agent-conversation-function-${System.currentTimeMillis()}"
        val request = linkedMapOf<String, Any?>(
            "taskId" to taskId,
            "userMessage" to userMessage,
            "title" to "Debug Agent Function Conversation",
            "conversationMode" to "normal",
            "toolProfile" to AgentToolExposurePolicy.PROFILE_FUNCTION_MANAGEMENT,
            "allowedTools" to listOf(
                "oob_function_register",
                "oob_function_list",
                "oob_function_guard_check",
                "oob_function_run",
                "oob_function_delete",
            ),
        )
        if (profileId.isNotEmpty() && modelId.isNotEmpty()) {
            request["modelOverride"] = mapOf(
                "providerProfileId" to profileId,
                "modelId" to modelId,
            )
        }

        val accepted = service.startConversationRun(conversationId, request)
        val startedAt = System.currentTimeMillis()
        waitForAgentIdle(context, startedAt, waitMs)
        delay(AGENT_SETTLE_MS)

        val functionPayload = OobOmniFlowToolkitService(context).getFunction(
            mapOf("function_id" to functionId)
        )
        val functionRegistered = functionPayload["success"] == true
        val runPayload = if (functionRegistered) {
            OobOmniFlowToolkitService(context).runFunction(
                mapOf(
                    "function_id" to functionId,
                    "goal" to "Validate agent-conversation registered Function replay.",
                    "arguments" to emptyMap<String, Any?>(),
                )
            )
        } else {
            emptyMap<String, Any?>()
        }
        val runSuccess = runPayload["success"] == true

        val messages = ConversationDomainService(context).listConversationMessages(
            conversationId = conversationId,
            conversationMode = "normal",
        )
        return linkedMapOf<String, Any?>(
            "success" to (functionRegistered && runSuccess),
            "source" to "debug_agent_conversation_function_management",
            "agent_path" to "MCP/WebChat AgentRunService -> AssistsCoreManager -> OmniAgentExecutor -> AgentToolRegistry/Router",
            "conversation_id" to conversationId,
            "task_id" to taskId,
            "accepted" to accepted,
            "function_id" to functionId,
            "target_package" to targetPackage,
            "tool_profile" to AgentToolExposurePolicy.PROFILE_FUNCTION_MANAGEMENT,
            "allowed_tools" to request["allowedTools"],
            "function_registered" to functionRegistered,
            "run_success" to runSuccess,
            "run_summary" to runSummary(runPayload),
            "message_count" to messages.size,
            "assistant_tail" to messages.asReversed()
                .firstOrNull { it["role"]?.toString() == "assistant" }
                ?.get("text")
                ?.toString()
                ?.take(1000),
            "function" to compactFunctionSummary(functionPayload),
        )
    }

    private suspend fun waitForAgentIdle(context: Context, startedAt: Long, waitMs: Long) {
        val manager = cn.com.omnimind.bot.manager.AssistsCoreManager.sharedInstanceOrCreate(context)
        while (System.currentTimeMillis() - startedAt < waitMs) {
            if (!manager.hasActiveAgentRuns()) return
            delay(500L)
        }
        error("agent conversation did not finish within ${waitMs}ms")
    }

    private fun compactFunctionSummary(payload: Map<String, Any?>): Map<String, Any?> {
        val summary = payload["summary"] as? Map<*, *>
        return linkedMapOf<String, Any?>(
            "success" to payload["success"],
            "function_id" to payload["function_id"],
            "name" to (summary?.get("name") ?: payload["name"]),
            "step_count" to summary?.get("step_count"),
            "omniflow_step_count" to summary?.get("omniflow_step_count"),
            "parameter_names" to summary?.get("parameter_names"),
        )
    }

    private fun runSummary(payload: Map<String, Any?>): Map<String, Any?> {
        val oobResult = payload["oob_result"] as? Map<*, *>
        val timing = payload["timing"] as? Map<*, *>
        return linkedMapOf<String, Any?>(
            "success" to payload["success"],
            "run_id" to payload["run_id"],
            "actions_executed" to payload["actions_executed"],
            "step_count" to oobResult?.get("step_count"),
            "success_step_count" to oobResult?.get("success_step_count"),
            "model_used" to oobResult?.get("model_used"),
            "runner_duration_ms" to timing?.get("runner_duration_ms"),
        )
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "DebugAgentConversationFunction"
        private const val RESULT_FILE = "debug-agent-conversation-function-result.json"
        private const val DEFAULT_FUNCTION_ID = "debug_agent_conversation_open_settings"
        private const val DEFAULT_TARGET_PACKAGE = "com.android.settings"
        private const val DEFAULT_WAIT_MS = 180_000L
        private const val MAX_WAIT_MS = 600_000L
        private const val AGENT_SETTLE_MS = 1_000L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun buildDefaultUserMessage(functionId: String, targetPackage: String): String =
            """
            Use the available OOB Function management tools to create and verify a reusable instruction.
            Register a reusable instruction with functionId "$functionId".
            Name it "Debug agent conversation open settings".
            Description: "Open Android Settings from an agent conversation."
            The instruction must target package "$targetPackage" and contain exactly these steps:
            1. open_app packageName "$targetPackage"
            2. finished content "Settings opened"
            Then list Functions, guard-check "$functionId", run "$functionId" with confirmed=true, and report the function id and run success.
            Do not call unrelated tools and do not ask follow-up questions.
            """.trimIndent()
    }
}
