import 'dart:convert';

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
    final baseJson = buildLocalFunctionJson(
      runId: runId,
      title: title,
      payload: payload,
      cards: cards,
      useEnglish: useEnglish,
    );

    if (!useAi || cards.isEmpty) {
      return RunLogReusableFunctionSpec(
        json: baseJson,
        agentPrompt: buildAgentPrompt(baseJson, useEnglish: useEnglish),
        aiEnhanced: false,
      );
    }

    final prompt = _buildAiPrompt(baseJson, useEnglish: useEnglish);
    try {
      final raw = await AssistsMessageService.postLLMChat(
        text: prompt,
        model: 'scene.compactor.context',
      );
      final aiJson = _extractJsonObject(raw ?? '');
      if (aiJson == null) {
        return RunLogReusableFunctionSpec(
          json: baseJson,
          agentPrompt: buildAgentPrompt(baseJson, useEnglish: useEnglish),
          aiEnhanced: false,
          rawAiText: raw,
          warning: _text(
            useEnglish,
            'AI 未返回可解析 JSON，已使用本地转换结果',
            'AI did not return parseable JSON. Using the local conversion.',
          ),
        );
      }
      final normalized = _normalizeAiJson(aiJson, baseJson);
      return RunLogReusableFunctionSpec(
        json: normalized,
        agentPrompt: buildAgentPrompt(normalized, useEnglish: useEnglish),
        aiEnhanced: true,
        rawAiText: raw,
      );
    } catch (error) {
      return RunLogReusableFunctionSpec(
        json: baseJson,
        agentPrompt: buildAgentPrompt(baseJson, useEnglish: useEnglish),
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

    for (var index = 0; index < cards.length; index++) {
      final snapshot = _RunLogActionSnapshot.fromCard(
        cards[index],
        fallbackIndex: index,
      );
      final args = _jsonSafe(snapshot.args);
      final stepId = 'step_${index + 1}';
      final executor = _executorForToolName(snapshot.toolName, snapshot.route);
      final scriptable = executor == 'tool';
      final fallbackPrompt = _stepFallbackPrompt(
        title: snapshot.title,
        toolName: snapshot.toolName,
        args: args,
        useEnglish: useEnglish,
      );
      steps.add({
        'id': stepId,
        'index': index,
        'kind': _stepKindForToolName(snapshot.toolName, snapshot.route),
        'title': snapshot.title.isNotEmpty ? snapshot.title : snapshot.toolName,
        'tool': snapshot.toolName,
        'callable_tool': executor == 'agent'
            ? 'oob.agent.run'
            : snapshot.toolName,
        'executor': executor,
        'scriptable': scriptable,
        'args': args,
        'tool_binding': {
          'kind': executor == 'agent' ? 'agent_replan' : 'oob_agent_tool',
          'name': snapshot.toolName,
          if (executor == 'agent') 'callable_tool': 'oob.agent.run',
        },
        if (executor == 'agent')
          'agent_call': {
            'tool': 'oob.agent.run',
            'args': {
              'prompt': fallbackPrompt,
              'original_tool': snapshot.toolName,
              'original_args': args,
            },
            'reason': 'non_scriptable_or_vlm_step',
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
        stepIndex: index,
        toolName: snapshot.toolName,
        args: args,
        parametersBySignature: parametersBySignature,
        seenNames: seenParameterNames,
        useEnglish: useEnglish,
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
      'runtime_targets': ['agent', 'script'],
      'parameters': parameters,
      'call_contract': {
        'api': 'AssistsMessageService.runOobReusableFunction',
        'method_channel': 'cn.com.omnimind.bot/AssistCoreEvent',
        'native_method': 'runOobReusableFunction',
        'argument_binding': 'parameters[*].bindings',
        'argument_application': 'native_materialized_before_agent_run',
        'arguments_schema': parameters
            .map(
              (item) => {
                'name': item['name'],
                'type': item['type'],
                'description': item['description'],
                if (item.containsKey('default')) 'default': item['default'],
                'required': item['required'] == true,
              },
            )
            .toList(),
        'example': {
          'function_id': 'runlog_${_compactId(runId)}',
          'arguments': {
            for (final item in parameters)
              item['name'].toString(): item['default'] ?? '<value>',
          },
        },
      },
      'constraints': {
        if (packageName.isNotEmpty) 'package_name': packageName,
        'start_state': _firstNonBlank([
          _asStringKeyMap(
            cards.isEmpty ? null : cards.first['before'],
          )['page_title'],
          _asStringKeyMap(
            cards.isEmpty ? null : cards.first['before'],
          )['activity'],
          _asStringKeyMap(
            cards.isEmpty ? null : cards.first['before'],
          )['package_name'],
        ]),
        'end_state': _firstNonBlank([
          _asStringKeyMap(
            cards.isEmpty ? null : cards.last['after'],
          )['page_title'],
          _asStringKeyMap(
            cards.isEmpty ? null : cards.last['after'],
          )['activity'],
          _asStringKeyMap(
            cards.isEmpty ? null : cards.last['after'],
          )['package_name'],
        ]),
      },
      'execution': {
        'kind': 'tool_sequence',
        'runner': 'oob_tool_sequence',
        'entrypoint': 'execute',
        'capabilities': {
          'scriptable_step_count': steps
              .where((step) => step['scriptable'] == true)
              .length,
          'agent_step_count': steps
              .where((step) => step['executor'] == 'agent')
              .length,
          'requires_agent_fallback': steps.any(
            (step) => step['executor'] == 'agent',
          ),
        },
        'binding_model': {
          'parameters_path': '\$.parameters',
          'bindings_path': '\$.parameters[*].bindings',
          'applied_by': 'OobReusableFunctionStore.materialize',
        },
        'fallback_runner': 'oob.agent.run',
        'steps': steps,
      },
      'agent_reuse': {
        'strategy':
            'Use materialized execution.steps when executor=tool and UI state matches. If executor=agent or any validation fails, keep the same goal and re-plan that step from the current screen.',
        'input_contract': parameters
            .map(
              (item) => {
                'name': item['name'],
                'type': item['type'],
                'description': item['description'],
                if (item['default'] != null) 'default': item['default'],
              },
            )
            .toList(),
      },
      'script_reuse': {
        'language': 'json-actions',
        'runner': 'oob_tool_sequence',
        'call_shape': {
          'function_id': 'runlog_${_compactId(runId)}',
          'arguments': {
            for (final item in parameters)
              item['name'].toString(): item['default'] ?? '<value>',
          },
        },
      },
    };
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
        '2. For executor=tool, call step.callable_tool with the materialized step.args after validation.',
        '3. For executor=agent or validation mismatch, call step.agent_call.tool / fallback.tool and re-plan that step from the current screen.',
        '4. Runtime arguments are applied through parameters.bindings before execution.',
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
      '2. executor=tool 时，先检查 validation，再用 step.callable_tool 和已物化 step.args 调工具。',
      '3. executor=agent 或状态不匹配时，调用 step.agent_call.tool / fallback.tool，从当前屏幕重规划该步。',
      '4. 运行时参数会先通过 parameters.bindings 写入对应 step args。',
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
- Keep schema_version = "oob.reusable_function.v1".
- Preserve execution.steps order, tool names, and key args. Do not invent tools that do not exist.
- You may rewrite name/description to make it a clearer reusable function name.
- You may refine parameters: abstract hard-coded user input, search terms, message text, URLs, and target objects into parameters; do not abstract coordinate x/y into user parameters.
- Every parameter must include name/type/description/bindings/default. bindings must be a JSONPath string array pointing to execution.steps[*].args.
- Preserve or improve call_contract, execution.binding_model, step executor/scriptable/callable_tool/agent_call/validation/fallback fields.
- Clarify agent_reuse.strategy: when to replay the script and when the agent should re-plan.
- Output must be consumable by both the agent and the script runner.

Draft JSON:
$compact
''';
    }
    return '''
你是 OOB/OmniFlow 的轨迹编译器。请把下面由 RunLog 抽取得到的草稿，整理成可复用的 function JSON。

要求：
- 只能输出一个 JSON object，不要 Markdown，不要解释。
- 保持 schema_version = "oob.reusable_function.v1"。
- 保留 execution.steps 的顺序、工具名和关键 args，不要编造不存在的工具。
- 可以重写 name/description，使其更像可复用功能名。
- 可以整理 parameters：把硬编码的用户输入、搜索词、消息文本、URL、目标对象抽象成参数；不要把坐标 x/y 抽象成用户参数。
- 每个 parameter 必须包含 name/type/description/bindings/default，其中 bindings 是 JSONPath 字符串数组，指向 execution.steps[*].args。
- 保留或优化 call_contract、execution.binding_model、每步的 executor/scriptable/callable_tool/agent_call/validation/fallback 字段。
- 为 agent_reuse.strategy 写清楚：什么时候脚本重放，什么时候 agent 重规划。
- 输出必须能被 agent 和 script 执行器共同消费。

草稿 JSON：
$compact
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
    normalized['runtime_targets'] = _normalizeStringList(
      normalized['runtime_targets'],
      fallback: const ['agent', 'script'],
    );
    normalized['parameters'] = _normalizeParameters(
      normalized['parameters'],
      fallback['parameters'],
    );
    normalized['call_contract'] = _normalizeCallContract(
      functionId: normalized['function_id']?.toString() ?? '',
      parameters: normalized['parameters'],
      fallback: _mergeMaps(
        _asStringKeyMap(fallback['call_contract']),
        _asStringKeyMap(normalized['call_contract']),
      ),
    );
    normalized['constraints'] = _mergeMaps(
      _asStringKeyMap(fallback['constraints']),
      _asStringKeyMap(normalized['constraints']),
    );

    final fallbackExecution = _asStringKeyMap(fallback['execution']);
    final execution = _asStringKeyMap(normalized['execution']);
    final aiSteps = execution['steps'];
    final fallbackSteps = fallbackExecution['steps'];
    normalized['execution'] = {
      ...fallbackExecution,
      ...execution,
      'binding_model': _mergeMaps(
        _asStringKeyMap(fallbackExecution['binding_model']),
        _asStringKeyMap(execution['binding_model']),
      ),
      'steps': _normalizeExecutionSteps(aiSteps, fallbackSteps),
    };
    normalized['agent_reuse'] = _mergeMaps(
      _asStringKeyMap(fallback['agent_reuse']),
      _asStringKeyMap(normalized['agent_reuse']),
    );
    normalized['script_reuse'] = _normalizeScriptReuse(
      functionId: normalized['function_id']?.toString() ?? '',
      parameters: normalized['parameters'],
      fallback: _mergeMaps(
        _asStringKeyMap(fallback['script_reuse']),
        _asStringKeyMap(normalized['script_reuse']),
      ),
    );
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

  factory _RunLogActionSnapshot.fromCard(
    Map<String, dynamic> card, {
    required int fallbackIndex,
  }) {
    final header = _asStringKeyMap(card['header']);
    final before = _asStringKeyMap(card['before']);
    final after = _asStringKeyMap(card['after']);
    final toolCall = _extractToolCall(card);
    final function = _asStringKeyMap(toolCall['function']);
    final args = _extractArgs(card, toolCall, function);
    final argsMap = _asStringKeyMap(args);
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
    'bindings': ['\$.execution.steps[$stepIndex].args.$pathSuffix'],
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
  return _executorForToolName(toolName, route) == 'agent'
      ? 'agent_call'
      : 'tool_call';
}

String _executorForToolName(String toolName, String route) {
  final normalizedTool = toolName.trim().toLowerCase();
  final normalizedRoute = route.trim().toLowerCase();
  if (normalizedTool.isEmpty || normalizedTool == 'unknown_tool') {
    return 'agent';
  }
  if (normalizedRoute == 'miss' || normalizedRoute == 'vlm') {
    return 'agent';
  }
  if (normalizedTool.contains('agent') ||
      normalizedTool.contains('llm') ||
      normalizedTool.contains('vlm')) {
    return 'agent';
  }
  return 'tool';
}

String _stepFallbackPrompt({
  required String title,
  required String toolName,
  required dynamic args,
  required bool useEnglish,
}) {
  final argsText = const JsonEncoder.withIndent('  ').convert(_jsonSafe(args));
  if (useEnglish) {
    return [
      'Re-plan this step from the current screen.',
      if (title.trim().isNotEmpty) 'Step goal: ${title.trim()}',
      'Original tool: $toolName',
      'Original args:',
      argsText,
    ].join('\n');
  }
  return [
    '请从当前屏幕重新规划并执行这一步。',
    if (title.trim().isNotEmpty) '步骤目标：${title.trim()}',
    '原始工具：$toolName',
    '原始参数：',
    argsText,
  ].join('\n');
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

List<String> _normalizeStringList(
  dynamic value, {
  required List<String> fallback,
}) {
  if (value is List) {
    final items = value
        .map((item) => item.toString().trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
    if (items.isNotEmpty) {
      return items;
    }
  }
  return fallback;
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
    final executor = _firstNonBlank([
      merged['executor'],
      fallbackStep['executor'],
      _executorForToolName(toolName, route),
    ]);
    final rawScriptable = merged['scriptable'] is bool
        ? merged['scriptable']
        : fallbackStep['scriptable'];
    final scriptable = rawScriptable is bool
        ? rawScriptable
        : executor == 'tool';
    final callableTool = _firstNonBlank([
      merged['callable_tool'],
      fallbackStep['callable_tool'],
      executor == 'agent' ? 'oob.agent.run' : toolName,
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
    );
    return {
      ...merged,
      'id': _firstNonBlank([
        merged['id'],
        fallbackStep['id'],
        'step_${index + 1}',
      ]),
      'index': _asInt(merged['index']) ?? index,
      'kind': _firstNonBlank([
        merged['kind'],
        fallbackStep['kind'],
        _stepKindForToolName(toolName, route),
      ]),
      'tool': toolName,
      'callable_tool': callableTool,
      'executor': executor,
      'scriptable': scriptable,
      'args': _jsonSafe(merged['args'] ?? fallbackStep['args']),
      'tool_binding': _mergeMaps(
        _asStringKeyMap(fallbackStep['tool_binding']),
        _asStringKeyMap(merged['tool_binding']),
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

Map<String, dynamic> _normalizeAgentCall({
  required Map<String, dynamic> raw,
  required bool enabled,
  required Map<String, dynamic> fallback,
  required String originalTool,
  required dynamic originalArgs,
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
    'tool': _firstNonBlank([raw['tool'], 'oob.agent.run']),
    'args': {
      ...rawArgs,
      if (prompt.isNotEmpty) 'prompt': prompt,
      'original_tool': _firstNonBlank([rawArgs['original_tool'], originalTool]),
      if (!_isEmptyJsonValue(originalArgs))
        'original_args': rawArgs['original_args'] ?? originalArgs,
    },
    'reason': _firstNonBlank([
      raw['reason'],
      enabled ? 'non_scriptable_or_vlm_step' : 'agent_fallback',
    ]),
  };
}

Map<String, dynamic> _normalizeCallContract({
  required String functionId,
  required dynamic parameters,
  required Map<String, dynamic> fallback,
}) {
  return {
    ...fallback,
    'api': _firstNonBlank([
      fallback['api'],
      'AssistsMessageService.runOobReusableFunction',
    ]),
    'method_channel': _firstNonBlank([
      fallback['method_channel'],
      'cn.com.omnimind.bot/AssistCoreEvent',
    ]),
    'native_method': _firstNonBlank([
      fallback['native_method'],
      'runOobReusableFunction',
    ]),
    'argument_binding': 'parameters[*].bindings',
    'argument_application': 'native_materialized_before_agent_run',
    'arguments_schema': _argumentSchemaFromParameters(parameters),
    'example': {
      'function_id': functionId,
      'arguments': _defaultArgumentsFromParameters(parameters),
    },
  };
}

Map<String, dynamic> _normalizeScriptReuse({
  required String functionId,
  required dynamic parameters,
  required Map<String, dynamic> fallback,
}) {
  return {
    ...fallback,
    'language': _firstNonBlank([fallback['language'], 'json-actions']),
    'runner': _firstNonBlank([fallback['runner'], 'oob_tool_sequence']),
    'call_shape': {
      'function_id': functionId,
      'arguments': _defaultArgumentsFromParameters(parameters),
    },
  };
}

List<Map<String, dynamic>> _argumentSchemaFromParameters(dynamic parameters) {
  if (parameters is! List) {
    return const <Map<String, dynamic>>[];
  }
  return parameters
      .map(_asStringKeyMap)
      .where((item) => item['name']?.toString().trim().isNotEmpty == true)
      .map(
        (item) => {
          'name': item['name'],
          'type': _firstNonBlank([item['type'], 'string']),
          'description': _firstNonBlank([item['description'], item['name']]),
          if (item.containsKey('default')) 'default': item['default'],
          'required': item['required'] == true,
        },
      )
      .toList(growable: false);
}

Map<String, dynamic> _defaultArgumentsFromParameters(dynamic parameters) {
  if (parameters is! List) {
    return const <String, dynamic>{};
  }
  return {
    for (final item in parameters.map(_asStringKeyMap))
      if (item['name']?.toString().trim().isNotEmpty == true)
        item['name'].toString(): item.containsKey('default')
            ? item['default']
            : '<value>',
  };
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
