import 'dart:convert';

class CodexToolCallInfo {
  const CodexToolCallInfo({
    required this.itemType,
    required this.toolType,
    required this.toolName,
    required this.displayName,
    required this.toolTitle,
    required this.status,
    required this.arguments,
    required this.argsJson,
    required this.resultPreviewJson,
    required this.rawResultJson,
    required this.terminalOutput,
    required this.summary,
    required this.progress,
    this.serverName,
  });

  final String itemType;
  final String toolType;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String status;
  final Map<String, dynamic> arguments;
  final String argsJson;
  final String resultPreviewJson;
  final String rawResultJson;
  final String terminalOutput;
  final String summary;
  final String progress;
  final String? serverName;
}

CodexToolCallInfo normalizeCodexToolCall(
  Map<String, dynamic> raw, {
  String? itemType,
  String? fallbackToolType,
  String? fallbackTitle,
  String fallbackStatus = 'running',
}) {
  final type = _firstString([itemType, raw['type']]) ?? '';
  final arguments = _normalizedArguments(raw);
  final rawToolName = _resolveToolName(raw, itemType: type);
  final toolType = _inferToolType(
    itemType: type,
    explicitToolType: _firstString([raw['toolType'], raw['tool_type']]),
    fallbackToolType: fallbackToolType,
    toolName: rawToolName,
    arguments: arguments,
  );
  final status = normalizeCodexToolStatus(raw, fallbackStatus: fallbackStatus);
  final title = _resolveToolTitle(
    raw,
    itemType: type,
    toolType: toolType,
    toolName: rawToolName,
    arguments: arguments,
    fallbackTitle: fallbackTitle,
  );
  final toolName = rawToolName ?? _defaultToolName(type, toolType);
  final displayName =
      _firstString([raw['displayName'], raw['display_name'], raw['name']]) ??
      title;
  final serverName = _firstString([raw['serverName'], raw['server']]);
  final terminalOutput = _firstOutputString([
    raw['terminalOutput'],
    raw['aggregatedOutput'],
    raw['aggregated_output'],
    raw['output'],
    raw['stdout'],
    _asStringMap(raw['result'])?['stdout'],
    _asStringMap(raw['result'])?['output'],
  ]);
  final summary =
      _firstString([
        raw['summary'],
        raw['message'],
        raw['description'],
        if (type != 'commandExecution') raw['status'],
      ]) ??
      '';
  final progress =
      _firstString([raw['progress'], raw['message'], raw['delta']]) ?? '';

  return CodexToolCallInfo(
    itemType: type,
    toolType: toolType,
    toolName: toolName,
    displayName: displayName,
    toolTitle: title,
    status: status,
    arguments: arguments,
    argsJson: arguments.isEmpty ? '' : _safeJson(arguments),
    resultPreviewJson: _resultPreviewJson(raw),
    rawResultJson: _safeJson(raw),
    terminalOutput: terminalOutput ?? '',
    summary: summary,
    progress: progress,
    serverName: serverName,
  );
}

String normalizeCodexToolStatus(
  Map<String, dynamic> raw, {
  String fallbackStatus = 'running',
}) {
  if (raw['error'] != null) {
    return 'error';
  }
  final success = raw['success'];
  if (success == false) {
    return 'error';
  }
  final exitCode = _asInt(raw['exitCode'] ?? raw['exit_code']);
  final explicit = _firstString([raw['status'], raw['state']]);
  final normalized = explicit?.trim().toLowerCase();
  if (normalized != null && normalized.isNotEmpty) {
    if (normalized == 'running' ||
        normalized == 'pending' ||
        normalized == 'progress' ||
        normalized == 'inprogress' ||
        normalized == 'in_progress' ||
        normalized == 'executing' ||
        normalized == 'started') {
      return 'running';
    }
    if (normalized == 'success' ||
        normalized == 'succeeded' ||
        normalized == 'completed' ||
        normalized == 'complete' ||
        normalized == 'applied' ||
        normalized == 'done') {
      if (exitCode != null && exitCode != 0) {
        return 'error';
      }
      return 'success';
    }
    if (normalized == 'error' ||
        normalized == 'failed' ||
        normalized == 'failure' ||
        normalized == 'rejected') {
      return 'error';
    }
    if (normalized == 'cancelled' ||
        normalized == 'canceled' ||
        normalized == 'interrupted' ||
        normalized == 'aborted') {
      return 'interrupted';
    }
    if (normalized == 'timeout' || normalized == 'timedout') {
      return 'timeout';
    }
  }
  if (exitCode != null && exitCode != 0) {
    return 'error';
  }
  if (success == true) {
    return 'success';
  }
  return fallbackStatus;
}

bool codexToolStatusIsExplicit(Map<String, dynamic> raw) {
  return _firstString([raw['status'], raw['state']]) != null ||
      raw.containsKey('success') ||
      raw.containsKey('error') ||
      raw.containsKey('exitCode') ||
      raw.containsKey('exit_code');
}

String codexToolCardSuffix(String toolType, {String? itemType}) {
  if (itemType == 'commandExecution' || toolType == 'terminal') {
    return 'command';
  }
  if (itemType == 'fileChange' || toolType == 'file') {
    return 'file';
  }
  if (itemType == 'plan' || toolType == 'plan') {
    return 'plan';
  }
  return 'tool';
}

bool isCodexToolItemType(String itemType) {
  return const <String>{
    'commandExecution',
    'fileChange',
    'tool',
    'mcpToolCall',
    'dynamicToolCall',
    'webSearch',
    'imageView',
    'imageGeneration',
    'collabAgentToolCall',
    'collabToolCall',
    'plan',
  }.contains(itemType);
}

Map<String, dynamic> _normalizedArguments(Map<String, dynamic> raw) {
  final args = <String, dynamic>{};
  final parsed = _toolArguments(raw);
  args.addAll(parsed);

  void add(String key, dynamic value) {
    if (args.containsKey(key) || value == null) {
      return;
    }
    final text = value is String ? value.trim() : null;
    if (text != null && text.isEmpty) {
      return;
    }
    args[key] = value;
  }

  for (final key in const <String>[
    'command',
    'cmd',
    'cwd',
    'workingDirectory',
    'working_directory',
    'query',
    'q',
    'url',
    'uri',
    'path',
    'filePath',
    'file_path',
    'filename',
    'fileName',
    'action',
    'tool',
    'server',
    'namespace',
    'prompt',
  ]) {
    add(key, raw[key]);
  }
  if (raw['changes'] != null) {
    add('changes', raw['changes']);
  }
  if (raw['files'] != null) {
    add('files', raw['files']);
  }
  return args;
}

Map<String, dynamic> _toolArguments(Map<String, dynamic> raw) {
  for (final key in const <String>['arguments', 'args', 'input']) {
    final map = _asStringMap(raw[key]);
    if (map != null) {
      return map;
    }
    final text = _string(raw[key]);
    if (text == null || text.trim().isEmpty) {
      continue;
    }
    try {
      final decoded = jsonDecode(text);
      final decodedMap = _asStringMap(decoded);
      if (decodedMap != null) {
        return decodedMap;
      }
    } catch (_) {
      continue;
    }
  }
  return const <String, dynamic>{};
}

String? _resolveToolName(Map<String, dynamic> raw, {required String itemType}) {
  final toolValue = raw['tool'];
  final toolString = toolValue is String ? toolValue : null;
  return _firstString([
    raw['toolName'],
    raw['tool_name'],
    raw['name'],
    raw['functionName'],
    raw['function_name'],
    _asStringMap(raw['function'])?['name'],
    _asStringMap(raw['tool'])?['name'],
    toolString,
  ]);
}

String _inferToolType({
  required String itemType,
  required String? explicitToolType,
  required String? fallbackToolType,
  required String? toolName,
  required Map<String, dynamic> arguments,
}) {
  final explicit = explicitToolType?.trim();
  if (explicit != null && explicit.isNotEmpty) {
    return explicit;
  }
  switch (itemType) {
    case 'commandExecution':
      return 'terminal';
    case 'fileChange':
      return 'file';
    case 'webSearch':
      return 'search';
    case 'imageView':
    case 'imageGeneration':
      return 'image';
    case 'collabAgentToolCall':
    case 'collabToolCall':
      return 'subagent';
    case 'plan':
      return 'plan';
  }

  final fullName = (toolName ?? '').trim().toLowerCase();
  final shortName = _shortToolName(fullName).toLowerCase();
  final name = '$fullName $shortName';
  if (_containsAny(name, const [
    'terminal',
    'shell',
    'exec',
    'command',
    'bash',
    'zsh',
    'powershell',
  ])) {
    return 'terminal';
  }
  if (_containsAny(shortName, const [
    'edit',
    'write',
    'patch',
    'apply_patch',
  ])) {
    return 'file';
  }
  if (_containsAny(name, const [
    'read',
    'list',
    'glob',
    'grep',
    'workspace',
    'file_search',
    'search_file',
  ])) {
    return 'workspace';
  }
  if (_containsAny(name, const ['web', 'browser', 'fetch', 'open_url'])) {
    return 'browser';
  }
  if (_containsAny(name, const ['search', 'query'])) {
    return 'search';
  }
  if (_containsAny(name, const ['image', 'screenshot', 'view_image'])) {
    return 'image';
  }
  if (_containsAny(name, const ['task', 'subagent', 'agent'])) {
    return 'subagent';
  }
  if (_containsAny(name, const ['memory'])) {
    return 'memory';
  }
  if (itemType == 'mcpToolCall') {
    return 'mcp';
  }
  final fallback = fallbackToolType?.trim();
  if (fallback != null && fallback.isNotEmpty) {
    return fallback;
  }
  return 'tool';
}

String _resolveToolTitle(
  Map<String, dynamic> raw, {
  required String itemType,
  required String toolType,
  required String? toolName,
  required Map<String, dynamic> arguments,
  required String? fallbackTitle,
}) {
  final explicit = _firstString([
    raw['toolTitle'],
    raw['tool_title'],
    raw['displayName'],
    raw['display_name'],
    arguments['toolTitle'],
    arguments['tool_title'],
    arguments['displayName'],
    arguments['display_name'],
  ]);
  if (explicit != null) {
    return _compactTitle(explicit, maxLength: 48);
  }

  if (itemType == 'commandExecution' || toolType == 'terminal') {
    final command = _firstString([
      raw['command'],
      arguments['command'],
      raw['cmd'],
      arguments['cmd'],
    ]);
    if (command != null) {
      return _compactTitle(command, maxLength: 48);
    }
    return fallbackTitle?.trim().isNotEmpty == true
        ? _compactTitle(fallbackTitle!, maxLength: 48)
        : 'Codex command';
  }

  if (itemType == 'fileChange' || toolType == 'file') {
    final path = _resolvePath(raw, arguments);
    if (path != null) {
      return _compactTitle(
        'Edit ${_lastPathSegment(path) ?? path}',
        maxLength: 42,
      );
    }
    return fallbackTitle?.trim().isNotEmpty == true
        ? _compactTitle(fallbackTitle!, maxLength: 48)
        : 'Codex file change';
  }

  if (itemType == 'webSearch') {
    final query = _firstString([
      raw['query'],
      arguments['query'],
      arguments['q'],
    ]);
    if (query != null) {
      return _compactTitle('Search: $query', maxLength: 48);
    }
    return 'Web search';
  }

  if (itemType == 'imageView') {
    final path = _resolvePath(raw, arguments);
    if (path != null) {
      return _compactTitle(
        'View ${_lastPathSegment(path) ?? path}',
        maxLength: 48,
      );
    }
    return 'View image';
  }

  if (itemType == 'imageGeneration') {
    return 'Generate image';
  }

  if (itemType == 'collabAgentToolCall' || itemType == 'collabToolCall') {
    final prompt = _firstString([raw['prompt'], arguments['prompt']]);
    if (prompt != null) {
      return _compactTitle('Subagent: $prompt', maxLength: 48);
    }
    final name = toolName == null ? 'Subagent' : _shortToolName(toolName);
    return _compactTitle(name, maxLength: 48);
  }

  if (itemType == 'plan' || toolType == 'plan') {
    return 'Codex plan';
  }

  final shortName = toolName == null ? null : _shortToolName(toolName);
  final command = _firstString([arguments['command'], arguments['cmd']]);
  if (command != null) {
    return _compactTitle(command, maxLength: 48);
  }
  final detail = _firstString([
    arguments['query'],
    arguments['q'],
    arguments['url'],
    arguments['uri'],
    arguments['path'],
    arguments['filePath'],
    arguments['file_path'],
    arguments['filename'],
    arguments['fileName'],
    raw['query'],
    raw['url'],
    raw['path'],
  ]);
  if (detail != null) {
    final operationTitle = _operationTitle(shortName, detail);
    if (operationTitle != null) {
      return _compactTitle(operationTitle, maxLength: 48);
    }
    final detailTitle = _looksLikePath(detail)
        ? (_lastPathSegment(detail) ?? detail)
        : detail;
    if (shortName != null && shortName.isNotEmpty) {
      return _compactTitle('$shortName: $detailTitle', maxLength: 48);
    }
    return _compactTitle(detailTitle, maxLength: 48);
  }

  if (fallbackTitle?.trim().isNotEmpty == true) {
    return _compactTitle(fallbackTitle!, maxLength: 48);
  }
  if (shortName != null && shortName.isNotEmpty) {
    return _compactTitle(shortName, maxLength: 48);
  }
  return 'Codex tool';
}

String? _operationTitle(String? shortName, String detail) {
  final name = (shortName ?? '').trim().toLowerCase();
  if (name.isEmpty) {
    return null;
  }
  final target = _looksLikePath(detail)
      ? (_lastPathSegment(detail) ?? detail)
      : detail;
  if (name == 'read' ||
      name == 'read_file' ||
      name == 'readfile' ||
      name == 'view_file') {
    return 'Read $target';
  }
  if (name == 'list' ||
      name == 'list_files' ||
      name == 'list_directory' ||
      name == 'ls') {
    return 'List $target';
  }
  if (name == 'write' || name == 'write_file' || name == 'writefile') {
    return 'Write $target';
  }
  if (name == 'edit' || name == 'edit_file' || name == 'apply_patch') {
    return 'Edit $target';
  }
  if (name == 'grep' ||
      name == 'search' ||
      name == 'search_files' ||
      name == 'file_search') {
    return 'Search $target';
  }
  return null;
}

String? _resolvePath(Map<String, dynamic> raw, Map<String, dynamic> args) {
  return _firstString([
    raw['path'],
    raw['filePath'],
    raw['file_path'],
    raw['filename'],
    raw['fileName'],
    args['path'],
    args['filePath'],
    args['file_path'],
    args['filename'],
    args['fileName'],
    _firstPathFromList(raw['files']),
    _firstPathFromList(raw['changes']),
    _firstPathFromList(args['files']),
    _firstPathFromList(args['changes']),
  ]);
}

String? _firstPathFromList(dynamic value) {
  if (value is String) {
    final decoded = _decodeJson(value);
    return _firstPathFromList(decoded);
  }
  if (value is! List) {
    return null;
  }
  for (final item in value) {
    if (item is String && item.trim().isNotEmpty) {
      final decoded = _decodeJson(item);
      final decodedPath = _firstPathFromList(decoded);
      if (decodedPath != null) {
        return decodedPath;
      }
      return item.trim();
    }
    final map = _asStringMap(item);
    final path = _firstString([
      map?['path'],
      map?['filePath'],
      map?['file_path'],
      map?['filename'],
      map?['fileName'],
    ]);
    if (path != null) {
      return path;
    }
  }
  return null;
}

String _defaultToolName(String itemType, String toolType) {
  if (itemType == 'mcpToolCall') {
    return 'codex.mcp';
  }
  if (itemType == 'dynamicToolCall') {
    return 'codex.dynamicTool';
  }
  if (itemType == 'webSearch') {
    return 'codex.webSearch';
  }
  if (itemType == 'imageView') {
    return 'codex.imageView';
  }
  if (itemType == 'imageGeneration') {
    return 'codex.imageGeneration';
  }
  if (itemType == 'collabAgentToolCall' || itemType == 'collabToolCall') {
    return 'codex.collabAgent';
  }
  return 'codex.$toolType';
}

String _resultPreviewJson(Map<String, dynamic> raw) {
  final result =
      raw['result'] ??
      raw['output'] ??
      raw['contentItems'] ??
      raw['content_items'];
  if (result != null) {
    return _safeJson(result);
  }
  final error = raw['error'];
  if (error != null) {
    return _safeJson({'error': error});
  }
  return '';
}

dynamic _decodeJson(String text) {
  final normalized = text.trim();
  if (normalized.isEmpty) {
    return null;
  }
  try {
    return jsonDecode(normalized);
  } catch (_) {
    return null;
  }
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is String) {
    return _asStringMap(_decodeJson(value));
  }
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue));
}

String? _firstString(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _string(value);
    if (text != null && text.trim().isNotEmpty) {
      return text.trim();
    }
  }
  return null;
}

String? _firstOutputString(Iterable<dynamic> values) {
  for (final value in values) {
    if (value == null) {
      continue;
    }
    if (value is String) {
      if (value.isNotEmpty) {
        return value;
      }
      continue;
    }
    if (value is num || value is bool) {
      return value.toString();
    }
  }
  return null;
}

String? _string(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String) {
    return value;
  }
  if (value is num || value is bool) {
    return value.toString();
  }
  return null;
}

int? _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse((value ?? '').toString().trim());
}

String _shortToolName(String value) {
  final normalized = value.trim();
  if (normalized.isEmpty) {
    return '';
  }
  final withoutNamespace = normalized.split(RegExp(r'[./:]')).last;
  final parts = withoutNamespace
      .split('__')
      .where((part) => part.isNotEmpty)
      .toList(growable: false);
  return parts.isEmpty ? withoutNamespace : parts.last;
}

String? _lastPathSegment(String path) {
  final normalized = path.trim().replaceAll(RegExp(r'[/\\]+$'), '');
  if (normalized.isEmpty) {
    return null;
  }
  final parts = normalized
      .split(RegExp(r'[/\\]+'))
      .where((part) => part.isNotEmpty)
      .toList(growable: false);
  return parts.isEmpty ? normalized : parts.last;
}

bool _looksLikePath(String value) {
  return value.contains('/') || value.contains('\\');
}

bool _containsAny(String haystack, List<String> needles) {
  return needles.any(haystack.contains);
}

String _compactTitle(String value, {required int maxLength}) {
  final normalized = value
      .trim()
      .split('\n')
      .first
      .trim()
      .replaceAll(RegExp(r'\s+'), ' ');
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return '${normalized.substring(0, maxLength)}...';
}

String _safeJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value?.toString() ?? '';
  }
}
