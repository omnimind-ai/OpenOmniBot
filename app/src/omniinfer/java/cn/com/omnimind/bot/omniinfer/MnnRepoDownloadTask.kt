package cn.com.omnimind.bot.omniinfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads an entire MNN model repository (multiple files) from HuggingFace, ModelScope, or
 * Modelers. Reuses the same OkHttpClient + Range-header resume pattern as the llama.cpp
 * [OmniInferModelsManager.DownloadTask].
 */
class MnnRepoDownloadTask(
    val downloadId: String,
    private val source: String,
    private val repoPath: String,
    private val destDir: File,
) {
    companion object {
        private const val TAG = "MnnRepoDownloadTask"
        private const val PROGRESS_THROTTLE_MS = 500L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 2000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cancelled = AtomicBoolean(false)
    private var activeCall: Call? = null

    @Volatile
    var info = MnnDownloadInfo(downloadState = MnnDownloadState.PREPARING)
        private set

    fun cancel() {
        cancelled.set(true)
        activeCall?.cancel()
    }

    /**
     * Execute the full download: list repo files, then download each one.
     * [onProgress] is invoked (throttled) whenever download progress changes.
     */
    suspend fun execute(onProgress: (MnnDownloadInfo) -> Unit) {
        if (cancelled.get()) return
        try {
            info = info.copy(downloadState = MnnDownloadState.PREPARING, progressStage = "listing")
            onProgress(info)

            val files = withContext(Dispatchers.IO) { listRepoFiles() }
            if (cancelled.get()) return
            if (files.isEmpty()) {
                throw IOException("No files found in repository: $repoPath")
            }

            val totalSize = files.sumOf { it.size }
            var savedSize = 0L

            // Account for already-completed files
            for (entry in files) {
                val destFile = File(destDir, entry.relativePath)
                if (destFile.exists() && !File(destFile.parent, destFile.name + ".part").exists()) {
                    savedSize += destFile.length()
                }
            }

            info = info.copy(
                downloadState = MnnDownloadState.DOWNLOADING,
                totalSize = totalSize,
                savedSize = savedSize,
                progress = if (totalSize > 0) savedSize.toDouble() / totalSize else 0.0,
                progressStage = "downloading",
            )
            onProgress(info)

            for (entry in files) {
                if (cancelled.get()) return
                val destFile = File(destDir, entry.relativePath)

                // Skip already-completed files
                if (destFile.exists() && !File(destFile.parent, destFile.name + ".part").exists()) {
                    continue
                }

                info = info.copy(currentFile = entry.relativePath)
                savedSize = downloadSingleFileWithRetry(entry, destFile, savedSize, totalSize, onProgress)
                if (cancelled.get()) return
            }

            info = info.copy(
                downloadState = MnnDownloadState.DOWNLOAD_SUCCESS,
                progress = 1.0,
                savedSize = totalSize,
                totalSize = totalSize,
                downloadedTime = System.currentTimeMillis(),
                progressStage = "",
                currentFile = "",
            )
            onProgress(info)
        } catch (e: Exception) {
            if (cancelled.get()) return
            Log.e(TAG, "Download failed for $downloadId", e)
            info = info.copy(
                downloadState = MnnDownloadState.DOWNLOAD_FAILED,
                errorMessage = e.message ?: "unknown error",
            )
            onProgress(info)
            throw e
        }
    }

    // ---- File listing per source ---------------------------------------------------------------

    private fun listRepoFiles(): List<RepoFileEntry> {
        return when (source) {
            MnnModelSources.sourceHuggingFace -> listHuggingFaceFiles()
            MnnModelSources.sourceModelScope -> listModelScopeFiles()
            MnnModelSources.sourceModelers -> listModelersFiles()
            else -> throw IOException("Unknown source: $source")
        }
    }

    /** HuggingFace: GET /api/models/{repo}/tree/main?recursive=true */
    private fun listHuggingFaceFiles(): List<RepoFileEntry> {
        val url = "https://huggingface.co/api/models/$repoPath/tree/main?recursive=true"
        val body = executeGet(url)
        val items = json.parseToJsonElement(body).jsonArray
        return items.mapNotNull { element ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (type != "file") return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val lfsSize = obj["lfs"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
            val size = lfsSize ?: obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val downloadUrl = "https://huggingface.co/$repoPath/resolve/main/$path"
            RepoFileEntry(relativePath = path, size = size, downloadUrl = downloadUrl)
        }
    }

    /** ModelScope: GET /api/v1/models/{group}/{path}/repo/files?Recursive=1 */
    private fun listModelScopeFiles(): List<RepoFileEntry> {
        val parts = repoPath.split("/", limit = 2)
        if (parts.size != 2) throw IOException("Invalid ModelScope repo path: $repoPath")
        val url = "https://modelscope.cn/api/v1/models/${parts[0]}/${parts[1]}/repo/files?Recursive=1"
        val body = executeGet(url)
        val root = json.parseToJsonElement(body).jsonObject
        val files = root["Data"]?.jsonObject?.get("Files")?.jsonArray
            ?: throw IOException("Invalid ModelScope API response")
        return files.mapNotNull { element ->
            val obj = element.jsonObject
            val type = obj["Type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (type == "tree") return@mapNotNull null
            val path = obj["Path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = obj["Size"]?.jsonPrimitive?.longOrNull ?: 0L
            val downloadUrl =
                "https://modelscope.cn/api/v1/models/$repoPath/repo?FilePath=$path"
            RepoFileEntry(relativePath = path, size = size, downloadUrl = downloadUrl)
        }
    }

    /** Modelers: GET /api/v1/file/{group}/{path}?path=  (recursive via repeated calls) */
    private fun listModelersFiles(): List<RepoFileEntry> {
        val parts = repoPath.split("/", limit = 2)
        if (parts.size != 2) throw IOException("Invalid Modelers repo path: $repoPath")
        val result = mutableListOf<RepoFileEntry>()
        listModelersFilesRecursive(parts[0], parts[1], "", result)
        return result
    }

    private fun listModelersFilesRecursive(
        owner: String,
        repo: String,
        subPath: String,
        out: MutableList<RepoFileEntry>,
    ) {
        val url = "https://modelers.cn/api/v1/file/$owner/$repo?path=$subPath"
        val body = executeGet(url)
        val root = json.parseToJsonElement(body).jsonObject
        val tree = root["data"]?.jsonObject?.get("tree")?.jsonArray ?: return
        for (element in tree) {
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
            if (type == "dir") {
                listModelersFilesRecursive(owner, repo, path, out)
            } else {
                val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val downloadUrl =
                    "https://modelers.cn/coderepo/web/v1/file/$owner/$repo/main/media/$path"
                out.add(RepoFileEntry(relativePath = path, size = size, downloadUrl = downloadUrl))
            }
        }
    }

    // ---- Single-file download with resume and retry ---------------------------------------------

    /**
     * Download a single file with automatic retry on transient IO errors (e.g. "software caused
     * connection abort" when Android suspends the app). Retries use exponential backoff and
     * resume from the .part file, so no data is re-downloaded.
     */
    private suspend fun downloadSingleFileWithRetry(
        entry: RepoFileEntry,
        destFile: File,
        startSavedSize: Long,
        totalSize: Long,
        onProgress: (MnnDownloadInfo) -> Unit,
    ): Long {
        var lastException: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            if (cancelled.get()) return startSavedSize
            try {
                return withContext(Dispatchers.IO) {
                    downloadSingleFile(entry, destFile, startSavedSize, totalSize, onProgress)
                }
            } catch (e: IOException) {
                if (cancelled.get()) return startSavedSize
                lastException = e
                if (attempt < MAX_RETRIES) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                    Log.w(TAG, "Retrying ${entry.relativePath} (attempt ${attempt + 1}/$MAX_RETRIES) " +
                        "after ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                }
            }
        }
        throw lastException!!
    }

    /**
     * Download a single file with Range-header resume support.
     * Returns the updated cumulative [savedSize] across all files.
     */
    private fun downloadSingleFile(
        entry: RepoFileEntry,
        destFile: File,
        startSavedSize: Long,
        totalSize: Long,
        onProgress: (MnnDownloadInfo) -> Unit,
    ): Long {
        destFile.parentFile?.mkdirs()
        val partFile = File(destFile.parent, destFile.name + ".part")
        val existingSize = if (partFile.exists()) partFile.length() else 0L

        val requestBuilder = Request.Builder().url(entry.downloadUrl)
        if (existingSize > 0) {
            requestBuilder.header("Range", "bytes=$existingSize-")
        }

        val call = client.newCall(requestBuilder.build())
        activeCall = call
        val response = call.execute()

        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw IOException("HTTP ${response.code} downloading ${entry.relativePath}")
        }

        val responseBody = response.body ?: run {
            response.close()
            throw IOException("Empty body for ${entry.relativePath}")
        }

        val isResumed = response.code == 206
        var fileSavedSize = if (isResumed) existingSize else 0L
        var cumulativeSaved = startSavedSize + fileSavedSize

        val raf = RandomAccessFile(partFile, "rw")
        if (isResumed) {
            raf.seek(existingSize)
        } else {
            raf.setLength(0)
        }

        val buffer = ByteArray(8192)
        val input = responseBody.byteStream()
        var lastEmitTime = 0L
        var lastSpeedTime = System.currentTimeMillis()
        var lastSpeedBytes = cumulativeSaved

        try {
            while (!cancelled.get()) {
                val read = input.read(buffer)
                if (read == -1) break
                raf.write(buffer, 0, read)
                fileSavedSize += read
                cumulativeSaved += read

                val now = System.currentTimeMillis()
                if (now - lastEmitTime > PROGRESS_THROTTLE_MS) {
                    lastEmitTime = now

                    // Speed calculation
                    val elapsed = now - lastSpeedTime
                    val speedInfo = if (elapsed > 0) {
                        val bytesPerSec = (cumulativeSaved - lastSpeedBytes) * 1000.0 / elapsed
                        lastSpeedTime = now
                        lastSpeedBytes = cumulativeSaved
                        formatSpeed(bytesPerSec)
                    } else {
                        ""
                    }

                    info = info.copy(
                        savedSize = cumulativeSaved,
                        progress = if (totalSize > 0) cumulativeSaved.toDouble() / totalSize else 0.0,
                        speedInfo = speedInfo,
                    )
                    onProgress(info)
                }
            }
        } finally {
            raf.close()
            input.close()
            responseBody.close()
        }

        if (!cancelled.get()) {
            partFile.renameTo(destFile)
        }
        return cumulativeSaved
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private fun executeGet(url: String): String {
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        activeCall = call
        val response = call.execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("HTTP $code fetching $url")
        }
        return response.body?.string() ?: throw IOException("Empty body from $url")
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1_073_741_824 -> String.format("%.1f GB/s", bytesPerSec / 1_073_741_824)
            bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576)
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }
}

data class RepoFileEntry(
    val relativePath: String,
    val size: Long,
    val downloadUrl: String,
)
