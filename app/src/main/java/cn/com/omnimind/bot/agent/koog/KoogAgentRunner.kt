package cn.com.omnimind.bot.agent.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentFinalResponse
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentOutputKind
import cn.com.omnimind.bot.agent.AgentResult
import kotlin.time.Clock

/**
 * Phase 6 scaffold: runs a single user message through Koog's [AIAgent].
 *
 * This is the entry point for the structural migration of [cn.com.omnimind.bot.agent.AgentOrchestrator]
 * to Koog's native agent loop. Today it covers the simplest path — no tools, no skills, no history
 * compression — but exercises every layer that the full migration needs:
 *   1. [OpenAILLMClient] with an OpenAI-compatible custom baseUrl from [ModelProviderConfigStore],
 *   2. [MultiLLMPromptExecutor] adapter that AIAgent expects,
 *   3. [AIAgentConfig] with system prompt + arbitrary model id + iteration cap,
 *   4. [AIAgent] default single-run strategy graph driving the LLM turn,
 *   5. result back to a callable `suspend fun`.
 *
 * Feature-flagged via MMKV key `agent_use_koog_runner` (default off). When future PRs add:
 *   - tool registry that delegates to existing `AgentToolExecutor` handlers (Phase 2),
 *   - history compression via `installFeatures { compression(...) }` (Phase 3),
 *   - chat memory via `installFeatures { memory(...) }` (Phase 5),
 * they plug into this runner without rewriting it.
 *
 * The legacy [cn.com.omnimind.bot.agent.AgentOrchestrator] remains the production path until
 * `KoogAgentRunner` reaches parity. See project memory `koog-migration` for the gap list.
 */
class KoogAgentRunner(
    private val modelOverride: AgentModelOverride? = null,
    private val clock: Clock = Clock.System
) {

    /**
     * Sends one turn through Koog AIAgent. Returns the assistant's textual response.
     *
     * @param systemPrompt System instructions to seed the agent with.
     * @param userMessage The user's message for this turn.
     * @param model OpenAI-style model id (passed through to the LLM provider as-is).
     * @param maxIterations Upper bound on AIAgent iterations to guard against loops.
     */
    suspend fun runSingleTurn(
        systemPrompt: String,
        userMessage: String,
        model: String,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        toolRegistry: ToolRegistry = ToolRegistry.EMPTY
    ): String {
        val (baseUrl, apiKey) = resolveProvider()
        val llmClient = OpenAILLMClient(
            apiKey = apiKey,
            settings = OpenAIClientSettings(baseUrl = baseUrl)
        )
        try {
            val promptExecutor: PromptExecutor = MultiLLMPromptExecutor(
                llmClients = mapOf(LLMProvider.OpenAI to llmClient)
            )
            val llModel = buildLLModel(modelOverride?.modelId?.takeIf { it.isNotBlank() } ?: model)
            val initialPrompt = Prompt(
                messages = listOf(
                    Message.System(systemPrompt, RequestMetaInfo.create(clock))
                ),
                id = "koog-runner-${clock.now().toEpochMilliseconds()}",
                params = LLMParams()
            )
            val config = AIAgentConfig(
                prompt = initialPrompt,
                model = llModel,
                maxAgentIterations = maxIterations
            )
            val agent: AIAgent<String, String> = AIAgent(
                promptExecutor = promptExecutor,
                agentConfig = config,
                toolRegistry = toolRegistry
            )
            return try {
                agent.run(userMessage)
            } finally {
                runCatching { agent.close() }.onFailure {
                    OmniLog.w(TAG, "close KoogAgent failed: ${it.message}")
                }
            }
        } finally {
            runCatching { llmClient.close() }.onFailure {
                OmniLog.w(TAG, "close OpenAILLMClient failed: ${it.message}")
            }
        }
    }

    private fun resolveProvider(): Pair<String, String> {
        val config = ModelProviderConfigStore.getConfig()
        val rawBaseUrl = modelOverride?.apiBase?.takeIf { it.isNotBlank() }
            ?: config.baseUrl.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Koog runner: no LLM base URL configured")
        val sanitizedBaseUrl = ModelProviderConfigStore.stripDirectRequestUrlMarker(rawBaseUrl)
            .ifBlank { rawBaseUrl }
        val apiKey = modelOverride?.apiKey?.takeIf { it.isNotBlank() } ?: config.apiKey
        return sanitizedBaseUrl to apiKey
    }

    private fun buildLLModel(modelId: String): LLModel {
        return LLModel(
            provider = LLMProvider.OpenAI,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.Completion,
                LLMCapability.Vision.Image,
                LLMCapability.Thinking
            ),
            contextLength = DEFAULT_CONTEXT_LENGTH
        )
    }

    /**
     * Production entry point used by [cn.com.omnimind.bot.agent.OmniAgentExecutor] when the
     * Phase 6 flag is on. Takes the same `initialMessages` and `toolRegistry` shape the legacy
     * orchestrator uses, so swapping in/out is a single conditional branch.
     *
     * The final assistant response is emitted via [callback.onContentUpdate] and then
     * [callback.onCompleted] before the function returns; intermediate streaming events from
     * Koog's `AIAgent` aren't bridged yet (see project memory: Phase 6 streaming bridge is
     * pending). Tool execution still goes through [cn.com.omnimind.bot.agent.AgentToolExecutor]
     * via the `KoogProxyTool` wrappers in [toolRegistry].
     */
    suspend fun runFromInitialMessages(
        initialMessages: List<ChatCompletionMessage>,
        userMessage: String,
        model: String,
        callback: AgentCallback,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        toolRegistry: ToolRegistry = ToolRegistry.EMPTY
    ): AgentResult {
        return try {
            val (baseUrl, apiKey) = resolveProvider()
            val llmClient = OpenAILLMClient(
                apiKey = apiKey,
                settings = OpenAIClientSettings(baseUrl = baseUrl)
            )
            try {
                val promptExecutor: PromptExecutor = MultiLLMPromptExecutor(
                    llmClients = mapOf(LLMProvider.OpenAI to llmClient)
                )
                val llModel = buildLLModel(modelOverride?.modelId?.takeIf { it.isNotBlank() } ?: model)
                val messages = initialMessages.mapNotNull(::toKoogMessage)
                if (messages.none { it is Message.System }) {
                    OmniLog.w(TAG, "no system message in initialMessages — Koog AIAgent will run without system prompt")
                }
                val initialPrompt = Prompt(
                    messages = messages,
                    id = "koog-runner-${clock.now().toEpochMilliseconds()}",
                    params = LLMParams()
                )
                val config = AIAgentConfig(
                    prompt = initialPrompt,
                    model = llModel,
                    maxAgentIterations = maxIterations
                )
                // Aggregate streamed text / reasoning frames as Koog's AIAgent loop runs them,
                // forwarding incremental updates back through the legacy AgentCallback contract so
                // any UI listening for `onChatMessage` / `onThinkingUpdate` keeps working under the
                // Koog path. The final assistant content is also captured for the AgentResult.
                val contentBuffer = StringBuilder()
                val reasoningBuffer = StringBuilder()
                val agent: AIAgent<String, String> = AIAgent(
                    promptExecutor = promptExecutor,
                    agentConfig = config,
                    toolRegistry = toolRegistry,
                    installFeatures = {
                        handleEvents {
                            onLLMStreamingFrameReceived { context ->
                                when (val frame = context.streamFrame) {
                                    is StreamFrame.TextDelta -> {
                                        contentBuffer.append(frame.text)
                                        callback.onChatMessage(contentBuffer.toString(), isFinal = false)
                                    }
                                    is StreamFrame.ReasoningDelta -> {
                                        frame.text?.let { reasoningBuffer.append(it) }
                                        if (reasoningBuffer.isNotEmpty()) {
                                            callback.onThinkingUpdate(reasoningBuffer.toString())
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                            onLLMStreamingStarting { callback.onThinkingStart() }
                        }
                    }
                )
                callback.onThinkingStart()
                val output = try {
                    agent.run(userMessage)
                } finally {
                    runCatching { agent.close() }.onFailure {
                        OmniLog.w(TAG, "close KoogAgent failed: ${it.message}")
                    }
                }
                callback.onChatMessage(output, isFinal = true)
                val agentResult = AgentResult.Success(
                    response = AgentFinalResponse(content = output, finishReason = "stop"),
                    executedTools = emptyList(),
                    outputKind = AgentOutputKind.CHAT_MESSAGE.value,
                    hasUserVisibleOutput = output.isNotBlank()
                )
                callback.onComplete(agentResult)
                agentResult
            } finally {
                runCatching { llmClient.close() }.onFailure {
                    OmniLog.w(TAG, "close OpenAILLMClient failed: ${it.message}")
                }
            }
        } catch (e: Exception) {
            OmniLog.w(TAG, "KoogAgentRunner failed: ${e.message}")
            callback.onError("Koog agent execution failed: ${e.message}")
            AgentResult.Error(message = e.message.orEmpty().ifBlank { "Koog agent failed" }, exception = e)
        }
    }

    /**
     * Maps legacy [ChatCompletionMessage] (system / user / assistant / tool) onto Koog [Message]
     * for use inside [AIAgentConfig.prompt]. Mirrors the conversion in [KoogAgentLlmClient] but
     * specialized for prompts (no per-turn tool descriptors). Lossy on multimodal user messages
     * — those are flattened to text since the prompt-side conversion only carries text.
     */
    private fun toKoogMessage(msg: ChatCompletionMessage): Message? {
        val text = msg.contentText()
        return when (msg.role) {
            "system" -> if (text.isNotEmpty()) Message.System(text, RequestMetaInfo.create(clock)) else null
            "user" -> Message.User(text, RequestMetaInfo.create(clock))
            "assistant" -> if (text.isNotEmpty()) Message.Assistant(text, ResponseMetaInfo.create(clock)) else null
            "tool" -> {
                val toolCallId = msg.toolCallId
                Message.Tool.Result(
                    id = toolCallId,
                    tool = msg.name.orEmpty(),
                    content = text,
                    metaInfo = RequestMetaInfo.create(clock)
                )
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "KoogAgentRunner"
        private const val DEFAULT_CONTEXT_LENGTH = 128_000L
        private const val DEFAULT_MAX_ITERATIONS = 10
    }
}
