package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.koog.KoogAgentRunner
import cn.com.omnimind.bot.agent.koog.KoogToolRegistryBuilder
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveryRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class OmniAgentExecutor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge
) {
    companion object {
        private const val EPHEMERAL_CACHE_TYPE = "ephemeral"

        internal fun buildCachedSystemPromptContent(prompt: String): JsonElement {
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                        put("cache_control", buildJsonObject {
                            put("type", EPHEMERAL_CACHE_TYPE)
                        })
                    }
                )
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }
    private val agentModelScene = "scene.dispatch.model"

    suspend fun processUserMessage(
        userMessage: String,
        conversationHistory: List<Map<String, Any?>>,
        runtimeContextRepository: AgentRuntimeContextRepository,
        currentPackageName: String?,
        attachments: List<Map<String, Any?>>,
        conversationId: Long?,
        conversationMode: String,
        modelOverride: AgentModelOverride?,
        reasoningEffort: String?,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback,
        runControl: AgentRunControl = NoOpAgentRunControl
    ): AgentResult {
        var toolRouter: AgentToolRouter? = null
        return try {
            val agentRunId = UUID.randomUUID().toString()
            val workspaceManager = AgentWorkspaceManager(context)
            val memoryService = WorkspaceMemoryService(context, workspaceManager)
            val workspaceDescriptor = workspaceManager.buildWorkspaceDescriptor(
                conversationId = conversationId,
                agentRunId = agentRunId
            )
            val historyRepository = AgentConversationHistoryRepository(context)
            val promptMemoryContext = runCatching {
                memoryService.buildPromptContext()
            }.getOrNull()
            val skillIndexService = SkillIndexService(context, workspaceManager)
            val skillLoader = SkillLoader(workspaceManager)
            val installedSkills = skillIndexService.listInstalledSkills()
            val failureLearningSkill = SelfImprovingSkillFailureHook.resolveInstalledSkill(
                installedSkills = installedSkills,
                skillLoader = skillLoader
            )
            val resolvedSkills = SkillTriggerMatcher.resolveMatches(
                userMessage = userMessage,
                entries = installedSkills
            ).mapNotNull { match ->
                val compatibility = SkillCompatibilityChecker.evaluate(match.entry)
                if (!compatibility.available) {
                    null
                } else {
                    skillLoader.load(match.entry, match.triggerReason)
                }
            }
            val discoveredServers = RemoteMcpDiscoveryRegistry.discoverEnabledServers()
            val toolRegistry = AgentToolRegistry(
                context = context,
                discoveredServers = discoveredServers,
                conversationMode = conversationMode
            )
            val initialMessages = buildInitialMessages(
                promptSeed = historyRepository.buildPromptSeed(
                    conversationId = conversationId,
                    conversationMode = conversationMode
                ),
                userMessage = userMessage,
                attachments = attachments,
                workspaceDescriptor = workspaceDescriptor,
                installedSkills = installedSkills,
                skillsRootShellPath = workspaceManager.shellPathForAndroid(workspaceManager.skillsRoot())
                    ?: workspaceManager.skillsRoot().absolutePath,
                skillsRootAndroidPath = workspaceManager.skillsRoot().absolutePath,
                resolvedSkills = resolvedSkills,
                memoryContext = promptMemoryContext
            )

            val contextCompactor = AgentConversationContextCompactor(
                historyRepository = historyRepository,
                modelScene = agentModelScene,
                modelOverride = modelOverride,
                reasoningEffort = reasoningEffort,
                json = json
            )
            toolRouter = AgentToolRouter(
                context = context,
                scope = scope,
                scheduleToolBridge = scheduleToolBridge,
                workspaceManager = workspaceManager
            )
            val executionEnv = DefaultAgentExecutionEnvironment(
                agentRunId = agentRunId,
                userMessage = userMessage,
                currentPackageName = currentPackageName,
                runtimeContextRepository = runtimeContextRepository,
                workspaceDescriptor = workspaceDescriptor,
                resolvedSkills = resolvedSkills,
                failureLearningSkill = failureLearningSkill,
                workspaceManager = workspaceManager,
                workspaceMemoryService = memoryService,
                conversationMode = conversationMode,
                reasoningEffort = reasoningEffort,
                terminalEnvironment = terminalEnvironment,
                runControl = runControl
            )

            val koogToolRegistry = KoogToolRegistryBuilder.build(
                catalog = toolRegistry,
                executor = toolRouter,
                env = executionEnv,
                callback = callback
            )
            KoogAgentRunner(modelOverride = modelOverride).runFromInitialMessages(
                initialMessages = initialMessages,
                userMessage = userMessage,
                model = agentModelScene,
                callback = callback,
                toolRegistry = koogToolRegistry
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callback.onError("Agent execution failed: ${e.message}")
            AgentResult.Error("Agent execution failed", e as? Exception)
        } finally {
            runCatching { toolRouter?.dispose() }
        }
    }

    private fun buildInitialMessages(
        promptSeed: AgentConversationHistoryRepository.PromptSeed,
        userMessage: String,
        attachments: List<Map<String, Any?>>,
        workspaceDescriptor: AgentWorkspaceDescriptor,
        installedSkills: List<SkillIndexEntry>,
        skillsRootShellPath: String,
        skillsRootAndroidPath: String,
        resolvedSkills: List<ResolvedSkillContext>,
        memoryContext: WorkspaceMemoryPromptContext?
    ): List<cn.com.omnimind.baselib.llm.ChatCompletionMessage> {
        val historyMessages = promptSeed.historyMessages.toMutableList()
        if (historyMessages.lastOrNull()?.role == "user") {
            historyMessages.removeAt(historyMessages.lastIndex)
        }
        val messages = mutableListOf<cn.com.omnimind.baselib.llm.ChatCompletionMessage>()
        val systemPrompt = AgentSystemPrompt.build(
            workspace = workspaceDescriptor,
            installedSkills = installedSkills,
            skillsRootShellPath = skillsRootShellPath,
            skillsRootAndroidPath = skillsRootAndroidPath,
            resolvedSkills = resolvedSkills,
            memoryContext = memoryContext,
            locale = AppLocaleManager.resolvePromptLocale(context)
        )
        messages.add(
            cn.com.omnimind.baselib.llm.ChatCompletionMessage(
                role = "system",
                content = buildCachedSystemPromptContent(systemPrompt)
            )
        )
        messages.addAll(historyMessages)
        messages.add(buildCurrentUserMessage(userMessage, attachments))
        return messages
    }

    private fun buildCurrentUserMessage(
        userMessage: String,
        attachments: List<Map<String, Any?>>
    ): cn.com.omnimind.baselib.llm.ChatCompletionMessage {
        val normalizedAttachments = normalizeAttachments(attachments)
        val imageParts = normalizedAttachments
            .filter { it.isImage }
            .mapNotNull { attachment ->
                val imageUrl = resolveImageAttachmentUrl(attachment)
                if (imageUrl.isBlank()) {
                    null
                } else {
                    buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", imageUrl)
                        })
                    }
                }
            }
        val rawText = userMessage
        val content = if (imageParts.isEmpty()) {
            JsonPrimitive(rawText)
        } else {
            buildJsonArray {
                if (rawText.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", rawText)
                        }
                    )
                }
                imageParts.forEach { add(it) }
            }
        }
        return cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "user",
            content = content
        )
    }

    private data class PromptAttachment(
        val isImage: Boolean,
        val url: String?,
        val dataUrl: String?,
        val path: String?,
        val mimeType: String?
    )

    private fun normalizeAttachments(attachments: List<Map<String, Any?>>): List<PromptAttachment> {
        return attachments.map { item ->
            val mimeType = item["mimeType"]?.toString()?.trim()
            val explicitImage = item["isImage"]?.toString()?.toBooleanStrictOrNull()
            val isImage = explicitImage ?: mimeType.orEmpty().lowercase().startsWith("image/")
            PromptAttachment(
                isImage = isImage,
                url = item["url"]?.toString(),
                dataUrl = item["dataUrl"]?.toString(),
                path = item["path"]?.toString(),
                mimeType = mimeType
            )
        }
    }

    private fun resolveImageAttachmentUrl(attachment: PromptAttachment): String {
        val dataUrl = attachment.dataUrl.orEmpty().trim()
        if (dataUrl.startsWith("data:")) return dataUrl

        val remoteUrl = attachment.url.orEmpty().trim()
        if (remoteUrl.startsWith("https://") || remoteUrl.startsWith("http://") || remoteUrl.startsWith("data:")) {
            return remoteUrl
        }
        val path = attachment.path.orEmpty().trim()
        if (path.isNotEmpty()) {
            val resolved = AgentImageAttachmentSupport.resolveImageAttachmentUrl(
                mapOf(
                    "path" to path,
                    "mimeType" to attachment.mimeType,
                    "isImage" to attachment.isImage
                )
            )
            if (resolved.isNotBlank()) {
                return resolved
            }
        }
        return ""
    }
}
