package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `manual recording click keeps coordinates and source context for replay`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf(
                        "target_description" to "Bluetooth",
                        "x" to 540f,
                        "y" to 620f,
                        "bounds" to "[40,560][1040,680]",
                        "recording_backend" to "accessibility_event",
                    ),
                    beforeXml = SOURCE_XML,
                    afterXml = AFTER_XML,
                    compileKind = "manual_recording",
                    source = "human_trajectory",
                ),
            ),
            runId = "run-manual-click",
        )

        val click = stepsFrom(spec).single()
        assertEquals("click", click["tool"])
        assertEquals("omniflow", click["executor"])
        assertEquals("omniflow", click["coordinate_hook"])
        val args = click["args"] as Map<*, *>
        assertEquals(540, (args["x"] as Number).toInt())
        assertEquals(620, (args["y"] as Number).toInt())
        assertEquals("Bluetooth", args["target_description"])
        val sourceContext = click["source_context"] as Map<*, *>
        val srcCtx = sourceContext["src_ctx"] as Map<*, *>
        val action = sourceContext["action"] as Map<*, *>
        assertEquals(SOURCE_XML, srcCtx["page"])
        assertEquals("click", action["tool"])
        assertEquals(540, (action["x"] as Number).toInt())
        assertEquals(620, (action["y"] as Number).toInt())
    }

    @Test
    fun `get state observation cards are omitted from reusable function replay`() {
        val spec = compile(
            listOf(
                card("get_state", mapOf("reason" to "refresh current page")),
                card(
                    "click",
                    mapOf("target_description" to "Open", "x" to 120, "y" to 240),
                    beforeXml = SOURCE_XML,
                ),
            ),
            runId = "run-get-state-click",
        )

        val steps = stepsFrom(spec)
        assertEquals(1, steps.size)
        assertEquals("click", steps.single()["tool"])
        assertFalse(steps.any { it["tool"] == "get_state" })

        val observationOnly = InternalRunLogRecord(
            runId = "run-get-state-only",
            goal = "Refresh state",
            toolName = "test_tool",
            operationDescription = "Refresh state",
            cards = listOf(card("get_state", mapOf("reason" to "refresh current page"))),
        )
        assertNull(RunLogReusableFunctionCompiler.compile(observationOnly))
    }

    @Test
    fun `settings toggle run log exports terminal postcondition`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Use Bluetooth", "x" to 120, "y" to 240),
                    beforeXml = SOURCE_XML,
                ),
                card("finished", mapOf("content" to "Bluetooth is on")),
            ),
            runId = "run-settings-toggle",
            goal = "打开蓝牙",
        )

        val terminal = spec["terminal_postconditions"] as? List<*>
        val postcondition = terminal?.single() as? Map<*, *>
        assertEquals("android_settings_toggle", postcondition?.get("kind"))
        assertEquals("打开蓝牙", postcondition?.get("goal"))
        val execution = spec["execution"] as Map<*, *>
        assertEquals(terminal, execution["terminal_postconditions"])
    }

    @Test
    fun `recorded after observation becomes replay postcondition`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Open", "x" to 120, "y" to 240),
                    beforeXml = SOURCE_XML,
                    afterXml = AFTER_XML,
                ),
            ),
            runId = "run-click-after",
        )

        val click = stepsFrom(spec).single()
        val sourceContext = click["source_context"] as Map<*, *>
        val dstCtx = sourceContext["dst_ctx"] as Map<*, *>
        val postcondition = click["postcondition"] as Map<*, *>

        assertEquals(AFTER_XML, dstCtx["page"])
        assertEquals("recorded_after_page_similarity", postcondition["kind"])
        assertEquals("agent", postcondition["fallback"])
    }

    @Test
    fun `weak after observation is repaired from next card before observation`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Open", "x" to 120, "y" to 240),
                    beforeXml = SOURCE_XML,
                    afterXml = EMPTY_FRAME_XML,
                ),
                card(
                    "click",
                    mapOf("target_description" to "Done", "x" to 120, "y" to 240),
                    beforeXml = AFTER_XML,
                ),
            ),
            runId = "run-click-weak-after",
        )

        val firstClick = stepsFrom(spec).first { it["tool"] == "click" }
        val sourceContext = firstClick["source_context"] as Map<*, *>
        val dstCtx = sourceContext["dst_ctx"] as Map<*, *>
        val postcondition = firstClick["postcondition"] as Map<*, *>

        assertEquals(AFTER_XML, dstCtx["page"])
        assertEquals("next_before_observation", dstCtx["repair_source"])
        assertEquals("recorded_after_page_similarity", postcondition["kind"])
    }

    @Test
    fun `builder prepends initial app launch for app scoped replay`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Display", "x" to 360, "y" to 760),
                    beforeXml = SOURCE_XML,
                    beforePackage = "com.android.settings",
                ),
            ),
            runId = "run-app-scoped-click",
        )

        val steps = stepsFrom(spec)
        assertEquals(2, steps.size)
        val openApp = steps[0]
        val click = steps[1]

        assertEquals("open_app", openApp["tool"])
        assertEquals("omniflow", openApp["executor"])
        assertEquals("injected_initial_package_from_runlog", openApp["route_note"])
        assertEquals(
            "com.android.settings",
            (openApp["args"] as Map<*, *>)["package_name"],
        )
        assertEquals(true, (openApp["args"] as Map<*, *>)["reset_task"])
        assertEquals("fresh_task", (openApp["args"] as Map<*, *>)["launch_mode"])
        assertEquals("step_1", openApp["id"])
        assertEquals(0, (openApp["index"] as Number).toInt())
        assertEquals("click", click["tool"])
        assertEquals("step_2", click["id"])
        assertEquals(1, (click["index"] as Number).toInt())
    }

    @Test
    fun `builder prefers page inferred package over transient launcher package`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Search settings", "x" to 360, "y" to 112),
                    beforeXml = SETTINGS_XML,
                    beforePackage = "com.google.android.apps.nexuslauncher",
                ),
            ),
            runId = "run-launcher-foreground-click",
        )

        val openApp = stepsFrom(spec).first()
        assertEquals("open_app", openApp["tool"])
        assertEquals(
            "com.android.settings",
            (openApp["args"] as Map<*, *>)["package_name"],
        )
        assertEquals("injected_initial_package_from_runlog", openApp["route_note"])
    }

    @Test
    fun `builder does not inject launcher package without page evidence`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Open", "x" to 120, "y" to 240),
                    beforeXml = SOURCE_XML,
                    beforePackage = "com.google.android.apps.nexuslauncher",
                ),
            ),
            runId = "run-launcher-only-click",
        )

        val steps = stepsFrom(spec)
        assertEquals(1, steps.size)
        assertEquals("click", steps.single()["tool"])
    }

    @Test
    fun `open app replay step does not inherit transient recorded page postcondition`() {
        val spec = compile(
            listOf(
                card(
                    "open_app",
                    mapOf("package_name" to "com.google.android.deskclock"),
                    beforeXml = ANDROID_CRASH_DIALOG_XML,
                    afterXml = ANDROID_CRASH_DIALOG_XML,
                    beforePackage = "android",
                    afterPackage = "android",
                    title = "打开应用",
                ),
            ),
            runId = "run-open-app-transient-dialog",
        )

        val step = stepsFrom(spec).single()
        assertEquals("open_app", step["tool"])
        assertFalse(step.containsKey("postcondition"))
    }

    @Test
    fun `recorded initial open app is normalized to fresh task launch`() {
        val spec = compile(
            listOf(
                card(
                    "open_app",
                    mapOf("package_name" to "com.android.settings"),
                    title = "打开应用",
                ),
                card(
                    "click",
                    mapOf("target_description" to "Display", "x" to 360, "y" to 760),
                    beforeXml = SETTINGS_XML,
                    beforePackage = "com.android.settings",
                ),
            ),
            runId = "run-recorded-open-app-first",
        )

        val openApp = stepsFrom(spec).first()
        val args = openApp["args"] as Map<*, *>
        assertEquals("open_app", openApp["tool"])
        assertEquals("com.android.settings", args["package_name"])
        assertEquals(true, args["reset_task"])
        assertEquals("fresh_task", args["launch_mode"])
        assertEquals("initial_open_app_fresh_launch", openApp["route_note"])
    }

    @Test
    fun `transient startup bridge click is dropped before stable app step`() {
        val spec = compile(
            listOf(
                card(
                    "open_app",
                    mapOf("package_name" to "com.google.android.deskclock"),
                    afterXml = CLOCK_TIMER_XML,
                    afterPackage = "com.google.android.deskclock",
                    title = "打开 Clock",
                ),
                card(
                    "click",
                    mapOf("target_description" to "Stopwatch tab", "x" to 349, "y" to 1192),
                    beforeXml = CLOCK_BEDTIME_PROMPT_XML,
                    afterXml = CLOCK_TIMER_XML,
                    beforePackage = "com.google.android.deskclock",
                    afterPackage = "com.google.android.deskclock",
                    title = "点击 Stopwatch tab",
                ),
                card(
                    "click",
                    mapOf("target_description" to "Stopwatch", "x" to 503, "y" to 1192),
                    beforeXml = CLOCK_TIMER_XML,
                    afterXml = CLOCK_STOPWATCH_XML,
                    beforePackage = "com.google.android.deskclock",
                    afterPackage = "com.google.android.deskclock",
                    title = "点击 Stopwatch",
                ),
                card(
                    "finished",
                    mapOf("content" to "Stopwatch page visible"),
                    beforeXml = CLOCK_STOPWATCH_XML,
                    beforePackage = "com.google.android.deskclock",
                ),
            ),
            runId = "run-clock-transient-bridge",
        )

        val steps = stepsFrom(spec)
        assertEquals(listOf("open_app", "click", "finished"), steps.map { it["tool"] })
        assertEquals("Stopwatch", (steps[1]["args"] as Map<*, *>)["target_description"])
        val source = spec["source"] as Map<*, *>
        assertEquals(4, source["replayable_card_count"])
        assertEquals(3, source["compiled_replayable_card_count"])
        assertEquals(1, source["transient_startup_bridge_dropped_count"])
    }

    @Test
    fun `manual transient-like click is preserved`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Stopwatch tab", "x" to 349, "y" to 1192),
                    beforeXml = CLOCK_BEDTIME_PROMPT_XML,
                    afterXml = CLOCK_TIMER_XML,
                    beforePackage = "com.google.android.deskclock",
                    afterPackage = "com.google.android.deskclock",
                    title = "人工点击 Stopwatch tab",
                    compileKind = "manual_recording",
                    source = "human_takeover",
                ),
                card(
                    "click",
                    mapOf("target_description" to "Stopwatch", "x" to 503, "y" to 1192),
                    beforeXml = CLOCK_TIMER_XML,
                    afterXml = CLOCK_STOPWATCH_XML,
                    beforePackage = "com.google.android.deskclock",
                    afterPackage = "com.google.android.deskclock",
                    title = "点击 Stopwatch",
                ),
            ),
            runId = "run-manual-transient-preserved",
        )

        val steps = stepsFrom(spec)
        assertEquals(listOf("open_app", "click", "click"), steps.map { it["tool"] })
        assertEquals("Stopwatch tab", (steps[1]["args"] as Map<*, *>)["target_description"])
        assertEquals(0, (spec["source"] as Map<*, *>)["transient_startup_bridge_dropped_count"])
    }

    @Test
    fun `builder infers page package when recorded package disagrees with xml`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf("target_description" to "Apps", "x" to 360, "y" to 977),
                    beforeXml = SETTINGS_XML,
                    afterXml = SETTINGS_APPS_XML,
                    beforePackage = "cn.com.omnimind.bot.debug",
                    afterPackage = "cn.com.omnimind.bot.debug",
                ),
            ),
            runId = "run-click-package-infer",
        )

        val steps = stepsFrom(spec)
        val openApp = steps.first()
        val click = steps.first { it["tool"] == "click" }
        assertEquals("open_app", openApp["tool"])
        assertEquals(
            "com.android.settings",
            (openApp["args"] as Map<*, *>)["package_name"],
        )
        val sourceContext = click["source_context"] as Map<*, *>
        val srcCtx = sourceContext["src_ctx"] as Map<*, *>
        val dstCtx = sourceContext["dst_ctx"] as Map<*, *>

        assertEquals("com.android.settings", srcCtx["package_name"])
        assertEquals("com.android.settings", dstCtx["package_name"])
    }

    @Test
    fun `settings search transition compiles weak transient postcondition`() {
        val spec = compile(
            listOf(
                card(
                    "click",
                    mapOf(
                        "target_description" to "Search settings search_action_bar ViewGroup",
                        "x" to 500,
                        "y" to 120,
                    ),
                    beforeXml = SETTINGS_XML,
                    afterXml = SETTINGS_SEARCH_XML,
                    afterPackage = "com.google.android.settings.intelligence",
                    title = "点击 Search settings search_action_bar ViewGroup",
                ),
            ),
            runId = "run-settings-search",
        )

        val click = stepsFrom(spec).first { it["tool"] == "click" }
        val postcondition = click["postcondition"] as Map<*, *>

        assertEquals("recorded_after_page_similarity", postcondition["kind"])
        assertEquals(true, postcondition["allow_package_only_for_transient_search"])
        assertEquals("settings_search_transition", postcondition["semantic_fallback"])
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
        assertEquals(1, steps.size)
        assertEquals("type", steps[0]["tool"])
        assertEquals("hello", (steps[0]["args"] as Map<*, *>)["content"])
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
        goal: String = "Replay $runId",
    ): Map<String, Any?> {
        val record = InternalRunLogRecord(
            runId = runId,
            goal = goal,
            toolName = "test_tool",
            operationDescription = goal,
            cards = cards,
        )
        return requireNotNull(RunLogReusableFunctionCompiler.compile(record))
    }

    private fun card(
        toolName: String,
        args: Map<String, Any?>,
        beforeXml: String = "",
        afterXml: String = "",
        beforePackage: String = "com.example",
        afterPackage: String = beforePackage,
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
                "package_name" to beforePackage,
                "observation_xml" to beforeXml,
            ),
            "after" to linkedMapOf(
                "package_name" to afterPackage,
                "observation_xml" to afterXml,
            ).takeIf { afterXml.isNotBlank() },
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
        private const val AFTER_XML =
            "<hierarchy><node bounds=\"[100,200][300,280]\" text=\"Done\"/></hierarchy>"
        private const val EMPTY_FRAME_XML =
            "<hierarchy><node class=\"android.widget.FrameLayout\" enabled=\"true\" bounds=\"[0,0][1080,2400]\" /></hierarchy>"
        private const val SETTINGS_XML =
            "<hierarchy><node bounds=\"[32,64][1048,160]\" clickable=\"true\" text=\"Search settings\" resource-id=\"com.android.settings:id/search_action_bar\"/></hierarchy>"
        private const val SETTINGS_APPS_XML =
            "<hierarchy><node bounds=\"[0,0][720,1280]\" text=\"Apps\" resource-id=\"com.android.settings:id/content_parent\"/><node bounds=\"[48,594][273,648]\" text=\"Default apps\" resource-id=\"android:id/title\"/></hierarchy>"
        private const val SETTINGS_SEARCH_XML =
            "<hierarchy><node bounds=\"[20,40][1060,140]\" text=\"Search settings\" resource-id=\"com.google.android.settings.intelligence:id/search_action_bar\"/></hierarchy>"
        private const val ANDROID_CRASH_DIALOG_XML =
            "<hierarchy><node class=\"android.widget.FrameLayout\" package=\"android\" bounds=\"[28,952][1052,1513]\"><node text=\"com.google.androidenv.accessibilityforwarder keeps stopping\" class=\"android.widget.TextView\" package=\"android\" bounds=\"[133,1041][947,1159]\"/><node text=\"Close app\" clickable=\"true\" class=\"android.widget.Button\" package=\"android\" bounds=\"[70,1324][1010,1450]\"/></node></hierarchy>"
        private const val CLOCK_BEDTIME_PROMPT_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[306,937][696,1072]\" package=\"com.google.android.deskclock\" class=\"android.widget.FrameLayout\"><node bounds=\"[306,937][696,1072]\" clickable=\"true\" package=\"com.google.android.deskclock\" class=\"android.view.ViewGroup\"><node bounds=\"[330,970][672,1018]\" text=\"Set a consistent bedtime for better sleep\" package=\"com.google.android.deskclock\" class=\"android.widget.TextView\"/></node></node></hierarchy>"
        private const val CLOCK_TIMER_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1280]\" package=\"com.google.android.deskclock\" class=\"android.widget.FrameLayout\"><node bounds=\"[0,176][720,1072]\" package=\"com.google.android.deskclock\" class=\"android.view.ViewGroup\" resource-id=\"com.google.android.deskclock:id/desk_clock_pager\"><node bounds=\"[256,420][464,520]\" text=\"Timer\" package=\"com.google.android.deskclock\" class=\"android.widget.TextView\" resource-id=\"com.google.android.deskclock:id/timer_title\"/></node><node bounds=\"[288,1072][432,1232]\" clickable=\"true\" package=\"com.google.android.deskclock\" class=\"android.widget.FrameLayout\" resource-id=\"com.google.android.deskclock:id/tab_menu_timer\"><node bounds=\"[318,1168][402,1209]\" text=\"Timer\" package=\"com.google.android.deskclock\" class=\"android.widget.TextView\"/></node><node bounds=\"[432,1072][576,1232]\" clickable=\"true\" package=\"com.google.android.deskclock\" class=\"android.widget.FrameLayout\" content-desc=\"Stopwatch\" resource-id=\"com.google.android.deskclock:id/tab_menu_stopwatch\"><node bounds=\"[433,1168][575,1209]\" text=\"Stopwatch\" package=\"com.google.android.deskclock\" class=\"android.widget.TextView\"/></node></node></hierarchy>"
        private const val CLOCK_STOPWATCH_XML =
            "<hierarchy bounds=\"[0,0][720,1280]\"><node bounds=\"[0,0][720,1280]\" package=\"com.google.android.deskclock\" class=\"android.widget.FrameLayout\"><node bounds=\"[0,176][720,1072]\" package=\"com.google.android.deskclock\" class=\"android.view.ViewGroup\" resource-id=\"com.google.android.deskclock:id/desk_clock_pager\"><node bounds=\"[216,370][504,460]\" text=\"Stopwatch\" package=\"com.google.android.deskclock\" class=\"android.widget.TextView\"/><node bounds=\"[300,780][420,900]\" content-desc=\"Start\" clickable=\"true\" package=\"com.google.android.deskclock\" class=\"android.widget.Button\"/></node><node bounds=\"[432,1072][576,1232]\" clickable=\"true\" selected=\"true\" package=\"com.google.android.deskclock\" class=\"android.widget.FrameLayout\" content-desc=\"Stopwatch\" resource-id=\"com.google.android.deskclock:id/tab_menu_stopwatch\"><node bounds=\"[433,1168][575,1209]\" text=\"Stopwatch\" package=\"com.google.android.deskclock\" class=\"android.widget.TextView\"/></node></node></hierarchy>"
    }
}
