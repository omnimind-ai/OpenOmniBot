package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import dev.langchain4j.agent.tool.ToolExecutionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class FileToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "file_read", "file_write", "file_edit", "file_list", "file_search", "file_stat", "file_move"
    )

    override suspend fun execute(
        toolRequest: ToolExecutionRequest,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolRequest.name()) {
            "file_read" -> executeFileRead(args, env.workspaceDescriptor, callback)
            "file_write" -> executeFileWrite(args, env.workspaceDescriptor, callback)
            "file_edit" -> executeFileEdit(args, env.workspaceDescriptor, callback)
            "file_list" -> executeFileList(args, env.workspaceDescriptor, callback)
            "file_search" -> executeFileSearch(args, env.workspaceDescriptor, callback)
            "file_stat" -> executeFileStat(args, env.workspaceDescriptor, callback)
            "file_move" -> executeFileMove(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolRequest.name(), "Unknown file tool")
        }
    }

    private suspend fun executeFileRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_read"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists()) { "文件不存在：${file.absolutePath}" }
            require(file.isFile) { "目标不是文件：${file.absolutePath}" }
            val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(128, 64_000)
                ?: SharedHelper.DEFAULT_FILE_READ_MAX_CHARS
            val offset = args["offset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
            val lineStart = args["lineStart"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val lineCount = args["lineCount"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val shellPath = workspaceManager.shellPathForAndroid(file) ?: file.absolutePath
            val mimeType = workspaceManager.guessMimeType(file)
            val imageReadResult = if (isImageFile(file, mimeType)) {
                AgentImageAttachmentSupport.buildFileReadImageResult(
                    file = file,
                    shellPath = shellPath,
                    mimeTypeHint = mimeType,
                    uri = artifact.uri,
                    sizeBytes = file.length()
                )
            } else {
                null
            }
            val payload = if (imageReadResult != null) {
                imageReadResult.payload
            } else {
                val content = file.readText()
                val sliced = when {
                    lineStart != null -> {
                        val lines = content.lines()
                        val from = (lineStart - 1).coerceAtMost(lines.size)
                        val until = if (lineCount != null) {
                            (from + lineCount).coerceAtMost(lines.size)
                        } else {
                            lines.size
                        }
                        lines.subList(from, until).joinToString("\n")
                    }
                    offset > 0 -> content.drop(offset)
                    else -> content
                }
                linkedMapOf<String, Any?>(
                    "path" to shellPath,
                    "androidPath" to file.absolutePath,
                    "uri" to artifact.uri,
                    "content" to helper.truncateText(sliced, maxChars),
                    "size" to file.length(),
                    "mimeType" to mimeType
                )
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                imageDataUrl = imageReadResult?.imageDataUrl,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "读取文件失败")
        }
    }

    private fun isImageFile(file: File, mimeType: String): Boolean {
        if (mimeType.startsWith("image/", ignoreCase = true)) {
            return true
        }
        val lowerName = file.name.lowercase()
        return lowerName.endsWith(".png") ||
            lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".gif") ||
            lowerName.endsWith(".bmp") ||
            lowerName.endsWith(".heic") ||
            lowerName.endsWith(".heif")
    }

    private suspend fun executeFileWrite(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_write"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在写入文件")
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            val content = args["content"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 content")
            val append = args["append"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "append" to append
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (append) "已追加写入文件：${file.name}" else "已写入文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "写入文件失败")
        }
    }

    private suspend fun executeFileEdit(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_edit"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在编辑文件")
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists() && file.isFile) { "目标文件不存在：${file.absolutePath}" }
            val oldText = args["oldText"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 oldText")
            val newText = args["newText"]?.jsonPrimitive?.content ?: ""
            val replaceAll = args["replaceAll"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val original = file.readText()
            require(original.contains(oldText)) { "文件中未找到 oldText" }
            val updated = if (replaceAll) {
                original.replace(oldText, newText)
            } else {
                original.replaceFirst(oldText, newText)
            }
            file.writeText(updated)
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "replaceAll" to replaceAll
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已更新文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "编辑文件失败")
        }
    }

    private suspend fun executeFileList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_list"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            helper.requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val recursive = args["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxDepth = args["maxDepth"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 6) ?: 2
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1000) ?: SharedHelper.DEFAULT_FILE_LIST_LIMIT
            val files = if (recursive) {
                directory.walkTopDown().maxDepth(maxDepth).drop(1).take(limit).toList()
            } else {
                directory.listFiles()?.sortedBy { it.name.lowercase() }?.take(limit) ?: emptyList()
            }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to files.size,
                "items" to files.map { entry ->
                    mapOf(
                        "name" to entry.name,
                        "path" to (workspaceManager.shellPathForAndroid(entry) ?: entry.absolutePath),
                        "androidPath" to entry.absolutePath,
                        "isDirectory" to entry.isDirectory,
                        "size" to if (entry.isFile) entry.length() else 0L
                    )
                }
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("共找到 ${files.size} 项"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id,
                actions = listOf(
                    helper.buildOpenDirectoryAction(workspaceManager, workspace, directory, "打开目录")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "列目录失败")
        }
    }

    private suspend fun executeFileSearch(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_search"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(query.isNotEmpty()) { "缺少 query" }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            helper.requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val caseSensitive = args["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: SharedHelper.DEFAULT_FILE_SEARCH_LIMIT
            val searchNeedle = if (caseSensitive) query else query.lowercase()
            val results = mutableListOf<Map<String, Any?>>()
            directory.walkTopDown().forEach { file ->
                if (results.size >= maxResults) return@forEach
                if (!file.isFile) return@forEach
                val normalizedName = if (caseSensitive) file.name else file.name.lowercase()
                if (normalizedName.contains(searchNeedle)) {
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "file_name",
                            "snippet" to file.name
                        )
                    )
                    return@forEach
                }
                if (file.length() > 512 * 1024) return@forEach
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val haystack = if (caseSensitive) text else text.lowercase()
                val index = haystack.indexOf(searchNeedle)
                if (index >= 0) {
                    val start = (index - 40).coerceAtLeast(0)
                    val end = (index + query.length + 120).coerceAtMost(text.length)
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "content",
                            "snippet" to text.substring(start, end)
                        )
                    )
                }
            }
            val payload = linkedMapOf<String, Any?>(
                "query" to query,
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to results.size,
                "items" to results
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (results.isEmpty()) "未找到匹配结果" else "找到 ${results.size} 个匹配结果"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "搜索文件失败")
        }
    }

    private suspend fun executeFileStat(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_stat"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowRootDirectories = true,
                allowPublicStorage = true
            )
            require(file.exists()) { "路径不存在：${file.absolutePath}" }
            val artifact = file.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "name" to file.name,
                "exists" to file.exists(),
                "isDirectory" to file.isDirectory,
                "isFile" to file.isFile,
                "size" to if (file.isFile) file.length() else 0L,
                "lastModified" to file.lastModified(),
                "mimeType" to if (file.isFile) workspaceManager.guessMimeType(file) else "inode/directory",
                "uri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取路径信息：${file.name.ifBlank { workspaceManager.shellPathForAndroid(file) ?: file.absolutePath }}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id,
                actions = if (file.isDirectory) {
                    listOf(helper.buildOpenDirectoryAction(workspaceManager, workspace, file))
                } else {
                    emptyList()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "查看文件信息失败")
        }
    }

    private suspend fun executeFileMove(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_move"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["sourcePath"]?.jsonPrimitive?.contentOrNull,
                args["targetPath"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在移动文件")
            val source = workspaceManager.resolvePath(
                args["sourcePath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            val target = workspaceManager.resolvePath(
                args["targetPath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            require(source.exists()) { "源文件不存在：${source.absolutePath}" }
            val overwrite = args["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            require(overwrite || !target.exists()) { "目标已存在：${target.absolutePath}" }
            target.parentFile?.mkdirs()
            if (overwrite && target.exists()) {
                target.deleteRecursively()
            }
            source.copyRecursively(target, overwrite = overwrite)
            source.deleteRecursively()
            val artifact = target.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "sourcePath" to (workspaceManager.shellPathForAndroid(source) ?: source.absolutePath),
                "androidSourcePath" to source.absolutePath,
                "targetPath" to (workspaceManager.shellPathForAndroid(target) ?: target.absolutePath),
                "androidTargetPath" to target.absolutePath,
                "overwrite" to overwrite,
                "targetUri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已移动到：${target.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "移动文件失败")
        }
    }
}
