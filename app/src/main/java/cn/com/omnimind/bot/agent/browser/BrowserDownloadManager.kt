package cn.com.omnimind.bot.agent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class BrowserDownloadManager(
    context: Context,
    workspaceId: String,
    private val store: BrowserHostStore,
    private val onChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloadsDir = File(appContext.filesDir, "browser_downloads/${safeWorkspaceDir(workspaceId)}")

    init {
        downloadsDir.mkdirs()
    }

    fun snapshot(): List<BrowserDownloadTaskRecord> = store.listDownloads()

    fun activeCount(): Int {
        return snapshot().count { it.status == STATUS_QUEUED || it.status == STATUS_DOWNLOADING }
    }

    fun failedCount(): Int = snapshot().count { it.status == STATUS_FAILED }

    fun overallProgress(): Double? {
        val active = snapshot().filter {
            (it.status == STATUS_QUEUED || it.status == STATUS_DOWNLOADING) && it.totalBytes > 0L
        }
        if (active.isEmpty()) {
            return null
        }
        val total = active.sumOf { it.totalBytes }
        if (total <= 0L) {
            return null
        }
        val downloaded = active.sumOf { it.downloadedBytes.coerceAtMost(it.totalBytes) }
        return downloaded.toDouble() / total.toDouble()
    }

    fun latestCompletedFileName(): String? {
        return snapshot()
            .filter { it.status == STATUS_COMPLETED }
            .maxByOrNull { it.updatedAt }
            ?.fileName
    }

    fun enqueueHttpDownload(
        url: String,
        fileName: String,
        mimeType: String? = null,
        headers: Map<String, String> = emptyMap()
    ): BrowserDownloadTaskRecord {
        val now = System.currentTimeMillis()
        val targetFile = buildDownloadFile(fileName)
        val record = BrowserDownloadTaskRecord(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = targetFile.name,
            mimeType = mimeType,
            destinationPath = targetFile.absolutePath,
            status = STATUS_QUEUED,
            createdAt = now,
            updatedAt = now,
            supportsResume = true
        )
        store.upsertDownload(record)
        onChanged()
        launchOrResume(record.id, headers)
        return record
    }

    fun saveInlineDownload(
        sourceUrl: String,
        fileName: String,
        mimeType: String?,
        bytes: ByteArray
    ): BrowserDownloadTaskRecord {
        val now = System.currentTimeMillis()
        val targetFile = buildDownloadFile(fileName)
        targetFile.writeBytes(bytes)
        val record = BrowserDownloadTaskRecord(
            id = UUID.randomUUID().toString(),
            url = sourceUrl,
            fileName = targetFile.name,
            mimeType = mimeType,
            destinationPath = targetFile.absolutePath,
            status = STATUS_COMPLETED,
            createdAt = now,
            updatedAt = now,
            downloadedBytes = bytes.size.toLong(),
            totalBytes = bytes.size.toLong(),
            supportsResume = false
        )
        store.upsertDownload(record)
        onChanged()
        return record
    }

    fun pause(taskId: String): Boolean {
        val record = snapshot().firstOrNull { it.id == taskId } ?: return false
        if (record.status != STATUS_QUEUED && record.status != STATUS_DOWNLOADING) {
            return false
        }
        activeJobs.remove(taskId)?.cancel()
        store.upsertDownload(record.copy(status = STATUS_PAUSED, updatedAt = System.currentTimeMillis()))
        onChanged()
        return true
    }

    fun cancel(taskId: String): Boolean {
        val record = snapshot().firstOrNull { it.id == taskId } ?: return false
        activeJobs.remove(taskId)?.cancel()
        store.upsertDownload(record.copy(status = STATUS_CANCELED, updatedAt = System.currentTimeMillis()))
        onChanged()
        return true
    }

    fun resume(taskId: String): Boolean {
        val record = snapshot().firstOrNull { it.id == taskId } ?: return false
        if (!record.supportsResume) {
            return retry(taskId)
        }
        if (record.status != STATUS_PAUSED && record.status != STATUS_CANCELED && record.status != STATUS_FAILED) {
            return false
        }
        launchOrResume(taskId)
        return true
    }

    fun retry(taskId: String): Boolean {
        val record = snapshot().firstOrNull { it.id == taskId } ?: return false
        File(record.destinationPath).takeIf { it.exists() }?.delete()
        store.upsertDownload(
            record.copy(
                status = STATUS_QUEUED,
                updatedAt = System.currentTimeMillis(),
                downloadedBytes = 0L,
                totalBytes = 0L,
                errorMessage = null
            )
        )
        onChanged()
        launchOrResume(taskId)
        return true
    }

    fun delete(
        taskId: String,
        deleteFile: Boolean
    ): Boolean {
        val record = snapshot().firstOrNull { it.id == taskId } ?: return false
        activeJobs.remove(taskId)?.cancel()
        if (deleteFile) {
            runCatching { File(record.destinationPath).delete() }
        }
        store.removeDownload(taskId)
        onChanged()
        return true
    }

    fun shutdown() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        scope.cancel()
    }

    private fun launchOrResume(
        taskId: String,
        headers: Map<String, String> = emptyMap()
    ) {
        activeJobs[taskId]?.cancel()
        activeJobs[taskId] = scope.launch {
            val original = snapshot().firstOrNull { it.id == taskId } ?: return@launch
            val targetFile = File(original.destinationPath)
            val existingSize = if (targetFile.exists()) targetFile.length() else 0L
            update(
                original.copy(
                    status = STATUS_DOWNLOADING,
                    updatedAt = System.currentTimeMillis(),
                    downloadedBytes = existingSize,
                    errorMessage = null
                )
            )
            try {
                val requestBuilder = Request.Builder().url(original.url)
                headers.forEach { (name, value) ->
                    requestBuilder.header(name, value)
                }
                if (existingSize > 0L) {
                    requestBuilder.header("Range", "bytes=$existingSize-")
                }
                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful && response.code !in 200..299 && response.code != 206) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val responseBody = response.body ?: throw IllegalStateException("empty_response_body")
                val contentLength = responseBody.contentLength()
                val append = existingSize > 0L && response.code == 206
                if (!append && targetFile.exists()) {
                    targetFile.delete()
                }
                targetFile.parentFile?.mkdirs()
                val sink = FileOutputStream(targetFile, append).buffered()
                var downloaded = if (append) existingSize else 0L
                val expectedTotal = if (contentLength > 0L) {
                    if (append) existingSize + contentLength else contentLength
                } else {
                    0L
                }
                sink.use { output ->
                    responseBody.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            downloaded += read.toLong()
                            update(
                                original.copy(
                                    status = STATUS_DOWNLOADING,
                                    updatedAt = System.currentTimeMillis(),
                                    downloadedBytes = downloaded,
                                    totalBytes = expectedTotal,
                                    mimeType = response.header("Content-Type") ?: original.mimeType,
                                    errorMessage = null
                                )
                            )
                        }
                    }
                }
                update(
                    original.copy(
                        status = STATUS_COMPLETED,
                        updatedAt = System.currentTimeMillis(),
                        downloadedBytes = downloaded,
                        totalBytes = if (expectedTotal > 0L) expectedTotal else downloaded,
                        mimeType = response.header("Content-Type") ?: original.mimeType,
                        errorMessage = null
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val latest = snapshot().firstOrNull { it.id == taskId } ?: original
                update(
                    latest.copy(
                        status = STATUS_FAILED,
                        updatedAt = System.currentTimeMillis(),
                        errorMessage = error.message ?: "download_failed"
                    )
                )
            } finally {
                activeJobs.remove(taskId)
            }
        }
    }

    private fun update(record: BrowserDownloadTaskRecord) {
        store.upsertDownload(record)
        onChanged()
    }

    private fun buildDownloadFile(fileName: String): File {
        val sanitized = fileName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "download_${UUID.randomUUID().toString().take(8)}.bin" }
        var candidate = File(downloadsDir, sanitized)
        if (!candidate.exists()) {
            return candidate
        }
        val base = sanitized.substringBeforeLast('.', sanitized)
        val extension = sanitized.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val nextName = if (extension.isBlank()) {
                "${base}_$index"
            } else {
                "${base}_$index.$extension"
            }
            candidate = File(downloadsDir, nextName)
            index += 1
        }
        return candidate
    }

    private fun safeWorkspaceDir(workspaceId: String): String {
        return workspaceId.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]"), "_")
            .ifBlank { "default" }
    }

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELED = "canceled"
    }
}
