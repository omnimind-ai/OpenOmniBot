package cn.com.omnimind.bot.omniinfer

import android.content.Context
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.baselib.util.OmniLog
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Manages ExecuTorch QNN (.pte) models: discovery, download, lifecycle.
 * Follows the same interface pattern as [OmniInferMnnModelsManager].
 */
object OmniInferQnnModelsManager {
    private const val TAG = "OmniInferQnnModelsManager"
    private const val BACKEND_NAME = OmniInferLocalRuntime.BACKEND_EXECUTORCH_QNN
    private const val MMKV_ID = "omniinfer_config"
    private const val KEY_ACTIVE_MODEL_ID = "omniinfer_qnn_active_model_id"
    private const val KEY_AUTO_START = "omniinfer_qnn_auto_start_on_app_open"

    private var appContext: Context? = null
    private var eventDispatcher: ((Map<String, Any?>) -> Unit)? = null
    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** modelId → active download state. */
    private val activeDownloads = ConcurrentHashMap<String, QnnDownloadState>()

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---- Lifecycle ----

    fun setContext(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        OmniInferLocalRuntime.setContext(applicationContext)
        OmniInferQnnMarketRepository.setContext(applicationContext)
    }

    fun setEventDispatcher(dispatcher: ((Map<String, Any?>) -> Unit)?) {
        eventDispatcher = dispatcher
    }

    fun activeDownloadCount(): Int = activeDownloads.size

    fun clear() {
        activeDownloads.values.forEach { it.cancelled = true }
        activeDownloads.clear()
        eventDispatcher = null
    }

    fun handleAppOpen() {
        if (shouldAutoStartOnAppOpen()) {
            Thread({ startApiService(getActiveModelId()) }, "OmniInfer-qnn-autostart").start()
        }
    }

    // ---- Overview / Config ----

    suspend fun getOverview(
        installedQuery: String? = null,
        marketQuery: String? = null,
        marketCategory: String? = null,
    ): Map<String, Any?> = mapOf(
        "config" to getConfig(),
        "installedModels" to listInstalledModels(installedQuery),
        "market" to listMarketModels(marketQuery),
    )

    fun getConfig(): Map<String, Any?> {
        ensureContext()
        val soc = OmniInferQnnMarketRepository.getDeviceSoc()
        return mapOf(
            "backend" to BACKEND_NAME,
            "autoStartOnAppOpen" to shouldAutoStartOnAppOpen(),
            "apiRunning" to OmniInferLocalRuntime.isReady(),
            "apiReady" to OmniInferLocalRuntime.isReady(),
            "apiState" to if (OmniInferLocalRuntime.isReady()) "running" else "stopped",
            "apiHost" to OmniInferLocalRuntime.getHost(),
            "apiPort" to OmniInferLocalRuntime.getPort(),
            "baseUrl" to OmniInferLocalRuntime.getBaseUrl(),
            "activeModelId" to getActiveModelId(),
            "downloadProvider" to "ModelScope",
            "availableSources" to listOf("ModelScope"),
            "loadedBackend" to OmniInferLocalRuntime.getLoadedBackend(),
            "loadedModelId" to getLoadedModelId(),
            "deviceSoc" to soc,
            "deviceSupported" to OmniInferQnnMarketRepository.isDeviceSupported(),
        )
    }

    fun saveConfig(arguments: Map<*, *>): Map<String, Any?> {
        arguments["autoStartOnAppOpen"]?.let {
            mmkv.encode(KEY_AUTO_START, it == true)
        }
        arguments["apiPort"]?.let {
            val port = (it as? Number)?.toInt()
            if (port != null && port > 0) OmniInferLocalRuntime.setPort(port)
        }
        arguments["activeModelId"]?.let {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, it.toString().trim())
        }
        emitConfigChanged()
        return getConfig()
    }

    fun setActiveModel(modelId: String?): Map<String, Any?> {
        mmkv.encode(KEY_ACTIVE_MODEL_ID, modelId?.trim().orEmpty())
        emitConfigChanged()
        return getConfig()
    }

    // ---- Installed models ----

    fun listInstalledModels(
        query: String? = null,
        category: String? = null,
    ): List<Map<String, Any?>> {
        ensureContext()
        val normalizedQuery = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        return scanInstalledModels()
            .filter { record ->
                normalizedQuery.isEmpty() || listOf(
                    record.modelId, record.modelName, record.path,
                ).any { it.lowercase(Locale.getDefault()).contains(normalizedQuery) }
            }
            .map(::installedRecordToMap)
    }

    suspend fun refreshInstalledModels(): List<Map<String, Any?>> = listInstalledModels()

    // ---- Market models ----

    suspend fun listMarketModels(
        query: String? = null,
        category: String? = null,
        refresh: Boolean = false,
    ): Map<String, Any?> {
        ensureContext()
        val normalizedQuery = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val models = OmniInferQnnMarketRepository.listModels(filterBySoc = true)
            .filter { resolved ->
                normalizedQuery.isEmpty() || listOf(
                    resolved.entry.modelName,
                    resolved.entry.modelId,
                    resolved.entry.socLabel,
                ).any { it.lowercase(Locale.getDefault()).contains(normalizedQuery) }
            }
            .map(::marketModelToMap)
        return mapOf(
            "source" to "ModelScope",
            "availableSources" to listOf("ModelScope"),
            "category" to "llm",
            "models" to models,
        )
    }

    suspend fun refreshMarketModels(
        query: String? = null,
        category: String? = null,
    ): Map<String, Any?> = listMarketModels(query = query, category = category, refresh = true)

    // ---- API service ----

    fun startApiService(modelId: String? = null): Map<String, Any?> {
        val targetModelId = modelId?.trim().orEmpty().ifBlank { getActiveModelId() }
        if (targetModelId.isBlank()) {
            OmniLog.w(TAG, "[startApiService] no modelId and no active model")
            return getConfig()
        }
        val installed = scanInstalledModels().firstOrNull { it.modelId == targetModelId }
        if (installed == null) {
            OmniLog.w(TAG, "[startApiService] model not found: $targetModelId")
            return getConfig()
        }
        val marketModel = OmniInferQnnMarketRepository.findModel(targetModelId)
        val decoderVersion = marketModel?.entry?.decoderModelVersion ?: "qwen3"

        OmniLog.i(TAG, "[startApiService] modelId=${installed.modelId}, ptePath=${installed.ptePath}, decoderVersion=$decoderVersion")
        mmkv.encode(KEY_ACTIVE_MODEL_ID, installed.modelId)
        OmniInferLocalRuntime.loadModel(
            modelId = installed.modelId,
            modelPath = installed.ptePath,
            backend = BACKEND_NAME,
            extraConfig = mapOf("decoder_model_version" to decoderVersion),
        )
        emitConfigChanged()
        return getConfig()
    }

    fun ensureModelReady(modelId: String): Boolean {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isEmpty()) {
            return false
        }
        val installed = scanInstalledModels().firstOrNull { it.modelId == normalizedModelId }
            ?: return false
        if (OmniInferLocalRuntime.isModelLoaded(BACKEND_NAME, installed.modelId)) {
            return true
        }
        val marketModel = OmniInferQnnMarketRepository.findModel(normalizedModelId)
        val decoderVersion = marketModel?.entry?.decoderModelVersion ?: "qwen3"
        mmkv.encode(KEY_ACTIVE_MODEL_ID, installed.modelId)
        return OmniInferLocalRuntime.loadModel(
            modelId = installed.modelId,
            modelPath = installed.ptePath,
            backend = BACKEND_NAME,
            extraConfig = mapOf("decoder_model_version" to decoderVersion),
        )
    }

    fun stopApiService(): Map<String, Any?> {
        OmniInferLocalRuntime.stop()
        emitConfigChanged()
        return getConfig()
    }

    // ---- Download ----

    fun startDownload(modelId: String) {
        val context = ensureContext()
        val resolved = OmniInferQnnMarketRepository.findModel(modelId) ?: run {
            emitEvent("download_error", mapOf("modelId" to modelId, "error" to "Model not found"))
            return
        }
        if (activeDownloads.containsKey(modelId)) return

        val destDir = File(AgentWorkspaceManager.modelsQnnDirectory(context), modelId)
        destDir.mkdirs()

        val state = QnnDownloadState()
        activeDownloads[modelId] = state
        ModelDownloadForegroundService.start(context, activeDownloads.size, resolved.entry.modelName)

        // Calculate initial progress from existing files (include .part files for resume)
        val savedSize = destDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val totalSize = resolved.entry.fileSize
        val initialProgress = if (totalSize > 0 && savedSize > 0) savedSize.toDouble() / totalSize else 0.0
        emitDownloadUpdate(modelId, downloadInfoMap(
            stateLabel = "preparing",
            progress = initialProgress,
            savedSize = savedSize,
            totalSize = totalSize,
        ))

        scope.launch {
            try {
                downloadModel(resolved, destDir, state) { info ->
                    emitDownloadUpdate(modelId, info)
                }
                activeDownloads.remove(modelId)
                emitDownloadUpdate(modelId, downloadInfoMap(
                    stateLabel = "completed",
                    progress = 1.0,
                    savedSize = totalSize,
                    totalSize = totalSize,
                ))
                emitEvent("downloads_changed", emptyMap())
                ModelDownloadForegroundService.stopIfIdle(context)
                OmniInferBuiltinProviderRefresher.refreshAsync(context, "qnn_download_finished:$modelId")
            } catch (e: Exception) {
                activeDownloads.remove(modelId)
                val errorMsg = e.message ?: "unknown_error"
                emitDownloadUpdate(modelId, downloadInfoMap(
                    stateLabel = if (state.cancelled) "paused" else "failed",
                    errorMessage = if (state.cancelled) "" else errorMsg,
                    progress = state.progress,
                    savedSize = state.savedSize,
                    totalSize = totalSize,
                ))
                emitEvent("downloads_changed", emptyMap())
                ModelDownloadForegroundService.stopIfIdle(context)
            }
        }
    }

    fun pauseDownload(modelId: String) {
        val state = activeDownloads.remove(modelId) ?: return
        state.cancelled = true
        emitDownloadUpdate(modelId, downloadInfoMap(
            stateLabel = "paused",
            progress = state.progress,
            savedSize = state.savedSize,
            totalSize = state.totalSize,
        ))
        emitEvent("downloads_changed", emptyMap())
        appContext?.let { ModelDownloadForegroundService.stopIfIdle(it) }
    }

    suspend fun deleteModel(modelId: String): List<Map<String, Any?>> {
        val context = ensureContext()
        // Cancel any active download first
        activeDownloads.remove(modelId)?.cancelled = true
        // Stop runtime if this model is loaded
        if (getLoadedModelId() == modelId) {
            OmniInferLocalRuntime.stop()
        }
        // Delete the model directory directly (handles both complete and partial downloads)
        val qnnDir = AgentWorkspaceManager.modelsQnnDirectory(context)
        val modelDir = File(qnnDir, modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        if (getActiveModelId() == modelId) {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, "")
        }
        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        OmniInferBuiltinProviderRefresher.refreshAsync(context, "qnn_delete:$modelId")
        return listInstalledModels()
    }

    // ---- Internal: download logic ----

    private suspend fun downloadModel(
        resolved: OmniInferQnnMarketRepository.ResolvedQnnModel,
        destDir: File,
        state: QnnDownloadState,
        onProgress: (Map<String, Any?>) -> Unit,
    ) {
        val entry = resolved.entry
        state.totalSize = entry.fileSize

        // Download .pte file
        downloadFile(
            url = entry.pteUrl,
            dest = File(destDir, "hybrid_llama_qnn.pte"),
            state = state,
            onProgress = { onProgress(it) },
        )

        // Download tokenizer.json
        downloadFile(
            url = entry.tokenizerUrl,
            dest = File(destDir, "tokenizer.json"),
            state = state,
            onProgress = { onProgress(it) },
        )
    }

    private fun downloadFile(
        url: String,
        dest: File,
        state: QnnDownloadState,
        onProgress: (Map<String, Any?>) -> Unit,
    ) {
        if (state.cancelled) throw DownloadCancelledException()

        val partFile = File(dest.parentFile, dest.name + ".part")

        // Skip if already complete
        if (dest.exists() && dest.length() > 0) {
            state.savedSize += dest.length()
            state.progress = if (state.totalSize > 0) state.savedSize.toDouble() / state.totalSize else 0.0
            return
        }

        val startByte = if (partFile.exists()) partFile.length() else 0L
        // Account for already-downloaded bytes in .part file for progress tracking
        if (startByte > 0) {
            state.savedSize += startByte
            state.progress = if (state.totalSize > 0) state.savedSize.toDouble() / state.totalSize else 0.0
        }

        val requestBuilder = Request.Builder().url(url)
        if (startByte > 0) {
            requestBuilder.header("Range", "bytes=$startByte-")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw RuntimeException("Download failed: HTTP ${response.code} for $url")
        }

        val body = response.body ?: throw RuntimeException("Empty response body for $url")
        val outputStream = if (startByte > 0) {
            // Append mode for resume
            java.io.FileOutputStream(partFile, true)
        } else {
            java.io.FileOutputStream(partFile)
        }

        var lastEmitTime = System.currentTimeMillis()
        body.byteStream().use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (state.cancelled) throw DownloadCancelledException()
                    output.write(buffer, 0, bytesRead)
                    state.savedSize += bytesRead
                    state.progress = if (state.totalSize > 0) state.savedSize.toDouble() / state.totalSize else 0.0

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= 500) {
                        lastEmitTime = now
                        onProgress(downloadInfoMap(
                            stateLabel = "downloading",
                            progress = state.progress,
                            savedSize = state.savedSize,
                            totalSize = state.totalSize,
                            currentFile = dest.name,
                        ))
                    }
                }
            }
        }

        // Rename .part to final
        partFile.renameTo(dest)
    }

    // ---- Internal: model scanning ----

    private data class InstalledQnnRecord(
        val modelId: String,
        val modelName: String,
        val path: String,
        val ptePath: String,
        val fileSize: Long,
        val downloadedAt: Long,
        val downloadInfo: Map<String, Any?>?,
    )

    private fun scanInstalledModels(): List<InstalledQnnRecord> {
        val context = appContext ?: return emptyList()
        val qnnDir = AgentWorkspaceManager.modelsQnnDirectory(context)
        if (!qnnDir.exists()) return emptyList()

        val dirs = qnnDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { modelDir ->
            val pteFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".pte") }
                ?: return@mapNotNull null

            val marketModel = OmniInferQnnMarketRepository.findModel(modelDir.name)
            val hasPartFiles = modelDir.walkTopDown().any { it.isFile && it.name.endsWith(".part") }

            val activeState = activeDownloads[modelDir.name]
            val downloadInfo = when {
                activeState != null -> downloadInfoMap(
                    stateLabel = "downloading",
                    progress = activeState.progress,
                    savedSize = activeState.savedSize,
                    totalSize = activeState.totalSize,
                )
                hasPartFiles -> {
                    val saved = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val total = marketModel?.entry?.fileSize ?: saved
                    downloadInfoMap(
                        stateLabel = "paused",
                        progress = if (total > 0) saved.toDouble() / total else 0.0,
                        savedSize = saved,
                        totalSize = total,
                    )
                }
                else -> null
            }

            InstalledQnnRecord(
                modelId = marketModel?.entry?.modelId ?: modelDir.name,
                modelName = marketModel?.entry?.modelName ?: modelDir.name,
                path = modelDir.absolutePath,
                ptePath = pteFile.absolutePath,
                fileSize = marketModel?.entry?.fileSize ?: pteFile.length(),
                downloadedAt = modelDir.lastModified(),
                downloadInfo = downloadInfo,
            )
        }
    }

    // ---- Internal: map conversion ----

    private fun installedRecordToMap(record: InstalledQnnRecord): Map<String, Any?> {
        val activeModelId = getActiveModelId()
        val loadedModelId = getLoadedModelId()
        val marketModel = OmniInferQnnMarketRepository.findModel(record.modelId)
        val socInfo = marketModel?.let { "${it.entry.soc} (Snapdragon ${it.entry.socLabel})" }
            ?: OmniInferQnnMarketRepository.getDeviceSoc()
        return mapOf(
            "id" to record.modelId,
            "name" to record.modelName,
            "category" to "llm",
            "source" to "ModelScope",
            "description" to "ExecuTorch QNN · $socInfo · NPU",
            "path" to record.path,
            "vendor" to "Qwen",
            "tags" to listOf("NPU", "QNN"),
            "extraTags" to emptyList<String>(),
            "active" to (record.modelId == activeModelId || record.modelId == loadedModelId),
            "isLocal" to true,
            "isPinned" to false,
            "hasUpdate" to false,
            "fileSize" to record.fileSize,
            "sizeB" to record.fileSize.toDouble(),
            "formattedSize" to formatFileSize(record.fileSize),
            "lastUsedAt" to 0,
            "downloadedAt" to record.downloadedAt,
            "readOnly" to false,
            "download" to record.downloadInfo,
        )
    }

    private fun marketModelToMap(resolved: OmniInferQnnMarketRepository.ResolvedQnnModel): Map<String, Any?> {
        val context = appContext ?: return emptyMap()
        val qnnDir = AgentWorkspaceManager.modelsQnnDirectory(context)
        val modelDir = File(qnnDir, resolved.modelId)
        val isDownloaded = modelDir.exists() && modelDir.listFiles()?.any { it.name.endsWith(".pte") } == true
        val hasPartFiles = modelDir.exists() && modelDir.walkTopDown().any { it.isFile && it.name.endsWith(".part") }

        val activeState = activeDownloads[resolved.modelId]
        val downloadInfo = when {
            activeState != null -> downloadInfoMap(
                stateLabel = "downloading",
                progress = activeState.progress,
                savedSize = activeState.savedSize,
                totalSize = activeState.totalSize,
            )
            isDownloaded -> downloadInfoMap(
                stateLabel = "completed",
                progress = 1.0,
                savedSize = resolved.entry.fileSize,
                totalSize = resolved.entry.fileSize,
            )
            hasPartFiles -> {
                val saved = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                downloadInfoMap(
                    stateLabel = "paused",
                    progress = if (resolved.entry.fileSize > 0) saved.toDouble() / resolved.entry.fileSize else 0.0,
                    savedSize = saved,
                    totalSize = resolved.entry.fileSize,
                )
            }
            else -> null
        }

        return mapOf(
            "id" to resolved.modelId,
            "name" to resolved.entry.modelName,
            "category" to "llm",
            "source" to "ModelScope",
            "description" to "ExecuTorch QNN · ${resolved.entry.soc} (Snapdragon ${resolved.entry.socLabel}) · NPU",
            "path" to modelDir.absolutePath,
            "vendor" to "Qwen",
            "tags" to listOf("NPU", "QNN"),
            "extraTags" to emptyList<String>(),
            "active" to false,
            "isLocal" to isDownloaded,
            "isPinned" to false,
            "hasUpdate" to false,
            "fileSize" to resolved.entry.fileSize,
            "sizeB" to resolved.entry.fileSize.toDouble(),
            "formattedSize" to formatFileSize(resolved.entry.fileSize),
            "lastUsedAt" to 0,
            "downloadedAt" to 0,
            "readOnly" to false,
            "download" to downloadInfo,
        )
    }

    // ---- Helpers ----

    private fun getActiveModelId(): String =
        mmkv.decodeString(KEY_ACTIVE_MODEL_ID, "").orEmpty().trim()

    private fun getLoadedModelId(): String =
        OmniInferLocalRuntime.getLoadedModelId()

    private fun shouldAutoStartOnAppOpen(): Boolean =
        mmkv.decodeBool(KEY_AUTO_START, false)

    private fun ensureContext(): Context =
        appContext ?: error("OmniInferQnnModelsManager not initialized, call setContext() first")

    private fun emitEvent(type: String, payload: Map<String, Any?>) {
        eventDispatcher?.invoke(mapOf("type" to type) + payload)
    }

    private fun emitConfigChanged() {
        emitEvent("config_changed", mapOf("config" to getConfig()))
    }

    private fun emitDownloadUpdate(modelId: String, info: Map<String, Any?>) {
        emitEvent("download_update", mapOf("modelId" to modelId, "download" to info))
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        }
    }

    private fun downloadInfoMap(
        stateLabel: String = "not_started",
        progress: Double = 0.0,
        savedSize: Long = 0,
        totalSize: Long = 0,
        speedInfo: String = "",
        errorMessage: String = "",
        progressStage: String = "",
        currentFile: String = "",
    ): Map<String, Any?> = mapOf(
        "state" to when (stateLabel) {
            "completed" -> MnnDownloadState.DOWNLOAD_SUCCESS
            "downloading" -> MnnDownloadState.DOWNLOADING
            "preparing" -> MnnDownloadState.PREPARING
            "paused" -> MnnDownloadState.DOWNLOAD_PAUSED
            "failed" -> MnnDownloadState.DOWNLOAD_FAILED
            else -> MnnDownloadState.NOT_START
        },
        "stateLabel" to stateLabel,
        "progress" to progress,
        "savedSize" to savedSize,
        "totalSize" to totalSize,
        "speedInfo" to speedInfo,
        "errorMessage" to errorMessage,
        "progressStage" to progressStage,
        "currentFile" to currentFile,
        "hasUpdate" to false,
    )

    /** Mutable download state shared between the download coroutine and the manager. */
    class QnnDownloadState {
        @Volatile var cancelled: Boolean = false
        @Volatile var progress: Double = 0.0
        @Volatile var savedSize: Long = 0L
        @Volatile var totalSize: Long = 0L
    }

    private class DownloadCancelledException : Exception("Download cancelled")
}
