import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_card_projection.dart';
import 'package:ui/services/agent_stream_reducer.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';

enum ThinkingStage {
  thinking(1),
  toolCall(2),
  executing(3),
  complete(4);

  final int value;
  const ThinkingStage(this.value);

  static ThinkingStage fromValue(int value) {
    return ThinkingStage.values.firstWhere(
      (e) => e.value == value,
      orElse: () => ThinkingStage.thinking,
    );
  }
}

mixin AgentStreamHandler<T extends StatefulWidget> on State<T> {
  static const int _maxTerminalOutputChars = 64 * 1024;
  static const int _maxToolSummaryChars = 240;
  static const int _maxToolProgressChars = 240;
  static const int _maxToolPreviewJsonChars = 8 * 1024;
  static const int _maxToolRawResultJsonChars = 24 * 1024;
  static const Map<String, String> _executionPermissionNameToId =
      <String, String>{
        '无障碍权限': kAccessibilityPermissionId,
        'Accessibility': kAccessibilityPermissionId,
        '悬浮窗权限': kOverlayPermissionId,
        'Overlay': kOverlayPermissionId,
        '应用列表读取权限': kInstalledAppsPermissionId,
        'Installed Apps Access': kInstalledAppsPermissionId,
        'Shizuku 权限': kShizukuPermissionId,
        'Shizuku Permission': kShizukuPermissionId,
        '公共文件访问': kPublicStoragePermissionId,
        'Public Storage Access': kPublicStoragePermissionId,
      };

  String? _lastAgentTaskId;
  String? _activeToolCardId;
  String? _activeThinkingCardId;
  String? _pendingAgentTextTaskId;
  final AgentStreamReducer _agentStreamReducer = const AgentStreamReducer();
  final Map<String, AgentStreamTaskState> _agentStreamStates =
      <String, AgentStreamTaskState>{};
  static const Duration _agentConversationPersistDelay = Duration(
    milliseconds: 350,
  );
  Timer? _agentConversationPersistTimer;
  bool _agentConversationPersistInFlight = false;
  bool _agentConversationPersistAgain = false;

  String? get currentDispatchTaskId;

  String get deepThinkingContent;
  set deepThinkingContent(String value);

  bool get isDeepThinking;
  set isDeepThinking(bool value);

  int get currentThinkingStage;
  set currentThinkingStage(int value);

  List<ChatMessageModel> get messages;

  bool get isAiResponding;
  set isAiResponding(bool value);

  void createThinkingCard(String taskID);

  void updateThinkingCard(String taskID);

  void createThinkingCardForAgent(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) {
    createThinkingCard(taskID);
  }

  void updateThinkingCardForAgent(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) {
    updateThinkingCard(taskID);
  }

  void resetDispatchState();

  void fallbackToChat(String taskID);

  void handleExecutableTaskClarify(String taskID, Map<String, dynamic> data);

  Future<void> persistAgentConversation();

  // Agent 文本消息更新后交给具体页面决定是否补充额外结构化内容。
  void onAgentTextMessageUpdated(String messageId, {bool isFinal = true}) {}

  @override
  void dispose() {
    _agentConversationPersistTimer?.cancel();
    _agentConversationPersistTimer = null;
    super.dispose();
  }

  @protected
  Set<String> activeAgentStreamTaskIds() {
    final ids = <String>{};
    final currentTaskId = currentDispatchTaskId?.trim() ?? '';
    if (currentTaskId.isNotEmpty) {
      ids.add(currentTaskId);
    }
    final lastTaskId = _lastAgentTaskId?.trim() ?? '';
    if (isAiResponding && lastTaskId.isNotEmpty) {
      ids.add(lastTaskId);
    }
    final pendingTaskId = _pendingAgentTextTaskId?.trim() ?? '';
    if (pendingTaskId.isNotEmpty) {
      ids.add(pendingTaskId);
    }
    return ids;
  }

  void handleAgentStreamEvent(AgentStreamEvent event) {
    final reduceResult = _agentStreamReducer.reduce(
      _agentStreamStates[event.taskId],
      event,
    );
    if (!reduceResult.accepted) {
      return;
    }
    final thinkingCardToFinalize = _resolveThinkingCardToFinalize(
      reduceResult,
      event,
    );
    _agentStreamStates[event.taskId] = reduceResult.nextState;
    _lastAgentTaskId = event.taskId;
    _activeThinkingCardId = reduceResult.nextState.activeThinkingEntryId;
    final activeToolIds = reduceResult.nextState.activeToolEntryIds;
    if (activeToolIds.isNotEmpty) {
      _activeToolCardId = activeToolIds.last;
    } else if (event.kind == AgentStreamEventKind.toolCompleted) {
      _activeToolCardId = null;
    }
    currentThinkingStage = reduceResult.nextState.thinkingStage;
    isDeepThinking = reduceResult.nextState.isDeepThinking;
    _pendingAgentTextTaskId =
        event.kind == AgentStreamEventKind.textSnapshot && !event.isFinal
        ? event.taskId
        : null;

    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        _applyAgentThinkingStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.textSnapshot:
        _applyAgentTextStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
        _applyAgentToolStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.workbenchProjectCard:
        _applyAgentUiCardStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.clarifyRequired:
        _applyAgentClarifyStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.completed:
        _applyAgentCompletedStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.error:
        _applyAgentErrorStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.permissionRequired:
        _applyAgentPermissionStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
    }
  }

  void _applyAgentThinkingStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) return;
    final hasThinkingContent = event.thinking.trim().isNotEmpty;
    if (hasThinkingContent) {
      deepThinkingContent = event.thinking;
    }
    setState(() {
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      final exists = messages.any((msg) => msg.id == cardId);
      if (exists) {
        updateThinkingCardForAgent(
          event.taskId,
          cardId: cardId,
          thinkingContent: hasThinkingContent ? event.thinking : null,
          isLoading: true,
          stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
          streamMeta: _streamMetaFromEvent(event),
          lockCompleted: false,
        );
      } else if (hasThinkingContent) {
        createThinkingCardForAgent(
          event.taskId,
          cardId: cardId,
          thinkingContent: event.thinking,
          isLoading: true,
          stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
          streamMeta: _streamMetaFromEvent(event),
        );
      }
      isAiResponding = true;
    });
    _persistAgentConversationSafely();
  }

  void _applyAgentTextStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isEmpty || text.isEmpty) return;
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: messageId,
      isFinal: event.isFinal,
    );

    setState(() {
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      _finalizeRunningToolCardsForTask(event.taskId);
      final index = messages.indexWhere((msg) => msg.id == messageId);
      if (index == -1) {
        messages.insert(
          0,
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: {
              'text': text,
              'id': messageId,
              if (event.isFinal && event.prefillTokensPerSecond != null)
                'prefillTokensPerSecond': event.prefillTokensPerSecond,
              if (event.isFinal && event.decodeTokensPerSecond != null)
                'decodeTokensPerSecond': event.decodeTokensPerSecond,
            },
            streamMeta: streamMeta,
          ),
        );
      } else {
        final existing = messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? {});
        content['text'] = text;
        if (event.isFinal && event.prefillTokensPerSecond != null) {
          content['prefillTokensPerSecond'] = event.prefillTokensPerSecond;
        }
        if (event.isFinal && event.decodeTokensPerSecond != null) {
          content['decodeTokensPerSecond'] = event.decodeTokensPerSecond;
        }
        messages[index] = existing.copyWith(
          content: content,
          streamMeta: streamMeta,
        );
      }
      isAiResponding = true;
    });
    onAgentTextMessageUpdated(messageId, isFinal: event.isFinal);
    unawaited(
      VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
        messageId: messageId,
        text: text,
        isFinal: event.isFinal,
      ),
    );
    _persistAgentConversationSafely(immediate: event.isFinal);
  }

  void _applyAgentToolStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final taskId = event.taskId;
    final toolEvent = AgentToolEventData.fromMap(event.raw);
    final cardId = resolveAgentToolCardId(event, raw: event.raw);
    if (cardId.isEmpty) return;

    setState(() {
      isAiResponding = true;
      _finalizeThinkingCardInMessages(taskId, completedThinkingCardId);
      _upsertToolCard(
        streamEvent: event,
        defaultRunningSummary: AppTextLocalizer.choose(
          en: 'Calling tool',
          zh: '正在调用工具',
        ),
        streamMeta: ensureAgentStreamMessageMeta(
          _streamMetaFromEvent(event),
          entryId: cardId,
        ),
      );
      if (event.kind == AgentStreamEventKind.toolCompleted &&
          (toolEvent.toolType == 'workbench' ||
              toolEvent.toolName.startsWith('workbench_'))) {
        _finalizeRunningToolCardsForTask(taskId);
      }
    });
    _persistAgentConversationSafely(
      immediate: event.kind == AgentStreamEventKind.toolCompleted,
    );
  }

  void _applyAgentClarifyStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final text = event.question.trim().isNotEmpty
        ? event.question.trim()
        : event.text.trim();
    final messageId = (event.entryId ?? '').trim();
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: messageId,
      isFinal: true,
    );
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      _finalizeRunningToolCardsForTask(
        event.taskId,
        status: 'interrupted',
        summary: AppTextLocalizer.choose(
          en: 'Waiting for more detail',
          zh: '等待补充信息',
        ),
      );
      if (messageId.isNotEmpty && text.isNotEmpty) {
        final index = messages.indexWhere((msg) => msg.id == messageId);
        if (index == -1) {
          messages.insert(
            0,
            ChatMessageModel(
              id: messageId,
              type: 1,
              user: 2,
              content: {'text': text, 'id': messageId},
              streamMeta: streamMeta,
            ),
          );
        } else {
          messages[index] = messages[index].copyWith(
            content: {'text': text, 'id': messageId},
            streamMeta: streamMeta,
          );
        }
      }
      isAiResponding = false;
    });
    clearAgentStreamSessionState(taskId: event.taskId);
    resetDispatchState();
    _persistAgentConversationSafely(immediate: true);
  }

  void _applyAgentCompletedStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      _finalizeRunningToolCardsForTask(event.taskId);
      isAiResponding = false;
    });
    clearAgentStreamSessionState(taskId: event.taskId);
    resetDispatchState();
    _persistAgentConversationSafely(immediate: true);
  }

  void _applyAgentErrorStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final shouldMarkError = event.raw['persistAsError'] == true;
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      _finalizeRunningToolCardsForTask(
        event.taskId,
        status: 'error',
        summary: event.errorMessage.trim().isEmpty
            ? AppTextLocalizer.choose(
                en: 'Workbench step failed',
                zh: '工作台步骤失败',
              )
            : event.errorMessage.trim(),
      );
      if (shouldMarkError && entryId.isNotEmpty) {
        final index = messages.indexWhere((msg) => msg.id == entryId);
        if (index != -1) {
          messages[index] = messages[index].copyWith(isError: true);
        }
      }
      isAiResponding = false;
    });
    clearAgentStreamSessionState(taskId: event.taskId);
    resetDispatchState();
    _persistAgentConversationSafely(immediate: true);
  }

  void _applyAgentPermissionStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final taskId = event.taskId;
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    final permissionCardId =
        (event.raw['permissionCardId'] ?? '$taskId-permission').toString();
    final executionPermissionIds = _resolveExecutionPermissionIds(
      event.missingPermissions,
    );
    final replyStreamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: messageId,
      isFinal: true,
    );
    final cardStreamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: permissionCardId,
      isFinal: true,
    );
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(taskId, completedThinkingCardId);
      _finalizeRunningToolCardsForTask(
        taskId,
        status: 'interrupted',
        summary: AppTextLocalizer.choose(
          en: 'Waiting for permission',
          zh: '等待权限确认',
        ),
      );
      if (messageId.isNotEmpty && text.isNotEmpty) {
        final index = messages.indexWhere((msg) => msg.id == messageId);
        if (index == -1) {
          messages.insert(
            0,
            ChatMessageModel(
              id: messageId,
              type: 1,
              user: 2,
              content: {'text': text, 'id': messageId},
              streamMeta: replyStreamMeta,
            ),
          );
        } else {
          messages[index] = messages[index].copyWith(
            content: {'text': text, 'id': messageId},
            streamMeta: replyStreamMeta,
          );
        }
      }
      if (executionPermissionIds.isNotEmpty) {
        final cardIndex = messages.indexWhere(
          (msg) => msg.id == permissionCardId,
        );
        final card = ChatMessageModel(
          id: permissionCardId,
          type: 2,
          user: 3,
          content: {
            'cardData': {
              'type': 'permission_section',
              'requiredPermissionIds': executionPermissionIds,
            },
            'id': permissionCardId,
          },
          streamMeta: cardStreamMeta,
        );
        if (cardIndex == -1) {
          messages.insert(0, card);
        } else {
          messages[cardIndex] = messages[cardIndex].copyWith(
            content: {
              'cardData': {
                'type': 'permission_section',
                'requiredPermissionIds': executionPermissionIds,
              },
              'id': permissionCardId,
            },
            streamMeta: cardStreamMeta,
          );
        }
      }
      isAiResponding = false;
    });
    clearAgentStreamSessionState(taskId: event.taskId);
    resetDispatchState();
    _persistAgentConversationSafely(immediate: true);
  }

  Map<String, dynamic> _streamMetaFromEvent(AgentStreamEvent event) {
    return buildAgentStreamMetaFromEvent(event);
  }

  void handleAgentError(String error) {
    final taskId = currentDispatchTaskId ?? _lastAgentTaskId;
    if (taskId == null) return;

    debugPrint('Agent error: $error');

    currentThinkingStage = ThinkingStage.complete.value;
    isDeepThinking = false;
    final thinkingCardId = _resolveThinkingCardId(taskId);
    if (thinkingCardId != null) {
      updateThinkingCardForAgent(
        taskId,
        cardId: thinkingCardId,
        isLoading: false,
        stage: ThinkingStage.complete.value,
      );
    }

    final textId =
        _resolvePendingAgentTextMessageId(taskId) ??
        _nextAgentTextMessageId(taskId);
    final index = messages.indexWhere((msg) => msg.id == textId);
    final existingText = index == -1
        ? ''
        : (messages[index].content?['text'] as String? ?? '');
    final preservedText = existingText.trim();
    final fallbackMessage = error.trim().isEmpty
        ? (AppTextLocalizer.choose(
            en: "I can't generate a reply right now. Please try again.",
            zh: '暂时无法生成回复，请重试。',
          ))
        : (AppTextLocalizer.choose(
            en: "I can't generate a reply right now. Please try again. ${error.trim()}",
            zh: '暂时无法生成回复，请重试。${error.trim()}',
          ));
    setState(() {
      if (index == -1) {
        messages.insert(
          0,
          ChatMessageModel(
            id: textId,
            type: 1,
            user: 2,
            content: {
              'text': preservedText.isNotEmpty
                  ? preservedText
                  : fallbackMessage,
              'id': textId,
            },
            isError: preservedText.isEmpty,
          ),
        );
      } else {
        final existing = messages[index];
        messages[index] = existing.copyWith(
          content: {
            'text': preservedText.isNotEmpty ? preservedText : fallbackMessage,
            'id': textId,
          },
          isError: preservedText.isEmpty,
        );
      }
      isAiResponding = false;
    });
    _pendingAgentTextTaskId = null;
    if (preservedText.isNotEmpty) {
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageCompleted(
          messageId: textId,
          text: preservedText,
        ),
      );
    }

    clearAgentStreamSessionState(taskId: taskId);
    resetDispatchState();
    _persistAgentConversationSafely(immediate: true);
  }

  List<String> _resolveExecutionPermissionIds(List<String> missing) {
    return missing
        .map((item) => item.trim())
        .map((item) => _executionPermissionNameToId[item])
        .whereType<String>()
        .toSet()
        .toList(growable: false);
  }

  String _baseThinkingCardId(String taskId) => '$taskId-thinking';
  String _agentTextBaseId(String taskId) => '$taskId-text';

  String? _resolveThinkingCardToFinalize(
    AgentStreamReduceResult reduceResult,
    AgentStreamEvent event,
  ) {
    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        return reduceResult.isNewThinkingEntry
            ? reduceResult.previousThinkingEntryId
            : null;
      case AgentStreamEventKind.textSnapshot:
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
      case AgentStreamEventKind.workbenchProjectCard:
      case AgentStreamEventKind.completed:
      case AgentStreamEventKind.error:
      case AgentStreamEventKind.permissionRequired:
      case AgentStreamEventKind.clarifyRequired:
        return reduceResult.previousThinkingEntryId;
    }
  }

  void _finalizeThinkingCardInMessages(String taskId, String? cardId) {
    final resolvedCardId = (cardId ?? '').trim();
    if (taskId.trim().isEmpty || resolvedCardId.isEmpty) {
      return;
    }
    final index = messages.indexWhere((msg) => msg.id == resolvedCardId);
    if (index == -1) {
      return;
    }

    final existing = messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final cardData = Map<String, dynamic>.from(content['cardData'] ?? const {});
    final currentStageRaw = cardData['stage'];
    final currentStage = currentStageRaw is num
        ? currentStageRaw.toInt()
        : int.tryParse(currentStageRaw?.toString() ?? '');
    final isLoading = cardData['isLoading'] == true;
    if (!isLoading && currentStage == ThinkingStage.complete.value) {
      return;
    }

    cardData['thinkingContent'] =
        cardData['thinkingContent'] ?? deepThinkingContent;
    cardData['isLoading'] = false;
    cardData['stage'] = ThinkingStage.complete.value;
    cardData['taskID'] = taskId;
    cardData['cardId'] = resolvedCardId;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    content['cardData'] = cardData;
    messages[index] = existing.copyWith(content: content);
  }

  String? _resolveThinkingCardId(String taskId) {
    if (_activeThinkingCardId != null) {
      return _activeThinkingCardId;
    }
    final baseId = _baseThinkingCardId(taskId);
    final exists = messages.any((msg) => msg.id == baseId);
    return exists ? baseId : null;
  }

  String? _resolvePendingAgentTextMessageId(String taskId) {
    if (_pendingAgentTextTaskId != taskId) return null;
    for (final message in messages) {
      if (_isAgentTextMessageForTask(message, taskId)) {
        return message.id;
      }
    }
    return null;
  }

  String _nextAgentTextMessageId(String taskId) {
    final baseId = _agentTextBaseId(taskId);
    var maxSequence = 0;
    for (final message in messages) {
      final sequence = _agentTextMessageSequence(message.id, taskId);
      if (sequence > maxSequence) {
        maxSequence = sequence;
      }
    }
    if (maxSequence == 0) {
      return baseId;
    }
    return '$baseId-${maxSequence + 1}';
  }

  bool _isAgentTextMessageForTask(ChatMessageModel message, String taskId) {
    if (message.type != 1 || message.user != 2) {
      return false;
    }
    return _agentTextMessageSequence(message.id, taskId) > 0;
  }

  int _agentTextMessageSequence(String messageId, String taskId) {
    final baseId = _agentTextBaseId(taskId);
    if (messageId == baseId) {
      return 1;
    }
    if (!messageId.startsWith('$baseId-')) {
      return 0;
    }
    return int.tryParse(messageId.substring(baseId.length + 1)) ?? 0;
  }

  void clearAgentStreamSessionState({String? taskId}) {
    final normalizedTaskId = taskId?.trim() ?? '';
    if (normalizedTaskId.isEmpty) {
      _lastAgentTaskId = null;
      _pendingAgentTextTaskId = null;
      _activeToolCardId = null;
      _agentStreamStates.clear();
      _activeThinkingCardId = null;
    } else {
      if (_lastAgentTaskId == normalizedTaskId) {
        _lastAgentTaskId = null;
      }
      if (_pendingAgentTextTaskId == normalizedTaskId) {
        _pendingAgentTextTaskId = null;
      }
      _agentStreamStates.remove(normalizedTaskId);
      final activeThinkingState = _agentStreamStates.values.firstWhere(
        (state) => state.activeThinkingEntryId != null,
        orElse: () => AgentStreamTaskState(taskId: normalizedTaskId),
      );
      _activeThinkingCardId = activeThinkingState.activeThinkingEntryId;
    }
  }

  void interruptActiveToolCard({String? summary}) {
    final cardId = _activeToolCardId;
    if (cardId == null) return;

    setState(() {
      final index = messages.indexWhere((msg) => msg.id == cardId);
      if (index == -1) {
        return;
      }

      final existingCardData = Map<String, dynamic>.from(
        messages[index].cardData ?? const {},
      );
      if ((existingCardData['status'] ?? '').toString() != 'running') {
        _activeToolCardId = null;
        return;
      }
      existingCardData['status'] = 'interrupted';
      existingCardData['success'] = false;
      if (summary != null && summary.trim().isNotEmpty) {
        existingCardData['summary'] = summary.trim();
      }

      messages[index] = messages[index].copyWith(
        content: {'cardData': existingCardData, 'id': cardId},
      );
    });

    _activeToolCardId = null;
  }

  void _finalizeRunningToolCardsForTask(
    String taskId, {
    String status = 'success',
    String? summary,
  }) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) return;

    final finalizedCardIds = <String>{};
    final isSuccess = status == 'success';
    for (var index = 0; index < messages.length; index++) {
      final message = messages[index];
      final rawCardData = message.cardData;
      if (rawCardData == null || !AgentToolCardPolicy.isToolCard(rawCardData)) {
        continue;
      }
      final cardTaskId = _firstNonEmpty([
        rawCardData['taskId'],
        message.streamMeta?['parentTaskId'],
      ]);
      if (cardTaskId != normalizedTaskId) {
        continue;
      }
      if ((rawCardData['status'] ?? '').toString() != 'running') {
        continue;
      }
      // Terminal and browser tools manage their own streaming lifecycle;
      // skip them here so their stream state is not prematurely closed.
      final toolType = (rawCardData['toolType'] ?? '').toString();
      if (toolType == 'terminal' || toolType == 'browser') {
        continue;
      }

      final cardData = Map<String, dynamic>.from(rawCardData);
      cardData['status'] = status;
      cardData['success'] = isSuccess;
      final normalizedSummary = summary?.trim() ?? '';
      if (normalizedSummary.isNotEmpty &&
          (cardData['summary'] ?? '').toString().trim().isEmpty) {
        cardData['summary'] = normalizedSummary;
      }
      final cardId = _firstNonEmpty([cardData['cardId'], message.id]);
      if (cardId.isNotEmpty) {
        finalizedCardIds.add(cardId);
      }
      messages[index] = message.copyWith(
        content: {'cardData': cardData, 'id': message.id},
      );
    }

    if (finalizedCardIds.isNotEmpty) {
      final state = _agentStreamStates[normalizedTaskId];
      if (state != null) {
        final activeToolEntryIds = Set<String>.from(state.activeToolEntryIds)
          ..removeWhere(finalizedCardIds.contains);
        if (activeToolEntryIds.length != state.activeToolEntryIds.length) {
          _agentStreamStates[normalizedTaskId] = state.copyWith(
            activeToolEntryIds: activeToolEntryIds,
            activeToolFallbackEntryIds: state.activeToolFallbackEntryIds
                .where(activeToolEntryIds.contains)
                .toSet(),
          );
        }
        _activeToolCardId = activeToolEntryIds.isEmpty
            ? null
            : activeToolEntryIds.last;
      }
      if (finalizedCardIds.contains(_activeToolCardId)) {
        _activeToolCardId = null;
      }
    }
  }

  String _firstNonEmpty(Iterable<Object?> values) {
    for (final value in values) {
      final text = value?.toString().trim() ?? '';
      if (text.isNotEmpty) return text;
    }
    return '';
  }

  void _persistAgentConversationSafely({bool immediate = false}) {
    if (immediate) {
      _agentConversationPersistTimer?.cancel();
      _agentConversationPersistTimer = null;
      unawaited(_runAgentConversationPersist());
      return;
    }
    _agentConversationPersistTimer?.cancel();
    _agentConversationPersistTimer = Timer(_agentConversationPersistDelay, () {
      _agentConversationPersistTimer = null;
      unawaited(_runAgentConversationPersist());
    });
  }

  Future<void> _runAgentConversationPersist() async {
    if (!mounted) return;
    if (_agentConversationPersistInFlight) {
      _agentConversationPersistAgain = true;
      return;
    }
    _agentConversationPersistInFlight = true;
    try {
      await persistAgentConversation();
    } catch (e) {
      debugPrint('persistAgentConversation failed: $e');
    } finally {
      _agentConversationPersistInFlight = false;
      if (_agentConversationPersistAgain && mounted) {
        _agentConversationPersistAgain = false;
        _persistAgentConversationSafely();
      }
    }
  }

  void _upsertToolCard({
    required AgentStreamEvent streamEvent,
    required String defaultRunningSummary,
    Map<String, dynamic>? streamMeta,
  }) {
    setState(() {
      final projection = AgentToolCardProjection.projectStreamEvent(
        event: streamEvent,
        messages: messages,
        defaultRunningSummary: defaultRunningSummary,
        streamMeta: streamMeta,
        maxTerminalOutputChars: _maxTerminalOutputChars,
        maxSummaryChars: _maxToolSummaryChars,
        maxProgressChars: _maxToolProgressChars,
        maxPreviewJsonChars: _maxToolPreviewJsonChars,
        maxRawResultJsonChars: _maxToolRawResultJsonChars,
      );
      if (projection == null) {
        return;
      }

      if (projection.isInsert) {
        messages.insert(
          0,
          ChatMessageModel.cardMessage(
            projection.cardData,
            id: projection.cardId,
            streamMeta: projection.streamMeta,
          ).copyWith(
            createAt: DateTime.fromMillisecondsSinceEpoch(
              streamEvent.createdAtMs,
            ),
          ),
        );
      } else {
        messages[projection.existingIndex] = messages[projection.existingIndex]
            .copyWith(
              id: projection.cardId,
              content: {
                'cardData': projection.cardData,
                'id': projection.cardId,
              },
              streamMeta: projection.streamMeta,
            );
      }
    });
  }

  void _applyAgentUiCardStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cards = extractAgentStreamUiCards(event);
    if (cards.isEmpty) return;

    setState(() {
      isAiResponding = true;
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      for (final card in cards) {
        _upsertAgentUiCard(event: event, card: card);
      }
    });
    _persistAgentConversationSafely();
  }

  void _upsertAgentUiCard({
    required AgentStreamEvent event,
    required AgentStreamUiCard card,
  }) {
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: card.id,
    );
    final index = messages.indexWhere(
      (message) =>
          message.id == card.id ||
          (message.cardData?['cardId'] ?? '').toString().trim() == card.id,
    );
    if (index == -1) {
      messages.insert(
        0,
        ChatMessageModel.cardMessage(
          card.cardData,
          id: card.id,
          streamMeta: streamMeta,
        ).copyWith(
          createAt: DateTime.fromMillisecondsSinceEpoch(event.createdAtMs),
        ),
      );
      return;
    }
    final existingCardData = Map<String, dynamic>.from(
      messages[index].cardData ?? const <String, dynamic>{},
    );
    messages[index] = messages[index].copyWith(
      content: {
        'cardData': <String, dynamic>{...existingCardData, ...card.cardData},
        'id': card.id,
      },
      streamMeta: streamMeta,
    );
  }
}
