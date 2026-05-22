import 'package:flutter/services.dart';

class QuickLogItem {
  final String id;
  final String content;
  final int createdAtMillis;
  final int updatedAtMillis;
  final String source;
  final bool shortMemorySynced;

  const QuickLogItem({
    required this.id,
    required this.content,
    required this.createdAtMillis,
    required this.updatedAtMillis,
    required this.source,
    required this.shortMemorySynced,
  });

  factory QuickLogItem.fromMap(Map<dynamic, dynamic> raw) {
    int parseInt(dynamic value) {
      if (value is int) return value;
      if (value is String) return int.tryParse(value) ?? 0;
      return 0;
    }

    return QuickLogItem(
      id: (raw['id'] ?? '').toString(),
      content: (raw['content'] ?? '').toString(),
      createdAtMillis: parseInt(raw['createdAtMillis']),
      updatedAtMillis: parseInt(raw['updatedAtMillis']),
      source: (raw['source'] ?? 'app').toString(),
      shortMemorySynced: raw['shortMemorySynced'] != false,
    );
  }
}

class QuickLogSnapshot {
  final List<QuickLogItem> items;
  final int totalCount;

  const QuickLogSnapshot({
    required this.items,
    required this.totalCount,
  });
}

class QuickLogService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  static Future<QuickLogSnapshot> listLogs({int limit = 200}) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'listQuickLogs',
      {'limit': limit},
    );
    final rawItems = (result?['items'] as List?) ?? const [];
    final items = rawItems
        .whereType<Map>()
        .map((item) => QuickLogItem.fromMap(item))
        .toList();
    final totalRaw = result?['totalCount'];
    final totalCount = totalRaw is int
        ? totalRaw
        : int.tryParse(totalRaw?.toString() ?? '') ?? items.length;
    return QuickLogSnapshot(items: items, totalCount: totalCount);
  }

  static Future<QuickLogItem> addLog(
    String content, {
    String source = 'app',
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'addQuickLog',
      {
        'content': content,
        'source': source,
      },
    );
    final item = result?['item'];
    if (item is! Map) {
      throw PlatformException(
        code: 'ADD_QUICK_LOG_INVALID_RESULT',
        message: 'Missing quick log item in response.',
      );
    }
    return QuickLogItem.fromMap(item);
  }

  static Future<QuickLogItem> updateLog(String id, String content) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'updateQuickLog',
      {
        'id': id,
        'content': content,
      },
    );
    final item = result?['item'];
    if (item is! Map) {
      throw PlatformException(
        code: 'UPDATE_QUICK_LOG_INVALID_RESULT',
        message: 'Missing quick log item in response.',
      );
    }
    return QuickLogItem.fromMap(item);
  }

  static Future<bool> deleteLog(String id) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'deleteQuickLog',
      {'id': id},
    );
    return result?['deleted'] == true;
  }
}
