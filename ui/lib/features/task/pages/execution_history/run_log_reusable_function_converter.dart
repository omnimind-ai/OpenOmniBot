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
    final parameters = <Map<String, dynamic>>[];
    final seenParameterNames = <String>{};

    for (var index = 0; index < cards.length; index++) {
      final snapshot = _RunLogActionSnapshot.fromCard(
        cards[index],
        fallbackIndex: index,
      );
      final args = _jsonSafe(snapshot.args);
      final stepId = 'step_${index + 1}';
      steps.add({
        'id': stepId,
        'index': index,
        'kind': 'tool_call',
        'title': snapshot.title.isNotEmpty ? snapshot.title : snapshot.toolName,
        'tool': snapshot.toolName,
        'args': args,
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
        'reuse_policy': {
          'mode': 'prefer_script_then_agent_replan',
          'allow_agent_replan_on_mismatch': true,
          'requires_runtime_validation': true,
        },
      });

      final argMap = _asStringKeyMap(args);
      for (final entry in argMap.entries) {
        final parameter = _parameterFromArg(
          stepId: stepId,
          stepIndex: index,
          toolName: snapshot.toolName,
          key: entry.key,
          value: entry.value,
          seenNames: seenParameterNames,
          useEnglish: useEnglish,
        );
        if (parameter != null) {
          parameters.add(parameter);
        }
      }
    }

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
        'steps': steps,
      },
      'agent_reuse': {
        'strategy':
            'Use the scripted tool sequence when the UI state matches. If any selector/state check fails, keep the same goal and re-plan from the current screen.',
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
        '1. Prefer executing tool calls in execution.steps order from the JSON.',
        '2. Before each step, verify the current UI still matches before_state/constraints.',
        '3. If the page does not match, do not blindly replay coordinates; keep the same goal and let the agent re-plan the current step.',
        '4. A script runner can use script_reuse.call_shape and replace step args pointed to by parameters.bindings.',
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
      '1. 优先按 JSON 中 execution.steps 的顺序执行工具调用。',
      '2. 每一步执行前检查当前 UI 是否仍匹配 before_state/constraints。',
      '3. 如果页面不匹配，不要盲目重放坐标；保持同一目标，用 agent 重新规划当前步骤。',
      '4. script 执行器可以用 script_reuse.call_shape 传参后替换 parameters.bindings 指向的步骤 args。',
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
      'steps': aiSteps is List && aiSteps.isNotEmpty ? aiSteps : fallbackSteps,
    };
    normalized['agent_reuse'] = _mergeMaps(
      _asStringKeyMap(fallback['agent_reuse']),
      _asStringKeyMap(normalized['agent_reuse']),
    );
    normalized['script_reuse'] = _mergeMaps(
      _asStringKeyMap(fallback['script_reuse']),
      _asStringKeyMap(normalized['script_reuse']),
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

Map<String, dynamic>? _parameterFromArg({
  required String stepId,
  required int stepIndex,
  required String toolName,
  required String key,
  required dynamic value,
  required Set<String> seenNames,
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
  final baseName = _parameterBaseName(normalizedKey, toolName);
  var name = baseName;
  var suffix = 2;
  while (seenNames.contains(name)) {
    name = '${baseName}_$suffix';
    suffix++;
  }
  seenNames.add(name);
  return {
    'name': name,
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
    'bindings': ['\$.execution.steps[$stepIndex].args.$normalizedKey'],
    'source_steps': [stepId],
  };
}

bool _isParameterCandidate(String toolName, String key, dynamic value) {
  if (value is Map || value is Iterable) {
    return false;
  }
  final normalizedKey = key.toLowerCase();
  const candidateKeys = {
    'text',
    'content',
    'message',
    'query',
    'keyword',
    'url',
    'target',
    'target_description',
    'targetdescription',
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
