import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:ui/services/storage_service.dart';

class ModelsDevModelMetadata {
  final String id;
  final String name;
  final int? contextLimit;
  final int? inputLimit;
  final int? outputLimit;
  final List<String> inputModalities;
  final List<String> outputModalities;
  final String? family;
  final String? status;
  final bool? attachment;
  final bool? reasoning;
  final bool? toolCall;
  final bool? structuredOutput;
  final bool? temperature;

  const ModelsDevModelMetadata({
    required this.id,
    required this.name,
    this.contextLimit,
    this.inputLimit,
    this.outputLimit,
    this.inputModalities = const [],
    this.outputModalities = const [],
    this.family,
    this.status,
    this.attachment,
    this.reasoning,
    this.toolCall,
    this.structuredOutput,
    this.temperature,
  });
}

class ModelsDevProviderEntry {
  final String key;
  final String id;
  final String name;
  final String? api;
  final Map<String, ModelsDevModelMetadata> models;

  const ModelsDevProviderEntry({
    required this.key,
    required this.id,
    required this.name,
    required this.models,
    this.api,
  });

  String get logoUrl => ModelsDevCatalogService.logoUrlForProvider(id);

  ModelsDevModelMetadata? findModel(String modelId) {
    for (final candidate in ModelsDevCatalogService.modelLookupCandidates(
      modelId,
    )) {
      final match = models[candidate];
      if (match != null) return match;
    }
    return null;
  }
}

class ModelsDevModelMatch {
  final ModelsDevProviderEntry provider;
  final ModelsDevModelMetadata metadata;

  const ModelsDevModelMatch({required this.provider, required this.metadata});
}

class ModelsDevCatalog {
  final Map<String, ModelsDevProviderEntry> providers;

  const ModelsDevCatalog({required this.providers});

  bool get isEmpty => providers.isEmpty;

  Iterable<ModelsDevProviderEntry> get uniqueProviders sync* {
    final seen = <ModelsDevProviderEntry>{};
    for (final provider in providers.values) {
      if (seen.add(provider)) {
        yield provider;
      }
    }
  }
}

class ModelsDevCatalogService {
  static const String catalogUrl = 'https://models.dev/api.json';
  static const String _kCacheKey = 'models_dev_catalog_cache_v1';
  static const Duration _kCacheTtl = Duration(hours: 24);

  static ModelsDevCatalog? _memoryCatalog;
  static DateTime? _memoryCatalogFetchedAt;

  static const Map<String, String> _kKnownHostProviderIds = {
    'api.openai.com': 'openai',
    'api.anthropic.com': 'anthropic',
    'generativelanguage.googleapis.com': 'google',
    'openrouter.ai': 'openrouter',
    'api.deepseek.com': 'deepseek',
    'api.siliconflow.cn': 'siliconflow-cn',
    'dashscope.aliyuncs.com': 'alibaba',
    'dashscope-intl.aliyuncs.com': 'alibaba',
    'api.x.ai': 'xai',
    'api.mistral.ai': 'mistral',
    'api.moonshot.ai': 'moonshotai',
    'api.fireworks.ai': 'fireworks-ai',
    'api.perplexity.ai': 'perplexity',
    'api.groq.com': 'groq',
    'api.cohere.ai': 'cohere',
    'api.together.xyz': 'togetherai',
  };

  static const Map<String, String> _kProviderPrefixAliases = {
    'dashscope': 'alibaba',
    'qwen': 'alibaba',
    'aliyun': 'alibaba',
    'alibaba': 'alibaba',
    'gemini': 'google',
    'googleai': 'google',
    'googlegenerativeai': 'google',
    'xai': 'xai',
    'x': 'xai',
    'mistralai': 'mistral',
    'moonshot': 'moonshotai',
    'kimi': 'moonshotai',
    'fireworks': 'fireworks-ai',
    'together': 'togetherai',
    'togetherai': 'togetherai',
  };

  static const Set<String> _kSpecialGroupProviders = {
    'aihubmix',
    'silicon',
    'siliconflow',
    'siliconflow-cn',
    'ocoolai',
    'o3',
    'dmxapi',
  };

  static const Set<String> _kQwenGroupProviders = {'dashscope', 'alibaba'};

  static const Set<String> _kFuzzyModelSuffixTokens = {
    'alpha',
    'beta',
    'experimental',
    'exp',
    'exacto',
    'free',
    'instruct',
    'latest',
    'online',
    'preview',
    'reasoning',
    'search',
    'thinking',
    'thu',
    'web',
  };

  static String logoUrlForProvider(String providerId) {
    final normalized = providerId.trim().toLowerCase();
    if (normalized.isEmpty) return '';
    return 'https://models.dev/logos/$normalized.svg';
  }

  static Future<ModelsDevCatalog> loadCatalog({
    http.Client? client,
    bool forceRefresh = false,
  }) async {
    final now = DateTime.now();
    final memoryCatalog = _memoryCatalog;
    final memoryFetchedAt = _memoryCatalogFetchedAt;
    if (!forceRefresh &&
        memoryCatalog != null &&
        memoryFetchedAt != null &&
        now.difference(memoryFetchedAt) < _kCacheTtl) {
      return memoryCatalog;
    }

    final cached = _readCachedCatalog();
    if (!forceRefresh &&
        cached != null &&
        now.difference(cached.$2) < _kCacheTtl) {
      _memoryCatalog = cached.$1;
      _memoryCatalogFetchedAt = cached.$2;
      return cached.$1;
    }

    final ownsClient = client == null;
    final httpClient = client ?? http.Client();
    try {
      final response = await httpClient
          .get(Uri.parse(catalogUrl))
          .timeout(const Duration(seconds: 6));
      if (response.statusCode >= 200 && response.statusCode < 300) {
        final catalog = parseCatalog(response.body);
        await _writeCachedCatalog(response.body, now);
        _memoryCatalog = catalog;
        _memoryCatalogFetchedAt = now;
        return catalog;
      }
    } catch (_) {
      // Fall through to stale cache.
    } finally {
      if (ownsClient) {
        httpClient.close();
      }
    }

    if (cached != null) {
      _memoryCatalog = cached.$1;
      _memoryCatalogFetchedAt = cached.$2;
      return cached.$1;
    }
    return const ModelsDevCatalog(providers: {});
  }

  @visibleForTesting
  static ModelsDevCatalog parseCatalog(String rawJson) {
    final decoded = jsonDecode(rawJson);
    if (decoded is! Map<String, dynamic>) {
      return const ModelsDevCatalog(providers: {});
    }
    final providers = <String, ModelsDevProviderEntry>{};
    for (final entry in decoded.entries) {
      final providerRaw = entry.value;
      if (providerRaw is! Map) continue;
      final providerMap = Map<dynamic, dynamic>.from(providerRaw);
      final providerId = (providerMap['id'] ?? entry.key).toString().trim();
      final providerName = (providerMap['name'] ?? providerId)
          .toString()
          .trim();
      final modelsRaw = providerMap['models'];
      final models = <String, ModelsDevModelMetadata>{};
      if (modelsRaw is Map) {
        for (final modelEntry in modelsRaw.entries) {
          final modelRaw = modelEntry.value;
          if (modelRaw is! Map) continue;
          final modelMap = Map<dynamic, dynamic>.from(modelRaw);
          final modelId = (modelMap['id'] ?? modelEntry.key).toString().trim();
          if (modelId.isEmpty) continue;
          final limit = modelMap['limit'] is Map
              ? Map<dynamic, dynamic>.from(modelMap['limit'] as Map)
              : const <dynamic, dynamic>{};
          final modalities = modelMap['modalities'] is Map
              ? Map<dynamic, dynamic>.from(modelMap['modalities'] as Map)
              : const <dynamic, dynamic>{};
          models[modelId.toLowerCase()] = ModelsDevModelMetadata(
            id: modelId,
            name: (modelMap['name'] ?? modelId).toString().trim(),
            contextLimit: _readInt(limit['context']),
            inputLimit: _readInt(limit['input']),
            outputLimit: _readInt(limit['output']),
            inputModalities: _readStringList(modalities['input']),
            outputModalities: _readStringList(modalities['output']),
            family: _readNonEmptyString(modelMap['family']),
            status: _readNonEmptyString(modelMap['status']),
            attachment: _readBool(modelMap['attachment']),
            reasoning: _readBool(modelMap['reasoning']),
            toolCall: _readBool(modelMap['tool_call']),
            structuredOutput: _readBool(modelMap['structured_output']),
            temperature: _readBool(modelMap['temperature']),
          );
        }
      }
      if (providerId.isEmpty && providerName.isEmpty) continue;
      final provider = ModelsDevProviderEntry(
        key: entry.key,
        id: providerId.isEmpty ? entry.key : providerId,
        name: providerName.isEmpty ? providerId : providerName,
        api: _readNonEmptyString(providerMap['api']),
        models: models,
      );
      providers[provider.key.toLowerCase()] = provider;
      providers[provider.id.toLowerCase()] = provider;
      providers[_normalizeProviderToken(provider.key)] = provider;
      providers[_normalizeProviderToken(provider.id)] = provider;
      providers[_normalizeProviderToken(provider.name)] = provider;
    }
    return ModelsDevCatalog(providers: providers);
  }

  static Future<ModelsDevProviderEntry?> resolveProvider({
    String providerId = '',
    String providerName = '',
    String apiBase = '',
    http.Client? client,
  }) async {
    final catalog = await loadCatalog(client: client);
    if (catalog.isEmpty) return null;
    return matchProvider(
      catalog: catalog,
      providerId: providerId,
      providerName: providerName,
      apiBase: apiBase,
    );
  }

  static ModelsDevProviderEntry? matchProvider({
    required ModelsDevCatalog catalog,
    String providerId = '',
    String providerName = '',
    String apiBase = '',
  }) {
    final idCandidates = <String>[
      providerId,
      providerName,
    ].map(_normalizeProviderToken).where((item) => item.isNotEmpty).toList();
    for (final candidate in idCandidates) {
      final direct = catalog.providers[candidate];
      if (direct != null) return direct;
    }

    for (final candidate in idCandidates) {
      for (final provider in catalog.providers.values.toSet()) {
        if (_normalizeProviderToken(provider.name) == candidate) {
          return provider;
        }
      }
    }

    final requestHost = _hostFromUrl(apiBase);
    if (requestHost != null) {
      final alias = _kKnownHostProviderIds[requestHost];
      if (alias != null) {
        final aliasProvider = catalog.providers[alias];
        if (aliasProvider != null) return aliasProvider;
      }
      for (final provider in catalog.providers.values.toSet()) {
        final providerHost = _hostFromUrl(provider.api ?? '');
        if (providerHost == null) continue;
        if (requestHost == providerHost ||
            requestHost.endsWith('.$providerHost') ||
            providerHost.endsWith('.$requestHost')) {
          return provider;
        }
      }
    }

    return null;
  }

  static Future<ModelsDevModelMatch?> resolveModelMetadata({
    String providerId = '',
    String providerName = '',
    String apiBase = '',
    required String modelId,
    http.Client? client,
  }) async {
    final catalog = await loadCatalog(client: client);
    if (catalog.isEmpty) return null;
    final provider = matchProvider(
      catalog: catalog,
      providerId: providerId,
      providerName: providerName,
      apiBase: apiBase,
    );
    return matchModelMetadata(
      catalog: catalog,
      provider: provider,
      modelId: modelId,
    );
  }

  static ModelsDevModelMatch? matchModelMetadata({
    required ModelsDevCatalog catalog,
    required String modelId,
    ModelsDevProviderEntry? provider,
  }) {
    final normalizedModelId = modelId.trim();
    if (normalizedModelId.isEmpty || catalog.isEmpty) return null;

    final providerMatch = _matchModelInProvider(provider, normalizedModelId);
    if (providerMatch != null) return providerMatch;

    final prefixedProvider = _providerFromModelPrefix(catalog, modelId);
    final prefixMatch = _matchModelInProvider(
      prefixedProvider,
      normalizedModelId,
    );
    if (prefixMatch != null) return prefixMatch;

    final inferredProvider = _inferProviderFromModelFamily(catalog, modelId);
    final inferredMatch = _matchModelInProvider(
      inferredProvider,
      normalizedModelId,
    );
    if (inferredMatch != null) return inferredMatch;

    return _findUniqueModelMatch(catalog, normalizedModelId);
  }

  @visibleForTesting
  static List<String> modelLookupCandidates(String modelId) {
    final normalized = modelId.trim().toLowerCase();
    if (normalized.isEmpty) return const [];

    final candidates = <String>[];
    void add(String value) {
      final item = value.trim().toLowerCase();
      if (item.isNotEmpty && !candidates.contains(item)) {
        candidates.add(item);
      }
    }

    add(normalized);
    add(_stripVariantSuffix(normalized));

    for (final value in List<String>.from(candidates)) {
      const modelsMarker = '/models/';
      final modelsIndex = value.indexOf(modelsMarker);
      if (modelsIndex >= 0) {
        add(value.substring(modelsIndex + modelsMarker.length));
      }

      final slashIndex = value.lastIndexOf('/');
      if (slashIndex >= 0 && slashIndex < value.length - 1) {
        add(value.substring(slashIndex + 1));
      }
    }

    for (final value in List<String>.from(candidates)) {
      add(_stripVariantSuffix(value));
    }

    for (final value in List<String>.from(candidates)) {
      _addFuzzyModelSuffixCandidates(value, add);
    }

    return candidates;
  }

  static String groupModelId(String modelId, {String providerId = ''}) {
    final normalizedProvider = providerId.trim().toLowerCase();
    final normalizedId = modelId.trim().toLowerCase();
    if (normalizedId.isEmpty) return 'other';

    final qwenGroupName = _qwenGroupName(normalizedId);
    if (_kQwenGroupProviders.contains(normalizedProvider) ||
        qwenGroupName != null) {
      if (qwenGroupName != null) return qwenGroupName;
    }

    return defaultGroupName(normalizedId, providerId: normalizedProvider);
  }

  @visibleForTesting
  static String defaultGroupName(String modelId, {String providerId = ''}) {
    final str = modelId.trim().toLowerCase();
    if (str.isEmpty) return 'other';

    var firstDelimiters = const ['/', ' ', ':'];
    var secondDelimiters = const ['-', '_'];
    if (_kSpecialGroupProviders.contains(providerId.trim().toLowerCase())) {
      firstDelimiters = const ['/', ' ', '-', '_', ':'];
      secondDelimiters = const [];
    }

    for (final delimiter in firstDelimiters) {
      if (str.contains(delimiter)) {
        return str.split(delimiter).first;
      }
    }

    for (final delimiter in secondDelimiters) {
      if (str.contains(delimiter)) {
        final parts = str.split(delimiter);
        return parts.length > 1 ? '${parts[0]}-${parts[1]}' : parts.first;
      }
    }

    return str;
  }

  @visibleForTesting
  static void resetForTesting() {
    _memoryCatalog = null;
    _memoryCatalogFetchedAt = null;
  }

  @visibleForTesting
  static void setCatalogForTesting(ModelsDevCatalog catalog) {
    _memoryCatalog = catalog;
    _memoryCatalogFetchedAt = DateTime.now();
  }

  static (ModelsDevCatalog, DateTime)? _readCachedCatalog() {
    final raw = StorageService.getString(_kCacheKey, defaultValue: '');
    if (raw == null || raw.trim().isEmpty) {
      return null;
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) return null;
      final fetchedAtMs = _readInt(decoded['fetchedAt']);
      final payload = decoded['payload']?.toString();
      if (fetchedAtMs == null || payload == null || payload.isEmpty) {
        return null;
      }
      return (
        parseCatalog(payload),
        DateTime.fromMillisecondsSinceEpoch(fetchedAtMs),
      );
    } catch (_) {
      return null;
    }
  }

  static Future<void> _writeCachedCatalog(String payload, DateTime fetchedAt) {
    return StorageService.setString(
      _kCacheKey,
      jsonEncode({
        'fetchedAt': fetchedAt.millisecondsSinceEpoch,
        'payload': payload,
      }),
    );
  }

  static ModelsDevModelMatch? _matchModelInProvider(
    ModelsDevProviderEntry? provider,
    String modelId,
  ) {
    if (provider == null) return null;
    final metadata = provider.findModel(modelId);
    if (metadata == null) return null;
    return ModelsDevModelMatch(provider: provider, metadata: metadata);
  }

  static ModelsDevModelMatch? _findUniqueModelMatch(
    ModelsDevCatalog catalog,
    String modelId,
  ) {
    final matches = <ModelsDevModelMatch>[];
    for (final provider in catalog.uniqueProviders) {
      final metadata = provider.findModel(modelId);
      if (metadata == null) continue;
      matches.add(ModelsDevModelMatch(provider: provider, metadata: metadata));
      if (matches.length > 1) return null;
    }
    return matches.isEmpty ? null : matches.single;
  }

  static ModelsDevProviderEntry? _providerFromModelPrefix(
    ModelsDevCatalog catalog,
    String modelId,
  ) {
    final normalized = modelId.trim().toLowerCase();
    final slashIndex = normalized.indexOf('/');
    if (slashIndex <= 0) return null;
    return _providerByToken(catalog, normalized.substring(0, slashIndex));
  }

  static ModelsDevProviderEntry? _inferProviderFromModelFamily(
    ModelsDevCatalog catalog,
    String modelId,
  ) {
    final candidates = modelLookupCandidates(modelId);
    if (candidates.isEmpty) return null;
    final model = candidates.last;
    if (model.startsWith('gpt-') ||
        model.startsWith('chatgpt-') ||
        model.startsWith('text-embedding-3-') ||
        RegExp(r'^o\d(?:-|$)').hasMatch(model)) {
      return _providerByToken(catalog, 'openai');
    }
    if (model.startsWith('claude-')) {
      return _providerByToken(catalog, 'anthropic');
    }
    if (model.startsWith('deepseek-')) {
      return _providerByToken(catalog, 'deepseek');
    }
    if (model.startsWith('gemini-')) {
      return _providerByToken(catalog, 'google');
    }
    if (model.startsWith('qwen') || model.startsWith('qwq-')) {
      return _providerByToken(catalog, 'alibaba');
    }
    if (model.startsWith('grok-')) {
      return _providerByToken(catalog, 'xai');
    }
    if (model.startsWith('mistral') ||
        model.startsWith('mixtral') ||
        model.startsWith('pixtral') ||
        model.startsWith('codestral') ||
        model.startsWith('ministral') ||
        model.startsWith('magistral')) {
      return _providerByToken(catalog, 'mistral');
    }
    if (model.startsWith('command-')) {
      return _providerByToken(catalog, 'cohere');
    }
    if (model.startsWith('sonar')) {
      return _providerByToken(catalog, 'perplexity');
    }
    if (model.startsWith('kimi-')) {
      return _providerByToken(catalog, 'moonshotai');
    }
    return null;
  }

  static ModelsDevProviderEntry? _providerByToken(
    ModelsDevCatalog catalog,
    String value,
  ) {
    final normalized = _normalizeProviderToken(value);
    if (normalized.isEmpty) return null;
    final alias = _kProviderPrefixAliases[normalized] ?? normalized;
    return catalog.providers[alias] ?? catalog.providers[normalized];
  }

  static String? _qwenGroupName(String modelId) {
    for (final candidate in modelLookupCandidates(modelId)) {
      final trimmed = _trimKnownModelSuffixes(candidate.replaceAll('_', '-'));
      if (!trimmed.startsWith('qwen')) continue;
      final match = RegExp(
        r'^(qwen(?:\d+(?:\.\d+)?|2(?:\.\d+)?|-\d+b)?(?:-[a-z0-9.]+){0,2})',
      ).firstMatch(trimmed);
      final groupName = match?.group(1)?.trim();
      if (groupName != null && groupName.isNotEmpty) {
        return groupName;
      }
    }
    return null;
  }

  static void _addFuzzyModelSuffixCandidates(
    String value,
    void Function(String value) add,
  ) {
    final dashed = value.replaceAll('_', '-');
    if (dashed != value) add(dashed);

    var current = dashed;
    while (true) {
      final trimmed = _trimOneKnownModelSuffix(current);
      if (trimmed == current) return;
      add(trimmed);
      current = trimmed;
    }
  }

  static String _trimKnownModelSuffixes(String value) {
    var current = value;
    while (true) {
      final trimmed = _trimOneKnownModelSuffix(current);
      if (trimmed == current) return current;
      current = trimmed;
    }
  }

  static String _trimOneKnownModelSuffix(String value) {
    final parts = value.split('-');
    if (parts.length < 2) return value;
    final suffix = parts.last.trim().toLowerCase();
    if (!_isKnownFuzzyModelSuffix(suffix)) {
      return value;
    }
    return parts.take(parts.length - 1).join('-');
  }

  static bool _isKnownFuzzyModelSuffix(String value) {
    return _kFuzzyModelSuffixTokens.contains(value) ||
        RegExp(r'^\d{2,8}$').hasMatch(value) ||
        RegExp(r'^v\d+$').hasMatch(value);
  }

  static String _stripVariantSuffix(String value) {
    final colonIndex = value.lastIndexOf(':');
    if (colonIndex <= 0 || colonIndex >= value.length - 1) {
      return value;
    }
    final suffix = value.substring(colonIndex + 1);
    if (suffix.contains('/')) {
      return value;
    }
    return value.substring(0, colonIndex);
  }

  static int? _readInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static bool? _readBool(Object? value) {
    if (value is bool) return value;
    final normalized = value?.toString().trim().toLowerCase() ?? '';
    if (normalized == 'true') return true;
    if (normalized == 'false') return false;
    return null;
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

  static String _normalizeProviderToken(String value) {
    return value.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '');
  }

  static String? _hostFromUrl(String value) {
    var normalized = value.trim();
    if (normalized.endsWith('#')) {
      normalized = normalized.substring(0, normalized.length - 1).trim();
    }
    if (normalized.isEmpty) return null;
    final uri = Uri.tryParse(normalized);
    final host = uri?.host.trim().toLowerCase();
    return host == null || host.isEmpty ? null : host;
  }
}
