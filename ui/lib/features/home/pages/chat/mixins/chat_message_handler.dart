import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/utils/stream_text_merge.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/utils/data_parser.dart';

import '../../command_overlay/constants/messages.dart';

/// 聊天消息处理 Mixin
/// 负责处理 AI 消息流、VLM 用户输入等功能
mixin ChatMessageHandler<T extends StatefulWidget> on State<T> {
  List<ChatMessageModel> get messages;
  bool get isAiResponding;
  set isAiResponding(bool value);
  Map<String, String> get currentAiMessages;
  TextEditingController get vlmAnswerController;
  String? get vlmInfoQuestion;
  set vlmInfoQuestion(String? value);
  bool get isSubmittingVlmReply;
  set isSubmittingVlmReply(bool value);

  Future<void> saveConversation();

  void addLoadingMessage() {
    final loadingId = '${DateTime.now().millisecondsSinceEpoch}-loading';
    setState(() {
      messages.insert(
        0,
        ChatMessageModel(
          id: loadingId,
          type: 1,
          user: 2,
          content: {'text': '', 'id': loadingId},
          isLoading: true,
        ),
      );
    });
  }

  void removeLatestLoadingIfExists() {
    if (messages.isNotEmpty && messages[0].isLoading) {
      setState(() {
        messages.removeAt(0);
      });
    }
  }

  void handleAiMessage(String taskId, String content, String? type) async {
    final isErrorMessage = type == 'error';
    final isRateLimited = type == 'rate_limited';
    final isSummaryStart = type == 'summary_start';
    final isOpenClawAttachment = type == 'openclaw_attachment';
    final payload = safeDecodeMap(content);
    final payloadAttachments = _parseAttachments(payload['attachments']);
    final prefillTokensPerSecond = extractChatTaskPrefillTokensPerSecond(
      content,
    );
    final decodeTokensPerSecond = extractChatTaskDecodeTokensPerSecond(content);
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    String messageText;
    bool isError;
    bool isSummarizing;
    bool shouldUpdateAiMessage = true;

    final isFirstChunk = !currentAiMessages.containsKey(taskId);
    if (isFirstChunk) {
      removeLatestLoadingIfExists();
    }

    if (isRateLimited) {
      messageText = kRateLimitErrorMessage;
      isError = true;
      isSummarizing = false;
      currentAiMessages.remove(taskId);
    } else if (isErrorMessage) {
      messageText = kNetworkErrorMessage;
      isError = true;
      isSummarizing = false;
      currentAiMessages.remove(taskId);
    } else if (isSummaryStart) {
      messageText = '';
      isError = false;
      isSummarizing = true;
      currentAiMessages[taskId] = '';
    } else if (isOpenClawAttachment) {
      messageText = currentAiMessages[taskId] ?? '';
      isError = false;
      isSummarizing = false;
    } else {
      final text = extractChatTaskText(content, fallbackToRawText: false);
      if (text.isNotEmpty) {
        currentAiMessages[taskId] = mergeLegacyStreamingText(
          currentAiMessages[taskId] ?? '',
          text,
        );
      }
      messageText = currentAiMessages[taskId] ?? '';
      isError = false;
      isSummarizing = false;
      shouldUpdateAiMessage =
          messageText.isNotEmpty ||
          payloadAttachments.isNotEmpty ||
          (hasPerformanceMetrics &&
              messages.any((message) => message.id == taskId));
    }

    if (shouldUpdateAiMessage) {
      updateOrAddAiMessage(
        taskId,
        messageText,
        isError,
        isSummarizing: isSummarizing,
        attachments: payloadAttachments,
        prefillTokensPerSecond: prefillTokensPerSecond,
        decodeTokensPerSecond: decodeTokensPerSecond,
      );
    }
  }

  void updateOrAddAiMessage(
    String taskId,
    String text,
    bool isError, {
    bool isSummarizing = false,
    List<Map<String, dynamic>> attachments = const [],
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
  }) {
    final index = messages.indexWhere((msg) => msg.id == taskId);

    setState(() {
      if (index == -1) {
        final content = <String, dynamic>{'text': text, 'id': taskId};
        if (prefillTokensPerSecond != null) {
          content['prefillTokensPerSecond'] = prefillTokensPerSecond;
        }
        if (decodeTokensPerSecond != null) {
          content['decodeTokensPerSecond'] = decodeTokensPerSecond;
        }
        if (attachments.isNotEmpty) {
          content['attachments'] = attachments;
        }
        messages.insert(
          0,
          ChatMessageModel(
            id: taskId,
            type: 1,
            user: 2,
            content: content,
            isLoading: false,
            isError: isError,
            isSummarizing: isSummarizing,
          ),
        );
      } else {
        final existing = messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? {});
        final existingText = content['text'] as String? ?? '';
        content['text'] = text.isNotEmpty ? text : existingText;
        if (prefillTokensPerSecond != null) {
          content['prefillTokensPerSecond'] = prefillTokensPerSecond;
        }
        if (decodeTokensPerSecond != null) {
          content['decodeTokensPerSecond'] = decodeTokensPerSecond;
        }
        final mergedAttachments = _mergeAttachments(
          _parseAttachments(content['attachments']),
          attachments,
        );
        if (mergedAttachments.isNotEmpty) {
          content['attachments'] = mergedAttachments;
        }
        messages[index] = existing.copyWith(
          content: content,
          isLoading: false,
          isError: isError,
          isSummarizing: isSummarizing,
        );
      }
    });
  }

  void handleAiMessageEnd(String taskId) async {
    setState(() => isAiResponding = false);

    final index = messages.indexWhere((msg) => msg.id == taskId);
    final isErrorMessage = index != -1 && messages[index].isError;
    final messageText = isErrorMessage
        ? (messages[index].content?['text'] as String? ?? '')
        : (currentAiMessages[taskId] ?? '');

    if (messageText.isNotEmpty && index != -1) {
      setState(() {
        final existing = messages[index];
        messages[index] = existing.copyWith(content: existing.content);
      });
    }
    currentAiMessages.remove(taskId);
    saveConversation();
  }

  Future<void> onSubmitVlmInfo() async {
    if (isSubmittingVlmReply || vlmInfoQuestion == null) return;
    final reply = vlmAnswerController.text.trim().isEmpty
        ? (Localizations.localeOf(context).languageCode == 'en'
              ? 'Completed action, continue execution'
              : '已完成操作，继续执行')
        : vlmAnswerController.text.trim();
    setState(() {
      isSubmittingVlmReply = true;
    });
    final success = await AssistsMessageService.provideUserInputToVLMTask(
      reply,
    );
    if (!mounted) return;
    setState(() {
      isSubmittingVlmReply = false;
      if (success) {
        vlmInfoQuestion = null;
        vlmAnswerController.clear();
      }
    });
  }

  void dismissVlmInfo() {
    setState(() {
      vlmInfoQuestion = null;
      vlmAnswerController.clear();
    });
  }

  List<Map<String, dynamic>> _parseAttachments(dynamic raw) {
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  List<Map<String, dynamic>> _mergeAttachments(
    List<Map<String, dynamic>> previous,
    List<Map<String, dynamic>> latest,
  ) {
    if (previous.isEmpty) return latest;
    if (latest.isEmpty) return previous;
    final merged = <Map<String, dynamic>>[];
    final seen = <String>{};
    void add(List<Map<String, dynamic>> source) {
      for (final item in source) {
        final key = _attachmentIdentity(item);
        if (seen.contains(key)) continue;
        seen.add(key);
        merged.add(item);
      }
    }

    add(previous);
    add(latest);
    return merged;
  }

  String _attachmentIdentity(Map<String, dynamic> item) {
    final id = (item['id'] as String? ?? '').trim();
    if (id.isNotEmpty) return id;
    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty) return path;
    final url = (item['url'] as String? ?? '').trim();
    if (url.isNotEmpty) return url;
    final name = (item['name'] as String? ?? '').trim();
    final fileName = (item['fileName'] as String? ?? '').trim();
    return '$name|$fileName|${item['size']}';
  }
}
