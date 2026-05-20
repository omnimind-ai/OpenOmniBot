package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
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

            val llmClient = cn.com.omnimind.bot.agent.langchain4j.LangChain4jAgentLlmClient(
                scope = scope,
                modelOverride = modelOverride
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
            val eventAdapter = AgentEventAdapter(json)
            val orchestrator = AgentOrchestrator(
                llmClient = llmClient,
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                eventAdapter = eventAdapter,
                model = agentModelScene
            )

            orchestrator.run(
                AgentOrchestrator.Input(
                    callback = callback,
                    initialMessages = initialMessages,
                    conversationId = conversationId,
                    contextCompactor = contextCompactor,
                    executionEnv = DefaultAgentExecutionEnvironment(
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
                )
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
    ): List<dev.langchain4j.data.message.ChatMessage> {
        val historyMessages = promptSeed.historyMessages.toMutableList()
        // Drop a trailing user message — the freshly typed user input replaces it.
        if (historyMessages.lastOrNull() is dev.langchain4j.data.message.UserMessage) {
            historyMessages.removeAt(historyMessages.lastIndex)
        }
        val messages = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
        val systemPrompt = AgentSystemPrompt.build(
            workspace = workspaceDescriptor,
            installedSkills = installedSkills,
            skillsRootShellPath = skillsRootShellPath,
            skillsRootAndroidPath = skillsRootAndroidPath,
            resolvedSkills = resolvedSkills,
            memoryContext = memoryContext,
            locale = AppLocaleManager.resolvePromptLocale(context)
        )
        messages.add(dev.langchain4j.data.message.SystemMessage.from(systemPrompt))
        messages.addAll(historyMessages)
        messages.add(buildCurrentUserMessage(userMessage, attachments))
        return messages
    }

    private fun buildCurrentUserMessage(
        userMessage: String,
        attachments: List<Map<String, Any?>>
    ): dev.langchain4j.data.message.UserMessage {
        val normalizedAttachments = normalizeAttachments(attachments)
        val imageContents = normalizedAttachments
            .filter { it.isImage }
            .mapNotNull { attachment ->
                val imageUrl = resolveImageAttachmentUrl(attachment)
                if (imageUrl.isBlank()) null else buildImageContent(imageUrl)
            }
        if (imageContents.isEmpty()) {
            return dev.langchain4j.data.message.UserMessage.from(userMessage)
        }
        val parts = mutableListOf<dev.langchain4j.data.message.Content>()
        if (userMessage.isNotBlank()) {
            parts += dev.langchain4j.data.message.TextContent.from(userMessage)
        }
        parts += imageContents
        return dev.langchain4j.data.message.UserMessage.from(parts)
    }

    private fun buildImageContent(url: String): dev.langchain4j.data.message.ImageContent {
        val trimmed = url.trim()
        if (trimmed.startsWith("data:")) {
            val commaIndex = trimmed.indexOf(',')
            if (commaIndex > 5) {
                val header = trimmed.substring(5, commaIndex)
                val payload = trimmed.substring(commaIndex + 1)
                val parts = header.split(';')
                val mime = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "image/png"
                val isBase64 = parts.any { it.equals("base64", ignoreCase = true) }
                if (isBase64) {
                    return dev.langchain4j.data.message.ImageContent.from(
                        dev.langchain4j.data.image.Image.builder()
                            .base64Data(payload).mimeType(mime).build()
                    )
                }
            }
        }
        return dev.langchain4j.data.message.ImageContent.from(trimmed)
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
