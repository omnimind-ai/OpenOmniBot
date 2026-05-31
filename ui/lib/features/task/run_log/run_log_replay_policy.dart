class RunLogReplayPolicy {
  const RunLogReplayPolicy._();

  static const schemaVersion = 'oob.runlog_replay_policy.v1';

  static const omniflowActions = <String>{
    'click',
    'long_press',
    'input_text',
    'swipe',
    'open_app',
    'press_key',
    'finished',
  };

  static const omniflowActionAliases = <String, String>{
    'tap': 'click',
    'click_at': 'click',
    'click_element': 'click',
    'clickelement': 'click',
    'longclick': 'long_press',
    'long_click': 'long_press',
    'longpress': 'long_press',
    'type': 'input_text',
    'type_text': 'input_text',
    'set_text': 'input_text',
    'settext': 'input_text',
    'inputtext': 'input_text',
    'scroll': 'swipe',
    'scroll_down': 'swipe',
    'scroll_up': 'swipe',
    'scroll_left': 'swipe',
    'scroll_right': 'swipe',
    'back': 'press_key',
    'press_back': 'press_key',
    'pressback': 'press_key',
    'press_back_button': 'press_key',
    'home': 'press_key',
    'press_home': 'press_key',
    'presshome': 'press_key',
    'press_home_button': 'press_key',
    'hot_key': 'press_key',
    'hotkey': 'press_key',
    'presskey': 'press_key',
    'key_event': 'press_key',
    'keyevent': 'press_key',
    'openapp': 'open_app',
    'launch_app': 'open_app',
    'launchapp': 'open_app',
    'finish': 'finished',
    'done': 'finished',
    'complete': 'finished',
  };

  static const coordinateActions = <String>{
    'click',
    'long_press',
    'input_text',
    'swipe',
  };

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
    'omniflow.recall',
    'omniflow.ingest_run_log',
    'workbench_api_list',
    'oob_function_list',
    'oob_function_get',
    'oob_function_register',
    'update_function',
    'oob_function_guard_check',
    'oob_run_log_list',
    'oob_run_log_get',
    'oob_run_log_convert',
  };

  static const omniflowGraphTools = <String>{
    'go_to_node',
    'click_node',
    'node_click',
    'navigate_to_node',
    'gotonode',
    'goto_node',
  };

  static const omniflowFunctionTools = <String>{
    'omniflow.call_function',
    'call_function',
    'oob_function_run',
    'run_function',
    'execute_function',
    'callfunction',
    'runfunction',
    'executefunction',
  };

  static const omniflowToolCallTools = <String>{
    'omniflow.call_tool',
    'call_tool',
    'oob_tool_call',
    'calltool',
  };

  static const providerOnlyTools = <String>{};

  static const skipTools = <String>{
    'notification_send',
    'calendar_event_create',
    'skills_loaded',
    'status_update',
    'assistant_response',
    'get_state',
    'wait',
  };

  static String normalizeToolName(String toolName) {
    return toolName.trim().toLowerCase();
  }

  static String? omniflowActionForToolName(String toolName) {
    final normalized = normalizeToolName(toolName);
    return omniflowActions.contains(normalized)
        ? normalized
        : omniflowActionAliases[normalized];
  }

  static bool isCoordinateAction(String toolName) {
    final action = omniflowActionForToolName(toolName);
    return action != null && coordinateActions.contains(action);
  }

  static bool isPerceptionTool(String toolName) {
    return perceptionTools.contains(normalizeToolName(toolName));
  }

  static bool isDataFlowTool(String toolName) {
    return dataFlowTools.contains(normalizeToolName(toolName));
  }

  static bool isProviderOnlyTool(String toolName) {
    return providerOnlyTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowGraphTool(String toolName) {
    return omniflowGraphTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowFunctionTool(String toolName) {
    return omniflowFunctionTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowToolCallTool(String toolName) {
    return omniflowToolCallTools.contains(normalizeToolName(toolName));
  }

  static bool isOmniflowExecutionTool(String toolName) {
    return isOmniflowGraphTool(toolName) ||
        isOmniflowFunctionTool(toolName) ||
        isOmniflowToolCallTool(toolName);
  }

  static bool isAgentTool(String toolName) {
    return isPerceptionTool(toolName) ||
        isDataFlowTool(toolName) ||
        isProviderOnlyTool(toolName);
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
    if (providerOnlyTools.contains(normalized)) {
      return 'provider_owned_replay_requires_omniflow';
    }
    return 'non_scriptable_or_vlm_step';
  }
}
