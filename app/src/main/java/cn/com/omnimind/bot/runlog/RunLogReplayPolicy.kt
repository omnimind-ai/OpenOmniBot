package cn.com.omnimind.bot.runlog

/**
 * Static replay classification shared by RunLog conversion and local replay.
 *
 * This is not a dispatcher or service layer. Keep execution in
 * [OmniflowStepExecutor], Function storage in
 * [cn.com.omnimind.bot.omniflow.OobFunctionRepository], and tool
 * routing in OobFunctionToolHandler. Canonical action parsing lives in
 * [OobActionCodec]; this policy only owns non-action tool categories.
 */
object RunLogReplayPolicy {
    const val schemaVersion: String = "oob.runlog_replay_policy.v1"
    const val fixedReplayOnly: Boolean = false
    const val fixedReplayRunner: String = "oob_omniflow_loop"
    const val EXECUTOR_OMNIFLOW: String = "omniflow"
    const val EXECUTOR_AGENT: String = "agent"
    const val EXECUTOR_TOOL: String = "tool"

    val omniflowActions: Set<String> = OobActionCodec.executableActions

    val omniflowActionAliases: Map<String, String> = OobActionCodec.actionAliases

    val coordinateActions: Set<String> = OobActionCodec.coordinateActions

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
        "omniflow.recall",
        "omniflow.ingest_run_log",
        "workbench_api_list",
        "oob_function_list",
        "oob_function_get",
        "oob_function_register",
        "update_function",
        "oob_function_guard_check",
        "oob_run_log_list",
        "oob_run_log_get",
        "oob_run_log_convert",
    )

    val omniflowGraphTools: Set<String> = setOf(
        "go_to_node",
        "click_node",
        "node_click",
        "navigate_to_node",
        "gotonode",
        "goto_node",
    )

    val omniflowFunctionTools: Set<String> = setOf(
        "omniflow.call_function",
        "call_function",
        "oob_function_run",
        "run_function",
        "execute_function",
        "callfunction",
        "runfunction",
        "executefunction",
    )

    val omniflowToolCallTools: Set<String> = setOf(
        "omniflow.call_tool",
        "call_tool",
        "oob_tool_call",
        "calltool",
    )

    /**
     * Backward-compatible contract field. These tools used to be provider-only,
     * but OOB now has a native execution layer for OmniFlow graph/function calls.
     */
    val providerOnlyTools: Set<String> = emptySet()

    val skipTools: Set<String> = setOf(
        "notification_send",
        "calendar_event_create",
        "skills_loaded",
        "status_update",
        "assistant_response",
        "get_state",
        "wait",
    )

    fun normalizeToolName(toolName: String): String = toolName.trim().lowercase()

    fun omniflowActionForToolName(toolName: String): String? =
        OobActionCodec.canonicalActionForName(toolName)

    fun isCoordinateAction(toolName: String): Boolean =
        OobActionCodec.canonicalActionForName(toolName) in coordinateActions

    fun isPerceptionTool(toolName: String): Boolean =
        normalizeToolName(toolName) in perceptionTools

    fun isDataFlowTool(toolName: String): Boolean =
        normalizeToolName(toolName) in dataFlowTools

    fun isProviderOnlyTool(toolName: String): Boolean =
        normalizeToolName(toolName) in providerOnlyTools

    fun isOmniflowGraphTool(toolName: String): Boolean =
        normalizeToolName(toolName) in omniflowGraphTools

    fun isOmniflowFunctionTool(toolName: String): Boolean =
        normalizeToolName(toolName) in omniflowFunctionTools

    fun isOmniflowToolCallTool(toolName: String): Boolean =
        normalizeToolName(toolName) in omniflowToolCallTools

    fun isOmniflowExecutionTool(toolName: String): Boolean =
        isOmniflowGraphTool(toolName) ||
            isOmniflowFunctionTool(toolName) ||
            isOmniflowToolCallTool(toolName)

    fun isAgentTool(toolName: String): Boolean =
        isPerceptionTool(toolName) || isDataFlowTool(toolName) || isProviderOnlyTool(toolName)

    fun shouldSkipTool(toolName: String): Boolean =
        normalizeToolName(toolName) in skipTools

    fun agentStepReason(toolName: String): String {
        val normalized = normalizeToolName(toolName)
        return when {
            normalized in perceptionTools -> "perception_only_step_without_recorded_actions"
            normalized in dataFlowTools -> "data_flow_tool_requires_live_context"
            normalized in providerOnlyTools -> "provider_owned_replay_requires_omniflow"
            else -> "non_scriptable_or_vlm_step"
        }
    }

    fun requiresAgentPlanningReason(reason: String): Boolean {
        return reason == "data_flow_tool_requires_live_context" ||
            reason == "perception_only_step_without_recorded_actions"
    }
}
