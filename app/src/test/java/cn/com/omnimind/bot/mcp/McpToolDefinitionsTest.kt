package cn.com.omnimind.bot.mcp

import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpToolDefinitionsTest {
    @Test
    fun fixedToolsIncludeAgentRunOobFunctionAndOobProjectControls() {
        val names = McpToolDefinitions.fixedTools.map { it["name"].toString() }.toSet()

        assertTrue(names.contains(AgentToolNames.VLM_TASK))
        assertTrue(names.contains("agent_run"))
        assertTrue(names.contains("oob_tool_call"))
        assertTrue(names.contains("omniflow.recall"))
        assertTrue(names.contains("omniflow.call_tool"))
        assertTrue(!names.contains("omniflow.call_function"))
        assertTrue(names.contains("omniflow.ingest_run_log"))
        assertTrue(names.contains("omniflow.explore_replay"))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_LIST))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_GET))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_REGISTER))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_UPDATE))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_GUARD_CHECK))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_RUN))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_DELETE))
        assertTrue(names.contains(OobFunctionToolNames.FUNCTION_CLEAR))
        assertTrue(names.contains(OobFunctionToolNames.RUN_LOG_LIST))
        assertTrue(names.contains(OobFunctionToolNames.RUN_LOG_GET))
        assertTrue(names.contains(OobFunctionToolNames.RUN_LOG_CONVERT))
        assertTrue(names.contains("oob_project_create"))
        assertTrue(names.contains("oob_project_activate"))
        assertTrue(names.contains("oob_project_open"))
        assertTrue(names.contains("oob_project_progress_get"))
    }

    @Test
    fun fixedToolsAreExplicitlyRoutedLocally() {
        val routeSource = listOf(
            File("app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
            File("src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"),
        ).first { it.exists() }.readText()
        val missingRoutes = McpToolDefinitions.fixedToolNames
            .filterNot { toolName ->
                "\"$toolName\" ->" in routeSource ||
                    functionToolRouteConstants[toolName]?.let { "$it ->" in routeSource } == true
            }

        assertTrue(
            "Fixed MCP tools must be routed before Workbench fallback: $missingRoutes",
            missingRoutes.isEmpty()
        )
    }

    private val functionToolRouteConstants = mapOf(
        AgentToolNames.VLM_TASK to "AgentToolNames.VLM_TASK",
        OobFunctionToolNames.FUNCTION_LIST to "OobFunctionToolNames.FUNCTION_LIST",
        OobFunctionToolNames.FUNCTION_GET to "OobFunctionToolNames.FUNCTION_GET",
        OobFunctionToolNames.FUNCTION_REGISTER to "OobFunctionToolNames.FUNCTION_REGISTER",
        OobFunctionToolNames.FUNCTION_UPDATE to "OobFunctionToolNames.FUNCTION_UPDATE",
        OobFunctionToolNames.FUNCTION_GUARD_CHECK to "OobFunctionToolNames.FUNCTION_GUARD_CHECK",
        OobFunctionToolNames.FUNCTION_RUN to "OobFunctionToolNames.FUNCTION_RUN",
        OobFunctionToolNames.FUNCTION_DELETE to "OobFunctionToolNames.FUNCTION_DELETE",
        OobFunctionToolNames.FUNCTION_CLEAR to "OobFunctionToolNames.FUNCTION_CLEAR",
        OobFunctionToolNames.RUN_LOG_LIST to "OobFunctionToolNames.RUN_LOG_LIST",
        OobFunctionToolNames.RUN_LOG_GET to "OobFunctionToolNames.RUN_LOG_GET",
        OobFunctionToolNames.RUN_LOG_CONVERT to "OobFunctionToolNames.RUN_LOG_CONVERT",
    )

    @Test
    fun exploreReplayToolExposesNativeExplorerControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "omniflow.explore_replay"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("goal"))
        assertTrue(properties.containsKey("package_name"))
        assertTrue(properties.containsKey("max_steps"))
        assertTrue(properties.containsKey("stop_text"))
        assertTrue(properties.containsKey("allow_risky_actions"))
        assertTrue(properties.containsKey("replay"))
        assertTrue(properties.containsKey("reset_before_replay"))
    }

    @Test
    fun oobFunctionRunToolExposesResumeControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == OobFunctionToolNames.FUNCTION_RUN
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val description = tool["description"]?.toString().orEmpty()

        assertTrue(description.contains("fallback_context"))
        assertTrue(properties.containsKey("functionId"))
        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("resume_from_step"))
        assertTrue(properties.containsKey("fallback_session_id"))
        assertTrue(properties.containsKey("fallback_attempt"))
    }

    @Test
    fun updateFunctionToolExposesRunLogAnalysisInputs() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == OobFunctionToolNames.FUNCTION_UPDATE
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val description = tool["description"]?.toString().orEmpty()

        assertTrue(description.contains("run_id"))
        assertTrue(description.contains("analysis_context"))
        assertTrue(properties.containsKey("functionId"))
        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("run_id"))
        assertTrue(properties.containsKey("runId"))
        assertTrue(properties.containsKey("analysis"))
        assertTrue(properties.containsKey("patch"))
    }

    @Test
    fun recallToolExposesPageMatchInputs() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "omniflow.recall"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        val description = tool["description"]?.toString().orEmpty()

        assertTrue(description.contains("page match -> UDEG node -> node skill-like decision context -> VLM/tool decision"))
        assertTrue(properties.containsKey("goal"))
        assertTrue(properties.containsKey("current_package"))
        assertTrue(properties.containsKey("current_node_id"))
        assertTrue(properties.containsKey("current_xml"))
        assertTrue(properties.containsKey("k"))
        assertTrue(properties.containsKey("include_debug"))
        val includeDebug = properties["include_debug"] as Map<*, *>
        assertEquals(false, includeDebug["default"])
    }

    @Test
    fun vlmTaskToolExposesDirectAndroidWorldControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == AgentToolNames.VLM_TASK
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("goal"))
        assertTrue(properties.containsKey("model"))
        assertTrue(properties.containsKey("packageName"))
        assertTrue(properties.containsKey("maxSteps"))
        assertTrue(properties.containsKey("startFromCurrent"))
        assertTrue(properties.containsKey("needSummary"))
        assertTrue(properties.containsKey("allowOmniFlowFunctionAutoExecute"))
        val autoExecute = properties["allowOmniFlowFunctionAutoExecute"] as Map<*, *>
        assertEquals(false, autoExecute["default"])
    }

    @Test
    fun agentRunToolExposesFocusedToolControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "agent_run"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("toolProfile"))
        assertTrue(properties.containsKey("allowedTools"))
        val toolProfile = properties["toolProfile"] as Map<*, *>
        assertEquals(listOf("function_management"), toolProfile["enum"])
    }

    @Test
    fun oobFunctionRegisterToolExposesSimpleConversationSchema() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == OobFunctionToolNames.FUNCTION_REGISTER
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("functionId"))
        assertTrue(properties.containsKey("name"))
        assertTrue(properties.containsKey("description"))
        assertTrue(properties.containsKey("steps"))
        assertTrue(properties.containsKey("sourcePage"))
        assertTrue(properties.containsKey("functionSpec"))
        assertTrue(schema["required"] == null)
    }
}
