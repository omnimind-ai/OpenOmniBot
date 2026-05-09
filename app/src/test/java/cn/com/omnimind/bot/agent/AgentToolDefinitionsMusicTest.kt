package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == "browser_use" }
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
