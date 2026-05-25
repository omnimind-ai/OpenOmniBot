package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutor
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.runlog.OmniflowActionBackend
import cn.com.omnimind.bot.runlog.OmniflowActionRuntime
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFunctionToolHandlerOmniFlowExecutionTest {
    @Test
    fun `local click replay fails before execution when accessibility is unavailable`() = runBlocking {
        val context = TempFilesContext()
        val backend = NotReadyBackend()
        try {
            val spec = functionSpec(
                functionId = "click_requires_accessibility",
                steps = listOf(
                    mapOf(
                        "id" to "click_step",
                        "title" to "Click target",
                        "kind" to "omniflow_action",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "click",
                        "callable_tool" to "click",
                        "args" to mapOf("x" to 100, "y" to 240),
                    )
                ),
            )
            OmniflowActionRuntime.useBackendForTesting(backend).use {
                val run = handler(context, WorkspaceFunctionStore(context.root)).runMaterializedFunction(
                    functionId = "click_requires_accessibility",
                    spec = spec,
                    materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                    allowAgentFallback = false,
                )

                assertEquals(false, run["success"])
                assertEquals("OOB_ACCESSIBILITY_REQUIRED", run["error_code"])
                assertEquals("accessibility", run["required_permission"])
                assertEquals(0, backend.clickCount)
                assertTrue(run["error_message"].toString().contains("无障碍"))
                val step = stepResults(run).single()
                assertEquals("click", step["tool"])
                assertEquals("OOB_ACCESSIBILITY_REQUIRED", step["error_code"])
            }
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool executes nested registered function locally by function id`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            val child = functionSpec(
                functionId = "child_finished",
                steps = listOf(finishedStep("child_step")),
            )
            val parent = functionSpec(
                functionId = "parent_calls_child",
                steps = listOf(
                    mapOf(
                        "id" to "parent_step",
                        "title" to "Call child",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "child_finished"),
                    )
                ),
            )
            assertTrue(store.register(child)["success"] == true)

            val handler = handler(context, store)
            val run = handler.runMaterializedFunction(
                functionId = "parent_calls_child",
                spec = parent,
                materializedSpec = OobReusableFunctionStore.materialize(parent, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(false, run["model_required"])
            assertEquals("oob_omniflow_replay", run["runner"])
            assertTrue(run["run_id"]?.toString()?.startsWith("omniflow_run_") == true)
            assertEquals(run["run_id"], run["audit_run_id"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("call_tool", step["tool"])
            assertEquals("child_finished", step["nested_function_id"])
            assertTrue(step["nested_run_id"]?.toString()?.startsWith("omniflow_run_") == true)
            assertEquals(true, step["success"])
            assertEquals(false, step["nested_model_required"])
            val nestedSteps = step["step_results"] as? List<*>
            assertEquals(1, nestedSteps?.size)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `go_to_node executes UTG path locally`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = functionSpec(
                functionId = "graph_path",
                steps = listOf(
                    mapOf(
                        "id" to "graph_step",
                        "title" to "Navigate graph",
                        "kind" to "omniflow_graph",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "go_to_node",
                        "callable_tool" to "go_to_node",
                        "args" to mapOf(
                            "node_id" to "node_done",
                            "path" to listOf(
                                mapOf(
                                    "edge_id" to "edge_done",
                                    "to_node_id" to "node_done",
                                    "action" to "finished",
                                    "args" to emptyMap<String, Any?>(),
                                )
                            ),
                        ),
                    )
                ),
            )

            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val run = handler.runMaterializedFunction(
                functionId = "graph_path",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(false, run["model_required"])
            val step = stepResults(run).single()
            assertEquals("omniflow_graph", step["executor"])
            assertEquals(true, step["success"])
            assertEquals(1, (step["path_length"] as Number).toInt())
            val pathSteps = step["step_results"] as? List<*>
            assertEquals(1, pathSteps?.size)
            val primitive = pathSteps?.single() as? Map<*, *>
            assertEquals("finished", primitive?.get("tool"))
            assertEquals("omniflow", primitive?.get("executor"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_function missing nested function fails locally without agent fallback`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = functionSpec(
                functionId = "parent_missing_child",
                steps = listOf(
                    mapOf(
                        "id" to "missing_child",
                        "title" to "Missing child",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "omniflow.call_function",
                        "callable_tool" to "omniflow.call_function",
                        "args" to mapOf("function_id" to "does_not_exist"),
                    )
                ),
            )

            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val run = handler.runMaterializedFunction(
                functionId = "parent_missing_child",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(false, run["success"])
            assertEquals(false, run["model_required"])
            assertEquals(false, run["delegated_tool_used"])
            assertEquals("OOB_FUNCTION_NOT_FOUND", run["error_code"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("OOB_FUNCTION_NOT_FOUND", step["error_code"])
            assertEquals(false, step["success"])
            assertFalse(step["needs_agent"] == true)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `legacy provider-owned agent call_function spec is executed locally`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            assertTrue(
                store.register(
                    functionSpec(
                        functionId = "legacy_child",
                        steps = listOf(finishedStep("legacy_child_done")),
                    )
                )["success"] == true
            )
            val legacyParent = functionSpec(
                functionId = "legacy_parent",
                steps = listOf(
                    mapOf(
                        "id" to "legacy_call",
                        "title" to "Legacy call",
                        "kind" to "agent_call",
                        "executor" to "agent",
                        "scriptable" to false,
                        "tool" to "call_function",
                        "callable_tool" to "oob.agent.run",
                        "args" to mapOf("function_id" to "legacy_child"),
                        "agent_call" to mapOf(
                            "tool" to "oob.agent.run",
                            "args" to mapOf(
                                "original_tool" to "call_function",
                                "original_args" to mapOf("function_id" to "legacy_child"),
                            ),
                            "reason" to "provider_owned_replay_requires_omniflow",
                        ),
                    )
                ),
            )

            val handler = handler(context, store)
            val run = handler.runMaterializedFunction(
                functionId = "legacy_parent",
                spec = legacyParent,
                materializedSpec = OobReusableFunctionStore.materialize(legacyParent, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(true, run["success"])
            assertEquals(false, run["model_required"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("legacy_child", step["nested_function_id"])
            assertEquals(true, step["success"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `recursive call_function is rejected by local runner`() = runBlocking {
        val context = TempFilesContext()
        try {
            val store = WorkspaceFunctionStore(context.root)
            val spec = functionSpec(
                functionId = "self_recursive",
                steps = listOf(
                    mapOf(
                        "id" to "self_call",
                        "title" to "Self call",
                        "kind" to "omniflow_function",
                        "executor" to "omniflow",
                        "model_free" to true,
                        "scriptable" to true,
                        "tool" to "call_function",
                        "callable_tool" to "call_function",
                        "args" to mapOf("function_id" to "self_recursive"),
                    )
                ),
            )
            assertTrue(store.register(spec)["success"] == true)

            val handler = handler(context, store)
            val run = handler.runMaterializedFunction(
                functionId = "self_recursive",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = false,
            )

            assertEquals(false, run["success"])
            assertEquals(false, run["model_required"])
            assertTrue(run["run_id"]?.toString()?.startsWith("omniflow_run_") == true)
            assertEquals(run["run_id"], run["audit_run_id"])
            val step = stepResults(run).single()
            assertEquals("omniflow_function", step["executor"])
            assertEquals("OOB_FUNCTION_RECURSION", step["error_code"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `canRunFullyWithOmniflow includes graph and function execution tools`() {
        val context = TempFilesContext()
        try {
            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val spec = functionSpec(
                functionId = "all_local",
                steps = listOf(
                    finishedStep("primitive"),
                    mapOf(
                        "id" to "graph",
                        "executor" to "omniflow",
                        "tool" to "go_to_node",
                        "callable_tool" to "go_to_node",
                        "args" to mapOf(
                            "path" to listOf(mapOf("action" to "finished")),
                        ),
                    ),
                    mapOf(
                        "id" to "function",
                        "executor" to "omniflow",
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf("function_id" to "anything"),
                    ),
                ),
            )
            val materialized = OobReusableFunctionStore.materialize(spec, emptyMap())

            assertTrue(handler.canRunFullyWithOmniflow(materialized))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool without function id requires agent runner when router unavailable`() = runBlocking {
        val context = TempFilesContext()
        try {
            val spec = functionSpec(
                functionId = "needs_router",
                steps = listOf(
                    mapOf(
                        "id" to "call_vlm",
                        "title" to "Call VLM",
                        "kind" to "tool_call",
                        "executor" to "tool",
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "tool_name" to "vlm_task",
                            "arguments" to mapOf("goal" to "tap settings"),
                        ),
                    )
                ),
            )

            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val run = handler.runMaterializedFunction(
                functionId = "needs_router",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                allowAgentFallback = true,
            )

            assertEquals(false, run["success"])
            assertEquals(true, run["model_required"])
            val step = stepResults(run).single()
            assertEquals("agent", step["executor"])
            assertEquals("tool", step["blocked_executor"])
            assertEquals("vlm_task", step["tool"])
            assertEquals(true, step["needs_agent"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool with vlm task delegates through router when available`() = runBlocking {
        val context = TempFilesContext()
        try {
            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val router = CapturingRouter()
            handler.router = router
            val spec = functionSpec(
                functionId = "delegates_vlm",
                steps = listOf(
                    mapOf(
                        "id" to "call_vlm",
                        "title" to "Call VLM",
                        "kind" to "tool_call",
                        "executor" to "tool",
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "tool_name" to "vlm_task",
                            "arguments" to mapOf(
                                "goal" to "open settings",
                                "model" to "scene.vlm.operation.primary",
                                "maxSteps" to 3,
                                "startFromCurrent" to true,
                            ),
                        ),
                    )
                ),
            )

            val run = handler.runMaterializedFunction(
                functionId = "delegates_vlm",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                toolHandle = cn.com.omnimind.bot.agent.NoOpAgentRunControl
                    .beginToolExecution("call_tool", "test"),
                env = FakeEnv(context),
                allowAgentFallback = true,
            )

            assertEquals(true, run["success"])
            assertEquals(true, run["delegated_tool_used"])
            assertEquals(false, run["model_required"])
            assertEquals("vlm_task", router.toolName)
            assertEquals("open settings", router.args?.get("goal")?.jsonPrimitive?.contentOrNull)
            assertEquals("scene.vlm.operation.primary", router.args?.get("model")?.jsonPrimitive?.contentOrNull)
            assertEquals(3, router.args?.get("maxSteps")?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(true, router.args?.get("startFromCurrent")?.jsonPrimitive?.contentOrNull?.toBoolean())
            val step = stepResults(run).single()
            assertEquals("tool", step["executor"])
            assertEquals("call_tool", step["delegated_from"])
            assertEquals("vlm_task", step["tool"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `call_tool with vlm task delegates through router without callback`() = runBlocking {
        val context = TempFilesContext()
        try {
            val handler = handler(context, WorkspaceFunctionStore(context.root))
            val router = CapturingRouter()
            handler.router = router
            val spec = functionSpec(
                functionId = "direct_androidworld_vlm",
                steps = listOf(
                    mapOf(
                        "id" to "androidworld_step",
                        "title" to "AndroidWorld current page smoke",
                        "kind" to "tool_call",
                        "executor" to "tool",
                        "scriptable" to true,
                        "tool" to "call_tool",
                        "callable_tool" to "call_tool",
                        "args" to mapOf(
                            "tool_name" to "vlm_task",
                            "arguments" to mapOf(
                                "goal" to "tap the visible OK button if present",
                                "maxSteps" to 2,
                                "startFromCurrent" to true,
                                "needSummary" to false,
                            ),
                        ),
                    )
                ),
            )

            val run = handler.runMaterializedFunction(
                functionId = "direct_androidworld_vlm",
                spec = spec,
                materializedSpec = OobReusableFunctionStore.materialize(spec, emptyMap()),
                env = FakeEnv(context),
                allowAgentFallback = true,
            )

            assertEquals(true, run["success"])
            assertEquals(true, run["delegated_tool_used"])
            assertEquals(false, run["model_required"])
            assertEquals("vlm_task", router.toolName)
            assertEquals(
                "tap the visible OK button if present",
                router.args?.get("goal")?.jsonPrimitive?.contentOrNull
            )
            assertEquals(2, router.args?.get("maxSteps")?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(true, router.args?.get("startFromCurrent")?.jsonPrimitive?.contentOrNull?.toBoolean())
            val step = stepResults(run).single()
            assertEquals("tool", step["executor"])
            assertEquals("call_tool", step["delegated_from"])
            assertEquals("vlm_task", step["tool"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun handler(
        context: Context,
        store: WorkspaceFunctionStore,
    ): OobFunctionToolHandler = OobFunctionToolHandler(
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
        workspaceFunctionStore = store
    }

    private fun functionSpec(
        functionId: String,
        steps: List<Map<String, Any?>>,
    ): Map<String, Any?> = linkedMapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to functionId,
        "description" to functionId,
        "parameters" to emptyList<Any?>(),
        "execution" to linkedMapOf(
            "kind" to "tool_sequence",
            "runner" to "oob_tool_sequence",
            "entrypoint" to "execute",
            "steps" to steps,
            "step_count" to steps.size,
        ),
    )

    private fun finishedStep(stepId: String): Map<String, Any?> = mapOf(
        "id" to stepId,
        "title" to "Finished",
        "kind" to "omniflow_action",
        "executor" to "omniflow",
        "model_free" to true,
        "scriptable" to true,
        "omniflow_action" to "finished",
        "tool" to "finished",
        "callable_tool" to "finished",
        "args" to emptyMap<String, Any?>(),
    )

    private fun stepResults(run: Map<String, Any?>): List<Map<String, Any?>> {
        val raw = run["step_results"] as? List<*>
        assertNotNull(raw)
        return raw!!.map { step ->
            @Suppress("UNCHECKED_CAST")
            step as Map<String, Any?>
        }
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("oob-function-runner-test").toFile()
        private val prefs = InMemoryPrefs()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class NotReadyBackend : OmniflowActionBackend {
        var clickCount = 0

        override fun isReady(): Boolean = false

        override suspend fun click(x: Float, y: Float) {
            clickCount += 1
        }

        override suspend fun longPress(x: Float, y: Float, durationMs: Long) = Unit

        override suspend fun scroll(
            x: Float,
            y: Float,
            direction: ScrollDirection,
            distance: Float,
            durationMs: Long,
        ) = Unit

        override suspend fun inputTextToFocusedNode(text: String) = Unit

        override suspend fun launchApplication(packageName: String) = Unit

        override suspend fun pressHotKey(key: String) = Unit

        override fun currentXml(): String? = null

        override fun currentPackageName(): String? = null

        override fun currentActivityName(): String? = null
    }

    private class InMemoryPrefs : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

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
            private val pending = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = values?.toSet() }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = null }

            override fun clear(): SharedPreferences.Editor =
                apply { clear = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }

    private class CapturingRouter : AgentToolExecutor {
        var toolName: String = ""
            private set
        var args: JsonObject? = null
            private set

        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: cn.com.omnimind.bot.agent.AgentToolExecutionHandle
        ): ToolExecutionResult {
            toolName = toolCall.function.name
            this.args = args
            return ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = "delegated",
                previewJson = "{}",
                rawResultJson = "{}",
                success = true,
            )
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
}
