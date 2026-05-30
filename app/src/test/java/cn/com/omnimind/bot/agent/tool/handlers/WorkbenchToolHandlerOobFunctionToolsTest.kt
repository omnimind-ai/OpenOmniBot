package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentEventAdapter
import cn.com.omnimind.bot.agent.AgentLlmClient
import cn.com.omnimind.bot.agent.AgentOrchestrator
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentToolCatalog
import cn.com.omnimind.bot.agent.AgentToolExposurePolicy
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentToolRouter
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.NoOpAgentCallback
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.agent.StreamingToolCallSnapshot
import cn.com.omnimind.bot.agent.SubagentDispatcher
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.runlog.OmniflowActionBackend
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.bot.runlog.OobRunLogReplayService
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchToolHandlerOobFunctionToolsTest {
    @Test
    fun `agent workbench handler registers simple oob functions without full spec`() = runBlocking {
        val context = TempFilesContext()
        try {
            val helper = SharedHelper(
                context = context,
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                },
            )
            val handler = WorkbenchToolHandler(helper)
            val env = FakeEnv(context)
            val functionId = "agent_simple_open_settings"

            val register = handler.execute(
                toolCall = toolCall("oob_function_register"),
                args = buildJsonObject {
                    put("functionId", JsonPrimitive(functionId))
                    put("name", JsonPrimitive("Open Settings"))
                    put("description", JsonPrimitive("Launch Android Settings"))
                    put("steps", mapToJson(listOf(
                        mapOf(
                            "action" to "open_app",
                            "packageName" to "com.android.settings",
                        ),
                        mapOf(
                            "action" to "finished",
                            "content" to "Settings opened",
                        ),
                    )))
                },
                runtimeDescriptor = descriptor("oob_function_register"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_function_register",
                    "register-simple",
                ),
            )
            assertContextSuccess(register)
            val registerPayload = payloadObject(register)
            assertEquals(
                "simple",
                registerPayload["registration_input_mode"]?.jsonPrimitive?.contentOrNull,
            )

            val stored = OobRunLogReplayService(context).getFunctionSpec(functionId)
            assertNotNull(stored)
            assertEquals("oob.reusable_function.v1", stored?.get("schema_version"))
            val execution = stored?.get("execution") as? Map<*, *>
            assertEquals(2, (execution?.get("step_count") as Number).toInt())
            assertEquals(false, execution["requires_agent_fallback"])

            val guard = handler.execute(
                toolCall = toolCall("oob_function_guard_check"),
                args = buildJsonObject {
                    put("functionId", JsonPrimitive(functionId))
                },
                runtimeDescriptor = descriptor("oob_function_guard_check"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_function_guard_check",
                    "guard",
                ),
            )
            assertContextSuccess(guard)
            val guardPayload = payloadObject(guard)
            assertEquals("allow", guardPayload["decision"]?.jsonPrimitive?.contentOrNull)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `agent workbench handler registers lists and deletes explicit oob functions`() = runBlocking {
        val context = TempFilesContext()
        try {
            val helper = SharedHelper(
                context = context,
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                },
            )
            val handler = WorkbenchToolHandler(helper)
            val env = FakeEnv(context)
            val functionId = "agent_managed_function"
            val spec = functionSpec(functionId)

            val register = handler.execute(
                toolCall = toolCall("oob_function_register"),
                args = buildJsonObject {
                    put("functionSpec", mapToJson(spec))
                },
                runtimeDescriptor = descriptor("oob_function_register"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_function_register",
                    "register",
                ),
            )
            assertContextSuccess(register)
            assertNotNull(OobRunLogReplayService(context).getFunctionSpec(functionId))

            val list = handler.execute(
                toolCall = toolCall("oob_function_list"),
                args = buildJsonObject {},
                runtimeDescriptor = descriptor("oob_function_list"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("oob_function_list", "list"),
            )
            val listPayload = payloadObject(list)
            assertEquals(true, listPayload["success"]?.jsonPrimitive?.booleanOrNull)
            assertTrue(
                listPayload["functions"]!!.jsonArray.any { raw ->
                    raw.jsonObject["function_id"]?.jsonPrimitive?.contentOrNull == functionId
                }
            )

            val delete = handler.execute(
                toolCall = toolCall("oob_function_delete"),
                args = buildJsonObject {
                    put("function_id", JsonPrimitive(functionId))
                },
                runtimeDescriptor = descriptor("oob_function_delete"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_function_delete",
                    "delete",
                ),
            )
            assertContextSuccess(delete)
            assertEquals(null, OobRunLogReplayService(context).getFunctionSpec(functionId))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `agent converts manual recording runlog enhances parameter and replays function from chat tool`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend()
        try {
            val helper = SharedHelper(
                context = context,
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                },
            )
            val handler = WorkbenchToolHandler(helper)
            val env = FakeEnv(context)
            val runId = "run-chat-replay-${System.nanoTime()}"
            val functionId = "agent_chat_replay_contact_name"

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "填写联系人姓名 Alice",
                source = "agent_tool",
                toolName = "vlm_task",
                operationDescription = "填写联系人姓名 Alice",
            )
            InternalRunLogStore.appendCards(
                context = context,
                runId = runId,
                cards = listOf(
                    manualRecordingRunLogCard(
                        toolName = "input_text",
                        args = mapOf(
                            "target_description" to "First name",
                            "content" to "Alice",
                            "x" to 300,
                            "y" to 420,
                        ),
                        title = "填写 First name",
                    ),
                    manualRecordingRunLogCard(
                        toolName = "finished",
                        args = mapOf("content" to "姓名已填写"),
                        title = "完成",
                    ),
                ),
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished",
            )

            val convert = handler.execute(
                toolCall = toolCall("oob_run_log_convert"),
                args = buildJsonObject {
                    put("run_id", JsonPrimitive(runId))
                    put("register", JsonPrimitive(true))
                    put("function_id", JsonPrimitive(functionId))
                    put("name", JsonPrimitive("填写联系人姓名"))
                    put("description", JsonPrimitive("复用录制轨迹填写联系人姓名"))
                },
                runtimeDescriptor = descriptor("oob_run_log_convert"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_run_log_convert",
                    "convert",
                ),
            )
            assertContextSuccess(convert)
            val convertPayload = payloadObject(convert)
            assertEquals(functionId, convertPayload["function_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals(true, convertPayload["registered"]?.jsonPrimitive?.booleanOrNull)
            val convertedSpec = convertPayload["function_spec"]!!.jsonObject
            val convertedExecution = convertedSpec["execution"]!!.jsonObject
            val convertedSteps = convertedExecution["steps"]!!.jsonArray
            assertEquals(
                1,
                convertPayload["run_log_binding_count"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
            )
            assertEquals("open_app", convertedSteps[0].jsonObject["tool"]?.jsonPrimitive?.contentOrNull)
            assertEquals("input_text", convertedSteps[1].jsonObject["tool"]?.jsonPrimitive?.contentOrNull)
            assertEquals("omniflow", convertedSteps[1].jsonObject["executor"]?.jsonPrimitive?.contentOrNull)

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            assertEquals(true, timeline["registered_as_function"])
            assertEquals(functionId, timeline["registered_function_id"])

            val update = handler.execute(
                toolCall = toolCall("update_function"),
                args = buildJsonObject {
                    put("functionId", JsonPrimitive(functionId))
                    put("patch", mapToJson(
                        mapOf(
                            "parameters" to listOf(
                                mapOf(
                                    "name" to "contact_name",
                                    "type" to "string",
                                    "required" to true,
                                    "description" to "联系人姓名",
                                    "bindings" to listOf(
                                        "$.execution.steps[1].args.content",
                                        "$.execution.steps[1].args.text",
                                    ),
                                ),
                            ),
                            "name" to "填写联系人姓名",
                            "description" to "使用录制轨迹填写一个可变联系人姓名",
                        )
                    ))
                },
                runtimeDescriptor = descriptor("update_function"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "update_function",
                    "enhance",
                ),
            )
            assertContextSuccess(update)
            val updatePayload = payloadObject(update)
            assertEquals(true, updatePayload["saved"]?.jsonPrimitive?.booleanOrNull)

            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val registry = AgentToolRegistry(
                    context = context,
                    discoveredServers = emptyList(),
                    toolExposurePolicy = AgentToolExposurePolicy(
                        profile = AgentToolExposurePolicy.PROFILE_FUNCTION_MANAGEMENT,
                    ),
                )
                val modelTool = registry.toolsForModel.singleOrNull {
                    it.function.name == functionId
                }
                assertNotNull(modelTool)
                assertEquals("oob_function", registry.runtimeDescriptor(functionId).toolType)
                val schema = modelTool!!.function.parameters
                val properties = schema["properties"] as JsonObject
                assertNotNull(properties["contact_name"])
                val required = (schema["required"] as JsonArray)
                    .map { it.jsonPrimitive.content }
                assertTrue(required.contains("contact_name"))

                val run = handler.execute(
                    toolCall = toolCall("oob_function_run"),
                    args = buildJsonObject {
                        put("functionId", JsonPrimitive(functionId))
                        put("arguments", buildJsonObject {
                            put("contact_name", JsonPrimitive("Bob"))
                        })
                    },
                    runtimeDescriptor = descriptor("oob_function_run"),
                    env = env,
                    callback = NoOpAgentCallback,
                    toolHandle = NoOpAgentRunControl.beginToolExecution(
                        "oob_function_run",
                        "run",
                    ),
                )
                assertContextSuccess(run)
                val runPayload = payloadObject(run)
                assertEquals(functionId, runPayload["function_id"]?.jsonPrimitive?.contentOrNull)
                assertEquals(true, runPayload["success"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(listOf("Bob"), backend.inputTexts)

                backend.inputTexts.clear()
                val router = routerForTest(
                    context = context,
                    registry = registry,
                    scope = this,
                )
                try {
                    val routedRun = router.execute(
                        toolCall = toolCall(functionId),
                        args = buildJsonObject {
                            put("contact_name", JsonPrimitive("Dora"))
                        },
                        runtimeDescriptor = registry.runtimeDescriptor(functionId),
                        env = env,
                        callback = NoOpAgentCallback,
                        toolHandle = NoOpAgentRunControl.beginToolExecution(
                            functionId,
                            "router-dynamic-run",
                        ),
                    )
                    assertContextSuccess(routedRun)
                    assertEquals(listOf("Dora"), backend.inputTexts)
                } finally {
                    router.dispose()
                }

                backend.inputTexts.clear()
                val directHandler = OobFunctionToolHandler(context, helper)
                val directRun = directHandler.execute(
                    toolCall = toolCall(functionId),
                    args = buildJsonObject {
                        put("contact_name", JsonPrimitive("Carol"))
                    },
                    runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor(
                        name = functionId,
                        displayName = "填写联系人姓名",
                        toolType = "oob_function",
                    ),
                    env = env,
                    callback = NoOpAgentCallback,
                    toolHandle = NoOpAgentRunControl.beginToolExecution(
                        functionId,
                        "direct-run",
                    ),
                )
                assertContextSuccess(directRun)
                assertEquals(listOf("Carol"), backend.inputTexts)

                backend.inputTexts.clear()
                val missingArgumentRun = directHandler.execute(
                    toolCall = toolCall(functionId),
                    args = buildJsonObject {},
                    runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor(
                        name = functionId,
                        displayName = "填写联系人姓名",
                        toolType = "oob_function",
                    ),
                    env = env,
                    callback = NoOpAgentCallback,
                    toolHandle = NoOpAgentRunControl.beginToolExecution(
                        functionId,
                        "missing-argument",
                    ),
                )
                assertTrue(missingArgumentRun is ToolExecutionResult.Error)
                assertEquals(emptyList<String>(), backend.inputTexts)
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `orchestrator exposes registered manual recording function and replays it from chat`() = runBlocking {
        val context = TempFilesContext()
        val backend = RecordingBackend()
        try {
            val helper = SharedHelper(
                context = context,
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                },
            )
            val handler = WorkbenchToolHandler(helper)
            val env = FakeEnv(context)
            val runId = "run-chat-orchestrator-${System.nanoTime()}"
            val functionId = "agent_chat_replay_contact_name"

            InternalRunLogStore.beginRun(
                context = context,
                runId = runId,
                goal = "填写联系人姓名 Alice",
                source = "agent_tool",
                toolName = "vlm_task",
                operationDescription = "填写联系人姓名 Alice",
            )
            InternalRunLogStore.appendCards(
                context = context,
                runId = runId,
                cards = listOf(
                    manualRecordingRunLogCard(
                        toolName = "input_text",
                        args = mapOf(
                            "target_description" to "First name",
                            "content" to "Alice",
                            "x" to 300,
                            "y" to 420,
                        ),
                        title = "填写 First name",
                    ),
                    manualRecordingRunLogCard(
                        toolName = "finished",
                        args = mapOf("content" to "姓名已填写"),
                        title = "完成",
                    ),
                ),
            )
            InternalRunLogStore.finishRun(
                context = context,
                runId = runId,
                success = true,
                doneReason = "finished",
            )

            assertContextSuccess(handler.execute(
                toolCall = toolCall("oob_run_log_convert"),
                args = buildJsonObject {
                    put("run_id", JsonPrimitive(runId))
                    put("register", JsonPrimitive(true))
                    put("function_id", JsonPrimitive(functionId))
                    put("name", JsonPrimitive("填写联系人姓名"))
                    put("description", JsonPrimitive("复用录制轨迹填写联系人姓名"))
                },
                runtimeDescriptor = descriptor("oob_run_log_convert"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "oob_run_log_convert",
                    "convert",
                ),
            ))
            assertContextSuccess(handler.execute(
                toolCall = toolCall("update_function"),
                args = buildJsonObject {
                    put("functionId", JsonPrimitive(functionId))
                    put("patch", mapToJson(
                        mapOf(
                            "parameters" to listOf(
                                mapOf(
                                    "name" to "contact_name",
                                    "type" to "string",
                                    "required" to true,
                                    "description" to "联系人姓名",
                                    "bindings" to listOf(
                                        "$.execution.steps[1].args.content",
                                        "$.execution.steps[1].args.text",
                                    ),
                                ),
                            ),
                        )
                    ))
                },
                runtimeDescriptor = descriptor("update_function"),
                env = env,
                callback = NoOpAgentCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution(
                    "update_function",
                    "enhance",
                ),
            ))

            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val registry = AgentToolRegistry(
                    context = context,
                    discoveredServers = emptyList(),
                    toolExposurePolicy = AgentToolExposurePolicy(
                        profile = AgentToolExposurePolicy.PROFILE_FUNCTION_MANAGEMENT,
                    ),
                )
                assertNotNull(registry.toolsForModel.singleOrNull {
                    it.function.name == functionId
                })
                val router = routerForTest(
                    context = context,
                    registry = registry,
                    scope = this,
                )
                val llm = FakeLlmClient(
                    turns = listOf(
                        assistantTurn(
                            toolCalls = listOf(
                                toolCall(
                                    name = functionId,
                                    arguments = """{"contact_name":"Eve"}""",
                                    id = "call-replay-contact",
                                )
                            )
                        ),
                        assistantTurn(content = "已使用复用指令填写 Eve。")
                    )
                )
                val callback = RecordingAgentCallback()
                val orchestrator = AgentOrchestrator(
                    llmClient = llm,
                    toolRegistry = registry,
                    toolRouter = router,
                    eventAdapter = AgentEventAdapter(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = false
                    }),
                    model = "test-model",
                )

                val result = orchestrator.run(
                    AgentOrchestrator.Input(
                        callback = callback,
                        initialMessages = listOf(
                            ChatCompletionMessage(
                                role = "user",
                                content = JsonPrimitive("帮我用刚才录制的联系人姓名轨迹填写 Eve"),
                            )
                        ),
                        executionEnv = env,
                    )
                )

                assertTrue(result is AgentResult.Success)
                assertEquals(2, llm.requests.size)
                assertTrue(llm.requests.first().tools.any { it.function.name == functionId })
                assertEquals(functionId, callback.startedTools.single())
                assertEquals(functionId, callback.completedTools.single())
                assertEquals(listOf("Eve"), backend.inputTexts)
                val executed = (result as AgentResult.Success).executedTools
                assertTrue(executed.any {
                    it is ToolExecutionResult.ContextResult &&
                        it.toolName == functionId &&
                        it.success
                })
                assertTrue(callback.finalChatMessages().last().contains("Eve"))
                assertFalse(callback.errors.any())
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun toolCall(
        name: String,
        arguments: String = "{}",
        id: String = "$name-call",
    ): AssistantToolCall = AssistantToolCall(
        id = id,
        function = AssistantToolCallFunction(
            name = name,
            arguments = arguments,
        ),
    )

    private fun assistantTurn(
        content: String = "",
        toolCalls: List<AssistantToolCall> = emptyList(),
        finishReason: String? = null,
    ): ChatCompletionTurn {
        return ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = if (content.isBlank()) null else JsonPrimitive(content),
                toolCalls = toolCalls.ifEmpty { null },
            ),
            finishReason = finishReason,
        )
    }

    private fun descriptor(name: String): AgentToolRegistry.RuntimeToolDescriptor =
        AgentToolRegistry.RuntimeToolDescriptor(
            name = name,
            displayName = name,
            toolType = "workbench",
        )

    private fun assertContextSuccess(result: ToolExecutionResult) {
        assertTrue(result is ToolExecutionResult.ContextResult)
        assertEquals(true, (result as ToolExecutionResult.ContextResult).success)
    }

    private fun payloadObject(result: ToolExecutionResult): JsonObject {
        assertTrue(result is ToolExecutionResult.ContextResult)
        return Json.parseToJsonElement(
            (result as ToolExecutionResult.ContextResult).rawResultJson,
        ).jsonObject
    }

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

    private fun runLogCard(
        toolName: String,
        args: Map<String, Any?>,
        title: String,
    ): Map<String, Any?> = linkedMapOf(
        "tool_name" to toolName,
        "title" to title,
        "tool_call" to linkedMapOf(
            "name" to toolName,
            "arguments" to args,
        ),
        "result" to linkedMapOf("success" to true),
    )

    private fun manualRecordingRunLogCard(
        toolName: String,
        args: Map<String, Any?>,
        title: String,
    ): Map<String, Any?> = linkedMapOf(
        "tool_name" to toolName,
        "toolName" to toolName,
        "title" to title,
        "summary" to title,
        "tool_type" to "manual_recording",
        "toolType" to "manual_recording",
        "compile_kind" to "manual_recording",
        "source" to "human_trajectory",
        "success" to true,
        "status" to "success",
        "header" to linkedMapOf(
            "title" to title,
            "tool_name" to toolName,
            "source" to "human_trajectory",
            "status" to "success",
            "success" to true,
        ),
        "tool_call" to linkedMapOf(
            "name" to toolName,
            "arguments" to args,
        ),
        "params" to args,
        "before" to linkedMapOf(
            "package_name" to "com.android.contacts",
            "observation_xml" to CONTACT_XML,
        ),
        "after" to linkedMapOf(
            "package_name" to "com.android.contacts",
            "observation_xml" to CONTACT_XML,
        ),
        "source_context" to linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to CONTACT_XML,
                "package_name" to "com.android.contacts",
            )
        ),
        "event_context" to linkedMapOf(
            "recording_backend" to "accessibility_event",
        ),
        "result" to linkedMapOf(
            "success" to true,
            "summary" to title,
            "source" to "human_trajectory",
        ),
    )

    private fun routerForTest(
        context: Context,
        registry: AgentToolCatalog,
        scope: CoroutineScope,
    ): AgentToolRouter = AgentToolRouter(
        context = context,
        scope = scope,
        scheduleToolBridge = NoOpScheduleToolBridge,
        workspaceManager = AgentWorkspaceManager(context),
        subagentDispatcher = SubagentDispatcher(
            llmClient = UnusedLlmClient,
            toolExecutorProvider = { UnusedToolExecutor },
            parentCatalogProvider = { registry },
            eventAdapter = AgentEventAdapter(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
            }),
            model = "test",
        ),
    )

    private fun functionSpec(functionId: String): Map<String, Any?> = linkedMapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to "Agent managed function",
        "description" to "Reusable function managed from agent conversation",
        "parameters" to emptyList<Any?>(),
        "execution" to linkedMapOf(
            "kind" to "tool_sequence",
            "runner" to "oob_tool_sequence",
            "entrypoint" to "execute",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "finished",
                    "index" to 0,
                    "title" to "Task completed",
                    "kind" to "omniflow_action",
                    "executor" to "omniflow",
                    "omniflow_action" to "finished",
                    "local_action" to "finished",
                    "tool" to "finished",
                    "callable_tool" to "finished",
                    "model_free" to true,
                    "scriptable" to true,
                    "args" to linkedMapOf("content" to "Done"),
                )
            ),
            "step_count" to 1,
        ),
    )

    private companion object {
        private const val CONTACT_XML =
            "<hierarchy><node package=\"com.android.contacts\" class=\"android.widget.FrameLayout\" bounds=\"[0,0][1080,2400]\"><node text=\"First name\" class=\"android.widget.EditText\" resource-id=\"com.android.contacts:id/first_name\" clickable=\"true\" focusable=\"true\" focused=\"true\" editable=\"true\" bounds=\"[120,360][960,460]\"/><node text=\"Save\" class=\"android.widget.TextView\" resource-id=\"com.android.contacts:id/editor_menu_save_button\" clickable=\"true\" bounds=\"[900,50][1060,150]\"/></node></hierarchy>"
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("workbench-oob-function-tools-test").toFile()
        private val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        private val appInfo = ApplicationInfo().apply {
            dataDir = root.absolutePath
            packageName = "cn.com.omnimind.bot.test"
        }

        override fun getApplicationContext(): Context = this

        override fun getApplicationInfo(): ApplicationInfo = appInfo

        override fun getFilesDir(): File = root

        override fun getPackageName(): String = appInfo.packageName

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsByName.getOrPut(name.orEmpty()) { InMemorySharedPreferences() }
        }
    }

    private class FakeEnv(
        private val context: Context
    ) : AgentExecutionEnvironment {
        override val agentRunId: String = "test-run"
        override val userMessage: String = "test"
        override val attachments: List<Map<String, Any?>> = emptyList()
        override val currentPackageName: String? = null
        override val runtimeContextRepository: AgentRuntimeContextRepository =
            AgentRuntimeContextRepository(context)
        override val workspaceDescriptor: AgentWorkspaceDescriptor =
            AgentWorkspaceDescriptor(
                id = "test",
                rootPath = context.filesDir.absolutePath,
                androidRootPath = context.filesDir.absolutePath,
                uriRoot = "content://test",
                currentCwd = context.filesDir.absolutePath,
                androidCurrentCwd = context.filesDir.absolutePath,
                shellRootPath = context.filesDir.absolutePath,
                retentionPolicy = "test",
            )
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList()
        override val failureLearningSkill: ResolvedSkillContext? = null
        override val workspaceManager: AgentWorkspaceManager
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceMemoryService: WorkspaceMemoryService
            get() = throw UnsupportedOperationException("unused in test")
        override val conversationMode: String = "normal"
        override val reasoningEffort: String? = null
        override val terminalEnvironment: Map<String, String> = emptyMap()
        override val runControl = NoOpAgentRunControl
    }

    private class RecordingBackend : OmniflowActionBackend {
        val inputTexts = mutableListOf<String>()

        override fun isReady(): Boolean = true

        override suspend fun click(x: Float, y: Float) = Unit

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) = Unit

        override suspend fun inputTextToFocusedNode(text: String) {
            inputTexts += text
        }

        override suspend fun launchApplication(packageName: String) = Unit

        override suspend fun pressHotKey(key: String) = Unit

        override fun currentXml(): String? = "<hierarchy />"

        override fun currentPackageName(): String? = "com.android.contacts"

        override fun currentActivityName(): String? = "ContactsActivity"
    }

    private object NoOpScheduleToolBridge : AgentScheduleToolBridge {
        override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> =
            emptyMap()

        override suspend fun listTasks(): List<Map<String, Any?>> = emptyList()

        override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> =
            emptyMap()

        override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> =
            emptyMap()
    }

    private object UnusedLlmClient : AgentLlmClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallUpdate: (suspend (StreamingToolCallSnapshot) -> Unit)?,
        ): ChatCompletionTurn {
            throw UnsupportedOperationException("unused in router dispatch test")
        }
    }

    private class FakeLlmClient(
        turns: List<ChatCompletionTurn>,
    ) : AgentLlmClient {
        private val queuedTurns = ArrayDeque(turns)
        val requests = mutableListOf<ChatCompletionRequest>()

        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallUpdate: (suspend (StreamingToolCallSnapshot) -> Unit)?,
        ): ChatCompletionTurn {
            requests += request
            val turn = queuedTurns.removeFirst()
            val content = turn.message.contentText()
            if (content.isNotBlank()) {
                onContentUpdate?.invoke(content)
            }
            return turn
        }
    }

    private class RecordingAgentCallback : AgentCallback {
        val startedTools = mutableListOf<String>()
        val completedTools = mutableListOf<String>()
        val chatMessages = mutableListOf<Pair<String, Boolean>>()
        val errors = mutableListOf<String>()

        override suspend fun onThinkingStart() = Unit

        override suspend fun onThinkingUpdate(thinking: String) = Unit

        override suspend fun onToolCallStart(
            toolName: String,
            toolCallId: String,
            arguments: JsonObject,
        ) {
            startedTools += toolName
        }

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>,
        ) = Unit

        override suspend fun onToolCallComplete(
            toolName: String,
            result: ToolExecutionResult,
        ) {
            completedTools += toolName
        }

        override suspend fun onChatMessage(message: String) {
            chatMessages += message to true
        }

        override suspend fun onChatMessage(message: String, isFinal: Boolean) {
            chatMessages += message to isFinal
        }

        override suspend fun onChatMessage(
            message: String,
            isFinal: Boolean,
            prefillTokensPerSecond: Double?,
            decodeTokensPerSecond: Double?,
        ) {
            chatMessages += message to isFinal
        }

        override suspend fun onComplete(result: AgentResult) = Unit

        override suspend fun onError(error: String) {
            errors += error
        }

        override suspend fun onPermissionRequired(missing: List<String>) = Unit

        fun finalChatMessages(): List<String> =
            chatMessages.filter { it.second }.map { it.first }
    }

    private object UnusedToolExecutor : AgentToolExecutor {
        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle,
        ): ToolExecutionResult {
            throw UnsupportedOperationException("unused in router dispatch test")
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                put(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = put(key, values?.toMutableSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                put(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                put(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                put(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let { removals += it }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                removals.forEach(values::remove)
                updates.forEach { (key, value) -> values[key] = value }
            }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                key?.let { updates[it] = value }
                return this
            }
        }
    }
}
