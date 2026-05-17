package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.content.ContextWrapper
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.bot.workbench.WorkspaceFunctionStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFunctionToolHandlerOmniFlowExecutionTest {
    @Test
    fun `call_function executes nested registered function locally`() = runBlocking {
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
                        "tool" to "call_function",
                        "callable_tool" to "call_function",
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
                        "tool" to "call_function",
                        "callable_tool" to "call_function",
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

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root
    }
}
