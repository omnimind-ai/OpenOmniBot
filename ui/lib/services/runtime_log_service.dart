import 'package:flutter/services.dart';

class RuntimeLogEntry {
  final String id;
  final DateTime createdAt;
  final String level;
  final String tag;
  final String message;
  final String? stackTrace;
  final bool isCrash;

  const RuntimeLogEntry({
    required this.id,
    required this.createdAt,
    required this.level,
    required this.tag,
    required this.message,
    this.stackTrace,
    required this.isCrash,
  });

  factory RuntimeLogEntry.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    final createdAtValue = raw['createdAt'];
    final createdAtMillis = createdAtValue is int
        ? createdAtValue
        : int.tryParse(createdAtValue?.toString() ?? '') ?? 0;
    return RuntimeLogEntry(
      id: (raw['id'] ?? '').toString(),
      createdAt: DateTime.fromMillisecondsSinceEpoch(createdAtMillis),
      level: (raw['level'] ?? 'INFO').toString(),
      tag: (raw['tag'] ?? '').toString(),
      message: (raw['message'] ?? '').toString(),
      stackTrace: raw['stackTrace']?.toString(),
      isCrash: raw['isCrash'] == true,
    );
  }

  String get displayTitle {
    final tagPart = tag.isNotEmpty ? '[$tag] ' : '';
    return '$tagPart$message';
  }
}

class RuntimeLogService {
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  static Future<List<RuntimeLogEntry>> listRecent({int limit = 100}) async {
    final result = await _assistCore.invokeMethod<List<dynamic>>(
      'listRuntimeLogs',
      {'limit': limit},
    );
    return (result ?? const [])
        .whereType<Map>()
        .map((item) => RuntimeLogEntry.fromMap(item))
        .toList();
  }

  static Future<void> clear() async {
    await _assistCore.invokeMethod('clearRuntimeLogs');
  }
}
