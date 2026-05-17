package cn.com.omnimind.bot.runlog

object RunLogReplayPolicy {
    const val schemaVersion: String = "oob.runlog_replay_policy.v1"

    val omniflowActions: Set<String> = setOf(
        "click",
        "long_press",
        "scroll",
        "type",
        "open_app",
        "press_home",
        "press_back",
        "hot_key",
        "wait",
    )

    val coordinateActions: Set<String> = setOf("click", "long_press", "scroll")

    val perceptionTools: Set<String> = setOf(
        "vlm_task",
        "image_picker",
        "android_privileged_action_screenshot",
        "screen_capture",
    )

    val dataFlowTools: Set<String> = setOf(
        "browser_use",
        "web_search",
        "memory_search",
        "memory_recall",
        "memory_query",
        "oob_agent_run",
        "workbench_api_list",
        "oob_run_log_list",
        "oob_run_log_get",
        "oob_run_log_convert",
    )

    val skipTools: Set<String> = setOf(
        "notification_send",
        "calendar_event_create",
        "skills_loaded",
        "status_update",
    )

    fun normalizeToolName(toolName: String): String = toolName.trim().lowercase()

    fun omniflowActionForToolName(toolName: String): String? {
        val normalized = normalizeToolName(toolName)
        return normalized.takeIf { it in omniflowActions }
    }

    fun isCoordinateAction(toolName: String): Boolean =
        normalizeToolName(toolName) in coordinateActions

    fun isPerceptionTool(toolName: String): Boolean =
        normalizeToolName(toolName) in perceptionTools

    fun isDataFlowTool(toolName: String): Boolean =
        normalizeToolName(toolName) in dataFlowTools

    fun isAgentTool(toolName: String): Boolean =
        isPerceptionTool(toolName) || isDataFlowTool(toolName)

    fun shouldSkipTool(toolName: String): Boolean =
        normalizeToolName(toolName) in skipTools

    fun agentStepReason(toolName: String): String {
        val normalized = normalizeToolName(toolName)
        return when {
            normalized in perceptionTools -> "perception_only_step_without_recorded_actions"
            normalized in dataFlowTools -> "data_flow_tool_requires_live_context"
            else -> "non_scriptable_or_vlm_step"
        }
    }

    fun requiresAgentPlanningReason(reason: String): Boolean {
        return reason == "data_flow_tool_requires_live_context" ||
            reason == "perception_only_step_without_recorded_actions"
    }
}
