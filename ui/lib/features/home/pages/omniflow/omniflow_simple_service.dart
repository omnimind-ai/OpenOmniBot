import 'package:flutter/services.dart';

class OmniFlowSimpleSnapshot {
  final Map<String, dynamic> status;
  final List<Map<String, dynamic>> functions;
  final List<Map<String, dynamic>> runLogs;

  const OmniFlowSimpleSnapshot({
    required this.status,
    required this.functions,
    required this.runLogs,
  });
}

class OmniFlowSimpleService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  Future<OmniFlowSimpleSnapshot> load() async {
    final results = await Future.wait([
      _channel.invokeMethod<Map<dynamic, dynamic>>('omniflowSimpleStatus'),
      _channel.invokeMethod<List<dynamic>>('omniflowSimpleListFunctions'),
      _channel.invokeMethod<List<dynamic>>('omniflowSimpleListRunLogs'),
    ]);
    return OmniFlowSimpleSnapshot(
      status: _normalizeMap(results[0]),
      functions: _normalizeList(results[1]),
      runLogs: _normalizeList(results[2]),
    );
  }

  Future<Map<String, dynamic>> executeFunction(String functionId) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'omniflowSimpleExecuteFunction',
      {'functionId': functionId},
    );
    return _normalizeMap(result);
  }

  Future<bool> deleteFunction(String functionId) async {
    final result = await _channel.invokeMethod<bool>(
      'omniflowSimpleDeleteFunction',
      {'functionId': functionId},
    );
    return result == true;
  }

  static List<Map<String, dynamic>> _normalizeList(Object? value) {
    return (value as List<dynamic>? ?? const [])
        .whereType<Map>()
        .map(_normalizeMap)
        .toList();
  }

  static Map<String, dynamic> _normalizeMap(Object? value) {
    return (value as Map<dynamic, dynamic>? ?? const {}).map(
      (key, item) => MapEntry(key.toString(), item),
    );
  }
}
