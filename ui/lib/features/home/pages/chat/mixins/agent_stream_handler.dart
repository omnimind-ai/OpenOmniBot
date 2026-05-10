import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_reducer.dart';
import 'package:ui/services/agent_stream_meta.dart';
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
  static const int _maxTerminalOutputLines = 600;
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
  AgentStreamTaskState? _agentStreamState;

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
    final reduceResult = _agentStreamReducer.reduce(_agentStreamState, event);
    if (!reduceResult.accepted) {
      return;
    }
    final thinkingCardToFinalize = _resolveThinkingCardToFinalize(
      reduceResult,
      event,
    );
    _agentStreamState = reduceResult.nextState;
    _lastAgentTaskId = event.taskId;
    _activeThinkingCardId = reduceResult.nextState.activeThinkingEntryId;
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
      case AgentStreamEventKind.clarifyRequired:
        _applyAgentClarifyStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.completed:
        _applyAgentCompletedStreamEvent(
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
    if (event.thinking.isNotEmpty) {
      deepThinkingContent = event.thinking;
    }
    setState(() {
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      final exists = messages.any((msg) => msg.id == cardId);
      if (exists) {
        updateThinkingCardForAgent(
          event.taskId,
          cardId: cardId,
          thinkingContent: event.thinking.isNotEmpty ? event.thinking : null,
          isLoading: true,
          stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
          streamMeta: _streamMetaFromEvent(event),
          lockCompleted: false,
        );
      } else {
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
    _persistAgentConversationSafely();
  }

  void _applyAgentToolStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final taskId = event.taskId;
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) return;

    final toolEvent = AgentToolEventData.fromMap(event.raw);
    _activeToolCardId = event.kind == AgentStreamEventKind.toolCompleted
        ? null
        : cardId;
    setState(() {
      isAiResponding = true;
      _finalizeThinkingCardInMessages(taskId, completedThinkingCardId);
      _upsertToolCard(
        taskId: taskId,
        cardId: cardId,
        event: toolEvent,
        status: event.kind == AgentStreamEventKind.toolCompleted
            ? _resolveToolStatus(toolEvent)
            : 'running',
        summary: toolEvent.summary.isNotEmpty
            ? toolEvent.summary
            : (LegacyTextLocalizer.isEnglish ? 'Calling tool' : '正在调用工具'),
        progress: toolEvent.progress,
        resultPreviewJson: toolEvent.resultPreviewJson,
        rawResultJson: toolEvent.rawResultJson,
        streamMeta: _streamMetaFromEvent(event),
      );
    });
    _persistAgentConversationSafely();
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
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
  }

  void _applyAgentCompletedStreamEvent({String? completedThinkingCardId}) {
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(
        _lastAgentTaskId ?? '',
        completedThinkingCardId,
      );
      isAiResponding = false;
    });
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
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
      if (shouldMarkError && entryId.isNotEmpty) {
        final index = messages.indexWhere((msg) => msg.id == entryId);
        if (index != -1) {
          messages[index] = messages[index].copyWith(isError: true);
        }
      }
      isAiResponding = false;
    });
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
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
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
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
        ? (LegacyTextLocalizer.isEnglish
              ? "I can't generate a reply right now. Please try again."
              : '暂时无法生成回复，请重试。')
        : (LegacyTextLocalizer.isEnglish
              ? "I can't generate a reply right now. Please try again. ${error.trim()}"
              : '暂时无法生成回复，请重试。${error.trim()}');
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

    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
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

  void clearAgentStreamSessionState() {
    _lastAgentTaskId = null;
    _pendingAgentTextTaskId = null;
    _activeToolCardId = null;
    _agentStreamState = null;
    _activeThinkingCardId = null;
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

  void _persistAgentConversationSafely() {
    Future<void>.microtask(() async {
      try {
        await persistAgentConversation();
      } catch (e) {
        debugPrint('persistAgentConversation failed: $e');
      }
    });
  }

  void _upsertToolCard({
    required String taskId,
    required String cardId,
    required AgentToolEventData event,
    required String status,
    required String summary,
    required String progress,
    required String resultPreviewJson,
    required String rawResultJson,
    Map<String, dynamic>? streamMeta,
  }) {
    setState(() {
      final index = messages.indexWhere((msg) => msg.id == cardId);
      final existingCardData = index == -1
          ? const <String, dynamic>{}
          : Map<String, dynamic>.from(messages[index].cardData ?? const {});
      final existingTerminalOutput = (existingCardData['terminalOutput'] ?? '')
          .toString();
      final terminalOutput = event.toolType == 'terminal'
          ? _resolveTerminalOutput(
              existing: existingTerminalOutput,
              event: event,
            )
          : '';
      final cardData = {
        'type': 'agent_tool_summary',
        'taskId': taskId,
        'cardId': cardId,
        'toolName': event.toolName,
        'displayName': event.displayName,
        'toolTitle': event.toolTitle.isNotEmpty
            ? event.toolTitle
            : (existingCardData['toolTitle'] ?? '').toString(),
        'toolType': event.toolType,
        'serverName': event.serverName,
        'status': status,
        'summary': summary.isNotEmpty
            ? summary
            : (existingCardData['summary'] ?? '').toString(),
        'progress': progress.isNotEmpty
            ? progress
            : (existingCardData['progress'] ?? '').toString(),
        'argsJson': event.argsJson.isNotEmpty
            ? event.argsJson
            : (existingCardData['argsJson'] ?? '').toString(),
        'resultPreviewJson': resultPreviewJson.isNotEmpty
            ? resultPreviewJson
            : (existingCardData['resultPreviewJson'] ?? '').toString(),
        'rawResultJson': rawResultJson.isNotEmpty
            ? rawResultJson
            : (existingCardData['rawResultJson'] ?? '').toString(),
        'terminalOutput': terminalOutput,
        'terminalOutputDelta': event.terminalOutputDelta,
        'terminalSessionId':
            event.terminalSessionId ?? existingCardData['terminalSessionId'],
        'terminalStreamState': event.terminalStreamState.isNotEmpty
            ? event.terminalStreamState
            : (existingCardData['terminalStreamState'] ?? '').toString(),
        'workspaceId': event.workspaceId ?? existingCardData['workspaceId'],
        'artifacts': event.artifacts.isNotEmpty
            ? event.artifacts
            : (existingCardData['artifacts'] ?? const []),
        'actions': event.actions.isNotEmpty
            ? event.actions
            : (existingCardData['actions'] ?? const []),
        'success': event.success,
        'showScheduleAction': event.toolType == 'schedule',
        'showAlarmAction': event.toolType == 'alarm',
      };

      if (index == -1) {
        messages.insert(
          0,
          ChatMessageModel.cardMessage(
            cardData,
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              streamMeta,
              entryId: cardId,
            ),
          ),
        );
      } else {
        messages[index] = messages[index].copyWith(
          content: {'cardData': cardData, 'id': cardId},
          streamMeta: ensureAgentStreamMessageMeta(
            streamMeta ?? messages[index].streamMeta,
            entryId: cardId,
          ),
        );
      }
    });
  }

  String _resolveTerminalOutput({
    required String existing,
    required AgentToolEventData event,
  }) {
    if (event.terminalOutput.isNotEmpty) {
      return _trimTerminalOutput(event.terminalOutput);
    }
    if (event.terminalOutputDelta.isNotEmpty) {
      return _trimTerminalOutput(existing + event.terminalOutputDelta);
    }
    return existing;
  }

  String _resolveToolStatus(AgentToolEventData event) {
    final normalized = event.status.trim().toLowerCase();
    if (normalized.isNotEmpty) {
      return normalized;
    }
    return event.success ? 'success' : 'error';
  }

  String _trimTerminalOutput(String value) {
    if (value.isEmpty) return value;

    var candidate = value;
    if (candidate.length > _maxTerminalOutputChars) {
      candidate = candidate.substring(
        candidate.length - _maxTerminalOutputChars,
      );
    }

    final lines = candidate.split('\n');
    if (lines.length > _maxTerminalOutputLines) {
      candidate = lines
          .sublist(lines.length - _maxTerminalOutputLines)
          .join('\n');
    }

    final wasTrimmed =
        candidate.length < value.length ||
        lines.length > _maxTerminalOutputLines;
    if (!wasTrimmed) {
      return candidate;
    }

    const notice = '[更早输出已省略]\n';
    final body = candidate.startsWith(notice)
        ? candidate.substring(notice.length)
        : candidate;
    final remaining = _maxTerminalOutputChars - notice.length;
    return '$notice${body.substring(body.length > remaining ? body.length - remaining : 0)}';
  }
}
