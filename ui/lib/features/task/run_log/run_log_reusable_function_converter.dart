import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:ui/features/task/run_log/run_log_replay_policy.dart';
import 'package:ui/services/assists_core_service.dart';

class RunLogReusableFunctionSpec {
  const RunLogReusableFunctionSpec({
    required this.json,
    required this.agentPrompt,
    required this.aiEnhanced,
    this.warning,
    this.rawAiText,
  });

  final Map<String, dynamic> json;
  final String agentPrompt;
  final bool aiEnhanced;
  final String? warning;
  final String? rawAiText;

  String get functionId => (json['function_id'] ?? '').toString();
  String get name => (json['name'] ?? '').toString();

  int get stepCount {
    final execution = _asStringKeyMap(json['execution']);
    final steps = execution['steps'];
    return steps is List ? steps.length : 0;
  }

  int get parameterCount {
    final parameters = json['parameters'];
    return parameters is List ? parameters.length : 0;
  }

  String get prettyJson => const JsonEncoder.withIndent('  ').convert(json);
}

class RunLogReusableFunctionConverter {
  const RunLogReusableFunctionConverter._();

  static Future<RunLogReusableFunctionSpec> convert({
    required String runId,
    required String title,
    required Map<String, dynamic> payload,
    required List<Map<String, dynamic>> cards,
    bool useAi = true,
    bool useEnglish = false,
  }) async {
    final baseJson = await buildLocalFunctionJsonAsync(
      runId: runId,
      title: title,
      payload: payload,
      cards: cards,
      useEnglish: useEnglish,
    );

    if (!useAi || cards.isEmpty) {
      final agentPrompt = await buildAgentPromptAsync(
        baseJson,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: baseJson,
        agentPrompt: agentPrompt,
        aiEnhanced: false,
      );
    }

    final prompt = await buildAiPromptAsync(baseJson, useEnglish: useEnglish);
    try {
      final raw = await AssistsMessageService.postLLMChat(
        text: prompt,
        model: 'scene.compactor.context',
        responseJsonObject: true,
      );
      var aiJson = await extractJsonObjectAsync(raw ?? '');
      String? repairRaw;
      if (aiJson == null && (raw ?? '').trim().isNotEmpty) {
        repairRaw = await AssistsMessageService.postLLMChat(
          text: await buildJsonRepairPromptAsync(
            invalidOutput: raw ?? '',
            fallbackJson: baseJson,
            useEnglish: useEnglish,
          ),
          model: 'scene.compactor.context',
          responseJsonObject: true,
        );
        aiJson = await extractJsonObjectAsync(repairRaw ?? '');
      }
      if (aiJson == null) {
        final agentPrompt = await buildAgentPromptAsync(
          baseJson,
          useEnglish: useEnglish,
        );
        return RunLogReusableFunctionSpec(
          json: baseJson,
          agentPrompt: agentPrompt,
          aiEnhanced: false,
          rawAiText: repairRaw ?? raw,
          warning: _text(
            useEnglish,
            'AI 未返回可解析 JSON，已使用本地规则生成，可继续注册和执行。',
            'AI did not return parseable JSON. Using the local conversion.',
          ),
        );
      }
      final normalized = await normalizeAiJsonAsync(aiJson, baseJson);
      final agentPrompt = await buildAgentPromptAsync(
        normalized,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: normalized,
        agentPrompt: agentPrompt,
        aiEnhanced: true,
        rawAiText: raw,
      );
    } catch (error) {
      final agentPrompt = await buildAgentPromptAsync(
        baseJson,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: baseJson,
        agentPrompt: agentPrompt,
        aiEnhanced: false,
        warning: useEnglish
            ? 'AI conversion failed. Using the local conversion: $error'
            : 'AI 转换失败，已使用本地转换结果：$error',
      );
    }
  }

  static Map<String, dynamic> buildLocalFunctionJson({
    required String runId,
    required String title,
    required Map<String, dynamic> payload,
    required List<Map<String, dynamic>> cards,
    bool useEnglish = false,
  }) {
    final goal = _firstNonBlank([
      payload['goal'],
      payload['task_goal'],
      payload['operation_description'],
      payload['operationDescription'],
      title,
    ]);
    final steps = <Map<String, dynamic>>[];
    final parametersBySignature = <String, Map<String, dynamic>>{};
    final seenParameterNames = <String>{};
    final snapshots = cards
        .asMap()
        .entries
        .map(
          (entry) => _RunLogActionSnapshot.fromCard(
            entry.value,
            fallbackIndex: entry.key,
          ),
        )
        .toList(growable: false);
    final hasRecordedReplayStep = snapshots.any(
      (snapshot) =>
          RunLogReplayPolicy.omniflowActionForToolName(snapshot.toolName) !=
          null,
    );

    for (var index = 0; index < snapshots.length; index++) {
      final snapshot = snapshots[index];
      final args = _jsonSafe(snapshot.args);
      final shouldSkipPerceptionStep =
          RunLogReplayPolicy.isPerceptionTool(snapshot.toolName) &&
          hasRecordedReplayStep;
      // Skip vlm_task outer calls entirely when concrete recorded actions are
      // present. The VLM-driven actions (click/scroll/type with compile_kind
      // metadata) are recorded as separate omniflow cards with source_context
      // for coordinate remapping.
      if (shouldSkipPerceptionStep) continue;

      final executionIndex = steps.length;
      final stepId = 'step_${executionIndex + 1}';
      final executor = _executorForSnapshot(
        snapshot,
        skipPerceptionStep: shouldSkipPerceptionStep,
      );
      final replayAction = RunLogReplayPolicy.omniflowActionForToolName(
        snapshot.toolName,
      );
      final modelFree = executor == 'omniflow';
      final emittedToolName = modelFree && replayAction != null
          ? replayAction
          : snapshot.toolName;
      final scriptable = executor != 'agent';
      final coordinateHook = _buildCoordinateHookMetadata(
        snapshot: snapshot,
        args: args,
      );
      final prompt = snapshot.prompt;
      final fallbackPrompt = _stepFallbackPrompt(
        title: snapshot.title,
        toolName: snapshot.toolName,
        args: args,
        prompt: prompt,
        useEnglish: useEnglish,
      );
      final summary = _stepSummary(
        title: snapshot.title,
        toolName: snapshot.toolName,
        prompt: prompt,
        args: args,
        result: snapshot.result,
      );
      steps.add({
        'id': stepId,
        'index': executionIndex,
        if (executionIndex != index) 'source_index': index,
        'kind': _stepKindForToolName(emittedToolName, snapshot.route),
        'title': snapshot.title.isNotEmpty ? snapshot.title : emittedToolName,
        if (summary.isNotEmpty) 'summary': summary,
        'tool': emittedToolName,
        'callable_tool': executor == 'agent'
            ? 'oob.agent.run'
            : emittedToolName,
        if (emittedToolName != snapshot.toolName)
          'source_tool': snapshot.toolName,
        'executor': executor,
        'scriptable': scriptable,
        if (modelFree) 'model_free': true,
        if (replayAction != null) 'omniflow_action': replayAction,
        if (coordinateHook != null) ...coordinateHook,
        'args': args,
        if (prompt.isNotEmpty)
          'prompt': {
            'text': prompt,
            'preview': _compactPreview(prompt, maxLength: 240),
            'source': snapshot.promptSource,
          },
        'tool_binding': {
          'kind': executor == 'agent'
              ? 'agent_replan'
              : executor == 'omniflow'
              ? 'omniflow_action'
              : 'oob_agent_tool',
          'name': emittedToolName,
          if (executor == 'agent') 'callable_tool': 'oob.agent.run',
        },
        if (executor == 'agent')
          'agent_call': {
            'tool': 'oob.agent.run',
            'args': {
              'prompt': fallbackPrompt,
              'original_tool': snapshot.toolName,
              'original_args': args,
              if (prompt.isNotEmpty) 'original_prompt': prompt,
            },
            'reason': RunLogReplayPolicy.agentStepReason(snapshot.toolName),
          },
        if (snapshot.route.isNotEmpty) 'route': snapshot.route,
        if (snapshot.success != null) 'success': snapshot.success,
        if (snapshot.durationMs != null) 'duration_ms': snapshot.durationMs,
        if (snapshot.packageName.isNotEmpty)
          'package_name': snapshot.packageName,
        if (!_isEmptyJsonValue(snapshot.result))
          'observed_result': snapshot.result,
        if (snapshot.beforeSummary.isNotEmpty)
          'before_state': snapshot.beforeSummary,
        if (snapshot.afterSummary.isNotEmpty)
          'after_state': snapshot.afterSummary,
        'validation': {
          'mode': 'soft_state_match',
          if (snapshot.packageName.isNotEmpty)
            'package_name': snapshot.packageName,
          if (snapshot.beforeSummary.isNotEmpty)
            'expected_before_state': snapshot.beforeSummary,
        },
        'fallback': {
          'kind': 'agent_replan',
          'tool': 'oob.agent.run',
          'when': scriptable
              ? 'state_or_selector_mismatch'
              : 'always_for_agent_executor',
          'prompt': fallbackPrompt,
        },
        'reuse_policy': {
          'mode': 'prefer_script_then_agent_replan',
          'allow_agent_replan_on_mismatch': true,
          'requires_runtime_validation': true,
        },
      });

      _collectParametersFromArgs(
        stepId: stepId,
        stepIndex: executionIndex,
        toolName: snapshot.toolName,
        args: args,
        parametersBySignature: parametersBySignature,
        seenNames: seenParameterNames,
        useEnglish: useEnglish,
        bindAgentOriginalArgs: executor == 'agent',
      );
    }

    final parameters = parametersBySignature.values.toList(growable: false);
    final packageName = _firstNonBlank([
      ...steps.map((step) => step['package_name']),
      payload['final_package_name'],
      payload['package_name'],
    ]);
    final name = _normalizeFunctionName(
      goal.isNotEmpty ? goal : title,
      fallback: 'reusable_run_${_compactId(runId)}',
    );
    final now = DateTime.now().toUtc().toIso8601String();

    final startState = _firstNonBlank([
      _asStringKeyMap(
        cards.isEmpty ? null : cards.first['before'],
      )['page_title'],
      _asStringKeyMap(cards.isEmpty ? null : cards.first['before'])['activity'],
      _asStringKeyMap(
        cards.isEmpty ? null : cards.first['before'],
      )['package_name'],
    ]);
    final endState = _firstNonBlank([
      _asStringKeyMap(cards.isEmpty ? null : cards.last['after'])['page_title'],
      _asStringKeyMap(cards.isEmpty ? null : cards.last['after'])['activity'],
      _asStringKeyMap(
        cards.isEmpty ? null : cards.last['after'],
      )['package_name'],
    ]);

    return {
      'schema_version': 'oob.reusable_function.v1',
      'function_id': 'runlog_${_compactId(runId)}',
      'name': name,
      'description': goal.isNotEmpty ? goal : name,
      'source': {
        'kind': 'run_log',
        'run_id': runId,
        'title': title,
        'converted_at': now,
        'converter': 'oob_run_log_reusable_function_converter',
      },
      'parameters': parameters,
      'constraints': {
        if (packageName.isNotEmpty) 'package_name': packageName,
        if (startState.isNotEmpty) 'start_state': startState,
        if (endState.isNotEmpty) 'end_state': endState,
      },
      'execution': {
        'kind': 'tool_sequence',
        'runner': 'oob_tool_sequence',
        'entrypoint': 'execute',
        'capabilities': {
          'scriptable_step_count': steps
              .where((step) => step['scriptable'] == true)
              .length,
          'model_free_step_count': steps
              .where((step) => step['model_free'] == true)
              .length,
          'omniflow_step_count': steps
              .where((step) => step['executor'] == 'omniflow')
              .length,
          'agent_step_count': steps
              .where((step) => step['executor'] == 'agent')
              .length,
          'requires_agent_fallback': steps.any(
            (step) => step['executor'] == 'agent',
          ),
        },
        'fallback_runner': 'oob.agent.run',
        'steps': steps,
      },
    };
  }

  static Future<Map<String, dynamic>> buildLocalFunctionJsonAsync({
    required String runId,
    required String title,
    required Map<String, dynamic> payload,
    required List<Map<String, dynamic>> cards,
    bool useEnglish = false,
  }) {
    return compute(_buildLocalFunctionJsonInIsolate, {
      'runId': runId,
      'title': title,
      'payload': _jsonSafeMap(payload),
      'cards': cards.map(_jsonSafeMap).toList(growable: false),
      'useEnglish': useEnglish,
    });
  }

  static Future<String> buildAiPromptAsync(
    Map<String, dynamic> baseJson, {
    bool useEnglish = false,
  }) {
    return compute(_buildAiPromptInIsolate, {
      'baseJson': _jsonSafeMap(baseJson),
      'useEnglish': useEnglish,
    });
  }

  static Future<String> buildJsonRepairPromptAsync({
    required String invalidOutput,
    required Map<String, dynamic> fallbackJson,
    bool useEnglish = false,
  }) {
    return compute(_buildJsonRepairPromptInIsolate, {
      'invalidOutput': invalidOutput,
      'fallbackJson': _jsonSafeMap(fallbackJson),
      'useEnglish': useEnglish,
    });
  }

  static Future<Map<String, dynamic>?> extractJsonObjectAsync(String raw) {
    return compute(_extractJsonObjectInIsolate, raw);
  }

  static Future<Map<String, dynamic>> normalizeAiJsonAsync(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    return compute(_normalizeAiJsonInIsolate, {
      'aiJson': _jsonSafeMap(aiJson),
      'fallback': _jsonSafeMap(fallback),
    });
  }

  static Future<String> buildAgentPromptAsync(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    return compute(_buildAgentPromptInIsolate, {
      'functionJson': _jsonSafeMap(functionJson),
      'useEnglish': useEnglish,
    });
  }

  static String buildAgentPrompt(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    final functionId = (functionJson['function_id'] ?? '').toString();
    final name = (functionJson['name'] ?? functionId).toString();
    final description = (functionJson['description'] ?? '').toString();
    final parameters = functionJson['parameters'];
    final parameterLines = parameters is List && parameters.isNotEmpty
        ? parameters
              .map((item) {
                final map = _asStringKeyMap(item);
                final defaultValue = map.containsKey('default')
                    ? useEnglish
                          ? ' default=${map['default']}'
                          : ' 默认值=${map['default']}'
                    : '';
                return '- ${map['name']}: ${map['description'] ?? map['type']}$defaultValue';
              })
              .join('\n')
        : useEnglish
        ? '- No explicit parameters'
        : '- 无显式参数';

    if (useEnglish) {
      return [
        'You can reuse this OOB function.',
        '',
        'Function: $name',
        'Function ID: $functionId',
        if (description.isNotEmpty) 'Goal: $description',
        '',
        'Parameters:',
        parameterLines,
        '',
        'Execution strategy:',
        '1. Prefer executing materialized execution.steps in order from the JSON.',
        '2. For executor=omniflow/model_free, execute the local replay action without a model call.',
        '3. For executor=tool, call step.callable_tool with the materialized step.args after validation.',
        '4. For executor=agent or validation mismatch, call step.agent_call.tool / fallback.tool and re-plan that step from the current screen.',
        '5. Runtime arguments are applied through parameters.bindings before execution.',
        '',
        'Function JSON:',
        const JsonEncoder.withIndent('  ').convert(functionJson),
      ].join('\n');
    }

    return [
      '你可以复用这个 OOB function。',
      '',
      'Function: $name',
      'Function ID: $functionId',
      if (description.isNotEmpty) '目标: $description',
      '',
      '参数:',
      parameterLines,
      '',
      '执行策略:',
      '1. 优先按已物化的 execution.steps 顺序执行。',
      '2. executor=omniflow/model_free 时直接执行本地动作，不需要模型调用。',
      '3. executor=tool 时，先检查 validation，再用 step.callable_tool 和已物化 step.args 调工具。',
      '4. executor=agent 或状态不匹配时，调用 step.agent_call.tool / fallback.tool，从当前屏幕重规划该步。',
      '5. 运行时参数会先通过 parameters.bindings 写入对应 step args。',
      '',
      'Function JSON:',
      const JsonEncoder.withIndent('  ').convert(functionJson),
    ].join('\n');
  }

  static String _buildAiPrompt(
    Map<String, dynamic> baseJson, {
    bool useEnglish = false,
  }) {
    final compact = const JsonEncoder.withIndent('  ').convert(baseJson);
    if (useEnglish) {
      return '''
You are the OOB/OmniFlow trajectory compiler. Convert the draft extracted from RunLog below into reusable function JSON.

Requirements:
- Output exactly one JSON object. Do not use Markdown and do not explain.
- The first non-whitespace character must be "{" and the last non-whitespace character must be "}".
- Do not wrap the JSON in code fences. Do not include comments, prose, XML, YAML, or bullet lists.
- Keep schema_version = "oob.reusable_function.v1".
- Preserve execution.steps order, tool names, and key args. Do not invent tools that do not exist.
- You may rewrite name/description to make it a clearer reusable function name.
- You may refine parameters: abstract hard-coded user input, search terms, message text, URLs, and target objects into parameters; do not abstract coordinate x/y into user parameters.
- Every parameter must include name/type/description/bindings/default. bindings must be a JSONPath string array pointing to execution.steps[*].args.
- Preserve or improve step executor/model_free/scriptable/omniflow_action/callable_tool/agent_call/validation/fallback fields.
- Keep model_free omniflow actions model-free; do not turn click/scroll/type/open_app/back/home/hot_key/wait into agent steps.
- Keep data-flow and perception tools as executor=agent with callable_tool=oob.agent.run; do not turn browser_use/web_search/memory/oob_run_log tools into direct tool replay.
- Output must be consumable by both the agent and the script runner.

Draft JSON:
$compact
''';
    }
    return '''
你是 OOB/OmniFlow 的轨迹编译器。请把下面由 RunLog 抽取得到的草稿，整理成可复用的 function JSON。

要求：
- 只能输出一个 JSON object，不要 Markdown，不要解释。
- 第一个非空白字符必须是 "{"，最后一个非空白字符必须是 "}"。
- 不要使用代码块，不要包含注释、说明文字、XML、YAML 或列表解释。
- 保持 schema_version = "oob.reusable_function.v1"。
- 保留 execution.steps 的顺序、工具名和关键 args，不要编造不存在的工具。
- 可以重写 name/description，使其更像可复用功能名。
- 可以整理 parameters：把硬编码的用户输入、搜索词、消息文本、URL、目标对象抽象成参数；不要把坐标 x/y 抽象成用户参数。
- 每个 parameter 必须包含 name/type/description/bindings/default，其中 bindings 是 JSONPath 字符串数组，指向 execution.steps[*].args。
- 保留或优化每步的 executor/model_free/scriptable/omniflow_action/callable_tool/agent_call/validation/fallback 字段。
- 保持 model_free omniflow 动作无模型执行，不要把 click/scroll/type/open_app/back/home/hot_key/wait 改成 agent 步骤。
- 保持 data-flow 和感知工具为 executor=agent 且 callable_tool=oob.agent.run；不要把 browser_use/web_search/memory/oob_run_log 工具改成直接 tool replay。
- 输出必须能被 agent 和 script 执行器共同消费。

草稿 JSON：
$compact
''';
  }

  static String _buildJsonRepairPrompt({
    required String invalidOutput,
    required Map<String, dynamic> fallbackJson,
    bool useEnglish = false,
  }) {
    final fallback = const JsonEncoder.withIndent('  ').convert(fallbackJson);
    if (useEnglish) {
      return '''
Repair the model output into exactly one valid JSON object.

Hard requirements:
- Return raw JSON only. The first non-whitespace character must be "{" and the last must be "}".
- Do not use Markdown, code fences, comments, or explanations.
- Keep schema_version = "oob.reusable_function.v1".
- If the invalid output is unusable, return a valid improved version of the fallback JSON.

Invalid output:
$invalidOutput

Fallback JSON:
$fallback
''';
    }
    return '''
把模型输出修复成一个合法 JSON object。

硬性要求：
- 只返回原始 JSON。第一个非空白字符必须是 "{"，最后一个必须是 "}"。
- 不要 Markdown、代码块、注释或解释。
- 保持 schema_version = "oob.reusable_function.v1"。
- 如果原输出不可用，就基于 fallback JSON 返回一个合法的优化版本。

无效输出：
$invalidOutput

Fallback JSON：
$fallback
''';
  }

  static Map<String, dynamic> _normalizeAiJson(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    final normalized = Map<String, dynamic>.from(aiJson);
    normalized['schema_version'] = 'oob.reusable_function.v1';
    normalized['function_id'] = _firstNonBlank([
      normalized['function_id'],
      fallback['function_id'],
    ]);
    normalized['source'] = _mergeMaps(
      _asStringKeyMap(fallback['source']),
      _asStringKeyMap(normalized['source']),
    );
    normalized['parameters'] = _normalizeParameters(
      normalized['parameters'],
      fallback['parameters'],
    );
    normalized['constraints'] = _mergeMaps(
      _asStringKeyMap(fallback['constraints']),
      _asStringKeyMap(normalized['constraints']),
    );

    final fallbackExecution = _asStringKeyMap(fallback['execution']);
    final execution = _asStringKeyMap(normalized['execution']);
    final aiSteps = execution['steps'];
    final fallbackSteps = fallbackExecution['steps'];
    final normalizedSteps = _normalizeExecutionSteps(aiSteps, fallbackSteps);
    normalized['execution'] = {
      ...fallbackExecution,
      ...execution,
      'capabilities': _executionCapabilitiesForSteps(
        normalizedSteps,
        fallback: _mergeMaps(
          _asStringKeyMap(fallbackExecution['capabilities']),
          _asStringKeyMap(execution['capabilities']),
        ),
      ),
      'steps': normalizedSteps,
    };
    for (final key in const [
      'tags',
      'runtime_targets',
      'call_contract',
      'agent_reuse',
      'script_reuse',
    ]) {
      normalized.remove(key);
    }
    if (_firstNonBlank([normalized['name']]).isEmpty) {
      normalized['name'] = fallback['name'];
    }
    if (_firstNonBlank([normalized['description']]).isEmpty) {
      normalized['description'] = fallback['description'];
    }
    final safe = _jsonSafe(normalized);
    if (safe is Map<String, dynamic>) {
      return safe;
    }
    if (safe is Map) {
      return safe.map((key, value) => MapEntry(key.toString(), value));
    }
    return Map<String, dynamic>.from(fallback);
  }

  @visibleForTesting
  static Map<String, dynamic> normalizeAiJsonForTesting(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    return _normalizeAiJson(aiJson, fallback);
  }
}

Map<String, dynamic> _buildLocalFunctionJsonInIsolate(
  Map<String, dynamic> args,
) {
  final payload = _asStringKeyMap(args['payload']);
  final rawCards = args['cards'] is List ? args['cards'] as List : const [];
  final cards = rawCards
      .map(_asStringKeyMap)
      .where((card) => card.isNotEmpty)
      .toList(growable: false);
  return RunLogReusableFunctionConverter.buildLocalFunctionJson(
    runId: (args['runId'] ?? '').toString(),
    title: (args['title'] ?? '').toString(),
    payload: payload,
    cards: cards,
    useEnglish: args['useEnglish'] == true,
  );
}

String _buildAiPromptInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter._buildAiPrompt(
    _asStringKeyMap(args['baseJson']),
    useEnglish: args['useEnglish'] == true,
  );
}

String _buildJsonRepairPromptInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter._buildJsonRepairPrompt(
    invalidOutput: (args['invalidOutput'] ?? '').toString(),
    fallbackJson: _asStringKeyMap(args['fallbackJson']),
    useEnglish: args['useEnglish'] == true,
  );
}

Map<String, dynamic>? _extractJsonObjectInIsolate(String raw) {
  return _extractJsonObject(raw);
}

Map<String, dynamic> _normalizeAiJsonInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter._normalizeAiJson(
    _asStringKeyMap(args['aiJson']),
    _asStringKeyMap(args['fallback']),
  );
}

String _buildAgentPromptInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter.buildAgentPrompt(
    _asStringKeyMap(args['functionJson']),
    useEnglish: args['useEnglish'] == true,
  );
}

class _RunLogActionSnapshot {
  const _RunLogActionSnapshot({
    required this.title,
    required this.toolName,
    required this.args,
    required this.result,
    required this.route,
    required this.success,
    required this.durationMs,
    required this.packageName,
    required this.beforeSummary,
    required this.afterSummary,
    required this.beforeXml,
    required this.prompt,
    required this.promptSource,
  });

  final String title;
  final String toolName;
  final dynamic args;
  final dynamic result;
  final String route;
  final bool? success;
  final int? durationMs;
  final String packageName;
  final String beforeSummary;
  final String afterSummary;
  final String beforeXml;
  final String prompt;
  final String promptSource;

  factory _RunLogActionSnapshot.fromCard(
    Map<String, dynamic> card, {
    required int fallbackIndex,
  }) {
    final header = _asStringKeyMap(card['header']);
    final before = _asStringKeyMap(card['before']).isNotEmpty
        ? _asStringKeyMap(card['before'])
        : _asStringKeyMap(card['observation_before_act']).isNotEmpty
        ? _asStringKeyMap(card['observation_before_act'])
        : _asStringKeyMap(card['before_observation']).isNotEmpty
        ? _asStringKeyMap(card['before_observation'])
        : _asStringKeyMap(card['observation']);
    final after = _asStringKeyMap(card['after']);
    final toolCall = _extractToolCall(card);
    final function = _asStringKeyMap(toolCall['function']);
    final args = _extractArgs(card, toolCall, function);
    final argsMap = _asStringKeyMap(args);
    final promptHit = _extractPromptHit([
      _PromptSearchRoot('args', args),
      _PromptSearchRoot('tool_call', toolCall),
      _PromptSearchRoot('function', function),
      _PromptSearchRoot('card', card),
    ]);
    final toolName = _firstNonBlank([
      toolCall['name'],
      toolCall['tool_name'],
      toolCall['toolName'],
      function['name'],
      card['tool_name'],
      card['toolName'],
      card['action_type'],
      card['actionType'],
      header['tool_name'],
      header['toolName'],
    ]);
    return _RunLogActionSnapshot(
      title: _firstNonBlank([
        header['title'],
        card['title'],
        card['summary'],
        card['operation_description'],
        card['operationDescription'],
        toolName,
        'Step ${fallbackIndex + 1}',
      ]),
      toolName: toolName.isEmpty ? 'unknown_tool' : toolName,
      args: args,
      result: _extractResult(card),
      route: _firstNonBlank([
        header['compile_kind'],
        header['compileKind'],
        card['compile_kind'],
        card['compileKind'],
        card['selection_source'] == 'vlm' ? 'miss' : null,
      ]),
      success:
          _asBool(_firstPresentValue(header, const ['success'])) ??
          _asBool(_firstPresentValue(card, const ['success'])),
      durationMs: _asInt(
        _firstPresentValue(header, const ['duration_ms', 'durationMs']) ??
            _firstPresentValue(card, const ['duration_ms', 'durationMs']),
      ),
      packageName: _firstNonBlank([
        before['package_name'],
        before['packageName'],
        after['package_name'],
        after['packageName'],
        argsMap['package_name'],
        argsMap['packageName'],
        card['package_name'],
        card['packageName'],
      ]),
      beforeSummary: _stateSummary(before),
      afterSummary: _stateSummary(after),
      beforeXml: _firstNonBlank([
        before['observation_xml'],
        before['observationXml'],
        before['xml'],
        before['page'],
      ]),
      prompt: promptHit.text,
      promptSource: promptHit.source,
    );
  }
}

void _collectParametersFromArgs({
  required String stepId,
  required int stepIndex,
  required String toolName,
  required dynamic args,
  required Map<String, Map<String, dynamic>> parametersBySignature,
  required Set<String> seenNames,
  required bool useEnglish,
  bool bindAgentOriginalArgs = false,
  List<String> path = const [],
}) {
  final argMap = _asStringKeyMap(args);
  if (argMap.isEmpty) {
    return;
  }
  for (final entry in argMap.entries) {
    final key = entry.key.trim();
    if (key.isEmpty) {
      continue;
    }
    final value = _decodeJsonIfNeeded(entry.value);
    final nextPath = [...path, key];
    if (value is Map) {
      _collectParametersFromArgs(
        stepId: stepId,
        stepIndex: stepIndex,
        toolName: toolName,
        args: value,
        parametersBySignature: parametersBySignature,
        seenNames: seenNames,
        useEnglish: useEnglish,
        bindAgentOriginalArgs: bindAgentOriginalArgs,
        path: nextPath,
      );
      continue;
    }
    if (value is Iterable) {
      var itemIndex = 0;
      for (final rawItem in value) {
        final item = _decodeJsonIfNeeded(rawItem);
        if (item is Map) {
          _collectParametersFromArgs(
            stepId: stepId,
            stepIndex: stepIndex,
            toolName: toolName,
            args: item,
            parametersBySignature: parametersBySignature,
            seenNames: seenNames,
            useEnglish: useEnglish,
            bindAgentOriginalArgs: bindAgentOriginalArgs,
            path: [...path, '$key[$itemIndex]'],
          );
        }
        itemIndex++;
      }
      continue;
    }
    final parameter = _parameterFromArg(
      stepId: stepId,
      stepIndex: stepIndex,
      toolName: toolName,
      key: key,
      path: nextPath,
      value: value,
      useEnglish: useEnglish,
      bindAgentOriginalArgs: bindAgentOriginalArgs,
    );
    if (parameter == null) {
      continue;
    }
    final signature = _parameterSignature(parameter);
    final existing = parametersBySignature[signature];
    if (existing != null) {
      _appendUnique(existing, 'bindings', parameter['bindings']);
      _appendUnique(existing, 'source_steps', parameter['source_steps']);
      continue;
    }
    final baseName = parameter['name'].toString();
    var name = baseName;
    var suffix = 2;
    while (seenNames.contains(name)) {
      name = '${baseName}_$suffix';
      suffix++;
    }
    seenNames.add(name);
    parametersBySignature[signature] = {...parameter, 'name': name};
  }
}

Map<String, dynamic>? _parameterFromArg({
  required String stepId,
  required int stepIndex,
  required String toolName,
  required String key,
  required List<String> path,
  required dynamic value,
  required bool useEnglish,
  required bool bindAgentOriginalArgs,
}) {
  final normalizedKey = key.trim();
  if (normalizedKey.isEmpty || value == null) {
    return null;
  }
  if (!_isParameterCandidate(toolName, normalizedKey, value)) {
    return null;
  }
  final defaultValue = value is String ? value.trim() : value;
  if (defaultValue is String && defaultValue.isEmpty) {
    return null;
  }
  final pathSuffix = _jsonPathSuffix(path);
  if (pathSuffix.isEmpty) {
    return null;
  }
  final bindings = <String>[
    '\$.execution.steps[$stepIndex].args.$pathSuffix',
    if (bindAgentOriginalArgs)
      '\$.execution.steps[$stepIndex].agent_call.args.original_args.$pathSuffix',
  ];
  final baseName = _parameterBaseName(normalizedKey, toolName);
  return {
    'name': baseName,
    'type': value is num
        ? 'number'
        : value is bool
        ? 'boolean'
        : 'string',
    'description': _parameterDescription(
      normalizedKey,
      toolName,
      useEnglish: useEnglish,
    ),
    'default': defaultValue,
    'required': false,
    'bindings': bindings,
    'source_steps': [stepId],
  };
}

String _parameterSignature(Map<String, dynamic> parameter) {
  return const JsonEncoder().convert({
    'name': parameter['name'],
    'type': parameter['type'],
    'default': parameter['default'],
  });
}

void _appendUnique(Map<String, dynamic> target, String key, dynamic rawValues) {
  final current = target[key] is List ? List<dynamic>.from(target[key]) : [];
  final incoming = rawValues is Iterable ? rawValues : [rawValues];
  for (final value in incoming) {
    if (value == null) continue;
    if (!current.contains(value)) {
      current.add(value);
    }
  }
  target[key] = current;
}

bool _isParameterCandidate(String toolName, String key, dynamic value) {
  if (value is Map || value is Iterable) {
    return false;
  }
  final normalizedKey = key.toLowerCase();
  if (_isCoordinateLikeKey(normalizedKey)) {
    return false;
  }
  const candidateKeys = {
    'text',
    'content',
    'message',
    'prompt',
    'instruction',
    'question',
    'query',
    'keyword',
    'url',
    'target',
    'target_description',
    'targetdescription',
    'title',
    'name',
    'label',
    'value',
    'value_text',
    'valuetext',
    'package_name',
    'packagename',
    'app_name',
    'appname',
  };
  if (candidateKeys.contains(normalizedKey)) {
    return true;
  }
  final normalizedTool = toolName.toLowerCase();
  return normalizedTool.contains('input') ||
      normalizedTool.contains('type') ||
      normalizedTool.contains('search') ||
      normalizedTool.contains('open');
}

bool _isCoordinateLikeKey(String key) {
  const blocked = {
    'x',
    'y',
    'left',
    'top',
    'right',
    'bottom',
    'width',
    'height',
    'center_x',
    'centery',
    'center_y',
    'centerx',
    'bounds',
    'rect',
  };
  return blocked.contains(key);
}

String _jsonPathSuffix(List<String> segments) {
  if (segments.isEmpty) {
    return '';
  }
  final safeSegments = <String>[];
  for (final raw in segments) {
    final segment = raw.trim();
    if (!RegExp(r'^[A-Za-z0-9_]+(?:\[\d+\])?$').hasMatch(segment)) {
      return '';
    }
    safeSegments.add(segment);
  }
  return safeSegments.join('.');
}

String _stepKindForToolName(String toolName, String route) {
  final executor = _executorForToolName(toolName, route);
  return executor == 'agent'
      ? 'agent_call'
      : executor == 'omniflow'
      ? 'omniflow_action'
      : 'tool_call';
}

String _executorForSnapshot(
  _RunLogActionSnapshot snapshot, {
  required bool skipPerceptionStep,
}) {
  if (RunLogReplayPolicy.isPerceptionTool(snapshot.toolName) &&
      !skipPerceptionStep) {
    return 'agent';
  }
  return _executorForToolName(snapshot.toolName, snapshot.route);
}

String _executorForToolName(String toolName, String route) {
  final normalizedTool = RunLogReplayPolicy.normalizeToolName(toolName);
  final normalizedRoute = route.trim().toLowerCase();
  if (normalizedTool.isEmpty || normalizedTool == 'unknown_tool') {
    return 'agent';
  }
  if (RunLogReplayPolicy.omniflowActionForToolName(normalizedTool) != null) {
    return 'omniflow';
  }
  if (normalizedRoute == 'miss' || normalizedRoute == 'vlm') {
    return 'agent';
  }
  if (normalizedTool.contains('agent') ||
      normalizedTool.contains('llm') ||
      normalizedTool.contains('vlm')) {
    return 'agent';
  }
  // Data-flow, perception, and provider-owned graph/function tools require
  // live context or OmniFlow provider semantics.
  if (RunLogReplayPolicy.isAgentTool(normalizedTool)) {
    return 'agent';
  }
  return 'tool';
}

String _stepFallbackPrompt({
  required String title,
  required String toolName,
  required dynamic args,
  required String prompt,
  required bool useEnglish,
}) {
  final argsText = const JsonEncoder.withIndent('  ').convert(_jsonSafe(args));
  final normalizedPrompt = prompt.trim();
  if (useEnglish) {
    return [
      'Re-plan this step from the current screen.',
      if (title.trim().isNotEmpty) 'Step goal: ${title.trim()}',
      if (normalizedPrompt.isNotEmpty) ...[
        'Original prompt:',
        normalizedPrompt,
      ],
      'Original tool: $toolName',
      'Original args:',
      argsText,
    ].join('\n');
  }
  return [
    '请从当前屏幕重新规划并执行这一步。',
    if (title.trim().isNotEmpty) '步骤目标：${title.trim()}',
    if (normalizedPrompt.isNotEmpty) ...['原始 prompt：', normalizedPrompt],
    '原始工具：$toolName',
    '原始参数：',
    argsText,
  ].join('\n');
}

String _stepSummary({
  required String title,
  required String toolName,
  required String prompt,
  required dynamic args,
  required dynamic result,
}) {
  final promptPreview = _compactPreview(prompt, maxLength: 180);
  if (promptPreview.isNotEmpty) {
    return 'Prompt: $promptPreview';
  }
  final argsMap = _asStringKeyMap(args);
  final resultMap = _asStringKeyMap(result);
  final preview = _compactPreview(
    _firstNonBlank([
      argsMap['target_description'],
      argsMap['targetDescription'],
      argsMap['text'],
      argsMap['content'],
      argsMap['message'],
      argsMap['query'],
      argsMap['url'],
      resultMap['summary'],
      resultMap['message'],
    ]),
    maxLength: 180,
  );
  if (preview.isNotEmpty) {
    return preview;
  }
  return _firstNonBlank([title, toolName]);
}

Map<String, dynamic>? _buildCoordinateHookMetadata({
  required _RunLogActionSnapshot snapshot,
  required dynamic args,
}) {
  if (!_usesCoordinateHook(snapshot.toolName)) {
    return null;
  }
  // No source XML → no coordinate remapping possible; use original coordinates.
  if (snapshot.beforeXml.isEmpty) {
    return null;
  }
  final argsMap = _asStringKeyMap(args);
  final replayAction =
      RunLogReplayPolicy.omniflowActionForToolName(snapshot.toolName) ??
      snapshot.toolName;
  final sourceAction = <String, dynamic>{
    'tool': replayAction,
    if (_firstNonBlank([
      argsMap['target_description'],
      argsMap['targetDescription'],
    ]).isNotEmpty)
      'target_description': _firstNonBlank([
        argsMap['target_description'],
        argsMap['targetDescription'],
      ]),
  };
  for (final key in const [
    'x',
    'y',
    'x1',
    'y1',
    'x2',
    'y2',
    'duration',
    'duration_ms',
    'durationMs',
  ]) {
    if (argsMap.containsKey(key) && argsMap[key] != null) {
      sourceAction[key] = argsMap[key];
    }
  }

  return {
    'coordinate_hook': 'omniflow',
    'coordinate_hook_policy': {
      'mode': 'coordinate_remap',
      'source_context_required': true,
    },
    'replay_policy': {
      'mode': 'coordinate_remap',
      'coordinate_transform': true,
      'source_context_required': true,
    },
    'source_context': {
      'src_ctx': {
        'page': snapshot.beforeXml,
        'require_unique_action_signature': false,
      },
      'action': sourceAction,
    },
  };
}

bool _usesCoordinateHook(String toolName) {
  return RunLogReplayPolicy.isCoordinateAction(toolName);
}

String _parameterBaseName(String key, String toolName) {
  final normalized = key
      .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]}_${m[2]}')
      .replaceAll(RegExp(r'[^A-Za-z0-9_]+'), '_')
      .toLowerCase()
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^_|_$'), '');
  if (normalized == 'target_description' || normalized == 'targetdescription') {
    return 'target';
  }
  if (normalized == 'package_name' || normalized == 'packagename') {
    return 'package_name';
  }
  if (normalized == 'app_name' || normalized == 'appname') {
    return 'app_name';
  }
  return normalized.isEmpty ? '${toolName}_value' : normalized;
}

String _parameterDescription(
  String key,
  String toolName, {
  required bool useEnglish,
}) {
  switch (key.toLowerCase()) {
    case 'text':
    case 'content':
    case 'message':
      return _text(useEnglish, '运行时输入的文本内容', 'Text entered at runtime');
    case 'query':
    case 'keyword':
      return _text(
        useEnglish,
        '运行时搜索或匹配的查询词',
        'Search or matching query at runtime',
      );
    case 'url':
      return _text(useEnglish, '运行时打开或访问的 URL', 'URL opened at runtime');
    case 'package_name':
    case 'packagename':
      return _text(
        useEnglish,
        '目标 Android 应用包名',
        'Target Android package name',
      );
    case 'target_description':
    case 'targetdescription':
    case 'target':
      return _text(
        useEnglish,
        '目标控件或目标对象描述',
        'Target control or object description',
      );
    default:
      return useEnglish ? '$key parameter for $toolName' : '$toolName 的参数 $key';
  }
}

String _text(bool useEnglish, String zh, String en) {
  return useEnglish ? en : zh;
}

Map<String, dynamic> _extractToolCall(Map<String, dynamic> card) {
  final explicit = _firstMap(card, const [
    'tool_call',
    'toolCall',
    'action',
    'call',
  ]);
  if (explicit.isNotEmpty) {
    return explicit;
  }
  final toolName = _firstNonBlank([
    card['tool_name'],
    card['toolName'],
    card['action_type'],
    card['actionType'],
  ]);
  if (toolName.isEmpty) {
    return const {};
  }
  return <String, dynamic>{
    'name': toolName,
    if (card.containsKey('params')) 'params': card['params'],
    if (card.containsKey('arguments')) 'arguments': card['arguments'],
  };
}

class _PromptHit {
  const _PromptHit(this.text, this.source);

  final String text;
  final String source;
}

class _PromptSearchRoot {
  const _PromptSearchRoot(this.name, this.value);

  final String name;
  final dynamic value;
}

const Set<String> _promptKeyNames = {
  'prompt',
  'subagentprompt',
  'agentprompt',
  'augmentprompt',
  'augumentprompt',
  'systemprompt',
  'userprompt',
  'instruction',
  'instructions',
  'query',
  'question',
  'message',
  'usermessage',
  'input',
  'task',
  'goal',
  'request',
};

_PromptHit _extractPromptHit(List<_PromptSearchRoot> roots) {
  for (final root in roots) {
    final hit = _findPromptInValue(
      root.value,
      path: root.name,
      visited: <Object>{},
    );
    if (hit.text.trim().isNotEmpty) {
      return hit;
    }
  }
  return const _PromptHit('', '');
}

_PromptHit _findPromptInValue(
  dynamic raw, {
  required String path,
  required Set<Object> visited,
}) {
  final value = _decodeJsonIfNeeded(raw);
  if (value is Map) {
    if (!visited.add(value)) {
      return const _PromptHit('', '');
    }
    final map = value.map((key, item) => MapEntry(key.toString(), item));
    for (final entry in map.entries) {
      final key = entry.key.trim();
      final normalizedKey = _normalizePromptKey(key);
      if (_promptKeyNames.contains(normalizedKey)) {
        final text = _promptTextFromValue(entry.value);
        if (text.isNotEmpty) {
          return _PromptHit(text, '$path.$key');
        }
      }
    }
    for (final entry in map.entries) {
      final hit = _findPromptInValue(
        entry.value,
        path: '$path.${entry.key}',
        visited: visited,
      );
      if (hit.text.isNotEmpty) {
        return hit;
      }
    }
  } else if (value is Iterable) {
    var index = 0;
    for (final item in value) {
      final hit = _findPromptInValue(
        item,
        path: '$path[$index]',
        visited: visited,
      );
      if (hit.text.isNotEmpty) {
        return hit;
      }
      index++;
    }
  }
  return const _PromptHit('', '');
}

String _promptTextFromValue(dynamic raw) {
  final value = _decodeJsonIfNeeded(raw);
  if (value is String) {
    return value.trim();
  }
  if (value is num || value is bool) {
    return value.toString();
  }
  if (value is Map) {
    return _firstNonBlank([
      value['text'],
      value['content'],
      value['message'],
      value['prompt'],
      value['value'],
    ]);
  }
  return '';
}

String _normalizePromptKey(String key) {
  return key.replaceAll(RegExp(r'[^A-Za-z0-9]+'), '').toLowerCase().trim();
}

dynamic _extractArgs(
  Map<String, dynamic> card,
  Map<String, dynamic> toolCall,
  Map<String, dynamic> function,
) {
  final value = _firstPresent([
    toolCall['params'],
    toolCall['arguments'],
    toolCall['args'],
    function['arguments'],
    card['params'],
    card['arguments'],
    card['args'],
  ]);
  return _decodeJsonIfNeeded(value) ?? const <String, dynamic>{};
}

dynamic _extractResult(Map<String, dynamic> card) {
  final value = _firstPresent([
    card['result'],
    card['tool_result'],
    card['toolResult'],
    card['execution_result'],
    card['executionResult'],
    card['output'],
    card['error'],
    card['error_message'],
    card['errorMessage'],
  ]);
  return _decodeJsonIfNeeded(value);
}

Map<String, dynamic>? _extractJsonObject(String raw) {
  final text = raw.trim();
  if (text.isEmpty) {
    return null;
  }
  final fenced = RegExp(
    r'```(?:json)?\s*([\s\S]*?)```',
    caseSensitive: false,
  ).firstMatch(text);
  final candidate = fenced?.group(1)?.trim() ?? text;
  final direct = _tryDecodeMap(candidate);
  if (direct != null) {
    return direct;
  }
  final start = candidate.indexOf('{');
  final end = candidate.lastIndexOf('}');
  if (start >= 0 && end > start) {
    return _tryDecodeMap(candidate.substring(start, end + 1));
  }
  return null;
}

Map<String, dynamic>? _tryDecodeMap(String value) {
  try {
    final decoded = jsonDecode(value);
    if (decoded is Map) {
      return decoded.map((key, item) => MapEntry(key.toString(), item));
    }
  } catch (_) {
    return null;
  }
  return null;
}

String _stateSummary(Map<String, dynamic> state) {
  return _firstNonBlank([
    state['page_title'],
    state['pageTitle'],
    state['activity'],
    state['package_name'],
    state['packageName'],
    state['description'],
    state['summary'],
  ]);
}

Map<String, dynamic> _mergeMaps(
  Map<String, dynamic> base,
  Map<String, dynamic> override,
) {
  return <String, dynamic>{...base, ...override};
}

List<dynamic> _normalizeParameters(dynamic value, dynamic fallback) {
  final candidates = value is List && value.isNotEmpty ? value : fallback;
  if (candidates is! List) {
    return const [];
  }
  return candidates
      .map((item) => _asStringKeyMap(item))
      .where((item) => item.isNotEmpty)
      .map((item) {
        final bindings = item['bindings'];
        return {
          'name': _firstNonBlank([item['name']]),
          'type': _firstNonBlank([item['type'], 'string']),
          'description': _firstNonBlank([item['description'], item['name']]),
          if (item.containsKey('default')) 'default': item['default'],
          'required': item['required'] == true,
          'bindings': bindings is List
              ? bindings.map((entry) => entry.toString()).toList()
              : const <String>[],
          if (item['source_steps'] is List)
            'source_steps': item['source_steps'],
        };
      })
      .where((item) => item['name'].toString().trim().isNotEmpty)
      .toList(growable: false);
}

List<dynamic> _normalizeExecutionSteps(dynamic value, dynamic fallback) {
  final fallbackSteps = fallback is List ? fallback : const <dynamic>[];
  final candidates = value is List && value.isNotEmpty ? value : fallbackSteps;
  return List<dynamic>.generate(candidates.length, (index) {
    final step = _asStringKeyMap(candidates[index]);
    final fallbackStep = index < fallbackSteps.length
        ? _asStringKeyMap(fallbackSteps[index])
        : const <String, dynamic>{};
    final merged = <String, dynamic>{...fallbackStep, ...step};
    final toolName = _firstNonBlank([
      merged['tool'],
      fallbackStep['tool'],
      'unknown_tool',
    ]);
    final route = _firstNonBlank([merged['route'], fallbackStep['route']]);
    final inferredExecutor = _executorForToolName(toolName, route);
    final executor = switch (inferredExecutor) {
      'omniflow' => 'omniflow',
      'agent' => 'agent',
      _ => _firstNonBlank([
        merged['executor'],
        fallbackStep['executor'],
        inferredExecutor,
      ]),
    };
    final rawReplayAction = _firstNonBlank([
      if (executor == 'omniflow') merged['omniflow_action'],
      if (executor == 'omniflow') fallbackStep['omniflow_action'],
      if (executor == 'omniflow')
        RunLogReplayPolicy.omniflowActionForToolName(toolName),
    ]);
    final replayAction = executor == 'omniflow'
        ? (RunLogReplayPolicy.omniflowActionForToolName(rawReplayAction) ??
              RunLogReplayPolicy.omniflowActionForToolName(toolName) ??
              rawReplayAction)
        : '';
    final emittedToolName = executor == 'omniflow' && replayAction.isNotEmpty
        ? replayAction
        : toolName;
    final modelFree =
        executor == 'omniflow' ||
        (executor != 'agent' &&
            (_asBool(merged['model_free']) == true ||
                _asBool(fallbackStep['model_free']) == true));
    final rawScriptable = merged['scriptable'] is bool
        ? merged['scriptable']
        : fallbackStep['scriptable'];
    final scriptable = executor == 'agent'
        ? false
        : rawScriptable is bool
        ? rawScriptable
        : true;
    final callableTool = executor == 'agent'
        ? 'oob.agent.run'
        : executor == 'omniflow' && replayAction.isNotEmpty
        ? replayAction
        : _firstNonBlank([
            merged['callable_tool'],
            fallbackStep['callable_tool'],
            toolName,
          ]);
    final fallback = _mergeMaps(
      _asStringKeyMap(fallbackStep['fallback']),
      _asStringKeyMap(merged['fallback']),
    );
    final agentCall = _normalizeAgentCall(
      raw: _mergeMaps(
        _asStringKeyMap(fallbackStep['agent_call']),
        _asStringKeyMap(merged['agent_call']),
      ),
      enabled: executor == 'agent',
      fallback: fallback,
      originalTool: toolName,
      originalArgs: _jsonSafe(merged['args'] ?? fallbackStep['args']),
      originalPrompt: _extractStepPromptText(merged, fallbackStep),
      reason: _firstNonBlank([
        _asStringKeyMap(merged['agent_call'])['reason'],
        _asStringKeyMap(fallbackStep['agent_call'])['reason'],
        RunLogReplayPolicy.agentStepReason(toolName),
      ]),
    );
    final prompt = _extractStepPromptText(merged, fallbackStep);
    return {
      ..._stepBaseWithoutDerivedFields(merged),
      'id': _firstNonBlank([
        merged['id'],
        fallbackStep['id'],
        'step_${index + 1}',
      ]),
      'index': _asInt(merged['index']) ?? index,
      'kind': _stepKindForToolName(emittedToolName, route),
      'tool': emittedToolName,
      'callable_tool': callableTool,
      if (emittedToolName != toolName) 'source_tool': toolName,
      'executor': executor,
      'scriptable': scriptable,
      if (modelFree) 'model_free': true,
      if (replayAction.isNotEmpty) 'omniflow_action': replayAction,
      'args': _jsonSafe(merged['args'] ?? fallbackStep['args']),
      'tool_binding': _mergeMaps(
        _mergeMaps(
          _asStringKeyMap(fallbackStep['tool_binding']),
          _asStringKeyMap(merged['tool_binding']),
        ),
        {
          'kind': executor == 'agent'
              ? 'agent_replan'
              : executor == 'omniflow'
              ? 'omniflow_action'
              : 'oob_agent_tool',
          'name': emittedToolName,
          if (executor == 'agent') 'callable_tool': 'oob.agent.run',
        },
      ),
      if (prompt.isNotEmpty)
        'prompt': {
          ..._asStringKeyMap(fallbackStep['prompt']),
          ..._asStringKeyMap(merged['prompt']),
          'text': prompt,
          'preview': _compactPreview(prompt, maxLength: 240),
        },
      if (executor == 'omniflow' &&
          _firstNonBlank([
            merged['coordinate_hook'],
            fallbackStep['coordinate_hook'],
          ]).isNotEmpty)
        'coordinate_hook': _firstNonBlank([
          merged['coordinate_hook'],
          fallbackStep['coordinate_hook'],
        ]),
      if (executor == 'omniflow' &&
          (_asStringKeyMap(merged['coordinate_hook_policy']).isNotEmpty ||
              _asStringKeyMap(
                fallbackStep['coordinate_hook_policy'],
              ).isNotEmpty))
        'coordinate_hook_policy': _mergeMaps(
          _asStringKeyMap(fallbackStep['coordinate_hook_policy']),
          _asStringKeyMap(merged['coordinate_hook_policy']),
        ),
      if (executor == 'omniflow' &&
          _firstNonBlank([
            merged['replay_engine'],
            fallbackStep['replay_engine'],
          ]).isNotEmpty)
        'replay_engine': _firstNonBlank([
          merged['replay_engine'],
          fallbackStep['replay_engine'],
        ]),
      if (executor == 'omniflow' &&
          (_asStringKeyMap(merged['replay_policy']).isNotEmpty ||
              _asStringKeyMap(fallbackStep['replay_policy']).isNotEmpty))
        'replay_policy': _mergeMaps(
          _asStringKeyMap(fallbackStep['replay_policy']),
          _asStringKeyMap(merged['replay_policy']),
        ),
      if (executor == 'omniflow' &&
          (_asStringKeyMap(merged['source_context']).isNotEmpty ||
              _asStringKeyMap(fallbackStep['source_context']).isNotEmpty))
        'source_context': _mergeMaps(
          _asStringKeyMap(fallbackStep['source_context']),
          _asStringKeyMap(merged['source_context']),
        ),
      'validation': _mergeMaps(
        _asStringKeyMap(fallbackStep['validation']),
        _asStringKeyMap(merged['validation']),
      ),
      'fallback': fallback,
      if (agentCall.isNotEmpty) 'agent_call': agentCall,
      'reuse_policy': _mergeMaps(
        _asStringKeyMap(fallbackStep['reuse_policy']),
        _asStringKeyMap(merged['reuse_policy']),
      ),
    };
  }, growable: false);
}

Map<String, dynamic> _stepBaseWithoutDerivedFields(Map<String, dynamic> step) {
  final copy = Map<String, dynamic>.from(step);
  for (final key in const [
    'kind',
    'callable_tool',
    'executor',
    'scriptable',
    'model_free',
    'modelFree',
    'omniflow_action',
    'local_action',
    'tool_binding',
    'agent_call',
    'coordinate_hook',
    'coordinate_hook_policy',
    'replay_engine',
    'replay_policy',
    'source_context',
    'validation',
    'fallback',
    'reuse_policy',
  ]) {
    copy.remove(key);
  }
  return copy;
}

Map<String, dynamic> _executionCapabilitiesForSteps(
  List<dynamic> steps, {
  required Map<String, dynamic> fallback,
}) {
  final stepMaps = steps.map(_asStringKeyMap).toList(growable: false);
  return {
    ...fallback,
    'scriptable_step_count': stepMaps
        .where((step) => step['scriptable'] == true)
        .length,
    'model_free_step_count': stepMaps
        .where((step) => step['model_free'] == true)
        .length,
    'omniflow_step_count': stepMaps
        .where((step) => step['executor'] == 'omniflow')
        .length,
    'agent_step_count': stepMaps
        .where((step) => step['executor'] == 'agent')
        .length,
    'requires_agent_fallback': stepMaps.any(
      (step) => step['executor'] == 'agent',
    ),
  };
}

Map<String, dynamic> _normalizeAgentCall({
  required Map<String, dynamic> raw,
  required bool enabled,
  required Map<String, dynamic> fallback,
  required String originalTool,
  required dynamic originalArgs,
  required String originalPrompt,
  required String reason,
}) {
  if (!enabled && raw.isEmpty) {
    return const <String, dynamic>{};
  }
  final rawArgs = _asStringKeyMap(raw['args']);
  final prompt = _firstNonBlank([
    rawArgs['prompt'],
    raw['prompt'],
    fallback['prompt'],
  ]);
  return {
    ...raw,
    'tool': enabled
        ? 'oob.agent.run'
        : _firstNonBlank([raw['tool'], 'oob.agent.run']),
    'args': {
      ...rawArgs,
      if (prompt.isNotEmpty) 'prompt': prompt,
      'original_tool': _firstNonBlank([rawArgs['original_tool'], originalTool]),
      if (!_isEmptyJsonValue(originalArgs))
        'original_args': rawArgs['original_args'] ?? originalArgs,
      if (originalPrompt.trim().isNotEmpty)
        'original_prompt': _firstNonBlank([
          rawArgs['original_prompt'],
          originalPrompt,
        ]),
    },
    'reason': _firstNonBlank([
      raw['reason'],
      reason,
      enabled ? 'non_scriptable_or_vlm_step' : 'agent_fallback',
    ]),
  };
}

String _extractStepPromptText(
  Map<String, dynamic> step,
  Map<String, dynamic> fallbackStep,
) {
  return _firstNonBlank([
    _asStringKeyMap(step['prompt'])['text'],
    step['prompt'],
    _asStringKeyMap(fallbackStep['prompt'])['text'],
    fallbackStep['prompt'],
    _asStringKeyMap(
      _asStringKeyMap(step['agent_call'])['args'],
    )['original_prompt'],
    _asStringKeyMap(
      _asStringKeyMap(fallbackStep['agent_call'])['args'],
    )['original_prompt'],
  ]);
}

Map<String, dynamic> _asStringKeyMap(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded is! Map) {
    return const <String, dynamic>{};
  }
  return decoded.map((key, item) => MapEntry(key.toString(), item));
}

Map<String, dynamic> _firstMap(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    final map = _asStringKeyMap(source[key]);
    if (map.isNotEmpty) {
      return map;
    }
  }
  return const <String, dynamic>{};
}

dynamic _firstPresentValue(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    if (source.containsKey(key) && source[key] != null) {
      return source[key];
    }
  }
  return null;
}

dynamic _firstPresent(List<dynamic> values) {
  for (final value in values) {
    if (value == null) {
      continue;
    }
    if (value is String && value.trim().isEmpty) {
      continue;
    }
    return value;
  }
  return null;
}

String _firstNonBlank(List<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
}

String _compactPreview(String value, {int maxLength = 160}) {
  final normalized = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (normalized.length <= maxLength) {
    return normalized;
  }
  if (maxLength <= 1) {
    return normalized.substring(0, maxLength);
  }
  return '${normalized.substring(0, maxLength - 1).trimRight()}…';
}

bool? _asBool(dynamic value) {
  if (value is bool) {
    return value;
  }
  final text = value?.toString().trim().toLowerCase();
  if (text == 'true') {
    return true;
  }
  if (text == 'false') {
    return false;
  }
  return null;
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString().trim() ?? '');
}

dynamic _decodeJsonIfNeeded(dynamic value) {
  if (value is! String) {
    return value;
  }
  final trimmed = value.trim();
  if (trimmed.isEmpty) {
    return value;
  }
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return value;
  }
  try {
    return jsonDecode(trimmed);
  } catch (_) {
    return value;
  }
}

bool _isEmptyJsonValue(dynamic value) {
  if (value == null) {
    return true;
  }
  if (value is String) {
    return value.trim().isEmpty;
  }
  if (value is Map || value is Iterable) {
    return value.isEmpty;
  }
  return false;
}

dynamic _jsonSafe(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded == null ||
      decoded is String ||
      decoded is num ||
      decoded is bool) {
    return decoded;
  }
  if (decoded is Map) {
    return decoded.map(
      (key, item) => MapEntry(key.toString(), _jsonSafe(item)),
    );
  }
  if (decoded is Iterable) {
    return decoded.map(_jsonSafe).toList(growable: false);
  }
  return decoded.toString();
}

Map<String, dynamic> _jsonSafeMap(Map<String, dynamic> value) {
  final safe = _jsonSafe(value);
  return safe is Map<String, dynamic> ? safe : _asStringKeyMap(safe);
}

String _normalizeFunctionName(String value, {required String fallback}) {
  final normalized = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (normalized.isEmpty) {
    return fallback;
  }
  return normalized.length <= 60 ? normalized : normalized.substring(0, 60);
}

String _compactId(String value) {
  final normalized = value
      .trim()
      .replaceAll(RegExp(r'[^A-Za-z0-9_]+'), '_')
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^_|_$'), '');
  if (normalized.isEmpty) {
    return DateTime.now().millisecondsSinceEpoch.toString();
  }
  return normalized.length <= 48 ? normalized : normalized.substring(0, 48);
}
