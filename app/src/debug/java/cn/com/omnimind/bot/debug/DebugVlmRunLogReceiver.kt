package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.mcp.VlmTaskRequest
import cn.com.omnimind.bot.runlog.OobOmniFlowToolkitService
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DebugVlmRunLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val goal = intent?.getStringExtra("goal")?.takeIf { it.isNotBlank() } ?: "打开 Settings"
        val prelaunch = intent?.getBooleanExtra("prelaunch", true) ?: true
        val packageName = if (prelaunch) {
            intent?.getStringExtra("packageName")?.takeIf { it.isNotBlank() } ?: "com.android.settings"
        } else {
            null
        }
        val maxSteps = intent?.getIntExtra("maxSteps", 1)?.takeIf { it > 0 } ?: 1
        val register = intent?.getBooleanExtra("register", true) ?: true
        val profileId = intent?.getStringExtra("profileId")?.trim().orEmpty()
        val modelId = intent?.getStringExtra("modelId")?.trim().orEmpty()

        scope.launch {
            val result = runCatching {
                run(appContext, goal, packageName, maxSteps, register, profileId, modelId)
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, "debug-vlm-runlog-result.json").writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private suspend fun run(
        context: Context,
        goal: String,
        packageName: String?,
        maxSteps: Int,
        register: Boolean,
        profileId: String,
        modelId: String,
    ): Map<String, Any?> {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        val configuredBinding = configureVlmBindingIfRequested(context, profileId, modelId)
        waitForAccessibility()

        val outcome = VlmToolCoordinator.executeNewTask(
            context = context,
            request = VlmTaskRequest(
                goal = goal,
                packageName = packageName,
                maxSteps = maxSteps,
                needSummary = false,
            ),
            scope = scope,
        )
        val runId = outcome.taskId
        val record = InternalRunLogStore.getRun(context, runId)
        val convert = if (outcome.status == VlmToolOutcomeStatus.FINISHED && record?.success == true) {
            OobOmniFlowToolkitService(context).convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to register,
                    "function_id" to "debug_${runId.replace('-', '_')}",
                    "name" to "Debug VLM RunLog",
                    "description" to goal,
                )
            )
        } else {
            null
        }

        return linkedMapOf(
            "success" to (outcome.status == VlmToolOutcomeStatus.FINISHED && convert?.get("success") == true),
            "goal" to goal,
            "packageName" to packageName,
            "configured_binding" to configuredBinding,
            "outcome" to outcome.toPayload(),
            "run_id" to runId,
            "runlog_found" to (record != null),
            "runlog_success" to record?.success,
            "runlog_card_count" to (record?.cards?.size ?: 0),
            "convert" to convert,
        )
    }

    private fun configureVlmBindingIfRequested(
        context: Context,
        profileId: String,
        modelId: String,
    ): Map<String, Any?>? {
        if (profileId.isEmpty() && modelId.isEmpty()) return null

        val resolvedProfileId = profileId.ifEmpty {
            ModelProviderConfigStore.getEditingProfileId()
        }
        val resolvedModelId = modelId.ifEmpty {
            SceneModelBindingStore.getBinding("scene.dispatch.model")?.modelId.orEmpty()
        }
        require(resolvedProfileId.isNotEmpty()) { "profileId is empty" }
        require(resolvedModelId.isNotEmpty()) { "modelId is empty" }

        SceneModelBindingStore.saveBinding(
            sceneId = "scene.vlm.operation.primary",
            providerProfileId = resolvedProfileId,
            modelId = resolvedModelId,
        )
        AgentAiCapabilityConfigSync.get(context).syncFileFromStores()
        AssistsCoreManager.dispatchAgentAiConfigChanged(
            source = "debug_vlm_runlog",
            path = "scene.vlm.operation.primary",
        )
        return linkedMapOf(
            "sceneId" to "scene.vlm.operation.primary",
            "profileId" to resolvedProfileId,
            "modelId" to resolvedModelId,
        )
    }

    private suspend fun waitForAccessibility() {
        repeat(50) {
            if (AssistsService.instance != null) return
            delay(200L)
        }
        error("OOB accessibility service is not bound")
    }

    companion object {
        private const val TAG = "DebugVlmRunLogReceiver"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
