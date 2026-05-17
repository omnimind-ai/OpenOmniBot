import 'dart:convert';

import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_meta.dart';

const String kAgentToolCardType = 'agent_tool_summary';

class AgentToolCardProjectionResult {
  const AgentToolCardProjectionResult({
    required this.cardId,
    required this.existingIndex,
    required this.cardData,
    required this.streamMeta,
  });

  final String cardId;
  final int existingIndex;
  final Map<String, dynamic> cardData;
  final Map<String, dynamic>? streamMeta;

  bool get isInsert => existingIndex == -1;
}

class AgentToolCardProjection {
  const AgentToolCardProjection._();

  static const int defaultMaxTerminalOutputChars = 24 * 1024;
  static const int defaultMaxSummaryChars = 240;
  static const int defaultMaxProgressChars = 240;
  static const int defaultMaxPreviewJsonChars = 8 * 1024;
  static const int defaultMaxRawResultJsonChars = 24 * 1024;

  static AgentToolCardProjectionResult? projectStreamEvent({
    required AgentStreamEvent event,
    required List<ChatMessageModel> messages,
    required String defaultRunningSummary,
    Map<String, dynamic>? streamMeta,
    int maxTerminalOutputChars = defaultMaxTerminalOutputChars,
    int maxSummaryChars = defaultMaxSummaryChars,
    int maxProgressChars = defaultMaxProgressChars,
    int maxPreviewJsonChars = defaultMaxPreviewJsonChars,
    int maxRawResultJsonChars = defaultMaxRawResultJsonChars,
  }) {
    final toolEvent = _AgentToolCardEvent.fromMap(event.raw);
    final identity = _ToolCardIdentity.fromEvent(event, toolEvent);
    if (identity.primaryId.isEmpty) {
      return null;
    }

    final existingIndex = _findExistingToolCardIndex(
      messages,
      parentTaskId: event.taskId,
      identity: identity,
      toolEvent: toolEvent,
      streamEvent: event,
    );
    final existingCardData = existingIndex == -1
        ? const <String, dynamic>{}
        : Map<String, dynamic>.from(
            messages[existingIndex].cardData ?? const <String, dynamic>{},
          );

    final resolvedCardId = _firstNonEmpty(<Object?>[
      identity.primaryId,
      existingCardData['cardId'],
      existingIndex == -1 ? null : messages[existingIndex].id,
    ]);
    if (resolvedCardId.isEmpty) {
      return null;
    }

    final argsJson = _firstNonEmpty(<Object?>[
      toolEvent.argsJson,
      existingCardData['argsJson'],
      existingCardData['args'],
    ]);
    final args = _decodeJsonMap(argsJson);
    final toolName = _firstNonEmpty(<Object?>[
      toolEvent.toolName,
      existingCardData['toolName'],
      existingCardData['name'],
    ]);
    final displayName = _firstNonEmpty(<Object?>[
      toolEvent.displayName,
      existingCardData['displayName'],
      toolName,
    ]);
    final toolType = _firstNonEmpty(<Object?>[
      toolEvent.toolType,
      existingCardData['toolType'],
      'builtin',
    ]);
    final terminalOutput = _resolveTerminalOutput(
      existing: (existingCardData['terminalOutput'] ?? '').toString(),
      full: toolEvent.terminalOutput,
      delta: toolEvent.terminalOutputDelta,
      maxChars: maxTerminalOutputChars,
    );
    final summary = _compactTextField(
      _resolveSummary(
        event.kind,
        toolEvent,
        existingCardData: existingCardData,
        defaultRunningSummary: defaultRunningSummary,
      ),
      maxChars: maxSummaryChars,
    );
    final progress = _compactTextField(
      _firstNonEmpty(<Object?>[
        toolEvent.progress,
        existingCardData['progress'],
        toolEvent.summary,
      ]),
      maxChars: maxProgressChars,
    );
    final resultPreviewJson = _compactJsonField(
      _firstNonEmpty(<Object?>[
        toolEvent.resultPreviewJson,
        existingCardData['resultPreviewJson'],
      ]),
      maxChars: maxPreviewJsonChars,
    );
    final rawResultJson = _compactJsonField(
      _firstNonEmpty(<Object?>[
        toolEvent.rawResultJson,
        existingCardData['rawResultJson'],
      ]),
      maxChars: maxRawResultJsonChars,
    );
    final artifacts = toolEvent.artifacts.isNotEmpty
        ? toolEvent.artifacts
        : _asMapList(existingCardData['artifacts']);
    final actions = toolEvent.actions.isNotEmpty
        ? toolEvent.actions
        : _asMapList(existingCardData['actions']);
    final runLogId = _firstNonEmpty(<Object?>[
      toolEvent.runLogId,
      event.runId,
      existingCardData['runLogId'],
      existingCardData['run_id'],
      event.taskId,
    ]);
    final toolCallId = _firstNonEmpty(<Object?>[
      toolEvent.toolCallId,
      existingCardData['toolCallId'],
      existingCardData['tool_call_id'],
      toolEvent.callId,
      resolvedCardId,
    ]);
    final toolTaskId = _firstNonEmpty(<Object?>[
      toolEvent.toolTaskId,
      existingCardData['toolTaskId'],
      existingCardData['tool_task_id'],
    ]);
    final status = _resolveStatus(
      event.kind,
      toolEvent,
      existingStatus: existingCardData['status'],
    );
    final cardData = <String, dynamic>{
      ...existingCardData,
      'type': kAgentToolCardType,
      'taskId': event.taskId,
      'runLogId': runLogId,
      'run_id': runLogId,
      'toolName': toolName,
      'displayName': displayName,
      'toolTitle': _firstNonEmpty(<Object?>[
        toolEvent.toolTitle,
        existingCardData['toolTitle'],
        existingCardData['tool_title'],
        args['tool_title'],
      ]),
      'cardId': resolvedCardId,
      'toolCallId': toolCallId,
      'toolType': toolType,
      'serverName': _firstNonEmpty(<Object?>[
        toolEvent.serverName,
        existingCardData['serverName'],
        existingCardData['server_name'],
      ]),
      'status': status,
      'summary': summary,
      'progress': progress,
      'argsJson': argsJson,
      'resultPreviewJson': resultPreviewJson,
      'rawResultJson': rawResultJson,
      'terminalOutput': toolType == 'terminal' ? terminalOutput : '',
      'terminalOutputDelta': toolEvent.terminalOutputDelta,
      'terminalSessionId':
          toolEvent.terminalSessionId ?? existingCardData['terminalSessionId'],
      'terminalStreamState': _firstNonEmpty(<Object?>[
        toolEvent.terminalStreamState,
        existingCardData['terminalStreamState'],
      ]),
      'workspaceId': toolEvent.workspaceId ?? existingCardData['workspaceId'],
      'interruptedBy':
          toolEvent.interruptedBy ?? existingCardData['interruptedBy'],
      'interruptionReason':
          toolEvent.interruptionReason ??
          existingCardData['interruptionReason'],
      'artifacts': artifacts,
      'actions': actions,
      'success': status == 'success'
          ? true
          : status == 'error'
          ? false
          : toolEvent.success,
      'showTerminalOutput': toolType == 'terminal' || terminalOutput.isNotEmpty,
      'showRawResult': rawResultJson.isNotEmpty,
      'showArtifactAction': artifacts.isNotEmpty,
      'showScheduleAction': toolType == 'schedule',
      'showAlarmAction': toolType == 'alarm',
    };
    if (toolTaskId.isNotEmpty) {
      cardData['toolTaskId'] = toolTaskId;
    }
    if (toolEvent.callId.isNotEmpty) {
      cardData['callId'] = toolEvent.callId;
    }

    final resolvedStreamMeta = ensureAgentStreamMessageMeta(
      streamMeta ?? buildAgentStreamMetaFromEvent(event),
      entryId: resolvedCardId,
      parentTaskId: event.taskId,
      kind: event.kind.value,
      seq: event.seq,
      roundIndex: event.roundIndex,
      isFinal: event.kind == AgentStreamEventKind.toolCompleted,
    );
    return AgentToolCardProjectionResult(
      cardId: resolvedCardId,
      existingIndex: existingIndex,
      cardData: cardData,
      streamMeta: resolvedStreamMeta,
    );
  }

  static String normalizeStatus(Object? raw, {String fallback = 'running'}) {
    final status = raw?.toString().trim().toLowerCase() ?? '';
    if (status.isEmpty) {
      return fallback;
    }
    return switch (status) {
      'failed' || 'failure' => 'error',
      'cancelled' || 'canceled' || 'aborted' => 'interrupted',
      _ => status,
    };
  }

  static int findExistingIndexForEvent({
    required AgentStreamEvent event,
    required List<ChatMessageModel> messages,
  }) {
    final toolEvent = _AgentToolCardEvent.fromMap(event.raw);
    return _findExistingToolCardIndex(
      messages,
      parentTaskId: event.taskId,
      identity: _ToolCardIdentity.fromEvent(event, toolEvent),
      toolEvent: toolEvent,
      streamEvent: event,
    );
  }

  static int _findExistingToolCardIndex(
    List<ChatMessageModel> messages, {
    required String parentTaskId,
    required _ToolCardIdentity identity,
    required _AgentToolCardEvent toolEvent,
    required AgentStreamEvent streamEvent,
  }) {
    for (var index = 0; index < messages.length; index++) {
      final cardData = messages[index].cardData;
      if (!_isToolCard(cardData)) {
        continue;
      }
      if (_identityMatches(messages[index], identity)) {
        return index;
      }
    }

    if (streamEvent.kind == AgentStreamEventKind.toolStarted) {
      return -1;
    }

    for (var index = 0; index < messages.length; index++) {
      final cardData = messages[index].cardData;
      if (!_isToolCard(cardData)) {
        continue;
      }
      if (!_isSameParentTask(cardData!, messages[index], parentTaskId)) {
        continue;
      }
      if (normalizeStatus(cardData['status']) != 'running') {
        continue;
      }
      if (!_isSameToolSurface(cardData, toolEvent)) {
        continue;
      }
      return index;
    }
    return -1;
  }

  static bool _identityMatches(
    ChatMessageModel message,
    _ToolCardIdentity identity,
  ) {
    if (identity.allIds.isEmpty) {
      return false;
    }
    final existingIds = _messageToolIdentities(message);
    return existingIds.any(identity.allIds.contains);
  }

  static bool _isToolCard(Map<String, dynamic>? cardData) {
    return (cardData?['type'] ?? '').toString() == kAgentToolCardType;
  }

  static bool _isSameParentTask(
    Map<String, dynamic> cardData,
    ChatMessageModel message,
    String parentTaskId,
  ) {
    final taskId = _firstNonEmpty(<Object?>[
      cardData['taskId'],
      cardData['taskID'],
      message.streamMeta?['parentTaskId'],
    ]);
    return taskId == parentTaskId;
  }

  static bool _isSameToolSurface(
    Map<String, dynamic> cardData,
    _AgentToolCardEvent toolEvent,
  ) {
    final existingToolName = (cardData['toolName'] ?? '').toString().trim();
    final incomingToolName = toolEvent.toolName.trim();
    if (existingToolName.isNotEmpty &&
        incomingToolName.isNotEmpty &&
        existingToolName != incomingToolName) {
      return false;
    }

    final existingToolType = (cardData['toolType'] ?? '').toString().trim();
    final incomingToolType = toolEvent.toolType.trim();
    if (existingToolType.isNotEmpty &&
        incomingToolType.isNotEmpty &&
        existingToolType != incomingToolType) {
      return false;
    }

    final existingServer = (cardData['serverName'] ?? '').toString().trim();
    final incomingServer = toolEvent.serverName?.trim() ?? '';
    return existingServer.isEmpty ||
        incomingServer.isEmpty ||
        existingServer == incomingServer;
  }

  static Set<String> _messageToolIdentities(ChatMessageModel message) {
    final cardData = message.cardData ?? const <String, dynamic>{};
    return _normalizeIdentitySet(<Object?>[
      message.id,
      message.contentId,
      cardData['cardId'],
      cardData['toolCallId'],
      cardData['tool_call_id'],
      cardData['callId'],
      cardData['call_id'],
      cardData['toolTaskId'],
      cardData['tool_task_id'],
      message.streamMeta?['entryId'],
    ]);
  }

  static String _resolveTerminalOutput({
    required String existing,
    required String full,
    required String delta,
    required int maxChars,
  }) {
    if (full.isNotEmpty) {
      return _trimTerminalOutput(full, maxChars: maxChars);
    }
    if (delta.isNotEmpty) {
      return _trimTerminalOutput(existing + delta, maxChars: maxChars);
    }
    return _trimTerminalOutput(existing, maxChars: maxChars);
  }

  static String _resolveSummary(
    AgentStreamEventKind kind,
    _AgentToolCardEvent event, {
    required Map<String, dynamic> existingCardData,
    required String defaultRunningSummary,
  }) {
    final incoming = event.summary.trim();
    if (incoming.isNotEmpty) {
      return incoming;
    }
    final existing = (existingCardData['summary'] ?? '').toString().trim();
    final isDefaultRunningSummary =
        existing.isNotEmpty && existing == defaultRunningSummary.trim();
    if (kind == AgentStreamEventKind.toolCompleted && isDefaultRunningSummary) {
      return '';
    }
    if (existing.isNotEmpty) {
      return existing;
    }
    return kind == AgentStreamEventKind.toolCompleted
        ? ''
        : defaultRunningSummary;
  }

  static String _resolveStatus(
    AgentStreamEventKind kind,
    _AgentToolCardEvent event, {
    Object? existingStatus,
  }) {
    final normalized = normalizeStatus(event.status, fallback: '');
    if (kind == AgentStreamEventKind.toolCompleted) {
      if (normalized.isNotEmpty && normalized != 'running') {
        return normalized;
      }
      return event.success ? 'success' : 'error';
    }
    if (normalized == 'error' ||
        normalized == 'interrupted' ||
        normalized == 'timeout') {
      return normalized;
    }
    final existing = normalizeStatus(existingStatus, fallback: '');
    if (existing == 'error' || existing == 'interrupted') {
      return existing;
    }
    return 'running';
  }
}

class _ToolCardIdentity {
  const _ToolCardIdentity({required this.primaryId, required this.allIds});

  final String primaryId;
  final Set<String> allIds;

  static _ToolCardIdentity fromEvent(
    AgentStreamEvent event,
    _AgentToolCardEvent toolEvent,
  ) {
    final primaryId = _firstNonEmpty(<Object?>[
      toolEvent.toolCallId,
      event.raw['toolCallId'],
      event.raw['tool_call_id'],
      toolEvent.callId,
      event.raw['callId'],
      event.raw['call_id'],
      toolEvent.cardId,
      event.raw['cardId'],
      toolEvent.toolTaskId,
      event.raw['toolTaskId'],
      event.raw['tool_task_id'],
      event.entryId,
    ]);
    final ids = _normalizeIdentitySet(<Object?>[
      toolEvent.cardId,
      toolEvent.toolCallId,
      toolEvent.callId,
      toolEvent.toolTaskId,
      event.raw['cardId'],
      event.raw['toolCallId'],
      event.raw['tool_call_id'],
      event.raw['callId'],
      event.raw['call_id'],
      event.raw['toolTaskId'],
      event.raw['tool_task_id'],
      event.entryId,
    ]);
    return _ToolCardIdentity(primaryId: primaryId, allIds: ids);
  }
}

class _AgentToolCardEvent {
  const _AgentToolCardEvent({
    required this.toolName,
    required this.displayName,
    required this.toolType,
    this.cardId = '',
    this.toolCallId = '',
    this.callId = '',
    this.toolTaskId = '',
    this.toolTitle = '',
    this.serverName,
    this.status = '',
    this.argsJson = '',
    this.progress = '',
    this.summary = '',
    this.resultPreviewJson = '',
    this.rawResultJson = '',
    this.terminalOutput = '',
    this.terminalOutputDelta = '',
    this.terminalSessionId,
    this.terminalStreamState = '',
    this.workspaceId,
    this.interruptedBy,
    this.interruptionReason,
    this.runLogId = '',
    this.artifacts = const <Map<String, dynamic>>[],
    this.actions = const <Map<String, dynamic>>[],
    this.success = true,
  });

  final String cardId;
  final String toolCallId;
  final String callId;
  final String toolTaskId;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String toolType;
  final String? serverName;
  final String status;
  final String argsJson;
  final String progress;
  final String summary;
  final String resultPreviewJson;
  final String rawResultJson;
  final String terminalOutput;
  final String terminalOutputDelta;
  final String? terminalSessionId;
  final String terminalStreamState;
  final String? workspaceId;
  final String? interruptedBy;
  final String? interruptionReason;
  final String runLogId;
  final List<Map<String, dynamic>> artifacts;
  final List<Map<String, dynamic>> actions;
  final bool success;

  factory _AgentToolCardEvent.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const <dynamic, dynamic>{};
    return _AgentToolCardEvent(
      cardId: (raw['cardId'] ?? '').toString(),
      toolCallId: (raw['toolCallId'] ?? raw['tool_call_id'] ?? '').toString(),
      callId: (raw['callId'] ?? raw['call_id'] ?? '').toString(),
      toolTaskId: (raw['toolTaskId'] ?? raw['tool_task_id'] ?? '').toString(),
      toolName: (raw['toolName'] ?? '').toString(),
      displayName: (raw['displayName'] ?? raw['toolName'] ?? '').toString(),
      toolTitle: (raw['toolTitle'] ?? raw['tool_title'] ?? '').toString(),
      toolType: (raw['toolType'] ?? 'builtin').toString(),
      serverName: raw['serverName']?.toString(),
      status: (raw['status'] ?? '').toString(),
      argsJson: (raw['argsJson'] ?? raw['args'] ?? '').toString(),
      progress: (raw['progress'] ?? '').toString(),
      summary: (raw['summary'] ?? '').toString(),
      resultPreviewJson: (raw['resultPreviewJson'] ?? '').toString(),
      rawResultJson: (raw['rawResultJson'] ?? '').toString(),
      terminalOutput: (raw['terminalOutput'] ?? '').toString(),
      terminalOutputDelta: (raw['terminalOutputDelta'] ?? '').toString(),
      terminalSessionId: raw['terminalSessionId']?.toString(),
      terminalStreamState: (raw['terminalStreamState'] ?? '').toString(),
      workspaceId: raw['workspaceId']?.toString(),
      interruptedBy: raw['interruptedBy']?.toString(),
      interruptionReason: raw['interruptionReason']?.toString(),
      runLogId: (raw['runLogId'] ?? raw['run_id'] ?? '').toString(),
      artifacts: _asMapList(raw['artifacts']),
      actions: _asMapList(raw['actions']),
      success: raw['success'] != false,
    );
  }
}

Set<String> _normalizeIdentitySet(Iterable<Object?> values) {
  final result = <String>{};
  for (final value in values) {
    final normalized = value?.toString().trim() ?? '';
    if (normalized.isNotEmpty) {
      result.add(normalized);
    }
  }
  return result;
}

String _firstNonEmpty(Iterable<Object?> values) {
  for (final value in values) {
    final normalized = value?.toString().trim() ?? '';
    if (normalized.isNotEmpty) {
      return normalized;
    }
  }
  return '';
}

String _compactTextField(String value, {required int maxChars}) {
  final normalized = value.trim();
  if (normalized.length <= maxChars) {
    return normalized;
  }
  return '${normalized.substring(0, maxChars)}...(truncated)';
}

String _compactJsonField(String value, {required int maxChars}) {
  final normalized = value.trim();
  if (normalized.length <= maxChars) {
    return normalized;
  }
  return '${normalized.substring(0, maxChars)}\n...(truncated)';
}

String _trimTerminalOutput(String value, {required int maxChars}) {
  if (value.length <= maxChars) {
    return value;
  }
  final start = value.length - maxChars;
  final newline = value.indexOf('\n', start);
  if (newline >= 0 && newline + 1 < value.length) {
    return value.substring(newline + 1);
  }
  return value.substring(start);
}

Map<String, dynamic> _decodeJsonMap(String raw) {
  final text = raw.trim();
  if (text.isEmpty) {
    return const <String, dynamic>{};
  }
  try {
    final decoded = jsonDecode(text);
    if (decoded is Map) {
      return decoded.map((key, value) => MapEntry(key.toString(), value));
    }
  } catch (_) {}
  return const <String, dynamic>{};
}

List<Map<String, dynamic>> _asMapList(dynamic raw) {
  if (raw is! List) {
    return const <Map<String, dynamic>>[];
  }
  return raw
      .whereType<Map>()
      .map((item) => item.map((key, value) => MapEntry(key.toString(), value)))
      .toList(growable: false);
}
