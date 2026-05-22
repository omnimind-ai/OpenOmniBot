import 'package:ui/models/chat_message_model.dart';

ChatMessageModel? resolveAgentThinkingCardForTask(
  Iterable<ChatMessageModel> messages, {
  required String taskId,
  String? preferredCardId,
}) {
  final normalizedTaskId = taskId.trim();
  if (normalizedTaskId.isEmpty) {
    return null;
  }

  final normalizedPreferredCardId = preferredCardId?.trim() ?? '';
  if (normalizedPreferredCardId.isNotEmpty) {
    for (final message in messages) {
      if (message.id != normalizedPreferredCardId) {
        continue;
      }
      if (_isAgentThinkingCardForTask(message, normalizedTaskId)) {
        return message;
      }
    }
  }

  ChatMessageModel? resolved;
  var resolvedRoundIndex = -1;
  var resolvedSeq = -1;
  var resolvedCreatedAtMillis = -1;

  for (final message in messages) {
    if (!_isAgentThinkingCardForTask(message, normalizedTaskId)) {
      continue;
    }

    final roundIndex = _agentThinkingRoundIndex(message, normalizedTaskId);
    final seq = _streamSeq(message);
    final createdAtMillis = message.createAt.millisecondsSinceEpoch;
    final shouldReplace =
        resolved == null ||
        roundIndex > resolvedRoundIndex ||
        (roundIndex == resolvedRoundIndex && seq > resolvedSeq) ||
        (roundIndex == resolvedRoundIndex &&
            seq == resolvedSeq &&
            createdAtMillis > resolvedCreatedAtMillis);

    if (!shouldReplace) {
      continue;
    }

    resolved = message;
    resolvedRoundIndex = roundIndex;
    resolvedSeq = seq;
    resolvedCreatedAtMillis = createdAtMillis;
  }

  return resolved;
}

bool _isAgentThinkingCardForTask(ChatMessageModel message, String taskId) {
  final baseThinkingCardId = '$taskId-thinking';
  final cardData = message.cardData;
  final cardTaskId = (cardData?['taskID'] ?? '').toString().trim();
  final streamTaskId = (message.streamMeta?['parentTaskId'] ?? '')
      .toString()
      .trim();
  return message.type == 2 &&
      cardData?['type'] == 'deep_thinking' &&
      (cardTaskId == taskId ||
          streamTaskId == taskId ||
          message.id == baseThinkingCardId ||
          message.id.startsWith('$baseThinkingCardId-'));
}

int _agentThinkingRoundIndex(ChatMessageModel message, String taskId) {
  final streamMetaRoundIndex = _asInt(message.streamMeta?['roundIndex']);
  if (streamMetaRoundIndex != null && streamMetaRoundIndex > 0) {
    return streamMetaRoundIndex;
  }

  final baseThinkingCardId = '$taskId-thinking';
  if (message.id == baseThinkingCardId) {
    return 1;
  }
  if (!message.id.startsWith('$baseThinkingCardId-')) {
    return 0;
  }
  final suffix = message.id.substring(baseThinkingCardId.length + 1).trim();
  return int.tryParse(suffix) ?? 0;
}

int _streamSeq(ChatMessageModel message) {
  final streamMetaSeq = _asInt(message.streamMeta?['seq']);
  return streamMetaSeq ?? -1;
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
