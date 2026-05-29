import 'dart:async';
import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/task/run_log/run_log_reusable_function_converter.dart';
import 'package:ui/services/assists_core_service.dart';

enum RunLogFunctionEnhancementJobPhase {
  queued,
  enhancing,
  saving,
  completed,
  failed,
}

extension RunLogFunctionEnhancementJobPhaseX
    on RunLogFunctionEnhancementJobPhase {
  String get wireName => name;

  bool get isRunning =>
      this == RunLogFunctionEnhancementJobPhase.queued ||
      this == RunLogFunctionEnhancementJobPhase.enhancing ||
      this == RunLogFunctionEnhancementJobPhase.saving;
}

class RunLogFunctionEnhancementJob {
  const RunLogFunctionEnhancementJob({
    required this.jobId,
    required this.runId,
    required this.functionId,
    required this.inputFunctionJson,
    required this.phase,
    required this.enhancementStatus,
    required this.message,
    required this.useEnglish,
    required this.createdAt,
    required this.updatedAt,
    this.enhancedFunctionJson,
    this.agentPrompt,
    this.rawAiText,
    this.warning,
    this.enhancementReport,
    this.registrationResult,
    this.error,
  });

  final String jobId;
  final String runId;
  final String functionId;
  final Map<String, dynamic> inputFunctionJson;
  final RunLogFunctionEnhancementJobPhase phase;
  final RunLogReusableFunctionEnhancementStatus enhancementStatus;
  final String message;
  final bool useEnglish;
  final DateTime createdAt;
  final DateTime updatedAt;
  final Map<String, dynamic>? enhancedFunctionJson;
  final String? agentPrompt;
  final String? rawAiText;
  final String? warning;
  final Map<String, dynamic>? enhancementReport;
  final Map<String, dynamic>? registrationResult;
  final String? error;

  bool get isRunning => phase.isRunning;
  bool get isCompleted =>
      phase == RunLogFunctionEnhancementJobPhase.completed &&
      enhancementStatus.isTerminal;
  bool get isSaved =>
      isCompleted &&
      registrationResult != null &&
      registrationResult?['success'] == true;

  RunLogReusableFunctionSpec? get savedSpec {
    final json = enhancedFunctionJson;
    if (json == null || !isSaved) {
      return null;
    }
    return RunLogReusableFunctionSpec(
      json: json,
      agentPrompt:
          agentPrompt ??
          RunLogReusableFunctionConverter.buildAgentPrompt(
            json,
            useEnglish: useEnglish,
          ),
      aiEnhanced: enhancementStatus.isApplied,
      rawAiText: rawAiText,
      warning: warning,
      enhancementStatus: enhancementStatus,
      enhancementMessage: message,
      enhancementReport: enhancementReport,
    );
  }

  RunLogFunctionEnhancementJob copyWith({
    RunLogFunctionEnhancementJobPhase? phase,
    RunLogReusableFunctionEnhancementStatus? enhancementStatus,
    String? message,
    DateTime? updatedAt,
    Map<String, dynamic>? enhancedFunctionJson,
    String? agentPrompt,
    String? rawAiText,
    String? warning,
    Map<String, dynamic>? enhancementReport,
    Map<String, dynamic>? registrationResult,
    String? error,
  }) {
    return RunLogFunctionEnhancementJob(
      jobId: jobId,
      runId: runId,
      functionId: functionId,
      inputFunctionJson: inputFunctionJson,
      phase: phase ?? this.phase,
      enhancementStatus: enhancementStatus ?? this.enhancementStatus,
      message: message ?? this.message,
      useEnglish: useEnglish,
      createdAt: createdAt,
      updatedAt: updatedAt ?? DateTime.now().toUtc(),
      enhancedFunctionJson: enhancedFunctionJson ?? this.enhancedFunctionJson,
      agentPrompt: agentPrompt ?? this.agentPrompt,
      rawAiText: rawAiText ?? this.rawAiText,
      warning: warning ?? this.warning,
      enhancementReport: enhancementReport ?? this.enhancementReport,
      registrationResult: registrationResult ?? this.registrationResult,
      error: error,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'job_id': jobId,
      'run_id': runId,
      'function_id': functionId,
      'input_function_json': inputFunctionJson,
      'phase': phase.wireName,
      'enhancement_status': enhancementStatus.wireName,
      'message': message,
      'use_english': useEnglish,
      'created_at': createdAt.toIso8601String(),
      'updated_at': updatedAt.toIso8601String(),
      if (enhancedFunctionJson != null)
        'enhanced_function_json': enhancedFunctionJson,
      if (agentPrompt != null) 'agent_prompt': agentPrompt,
      if (rawAiText != null) 'raw_ai_text': rawAiText,
      if (warning != null) 'warning': warning,
      if (enhancementReport != null) 'enhancement_report': enhancementReport,
      if (registrationResult != null) 'registration_result': registrationResult,
      if (error != null) 'error': error,
    };
  }

  factory RunLogFunctionEnhancementJob.fromJson(Map<String, dynamic> json) {
    final createdAt = DateTime.tryParse((json['created_at'] ?? '').toString());
    final updatedAt = DateTime.tryParse((json['updated_at'] ?? '').toString());
    return RunLogFunctionEnhancementJob(
      jobId: (json['job_id'] ?? '').toString(),
      runId: (json['run_id'] ?? '').toString(),
      functionId: (json['function_id'] ?? '').toString(),
      inputFunctionJson: _stringKeyMap(json['input_function_json']),
      phase: _phaseFromWire(json['phase']),
      enhancementStatus: _enhancementStatusFromWire(json['enhancement_status']),
      message: (json['message'] ?? '').toString(),
      useEnglish: json['use_english'] == true,
      createdAt: createdAt ?? DateTime.now().toUtc(),
      updatedAt: updatedAt ?? DateTime.now().toUtc(),
      enhancedFunctionJson: _nullableStringKeyMap(
        json['enhanced_function_json'],
      ),
      agentPrompt: json['agent_prompt']?.toString(),
      rawAiText: json['raw_ai_text']?.toString(),
      warning: json['warning']?.toString(),
      enhancementReport: _nullableStringKeyMap(json['enhancement_report']),
      registrationResult: _nullableStringKeyMap(json['registration_result']),
      error: json['error']?.toString(),
    );
  }
}

class RunLogFunctionEnhancementJobService {
  const RunLogFunctionEnhancementJobService._();

  static const String _jobsKey = 'run_log_function_enhancement_jobs_v1';
  static final StreamController<RunLogFunctionEnhancementJob>
  _jobChangedController =
      StreamController<RunLogFunctionEnhancementJob>.broadcast();
  static final Map<String, Future<void>> _runningJobs = {};
  static final Set<String> _canceledJobIds = <String>{};
  static int _jobSequence = 0;

  static Stream<RunLogFunctionEnhancementJob> watchJob(String jobId) {
    return _jobChangedController.stream.where((job) => job.jobId == jobId);
  }

  static Future<RunLogFunctionEnhancementJob?> latestFor({
    required String runId,
    required String functionId,
  }) async {
    final normalizedRunId = runId.trim();
    final normalizedFunctionId = functionId.trim();
    final jobs = await _loadJobs();
    RunLogFunctionEnhancementJob? latest;
    for (final job in jobs) {
      if (job.runId != normalizedRunId ||
          job.functionId != normalizedFunctionId) {
        continue;
      }
      if (latest == null || job.updatedAt.isAfter(latest.updatedAt)) {
        latest = job;
      }
    }
    return latest;
  }

  static Future<RunLogFunctionEnhancementJob> enqueue({
    required String runId,
    required Map<String, dynamic> functionJson,
    required bool useEnglish,
  }) async {
    final normalizedRunId = runId.trim();
    final normalizedFunctionJson = _stringKeyMap(functionJson);
    final functionId = (normalizedFunctionJson['function_id'] ?? '')
        .toString()
        .trim();
    if (functionId.isEmpty) {
      throw Exception('function_id 为空，无法增强 Function');
    }
    final now = DateTime.now().toUtc();
    final sequence = _jobSequence++;
    final job = RunLogFunctionEnhancementJob(
      jobId:
          'enhance_${_safeId(functionId)}_${now.microsecondsSinceEpoch}_${sequence.toString()}',
      runId: normalizedRunId,
      functionId: functionId,
      inputFunctionJson: normalizedFunctionJson,
      phase: RunLogFunctionEnhancementJobPhase.queued,
      enhancementStatus: RunLogReusableFunctionEnhancementStatus.enhancing,
      message: useEnglish
          ? 'Agent queued background enhancement for this Function.'
          : 'Agent 已将这个 Function 加入后台增强队列。',
      useEnglish: useEnglish,
      createdAt: now,
      updatedAt: now,
    );
    await _upsertJob(job);
    _startJob(job.jobId);
    return job;
  }

  static Future<void> resumePendingJobs() async {
    final jobs = await _loadJobs();
    for (final job in jobs) {
      if (job.phase.isRunning) {
        _startJob(job.jobId);
      }
    }
  }

  static Future<void> clearForTesting() async {
    _canceledJobIds.addAll(_runningJobs.keys);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_jobsKey);
    _runningJobs.clear();
  }

  static void _startJob(String jobId) {
    if (_runningJobs.containsKey(jobId)) {
      return;
    }
    _canceledJobIds.remove(jobId);
    final future = _runJob(jobId);
    _runningJobs[jobId] = future;
    future.whenComplete(() => _runningJobs.remove(jobId));
  }

  static Future<void> _runJob(String jobId) async {
    var job = await _findJob(jobId);
    if (job == null || _canceledJobIds.contains(jobId)) {
      return;
    }
    try {
      RunLogReusableFunctionSpec enhanced;
      if (job.phase == RunLogFunctionEnhancementJobPhase.saving &&
          job.enhancedFunctionJson != null) {
        enhanced = RunLogReusableFunctionSpec(
          json: job.enhancedFunctionJson!,
          agentPrompt:
              job.agentPrompt ??
              RunLogReusableFunctionConverter.buildAgentPrompt(
                job.enhancedFunctionJson!,
                useEnglish: job.useEnglish,
              ),
          aiEnhanced: job.enhancementStatus.isApplied,
          rawAiText: job.rawAiText,
          warning: job.warning,
          enhancementStatus: job.enhancementStatus,
          enhancementMessage: job.message,
          enhancementReport: job.enhancementReport,
        );
      } else {
        job = await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.enhancing,
            enhancementStatus:
                RunLogReusableFunctionEnhancementStatus.enhancing,
            message: job.useEnglish
                ? 'Agent is refining labels, steps, parameters, and reuse metadata in the background.'
                : 'Agent 正在后台整理名称、步骤、参数和复用元数据。',
          ),
        );
        if (_canceledJobIds.contains(jobId)) return;
        enhanced = await RunLogReusableFunctionConverter.enhanceLabels(
          functionJson: job.inputFunctionJson,
          useEnglish: job.useEnglish,
        );
        if (_canceledJobIds.contains(jobId)) return;
      }

      if (enhanced.enhancementStatus ==
          RunLogReusableFunctionEnhancementStatus.failed) {
        await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.failed,
            enhancementStatus: RunLogReusableFunctionEnhancementStatus.failed,
            message:
                enhanced.enhancementMessage ??
                enhanced.warning ??
                (job.useEnglish
                    ? 'Agent enhancement produced no usable result. Keeping the current Function.'
                    : 'Agent 增强未产生可用结果，当前 Function 保持原样。'),
            rawAiText: enhanced.rawAiText,
            warning: enhanced.warning,
            enhancementReport: enhanced.enhancementReport,
            error: enhanced.warning,
          ),
        );
        return;
      }

      job = await _transition(
        job.copyWith(
          phase: RunLogFunctionEnhancementJobPhase.saving,
          enhancementStatus: RunLogReusableFunctionEnhancementStatus.enhancing,
          message: job.useEnglish
              ? 'Agent generated the enhancement and is saving it to the Function library.'
              : 'Agent 已生成增强结果，正在保存到 Function 库。',
          enhancedFunctionJson: enhanced.json,
          agentPrompt: enhanced.agentPrompt,
          rawAiText: enhanced.rawAiText,
          warning: enhanced.warning,
          enhancementReport: enhanced.enhancementReport,
        ),
      );
      if (_canceledJobIds.contains(jobId)) return;

      final saveResult =
          await AssistsMessageService.registerOobReusableFunction(
            functionSpec: enhanced.json,
          );
      if (_canceledJobIds.contains(jobId)) return;
      final saveJson = saveResult.rawJson.isNotEmpty
          ? saveResult.rawJson
          : <String, dynamic>{
              'success': saveResult.success,
              'function_id': saveResult.functionId,
              'created_function_id': saveResult.createdFunctionId,
              'already_exists': saveResult.alreadyExists,
              'asset_kind': saveResult.assetKind,
              'asset_state': saveResult.assetState,
              if (saveResult.errorMessage != null)
                'error_message': saveResult.errorMessage,
            };
      if (!saveResult.success) {
        await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.failed,
            enhancementStatus: RunLogReusableFunctionEnhancementStatus.failed,
            message: job.useEnglish
                ? 'Agent generated an enhancement, but saving the Function failed. The current Function is unchanged.'
                : 'Agent 已生成增强结果，但保存 Function 失败；当前 Function 保持原样。',
            registrationResult: saveJson,
            error:
                saveResult.errorMessage ??
                (job.useEnglish ? 'Function save failed' : 'Function 保存失败'),
          ),
        );
        return;
      }

      await _transition(
        job.copyWith(
          phase: RunLogFunctionEnhancementJobPhase.completed,
          enhancementStatus: enhanced.enhancementStatus,
          message:
              enhanced.enhancementMessage ??
              _statusMessage(enhanced.enhancementStatus, job.useEnglish),
          registrationResult: saveJson,
          error: null,
        ),
      );
    } catch (error) {
      final current = await _findJob(jobId) ?? job;
      if (current == null || _canceledJobIds.contains(jobId)) {
        return;
      }
      await _transition(
        current.copyWith(
          phase: RunLogFunctionEnhancementJobPhase.failed,
          enhancementStatus: RunLogReusableFunctionEnhancementStatus.failed,
          message: current.useEnglish
              ? 'Agent enhancement failed. Keeping the current Function.'
              : 'Agent 增强失败，当前 Function 保持原样。',
          error: error.toString(),
        ),
      );
    }
  }

  static Future<RunLogFunctionEnhancementJob> _transition(
    RunLogFunctionEnhancementJob job,
  ) async {
    if (_canceledJobIds.contains(job.jobId)) {
      return job;
    }
    final updated = job.copyWith(updatedAt: DateTime.now().toUtc());
    await _upsertJob(updated);
    return updated;
  }

  static Future<RunLogFunctionEnhancementJob?> _findJob(String jobId) async {
    final jobs = await _loadJobs();
    for (final job in jobs) {
      if (job.jobId == jobId) {
        return job;
      }
    }
    return null;
  }

  static Future<List<RunLogFunctionEnhancementJob>> _loadJobs() async {
    final prefs = await SharedPreferences.getInstance();
    final rawList = prefs.getStringList(_jobsKey) ?? const <String>[];
    final output = <RunLogFunctionEnhancementJob>[];
    for (final raw in rawList) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is Map) {
          final job = RunLogFunctionEnhancementJob.fromJson(
            decoded.map((key, value) => MapEntry(key.toString(), value)),
          );
          if (job.jobId.isNotEmpty && job.functionId.isNotEmpty) {
            output.add(job);
          }
        }
      } catch (_) {
        // Ignore corrupt persisted jobs; a new enhancement can be queued.
      }
    }
    output.sort((a, b) => a.updatedAt.compareTo(b.updatedAt));
    return output;
  }

  static Future<void> _upsertJob(RunLogFunctionEnhancementJob job) async {
    final jobs = await _loadJobs();
    final index = jobs.indexWhere((item) => item.jobId == job.jobId);
    if (index >= 0) {
      jobs[index] = job;
    } else {
      jobs.add(job);
    }
    jobs.sort((a, b) => a.updatedAt.compareTo(b.updatedAt));
    final retained = jobs.length > 40 ? jobs.sublist(jobs.length - 40) : jobs;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(
      _jobsKey,
      retained.map((item) => jsonEncode(item.toJson())).toList(),
    );
    _jobChangedController.add(job);
  }

  static String _statusMessage(
    RunLogReusableFunctionEnhancementStatus status,
    bool useEnglish,
  ) {
    switch (status) {
      case RunLogReusableFunctionEnhancementStatus.enhanced:
        return useEnglish
            ? 'Agent enhancement applied and saved.'
            : 'Agent 增强已应用并保存。';
      case RunLogReusableFunctionEnhancementStatus.partial:
        return useEnglish
            ? 'Agent enhancement partially applied and saved.'
            : 'Agent 增强已部分应用并保存。';
      case RunLogReusableFunctionEnhancementStatus.unchanged:
        return useEnglish
            ? 'Agent checked this Function and found no safe change.'
            : 'Agent 已检查，没有安全可应用的变化。';
      case RunLogReusableFunctionEnhancementStatus.failed:
        return useEnglish ? 'Agent enhancement failed.' : 'Agent 增强失败。';
      case RunLogReusableFunctionEnhancementStatus.enhancing:
        return useEnglish ? 'Agent enhancement is running.' : 'Agent 正在后台增强。';
      case RunLogReusableFunctionEnhancementStatus.none:
        return '';
    }
  }
}

RunLogFunctionEnhancementJobPhase _phaseFromWire(dynamic value) {
  final name = value?.toString().trim().toLowerCase() ?? '';
  for (final phase in RunLogFunctionEnhancementJobPhase.values) {
    if (phase.name == name) {
      return phase;
    }
  }
  return RunLogFunctionEnhancementJobPhase.queued;
}

RunLogReusableFunctionEnhancementStatus _enhancementStatusFromWire(
  dynamic value,
) {
  final name = value?.toString().trim().toLowerCase() ?? '';
  for (final status in RunLogReusableFunctionEnhancementStatus.values) {
    if (status.name == name) {
      return status;
    }
  }
  return RunLogReusableFunctionEnhancementStatus.none;
}

Map<String, dynamic> _stringKeyMap(dynamic value) {
  final safe = _jsonSafe(value);
  if (safe is Map) {
    return safe.map((key, item) => MapEntry(key.toString(), item));
  }
  return <String, dynamic>{};
}

Map<String, dynamic>? _nullableStringKeyMap(dynamic value) {
  final map = _stringKeyMap(value);
  return map.isEmpty ? null : map;
}

dynamic _jsonSafe(dynamic value) {
  if (value == null || value is String || value is num || value is bool) {
    return value;
  }
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), _jsonSafe(item)));
  }
  if (value is Iterable) {
    return value.map(_jsonSafe).toList(growable: false);
  }
  return value.toString();
}

String _safeId(String value) {
  final safe = value
      .replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '_')
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^[_-]+|[_-]+$'), '');
  return safe.isEmpty ? 'function' : safe;
}
