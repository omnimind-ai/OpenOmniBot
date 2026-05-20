package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAiCapabilityConfigSync
import cn.com.omnimind.bot.manager.AssistsCoreManager
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DebugModelProviderConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val baseUrl = intent.stringExtra("baseUrl", "base_url")
        val apiKey = intent.stringExtra("apiKey", "api_key")
        val modelId = intent.stringExtra("modelId", "model_id")
        val profileId = intent.stringExtra("profileId", "profile_id")
            .ifBlank { DEFAULT_PROFILE_ID }
        val name = intent.stringExtra("name")
            .ifBlank { DEFAULT_PROFILE_NAME }
        val protocolType = intent.stringExtra("protocolType", "protocol_type")
            .ifBlank { "openai_compatible" }
        val sceneIds = parseSceneIds(intent.stringExtra("sceneIds", "scene_ids"))

        scope.launch {
            val result = runCatching {
                configure(
                    context = appContext,
                    profileId = profileId,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    modelId = modelId,
                    protocolType = protocolType,
                    sceneIds = sceneIds,
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val json = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
        }
    }

    private fun configure(
        context: Context,
        profileId: String,
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        protocolType: String,
        sceneIds: List<String>,
    ): Map<String, Any?> {
        require(baseUrl.isNotBlank()) { "baseUrl is empty" }
        require(apiKey.isNotBlank()) { "apiKey is empty" }
        require(modelId.isNotBlank()) { "modelId is empty" }
        require(sceneIds.isNotEmpty()) { "sceneIds is empty" }

        val profile = ModelProviderConfigStore.saveProfile(
            id = profileId,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            protocolType = protocolType,
        )
        sceneIds.forEach { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profile.id,
                modelId = modelId,
            )
        }
        seedFlutterManualModelId(context, profile.id, modelId)
        AgentAiCapabilityConfigSync.get(context).syncFileFromStores()
        AssistsCoreManager.dispatchAgentAiConfigChanged(
            source = "debug_model_provider_config",
            path = "broadcast_configure_model_provider",
        )

        return linkedMapOf(
            "success" to true,
            "profile" to profile.toSafePayload(),
            "modelId" to modelId,
            "configuredSceneIds" to sceneIds,
            "sceneBindings" to SceneModelBindingStore.getBindingEntries().map { it.toPayload() },
        )
    }

    private fun seedFlutterManualModelId(context: Context, profileId: String, modelId: String) {
        val normalizedProfileId = profileId.trim()
        val normalizedModelId = modelId.trim()
        if (normalizedProfileId.isEmpty() || normalizedModelId.isEmpty()) return

        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val key = "flutter.manual_provider_model_ids_v2"
        val current = runCatching {
            JSONObject(prefs.getString(key, null).orEmpty())
        }.getOrElse {
            JSONObject()
        }
        val ids = current.optJSONArray(normalizedProfileId) ?: JSONArray()
        val exists = (0 until ids.length()).any { index ->
            ids.optString(index).trim() == normalizedModelId
        }
        if (!exists) {
            ids.put(normalizedModelId)
        }
        current.put(normalizedProfileId, ids)
        prefs.edit().putString(key, current.toString()).apply()
    }

    private fun Intent?.stringExtra(vararg names: String): String {
        if (this == null) return ""
        names.forEach { name ->
            getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return it
            }
        }
        return ""
    }

    private fun parseSceneIds(raw: String): List<String> {
        return raw
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_SCENE_IDS }
    }

    private fun ModelProviderProfile.toSafePayload(): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "name" to name,
            "baseUrl" to baseUrl,
            "protocolType" to protocolType,
            "apiKeyConfigured" to apiKey.isNotBlank(),
            "configured" to isConfigured(),
        )
    }

    private fun SceneModelBindingEntry.toPayload(): Map<String, Any?> {
        return linkedMapOf(
            "sceneId" to sceneId,
            "providerProfileId" to providerProfileId,
            "modelId" to modelId,
        )
    }

    companion object {
        private const val TAG = "DebugModelProviderConfigReceiver"
        private const val RESULT_FILE = "debug-model-provider-config-result.json"
        private const val DEFAULT_PROFILE_ID = "debug-runtime-provider"
        private const val DEFAULT_PROFILE_NAME = "Debug Runtime Provider"
        private val DEFAULT_SCENE_IDS = listOf(
            "scene.dispatch.model",
            "scene.vlm.operation.primary",
            "scene.compactor.context",
            "scene.compactor.context.chat",
        )
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
