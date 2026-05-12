package cn.com.omnimind.bot.mcp

/**
 * Built-in MCP Prompts for OOB Workbench Project workflows.
 *
 * Prompts return standard user instructions only. They do not write files or call Workbench tools
 * by themselves; clients should pass the returned message to `agent_run` or invoke MCP tools.
 */
object McpPromptDefinitions {
    private val promptBodies = linkedMapOf(
        "create_html_project" to """
            Create an OOB Workbench Project with an HTML Display.

            Requirements:
            - Choose a stable oob-workbench-* projectId.
            - Provide htmlFiles with at least frontend/html/index.html content.
            - Target the real-phone right-side Workspace WebView by default using the app-injected Workbench Display layout profile; do not hard-code phone width or height.
            - Use viewport width=device-width for mobile UI and portrait reports. Use a phone-width article layout for portrait reports, with the executive summary in the first measured viewport.
            - Keep search boxes, filters, input fields, and primary actions visible at the narrow right-side WebView width; wrap or stack them instead of hiding them behind desktop breakpoints.
            - Use viewport width=1280 only for explicitly wide reports, slide decks, or landscape comparison canvases.
            - Use window.oob.callApi(apiId, inputs) for all Project Tool calls.
            - Register only Project Tools that the displayed UI needs.
            - Activate the Project after creation.
            - Open the HTML Display when ready.
            - Keep Workbench control tools out of the Project Tool list.
        """.trimIndent(),
        "create_markdown_project" to """
            Create an OOB Workbench Project with a Markdown Display.

            Requirements:
            - Choose a stable oob-workbench-* projectId.
            - Provide markdownFiles with at least frontend/markdown/index.md content.
            - Use Markdown only when the user explicitly asks for Markdown, editable documents, or plain-text long-form output.
            - Register only Project Tools that the document workflow needs.
            - Activate the Project after creation.
            - Open the Markdown Display when ready.
            - Keep Workbench control tools out of the Project Tool list.
        """.trimIndent(),
        "create_project_display" to """
            Create an OOB Workbench Project from the user's domain.

            Requirements:
            - Choose a stable oob-workbench-* projectId.
            - Default to htmlFiles for visible output; choose markdownFiles only for explicitly requested Markdown/editable documents/plain-text long-form output, or the default Project Display for simple structured data.
            - Register only Project Tools such as <entity>.create and <entity>.archive.
            - Activate the Project.
            - Seed initial data through workbench_api_call or the corresponding MCP Toolbox tool.
            - Open the Workbench Display when ready.
        """.trimIndent(),
        "inspect_active_toolbox" to """
            Inspect the currently active OOB Project Toolbox.

            Steps:
            - Read MCP resource oob://projects/active.
            - Read the active Project's oob://projects/{projectId}/toolbox resource.
            - List available MCP Tools and identify dynamic tools in <toolbox>.<tool> form.
            - Do not assume Workbench control tools are Project Tools.
        """.trimIndent(),
        "fix_project_last_error" to """
            Fix the active OOB Workbench Project's last error.

            Steps:
            - Read oob://projects/active.
            - Read oob://projects/{projectId}/logs/api_calls.
            - Identify the latest failed Project Tool call.
            - Propose a minimal fix using the Project contract and existing Workbench tools.
            - If execution is needed, call the Project's dynamic MCP Toolbox tool or ask OOB Agent through agent_run.
        """.trimIndent()
    )

    val prompts: List<Map<String, Any?>>
        get() = promptBodies.map { (name, body) ->
            linkedMapOf(
                "name" to name,
                "description" to body.lineSequence().firstOrNull().orEmpty(),
                "arguments" to emptyList<Map<String, Any?>>()
            )
        }

    /**
     * Returns one MCP Prompt by name.
     *
     * @param name Prompt name from `prompts/list`.
     * @return MCP `prompts/get` payload with a single user message.
     */
    fun getPrompt(name: String): Map<String, Any?> {
        val body = promptBodies[name]
            ?: throw IllegalArgumentException("Unknown prompt: $name")
        return linkedMapOf(
            "description" to body.lineSequence().firstOrNull().orEmpty(),
            "messages" to listOf(
                linkedMapOf(
                    "role" to "user",
                    "content" to linkedMapOf(
                        "type" to "text",
                        "text" to body
                    )
                )
            )
        )
    }
}
