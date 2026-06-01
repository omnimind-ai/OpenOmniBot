package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.omniflow.OobFunctionSkillProfile
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsMusicTest {

    @Test
    fun `music playback tool is exposed in static tools`() {
        val toolNames = AgentToolDefinitions.staticTools()
            .mapNotNull { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull)
            }

        assertTrue(toolNames.contains("music_playback_control"))
    }

    @Test
    fun `web search tool is exposed in static tools`() {
        val toolNames = AgentToolDefinitions.staticTools()
            .mapNotNull { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull)
            }

        assertTrue(toolNames.contains(AgentToolNames.WEB_SEARCH))
    }

    @Test
    fun `call tool wrapper is exposed in static tools`() {
        val toolNames = AgentToolDefinitions.staticTools()
            .mapNotNull { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull)
            }

        assertTrue(toolNames.contains("call_tool"))
    }

    @Test
    fun `workbench delete tool is exposed as control tool`() {
        val toolNames = AgentToolDefinitions.staticTools()
            .mapNotNull { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull)
            }

        assertTrue(toolNames.contains("workbench_project_delete"))
        assertTrue(toolNames.contains("workbench_project_list"))
        assertTrue(toolNames.contains("workbench_project_get"))
        assertTrue(toolNames.contains("workbench_project_activate"))
        assertTrue(toolNames.contains("workbench_project_active_get"))
        assertTrue(toolNames.contains("workbench_project_deactivate"))
        assertTrue(toolNames.contains("workbench_project_ingest_oss"))
        assertTrue(toolNames.contains("workbench_project_progress_get"))
        assertFalse(toolNames.contains("oob_command_save"))
        assertFalse(toolNames.contains("oob_command_list"))
        assertFalse(toolNames.contains("oob_command_delete"))
        assertFalse(toolNames.contains("oob_command_clear"))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_LIST))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_GET))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_REGISTER))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_UPDATE))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_GUARD_CHECK))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_RUN))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_DELETE))
        assertFalse(toolNames.contains(OobFunctionToolNames.FUNCTION_CLEAR))
        assertFalse(toolNames.contains(OobFunctionToolNames.RUN_LOG_LIST))
        assertFalse(toolNames.contains(OobFunctionToolNames.RUN_LOG_GET))
        assertFalse(toolNames.contains(OobFunctionToolNames.RUN_LOG_CONVERT))
    }

    @Test
    fun `workbench create tool accepts source prompt`() {
        val createTool = AgentToolDefinitions.staticTools()
            .first { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull) == "workbench_project_create"
            }
        val function = createTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject

        assertTrue(properties.containsKey("prompt"))
    }

    @Test
    fun `vlm task metadata defaults function auto execution off`() {
        val vlmTool = AgentToolDefinitions.staticTools()
            .first { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull) == AgentToolNames.VLM_TASK
            }
        val function = vlmTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val autoExecute = properties["allowOmniFlowFunctionAutoExecute"] as JsonObject

        assertEquals(false, autoExecute["default"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        assertTrue(
            autoExecute["description"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.contains("默认 false") == true
        )
    }

    @Test
    fun `oob function register exposes simple conversation schema`() {
        val registerTool = OobFunctionSkillProfile.staticToolDefinitions(PromptLocale.ZH_CN)
            .first { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull) == OobFunctionToolNames.FUNCTION_REGISTER
            }
        val function = registerTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject

        assertTrue(properties.containsKey("functionId"))
        assertTrue(properties.containsKey("name"))
        assertTrue(properties.containsKey("description"))
        assertTrue(properties.containsKey("steps"))
        assertTrue(properties.containsKey("sourcePage"))
        assertTrue(properties.containsKey("functionSpec"))
    }

    @Test
    fun `update function exposes runlog analysis inputs`() {
        val updateTool = OobFunctionSkillProfile.staticToolDefinitions(PromptLocale.ZH_CN)
            .first { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull) == OobFunctionToolNames.FUNCTION_UPDATE
            }
        val function = updateTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject

        assertTrue(properties.containsKey("functionId"))
        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("run_id"))
        assertTrue(properties.containsKey("runId"))
        assertTrue(properties.containsKey("analysis"))
        assertTrue(properties.containsKey("patch"))
    }

    @Test
    fun `oob function run requires function id not run id`() {
        val runTool = OobFunctionSkillProfile.staticToolDefinitions(PromptLocale.ZH_CN)
            .first { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull) == OobFunctionToolNames.FUNCTION_RUN
            }
        val function = runTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val required = (parameters["required"] as JsonArray)
            .mapNotNull { it.jsonPrimitive.contentOrNull }

        assertTrue(properties.containsKey("functionId"))
        assertTrue(properties.containsKey("function_id"))
        assertTrue(properties.containsKey("resume_from_step"))
        assertTrue(properties.containsKey("fallback_session_id"))
        assertTrue(properties.containsKey("fallback_attempt"))
        assertFalse(properties.containsKey("run_id"))
        assertTrue(required.contains("functionId"))
        assertFalse(required.contains("run_id"))
    }

    @Test
    fun `workbench hot update accepts drawing frontend context`() {
        val hotUpdateTool = AgentToolDefinitions.staticTools()
            .first { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull) == "workbench_project_hot_update"
            }
        val function = hotUpdateTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val frontendContext = properties["frontendContext"] as JsonObject

        assertTrue(properties.containsKey("frontendContext"))
        assertTrue(
            frontendContext["description"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.contains("drawingPaths") == true
        )
    }

    @Test
    fun `english browser tool metadata is localized`() {
        val browserTool = AgentToolDefinitions.staticTools(PromptLocale.EN_US)
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == AgentToolNames.BROWSER_USE }
        val function = browserTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val toolTitle = properties["tool_title"] as JsonObject

        assertEquals("Browser Action", function["displayName"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("off-screen browser with up to 3 tabs") == true
        )
        assertEquals(
            "A concise title describing what this tool call is doing. It is shown to the user, should stay short, and should use the same language as the user.",
            toolTitle["description"]?.jsonPrimitive?.contentOrNull
        )
    }

    @Test
    fun `english web search metadata is localized`() {
        val webSearchTool = AgentToolDefinitions.staticTools(PromptLocale.EN_US)
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == AgentToolNames.WEB_SEARCH }
        val function = webSearchTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val query = properties["query"] as JsonObject

        assertEquals("Web Search", function["displayName"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("Run one web search") == true
        )
        assertEquals("Search query or question.", query["description"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `browser get cookies keywords schema declares a concrete type`() {
        val browserTool = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN)
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == AgentToolNames.BROWSER_USE }
        val function = browserTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val keywords = properties["keywords"] as JsonObject

        assertEquals("string", keywords["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            keywords["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("get_cookies 的 cookie 名过滤关键词") == true
        )
    }

    @Test
    fun `schedule subagent tool description requires execution prompt instead of scheduling wording`() {
        val scheduleTool = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN)
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == "schedule_task_create" }
        val function = scheduleTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val subagentPrompt = properties["subagentPrompt"] as JsonObject

        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("targetKind=subagent") == true
        )
        assertTrue(
            subagentPrompt["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("真正要完成的动作") == true
        )
    }

    @Test
    fun `artifact ref treats pdf and html as inline renderable resources`() {
        val pdf = ArtifactRef(
            id = "pdf",
            uri = "omnibot://workspace/docs/spec.pdf",
            title = "spec.pdf",
            mimeType = "application/pdf",
            size = 128,
            sourceTool = "test",
            workspacePath = "/workspace/docs/spec.pdf",
            androidPath = "/tmp/spec.pdf",
            previewKind = "pdf"
        )
        val html = pdf.copy(
            id = "html",
            uri = "omnibot://workspace/docs/index.html",
            title = "index.html",
            mimeType = "text/html",
            androidPath = "/tmp/index.html",
            previewKind = "html"
        )

        assertEquals("pdf", pdf.embedKind)
        assertTrue(pdf.inlineRenderable)
        assertEquals("[spec.pdf](omnibot://workspace/docs/spec.pdf)", pdf.renderMarkdown)

        assertEquals("html", html.embedKind)
        assertTrue(html.inlineRenderable)
        assertEquals("[index.html](omnibot://workspace/docs/index.html)", html.renderMarkdown)
    }
}
