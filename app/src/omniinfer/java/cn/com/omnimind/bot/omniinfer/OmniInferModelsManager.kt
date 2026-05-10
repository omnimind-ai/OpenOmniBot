package cn.com.omnimind.bot.omniinfer

import android.content.Context
import android.util.Log
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.omniinfer.server.OmniInferServer
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.closeQuietly
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object OmniInferModelsManager {
    private const val TAG = "OmniInferModels"
    private const val MARKET_URL =
        "https://omnimind-model.oss-cn-beijing.aliyuncs.com/llama.cpp/model_market.json"
    private const val ASSET_NAME = "omniinfer_llama_model_market.json"
    private const val CACHE_DIR = "omniinfer"
    private const val CACHE_FILE_NAME = "llama_model_market_cache.json"
    private const val MMKV_ID = "omniinfer_config"
    private const val KEY_ACTIVE_MODEL_ID = "omniinfer_llama_active_model_id"
    private const val KEY_AUTO_START = "omniinfer_llama_auto_start_on_app_open"
    private const val KEY_DOWNLOAD_PROVIDER = "omniinfer_llama_download_provider"
    private const val LEGACY_ACTIVE_MODEL_ID = "activeModelId"
    private const val LEGACY_AUTO_START = "autoStartOnAppOpen"
    private const val LEGACY_DOWNLOAD_PROVIDER = "downloadProvider"

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var cachedMarketJson: JsonObject? = null
    private var cachedMarketModels: List<JsonObject> = emptyList()
    private val activeDownloads = ConcurrentHashMap<String, DownloadTask>()
    private var eventDispatcher: ((Map<String, Any?>) -> Unit)? = null

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    fun setContext(context: Context) {
        appContext = context.applicationContext
        OmniInferLocalRuntime.setContext(context.applicationContext)
    }

    fun setEventDispatcher(dispatcher: ((Map<String, Any?>) -> Unit)?) {
        eventDispatcher = dispatcher
    }

    fun activeDownloadCount(): Int = activeDownloads.size

    fun clear() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        eventDispatcher = null
    }

    fun handleAppOpen() {
        if (shouldAutoStartOnAppOpen()) {
            Thread({ startApiService(getActiveModelId()) }, "OmniInfer-autostart").start()
        }
    }

    fun getConfig(): Map<String, Any?> {
        val running = OmniInferLocalRuntime.isReady()
        return mapOf(
            "backend" to OmniInferLocalRuntime.BACKEND_LLAMA_CPP,
            "autoStartOnAppOpen" to shouldAutoStartOnAppOpen(),
            "apiRunning" to running,
            "apiReady" to running,
            "apiState" to if (running) "running" else "stopped",
            "apiHost" to OmniInferLocalRuntime.getHost(),
            "apiPort" to OmniInferLocalRuntime.getPort(),
            "baseUrl" to OmniInferLocalRuntime.getBaseUrl(),
            "activeModelId" to getActiveModelId(),
            "downloadProvider" to getDownloadProvider(),
            "availableSources" to listOf("ModelScope", "HuggingFace"),
            "loadedBackend" to OmniInferLocalRuntime.getLoadedBackend(),
            "loadedModelId" to OmniInferLocalRuntime.getLoadedModelId(),
        )
    }

    fun saveConfig(args: Map<*, *>): Map<String, Any?> {
        args["apiPort"]?.let { value ->
            val port = (value as? Number)?.toInt()
            if (port != null && port > 0) {
                OmniInferLocalRuntime.setPort(port)
            }
        }
        args["activeModelId"]?.let { mmkv.encode(KEY_ACTIVE_MODEL_ID, it.toString()) }
        args["autoStartOnAppOpen"]?.let { mmkv.encode(KEY_AUTO_START, it == true) }
        args["downloadProvider"]?.let { mmkv.encode(KEY_DOWNLOAD_PROVIDER, normalizeSource(it.toString())) }
        emitConfigChanged()
        return getConfig()
    }

    fun setActiveModel(modelId: String?): Map<String, Any?> {
        mmkv.encode(KEY_ACTIVE_MODEL_ID, modelId?.trim().orEmpty())
        emitConfigChanged()
        return getConfig()
    }

    private fun getModelDir(): File {
        val context = appContext ?: error("OmniInfer context is not initialized")
        val dir = AgentWorkspaceManager.modelsLlamaDirectory(context)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getLegacyModelDir(): File? {
        val context = appContext ?: return null
        val dir = File(context.getExternalFilesDir(null), "omniinfer-llama")
        return if (dir.exists()) dir else null
    }

    private fun findModelFile(modelId: String): File? {
        val fileName = "$modelId.gguf"
        // New structure: subdirectory per model
        val subDirFile = File(getModelDir(), "$modelId/$fileName")
        if (subDirFile.exists()) return subDirFile
        // Old structure: flat layout
        val flatFile = File(getModelDir(), fileName)
        if (flatFile.exists()) return flatFile
        // Legacy external directory
        val legacyDir = getLegacyModelDir()
        if (legacyDir != null) {
            val legacyFile = File(legacyDir, fileName)
            if (legacyFile.exists()) return legacyFile
        }
        return null
    }

    fun listInstalledModels(query: String? = null, category: String? = null): List<Map<String, Any?>> {
        val normalizedQuery = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val seen = mutableSetOf<String>()
        val allFiles = mutableListOf<File>()

        val modelDir = getModelDir()
        // New structure: subdirectories containing .gguf files (skip mmproj)
        modelDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && !f.name.contains("mmproj") }
                ?.forEach { file -> if (seen.add(file.nameWithoutExtension)) allFiles.add(file) }
        }
        // Old structure: flat .gguf files
        modelDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.forEach { file -> if (seen.add(file.nameWithoutExtension)) allFiles.add(file) }

        getLegacyModelDir()?.listFiles { file -> file.isFile && file.name.endsWith(".gguf") }
            ?.forEach { file -> if (seen.add(file.nameWithoutExtension)) allFiles.add(file) }

        return allFiles
            .sortedByDescending { it.lastModified() }
            .filter { file ->
                if (normalizedQuery.isEmpty()) {
                    true
                } else {
                    file.name.lowercase(Locale.getDefault()).contains(normalizedQuery)
                }
            }
            .map { file -> modelFileToMap(file, activeDownloads[file.nameWithoutExtension]) }
    }

    private fun modelFileToMap(file: File, download: DownloadTask? = null): Map<String, Any?> {
        val id = file.nameWithoutExtension
        val sizeBytes = file.length()
        return mapOf(
            "id" to id,
            "name" to id,
            "category" to "llm",
            "backend" to OmniInferLocalRuntime.BACKEND_LLAMA_CPP,
            "source" to "llama.cpp",
            "description" to "",
            "path" to file.absolutePath,
            "vendor" to "",
            "tags" to listOf("GGUF"),
            "extraTags" to emptyList<String>(),
            "active" to (id == getActiveModelId() || id == OmniInferLocalRuntime.getLoadedModelId()),
            "isLocal" to true,
            "isPinned" to false,
            "hasUpdate" to false,
            "fileSize" to sizeBytes,
            "sizeB" to sizeBytes.toDouble(),
            "formattedSize" to formatSize(sizeBytes),
            "lastUsedAt" to 0,
            "downloadedAt" to file.lastModified(),
            "download" to download?.toMap(),
            "readOnly" to false,
        )
    }

    suspend fun refreshInstalledModels(): List<Map<String, Any?>> {
        return listInstalledModels()
    }

    suspend fun listMarketModels(
        query: String? = null,
        category: String? = null,
        refresh: Boolean = false,
    ): Map<String, Any?> {
        if (refresh || cachedMarketJson == null) {
            fetchMarketJson(refresh)
        }
        val selectedSource = getDownloadProvider()
        val normalizedQuery = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val models = cachedMarketModels
            .filter { model ->
                if (normalizedQuery.isEmpty()) {
                    true
                } else {
                    val name = model["modelName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val vendor = model["vendor"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    name.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                        vendor.lowercase(Locale.getDefault()).contains(normalizedQuery)
                }
            }
            .flatMap { model -> marketModelToMaps(model, selectedSource) }

        return mapOf(
            "source" to selectedSource,
            "category" to "llm",
            "availableSources" to listOf("ModelScope", "HuggingFace"),
            "models" to models,
        )
    }

    suspend fun refreshMarketModels(
        query: String? = null,
        category: String? = null,
    ): Map<String, Any?> {
        return listMarketModels(query = query, category = category, refresh = true)
    }

    private suspend fun fetchMarketJson(refresh: Boolean) {
        val resolvedJson = if (!refresh) {
            cachedMarketJson ?: loadLocalMarketJson()
        } else {
            runCatching { fetchRemoteMarketJson() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to fetch llama.cpp market JSON", error)
                }
                .getOrElse { cachedMarketJson ?: loadLocalMarketJson() }
        }
        cachedMarketJson = resolvedJson
        cachedMarketModels = resolvedJson["models"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()
    }

    private fun ensureMarketSeedLoaded() {
        if (cachedMarketJson != null) {
            return
        }
        runCatching { loadLocalMarketJson() }
            .onSuccess { json ->
                cachedMarketJson = json
                cachedMarketModels = json["models"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()
            }
            .onFailure { error ->
                Log.e(TAG, "Failed to load local llama.cpp market JSON", error)
            }
    }

    private fun loadLocalMarketJson(): JsonObject {
        val context = appContext ?: error("OmniInfer context is not initialized")
        val cacheFile = marketCacheFile(context)
        val cachedText = runCatching {
            if (cacheFile.exists()) cacheFile.readText() else null
        }.getOrNull()
        if (!cachedText.isNullOrBlank()) {
            return decodeMarketJson(cachedText)
        }
        val assetText = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return decodeMarketJson(assetText)
    }

    private suspend fun fetchRemoteMarketJson(): JsonObject {
        val context = appContext ?: error("OmniInfer context is not initialized")
        val body = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(MARKET_URL).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Failed to fetch llama.cpp market: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        }
        val json = decodeMarketJson(body)
        withContext(Dispatchers.IO) {
            val target = marketCacheFile(context)
            target.parentFile?.mkdirs()
            target.writeText(body)
        }
        return json
    }

    private fun decodeMarketJson(raw: String): JsonObject {
        return Json.parseToJsonElement(raw).jsonObject
    }

    private fun marketCacheFile(context: Context): File {
        return File(File(context.filesDir, CACHE_DIR), CACHE_FILE_NAME)
    }

    private fun marketModelToMaps(model: JsonObject, source: String): List<Map<String, Any?>> {
        val modelName = model["modelName"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val vendor = model["vendor"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tags = model["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val params = model["params"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val license = model["license"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val quants = model["quants"]?.jsonObject ?: return emptyList()
        val sources = model["sources"]?.jsonObject

        val repo = sources?.get(source)?.jsonPrimitive?.contentOrNull.orEmpty()

        return quants.entries.mapNotNull { (quantName, quantObj) ->
            val sizeBytes = quantObj.jsonObject["size_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val id = "$modelName-$quantName"
            val localFile = findModelFile(id)
            val activeDownload = activeDownloads[id]
            val hasPausedPart = buildPausedMapFromPartFiles(id, sizeBytes) != null
            // Hide models that have no repo for the current source,
            // unless already downloaded, actively downloading, or paused.
            if (repo.isEmpty() && localFile == null && activeDownload == null && !hasPausedPart) {
                return@mapNotNull null
            }
            val downloadMap = when {
                activeDownload != null -> activeDownload.toMap()
                localFile != null -> completedDownloadMap(localFile.length())
                else -> buildPausedMapFromPartFiles(id, sizeBytes)
            }
            mapOf(
                "id" to id,
                "name" to "$modelName $quantName",
                "category" to "llm",
                "source" to source,
                "description" to buildList {
                    if (params.isNotEmpty()) add("Params: $params")
                    if (license.isNotEmpty()) add("License: $license")
                    if (repo.isNotEmpty()) add("Repo: $repo")
                }.joinToString(separator = " | "),
                "path" to (localFile?.absolutePath ?: ""),
                "vendor" to vendor,
                "tags" to (tags + listOf("GGUF", quantName)),
                "extraTags" to listOf(params).filter { it.isNotBlank() },
                "active" to (id == getActiveModelId()),
                "isLocal" to (localFile != null),
                "isPinned" to false,
                "hasUpdate" to false,
                "fileSize" to sizeBytes,
                "sizeB" to sizeBytes.toDouble(),
                "formattedSize" to formatSize(sizeBytes),
                "lastUsedAt" to 0,
                "downloadedAt" to (localFile?.lastModified() ?: 0L),
                "download" to downloadMap,
                "readOnly" to false,
            )
        }
    }

    suspend fun getOverview(
        installedQuery: String? = null,
        marketQuery: String? = null,
        marketCategory: String? = null,
    ): Map<String, Any?> {
        return mapOf(
            "config" to getConfig(),
            "installedModels" to listInstalledModels(query = installedQuery),
            "market" to listMarketModels(query = marketQuery, category = marketCategory),
        )
    }

    fun startDownload(modelId: String) {
        if (activeDownloads.containsKey(modelId)) {
            Log.d(TAG, "[Download] startDownload skipped, already active: $modelId")
            return
        }
        Log.d(TAG, "[Download] startDownload: $modelId")
        val source = getDownloadProvider()
        val resolved = findRepoAndQuantForModel(modelId, source) ?: run {
            Log.e(TAG, "[Download] startDownload failed, no download source: $modelId")
            emitEvent("download_error", mapOf("modelId" to modelId, "error" to "No download source"))
            return
        }
        val repoBaseName = resolved.repo.substringAfterLast("/")
            .removeSuffix("-GGUF")
            .removeSuffix("-gguf")
        val remoteFileName = "$repoBaseName-${resolved.quant}.gguf"
        val url = buildDownloadUrl(source, resolved.repo, remoteFileName)
        val modelSubDir = File(getModelDir(), modelId)
        modelSubDir.mkdirs()
        val destination = File(modelSubDir, "$modelId.gguf")
        val mmprojUrl = if (resolved.needsMmproj) buildDownloadUrl(source, resolved.repo, "mmproj-F16.gguf") else null
        val mmprojDest = if (resolved.needsMmproj) File(modelSubDir, "mmproj-F16.gguf") else null
        val task = DownloadTask(modelId, url, destination, mmprojUrl, mmprojDest)
        task.initFromPartFile(lookupMarketFileSize(modelId))
        activeDownloads[modelId] = task
        appContext?.let {
            ModelDownloadForegroundService.start(it, activeDownloads.size, modelId)
        }
        task.start()
    }

    fun pauseDownload(modelId: String) {
        Log.d(TAG, "[Download] pauseDownload: $modelId, hasActiveTask=${activeDownloads.containsKey(modelId)}")
        val task = activeDownloads.remove(modelId)
        task?.cancel()
        val pausedMap = task?.toPausedMap() ?: buildPausedMapFromPartFiles(modelId)
        Log.d(TAG, "[Download] pauseDownload: $modelId -> paused state=${pausedMap != null}, progress=${pausedMap?.get("progress")}, savedSize=${pausedMap?.get("savedSize")}")
        if (pausedMap != null) {
            emitEvent(
                "download_update",
                mapOf("modelId" to modelId, "download" to pausedMap),
            )
        }
        emitEvent("downloads_changed", mapOf("modelId" to modelId))
        appContext?.let { ModelDownloadForegroundService.stopIfIdle(it) }
    }

    suspend fun deleteModel(modelId: String): List<Map<String, Any?>> {
        if (OmniInferLocalRuntime.isModelLoaded(OmniInferLocalRuntime.BACKEND_LLAMA_CPP, modelId)) {
            OmniInferLocalRuntime.stop()
        }
        // Delete subdirectory (new structure) including mmproj
        val modelSubDir = File(getModelDir(), modelId)
        if (modelSubDir.isDirectory) {
            modelSubDir.deleteRecursively()
        }
        // Also delete flat file (old structure) if exists
        val flatFile = File(getModelDir(), "$modelId.gguf")
        if (flatFile.exists()) flatFile.delete()
        // Legacy
        getLegacyModelDir()?.let { dir ->
            val legacyFile = File(dir, "$modelId.gguf")
            if (legacyFile.exists()) legacyFile.delete()
        }
        if (getActiveModelId() == modelId) {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, "")
        }
        activeDownloads.remove(modelId)
        emitConfigChanged()
        appContext?.let {
            OmniInferBuiltinProviderRefresher.refreshAsync(it, "llama_delete:$modelId")
        }
        return listInstalledModels()
    }

    private data class ResolvedModel(
        val repo: String,
        val quant: String,
        val needsMmproj: Boolean,
    )

    private fun findRepoAndQuantForModel(modelId: String, source: String): ResolvedModel? {
        ensureMarketSeedLoaded()
        cachedMarketModels.forEach { model ->
            val modelName = model["modelName"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val quants = model["quants"]?.jsonObject ?: return@forEach
            quants.keys.forEach { quantName ->
                if ("$modelName-$quantName" == modelId) {
                    val repo = model["sources"]?.jsonObject?.get(source)?.jsonPrimitive?.contentOrNull
                        ?: return null
                    val needsMmproj = model["mmproj"]?.jsonPrimitive?.booleanOrNull == true
                    return ResolvedModel(repo, quantName, needsMmproj)
                }
            }
        }
        return null
    }

    /** Look up the expected file size (bytes) for [modelId] from cached market data. */
    private fun lookupMarketFileSize(modelId: String): Long {
        ensureMarketSeedLoaded()
        for (model in cachedMarketModels) {
            val modelName = model["modelName"]?.jsonPrimitive?.contentOrNull ?: continue
            val quants = model["quants"]?.jsonObject ?: continue
            for ((quantName, quantObj) in quants) {
                if ("$modelName-$quantName" == modelId) {
                    return quantObj.jsonObject["size_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                }
            }
        }
        return 0L
    }

    private fun buildDownloadUrl(source: String, repo: String, fileName: String): String {
        return when (source) {
            "HuggingFace" -> "https://huggingface.co/$repo/resolve/main/$fileName"
            else -> "https://modelscope.cn/models/$repo/resolve/master/$fileName"
        }
    }

    fun startApiService(modelId: String?): Map<String, Any?> {
        val resolvedModelId = modelId?.trim().orEmpty().ifBlank { getActiveModelId() }
        if (resolvedModelId.isBlank()) {
            return getConfig()
        }
        val modelFile = findModelFile(resolvedModelId) ?: return getConfig()
        mmkv.encode(KEY_ACTIVE_MODEL_ID, resolvedModelId)
        OmniInferLocalRuntime.loadModel(
            modelId = resolvedModelId,
            modelPath = modelFile.absolutePath,
            backend = OmniInferLocalRuntime.BACKEND_LLAMA_CPP,
        )
        emitConfigChanged()
        return getConfig()
    }

    fun stopApiService(): Map<String, Any?> {
        OmniInferLocalRuntime.stop()
        emitConfigChanged()
        return getConfig()
    }

    fun ensureModelReady(modelId: String): Boolean {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isEmpty()) {
            return false
        }
        if (OmniInferLocalRuntime.isModelLoaded(OmniInferLocalRuntime.BACKEND_LLAMA_CPP, normalizedModelId)) {
            return true
        }
        val targetFile = findModelFile(normalizedModelId) ?: return false
        mmkv.encode(KEY_ACTIVE_MODEL_ID, normalizedModelId)
        return OmniInferLocalRuntime.loadModel(
            modelId = normalizedModelId,
            modelPath = targetFile.absolutePath,
            backend = OmniInferLocalRuntime.BACKEND_LLAMA_CPP,
        )
    }

    private fun getActiveModelId(): String {
        val stored = mmkv.decodeString(KEY_ACTIVE_MODEL_ID, null)
        if (!stored.isNullOrBlank()) {
            return stored
        }
        val legacy = mmkv.decodeString(LEGACY_ACTIVE_MODEL_ID, "").orEmpty()
        if (legacy.isNotBlank()) {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, legacy)
        }
        return legacy
    }

    private fun shouldAutoStartOnAppOpen(): Boolean {
        return if (mmkv.containsKey(KEY_AUTO_START)) {
            mmkv.decodeBool(KEY_AUTO_START, false)
        } else {
            mmkv.decodeBool(LEGACY_AUTO_START, false).also { legacy ->
                mmkv.encode(KEY_AUTO_START, legacy)
            }
        }
    }

    private fun getDownloadProvider(): String {
        val stored = if (mmkv.containsKey(KEY_DOWNLOAD_PROVIDER)) {
            mmkv.decodeString(KEY_DOWNLOAD_PROVIDER, "ModelScope")
        } else {
            mmkv.decodeString(LEGACY_DOWNLOAD_PROVIDER, "ModelScope")
        }
        val normalized = normalizeSource(stored)
        mmkv.encode(KEY_DOWNLOAD_PROVIDER, normalized)
        return normalized
    }

    private fun normalizeSource(rawSource: String?): String {
        return when (rawSource?.trim()) {
            "HuggingFace" -> "HuggingFace"
            else -> "ModelScope"
        }
    }

    suspend fun importModel(filePath: String): Map<String, Any?> {
        val sourceFile = File(filePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return mapOf("success" to false, "error" to "File does not exist: $filePath")
        }
        if (!sourceFile.name.endsWith(".gguf", ignoreCase = true)) {
            return mapOf("success" to false, "error" to "Not a .gguf file")
        }

        val modelId = sourceFile.nameWithoutExtension
        val modelSubDir = File(getModelDir(), modelId)
        val destFile = File(modelSubDir, "${modelId}.gguf")

        if (destFile.exists()) {
            return mapOf("success" to false, "error" to "Model already exists: $modelId")
        }

        val fileSize = sourceFile.length()
        val usableSpace = modelSubDir.parentFile?.usableSpace ?: 0L
        if (fileSize > usableSpace) {
            return mapOf("success" to false, "error" to "Insufficient storage space")
        }

        modelSubDir.mkdirs()

        try {
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(8192)
                var copiedSize = 0L
                var lastEmitTime = 0L
                sourceFile.inputStream().buffered().use { input ->
                    destFile.outputStream().buffered().use { output ->
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            copiedSize += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 300) {
                                lastEmitTime = now
                                val progress = if (fileSize > 0) copiedSize.toDouble() / fileSize else 0.0
                                emitEvent("import_progress", mapOf(
                                    "modelId" to modelId,
                                    "progress" to progress,
                                    "copiedSize" to copiedSize,
                                    "totalSize" to fileSize,
                                ))
                            }
                        }
                    }
                }
                emitEvent("import_progress", mapOf(
                    "modelId" to modelId,
                    "progress" to 1.0,
                    "copiedSize" to fileSize,
                    "totalSize" to fileSize,
                ))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Import failed for $modelId", e)
            if (modelSubDir.exists()) modelSubDir.deleteRecursively()
            return mapOf("success" to false, "error" to "Copy failed: ${e.message}")
        }

        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        appContext?.let {
            OmniInferBuiltinProviderRefresher.refreshAsync(it, "llama_import:$modelId")
        }
        return mapOf("success" to true, "modelId" to modelId)
    }

    suspend fun importModelFromUri(context: Context, uri: android.net.Uri): Map<String, Any?> {
        // Resolve display name from content URI
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "unknown"

        if (!displayName.endsWith(".gguf", ignoreCase = true)) {
            return mapOf("success" to false, "error" to "Not a .gguf file: $displayName")
        }

        val modelId = displayName.removeSuffix(".gguf").removeSuffix(".GGUF")
        val modelSubDir = File(getModelDir(), modelId)
        val destFile = File(modelSubDir, "$modelId.gguf")

        if (destFile.exists()) {
            return mapOf("success" to false, "error" to "Model already exists: $modelId")
        }

        // Get file size from content URI
        val fileSize = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            } else 0L
        } ?: 0L

        val usableSpace = getModelDir().usableSpace
        if (fileSize > 0 && fileSize > usableSpace) {
            return mapOf("success" to false, "error" to "Insufficient storage space")
        }

        modelSubDir.mkdirs()

        try {
            withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Cannot open input stream for URI")
                val buffer = ByteArray(8192)
                var copiedSize = 0L
                var lastEmitTime = 0L
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
                                val progress = if (fileSize > 0) copiedSize.toDouble() / fileSize else 0.0
                                emitEvent("import_progress", mapOf(
                                    "modelId" to modelId,
                                    "progress" to progress,
                                    "copiedSize" to copiedSize,
                                    "totalSize" to fileSize,
                                ))
                            }
                        }
                    }
                }
                emitEvent("import_progress", mapOf(
                    "modelId" to modelId,
                    "progress" to 1.0,
                    "copiedSize" to copiedSize,
                    "totalSize" to copiedSize,
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import from URI failed for $modelId", e)
            if (modelSubDir.exists()) modelSubDir.deleteRecursively()
            return mapOf("success" to false, "error" to "Copy failed: ${e.message}")
        }

        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        appContext?.let {
            OmniInferBuiltinProviderRefresher.refreshAsync(it, "llama_import:$modelId")
        }
        return mapOf("success" to true, "modelId" to modelId)
    }

    private fun emitConfigChanged() {
        emitEvent("config_changed", mapOf("config" to getConfig()))
    }

    private fun emitEvent(type: String, payload: Map<String, Any?>) {
        eventDispatcher?.invoke(mapOf("type" to type) + payload)
    }

    private fun buildPausedMapFromPartFiles(modelId: String, knownTotalSize: Long = 0L): Map<String, Any?>? {
        val modelSubDir = File(getModelDir(), modelId)
        if (!modelSubDir.isDirectory) return null
        val partFile = File(modelSubDir, "$modelId.gguf.part")
        if (!partFile.exists()) return null
        val savedSize = partFile.length()
        val totalSize = if (knownTotalSize > 0) knownTotalSize else lookupMarketFileSize(modelId)
        val progress = if (totalSize > 0) savedSize.toDouble() / totalSize else 0.0
        return mapOf(
            "state" to 4,
            "stateLabel" to "paused",
            "progress" to progress,
            "savedSize" to savedSize,
            "totalSize" to totalSize,
            "speedInfo" to "",
            "errorMessage" to "",
            "progressStage" to "",
            "currentFile" to "$modelId.gguf",
            "hasUpdate" to false,
        )
    }

    private fun completedDownloadMap(sizeBytes: Long): Map<String, Any?> {
        return mapOf(
            "state" to 0,
            "stateLabel" to "completed",
            "progress" to 1.0,
            "savedSize" to sizeBytes,
            "totalSize" to sizeBytes,
            "speedInfo" to "",
            "errorMessage" to "",
            "progressStage" to "",
            "currentFile" to "",
            "hasUpdate" to false,
        )
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

    private class DownloadTask(
        private val modelId: String,
        private val url: String,
        private val destFile: File,
        private val mmprojUrl: String? = null,
        private val mmprojDest: File? = null,
    ) {
        companion object {
            private const val MAX_RETRIES = 3
            private const val INITIAL_RETRY_DELAY_MS = 2000L
        }

        private var call: Call? = null
        private val cancelled = AtomicBoolean(false)

        @Volatile
        private var progress: Double = 0.0

        @Volatile
        private var savedSize: Long = 0L

        @Volatile
        private var totalSize: Long = 0L

        @Volatile
        private var stage: String = "downloading"

        /** Pre-populate progress from an existing .part file so the UI doesn't flash 0%. */
        fun initFromPartFile(marketFileSize: Long) {
            val partFile = File(destFile.parent, destFile.name + ".part")
            if (partFile.exists()) {
                savedSize = partFile.length()
                totalSize = marketFileSize
                progress = if (totalSize > 0) savedSize.toDouble() / totalSize else 0.0
            }
        }

        fun cancel() {
            cancelled.set(true)
            call?.cancel()
        }

        fun toMap(): Map<String, Any?> {
            return mapOf(
                "state" to 1,
                "stateLabel" to "downloading",
                "progress" to progress,
                "savedSize" to savedSize,
                "totalSize" to totalSize,
                "speedInfo" to "",
                "errorMessage" to "",
                "progressStage" to stage,
                "currentFile" to destFile.name,
                "hasUpdate" to false,
            )
        }

        fun toPausedMap(): Map<String, Any?> {
            return mapOf(
                "state" to 4,
                "stateLabel" to "paused",
                "progress" to progress,
                "savedSize" to savedSize,
                "totalSize" to totalSize,
                "speedInfo" to "",
                "errorMessage" to "",
                "progressStage" to stage,
                "currentFile" to destFile.name,
                "hasUpdate" to false,
            )
        }

        private fun downloadFile(fileUrl: String, dest: File): Boolean {
            val tempFile = File(dest.parent, dest.name + ".part")
            val existingSize = if (tempFile.exists()) tempFile.length() else 0L
            val requestBuilder = Request.Builder().url(fileUrl)
            if (existingSize > 0) {
                requestBuilder.header("Range", "bytes=$existingSize-")
            }
            call = OmniInferModelsManager.client.newCall(requestBuilder.build())
            val response = call!!.execute()
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty body")
            val contentLength = body.contentLength()
            totalSize = if (response.code == 206) existingSize + contentLength else contentLength
            savedSize = if (response.code == 206) existingSize else 0L
            val output = RandomAccessFile(tempFile, "rw")
            if (response.code == 206) {
                output.seek(existingSize)
            } else {
                output.setLength(0)
            }
            val buffer = ByteArray(8192)
            val input = body.byteStream()
            var lastEmitTime = 0L
            while (!cancelled.get()) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                savedSize += read
                progress = if (totalSize > 0) savedSize.toDouble() / totalSize else 0.0
                val now = System.currentTimeMillis()
                if (now - lastEmitTime > 500) {
                    lastEmitTime = now
                    OmniInferModelsManager.emitEvent("downloads_changed", mapOf("modelId" to modelId))
                }
            }
            output.close()
            input.close()
            body.closeQuietly()
            if (!cancelled.get()) {
                tempFile.renameTo(dest)
                return true
            }
            return false
        }

        /**
         * Download a file with automatic retry on transient IO errors (e.g. "software caused
         * connection abort" when Android suspends the app). Retries use exponential backoff;
         * the .part file + Range header ensure no data is re-downloaded.
         */
        private suspend fun downloadFileWithRetry(fileUrl: String, dest: File): Boolean {
            var lastException: IOException? = null
            for (attempt in 0..MAX_RETRIES) {
                if (cancelled.get()) return false
                try {
                    return withContext(Dispatchers.IO) { downloadFile(fileUrl, dest) }
                } catch (e: IOException) {
                    if (cancelled.get()) return false
                    lastException = e
                    if (attempt < MAX_RETRIES) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                        Log.w(TAG, "[Download] Retrying $modelId (attempt ${attempt + 1}/$MAX_RETRIES) " +
                            "after ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    }
                }
            }
            throw lastException!!
        }

        fun start() {
            OmniInferModelsManager.scope.launch {
                try {
                    stage = "downloading"
                    Log.d(TAG, "[Download] task started: $modelId, url=$url")
                    if (!downloadFileWithRetry(url, destFile)) {
                        Log.d(TAG, "[Download] task cancelled during main file: $modelId")
                        return@launch
                    }

                    // Download mmproj if needed
                    if (mmprojUrl != null && mmprojDest != null && !cancelled.get()) {
                        if (!mmprojDest.exists()) {
                            stage = "downloading mmproj"
                            progress = 0.0
                            savedSize = 0L
                            totalSize = 0L
                            Log.d(TAG, "[Download] downloading mmproj: $modelId")
                            OmniInferModelsManager.emitEvent("downloads_changed", mapOf("modelId" to modelId))
                            if (!downloadFileWithRetry(mmprojUrl, mmprojDest)) {
                                Log.d(TAG, "[Download] task cancelled during mmproj: $modelId")
                                return@launch
                            }
                        }
                    }

                    if (!cancelled.get()) {
                        Log.d(TAG, "[Download] task completed: $modelId")
                        OmniInferModelsManager.activeDownloads.remove(modelId)
                        OmniInferModelsManager.emitEvent("downloads_changed", mapOf("modelId" to modelId))
                        OmniInferModelsManager.appContext?.let {
                            ModelDownloadForegroundService.stopIfIdle(it)
                            OmniInferBuiltinProviderRefresher.refreshAsync(
                                it,
                                "llama_download_finished:$modelId"
                            )
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "[Download] task error: $modelId, cancelled=${cancelled.get()}, error=${error.message}")
                    if (!cancelled.get()) {
                        OmniInferModelsManager.activeDownloads.remove(modelId)
                        OmniInferModelsManager.emitEvent(
                            "download_error",
                            mapOf(
                                "modelId" to modelId,
                                "error" to (error.message ?: "unknown"),
                            )
                        )
                    }
                    OmniInferModelsManager.appContext?.let {
                        ModelDownloadForegroundService.stopIfIdle(it)
                    }
                }
            }
        }
    }
}


