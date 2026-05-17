class RunLogReplayPolicy {
  const RunLogReplayPolicy._();

  static const schemaVersion = 'oob.runlog_replay_policy.v1';

  static const omniflowActions = <String>{
    'click',
    'long_press',
    'scroll',
    'type',
    'open_app',
    'press_home',
    'press_back',
    'hot_key',
    'wait',
  };

  static const coordinateActions = <String>{'click', 'long_press', 'scroll'};

  static const perceptionTools = <String>{
    'vlm_task',
    'image_picker',
    'android_privileged_action_screenshot',
    'screen_capture',
  };

  static const dataFlowTools = <String>{
    'browser_use',
    'web_search',
    'memory_search',
    'memory_recall',
    'memory_query',
    'oob_agent_run',
    'workbench_api_list',
    'oob_run_log_list',
    'oob_run_log_get',
    'oob_run_log_convert',
  };

  static const skipTools = <String>{
    'notification_send',
    'calendar_event_create',
    'skills_loaded',
    'status_update',
  };

  static String normalizeToolName(String toolName) {
    return toolName.trim().toLowerCase();
  }

  static String? omniflowActionForToolName(String toolName) {
    final normalized = normalizeToolName(toolName);
    return omniflowActions.contains(normalized) ? normalized : null;
  }

  static bool isCoordinateAction(String toolName) {
    return coordinateActions.contains(normalizeToolName(toolName));
  }

  static bool isPerceptionTool(String toolName) {
    return perceptionTools.contains(normalizeToolName(toolName));
  }

  static bool isDataFlowTool(String toolName) {
    return dataFlowTools.contains(normalizeToolName(toolName));
  }

  static bool isAgentTool(String toolName) {
    return isPerceptionTool(toolName) || isDataFlowTool(toolName);
  }

  static bool shouldSkipTool(String toolName) {
    return skipTools.contains(normalizeToolName(toolName));
  }

  static String agentStepReason(String toolName) {
    final normalized = normalizeToolName(toolName);
    if (perceptionTools.contains(normalized)) {
      return 'perception_only_step_without_recorded_actions';
    }
    if (dataFlowTools.contains(normalized)) {
      return 'data_flow_tool_requires_live_context';
    }
    return 'non_scriptable_or_vlm_step';
  }
}
