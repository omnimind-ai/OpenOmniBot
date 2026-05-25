import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/agent_tool_card_policy.dart';

const String kAgentToolCardType = kAgentToolSummaryCardType;

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

  static const int defaultMaxTerminalOutputChars =
      AgentToolTerminalOutputPolicy.defaultMaxChars;
  static const int defaultMaxTerminalOutputLines =
      AgentToolTerminalOutputPolicy.defaultMaxLines;
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
    int maxTerminalOutputLines = defaultMaxTerminalOutputLines,
    int maxSummaryChars = defaultMaxSummaryChars,
    int maxProgressChars = defaultMaxProgressChars,
    int maxPreviewJsonChars = defaultMaxPreviewJsonChars,
    int maxRawResultJsonChars = defaultMaxRawResultJsonChars,
  }) {
    final toolEvent = _AgentToolCardEvent.fromMap(event.raw);
    final identity = AgentToolCardPolicy.identityFromEvent(event);
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
    final existingMessage = existingIndex == -1
        ? null
        : messages[existingIndex];
    final existingCardData = existingMessage == null
        ? const <String, dynamic>{}
        : Map<String, dynamic>.from(
            existingMessage.cardData ?? const <String, dynamic>{},
          );

    final resolvedCardId = AgentToolCardPolicy.firstNonBlank(<Object?>[
      identity.primaryId,
      existingCardData['cardId'],
      existingMessage?.id,
    ]);
    if (resolvedCardId.isEmpty) {
      return null;
    }

    final argsJson = AgentToolCardPolicy.firstNonBlank(<Object?>[
      toolEvent.argsJson,
      existingCardData['argsJson'],
      existingCardData['args'],
    ]);
    final args = AgentToolCardPolicy.decodeJsonMap(argsJson);
    final toolName = AgentToolCardPolicy.firstNonBlank(<Object?>[
      toolEvent.toolName,
      existingCardData['toolName'],
      existingCardData['name'],
    ]);
    final displayName = AgentToolCardPolicy.firstNonBlank(<Object?>[
      toolEvent.displayName,
      existingCardData['displayName'],
      toolName,
    ]);
    final toolType = AgentToolCardPolicy.firstNonBlank(<Object?>[
      toolEvent.toolType,
      existingCardData['toolType'],
      'builtin',
    ]);
    final terminalOutput = AgentToolTerminalOutputPolicy.merge(
      existing: (existingCardData['terminalOutput'] ?? '').toString(),
      full: toolEvent.terminalOutput,
      delta: toolEvent.terminalOutputDelta,
      maxChars: maxTerminalOutputChars,
      maxLines: maxTerminalOutputLines,
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
      AgentToolCardPolicy.firstNonBlank(<Object?>[
        toolEvent.progress,
        existingCardData['progress'],
        toolEvent.summary,
      ]),
      maxChars: maxProgressChars,
    );
    final resultPreviewJson = _compactJsonField(
      AgentToolCardPolicy.firstNonBlank(<Object?>[
        toolEvent.resultPreviewJson,
        existingCardData['resultPreviewJson'],
      ]),
      maxChars: maxPreviewJsonChars,
    );
    final rawResultJson = _compactJsonField(
      AgentToolCardPolicy.firstNonBlank(<Object?>[
        toolEvent.rawResultJson,
        existingCardData['rawResultJson'],
      ]),
      maxChars: maxRawResultJsonChars,
    );
    final artifacts = toolEvent.artifacts.isNotEmpty
        ? toolEvent.artifacts
        : AgentToolCardPolicy.asMapList(existingCardData['artifacts']);
    final actions = toolEvent.actions.isNotEmpty
        ? toolEvent.actions
        : AgentToolCardPolicy.asMapList(existingCardData['actions']);
    final runLogId = AgentToolCardPolicy.firstNonBlank(<Object?>[
      toolEvent.runLogId,
      event.runId,
      existingCardData['runLogId'],
      existingCardData['run_id'],
      event.taskId,
    ]);
    final toolCallId = AgentToolCardPolicy.firstNonBlank(<Object?>[
      toolEvent.toolCallId,
      existingCardData['toolCallId'],
      existingCardData['tool_call_id'],
      toolEvent.callId,
      resolvedCardId,
    ]);
    final toolTaskId = AgentToolCardPolicy.firstNonBlank(<Object?>[
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
      'toolTitle': AgentToolCardPolicy.firstNonBlank(<Object?>[
        toolEvent.toolTitle,
        existingCardData['toolTitle'],
        existingCardData['tool_title'],
        args['tool_title'],
      ]),
      'cardId': resolvedCardId,
      'toolCallId': toolCallId,
      'toolType': toolType,
      'serverName': AgentToolCardPolicy.firstNonBlank(<Object?>[
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
      'terminalStreamState': AgentToolCardPolicy.firstNonBlank(<Object?>[
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
    return AgentToolCardPolicy.normalizeStatus(raw, fallback: fallback);
  }

  static int findExistingIndexForEvent({
    required AgentStreamEvent event,
    required List<ChatMessageModel> messages,
  }) {
    final toolEvent = _AgentToolCardEvent.fromMap(event.raw);
    return _findExistingToolCardIndex(
      messages,
      parentTaskId: event.taskId,
      identity: AgentToolCardPolicy.identityFromEvent(event),
      toolEvent: toolEvent,
      streamEvent: event,
    );
  }

  static int _findExistingToolCardIndex(
    List<ChatMessageModel> messages, {
    required String parentTaskId,
    required AgentToolCardIdentity identity,
    required _AgentToolCardEvent toolEvent,
    required AgentStreamEvent streamEvent,
  }) {
    for (var index = 0; index < messages.length; index++) {
      final cardData = messages[index].cardData;
      if (!AgentToolCardPolicy.isToolCard(cardData)) {
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
      if (!AgentToolCardPolicy.isToolCard(cardData)) {
        continue;
      }
      if (!_isSameParentTask(cardData!, messages[index], parentTaskId)) {
        continue;
      }
      if (AgentToolCardPolicy.normalizeStatus(cardData['status']) !=
          'running') {
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
    AgentToolCardIdentity identity,
  ) {
    if (identity.ids.isEmpty) {
      return false;
    }
    final existingIdentity = AgentToolCardPolicy.identityFromCard(
      message.cardData,
      message: message,
    );
    return existingIdentity.matches(identity);
  }

  static bool _isSameParentTask(
    Map<String, dynamic> cardData,
    ChatMessageModel message,
    String parentTaskId,
  ) {
    final taskId =
        AgentToolCardPolicy.taskIdForMessage(message, cardData: cardData) ?? '';
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
      cardId: _firstString(raw, const ['cardId', 'card_id']),
      toolCallId: (raw['toolCallId'] ?? raw['tool_call_id'] ?? '').toString(),
      callId: (raw['callId'] ?? raw['call_id'] ?? '').toString(),
      toolTaskId: (raw['toolTaskId'] ?? raw['tool_task_id'] ?? '').toString(),
      toolName: _firstString(raw, const ['toolName', 'tool_name']),
      displayName: _firstString(raw, const [
        'displayName',
        'display_name',
        'toolName',
        'tool_name',
      ]),
      toolTitle: _firstString(raw, const ['toolTitle', 'tool_title']),
      toolType: _firstStringOr(raw, const ['toolType', 'tool_type'], 'builtin'),
      serverName: _firstNullableString(raw, const [
        'serverName',
        'server_name',
      ]),
      status: (raw['status'] ?? '').toString(),
      argsJson: _firstString(raw, const ['argsJson', 'args_json', 'args']),
      progress: (raw['progress'] ?? '').toString(),
      summary: (raw['summary'] ?? '').toString(),
      resultPreviewJson: _firstString(raw, const [
        'resultPreviewJson',
        'result_preview_json',
      ]),
      rawResultJson: _firstString(raw, const [
        'rawResultJson',
        'raw_result_json',
      ]),
      terminalOutput: _firstString(raw, const [
        'terminalOutput',
        'terminal_output',
      ]),
      terminalOutputDelta: _firstString(raw, const [
        'terminalOutputDelta',
        'terminal_output_delta',
      ]),
      terminalSessionId: _firstNullableString(raw, const [
        'terminalSessionId',
        'terminal_session_id',
      ]),
      terminalStreamState: _firstString(raw, const [
        'terminalStreamState',
        'terminal_stream_state',
      ]),
      workspaceId: _firstNullableString(raw, const [
        'workspaceId',
        'workspace_id',
      ]),
      interruptedBy: _firstNullableString(raw, const [
        'interruptedBy',
        'interrupted_by',
      ]),
      interruptionReason: _firstNullableString(raw, const [
        'interruptionReason',
        'interruption_reason',
      ]),
      runLogId: _firstString(raw, const ['runLogId', 'run_log_id', 'run_id']),
      artifacts: AgentToolCardPolicy.asMapList(raw['artifacts']),
      actions: AgentToolCardPolicy.asMapList(raw['actions']),
      success: raw['success'] != false,
    );
  }
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

String _firstString(Map<dynamic, dynamic> raw, List<String> keys) {
  return _firstNullableString(raw, keys) ?? '';
}

String _firstStringOr(
  Map<dynamic, dynamic> raw,
  List<String> keys,
  String fallback,
) {
  final value = _firstNullableString(raw, keys);
  return value == null || value.isEmpty ? fallback : value;
}

String? _firstNullableString(Map<dynamic, dynamic> raw, List<String> keys) {
  for (final key in keys) {
    final value = raw[key];
    if (value == null) {
      continue;
    }
    final text = value.toString();
    if (text.trim().isNotEmpty) {
      return text;
    }
  }
  return null;
}
