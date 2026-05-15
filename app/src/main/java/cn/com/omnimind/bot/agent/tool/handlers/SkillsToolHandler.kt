package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SkillsToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf("skills_list", "skills_read", "skills_read_reference")

    private val skillIndexService = SkillIndexService(helper.context, workspaceManager)
    private val skillLoader = SkillLoader(workspaceManager)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "skills_list" -> executeSkillsList(args, env.workspaceDescriptor, callback)
            "skills_read" -> executeSkillsRead(args, env.workspaceDescriptor, callback)
            "skills_read_reference" -> executeSkillsReadReference(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown skills tool")
        }
    }

    private suspend fun executeSkillsList(args: JsonObject, workspace: AgentWorkspaceDescriptor, callback: AgentCallback): ToolExecutionResult {
        val toolName = "skills_list"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: SharedHelper.DEFAULT_SKILLS_LIST_LIMIT
            val normalizedQuery = query.lowercase()
            val entries = skillIndexService.listInstalledSkills()
                .filter { entry ->
                    val compatibility = SkillCompatibilityChecker.evaluate(entry)
                    if (!compatibility.available) return@filter false
                    if (normalizedQuery.isBlank()) true
                    else listOf(entry.id, entry.name, entry.description, entry.shellSkillFilePath, entry.shellRootPath).any { it.lowercase().contains(normalizedQuery) }
                }
                .take(limit)
            val items = entries.map { entry ->
                mapOf(
                    "id" to entry.id, "name" to entry.name, "description" to entry.description,
                    "enabled" to entry.enabled, "source" to entry.source, "installed" to entry.installed,
                    "rootPath" to entry.shellRootPath, "androidRootPath" to entry.rootPath,
                    "skillFilePath" to entry.shellSkillFilePath, "androidSkillFilePath" to entry.skillFilePath,
                    "capabilities" to buildList {
                        if (entry.hasScripts) add("scripts")
                        if (entry.hasReferences) add("references")
                        if (entry.hasAssets) add("assets")
                        if (entry.hasEvals) add("evals")
                    },
                    "metadata" to entry.metadata
                )
            }
            val payload = linkedMapOf<String, Any?>(
                "query" to query, "count" to items.size,
                "skillsRoot" to workspaceManager.shellPathForAndroid(workspaceManager.skillsRoot()),
                "androidSkillsRoot" to workspaceManager.skillsRoot().absolutePath,
                "items" to items
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (items.isEmpty()) "当前没有匹配的 skills" else "共找到 ${items.size} 个 skill"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "列出 skills 失败")
        }
    }

    private suspend fun executeSkillsRead(args: JsonObject, workspace: AgentWorkspaceDescriptor, callback: AgentCallback): ToolExecutionResult {
        val toolName = "skills_read"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val skillId = args["skillId"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(skillId.isNotEmpty()) { "缺少 skillId" }
            val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull?.coerceIn(512, 64_000) ?: SharedHelper.DEFAULT_SKILL_READ_MAX_CHARS
            val entry = skillIndexService.findInstalledSkill(skillId) ?: throw IllegalArgumentException("未找到 skill：$skillId")
            val compatibility = SkillCompatibilityChecker.evaluate(entry)
            require(compatibility.available) { compatibility.reason ?: "当前环境不可用" }
            val resolved = skillLoader.load(entry, "agent 主动读取 skill") ?: throw IllegalStateException("读取 SKILL.md 失败：${entry.shellSkillFilePath}")
            val skillFile = File(entry.skillFilePath)
            val artifact = workspaceManager.buildArtifactForFile(skillFile, toolName)
            val payload = linkedMapOf<String, Any?>(
                "id" to entry.id, "name" to entry.name, "description" to entry.description,
                "enabled" to entry.enabled, "source" to entry.source, "installed" to entry.installed,
                "rootPath" to entry.shellRootPath, "androidRootPath" to entry.rootPath,
                "skillFilePath" to entry.shellSkillFilePath, "androidSkillFilePath" to entry.skillFilePath,
                "scriptsDir" to resolved.scriptsDir, "assetsDir" to resolved.assetsDir,
                "references" to resolved.loadedReferences, "metadata" to resolved.metadata,
                "frontmatter" to resolved.frontmatter,
                "bodyMarkdown" to helper.truncateText(resolved.bodyMarkdown, maxChars),
                "uri" to artifact.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取 skill：${entry.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true, artifacts = listOf(artifact), workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "读取 skill 失败")
        }
    }

    private suspend fun executeSkillsReadReference(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "skills_read_reference"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val skillId = args["skillId"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(skillId.isNotEmpty()) { "缺少 skillId" }
            val refId = args["refId"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(refId.isNotEmpty()) { "缺少 refId" }
            val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull?.coerceIn(512, 64_000)
                ?: SharedHelper.DEFAULT_SKILL_READ_MAX_CHARS
            val entry = skillIndexService.findInstalledSkill(skillId)
                ?: throw IllegalArgumentException("未找到 skill：$skillId")
            val compatibility = SkillCompatibilityChecker.evaluate(entry)
            require(compatibility.available) { compatibility.reason ?: "当前环境不可用" }

            val referencesDir = File(entry.rootPath, "references").canonicalFile
            require(referencesDir.isDirectory) { "skill 没有 references 目录：${entry.id}" }
            val referenceFile = resolveReferenceFile(referencesDir, refId)
                ?: throw IllegalArgumentException("未找到 skill reference：$refId")
            val canonicalReference = referenceFile.canonicalFile
            require(canonicalReference.path.startsWith(referencesDir.path + File.separator)) {
                "reference 路径越界：$refId"
            }
            require(canonicalReference.isFile) { "reference 不是文件：$refId" }

            val artifact = workspaceManager.buildArtifactForFile(canonicalReference, toolName)
            val shellPath = workspaceManager.shellPathForAndroid(canonicalReference)
                ?: canonicalReference.absolutePath
            val payload = linkedMapOf<String, Any?>(
                "skillId" to entry.id,
                "refId" to referenceFile.nameWithoutExtension,
                "path" to shellPath,
                "androidPath" to canonicalReference.absolutePath,
                "uri" to artifact.uri,
                "content" to helper.truncateText(canonicalReference.readText(), maxChars),
                "size" to canonicalReference.length(),
                "mimeType" to workspaceManager.guessMimeType(canonicalReference)
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取 skill reference：${canonicalReference.name}"),
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
            helper.errorResult(toolName, e.message, "读取 skill reference 失败")
        }
    }

    private fun resolveReferenceFile(referencesDir: File, refId: String): File? {
        val normalizedRef = refId.trim().replace('\\', '/').trim('/')
        require(normalizedRef.isNotBlank()) { "缺少 refId" }
        require(!normalizedRef.startsWith(".") && !normalizedRef.contains("/../")) {
            "reference 路径越界：$refId"
        }
        val directCandidate = File(referencesDir, normalizedRef)
        if (directCandidate.isFile) return directCandidate
        if (!normalizedRef.contains('/')) {
            referencesDir.listFiles()
                ?.filter { it.isFile }
                ?.firstOrNull { file ->
                    file.name == normalizedRef ||
                        file.nameWithoutExtension == normalizedRef ||
                        file.name.equals(normalizedRef, ignoreCase = true) ||
                        file.nameWithoutExtension.equals(normalizedRef, ignoreCase = true)
                }
                ?.let { return it }
        }
        return null
    }
}
