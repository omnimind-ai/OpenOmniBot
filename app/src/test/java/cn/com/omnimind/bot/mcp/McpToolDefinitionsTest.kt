package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpToolDefinitionsTest {
    @Test
    fun fixedToolsIncludeAgentRunOobFunctionAndOobProjectControls() {
        val names = McpToolDefinitions.fixedTools.map { it["name"].toString() }.toSet()

        assertTrue(names.contains("vlm_task"))
        assertTrue(names.contains("agent_run"))
        assertTrue(names.contains("oob_tool_call"))
        assertTrue(names.contains("omniflow.recall"))
        assertTrue(names.contains("omniflow.call_tool"))
        assertTrue(!names.contains("omniflow.call_function"))
        assertTrue(names.contains("omniflow.ingest_run_log"))
        assertTrue(names.contains("omniflow.explore_replay"))
        assertTrue(names.contains("oob_function_list"))
        assertTrue(names.contains("oob_function_get"))
        assertTrue(names.contains("oob_function_register"))
        assertTrue(names.contains("oob_function_guard_check"))
        assertTrue(names.contains("oob_function_run"))
        assertTrue(names.contains("oob_run_log_list"))
        assertTrue(names.contains("oob_run_log_get"))
        assertTrue(names.contains("oob_run_log_convert"))
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
            .filterNot { toolName -> "\"$toolName\" ->" in routeSource }

        assertTrue(
            "Fixed MCP tools must be routed before Workbench fallback: $missingRoutes",
            missingRoutes.isEmpty()
        )
    }

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
    }

    @Test
    fun vlmTaskToolExposesDirectAndroidWorldControls() {
        val tool = McpToolDefinitions.fixedTools.single {
            it["name"] == "vlm_task"
        }
        val schema = tool["inputSchema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        assertTrue(properties.containsKey("goal"))
        assertTrue(properties.containsKey("model"))
        assertTrue(properties.containsKey("packageName"))
        assertTrue(properties.containsKey("maxSteps"))
        assertTrue(properties.containsKey("startFromCurrent"))
        assertTrue(properties.containsKey("needSummary"))
    }
}
