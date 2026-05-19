package cn.com.omnimind.bot.localmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import cn.com.omnimind.bot.omniinfer.OmniInferLocalRuntime
import cn.com.omnimind.bot.omniinfer.OmniInferLiteRtModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferMnnModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferQnnModelsManager
import cn.com.omnimind.bot.ui.channel.MnnLocalModelsChannel
import com.omniinfer.server.OmniInferServer
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalModelFeatureInstaller {
    fun install(context: Context) {
        LocalModelFeature.install(context, OmniInferLocalModelFeature)
    }
}

private object OmniInferLocalModelFeature : LocalModelFeatureDelegate {
    override val enabled: Boolean = true

    private val channel = MnnLocalModelsChannel()

    override fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        OmniInferServer.init(applicationContext)
        OmniInferLocalRuntime.setContext(applicationContext)
        OmniInferModelsManager.setContext(applicationContext)
        OmniInferMnnModelsManager.setContext(applicationContext)
        OmniInferQnnModelsManager.setContext(applicationContext)
        OmniInferLiteRtModelsManager.setContext(applicationContext)
    }

    override fun onChannelManagerCreate(context: Context) {
        channel.onCreate(context)
    }

    override fun setChannel(flutterEngine: FlutterEngine) {
        channel.setChannel(flutterEngine)
    }

    override fun clearChannel() {
        channel.clear()
    }

    override fun handleAppOpen(activity: Activity) {
        OmniInferLocalRuntime.handleAppOpen(activity)
    }

    override fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        return MnnLocalModelsChannel.onActivityResult(activity, requestCode, resultCode, data)
    }

    override fun listBuiltinProviderModels(): List<Map<String, Any?>> {
        return OmniInferLocalRuntime.listBuiltinProviderModels()
    }

    override suspend fun prepareForRequest(
        profileId: String?,
        apiBase: String?,
        modelId: String,
    ): Boolean {
        val ggufReady = runCatching {
            OmniInferModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
        if (ggufReady) {
            return true
        }
        val mnnReady = runCatching {
            OmniInferMnnModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
        if (mnnReady) {
            return true
        }
        val liteRtReady = runCatching {
            OmniInferLiteRtModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
        if (liteRtReady) {
            return true
        }
        return runCatching {
            OmniInferQnnModelsManager.ensureModelReady(modelId)
        }.getOrDefault(false)
    }

    override fun getControlState(): Map<String, Any?> {
        return buildControlState()
    }

    override suspend fun control(
        action: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val normalizedAction = action.trim().lowercase()
        runCatching {
            when (normalizedAction) {
                "state", "get_state" -> buildControlState()
                "get_backend" -> mapOf(
                    "success" to true,
                    "enabled" to true,
                    "backend" to OmniInferLocalRuntime.getSelectedBackend()
                )
                "set_backend" -> {
                    val backend = stringArgument(arguments, "backend")
                        ?: stringArgument(arguments, "value")
                        ?: error("backend is required")
                    OmniInferLocalRuntime.setSelectedBackend(backend)
                    mapOf(
                        "success" to true,
                        "enabled" to true,
                        "backend" to OmniInferLocalRuntime.getSelectedBackend(),
                        "config" to currentConfig()
                    )
                }
                "get_config" -> buildControlState()
                "save_config" -> {
                    val config = mapArgument(arguments, "config") ?: arguments
                    mapOf(
                        "success" to true,
                        "enabled" to true,
                        "backend" to OmniInferLocalRuntime.getSelectedBackend(),
                        "config" to saveCurrentConfig(config)
                    )
                }
                "set_active_model" -> {
                    val modelId = stringArgument(arguments, "modelId")
                        ?: stringArgument(arguments, "model_id")
                        ?: stringArgument(arguments, "value")
                    mapOf(
                        "success" to true,
                        "enabled" to true,
                        "backend" to OmniInferLocalRuntime.getSelectedBackend(),
                        "config" to setCurrentActiveModel(modelId)
                    )
                }
                "start", "start_api_service" -> {
                    val modelId = stringArgument(arguments, "modelId")
                        ?: stringArgument(arguments, "model_id")
                    val exposeLan = booleanArgument(arguments, "exposeLan")
                        ?: booleanArgument(arguments, "expose_lan")
                        ?: true
                    val config = startCurrentApiService(modelId, exposeLan)
                    val state = buildControlState()
                    val success = config["success"] as? Boolean
                        ?: localModelServerStarted(state)
                    mapOf(
                        "success" to success,
                        "enabled" to true,
                        "backend" to OmniInferLocalRuntime.getSelectedBackend(),
                        "config" to config,
                        "state" to state,
                        "summary" to if (success) "已启动本地模型服务" else "本地模型服务未启动",
                        "error" to if (success) "" else config["error"]?.toString().orEmpty().ifBlank {
                            "local model server is not ready"
                        }
                    )
                }
                "stop", "stop_api_service" -> mapOf(
                    "success" to true,
                    "enabled" to true,
                    "backend" to OmniInferLocalRuntime.getSelectedBackend(),
                    "config" to stopCurrentApiService(),
                    "state" to buildControlState()
                )
                "list_installed", "list_installed_models" -> mapOf(
                    "success" to true,
                    "enabled" to true,
                    "backend" to OmniInferLocalRuntime.getSelectedBackend(),
                    "models" to listCurrentInstalledModels(
                        query = stringArgument(arguments, "query"),
                        category = stringArgument(arguments, "category")
                    )
                )
                "preload", "prepare" -> {
                    val modelId = stringArgument(arguments, "modelId")
                        ?: stringArgument(arguments, "model_id")
                        ?: error("modelId is required")
                    mapOf(
                        "success" to prepareForRequest(
                            profileId = stringArgument(arguments, "profileId")
                                ?: stringArgument(arguments, "profile_id"),
                            apiBase = stringArgument(arguments, "apiBase")
                                ?: stringArgument(arguments, "api_base"),
                            modelId = modelId
                        ),
                        "enabled" to true,
                        "backend" to OmniInferLocalRuntime.getSelectedBackend(),
                        "modelId" to modelId,
                        "state" to buildControlState()
                    )
                }
                else -> mapOf(
                    "success" to false,
                    "enabled" to true,
                    "action" to action,
                    "error" to "unsupported local model action"
                )
            }
        }.getOrElse { error ->
            mapOf(
                "success" to false,
                "enabled" to true,
                "action" to action,
                "error" to (error.message ?: error.javaClass.simpleName)
            )
        }
    }

    private fun buildControlState(): Map<String, Any?> {
        return mapOf(
            "success" to true,
            "enabled" to true,
            "available" to true,
            "backend" to OmniInferLocalRuntime.getSelectedBackend(),
            "port" to OmniInferLocalRuntime.getPort(),
            "baseUrl" to OmniInferLocalRuntime.getBaseUrl(),
            "ready" to OmniInferLocalRuntime.isReady(),
            "loadedBackend" to OmniInferLocalRuntime.getLoadedBackend(),
            "loadedModelId" to OmniInferLocalRuntime.getLoadedModelId(),
            "lanProxy" to OmniInferLocalRuntime.getLanProxyState(),
            "config" to currentConfig()
        )
    }

    private fun localModelServerStarted(state: Map<String, Any?>): Boolean {
        val loadedBackend = state["loadedBackend"]?.toString().orEmpty()
        val loadedModelId = state["loadedModelId"]?.toString().orEmpty()
        return state["ready"] == true &&
            loadedBackend == OmniInferLocalRuntime.getSelectedBackend() &&
            loadedModelId.isNotBlank()
    }

    private fun currentConfig(): Map<String, Any?> {
        return when (OmniInferLocalRuntime.getSelectedBackend()) {
            OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN -> OmniInferMnnModelsManager.getConfig()
            OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN -> OmniInferQnnModelsManager.getConfig()
            OmniInferLocalRuntime.BACKEND_LITERT -> OmniInferLiteRtModelsManager.getConfig()
            else -> OmniInferModelsManager.getConfig()
        }
    }

    private fun saveCurrentConfig(arguments: Map<*, *>): Map<String, Any?> {
        return when (OmniInferLocalRuntime.getSelectedBackend()) {
            OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN -> OmniInferMnnModelsManager.saveConfig(arguments)
            OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN -> OmniInferQnnModelsManager.saveConfig(arguments)
            OmniInferLocalRuntime.BACKEND_LITERT -> OmniInferLiteRtModelsManager.saveConfig(arguments)
            else -> OmniInferModelsManager.saveConfig(arguments)
        }
    }

    private fun setCurrentActiveModel(modelId: String?): Map<String, Any?> {
        return when (OmniInferLocalRuntime.getSelectedBackend()) {
            OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN -> OmniInferMnnModelsManager.setActiveModel(modelId)
            OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN -> OmniInferQnnModelsManager.setActiveModel(modelId)
            OmniInferLocalRuntime.BACKEND_LITERT -> OmniInferLiteRtModelsManager.setActiveModel(modelId)
            else -> OmniInferModelsManager.setActiveModel(modelId)
        }
    }

    private fun startCurrentApiService(modelId: String?, exposeLan: Boolean): Map<String, Any?> {
        return when (OmniInferLocalRuntime.getSelectedBackend()) {
            OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN -> OmniInferMnnModelsManager.startApiService(modelId, exposeLan)
            OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN -> OmniInferQnnModelsManager.startApiService(modelId, exposeLan)
            OmniInferLocalRuntime.BACKEND_LITERT -> OmniInferLiteRtModelsManager.startApiService(modelId, exposeLan)
            else -> OmniInferModelsManager.startApiService(modelId, exposeLan)
        }
    }

    private fun stopCurrentApiService(): Map<String, Any?> {
        return when (OmniInferLocalRuntime.getSelectedBackend()) {
            OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN -> OmniInferMnnModelsManager.stopApiService()
            OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN -> OmniInferQnnModelsManager.stopApiService()
            OmniInferLocalRuntime.BACKEND_LITERT -> OmniInferLiteRtModelsManager.stopApiService()
            else -> OmniInferModelsManager.stopApiService()
        }
    }

    private fun listCurrentInstalledModels(
        query: String?,
        category: String?,
    ): List<Map<String, Any?>> {
        return when (OmniInferLocalRuntime.getSelectedBackend()) {
            OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN -> OmniInferMnnModelsManager.listInstalledModels(query, category)
            OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN -> OmniInferQnnModelsManager.listInstalledModels(query, category)
            OmniInferLocalRuntime.BACKEND_LITERT -> OmniInferLiteRtModelsManager.listInstalledModels(query, category)
            else -> OmniInferModelsManager.listInstalledModels(query, category)
        }
    }

    private fun stringArgument(arguments: Map<String, Any?>, key: String): String? {
        return arguments[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun mapArgument(arguments: Map<String, Any?>, key: String): Map<*, *>? {
        return arguments[key] as? Map<*, *>
    }

    private fun booleanArgument(arguments: Map<String, Any?>, key: String): Boolean? {
        return when (val value = arguments[key]) {
            is Boolean -> value
            is String -> value.trim().lowercase().let {
                when (it) {
                    "1", "true", "yes", "on", "enabled" -> true
                    "0", "false", "no", "off", "disabled" -> false
                    else -> null
                }
            }
            is Number -> value.toInt() != 0
            else -> null
        }
    }
}
