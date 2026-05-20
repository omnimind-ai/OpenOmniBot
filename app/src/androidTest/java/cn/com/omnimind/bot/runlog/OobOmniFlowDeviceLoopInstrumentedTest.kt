package cn.com.omnimind.bot.runlog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.DefaultAgentExecutionEnvironment
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.UserDialog
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.tool.handlers.OobFunctionToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OobOmniFlowDeviceLoopInstrumentedTest {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Test
    fun exploredRunLogRegistersRecallsAndReplaysOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspaceRoot = File(context.filesDir, "instrumented_omniflow_workspace")
        workspaceRoot.deleteRecursively()
        val workspaceStore = WorkspaceFunctionStore(workspaceRoot)
        val toolkit = OobOmniFlowToolkitService(
            context = context,
            workspaceFunctionStore = workspaceStore,
        )
        val runId = "device-utg-loop-${System.currentTimeMillis()}"
        val goal = "open network settings"
        val functionId = "oob_device_acceptance_explore_replay_loop"

        try {
            val before = requireNotNull(
                OobOmniFlowExplorer.parseSnapshot(
                    xml = SOURCE_XML,
                    packageName = "com.example.settings",
                    activityName = "SettingsActivity",
                )
            )
            val after = requireNotNull(
                OobOmniFlowExplorer.parseSnapshot(
                    xml = AFTER_XML,
                    packageName = "com.example.settings",
                    activityName = "NetworkActivity",
                )
            )
            val exploredCandidate = OobOmniFlowExplorer.rankCandidates(before, goal).first()
            val terminalCandidate = exploredCandidate.copy(action = "finished")
            val edge = OobOmniFlowExplorer.edgeFor(
                before = before,
                after = after,
                candidate = terminalCandidate,
                stepIndex = 0,
            )
            val card = OobOmniFlowExplorer.buildActionCard(
                stepIndex = 0,
                before = before,
                after = after,
                candidate = terminalCandidate,
                edge = edge,
            )

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = goal,
                source = "oob_native_omniflow_explorer_device_test",
                toolName = "omniflow.explore_replay",
                operationDescription = goal,
            )
            InternalRunLogStore.appendCard(context, runId, card)
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "device_utg_exploration_completed",
            )

            val convert = toolkit.convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to true,
                    "function_id" to functionId,
                    "name" to goal,
                    "description" to goal,
                )
            )
            assertEquals(true, convert["success"])
            assertEquals(functionId, convert["function_id"])
            assertEquals(true, convert["registered"])

            val recall = toolkit.recall(
                mapOf(
                    "goal" to goal,
                    "current_package" to "com.example.settings",
                    "k" to 3,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))

            val call = toolkit.callFunction(
                mapOf(
                    "function_id" to functionId,
                    "goal" to goal,
                )
            )
            assertEquals(true, call["success"])
            assertEquals(false, call["fallback"])
            assertEquals(1, (call["actions_executed"] as Number).toInt())

            val oobResult = call["oob_result"] as? Map<*, *>
            assertNotNull(oobResult)
            assertEquals("oob_omniflow_replay", oobResult?.get("runner"))
            assertEquals(false, oobResult?.get("model_required"))

            val stepResults = call["step_results"] as? List<*>
            assertEquals(1, stepResults?.size)
            val replayedStep = stepResults?.single() as? Map<*, *>
            assertEquals("omniflow", replayedStep?.get("executor"))
            assertEquals("finished", replayedStep?.get("tool"))
            assertEquals(true, replayedStep?.get("success"))

            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            val execution = stored["execution"] as? Map<*, *>
            assertEquals(functionId, stored["function_id"])
            assertEquals(1, (execution?.get("omniflow_step_count") as Number).toInt())
            assertEquals(false, execution["requires_agent_fallback"])
        } finally {
            OobRunLogReplayService(context, workspaceStore).deleteFunction(functionId)
            workspaceRoot.deleteRecursively()
        }
    }

    @Test
    fun recordedRunLogFunctionIsAgentToolAndExecutesChangedParameterOnDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixturePackage = instrumentation.context.packageName
        val workspaceRoot = File(context.filesDir, "instrumented_agent_function_workspace")
        workspaceRoot.deleteRecursively()
        val workspaceStore = WorkspaceFunctionStore(workspaceRoot)
        val service = OobRunLogReplayService(context, workspaceStore)
        val toolkit = OobOmniFlowToolkitService(context, workspaceStore)
        val functionId = "oob_device_agent_callable_recorded_input_function"
        val runId = "device-agent-callable-runlog-${System.currentTimeMillis()}"
        val backend = UiAutomationOmniflowActionBackend(
            instrumentation = instrumentation,
            fixturePackageName = fixturePackage,
        )
        val backendHandle = OmniflowActionRuntime.useBackendForTesting(backend)

        try {
            backend.launchApplication(fixturePackage)
            val sourceXml = requireNotNull(backend.currentXml())
            val inputCenter = centerForContentDescription(sourceXml, "Message input")

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "type message into fixture input",
                source = "oob_native_direct_device_recording",
                toolName = "agent_recorded_input",
                operationDescription = "Type message into fixture input",
            )
            InternalRunLogStore.appendCard(
                context,
                runId,
                runLogCard(
                    toolName = "click",
                    args = mapOf(
                        "target_description" to "Message input",
                        "x" to inputCenter.first,
                        "y" to inputCenter.second,
                    ),
                    beforeXml = sourceXml,
                    beforePackage = fixturePackage,
                )
            )
            backend.click(inputCenter.first, inputCenter.second)
            InternalRunLogStore.appendCard(
                context,
                runId,
                runLogCard(
                    toolName = "input_text",
                    args = mapOf("text" to "hello"),
                    beforeXml = backend.currentXml().orEmpty(),
                    beforePackage = fixturePackage,
                )
            )
            backend.inputTextToFocusedNode("hello")
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "recorded_input_completed",
            )

            val convert = toolkit.convertRunLog(
                mapOf(
                    "run_id" to runId,
                    "register" to true,
                    "function_id" to functionId,
                    "name" to "Recorded fixture input",
                    "description" to "Type into the fixture input from a recorded RunLog",
                )
            )
            assertEquals(true, convert["success"])
            assertEquals(true, convert["registered"])

            val registry = AgentToolRegistry(
                context = context,
                discoveredServers = emptyList(),
            )
            val tool = registry.toolsForModel.singleOrNull { it.function.name == functionId }
            assertNotNull(tool)
            assertEquals("oob_function", registry.runtimeDescriptor(functionId).toolType)

            val schema = tool!!.function.parameters
            val properties = schema["properties"] as JsonObject
            val replacement = properties["input_text"] as JsonObject
            assertEquals("string", replacement["type"]?.jsonPrimitive?.content)
            assertEquals("hello", replacement["default"]?.jsonPrimitive?.content)
            val required = (schema["required"] as JsonArray)
                .map { it.jsonPrimitive.content }
            assertTrue(required.contains("tool_title"))
            assertFalse(required.contains("input_text"))

            val changedArgs = buildJsonObject {
                put("tool_title", JsonPrimitive("Replay recorded input"))
                put("input_text", JsonPrimitive("deviceworld"))
            }
            registry.validateArguments(
                functionId,
                changedArgs,
            )

            val stored = requireNotNull(service.getFunctionSpec(functionId))
            val materialized = OobReusableFunctionStore.materialize(
                stored,
                mapOf("input_text" to "deviceworld"),
            )
            val steps = ((materialized["execution"] as Map<*, *>)["steps"] as List<*>)
            val inputStep = steps.last() as Map<*, *>
            val materializedArgs = inputStep["args"] as Map<*, *>
            assertEquals("deviceworld", materializedArgs["text"])

            backend.launchApplication(fixturePackage)
            val handler = OobFunctionToolHandler(
                context = context,
                helper = SharedHelper(
                    context = context,
                    json = Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = false
                    },
                ),
            ).apply {
                workspaceFunctionStore = workspaceStore
            }
            val toolCall = AssistantToolCall(
                id = "device_direct_toolcall",
                function = AssistantToolCallFunction(
                    name = functionId,
                    arguments = gson.toJson(mapOf("input_text" to "deviceworld")),
                ),
            )
            val result = handler.execute(
                toolCall = toolCall,
                args = changedArgs,
                runtimeDescriptor = registry.runtimeDescriptor(functionId),
                env = directExecutionEnv(context),
                callback = NoOpCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    toolName = functionId,
                    toolCallId = toolCall.id,
                ),
            )
            assertTrue(result is ToolExecutionResult.ContextResult)
            val contextResult = result as ToolExecutionResult.ContextResult
            assertEquals(contextResult.rawResultJson, true, contextResult.success)
            val replayedXml = backend.currentXml().orEmpty()
            assertTrue(replayedXml, replayedXml.contains("deviceworld"))
        } finally {
            backendHandle.close()
            service.deleteFunction(functionId)
            workspaceRoot.deleteRecursively()
        }
    }

    @Test
    fun mcpJsonRpcRunsOmniFlowLoopThroughDeviceServer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val port = 18_899
        var functionId = ""
        val goal = "open network settings device mcp ${System.currentTimeMillis()}"
        try {
            val state = McpServerManager.setEnabled(context, true, port)
            assertTrue(state.running)
            assertEquals(port, state.port)
            assertTrue(state.token.isNotBlank())

            val tools = rpcCall(
                port = port,
                token = state.token,
                id = 1,
                method = "tools/list",
            )
            val toolNames = (((tools["result"] as? Map<*, *>)?.get("tools") as? List<*>)
                ?: emptyList<Any?>())
                .mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }
                .toSet()
            assertTrue(toolNames.contains("omniflow.ingest_run_log"))
            assertTrue(toolNames.contains("omniflow.recall"))
            assertTrue(toolNames.contains("omniflow.call_tool"))

            val ingest = toolResult(
                rpcCall(
                    port = port,
                    token = state.token,
                    id = 2,
                    method = "tools/call",
                    params = mapOf(
                            "name" to "omniflow.ingest_run_log",
                            "arguments" to mapOf(
                            "run_log" to inlineRunLog(goal)
                        )
                    )
                )
            )
            assertEquals(true, ingest["success"])
            functionId = ingest["function_id"]?.toString().orEmpty()
            assertTrue(functionId.isNotBlank())
            assertEquals("created", ingest["status"])

            val recall = toolResult(
                rpcCall(
                    port = port,
                    token = state.token,
                    id = 3,
                    method = "tools/call",
                    params = mapOf(
                        "name" to "omniflow.recall",
                        "arguments" to mapOf(
                            "goal" to goal,
                            "current_package" to "com.example.settings",
                            "k" to 3,
                        )
                    )
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))

            val call = toolResult(
                rpcCall(
                    port = port,
                    token = state.token,
                    id = 4,
                    method = "tools/call",
                    params = mapOf(
                        "name" to "omniflow.call_tool",
                        "arguments" to mapOf(
                            "function_id" to functionId,
                            "goal" to goal,
                        )
                    )
                )
            )
            assertEquals(true, call["success"])
            assertEquals(false, call["fallback"])
            assertEquals(1, (call["actions_executed"] as Number).toInt())
            val oobResult = call["oob_result"] as? Map<*, *>
            assertEquals("oob_omniflow_replay", oobResult?.get("runner"))
            assertEquals(false, oobResult?.get("model_required"))
        } finally {
            if (functionId.isNotBlank()) {
                OobRunLogReplayService(context).deleteFunction(functionId)
            }
            McpServerManager.stopServer()
        }
    }

    @Test
    fun uiAutomationExploreReplayRunsFullLoopOnRealDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixturePackage = instrumentation.context.packageName
        val workspaceRoot = File(context.filesDir, "instrumented_uiautomation_omniflow_workspace")
        workspaceRoot.deleteRecursively()
        val workspaceStore = WorkspaceFunctionStore(workspaceRoot)
        val toolkit = OobOmniFlowToolkitService(context, workspaceStore)
        val functionId = "oob_device_uiautomation_explore_replay"
        val backend = UiAutomationOmniflowActionBackend(
            instrumentation = instrumentation,
            fixturePackageName = fixturePackage,
        )
        val backendHandle = OmniflowActionRuntime.useBackendForTesting(backend)

        try {
            val result = toolkit.exploreAndReplay(
                mapOf(
                    "goal" to "open network settings",
                    "package_name" to fixturePackage,
                    "max_steps" to 1,
                    "settle_delay_ms" to 600,
                    "stop_text" to "Internet",
                    "function_id" to functionId,
                    "name" to "Open fixture network settings",
                    "description" to "Open the fixture network settings page",
                    "replay" to true,
                    "reset_before_replay" to true,
                    "reset_back_steps" to 1,
                    "allow_risky_actions" to false,
                )
            )

            assertEquals(gson.toJson(result), true, result["success"])
            assertEquals("replayed", result["phase"])
            assertEquals(functionId, result["function_id"])
            val utg = result["utg"] as? Map<*, *>
            assertNotNull(utg)
            assertEquals("oob.omniflow_utg.v1", utg?.get("schema_version"))
            assertTrue((utg?.get("node_count") as Number).toInt() >= 1)
            assertTrue((utg["edge_count"] as Number).toInt() >= 1)

            val explore = result["explore"] as? Map<*, *>
            assertEquals(true, explore?.get("success"))
            val rawExplore = explore?.get("explore") as? Map<*, *>
            assertEquals(1, (rawExplore?.get("step_count") as Number).toInt())

            val replay = result["replay"] as? Map<*, *>
            assertEquals(true, replay?.get("success"))
            assertEquals(false, replay?.get("fallback"))
            assertEquals(1, (replay?.get("actions_executed") as Number).toInt())
            val replaySteps = replay["step_results"] as? List<*>
            val replayedStep = replaySteps?.single() as? Map<*, *>
            assertEquals("omniflow", replayedStep?.get("executor"))
            assertEquals("click", replayedStep?.get("tool"))

            val recall = toolkit.recall(
                mapOf(
                    "goal" to "open network settings",
                    "current_package" to fixturePackage,
                    "k" to 3,
                )
            )
            assertEquals(true, recall["success"])
            assertEquals("hit", recall["decision"])
            val hit = recall["hit"] as? Map<*, *>
            assertEquals(functionId, hit?.get("function_id"))

            val stored = toolkit.getFunction(mapOf("function_id" to functionId))
            val execution = stored["execution"] as? Map<*, *>
            assertEquals(functionId, stored["function_id"])
            assertEquals(1, (execution?.get("omniflow_step_count") as Number).toInt())
            assertEquals(false, execution["requires_agent_fallback"])
        } finally {
            backendHandle.close()
            OobRunLogReplayService(context, workspaceStore).deleteFunction(functionId)
            workspaceRoot.deleteRecursively()
        }
    }

    @Test
    fun liveSettingsExploreReplayCapturesUtgAndReplaysOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rebindAccessibilityServiceForInstrumentedProcess()
        assumeTrue(
            "Accessibility service is enabled but not bound on this device; live Settings exploration is covered by external MCP acceptance when the service binds.",
            waitForAccessibilityController()
        )
        val workspaceRoot = File(context.filesDir, "instrumented_live_omniflow_workspace")
        workspaceRoot.deleteRecursively()
        val workspaceStore = WorkspaceFunctionStore(workspaceRoot)
        val toolkit = OobOmniFlowToolkitService(context, workspaceStore)
        val functionId = "oob_device_live_settings_explore_replay"

        try {
            val result = toolkit.exploreAndReplay(
                mapOf(
                    "goal" to "network settings",
                    "package_name" to "com.android.settings",
                    "max_steps" to 1,
                    "settle_delay_ms" to 1_200,
                    "function_id" to functionId,
                    "name" to "Network settings",
                    "description" to "Open network settings from Android Settings",
                    "replay" to true,
                    "reset_before_replay" to true,
                    "reset_back_steps" to 1,
                    "allow_risky_actions" to false,
                )
            )

            assertEquals(true, result["success"])
            assertEquals("replayed", result["phase"])
            assertEquals(functionId, result["function_id"])
            val utg = result["utg"] as? Map<*, *>
            assertNotNull(utg)
            assertTrue((utg?.get("node_count") as Number).toInt() >= 1)
            assertTrue((utg["edge_count"] as Number).toInt() >= 1)
            val replay = result["replay"] as? Map<*, *>
            assertEquals(true, replay?.get("success"))
            assertEquals(false, replay?.get("fallback"))
            assertTrue(((replay?.get("actions_executed") as? Number)?.toInt() ?: 0) >= 1)
        } finally {
            OobRunLogReplayService(context, workspaceStore).deleteFunction(functionId)
            workspaceRoot.deleteRecursively()
        }
    }

    private fun rpcCall(
        port: Int,
        token: String,
        id: Int,
        method: String,
        params: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
        )
        if (params.isNotEmpty()) payload["params"] = params
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/mcp")
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue("HTTP ${response.code}: $body", response.isSuccessful)
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(body, Map::class.java) as Map<String, Any?>
        }
    }

    private fun toolResult(response: Map<String, Any?>): Map<String, Any?> {
        assertFalse(response.containsKey("error"))
        @Suppress("UNCHECKED_CAST")
        return requireNotNull(response["result"] as? Map<String, Any?>)
    }

    private fun runLogCard(
        toolName: String,
        args: Map<String, Any?>,
        beforeXml: String,
        beforePackage: String,
    ): Map<String, Any?> = linkedMapOf(
        "tool_name" to toolName,
        "args" to args,
        "success" to true,
        "before" to linkedMapOf(
            "package_name" to beforePackage,
            "observation_xml" to beforeXml,
        ),
    )

    private fun centerForContentDescription(xml: String, contentDescription: String): Pair<Float, Float> {
        val pattern = Regex(
            "<node[^>]*content-desc=\"${Regex.escape(contentDescription)}\"[^>]*" +
                "bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\""
        )
        val match = pattern.find(xml)
            ?: error("Node not found for content-desc=$contentDescription in $xml")
        val left = match.groupValues[1].toFloat()
        val top = match.groupValues[2].toFloat()
        val right = match.groupValues[3].toFloat()
        val bottom = match.groupValues[4].toFloat()
        return ((left + right) / 2f) to ((top + bottom) / 2f)
    }

    private fun directExecutionEnv(
        context: android.content.Context,
    ): DefaultAgentExecutionEnvironment {
        val workspaceManager = AgentWorkspaceManager(context)
        val agentRunId = "device-direct-toolcall-${System.currentTimeMillis()}"
        return DefaultAgentExecutionEnvironment(
            agentRunId = agentRunId,
            userMessage = "direct OOB function toolcall",
            currentPackageName = null,
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            workspaceDescriptor = workspaceManager.buildWorkspaceDescriptor(
                conversationId = null,
                agentRunId = agentRunId,
            ),
            resolvedSkills = emptyList(),
            workspaceManager = workspaceManager,
            workspaceMemoryService = WorkspaceMemoryService(context, workspaceManager),
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            runControl = NoOpAgentRunControl,
        )
    }

    private object NoOpCallback : AgentCallback {
        override suspend fun onThinkingStart() = Unit

        override suspend fun onThinkingUpdate(thinking: String) = Unit

        override suspend fun onToolCallStart(
            toolName: String,
            toolCallId: String,
            arguments: JsonObject,
        ) = Unit

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>,
        ) = Unit

        override suspend fun onToolCallComplete(
            toolName: String,
            result: ToolExecutionResult,
        ) = Unit

        override suspend fun onChatMessage(message: String) = Unit

        override suspend fun onClarifyRequired(
            question: String,
            missingFields: List<String>?,
            dialog: UserDialog?,
        ) = Unit

        override suspend fun onComplete(result: AgentResult) = Unit

        override suspend fun onError(error: String) = Unit

        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }

    private suspend fun waitForAccessibilityController(timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AccessibilityController.initController()) return true
            delay(250)
        }
        return AccessibilityController.initController()
    }

    private suspend fun rebindAccessibilityServiceForInstrumentedProcess() {
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
        delay(500)
        shell(
            "settings put secure enabled_accessibility_services " +
                "cn.com.omnimind.bot.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        )
        shell("settings put secure accessibility_enabled 1")
        delay(1_000)
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return try {
            FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            runCatching { fd.close() }
        }
    }

    private fun inlineRunLog(goal: String): Map<String, Any?> {
        return mapOf(
            "run_id" to "device-mcp-inline-${System.currentTimeMillis()}",
            "goal" to goal,
            "source" to "device_mcp_jsonrpc_test",
            "tool_name" to "omniflow.explore_replay",
            "operation_description" to goal,
            "success" to true,
            "result" to mapOf("success" to true),
            "cards" to listOf(
                mapOf(
                    "tool_name" to "finished",
                    "title" to "Open network settings",
                    "summary" to "Terminal deterministic OmniFlow step",
                    "success" to true,
                    "args" to emptyMap<String, Any?>(),
                )
            ),
        )
    }

    companion object {
        private const val SOURCE_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Network" content-desc="" resource-id="android:id/network" clickable="true" enabled="true" visible-to-user="true" bounds="[40,200][1040,320]" />
              <node index="1" package="com.example.settings" class="android.widget.TextView" text="Delete account" content-desc="" resource-id="android:id/delete" clickable="true" enabled="true" visible-to-user="true" bounds="[40,360][1040,480]" />
            </hierarchy>
        """

        private const val AFTER_XML = """
            <hierarchy bounds="[0,0][1080,1920]">
              <node index="0" package="com.example.settings" class="android.widget.TextView" text="Internet" content-desc="" resource-id="android:id/internet" clickable="true" enabled="true" visible-to-user="true" bounds="[40,200][1040,320]" />
            </hierarchy>
        """
    }
}
