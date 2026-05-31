import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/agent_schedule_bridge_service.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/model_provider_config_service.dart';

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
typedef AgentStreamEventCallback = void Function(AgentStreamEvent event);
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
        message: '检测失败：返回为空',
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
  final String runIndexPath;
  final String runStorageDir;
  final bool useEmbeddedProvider; // 是否使用内置 Provider
  final String providerConnectionMode;
  final OmniFlowPackageStatus devicePackageStatus;

  /// `/health` 响应中的 provider 状态，已随 config 一起返回，无需额外 HTTP 调用
  final OmniFlowStatus? providerStatus;

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
    required this.runIndexPath,
    required this.runStorageDir,
    required this.useEmbeddedProvider,
    required this.providerConnectionMode,
    required this.devicePackageStatus,
    this.providerStatus,
  });

  factory UtgBridgeConfig.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    final healthRaw = raw['providerHealth'];
    final health = (healthRaw is Map)
        ? Map<String, dynamic>.from(healthRaw)
        : <String, dynamic>{};
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
      runIndexPath: (raw['runIndexPath'] ?? '').toString(),
      runStorageDir: (raw['runStorageDir'] ?? '').toString(),
      useEmbeddedProvider: raw['useEmbeddedProvider'] == true,
      providerConnectionMode:
          (raw['providerConnectionMode'] ??
                  (raw['useEmbeddedProvider'] == true ? 'embedded' : 'bridge'))
              .toString(),
      devicePackageStatus: OmniFlowPackageStatus.fromMap(
        raw['devicePackageStatus'] is Map
            ? Map<String, dynamic>.from(raw['devicePackageStatus'] as Map)
            : const <String, dynamic>{},
      ),
      providerStatus: health.isNotEmpty
          ? OmniFlowStatus.fromHealth(health)
          : null,
    );
  }
}

/// 当前设备上的 OmniFlow 包状态（来自 native package manager）。
class OmniFlowPackageStatus {
  final bool installed;
  final String? installedVersion;
  final String? installedHash;
  final String? installSource;
  final bool externalWheelAvailable;

  const OmniFlowPackageStatus({
    required this.installed,
    this.installedVersion,
    this.installedHash,
    this.installSource,
    required this.externalWheelAvailable,
  });

  factory OmniFlowPackageStatus.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return OmniFlowPackageStatus(
      installed: raw['installed'] == true,
      installedVersion: raw['installedVersion']?.toString(),
      installedHash: raw['installedHash']?.toString(),
      installSource: raw['installSource']?.toString(),
      externalWheelAvailable: raw['externalWheelAvailable'] == true,
    );
  }

  String? get versionDisplay => installedVersion?.trim().isNotEmpty == true
      ? installedVersion!.trim()
      : installedHash?.trim();
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
      port: raw['port'] is num
          ? (raw['port'] as num).toInt()
          : int.tryParse((raw['port'] ?? '9417').toString()) ?? 9417,
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
  final int? wheelSizeBytes; // wheel 文件大小
  final bool localWheelExists;
  final String? localWheelHash;
  final String? error;

  const UtgUpdateCheckResult({
    required this.currentVersion,
    this.latestVersion,
    this.latestCommit,
    required this.updateAvailable,
    required this.wheelUrl,
    this.wheelSizeBytes,
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
      wheelSizeBytes: raw['wheel_size_bytes'] is num
          ? (raw['wheel_size_bytes'] as num).toInt()
          : int.tryParse((raw['wheel_size_bytes'] ?? '').toString()),
      localWheelExists: raw['local_wheel_exists'] == true,
      localWheelHash: raw['local_wheel_hash']?.toString(),
      error: raw['error']?.toString(),
    );
  }

  /// 格式化 wheel 大小显示
  String get wheelSizeFormatted {
    if (wheelSizeBytes == null) return '';
    final mb = wheelSizeBytes! / (1024 * 1024);
    return '${mb.toStringAsFixed(1)} MB';
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
  final String? connectionMode;
  final bool providerRestarted;
  final String? hint; // 额外提示信息

  const UtgUpdateApplyResult({
    required this.success,
    this.previousVersion,
    this.installedVersion,
    this.latestVersion,
    required this.restartRequired,
    this.message,
    this.error,
    this.connectionMode,
    this.providerRestarted = false,
    this.hint,
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
      connectionMode: raw['connection_mode']?.toString(),
      providerRestarted: raw['provider_restarted'] == true,
      hint: raw['hint']?.toString(),
    );
  }
}

/// Provider 本地 Store 信息（来自 /health 的 store 字段）
class OmniFlowStore {
  final String? path;
  final int functionCount;
  final int runLogCount;

  const OmniFlowStore({
    this.path,
    this.functionCount = 0,
    this.runLogCount = 0,
  });

  factory OmniFlowStore.fromJson(Map<String, dynamic>? json) {
    if (json == null) return const OmniFlowStore();
    return OmniFlowStore(
      path: json['path']?.toString(),
      functionCount: json['function_count'] is num
          ? (json['function_count'] as num).toInt()
          : int.tryParse((json['function_count'] ?? '0').toString()) ?? 0,
      runLogCount: json['run_log_count'] is num
          ? (json['run_log_count'] as num).toInt()
          : int.tryParse((json['run_log_count'] ?? '0').toString()) ?? 0,
    );
  }

  String get pathDisplay {
    if (path == null) return '未加载';
    final name = path!.split('/').last;
    return name.length > 24 ? '...${name.substring(name.length - 24)}' : name;
  }
}

/// Provider 连接状态（来自 /health 端点）
class OmniFlowStatus {
  final bool connected;
  final String version;
  final String buildType;
  final int port;
  final int embeddingDim;
  final OmniFlowStore store;

  const OmniFlowStatus({
    required this.connected,
    required this.version,
    required this.buildType,
    required this.port,
    required this.embeddingDim,
    required this.store,
  });

  factory OmniFlowStatus.fromHealth(Map<String, dynamic> json) {
    return OmniFlowStatus(
      connected: json['success'] == true,
      version: (json['version'] ?? 'unknown').toString(),
      buildType: (json['build_type'] ?? 'python').toString(),
      port: json['port'] is num
          ? (json['port'] as num).toInt()
          : int.tryParse((json['port'] ?? '9417').toString()) ?? 9417,
      embeddingDim: json['embedding_dim'] is num
          ? (json['embedding_dim'] as num).toInt()
          : int.tryParse((json['embedding_dim'] ?? '64').toString()) ?? 64,
      store: OmniFlowStore.fromJson(
        json['store'] is Map
            ? Map<String, dynamic>.from(json['store'] as Map)
            : null,
      ),
    );
  }

  String get versionDisplay {
    final suffix = buildType == 'cython' ? ' (Cython)' : '';
    return '$version$suffix';
  }
}

class UtgFunctionSummary {
  final String functionId;
  final String description;
  final int actionCount; // Recursive total (expanding call_function)
  final int stepCount; // Direct steps only
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
  final String runIndexPath;
  final String runStorageDir;
  final String runFilePath;
  final Map<String, dynamic> rawJson;

  const UtgManualRunResult({
    required this.success,
    required this.goal,
    required this.functionId,
    required this.errorCode,
    required this.errorMessage,
    required this.terminalState,
    required this.runIndexPath,
    required this.runStorageDir,
    required this.runFilePath,
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
      runIndexPath: (map['run_index_path'] ?? '').toString(),
      runStorageDir: (map['run_storage_dir'] ?? '').toString(),
      runFilePath: (map['run_file_path'] ?? '').toString(),
      rawJson: Map<String, dynamic>.from(map),
    );
  }

  Map<String, dynamic> get context {
    final raw = rawJson['context'];
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return raw.map((key, value) => MapEntry(key.toString(), value));
    }
    return const <String, dynamic>{};
  }

  List<Map<String, dynamic>> get stepResults {
    final raw =
        context['step_results'] ??
        terminalState['step_results'] ??
        rawJson['step_results'];
    if (raw is! List) return const <Map<String, dynamic>>[];
    return raw
        .whereType<Map>()
        .map(
          (item) => item.map((key, value) => MapEntry(key.toString(), value)),
        )
        .toList(growable: false);
  }

  bool get modelRequired => _truthy(
    terminalState['model_required'] ??
        rawJson['model_required'] ??
        context['model_required'],
  );

  bool get fallbackAvailable => _truthy(
    terminalState['fallback_available'] ??
        rawJson['fallback_available'] ??
        context['fallback_available'],
  );

  bool get canContinueWithAgent {
    if (success || completedVlmFallback || startedAgentFallback) return false;
    if (fallbackAvailable || modelRequired) return true;
    return stepResults.any(
      (step) =>
          step['needs_agent'] == true ||
          step['fallback_available'] == true ||
          step['blocked_executor'] != null,
    );
  }

  bool get canContinueWithVlm => canContinueWithAgent;

  bool get delegatedToolUsed => _truthy(
    terminalState['delegated_tool_used'] ??
        rawJson['delegated_tool_used'] ??
        context['delegated_tool_used'],
  );

  int get stepCount => _intValue(
    terminalState['step_count'] ??
        rawJson['step_count'] ??
        context['step_count'],
  );

  int get successStepCount => _intValue(
    terminalState['success_step_count'] ??
        rawJson['success_step_count'] ??
        context['success_step_count'],
  );

  String get runner =>
      (terminalState['runner'] ?? rawJson['runner'] ?? context['runner'] ?? '')
          .toString()
          .trim();

  String get executionStatus =>
      (terminalState['execution_status'] ??
              terminalState['executionStatus'] ??
              rawJson['execution_status'] ??
              rawJson['executionStatus'] ??
              context['execution_status'] ??
              context['executionStatus'] ??
              terminalState['status'] ??
              '')
          .toString()
          .trim();

  String get taskId =>
      (terminalState['taskId'] ??
              terminalState['task_id'] ??
              terminalState['agent_task_id'] ??
              rawJson['taskId'] ??
              rawJson['task_id'] ??
              '')
          .toString()
          .trim();

  bool get completedLocal =>
      executionStatus == 'completed_local' ||
      executionStatus == 'completed' ||
      terminalState['status'] == 'completed';

  bool get completedVlmFallback =>
      executionStatus == 'completed_vlm_fallback' ||
      executionStatus == 'vlm_fallback_completed';

  bool get startedAgentFallback {
    if (completedVlmFallback) return false;
    final agentTaskStarted =
        terminalState['agent_task_started'] ??
        rawJson['agent_task_started'] ??
        context['agent_task_started'];
    if (agentTaskStarted == false) return false;
    return executionStatus == 'started_agent_fallback' ||
        executionStatus == 'started_agent' ||
        (success && taskId.isNotEmpty && modelRequired);
  }

  bool get failed =>
      !success ||
      executionStatus == 'failed' ||
      executionStatus == 'error' ||
      terminalState['status'] == 'error';

  int get startedAtMs => _intValue(
    _firstPresent([
      rawJson['started_at_ms'],
      rawJson['startedAtMs'],
      terminalState['started_at_ms'],
      terminalState['startedAtMs'],
      _timing['started_at_ms'],
      _timing['startedAtMs'],
    ]),
  );

  int get finishedAtMs => _intValue(
    _firstPresent([
      rawJson['finished_at_ms'],
      rawJson['finishedAtMs'],
      terminalState['finished_at_ms'],
      terminalState['finishedAtMs'],
      _timing['finished_at_ms'],
      _timing['finishedAtMs'],
    ]),
  );

  int get durationMs {
    final explicit = _intValue(
      _firstPresent([
        rawJson['duration_ms'],
        rawJson['durationMs'],
        terminalState['duration_ms'],
        terminalState['durationMs'],
        _timing['duration_ms'],
        _timing['durationMs'],
        _timing['runner_duration_ms'],
        _timing['runnerDurationMs'],
      ]),
    );
    if (explicit > 0) return explicit;
    final started = startedAtMs;
    final finished = finishedAtMs;
    if (started > 0 && finished >= started) return finished - started;
    return 0;
  }

  Map<String, dynamic> get phaseMs {
    final raw =
        rawJson['phase_ms'] ??
        rawJson['phaseMs'] ??
        terminalState['phase_ms'] ??
        terminalState['phaseMs'] ??
        _timing['phase_ms'] ??
        _timing['phaseMs'];
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return raw.map((key, value) => MapEntry(key.toString(), value));
    }
    return const <String, dynamic>{};
  }

  Map<String, dynamic> get _timing {
    final raw =
        rawJson['timing'] ?? terminalState['timing'] ?? context['timing'];
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return raw.map((key, value) => MapEntry(key.toString(), value));
    }
    return const <String, dynamic>{};
  }

  static dynamic _firstPresent(Iterable<dynamic> values) {
    for (final value in values) {
      if (value != null) return value;
    }
    return null;
  }

  static bool _truthy(dynamic value) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    if (value is String) {
      final normalized = value.trim().toLowerCase();
      return normalized == 'true' || normalized == '1' || normalized == 'yes';
    }
    return false;
  }

  static int _intValue(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value.trim()) ?? 0;
    return 0;
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
  final bool runFinished;
  final bool? runSuccess;
  final String runStatus;
  final String doneReason;
  final int stepCount;
  final int? startedAtMs;
  final int? finishedAtMs;
  final String startedAt;
  final String finishedAt;
  final num? durationMs;
  final String toolName;
  final String executionStatus;
  final String executionFunctionId;
  final String executionMode;
  final String actFunctionId;
  final String source;
  final String executionSummary;
  final String operationDescription;
  final String selectorLabel;
  final String selectorReason;
  final String errorMessage;
  final String finalPackageName;
  final int? tokenUsageTotal;
  final Map<String, dynamic> tokenUsage;
  final bool registeredAsFunction;
  final String registeredFunctionId;
  final int registeredFunctionCount;
  final List<String> registeredFunctionIds;
  final Map<String, dynamic> rawJson;

  const UtgRunLogSummary({
    required this.runId,
    required this.goal,
    required this.success,
    required this.runFinished,
    required this.runSuccess,
    required this.runStatus,
    required this.doneReason,
    required this.stepCount,
    required this.startedAtMs,
    required this.finishedAtMs,
    required this.startedAt,
    required this.finishedAt,
    required this.durationMs,
    required this.toolName,
    required this.executionStatus,
    required this.executionFunctionId,
    required this.executionMode,
    required this.actFunctionId,
    required this.source,
    required this.executionSummary,
    required this.operationDescription,
    required this.selectorLabel,
    required this.selectorReason,
    required this.errorMessage,
    required this.finalPackageName,
    required this.tokenUsageTotal,
    required this.tokenUsage,
    required this.registeredAsFunction,
    required this.registeredFunctionId,
    required this.registeredFunctionCount,
    required this.registeredFunctionIds,
    required this.rawJson,
  });

  factory UtgRunLogSummary.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgRunLogSummary(
      runId: (raw['run_id'] ?? '').toString(),
      goal: (raw['goal'] ?? '').toString(),
      success: raw['success'] == true,
      runFinished:
          _parseBool(raw['run_finished'] ?? raw['runFinished']) ??
          ((raw['finished_at'] ?? raw['finishedAt'] ?? '')
                  .toString()
                  .trim()
                  .isNotEmpty ||
              raw['finished_at_ms'] != null ||
              raw['finishedAtMs'] != null),
      runSuccess: _parseBool(raw['run_success'] ?? raw['runSuccess']),
      runStatus: (raw['run_status'] ?? raw['runStatus'] ?? '').toString(),
      doneReason: (raw['done_reason'] ?? '').toString(),
      stepCount: raw['step_count'] is num
          ? (raw['step_count'] as num).toInt()
          : int.tryParse((raw['step_count'] ?? '0').toString()) ?? 0,
      startedAtMs: raw['started_at_ms'] is num
          ? (raw['started_at_ms'] as num).toInt()
          : int.tryParse((raw['started_at_ms'] ?? '').toString()),
      finishedAtMs: raw['finished_at_ms'] is num
          ? (raw['finished_at_ms'] as num).toInt()
          : int.tryParse((raw['finished_at_ms'] ?? '').toString()),
      startedAt: (raw['started_at'] ?? '').toString(),
      finishedAt: (raw['finished_at'] ?? '').toString(),
      durationMs: raw['duration_ms'] as num?,
      toolName: (raw['tool_name'] ?? '').toString(),
      executionStatus: (raw['execution_status'] ?? raw['compile_status'] ?? '')
          .toString(),
      executionFunctionId:
          (raw['execution_function_id'] ?? raw['compile_function_id'] ?? '')
              .toString(),
      executionMode: (raw['execution_mode'] ?? raw['compile_mode'] ?? '')
          .toString(),
      actFunctionId: (raw['act_function_id'] ?? '').toString(),
      source: (raw['source'] ?? '').toString(),
      executionSummary: _userVisibleExecutionText(
        (raw['execution_summary'] ?? raw['compile_summary'] ?? '').toString(),
      ),
      operationDescription: (raw['operation_description'] ?? '').toString(),
      selectorLabel: (raw['selector_label'] ?? '').toString(),
      selectorReason: (raw['selector_reason'] ?? '').toString(),
      errorMessage: (raw['error_message'] ?? '').toString(),
      finalPackageName: (raw['final_package_name'] ?? '').toString(),
      tokenUsageTotal: raw['token_usage_total'] is num
          ? (raw['token_usage_total'] as num).toInt()
          : int.tryParse((raw['token_usage_total'] ?? '').toString()),
      tokenUsage: Map<String, dynamic>.from(
        (raw['token_usage'] as Map<dynamic, dynamic>? ?? const {}).map(
          (k, v) => MapEntry(k.toString(), v),
        ),
      ),
      registeredAsFunction:
          _parseBool(
            raw['registered_as_function'] ??
                raw['registeredAsFunction'] ??
                raw['is_registered_function'] ??
                raw['isRegisteredFunction'],
          ) ??
          false,
      registeredFunctionId:
          (raw['registered_function_id'] ?? raw['registeredFunctionId'] ?? '')
              .toString(),
      registeredFunctionCount: raw['registered_function_count'] is num
          ? (raw['registered_function_count'] as num).toInt()
          : int.tryParse(
                  (raw['registered_function_count'] ??
                          raw['registeredFunctionCount'] ??
                          '0')
                      .toString(),
                ) ??
                0,
      registeredFunctionIds:
          ((raw['registered_function_ids'] ?? raw['registeredFunctionIds'])
                  as List<dynamic>?)
              ?.map((e) => e.toString())
              .where((e) => e.trim().isNotEmpty)
              .toList() ??
          const <String>[],
      rawJson: Map<String, dynamic>.from(
        (raw['raw_run'] as Map<dynamic, dynamic>? ?? const {}).map(
          (k, v) => MapEntry(k.toString(), v),
        ),
      ),
    );
  }
}

bool? _parseBool(dynamic value) {
  if (value is bool) return value;
  final text = value?.toString().trim().toLowerCase();
  if (text == 'true') return true;
  if (text == 'false') return false;
  return null;
}

String _userVisibleExecutionText(String value) {
  final trimmed = value.trim();
  if (trimmed.isEmpty) return '';
  return trimmed
      .replaceAll(RegExp(r'\bcompiled\b', caseSensitive: false), 'executed')
      .replaceAll(RegExp(r'\bcompiler\b', caseSensitive: false), 'runner')
      .replaceAll(RegExp(r'\bcompilation\b', caseSensitive: false), 'execution')
      .replaceAll(RegExp(r'\bcompile\b', caseSensitive: false), 'execute')
      .replaceAll('编译', '执行');
}

class UtgRunLogsSnapshot {
  final bool success;
  final int count;
  final int totalCount;
  final int limit;
  final int offset;
  final int nextOffset;
  final bool hasMore;
  final List<UtgRunLogSummary> runs;
  final String runIndexPath;
  final String runStorageDir;
  final String provider;

  const UtgRunLogsSnapshot({
    required this.success,
    required this.count,
    required this.totalCount,
    required this.limit,
    required this.offset,
    required this.nextOffset,
    required this.hasMore,
    required this.runs,
    required this.runIndexPath,
    required this.runStorageDir,
    required this.provider,
  });

  factory UtgRunLogsSnapshot.fromMap(Map<String, dynamic> map) {
    return UtgRunLogsSnapshot(
      success: map['success'] == true,
      count: map['count'] is num
          ? (map['count'] as num).toInt()
          : int.tryParse((map['count'] ?? '0').toString()) ?? 0,
      totalCount: map['total_count'] is num
          ? (map['total_count'] as num).toInt()
          : int.tryParse(
                  (map['total_count'] ?? map['count'] ?? '0').toString(),
                ) ??
                0,
      limit: map['limit'] is num
          ? (map['limit'] as num).toInt()
          : int.tryParse((map['limit'] ?? '0').toString()) ?? 0,
      offset: map['offset'] is num
          ? (map['offset'] as num).toInt()
          : int.tryParse((map['offset'] ?? '0').toString()) ?? 0,
      nextOffset: map['next_offset'] is num
          ? (map['next_offset'] as num).toInt()
          : int.tryParse((map['next_offset'] ?? '0').toString()) ?? 0,
      hasMore: map['has_more'] == true || map['hasMore'] == true,
      runs:
          (map['runs'] as List<dynamic>?)
              ?.map((e) => UtgRunLogSummary.fromMap(e as Map?))
              .toList() ??
          const <UtgRunLogSummary>[],
      runIndexPath: (map['run_index_path'] ?? '').toString(),
      runStorageDir: (map['run_storage_dir'] ?? '').toString(),
      provider: (map['provider'] ?? '').toString(),
    );
  }
}

class UtgRunLogDetail {
  final bool success;
  final String runId;
  final String runFilePath;
  final String provider;
  final String errorCode;
  final String errorMessage;
  final Map<String, dynamic> runLog;
  final Map<String, dynamic> rawJson;

  const UtgRunLogDetail({
    required this.success,
    required this.runId,
    required this.runFilePath,
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
      runFilePath: (raw['run_file_path'] ?? '').toString(),
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
  final String runFilePath;
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
    required this.runFilePath,
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
      runFilePath: (map['run_file_path'] ?? '').toString(),
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
  final String? errorCode;
  final String? errorMessage;

  const UtgFunctionEnrichResult({
    required this.success,
    required this.functionId,
    this.description,
    this.slots = const [],
    this.preconditions = const [],
    this.errorCode,
    this.errorMessage,
  });

  factory UtgFunctionEnrichResult.fromMap(Map<String, dynamic> map) {
    return UtgFunctionEnrichResult(
      success: map['success'] == true,
      functionId: (map['function_id'] ?? '').toString(),
      description: map['description']?.toString(),
      slots:
          (map['slots'] as List<dynamic>?)
              ?.map(
                (s) => s is Map
                    ? Map<String, dynamic>.from(s)
                    : <String, dynamic>{},
              )
              .toList() ??
          const [],
      preconditions:
          (map['preconditions'] as List<dynamic>?)
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
      newFunctions:
          (map['new_functions'] as List<dynamic>?)
              ?.map(
                (f) => f is Map
                    ? Map<String, dynamic>.from(f)
                    : <String, dynamic>{},
              )
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
        .map(
          (t) => t is Map ? Map<String, dynamic>.from(t) : <String, dynamic>{},
        )
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
  final String toolCallId;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String toolType;
  final String? serverName;
  final String status;
  final String argsJson;
  final String progress;
  final String summary;
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
  final String subagentStatusText;
  final List<Map<String, dynamic>> subagentEvents;
  final bool success;

  const AgentToolEventData({
    required this.taskId,
    this.cardId = '',
    this.toolCallId = '',
    required this.toolName,
    required this.displayName,
    this.toolTitle = '',
    required this.toolType,
    this.serverName,
    this.status = '',
    this.argsJson = '',
    this.progress = '',
    this.summary = '',
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
    this.subagentStatusText = '',
    this.subagentEvents = const [],
    this.success = true,
  });

  factory AgentToolEventData.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return AgentToolEventData(
      taskId: (raw['taskId'] ?? '').toString(),
      cardId: (raw['cardId'] ?? '').toString(),
      toolCallId: (raw['toolCallId'] ?? raw['tool_call_id'] ?? '').toString(),
      toolName: (raw['toolName'] ?? '').toString(),
      displayName: (raw['displayName'] ?? raw['toolName'] ?? '').toString(),
      toolTitle: (raw['toolTitle'] ?? '').toString(),
      toolType: (raw['toolType'] ?? 'builtin').toString(),
      serverName: raw['serverName']?.toString(),
      status: (raw['status'] ?? '').toString(),
      argsJson: (raw['argsJson'] ?? raw['args'] ?? '').toString(),
      progress: (raw['progress'] ?? '').toString(),
      summary: (raw['summary'] ?? '').toString(),
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
      subagentStatusText: (raw['subagentStatusText'] ?? '').toString(),
      subagentEvents: _readSubagentEvents(
        raw['subagentEvents'] ?? raw['subagentEvent'],
      ),
      success: raw['success'] != false,
    );
  }

  static List<Map<String, dynamic>> _readSubagentEvents(dynamic value) {
    final rawEvents = value is List
        ? value
        : value is Map
        ? <dynamic>[value]
        : const <dynamic>[];
    return rawEvents
        .whereType<Map>()
        .map(
          (item) => item.map<String, dynamic>(
            (key, value) => MapEntry(key.toString(), value),
          ),
        )
        .toList(growable: false);
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
  static AgentPromptTokenUsageCallback? _onAgentPromptTokenUsageCallback;
  static AgentContextCompactionStateCallback?
  _onAgentContextCompactionStateCallback;

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
  static final StreamController<Map<String, dynamic>>
  _workbenchProjectUpdatedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _agentRunStateChangedController =
      StreamController<Map<String, dynamic>>.broadcast();

  // 改为回调列表，支持多个监听器
  static final List<ChatTaskMessageCallBack> _onChatTaskMessageCallBacks = [];
  static final List<ChatTaskMessageEndCallBack> _onChatTaskMessageEndCallBacks =
      [];
  static final List<AgentStreamEventCallback> _onAgentStreamEventCallbacks = [];
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
  static Stream<Map<String, dynamic>> get workbenchProjectUpdatedStream =>
      _workbenchProjectUpdatedController.stream;
  static Stream<Map<String, dynamic>> get agentRunStateChangedStream =>
      _agentRunStateChangedController.stream;

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
        case 'workbenchProjectUpdated':
          _workbenchProjectUpdatedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onAgentRunStateChanged':
          _agentRunStateChangedController.add(
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
        case 'onAgentStreamEvent':
          final event = AgentStreamEvent.fromMap(call.arguments as Map?);
          for (final callback in _onAgentStreamEventCallbacks) {
            callback(event);
          }
          break;
        case 'onAgentStreamEventBatch':
          final rawBatch = call.arguments;
          if (rawBatch is List) {
            for (final rawEvent in rawBatch) {
              try {
                final event = AgentStreamEvent.fromMap(rawEvent as Map?);
                for (final callback in _onAgentStreamEventCallbacks) {
                  callback(event);
                }
              } catch (_) {}
            }
          }
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
        case 'agentImagePick':
          final args = call.arguments is Map
              ? Map<String, dynamic>.from(call.arguments as Map)
              : <String, dynamic>{};
          final sourceStr = args['source']?.toString() ?? 'gallery';
          final source = sourceStr == 'camera'
              ? ImageSource.camera
              : ImageSource.gallery;
          final XFile? file = await ImagePicker().pickImage(
            source: source,
            imageQuality: 85,
          );
          return file == null ? null : {'path': file.path, 'name': file.name};

        case 'agentImagePickMultiple':
          final multiArgs = call.arguments is Map
              ? Map<String, dynamic>.from(call.arguments as Map)
              : <String, dynamic>{};
          final limit = (multiArgs['limit'] as num?)?.toInt() ?? 9;
          final files = await ImagePicker().pickMultiImage(
            imageQuality: 85,
            limit: limit,
          );
          return files.map((f) => {'path': f.path, 'name': f.name}).toList();

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

  static int? _asNullableInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw);
    return null;
  }

  static void setOnAgentStreamEventCallback(
    AgentStreamEventCallback? callback,
  ) {
    if (callback != null && !_onAgentStreamEventCallbacks.contains(callback)) {
      _onAgentStreamEventCallbacks.add(callback);
    }
  }

  static void removeOnAgentStreamEventCallback(
    AgentStreamEventCallback? callback,
  ) {
    _onAgentStreamEventCallbacks.remove(callback);
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

  /// 查询后端当前正在执行的 Agent 任务。
  static Future<List<Map<String, dynamic>>> listActiveAgentRuns() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentRunList',
      );
      final runs = (result?['runs'] as List?) ?? const [];
      return runs
          .whereType<Map>()
          .map(
            (item) => item.map((key, value) => MapEntry(key.toString(), value)),
          )
          .toList(growable: false);
    } on Exception catch (e) {
      final message = e is PlatformException ? e.message : e.toString();
      print('查询运行中 Agent 失败: $message');
      return const [];
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

  static Future<UtgRunLogsSnapshot> getInternalRunLogs({
    int limit = 50,
    int offset = 0,
  }) async {
    final result = await assistCore.invokeMethod('getInternalRunLogs', {
      'limit': limit,
      'offset': offset,
    });
    if (result is! Map) {
      throw Exception('内部 RunLog 响应格式错误');
    }
    return UtgRunLogsSnapshot.fromMap(Map<String, dynamic>.from(result));
  }

  static Future<UtgRunLogsSnapshot> getRunLogsPreferInternal({
    String? baseUrl,
    int limit = 50,
  }) async {
    return getInternalRunLogs(limit: limit);
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

  static Future<Map<String, dynamic>> getInternalRunLogTimeline({
    required String runId,
  }) async {
    final result = await assistCore.invokeMethod('getInternalRunLogTimeline', {
      'runId': runId.trim(),
    });
    if (result is! Map) {
      throw Exception('内部 RunLog 响应格式错误');
    }
    return Map<String, dynamic>.from(result);
  }

  static Future<Map<String, dynamic>> getRunLogTimelinePreferInternal({
    required String runId,
    String? baseUrl,
  }) async {
    return getInternalRunLogTimeline(runId: runId);
  }

  /// 将当前 OOB 模型 provider 的 API Key 推送到 OmniFlow（best-effort）。
  ///
  /// OOB 在 provider 连接建立后调用，将配置好的 API Key 注入 OmniFlow
  /// 的运行时配置，使嵌入和 LLM 调用自动使用 OOB 里配置的 provider。
  /// 任何异常都被静默忽略，不影响正常流程。
  static Future<void> syncModelProviderToOmniFlow({String? baseUrl}) async {
    try {
      final profile = await ModelProviderConfigService.getConfig();
      if (!profile.configured || profile.apiKey.trim().isEmpty) return;
      final protocolType = profile.providerType == 'dashscope'
          ? 'dashscope'
          : 'openai_compatible';
      await _requestUtgJson(
        method: 'POST',
        path: '/api/configure',
        baseUrl: baseUrl,
        payload: {
          'api_key': profile.apiKey.trim(),
          'base_url': profile.baseUrl.trim(),
          'protocol_type': protocolType,
        },
      );
    } catch (_) {
      // best-effort: 不抛出异常，不阻塞 UI
    }
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

  /// 获取 Provider 健康状态（包含版本、构建类型、端口、Store 信息）
  static Future<OmniFlowStatus?> getProviderHealth({String? baseUrl}) async {
    try {
      final decoded = await _requestUtgJson(
        method: 'GET',
        path: '/health',
        baseUrl: baseUrl,
      );
      return OmniFlowStatus.fromHealth(decoded);
    } catch (_) {
      return null;
    }
  }

  /// 检查当前设备里的 OmniFlow 包是否有新版本。
  static Future<UtgUpdateCheckResult> checkForUpdate({String? baseUrl}) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/update/check',
      baseUrl: baseUrl,
    );
    final merged = Map<String, dynamic>.from(decoded);
    try {
      final config = await getUtgBridgeConfig();
      final deviceVersion = config.devicePackageStatus.versionDisplay;
      if (deviceVersion != null && deviceVersion.trim().isNotEmpty) {
        merged['current_version'] = deviceVersion.trim();
        final latestVersion = merged['latest_version']?.toString().trim();
        if (latestVersion != null && latestVersion.isNotEmpty) {
          merged['update_available'] = latestVersion != deviceVersion.trim();
        }
      } else {
        merged['current_version'] = '';
        final latestVersion = merged['latest_version']?.toString().trim();
        if (latestVersion != null && latestVersion.isNotEmpty) {
          merged['update_available'] = true;
        }
      }
    } catch (_) {
      // ignore native config failure and fall back to provider payload
    }
    return UtgUpdateCheckResult.fromMap(merged);
  }

  /// 一键更新当前设备里的 OmniFlow 包。
  ///
  /// 注意：此方法直接调用 OOB 本地的 OmniFlowPackageManager，
  /// 不经过 Provider API；当前 provider 连接模式只影响提示和内置 Provider
  /// 是否需要自动重启。
  static Future<UtgUpdateApplyResult> applyUpdate({String? baseUrl}) async {
    final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
      'updateOmniFlowPackage',
    );
    return UtgUpdateApplyResult.fromMap(result);
  }

  static Future<Map<String, dynamic>> getUtgFunctionDetail({
    required String functionId,
    String? baseUrl,
  }) async {
    final decoded = await _requestUtgJson(
      method: 'GET',
      path: '/functions/${Uri.encodeComponent(functionId.trim())}',
      baseUrl: baseUrl,
    );
    final normalized = Map<String, dynamic>.from(decoded);
    if (normalized['success'] != true) {
      final errorMessage = normalized['error_message']?.toString().trim();
      throw Exception(
        errorMessage != null && errorMessage.isNotEmpty
            ? errorMessage
            : 'Failed to load function detail',
      );
    }
    return normalized;
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
      method: 'POST',
      path: '/functions/$functionId/update',
      baseUrl: baseUrl,
      payload: {if (description != null) 'description': description},
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
      payload: {if (hint != null) 'hint': hint},
    );
    return UtgFunctionSplitResult.fromMap(decoded);
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

  static Future<UtgFunctionMutationResult> registerOobReusableFunction({
    required Map<String, dynamic> functionSpec,
  }) async {
    final spec = _jsonSafeMap(functionSpec);
    final functionId = (spec['function_id'] ?? '').toString().trim();
    if (functionId.isEmpty) {
      throw Exception('function_id 为空，无法注册为 OOB API');
    }

    final result = await assistCore.invokeMethod(
      'registerOobReusableFunction',
      {'functionSpec': spec},
    );
    return UtgFunctionMutationResult.fromMap(_jsonSafeDynamicMap(result));
  }

  static Future<Map<String, dynamic>> convertInternalRunLogToOobFunction({
    required String runId,
    bool register = true,
    String? functionId,
    String? name,
    String? description,
  }) async {
    final normalizedRunId = runId.trim();
    if (normalizedRunId.isEmpty) {
      throw Exception('runId 为空，无法转换 RunLog');
    }
    final result = await assistCore
        .invokeMethod('convertInternalRunLogToOobFunction', {
          'runId': normalizedRunId,
          'register': register,
          if (functionId != null && functionId.trim().isNotEmpty)
            'functionId': functionId.trim(),
          if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
          if (description != null && description.trim().isNotEmpty)
            'description': description.trim(),
        });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> startHumanTrajectoryLearning({
    String? name,
    String? description,
    bool enableDebugScreenshots = false,
  }) async {
    final normalizedName = name?.trim() ?? '';
    final result = await assistCore
        .invokeMethod('startHumanTrajectoryLearning', {
          if (normalizedName.isNotEmpty) 'name': normalizedName,
          if (description != null && description.trim().isNotEmpty)
            'description': description.trim(),
          'enableDebugScreenshots': enableDebugScreenshots,
        });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> pauseHumanTrajectoryLearning() async {
    final result = await assistCore.invokeMethod(
      'pauseHumanTrajectoryLearning',
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> resumeHumanTrajectoryLearning() async {
    final result = await assistCore.invokeMethod(
      'resumeHumanTrajectoryLearning',
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> getHumanTrajectoryLearningStatus() async {
    final result = await assistCore.invokeMethod(
      'getHumanTrajectoryLearningStatus',
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> saveCurrentUdegState({
    String? goal,
  }) async {
    final result = await assistCore.invokeMethod('saveCurrentUdegState', {
      if (goal != null && goal.trim().isNotEmpty) 'goal': goal.trim(),
    });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> exportOobUdeg({int limit = 1000}) async {
    final result = await assistCore.invokeMethod('exportOobUdeg', {
      'limit': limit,
    });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> listOobReusableFunctions({
    int limit = 100,
    int offset = 0,
    bool autoRegister = true,
  }) async {
    final result = await assistCore.invokeMethod('listOobReusableFunctions', {
      'limit': limit,
      'offset': offset,
      'autoRegister': autoRegister,
    });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> deleteOobReusableFunction(
    String functionId,
  ) async {
    final normalized = functionId.trim();
    if (normalized.isEmpty) {
      return {'success': false, 'error': 'functionId is empty'};
    }
    final result = await assistCore.invokeMethod('deleteOobReusableFunction', {
      'functionId': normalized,
    });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>?> getOobReusableFunction(
    String functionId,
  ) async {
    final normalized = functionId.trim();
    if (normalized.isEmpty) {
      return null;
    }
    final result = await assistCore.invokeMethod('getOobReusableFunction', {
      'functionId': normalized,
    });
    if (result is! Map) {
      return null;
    }
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> getAgentToolFeatures() async {
    final result = await assistCore.invokeMethod<Map>('getAgentToolFeatures');
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> setAgentToolFeatures({
    bool? oobFunctionAsToolEnabled,
  }) async {
    final result = await assistCore.invokeMethod<Map>('setAgentToolFeatures', {
      if (oobFunctionAsToolEnabled != null)
        'oobFunctionAsToolEnabled': oobFunctionAsToolEnabled,
    });
    return _jsonSafeDynamicMap(result);
  }

  static Future<UtgManualRunResult> runOobReusableFunction({
    required String functionId,
    Map<String, dynamic> arguments = const {},
    int? conversationId,
    String? conversationMode,
    bool allowVlmFallback = false,
    Map<String, dynamic>? localReplayResult,
  }) async {
    final args = <String, dynamic>{
      'functionId': functionId.trim(),
      'arguments': _jsonSafeMap(arguments),
    };
    if (conversationId != null && conversationId > 0) {
      args['conversationId'] = conversationId;
    }
    if (conversationMode != null && conversationMode.trim().isNotEmpty) {
      args['conversationMode'] = conversationMode.trim();
    }
    if (allowVlmFallback) {
      args['allowVlmFallback'] = true;
    }
    if (localReplayResult != null && localReplayResult.isNotEmpty) {
      args['localReplayResult'] = _jsonSafeMap(localReplayResult);
    }
    final result = await assistCore.invokeMethod('runOobReusableFunction', {
      ...args,
    });
    return UtgManualRunResult.fromMap(_jsonSafeDynamicMap(result));
  }

  static Map<String, dynamic> _jsonSafeMap(Map<String, dynamic> value) {
    final safe = _jsonSafeValue(value);
    if (safe is Map<String, dynamic>) {
      return safe;
    }
    if (safe is Map) {
      return safe.map((key, item) => MapEntry(key.toString(), item));
    }
    return <String, dynamic>{};
  }

  static Map<String, dynamic> _jsonSafeDynamicMap(dynamic value) {
    if (value is Map<String, dynamic>) {
      return _jsonSafeMap(value);
    }
    if (value is Map) {
      return _jsonSafeMap(
        value.map((key, item) => MapEntry(key.toString(), item)),
      );
    }
    return <String, dynamic>{};
  }

  static dynamic _jsonSafeValue(dynamic value) {
    if (value == null || value is String || value is num || value is bool) {
      return value;
    }
    if (value is Map) {
      return value.map(
        (key, item) => MapEntry(key.toString(), _jsonSafeValue(item)),
      );
    }
    if (value is Iterable) {
      return value.map(_jsonSafeValue).toList();
    }
    return value.toString();
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

  static bool isVlmManualTakeoverPrompt(String? question) {
    final normalized = question?.trim().toLowerCase() ?? '';
    if (normalized.isEmpty) return false;
    return normalized.contains('已接管控制') ||
        normalized.contains('用户已接管') ||
        (normalized.contains('takeover') && normalized.contains('continue')) ||
        (normalized.contains('taken over') && normalized.contains('continue'));
  }

  static Future<bool> continueVLMTaskPrompt({
    required String? question,
    required String userInput,
  }) {
    if (isVlmManualTakeoverPrompt(question)) {
      return resumeVLMTask();
    }
    return provideUserInputToVLMTask(userInput);
  }

  static Future<bool> pauseVLMTask() async {
    try {
      final result = await assistCore.invokeMethod<bool>('pauseVLMTask');
      return result == true;
    } on PlatformException catch (e) {
      print('暂停VLM任务失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> resumeVLMTask() async {
    try {
      final result = await assistCore.invokeMethod<bool>('resumeVLMTask');
      return result == true;
    } on PlatformException catch (e) {
      print('恢复VLM任务失败: ${e.message}');
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

  /// 停止并清空统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<bool> deleteAllAgentExactAlarms() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteAgentExactAlarm',
        {'alarmId': ''},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      print('清空应用内闹钟失败: ${e.message}');
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
    bool responseJsonObject = false,
  }) async {
    try {
      final result = await assistCore.invokeMethod<String>('postLLMChat', {
        'text': text,
        'model': model,
        'responseJsonObject': responseJsonObject,
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
    String? toolProfile,
    List<String> allowedTools = const [],
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
      if (toolProfile != null && toolProfile.trim().isNotEmpty) {
        args['toolProfile'] = toolProfile.trim();
      }
      if (allowedTools.isNotEmpty) {
        args['allowedTools'] = allowedTools
            .map((tool) => tool.trim())
            .where((tool) => tool.isNotEmpty)
            .toList(growable: false);
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

  static Future<Map<String, dynamic>?> captureWorkbenchAnnotationAttachment({
    required double canvasWidth,
    required double canvasHeight,
    required List<Map<String, dynamic>> drawingPaths,
    String source = 'xiaowan_floating_annotation_canvas',
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'captureWorkbenchAnnotationAttachment',
        {
          'canvasWidth': canvasWidth,
          'canvasHeight': canvasHeight,
          'drawingPaths': drawingPaths,
          'source': source,
        },
      );
      if (result == null) return null;
      return result.map((key, value) => MapEntry(key.toString(), value));
    } on PlatformException catch (e) {
      print('捕获 Workbench 标注截图失败: ${e.message}');
      return null;
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

  static Future<Map<String, dynamic>?> syncOfficialAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSyncOfficialRepository',
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('同步官方 Agent skills 失败: ${e.message}');
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

  static Future<bool> setPreventScreenSleepDuringTasksEnabled(
    bool enabled,
  ) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setPreventScreenSleepDuringTasksEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('Failed to sync prevent sleep setting: ${e.message}');
      return false;
    }
  }

  static Future<bool> setTaskCompletionNotificationEnabled(bool enabled) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setTaskCompletionNotificationEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print(
        'Failed to sync task completion notification setting: ${e.message}',
      );
      return false;
    }
  }

  static Future<bool> setVisibleChatConversation({
    int? conversationId,
    String? conversationMode,
    bool visible = true,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<String>('setVisibleChatConversation', {
            'conversationId': conversationId ?? 0,
            'visible': visible,
            if (conversationMode != null) 'mode': conversationMode,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('Failed to sync visible chat conversation: ${e.message}');
      return false;
    }
  }

  static Future<bool> showTaskCompletionNotification({
    required String title,
    required String message,
    int? conversationId,
    String? conversationMode,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<String>('showTaskCompletionNotification', {
            'title': title,
            'message': message,
            if (conversationId != null) 'conversationId': conversationId,
            if (conversationMode != null) 'conversationMode': conversationMode,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('Failed to show task completion notification: ${e.message}');
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
}
