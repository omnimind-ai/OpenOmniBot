import 'dart:convert';

import 'package:ui/models/conversation_model.dart';

class ConversationThreadTarget {
  const ConversationThreadTarget({
    required this.mode,
    this.conversationId,
    this.codexThreadId,
    this.codexRuntime,
    this.isNewConversation = false,
    this.fromNativeRoute = false,
    this.requestKey,
  });

  final int? conversationId;
  final String? codexThreadId;
  final String? codexRuntime;
  final ConversationMode mode;
  final bool isNewConversation;
  final bool fromNativeRoute;
  final String? requestKey;

  const ConversationThreadTarget.newConversation({
    this.mode = ConversationMode.normal,
    this.fromNativeRoute = false,
    this.requestKey,
    this.codexRuntime,
  }) : conversationId = null,
       codexThreadId = null,
       isNewConversation = true;

  const ConversationThreadTarget.existing({
    required this.conversationId,
    this.mode = ConversationMode.normal,
    this.fromNativeRoute = false,
    this.requestKey,
    this.codexThreadId,
    this.codexRuntime,
  }) : isNewConversation = false;

  const ConversationThreadTarget.codexSession({
    required String threadId,
    String runtime = 'remote',
    this.fromNativeRoute = false,
    this.requestKey,
  }) : conversationId = null,
       codexThreadId = threadId,
       codexRuntime = runtime,
       mode = ConversationMode.codex,
       isNewConversation = false;

  bool get hasConversationId => conversationId != null;
  bool get isCodexSessionTarget =>
      mode == ConversationMode.codex &&
      !isNewConversation &&
      (codexThreadId?.trim().isNotEmpty ?? false);
  bool get isRemoteCodexSessionTarget =>
      isCodexSessionTarget && (codexRuntime ?? '').trim() == 'remote';

  String get threadKey {
    final type = isNewConversation ? 'new' : 'existing';
    final idPart = codexThreadId?.trim().isNotEmpty == true
        ? 'codex-thread:${codexThreadId!.trim()}'
        : conversationId?.toString() ?? 'none';
    return '${mode.storageValue}:$type:$idPart';
  }

  ConversationThreadTarget copyWith({
    int? conversationId,
    String? codexThreadId,
    String? codexRuntime,
    ConversationMode? mode,
    bool? isNewConversation,
    bool? fromNativeRoute,
    String? requestKey,
    bool clearRequestKey = false,
  }) {
    return ConversationThreadTarget(
      conversationId: conversationId ?? this.conversationId,
      codexThreadId: codexThreadId ?? this.codexThreadId,
      codexRuntime: codexRuntime ?? this.codexRuntime,
      mode: mode ?? this.mode,
      isNewConversation: isNewConversation ?? this.isNewConversation,
      fromNativeRoute: fromNativeRoute ?? this.fromNativeRoute,
      requestKey: clearRequestKey ? null : (requestKey ?? this.requestKey),
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'conversationId': conversationId,
      if (codexThreadId != null && codexThreadId!.isNotEmpty)
        'codexThreadId': codexThreadId,
      if (codexRuntime != null && codexRuntime!.isNotEmpty)
        'codexRuntime': codexRuntime,
      'mode': mode.storageValue,
      'isNewConversation': isNewConversation,
      'fromNativeRoute': fromNativeRoute,
      if (requestKey != null && requestKey!.isNotEmpty)
        'requestKey': requestKey,
    };
  }

  factory ConversationThreadTarget.fromJson(Map<String, dynamic> json) {
    final conversationIdRaw = json['conversationId'];
    final conversationId = conversationIdRaw is int
        ? conversationIdRaw
        : int.tryParse(conversationIdRaw?.toString() ?? '');
    final isNewConversation = json['isNewConversation'] == true;
    return ConversationThreadTarget(
      conversationId: conversationId,
      mode: ConversationMode.fromStorageValue(json['mode'] as String?),
      isNewConversation: isNewConversation,
      fromNativeRoute: json['fromNativeRoute'] == true,
      requestKey: json['requestKey']?.toString(),
      codexThreadId: json['codexThreadId']?.toString(),
      codexRuntime: json['codexRuntime']?.toString(),
    );
  }

  String toEncodedJson() => jsonEncode(toJson());

  factory ConversationThreadTarget.fromEncodedJson(String raw) {
    final decoded = jsonDecode(raw);
    if (decoded is! Map) {
      throw FormatException('Invalid thread target json');
    }
    return ConversationThreadTarget.fromJson(
      decoded.map((key, value) => MapEntry(key.toString(), value)),
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ConversationThreadTarget &&
        other.conversationId == conversationId &&
        other.codexThreadId == codexThreadId &&
        other.codexRuntime == codexRuntime &&
        other.mode == mode &&
        other.isNewConversation == isNewConversation &&
        other.fromNativeRoute == fromNativeRoute &&
        other.requestKey == requestKey;
  }

  @override
  int get hashCode => Object.hash(
    conversationId,
    codexThreadId,
    codexRuntime,
    mode,
    isNewConversation,
    fromNativeRoute,
    requestKey,
  );

  @override
  String toString() {
    return 'ConversationThreadTarget('
        'conversationId: $conversationId, '
        'codexThreadId: $codexThreadId, '
        'codexRuntime: $codexRuntime, '
        'mode: ${mode.storageValue}, '
        'isNewConversation: $isNewConversation, '
        'fromNativeRoute: $fromNativeRoute, '
        'requestKey: $requestKey'
        ')';
  }
}
