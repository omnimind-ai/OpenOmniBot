import 'dart:async';

import 'package:flutter/services.dart';

class CodexStatus {
  const CodexStatus({
    required this.connected,
    required this.ready,
    this.version,
    this.error,
    this.codexHome,
    this.cwd,
  });

  final bool connected;
  final bool ready;
  final String? version;
  final String? error;
  final String? codexHome;
  final String? cwd;

  bool get canConnect => ready;

  factory CodexStatus.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    return CodexStatus(
      connected: source['connected'] == true,
      ready: source['ready'] == true,
      version: _stringOrNull(source['version']),
      error: _stringOrNull(source['error']),
      codexHome: _stringOrNull(source['codexHome']),
      cwd: _stringOrNull(source['cwd']),
    );
  }

  static const disconnected = CodexStatus(connected: false, ready: false);
}

class CodexLocalConfig {
  const CodexLocalConfig({
    required this.baseUrl,
    required this.model,
    required this.apiKey,
    this.codexHome,
  });

  final String baseUrl;
  final String model;
  final String apiKey;
  final String? codexHome;

  factory CodexLocalConfig.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    return CodexLocalConfig(
      baseUrl: _stringOrNull(source['baseUrl']) ?? '',
      model: _stringOrNull(source['model']) ?? '',
      apiKey: _stringOrNull(source['apiKey']) ?? '',
      codexHome: _stringOrNull(source['codexHome']),
    );
  }
}

class CodexAppServerService {
  CodexAppServerService._();

  static const MethodChannel _methodChannel = MethodChannel(
    'cn.com.omnimind.bot/CodexAppServer',
  );
  static const EventChannel _eventChannel = EventChannel(
    'cn.com.omnimind.bot/CodexAppServerEvents',
  );

  static final StreamController<Map<String, dynamic>> _eventController =
      StreamController<Map<String, dynamic>>.broadcast();
  static StreamSubscription<dynamic>? _nativeEventSubscription;

  static Stream<Map<String, dynamic>> get events {
    _ensureEventSubscription();
    return _eventController.stream;
  }

  static Future<CodexStatus> status() async {
    final result = await _invokeMap('status');
    return CodexStatus.fromMap(result);
  }

  static Future<CodexStatus> connect() async {
    final result = await _invokeMap('connect');
    return CodexStatus.fromMap(result);
  }

  static Future<CodexStatus> disconnect() async {
    final result = await _invokeMap('disconnect');
    return CodexStatus.fromMap(result);
  }

  static Future<Map<String, dynamic>> startThread({
    int? conversationId,
    String? cwd,
    String? model,
    String? effort,
    String? collaborationMode,
  }) {
    return _invokeMap('thread/start', {
      if (conversationId != null) 'conversationId': conversationId,
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      if (model != null && model.trim().isNotEmpty) 'model': model.trim(),
      if (effort != null && effort.trim().isNotEmpty) 'effort': effort.trim(),
      if (collaborationMode != null && collaborationMode.trim().isNotEmpty)
        'collaborationMode': collaborationMode.trim(),
    });
  }

  static Future<Map<String, dynamic>> resumeThread({
    String? threadId,
    int? conversationId,
  }) {
    return _invokeMap('thread/resume', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  static Future<Map<String, dynamic>> readThread({
    String? threadId,
    int? conversationId,
  }) {
    return _invokeMap('thread/read', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  static Future<Map<String, dynamic>> listThreads({int limit = 50}) {
    return _invokeMap('thread/list', {'limit': limit});
  }

  static Future<Map<String, dynamic>> archiveThread({
    String? threadId,
    int? conversationId,
  }) {
    return _invokeMap('thread/archive', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  static Future<Map<String, dynamic>> unarchiveThread({
    String? threadId,
    int? conversationId,
  }) {
    return _invokeMap('thread/unarchive', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  static Future<Map<String, dynamic>> setThreadName({
    String? threadId,
    int? conversationId,
    required String name,
  }) {
    return _invokeMap('thread/name/set', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      'name': name,
    });
  }

  static Future<Map<String, dynamic>> startTurn({
    String? threadId,
    int? conversationId,
    required String text,
    String? cwd,
    String? approvalPolicy,
    String? approvalsReviewer,
    Map<String, dynamic>? sandboxPolicy,
    String? model,
    String? effort,
    String? collaborationMode,
  }) {
    return _invokeMap('turn/start', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      if (approvalPolicy != null && approvalPolicy.trim().isNotEmpty)
        'approvalPolicy': approvalPolicy.trim(),
      if (approvalsReviewer != null && approvalsReviewer.trim().isNotEmpty)
        'approvalsReviewer': approvalsReviewer.trim(),
      if (sandboxPolicy != null) 'sandboxPolicy': sandboxPolicy,
      if (model != null && model.trim().isNotEmpty) 'model': model.trim(),
      if (effort != null && effort.trim().isNotEmpty) 'effort': effort.trim(),
      if (collaborationMode != null && collaborationMode.trim().isNotEmpty)
        'collaborationMode': collaborationMode.trim(),
      'text': text,
    });
  }

  static Future<Map<String, dynamic>> startReview({
    String? threadId,
    int? conversationId,
    String? cwd,
    Map<String, dynamic>? target,
    String? approvalPolicy,
    String? approvalsReviewer,
    Map<String, dynamic>? sandboxPolicy,
    String? model,
    String? effort,
    String? collaborationMode,
  }) {
    return _invokeMap('review/start', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      'target': target ?? <String, dynamic>{'type': 'uncommittedChanges'},
      if (approvalPolicy != null && approvalPolicy.trim().isNotEmpty)
        'approvalPolicy': approvalPolicy.trim(),
      if (approvalsReviewer != null && approvalsReviewer.trim().isNotEmpty)
        'approvalsReviewer': approvalsReviewer.trim(),
      if (sandboxPolicy != null) 'sandboxPolicy': sandboxPolicy,
      if (model != null && model.trim().isNotEmpty) 'model': model.trim(),
      if (effort != null && effort.trim().isNotEmpty) 'effort': effort.trim(),
      if (collaborationMode != null && collaborationMode.trim().isNotEmpty)
        'collaborationMode': collaborationMode.trim(),
    });
  }

  static Future<Map<String, dynamic>> listModels() {
    return _invokeMap('model/list');
  }

  static Future<Map<String, dynamic>> listCollaborationModes() {
    return _invokeMap('collaborationMode/list');
  }

  static Future<Map<String, dynamic>> readConfig() {
    return _invokeMap('config/read');
  }

  static Future<CodexLocalConfig> readLocalConfig() async {
    final result = await _invokeMap('config/local/read');
    return CodexLocalConfig.fromMap(result);
  }

  static Future<CodexLocalConfig> writeLocalConfig({
    required String baseUrl,
    required String model,
    required String apiKey,
  }) async {
    final result = await _invokeMap('config/local/write', {
      'baseUrl': baseUrl.trim(),
      'model': model.trim(),
      'apiKey': apiKey.trim(),
    });
    return CodexLocalConfig.fromMap(result);
  }

  static Future<Map<String, dynamic>> steerTurn({
    String? threadId,
    int? conversationId,
    String? turnId,
    required String text,
  }) {
    return _invokeMap('turn/steer', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      if (turnId != null) 'turnId': turnId,
      'text': text,
    });
  }

  static Future<Map<String, dynamic>> interruptTurn({
    String? threadId,
    int? conversationId,
    String? turnId,
  }) {
    return _invokeMap('turn/interrupt', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      if (turnId != null) 'turnId': turnId,
    });
  }

  static Future<Map<String, dynamic>> readAccount() {
    return _invokeMap('account/read');
  }

  static Future<Map<String, dynamic>> startLogin({String type = 'chatgpt'}) {
    return _invokeMap('account/login/start', {'type': type});
  }

  static Future<Map<String, dynamic>> cancelLogin() {
    return _invokeMap('account/login/cancel');
  }

  static Future<Map<String, dynamic>> respondToApproval({
    required Object requestId,
    required bool accepted,
  }) {
    return _invokeMap('respondToServerRequest', {
      'requestId': requestId,
      'response': {'decision': accepted ? 'accept' : 'decline'},
    });
  }

  static Future<Map<String, dynamic>> respondToUserInput({
    required Object requestId,
    required String questionId,
    required List<String> answers,
  }) {
    return _invokeMap('respondToServerRequest', {
      'requestId': requestId,
      'response': {
        'answers': {
          questionId: {'answers': answers},
        },
      },
    });
  }

  static void _ensureEventSubscription() {
    if (_nativeEventSubscription != null) return;
    _nativeEventSubscription = _eventChannel.receiveBroadcastStream().listen(
      (event) {
        final normalized = _normalizeMap(event);
        if (normalized != null) {
          _eventController.add(normalized);
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        _eventController.add({
          'method': 'codex/flutterEventError',
          'message': {
            'method': 'codex/flutterEventError',
            'params': {'error': error.toString()},
          },
        });
      },
    );
  }

  static Future<Map<String, dynamic>> _invokeMap(
    String method, [
    Map<String, dynamic> args = const <String, dynamic>{},
  ]) async {
    final result = await _methodChannel.invokeMethod<dynamic>(method, args);
    return _normalizeMap(result) ?? <String, dynamic>{};
  }
}

Map<String, dynamic>? _normalizeMap(dynamic value) {
  if (value is! Map) return null;
  return value.map((key, nestedValue) {
    return MapEntry(key.toString(), _normalizeValue(nestedValue));
  });
}

dynamic _normalizeValue(dynamic value) {
  if (value is Map) {
    return value.map((key, nestedValue) {
      return MapEntry(key.toString(), _normalizeValue(nestedValue));
    });
  }
  if (value is List) {
    return value.map(_normalizeValue).toList();
  }
  return value;
}

String? _stringOrNull(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}
