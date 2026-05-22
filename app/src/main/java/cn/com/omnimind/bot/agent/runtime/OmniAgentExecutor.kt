package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.agent.workspace.memory.LongTermMemoryIndex
import cn.com.omnimind.bot.agent.workspace.memory.MemoryRetrievalPipeline
import cn.com.omnimind.bot.agent.workspace.memory.TurnMemoryLoadTracker
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveryRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
            val ltmIndex = runCatching {
                LongTermMemoryIndex(workspaceManager)
            }.getOrNull()
            val memoryLoadTracker = TurnMemoryLoadTracker()
            val prefetchedMemoryHits = if (ltmIndex != null) {
                runCatching {
                    MemoryRetrievalPipeline(memoryService, ltmIndex)
                        .prefetchRelevant(userMessage, topK = 4)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            if (prefetchedMemoryHits.isNotEmpty()) {
                memoryLoadTracker.markLoaded(prefetchedMemoryHits.map { it.id })
            }
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
                memoryContext = promptMemoryContext,
                prefetchedMemoryHits = prefetchedMemoryHits
            )

            val llmClient = HttpAgentLlmClient(
                scope = scope,
                json = json,
                modelOverride = modelOverride
            )
            val contextCompactor = AgentConversationContextCompactor(
                historyRepository = historyRepository,
                modelScene = agentModelScene,
                modelOverride = modelOverride,
                reasoningEffort = reasoningEffort,
                json = json
            )
            val eventAdapter = AgentEventAdapter(json)
            // Break the SubagentDispatcher ↔ AgentToolRouter cycle: hand the
            // dispatcher a lazy reference to the router that we'll populate
            // immediately after the router is constructed.
            val routerRef = AtomicReference<AgentToolExecutor?>()
            val catalogRef = AtomicReference<AgentToolCatalog?>(toolRegistry)
            val subagentDispatcher = SubagentDispatcher(
                llmClient = llmClient,
                toolExecutorProvider = {
                    routerRef.get() ?: error("subagent dispatcher invoked before router was bound")
                },
                parentCatalogProvider = {
                    catalogRef.get() ?: error("subagent dispatcher missing parent catalog")
                },
                eventAdapter = eventAdapter,
                model = agentModelScene
            )
            toolRouter = AgentToolRouter(
                context = context,
                scope = scope,
                scheduleToolBridge = scheduleToolBridge,
                workspaceManager = workspaceManager,
                subagentDispatcher = subagentDispatcher
            )
            routerRef.set(toolRouter)
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
                        runControl = runControl,
                        longTermMemoryIndex = ltmIndex,
                        turnMemoryLoadTracker = memoryLoadTracker
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callback.onError("Agent execution failed: ${e.message}")
            AgentResult.Error("Agent execution failed", e)
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
        memoryContext: WorkspaceMemoryPromptContext?,
        prefetchedMemoryHits: List<WorkspaceMemorySearchHit> = emptyList()
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
        messages.add(buildCurrentTimeContextMessage(AppLocaleManager.resolvePromptLocale(context)))
        messages.addAll(historyMessages)
        buildPrefetchedMemoryAttachment(prefetchedMemoryHits)?.let { messages.add(it) }
        messages.add(buildCurrentUserMessage(userMessage, attachments))
        return messages
    }

    private fun buildCurrentTimeContextMessage(
        locale: cn.com.omnimind.baselib.i18n.PromptLocale
    ): cn.com.omnimind.baselib.llm.ChatCompletionMessage {
        val zoneId = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zoneId)
        val utcNow = now.withZoneSameInstant(ZoneOffset.UTC)
        val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val content = when (locale) {
            cn.com.omnimind.baselib.i18n.PromptLocale.ZH_CN -> """
                [time_context]
                当前本地时间: ${now.format(isoFormatter)}
                本地日期: ${now.toLocalDate()}
                本地时间: ${now.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME)}
                时区: ${zoneId.id}
                UTC: ${utcNow.format(isoFormatter)}
                星期: ${now.dayOfWeek.name}
                这条上下文由运行时为本轮请求自动生成，用于解释“今天”“明天”“现在”等相对时间；不要把它当作用户原文或长期记忆。
            """.trimIndent()

            cn.com.omnimind.baselib.i18n.PromptLocale.EN_US -> """
                [time_context]
                Current local time: ${now.format(isoFormatter)}
                Local date: ${now.toLocalDate()}
                Local clock time: ${now.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME)}
                Timezone: ${zoneId.id}
                UTC: ${utcNow.format(isoFormatter)}
                Day of week: ${now.dayOfWeek.name}
                This context is generated by the runtime for this request only. Use it to interpret relative times such as "today", "tomorrow", and "now"; do not treat it as user-authored text or long-term memory.
            """.trimIndent()
        }
        return cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "system",
            content = JsonPrimitive(content)
        )
    }

    private fun buildPrefetchedMemoryAttachment(
        hits: List<WorkspaceMemorySearchHit>
    ): cn.com.omnimind.baselib.llm.ChatCompletionMessage? {
        if (hits.isEmpty()) return null
        val payload = buildString {
            appendLine("[memory.prefetch] 与当前用户问题最相关的工作区记忆：")
            hits.take(4).forEach { hit ->
                val text = hit.text.replace(Regex("\\s+"), " ").trim().take(280)
                appendLine("- (${hit.source}) $text")
            }
        }.trim()
        return cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(payload)
        )
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
