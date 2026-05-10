import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';

class ModelProviderConfig {
  final String id;
  final String name;
  final String baseUrl;
  final String apiKey;
  final String source;
  final String providerType;
  final bool readOnly;
  final bool ready;
  final String statusText;
  final bool configured;

  const ModelProviderConfig({
    required this.id,
    required this.name,
    required this.baseUrl,
    required this.apiKey,
    required this.source,
    required this.providerType,
    required this.readOnly,
    required this.ready,
    required this.statusText,
    required this.configured,
  });

  factory ModelProviderConfig.empty() {
    return const ModelProviderConfig(
      id: '',
      name: '',
      baseUrl: '',
      apiKey: '',
      source: 'none',
      providerType: 'custom',
      readOnly: false,
      ready: false,
      statusText: '',
      configured: false,
    );
  }

  factory ModelProviderConfig.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) {
      return ModelProviderConfig.empty();
    }
    return ModelProviderConfig(
      id: (map['id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      baseUrl: (map['baseUrl'] ?? '').toString(),
      apiKey: (map['apiKey'] ?? '').toString(),
      source: (map['source'] ?? 'none').toString(),
      providerType: (map['providerType'] ?? 'custom').toString(),
      readOnly: map['readOnly'] == true,
      ready: map['ready'] == true,
      statusText: (map['statusText'] ?? '').toString(),
      configured: map['configured'] == true,
    );
  }
}

class ModelProviderProfileSummary {
  final String id;
  final String name;
  final String baseUrl;
  final String apiKey;
  final String sourceType;
  final bool readOnly;
  final bool ready;
  final String statusText;
  final bool configured;
  final String protocolType;

  const ModelProviderProfileSummary({
    required this.id,
    required this.name,
    required this.baseUrl,
    required this.apiKey,
    required this.sourceType,
    required this.readOnly,
    required this.ready,
    required this.statusText,
    required this.configured,
    this.protocolType = 'openai_compatible',
  });

  factory ModelProviderProfileSummary.fromMap(Map<dynamic, dynamic>? map) {
    return ModelProviderProfileSummary(
      id: (map?['id'] ?? '').toString(),
      name: (map?['name'] ?? '').toString(),
      baseUrl: (map?['baseUrl'] ?? '').toString(),
      apiKey: (map?['apiKey'] ?? '').toString(),
      sourceType: (map?['sourceType'] ?? 'custom').toString(),
      readOnly: map?['readOnly'] == true,
      ready: map?['ready'] == true,
      statusText: (map?['statusText'] ?? '').toString(),
      configured: map?['configured'] == true,
      protocolType: (map?['protocolType'] ?? 'openai_compatible').toString(),
    );
  }

  ModelProviderConfig toConfig({String source = 'profile'}) {
    return ModelProviderConfig(
      id: id,
      name: name,
      baseUrl: baseUrl,
      apiKey: apiKey,
      source: source,
      providerType: sourceType,
      readOnly: readOnly,
      ready: ready,
      statusText: statusText,
      configured: configured,
    );
  }
}

class ModelProviderProfilesPayload {
  final List<ModelProviderProfileSummary> profiles;
  final String editingProfileId;

  const ModelProviderProfilesPayload({
    required this.profiles,
    required this.editingProfileId,
  });

  factory ModelProviderProfilesPayload.fromMap(Map<dynamic, dynamic>? map) {
    final profiles = ((map?['profiles'] as List?) ?? const [])
        .map((item) => ModelProviderProfileSummary.fromMap(item as Map?))
        .where((item) => item.id.isNotEmpty)
        .toList();
    final editingProfileId = (map?['editingProfileId'] ?? '').toString();
    return ModelProviderProfilesPayload(
      profiles: profiles,
      editingProfileId: editingProfileId,
    );
  }
}

class ProviderModelOption {
  final String id;
  final String displayName;
  final String? ownedBy;
  final int? contextLimit;
  final int? inputLimit;
  final int? outputLimit;
  final List<String> inputModalities;
  final List<String> outputModalities;
  final String? modelsDevProviderId;
  final String? modelsDevProviderName;
  final String? providerLogoUrl;
  final String? family;
  final String? group;
  final bool? attachment;
  final bool? reasoning;
  final bool? toolCall;
  final bool? structuredOutput;
  final bool? temperature;

  const ProviderModelOption({
    required this.id,
    required this.displayName,
    this.ownedBy,
    this.contextLimit,
    this.inputLimit,
    this.outputLimit,
    this.inputModalities = const [],
    this.outputModalities = const [],
    this.modelsDevProviderId,
    this.modelsDevProviderName,
    this.providerLogoUrl,
    this.family,
    this.group,
    this.attachment,
    this.reasoning,
    this.toolCall,
    this.structuredOutput,
    this.temperature,
  });

  factory ProviderModelOption.fromMap(Map<dynamic, dynamic>? map) {
    return ProviderModelOption(
      id: (map?['id'] ?? '').toString(),
      displayName: (map?['displayName'] ?? map?['id'] ?? '').toString(),
      ownedBy: map?['ownedBy']?.toString(),
      contextLimit: _readInt(map?['contextLimit']),
      inputLimit: _readInt(map?['inputLimit']),
      outputLimit: _readInt(map?['outputLimit']),
      inputModalities: _readStringList(map?['inputModalities']),
      outputModalities: _readStringList(map?['outputModalities']),
      modelsDevProviderId: _readNonEmptyString(map?['modelsDevProviderId']),
      modelsDevProviderName: _readNonEmptyString(map?['modelsDevProviderName']),
      providerLogoUrl: _readNonEmptyString(map?['providerLogoUrl']),
      family: _readNonEmptyString(map?['family']),
      group: _readNonEmptyString(map?['group']),
      attachment: _readBool(map?['attachment']),
      reasoning: _readBool(map?['reasoning']),
      toolCall: _readBool(map?['toolCall']),
      structuredOutput: _readBool(map?['structuredOutput']),
      temperature: _readBool(map?['temperature']),
    );
  }

  ProviderModelOption copyWith({
    String? id,
    String? displayName,
    String? ownedBy,
    int? contextLimit,
    int? inputLimit,
    int? outputLimit,
    List<String>? inputModalities,
    List<String>? outputModalities,
    String? modelsDevProviderId,
    String? modelsDevProviderName,
    String? providerLogoUrl,
    String? family,
    String? group,
    bool? attachment,
    bool? reasoning,
    bool? toolCall,
    bool? structuredOutput,
    bool? temperature,
  }) {
    return ProviderModelOption(
      id: id ?? this.id,
      displayName: displayName ?? this.displayName,
      ownedBy: ownedBy ?? this.ownedBy,
      contextLimit: contextLimit ?? this.contextLimit,
      inputLimit: inputLimit ?? this.inputLimit,
      outputLimit: outputLimit ?? this.outputLimit,
      inputModalities: inputModalities ?? this.inputModalities,
      outputModalities: outputModalities ?? this.outputModalities,
      modelsDevProviderId: modelsDevProviderId ?? this.modelsDevProviderId,
      modelsDevProviderName:
          modelsDevProviderName ?? this.modelsDevProviderName,
      providerLogoUrl: providerLogoUrl ?? this.providerLogoUrl,
      family: family ?? this.family,
      group: group ?? this.group,
      attachment: attachment ?? this.attachment,
      reasoning: reasoning ?? this.reasoning,
      toolCall: toolCall ?? this.toolCall,
      structuredOutput: structuredOutput ?? this.structuredOutput,
      temperature: temperature ?? this.temperature,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'displayName': displayName,
      'ownedBy': ownedBy,
      'contextLimit': contextLimit,
      'inputLimit': inputLimit,
      'outputLimit': outputLimit,
      'inputModalities': inputModalities,
      'outputModalities': outputModalities,
      'modelsDevProviderId': modelsDevProviderId,
      'modelsDevProviderName': modelsDevProviderName,
      'providerLogoUrl': providerLogoUrl,
      'family': family,
      'group': group,
      'attachment': attachment,
      'reasoning': reasoning,
      'toolCall': toolCall,
      'structuredOutput': structuredOutput,
      'temperature': temperature,
    };
  }

  static int? _readInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static String? _readNonEmptyString(Object? value) {
    final normalized = value?.toString().trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  static List<String> _readStringList(Object? value) {
    if (value is List) {
      return value
          .map((item) => item.toString().trim().toLowerCase())
          .where((item) => item.isNotEmpty)
          .toSet()
          .toList();
    }
    final raw = value?.toString().trim() ?? '';
    if (raw.isEmpty) return const [];
    return raw
        .split(',')
        .map((item) => item.trim().toLowerCase())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList();
  }

  static bool? _readBool(Object? value) {
    if (value is bool) return value;
    final normalized = value?.toString().trim().toLowerCase() ?? '';
    if (normalized == 'true') return true;
    if (normalized == 'false') return false;
    return null;
  }
}

class ProviderModelGroup {
  final ModelProviderProfileSummary profile;
  final List<ProviderModelOption> models;

  const ProviderModelGroup({required this.profile, required this.models});
}

class ModelProviderConfigService {
  static const String _kBuiltinOmniInferProfileId = 'omniinfer-local';
  static const String _kLegacyBuiltinMnnLocalProfileId = 'mnn-local';
  static const String _kManualModelIdsKey = 'manual_provider_model_ids_v2';
  static const String _kCachedFetchedModelsKey =
      'cached_provider_models_with_base_v2';
  static const String _kLegacyManualModelIdsKey =
      'manual_provider_model_ids_v1';
  static const String _kLegacyCachedFetchedModelsKey =
      'cached_provider_models_with_base_v1';
  static const String _kDirectRequestUrlMarker = '#';
  static const List<String> _kCanonicalEndpointSuffixes = <String>[
    '/v1/chat/completions',
    '/chat/completions',
    '/v1/models',
    '/models',
    '/v1/messages',
    '/messages',
  ];

  static bool _isBuiltinLocalProfileId(String profileId) {
    final normalized = profileId.trim();
    return normalized == _kBuiltinOmniInferProfileId ||
        normalized == _kLegacyBuiltinMnnLocalProfileId;
  }

  static String _canonicalProfileId(String profileId) {
    final normalized = profileId.trim();
    if (_isBuiltinLocalProfileId(normalized)) {
      return _kBuiltinOmniInferProfileId;
    }
    return normalized;
  }

  static Future<ModelProviderConfig> getConfig() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<Map<dynamic, dynamic>>('getModelProviderConfig');
      return ModelProviderConfig.fromMap(result);
    } on PlatformException {
      return ModelProviderConfig.empty();
    }
  }

  static Future<ModelProviderProfilesPayload> listProfiles() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<Map<dynamic, dynamic>>('listModelProviderProfiles');
      return ModelProviderProfilesPayload.fromMap(result);
    } on PlatformException {
      final fallback = await getConfig();
      final profile = ModelProviderProfileSummary(
        id: fallback.id.isNotEmpty ? fallback.id : 'profile-1',
        name: fallback.name.isNotEmpty ? fallback.name : 'Provider 1',
        baseUrl: fallback.baseUrl,
        apiKey: fallback.apiKey,
        sourceType: fallback.providerType,
        readOnly: fallback.readOnly,
        ready: fallback.ready,
        statusText: fallback.statusText,
        configured: fallback.configured,
      );
      return ModelProviderProfilesPayload(
        profiles: [profile],
        editingProfileId: profile.id,
      );
    }
  }

  static Future<ModelProviderProfileSummary> saveProfile({
    String? id,
    required String name,
    required String baseUrl,
    required String apiKey,
    String protocolType = 'openai_compatible',
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveModelProviderProfile', {
          if (id != null && id.trim().isNotEmpty) 'id': id.trim(),
          'name': name,
          'baseUrl': baseUrl,
          'apiKey': apiKey,
          'protocolType': protocolType,
        });
    return ModelProviderProfileSummary.fromMap(result);
  }

  static Future<ModelProviderProfilesPayload> deleteProfile(
    String profileId,
  ) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('deleteModelProviderProfile', {
          'profileId': profileId,
        });
    return ModelProviderProfilesPayload.fromMap(result);
  }

  static Future<ModelProviderProfileSummary> setEditingProfile(
    String profileId,
  ) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('setEditingModelProviderProfile', {
          'profileId': profileId,
        });
    return ModelProviderProfileSummary.fromMap(result);
  }

  static Future<ModelProviderConfig> saveConfig({
    required String baseUrl,
    required String apiKey,
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveModelProviderConfig', {
          'baseUrl': baseUrl,
          'apiKey': apiKey,
        });
    return ModelProviderConfig.fromMap(result);
  }

  static Future<ModelProviderConfig> clearConfig() async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('clearModelProviderConfig');
    return ModelProviderConfig.fromMap(result);
  }

  static Future<String?> resolveProviderLogoUrl({
    String providerId = '',
    String providerName = '',
    String apiBase = '',
  }) async {
    final provider = await ModelsDevCatalogService.resolveProvider(
      providerId: providerId,
      providerName: providerName,
      apiBase: apiBase,
    );
    return provider?.logoUrl;
  }

  static Future<List<ProviderModelOption>> enrichModelsForProfile({
    required String profileId,
    required String providerName,
    required String apiBase,
    required List<ProviderModelOption> models,
  }) async {
    if (models.isEmpty) {
      return const [];
    }
    var catalog = const ModelsDevCatalog(providers: {});
    ModelsDevProviderEntry? provider;
    try {
      catalog = await ModelsDevCatalogService.loadCatalog();
      provider = ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        providerId: profileId,
        providerName: providerName,
        apiBase: apiBase,
      );
    } catch (_) {
      catalog = const ModelsDevCatalog(providers: {});
      provider = null;
    }
    final providerGroupId =
        provider?.id ??
        (providerName.trim().isNotEmpty ? providerName.trim() : profileId);
    return models
        .map(
          (item) => _enrichModelOption(
            item: item,
            catalog: catalog,
            provider: provider,
            providerGroupId: providerGroupId,
          ),
        )
        .toList();
  }

  static ProviderModelOption _enrichModelOption({
    required ProviderModelOption item,
    required ModelsDevCatalog catalog,
    required ModelsDevProviderEntry? provider,
    required String providerGroupId,
  }) {
    final modelMatch = catalog.isEmpty
        ? null
        : ModelsDevCatalogService.matchModelMetadata(
            catalog: catalog,
            provider: provider,
            modelId: item.id,
          );
    final metadata = modelMatch?.metadata;
    final metadataProvider = modelMatch?.provider ?? provider;
    final metadataDisplayName = metadata?.name.trim() ?? '';
    final shouldUseMetadataName =
        item.displayName.trim().isEmpty || item.displayName.trim() == item.id;
    final metadataProviderGroupId = metadataProvider?.id ?? providerGroupId;
    return item.copyWith(
      displayName: shouldUseMetadataName && metadataDisplayName.isNotEmpty
          ? metadataDisplayName
          : item.displayName,
      contextLimit: metadata?.contextLimit,
      inputLimit: metadata?.inputLimit,
      outputLimit: metadata?.outputLimit,
      inputModalities: metadata?.inputModalities.isNotEmpty == true
          ? metadata!.inputModalities
          : item.inputModalities,
      outputModalities: metadata?.outputModalities.isNotEmpty == true
          ? metadata!.outputModalities
          : item.outputModalities,
      modelsDevProviderId: metadataProvider?.id,
      modelsDevProviderName: metadataProvider?.name,
      providerLogoUrl: metadataProvider?.logoUrl,
      family: metadata?.family,
      attachment: metadata?.attachment,
      reasoning: metadata?.reasoning,
      toolCall: metadata?.toolCall,
      structuredOutput: metadata?.structuredOutput,
      temperature: metadata?.temperature,
      group: ModelsDevCatalogService.groupModelId(
        item.id,
        providerId: metadataProviderGroupId,
      ),
    );
  }

  static Future<List<ProviderModelOption>> fetchModels({
    String apiBase = '',
    String apiKey = '',
    String? profileId,
    String providerName = '',
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<List<dynamic>>('fetchProviderModels', {
          'apiBase': apiBase,
          'apiKey': apiKey,
          if (profileId != null && profileId.trim().isNotEmpty)
            'profileId': profileId.trim(),
        });
    final models = (result ?? const [])
        .map((item) => ProviderModelOption.fromMap(item as Map?))
        .where((item) => item.id.isNotEmpty)
        .toList();

    final targetProfileId = await _resolveProfileId(profileId);
    var cacheBase = normalizeApiBase(apiBase) ?? '';
    if (cacheBase.isEmpty) {
      final config = await getConfig();
      cacheBase = config.baseUrl;
    }
    var resolvedProviderName = providerName.trim();
    if (resolvedProviderName.isEmpty && targetProfileId != null) {
      final profile = await _findProfileById(targetProfileId);
      resolvedProviderName = profile?.name ?? '';
    }
    final enrichedModels = await enrichModelsForProfile(
      profileId: targetProfileId ?? '',
      providerName: resolvedProviderName,
      apiBase: cacheBase,
      models: models,
    );
    if (targetProfileId != null) {
      try {
        await _saveCachedFetchedModels(
          profileId: targetProfileId,
          apiBase: cacheBase,
          models: enrichedModels,
        );
      } catch (_) {
        // ignore cache write failures
      }
    }

    return enrichedModels;
  }

  static Future<List<ProviderModelOption>> getCachedFetchedModels({
    required String profileId,
    String apiBase = '',
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final raw = StorageService.getString(
      _kCachedFetchedModelsKey,
      defaultValue: '',
    );
    if (raw == null || raw.trim().isEmpty) {
      return const [];
    }

    final requestedBase = normalizeApiBase(apiBase) ?? '';
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) {
        return const [];
      }
      final bucket = decoded[normalizedProfileId];
      if (bucket is! Map<String, dynamic>) {
        return const [];
      }
      final cacheBase = (bucket['apiBase'] ?? '').toString();
      if (requestedBase.isNotEmpty && cacheBase != requestedBase) {
        return const [];
      }
      final modelsRaw = bucket['models'];
      if (modelsRaw is! List) {
        return const [];
      }
      return modelsRaw
          .map((item) => ProviderModelOption.fromMap(item as Map?))
          .where((item) => item.id.isNotEmpty)
          .toList();
    } catch (_) {
      return const [];
    }
  }

  static Future<void> saveCachedFetchedModels({
    required String profileId,
    required String apiBase,
    required List<ProviderModelOption> models,
  }) async {
    await _saveCachedFetchedModels(
      profileId: profileId,
      apiBase: apiBase,
      models: models,
    );
  }

  static Future<void> _saveCachedFetchedModels({
    required String profileId,
    required String apiBase,
    required List<ProviderModelOption> models,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kCachedFetchedModelsKey);
    final normalizedBase = normalizeApiBase(apiBase) ?? '';
    current[normalizedProfileId] = {
      'apiBase': normalizedBase,
      'models': models.map((item) => item.toMap()).toList(),
    };
    await StorageService.setString(
      _kCachedFetchedModelsKey,
      jsonEncode(current),
    );
  }

  static Future<List<String>> getManualModelIds({
    required String profileId,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kManualModelIdsKey);
    final rawIds = (current[normalizedProfileId] as List?)
        ?.map((item) => item.toString())
        .toList();
    return _normalizeModelIds(rawIds ?? const []);
  }

  static Future<void> saveManualModelIds({
    required String profileId,
    required List<String> ids,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kManualModelIdsKey);
    current[normalizedProfileId] = _normalizeModelIds(ids);
    await StorageService.setString(_kManualModelIdsKey, jsonEncode(current));
  }

  static Future<List<ProviderModelOption>> getStoredModelOptionsForProfile(
    String profileId, {
    ModelProviderProfileSummary? profile,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    final resolvedProfile = profile ?? await _findProfileById(profileId);
    final manualModelIds = await getManualModelIds(
      profileId: normalizedProfileId,
    );
    List<ProviderModelOption> remoteModels;
    if (_isBuiltinLocalProfileId(normalizedProfileId)) {
      try {
        remoteModels = await fetchModels(profileId: normalizedProfileId);
      } catch (_) {
        remoteModels = await getCachedFetchedModels(
          profileId: normalizedProfileId,
        );
      }
    } else {
      remoteModels = await getCachedFetchedModels(
        profileId: normalizedProfileId,
      );
    }
    final merged = mergeModelOptions(
      remoteModels: remoteModels,
      manualModelIds: manualModelIds,
    );
    return enrichModelsForProfile(
      profileId: normalizedProfileId,
      providerName: resolvedProfile?.name ?? '',
      apiBase: resolvedProfile?.baseUrl ?? '',
      models: merged,
    );
  }

  static Future<List<ProviderModelGroup>> loadModelGroups() async {
    final payload = await listProfiles();
    final groups = <ProviderModelGroup>[];
    for (final profile in payload.profiles) {
      final models = await getStoredModelOptionsForProfile(
        profile.id,
        profile: profile,
      );
      groups.add(ProviderModelGroup(profile: profile, models: models));
    }
    return groups;
  }

  static List<ProviderModelOption> mergeModelOptions({
    required List<ProviderModelOption> remoteModels,
    required List<String> manualModelIds,
  }) {
    final merged = <ProviderModelOption>[];
    final seen = <String>{};

    for (final modelId in _normalizeModelIds(manualModelIds)) {
      if (seen.add(modelId)) {
        merged.add(
          ProviderModelOption(
            id: modelId,
            displayName: modelId,
            ownedBy: 'manual',
          ),
        );
      }
    }

    for (final item in remoteModels) {
      if (seen.add(item.id)) {
        merged.add(item);
      }
    }
    return merged;
  }

  static String defaultModelGroupName(
    String modelId, {
    String providerId = '',
  }) {
    return ModelsDevCatalogService.groupModelId(
      modelId,
      providerId: providerId,
    );
  }

  static Future<String?> _resolveProfileId(String? profileId) async {
    if (profileId != null && profileId.trim().isNotEmpty) {
      return _canonicalProfileId(profileId);
    }
    final config = await getConfig();
    final normalized = _canonicalProfileId(config.id);
    return normalized.isEmpty ? null : normalized;
  }

  static Future<ModelProviderProfileSummary?> _findProfileById(
    String profileId,
  ) async {
    final normalized = _canonicalProfileId(profileId);
    if (normalized.isEmpty) {
      return null;
    }
    try {
      final payload = await listProfiles();
      for (final profile in payload.profiles) {
        if (_canonicalProfileId(profile.id) == normalized) {
          return profile;
        }
      }
    } catch (_) {
      // Ignore lookup failures; metadata enrichment can still use base URL.
    }
    return null;
  }

  static Map<String, dynamic> _readJsonMap(String key) {
    final raw = StorageService.getString(key, defaultValue: '');
    if (raw == null || raw.trim().isEmpty) {
      return <String, dynamic>{};
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        return Map<String, dynamic>.from(decoded);
      }
    } catch (_) {
      // ignore broken cache
    }
    return <String, dynamic>{};
  }

  static Future<void> _migrateLegacyStorageIfNeeded(String profileId) async {
    final targetProfileId = _canonicalProfileId(profileId);
    if (targetProfileId.isEmpty) {
      return;
    }

    final currentManual = _readJsonMap(_kManualModelIdsKey);
    if (targetProfileId == _kBuiltinOmniInferProfileId &&
        !currentManual.containsKey(targetProfileId) &&
        currentManual.containsKey(_kLegacyBuiltinMnnLocalProfileId)) {
      currentManual[targetProfileId] =
          currentManual[_kLegacyBuiltinMnnLocalProfileId];
      currentManual.remove(_kLegacyBuiltinMnnLocalProfileId);
      await StorageService.setString(
        _kManualModelIdsKey,
        jsonEncode(currentManual),
      );
    }
    if (!currentManual.containsKey(targetProfileId)) {
      final legacyManual = StorageService.getStringList(
        _kLegacyManualModelIdsKey,
        defaultValue: [],
      );
      if (legacyManual != null && legacyManual.isNotEmpty) {
        currentManual[targetProfileId] = _normalizeModelIds(legacyManual);
        await StorageService.setString(
          _kManualModelIdsKey,
          jsonEncode(currentManual),
        );
        await StorageService.remove(_kLegacyManualModelIdsKey);
      }
    }

    final currentCached = _readJsonMap(_kCachedFetchedModelsKey);
    if (targetProfileId == _kBuiltinOmniInferProfileId &&
        !currentCached.containsKey(targetProfileId) &&
        currentCached.containsKey(_kLegacyBuiltinMnnLocalProfileId)) {
      currentCached[targetProfileId] =
          currentCached[_kLegacyBuiltinMnnLocalProfileId];
      currentCached.remove(_kLegacyBuiltinMnnLocalProfileId);
      await StorageService.setString(
        _kCachedFetchedModelsKey,
        jsonEncode(currentCached),
      );
    }
    if (!currentCached.containsKey(targetProfileId)) {
      final legacyRaw = StorageService.getString(
        _kLegacyCachedFetchedModelsKey,
        defaultValue: '',
      );
      if (legacyRaw != null && legacyRaw.trim().isNotEmpty) {
        try {
          final decoded = jsonDecode(legacyRaw);
          if (decoded is Map<String, dynamic>) {
            currentCached[targetProfileId] = decoded;
            await StorageService.setString(
              _kCachedFetchedModelsKey,
              jsonEncode(currentCached),
            );
            await StorageService.remove(_kLegacyCachedFetchedModelsKey);
          }
        } catch (_) {
          // ignore
        }
      }
    }
  }

  static List<String> _normalizeModelIds(List<String> ids) {
    final result = <String>[];
    final seen = <String>{};
    for (final raw in ids) {
      final normalized = raw.trim();
      if (!isValidModelName(normalized)) {
        continue;
      }
      if (seen.add(normalized)) {
        result.add(normalized);
      }
    }
    return result;
  }

  static bool isValidApiBase(String value) {
    return normalizeApiBase(value) != null;
  }

  static bool _hasDirectRequestUrlMarker(String value) {
    return value.trim().endsWith(_kDirectRequestUrlMarker);
  }

  static String _stripDirectRequestUrlMarker(String value) {
    var result = value.trim();
    if (result.endsWith(_kDirectRequestUrlMarker)) {
      result = result.substring(
        0,
        result.length - _kDirectRequestUrlMarker.length,
      );
    }
    return result.replaceAll(RegExp(r'/+$'), '');
  }

  static String? normalizeApiBase(String value) {
    final normalized = value.trim();
    if (normalized.isEmpty) {
      return null;
    }

    final hasDirectRequestUrl = _hasDirectRequestUrlMarker(normalized);
    final candidate = hasDirectRequestUrl
        ? normalized
              .substring(0, normalized.length - _kDirectRequestUrlMarker.length)
              .trim()
        : normalized;
    if (candidate.isEmpty) {
      return null;
    }

    final uri = Uri.tryParse(candidate);
    if (uri == null || !uri.hasScheme || !uri.hasAuthority) {
      return null;
    }
    if (uri.scheme != 'http' && uri.scheme != 'https') {
      return null;
    }

    var result = candidate.replaceAll(RegExp(r'/+$'), '');
    if (!hasDirectRequestUrl) {
      for (final suffix in _kCanonicalEndpointSuffixes) {
        if (result.toLowerCase().endsWith(suffix)) {
          result = result.substring(0, result.length - suffix.length);
          break;
        }
      }
    }
    result = result.replaceAll(RegExp(r'/+$'), '');
    if (result.isEmpty) {
      return null;
    }
    return hasDirectRequestUrl ? '$result$_kDirectRequestUrlMarker' : result;
  }

  static String? buildModelsRequestUrl(String value) {
    return _buildRequestUrl(
      value,
      suffixAfterV1: '/models',
      suffixWithVersion: '/v1/models',
    );
  }

  static String? buildChatCompletionsRequestUrl(String value) {
    return _buildRequestUrl(
      value,
      suffixAfterV1: '/chat/completions',
      suffixWithVersion: '/v1/chat/completions',
    );
  }

  static String? buildAnthropicMessagesRequestUrl(String value) {
    return _buildRequestUrl(
      value,
      suffixAfterV1: '/messages',
      suffixWithVersion: '/v1/messages',
    );
  }

  static String? _buildRequestUrl(
    String value, {
    required String suffixAfterV1,
    required String suffixWithVersion,
  }) {
    final normalizedBase = normalizeApiBase(value);
    if (normalizedBase == null) {
      return null;
    }
    final base = _stripDirectRequestUrlMarker(normalizedBase);
    if (_hasDirectRequestUrlMarker(normalizedBase)) {
      return base;
    }
    if (base.toLowerCase().endsWith('/v1')) {
      return '$base$suffixAfterV1';
    }
    return '$base$suffixWithVersion';
  }

  static bool isValidModelName(String value) {
    final normalized = value.trim();
    return normalized.isNotEmpty && !normalized.startsWith('scene.');
  }
}
