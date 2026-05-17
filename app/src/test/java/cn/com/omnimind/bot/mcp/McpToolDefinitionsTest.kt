package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolDefinitionsTest {
    @Test
    fun fixedToolsIncludeAgentRunAndOobProjectControls() {
        val names = McpToolDefinitions.fixedTools.map { it["name"].toString() }.toSet()

        assertTrue(names.contains("vlm_task"))
        assertTrue(names.contains("agent_run"))
        assertTrue(names.contains("oob_tool_call"))
        assertTrue(names.contains("omniflow.recall"))
        assertTrue(names.contains("omniflow.call_function"))
        assertTrue(names.contains("omniflow.ingest_run_log"))
        assertTrue(names.contains("oob_project_create"))
        assertTrue(names.contains("oob_project_activate"))
        assertTrue(names.contains("oob_project_open"))
        assertTrue(names.contains("oob_project_progress_get"))
        assertFalse(names.contains("oob_function_list"))
        assertFalse(names.contains("oob_function_get"))
        assertFalse(names.contains("oob_function_register"))
        assertFalse(names.contains("oob_function_guard_check"))
        assertFalse(names.contains("oob_function_run"))
        assertFalse(names.contains("oob_run_log_list"))
        assertFalse(names.contains("oob_run_log_get"))
        assertFalse(names.contains("oob_run_log_convert"))
        assertFalse(names.contains("workbench_project_create"))
        assertFalse(names.contains("workbench_api_call"))
    }
}
