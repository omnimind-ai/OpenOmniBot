// ignore_for_file: avoid_web_libraries_in_flutter, deprecated_member_use

import 'dart:async';
import 'dart:convert';
import 'dart:html' as html;

import 'package:http/http.dart' as http;
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';

class WebChatEvent {
  const WebChatEvent({required this.event, required this.data});

  final String event;
  final Map<String, dynamic> data;
}

abstract class ConversationBackend {
  Future<Map<String, dynamic>> bootstrapSession(String token);

  Future<Map<String, dynamic>> bootstrap();

  Future<List<ConversationModel>> listConversations({
    bool includeArchived = true,
    bool archivedOnly = false,
  });

  Future<ConversationModel> createConversation({
    required String title,
    ConversationMode mode = ConversationMode.normal,
  });

  Future<ConversationModel> updateConversation(ConversationModel conversation);

  Future<void> deleteConversation(int conversationId);

  Future<List<ChatMessageModel>> getMessages(
    int conversationId, {
    ConversationMode mode = ConversationMode.normal,
  });

  Future<String> startRun(
    int conversationId, {
    required String userMessage,
    ConversationMode mode = ConversationMode.normal,
    List<Map<String, dynamic>> attachments = const [],
  });

  Future<void> cancelTask(String taskId);

  Future<void> clarifyTask(String taskId, String reply);
}

abstract class RunStreamBackend {
  Stream<WebChatEvent> connect();

  void dispose();
}

abstract class WorkspaceBackend {
  Future<Map<String, dynamic>> bootstrap();

  Future<Map<String, dynamic>> list({
    String? path,
    bool recursive = false,
    int maxDepth = 2,
    int limit = 200,
  });

  Future<Map<String, dynamic>> readFile(String path, {int maxChars = 64000});

  Future<Map<String, dynamic>> writeFile(
    String path,
    String content, {
    bool append = false,
  });

  Future<Map<String, dynamic>> move(
    String sourcePath,
    String targetPath, {
    bool overwrite = false,
  });

  Future<void> delete(String path, {bool recursive = false});
}

abstract class BrowserSessionBackend {
  Future<Map<String, dynamic>> snapshot();

  Future<Map<String, dynamic>> action(Map<String, dynamic> payload);

  String frameUrl({int seed = 0});
}

abstract class ResourcePreviewBackend {
  String workspaceDownloadUrl(String path);
}

class WebChatHttpClient
    implements
        ConversationBackend,
        WorkspaceBackend,
        BrowserSessionBackend,
        ResourcePreviewBackend {
  WebChatHttpClient({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  Uri _uri(String path, [Map<String, String>? queryParameters]) {
    final resolved = Uri.base.resolve(path);
    return resolved.replace(
      queryParameters: queryParameters == null || queryParameters.isEmpty
          ? null
          : queryParameters,
    );
  }

  Future<dynamic> _requestJson(
    String method,
    String path, {
    Map<String, String>? queryParameters,
    Object? body,
  }) async {
    final uri = _uri(path, queryParameters);
    final headers = <String, String>{
      'Accept': 'application/json',
      if (body != null) 'Content-Type': 'application/json',
    };
    late final http.Response response;
    switch (method.toUpperCase()) {
      case 'GET':
        response = await _client.get(uri, headers: headers);
        break;
      case 'POST':
        response = await _client.post(
          uri,
          headers: headers,
          body: body == null ? null : jsonEncode(body),
        );
        break;
      case 'PUT':
        response = await _client.put(
          uri,
          headers: headers,
          body: body == null ? null : jsonEncode(body),
        );
        break;
      case 'PATCH':
        response = await _client.patch(
          uri,
          headers: headers,
          body: body == null ? null : jsonEncode(body),
        );
        break;
      case 'DELETE':
        response = await _client.delete(
          uri,
          headers: headers,
          body: body == null ? null : jsonEncode(body),
        );
        break;
      default:
        throw UnsupportedError('Unsupported method: $method');
    }

    final responseText = utf8.decode(response.bodyBytes);
    final decoded = responseText.isEmpty ? null : jsonDecode(responseText);
    if (response.statusCode >= 400) {
      final message = decoded is Map<String, dynamic>
          ? (decoded['error'] ?? decoded['message'] ?? 'Request failed')
                .toString()
          : 'Request failed (${response.statusCode})';
      throw Exception(message);
    }
    return decoded;
  }

  @override
  Future<Map<String, dynamic>> bootstrapSession(String token) async {
    final response = await _requestJson(
      'POST',
      '/webchat/api/session/bootstrap',
      body: {'token': token},
    );
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  Future<Map<String, dynamic>> bootstrap() async {
    final response = await _requestJson('GET', '/webchat/api/bootstrap');
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  Future<List<ConversationModel>> listConversations({
    bool includeArchived = true,
    bool archivedOnly = false,
  }) async {
    final response = await _requestJson(
      'GET',
      '/webchat/api/conversations',
      queryParameters: <String, String>{
        'includeArchived': includeArchived.toString(),
        'archivedOnly': archivedOnly.toString(),
      },
    );
    return (response as List)
        .whereType<Map>()
        .map(
          (item) => ConversationModel.fromJson(
            Map<String, dynamic>.from(item.cast<String, dynamic>()),
          ),
        )
        .toList();
  }

  @override
  Future<ConversationModel> createConversation({
    required String title,
    ConversationMode mode = ConversationMode.normal,
  }) async {
    final response = await _requestJson(
      'POST',
      '/webchat/api/conversations',
      body: {'title': title, 'mode': mode.storageValue},
    );
    return ConversationModel.fromJson(
      Map<String, dynamic>.from(response as Map),
    );
  }

  @override
  Future<ConversationModel> updateConversation(
    ConversationModel conversation,
  ) async {
    final response = await _requestJson(
      'PATCH',
      '/webchat/api/conversations/${conversation.id}',
      body: conversation.toJson(),
    );
    return ConversationModel.fromJson(
      Map<String, dynamic>.from(response as Map),
    );
  }

  @override
  Future<void> deleteConversation(int conversationId) async {
    await _requestJson('DELETE', '/webchat/api/conversations/$conversationId');
  }

  @override
  Future<List<ChatMessageModel>> getMessages(
    int conversationId, {
    ConversationMode mode = ConversationMode.normal,
  }) async {
    final response = await _requestJson(
      'GET',
      '/webchat/api/conversations/$conversationId/messages',
      queryParameters: {'mode': mode.storageValue},
    );
    return (response as List)
        .whereType<Map>()
        .map(
          (item) => ChatMessageModel.fromJson(
            Map<String, dynamic>.from(item.cast<String, dynamic>()),
          ),
        )
        .toList();
  }

  @override
  Future<String> startRun(
    int conversationId, {
    required String userMessage,
    ConversationMode mode = ConversationMode.normal,
    List<Map<String, dynamic>> attachments = const [],
  }) async {
    final response = await _requestJson(
      'POST',
      '/webchat/api/conversations/$conversationId/runs',
      body: {
        'userMessage': userMessage,
        'conversationMode': mode.storageValue,
        'attachments': attachments,
      },
    );
    return (response as Map)['taskId']?.toString() ?? '';
  }

  @override
  Future<void> cancelTask(String taskId) async {
    await _requestJson('POST', '/webchat/api/tasks/$taskId/cancel');
  }

  @override
  Future<void> clarifyTask(String taskId, String reply) async {
    await _requestJson(
      'POST',
      '/webchat/api/tasks/$taskId/clarify',
      body: {'reply': reply},
    );
  }

  @override
  Future<Map<String, dynamic>> list({
    String? path,
    bool recursive = false,
    int maxDepth = 2,
    int limit = 200,
  }) async {
    final response = await _requestJson(
      'GET',
      '/webchat/api/workspaces',
      queryParameters: <String, String>{
        if (path != null && path.trim().isNotEmpty) 'path': path,
        'recursive': recursive.toString(),
        'maxDepth': '$maxDepth',
        'limit': '$limit',
      },
    );
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  Future<Map<String, dynamic>> readFile(
    String path, {
    int maxChars = 64000,
  }) async {
    final response = await _requestJson(
      'GET',
      '/webchat/api/workspaces/file',
      queryParameters: <String, String>{'path': path, 'maxChars': '$maxChars'},
    );
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  Future<Map<String, dynamic>> writeFile(
    String path,
    String content, {
    bool append = false,
  }) async {
    final response = await _requestJson(
      'PUT',
      '/webchat/api/workspaces/file',
      body: <String, dynamic>{
        'path': path,
        'content': content,
        'append': append,
      },
    );
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  Future<Map<String, dynamic>> move(
    String sourcePath,
    String targetPath, {
    bool overwrite = false,
  }) async {
    final response = await _requestJson(
      'POST',
      '/webchat/api/workspaces/move',
      body: <String, dynamic>{
        'sourcePath': sourcePath,
        'targetPath': targetPath,
        'overwrite': overwrite,
      },
    );
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  Future<void> delete(String path, {bool recursive = false}) async {
    await _requestJson(
      'DELETE',
      '/webchat/api/workspaces/file',
      queryParameters: <String, String>{
        'path': path,
        'recursive': recursive.toString(),
      },
    );
  }

  @override
  Future<Map<String, dynamic>> snapshot() async {
    final response = await _requestJson('GET', '/webchat/api/browser/snapshot');
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  Future<Map<String, dynamic>> action(Map<String, dynamic> payload) async {
    final response = await _requestJson(
      'POST',
      '/webchat/api/browser/action',
      body: payload,
    );
    return Map<String, dynamic>.from(response as Map);
  }

  @override
  String frameUrl({int seed = 0}) {
    return _uri('/webchat/api/browser/frame', <String, String>{
      't': '$seed',
    }).toString();
  }

  @override
  String workspaceDownloadUrl(String path) {
    return _uri('/webchat/api/workspaces/download', <String, String>{
      'path': path,
    }).toString();
  }

  void dispose() {
    _client.close();
  }
}

class WebRunStreamBackend implements RunStreamBackend {
  WebRunStreamBackend();

  static const List<String> _eventNames = <String>[
    'conversation_created',
    'conversation_updated',
    'conversation_deleted',
    'messages_replaced',
    'agent_stream_event',
    'browser_snapshot_updated',
    'workspace_changed',
  ];

  final StreamController<WebChatEvent> _controller =
      StreamController<WebChatEvent>.broadcast();
  html.EventSource? _source;
  bool _connecting = false;

  @override
  Stream<WebChatEvent> connect() {
    if (_source == null && !_connecting) {
      _open();
    }
    return _controller.stream;
  }

  void _open() {
    _connecting = true;
    final source = html.EventSource('/webchat/api/events');
    _source = source;
    for (final eventName in _eventNames) {
      source.addEventListener(eventName, (event) {
        final messageEvent = event as html.MessageEvent;
        final rawData = messageEvent.data;
        if (rawData == null) {
          return;
        }
        final decoded = jsonDecode(rawData.toString());
        if (decoded is Map<String, dynamic>) {
          _controller.add(WebChatEvent(event: eventName, data: decoded));
        } else if (decoded is Map) {
          _controller.add(
            WebChatEvent(
              event: eventName,
              data: Map<String, dynamic>.from(decoded.cast<String, dynamic>()),
            ),
          );
        }
      });
    }
    source.onOpen.listen((_) {
      _connecting = false;
    });
    source.onError.listen((_) {
      _connecting = false;
      _source?.close();
      _source = null;
      Future<void>.delayed(const Duration(seconds: 2), () {
        if (!_controller.isClosed) {
          _open();
        }
      });
    });
  }

  @override
  void dispose() {
    _source?.close();
    _source = null;
    _controller.close();
  }
}
