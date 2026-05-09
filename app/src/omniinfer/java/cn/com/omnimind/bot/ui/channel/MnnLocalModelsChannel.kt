package cn.com.omnimind.bot.ui.channel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.bot.omniinfer.OmniInferLocalRuntime
import cn.com.omnimind.bot.omniinfer.OmniInferLiteRtModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferMnnModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferModelsManager
import cn.com.omnimind.bot.omniinfer.OmniInferQnnModelsManager
import com.tencent.mmkv.MMKV
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MnnLocalModelsChannel {
    companion object {
        private const val TAG = "MnnLocalModelsChannel"
        private const val METHOD_CHANNEL = "cn.com.omnimind.bot/MnnLocalModels"
        private const val EVENT_CHANNEL = "cn.com.omnimind.bot/MnnLocalModelsEvents"
        private const val ERROR_CODE = "MNN_LOCAL_ERROR"
        private const val MMKV_BACKEND_KEY = "omniinfer_selected_backend"
        private const val DEFAULT_BACKEND = OmniInferLocalRuntime.BACKEND_LLAMA_CPP

        private const val REQUEST_CODE_IMPORT_FILE = 39122
        private const val REQUEST_CODE_IMPORT_TREE = 39123

        private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val importHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var pendingImportResult: MethodChannel.Result? = null
        @Volatile
        private var pendingImportBackend: String = DEFAULT_BACKEND

        fun onActivityResult(
            activity: Activity,
            requestCode: Int,
            resultCode: Int,
            data: Intent?,
        ): Boolean {
            if (requestCode != REQUEST_CODE_IMPORT_FILE && requestCode != REQUEST_CODE_IMPORT_TREE) {
                return false
            }

            val result = pendingImportResult
            val backend = pendingImportBackend
            pendingImportResult = null
            pendingImportBackend = DEFAULT_BACKEND
            if (result == null) return true

            if (resultCode != Activity.RESULT_OK || data?.data == null) {
                result.success(mapOf("success" to false, "error" to "cancelled"))
                return true
            }

            val uri = data.data!!
            val isTree = requestCode == REQUEST_CODE_IMPORT_TREE

            importScope.launch {
                val importResult = runCatching {
                    if (isTree) {
                        OmniInferMnnModelsManager.importModelFromUri(activity, uri)
                    } else if (backend == OmniInferLocalRuntime.BACKEND_LITERT) {
                        OmniInferLiteRtModelsManager.importModelFromUri(activity, uri)
                    } else {
                        OmniInferModelsManager.importModelFromUri(activity, uri)
                    }
                }.getOrElse { e ->
                    mapOf("success" to false, "error" to (e.message ?: "unknown_error"))
                }
                importHandler.post { result.success(importResult) }
            }
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var context: Context? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private val backendMmkv: MMKV by lazy { MMKV.mmkvWithID("omniinfer_config") }
    private var preloadJob: Job? = null
    private val preloadMutex = Mutex()

    fun onCreate(context: Context) {
        this.context = context
        OmniInferModelsManager.setContext(context)
        OmniInferMnnModelsManager.setContext(context)
        OmniInferQnnModelsManager.setContext(context)
        OmniInferLiteRtModelsManager.setContext(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        methodChannel?.setMethodCallHandler(::handleMethodCall)

        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        eventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
                val dispatcher: (Map<String, Any?>) -> Unit = { payload ->
                    mainHandler.post { eventSink?.success(payload) }
                }
                OmniInferModelsManager.setEventDispatcher(dispatcher)
                OmniInferMnnModelsManager.setEventDispatcher(dispatcher)
                OmniInferQnnModelsManager.setEventDispatcher(dispatcher)
                OmniInferLiteRtModelsManager.setEventDispatcher(dispatcher)
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
                OmniInferModelsManager.setEventDispatcher(null)
                OmniInferMnnModelsManager.setEventDispatcher(null)
                OmniInferQnnModelsManager.setEventDispatcher(null)
                OmniInferLiteRtModelsManager.setEventDispatcher(null)
            }
        })
    }

    fun clear() {
        OmniInferModelsManager.setEventDispatcher(null)
        OmniInferMnnModelsManager.setEventDispatcher(null)
        OmniInferQnnModelsManager.setEventDispatcher(null)
        OmniInferLiteRtModelsManager.setEventDispatcher(null)
        eventSink = null
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        // NOTE: Do NOT call OmniInferModelsManager.clear() or OmniInferMnnModelsManager.clear()
        // here. Those methods cancel active downloads, but this clear() is called when the
        // Flutter view is destroyed (e.g. app goes to background). Downloads should continue
        // running. The event dispatcher is already disconnected above; it will be reconnected
        // when the channel is re-established via setChannel().
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getBackend" -> {
                result.success(getSelectedBackend())
                return
            }

            "setBackend" -> {
                val backend = OmniInferLocalRuntime.normalizeBackend(call.argument<String>("backend"))
                backendMmkv.encode(MMKV_BACKEND_KEY, backend)
                OmniInferLocalRuntime.setSelectedBackend(backend)
                result.success(backend)
                return
            }

            "importModel" -> {
                val activity = context as? Activity
                if (activity == null) {
                    result.error(ERROR_CODE, "Not attached to activity", null)
                    return
                }
                if (pendingImportResult != null) {
                    result.error(ERROR_CODE, "Another import is already in progress", null)
                    return
                }
                pendingImportResult = result
                try {
                    val backend = getSelectedBackend()
                    pendingImportBackend = backend
                    val isTreePicker = backend == OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN
                    if (isTreePicker) {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        activity.startActivityForResult(intent, REQUEST_CODE_IMPORT_TREE)
                    } else {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        activity.startActivityForResult(intent, REQUEST_CODE_IMPORT_FILE)
                    }
                } catch (e: Exception) {
                    pendingImportResult = null
                    pendingImportBackend = DEFAULT_BACKEND
                    result.error(ERROR_CODE, e.message ?: "Failed to open file picker", null)
                }
                return
            }

            "preloadModel" -> {
                val modelId = call.argument<String>("modelId")?.trim().orEmpty()
                if (modelId.isEmpty()) {
                    result.success(mapOf("success" to false, "error" to "empty modelId"))
                    return
                }
                preloadJob?.cancel()
                preloadJob = scope.launch(Dispatchers.IO) {
                    var success = false
                    var error = ""
                    try {
                        preloadMutex.withLock {
                            ensureActive()
                            OmniLog.i(TAG, "[preloadModel] loading modelId=$modelId")
                            success = runCatching {
                                OmniInferModelsManager.ensureModelReady(modelId)
                            }.getOrDefault(false)
                            if (!success) {
                                ensureActive()
                                success = runCatching {
                                    OmniInferMnnModelsManager.ensureModelReady(modelId)
                                }.getOrDefault(false)
                            }
                            if (!success) {
                                ensureActive()
                                success = runCatching {
                                    OmniInferLiteRtModelsManager.ensureModelReady(modelId)
                                }.getOrDefault(false)
                            }
                            if (!success) {
                                ensureActive()
                                success = runCatching {
                                    OmniInferQnnModelsManager.ensureModelReady(modelId)
                                }.getOrDefault(false)
                            }
                            if (!success) error = "Model not found or load failed"
                        }
                        ensureActive()
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        OmniLog.i(TAG, "[preloadModel] cancelled modelId=$modelId")
                        mainHandler.post {
                            runCatching {
                                result.success(mapOf(
                                    "success" to false,
                                    "cancelled" to true,
                                    "modelId" to modelId,
                                ))
                            }
                        }
                        return@launch
                    }
                    OmniLog.i(TAG, "[preloadModel] done modelId=$modelId success=$success")
                    mainHandler.post {
                        result.success(mapOf(
                            "success" to success,
                            "modelId" to modelId,
                            "error" to error,
                        ))
                    }
                }
                return
            }
        }

        when (getSelectedBackend()) {
            OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN -> handleMnnCall(call, result)
            OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN -> handleQnnCall(call, result)
            OmniInferLocalRuntime.BACKEND_LITERT -> handleLiteRtCall(call, result)
            else -> handleLlamaCppCall(call, result)
        }
    }

    private fun getSelectedBackend(): String {
        val rawBackend = backendMmkv.decodeString(MMKV_BACKEND_KEY, DEFAULT_BACKEND)
        val normalizedBackend = OmniInferLocalRuntime.normalizeBackend(rawBackend)
        if (normalizedBackend != rawBackend) {
            backendMmkv.encode(MMKV_BACKEND_KEY, normalizedBackend)
        }
        OmniInferLocalRuntime.setSelectedBackend(normalizedBackend)
        return normalizedBackend
    }

    private fun handleLlamaCppCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getOverview" -> runSuspend(result) {
                OmniInferModelsManager.getOverview(
                    installedQuery = call.argument<String>("installedQuery"),
                    marketQuery = call.argument<String>("marketQuery"),
                    marketCategory = call.argument<String>("marketCategory"),
                )
            }

            "listInstalledModels" -> runSuspend(result) {
                OmniInferModelsManager.listInstalledModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "refreshInstalledModels" -> runSuspend(result) {
                OmniInferModelsManager.refreshInstalledModels()
            }

            "listMarketModels" -> runSuspend(result) {
                OmniInferModelsManager.listMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                    refresh = call.argument<Boolean>("refresh") == true,
                )
            }

            "refreshMarketModels" -> runSuspend(result) {
                OmniInferModelsManager.refreshMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "getConfig" -> result.success(OmniInferModelsManager.getConfig())

            "saveConfig" -> {
                val args = (call.arguments as? Map<*, *>) ?: emptyMap<String, Any?>()
                result.success(OmniInferModelsManager.saveConfig(args))
            }

            "setActiveModel" -> {
                result.success(OmniInferModelsManager.setActiveModel(call.argument<String>("modelId")))
            }

            "startApiService" -> {
                val modelId = call.argument<String>("modelId")
                scope.launch(Dispatchers.IO) {
                    val config = OmniInferModelsManager.startApiService(modelId)
                    mainHandler.post { result.success(config) }
                }
            }

            "stopApiService" -> result.success(OmniInferModelsManager.stopApiService())

            "startDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferModelsManager.startDownload(modelId)
                    result.success(true)
                }
            }

            "pauseDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferModelsManager.pauseDownload(modelId)
                    result.success(true)
                }
            }

            "deleteModel" -> runSuspend(result) {
                val modelId = call.argument<String>("modelId") ?: error("modelId is required")
                OmniInferModelsManager.deleteModel(modelId)
            }


            else -> result.notImplemented()
        }
    }

    private fun handleMnnCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getOverview" -> runSuspend(result) {
                OmniInferMnnModelsManager.getOverview(
                    installedQuery = call.argument<String>("installedQuery"),
                    marketQuery = call.argument<String>("marketQuery"),
                    marketCategory = call.argument<String>("marketCategory"),
                )
            }

            "listInstalledModels" -> runSuspend(result) {
                OmniInferMnnModelsManager.listInstalledModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "refreshInstalledModels" -> runSuspend(result) {
                OmniInferMnnModelsManager.refreshInstalledModels()
            }

            "listMarketModels" -> runSuspend(result) {
                OmniInferMnnModelsManager.listMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                    refresh = call.argument<Boolean>("refresh") == true,
                )
            }

            "refreshMarketModels" -> runSuspend(result) {
                OmniInferMnnModelsManager.refreshMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "getConfig" -> result.success(OmniInferMnnModelsManager.getConfig())

            "saveConfig" -> {
                val args = (call.arguments as? Map<*, *>) ?: emptyMap<String, Any?>()
                result.success(OmniInferMnnModelsManager.saveConfig(args))
            }

            "setActiveModel" -> {
                result.success(OmniInferMnnModelsManager.setActiveModel(call.argument<String>("modelId")))
            }

            "startApiService" -> {
                val modelId = call.argument<String>("modelId")
                scope.launch(Dispatchers.IO) {
                    val config = OmniInferMnnModelsManager.startApiService(modelId)
                    mainHandler.post { result.success(config) }
                }
            }

            "stopApiService" -> result.success(OmniInferMnnModelsManager.stopApiService())

            "startDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferMnnModelsManager.startDownload(modelId)
                    result.success(true)
                }
            }

            "pauseDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferMnnModelsManager.pauseDownload(modelId)
                    result.success(true)
                }
            }

            "deleteModel" -> runSuspend(result) {
                val modelId = call.argument<String>("modelId") ?: error("modelId is required")
                OmniInferMnnModelsManager.deleteModel(modelId)
            }


            else -> result.notImplemented()
        }
    }

    private fun handleQnnCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getOverview" -> runSuspend(result) {
                OmniInferQnnModelsManager.getOverview(
                    installedQuery = call.argument<String>("installedQuery"),
                    marketQuery = call.argument<String>("marketQuery"),
                    marketCategory = call.argument<String>("marketCategory"),
                )
            }

            "listInstalledModels" -> runSuspend(result) {
                OmniInferQnnModelsManager.listInstalledModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "refreshInstalledModels" -> runSuspend(result) {
                OmniInferQnnModelsManager.refreshInstalledModels()
            }

            "listMarketModels" -> runSuspend(result) {
                OmniInferQnnModelsManager.listMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                    refresh = call.argument<Boolean>("refresh") == true,
                )
            }

            "refreshMarketModels" -> runSuspend(result) {
                OmniInferQnnModelsManager.refreshMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "getConfig" -> result.success(OmniInferQnnModelsManager.getConfig())

            "saveConfig" -> {
                val args = (call.arguments as? Map<*, *>) ?: emptyMap<String, Any?>()
                result.success(OmniInferQnnModelsManager.saveConfig(args))
            }

            "setActiveModel" -> {
                result.success(OmniInferQnnModelsManager.setActiveModel(call.argument<String>("modelId")))
            }

            "startApiService" -> {
                val modelId = call.argument<String>("modelId")
                scope.launch(Dispatchers.IO) {
                    val config = OmniInferQnnModelsManager.startApiService(modelId)
                    mainHandler.post { result.success(config) }
                }
            }

            "stopApiService" -> result.success(OmniInferQnnModelsManager.stopApiService())

            "startDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferQnnModelsManager.startDownload(modelId)
                    result.success(true)
                }
            }

            "pauseDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferQnnModelsManager.pauseDownload(modelId)
                    result.success(true)
                }
            }

            "deleteModel" -> runSuspend(result) {
                val modelId = call.argument<String>("modelId") ?: error("modelId is required")
                OmniInferQnnModelsManager.deleteModel(modelId)
            }

            else -> result.notImplemented()
        }
    }

    private fun handleLiteRtCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getOverview" -> runSuspend(result) {
                OmniInferLiteRtModelsManager.getOverview(
                    installedQuery = call.argument<String>("installedQuery"),
                    marketQuery = call.argument<String>("marketQuery"),
                    marketCategory = call.argument<String>("marketCategory"),
                )
            }

            "listInstalledModels" -> runSuspend(result) {
                OmniInferLiteRtModelsManager.listInstalledModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "refreshInstalledModels" -> runSuspend(result) {
                OmniInferLiteRtModelsManager.refreshInstalledModels()
            }

            "listMarketModels" -> runSuspend(result) {
                OmniInferLiteRtModelsManager.listMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                    refresh = call.argument<Boolean>("refresh") == true,
                )
            }

            "refreshMarketModels" -> runSuspend(result) {
                OmniInferLiteRtModelsManager.refreshMarketModels(
                    query = call.argument<String>("query"),
                    category = call.argument<String>("category"),
                )
            }

            "getConfig" -> result.success(OmniInferLiteRtModelsManager.getConfig())

            "saveConfig" -> {
                val args = (call.arguments as? Map<*, *>) ?: emptyMap<String, Any?>()
                result.success(OmniInferLiteRtModelsManager.saveConfig(args))
            }

            "setActiveModel" -> {
                result.success(OmniInferLiteRtModelsManager.setActiveModel(call.argument<String>("modelId")))
            }

            "startApiService" -> {
                val modelId = call.argument<String>("modelId")
                scope.launch(Dispatchers.IO) {
                    val config = OmniInferLiteRtModelsManager.startApiService(modelId)
                    mainHandler.post { result.success(config) }
                }
            }

            "stopApiService" -> result.success(OmniInferLiteRtModelsManager.stopApiService())

            "startDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferLiteRtModelsManager.startDownload(modelId)
                    result.success(true)
                }
            }

            "pauseDownload" -> {
                val modelId = call.argument<String>("modelId")
                if (modelId.isNullOrBlank()) {
                    result.error(ERROR_CODE, "modelId is required", null)
                } else {
                    OmniInferLiteRtModelsManager.pauseDownload(modelId)
                    result.success(true)
                }
            }

            "deleteModel" -> runSuspend(result) {
                val modelId = call.argument<String>("modelId") ?: error("modelId is required")
                OmniInferLiteRtModelsManager.deleteModel(modelId)
            }

            else -> result.notImplemented()
        }
    }

    private fun runSuspend(
        result: MethodChannel.Result,
        block: suspend () -> Any?,
    ) {
        scope.launch {
            runCatching { block() }
                .onSuccess { value -> result.success(value) }
                .onFailure { error -> result.error(ERROR_CODE, error.message ?: "unknown_error", null) }
        }
    }
}

