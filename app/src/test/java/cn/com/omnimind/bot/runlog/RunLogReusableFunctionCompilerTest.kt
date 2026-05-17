package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
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
    fun `provider owned omniflow graph tools compile to agent steps`() {
        val spec = compile(
            listOf(
                card("go_to_node", mapOf("node_id" to "node_1")),
                card("call_function", mapOf("function_id" to "func_provider")),
            ),
            runId = "run-provider-owned",
        )

        val steps = stepsFrom(spec)
        assertEquals(2, steps.size)
        for (step in steps) {
            assertEquals("agent", step["executor"])
            assertEquals(false, step["scriptable"])
            assertEquals("oob.agent.run", step["callable_tool"])
            val agentCall = step["agent_call"] as? Map<*, *>
            assertEquals("provider_owned_replay_requires_omniflow", agentCall?.get("reason"))
        }
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
        assertEquals(500, ((steps[1]["args"] as Map<*, *>)["duration_ms"] as Number).toInt())
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
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "tool_name" to toolName,
            "args" to args,
            "before" to linkedMapOf(
                "package_name" to "com.example",
                "observation_xml" to beforeXml,
            ),
        )
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
