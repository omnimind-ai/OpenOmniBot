package cn.com.omnimind.bot.agent

/**
 * Controls which tools are exposed to the model for a single Agent run.
 *
 * The router can still execute known tools when they are delegated internally;
 * this policy only reduces the tool schema list sent to the LLM. That keeps
 * default Agent behavior unchanged while letting focused flows avoid paying for
 * every tool schema on each round.
 */
data class AgentToolExposurePolicy(
    val profile: String? = null,
    val allowedTools: Set<String>? = null,
) {
    fun isDefault(): Boolean = normalizedAllowedTools().isNullOrEmpty() &&
        normalizeProfile(profile).isEmpty()

    fun normalizedAllowedTools(): Set<String>? = allowedTools
        ?.mapNotNull { normalizeToolName(it) }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }

    fun isFunctionManagementProfile(): Boolean =
        normalizeProfile(profile) == PROFILE_FUNCTION_MANAGEMENT

    fun effectiveAllowedTools(): Set<String>? {
        val explicit = normalizedAllowedTools()
        if (!explicit.isNullOrEmpty()) return explicit
        return allowedToolsForProfile(profile)
    }

    companion object {
        const val PROFILE_FUNCTION_MANAGEMENT = "function_management"

        val FUNCTION_MANAGEMENT_TOOLS: Set<String> = setOf(
            "context_apps_query",
            "vlm_task",
            "oob_function_list",
            "oob_function_get",
            "oob_function_register",
            "oob_function_guard_check",
            "oob_function_run",
            "oob_function_delete",
            "oob_function_clear",
            "oob_run_log_list",
            "oob_run_log_get",
            "oob_run_log_convert",
        )

        val DEFAULT = AgentToolExposurePolicy()

        fun fromRaw(profile: String?, allowedTools: Collection<*>?): AgentToolExposurePolicy {
            return AgentToolExposurePolicy(
                profile = normalizeProfile(profile).takeIf { it.isNotEmpty() },
                allowedTools = allowedTools
                    ?.mapNotNull { normalizeToolName(it?.toString()) }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() },
            )
        }

        fun allowedToolsForProfile(profile: String?): Set<String>? {
            return when (normalizeProfile(profile)) {
                PROFILE_FUNCTION_MANAGEMENT -> FUNCTION_MANAGEMENT_TOOLS
                else -> null
            }
        }

        fun normalizeProfile(profile: String?): String = profile
            ?.trim()
            ?.lowercase()
            ?.replace('-', '_')
            .orEmpty()

        private fun normalizeToolName(raw: String?): String? = raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
