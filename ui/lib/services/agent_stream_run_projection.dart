import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';

class AgentStreamErrorProjection {
  const AgentStreamErrorProjection({
    required this.messageId,
    required this.text,
    required this.isError,
  });

  final String messageId;
  final String text;
  final bool isError;

  bool get shouldWrite => messageId.trim().isNotEmpty && text.trim().isNotEmpty;
}

class AgentStreamRunProjection {
  const AgentStreamRunProjection._();

  static bool shouldCreateThinkingPlaceholder(AgentStreamEvent event) {
    return event.kind == AgentStreamEventKind.thinkingStarted;
  }

  static bool shouldDropEmptyBaseThinkingPlaceholder(
    ChatMessageModel message, {
    required String taskId,
    required String nextThinkingEntryId,
  }) {
    final basePlaceholderId = '$taskId-thinking-1';
    if (nextThinkingEntryId == basePlaceholderId ||
        message.id != basePlaceholderId) {
      return false;
    }
    final cardData = message.cardData;
    if ((cardData?['type'] ?? '').toString() != 'deep_thinking') {
      return false;
    }
    if ((cardData?['taskID'] ?? '').toString().trim() != taskId) {
      return false;
    }
    return (cardData?['thinkingContent']?.toString() ?? '').trim().isEmpty;
  }

  static bool shouldTrackActiveRun(AgentStreamEventKind kind) {
    return switch (kind) {
      AgentStreamEventKind.thinkingStarted ||
      AgentStreamEventKind.thinkingSnapshot ||
      AgentStreamEventKind.textSnapshot ||
      AgentStreamEventKind.toolStarted ||
      AgentStreamEventKind.toolProgress ||
      AgentStreamEventKind.toolCompleted ||
      AgentStreamEventKind.workbenchProjectCard => true,
      AgentStreamEventKind.completed ||
      AgentStreamEventKind.error ||
      AgentStreamEventKind.clarifyRequired ||
      AgentStreamEventKind.permissionRequired => false,
    };
  }

  static AgentStreamErrorProjection resolveError(
    AgentStreamEvent event,
    List<ChatMessageModel> messages,
  ) {
    final existingIndex = resolveAssistantMessageIndex(messages, event);
    final existingMessage = existingIndex == -1
        ? null
        : messages[existingIndex];
    final existingText = (existingMessage?.text ?? '').trim();
    final eventText = event.text.trim();
    final messageId = resolveAssistantMessageId(
      messages,
      event,
      fallbackSuffix: 'error',
    );

    if (existingText.isNotEmpty) {
      return AgentStreamErrorProjection(
        messageId: messageId,
        text: existingText,
        isError: false,
      );
    }

    final fallbackError = event.errorMessage.trim().isNotEmpty
        ? event.errorMessage.trim()
        : AppTextLocalizer.choose(
            en: "I can't generate a reply right now. Please try again.",
            zh: '暂时无法生成回复，请重试。',
          );
    final resolvedText = eventText.isNotEmpty ? eventText : fallbackError;
    return AgentStreamErrorProjection(
      messageId: messageId,
      text: resolvedText,
      isError: true,
    );
  }

  static String resolveAssistantMessageId(
    List<ChatMessageModel> messages,
    AgentStreamEvent event, {
    String fallbackSuffix = 'text',
  }) {
    final entryId = (event.entryId ?? '').trim();
    if (entryId.isNotEmpty) {
      return entryId;
    }
    final existingIndex = resolveAssistantMessageIndex(messages, event);
    if (existingIndex != -1) {
      return messages[existingIndex].id;
    }
    final taskId = event.taskId.trim();
    if (taskId.isEmpty) {
      return '';
    }
    final suffix = fallbackSuffix.trim().isEmpty
        ? 'text'
        : fallbackSuffix.trim();
    return '$taskId-$suffix';
  }

  static int resolveAssistantMessageIndex(
    List<ChatMessageModel> messages,
    AgentStreamEvent event,
  ) {
    final entryId = (event.entryId ?? '').trim();
    if (entryId.isNotEmpty) {
      final directIndex = messages.indexWhere(
        (message) => message.id == entryId,
      );
      if (directIndex != -1) {
        return directIndex;
      }
    }

    var result = -1;
    var newestSeq = -1;
    for (var index = 0; index < messages.length; index++) {
      final message = messages[index];
      final ref = agentRunMessageRef(message);
      if (ref == null || ref.taskId != event.taskId || !ref.isAssistantText) {
        continue;
      }
      if (ref.sequence >= newestSeq) {
        newestSeq = ref.sequence;
        result = index;
      }
    }
    return result;
  }

  static int compareMessagesOldestFirst(
    ChatMessageModel left,
    ChatMessageModel right,
  ) {
    final timeCompare = left.createAt.compareTo(right.createAt);
    if (timeCompare != 0) {
      return timeCompare;
    }
    final leftSeq = agentRunSequence(left);
    final rightSeq = agentRunSequence(right);
    if (leftSeq != rightSeq) {
      if (leftSeq < 0) return -1;
      if (rightSeq < 0) return 1;
      return leftSeq.compareTo(rightSeq);
    }
    return left.id.compareTo(right.id);
  }
}
