package cn.com.omnimind.bot.codex

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.setup.EnvironmentSetupLogic
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CodexAppServerManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val threadStartMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindingRepository = CodexThreadBindingRepository(appContext)
    private val activeTurnsByThreadId = ConcurrentHashMap<String, String>()

    @Volatile
    private var pendingThreadStartConversationId: Long? = null

    @Volatile
    private var session: CodexAppServerSession? = null
    @Volatile
    private var eventListener: ((Map<String, Any?>) -> Unit)? = null

    fun setEventListener(listener: ((Map<String, Any?>) -> Unit)?) {
        eventListener = listener
    }

    suspend fun status(): Map<String, Any?> {
        val probe = probeCodex()
        return linkedMapOf(
            "connected" to (session?.isRunning == true),
            "ready" to probe.ready,
            "version" to probe.version,
            "error" to probe.error,
            "codexHome" to CodexAppServerDefaults.CODEX_HOME,
            "cwd" to resolveDefaultCwd()
        )
    }

    suspend fun connect(): Map<String, Any?> {
        sessionMutex.withLock {
            val existing = session
            if (existing?.isRunning == true) {
                return status()
            }
            val nextSession = CodexAppServerSession(
                context = appContext,
                scope = scope,
                onServerMessage = ::handleServerMessage
            )
            session = nextSession
            try {
                nextSession.start(clientVersion = BuildConfig.VERSION_NAME)
            } catch (error: Throwable) {
                if (session === nextSession) {
                    session = null
                }
                throw error
            }
        }
        return status()
    }

    suspend fun disconnect(): Map<String, Any?> {
        sessionMutex.withLock {
            session?.disconnect()
            session = null
            activeTurnsByThreadId.clear()
        }
        return status()
    }

    suspend fun handleMethod(method: String, args: Map<String, Any?>): Any? {
        return when (method) {
            "status" -> status()
            "connect" -> connect()
            "disconnect" -> disconnect()
            "thread/start" -> startThread(args)
            "thread/resume" -> requestWithResolvedThread("thread/resume", args)
            "thread/read" -> requestWithResolvedThread("thread/read", args)
            "thread/list" -> listThreads(args)
            "thread/archive" -> archiveThread(args, archived = true)
            "thread/unarchive" -> archiveThread(args, archived = false)
            "thread/name/set" -> setThreadName(args)
            "model/list" -> requestWrappedList("model/list", args, "models")
            "collaborationMode/list" -> requestWrappedList(
                "collaborationMode/list",
                args,
                "collaborationModes"
            )
            "config/local/read" -> readLocalConfig()
            "config/local/write" -> writeLocalConfig(args)
            "turn/start" -> startTurn(args)
            "turn/steer" -> steerTurn(args)
            "turn/interrupt" -> interruptTurn(args)
            "review/start" -> startReview(args)
            "account/read" -> request("account/read", null)
            "account/login/start" -> request(
                "account/login/start",
                args.ifEmpty { mapOf("type" to "chatgpt") }
            )
            "account/login/cancel" -> request("account/login/cancel", args)
            "account/rateLimits/read" -> request("account/rateLimits/read", null)
            "respondToServerRequest" -> respondToServerRequest(args)
            else -> request(method, args)
        }
    }

    private suspend fun startThread(args: Map<String, Any?>): Map<String, Any?> = threadStartMutex.withLock {
        val cwd = sanitizeCodexAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        val conversationId = args.longValue("conversationId")
        val params = linkedMapOf<String, Any?>(
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandboxPolicy" to (args["sandboxPolicy"] ?: buildDefaultCodexSandboxPolicy(cwd))
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addCodexOptionalRunParams(params, args)
        if (conversationId != null) {
            pendingThreadStartConversationId = conversationId
        }
        try {
            val response = request("thread/start", params) as Map<String, Any?>
            val threadId = extractThreadId(response) ?: response.stringValue("id")
            var localConversationId: Long? = null
            if (!threadId.isNullOrBlank()) {
                localConversationId = bindingRepository.ensureBinding(
                    threadId = threadId,
                    conversationId = conversationId,
                    cwd = cwd,
                    title = extractThreadTitle(response)
                )
            }
            response.withLocalIds(threadId = threadId, conversationId = localConversationId)
        } finally {
            if (pendingThreadStartConversationId == conversationId) {
                pendingThreadStartConversationId = null
            }
        }
    }

    private suspend fun listThreads(args: Map<String, Any?>): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>()
        args["cursor"]?.let { params["cursor"] = it }
        args["limit"]?.let { params["limit"] = it }
        args["sortKey"]?.let { params["sortKey"] = it }
        params["sourceKinds"] = args["sourceKinds"] ?: listOf(
            "interactive",
            "background",
            "subAgentInteractive"
        )
        val response = request("thread/list", params) as Map<String, Any?>
        syncThreadListResponse(response)
        return response
    }

    private suspend fun requestWithResolvedThread(
        method: String,
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val response = request(method, mapOf("threadId" to threadId)) as Map<String, Any?>
        if (method == "thread/read" || method == "thread/resume") {
            syncThreadListResponse(response)
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun archiveThread(
        args: Map<String, Any?>,
        archived: Boolean
    ): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val method = if (archived) "thread/archive" else "thread/unarchive"
        val response = request(method, mapOf("threadId" to threadId)) as Map<String, Any?>
        bindingRepository.setArchived(threadId, archived)
        return response.withLocalIds(
            threadId = threadId,
            conversationId = bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun setThreadName(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val name = args.stringValue("name") ?: args.stringValue("threadName") ?: ""
        val response = request(
            "thread/name/set",
            mapOf("threadId" to threadId, "name" to name)
        ) as Map<String, Any?>
        bindingRepository.updateTitle(threadId, name)
        return response.withLocalIds(
            threadId = threadId,
            conversationId = bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun requestWrappedList(
        method: String,
        args: Map<String, Any?>,
        listKey: String
    ): Map<String, Any?> {
        val response = request(method, if (args.isEmpty()) null else args)
        return when (response) {
            is Map<*, *> -> response.entries.associate { (key, value) -> key.toString() to value }
            is List<*> -> mapOf(listKey to response)
            else -> mapOf(listKey to emptyList<Any?>(), "raw" to response)
        }
    }

    private suspend fun startTurn(args: Map<String, Any?>): Map<String, Any?> {
        val cwd = sanitizeCodexAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        var threadId = ensureThreadForTurn(args, cwd)
        val params = buildTurnStartParams(
            args = args,
            cwd = cwd,
            threadId = threadId
        )
        val response = try {
            request("turn/start", params) as Map<String, Any?>
        } catch (error: Throwable) {
            if (!shouldRecoverMissingThread(error)) {
                throw error
            }
            Log.w(
                "CodexAppServerManager",
                "Codex turn/start hit a missing thread; creating a fresh thread binding."
            )
            val retryResponse = startThread(args + mapOf("cwd" to cwd))
            threadId = retryResponse["threadId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw error
            params["threadId"] = threadId
            request("turn/start", params) as Map<String, Any?>
        }
        val turnId = extractTurnId(response)
        if (!turnId.isNullOrBlank()) {
            activeTurnsByThreadId[threadId] = turnId
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = bindingRepository.getBindingByThreadId(threadId)?.conversationId,
            turnId = turnId
        )
    }

    private suspend fun startReview(args: Map<String, Any?>): Map<String, Any?> {
        val cwd = sanitizeCodexAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        var threadId = ensureThreadForTurn(args, cwd)
        val params = buildReviewStartParams(
            args = args,
            cwd = cwd,
            threadId = threadId
        )
        val response = try {
            request("review/start", params) as Map<String, Any?>
        } catch (error: Throwable) {
            if (!shouldRecoverMissingThread(error)) {
                throw error
            }
            Log.w(
                "CodexAppServerManager",
                "Codex review/start hit a missing thread; creating a fresh thread binding."
            )
            val retryResponse = startThread(args + mapOf("cwd" to cwd))
            threadId = retryResponse["threadId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw error
            params["threadId"] = threadId
            request("review/start", params) as Map<String, Any?>
        }
        val turnId = extractTurnId(response)
        if (!turnId.isNullOrBlank()) {
            activeTurnsByThreadId[threadId] = turnId
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = bindingRepository.getBindingByThreadId(threadId)?.conversationId,
            turnId = turnId
        )
    }

    private suspend fun steerTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val expectedTurnId = args.stringValue("expectedTurnId")
            ?: args.stringValue("turnId")
            ?: activeTurnsByThreadId[threadId]
            ?: throw IllegalArgumentException("missing active Codex turn id")
        val response = request(
            "turn/steer",
            mapOf(
                "threadId" to threadId,
                "expectedTurnId" to expectedTurnId,
                "input" to resolveInput(args)
            )
        ) as Map<String, Any?>
        return response.withLocalIds(
            threadId = threadId,
            conversationId = bindingRepository.getBindingByThreadId(threadId)?.conversationId,
            turnId = expectedTurnId
        )
    }

    private suspend fun interruptTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val turnId = args.stringValue("turnId")
            ?: activeTurnsByThreadId[threadId]
            ?: throw IllegalArgumentException("missing active Codex turn id")
        val response = request(
            "turn/interrupt",
            mapOf("threadId" to threadId, "turnId" to turnId)
        ) as Map<String, Any?>
        activeTurnsByThreadId.remove(threadId)
        return response.withLocalIds(
            threadId = threadId,
            conversationId = bindingRepository.getBindingByThreadId(threadId)?.conversationId,
            turnId = turnId
        )
    }

    private suspend fun respondToServerRequest(args: Map<String, Any?>): Map<String, Any?> {
        val requestId = args["requestId"] ?: args["id"]
            ?: throw IllegalArgumentException("requestId is required")
        val result = args["response"] ?: args["result"]
            ?: throw IllegalArgumentException("response is required")
        ensureConnectedSession().sendResponse(requestId, result)
        return mapOf("ok" to true)
    }

    private suspend fun readLocalConfig(): Map<String, Any?> {
        val command = """
            mkdir -p ${shellQuote(CodexAppServerDefaults.CODEX_HOME)}
            printf '__OMNI_CODEX_CONFIG_START__\n'
            if [ -f ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/config.toml")} ]; then
              cat ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/config.toml")}
            fi
            printf '\n__OMNI_CODEX_CONFIG_END__\n'
            printf '__OMNI_CODEX_AUTH_START__\n'
            if [ -f ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/auth.json")} ]; then
              cat ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/auth.json")}
            fi
            printf '\n__OMNI_CODEX_AUTH_END__\n'
        """.trimIndent()
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = "codex-config-read",
            timeoutMs = 30_000L
        )
        if (!result.isOk || result.exitCode != 0) {
            throw IllegalStateException(
                result.error.ifBlank { result.rawOutputPreview.ifBlank { "Failed to read Codex config." } }
            )
        }

        val configToml = extractMarkedBlock(
            result.output,
            "__OMNI_CODEX_CONFIG_START__",
            "__OMNI_CODEX_CONFIG_END__"
        )
        val authJson = extractMarkedBlock(
            result.output,
            "__OMNI_CODEX_AUTH_START__",
            "__OMNI_CODEX_AUTH_END__"
        )
        return buildCodexLocalConfigPayload(
            model = extractTomlString(configToml, "model").orEmpty(),
            baseUrl = extractTomlString(configToml, "base_url").orEmpty(),
            apiKey = extractOpenAiApiKey(authJson).orEmpty()
        )
    }

    private suspend fun writeLocalConfig(args: Map<String, Any?>): Map<String, Any?> {
        val baseUrl = args.stringValue("baseUrl")
            ?: throw IllegalArgumentException("baseUrl is required")
        val model = args.stringValue("model")
            ?: throw IllegalArgumentException("model is required")
        val apiKey = args.stringValue("apiKey")
            ?: throw IllegalArgumentException("OPENAI_API_KEY is required")

        val configToml = buildCodexConfigToml(baseUrl = baseUrl, model = model)
        val authJson = JSONObject()
            .put("OPENAI_API_KEY", apiKey)
            .toString(4) + "\n"
        val configPath = "${CodexAppServerDefaults.CODEX_HOME}/config.toml"
        val authPath = "${CodexAppServerDefaults.CODEX_HOME}/auth.json"
        val command = """
            set -eu
            mkdir -p ${shellQuote(CodexAppServerDefaults.CODEX_HOME)}
            umask 077
            printf %s ${shellQuote(configToml)} > ${shellQuote(configPath)}
            printf %s ${shellQuote(authJson)} > ${shellQuote(authPath)}
            chmod 600 ${shellQuote(configPath)} ${shellQuote(authPath)}
            printf '__OMNI_CODEX_WRITE_OK__\n'
        """.trimIndent()
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = "codex-config-write",
            timeoutMs = 30_000L
        )
        if (!result.isOk || result.exitCode != 0) {
            throw IllegalStateException(
                result.error.ifBlank { result.rawOutputPreview.ifBlank { "Failed to write Codex config." } }
            )
        }
        return buildCodexLocalConfigPayload(
            model = model,
            baseUrl = baseUrl,
            apiKey = apiKey
        )
    }

    private fun buildTurnStartParams(
        args: Map<String, Any?>,
        cwd: String,
        threadId: String
    ): MutableMap<String, Any?> {
        val params = linkedMapOf<String, Any?>(
            "threadId" to threadId,
            "input" to resolveInput(args),
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandboxPolicy" to (args["sandboxPolicy"] ?: buildDefaultCodexSandboxPolicy(cwd))
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addCodexOptionalRunParams(params, args)
        return params
    }

    private fun buildReviewStartParams(
        args: Map<String, Any?>,
        cwd: String,
        threadId: String
    ): MutableMap<String, Any?> {
        val params = linkedMapOf<String, Any?>(
            "threadId" to threadId,
            "target" to resolveCodexReviewTarget(args["target"]),
            "delivery" to (args.stringValue("delivery") ?: "inline"),
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandboxPolicy" to (args["sandboxPolicy"] ?: buildDefaultCodexSandboxPolicy(cwd))
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addCodexOptionalRunParams(params, args)
        return params
    }

    private fun shouldRecoverMissingThread(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("thread not found")
    }

    private suspend fun ensureThreadForTurn(args: Map<String, Any?>, cwd: String): String {
        val explicitThreadId = args.stringValue("threadId")
        if (!explicitThreadId.isNullOrBlank()) {
            return explicitThreadId
        }
        val conversationId = args.longValue("conversationId")
        if (conversationId != null) {
            val binding = bindingRepository.getBindingByConversationId(conversationId)
            if (binding != null) {
                return binding.threadId
            }
        }
        val response = startThread(args + mapOf("cwd" to cwd))
        return response["threadId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("thread/start did not return a threadId")
    }

    private suspend fun request(method: String, params: Any?): Any {
        val response = ensureConnectedSession().sendRequest(method, params)
        val error = response["error"]
        if (error != null) {
            throw IllegalStateException(error.toString())
        }
        return response["result"] ?: response
    }

    private suspend fun ensureConnectedSession(): CodexAppServerSession {
        val existing = session
        if (existing?.isRunning == true) {
            return existing
        }
        connect()
        return session ?: throw IllegalStateException("Codex app-server is not connected.")
    }

    private suspend fun handleServerMessage(message: Map<String, Any?>) {
        val method = message["method"]?.toString()?.trim().orEmpty()
        val params = message.mapValue("params")
        val threadId = extractThreadId(message)
        val turnId = extractTurnId(message)
        if (!threadId.isNullOrBlank() && !turnId.isNullOrBlank() && method == "turn/started") {
            activeTurnsByThreadId[threadId] = turnId
        }
        if (!threadId.isNullOrBlank() && method == "turn/completed") {
            activeTurnsByThreadId.remove(threadId)
        }

        val localConversationId = syncMessage(method, message, params, threadId)
        emitEvent(
            linkedMapOf(
                "method" to method,
                "workspaceId" to CodexAppServerSession.DEFAULT_WORKSPACE_ID,
                "threadId" to threadId,
                "turnId" to turnId,
                "conversationId" to localConversationId,
                "params" to params,
                "message" to message
            )
        )
    }

    private suspend fun syncMessage(
        method: String,
        message: Map<String, Any?>,
        params: Map<String, Any?>,
        threadId: String?
    ): Long? {
        return when (method) {
            "thread/started" -> {
                val thread = params.mapValue("thread")
                val resolvedThreadId = thread.stringValue("id") ?: threadId
                if (resolvedThreadId.isNullOrBlank()) {
                    null
                } else {
                    bindingRepository.ensureBinding(
                        threadId = resolvedThreadId,
                        conversationId = pendingThreadStartConversationId,
                        cwd = sanitizeCodexAbsolutePath(thread.stringValue("cwd"))
                            ?: sanitizeCodexAbsolutePath(params.stringValue("cwd"))
                            ?: resolveDefaultCwd(),
                        title = extractThreadTitle(message)
                    )
                }
            }
            "thread/name/updated" -> {
                val resolvedThreadId = threadId ?: params.stringValue("threadId") ?: params.stringValue("thread_id")
                if (!resolvedThreadId.isNullOrBlank()) {
                    bindingRepository.updateTitle(
                        resolvedThreadId,
                        params.stringValue("threadName")
                            ?: params.stringValue("thread_name")
                            ?: params.stringValue("name")
                            ?: params.stringValue("title")
                    )
                    bindingRepository.getBindingByThreadId(resolvedThreadId)?.conversationId
                } else {
                    null
                }
            }
            "thread/archived" -> {
                threadId?.let {
                    bindingRepository.setArchived(it, true)
                    bindingRepository.getBindingByThreadId(it)?.conversationId
                }
            }
            "thread/unarchived" -> {
                threadId?.let {
                    bindingRepository.setArchived(it, false)
                    bindingRepository.getBindingByThreadId(it)?.conversationId
                }
            }
            else -> {
                if (!threadId.isNullOrBlank()) {
                    bindingRepository.getBindingByThreadId(threadId)?.conversationId
                } else {
                    null
                }
            }
        }
    }

    private suspend fun syncThreadListResponse(response: Map<String, Any?>) {
        collectThreadEntries(response).forEach { entry ->
            bindingRepository.ensureBinding(
                threadId = entry.threadId,
                cwd = sanitizeCodexAbsolutePath(entry.cwd) ?: resolveDefaultCwd(),
                title = entry.title,
                archived = entry.archived
            )
        }
    }

    private fun emitEvent(event: Map<String, Any?>) {
        val listener = eventListener ?: return
        mainHandler.post {
            listener(event)
        }
    }

    private suspend fun probeCodex(): CodexProbe {
        return runCatching {
            val terminalManager = TerminalManager.getInstance(appContext)
            val result = terminalManager.executeHiddenCommand(
                command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("codex")),
                executorKey = "codex-probe",
                timeoutMs = 30_000L
            )
            val parsed = EnvironmentSetupLogic.parseInventoryProbeOutput(result.output)
            val codex = parsed["codex"]
            CodexProbe(
                ready = codex?.ready == true,
                version = codex?.version,
                error = if (result.exitCode == 0) null else result.error.ifBlank { result.rawOutputPreview }
            )
        }.getOrElse { error ->
            CodexProbe(
                ready = false,
                version = null,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private suspend fun resolveDefaultCwd(): String {
        return runCatching {
            val workspaceRoot = AgentWorkspaceManager.rootDirectory(appContext)
            workspaceRoot.mkdirs()
            if (workspaceRoot.exists() && workspaceRoot.isDirectory) {
                CodexAppServerDefaults.DEFAULT_WORKSPACE_CWD
            } else {
                CodexAppServerDefaults.FALLBACK_CWD
            }
        }.getOrNull() ?: CodexAppServerDefaults.FALLBACK_CWD
    }

    private suspend fun resolveThreadId(args: Map<String, Any?>): String {
        val explicit = args.stringValue("threadId") ?: args.stringValue("thread_id")
        if (!explicit.isNullOrBlank()) {
            return explicit
        }
        val conversationId = args.longValue("conversationId")
            ?: throw IllegalArgumentException("threadId or conversationId is required")
        val binding = bindingRepository.getBindingByConversationId(conversationId)
            ?: throw IllegalArgumentException("Codex thread binding not found for conversation $conversationId")
        return binding.threadId
    }

    private fun resolveInput(args: Map<String, Any?>): List<Map<String, Any?>> {
        val rawInput = args["input"]
        if (rawInput is List<*>) {
            return rawInput
                .mapNotNull { it as? Map<*, *> }
                .map { entry ->
                    LinkedHashMap<String, Any?>().apply {
                        entry.entries.forEach { (key, value) ->
                            put(key.toString(), value)
                        }
                        if (this["type"]?.toString() == "text" && !containsKey("text_elements")) {
                            put("text_elements", emptyList<Map<String, Any?>>())
                        }
                    }
                }
                .filter { it.isNotEmpty() }
        }
        val text = args.stringValue("text") ?: args.stringValue("message") ?: ""
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Codex turn input is empty" }
        return buildCodexTextInput(trimmed)
    }

    private data class CodexProbe(
        val ready: Boolean,
        val version: String?,
        val error: String?
    )

    companion object {
        @Volatile
        private var INSTANCE: CodexAppServerManager? = null

        fun getInstance(context: Context): CodexAppServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CodexAppServerManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

private data class CodexThreadListEntry(
    val threadId: String,
    val cwd: String?,
    val title: String?,
    val archived: Boolean?
)

private fun Map<String, Any?>.withLocalIds(
    threadId: String?,
    conversationId: Long?,
    turnId: String? = null
): Map<String, Any?> {
    val result = LinkedHashMap(this)
    if (!threadId.isNullOrBlank()) {
        result["threadId"] = threadId
    }
    if (conversationId != null) {
        result["conversationId"] = conversationId
    }
    if (!turnId.isNullOrBlank()) {
        result["turnId"] = turnId
    }
    return result
}

internal fun sanitizeCodexAbsolutePath(raw: String?): String? {
    val source = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return source
        .lineSequence()
        .map { it.trim() }
        .lastOrNull { line ->
            line.startsWith("/") && line.none { char -> char.isISOControl() }
        }
}

internal fun buildCodexTextInput(text: String): List<Map<String, Any?>> {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "Codex turn input is empty" }
    return listOf(
        linkedMapOf(
            "type" to "text",
            "text" to trimmed,
            "text_elements" to emptyList<Map<String, Any?>>()
        )
    )
}

internal fun buildDefaultCodexSandboxPolicy(cwd: String): Map<String, Any?> {
    val writableRoot = sanitizeCodexAbsolutePath(cwd) ?: CodexAppServerDefaults.FALLBACK_CWD
    return linkedMapOf(
        "type" to "workspaceWrite",
        "writableRoots" to listOf(writableRoot),
        "networkAccess" to true,
        "excludeTmpdirEnvVar" to false,
        "excludeSlashTmp" to false
    )
}

internal fun addCodexOptionalRunParams(
    params: MutableMap<String, Any?>,
    args: Map<String, Any?>
) {
    args["model"]?.let { params["model"] = it }
    args["effort"]?.let { params["effort"] = it }
    args["collaborationMode"]?.let { params["collaborationMode"] = it }
    args["serviceTier"]?.let { params["serviceTier"] = it }
}

private fun buildCodexLocalConfigPayload(
    model: String,
    baseUrl: String,
    apiKey: String
): Map<String, Any?> {
    return linkedMapOf(
        "codexHome" to CodexAppServerDefaults.CODEX_HOME,
        "model" to model,
        "baseUrl" to baseUrl,
        "apiKey" to apiKey
    )
}

private fun buildCodexConfigToml(baseUrl: String, model: String): String {
    return """
        model_provider = "omnimind"
        model = ${tomlString(model)}
        model_reasoning_effort = "xhigh"
        disable_response_storage = true

        [model_providers.omnimind]
        name = "omnimind"
        base_url = ${tomlString(baseUrl)}
        wire_api = "responses"
        requires_openai_auth = true
    """.trimIndent() + "\n"
}

private fun extractMarkedBlock(source: String, startMarker: String, endMarker: String): String {
    val start = source.indexOf(startMarker)
    if (start < 0) return ""
    val bodyStart = start + startMarker.length
    val end = source.indexOf(endMarker, bodyStart)
    if (end < 0) return ""
    return source.substring(bodyStart, end).trim()
}

private fun extractTomlString(source: String, key: String): String? {
    if (source.isBlank()) return null
    val escapedKey = Regex.escape(key)
    val pattern = Regex(
        pattern = """(?m)^\s*$escapedKey\s*=\s*"((?:\\.|[^"\\])*)"\s*(?:#.*)?$"""
    )
    return pattern.find(source)?.groupValues?.getOrNull(1)?.let(::unescapeTomlBasicString)
}

private fun extractOpenAiApiKey(source: String): String? {
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        JSONObject(trimmed).optString("OPENAI_API_KEY").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private fun tomlString(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
}

private fun unescapeTomlBasicString(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '\\' || index == value.lastIndex) {
            result.append(char)
            index += 1
            continue
        }
        val escaped = value[index + 1]
        when (escaped) {
            'b' -> result.append('\b')
            't' -> result.append('\t')
            'n' -> result.append('\n')
            'f' -> result.append('\u000C')
            'r' -> result.append('\r')
            '"' -> result.append('"')
            '\\' -> result.append('\\')
            else -> result.append(escaped)
        }
        index += 2
    }
    return result.toString()
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun Map<String, Any?>.stringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.longValue(key: String): Long? {
    val raw = this[key] ?: return null
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}

private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> {
    val raw = this[key] as? Map<*, *> ?: return emptyMap()
    return raw.entries.associate { (entryKey, value) -> entryKey.toString() to value }
}

private fun extractThreadId(value: Any?): String? {
    return extractStringRecursive(
        value = value,
        keys = setOf("threadId", "thread_id"),
        nestedObjectKeys = setOf("thread")
    )
}

private fun extractTurnId(value: Any?): String? {
    val fromTurn = extractStringRecursive(
        value = value,
        keys = setOf("turnId", "turn_id"),
        nestedObjectKeys = setOf("turn")
    )
    if (!fromTurn.isNullOrBlank()) {
        return fromTurn
    }
    val map = value as? Map<*, *> ?: return null
    val turn = map["turn"] as? Map<*, *> ?: return null
    return turn["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun extractThreadTitle(value: Any?): String? {
    val map = value as? Map<*, *> ?: return null
    val params = map["params"] as? Map<*, *>
    val result = map["result"] as? Map<*, *>
    val thread = map["thread"] as? Map<*, *>
    return listOfNotNull(
        map["threadName"],
        map["thread_name"],
        map["name"],
        map["title"],
        params?.get("threadName"),
        params?.get("thread_name"),
        params?.get("name"),
        params?.get("title"),
        result?.get("threadName"),
        result?.get("thread_name"),
        result?.get("name"),
        result?.get("title"),
        thread?.get("name"),
        thread?.get("title"),
        (params?.get("thread") as? Map<*, *>)?.get("name"),
        (result?.get("thread") as? Map<*, *>)?.get("name"),
        (params?.get("thread") as? Map<*, *>)?.get("title"),
        (result?.get("thread") as? Map<*, *>)?.get("title")
    ).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
}

private fun extractStringRecursive(
    value: Any?,
    keys: Set<String>,
    nestedObjectKeys: Set<String>
): String? {
    val map = value as? Map<*, *> ?: return null
    for (key in keys) {
        val direct = map[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (direct != null) {
            return direct
        }
    }
    for (nestedKey in nestedObjectKeys) {
        val nested = map[nestedKey] as? Map<*, *>
        val id = nested?.get("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) {
            return id
        }
        val recursive = extractStringRecursive(nested, keys, nestedObjectKeys)
        if (recursive != null) {
            return recursive
        }
    }
    val params = map["params"] as? Map<*, *>
    val fromParams = extractStringRecursive(params, keys, nestedObjectKeys)
    if (fromParams != null) {
        return fromParams
    }
    val result = map["result"] as? Map<*, *>
    return extractStringRecursive(result, keys, nestedObjectKeys)
}

private fun collectThreadEntries(value: Any?): List<CodexThreadListEntry> {
    val entries = mutableListOf<CodexThreadListEntry>()
    fun visit(current: Any?, parentKey: String? = null) {
        when (current) {
            is List<*> -> current.forEach { visit(it, parentKey) }
            is Map<*, *> -> {
                val threadMap = current["thread"] as? Map<*, *>
                val threadId = threadEntryId(current, threadMap, parentKey)
                if (threadId != null) {
                    val cwd = listOfNotNull(current["cwd"], threadMap?.get("cwd"))
                        .firstNotNullOfOrNull {
                            it?.toString()?.trim()?.takeIf(String::isNotEmpty)
                        }
                    val title = listOfNotNull(
                        current["name"],
                        current["title"],
                        current["threadName"],
                        current["thread_name"],
                        threadMap?.get("name"),
                        threadMap?.get("title")
                    ).firstNotNullOfOrNull {
                        it?.toString()?.trim()?.takeIf(String::isNotEmpty)
                    }
                    val archived = listOfNotNull(
                        current["archived"],
                        current["isArchived"],
                        current["is_archived"],
                        threadMap?.get("archived"),
                        threadMap?.get("isArchived"),
                        threadMap?.get("is_archived")
                    ).firstNotNullOfOrNull(::asBooleanOrNull)
                    entries += CodexThreadListEntry(
                        threadId = threadId,
                        cwd = cwd,
                        title = title,
                        archived = archived
                    )
                }
                current.entries.forEach { (key, nestedValue) ->
                    val nestedKey = key?.toString()
                    if (nestedKey !in THREAD_ITEM_COLLECTION_KEYS) {
                        visit(nestedValue, nestedKey)
                    }
                }
            }
        }
    }
    visit(value)
    return entries.distinctBy { it.threadId }
}

private fun threadEntryId(
    current: Map<*, *>,
    threadMap: Map<*, *>?,
    parentKey: String?
): String? {
    return listOfNotNull(
        current["threadId"],
        current["thread_id"],
        threadMap?.get("id"),
        if (current.looksLikeThreadEntry(threadMap, parentKey)) current["id"] else null
    ).firstNotNullOfOrNull {
        it?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }
}

private fun Map<*, *>.looksLikeThreadEntry(threadMap: Map<*, *>?, parentKey: String?): Boolean {
    if (threadMap != null || containsKey("threadId") || containsKey("thread_id")) {
        return true
    }
    if (!containsKey("id")) {
        return false
    }
    val normalizedParentKey = parentKey?.lowercase().orEmpty()
    if (normalizedParentKey == "thread" || normalizedParentKey == "threads") {
        return true
    }
    val type = this["type"]?.toString()?.trim().orEmpty()
    if (type in CODEX_THREAD_ITEM_TYPES) {
        return false
    }
    return keys.any { key ->
        key?.toString() in THREAD_SUMMARY_KEYS
    }
}

private fun asBooleanOrNull(value: Any?): Boolean? {
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }
}

internal fun resolveCodexReviewTarget(value: Any?): Map<String, Any?> {
    val target = value as? Map<*, *>
    if (target.isNullOrEmpty()) {
        return mapOf("type" to "uncommittedChanges")
    }
    return target.entries.mapNotNull { (key, nestedValue) ->
        val normalizedKey = key?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        normalizedKey to nestedValue
    }.toMap().ifEmpty { mapOf("type" to "uncommittedChanges") }
}

private val THREAD_ITEM_COLLECTION_KEYS = setOf(
    "items",
    "messages",
    "turns"
)

private val THREAD_SUMMARY_KEYS = setOf(
    "cwd",
    "name",
    "title",
    "threadName",
    "thread_name",
    "archived",
    "isArchived",
    "is_archived",
    "sourceKind",
    "source_kind",
    "createdAt",
    "created_at",
    "updatedAt",
    "updated_at",
    "lastActivityAt",
    "last_activity_at"
)

private val CODEX_THREAD_ITEM_TYPES = setOf(
    "agentMessage",
    "reasoning",
    "commandExecution",
    "fileChange",
    "tool",
    "mcpToolCall",
    "userMessage",
    "plan",
    "serverRequest"
)
