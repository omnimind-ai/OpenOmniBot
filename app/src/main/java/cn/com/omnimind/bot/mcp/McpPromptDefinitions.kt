package cn.com.omnimind.bot.mcp

/**
 * Built-in MCP Prompts for OOB Workbench Project workflows.
 *
 * Prompts return standard user instructions only. They do not write files or call Workbench APIs
 * by themselves; clients should pass the returned message to `agent_run` or invoke MCP tools.
 */
object McpPromptDefinitions {
    private val promptBodies = linkedMapOf(
        "create_quick_capture_project" to """
            Create an OOB Workbench Project for quick capture / 随手记.

            Requirements:
            - projectId: oob-workbench-v01-quick-note
            - templateId: quick_capture_inbox
            - name: 随手记 Inbox
            - It should capture text, links, shared content, and screenshot references.
            - Activate the Project after creation.
            - Seed one sample note through the Project business API, not by editing data files.
            - Open the native OOB Display when ready.
            - Keep Workbench control APIs out of the Project business API list.
        """.trimIndent(),
        "create_schema_project" to """
            Create an OOB Workbench schema_app Project from the user's domain.

            Requirements:
            - Choose a stable oob-workbench-* projectId.
            - Use templateId=schema_app unless the user explicitly requested a different template.
            - Register only business APIs such as <entity>.create and <entity>.archive.
            - Activate the Project.
            - Seed initial data through workbench_api_call or the corresponding MCP Toolbox tool.
            - Open the native OOB Display when ready.
        """.trimIndent(),
        "inspect_active_toolbox" to """
            Inspect the currently active OOB Project Toolbox.

            Steps:
            - Read MCP resource oob://projects/active.
            - Read the active Project's oob://projects/{projectId}/toolbox resource.
            - List available MCP Tools and identify dynamic tools in <toolbox>.<api> form.
            - Do not assume Workbench control APIs are Project business tools.
        """.trimIndent(),
        "fix_project_last_error" to """
            Fix the active OOB Workbench Project's last error.

            Steps:
            - Read oob://projects/active.
            - Read oob://projects/{projectId}/logs/api_calls.
            - Identify the latest failed Project business API call.
            - Propose a minimal fix using the Project contract and existing Workbench APIs.
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
