import 'dart:convert';

import 'package:ui/models/chat_message_model.dart';

class AgentRunTimelineEntry {
  const AgentRunTimelineEntry.message(this.message) : group = null;

  const AgentRunTimelineEntry.group(this.group) : message = null;

  final ChatMessageModel? message;
  final AgentRunTimelineGroup? group;

  bool get isMessage => message != null;

  bool get isUserMessage => message?.user == 1;

  String get key => message?.id ?? 'agent-run-${group!.taskId}';
}

class AgentRunTimelineGroup {
  const AgentRunTimelineGroup({
    required this.taskId,
    required this.visibleMessagesNewestFirst,
    required this.processMessagesNewestFirst,
    this.isActiveRun = false,
  });

  final String taskId;
  final List<ChatMessageModel> visibleMessagesNewestFirst;
  final List<ChatMessageModel> processMessagesNewestFirst;
  final bool isActiveRun;

  List<ChatMessageModel> get visibleMessagesOldestFirst =>
      visibleMessagesNewestFirst.reversed.toList(growable: false);

  List<ChatMessageModel> get processMessagesOldestFirst =>
      processMessagesNewestFirst.reversed.toList(growable: false);

  int get thinkingCount => processMessagesNewestFirst
      .where((message) => agentRunMessageRef(message)?.isThinkingCard ?? false)
      .length;

  int get toolCount => processMessagesNewestFirst
      .where((message) => agentRunMessageRef(message)?.isToolCard ?? false)
      .length;

  String get runLogId {
    final candidates = <Object?>[
      ...visibleMessagesNewestFirst.map(_runLogIdFromMessage),
      ...processMessagesNewestFirst.map(_runLogIdFromMessage),
      taskId,
    ];
    for (final candidate in candidates) {
      final value = candidate?.toString().trim() ?? '';
      if (value.isNotEmpty) {
        return value;
      }
    }
    return '';
  }
}

class AgentRunMessageRef {
  const AgentRunMessageRef({
    required this.taskId,
    required this.entryId,
    required this.kind,
    required this.cardType,
    required this.sequence,
    required this.roundIndex,
    required this.isFinal,
    required this.hasExplicitFinalFlag,
    required this.isAssistantText,
  });

  final String taskId;
  final String entryId;
  final String kind;
  final String cardType;
  final int sequence;
  final int roundIndex;
  final bool isFinal;
  final bool hasExplicitFinalFlag;
  final bool isAssistantText;

  bool get isThinkingCard => cardType == 'deep_thinking';

  bool get isToolCard => cardType == 'agent_tool_summary';

  bool get isPermissionCard => cardType == 'permission_section';

  String get thinkingDedupeKey {
    if (entryId.isNotEmpty) {
      return '$taskId#thinking#$entryId';
    }
    return '$taskId#thinking#$sequence';
  }

  String get entryDedupeKey {
    if (entryId.isNotEmpty) {
      return '$taskId#$kind#$entryId';
    }
    return '$taskId#$kind#$sequence';
  }
}

class AgentRunCompletionExpansionTracker {
  final Set<String> _autoExpandedCompletedTaskIds = <String>{};
  Set<String> _previousActiveTaskIds = <String>{};

  Set<String> get autoExpandedCompletedTaskIds =>
      Set.unmodifiable(_autoExpandedCompletedTaskIds);

  Set<String> effectiveExpandedTaskIds(Iterable<String> manualTaskIds) {
    return <String>{
      ...normalizeAgentRunTaskIds(manualTaskIds),
      ..._autoExpandedCompletedTaskIds,
    };
  }

  bool sync({
    required Iterable<ChatMessageModel> messages,
    required Iterable<String> activeTaskIds,
  }) {
    final currentActiveTaskIds = normalizeAgentRunTaskIds(activeTaskIds);
    final messageTaskIds = agentRunTaskIdsFromMessages(messages);
    final completedTaskIds = _previousActiveTaskIds
        .difference(currentActiveTaskIds)
        .intersection(messageTaskIds);
    final before = Set<String>.from(_autoExpandedCompletedTaskIds);

    _autoExpandedCompletedTaskIds
      ..removeWhere(
        (taskId) =>
            currentActiveTaskIds.contains(taskId) ||
            !messageTaskIds.contains(taskId),
      )
      ..addAll(completedTaskIds);
    _previousActiveTaskIds = currentActiveTaskIds;
    return !_setEquals(before, _autoExpandedCompletedTaskIds);
  }

  bool isTaskExpanded(String taskId, Iterable<String> manualExpandedTaskIds) {
    final normalizedTaskId = _normalizeTaskId(taskId);
    if (normalizedTaskId == null) {
      return false;
    }
    return _autoExpandedCompletedTaskIds.contains(normalizedTaskId) ||
        normalizeAgentRunTaskIds(
          manualExpandedTaskIds,
        ).contains(normalizedTaskId);
  }

  bool isGroupExpanded(
    AgentRunTimelineGroup group,
    Iterable<String> manualExpandedTaskIds,
  ) {
    return group.isActiveRun ||
        isTaskExpanded(group.taskId, manualExpandedTaskIds);
  }

  bool consumeAutoExpandedTask(String taskId) {
    final normalizedTaskId = _normalizeTaskId(taskId);
    if (normalizedTaskId == null) {
      return false;
    }
    return _autoExpandedCompletedTaskIds.remove(normalizedTaskId);
  }

  void clear() {
    _autoExpandedCompletedTaskIds.clear();
    _previousActiveTaskIds = <String>{};
  }
}

List<AgentRunTimelineEntry> buildAgentRunTimelineEntries(
  List<ChatMessageModel> messages, {
  Set<String> activeTaskIds = const <String>{},
}) {
  if (messages.isEmpty) {
    return const <AgentRunTimelineEntry>[];
  }

  final normalizedActiveTaskIds = normalizeAgentRunTaskIds(activeTaskIds);
  final emittedTaskIds = <String>{};
  final entries = <AgentRunTimelineEntry>[];

  for (final message in messages) {
    final taskId = agentRunParentTaskId(message);
    if (taskId == null) {
      entries.add(AgentRunTimelineEntry.message(message));
      continue;
    }
    if (emittedTaskIds.contains(taskId)) {
      if (!_isAgentRunCandidateMessage(message)) {
        entries.add(AgentRunTimelineEntry.message(message));
      }
      continue;
    }

    final group = _buildTimelineGroup(
      messages,
      taskId: taskId,
      isActive: normalizedActiveTaskIds.contains(taskId),
    );
    if (group == null) {
      entries.add(AgentRunTimelineEntry.message(message));
      continue;
    }

    entries.add(AgentRunTimelineEntry.group(group));
    emittedTaskIds.add(taskId);
  }

  return entries;
}

Set<String> normalizeAgentRunTaskIds(Iterable<String> taskIds) {
  return taskIds.map(_normalizeTaskId).whereType<String>().toSet();
}

Set<String> agentRunTaskIdsFromMessages(Iterable<ChatMessageModel> messages) {
  return messages.map(agentRunParentTaskId).whereType<String>().toSet();
}

String? agentRunParentTaskId(ChatMessageModel message) {
  return agentRunMessageRef(message)?.taskId;
}

bool isAgentRunFinalMessage(ChatMessageModel message) {
  return agentRunMessageRef(message)?.isFinal ?? false;
}

String agentRunKind(ChatMessageModel message) {
  return agentRunMessageRef(message)?.kind ?? '';
}

int agentRunSequence(ChatMessageModel message) {
  return agentRunMessageRef(message)?.sequence ?? -1;
}

AgentRunMessageRef? agentRunMessageRef(ChatMessageModel message) {
  final cardData = message.cardData;
  final embeddedStreamMeta = _asStringMap(cardData?['streamMeta']);
  final topLevelStreamMeta = _asStringMap(message.streamMeta);
  final streamMeta = <String, dynamic>{
    if (embeddedStreamMeta != null) ...embeddedStreamMeta,
    if (topLevelStreamMeta != null) ...topLevelStreamMeta,
  };
  final cardType = _cardType(message);
  final entryId = _firstNonEmpty([
    streamMeta['entryId'],
    cardData?['cardId'],
    message.contentId,
    message.id,
  ]);
  final taskId = _firstNonEmpty([
    streamMeta['parentTaskId'],
    cardData?['taskId'],
    cardData?['taskID'],
    _taskIdFromEntryId(entryId),
    _taskIdFromEntryId(message.id),
  ]);
  if (taskId.isEmpty) {
    return null;
  }

  final kind = _firstNonEmpty([
    streamMeta['kind'],
    _kindForCardType(cardType),
    _kindFromEntryId(taskId, entryId),
  ]).toLowerCase();
  final roundIndex =
      _asInt(streamMeta['roundIndex']) ??
      _roundIndexFromEntryId(taskId: taskId, entryId: entryId) ??
      _defaultRoundIndexFor(cardType: cardType, kind: kind);
  return AgentRunMessageRef(
    taskId: taskId,
    entryId: entryId,
    kind: kind,
    cardType: cardType,
    sequence: _asInt(streamMeta['seq']) ?? -1,
    roundIndex: roundIndex,
    isFinal: _asBool(streamMeta['isFinal']),
    hasExplicitFinalFlag: streamMeta.containsKey('isFinal'),
    isAssistantText: message.type == 1 && message.user == 2,
  );
}

AgentRunTimelineGroup? _buildTimelineGroup(
  List<ChatMessageModel> messages, {
  required String taskId,
  required bool isActive,
}) {
  final taskMessages = _dedupeAgentRunMessages(
    messages
        .where((message) => agentRunParentTaskId(message) == taskId)
        .where(_isAgentRunCandidateMessage)
        .toList(growable: false)
      ..sort((left, right) => _compareNewestFirst(left, right)),
  );
  if (taskMessages.isEmpty || (!isActive && taskMessages.length < 2)) {
    return null;
  }

  final primaryVisibleMessage = _resolvePrimaryVisibleMessage(
    taskMessages,
    isActive: isActive,
  );
  if (primaryVisibleMessage == null) {
    if (!isActive) {
      return null;
    }
    return AgentRunTimelineGroup(
      taskId: taskId,
      visibleMessagesNewestFirst: const <ChatMessageModel>[],
      processMessagesNewestFirst: _resolveProcessMessages(taskMessages),
      isActiveRun: true,
    );
  }

  final visibleMessages = _resolveVisibleMessages(
    taskMessages,
    primaryVisibleMessage: primaryVisibleMessage,
  );
  final visibleIds = visibleMessages.map((message) => message.id).toSet();
  final processMessages = taskMessages
      .where((message) => !visibleIds.contains(message.id))
      .toList(growable: false);
  final compactProcessMessages = _resolveProcessMessages(processMessages);
  if (compactProcessMessages.isEmpty) {
    return null;
  }

  return AgentRunTimelineGroup(
    taskId: taskId,
    visibleMessagesNewestFirst: visibleMessages,
    processMessagesNewestFirst: compactProcessMessages,
    isActiveRun: isActive,
  );
}

List<ChatMessageModel> _resolveProcessMessages(
  List<ChatMessageModel> processMessages,
) {
  final oldestFirst = processMessages.toList(growable: false)
    ..sort((left, right) => _compareNewestFirst(right, left));
  final collapsedOldestFirst = <ChatMessageModel>[];
  ChatMessageModel? pendingThinking;

  for (final message in oldestFirst) {
    final isThinking = agentRunMessageRef(message)?.isThinkingCard ?? false;
    if (isThinking) {
      pendingThinking = message;
      continue;
    }
    if (pendingThinking != null) {
      collapsedOldestFirst.add(pendingThinking);
      pendingThinking = null;
    }
    collapsedOldestFirst.add(message);
  }
  if (pendingThinking != null) {
    collapsedOldestFirst.add(pendingThinking);
  }

  return collapsedOldestFirst
    ..sort((left, right) => _compareNewestFirst(left, right));
}

List<ChatMessageModel> _dedupeAgentRunMessages(
  List<ChatMessageModel> messages,
) {
  final emittedKeys = <String>{};
  final deduped = <ChatMessageModel>[];
  for (final message in messages) {
    final key = _semanticDedupeKey(message);
    if (key != null && !emittedKeys.add(key)) {
      continue;
    }
    deduped.add(message);
  }
  return deduped;
}

String? _semanticDedupeKey(ChatMessageModel message) {
  final ref = agentRunMessageRef(message);
  if (ref == null) {
    return null;
  }
  if (ref.isThinkingCard) {
    return ref.thinkingDedupeKey;
  }
  if (ref.isToolCard || ref.isAssistantText || ref.isPermissionCard) {
    return ref.entryDedupeKey;
  }
  return null;
}

bool _isAgentRunCandidateMessage(ChatMessageModel message) {
  if (message.user == 1) {
    return false;
  }
  final ref = agentRunMessageRef(message);
  if (ref == null) {
    return false;
  }
  if (message.type == 1) {
    return ref.isAssistantText;
  }
  if (message.type != 2) {
    return false;
  }
  return ref.isThinkingCard || ref.isToolCard || ref.isPermissionCard;
}

ChatMessageModel? _resolvePrimaryVisibleMessage(
  List<ChatMessageModel> taskMessages, {
  required bool isActive,
}) {
  final aiTextMessages = taskMessages
      .where((message) => agentRunMessageRef(message)?.isAssistantText ?? false)
      .toList(growable: false);
  if (aiTextMessages.isEmpty) {
    return null;
  }

  if (isActive) {
    final activeTextSnapshots = aiTextMessages
        .where((message) => agentRunKind(message) == 'text_snapshot')
        .toList(growable: false);
    if (activeTextSnapshots.isNotEmpty) {
      return _newestBySequence(activeTextSnapshots);
    }
    final terminalMatches = aiTextMessages
        .where(_isTerminalVisibleTextMessage)
        .toList(growable: false);
    if (terminalMatches.isNotEmpty) {
      return _newestBySequence(terminalMatches);
    }
    return null;
  }

  final directFinalMatches = aiTextMessages
      .where((message) => _isTerminalVisibleTextMessage(message))
      .toList(growable: false);
  if (directFinalMatches.isNotEmpty) {
    return _newestBySequence(directFinalMatches);
  }

  final fallbackTextSnapshots = aiTextMessages
      .where(_isLegacyTextSnapshotFallbackCandidate)
      .toList(growable: false);
  if (fallbackTextSnapshots.isEmpty) {
    return null;
  }
  return _newestBySequence(fallbackTextSnapshots);
}

bool _isTerminalVisibleTextMessage(ChatMessageModel message) {
  if (isAgentRunFinalMessage(message)) {
    return true;
  }
  final kind = agentRunKind(message);
  return kind == 'clarify_required' ||
      kind == 'permission_required' ||
      kind == 'error' ||
      message.isError;
}

bool _isLegacyTextSnapshotFallbackCandidate(ChatMessageModel message) {
  if (agentRunKind(message) != 'text_snapshot') {
    return false;
  }
  final ref = agentRunMessageRef(message);
  if (ref == null || !ref.hasExplicitFinalFlag) {
    return true;
  }
  return ref.isFinal;
}

List<ChatMessageModel> _resolveVisibleMessages(
  List<ChatMessageModel> taskMessages, {
  required ChatMessageModel primaryVisibleMessage,
}) {
  final visibleMessages = <ChatMessageModel>[primaryVisibleMessage];
  final primaryKind = agentRunKind(primaryVisibleMessage);
  if (primaryKind == 'permission_required') {
    visibleMessages.addAll(
      taskMessages.where(
        (message) =>
            message.id != primaryVisibleMessage.id &&
            (agentRunMessageRef(message)?.isPermissionCard ?? false),
      ),
    );
  }
  final orderedByNewest = visibleMessages.toList(growable: false)
    ..sort((left, right) => _compareNewestFirst(left, right));
  return orderedByNewest;
}

ChatMessageModel _newestBySequence(List<ChatMessageModel> messages) {
  final sorted = messages.toList(growable: false)
    ..sort((left, right) => _compareNewestFirst(left, right));
  return sorted.first;
}

int _compareNewestFirst(ChatMessageModel left, ChatMessageModel right) {
  final seqCompare = agentRunSequence(right).compareTo(agentRunSequence(left));
  if (seqCompare != 0) {
    return seqCompare;
  }
  return right.createAt.compareTo(left.createAt);
}

String _cardType(ChatMessageModel message) {
  return (message.cardData?['type'] ?? '').toString().trim();
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is Map<String, dynamic>) {
    return value;
  }
  if (value is Map) {
    return value.map((key, value) => MapEntry(key.toString(), value));
  }
  return null;
}

String _firstNonEmpty(Iterable<dynamic> values) {
  for (final value in values) {
    final normalized = value?.toString().trim() ?? '';
    if (normalized.isNotEmpty) {
      return normalized;
    }
  }
  return '';
}

String? _normalizeTaskId(String taskId) {
  final normalized = taskId.trim();
  return normalized.isEmpty ? null : normalized;
}

String _kindForCardType(String cardType) {
  return switch (cardType) {
    'deep_thinking' => 'thinking_snapshot',
    'agent_tool_summary' => 'tool_completed',
    'permission_section' => 'permission_required',
    _ => '',
  };
}

String _kindFromEntryId(String taskId, String entryId) {
  if (entryId == '$taskId-thinking' ||
      entryId.startsWith('$taskId-thinking-')) {
    return 'thinking_snapshot';
  }
  if (entryId == '$taskId-text' || entryId.startsWith('$taskId-text-')) {
    return 'text_snapshot';
  }
  if (entryId.startsWith('$taskId-tool-')) {
    return 'tool_completed';
  }
  if (entryId == '$taskId-permission') {
    return 'permission_required';
  }
  return '';
}

String _taskIdFromEntryId(String entryId) {
  final normalized = entryId.trim();
  if (normalized.isEmpty) {
    return '';
  }
  final patterns = <RegExp>[
    RegExp(r'^(.*)-thinking(?:-\d+)?$'),
    RegExp(r'^(.*)-tool-\d+$'),
    RegExp(r'^(.*)-text(?:-\d+)?$'),
    RegExp(r'^(.*)-permission$'),
  ];
  for (final pattern in patterns) {
    final match = pattern.firstMatch(normalized);
    final taskId = match?.group(1)?.trim() ?? '';
    if (taskId.isNotEmpty) {
      return taskId;
    }
  }
  return '';
}

int? _roundIndexFromEntryId({required String taskId, required String entryId}) {
  final normalizedEntryId = entryId.trim();
  if (normalizedEntryId == '$taskId-thinking' ||
      normalizedEntryId == '$taskId-text') {
    return 1;
  }
  for (final prefix in <String>['$taskId-thinking-', '$taskId-text-']) {
    if (!normalizedEntryId.startsWith(prefix)) {
      continue;
    }
    return int.tryParse(normalizedEntryId.substring(prefix.length).trim()) ?? 1;
  }
  return null;
}

int _defaultRoundIndexFor({required String cardType, required String kind}) {
  if (cardType == 'deep_thinking' || kind == 'thinking_snapshot') {
    return 1;
  }
  if (kind == 'text_snapshot') {
    return 1;
  }
  return 0;
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    final asDouble = value.toDouble();
    if (asDouble.isFinite && asDouble == asDouble.truncateToDouble()) {
      return value.toInt();
    }
  }
  if (value is String) {
    return int.tryParse(value.trim());
  }
  return null;
}

bool _asBool(dynamic value) {
  if (value is bool) {
    return value;
  }
  if (value is String) {
    return value.trim().toLowerCase() == 'true';
  }
  return false;
}

bool _setEquals(Set<String> left, Set<String> right) {
  if (left.length != right.length) {
    return false;
  }
  return left.containsAll(right);
}

String _runLogIdFromMessage(ChatMessageModel message) {
  final cardData = message.cardData;
  final streamMeta = message.streamMeta;
  return _firstNonEmpty([
    streamMeta?['runLogId'],
    streamMeta?['run_log_id'],
    streamMeta?['runId'],
    streamMeta?['run_id'],
    cardData?['runLogId'],
    cardData?['run_log_id'],
    cardData?['runId'],
    cardData?['run_id'],
    _runLogIdFromJsonString(cardData?['resultPreviewJson']),
    _runLogIdFromJsonString(cardData?['rawResultJson']),
    cardData?['taskId'],
    cardData?['taskID'],
  ]);
}

String _runLogIdFromJsonString(dynamic raw) {
  final text = raw?.toString().trim() ?? '';
  if (text.isEmpty) {
    return '';
  }
  try {
    final decoded = jsonDecode(text);
    if (decoded is! Map) {
      return '';
    }
    return _firstNonEmpty([
      decoded['runLogId'],
      decoded['run_log_id'],
      decoded['runId'],
      decoded['run_id'],
      decoded['taskId'],
      decoded['task_id'],
    ]);
  } catch (_) {
    return '';
  }
}
