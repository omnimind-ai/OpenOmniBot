import 'dart:async';

import 'package:flutter/services.dart';

const String _llamaCppBackend = 'llama.cpp';
const String _omniinferMnnBackend = 'omniinfer-mnn';
const String _omniinferQnnBackend = 'executorch-qnn';
const String _omniinferLiteRtBackend = 'litert';

String _normalizeInferenceBackend(Object? raw) {
  final value = (raw ?? '').toString().trim();
  switch (value) {
    case _llamaCppBackend:
      return _llamaCppBackend;
    case 'mnn':
    case _omniinferMnnBackend:
      return _omniinferMnnBackend;
    case 'qnn':
    case _omniinferQnnBackend:
      return _omniinferQnnBackend;
    case 'litert':
    case 'litert-lm':
    case 'litertlm':
      return _omniinferLiteRtBackend;
    default:
      return _llamaCppBackend;
  }
}

class MnnLocalDownloadInfo {
  final int state;
  final String stateLabel;
  final double progress;
  final int savedSize;
  final int totalSize;
  final String speedInfo;
  final String errorMessage;
  final String progressStage;
  final String currentFile;
  final bool hasUpdate;

  const MnnLocalDownloadInfo({
    required this.state,
    required this.stateLabel,
    required this.progress,
    required this.savedSize,
    required this.totalSize,
    required this.speedInfo,
    required this.errorMessage,
    required this.progressStage,
    required this.currentFile,
    required this.hasUpdate,
  });

  factory MnnLocalDownloadInfo.fromMap(Map<dynamic, dynamic>? map) {
    return MnnLocalDownloadInfo(
      state: (map?['state'] as num?)?.toInt() ?? 0,
      stateLabel: (map?['stateLabel'] ?? 'not_started').toString(),
      progress: (map?['progress'] as num?)?.toDouble() ?? 0,
      savedSize: (map?['savedSize'] as num?)?.toInt() ?? 0,
      totalSize: (map?['totalSize'] as num?)?.toInt() ?? 0,
      speedInfo: (map?['speedInfo'] ?? '').toString(),
      errorMessage: (map?['errorMessage'] ?? '').toString(),
      progressStage: (map?['progressStage'] ?? '').toString(),
      currentFile: (map?['currentFile'] ?? '').toString(),
      hasUpdate: map?['hasUpdate'] == true,
    );
  }

  bool get isDownloading =>
      stateLabel == 'downloading' || stateLabel == 'preparing';
  bool get isCompleted => stateLabel == 'completed';
  bool get isPaused => stateLabel == 'paused';
}

class MnnLocalModel {
  final String id;
  final String name;
  final String category;
  final String source;
  final String description;
  final String path;
  final String vendor;
  final List<String> tags;
  final List<String> extraTags;
  final bool active;
  final bool isLocal;
  final bool isPinned;
  final bool hasUpdate;
  final int fileSize;
  final double sizeB;
  final String formattedSize;
  final int lastUsedAt;
  final int downloadedAt;
  final bool readOnly;
  final MnnLocalDownloadInfo? download;

  const MnnLocalModel({
    required this.id,
    required this.name,
    required this.category,
    required this.source,
    required this.description,
    required this.path,
    required this.vendor,
    required this.tags,
    required this.extraTags,
    required this.active,
    required this.isLocal,
    required this.isPinned,
    required this.hasUpdate,
    required this.fileSize,
    required this.sizeB,
    required this.formattedSize,
    required this.lastUsedAt,
    required this.downloadedAt,
    required this.readOnly,
    this.download,
  });

  factory MnnLocalModel.fromMap(Map<dynamic, dynamic>? map) {
    return MnnLocalModel(
      id: (map?['id'] ?? '').toString(),
      name: (map?['name'] ?? '').toString(),
      category: (map?['category'] ?? 'llm').toString(),
      source: (map?['source'] ?? '').toString(),
      description: (map?['description'] ?? '').toString(),
      path: (map?['path'] ?? '').toString(),
      vendor: (map?['vendor'] ?? '').toString(),
      tags: ((map?['tags'] as List?) ?? const [])
          .map((item) => item.toString())
          .toList(),
      extraTags: ((map?['extraTags'] as List?) ?? const [])
          .map((item) => item.toString())
          .toList(),
      active: map?['active'] == true,
      isLocal: map?['isLocal'] == true,
      isPinned: map?['isPinned'] == true,
      hasUpdate: map?['hasUpdate'] == true,
      fileSize: (map?['fileSize'] as num?)?.toInt() ?? 0,
      sizeB: (map?['sizeB'] as num?)?.toDouble() ?? 0,
      formattedSize: (map?['formattedSize'] ?? '').toString(),
      lastUsedAt: (map?['lastUsedAt'] as num?)?.toInt() ?? 0,
      downloadedAt: (map?['downloadedAt'] as num?)?.toInt() ?? 0,
      readOnly: map?['readOnly'] == true,
      download: map?['download'] is Map
          ? MnnLocalDownloadInfo.fromMap(map?['download'] as Map?)
          : null,
    );
  }
}

class MnnLocalConfig {
  final String backend;
  final bool autoStartOnAppOpen;
  final bool apiRunning;
  final bool apiReady;
  final String apiState;
  final String apiHost;
  final int apiPort;
  final String baseUrl;
  final String activeModelId;
  final String downloadProvider;
  final List<String> availableSources;
  final String loadedBackend;
  final String loadedModelId;

  const MnnLocalConfig({
    required this.backend,
    required this.autoStartOnAppOpen,
    required this.apiRunning,
    required this.apiReady,
    required this.apiState,
    required this.apiHost,
    required this.apiPort,
    required this.baseUrl,
    required this.activeModelId,
    required this.downloadProvider,
    required this.availableSources,
    required this.loadedBackend,
    required this.loadedModelId,
  });

  factory MnnLocalConfig.fromMap(Map<dynamic, dynamic>? map) {
    return MnnLocalConfig(
      backend: _normalizeInferenceBackend(map?['backend']),
      autoStartOnAppOpen: map?['autoStartOnAppOpen'] == true,
      apiRunning: map?['apiRunning'] == true,
      apiReady: map?['apiReady'] == true,
      apiState: (map?['apiState'] ?? 'stopped').toString(),
      apiHost: (map?['apiHost'] ?? '127.0.0.1').toString(),
      apiPort: (map?['apiPort'] as num?)?.toInt() ?? 9099,
      baseUrl: (map?['baseUrl'] ?? '').toString(),
      activeModelId: (map?['activeModelId'] ?? '').toString(),
      downloadProvider: (map?['downloadProvider'] ?? 'ModelScope').toString(),
      availableSources: ((map?['availableSources'] as List?) ?? const [])
          .map((item) => item.toString())
          .toList(),
      loadedBackend: _normalizeInferenceBackend(map?['loadedBackend']),
      loadedModelId: (map?['loadedModelId'] ?? '').toString(),
    );
  }
}

class MnnLocalMarketPayload {
  final String source;
  final String category;
  final List<String> availableSources;
  final List<MnnLocalModel> models;

  const MnnLocalMarketPayload({
    required this.source,
    required this.category,
    required this.availableSources,
    required this.models,
  });

  factory MnnLocalMarketPayload.fromMap(Map<dynamic, dynamic>? map) {
    return MnnLocalMarketPayload(
      source: (map?['source'] ?? '').toString(),
      category: (map?['category'] ?? 'llm').toString(),
      availableSources: ((map?['availableSources'] as List?) ?? const [])
          .map((item) => item.toString())
          .toList(),
      models: ((map?['models'] as List?) ?? const [])
          .map((item) => MnnLocalModel.fromMap(item as Map?))
          .toList(),
    );
  }
}

class MnnLocalOverviewPayload {
  final MnnLocalConfig config;
  final List<MnnLocalModel> installedModels;
  final MnnLocalMarketPayload market;

  const MnnLocalOverviewPayload({
    required this.config,
    required this.installedModels,
    required this.market,
  });

  factory MnnLocalOverviewPayload.fromMap(Map<dynamic, dynamic>? map) {
    return MnnLocalOverviewPayload(
      config: MnnLocalConfig.fromMap(map?['config'] as Map?),
      installedModels: ((map?['installedModels'] as List?) ?? const [])
          .map((item) => MnnLocalModel.fromMap(item as Map?))
          .toList(),
      market: MnnLocalMarketPayload.fromMap(map?['market'] as Map?),
    );
  }
}

class MnnLocalEvent {
  final String type;
  final Map<String, dynamic> payload;

  const MnnLocalEvent({required this.type, required this.payload});

  factory MnnLocalEvent.fromDynamic(dynamic raw) {
    final map = (raw as Map?)?.cast<dynamic, dynamic>() ?? const {};
    return MnnLocalEvent(
      type: (map['type'] ?? '').toString(),
      payload: map.map((key, value) => MapEntry(key.toString(), value)),
    );
  }
}

class MnnLocalModelsService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/MnnLocalModels',
  );
  static const EventChannel _events = EventChannel(
    'cn.com.omnimind.bot/MnnLocalModelsEvents',
  );

  static Stream<MnnLocalEvent> get eventStream =>
      _events.receiveBroadcastStream().map(MnnLocalEvent.fromDynamic);

  static Future<MnnLocalOverviewPayload> getOverview({
    String installedQuery = '',
    String marketQuery = '',
    String marketCategory = 'llm',
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getOverview',
      {
        'installedQuery': installedQuery,
        'marketQuery': marketQuery,
        'marketCategory': marketCategory.trim().toLowerCase(),
      },
    );
    return MnnLocalOverviewPayload.fromMap(result);
  }

  static Future<List<MnnLocalModel>> listInstalledModels({
    String query = '',
    String category = 'all',
  }) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'listInstalledModels',
      {'query': query, 'category': category},
    );
    return (result ?? const [])
        .map((item) => MnnLocalModel.fromMap(item as Map?))
        .toList();
  }

  static Future<List<MnnLocalModel>> refreshInstalledModels() async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'refreshInstalledModels',
    );
    return (result ?? const [])
        .map((item) => MnnLocalModel.fromMap(item as Map?))
        .toList();
  }

  static Future<MnnLocalMarketPayload> listMarketModels({
    String query = '',
    String category = 'llm',
    bool refresh = false,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'listMarketModels',
      {
        'query': query,
        'category': category.trim().toLowerCase(),
        'refresh': refresh,
      },
    );
    return MnnLocalMarketPayload.fromMap(result);
  }

  static Future<MnnLocalConfig> getConfig() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getConfig',
    );
    return MnnLocalConfig.fromMap(result);
  }

  static Future<MnnLocalConfig> saveConfig({
    bool? autoStartOnAppOpen,
    int? apiPort,
    String? activeModelId,
    String? downloadProvider,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'saveConfig',
      {
        if (autoStartOnAppOpen != null)
          'autoStartOnAppOpen': autoStartOnAppOpen,
        if (apiPort != null) 'apiPort': apiPort,
        if (activeModelId != null) 'activeModelId': activeModelId,
        if (downloadProvider != null) 'downloadProvider': downloadProvider,
      },
    );
    return MnnLocalConfig.fromMap(result);
  }

  static Future<MnnLocalConfig> setActiveModel(String? modelId) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'setActiveModel',
      {'modelId': modelId},
    );
    return MnnLocalConfig.fromMap(result);
  }

  static Future<MnnLocalConfig> startApiService({String? modelId}) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'startApiService',
      {'modelId': modelId},
    );
    return MnnLocalConfig.fromMap(result);
  }

  /// Eagerly preload a model (backend-agnostic).
  /// Cancels any previous in-progress preload.
  /// Returns a map with keys: success (bool), modelId (String),
  /// cancelled (bool, optional), error (String, optional).
  static Future<Map<dynamic, dynamic>?> preloadModel({
    required String modelId,
  }) {
    return _channel.invokeMethod<Map<dynamic, dynamic>>(
      'preloadModel',
      {'modelId': modelId},
    );
  }

  static Future<MnnLocalConfig> stopApiService() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'stopApiService',
    );
    return MnnLocalConfig.fromMap(result);
  }

  static Future<void> startDownload(String modelId) {
    return _channel.invokeMethod('startDownload', {'modelId': modelId});
  }

  static Future<void> pauseDownload(String modelId) {
    return _channel.invokeMethod('pauseDownload', {'modelId': modelId});
  }

  static Future<List<MnnLocalModel>> deleteModel(String modelId) async {
    final result = await _channel.invokeMethod<List<dynamic>>('deleteModel', {
      'modelId': modelId,
    });
    return (result ?? const [])
        .map((item) => MnnLocalModel.fromMap(item as Map?))
        .toList();
  }

  static Future<String> getBackend() async {
    final result = await _channel.invokeMethod<String>('getBackend');
    return _normalizeInferenceBackend(result);
  }

  static Future<String> setBackend(String backend) async {
    final result = await _channel.invokeMethod<String>(
      'setBackend',
      {'backend': backend},
    );
    return _normalizeInferenceBackend(result ?? backend);
  }

  static Future<Map<String, dynamic>> importModel() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'importModel',
    );
    return (result ?? {}).cast<String, dynamic>();
  }
}
