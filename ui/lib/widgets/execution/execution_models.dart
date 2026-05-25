// 执行相关的通用数据模型
// 用于统一 Function 和 RunLog 的展示

/// Route/reuse kind from legacy payload keys.
enum CompileKind {
  hit, // 复用已有技能
  miss, // VLM 执行
  none, // 无路由信息
}

/// 执行步骤的统一模型
class ExecutionStep {
  final int index;
  final String actionType;
  final String? targetDescription;
  final String? screenshotUrl;
  final String? xmlUrl;
  final Map<String, dynamic> params;
  final String? compileLabel; // 兼容旧 payload key，展示时按 route/reuse 语义处理
  final CompileKind compileKind;
  final String? compileFunctionId; // 复用命中时的 function id
  final bool? success;
  final String? startedAt;
  final String? finishedAt;
  final int? durationMs;
  final TokenUsageSummary? tokenUsage;

  const ExecutionStep({
    required this.index,
    required this.actionType,
    this.targetDescription,
    this.screenshotUrl,
    this.xmlUrl,
    this.params = const {},
    this.compileLabel,
    this.compileKind = CompileKind.none,
    this.compileFunctionId,
    this.success,
    this.startedAt,
    this.finishedAt,
    this.durationMs,
    this.tokenUsage,
  });

  /// 是否命中可复用技能
  bool get isCompileHit => compileKind == CompileKind.hit;

  String? get routeLabel => _userVisibleRouteText(compileLabel);

  Map<String, dynamic> toUserJson() {
    return {
      'index': index,
      'action_type': actionType,
      'params': params,
      'target_description': targetDescription,
      'route_label': routeLabel,
      if (tokenUsage?.raw.isNotEmpty == true) 'token_usage': tokenUsage!.raw,
      'success': success,
    };
  }

  /// 从 function action 创建
  factory ExecutionStep.fromFunctionAction(
    int index,
    Map<String, dynamic> action,
  ) {
    final params =
        (action['params'] as Map<dynamic, dynamic>?)?.map(
          (k, v) => MapEntry(k.toString(), v),
        ) ??
        {};
    return ExecutionStep(
      index: index,
      actionType: (action['type'] ?? '').toString(),
      targetDescription:
          (params['target_description'] ?? params['targetDescription'] ?? '')
              .toString()
              .trim(),
      params: Map<String, dynamic>.from(params),
    );
  }

  /// 从 run_log step 创建
  factory ExecutionStep.fromRunLogStep(int index, Map<String, dynamic> step) {
    final toolCall = (step['tool_call'] as Map<dynamic, dynamic>?) ?? {};
    final compileResult =
        (step['compile_result'] as Map<dynamic, dynamic>?) ?? {};
    final params =
        (toolCall['params'] as Map<dynamic, dynamic>?)?.map(
          (k, v) => MapEntry(k.toString(), v),
        ) ??
        {};

    // 解析 route/reuse 信息
    final functionId = compileResult['function_id']?.toString().trim();
    CompileKind compileKind = CompileKind.none;
    if (functionId != null && functionId.isNotEmpty) {
      compileKind = CompileKind.hit;
    } else if (step['selection_source'] == 'vlm') {
      compileKind = CompileKind.miss;
    }

    // 兼容旧的 API 返回标签，但对 UI/复制内容只暴露 route/reuse 语义。
    final apiCompileLabel = _userVisibleRouteText(
      step['compile_label']?.toString(),
    );

    return ExecutionStep(
      index: index,
      actionType: (toolCall['name'] ?? step['action_type'] ?? '').toString(),
      targetDescription:
          (params['target_description'] ??
                  params['targetDescription'] ??
                  step['action_description'] ??
                  '')
              .toString()
              .trim(),
      params: Map<String, dynamic>.from(params),
      compileLabel: apiCompileLabel,
      compileKind: compileKind,
      compileFunctionId: functionId,
      success: step['success'] as bool?,
      startedAt: step['started_at']?.toString(),
      finishedAt: step['finished_at']?.toString(),
      durationMs: (step['duration_ms'] as num?)?.toInt(),
      tokenUsage: TokenUsageSummary.fromStep(step),
    );
  }

  /// 获取动作的显示名称（英文，用于 UI 层本地化）
  /// 使用 ExecutionStepTile._getLocalizedDisplayName 获取本地化版本
  String get displayName {
    switch (actionType.trim().toLowerCase()) {
      case 'open_app':
        return 'Open App';
      case 'click':
        return 'Click';
      case 'click_node':
        return 'Click Element';
      case 'long_press':
        return 'Long Press';
      case 'input_text':
        return 'Input Text';
      case 'swipe':
        return 'Swipe';
      case 'scroll':
        return 'Scroll';
      case 'press_key':
        return 'Press Key';
      case 'finished':
        return 'Finished';
      case 'call_function':
        return 'Call Skill';
      default:
        return actionType.trim().isEmpty ? 'Action' : actionType.trim();
    }
  }

  /// 获取动作的简要描述
  String get summary {
    final parts = <String>[];
    final packageName = (params['package_name'] ?? params['packageName'] ?? '')
        .toString()
        .trim();
    final text = (params['text'] ?? params['content'] ?? '').toString().trim();
    final key = (params['key'] ?? '').toString().trim();
    final direction = (params['direction'] ?? '').toString().trim();
    final x = params['x'];
    final y = params['y'];

    if (packageName.isNotEmpty) parts.add(packageName);
    if (text.isNotEmpty) parts.add('"$text"');
    if (key.isNotEmpty) parts.add('key=$key');
    if (direction.isNotEmpty) parts.add(direction);
    if (x != null && y != null) parts.add('($x, $y)');
    if (targetDescription != null && targetDescription!.isNotEmpty) {
      parts.add(targetDescription!);
    }

    return parts.isEmpty ? displayName : '$displayName: ${parts.join(' ')}';
  }
}

/// Token 消耗统计。只用于 UI / 调试显示，不注入 agent 上下文。
class TokenUsageSummary {
  final int? totalTokens;
  final int? promptTokens;
  final int? completionTokens;
  final int? cachedTokens;
  final int? reasoningTokens;
  final int? callCount;
  final int? stepCount;
  final Map<String, dynamic> raw;

  const TokenUsageSummary({
    this.totalTokens,
    this.promptTokens,
    this.completionTokens,
    this.cachedTokens,
    this.reasoningTokens,
    this.callCount,
    this.stepCount,
    this.raw = const {},
  });

  bool get hasUsage =>
      totalTokens != null ||
      promptTokens != null ||
      completionTokens != null ||
      cachedTokens != null ||
      reasoningTokens != null ||
      callCount != null ||
      stepCount != null ||
      raw.isNotEmpty;

  factory TokenUsageSummary.fromStep(Map<String, dynamic> step) {
    final header = _asStringMap(step['header']);
    final usage = _firstMap(step, const [
      'token_usage',
      'tokenUsage',
    ]).ifEmpty(() => _firstMap(header, const ['token_usage', 'tokenUsage']));
    return TokenUsageSummary.fromMap(usage);
  }

  factory TokenUsageSummary.fromRunLog(Map<String, dynamic> runLog) {
    final usage = _firstMap(runLog, const ['token_usage', 'tokenUsage']);
    final byStep = _asMapList(
      _firstPresentValue(runLog, const [
        'token_usage_by_step',
        'tokenUsageByStep',
      ]),
    );
    final byCall = _asMapList(
      _firstPresentValue(runLog, const [
        'token_usage_by_call',
        'tokenUsageByCall',
      ]),
    );
    return TokenUsageSummary.fromMap(
      usage,
      totalTokens: _asInt(
        runLog['token_usage_total'] ??
            runLog['tokenUsageTotal'] ??
            usage['total_tokens'] ??
            usage['totalTokens'],
      ),
      callCount:
          _asInt(
            runLog['token_usage_call_count'] ??
                runLog['tokenUsageCallCount'] ??
                usage['call_count'] ??
                usage['callCount'] ??
                usage['attempt_count'] ??
                usage['attemptCount'],
          ) ??
          (byCall.isNotEmpty ? byCall.length : null),
      stepCount:
          _asInt(usage['step_count'] ?? usage['stepCount']) ??
          (byStep.isNotEmpty ? byStep.length : null),
    );
  }

  factory TokenUsageSummary.fromMap(
    Map<String, dynamic> map, {
    int? totalTokens,
    int? callCount,
    int? stepCount,
  }) {
    if (map.isEmpty &&
        totalTokens == null &&
        callCount == null &&
        stepCount == null) {
      return const TokenUsageSummary();
    }
    return TokenUsageSummary(
      totalTokens:
          totalTokens ??
          _asInt(map['total_tokens'] ?? map['totalTokens'] ?? map['total']),
      promptTokens: _asInt(map['prompt_tokens'] ?? map['promptTokens']),
      completionTokens: _asInt(
        map['completion_tokens'] ?? map['completionTokens'],
      ),
      cachedTokens: _asInt(map['cached_tokens'] ?? map['cachedTokens']),
      reasoningTokens: _asInt(
        map['reasoning_tokens'] ?? map['reasoningTokens'],
      ),
      callCount: callCount ?? _asInt(map['call_count'] ?? map['callCount']),
      stepCount: stepCount ?? _asInt(map['step_count'] ?? map['stepCount']),
      raw: Map<String, dynamic>.from(map),
    );
  }

  String get compactText {
    final parts = <String>[];
    if (totalTokens != null) parts.add(_formatTokenCount(totalTokens!));
    if (promptTokens != null || completionTokens != null) {
      parts.add('P${promptTokens ?? 0}/C${completionTokens ?? 0}');
    }
    if (reasoningTokens != null && reasoningTokens! > 0) {
      parts.add('R$reasoningTokens');
    }
    if (cachedTokens != null && cachedTokens! > 0) {
      parts.add('cached $cachedTokens');
    }
    if (callCount != null && callCount! > 0) {
      parts.add('$callCount calls');
    }
    return parts.join(' · ');
  }
}

/// 执行统计
class ExecutionStats {
  final int callCount;
  final int successCount;
  final int failCount;
  final String? lastRunId;
  final String? lastRunAt;
  final bool? lastSuccess;

  const ExecutionStats({
    this.callCount = 0,
    this.successCount = 0,
    this.failCount = 0,
    this.lastRunId,
    this.lastRunAt,
    this.lastSuccess,
  });

  factory ExecutionStats.fromMap(Map<String, dynamic>? map) {
    if (map == null) return const ExecutionStats();
    return ExecutionStats(
      callCount: (map['call_count'] as num?)?.toInt() ?? 0,
      successCount: (map['success_count'] as num?)?.toInt() ?? 0,
      failCount: (map['fail_count'] as num?)?.toInt() ?? 0,
      lastRunId: map['last_run_id']?.toString(),
      lastRunAt: map['last_run_at']?.toString(),
      lastSuccess: map['last_success'] as bool?,
    );
  }

  double get successRate =>
      callCount > 0 ? (successCount / callCount) * 100 : 0;
}

/// 资产引用
class AssetRefs {
  final List<String> xmlRefs;
  final List<String> screenshotRefs;
  final String? functionDir;

  const AssetRefs({
    this.xmlRefs = const [],
    this.screenshotRefs = const [],
    this.functionDir,
  });

  factory AssetRefs.fromMap(Map<String, dynamic>? map) {
    if (map == null) return const AssetRefs();
    return AssetRefs(
      xmlRefs:
          (map['xml_refs'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
      screenshotRefs:
          (map['screenshot_refs'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
      functionDir: map['function_dir']?.toString(),
    );
  }

  bool get hasAssets => xmlRefs.isNotEmpty || screenshotRefs.isNotEmpty;
}

/// 执行详情的统一模型
class ExecutionDetail {
  final String id;
  final ExecutionDetailType type;
  final String? goal;
  final String? description;
  final List<ExecutionStep> steps;
  final bool? success;
  final String? startedAt;
  final String? finishedAt;
  final int? durationMs;
  final ExecutionStats? stats;
  final AssetRefs? assetRefs;
  final List<String> sourceRunIds;
  final String? packageName;
  final String? appName;
  final TokenUsageSummary? tokenUsage;

  const ExecutionDetail({
    required this.id,
    required this.type,
    this.goal,
    this.description,
    this.steps = const [],
    this.success,
    this.startedAt,
    this.finishedAt,
    this.durationMs,
    this.stats,
    this.assetRefs,
    this.sourceRunIds = const [],
    this.packageName,
    this.appName,
    this.tokenUsage,
  });

  /// 从 function 创建
  factory ExecutionDetail.fromFunction(Map<String, dynamic> func) {
    final actions = (func['actions'] as List<dynamic>?) ?? [];
    final steps = actions.asMap().entries.map((e) {
      final action = e.value is Map
          ? Map<String, dynamic>.from(e.value as Map)
          : <String, dynamic>{};
      return ExecutionStep.fromFunctionAction(e.key, action);
    }).toList();

    final runStats =
        (func['run_stats'] as Map<dynamic, dynamic>?)?.map(
          (k, v) => MapEntry(k.toString(), v),
        ) ??
        {};
    final assetRefsMap =
        (func['asset_refs'] as Map<dynamic, dynamic>?)?.map(
          (k, v) => MapEntry(k.toString(), v),
        ) ??
        {};
    final metadata =
        (func['metadata'] as Map<dynamic, dynamic>?)?.map(
          (k, v) => MapEntry(k.toString(), v),
        ) ??
        {};

    return ExecutionDetail(
      id: (func['function_id'] ?? func['name'] ?? '').toString(),
      type: ExecutionDetailType.function,
      goal: (func['description'] ?? '').toString().trim(),
      description: (func['description'] ?? '').toString().trim(),
      steps: steps,
      stats: ExecutionStats.fromMap(Map<String, dynamic>.from(runStats)),
      assetRefs: AssetRefs.fromMap(Map<String, dynamic>.from(assetRefsMap)),
      sourceRunIds:
          (metadata['source_run_ids'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
      packageName: (func['package_name'] ?? '').toString().trim(),
      appName: (func['app_name'] ?? '').toString().trim(),
    );
  }

  /// 从 run_log 创建
  factory ExecutionDetail.fromRunLog(Map<String, dynamic> runLog) {
    final stepsRaw = (runLog['steps'] as List<dynamic>?) ?? [];
    final steps = stepsRaw.asMap().entries.map((e) {
      final step = e.value is Map
          ? Map<String, dynamic>.from(e.value as Map)
          : <String, dynamic>{};
      return ExecutionStep.fromRunLogStep(e.key, step);
    }).toList();

    return ExecutionDetail(
      id: (runLog['run_id'] ?? '').toString(),
      type: ExecutionDetailType.runLog,
      goal: (runLog['goal'] ?? '').toString().trim(),
      description: (runLog['goal'] ?? '').toString().trim(),
      steps: steps,
      success: runLog['success'] as bool?,
      startedAt: runLog['started_at']?.toString(),
      finishedAt: runLog['finished_at']?.toString(),
      durationMs: (runLog['duration_ms'] as num?)?.toInt(),
      packageName: (runLog['final_package_name'] ?? '').toString().trim(),
      tokenUsage: TokenUsageSummary.fromRunLog(runLog),
    );
  }

  int get stepCount => steps.length;

  String get durationText {
    if (durationMs == null) return '';
    final seconds = durationMs! / 1000;
    if (seconds < 60) return '${seconds.toStringAsFixed(1)}s';
    final minutes = seconds / 60;
    return '${minutes.toStringAsFixed(1)}min';
  }
}

enum ExecutionDetailType { function, runLog }

Map<String, dynamic> _asStringMap(Object? value) {
  if (value is Map<String, dynamic>) {
    return value;
  }
  if (value is Map) {
    return value.map((key, value) => MapEntry(key.toString(), value));
  }
  return const <String, dynamic>{};
}

Map<String, dynamic> _firstMap(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    final map = _asStringMap(source[key]);
    if (map.isNotEmpty) return map;
  }
  return const <String, dynamic>{};
}

Object? _firstPresentValue(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    if (source.containsKey(key)) {
      return source[key];
    }
  }
  return null;
}

List<Map<String, dynamic>> _asMapList(Object? value) {
  if (value is! List) return const <Map<String, dynamic>>[];
  return value.map(_asStringMap).where((item) => item.isNotEmpty).toList();
}

int? _asInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value.trim());
  return null;
}

String? _userVisibleRouteText(String? raw) {
  final value = raw?.trim();
  if (value == null || value.isEmpty) {
    return null;
  }
  return value
      .replaceAll(RegExp(r'\bcompiled\b', caseSensitive: false), 'routed')
      .replaceAll(RegExp(r'\bcompiler\b', caseSensitive: false), 'router')
      .replaceAll(RegExp(r'\bcompilation\b', caseSensitive: false), 'routing')
      .replaceAll(RegExp(r'\bcompile\b', caseSensitive: false), 'route')
      .replaceAll('编译', '路由');
}

String _formatTokenCount(int value) {
  if (value >= 1000000) return '${(value / 1000000).toStringAsFixed(1)}M';
  if (value >= 1000) return '${(value / 1000).toStringAsFixed(1)}K';
  return '$value';
}

extension _MapIfEmpty on Map<String, dynamic> {
  Map<String, dynamic> ifEmpty(Map<String, dynamic> Function() fallback) {
    return isEmpty ? fallback() : this;
  }
}
