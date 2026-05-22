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
  });

  final String taskId;
  final List<ChatMessageModel> visibleMessagesNewestFirst;
  final List<ChatMessageModel> processMessagesNewestFirst;

  List<ChatMessageModel> get visibleMessagesOldestFirst =>
      visibleMessagesNewestFirst.reversed.toList(growable: false);

  List<ChatMessageModel> get processMessagesOldestFirst =>
      processMessagesNewestFirst.reversed.toList(growable: false);

  int get thinkingCount => processMessagesNewestFirst
      .where((message) => _cardType(message) == 'deep_thinking')
      .length;

  int get toolCount => processMessagesNewestFirst
      .where((message) => _cardType(message) == 'agent_tool_summary')
      .length;
}

List<AgentRunTimelineEntry> buildAgentRunTimelineEntries(
  List<ChatMessageModel> messages, {
  Set<String> activeTaskIds = const <String>{},
}) {
  if (messages.isEmpty) {
    return const <AgentRunTimelineEntry>[];
  }

  final normalizedActiveTaskIds = activeTaskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
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

String? agentRunParentTaskId(ChatMessageModel message) {
  final raw =
      message.streamMeta?['parentTaskId'] ??
      message.cardData?['taskID'] ??
      message.cardData?['taskId'];
  final normalized = raw?.toString().trim() ?? '';
  return normalized.isEmpty ? null : normalized;
}

bool isAgentRunFinalMessage(ChatMessageModel message) {
  return message.streamMeta?['isFinal'] == true;
}

String agentRunKind(ChatMessageModel message) {
  return (message.streamMeta?['kind'] ?? '').toString().trim().toLowerCase();
}

int agentRunSequence(ChatMessageModel message) {
  final value = message.streamMeta?['seq'];
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
    return int.tryParse(value.trim()) ?? -1;
  }
  return -1;
}

AgentRunTimelineGroup? _buildTimelineGroup(
  List<ChatMessageModel> messages, {
  required String taskId,
  required bool isActive,
}) {
  final taskMessages = messages
      .where((message) => agentRunParentTaskId(message) == taskId)
      .where(_isAgentRunCandidateMessage)
      .toList(growable: false);
  if (taskMessages.length < 2) {
    return null;
  }

  final primaryVisibleMessage = _resolvePrimaryVisibleMessage(
    taskMessages,
    isActive: isActive,
  );
  if (primaryVisibleMessage == null) {
    return null;
  }

  final visibleMessages = _resolveVisibleMessages(
    taskMessages,
    primaryVisibleMessage: primaryVisibleMessage,
  );
  final visibleIds = visibleMessages.map((message) => message.id).toSet();
  final processMessages = taskMessages
      .where((message) => !visibleIds.contains(message.id))
      .toList(growable: false);
  if (processMessages.isEmpty) {
    return null;
  }

  return AgentRunTimelineGroup(
    taskId: taskId,
    visibleMessagesNewestFirst: visibleMessages,
    processMessagesNewestFirst: processMessages,
  );
}

bool _isAgentRunCandidateMessage(ChatMessageModel message) {
  if (message.user == 1) {
    return false;
  }
  if (message.type == 1) {
    return message.user == 2;
  }
  if (message.type != 2) {
    return false;
  }
  final type = _cardType(message);
  return type == 'deep_thinking' ||
      type == 'agent_tool_summary' ||
      type == 'permission_section';
}

ChatMessageModel? _resolvePrimaryVisibleMessage(
  List<ChatMessageModel> taskMessages, {
  required bool isActive,
}) {
  final aiTextMessages = taskMessages
      .where((message) => message.type == 1 && message.user == 2)
      .toList(growable: false);
  if (aiTextMessages.isEmpty) {
    return null;
  }

  if (isActive) {
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
  if (fallbackTextSnapshots.isNotEmpty) {
    return _newestBySequence(fallbackTextSnapshots);
  }

  final cancelledTextMessages = aiTextMessages
      .where(_isCancelledTextMessage)
      .toList(growable: false);
  if (cancelledTextMessages.isNotEmpty) {
    return _newestBySequence(cancelledTextMessages);
  }
  return null;
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
  final streamMeta = message.streamMeta;
  if (streamMeta == null || !streamMeta.containsKey('isFinal')) {
    return true;
  }
  return streamMeta['isFinal'] == true;
}

bool _isCancelledTextMessage(ChatMessageModel message) {
  final text = (message.text ?? '').trim().toLowerCase();
  return text == '任务已取消' || text == 'task canceled' || text == 'task cancelled';
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
            _cardType(message) == 'permission_section',
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
