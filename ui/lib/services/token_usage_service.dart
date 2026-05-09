import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class TokenUsageRecord {
  final int id;
  final int conversationId;
  final bool isLocal;
  final String model;
  final int promptTokens;
  final int completionTokens;
  final int reasoningTokens;
  final int textTokens;
  final int cachedTokens;
  final int createdAt;

  TokenUsageRecord({
    required this.id,
    required this.conversationId,
    required this.isLocal,
    required this.model,
    required this.promptTokens,
    required this.completionTokens,
    required this.reasoningTokens,
    required this.textTokens,
    required this.cachedTokens,
    required this.createdAt,
  });

  /// reasoning_tokens + text_tokens；若服务商未返回明细则回退到 completionTokens
  int get totalTokens {
    final detailed = reasoningTokens + textTokens;
    return detailed > 0 ? detailed : completionTokens;
  }

  factory TokenUsageRecord.fromJson(Map<String, dynamic> json) {
    return TokenUsageRecord(
      id: (json['id'] as num?)?.toInt() ?? 0,
      conversationId: (json['conversationId'] as num?)?.toInt() ?? 0,
      isLocal: json['isLocal'] as bool? ?? false,
      model: json['model'] as String? ?? '',
      promptTokens: (json['promptTokens'] as num?)?.toInt() ?? 0,
      completionTokens: (json['completionTokens'] as num?)?.toInt() ?? 0,
      reasoningTokens: (json['reasoningTokens'] as num?)?.toInt() ?? 0,
      textTokens: (json['textTokens'] as num?)?.toInt() ?? 0,
      cachedTokens: (json['cachedTokens'] as num?)?.toInt() ?? 0,
      createdAt: (json['createdAt'] as num?)?.toInt() ?? 0,
    );
  }
}

class TokenUsageService {
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  static Future<List<TokenUsageRecord>> getRecordsSince(int sinceMs) async {
    try {
      final result = await _assistCore.invokeMethod<List<dynamic>>(
        'getTokenUsageRecords',
        {'since': sinceMs},
      );
      if (result == null) return [];
      return result
          .whereType<Map>()
          .map(
            (item) => TokenUsageRecord.fromJson(
              Map<String, dynamic>.from(item),
            ),
          )
          .toList();
    } on PlatformException catch (e) {
      debugPrint('[TokenUsageService] Failed to get records: ${e.message}');
      return [];
    }
  }
}
