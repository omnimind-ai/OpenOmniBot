package cn.com.omnimind.bot.omniinfer

import android.content.Context
import cn.com.omnimind.baselib.llm.MnnLocalProviderStateStore
import cn.com.omnimind.baselib.util.OmniLog
import com.omniinfer.server.OmniInferServer
import com.tencent.mmkv.MMKV

object OmniInferLocalRuntime {
    private const val TAG = "OmniInferLocalRuntime"
    const val BACKEND_LLAMA_CPP = "llama.cpp"
    const val BACKEND_OMNIINFER_MNN = "omniinfer-mnn"
    const val BACKEND_EXECUTORCH_QNN = "executorch-qnn"
    const val BACKEND_LITERT = "litert"

    private const val MMKV_ID = "omniinfer_config"
    private const val KEY_API_PORT = "apiPort"
    private const val KEY_SELECTED_BACKEND = "omniinfer_selected_backend"
    private const val KEY_LOADED_BACKEND = "omniinfer_loaded_backend"
    private const val KEY_LOADED_MODEL_ID = "omniinfer_loaded_model_id"
    private const val DEFAULT_PORT = 9099

    private var appContext: Context? = null
    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    fun setContext(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        syncProviderState()
    }

    fun getContext(): Context {
        return appContext ?: error("OmniInfer runtime context is not initialized")
    }

    fun normalizeBackend(rawBackend: String?): String {
        return when (rawBackend?.trim()) {
            BACKEND_OMNIINFER_MNN, "mnn" -> BACKEND_OMNIINFER_MNN
            BACKEND_EXECUTORCH_QNN, "qnn" -> BACKEND_EXECUTORCH_QNN
            BACKEND_LITERT, "litert-lm", "litertlm" -> BACKEND_LITERT
            else -> BACKEND_LLAMA_CPP
        }
    }

    fun getSelectedBackend(): String {
        val stored = mmkv.decodeString(KEY_SELECTED_BACKEND, BACKEND_LLAMA_CPP)
        val normalized = normalizeBackend(stored)
        if (normalized != stored) {
            mmkv.encode(KEY_SELECTED_BACKEND, normalized)
        }
        return normalized
    }

    fun setSelectedBackend(rawBackend: String) {
        mmkv.encode(KEY_SELECTED_BACKEND, normalizeBackend(rawBackend))
    }

    fun getPort(): Int {
        val port = mmkv.decodeInt(KEY_API_PORT, DEFAULT_PORT)
        return if (port > 0) port else DEFAULT_PORT
    }

    fun setPort(port: Int) {
        if (port > 0) {
            mmkv.encode(KEY_API_PORT, port)
            syncProviderState()
        }
    }

    fun getHost(): String = "127.0.0.1"

    fun getBaseUrl(): String = "http://${getHost()}:${getPort()}"

    fun isReady(): Boolean {
        syncProviderState()
        return OmniInferServer.isReady()
    }

    fun getLoadedBackend(): String {
        if (!OmniInferServer.isReady()) {
            clearLoadedState()
        }
        return mmkv.decodeString(KEY_LOADED_BACKEND, "").orEmpty()
    }

    fun getLoadedModelId(): String {
        if (!OmniInferServer.isReady()) {
            clearLoadedState()
        }
        return mmkv.decodeString(KEY_LOADED_MODEL_ID, "").orEmpty()
    }

    fun isModelLoaded(backend: String, modelId: String): Boolean {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isEmpty()) {
            return false
        }
        return isReady() &&
            getLoadedBackend() == normalizeBackend(backend) &&
            getLoadedModelId() == normalizedModelId
    }

    fun loadModel(
        modelId: String,
        modelPath: String,
        backend: String,
        extraConfig: Map<String, String>? = null,
        nCtx: Int = 16384,
    ): Boolean {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isEmpty() || modelPath.isBlank()) {
            OmniLog.w(TAG, "[loadModel] invalid params: modelId='$modelId', modelPath='$modelPath'")
            return false
        }
        val normalizedBackend = normalizeBackend(backend)
        val serverBackend = when (normalizedBackend) {
            BACKEND_OMNIINFER_MNN -> "mnn"
            BACKEND_EXECUTORCH_QNN -> "executorch-qnn"
            BACKEND_LITERT -> "litert"
            else -> BACKEND_LLAMA_CPP
        }
        val port = getPort()
        OmniLog.i(
            TAG,
            "[loadModel] >> OmniInferServer.loadModel(" +
                "modelId=$normalizedModelId, modelPath=$modelPath, " +
                "backend=$serverBackend, port=$port, nCtx=$nCtx, extraConfig=$extraConfig)"
        )
        val success = OmniInferServer.loadModel(
            modelPath = modelPath,
            backend = serverBackend,
            port = port,
            nCtx = nCtx,
            extraConfig = extraConfig,
        )
        OmniLog.i(TAG, "[loadModel] << OmniInferServer.loadModel result=$success")
        if (success) {
            mmkv.encode(KEY_LOADED_BACKEND, normalizedBackend)
            mmkv.encode(KEY_LOADED_MODEL_ID, normalizedModelId)
        } else {
            OmniInferServer.stop()
            clearLoadedState()
        }
        syncProviderState()
        return success
    }

    fun stop() {
        OmniInferServer.stop()
        clearLoadedState()
        syncProviderState()
    }

    fun handleAppOpen(context: Context) {
        setContext(context)
        when (getSelectedBackend()) {
            BACKEND_OMNIINFER_MNN -> OmniInferMnnModelsManager.handleAppOpen()
            BACKEND_EXECUTORCH_QNN -> OmniInferQnnModelsManager.handleAppOpen()
            BACKEND_LITERT -> OmniInferLiteRtModelsManager.handleAppOpen()
            else -> OmniInferModelsManager.handleAppOpen()
        }
    }

    fun listBuiltinProviderModels(): List<Map<String, Any?>> {
        val combined = LinkedHashMap<String, Map<String, Any?>>()
        (OmniInferModelsManager.listInstalledModels() +
            OmniInferMnnModelsManager.listInstalledModels() +
            OmniInferQnnModelsManager.listInstalledModels() +
            OmniInferLiteRtModelsManager.listInstalledModels())
            .forEach { model ->
                val modelId = model["id"]?.toString()?.trim().orEmpty()
                if (modelId.isNotEmpty()) {
                    combined.putIfAbsent(modelId, model)
                }
            }
        return combined.values.toList()
    }

    private fun syncProviderState() {
        val ready = OmniInferServer.isReady()
        if (!ready) {
            clearLoadedState()
        }
        MnnLocalProviderStateStore.update(
            port = getPort(),
            apiKey = "",
            ready = ready,
        )
    }

    private fun clearLoadedState() {
        mmkv.encode(KEY_LOADED_BACKEND, "")
        mmkv.encode(KEY_LOADED_MODEL_ID, "")
    }
}
