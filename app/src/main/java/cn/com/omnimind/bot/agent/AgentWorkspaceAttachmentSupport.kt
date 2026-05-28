package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal object AgentWorkspaceAttachmentSupport {
    private const val TAG = "AgentWorkspaceAttachment"

    fun prepareAttachmentsForRuntime(
        context: android.content.Context,
        taskId: String,
        rawAttachments: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        if (rawAttachments.isEmpty()) {
            return emptyList()
        }
        return rawAttachments.map { attachment ->
            prepareSingleAttachment(context, taskId, attachment)
        }
    }

    private fun prepareSingleAttachment(
        context: android.content.Context,
        taskId: String,
        rawAttachment: Map<String, Any?>
    ): Map<String, Any?> {
        val attachment = LinkedHashMap(rawAttachment)
        val isImage = AgentImageAttachmentSupport.isImageAttachment(attachment)
        attachment["isImage"] = isImage
        if (isImage) {
            return attachment
        }

        attachment["sendToModel"] = false
        val promptPath = attachment["promptPath"]?.toString()?.trim().orEmpty()
        val workspacePath = attachment["workspacePath"]?.toString()?.trim().orEmpty()
        if (promptPath.isNotEmpty() || workspacePath.isNotEmpty()) {
            if (promptPath.isEmpty() && workspacePath.isNotEmpty()) {
                attachment["promptPath"] = workspacePath
            }
            return attachment
        }

        val localPath = attachment["path"]?.toString()?.trim().orEmpty()
        if (localPath.isEmpty() ||
            localPath.startsWith("http://", ignoreCase = true) ||
            localPath.startsWith("https://", ignoreCase = true) ||
            localPath.startsWith("data:", ignoreCase = true)
        ) {
            return attachment
        }

        val source = File(localPath)
        if (!source.exists() || !source.isFile) {
            return attachment
        }

        return copyIntoWorkspace(
            context = context,
            taskId = taskId,
            source = source,
            attachment = attachment
        ) ?: attachment
    }

    private fun copyIntoWorkspace(
        context: android.content.Context,
        taskId: String,
        source: File,
        attachment: LinkedHashMap<String, Any?>
    ): Map<String, Any?>? {
        val workspaceManager = AgentWorkspaceManager(context)
        workspaceManager.ensureRuntimeDirectories()
        val batchName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(
            workspaceManager.sharedDirectory(),
            "agent-attachments/${sanitizeSegment(taskId)}/$batchName"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            OmniLog.w(TAG, "Failed to create workspace attachment dir: ${dir.absolutePath}")
            return null
        }

        val preferredName = resolveAttachmentName(attachment, source.name)
        val target = File(dir, "${UUID.randomUUID()}_${sanitizeFileName(preferredName)}")
        return try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val shellPath = workspaceManager.shellPathForAndroid(target) ?: target.absolutePath
            LinkedHashMap(attachment).apply {
                put("path", target.absolutePath)
                put("promptPath", shellPath)
                put("workspacePath", shellPath)
                if (attachment["size"] == null && attachment["sizeBytes"] == null) {
                    put("size", target.length())
                }
                val mimeType = attachment["mimeType"]?.toString()?.trim().orEmpty()
                    .ifEmpty { workspaceManager.guessMimeType(target) }
                if (mimeType.isNotEmpty()) {
                    put("mimeType", mimeType)
                }
                if (attachment["name"]?.toString()?.trim().isNullOrEmpty()) {
                    put("name", preferredName)
                }
                if (attachment["fileName"]?.toString()?.trim().isNullOrEmpty()) {
                    put("fileName", preferredName)
                }
            }
        } catch (error: Exception) {
            OmniLog.w(
                TAG,
                "Failed to copy attachment into workspace: ${source.absolutePath}: ${error.message}"
            )
            runCatching { target.delete() }
            null
        }
    }

    private fun resolveAttachmentName(
        attachment: Map<String, Any?>,
        fallback: String
    ): String {
        val name = attachment["name"]?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) {
            return name
        }
        val fileName = attachment["fileName"]?.toString()?.trim().orEmpty()
        if (fileName.isNotEmpty()) {
            return fileName
        }
        return fallback
    }

    private fun sanitizeSegment(value: String): String {
        val normalized = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return normalized.ifEmpty { "agent" }
    }

    private fun sanitizeFileName(value: String): String {
        val normalized = value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return normalized.ifEmpty { "attachment" }
    }
}
