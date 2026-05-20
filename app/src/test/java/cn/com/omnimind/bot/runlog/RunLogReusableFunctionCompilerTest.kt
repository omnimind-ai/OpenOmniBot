package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogReusableFunctionCompilerTest {
    @Test
    fun `replay policy matches shared json contract`() {
        val policy = readSharedPolicyJson()

        assertEquals(RunLogReplayPolicy.schemaVersion, policy["schema_version"])
        assertEquals(RunLogReplayPolicy.omniflowActions, stringSet(policy["omniflow_actions"]))
        assertEquals(RunLogReplayPolicy.omniflowActionAliases, stringMap(policy["omniflow_action_aliases"]))
        assertEquals(RunLogReplayPolicy.coordinateActions, stringSet(policy["coordinate_actions"]))
        assertEquals(RunLogReplayPolicy.perceptionTools, stringSet(policy["perception_tools"]))
        assertEquals(RunLogReplayPolicy.dataFlowTools, stringSet(policy["data_flow_tools"]))
        assertEquals(RunLogReplayPolicy.omniflowGraphTools, stringSet(policy["omniflow_graph_tools"]))
        assertEquals(RunLogReplayPolicy.omniflowFunctionTools, stringSet(policy["omniflow_function_tools"]))
        assertEquals(RunLogReplayPolicy.providerOnlyTools, stringSet(policy["provider_only_tools"]))
        assertEquals(RunLogReplayPolicy.skipTools, stringSet(policy["skip_tools"]))
    }

    @Test
    fun `vlm only run log compiles to agent step`() {
        val spec = compile(
            listOf(
                card("vlm_task", mapOf("goal" to "Find settings")),
            ),
            runId = "run-vlm-only",
        )

        val steps = stepsFrom(spec)
        assertEquals(1, steps.size)
        val step = steps.single()
        assertEquals("vlm_task", step["tool"])
        assertEquals("agent", step["executor"])
        assertEquals("oob.agent.run", step["callable_tool"])
        assertEquals(false, step["scriptable"])

        val agentCall = step["agent_call"] as? Map<*, *>
        assertNotNull(agentCall)
        assertEquals("oob.agent.run", agentCall?.get("tool"))
        assertEquals(
            "perception_only_step_without_recorded_actions",
            agentCall?.get("reason"),
        )

        val capabilities = capabilitiesFrom(spec)
        assertEquals(1, capabilities["agent_step_count"])
        assertEquals(0, capabilities["omniflow_step_count"])
        assertEquals(true, capabilities["requires_agent_fallback"])
        assertEquals("oob.reusable_function.v1", spec["schema_version"])
        assertEquals("run_log", (spec["source"] as Map<*, *>)["kind"])
        assertFalse(spec.containsKey("runtime_targets"))
        assertFalse(spec.containsKey("call_contract"))
        assertFalse(spec.containsKey("script_reuse"))
        assertFalse(spec.containsKey("agent_reuse"))
    }

    @Test
    fun `vlm wrapper is skipped when recorded omniflow action exists`() {
        val spec = compile(
            listOf(
                card("vlm_task", mapOf("goal" to "Tap Open")),
                card(
                    "click",
                    mapOf("target_description" to "Open", "x" to 120, "y" to 240),
                    beforeXml = SOURCE_XML,
                ),
            ),
            runId = "run-vlm-click",
        )

        val steps = stepsFrom(spec)
        assertEquals(1, steps.size)
        val click = steps.single()
        assertEquals("step_1", click["id"])
        assertEquals(0, (click["index"] as Number).toInt())
        assertEquals("click", click["tool"])
        assertEquals("omniflow", click["executor"])
        assertEquals(true, click["model_free"])
        assertEquals("click", click["omniflow_action"])
        assertEquals("omniflow", click["coordinate_hook"])
        assertFalse(click.containsKey("agent_call"))
        assertEquals(
            SOURCE_XML,
            ((click["source_context"] as Map<*, *>)["src_ctx"] as Map<*, *>)["page"],
        )
    }

    @Test
    fun `failed replay card does not suppress vlm fallback`() {
        val spec = compile(
            listOf(
                card("vlm_task", mapOf("goal" to "Tap Open")),
                card(
                    "click",
                    mapOf("target_description" to "Open", "x" to 120, "y" to 240),
                    success = false,
                ),
            ),
            runId = "run-vlm-failed-click",
        )

        val steps = stepsFrom(spec)
        assertEquals(1, steps.size)
        val step = steps.single()
        assertEquals("vlm_task", step["tool"])
        assertEquals("agent", step["executor"])
        assertEquals(
            "perception_only_step_without_recorded_actions",
            (step["agent_call"] as Map<*, *>)["reason"],
        )
    }

    @Test
    fun `data flow tools compile to agent steps`() {
        val spec = compile(
            listOf(
                card("browser_use", mapOf("url" to "https://example.com")),
                card("web_search", mapOf("query" to "release notes")),
                card("oob_run_log_convert", mapOf("run_id" to "run-1")),
            ),
            runId = "run-data-flow",
        )

        val steps = stepsFrom(spec)
        assertEquals(3, steps.size)
        for (step in steps) {
            assertEquals("agent", step["executor"])
            assertEquals(false, step["scriptable"])
            assertEquals("oob.agent.run", step["callable_tool"])
            val agentCall = step["agent_call"] as? Map<*, *>
            assertEquals("data_flow_tool_requires_live_context", agentCall?.get("reason"))
            val agentArgs = agentCall?.get("args") as? Map<*, *>
            assertEquals(step["tool"], agentArgs?.get("original_tool"))
            assertNotNull(agentArgs?.get("original_args"))
        }
    }

    @Test
    fun `omniflow graph and function tools compile to local omniflow execution`() {
        val spec = compile(
            listOf(
                card("go_to_node", mapOf("node_id" to "node_1")),
                card("call_function", mapOf("function_id" to "func_local")),
            ),
            runId = "run-omniflow-execution",
        )

        val steps = stepsFrom(spec)
        assertEquals(2, steps.size)
        val graph = steps[0]
        assertEquals("go_to_node", graph["tool"])
        assertEquals("go_to_node", graph["callable_tool"])
        assertEquals("omniflow", graph["executor"])
        assertEquals("omniflow_graph", graph["kind"])
        assertEquals(true, graph["model_free"])
        assertEquals(true, graph["scriptable"])
        assertFalse(graph.containsKey("agent_call"))

        val function = steps[1]
        assertEquals("call_tool", function["tool"])
        assertEquals("call_tool", function["callable_tool"])
        assertEquals("call_function", function["source_tool"])
        assertEquals("omniflow", function["executor"])
        assertEquals("omniflow_function", function["kind"])
        assertEquals(true, function["model_free"])
        assertEquals(true, function["scriptable"])
        assertFalse(function.containsKey("agent_call"))

        val capabilities = capabilitiesFrom(spec)
        assertEquals(2, capabilities["omniflow_step_count"])
        assertEquals(0, capabilities["agent_step_count"])
        assertEquals(false, capabilities["requires_agent_fallback"])
    }

    @Test
    fun `provider only policy no longer classifies omniflow execution tools as agent`() {
        assertTrue(RunLogReplayPolicy.providerOnlyTools.isEmpty())
        for (toolName in listOf(
            "go_to_node",
            "click_node",
            "call_tool",
            "omniflow.call_tool",
            "oob_tool_call",
            "call_function",
            "omniflow.call_function",
            "oob_function_run",
        )) {
            assertTrue(RunLogReplayPolicy.isOmniflowExecutionTool(toolName))
            assertFalse(RunLogReplayPolicy.isAgentTool(toolName))
        }
        for (toolName in listOf(
            "oob_function_list",
            "oob_function_get",
            "oob_function_register",
            "oob_function_guard_check",
            "oob_run_log_convert",
        )) {
            assertTrue(RunLogReplayPolicy.isAgentTool(toolName))
            assertFalse(RunLogReplayPolicy.isOmniflowExecutionTool(toolName))
        }
        for (reason in listOf("provider_owned_replay_requires_omniflow")) {
            assertFalse(RunLogReplayPolicy.requiresAgentPlanningReason(reason))
        }
    }

    @Test
    fun `generic call_tool without function id compiles to tool delegation`() {
        val spec = compile(
            listOf(
                card(
                    "oob_tool_call",
                    mapOf(
                        "toolName" to "vlm_task",
                        "arguments" to mapOf("goal" to "tap settings"),
                    ),
                ),
            ),
            runId = "run-call-tool",
        )

        val step = stepsFrom(spec).single()
        assertEquals("call_tool", step["tool"])
        assertEquals("call_tool", step["callable_tool"])
        assertEquals("oob_tool_call", step["source_tool"])
        assertEquals("tool", step["executor"])
        assertEquals("tool_call", step["kind"])
        assertFalse(step.containsKey("model_free"))
        val args = step["args"] as? Map<*, *>
        assertEquals("vlm_task", args?.get("tool_name"))
    }

    @Test
    fun `data flow tools still compile to agent steps after omniflow migration`() {
        val spec = compile(
            listOf(
                card("omniflow.recall", mapOf("goal" to "settings")),
                card("web_search", mapOf("query" to "release notes")),
            ),
            runId = "run-agent-still-needed",
        )

        val steps = stepsFrom(spec)
        assertEquals(2, steps.size)
        for (step in steps) {
            assertEquals("agent", step["executor"])
            assertEquals(false, step["scriptable"])
            assertEquals("oob.agent.run", step["callable_tool"])
            val agentCall = step["agent_call"] as? Map<*, *>
            assertEquals("data_flow_tool_requires_live_context", agentCall?.get("reason"))
        }
    }

    @Test
    fun `android privileged local action arguments are flattened for replay`() {
        val spec = compile(
            listOf(
                card(
                    "android_privileged_action",
                    mapOf(
                        "action" to "tap",
                        "arguments" to mapOf(
                            "target_description" to "Open",
                            "x" to 120,
                            "y" to 240,
                        ),
                    ),
                    beforeXml = SOURCE_XML,
                ),
            ),
            runId = "run-privileged-click",
        )

        val step = stepsFrom(spec).single()
        assertEquals("click", step["tool"])
        assertEquals("click", step["omniflow_action"])
        assertEquals("android_privileged_action", step["source_tool"])
        assertEquals("omniflow", step["executor"])
        assertEquals("omniflow", step["coordinate_hook"])
        val args = step["args"] as Map<*, *>
        assertEquals(120, (args["x"] as Number).toInt())
        assertEquals(240, (args["y"] as Number).toInt())
        assertFalse(args.containsKey("action"))
        assertFalse(args.containsKey("arguments"))
        assertEquals(
            "click",
            ((step["source_context"] as Map<*, *>)["action"] as Map<*, *>)["tool"],
        )
    }

    @Test
    fun `args can come from direct args or nested tool call arguments`() {
        val spec = compile(
            listOf(
                mapOf(
                    "tool_call" to mapOf(
                        "function" to mapOf("name" to "type"),
                        "arguments" to """{"content":"hello"}""",
                    ),
                ),
                card("wait", mapOf("duration_ms" to 500)),
            ),
            runId = "run-args",
        )

        val steps = stepsFrom(spec)
        assertEquals(2, steps.size)
        assertEquals("type", steps[0]["tool"])
        assertEquals("hello", (steps[0]["args"] as Map<*, *>)["content"])
        assertEquals(
            500,
            ((steps[1]["args"] as Map<*, *>)["duration_ms"] as Number).toInt(),
        )
    }

    @Test
    fun `omniflow canonical action names compile to local replay steps`() {
        val spec = compile(
            listOf(
                card("input_text", mapOf("text" to "hello")),
                card("swipe", mapOf("x1" to 10, "y1" to 20, "x2" to 10, "y2" to 300)),
                card("press_key", mapOf("key" to "BACK")),
                card("finish", mapOf("content" to "done")),
            ),
            runId = "run-omniflow-canonical",
        )

        val steps = stepsFrom(spec)
        assertEquals(listOf("input_text", "swipe", "press_key", "finished"), steps.map { it["tool"] })
        assertTrue(steps.all { it["executor"] == "omniflow" })
        assertTrue(steps.all { it["model_free"] == true })
        assertEquals("input_text", steps[0]["omniflow_action"])
        assertEquals("swipe", steps[1]["omniflow_action"])
        assertEquals("press_key", steps[2]["omniflow_action"])
        assertEquals("finished", steps[3]["omniflow_action"])
        assertEquals("finished", steps[3]["callable_tool"])
        assertEquals("finish", steps[3]["source_tool"])
    }

    @Test
    fun `manual takeover recorded actions compile to local replay steps`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Confirm", "x" to 540, "y" to 1600),
                    beforeXml = SOURCE_XML,
                    title = "人工点击 Confirm",
                    compileKind = "manual_recording",
                    source = "human_takeover",
                ),
                card(
                    "input_text",
                    mapOf("target_description" to "Search", "text" to "query"),
                    beforeXml = SOURCE_XML,
                    title = "人工输入文本",
                    compileKind = "manual_recording",
                    source = "human_takeover",
                ),
            ),
            runId = "run-manual-takeover",
        )

        val steps = stepsFrom(spec)
        assertEquals(listOf("click", "input_text"), steps.map { it["tool"] })
        assertTrue(steps.all { it["executor"] == "omniflow" })
        assertTrue(steps.all { it["model_free"] == true })
        assertEquals("omniflow", steps[0]["coordinate_hook"])
        assertEquals(
            SOURCE_XML,
            ((steps[0]["source_context"] as Map<*, *>)["src_ctx"] as Map<*, *>)["page"],
        )
        assertEquals("query", (steps[1]["args"] as Map<*, *>)["text"])
    }

    @Test
    fun `text input run log infers callable parameter and materializes changed argument`() {
        val spec = compile(
            listOf(
                card("input_text", mapOf("text" to "hello")),
            ),
            runId = "run-input-parameter",
        )

        val parameter = parametersFrom(spec).single()
        assertEquals("input_text", parameter["name"])
        assertEquals("string", parameter["type"])
        assertEquals(false, parameter["required"])
        assertEquals("hello", parameter["default"])
        assertEquals(
            listOf("$.execution.steps[0].args.text"),
            parameter["bindings"],
        )

        val changed = OobReusableFunctionStore.materialize(
            spec,
            mapOf("input_text" to "world"),
        )
        assertEquals("world", (stepsFrom(changed).single()["args"] as Map<*, *>)["text"])

        val defaulted = OobReusableFunctionStore.materialize(spec, emptyMap())
        assertEquals("hello", (stepsFrom(defaulted).single()["args"] as Map<*, *>)["text"])
    }

    @Test
    fun `pseudo tool dump titles are sanitized for reusable function steps`() {
        val spec = compile(
            listOf(
                card(
                    "open_app",
                    mapOf("package_name" to "com.android.settings"),
                    title = """
                        open_app
                        ```html
                        <arg_key>package_name</arg_key>
                        <arg_value>com.android.settings</arg_value>
                        </tool_call>
                        ```
                    """.trimIndent(),
                ),
            ),
            runId = "run-pseudo-title",
        )

        val step = stepsFrom(spec).single()
        assertEquals("open_app: com.android.settings", step["title"])
    }

    private fun compile(
        cards: List<Map<String, Any?>>,
        runId: String,
    ): Map<String, Any?> {
        val record = InternalRunLogRecord(
            runId = runId,
            goal = "Replay $runId",
            toolName = "test_tool",
            operationDescription = "Replay $runId",
            cards = cards,
        )
        return requireNotNull(RunLogReusableFunctionCompiler.compile(record))
    }

    private fun card(
        toolName: String,
        args: Map<String, Any?>,
        beforeXml: String = "",
        success: Boolean? = null,
        title: String? = null,
        compileKind: String? = null,
        source: String? = null,
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "tool_name" to toolName,
            "title" to title,
            "args" to args,
            "success" to success,
            "compile_kind" to compileKind,
            "source" to source,
            "before" to linkedMapOf(
                "package_name" to "com.example",
                "observation_xml" to beforeXml,
            ),
        ).filterValues { it != null }
    }

    private fun stepsFrom(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val steps = (spec["execution"] as Map<*, *>)["steps"] as List<*>
        return steps.map { raw ->
            (raw as Map<*, *>).entries.associate { (key, value) ->
                key.toString() to value
            }
        }
    }

    private fun capabilitiesFrom(spec: Map<String, Any?>): Map<String, Any?> {
        val capabilities = (spec["execution"] as Map<*, *>)["capabilities"] as Map<*, *>
        return capabilities.entries.associate { (key, value) -> key.toString() to value }
    }

    private fun parametersFrom(spec: Map<String, Any?>): List<Map<String, Any?>> {
        val parameters = spec["parameters"] as List<*>
        return parameters.map { raw ->
            (raw as Map<*, *>).entries.associate { (key, value) ->
                key.toString() to value
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSharedPolicyJson(): Map<String, Any?> {
        val candidates = listOf(
            File("app/src/main/assets/omniflow/runlog/replay_policy.json"),
            File("src/main/assets/omniflow/runlog/replay_policy.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("replay_policy.json not found")
        return Gson().fromJson(file.readText(), Map::class.java) as Map<String, Any?>
    }

    private fun stringSet(value: Any?): Set<String> {
        return (value as? List<*>)
            ?.map { it.toString() }
            ?.toSet()
            .orEmpty()
    }

    private fun stringMap(value: Any?): Map<String, String> {
        return (value as? Map<*, *>)
            ?.entries
            ?.associate { (key, item) -> key.toString() to item.toString() }
            .orEmpty()
    }

    companion object {
        private const val SOURCE_XML =
            "<hierarchy><node bounds=\"[100,200][300,280]\" clickable=\"true\" text=\"Open\"/></hierarchy>"
    }
}
