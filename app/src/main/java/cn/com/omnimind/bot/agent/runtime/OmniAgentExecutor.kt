package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.config.AgentToolFeatureStore
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveryRegistry
import cn.com.omnimind.bot.workbench.WorkbenchDisplayLayoutContext
import cn.com.omnimind.bot.workbench.WorkbenchProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
            val activeWorkbenchProjectContext = runCatching {
                WorkbenchProjectStore(context).activeProjectPromptContext()
            }.getOrNull()
            val promptLocale = AppLocaleManager.resolvePromptLocale(context)
            val workbenchDisplayLayoutContext = WorkbenchDisplayLayoutContext.promptSection(
                context = context,
                locale = promptLocale
            )
            val skillIndexService = SkillIndexService(context, workspaceManager)
            val skillLoader = SkillLoader(workspaceManager)
            val installedSkills = skillIndexService.listInstalledSkills()
            val failureLearningSkill = SelfImprovingSkillFailureHook.resolveInstalledSkill(
                installedSkills = installedSkills,
                skillLoader = skillLoader
            )
            // Standard skill mode: no auto-injection. Skills are listed in the system
            // prompt by name/description. The agent proactively calls skills_read when
            // a skill seems relevant, receiving the full SKILL.md content (up to 64k).
            // This matches the Anthropic/Claude Code skill pattern and avoids the
            // 16-line truncation problem of keyword-based auto-injection.
            val resolvedSkills = emptyList<ResolvedSkillContext>()
            callback.onSkillsResolved(emptyList())
            val discoveredServers = RemoteMcpDiscoveryRegistry.discoverEnabledServers()
            val oobFunctionDefinitions = if (AgentToolFeatureStore.isOobFunctionAsToolEnabled(context)) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val functions = (OobReusableFunctionStore.list(context, limit = 50)["functions"]
                        as? List<Map<String, Any?>>) ?: emptyList()
                    functions.mapNotNull { spec -> buildOobToolDefinition(spec, promptLocale) }
                }.getOrElse { emptyList() }
            } else emptyList()
            val toolRegistry = AgentToolRegistry(
                context = context,
                discoveredServers = discoveredServers,
                conversationMode = conversationMode,
                dynamicDefinitions = oobFunctionDefinitions
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
                activeWorkbenchProjectContext = activeWorkbenchProjectContext,
                workbenchDisplayLayoutContext = workbenchDisplayLayoutContext,
                locale = promptLocale
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
                        attachments = attachments,
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
        memoryContext: WorkspaceMemoryPromptContext?,
        activeWorkbenchProjectContext: String?,
        workbenchDisplayLayoutContext: String?,
        locale: cn.com.omnimind.baselib.i18n.PromptLocale
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
            activeWorkbenchProjectContext = activeWorkbenchProjectContext,
            workbenchDisplayLayoutContext = workbenchDisplayLayoutContext,
            locale = locale
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

    private fun buildOobToolDefinition(
        spec: Map<String, Any?>,
        locale: PromptLocale
    ): JsonObject? {
        val name = spec["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val description = spec["description"]?.toString().orEmpty()
        val parameters = spec["parameters"] as? List<*> ?: emptyList<Any?>()

        return AgentToolDefinitions.decorateToolDefinition(
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("function", buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("displayName", JsonPrimitive(name))
                    put("toolType", JsonPrimitive("oob_function"))
                    put("description", JsonPrimitive(
                        description.ifBlank { "OOB reusable function: $name" }
                    ))
                    put("parameters", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {
                            for (rawParam in parameters) {
                                val param = rawParam as? Map<*, *> ?: continue
                                val pName = param["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                                put(pName, buildJsonObject {
                                    put("type", JsonPrimitive(
                                        param["type"]?.toString() ?: "string"
                                    ))
                                    put("description", JsonPrimitive(
                                        param["description"]?.toString() ?: pName
                                    ))
                                    if (param.containsKey("default")) {
                                        put("default", JsonPrimitive(
                                            param["default"]?.toString() ?: ""
                                        ))
                                    }
                                })
                            }
                        })
                        // required: only params where required=true and no default
                        put("required", buildJsonArray {
                            for (rawParam in parameters) {
                                val param = rawParam as? Map<*, *> ?: continue
                                val pName = param["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                                val isRequired = when (val r = param["required"]) {
                                    is Boolean -> r
                                    is String -> r.equals("true", ignoreCase = true)
                                    else -> false
                                }
                                val hasDefault = param.containsKey("default")
                                if (isRequired && !hasDefault) add(JsonPrimitive(pName))
                            }
                        })
                    })
                })
            }, locale
        )
    }
}
