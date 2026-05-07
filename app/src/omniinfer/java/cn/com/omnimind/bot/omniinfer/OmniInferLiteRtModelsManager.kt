package cn.com.omnimind.bot.omniinfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.baselib.util.OmniLog
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object OmniInferLiteRtModelsManager {
    private const val TAG = "OmniInferLiteRtModelsManager"
    private const val BACKEND_NAME = OmniInferLocalRuntime.BACKEND_LITERT
    private const val MMKV_ID = "omniinfer_config"
    private const val KEY_ACTIVE_MODEL_ID = "omniinfer_litert_active_model_id"
    private const val KEY_AUTO_START = "omniinfer_litert_auto_start_on_app_open"
    private const val LITERT_EXTENSION = ".litertlm"
    private const val DEFAULT_N_CTX = 8192

    private var appContext: Context? = null
    private var eventDispatcher: ((Map<String, Any?>) -> Unit)? = null
    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    private data class InstalledLiteRtRecord(
        val id: String,
        val name: String,
        val path: String,
        val fileSize: Long,
        val downloadedAt: Long,
    )

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
        return mapOf(
            "source" to "",
            "availableSources" to emptyList<String>(),
            "category" to "llm",
            "models" to emptyList<Map<String, Any?>>(),
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
            "downloadProvider" to "",
            "availableSources" to emptyList<String>(),
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
        OmniLog.i(
            TAG,
            "[startApiService] modelId=${resolved.id}, path=${resolved.path}, " +
                "backend=$BACKEND_NAME, backendType=gpu, nCtx=$DEFAULT_N_CTX"
        )
        mmkv.encode(KEY_ACTIVE_MODEL_ID, resolved.id)
        OmniInferLocalRuntime.loadModel(
            modelId = resolved.id,
            modelPath = resolved.path,
            backend = BACKEND_NAME,
            extraConfig = mapOf("backend_type" to "gpu"),
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
        mmkv.encode(KEY_ACTIVE_MODEL_ID, resolved.id)
        return OmniInferLocalRuntime.loadModel(
            modelId = resolved.id,
            modelPath = resolved.path,
            backend = BACKEND_NAME,
            extraConfig = mapOf("backend_type" to "gpu"),
            nCtx = DEFAULT_N_CTX,
        )
    }

    fun stopApiService(): Map<String, Any?> {
        OmniInferLocalRuntime.stop()
        emitConfigChanged()
        return getConfig()
    }

    suspend fun deleteModel(modelId: String): List<Map<String, Any?>> {
        val normalizedModelId = normalizeStoredModelId(modelId)
        val target = findInstalledRecord(normalizedModelId) ?: return listInstalledModels()
        if (OmniInferLocalRuntime.isModelLoaded(BACKEND_NAME, target.id)) {
            OmniInferLocalRuntime.stop()
        }
        File(target.path).delete()
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
        return mapOf(
            "id" to record.id,
            "name" to record.name,
            "category" to "llm",
            "backend" to BACKEND_NAME,
            "source" to "Manual",
            "description" to "LiteRT-LM GPU",
            "path" to record.path,
            "vendor" to "LiteRT",
            "tags" to listOf("LiteRT", "GPU"),
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
}
