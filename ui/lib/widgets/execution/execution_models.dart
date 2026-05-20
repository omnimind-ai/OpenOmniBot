// 执行相关的通用数据模型
// 用于统一 Function 和 RunLog 的展示

/// Compile kind 枚举
enum CompileKind {
  hit, // 复用已有技能
  miss, // VLM 执行
  none, // 无 compile 信息
}

/// 执行步骤的统一模型
class ExecutionStep {
  final int index;
  final String actionType;
  final String? targetDescription;
  final String? screenshotUrl;
  final String? xmlUrl;
  final Map<String, dynamic> params;
  final String? compileLabel; // 兼容旧代码，建议使用 compileKind
  final CompileKind compileKind;
  final String? compileFunctionId; // compile hit 时的 function id
  final bool? success;
  final String? startedAt;
  final String? finishedAt;
  final int? durationMs;

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
  });

  /// 是否是 compile hit（复用已有技能）
  bool get isCompileHit => compileKind == CompileKind.hit;

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

    // 解析 compile 信息
    final functionId = compileResult['function_id']?.toString().trim();
    CompileKind compileKind = CompileKind.none;
    if (functionId != null && functionId.isNotEmpty) {
      compileKind = CompileKind.hit;
    } else if (step['selection_source'] == 'vlm') {
      compileKind = CompileKind.miss;
    }

    // 兼容旧的 compileLabel（来自 API 返回）
    final apiCompileLabel = step['compile_label']?.toString().trim();

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
