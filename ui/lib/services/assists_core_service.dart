import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:ui/services/agent_schedule_bridge_service.dart';
import 'package:ui/services/app_state_service.dart';

// 卡片推送
typedef CardPushCallback<T> = void Function(Map<String, dynamic> cardData);
//陪伴任务结束
typedef TaskFinishCallback = void Function();
//消息回执
typedef ChatTaskMessageCallBack =
    void Function(String taskID, String content, String? type);
//消息回执结束
typedef ChatTaskMessageEndCallBack = void Function(String taskID);
//VLM任务结束
typedef VLMTaskFinishEndCallBack = void Function(String? taskId);
//普通任务结束
typedef CommonTaskFinishEndCallBack = void Function();
//VLM请求用户输入（INFO动作）
typedef VLMRequestUserInputCallBack =
    void Function(String question, String? taskId);
//Dispatch流式数据回调
typedef DispatchStreamDataCallBack =
    void Function(String taskID, String data, String fullContent);
//Dispatch流式结束回调
typedef DispatchStreamEndCallBack =
    void Function(String taskID, String fullContent);
//Dispatch流式错误回调
typedef DispatchStreamErrorCallBack =
    void Function(
      String taskID,
      String error,
      String fullContent,
      bool isRateLimited,
    );

// Agent相关回调
typedef AgentThinkingStartCallback = void Function(String taskId);
typedef AgentThinkingUpdateCallback =
    void Function(String taskId, String thinking);
typedef AgentToolCallStartCallback = void Function(AgentToolEventData event);
typedef AgentToolCallProgressCallback = void Function(AgentToolEventData event);
typedef AgentToolCallCompleteCallback = void Function(AgentToolEventData event);
typedef AgentChatMessageCallback =
    void Function(
      String taskId,
      String message, {
      bool isFinal,
      double? prefillTokensPerSecond,
      double? decodeTokensPerSecond,
    });
typedef AgentPromptTokenUsageCallback =
    void Function(
      String taskId,
      int latestPromptTokens,
      int? promptTokenThreshold,
    );
typedef AgentContextCompactionStateCallback =
    void Function(
      String taskId,
      bool isCompacting,
      int? latestPromptTokens,
      int? promptTokenThreshold,
    );
typedef AgentClarifyCallback =
    void Function(String taskId, String question, List<String> missingFields);
typedef AgentCompleteCallback =
    void Function(
      String taskId,
      bool success,
      String outputKind,
      bool hasUserVisibleOutput,
      int? latestPromptTokens,
      int? promptTokenThreshold,
    );
typedef AgentErrorCallback = void Function(String taskId, String error);
typedef AgentPermissionRequiredCallback =
    void Function(String taskId, List<String> missing);
typedef AgentUtgConfirmCallback = Future<bool> Function(String prompt);
typedef ScheduledTaskCancelledCallBack = void Function(String taskId);
typedef ScheduledTaskExecuteNowCallBack = void Function(String taskId);

class ModelAvailabilityCheckResult {
  final bool available;
  final int? code;
  final String message;

  const ModelAvailabilityCheckResult({
    required this.available,
    required this.code,
    required this.message,
  });

  factory ModelAvailabilityCheckResult.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) {
      return const ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: 'Check failed: empty response',
      );
    }

    final codeValue = map['code'];
    int? code;
    if (codeValue is int) {
      code = codeValue;
    } else if (codeValue is String) {
      code = int.tryParse(codeValue);
    }

    return ModelAvailabilityCheckResult(
      available: map['available'] == true,
      code: code,
      message: (map['message'] ?? '').toString(),
    );
  }
}

class UtgBridgeConfig {
  final bool utgEnabled;
  final String omniflowBaseUrl;
  final String resolvedOmniflowBaseUrl;
  final bool providerAutoStartEnabled;
  final String providerStartCommand;
  final bool providerStartCommandConfigured;
  final String? providerWorkingDirectory;
  final bool providerHealthy;
  final String providerHealthStatus;
  final String providerRunLogPath;
  final String canonicalRunLogPath;

  const UtgBridgeConfig({
    required this.utgEnabled,
    required this.omniflowBaseUrl,
    required this.resolvedOmniflowBaseUrl,
    required this.providerAutoStartEnabled,
    required this.providerStartCommand,
    required this.providerStartCommandConfigured,
    required this.providerWorkingDirectory,
    required this.providerHealthy,
    required this.providerHealthStatus,
    required this.providerRunLogPath,
    required this.canonicalRunLogPath,
  });

  factory UtgBridgeConfig.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    final health = (raw['providerHealth'] as Map?) ?? const {};
    return UtgBridgeConfig(
      utgEnabled: raw['utgEnabled'] != false,
      omniflowBaseUrl: (raw['omniflowBaseUrl'] ?? '').toString(),
      resolvedOmniflowBaseUrl: (raw['resolvedOmniflowBaseUrl'] ?? '')
          .toString(),
      providerAutoStartEnabled: raw['providerAutoStartEnabled'] == true,
      providerStartCommand: (raw['providerStartCommand'] ?? '').toString(),
      providerStartCommandConfigured:
          raw['providerStartCommandConfigured'] == true,
      providerWorkingDirectory: raw['providerWorkingDirectory']?.toString(),
      providerHealthy: raw['providerHealthy'] == true,
      providerHealthStatus:
          (raw['providerHealthStatus'] ?? health['status'] ?? '').toString(),
      providerRunLogPath: (raw['providerRunLogPath'] ?? '').toString(),
      canonicalRunLogPath: (raw['canonicalRunLogPath'] ?? '').toString(),
    );
  }
}

/// Embedded Provider 状态
class EmbeddedProviderStatus {
  final bool installed;
  final String? installedVersion;
  final bool running;
  final int port;
  final String? binaryPath;
  final String latestVersion;
  final bool needsUpdate;

  const EmbeddedProviderStatus({
    required this.installed,
    required this.installedVersion,
    required this.running,
    required this.port,
    required this.binaryPath,
    required this.latestVersion,
    required this.needsUpdate,
  });

  factory EmbeddedProviderStatus.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return EmbeddedProviderStatus(
      installed: raw['installed'] == true,
      installedVersion: raw['installedVersion']?.toString(),
      running: raw['running'] == true,
      port: (raw['port'] as int?) ?? 9417,
      binaryPath: raw['binaryPath']?.toString(),
      latestVersion: (raw['latestVersion'] ?? '0.1.0').toString(),
      needsUpdate: raw['needsUpdate'] == true,
    );
  }
}

/// Embedded Provider 安装结果
class EmbeddedProviderInstallResult {
  final bool success;
  final String? version;
  final String? binaryPath;
  final String? error;

  const EmbeddedProviderInstallResult({
    required this.success,
    this.version,
    this.binaryPath,
    this.error,
  });

  factory EmbeddedProviderInstallResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return EmbeddedProviderInstallResult(
      success: raw['success'] == true,
      version: raw['version']?.toString(),
      binaryPath: raw['binaryPath']?.toString(),
      error: raw['error']?.toString(),
    );
  }
}

/// Provider 更新检查结果
class UtgUpdateCheckResult {
  final String currentVersion;
  final String? latestVersion;
  final String? latestCommit;
  final bool updateAvailable;
  final String wheelUrl;
  final bool localWheelExists;
  final String? localWheelHash;
  final String? error;

  const UtgUpdateCheckResult({
    required this.currentVersion,
    this.latestVersion,
    this.latestCommit,
    required this.updateAvailable,
    required this.wheelUrl,
    required this.localWheelExists,
    this.localWheelHash,
    this.error,
  });

  factory UtgUpdateCheckResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgUpdateCheckResult(
      currentVersion: (raw['current_version'] ?? 'unknown').toString(),
      latestVersion: raw['latest_version']?.toString(),
      latestCommit: raw['latest_commit']?.toString(),
      updateAvailable: raw['update_available'] == true,
      wheelUrl: (raw['wheel_url'] ?? '').toString(),
      localWheelExists: raw['local_wheel_exists'] == true,
      localWheelHash: raw['local_wheel_hash']?.toString(),
      error: raw['error']?.toString(),
    );
  }
}

/// Provider 更新应用结果
class UtgUpdateApplyResult {
  final bool success;
  final String? previousVersion;
  final String? installedVersion;
  final String? latestVersion;
  final bool restartRequired;
  final String? message;
  final String? error;

  const UtgUpdateApplyResult({
    required this.success,
    this.previousVersion,
    this.installedVersion,
    this.latestVersion,
    required this.restartRequired,
    this.message,
    this.error,
  });

  factory UtgUpdateApplyResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgUpdateApplyResult(
      success: raw['success'] == true,
      previousVersion: raw['previous_version']?.toString(),
      installedVersion: raw['installed_version']?.toString(),
      latestVersion: raw['latest_version']?.toString(),
      restartRequired: raw['restart_required'] == true,
      message: raw['message']?.toString(),
      error: raw['error']?.toString(),
    );
  }
}

class UtgFunctionSummary {
  final String functionId;
  final String description;
  final int actionCount;  // Recursive total (expanding call_function)
  final int stepCount;    // Direct steps only
  final List<String> parameterNames;
  final Map<String, String> parameterExamples;
  final String startNodeId;
  final String endNodeId;
  final String startNodeDescription;
  final String endNodeDescription;
  final String packageName;
  final String appName;
  final String groupName;
  final String source;
  final String createdAt;
  final String updatedAt;
  final String syncStatus;
  final String syncOrigin;
  final String cloudBaseUrl;
  final String lastSyncedAt;
  final String assetKind;
  final String assetState;
  final String derivedFromRawFunctionId;
  final int runCount;
  final int successCount;
  final int failCount;
  final Map<String, dynamic> lastRun;
  // 新增字段
  final List<String> sourceRunIds;
  final Map<String, dynamic> assetRefs;
  final Map<String, dynamic> runStats;

  const UtgFunctionSummary({
    required this.functionId,
    required this.description,
    required this.actionCount,
    required this.stepCount,
    required this.parameterNames,
    required this.parameterExamples,
    required this.startNodeId,
    required this.endNodeId,
    required this.startNodeDescription,
    required this.endNodeDescription,
    required this.packageName,
    required this.appName,
    required this.groupName,
    required this.source,
    required this.createdAt,
    required this.updatedAt,
    required this.syncStatus,
    required this.syncOrigin,
    required this.cloudBaseUrl,
    required this.lastSyncedAt,
    required this.assetKind,
    required this.assetState,
    required this.derivedFromRawFunctionId,
    required this.runCount,
    required this.successCount,
    required this.failCount,
    required this.lastRun,
    this.sourceRunIds = const [],
    this.assetRefs = const {},
    this.runStats = const {},
  });

  factory UtgFunctionSummary.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgFunctionSummary(
      functionId: (raw['function_id'] ?? '').toString(),
      description: (raw['description'] ?? '').toString(),
      actionCount: raw['action_count'] is num
          ? (raw['action_count'] as num).toInt()
          : int.tryParse((raw['action_count'] ?? '0').toString()) ?? 0,
      stepCount: raw['step_count'] is num
          ? (raw['step_count'] as num).toInt()
          : int.tryParse((raw['step_count'] ?? '0').toString()) ?? 0,
      parameterNames:
          (raw['parameter_names'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          const <String>[],
      parameterExamples:
          (raw['parameter_examples'] as Map<dynamic, dynamic>?)?.map(
            (k, v) => MapEntry(k.toString(), v.toString()),
          ) ??
          const <String, String>{},
      startNodeId: (raw['start_node_id'] ?? '').toString(),
      endNodeId: (raw['end_node_id'] ?? '').toString(),
      startNodeDescription: (raw['start_node_description'] ?? '').toString(),
      endNodeDescription: (raw['end_node_description'] ?? '').toString(),
      packageName: (raw['package_name'] ?? '').toString(),
      appName: (raw['app_name'] ?? '').toString(),
      groupName: (raw['group_name'] ?? '').toString(),
      source: (raw['source'] ?? '').toString(),
      createdAt: (raw['created_at'] ?? '').toString(),
      updatedAt: (raw['updated_at'] ?? '').toString(),
      syncStatus: (raw['sync_status'] ?? '').toString(),
      syncOrigin: (raw['sync_origin'] ?? '').toString(),
      cloudBaseUrl: (raw['cloud_base_url'] ?? '').toString(),
      lastSyncedAt: (raw['last_synced_at'] ?? '').toString(),
      assetKind: (raw['function_kind'] ?? raw['asset_kind'] ?? '').toString(),
      assetState: (raw['asset_state'] ?? '').toString(),
      derivedFromRawFunctionId: (raw['derived_from_raw_function_id'] ?? '')
          .toString(),
      runCount: ((raw['run_stats'] as Map?)?['run_count'] is num)
          ? (((raw['run_stats'] as Map?)?['run_count'] as num).toInt())
          : int.tryParse(
                  (((raw['run_stats'] as Map?)?['run_count']) ?? '0')
                      .toString(),
                ) ??
                0,
      successCount: ((raw['run_stats'] as Map?)?['success_count'] is num)
          ? (((raw['run_stats'] as Map?)?['success_count'] as num).toInt())
          : int.tryParse(
                  (((raw['run_stats'] as Map?)?['success_count']) ?? '0')
                      .toString(),
                ) ??
                0,
      failCount: ((raw['run_stats'] as Map?)?['fail_count'] is num)
          ? (((raw['run_stats'] as Map?)?['fail_count'] as num).toInt())
          : int.tryParse(
                  (((raw['run_stats'] as Map?)?['fail_count']) ?? '0')
                      .toString(),
                ) ??
                0,
      lastRun:
          (raw['last_run'] as Map<dynamic, dynamic>?)?.map(
            (k, v) => MapEntry(k.toString(), v),
          ) ??
          const <String, dynamic>{},
      sourceRunIds:
          (raw['source_run_ids'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          const <String>[],
      assetRefs:
          (raw['asset_refs'] as Map<dynamic, dynamic>?)?.map(
            (k, v) => MapEntry(k.toString(), v),
          ) ??
          const <String, dynamic>{},
      runStats:
          (raw['run_stats'] as Map<dynamic, dynamic>?)?.map(
            (k, v) => MapEntry(k.toString(), v),
          ) ??
          const <String, dynamic>{},
    );
  }
}

class UtgFunctionsSnapshot {
  final bool success;
  final int count;
  final List<UtgFunctionSummary> functions;
  final String provider;

  const UtgFunctionsSnapshot({
    required this.success,
    required this.count,
    required this.functions,
    required this.provider,
  });

  factory UtgFunctionsSnapshot.fromMap(Map<String, dynamic> map) {
    return UtgFunctionsSnapshot(
      success: map['success'] == true,
      count: map['count'] is num
          ? (map['count'] as num).toInt()
          : int.tryParse((map['count'] ?? '0').toString()) ?? 0,
      functions:
          (map['functions'] as List<dynamic>?)
              ?.map((e) => UtgFunctionSummary.fromMap(e as Map?))
              .toList() ??
          const <UtgFunctionSummary>[],
      provider: (map['provider'] ?? '').toString(),
    );
  }
}

class UtgBridgeExecutionContext {
  final String bridgeBaseUrl;
  final String bridgeToken;
  final String resolvedOmniflowBaseUrl;
  final bool providerHealthy;
  final String providerMessage;

  const UtgBridgeExecutionContext({
    required this.bridgeBaseUrl,
    required this.bridgeToken,
    required this.resolvedOmniflowBaseUrl,
    required this.providerHealthy,
    required this.providerMessage,
  });

  factory UtgBridgeExecutionContext.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgBridgeExecutionContext(
      bridgeBaseUrl: (raw['bridgeBaseUrl'] ?? '').toString(),
      bridgeToken: (raw['bridgeToken'] ?? '').toString(),
      resolvedOmniflowBaseUrl: (raw['resolvedOmniflowBaseUrl'] ?? '')
          .toString(),
      providerHealthy: raw['providerHealthy'] == true,
      providerMessage: (raw['providerMessage'] ?? '').toString(),
    );
  }
}

class UtgManualRunResult {
  final bool success;
  final String goal;
  final String functionId;
  final String? errorCode;
  final String? errorMessage;
  final Map<String, dynamic> terminalState;
  final String providerRunLogPath;
  final String canonicalRunLogPath;
  final Map<String, dynamic> rawJson;

  const UtgManualRunResult({
    required this.success,
    required this.goal,
    required this.functionId,
    required this.errorCode,
    required this.errorMessage,
    required this.terminalState,
    required this.providerRunLogPath,
    required this.canonicalRunLogPath,
    required this.rawJson,
  });

  factory UtgManualRunResult.fromMap(Map<String, dynamic> map) {
    return UtgManualRunResult(
      success: map['success'] == true,
      goal: (map['goal'] ?? '').toString(),
      functionId: (map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      terminalState:
          (map['terminal_state'] as Map<dynamic, dynamic>?)?.map(
            (k, v) => MapEntry(k.toString(), v),
          ) ??
          const <String, dynamic>{},
      providerRunLogPath: (map['provider_run_log_path'] ?? '').toString(),
      canonicalRunLogPath: (map['canonical_run_log_path'] ?? '').toString(),
      rawJson: Map<String, dynamic>.from(map),
    );
  }
}

class UtgFunctionMutationResult {
  final bool success;
  final String functionId;
  final String createdFunctionId;
  final String? errorCode;
  final String? errorMessage;
  final bool deleted;
  final bool imported;
  final bool alreadyExists;
  final int count;
  final String? cloudBaseUrl;
  final String assetKind;
  final String assetState;
  final String derivedFromRawFunctionId;
  final Map<String, dynamic> rawJson;

  // 语义去重相关字段
  final bool isUpdate;
  final double? similarity;
  final String? enrichedGoal;
  final String? originalGoal;
  final List<String> sourceRunIds;

  const UtgFunctionMutationResult({
    required this.success,
    required this.functionId,
    required this.createdFunctionId,
    required this.errorCode,
    required this.errorMessage,
    required this.deleted,
    required this.imported,
    required this.alreadyExists,
    required this.count,
    required this.cloudBaseUrl,
    required this.assetKind,
    required this.assetState,
    required this.derivedFromRawFunctionId,
    required this.rawJson,
    this.isUpdate = false,
    this.similarity,
    this.enrichedGoal,
    this.originalGoal,
    this.sourceRunIds = const [],
  });

  factory UtgFunctionMutationResult.fromMap(Map<String, dynamic> map) {
    // 解析 source_run_ids
    List<String> parseSourceRunIds() {
      final raw = map['source_run_ids'];
      if (raw is List) {
        return raw.map((e) => e.toString()).toList();
      }
      return const [];
    }

    return UtgFunctionMutationResult(
      success: map['success'] == true,
      functionId: (map['function_id'] ?? '').toString(),
      createdFunctionId:
          (map['created_function_id'] ?? map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      deleted: map['deleted'] == true,
      imported: map['imported'] == true,
      alreadyExists: map['already_exists'] == true,
      count: map['count'] is num
          ? (map['count'] as num).toInt()
          : int.tryParse((map['count'] ?? '0').toString()) ?? 0,
      cloudBaseUrl: map['cloud_base_url']?.toString(),
      assetKind: (map['function_kind'] ?? map['asset_kind'] ?? '').toString(),
      assetState: (map['asset_state'] ?? '').toString(),
      derivedFromRawFunctionId: (map['derived_from_raw_function_id'] ?? '')
          .toString(),
      rawJson: Map<String, dynamic>.from(map),
      // 语义去重字段
      isUpdate: map['is_update'] == true,
      similarity: map['similarity'] is num
          ? (map['similarity'] as num).toDouble()
          : null,
      enrichedGoal: map['enriched_goal']?.toString(),
      originalGoal: map['original_goal']?.toString(),
      sourceRunIds: parseSourceRunIds(),
    );
  }
}

class UtgProviderControlResult {
  final bool success;
  final String action;
  final String message;
  final UtgBridgeConfig config;
  final Map<String, dynamic> rawJson;

  const UtgProviderControlResult({
    required this.success,
    required this.action,
    required this.message,
    required this.config,
    required this.rawJson,
  });

  factory UtgProviderControlResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgProviderControlResult(
      success: raw['success'] == true,
      action: (raw['action'] ?? '').toString(),
      message: (raw['message'] ?? '').toString(),
      config: UtgBridgeConfig.fromMap(raw),
      rawJson: Map<String, dynamic>.from(
        raw.map((key, value) => MapEntry(key.toString(), value)),
      ),
    );
  }
}

class UtgRunLogSummary {
  final String runId;
  final String goal;
  final bool success;
  final String doneReason;
  final int stepCount;
  final String startedAt;
  final String finishedAt;
  final num? durationMs;
  final String toolName;
  final String compileStatus;
  final String compileFunctionId;
  final String compileMode;
  final String actFunctionId;
  final String source;
  final String compileSummary;
  final String operationDescription;
  final String selectorLabel;
  final String selectorReason;
  final String errorMessage;
  final String finalPackageName;
  final Map<String, dynamic> rawJson;

  const UtgRunLogSummary({
    required this.runId,
    required this.goal,
    required this.success,
    required this.doneReason,
    required this.stepCount,
    required this.startedAt,
    required this.finishedAt,
    required this.durationMs,
    required this.toolName,
    required this.compileStatus,
    required this.compileFunctionId,
    required this.compileMode,
    required this.actFunctionId,
    required this.source,
    required this.compileSummary,
    required this.operationDescription,
    required this.selectorLabel,
    required this.selectorReason,
    required this.errorMessage,
    required this.finalPackageName,
    required this.rawJson,
  });

  factory UtgRunLogSummary.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgRunLogSummary(
      runId: (raw['run_id'] ?? '').toString(),
      goal: (raw['goal'] ?? '').toString(),
      success: raw['success'] == true,
      doneReason: (raw['done_reason'] ?? '').toString(),
      stepCount: raw['step_count'] is num
          ? (raw['step_count'] as num).toInt()
          : int.tryParse((raw['step_count'] ?? '0').toString()) ?? 0,
      startedAt: (raw['started_at'] ?? '').toString(),
      finishedAt: (raw['finished_at'] ?? '').toString(),
      durationMs: raw['duration_ms'] as num?,
      toolName: (raw['tool_name'] ?? '').toString(),
      compileStatus: (raw['compile_status'] ?? '').toString(),
      compileFunctionId: (raw['compile_function_id'] ?? '').toString(),
      compileMode: (raw['compile_mode'] ?? '').toString(),
      actFunctionId: (raw['act_function_id'] ?? '').toString(),
      source: (raw['source'] ?? '').toString(),
      compileSummary: (raw['compile_summary'] ?? '').toString(),
      operationDescription: (raw['operation_description'] ?? '').toString(),
      selectorLabel: (raw['selector_label'] ?? '').toString(),
      selectorReason: (raw['selector_reason'] ?? '').toString(),
      errorMessage: (raw['error_message'] ?? '').toString(),
      finalPackageName: (raw['final_package_name'] ?? '').toString(),
      rawJson: Map<String, dynamic>.from(
        (raw['raw_run'] as Map<dynamic, dynamic>? ?? const {}).map(
          (k, v) => MapEntry(k.toString(), v),
        ),
      ),
    );
  }
}

class UtgRunLogsSnapshot {
  final bool success;
  final int count;
  final List<UtgRunLogSummary> runs;
  final String runLogPath;
  final String provider;

  const UtgRunLogsSnapshot({
    required this.success,
    required this.count,
    required this.runs,
    required this.runLogPath,
    required this.provider,
  });

  factory UtgRunLogsSnapshot.fromMap(Map<String, dynamic> map) {
    return UtgRunLogsSnapshot(
      success: map['success'] == true,
      count: map['count'] is num
          ? (map['count'] as num).toInt()
          : int.tryParse((map['count'] ?? '0').toString()) ?? 0,
      runs:
          (map['runs'] as List<dynamic>?)
              ?.map((e) => UtgRunLogSummary.fromMap(e as Map?))
              .toList() ??
          const <UtgRunLogSummary>[],
      runLogPath: (map['run_log_path'] ?? '').toString(),
      provider: (map['provider'] ?? '').toString(),
    );
  }
}

class UtgRunLogDetail {
  final bool success;
  final String runId;
  final String runLogPath;
  final String provider;
  final String errorCode;
  final String errorMessage;
  final Map<String, dynamic> runLog;
  final Map<String, dynamic> rawJson;

  const UtgRunLogDetail({
    required this.success,
    required this.runId,
    required this.runLogPath,
    required this.provider,
    required this.errorCode,
    required this.errorMessage,
    required this.runLog,
    required this.rawJson,
  });

  factory UtgRunLogDetail.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgRunLogDetail(
      success: raw['success'] == true,
      runId: (raw['run_id'] ?? '').toString(),
      runLogPath: (raw['run_log_path'] ?? '').toString(),
      provider: (raw['provider'] ?? '').toString(),
      errorCode: (raw['error_code'] ?? '').toString(),
      errorMessage: (raw['error_message'] ?? '').toString(),
      runLog: Map<String, dynamic>.from(
        (raw['run_log'] as Map<dynamic, dynamic>? ?? const {}).map(
          (k, v) => MapEntry(k.toString(), v),
        ),
      ),
      rawJson: Map<String, dynamic>.from(
        raw.map((key, value) => MapEntry(key.toString(), value)),
      ),
    );
  }
}

class UtgRunLogImportResult {
  final bool success;
  final String runId;
  final String createdFunctionId;
  final String? errorCode;
  final String? errorMessage;
  final int pathsCreated;
  final int nodesCreated;
  final int nodesUpdated;
  final int functionsCreated;
  final List<String> warnings;
  final String runLogPath;
  final String assetKind;
  final String assetState;
  final Map<String, dynamic> rawJson;
  // 新增字段
  final List<String> hitFunctionIds;
  final int missActionCount;

  const UtgRunLogImportResult({
    required this.success,
    required this.runId,
    required this.createdFunctionId,
    required this.errorCode,
    required this.errorMessage,
    required this.pathsCreated,
    required this.nodesCreated,
    required this.nodesUpdated,
    required this.functionsCreated,
    required this.warnings,
    required this.runLogPath,
    required this.assetKind,
    required this.assetState,
    required this.rawJson,
    this.hitFunctionIds = const [],
    this.missActionCount = 0,
  });

  factory UtgRunLogImportResult.fromMap(Map<String, dynamic> map) {
    return UtgRunLogImportResult(
      success: map['success'] == true,
      runId: (map['run_id'] ?? '').toString(),
      createdFunctionId:
          (map['created_function_id'] ?? map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      pathsCreated: map['paths_created'] is num
          ? (map['paths_created'] as num).toInt()
          : int.tryParse((map['paths_created'] ?? '0').toString()) ?? 0,
      nodesCreated: map['nodes_created'] is num
          ? (map['nodes_created'] as num).toInt()
          : int.tryParse((map['nodes_created'] ?? '0').toString()) ?? 0,
      nodesUpdated: map['nodes_updated'] is num
          ? (map['nodes_updated'] as num).toInt()
          : int.tryParse((map['nodes_updated'] ?? '0').toString()) ?? 0,
      functionsCreated: map['functions_created'] is num
          ? (map['functions_created'] as num).toInt()
          : int.tryParse((map['functions_created'] ?? '0').toString()) ?? 0,
      warnings:
          (map['warnings'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          const <String>[],
      runLogPath: (map['run_log_path'] ?? '').toString(),
      assetKind: (map['function_kind'] ?? map['asset_kind'] ?? '').toString(),
      assetState: (map['asset_state'] ?? '').toString(),
      rawJson: Map<String, dynamic>.from(map),
      hitFunctionIds:
          (map['hit_function_ids'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          const <String>[],
      missActionCount: map['miss_action_count'] is num
          ? (map['miss_action_count'] as num).toInt()
          : int.tryParse((map['miss_action_count'] ?? '0').toString()) ?? 0,
    );
  }
}

/// Function 升级结果
class UtgFunctionEnrichResult {
  final bool success;
  final String functionId;
  final String? description;
  final List<Map<String, dynamic>> slots;
  final List<String> preconditions;
  final List<String> postconditions;
  final String? errorCode;
  final String? errorMessage;

  const UtgFunctionEnrichResult({
    required this.success,
    required this.functionId,
    this.description,
    this.slots = const [],
    this.preconditions = const [],
    this.postconditions = const [],
    this.errorCode,
    this.errorMessage,
  });

  factory UtgFunctionEnrichResult.fromMap(Map<String, dynamic> map) {
    return UtgFunctionEnrichResult(
      success: map['success'] == true,
      functionId: (map['function_id'] ?? '').toString(),
      description: map['description']?.toString(),
      slots: (map['slots'] as List<dynamic>?)
              ?.map((s) => s is Map ? Map<String, dynamic>.from(s) : <String, dynamic>{})
              .toList() ??
          const [],
      preconditions: (map['preconditions'] as List<dynamic>?)
              ?.map((p) => p.toString())
              .toList() ??
          const [],
      postconditions: (map['postconditions'] as List<dynamic>?)
              ?.map((p) => p.toString())
              .toList() ??
          const [],
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
    );
  }
}

/// Function 拆分结果
class UtgFunctionSplitResult {
  final bool success;
  final String functionId;
  final List<Map<String, dynamic>> newFunctions;
  final String? errorCode;
  final String? errorMessage;

  const UtgFunctionSplitResult({
    required this.success,
    required this.functionId,
    this.newFunctions = const [],
    this.errorCode,
    this.errorMessage,
  });

  factory UtgFunctionSplitResult.fromMap(Map<String, dynamic> map) {
    return UtgFunctionSplitResult(
      success: map['success'] == true,
      functionId: (map['function_id'] ?? '').toString(),
      newFunctions: (map['new_functions'] as List<dynamic>?)
              ?.map((f) => f is Map ? Map<String, dynamic>.from(f) : <String, dynamic>{})
              .toList() ??
          const [],
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
    );
  }
}

/// VLM 预处理钩子结果
/// execution_route: "utg" (使用缓存), "vlm" (使用 planner), "blocked" (中止)
class UtgVlmPreHookResult {
  final String kind; // hit, miss, disabled_or_fallback, hard_fail
  final String summary;
  final String? functionId;
  final List<Map<String, dynamic>> tools;
  final String plannerGuidance;
  final bool fallbackAllowed;
  final String executionRoute; // utg, vlm, blocked
  final Map<String, dynamic> rawJson;

  const UtgVlmPreHookResult({
    required this.kind,
    required this.summary,
    required this.functionId,
    required this.tools,
    required this.plannerGuidance,
    required this.fallbackAllowed,
    required this.executionRoute,
    required this.rawJson,
  });

  factory UtgVlmPreHookResult.fromMap(Map<String, dynamic> map) {
    final toolsList = (map['tools'] as List<dynamic>? ?? [])
        .map((t) => t is Map ? Map<String, dynamic>.from(t) : <String, dynamic>{})
        .toList();
    return UtgVlmPreHookResult(
      kind: (map['kind'] ?? '').toString(),
      summary: (map['summary'] ?? '').toString(),
      functionId: map['function_id']?.toString(),
      tools: toolsList,
      plannerGuidance: (map['planner_guidance'] ?? '').toString(),
      fallbackAllowed: map['fallback_allowed'] == true,
      executionRoute: (map['execution_route'] ?? 'vlm').toString(),
      rawJson: Map<String, dynamic>.from(map),
    );
  }

  bool get isHit => executionRoute == 'utg';
  bool get shouldFallbackToVlm => executionRoute == 'vlm';
  bool get isBlocked => executionRoute == 'blocked';
}

class AgentToolEventData {
  final String taskId;
  final String cardId;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String toolType;
  final String? serverName;
  final String status;
  final String argsJson;
  final String progress;
  final String summary;
  final String compileStatus;
  final String executionRoute;
  final String resultPreviewJson;
  final String rawResultJson;
  final String terminalOutput;
  final String terminalOutputDelta;
  final String? terminalSessionId;
  final String terminalStreamState;
  final String? workspaceId;
  final String? interruptedBy;
  final String? interruptionReason;
  final List<Map<String, dynamic>> artifacts;
  final List<Map<String, dynamic>> actions;
  final bool success;

  const AgentToolEventData({
    required this.taskId,
    this.cardId = '',
    required this.toolName,
    required this.displayName,
    this.toolTitle = '',
    required this.toolType,
    this.serverName,
    this.status = '',
    this.argsJson = '',
    this.progress = '',
    this.summary = '',
    this.compileStatus = '',
    this.executionRoute = '',
    this.resultPreviewJson = '',
    this.rawResultJson = '',
    this.terminalOutput = '',
    this.terminalOutputDelta = '',
    this.terminalSessionId,
    this.terminalStreamState = '',
    this.workspaceId,
    this.interruptedBy,
    this.interruptionReason,
    this.artifacts = const [],
    this.actions = const [],
    this.success = true,
  });

  factory AgentToolEventData.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return AgentToolEventData(
      taskId: (raw['taskId'] ?? '').toString(),
      cardId: (raw['cardId'] ?? '').toString(),
      toolName: (raw['toolName'] ?? '').toString(),
      displayName: (raw['displayName'] ?? raw['toolName'] ?? '').toString(),
      toolTitle: (raw['toolTitle'] ?? '').toString(),
      toolType: (raw['toolType'] ?? 'builtin').toString(),
      serverName: raw['serverName']?.toString(),
      status: (raw['status'] ?? '').toString(),
      argsJson: (raw['argsJson'] ?? raw['args'] ?? '').toString(),
      progress: (raw['progress'] ?? '').toString(),
      summary: (raw['summary'] ?? '').toString(),
      compileStatus: (raw['compileStatus'] ?? '').toString(),
      executionRoute: (raw['executionRoute'] ?? '').toString(),
      resultPreviewJson: (raw['resultPreviewJson'] ?? '').toString(),
      rawResultJson: (raw['rawResultJson'] ?? '').toString(),
      terminalOutput: (raw['terminalOutput'] ?? '').toString(),
      terminalOutputDelta: (raw['terminalOutputDelta'] ?? '').toString(),
      terminalSessionId: raw['terminalSessionId']?.toString(),
      terminalStreamState: (raw['terminalStreamState'] ?? '').toString(),
      workspaceId: raw['workspaceId']?.toString(),
      interruptedBy: raw['interruptedBy']?.toString(),
      interruptionReason: raw['interruptionReason']?.toString(),
      artifacts: ((raw['artifacts'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      actions: ((raw['actions'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      success: raw['success'] != false,
    );
  }
}

class AgentAiConfigChangedEvent {
  final String source;
  final String path;

  const AgentAiConfigChangedEvent({required this.source, required this.path});

  factory AgentAiConfigChangedEvent.fromMap(Map<dynamic, dynamic>? map) {
    return AgentAiConfigChangedEvent(
      source: (map?['source'] ?? '').toString(),
      path: (map?['path'] ?? '').toString(),
    );
  }
}

class AssistsMessageService {
  static const MethodChannel assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  // 回调函数
  static CardPushCallback? _onCardPushCallback;
  static TaskFinishCallback? _onTaskFinishCallback;
  static ChatTaskMessageCallBack? _onChatTaskMessageCallBack;
  static ChatTaskMessageEndCallBack? _onChatTaskMessageEndCallBack;
  static VLMRequestUserInputCallBack? _onVLMRequestUserInputCallBack;
  static DispatchStreamDataCallBack? _onDispatchStreamDataCallBack;
  static DispatchStreamEndCallBack? _onDispatchStreamEndCallBack;
  static DispatchStreamErrorCallBack? _onDispatchStreamErrorCallBack;

  // Agent回调
  static AgentThinkingStartCallback? _onAgentThinkingStartCallback;
  static AgentThinkingUpdateCallback? _onAgentThinkingUpdateCallback;
  static AgentToolCallStartCallback? _onAgentToolCallStartCallback;
  static AgentToolCallProgressCallback? _onAgentToolCallProgressCallback;
  static AgentToolCallCompleteCallback? _onAgentToolCallCompleteCallback;
  static AgentChatMessageCallback? _onAgentChatMessageCallback;
  static AgentPromptTokenUsageCallback? _onAgentPromptTokenUsageCallback;
  static AgentContextCompactionStateCallback?
  _onAgentContextCompactionStateCallback;
  static AgentClarifyCallback? _onAgentClarifyCallback;
  static AgentCompleteCallback? _onAgentCompleteCallback;
  static AgentErrorCallback? _onAgentErrorCallback;
  static AgentPermissionRequiredCallback? _onAgentPermissionRequiredCallback;
  static AgentUtgConfirmCallback? _onAgentUtgConfirmCallback;

  static ScheduledTaskCancelledCallBack? _onScheduledTaskCancelledCallBack;
  static ScheduledTaskExecuteNowCallBack? _onScheduledTaskExecuteNowCallBack;
  static final StreamController<AgentAiConfigChangedEvent>
  _agentAiConfigChangedController =
      StreamController<AgentAiConfigChangedEvent>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationListChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationMessagesChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _browserSessionSnapshotChangedController =
      StreamController<Map<String, dynamic>>.broadcast();

  // 改为回调列表，支持多个监听器
  static final List<ChatTaskMessageCallBack> _onChatTaskMessageCallBacks = [];
  static final List<ChatTaskMessageEndCallBack> _onChatTaskMessageEndCallBacks =
      [];
  static final List<VLMTaskFinishEndCallBack> _onVLMTaskFinishCallBacks = [];
  static final List<CommonTaskFinishEndCallBack> _onCommonTaskFinishCallBacks =
      [];

  static Stream<AgentAiConfigChangedEvent> get agentAiConfigChangedStream =>
      _agentAiConfigChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationListChangedStream =>
      _conversationListChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationMessagesChangedStream =>
      _conversationMessagesChangedController.stream;
  static Stream<Map<String, dynamic>> get browserSessionSnapshotChangedStream =>
      _browserSessionSnapshotChangedController.stream;

  static void initialize() {
    assistCore.setMethodCallHandler(_handleMethod);
  }

  static void dispatchAgentAiConfigChanged(AgentAiConfigChangedEvent event) {
    _agentAiConfigChangedController.add(event);
  }

  static Future<dynamic> _handleMethod(MethodCall call) async {
    try {
      switch (call.method) {
        case 'onCardPush':
          final Map<String, dynamic> cardData = Map<String, dynamic>.from(
            call.arguments,
          );
          _onCardPushCallback?.call(cardData['data']);
          break;

        case 'onTaskFinish':
          print('任务完成');
          _onTaskFinishCallback?.call();
          break;
        case 'onAgentAiConfigChanged':
          final data = Map<String, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          // Defer broadcast to the next event-loop turn so listeners can
          // safely invoke the same platform channel without re-entrancy.
          unawaited(
            Future<void>(() {
              dispatchAgentAiConfigChanged(
                AgentAiConfigChangedEvent.fromMap(data),
              );
            }),
          );
          break;
        case 'onConversationListChanged':
          _conversationListChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onConversationMessagesChanged':
          _conversationMessagesChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onBrowserSessionSnapshotUpdated':
          _browserSessionSnapshotChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onChatMessage':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          print(
            'onChatMessage content: ${data['content']}, type: ${data['type']}',
          );
          _onChatTaskMessageCallBack?.call(
            data['taskID'],
            data['content'],
            data['type'],
          );
          for (final callback in _onChatTaskMessageCallBacks) {
            callback(data['taskID'], data['content'], data['type']);
          }
          break;
        case 'onChatMessageEnd':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onChatTaskMessageEndCallBack?.call(data['taskID']);
          for (final callback in _onChatTaskMessageEndCallBacks) {
            callback(data['taskID']);
          }
          break;
        case 'onVLMRequestUserInput':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          print('onVLMRequestUserInput question: ${data['question']}');
          _onVLMRequestUserInputCallBack?.call(
            data['question'],
            data['taskId']?.toString(),
          );
          break;
        case 'onVLMTaskFinish':
          print('任务完成');
          // 通知所有注册的回调
          for (final callback in _onVLMTaskFinishCallBacks) {
            callback((call.arguments as Map?)?['taskId']?.toString());
          }
          break;
        case 'onCommonTaskFinish':
          print('任务完成');
          // 通知所有注册的回调
          for (final callback in _onCommonTaskFinishCallBacks) {
            callback();
          }
          break;
        case 'onDispatchStreamData':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamDataCallBack?.call(
            data['taskID'] ?? '',
            data['data'] ?? '',
            data['fullContent'] ?? '',
          );
          break;
        case 'onDispatchStreamEnd':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamEndCallBack?.call(
            data['taskID'] ?? '',
            data['fullContent'] ?? '',
          );
          break;
        case 'onDispatchStreamError':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamErrorCallBack?.call(
            data['taskID'] ?? '',
            data['error'] ?? '',
            data['fullContent'] ?? '',
            data['isRateLimited'] == true,
          );
          break;
        case 'onAgentThinkingStart':
          _onAgentThinkingStartCallback?.call(
            ((call.arguments as Map?)?['taskId'] ?? '').toString(),
          );
          break;
        case 'onAgentThinkingUpdate':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onAgentThinkingUpdateCallback?.call(
            (data['taskId'] ?? '').toString(),
            data['thinking'] ?? '',
          );
          break;
        case 'onAgentToolCallStart':
          _onAgentToolCallStartCallback?.call(
            AgentToolEventData.fromMap(call.arguments as Map?),
          );
          break;
        case 'onAgentToolCallProgress':
          _onAgentToolCallProgressCallback?.call(
            AgentToolEventData.fromMap(call.arguments as Map?),
          );
          break;
        case 'onAgentToolCallComplete':
          _onAgentToolCallCompleteCallback?.call(
            AgentToolEventData.fromMap(call.arguments as Map?),
          );
          break;
        case 'onAgentChatMessage':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          final dynamic isFinalRaw = data['isFinal'];
          final bool isFinal = isFinalRaw == null
              ? true
              : (isFinalRaw is bool
                    ? isFinalRaw
                    : isFinalRaw.toString().toLowerCase() == 'true');
          final double? prefillTokensPerSecond = _asNullableDouble(
            data['prefillTokensPerSecond'],
          );
          final double? decodeTokensPerSecond = _asNullableDouble(
            data['decodeTokensPerSecond'],
          );
          _onAgentChatMessageCallback?.call(
            (data['taskId'] ?? '').toString(),
            (data['message'] ?? '').toString(),
            isFinal: isFinal,
            prefillTokensPerSecond: prefillTokensPerSecond,
            decodeTokensPerSecond: decodeTokensPerSecond,
          );
          break;
        case 'onAgentPromptTokenUsageChanged':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          final latestPromptTokens = _asNullableInt(data['latestPromptTokens']);
          if (latestPromptTokens == null) {
            break;
          }
          _onAgentPromptTokenUsageCallback?.call(
            (data['taskId'] ?? '').toString(),
            latestPromptTokens,
            _asNullableInt(data['promptTokenThreshold']),
          );
          break;
        case 'onAgentContextCompactionStateChanged':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onAgentContextCompactionStateCallback?.call(
            (data['taskId'] ?? '').toString(),
            data['isCompacting'] == true,
            _asNullableInt(data['latestPromptTokens']),
            _asNullableInt(data['promptTokenThreshold']),
          );
          break;
        case 'onAgentClarifyRequired':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          final List<String> missingFields =
              (data['missingFields'] as List<dynamic>?)
                  ?.map((e) => e.toString())
                  .toList() ??
              [];
          _onAgentClarifyCallback?.call(
            (data['taskId'] ?? '').toString(),
            data['question'] ?? '',
            missingFields,
          );
          break;
        case 'onAgentComplete':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onAgentCompleteCallback?.call(
            (data['taskId'] ?? '').toString(),
            data['success'] == true,
            (data['outputKind'] ?? 'none').toString(),
            data['hasUserVisibleOutput'] == true,
            _asNullableInt(data['latestPromptTokens']),
            _asNullableInt(data['promptTokenThreshold']),
          );
          break;
        case 'onAgentError':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onAgentErrorCallback?.call(
            (data['taskId'] ?? '').toString(),
            data['error'] ?? '',
          );
          break;
        case 'onAgentPermissionRequired':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          final List<String> missing =
              (data['missing'] as List<dynamic>?)
                  ?.map((e) => e.toString())
                  .toList() ??
              [];
          _onAgentPermissionRequiredCallback?.call(
            (data['taskId'] ?? '').toString(),
            missing,
          );
          break;
        case 'onScheduledTaskCancelled':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskCancelledCallBack?.call(data['taskId'] ?? '');
          break;
        case 'onScheduledTaskExecuteNow':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskExecuteNowCallBack?.call(data['taskId'] ?? '');
          break;
        case 'agentScheduleCreate':
          return await AgentScheduleBridgeService.createTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        case 'agentScheduleList':
          return await AgentScheduleBridgeService.listTasks();
        case 'agentScheduleUpdate':
          return await AgentScheduleBridgeService.updateTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        case 'agentScheduleDelete':
          return await AgentScheduleBridgeService.deleteTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        case 'agentUtgConfirm':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          final prompt = (data['prompt'] ?? '').toString();
          final callback = _onAgentUtgConfirmCallback;
          if (callback == null) {
            return false;
          }
          return await callback(prompt);

        default:
          print('未处理的方法: ${call.method}');
      }
    } catch (e) {
      print('处理方法调用时出错: $e');
      rethrow;
    }
  }

  // 设置回调函数
  static void setOnCardPushCallback(CardPushCallback callback) {
    _onCardPushCallback = callback;
  }

  static void setOnTaskFinishCallback(TaskFinishCallback callback) {
    _onTaskFinishCallback = callback;
  }

  static void setOnChatTaskMessageCallBack(ChatTaskMessageCallBack callback) {
    _onChatTaskMessageCallBack = callback;
  }

  static void addOnChatTaskMessageCallBack(ChatTaskMessageCallBack? callback) {
    if (callback != null && !_onChatTaskMessageCallBacks.contains(callback)) {
      _onChatTaskMessageCallBacks.add(callback);
    }
  }

  static void removeOnChatTaskMessageCallBack(
    ChatTaskMessageCallBack? callback,
  ) {
    _onChatTaskMessageCallBacks.remove(callback);
  }

  static void setOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack callback,
  ) {
    _onChatTaskMessageEndCallBack = callback;
  }

  static void addOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack? callback,
  ) {
    if (callback != null &&
        !_onChatTaskMessageEndCallBacks.contains(callback)) {
      _onChatTaskMessageEndCallBacks.add(callback);
    }
  }

  static void removeOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack? callback,
  ) {
    _onChatTaskMessageEndCallBacks.remove(callback);
  }

  static void setOnVLMRequestUserInputCallBack(
    VLMRequestUserInputCallBack callback,
  ) {
    _onVLMRequestUserInputCallBack = callback;
  }

  static void setOnVLMTaskFinishCallBack(VLMTaskFinishEndCallBack? callback) {
    if (callback != null && !_onVLMTaskFinishCallBacks.contains(callback)) {
      _onVLMTaskFinishCallBacks.add(callback);
    }
  }

  static void setOnCommonTaskFinishCallBack(
    CommonTaskFinishEndCallBack? callback,
  ) {
    if (callback != null && !_onCommonTaskFinishCallBacks.contains(callback)) {
      _onCommonTaskFinishCallBacks.add(callback);
    }
  }

  static void removeOnVLMTaskFinishCallBack(
    VLMTaskFinishEndCallBack? callback,
  ) {
    _onVLMTaskFinishCallBacks.remove(callback);
  }

  static void removeOnCommonTaskFinishCallBack(
    CommonTaskFinishEndCallBack? callback,
  ) {
    _onCommonTaskFinishCallBacks.remove(callback);
  }

  static void setOnDispatchStreamDataCallBack(
    DispatchStreamDataCallBack? callback,
  ) {
    _onDispatchStreamDataCallBack = callback;
  }

  static void setOnDispatchStreamEndCallBack(
    DispatchStreamEndCallBack? callback,
  ) {
    _onDispatchStreamEndCallBack = callback;
  }

  static void setOnDispatchStreamErrorCallBack(
    DispatchStreamErrorCallBack? callback,
  ) {
    _onDispatchStreamErrorCallBack = callback;
  }

  static void setOnScheduledTaskCancelledCallBack(
    ScheduledTaskCancelledCallBack? callback,
  ) {
    _onScheduledTaskCancelledCallBack = callback;
  }

  static void setOnScheduledTaskExecuteNowCallBack(
    ScheduledTaskExecuteNowCallBack? callback,
  ) {
    _onScheduledTaskExecuteNowCallBack = callback;
  }

  static void setOnAgentThinkingStartCallback(
    AgentThinkingStartCallback? callback,
  ) {
    _onAgentThinkingStartCallback = callback;
  }

  static void setOnAgentThinkingUpdateCallback(
    AgentThinkingUpdateCallback? callback,
  ) {
    _onAgentThinkingUpdateCallback = callback;
  }

  static void setOnAgentToolCallStartCallback(
    AgentToolCallStartCallback? callback,
  ) {
    _onAgentToolCallStartCallback = callback;
  }

  static void setOnAgentToolCallProgressCallback(
    AgentToolCallProgressCallback? callback,
  ) {
    _onAgentToolCallProgressCallback = callback;
  }

  static void setOnAgentToolCallCompleteCallback(
    AgentToolCallCompleteCallback? callback,
  ) {
    _onAgentToolCallCompleteCallback = callback;
  }

  static void setOnAgentChatMessageCallback(
    AgentChatMessageCallback? callback,
  ) {
    _onAgentChatMessageCallback = callback;
  }

  static void setOnAgentPromptTokenUsageCallback(
    AgentPromptTokenUsageCallback? callback,
  ) {
    _onAgentPromptTokenUsageCallback = callback;
  }

  static void setOnAgentContextCompactionStateCallback(
    AgentContextCompactionStateCallback? callback,
  ) {
    _onAgentContextCompactionStateCallback = callback;
  }

  static void setOnAgentClarifyCallback(AgentClarifyCallback? callback) {
    _onAgentClarifyCallback = callback;
  }

  static void setOnAgentCompleteCallback(AgentCompleteCallback? callback) {
    _onAgentCompleteCallback = callback;
  }

  static int? _asNullableInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw);
    return null;
  }

  static double? _asNullableDouble(dynamic raw) {
    if (raw is double) return raw;
    if (raw is num) return raw.toDouble();
    if (raw is String) return double.tryParse(raw.trim());
    return null;
  }

  static void setOnAgentErrorCallback(AgentErrorCallback? callback) {
    _onAgentErrorCallback = callback;
  }

  static void setOnAgentPermissionRequiredCallback(
    AgentPermissionRequiredCallback? callback,
  ) {
    _onAgentPermissionRequiredCallback = callback;
  }

  static void setOnAgentUtgConfirmCallback(AgentUtgConfirmCallback? callback) {
    _onAgentUtgConfirmCallback = callback;
  }

  // 发送按钮点击事件到Android端
  static Future<bool> clickButton(
    String taskID,
    String btnId,
    String value, //需要保留.因为有多选数据比如选择app列表,具体协议再定义
    bool isNeedPermission, //是否需要检查权限
  ) async {
    try {
      var result = await assistCore.invokeMethod('clickButton', {
        'taskID': taskID,
        'id': btnId,
        'value': value,
        'isNeedPermission': isNeedPermission,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('发送按钮点击事件失败: ${e.message}');
      return false;
    }
  }

  // 创建陪伴任务
  static Future<bool> createCompanionTask() async {
    var result = await assistCore.invokeMethod('createCompanionTask');
    return result == "SUCCESS";
  }

  //取消陪伴任务
  static Future<bool> cancelTask() async {
    var result = await assistCore.invokeMethod('cancelTask');
    return result == "SUCCESS";
  }

  /// 取消正在运行的任务，不影响陪伴模式
  static Future<bool> cancelRunningTask({String? taskId}) async {
    try {
      var result = await assistCore.invokeMethod(
        'cancelRunningTask',
        taskId == null ? null : {'taskId': taskId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('取消运行中任务失败: ${e.message}');
      return false;
    }
  }

  /// 停止当前 Agent 正在执行的工具调用，但不终止整轮 Agent 响应
  static Future<bool> stopAgentToolCall({
    required String taskId,
    required String cardId,
  }) async {
    try {
      final result = await assistCore.invokeMethod(
        'stopAgentToolCall',
        <String, String>{'taskId': taskId, 'cardId': cardId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('停止工具调用失败: ${e.message}');
      return false;
    }
  }

  /// 取消陪伴任务的回到桌面操作
  /// 当用户在开启陪伴后离开主页时调用
  static Future<bool> cancelCompanionGoHome() async {
    try {
      var result = await assistCore.invokeMethod('cancelCompanionGoHome');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('取消回到桌面失败: ${e.message}');
      return false;
    }
  }

  /// Trigger the system Home action.
  static Future<bool> pressHome() async {
    try {
      var result = await assistCore.invokeMethod('pressHome');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('pressHome failed: ${e.message}');
      return false;
    }
  }

  // cancel chat task
  static Future<bool> cancelChatTask({String? taskId}) async {
    var result = await assistCore.invokeMethod(
      'cancelChatTask',
      taskId == null ? null : {'taskId': taskId},
    );
    return result == "SUCCESS";
  }

  static Future<UtgBridgeConfig> getUtgBridgeConfig() async {
    final result = await assistCore.invokeMethod('getUtgBridgeConfig');
    return UtgBridgeConfig.fromMap(result as Map?);
  }

  static Future<UtgBridgeConfig> saveUtgBridgeConfig({
    bool? utgEnabled,
    bool? providerAutoStartEnabled,
    String? omniflowBaseUrl,
    String? providerStartCommand,
    String? providerWorkingDirectory,
  }) async {
    final result = await assistCore.invokeMethod('saveUtgBridgeConfig', {
      if (utgEnabled != null) 'utgEnabled': utgEnabled,
      if (providerAutoStartEnabled != null)
        'providerAutoStartEnabled': providerAutoStartEnabled,
      if (omniflowBaseUrl != null) 'omniflowBaseUrl': omniflowBaseUrl,
      if (providerStartCommand != null)
        'providerStartCommand': providerStartCommand,
      if (providerWorkingDirectory != null)
        'providerWorkingDirectory': providerWorkingDirectory,
    });
    return UtgBridgeConfig.fromMap(result as Map?);
  }

  static Future<UtgProviderControlResult> controlUtgProvider({
    required String action,
  }) async {
    final result = await assistCore.invokeMethod('controlUtgProvider', {
      'action': action.trim(),
    });
    return UtgProviderControlResult.fromMap(result as Map?);
  }

  static Future<UtgBridgeExecutionContext>
  getUtgBridgeExecutionContext() async {
    final result = await assistCore.invokeMethod(
      'getUtgBridgeExecutionContext',
    );
    return UtgBridgeExecutionContext.fromMap(result as Map?);
  }

  static String _normalizeUtgPath(String path) {
    final trimmed = path.trim();
    if (trimmed.isEmpty) {
      return '/';
    }
    return trimmed.startsWith('/') ? trimmed : '/$trimmed';
  }

  static Future<Map<String, dynamic>> _requestUtgJson({
    required String method,
    required String path,
    Object? payload,
    String? baseUrl,
  }) async {
    final result = await assistCore.invokeMethod('requestUtgJson', {
      'method': method.trim().toUpperCase(),
      'path': _normalizeUtgPath(path),
      if (payload != null) 'payload': payload,
      if (baseUrl != null && baseUrl.trim().isNotEmpty)
        'baseUrl': baseUrl.trim(),
    });
    if (result == null) {
      throw Exception('OmniFlow provider 无响应');
    }
    if (result is! Map) {
      throw Exception('OmniFlow provider 响应格式错误');
    }
    return Map<String, dynamic>.from(result);
  }

  static Future<UtgRunLogsSnapshot> getUtgRunLogs({
    String? baseUrl,
    int limit = 20,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/run_logs?limit=$limit',
      baseUrl: baseUrl,
    );
    return UtgRunLogsSnapshot.fromMap(decoded);
  }

  static Future<UtgRunLogDetail> getUtgRunLogDetail({
    required String runId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/run_logs/${Uri.encodeComponent(runId.trim())}',
      baseUrl: baseUrl,
    );
    final detail = UtgRunLogDetail.fromMap(decoded);
    if (!detail.success) {
      throw Exception(
        detail.errorMessage.trim().isNotEmpty
            ? detail.errorMessage
            : 'Failed to load run_log details',
      );
    }
    return detail;
  }

  static Future<Map<String, dynamic>> getVlmTaskRunLog({
    required String taskId,
  }) async {
    final result = await assistCore.invokeMethod<Map<Object?, Object?>>(
      'getVlmTaskRunLog',
      {'taskId': taskId.trim()},
    );
    if (result == null) {
      return <String, dynamic>{
        'success': false,
        'task_id': taskId.trim(),
        'error_message': 'Run log not found',
      };
    }
    return result.map((key, value) => MapEntry(key.toString(), value));
  }

  static Future<UtgRunLogImportResult> importUtgRunLog({
    required String runId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/run_logs/import',
      baseUrl: baseUrl,
      payload: {'run_id': runId.trim()},
    );
    return UtgRunLogImportResult.fromMap(decoded);
  }

  /// 获取 run log 时间线数据（用于可视化）
  static Future<Map<String, dynamic>> getRunLogTimeline({
    required String runId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/run_logs/${Uri.encodeComponent(runId.trim())}/timeline_payload',
      baseUrl: baseUrl,
    );
    return Map<String, dynamic>.from(decoded);
  }

  static Future<UtgManualRunResult> replayUtgRunLog({
    required String runId,
    String? baseUrl,
  }) async {
    final executionContext = await getUtgBridgeExecutionContext();
    if (executionContext.bridgeBaseUrl.trim().isEmpty ||
        executionContext.bridgeToken.trim().isEmpty) {
      throw Exception('OmniFlow bridge 上下文不可用');
    }
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/run_logs/replay',
      baseUrl: baseUrl,
      payload: {
        'run_id': runId.trim(),
        'bridge_base_url': executionContext.bridgeBaseUrl,
        'bridge_token': executionContext.bridgeToken,
        'skip_terminal_verify': true,
        'context': {'source': 'utg_run_log_replay'},
      },
    );
    return UtgManualRunResult.fromMap(decoded);
  }

  static Future<UtgFunctionsSnapshot> getUtgFunctions({String? baseUrl}) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/functions',
      baseUrl: baseUrl,
    );
    return UtgFunctionsSnapshot.fromMap(decoded);
  }

  /// VLM 预处理钩子 - 检查目标是否可以使用 UTG 缓存
  /// 返回 execution_route: "utg" (命中缓存), "vlm" (需要 planner), "blocked" (中止)
  static Future<UtgVlmPreHookResult> vlmPreHook({
    required String goal,
    String? currentPackageName,
    bool fallbackAllowed = true,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/vlm/pre_hook',
      baseUrl: baseUrl,
      payload: {
        'goal': goal.trim(),
        if (currentPackageName != null && currentPackageName.trim().isNotEmpty)
          'current_package_name': currentPackageName.trim(),
        'fallback_allowed': fallbackAllowed,
      },
    );
    return UtgVlmPreHookResult.fromMap(decoded);
  }

  /// 清空所有数据：run_logs + functions + shared_pages
  static Future<Map<String, dynamic>> resetAllData({String? baseUrl}) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/provider/reset',
      baseUrl: baseUrl,
    );
    return Map<String, dynamic>.from(decoded);
  }

  /// 检查 Provider 是否有新版本
  static Future<UtgUpdateCheckResult> checkForUpdate({String? baseUrl}) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/update/check',
      baseUrl: baseUrl,
    );
    return UtgUpdateCheckResult.fromMap(decoded);
  }

  /// 一键更新 Provider：检查 + 下载 + 安装
  static Future<UtgUpdateApplyResult> applyUpdate({String? baseUrl}) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/update/apply',
      baseUrl: baseUrl,
    );
    return UtgUpdateApplyResult.fromMap(decoded);
  }

  static Future<Map<String, dynamic>> getUtgFunctionBundle({
    required String functionId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/functions/$functionId/bundle',
      baseUrl: baseUrl,
    );
    final normalized = jsonDecode(jsonEncode(decoded));
    if (normalized is Map<String, dynamic>) {
      return normalized;
    }
    if (normalized is Map) {
      return Map<String, dynamic>.from(normalized);
    }
    throw Exception('OmniFlow 资产详情响应格式错误');
  }

  static Future<UtgFunctionMutationResult> deleteUtgFunction({
    required String functionId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'DELETE',
      path: '/functions/$functionId',
      baseUrl: baseUrl,
    );
    return UtgFunctionMutationResult.fromMap(decoded);
  }

  /// 更新 function 信息
  static Future<UtgFunctionMutationResult> updateUtgFunction({
    required String functionId,
    String? description,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'PATCH',
      path: '/functions/$functionId',
      baseUrl: baseUrl,
      payload: {
        if (description != null) 'description': description,
      },
    );
    return UtgFunctionMutationResult.fromMap(decoded);
  }

  /// 升级 function（LLM 补齐语义信息）
  static Future<UtgFunctionEnrichResult> enrichUtgFunction({
    required String functionId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/functions/$functionId/enrich',
      baseUrl: baseUrl,
    );
    return UtgFunctionEnrichResult.fromMap(decoded);
  }

  /// 拆分 function（LLM 语义切分）
  static Future<UtgFunctionSplitResult> splitUtgFunction({
    required String functionId,
    String? hint,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/functions/$functionId/split',
      baseUrl: baseUrl,
      payload: {
        if (hint != null) 'hint': hint,
      },
    );
    return UtgFunctionSplitResult.fromMap(decoded);
  }

  /// 导入 run_log 为持久化 function
  static Future<UtgFunctionMutationResult> importRunLog({
    required String runId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/run_logs/import',
      baseUrl: baseUrl,
      payload: {'run_id': runId},
    );
    return UtgFunctionMutationResult.fromMap(decoded);
  }

  static Future<UtgFunctionMutationResult> downloadCloudUtgFunction({
    String functionId = '',
    required String cloudBaseUrl,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/functions/pull',
      baseUrl: baseUrl,
      payload: {
        'cloud_base_url': cloudBaseUrl.trim(),
        'function_id': functionId.trim(),
      },
    );
    return UtgFunctionMutationResult.fromMap(decoded);
  }

  static Future<UtgFunctionMutationResult> uploadCloudUtgFunction({
    required String functionId,
    required String cloudBaseUrl,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/functions/push',
      baseUrl: baseUrl,
      payload: {
        'cloud_base_url': cloudBaseUrl.trim(),
        'function_id': functionId.trim(),
      },
    );
    return UtgFunctionMutationResult.fromMap(decoded);
  }

  static Future<UtgManualRunResult> runUtgFunction({
    required String functionId,
    Map<String, String> arguments = const {},
    String? baseUrl,
  }) async {
    final executionContext = await getUtgBridgeExecutionContext();
    if (executionContext.bridgeBaseUrl.trim().isEmpty ||
        executionContext.bridgeToken.trim().isEmpty) {
      throw Exception('OmniFlow bridge 上下文不可用');
    }
    final decoded = await _requestUtgJson(
      method: 'POST',
      path: '/functions/execute',
      baseUrl: baseUrl,
      payload: {
        'goal': 'manual_utg_function_run:$functionId',
        'function_id': functionId,
        'arguments': arguments,
        'bridge_base_url': executionContext.bridgeBaseUrl,
        'bridge_token': executionContext.bridgeToken,
        'skip_terminal_verify': true,
        'context': {'source': 'utg_manual_dashboard'},
      },
    );
    return UtgManualRunResult.fromMap(decoded);
  }

  static Future<bool> copyToClipboard(String text) async {
    try {
      var result = await assistCore.invokeMethod('copyToClipboard', {
        'text': text,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('复制到剪贴板失败: ${e.message}');
      return false;
    }
  }

  static Future<String?> getClipboardText() async {
    try {
      final result = await assistCore.invokeMethod<String>('getClipboardText');
      return result;
    } on PlatformException catch (e) {
      print('读取剪贴板失败: ${e.message}');
      return null;
    }
  }

  //开始聊天任务
  static Future<bool> createChatTask(
    String taskID,
    List<Map<String, dynamic>> content, {
    String? provider,
    Map<String, dynamic>? openClawConfig,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
    int? conversationId,
    String? conversationMode,
    String? userMessage,
    List<Map<String, dynamic>> userAttachments = const [],
  }) async {
    try {
      print('createChatTask taskID: $taskID content: $content');
      final args = {'taskID': taskID, 'content': content};
      if (provider != null) {
        args['provider'] = provider;
      }
      if (openClawConfig != null) {
        args['openClawConfig'] = openClawConfig;
      }
      if (modelOverride != null) {
        args['modelOverride'] = modelOverride;
      }
      if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty) {
        args['reasoningEffort'] = reasoningEffort.trim();
      }
      if (conversationId != null) {
        args['conversationId'] = conversationId;
      }
      if (conversationMode != null && conversationMode.trim().isNotEmpty) {
        args['conversationMode'] = conversationMode.trim();
      }
      if (userMessage != null) {
        args['userMessage'] = userMessage;
      }
      if (userAttachments.isNotEmpty) {
        args['userAttachments'] = userAttachments;
      }
      final result = await assistCore.invokeMethod('createChatTask', args);
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('createChatTask failed: ${e.message}');
      return false;
    }
  }

  //开始视觉模型任务
  static Future<bool> createVLMOperationTask(
    String goal, {
    String? taskId,
    String model = "scene.vlm.operation.primary",
    int maxSteps = 25,
    String? packageName,
    bool needSummary = false,
    bool skipGoHome = false, // 是否跳过回到主页，从当前页面开始执行
  }) async {
    print(
      'createVLMOperationTask goal: $goal model: $model  maxSteps: $maxSteps packageName: $packageName needSummary: $needSummary skipGoHome: $skipGoHome',
    );
    var result = await assistCore.invokeMethod('createVLMOperationTask', {
      'goal': goal,
      if (taskId != null) 'taskId': taskId,
      'model': model,
      'maxSteps': maxSteps,
      'packageName': packageName,
      'needSummary': needSummary,
      'skipGoHome': skipGoHome,
    });

    return result == "SUCCESS";
  }

  /// 向运行中的VLM任务提供用户输入（INFO动作）
  static Future<bool> provideUserInputToVLMTask(String userInput) async {
    try {
      final result = await assistCore.invokeMethod<bool>(
        'provideUserInputToVLMTask',
        {'userInput': userInput},
      );
      return result == true;
    } on PlatformException catch (e) {
      print('提供用户输入失败: ${e.message}');
      return false;
    }
  }

  /// 通知原生层ChatBotSheet已准备好接收总结
  static Future<bool> notifySummarySheetReady() async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'notifySummarySheetReady',
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('通知总结Sheet准备就绪失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> isCompanionTaskRunning() async {
    return await assistCore.invokeMethod('isCompanionTaskRunning', {});
  }

  /// 获取已安装应用（包含中文应用名和包名）
  static Future<List<Map<String, dynamic>>> getInstalledApplications() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplications',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      print('获取已安装应用失败: ${e.message}');
      return [];
    }
  }

  /// 获取已安装应用（附带图标更新）
  static Future<List<Map<String, dynamic>>>
  getInstalledApplicationsWithIconUpdate() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplicationsWithIconUpdate',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      print('获取已安装应用(附带图标更新)失败: ${e.message}');
      return [];
    }
  }

  /// 开源版不提供 suggestions
  static Future<List<Map<String, dynamic>>> getSuggestions() async {
    return [];
  }

  static Future<bool> isPackageAuthorized(String packageName) async {
    try {
      final result = await assistCore.invokeMethod<bool>(
        'isPackageAuthorized',
        {'packageName': packageName},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      print('检查包名授权状态失败: ${e.message}');
      return false;
    }
  }

  // 开源版已移除学习模式

  /// 预约VLM操作任务
  static Future<String?> scheduleVLMOperationTask(
    String goal, //目标文本
    int times, { //预约时间
    String model = "scene.vlm.operation.primary", //模型(sceneId)
    int maxSteps = 25, //最大步数
    String? packageName, //执行任务包名
    String title = "", //任务标题
    String? subTitle, //子标题
    String? extraJson, //额外参数,获取info时会返回
  }) async {
    print(
      'scheduleVLMOperationTask goal: $goal, times: $times, model: $model, maxSteps: $maxSteps, packageName: $packageName',
    );
    try {
      final result = await assistCore
          .invokeMethod<String>('scheduleVLMOperationTask', {
            'goal': goal,
            'model': model,
            'maxSteps': maxSteps,
            'packageName': packageName,
            'times': times,
            'title': title,
            'subTitle': subTitle,
            'extraJson': extraJson,
          });
      return result;
    } on PlatformException catch (e) {
      print('预约VLM操作任务失败: ${e.message}');
      return null;
    }
  }

  /// 获取预约任务信息信息
  static Future<Map<String, dynamic>?> getScheduleTaskInfo() async {
    try {
      final result = await assistCore.invokeMethod<Map<Object?, Object?>>(
        'getScheduleInfo',
      );
      if (result != null) {
        return result.cast<String, dynamic>();
      }
      return null;
    } on PlatformException catch (e) {
      print('获取预约任务信息失败: ${e.message}');
      return null;
    }
  }

  /// 清除预约任务
  static Future<bool> clearScheduleTask() async {
    try {
      final result = await assistCore.invokeMethod('clearScheduleTask');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('清除预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 立即执行预约任务
  static Future<bool> doScheduleNow() async {
    try {
      final result = await assistCore.invokeMethod('doScheduleNow');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('立即执行预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 取消预约任务
  static Future<bool> cancelScheduleTask() async {
    try {
      final result = await assistCore.invokeMethod('cancelScheduleTask');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('取消预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 查询统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<List<Map<String, dynamic>>> listAgentExactAlarms() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'listAgentExactAlarms',
      );
      if (result == null) return [];
      return result.map((item) {
        if (item is Map) {
          return Map<String, dynamic>.from(item);
        }
        return <String, dynamic>{};
      }).toList();
    } on PlatformException catch (e) {
      print('查询应用内闹钟失败: ${e.message}');
      return [];
    }
  }

  /// 删除统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<bool> deleteAgentExactAlarm(String alarmId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteAgentExactAlarm',
        {'alarmId': alarmId},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      print('删除应用内闹钟失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>> getAlarmSettings() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'getAlarmSettings',
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('读取闹钟设置失败: ${e.message}');
      return {};
    }
  }

  static Future<Map<String, dynamic>> saveAlarmSettings({
    required String source,
    String? localPath,
    String? remoteUrl,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'saveAlarmSettings',
        {'source': source, 'localPath': localPath, 'remoteUrl': remoteUrl},
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('保存闹钟设置失败: ${e.message}');
      return {'success': false, 'message': e.message ?? '保存失败'};
    }
  }

  /// 获取当前 nanoTime（毫秒级，System.nanoTime() / 1_000_000）
  static Future<int?> getNanoTime() async {
    try {
      final result = await assistCore.invokeMethod<int>('getNanoTime');
      return result;
    } on PlatformException catch (e) {
      print('获取nanoTime失败: ${e.message}');
      return null;
    }
  }

  /// 执行首次任务
  static Future<bool> startFirstUse(String packageName) async {
    try {
      final result = await assistCore.invokeMethod('startFirstUse', {
        'packageName': packageName,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('执行首次任务失败: ${e.message}');
      return false;
    }
  }

  /// 初始化半屏引擎并启动首次体验
  static Future<void> initializeAndStartFirstUse(String packageName) async {
    print('🎯 [FirstUse] 开始初始化半屏引擎并启动首次体验');

    // 1. 首先初始化半屏引擎
    final initSuccess = await AppStateService.initHalfScreenEngine();
    if (initSuccess) {
      print('✅ [FirstUse] 半屏引擎初始化成功');
    } else {
      print('⚠️ [FirstUse] 半屏引擎初始化失败');
    }

    // 2. 延迟启动首次体验，确保引擎完全就绪
    await Future.delayed(const Duration(milliseconds: 300));

    // 3. 启动首次体验
    final startSuccess = await startFirstUse(packageName);
    if (startSuccess) {
      print('✅ [FirstUse] 首次体验启动成功');
    } else {
      print('⚠️ [FirstUse] 首次体验启动失败');
    }
  }

  /// 调用LLM chat接口（非流式）
  /// 用于修复JSON格式等场景
  static Future<String?> postLLMChat({
    required String text,
    String model = 'scene.dispatch.model',
  }) async {
    try {
      final result = await assistCore.invokeMethod<String>('postLLMChat', {
        'text': text,
        'model': model,
      });
      return result;
    } on PlatformException catch (e) {
      print('调用LLM chat失败: ${e.message}');
      return null;
    }
  }

  /// 生成记忆中心问候语（原生端优先使用标准 tool_calls）
  static Future<String?> generateMemoryGreeting({
    required List<Map<String, String>> records,
    String model = 'scene.compactor.context',
  }) async {
    try {
      final payloadRecords = records
          .map(
            (item) => {
              'title': item['title'] ?? '',
              'description': item['description'] ?? '',
              'appName': item['appName'] ?? '',
            },
          )
          .toList();
      final result = await assistCore.invokeMethod<String>(
        'generateMemoryGreeting',
        {'model': model, 'records': payloadRecords},
      );
      return result;
    } on PlatformException catch (e) {
      print('生成记忆中心问候语失败: ${e.message}');
      return null;
    }
  }

  /// 创建 Agent 任务
  static Future<bool> createAgentTask({
    required String taskId,
    required String userMessage,
    List<Map<String, dynamic>> conversationHistory = const [],
    List<Map<String, dynamic>> attachments = const [],
    int? userMessageCreatedAtMillis,
    int? conversationId,
    String? conversationMode,
    String? scheduledTaskId,
    String? scheduledTaskTitle,
    bool? scheduleNotificationEnabled,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
    Map<String, String>? terminalEnvironment,
  }) async {
    try {
      final args = <String, dynamic>{
        'taskId': taskId,
        'userMessage': userMessage,
      };
      if (conversationHistory.isNotEmpty) {
        args['conversationHistory'] = conversationHistory;
      }
      if (conversationId != null) {
        args['conversationId'] = conversationId;
      }
      if (conversationMode != null && conversationMode.trim().isNotEmpty) {
        args['conversationMode'] = conversationMode.trim();
      }
      if (userMessageCreatedAtMillis != null &&
          userMessageCreatedAtMillis > 0) {
        args['userMessageCreatedAt'] = userMessageCreatedAtMillis;
      }
      if (scheduledTaskId != null && scheduledTaskId.trim().isNotEmpty) {
        args['scheduledTaskId'] = scheduledTaskId.trim();
      }
      if (scheduledTaskTitle != null && scheduledTaskTitle.trim().isNotEmpty) {
        args['scheduledTaskTitle'] = scheduledTaskTitle.trim();
      }
      if (scheduleNotificationEnabled != null) {
        args['scheduleNotificationEnabled'] = scheduleNotificationEnabled;
      }
      if (attachments.isNotEmpty) {
        args['attachments'] = attachments;
      }
      if (modelOverride != null) {
        args['modelOverride'] = modelOverride;
      }
      if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty) {
        args['reasoningEffort'] = reasoningEffort.trim();
      }
      if (terminalEnvironment != null && terminalEnvironment.isNotEmpty) {
        args['terminalEnvironment'] = terminalEnvironment;
      }
      final result = await assistCore.invokeMethod('createAgentTask', {
        ...args,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('创建 Agent 任务失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>> compactConversationContext({
    required int conversationId,
    required String conversationMode,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<Map<dynamic, dynamic>>('compactConversationContext', {
            'conversationId': conversationId,
            'conversationMode': conversationMode,
            if (modelOverride != null) 'modelOverride': modelOverride,
            if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty)
              'reasoningEffort': reasoningEffort.trim(),
          });
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('手动压缩上下文失败: ${e.message}');
      return {
        'compacted': false,
        'reason': 'failed',
        'message': e.message ?? '手动压缩上下文失败',
      };
    }
  }

  static Future<Map<String, dynamic>?> upsertWorkspaceScheduledTask(
    Map<String, dynamic> task,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'upsertWorkspaceScheduledTask',
        {'task': task},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('更新原生定时任务失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteWorkspaceScheduledTask(String taskId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteWorkspaceScheduledTask',
        {'taskId': taskId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      print('删除原生定时任务失败: ${e.message}');
      return false;
    }
  }

  static Future<int> syncWorkspaceScheduledTasks(
    List<Map<String, dynamic>> tasks,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'syncWorkspaceScheduledTasks',
        {'tasks': tasks},
      );
      if (result == null) return 0;
      final count = result['count'];
      if (count is int) return count;
      if (count is String) return int.tryParse(count) ?? 0;
      return 0;
    } on PlatformException catch (e) {
      print('同步原生定时任务失败: ${e.message}');
      return 0;
    }
  }

  static Future<List<Map<String, dynamic>>> listAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'agentSkillList',
      );
      return (result ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList();
    } on PlatformException catch (e) {
      print('读取 Agent skills 失败: ${e.message}');
      return const [];
    }
  }

  static Future<Map<String, dynamic>?> installAgentSkill({
    required String sourcePath,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstall',
        {'sourcePath': sourcePath},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('安装 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> setAgentSkillEnabled({
    required String skillId,
    required bool enabled,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSetEnabled',
        {'skillId': skillId, 'enabled': enabled},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('切换 Agent skill 启用状态失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteAgentSkill({required String skillId}) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillDelete',
        {'skillId': skillId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      print('删除 Agent skill 失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>?> installBuiltinAgentSkill({
    required String skillId,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstallBuiltin',
        {'skillId': skillId},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('安装内置 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  /// 检测自定义 VLM 模型可用性（OpenAI-compatible）
  static Future<ModelAvailabilityCheckResult> checkVlmModelAvailability({
    required String model,
    required String apiBase,
    String apiKey = '',
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'checkVlmModelAvailability',
        {'model': model, 'apiBase': apiBase, 'apiKey': apiKey},
      );
      return ModelAvailabilityCheckResult.fromMap(result);
    } on PlatformException catch (e) {
      return ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: e.message ?? '检测失败',
      );
    } catch (e) {
      return ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: '检测失败: $e',
      );
    }
  }

  /// 打开应用市场
  static Future<String?> openAPPMarket(String packageName) async {
    try {
      final result = await assistCore.invokeMethod<String>('openAPPMarket', {
        'packageName': packageName,
      });
      return result;
    } on PlatformException catch (e) {
      print('调用openAPPMarket失败: ${e.message}');
      return null;
    }
  }

  /// 检查是否在桌面
  static Future<bool> isDesktop() async {
    try {
      final result = await assistCore.invokeMethod<bool>('isDesktop');
      return result ?? false;
    } on PlatformException catch (e) {
      print('检查是否在桌面失败: ${e.message}');
      return false;
    }
  }

  /// 获取桌面包名
  static Future<List<String>?> getDeskTopPackageName() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getDeskTopPackageName',
      );
      if (result != null) {
        return result.map((e) => e.toString()).toList();
      }
      return null;
    } on PlatformException catch (e) {
      print('获取桌面包名失败: ${e.message}');
      return null;
    }
  }

  /// 获取当前应用包名
  /// 用于从当前页面开始执行任务
  static Future<String?> getCurrentPackageName() async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'getCurrentPackageName',
      );
      return result;
    } on PlatformException catch (e) {
      print('获取当前应用包名失败: ${e.message}');
      return null;
    }
  }

  /// 同步“任务完成后自动回聊天”设置到原生层
  static Future<bool> setAutoBackToChatAfterTaskEnabled(bool enabled) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setAutoBackToChatAfterTaskEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('同步自动回聊天设置失败: ${e.message}');
      return false;
    }
  }

  /// 跳转到主引擎路由
  static Future<bool> navigateToMainEngineRoute(String route) async {
    try {
      final result = await assistCore.invokeMethod(
        'navigateToMainEngineRoute',
        {'route': route},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('跳转到主引擎路由失败: ${e.message}');
      return false;
    }
  }

  /// 显示定时任务倒计时提醒（原生浮层）
  static Future<bool> showScheduledTaskReminder({
    required String taskId,
    required String taskName,
    int countdownSeconds = 5,
  }) async {
    try {
      final result = await assistCore.invokeMethod(
        'showScheduledTaskReminder',
        {
          'taskId': taskId,
          'taskName': taskName,
          'countdownSeconds': countdownSeconds,
        },
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('显示定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 隐藏定时任务倒计时提醒
  static Future<bool> hideScheduledTaskReminder() async {
    try {
      final result = await assistCore.invokeMethod('hideScheduledTaskReminder');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('隐藏定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 授权完成后重新打开ChatBot
  static Future<bool> reopenChatBotAfterAuth() async {
    try {
      final result = await assistCore.invokeMethod('reopenChatBotAfterAuth');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('重新打开ChatBot失败: ${e.message}');
      return false;
    }
  }

  // ==================== Embedded Provider 管理 ====================

  /// 获取 Embedded Provider 状态
  static Future<EmbeddedProviderStatus> getEmbeddedProviderStatus() async {
    final result = await assistCore.invokeMethod('getEmbeddedProviderStatus');
    return EmbeddedProviderStatus.fromMap(result as Map?);
  }

  /// 安装 Embedded Provider
  static Future<EmbeddedProviderInstallResult> installEmbeddedProvider({
    String? downloadUrl,
  }) async {
    final result = await assistCore.invokeMethod('installEmbeddedProvider', {
      if (downloadUrl != null) 'downloadUrl': downloadUrl,
    });
    return EmbeddedProviderInstallResult.fromMap(result as Map?);
  }

  /// 启动 Embedded Provider
  static Future<bool> startEmbeddedProvider({int port = 9417}) async {
    final result = await assistCore.invokeMethod('startEmbeddedProvider', {
      'port': port,
    });
    return (result as Map?)?['success'] == true;
  }

  /// 停止 Embedded Provider
  static Future<bool> stopEmbeddedProvider() async {
    final result = await assistCore.invokeMethod('stopEmbeddedProvider');
    return (result as Map?)?['success'] == true;
  }

  /// 卸载 Embedded Provider
  static Future<bool> uninstallEmbeddedProvider() async {
    final result = await assistCore.invokeMethod('uninstallEmbeddedProvider');
    return (result as Map?)?['success'] == true;
  }
}
