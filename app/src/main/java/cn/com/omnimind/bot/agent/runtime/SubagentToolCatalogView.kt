package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject

/**
 * A filtered view over an existing [AgentToolCatalog] that only exposes
 * tools allowed by the active [SubagentProfile]. Any attempt to access a
 * tool outside the whitelist throws [IllegalStateException], preventing
 * a subagent from escalating beyond its declared scope.
 */
class SubagentToolCatalogView(
    private val parent: AgentToolCatalog,
    private val allowed: Set<String>
) : AgentToolCatalog {

    override val toolsForModel: List<ChatCompletionTool> by lazy {
        parent.toolsForModel.filter { tool ->
            tool.function.name in allowed
        }
    }

    override fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor {
        ensureAllowed(toolName)
        return parent.runtimeDescriptor(toolName)
    }

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        ensureAllowed(toolName)
        parent.validateArguments(toolName, arguments)
    }

    private fun ensureAllowed(toolName: String) {
        if (toolName !in allowed) {
            throw IllegalStateException(
                "tool '$toolName' is not allowed for this subagent (whitelist=${allowed.size})"
            )
        }
    }
}
