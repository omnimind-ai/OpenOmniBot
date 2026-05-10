import 'dart:collection';

import 'package:ui/services/storage_service.dart';

class ChatTerminalEnvironmentVariable {
  const ChatTerminalEnvironmentVariable({
    required this.key,
    required this.value,
  });

  final String key;
  final String value;

  String get normalizedKey => key.trim();

  Map<String, dynamic> toMap() {
    return <String, dynamic>{'key': normalizedKey, 'value': value};
  }

  factory ChatTerminalEnvironmentVariable.fromMap(Map<dynamic, dynamic> raw) {
    return ChatTerminalEnvironmentVariable(
      key: (raw['key'] ?? '').toString().trim(),
      value: (raw['value'] ?? '').toString(),
    );
  }
}

class ChatTerminalEnvironmentService {
  static const String _storageKey = 'chat_terminal_environment_variables';
  static final RegExp _envKeyPattern = RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$');

  static bool isValidKey(String value) {
    return _envKeyPattern.hasMatch(value.trim());
  }

  static List<ChatTerminalEnvironmentVariable> loadVariables() {
    final raw = StorageService.getJson<dynamic>(_storageKey);
    if (raw is! List) {
      return const <ChatTerminalEnvironmentVariable>[];
    }
    return normalizeVariables(
      raw.whereType<Map>().map(ChatTerminalEnvironmentVariable.fromMap),
    );
  }

  static Future<void> saveVariables(
    List<ChatTerminalEnvironmentVariable> variables,
  ) async {
    final normalized = normalizeVariables(variables);
    await StorageService.setJson(
      _storageKey,
      normalized.map((item) => item.toMap()).toList(),
    );
  }

  static List<ChatTerminalEnvironmentVariable> normalizeVariables(
    Iterable<ChatTerminalEnvironmentVariable> variables,
  ) {
    final ordered = LinkedHashMap<String, String>();
    for (final item in variables) {
      final key = item.normalizedKey;
      if (key.isEmpty || !isValidKey(key)) {
        continue;
      }
      ordered.remove(key);
      ordered[key] = item.value;
    }
    return ordered.entries
        .map(
          (entry) => ChatTerminalEnvironmentVariable(
            key: entry.key,
            value: entry.value,
          ),
        )
        .toList(growable: false);
  }

  static bool containsKey(
    Iterable<ChatTerminalEnvironmentVariable> variables,
    String key, {
    String? exceptKey,
  }) {
    final normalizedKey = key.trim();
    if (normalizedKey.isEmpty) {
      return false;
    }
    final normalizedExceptKey = exceptKey?.trim();
    return normalizeVariables(variables).any(
      (item) =>
          item.normalizedKey == normalizedKey &&
          item.normalizedKey != normalizedExceptKey,
    );
  }

  static List<ChatTerminalEnvironmentVariable> replaceVariable(
    Iterable<ChatTerminalEnvironmentVariable> variables, {
    required String originalKey,
    required ChatTerminalEnvironmentVariable replacement,
  }) {
    final normalized = normalizeVariables(variables);
    final replacementKey = replacement.normalizedKey;
    if (replacementKey.isEmpty || !isValidKey(replacementKey)) {
      return normalized;
    }

    final originalIndex = normalized.indexWhere(
      (item) => item.normalizedKey == originalKey.trim(),
    );
    final replacementItem = ChatTerminalEnvironmentVariable(
      key: replacementKey,
      value: replacement.value,
    );
    if (originalIndex == -1) {
      return normalizeVariables([...normalized, replacementItem]);
    }

    final next = List<ChatTerminalEnvironmentVariable>.from(normalized)
      ..removeAt(originalIndex);
    next.removeWhere((item) => item.normalizedKey == replacementKey);
    final insertionIndex = originalIndex.clamp(0, next.length).toInt();
    next.insert(insertionIndex, replacementItem);
    return next;
  }

  static Map<String, String> buildEnvironmentMap(
    Iterable<ChatTerminalEnvironmentVariable> variables,
  ) {
    final ordered = LinkedHashMap<String, String>();
    for (final item in normalizeVariables(variables)) {
      ordered[item.key] = item.value;
    }
    return ordered;
  }
}
