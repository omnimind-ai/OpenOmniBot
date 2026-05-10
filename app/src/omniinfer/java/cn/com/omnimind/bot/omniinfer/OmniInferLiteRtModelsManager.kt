package cn.com.omnimind.bot.omniinfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.baselib.util.OmniLog
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object OmniInferLiteRtModelsManager {
    private const val TAG = "OmniInferLiteRtModelsManager"
    private const val BACKEND_NAME = OmniInferLocalRuntime.BACKEND_LITERT
    private const val MMKV_ID = "omniinfer_config"
    private const val KEY_ACTIVE_MODEL_ID = "omniinfer_litert_active_model_id"
    private const val KEY_AUTO_START = "omniinfer_litert_auto_start_on_app_open"
    private const val LITERT_EXTENSION = ".litertlm"
    private const val MARKET_ASSET_NAME = "omniinfer_litert_model_market.json"
    private const val DEFAULT_N_CTX = 16384

    private var appContext: Context? = null
    private var cachedMarketModels: List<LiteRtMarketModel>? = null
    private var eventDispatcher: ((Map<String, Any?>) -> Unit)? = null
    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, LiteRtDownloadState>()
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private data class InstalledLiteRtRecord(
        val id: String,
        val name: String,
        val path: String,
        val fileSize: Long,
        val downloadedAt: Long,
    )

    private data class LiteRtMarketModel(
        val id: String,
        val name: String,
        val repo: String,
        val fileName: String,
        val vendor: String = "Google",
        val description: String = "Gemma LiteRT-LM model for OmniInfer LiteRT backend",
        val tags: List<String> = listOf("LiteRT", "Gemma", "GPU"),
    ) {
        val downloadUrl: String
            get() = "https://modelscope.cn/models/$repo/resolve/master/$fileName"
    }

    fun setContext(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        AgentWorkspaceManager.modelsLiteRtDirectory(applicationContext).mkdirs()
        OmniInferLocalRuntime.setContext(applicationContext)
    }

    fun setEventDispatcher(dispatcher: ((Map<String, Any?>) -> Unit)?) {
        eventDispatcher = dispatcher
    }

    fun clear() {
        activeDownloads.values.forEach { state ->
            state.cancelled = true
            state.call?.cancel()
        }
        activeDownloads.clear()
        eventDispatcher = null
    }

    fun handleAppOpen() {
        if (shouldAutoStartOnAppOpen()) {
            Thread({ startApiService(getActiveModelId()) }, "OmniInfer-litert-autostart").start()
        }
    }

    suspend fun getOverview(
        installedQuery: String? = null,
        marketQuery: String? = null,
        marketCategory: String? = null,
    ): Map<String, Any?> {
        return mapOf(
            "config" to getConfig(),
            "installedModels" to listInstalledModels(installedQuery, marketCategory),
            "market" to listMarketModels(marketQuery, marketCategory, refresh = false),
        )
    }

    fun listInstalledModels(
        query: String? = null,
        category: String? = null,
    ): List<Map<String, Any?>> {
        ensureContext()
        val normalizedQuery = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        return installedRecords()
            .filter { record ->
                normalizedQuery.isEmpty() || listOf(
                    record.id,
                    record.name,
                    record.path,
                    "LiteRT-LM",
                ).any { it.lowercase(Locale.getDefault()).contains(normalizedQuery) }
            }
            .sortedWith(
                compareByDescending<InstalledLiteRtRecord> { it.downloadedAt }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
            )
            .map(::installedRecordToMap)
    }

    suspend fun refreshInstalledModels(): List<Map<String, Any?>> = listInstalledModels()

    suspend fun listMarketModels(
        query: String? = null,
        category: String? = null,
        refresh: Boolean = false,
    ): Map<String, Any?> {
        ensureContext()
        val normalizedCategory = category?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val normalizedQuery = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val models = marketModels()
            .asSequence()
            .filter { normalizedCategory.isEmpty() || normalizedCategory == "llm" }
            .filter { model ->
                normalizedQuery.isEmpty() || listOf(
                    model.id,
                    model.name,
                    model.repo,
                    model.fileName,
                    model.vendor,
                    model.description,
                    "LiteRT-LM",
                    "ModelScope",
                ).any { it.lowercase(Locale.getDefault()).contains(normalizedQuery) }
            }
            .map(::marketModelToMap)
            .toList()
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

    fun getConfig(): Map<String, Any?> {
        ensureContext()
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
        )
    }

    fun saveConfig(arguments: Map<*, *>): Map<String, Any?> {
        arguments["autoStartOnAppOpen"]?.let {
            mmkv.encode(KEY_AUTO_START, it == true)
        }
        arguments["apiPort"]?.let {
            val port = (it as? Number)?.toInt()
            if (port != null && port > 0) {
                OmniInferLocalRuntime.setPort(port)
            }
        }
        arguments["activeModelId"]?.let {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, normalizeStoredModelId(it.toString()))
        }
        emitConfigChanged()
        return getConfig()
    }

    fun setActiveModel(modelId: String?): Map<String, Any?> {
        mmkv.encode(KEY_ACTIVE_MODEL_ID, normalizeStoredModelId(modelId))
        emitConfigChanged()
        return getConfig()
    }

    fun startApiService(modelId: String? = null): Map<String, Any?> {
        val targetModelId = modelId?.trim().orEmpty().ifBlank { getActiveModelId() }
        if (targetModelId.isBlank()) {
            OmniLog.w(TAG, "[startApiService] no modelId specified and no active model")
            return getConfig()
        }
        val resolved = findInstalledRecord(targetModelId)
        if (resolved == null) {
            OmniLog.w(TAG, "[startApiService] model not found: $targetModelId")
            return getConfig()
        }
        val extraConfig = buildLiteRtExtraConfig(resolved.id)
        OmniLog.i(
            TAG,
            "[startApiService] modelId=${resolved.id}, path=${resolved.path}, " +
                "backend=$BACKEND_NAME, nCtx=$DEFAULT_N_CTX, extraConfig=$extraConfig"
        )
        mmkv.encode(KEY_ACTIVE_MODEL_ID, resolved.id)
        OmniInferLocalRuntime.loadModel(
            modelId = resolved.id,
            modelPath = resolved.path,
            backend = BACKEND_NAME,
            extraConfig = extraConfig,
            nCtx = DEFAULT_N_CTX,
        )
        emitConfigChanged()
        return getConfig()
    }

    fun ensureModelReady(modelId: String): Boolean {
        val normalizedModelId = normalizeStoredModelId(modelId)
        if (normalizedModelId.isEmpty()) {
            return false
        }
        val resolved = findInstalledRecord(normalizedModelId) ?: return false
        if (OmniInferLocalRuntime.isModelLoaded(BACKEND_NAME, resolved.id)) {
            return true
        }
        val extraConfig = buildLiteRtExtraConfig(resolved.id)
        mmkv.encode(KEY_ACTIVE_MODEL_ID, resolved.id)
        return OmniInferLocalRuntime.loadModel(
            modelId = resolved.id,
            modelPath = resolved.path,
            backend = BACKEND_NAME,
            extraConfig = extraConfig,
            nCtx = DEFAULT_N_CTX,
        )
    }

    fun stopApiService(): Map<String, Any?> {
        OmniInferLocalRuntime.stop()
        emitConfigChanged()
        return getConfig()
    }

    fun startDownload(modelId: String) {
        val context = ensureContext()
        val marketModel = findMarketModel(modelId) ?: run {
            emitEvent("download_error", mapOf("modelId" to modelId, "error" to "LiteRT model not found"))
            return
        }
        if (activeDownloads.containsKey(marketModel.id)) return

        val liteRtDir = AgentWorkspaceManager.modelsLiteRtDirectory(context)
        liteRtDir.mkdirs()
        val destFile = File(liteRtDir, marketModel.fileName)
        if (destFile.exists() && destFile.length() > 0L) {
            emitDownloadUpdate(
                marketModel.id,
                downloadInfoMap(
                    stateLabel = "completed",
                    progress = 1.0,
                    savedSize = destFile.length(),
                    totalSize = destFile.length(),
                    currentFile = marketModel.fileName,
                )
            )
            return
        }

        val partFile = File(liteRtDir, "${marketModel.fileName}.part")
        val state = LiteRtDownloadState(
            savedSize = partFile.takeIf { it.exists() }?.length() ?: 0L,
            currentFile = marketModel.fileName,
        )
        activeDownloads[marketModel.id] = state
        ModelDownloadForegroundService.start(context, activeDownloads.size, marketModel.name)
        emitDownloadUpdate(
            marketModel.id,
            downloadInfoMap(
                stateLabel = "preparing",
                savedSize = state.savedSize,
                totalSize = state.totalSize,
                currentFile = marketModel.fileName,
            )
        )

        scope.launch {
            try {
                downloadMarketModel(marketModel, destFile, state)
                activeDownloads.remove(marketModel.id)
                emitDownloadUpdate(
                    marketModel.id,
                    downloadInfoMap(
                        stateLabel = "completed",
                        progress = 1.0,
                        savedSize = destFile.length(),
                        totalSize = destFile.length(),
                        currentFile = marketModel.fileName,
                    )
                )
                emitConfigChanged()
                emitEvent("downloads_changed", emptyMap())
                ModelDownloadForegroundService.stopIfIdle(context)
                OmniInferBuiltinProviderRefresher.refreshAsync(
                    context,
                    "litert_download_finished:${marketModel.id}"
                )
            } catch (e: Exception) {
                activeDownloads.remove(marketModel.id)
                val stateLabel = if (state.cancelled) "paused" else "failed"
                emitDownloadUpdate(
                    marketModel.id,
                    downloadInfoMap(
                        stateLabel = stateLabel,
                        progress = state.progress,
                        savedSize = state.savedSize,
                        totalSize = state.totalSize,
                        errorMessage = if (state.cancelled) "" else (e.message ?: "unknown_error"),
                        currentFile = marketModel.fileName,
                    )
                )
                emitEvent("downloads_changed", emptyMap())
                ModelDownloadForegroundService.stopIfIdle(context)
            }
        }
    }

    fun pauseDownload(modelId: String) {
        val normalizedModelId = findMarketModel(modelId)?.id ?: normalizeStoredModelId(modelId)
        val state = activeDownloads.remove(normalizedModelId) ?: return
        state.cancelled = true
        state.call?.cancel()
        emitDownloadUpdate(
            normalizedModelId,
            downloadInfoMap(
                stateLabel = "paused",
                progress = state.progress,
                savedSize = state.savedSize,
                totalSize = state.totalSize,
                currentFile = state.currentFile,
            )
        )
        emitEvent("downloads_changed", emptyMap())
        appContext?.let { ModelDownloadForegroundService.stopIfIdle(it) }
    }

    suspend fun deleteModel(modelId: String): List<Map<String, Any?>> {
        val normalizedModelId = findMarketModel(modelId)?.id ?: normalizeStoredModelId(modelId)
        activeDownloads.remove(normalizedModelId)?.let { state ->
            state.cancelled = true
            state.call?.cancel()
        }
        val target = findInstalledRecord(normalizedModelId)
        if (target == null) {
            val marketModel = findMarketModel(normalizedModelId)
            if (marketModel != null) {
                val context = ensureContext()
                val liteRtDir = AgentWorkspaceManager.modelsLiteRtDirectory(context)
                File(liteRtDir, marketModel.fileName).delete()
                File(liteRtDir, "${marketModel.fileName}.part").delete()
                emitConfigChanged()
                emitEvent("downloads_changed", emptyMap())
                OmniInferBuiltinProviderRefresher.refreshAsync(context, "litert_delete:$normalizedModelId")
            }
            return listInstalledModels()
        }
        if (OmniInferLocalRuntime.isModelLoaded(BACKEND_NAME, target.id)) {
            OmniInferLocalRuntime.stop()
        }
        File(target.path).delete()
        File("${target.path}.part").delete()
        if (getActiveModelId() == target.id) {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, "")
        }
        val context = ensureContext()
        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        OmniInferBuiltinProviderRefresher.refreshAsync(context, "litert_delete:${target.id}")
        return listInstalledModels()
    }

    suspend fun importModelFromUri(context: Context, uri: Uri): Map<String, Any?> {
        val applicationContext = context.applicationContext
        setContext(applicationContext)
        val docFile = DocumentFile.fromSingleUri(context, uri)
            ?: return mapOf("success" to false, "error" to "Cannot open selected file")
        val rawName = docFile.name?.trim().orEmpty()
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty() }
        if (!rawName.endsWith(LITERT_EXTENSION, ignoreCase = true)) {
            return mapOf("success" to false, "error" to "Please select a .litertlm model file")
        }

        val safeName = sanitizeFileName(rawName)
        val modelId = safeName.removeSuffixIgnoreCase(LITERT_EXTENSION)
        if (modelId.isBlank()) {
            return mapOf("success" to false, "error" to "Invalid LiteRT model filename")
        }

        val liteRtDir = AgentWorkspaceManager.modelsLiteRtDirectory(applicationContext)
        liteRtDir.mkdirs()
        val destFile = File(liteRtDir, safeName)
        if (destFile.exists()) {
            return mapOf("success" to false, "error" to "Model already exists: $modelId")
        }

        val totalSize = docFile.length().takeIf { it > 0L } ?: 0L
        if (totalSize > 0L && totalSize > liteRtDir.usableSpace) {
            return mapOf("success" to false, "error" to "Insufficient storage space")
        }

        try {
            withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Cannot open selected file")
                var copiedSize = 0L
                var lastEmitTime = 0L
                val buffer = ByteArray(8192)
                inputStream.buffered().use { input ->
                    destFile.outputStream().buffered().use { output ->
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            copiedSize += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 300) {
                                lastEmitTime = now
                                emitEvent(
                                    "import_progress",
                                    mapOf(
                                        "modelId" to modelId,
                                        "progress" to if (totalSize > 0L) copiedSize.toDouble() / totalSize else 0.0,
                                        "copiedSize" to copiedSize,
                                        "totalSize" to totalSize,
                                        "currentFile" to safeName,
                                    )
                                )
                            }
                        }
                    }
                }
                emitEvent(
                    "import_progress",
                    mapOf(
                        "modelId" to modelId,
                        "progress" to 1.0,
                        "copiedSize" to copiedSize,
                        "totalSize" to if (totalSize > 0L) totalSize else copiedSize,
                        "currentFile" to safeName,
                    )
                )
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Import failed for $modelId", e)
            if (destFile.exists()) {
                destFile.delete()
            }
            return mapOf("success" to false, "error" to "Copy failed: ${e.message}")
        }

        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        OmniInferBuiltinProviderRefresher.refreshAsync(applicationContext, "litert_import:$modelId")
        return mapOf("success" to true, "modelId" to modelId)
    }

    private fun installedRecords(): List<InstalledLiteRtRecord> {
        val context = ensureContext()
        val liteRtDir = AgentWorkspaceManager.modelsLiteRtDirectory(context)
        if (!liteRtDir.exists()) return emptyList()
        return liteRtDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(LITERT_EXTENSION, ignoreCase = true) }
            ?.map { file ->
                InstalledLiteRtRecord(
                    id = file.name.removeSuffixIgnoreCase(LITERT_EXTENSION),
                    name = file.name.removeSuffixIgnoreCase(LITERT_EXTENSION),
                    path = file.absolutePath,
                    fileSize = file.length(),
                    downloadedAt = file.lastModified(),
                )
            }
            .orEmpty()
    }

    private fun findInstalledRecord(modelId: String): InstalledLiteRtRecord? {
        val normalizedModelId = normalizeStoredModelId(modelId)
        if (normalizedModelId.isEmpty()) {
            return null
        }
        return installedRecords().firstOrNull {
            it.id == normalizedModelId ||
                File(it.path).name.equals(normalizedModelId, ignoreCase = true)
        }
    }

    private fun installedRecordToMap(record: InstalledLiteRtRecord): Map<String, Any?> {
        val activeModelId = getActiveModelId()
        val loadedModelId = getLoadedModelId()
        val marketModel = findMarketModel(record.id)
        return mapOf(
            "id" to record.id,
            "name" to record.name,
            "category" to "llm",
            "backend" to BACKEND_NAME,
            "source" to if (marketModel != null) "ModelScope" else "Manual",
            "description" to (marketModel?.description ?: "LiteRT-LM GPU"),
            "path" to record.path,
            "vendor" to (marketModel?.vendor ?: "LiteRT"),
            "tags" to (marketModel?.tags ?: listOf("LiteRT", "GPU")),
            "extraTags" to emptyList<String>(),
            "active" to (record.id == activeModelId || record.id == loadedModelId),
            "isLocal" to true,
            "isPinned" to false,
            "hasUpdate" to false,
            "fileSize" to record.fileSize,
            "sizeB" to record.fileSize.toDouble(),
            "formattedSize" to formatSize(record.fileSize),
            "lastUsedAt" to 0,
            "downloadedAt" to record.downloadedAt,
            "readOnly" to false,
            "download" to null,
        )
    }

    private fun marketModelToMap(model: LiteRtMarketModel): Map<String, Any?> {
        val context = ensureContext()
        val liteRtDir = AgentWorkspaceManager.modelsLiteRtDirectory(context)
        val destFile = File(liteRtDir, model.fileName)
        val partFile = File(liteRtDir, "${model.fileName}.part")
        val activeState = activeDownloads[model.id]
        val downloadInfo = when {
            activeState != null -> downloadInfoMap(
                stateLabel = "downloading",
                progress = activeState.progress,
                savedSize = activeState.savedSize,
                totalSize = activeState.totalSize,
                currentFile = model.fileName,
            )
            destFile.exists() && destFile.length() > 0L -> downloadInfoMap(
                stateLabel = "completed",
                progress = 1.0,
                savedSize = destFile.length(),
                totalSize = destFile.length(),
                currentFile = model.fileName,
            )
            partFile.exists() -> {
                val savedSize = partFile.length()
                downloadInfoMap(
                    stateLabel = "paused",
                    progress = 0.0,
                    savedSize = savedSize,
                    totalSize = 0,
                    currentFile = model.fileName,
                )
            }
            else -> null
        }
        val fileSize = destFile.takeIf { it.exists() }?.length() ?: 0L
        return mapOf(
            "id" to model.id,
            "name" to model.name,
            "category" to "llm",
            "backend" to BACKEND_NAME,
            "source" to "ModelScope",
            "description" to model.description,
            "path" to destFile.absolutePath,
            "vendor" to model.vendor,
            "tags" to model.tags,
            "extraTags" to emptyList<String>(),
            "active" to false,
            "isLocal" to (destFile.exists() && destFile.length() > 0L),
            "isPinned" to false,
            "hasUpdate" to false,
            "fileSize" to fileSize,
            "sizeB" to fileSize.toDouble(),
            "formattedSize" to formatSize(fileSize),
            "lastUsedAt" to 0,
            "downloadedAt" to 0,
            "readOnly" to false,
            "download" to downloadInfo,
        )
    }

    private fun findMarketModel(modelId: String?): LiteRtMarketModel? {
        val normalized = normalizeStoredModelId(modelId)
        if (normalized.isEmpty()) return null
        return marketModels().firstOrNull { model ->
            model.id.equals(normalized, ignoreCase = true) ||
                model.fileName.equals(normalized, ignoreCase = true) ||
                model.fileName.removeSuffixIgnoreCase(LITERT_EXTENSION)
                    .equals(normalized, ignoreCase = true)
        }
    }

    private fun buildLiteRtExtraConfig(modelId: String): Map<String, String> {
        val marketModel = findMarketModel(modelId)
        return if (marketModel != null) {
            mapOf(
                "backend_type" to "gpu",
                "vision_backend" to "gpu",
                "max_images" to "5",
                "enable_speculative_decoding" to "true",
            )
        } else {
            mapOf("backend_type" to "gpu")
        }
    }

    private fun marketModels(): List<LiteRtMarketModel> {
        cachedMarketModels?.let { return it }
        val context = ensureContext()
        val raw = context.assets.open(MARKET_ASSET_NAME).bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(raw).jsonObject
        val models = root["models"]?.jsonArray
            ?.mapNotNull(::parseMarketModel)
            .orEmpty()
        cachedMarketModels = models
        return models
    }

    private fun parseMarketModel(element: kotlinx.serialization.json.JsonElement): LiteRtMarketModel? {
        val obj = element.jsonObject
        val id = obj.stringValue("id") ?: return null
        val name = obj.stringValue("name") ?: id
        val repo = obj.stringValue("repo") ?: return null
        val fileName = obj.stringValue("fileName") ?: return null
        val vendor = obj.stringValue("vendor") ?: "Google"
        val description = obj.stringValue("description")
            ?: "Gemma LiteRT-LM model for OmniInfer LiteRT backend"
        val tags = obj["tags"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("LiteRT", "Gemma", "GPU")
        return LiteRtMarketModel(
            id = id,
            name = name,
            repo = repo,
            fileName = fileName,
            vendor = vendor,
            description = description,
            tags = tags,
        )
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun downloadMarketModel(
        model: LiteRtMarketModel,
        destFile: File,
        state: LiteRtDownloadState,
    ) {
        if (state.cancelled) throw DownloadCancelledException()
        destFile.parentFile?.mkdirs()
        val partFile = File(destFile.parentFile, "${destFile.name}.part")
        var startByte = partFile.takeIf { it.exists() }?.length() ?: 0L
        val requestBuilder = Request.Builder().url(model.downloadUrl)
        if (startByte > 0L) {
            requestBuilder.header("Range", "bytes=$startByte-")
        }
        val call = httpClient.newCall(requestBuilder.build())
        state.call = call
        call.execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw RuntimeException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw RuntimeException("Empty response body")
            if (startByte > 0L && response.code != 206) {
                startByte = 0L
            }
            val contentLength = body.contentLength().coerceAtLeast(0L)
            state.totalSize = if (response.code == 206) startByte + contentLength else contentLength
            state.savedSize = startByte
            state.progress = if (state.totalSize > 0L) state.savedSize.toDouble() / state.totalSize else 0.0

            FileOutputStream(partFile, startByte > 0L).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var lastEmitTime = 0L
                    while (true) {
                        if (state.cancelled) throw DownloadCancelledException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        state.savedSize += read
                        state.progress = if (state.totalSize > 0L) {
                            state.savedSize.toDouble() / state.totalSize
                        } else {
                            0.0
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 500L) {
                            lastEmitTime = now
                            emitDownloadUpdate(
                                model.id,
                                downloadInfoMap(
                                    stateLabel = "downloading",
                                    progress = state.progress,
                                    savedSize = state.savedSize,
                                    totalSize = state.totalSize,
                                    currentFile = model.fileName,
                                )
                            )
                        }
                    }
                }
            }
        }
        if (state.cancelled) throw DownloadCancelledException()
        if (!partFile.renameTo(destFile)) {
            throw RuntimeException("Failed to finalize downloaded file")
        }
    }

    private fun getActiveModelId(): String {
        val stored = mmkv.decodeString(KEY_ACTIVE_MODEL_ID, "").orEmpty()
        val normalized = normalizeStoredModelId(stored)
        if (normalized != stored) {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, normalized)
        }
        return normalized
    }

    private fun getLoadedModelId(): String =
        normalizeStoredModelId(OmniInferLocalRuntime.getLoadedModelId())

    private fun shouldAutoStartOnAppOpen(): Boolean =
        mmkv.decodeBool(KEY_AUTO_START, false)

    private fun normalizeStoredModelId(modelId: String?): String =
        modelId?.trim()
            ?.removeSuffixIgnoreCase(LITERT_EXTENSION)
            .orEmpty()

    private fun ensureContext(): Context =
        appContext ?: error("OmniInfer LiteRT context is not initialized")

    private fun emitConfigChanged() {
        emitEvent("config_changed", mapOf("config" to getConfig()))
    }

    private fun emitEvent(type: String, payload: Map<String, Any?>) {
        eventDispatcher?.invoke(
            buildMap {
                put("type", type)
                putAll(payload)
            }
        )
    }

    private fun emitDownloadUpdate(modelId: String, info: Map<String, Any?>) {
        emitEvent("download_update", mapOf("modelId" to modelId, "download" to info))
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

    private fun sanitizeFileName(rawName: String): String {
        return rawName.replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("""[^\w.\-()+ ]"""), "_")
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String {
        return if (endsWith(suffix, ignoreCase = true)) {
            substring(0, length - suffix.length)
        } else {
            this
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) {
            return ""
        }
        return when {
            bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private class LiteRtDownloadState(
        @Volatile var progress: Double = 0.0,
        @Volatile var savedSize: Long = 0L,
        @Volatile var totalSize: Long = 0L,
        @Volatile var currentFile: String = "",
        @Volatile var cancelled: Boolean = false,
        @Volatile var call: Call? = null,
    )

    private class DownloadCancelledException : Exception("Download cancelled")
}
