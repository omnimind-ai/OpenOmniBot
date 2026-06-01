package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames

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
    const val TOOL_AGENT_RUN: String = "oob.agent.run"
    const val TOOL_CALL_TOOL: String = "call_tool"
    const val TOOL_OOB_TOOL_CALL: String = "oob_tool_call"
    const val TOOL_CALL_FUNCTION: String = "call_function"
    const val TOOL_GO_TO_NODE: String = "go_to_node"

    val omniflowActions: Set<String> = OobActionCodec.executableActions

    val omniflowActionAliases: Map<String, String> = OobActionCodec.actionAliases

    val coordinateActions: Set<String> = OobActionCodec.coordinateActions

    val perceptionTools: Set<String> = setOf(
        AgentToolNames.VLM_TASK,
        "image_picker",
        "android_privileged_action_screenshot",
        "screen_capture",
    )

    private val functionDataFlowTools: Set<String> = setOf(
        OobFunctionToolNames.FUNCTION_LIST,
        OobFunctionToolNames.FUNCTION_GET,
        OobFunctionToolNames.FUNCTION_REGISTER,
        OobFunctionToolNames.FUNCTION_UPDATE,
        OobFunctionToolNames.FUNCTION_GUARD_CHECK,
    ) + OobFunctionToolNames.runLogTools

    val dataFlowTools: Set<String> = setOf(
        AgentToolNames.BROWSER_USE,
        AgentToolNames.WEB_SEARCH,
        "memory_search",
        "memory_recall",
        "memory_query",
        "oob_agent_run",
        "omniflow.recall",
        "omniflow.ingest_run_log",
        "workbench_api_list",
    ) + functionDataFlowTools

    val omniflowGraphTools: Set<String> = setOf(
        TOOL_GO_TO_NODE,
        "click_node",
        "node_click",
        "navigate_to_node",
        "gotonode",
        "goto_node",
    )

    val omniflowFunctionTools: Set<String> = setOf(
        "omniflow.call_function",
        TOOL_CALL_FUNCTION,
        OobFunctionToolNames.FUNCTION_RUN,
        "run_function",
        "execute_function",
        "callfunction",
        "runfunction",
        "executefunction",
    )

    val omniflowToolCallTools: Set<String> = setOf(
        "omniflow.call_tool",
        TOOL_CALL_TOOL,
        TOOL_OOB_TOOL_CALL,
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
