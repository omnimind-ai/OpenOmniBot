package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentEventAdapter
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentLlmClient
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentToolExposurePolicy
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentToolRouter
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.DefaultAgentExecutionEnvironment
import cn.com.omnimind.bot.agent.NoOpAgentCallback
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.StreamingToolCallSnapshot
import cn.com.omnimind.bot.agent.SubagentDispatcher
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.runlog.RunLogPagePackageInference
import cn.com.omnimind.bot.util.AssistsUtil
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class DebugAgentFunctionManagementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val targetPackage = intent?.getStringExtra("targetPackage")
            ?: intent?.getStringExtra("target_package")
            ?: DEFAULT_TARGET_PACKAGE
        val functionId = intent?.getStringExtra("functionId")
            ?: intent?.getStringExtra("function_id")
            ?: DEFAULT_FUNCTION_ID
        val name = intent.decodeBase64Extra("nameBase64")
            ?: intent?.getStringExtra("name")
            ?: "Debug open ${targetPackage.substringAfterLast('.')}"
        val description = intent.decodeBase64Extra("descriptionBase64")
            ?: intent?.getStringExtra("description")
            ?: "Debug validation Function registered through AgentToolRouter."
        val shouldRun = intent?.getBooleanExtra("run", true) ?: true
        val deleteBefore = intent?.getBooleanExtra("deleteBefore", true) ?: true

        scope.launch {
            val result = runCatching {
                runValidation(
                    context = appContext,
                    functionId = functionId,
                    name = name,
                    description = description,
                    targetPackage = targetPackage,
                    shouldRun = shouldRun,
                    deleteBefore = deleteBefore,
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "error_message" to error.message.orEmpty(),
                    "error_type" to error.javaClass.name,
                )
            }
            val jsonText = gson.toJson(result)
            File(appContext.filesDir, RESULT_FILE).writeText(jsonText)
            OmniLog.i(TAG, jsonText)
        }
    }

    private suspend fun runValidation(
        context: Context,
        functionId: String,
        name: String,
        description: String,
        targetPackage: String,
        shouldRun: Boolean,
        deleteBefore: Boolean,
    ): Map<String, Any?> {
        waitForRuntime(context)
        val currentBefore = currentEffectivePackage(context)
        val workspaceManager = AgentWorkspaceManager(context)
        val workspace = workspaceManager.buildWorkspaceDescriptor(
            conversationId = null,
            agentRunId = "debug-agent-function-management",
        )
        val registry = AgentToolRegistry(
            context = context,
            discoveredServers = emptyList(),
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            toolExposurePolicy = AgentToolExposurePolicy(
                profile = AgentToolExposurePolicy.PROFILE_FUNCTION_MANAGEMENT,
            ),
        )
        lateinit var router: AgentToolRouter
        val eventJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
        val subagentDispatcher = SubagentDispatcher(
            llmClient = object : AgentLlmClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                    onContentUpdate: (suspend (String) -> Unit)?,
                    onToolCallUpdate: (suspend (StreamingToolCallSnapshot) -> Unit)?,
                ): ChatCompletionTurn {
                    error("Subagent dispatch is not part of debug Function management validation")
                }
            },
            toolExecutorProvider = { router },
            parentCatalogProvider = { registry },
            eventAdapter = AgentEventAdapter(eventJson),
            model = "debug-no-model",
        )
        router = AgentToolRouter(
            context = context,
            scope = scope,
            scheduleToolBridge = NoOpScheduleBridge,
            workspaceManager = workspaceManager,
            subagentDispatcher = subagentDispatcher,
        )
        val env = DefaultAgentExecutionEnvironment(
            agentRunId = "debug-agent-function-management",
            userMessage = "Validate OOB Function registration, listing, guard, and run through Agent tools.",
            currentPackageName = currentBefore,
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            workspaceDescriptor = workspace,
            resolvedSkills = emptyList(),
            workspaceManager = workspaceManager,
            workspaceMemoryService = WorkspaceMemoryService(context, workspaceManager),
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            runControl = NoOpAgentRunControl,
        )

        val exposedTools = registry.toolsForModel.map { it.function.name }.sorted()
        val expectedTools = AgentToolExposurePolicy.FUNCTION_MANAGEMENT_TOOLS.sorted()
        val missingTools = expectedTools.filterNot { it in exposedTools }
        val unexpectedTools = exposedTools.filterNot { it in expectedTools }
        val records = mutableListOf<Map<String, Any?>>()

        if (deleteBefore) {
            records += executeAgentTool(
                registry = registry,
                router = router,
                env = env,
                toolName = "oob_function_delete",
                args = buildJsonObject {
                    put("function_id", JsonPrimitive(functionId))
                },
                callback = NoOpAgentCallback,
            )
        }

        records += executeAgentTool(
            registry = registry,
            router = router,
            env = env,
            toolName = "oob_function_register",
            args = buildJsonObject {
                put("functionId", JsonPrimitive(functionId))
                put("name", JsonPrimitive(name))
                put("description", JsonPrimitive(description))
                put("packageName", JsonPrimitive(targetPackage))
                put("disable_current_page_capture", JsonPrimitive(true))
                put(
                    "steps",
                    JsonArray(
                        listOf(
                            mapToJson(
                                linkedMapOf(
                                    "action" to "open_app",
                                    "packageName" to targetPackage,
                                    "title" to "Open $targetPackage",
                                )
                            ),
                            mapToJson(
                                linkedMapOf(
                                    "action" to "finished",
                                    "content" to "$targetPackage opened",
                                )
                            ),
                        )
                    )
                )
            },
            callback = NoOpAgentCallback,
        )
        records += executeAgentTool(
            registry = registry,
            router = router,
            env = env,
            toolName = "oob_function_list",
            args = buildJsonObject {},
            callback = NoOpAgentCallback,
        )
        records += executeAgentTool(
            registry = registry,
            router = router,
            env = env,
            toolName = "oob_function_guard_check",
            args = buildJsonObject {
                put("functionId", JsonPrimitive(functionId))
            },
            callback = NoOpAgentCallback,
        )
        if (shouldRun) {
            records += executeAgentTool(
                registry = registry,
                router = router,
                env = env,
                toolName = "oob_function_run",
                args = buildJsonObject {
                    put("functionId", JsonPrimitive(functionId))
                    put("confirmed", JsonPrimitive(true))
                    put("executionMode", JsonPrimitive("foreground"))
                },
                callback = NoOpAgentCallback,
            )
            delay(500L)
        }

        val currentAfter = currentEffectivePackage(context)
        val registerRecord = records.lastOrNull { it["tool_name"] == "oob_function_register" }
        val listRecord = records.lastOrNull { it["tool_name"] == "oob_function_list" }
        val guardRecord = records.lastOrNull { it["tool_name"] == "oob_function_guard_check" }
        val runRecord = records.lastOrNull { it["tool_name"] == "oob_function_run" }
        val listContainsFunction = recordPayload(listRecord)["functions"].let { raw ->
            (raw as? List<*>)?.any { item ->
                (item as? Map<*, *>)?.get("function_id")?.toString() == functionId
            } == true
        }
        val guardDecision = recordPayload(guardRecord)["decision"]?.toString().orEmpty()
        val foregroundMatched = !shouldRun || currentAfter == targetPackage
        val success = missingTools.isEmpty() &&
            unexpectedTools.isEmpty() &&
            recordSucceeded(registerRecord) &&
            listContainsFunction &&
            recordSucceeded(guardRecord) &&
            guardDecision == "allow" &&
            (!shouldRun || recordSucceeded(runRecord)) &&
            foregroundMatched

        return linkedMapOf<String, Any?>(
            "success" to success,
            "source" to "debug_agent_tool_registry_router_function_management",
            "agent_path" to "AgentToolRegistry -> AgentToolRouter -> WorkbenchToolHandler",
            "function_id" to functionId,
            "target_package" to targetPackage,
            "run_requested" to shouldRun,
            "current_package_before" to currentBefore,
            "current_package_after" to currentAfter,
            "foreground_package_matched" to foregroundMatched,
            "tool_profile" to AgentToolExposurePolicy.PROFILE_FUNCTION_MANAGEMENT,
            "exposed_tools" to exposedTools,
            "missing_tools" to missingTools,
            "unexpected_tools" to unexpectedTools,
            "register_success" to recordSucceeded(registerRecord),
            "list_contains_function" to listContainsFunction,
            "guard_decision" to guardDecision,
            "run_success" to if (shouldRun) recordSucceeded(runRecord) else null,
            "records" to records,
        ).filterValues { it != null }
    }

    private suspend fun executeAgentTool(
        registry: AgentToolRegistry,
        router: AgentToolRouter,
        env: AgentExecutionEnvironment,
        toolName: String,
        args: JsonObject,
        callback: AgentCallback,
    ): Map<String, Any?> {
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            registry.validateArguments(toolName, args)
            val call = AssistantToolCall(
                id = "$toolName-$startedAt",
                function = AssistantToolCallFunction(
                    name = toolName,
                    arguments = args.toString(),
                ),
            )
            val handle = NoOpAgentRunControl.beginToolExecution(toolName, call.id)
            router.execute(
                toolCall = call,
                args = args,
                runtimeDescriptor = registry.runtimeDescriptor(toolName),
                env = env,
                callback = callback,
                toolHandle = handle,
            )
        }.getOrElse { error ->
            ToolExecutionResult.Error(toolName, error.message ?: error.javaClass.name)
        }
        val finishedAt = System.currentTimeMillis()
        return toolRecord(
            toolName = toolName,
            args = args,
            result = result,
            startedAtMs = startedAt,
            finishedAtMs = finishedAt,
        )
    }

    private fun toolRecord(
        toolName: String,
        args: JsonObject,
        result: ToolExecutionResult,
        startedAtMs: Long,
        finishedAtMs: Long,
    ): Map<String, Any?> {
        val rawResult = when (result) {
            is ToolExecutionResult.ContextResult -> result.rawResultJson
            is ToolExecutionResult.McpResult -> result.rawResultJson
            is ToolExecutionResult.MemoryResult -> result.rawResultJson
            is ToolExecutionResult.TerminalResult -> result.rawResultJson
            is ToolExecutionResult.ScheduleResult -> result.previewJson
            is ToolExecutionResult.Interrupted -> result.rawResultJson
            else -> ""
        }
        val payload = parseJsonMap(rawResult)
        val success = when (result) {
            is ToolExecutionResult.ContextResult -> result.success
            is ToolExecutionResult.McpResult -> result.success
            is ToolExecutionResult.MemoryResult -> result.success
            is ToolExecutionResult.TerminalResult -> result.success
            is ToolExecutionResult.ScheduleResult -> result.success
            is ToolExecutionResult.Interrupted -> false
            is ToolExecutionResult.Error -> false
            else -> true
        } && payload["success"] != false
        val summary = when (result) {
            is ToolExecutionResult.ContextResult -> result.summaryText
            is ToolExecutionResult.McpResult -> result.summaryText
            is ToolExecutionResult.MemoryResult -> result.summaryText
            is ToolExecutionResult.TerminalResult -> result.summaryText
            is ToolExecutionResult.ScheduleResult -> result.summaryText
            is ToolExecutionResult.Interrupted -> result.summaryText
            is ToolExecutionResult.Error -> result.message
            is ToolExecutionResult.ChatMessage -> result.message
            is ToolExecutionResult.Clarify -> result.question
            is ToolExecutionResult.PermissionRequired -> result.missing.joinToString(",")
            is ToolExecutionResult.VlmTaskStarted -> result.goal
        }
        return linkedMapOf<String, Any?>(
            "tool_name" to toolName,
            "success" to success,
            "summary" to summary,
            "result_type" to result.javaClass.simpleName,
            "duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0L),
            "started_at_ms" to startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "args" to parseJsonMap(args.toString()),
            "payload" to payload,
            "raw_result_json" to rawResult.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
    }

    private suspend fun waitForRuntime(context: Context) {
        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(context)
        }
        repeat(RUNTIME_OBSERVE_ATTEMPTS) {
            if (AssistsService.instance != null && AccessibilityController.initController()) {
                return
            }
            delay(RUNTIME_OBSERVE_INTERVAL_MS)
        }
        error("OOB accessibility service is not bound")
    }

    private suspend fun currentEffectivePackage(context: Context): String {
        val xml = currentXml()
        val rawPackage = currentPackageName()
        return RunLogPagePackageInference.effectivePackage(rawPackage, xml)
            .ifBlank { rawPackage }
            .takeUnless { it == context.packageName || it.startsWith("cn.com.omnimind.") }
            .orEmpty()
    }

    private suspend fun currentXml(): String =
        runCatching {
            if (AccessibilityController.initController()) {
                withContext(Dispatchers.Main.immediate) {
                    AccessibilityController.getCaptureScreenShotXml(true)
                }
            } else {
                null
            }
        }.getOrNull()?.trim().orEmpty()

    private suspend fun currentPackageName(): String =
        runCatching {
            if (AccessibilityController.initController()) {
                withContext(Dispatchers.Main.immediate) {
                    AccessibilityController.getPackageName()
                }
            } else {
                null
            }
        }.getOrNull()?.trim().orEmpty()

    private fun recordSucceeded(record: Map<String, Any?>?): Boolean =
        record?.get("success") == true

    @Suppress("UNCHECKED_CAST")
    private fun recordPayload(record: Map<String, Any?>?): Map<String, Any?> =
        record?.get("payload") as? Map<String, Any?> ?: emptyMap()

    private fun parseJsonMap(raw: String): Map<String, Any?> =
        runCatching {
            if (raw.isBlank()) emptyMap()
            else gson.fromJson<Map<String, Any?>>(raw, mapType) ?: emptyMap()
        }.getOrDefault(emptyMap())

    private fun mapToJson(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJson(item)
                }
            )
            is List<*> -> JsonArray(value.map(::mapToJson))
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private object NoOpScheduleBridge : AgentScheduleToolBridge {
        override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> =
            unsupported()

        override suspend fun listTasks(): List<Map<String, Any?>> = emptyList()

        override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> =
            unsupported()

        override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> =
            unsupported()

        private fun unsupported(): Map<String, Any?> =
            mapOf("success" to false, "error" to "schedule tools are not part of this validation")
    }

    companion object {
        private const val TAG = "DebugAgentFunctionManagement"
        private const val RESULT_FILE = "debug-agent-function-management-result.json"
        private const val DEFAULT_FUNCTION_ID = "debug_agent_function_management_open_settings"
        private const val DEFAULT_TARGET_PACKAGE = "com.android.settings"
        private const val RUNTIME_OBSERVE_ATTEMPTS = 50
        private const val RUNTIME_OBSERVE_INTERVAL_MS = 200L
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
