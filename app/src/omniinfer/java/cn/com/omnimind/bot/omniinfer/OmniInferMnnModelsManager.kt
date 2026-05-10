package cn.com.omnimind.bot.omniinfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.tencent.mmkv.MMKV
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object OmniInferMnnModelsManager {
    private const val TAG = "OmniInferMnnModelsManager"
    private const val BACKEND_NAME = OmniInferLocalRuntime.BACKEND_OMNIINFER_MNN
    private const val MMKV_ID = "omniinfer_config"
    private const val KEY_ACTIVE_MODEL_ID = "omniinfer_mnn_active_model_id"
    private const val KEY_AUTO_START = "omniinfer_mnn_auto_start_on_app_open"
    private const val KEY_DOWNLOAD_PROVIDER = "omniinfer_mnn_download_provider"
    private const val MANUAL_MODELS_ROOT = "/data/local/tmp/mnn_models"

    internal data class InstalledModelRecord(
        val id: String,
        val name: String,
        val path: String,
        val configPath: String,
        val downloadModelId: String?,
        val source: String,
        val description: String,
        val vendor: String,
        val tags: List<String>,
        val extraTags: List<String>,
        val fileSize: Long,
        val downloadedAt: Long,
        val readOnly: Boolean,
        val downloadInfo: MnnDownloadInfo?,
    )

    private var appContext: Context? = null
    private var eventDispatcher: ((Map<String, Any?>) -> Unit)? = null
    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, MnnRepoDownloadTask>()

    fun setContext(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        ensureMnnModelSymlink(applicationContext)
        OmniInferLocalRuntime.setContext(applicationContext)
        OmniInferMnnMarketRepository.setContext(applicationContext)
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
                if (normalizedQuery.isEmpty()) {
                    true
                } else {
                    listOf(
                        record.id,
                        record.name,
                        record.vendor,
                        record.description,
                        record.path,
                    ).plus(record.tags)
                        .plus(record.extraTags)
                        .any { value -> value.lowercase(Locale.getDefault()).contains(normalizedQuery) }
                }
            }
            .sortedWith(
                compareByDescending<InstalledModelRecord> { it.downloadedAt }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
            )
            .map(::installedRecordToMap)
    }

    suspend fun refreshInstalledModels(): List<Map<String, Any?>> {
        return listInstalledModels()
    }

    suspend fun listMarketModels(
        query: String? = null,
        category: String? = null,
        refresh: Boolean = false,
    ): Map<String, Any?> {
        ensureContext()
        val selectedSource = getDownloadProvider()
        val normalizedQuery = query?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val models = OmniInferMnnMarketRepository.listModels(selectedSource, refresh)
            .filter { resolved ->
                if (normalizedQuery.isEmpty()) {
                    true
                } else {
                    buildList {
                        add(resolved.modelId)
                        add(resolved.downloadId)
                        add(resolved.item.modelName)
                        add(resolved.item.vendor.orEmpty())
                        addAll(resolved.item.tags)
                        addAll(resolved.item.extraTags)
                    }.any { value -> value.lowercase(Locale.getDefault()).contains(normalizedQuery) }
                }
            }
            .map(::marketModelToMap)
        return mapOf(
            "source" to selectedSource,
            "availableSources" to MnnModelSources.sourceList,
            "category" to "llm",
            "models" to models,
        )
    }

    suspend fun refreshMarketModels(
        query: String? = null,
        category: String? = null,
    ): Map<String, Any?> {
        return listMarketModels(query = query, category = category, refresh = true)
    }

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
            "downloadProvider" to getDownloadProvider(),
            "availableSources" to MnnModelSources.sourceList,
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
        arguments["downloadProvider"]?.let {
            mmkv.encode(KEY_DOWNLOAD_PROVIDER, normalizeSource(it.toString()))
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
        OmniLog.i(
            TAG,
            "[startApiService] modelId=${resolved.id}, name=${resolved.name}, " +
                "configPath=${resolved.configPath}, path=${resolved.path}, " +
                "source=${resolved.source}, backend=$BACKEND_NAME, " +
                "fileSize=${resolved.fileSize}, vendor=${resolved.vendor}"
        )
        mmkv.encode(KEY_ACTIVE_MODEL_ID, resolved.id)
        OmniInferLocalRuntime.loadModel(
            modelId = resolved.id,
            modelPath = resolved.configPath,
            backend = BACKEND_NAME,
        )
        emitConfigChanged()
        return getConfig()
    }

    fun ensureModelReady(modelId: String): Boolean {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isEmpty()) {
            return false
        }
        val resolved = findInstalledRecord(normalizedModelId) ?: return false
        if (isModelCurrentlyLoaded(resolved.id)) {
            return true
        }
        mmkv.encode(KEY_ACTIVE_MODEL_ID, resolved.id)
        return OmniInferLocalRuntime.loadModel(
            modelId = resolved.id,
            modelPath = resolved.configPath,
            backend = BACKEND_NAME,
        )
    }

    fun stopApiService(): Map<String, Any?> {
        OmniInferLocalRuntime.stop()
        emitConfigChanged()
        return getConfig()
    }

    fun startDownload(modelId: String) {
        val context = ensureContext()
        val resolved = resolveMarketModel(modelId) ?: run {
            emitEvent("download_error", mapOf("modelId" to modelId, "error" to "No download source"))
            return
        }
        if (activeDownloads.containsKey(resolved.downloadId)) return

        val repoDirName = resolved.repoPath.substringAfterLast('/')
        val destDir = File(AgentWorkspaceManager.modelsMnnDirectory(context), repoDirName)
        destDir.mkdirs()

        val task = MnnRepoDownloadTask(
            downloadId = resolved.downloadId,
            source = resolved.source,
            repoPath = resolved.repoPath,
            destDir = destDir,
        )
        activeDownloads[resolved.downloadId] = task
        ModelDownloadForegroundService.start(
            context, activeDownloads.size, resolved.item.modelName,
        )

        // Calculate existing progress from partially downloaded files so the UI
        // doesn't briefly show 0% when resuming a paused download.
        val savedSize = destDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val totalSize = if (resolved.item.fileSize > 0L) resolved.item.fileSize else savedSize
        val initialProgress = if (totalSize > 0 && savedSize > 0) savedSize.toDouble() / totalSize else 0.0
        emitDownloadUpdate(resolved.modelId, task.info.copy(
            savedSize = savedSize,
            totalSize = totalSize,
            progress = initialProgress,
        ))

        scope.launch {
            try {
                task.execute { info ->
                    emitDownloadUpdate(resolved.modelId, info)
                }
                activeDownloads.remove(resolved.downloadId)
                emitDownloadUpdate(resolved.modelId, task.info)
                emitEvent("downloads_changed", emptyMap())
                ModelDownloadForegroundService.stopIfIdle(context)
                OmniInferBuiltinProviderRefresher.refreshAsync(
                    context, "mnn_download_finished:${resolved.modelId}"
                )
            } catch (_: Exception) {
                activeDownloads.remove(resolved.downloadId)
                emitDownloadUpdate(resolved.modelId, task.info)
                emitEvent("downloads_changed", emptyMap())
                ModelDownloadForegroundService.stopIfIdle(context)
            }
        }
    }

    fun pauseDownload(modelId: String) {
        val resolved = resolveMarketModel(modelId) ?: return
        val task = activeDownloads.remove(resolved.downloadId) ?: return
        task.cancel()
        val pausedInfo = task.info.copy(downloadState = MnnDownloadState.DOWNLOAD_PAUSED)
        emitDownloadUpdate(resolved.modelId, pausedInfo)
        emitEvent("downloads_changed", emptyMap())
        appContext?.let { ModelDownloadForegroundService.stopIfIdle(it) }
    }

    suspend fun deleteModel(modelId: String): List<Map<String, Any?>> {
        val normalizedModelId = normalizeStoredModelId(modelId)
        val target = findInstalledRecord(normalizedModelId) ?: return listInstalledModels()
        if (target.readOnly) {
            return listInstalledModels()
        }
        val context = ensureContext()
        if (isModelCurrentlyLoaded(target.id)) {
            OmniInferLocalRuntime.stop()
        }
        // Cancel active download if any
        val downloadId = target.downloadModelId ?: target.id
        activeDownloads.remove(downloadId)?.cancel()
        // Delete the model directory
        val modelDir = File(target.path)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        // Also clean up old blob+symlink storage (backward compat with old downloader)
        cleanupLegacyStorage(context, downloadId)
        if (getActiveModelId() == target.id) {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, "")
        }
        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        OmniInferBuiltinProviderRefresher.refreshAsync(context, "mnn_delete:${target.id}")
        return listInstalledModels()
    }

    // ---- Symlink migration (legacy .mnnmodels → workspace) ------------------------------------

    private fun ensureMnnModelSymlink(context: Context) {
        val workspaceDir = AgentWorkspaceManager.modelsMnnDirectory(context)
        val legacyDir = File(context.filesDir, ".mnnmodels")
        val legacyPath = legacyDir.toPath()
        val workspacePath = workspaceDir.toPath()

        if (Files.isSymbolicLink(legacyPath)) {
            return
        }
        workspaceDir.mkdirs()
        if (legacyDir.exists() && legacyDir.isDirectory) {
            var allMoved = true
            legacyDir.listFiles()?.forEach { child ->
                val target = File(workspaceDir, child.name)
                if (!target.exists()) {
                    if (!child.renameTo(target)) {
                        allMoved = false
                    }
                }
            }
            if (!allMoved) return
            legacyDir.deleteRecursively()
        }
        runCatching { Files.createSymbolicLink(legacyPath, workspacePath) }
    }

    private fun resolveDisplayPath(file: File): String {
        val context = appContext ?: return file.absolutePath
        val legacyPrefix = File(context.filesDir, ".mnnmodels").absolutePath
        val path = file.absolutePath
        if (!path.startsWith(legacyPrefix)) return path
        return AgentWorkspaceManager.modelsMnnDirectory(context).absolutePath +
            path.removePrefix(legacyPrefix)
    }

    // ---- Installed model detection (filesystem scan) ------------------------------------------

    private fun installedRecords(): List<InstalledModelRecord> {
        val preferredIds = buildSet {
            getActiveModelId().takeIf { it.isNotBlank() }?.let(::add)
            getLoadedModelId()
                .takeIf { it.isNotBlank() }
                ?.let(::add)
        }
        return dedupeInstalledRecords(rawInstalledRecords(), preferredIds)
    }

    private fun rawInstalledRecords(): List<InstalledModelRecord> {
        return buildList {
            addAll(downloadedRecords())
            addAll(manualRecords())
        }
    }

    internal fun dedupeInstalledRecords(
        records: List<InstalledModelRecord>,
        preferredIds: Set<String> = emptySet(),
    ): List<InstalledModelRecord> {
        val deduped = LinkedHashMap<String, InstalledModelRecord>()
        records.forEach { record ->
            val key = installedRecordDirectoryKey(record)
            val existing = deduped[key]
            if (existing == null || shouldReplaceInstalledRecord(existing, record, preferredIds)) {
                deduped[key] = record
            }
        }
        return deduped.values.toList()
    }

    private fun installedRecordDirectoryKey(record: InstalledModelRecord): String {
        fun normalizePath(path: String): String {
            return path.trim()
                .replace('\\', '/')
                .trimEnd('/')
                .lowercase(Locale.getDefault())
        }

        return listOf(record.id, record.path, record.configPath)
            .map(::normalizePath)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun shouldReplaceInstalledRecord(
        existing: InstalledModelRecord,
        candidate: InstalledModelRecord,
        preferredIds: Set<String>,
    ): Boolean {
        val existingPreferred = existing.id in preferredIds
        val candidatePreferred = candidate.id in preferredIds
        if (existingPreferred != candidatePreferred) {
            return candidatePreferred
        }
        if (existing.readOnly != candidate.readOnly) {
            return !candidate.readOnly
        }
        return false
    }

    /**
     * Scan the MNN models directory for downloaded models (directories containing config.json).
     * Matches found directories against market models by directory name.
     */
    private fun downloadedRecords(): List<InstalledModelRecord> {
        val context = ensureContext()
        val mnnDir = AgentWorkspaceManager.modelsMnnDirectory(context)
        if (!mnnDir.exists()) return emptyList()

        val allModels = OmniInferMnnMarketRepository.allModels()

        // Build lookup: directory name → resolved market model
        val dirNameToModel = mutableMapOf<String, OmniInferMnnMarketRepository.ResolvedMarketModel>()
        for (model in allModels) {
            val dirName = model.repoPath.substringAfterLast('/')
            dirNameToModel.putIfAbsent(dirName, model)
        }

        return scanModelDirectories(mnnDir).mapNotNull { modelDir ->
            val configFile = File(modelDir, "config.json")
            if (!configFile.exists()) return@mapNotNull null

            val dirName = modelDir.name
            val resolved = dirNameToModel[dirName]
            val activeTask = resolved?.let { activeDownloads[it.downloadId] }
            val downloadInfo = activeTask?.info ?: run {
                val hasPartFiles = modelDir.walkTopDown()
                    .any { it.isFile && it.name.endsWith(".part") }
                if (hasPartFiles) {
                    val savedSize = modelDir.walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    val total = resolved?.item?.fileSize?.takeIf { it > 0L } ?: savedSize
                    MnnDownloadInfo(
                        downloadState = MnnDownloadState.DOWNLOAD_PAUSED,
                        progress = if (total > 0) savedSize.toDouble() / total else 0.0,
                        totalSize = total,
                        savedSize = savedSize,
                    )
                } else {
                    null
                }
            }

            InstalledModelRecord(
                id = resolved?.modelId ?: dirName,
                name = resolved?.item?.modelName?.ifBlank { dirName } ?: dirName,
                path = resolveDisplayPath(modelDir),
                configPath = resolveDisplayPath(configFile),
                downloadModelId = resolved?.downloadId,
                source = resolved?.source ?: "unknown",
                description = resolved?.let { buildDescription(it.item) } ?: "",
                vendor = resolved?.item?.vendor.orEmpty(),
                tags = (resolved?.item?.tags?.plus(listOf("MNN"))?.distinct() ?: listOf("MNN")),
                extraTags = resolved?.item?.extraTags ?: emptyList(),
                fileSize = resolved?.item?.fileSize ?: 0L,
                downloadedAt = modelDir.lastModified(),
                readOnly = false,
                downloadInfo = downloadInfo,
            )
        }
    }

    /**
     * Scan for model directories, handling both flat layout and source-subfolder layout
     * left by the old downloader (hf/, modelscope/, modelers/ subdirectories).
     */
    private fun scanModelDirectories(rootDir: File): List<File> {
        val result = mutableListOf<File>()
        rootDir.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (File(child, "config.json").exists()) {
                result.add(child)
            } else {
                // Old layout: source subfolders containing model directories
                child.listFiles()?.forEach { grandchild ->
                    if (grandchild.isDirectory && File(grandchild, "config.json").exists()) {
                        result.add(grandchild)
                    }
                }
            }
        }
        return result
    }

    private fun manualRecords(): List<InstalledModelRecord> {
        val modelsRoot = File(MANUAL_MODELS_ROOT)
        if (!modelsRoot.exists() || !modelsRoot.isDirectory) {
            return emptyList()
        }
        return modelsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { modelDir ->
                val configFile = File(modelDir, "config.json")
                if (!configFile.exists()) {
                    null
                } else {
                    InstalledModelRecord(
                        id = "local/${modelDir.absolutePath}",
                        name = modelDir.name,
                        path = modelDir.absolutePath,
                        configPath = configFile.absolutePath,
                        downloadModelId = null,
                        source = "local",
                        description = "手动放置目录模型",
                        vendor = "",
                        tags = listOf("MNN", "Manual"),
                        extraTags = emptyList(),
                        fileSize = 0L,
                        downloadedAt = modelDir.lastModified(),
                        readOnly = true,
                        downloadInfo = null,
                    )
                }
            }
            ?.sortedByDescending { it.downloadedAt }
            .orEmpty()
    }

    private fun findInstalledRecord(modelId: String): InstalledModelRecord? {
        val normalizedModelId = normalizeStoredModelId(modelId)
        if (normalizedModelId.isEmpty()) {
            return null
        }
        return installedRecords().firstOrNull { it.id == normalizedModelId }
    }

    // ---- Map conversion for Flutter -----------------------------------------------------------

    private fun installedRecordToMap(record: InstalledModelRecord): Map<String, Any?> {
        val activeModelId = getActiveModelId()
        val loadedModelId = getLoadedModelId()
        val fileSize = record.fileSize.takeIf { it > 0L } ?: record.downloadInfo?.totalSize ?: 0L
        return mapOf(
            "id" to record.id,
            "name" to record.name,
            "category" to "llm",
            "backend" to BACKEND_NAME,
            "source" to record.source,
            "description" to record.description,
            "path" to record.path,
            "vendor" to record.vendor,
            "tags" to record.tags,
            "extraTags" to record.extraTags,
            "active" to (record.id == activeModelId || record.id == loadedModelId),
            "isLocal" to true,
            "isPinned" to false,
            "hasUpdate" to (record.downloadInfo?.hasUpdate == true),
            "fileSize" to fileSize,
            "sizeB" to fileSize.toDouble(),
            "formattedSize" to formatSize(fileSize),
            "lastUsedAt" to 0,
            "downloadedAt" to record.downloadedAt,
            "download" to record.downloadInfo?.toMap(),
            "readOnly" to record.readOnly,
        )
    }

    private fun marketModelToMap(model: OmniInferMnnMarketRepository.ResolvedMarketModel): Map<String, Any?> {
        val context = ensureContext()
        val mnnDir = AgentWorkspaceManager.modelsMnnDirectory(context)
        val repoDirName = model.repoPath.substringAfterLast('/')
        val downloadedFile = scanModelDirectories(mnnDir)
            .firstOrNull { it.name == repoDirName }
        val activeTask = activeDownloads[model.downloadId]
        val downloadInfo: MnnDownloadInfo? = activeTask?.info
            ?: if (downloadedFile != null) {
                val hasPartFiles = downloadedFile.walkTopDown()
                    .any { it.isFile && it.name.endsWith(".part") }
                if (hasPartFiles) {
                    // Incomplete download: .part files still present
                    val savedSize = downloadedFile.walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    val total = if (model.item.fileSize > 0L) model.item.fileSize else savedSize
                    MnnDownloadInfo(
                        downloadState = MnnDownloadState.DOWNLOAD_PAUSED,
                        progress = if (total > 0) savedSize.toDouble() / total else 0.0,
                        totalSize = total,
                        savedSize = savedSize,
                    )
                } else {
                    MnnDownloadInfo(
                        downloadState = MnnDownloadState.DOWNLOAD_SUCCESS,
                        progress = 1.0,
                        totalSize = model.item.fileSize,
                        savedSize = model.item.fileSize,
                    )
                }
            } else {
                null
            }
        val fileSize = if (model.item.fileSize > 0L) model.item.fileSize else downloadInfo?.totalSize ?: 0L
        return mapOf(
            "id" to model.modelId,
            "name" to model.item.modelName,
            "category" to "llm",
            "source" to model.source,
            "description" to buildDescription(model.item),
            "path" to (downloadedFile?.let(::resolveDisplayPath)).orEmpty(),
            "vendor" to model.item.vendor.orEmpty(),
            "tags" to (model.item.tags + listOf("MNN")).distinct(),
            "extraTags" to model.item.extraTags,
            "active" to (model.modelId == getActiveModelId()),
            "isLocal" to (downloadedFile != null),
            "isPinned" to false,
            "hasUpdate" to (downloadInfo?.hasUpdate == true),
            "fileSize" to fileSize,
            "sizeB" to fileSize.toDouble(),
            "formattedSize" to formatSize(fileSize),
            "lastUsedAt" to 0,
            "downloadedAt" to (downloadedFile?.lastModified() ?: 0L),
            "download" to downloadInfo?.toMap(),
            "readOnly" to false,
        )
    }

    private fun buildDescription(item: OmniInferMnnMarketRepository.MarketItem): String {
        return buildList {
            item.vendor?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (item.tags.isNotEmpty()) {
                add(item.tags.joinToString(separator = " / "))
            }
        }.joinToString(separator = " | ")
    }

    // ---- Internal helpers ----------------------------------------------------------------------

    private fun ensureContext(): Context {
        return appContext ?: error("OmniInfer MNN context is not initialized")
    }

    private fun getActiveModelId(): String {
        val stored = mmkv.decodeString(KEY_ACTIVE_MODEL_ID, "").orEmpty()
        val normalized = normalizeStoredModelId(stored)
        if (normalized != stored) {
            mmkv.encode(KEY_ACTIVE_MODEL_ID, normalized)
        }
        return normalized
    }

    private fun shouldAutoStartOnAppOpen(): Boolean {
        return mmkv.decodeBool(KEY_AUTO_START, false)
    }

    private fun getDownloadProvider(): String {
        return normalizeSource(mmkv.decodeString(KEY_DOWNLOAD_PROVIDER, MnnModelSources.sourceModelScope))
    }

    private fun normalizeSource(rawSource: String?): String {
        val source = rawSource?.trim().orEmpty()
        return if (MnnModelSources.sourceList.contains(source)) {
            source
        } else {
            MnnModelSources.sourceModelScope
        }
    }

    private fun getLoadedModelId(): String {
        return normalizeStoredModelId(OmniInferLocalRuntime.getLoadedModelId())
    }

    private fun normalizeStoredModelId(modelId: String?): String {
        return OmniInferMnnMarketRepository.normalizeModelId(modelId)
    }

    private fun resolveMarketModel(modelId: String): OmniInferMnnMarketRepository.ResolvedMarketModel? {
        return OmniInferMnnMarketRepository.findModel(
            modelId = modelId,
            preferredSource = getDownloadProvider(),
        )
    }

    private fun isModelCurrentlyLoaded(modelId: String): Boolean {
        return OmniInferLocalRuntime.isReady() &&
            OmniInferLocalRuntime.getLoadedBackend() == BACKEND_NAME &&
            getLoadedModelId() == modelId.trim()
    }

    /**
     * Clean up legacy blob+symlink storage left by the old model_downloader submodule.
     */
    private fun cleanupLegacyStorage(context: Context, downloadId: String) {
        val mnnDir = AgentWorkspaceManager.modelsMnnDirectory(context)
        // Old downloader stored blobs under source-specific subfolders: hf/, modelscope/, modelers/
        val sourceSubDirs = listOf("hf", "modelscope", "modelers")
        for (sub in sourceSubDirs) {
            val sourceDir = File(mnnDir, sub)
            if (!sourceDir.isDirectory) continue
            // Look for repo folders matching pattern models--{...}
            sourceDir.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name.startsWith("models--")) {
                    child.deleteRecursively()
                }
            }
        }
    }

    // ---- Import from device -------------------------------------------------------------------

    suspend fun importModel(filePath: String): Map<String, Any?> {
        val sourceFile = File(filePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return mapOf("success" to false, "error" to "File does not exist: $filePath")
        }

        if (!sourceFile.name.equals("config.json", ignoreCase = true)) {
            return mapOf("success" to false, "error" to "Please select config.json from the MNN model directory")
        }

        val sourceDir = sourceFile.parentFile
            ?: return mapOf("success" to false, "error" to "Cannot resolve parent directory")

        val context = ensureContext()
        val mnnDir = AgentWorkspaceManager.modelsMnnDirectory(context)
        val modelId = sourceDir.name
        val destDir = File(mnnDir, modelId)

        if (destDir.exists()) {
            return mapOf("success" to false, "error" to "Model directory already exists: $modelId")
        }

        val totalSize = sourceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val usableSpace = mnnDir.usableSpace
        if (totalSize > usableSpace) {
            return mapOf("success" to false, "error" to "Insufficient storage space")
        }

        destDir.mkdirs()

        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                var copiedSize = 0L
                var lastEmitTime = 0L
                val buffer = ByteArray(8192)

                sourceDir.walkTopDown().filter { it.isFile }.forEach { srcFile ->
                    val relativePath = srcFile.relativeTo(sourceDir).path
                    val destFile = File(destDir, relativePath)
                    destFile.parentFile?.mkdirs()

                    srcFile.inputStream().buffered().use { input ->
                        destFile.outputStream().buffered().use { output ->
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                output.write(buffer, 0, bytesRead)
                                copiedSize += bytesRead
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 300) {
                                    lastEmitTime = now
                                    emitEvent("import_progress", mapOf(
                                        "modelId" to modelId,
                                        "progress" to if (totalSize > 0) copiedSize.toDouble() / totalSize else 0.0,
                                        "copiedSize" to copiedSize,
                                        "totalSize" to totalSize,
                                        "currentFile" to relativePath,
                                    ))
                                }
                            }
                        }
                    }
                }
                emitEvent("import_progress", mapOf(
                    "modelId" to modelId,
                    "progress" to 1.0,
                    "copiedSize" to totalSize,
                    "totalSize" to totalSize,
                ))
            }
        } catch (e: java.io.IOException) {
            OmniLog.e(TAG, "Import failed for $modelId", e)
            if (destDir.exists()) destDir.deleteRecursively()
            return mapOf("success" to false, "error" to "Copy failed: ${e.message}")
        }

        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        OmniInferBuiltinProviderRefresher.refreshAsync(context, "mnn_import:$modelId")
        return mapOf("success" to true, "modelId" to modelId)
    }

    suspend fun importModelFromUri(context: Context, treeUri: Uri): Map<String, Any?> {
        val docFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: return mapOf("success" to false, "error" to "Cannot open selected directory")

        val modelId = docFile.name
            ?: return mapOf("success" to false, "error" to "Cannot determine directory name")

        // Verify it's an MNN model directory (has config.json)
        val children = docFile.listFiles()
        val hasConfig = children.any { child ->
            child.name.equals("config.json", ignoreCase = true) && child.isFile
        }
        if (!hasConfig) {
            return mapOf("success" to false, "error" to "Not an MNN model directory: missing config.json")
        }

        val mnnDir = AgentWorkspaceManager.modelsMnnDirectory(context)
        val destDir = File(mnnDir, modelId)

        if (destDir.exists()) {
            return mapOf("success" to false, "error" to "Model directory already exists: $modelId")
        }

        // Calculate total size
        val allFiles = collectDocumentFiles(docFile)
        var totalSize = 0L
        for (f in allFiles) { totalSize += f.length() }
        val usableSpace = mnnDir.usableSpace
        if (totalSize > 0L && totalSize > usableSpace) {
            return mapOf("success" to false, "error" to "Insufficient storage space")
        }

        destDir.mkdirs()

        try {
            withContext(Dispatchers.IO) {
                var copiedSize = 0L
                var lastEmitTime = 0L
                val buffer = ByteArray(8192)

                for (srcDoc in allFiles) {
                    val relativePath = getDocRelativePath(docFile, srcDoc)
                    val destFile = File(destDir, relativePath)
                    destFile.parentFile?.mkdirs()

                    val inputStream = context.contentResolver.openInputStream(srcDoc.uri)
                        ?: continue
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
                                    emitEvent("import_progress", mapOf(
                                        "modelId" to modelId,
                                        "progress" to if (totalSize > 0) copiedSize.toDouble() / totalSize else 0.0,
                                        "copiedSize" to copiedSize,
                                        "totalSize" to totalSize,
                                        "currentFile" to relativePath,
                                    ))
                                }
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
            OmniLog.e(TAG, "Import from URI failed for $modelId", e)
            if (destDir.exists()) destDir.deleteRecursively()
            return mapOf("success" to false, "error" to "Copy failed: ${e.message}")
        }

        emitConfigChanged()
        emitEvent("downloads_changed", emptyMap())
        OmniInferBuiltinProviderRefresher.refreshAsync(context, "mnn_import:$modelId")
        return mapOf("success" to true, "modelId" to modelId)
    }

    private fun collectDocumentFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        for (child in dir.listFiles()) {
            if (child.isFile) {
                result.add(child)
            } else if (child.isDirectory) {
                result.addAll(collectDocumentFiles(child))
            }
        }
        return result
    }

    private fun getDocRelativePath(root: DocumentFile, file: DocumentFile): String {
        val rootPath = root.uri.path ?: ""
        val filePath = file.uri.path ?: ""
        if (filePath.startsWith(rootPath)) {
            val relative = filePath.removePrefix(rootPath).trimStart('/')
            if (relative.isNotEmpty()) return relative
        }
        return file.name ?: "unknown"
    }

    // ---- Event emission -----------------------------------------------------------------------

    private fun emitConfigChanged() {
        emitEvent("config_changed", mapOf("config" to getConfig()))
    }

    private fun emitDownloadUpdate(
        modelId: String,
        downloadInfo: MnnDownloadInfo?,
    ) {
        val publicModelId = normalizeStoredModelId(modelId)
        emitEvent(
            "download_update",
            mapOf(
                "modelId" to publicModelId,
                "download" to downloadInfo?.toMap(),
            )
        )
    }

    private fun emitEvent(type: String, payload: Map<String, Any?>) {
        eventDispatcher?.invoke(
            buildMap {
                put("type", type)
                putAll(payload)
            }
        )
    }

    private fun MnnDownloadInfo.toMap(): Map<String, Any?> {
        return mapOf(
            "state" to downloadState,
            "stateLabel" to when (downloadState) {
                MnnDownloadState.NOT_START -> "not_started"
                MnnDownloadState.PREPARING -> "preparing"
                MnnDownloadState.DOWNLOADING -> "downloading"
                MnnDownloadState.DOWNLOAD_SUCCESS -> "completed"
                MnnDownloadState.DOWNLOAD_FAILED -> "failed"
                MnnDownloadState.DOWNLOAD_PAUSED -> "paused"
                MnnDownloadState.DOWNLOAD_CANCELLED -> "cancelled"
                else -> "unknown"
            },
            "progress" to progress,
            "savedSize" to savedSize,
            "totalSize" to totalSize,
            "speedInfo" to speedInfo,
            "errorMessage" to errorMessage.orEmpty(),
            "progressStage" to progressStage,
            "currentFile" to currentFile.orEmpty(),
            "downloadedTime" to downloadedTime,
            "hasUpdate" to hasUpdate,
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
}
