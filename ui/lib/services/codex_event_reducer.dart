import 'dart:convert';

import 'package:ui/features/home/pages/chat/mixins/agent_stream_handler.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_meta.dart';

class CodexReduceResult {
  const CodexReduceResult({
    required this.handled,
    this.method,
    this.threadId,
    this.turnId,
    this.requestId,
  });

  final bool handled;
  final String? method;
  final String? threadId;
  final String? turnId;
  final Object? requestId;
}

class CodexEventReducer {
  const CodexEventReducer();

  CodexReduceResult reduce({
    required ChatConversationRuntimeState runtime,
    required Map<String, dynamic> event,
  }) {
    final message = _asStringMap(event['message']) ?? event;
    final method = _string(message['method']) ?? _string(event['method']);
    if (method == null || method.isEmpty) {
      return const CodexReduceResult(handled: false);
    }

    final params =
        _asStringMap(message['params']) ??
        _asStringMap(event['params']) ??
        const <String, dynamic>{};
    final threadId = _firstString([
      event['threadId'],
      params['threadId'],
      params['thread_id'],
      _asStringMap(params['thread'])?['id'],
    ]);
    final turnId = _firstString([
      event['turnId'],
      params['turnId'],
      params['turn_id'],
      _asStringMap(params['turn'])?['id'],
    ]);
    final itemId = _firstString([
      params['itemId'],
      params['item_id'],
      _asStringMap(params['item'])?['id'],
      params['id'],
    ]);
    final parentTaskId =
        _firstString([turnId, itemId, threadId]) ??
        'codex-${runtime.conversationId}';

    if (method == 'turn/started') {
      _touchActiveTurn(runtime, parentTaskId);
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'turn/completed' || method == 'thread/closed') {
      _completeTurn(runtime, parentTaskId);
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'thread/started' || method == 'thread/status/changed') {
      final status = _statusType([
        params['status'],
        params['state'],
        _asStringMap(params['thread'])?['status'],
        _asStringMap(params['thread'])?['state'],
      ]);
      if (_statusIsActive(status)) {
        _touchActiveTurn(runtime, parentTaskId);
      } else if (method == 'thread/status/changed' &&
          _statusIsInactive(status)) {
        final taskId =
            turnId ??
            runtime.currentDispatchTaskId ??
            runtime.lastAgentTaskId ??
            parentTaskId;
        _completeTurn(
          runtime,
          taskId,
          appendCancelIfEmpty: _statusIsCancelled(status),
        );
      }
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/started') {
      final item = _asStringMap(params['item']) ?? params;
      final itemType = _string(item['type']) ?? '';
      final startedItemId =
          _firstString([item['id'], params['itemId'], params['id']]) ??
          parentTaskId;
      _touchActiveTurn(runtime, parentTaskId);
      if (itemType == 'reasoning') {
        final thinkingEntryId = '$startedItemId-codex-thinking';
        final text =
            _extractText(item['text']) ??
            _extractText(item['summary']) ??
            _extractText(item['content']) ??
            '';
        _upsertThinkingCard(
          runtime,
          taskId: parentTaskId,
          cardId: thinkingEntryId,
          thinkingContent: text,
          isLoading: true,
          stage: ThinkingStage.thinking.value,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: thinkingEntryId,
            kind: 'thinking_snapshot',
          ),
        );
      } else if (itemType == 'agentMessage') {
        final text = _extractText(item['text']) ?? '';
        if (text.isNotEmpty) {
          _appendAssistantText(
            runtime,
            parentTaskId: parentTaskId,
            entryId: '$startedItemId-codex-agent',
            delta: text,
            isFinal: false,
          );
        }
      } else if (itemType == 'commandExecution' || itemType == 'fileChange') {
        final cardId =
            '$startedItemId-codex-${itemType == 'commandExecution' ? 'command' : 'file'}';
        _upsertToolCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          toolType: itemType == 'commandExecution' ? 'terminal' : 'file',
          title: itemType == 'commandExecution'
              ? _commandTitle(item)
              : _fileChangeTitle(item),
          status: 'running',
          summary: _extractText(item['summary']) ?? '',
          progress: _extractText(item['status']) ?? '',
          raw: item,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: 'tool_started',
          ),
        );
      } else if (itemType.contains('requestApproval')) {
        final cardId = '$startedItemId-codex-approval';
        _upsertCodexRequestCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          requestId: params['requestId'] ?? message['id'],
          requestKind: 'approval',
          title: _approvalTitle(itemType, item),
          detail: _approvalDetail(item),
          params: item,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: 'permission_required',
          ),
        );
      } else if (itemType == 'tool' || itemType == 'mcpToolCall') {
        final cardId = '$startedItemId-codex-tool';
        _upsertToolCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          toolType: _string(item['toolType']) ?? 'tool',
          title: _genericToolTitle(item),
          status: 'running',
          summary: _extractText(item['summary']) ?? '',
          progress: _extractText(item['progress']) ?? '',
          raw: item,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: 'tool_started',
          ),
        );
      }
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: params['requestId'] ?? message['id'],
      );
    }

    if (method == 'item/agentMessage/delta') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['text']) ??
          _extractText(params['message']) ??
          '';
      if (delta.isNotEmpty) {
        final entryId = '${itemId ?? parentTaskId}-codex-agent';
        _appendAssistantText(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          delta: delta,
          isFinal: false,
        );
      }
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (_isReasoningMethod(method)) {
      final text =
          _extractText(params['delta']) ??
          _extractText(params['text']) ??
          _extractText(params['summary']) ??
          _extractText(params['part']) ??
          '';
      if (text.isNotEmpty) {
        final entryId = '${itemId ?? parentTaskId}-codex-thinking';
        _appendThinking(
          runtime,
          parentTaskId: parentTaskId,
          cardId: entryId,
          delta: text,
        );
      }
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/plan/delta' || method == 'turn/plan/updated') {
      final text =
          _extractText(params['delta']) ??
          _extractText(params['plan']) ??
          _extractText(params['text']) ??
          '';
      final cardId = '${itemId ?? parentTaskId}-codex-plan';
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'plan',
        title: 'Codex plan',
        status: 'running',
        summary: text,
        progress: text,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/commandExecution/outputDelta' ||
        method == 'item/commandExecution/terminalInteraction') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['output']) ??
          _extractText(params['text']) ??
          '';
      final cardId = '${itemId ?? parentTaskId}-codex-command';
      _appendToolOutput(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'terminal',
        title: _commandTitle(params),
        outputDelta: delta,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/fileChange/outputDelta' ||
        method == 'turn/diff/updated') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['output']) ??
          _extractText(params['text']) ??
          '';
      final cardId = '${itemId ?? parentTaskId}-codex-file';
      _appendToolOutput(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'file',
        title: _fileChangeTitle(params),
        outputDelta: delta,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method.endsWith('requestApproval')) {
      final requestId = message['id'];
      final cardId = '${requestId ?? itemId ?? parentTaskId}-codex-approval';
      _upsertCodexRequestCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        requestId: requestId,
        requestKind: 'approval',
        title: _approvalTitle(method, params),
        detail: _approvalDetail(params),
        params: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'permission_required',
        ),
      );
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: requestId,
      );
    }

    if (method == 'item/tool/requestUserInput') {
      final requestId = message['id'];
      final question = _firstQuestion(params);
      final cardId = '${requestId ?? itemId ?? parentTaskId}-codex-user-input';
      _upsertCodexRequestCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        requestId: requestId,
        requestKind: 'user_input',
        title: question.title,
        detail: question.detail,
        questionId: question.id,
        params: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'clarify_required',
        ),
      );
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: requestId,
      );
    }

    if (method == 'item/completed') {
      _completeItem(runtime, parentTaskId, itemId, params);
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'account/updated' ||
        method == 'account/login/completed' ||
        method == 'account/rateLimits/updated' ||
        method == 'account/read') {
      final cardId = '$parentTaskId-codex-account';
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'account',
        title: method,
        status: 'success',
        summary: _accountSummary(params),
        progress: _accountSummary(params),
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_completed',
          isFinal: true,
        ),
        touchTurn: false,
      );
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'codex/stderr' || method == 'codex/parseError') {
      final removedStaleCard = _removeCodexDebugStatusCards(runtime);
      return CodexReduceResult(
        handled: removedStaleCard,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'error') {
      final detail =
          _extractText(params['message']) ??
          _extractText(params['error']) ??
          _safeJson(params);
      final cardId = '$parentTaskId-codex-status';
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'status',
        title: method,
        status: 'error',
        summary: detail,
        progress: detail,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'error',
          isFinal: true,
        ),
        touchTurn: false,
      );
      // codex app-server emits the top-level `error` notification when a
      // turn fails terminally (network, rate-limit, server error). When
      // willRetry=false the server will NOT follow up with turn/completed,
      // so we must finalize the turn ourselves — otherwise runtime stays
      // isAiResponding=true forever.
      final willRetry = params['willRetry'] == true;
      if (!willRetry) {
        final completionTaskId =
            turnId ??
            runtime.currentDispatchTaskId ??
            runtime.lastAgentTaskId ??
            parentTaskId;
        _completeTurn(
          runtime,
          completionTaskId,
          appendCancelIfEmpty: false,
        );
      }
      return CodexReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    return CodexReduceResult(
      handled: false,
      method: method,
      threadId: threadId,
      turnId: turnId,
    );
  }

  void _touchActiveTurn(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    runtime.isAiResponding = true;
    runtime.currentDispatchTaskId = parentTaskId;
    runtime.lastAgentTaskId = parentTaskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
  }

  void _appendAssistantText(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required String delta,
    required bool isFinal,
    bool replace = false,
  }) {
    final messageId = entryId;
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    final cachedText = runtime.currentAiMessages[messageId];
    final previous =
        cachedText ?? (index == -1 ? '' : runtime.messages[index].text ?? '');
    final effectiveDelta = replace
        ? delta
        : _deduplicateReplayDelta(
            runtime,
            entryId: messageId,
            existingText: previous,
            delta: delta,
            hasLiveCache: cachedText != null,
          );
    if (effectiveDelta == null) {
      return;
    }
    _touchActiveTurn(runtime, parentTaskId);
    final next = replace ? effectiveDelta : previous + effectiveDelta;
    runtime.codexReplayDeltaOffsets.remove(messageId);
    runtime.currentAiMessages[messageId] = next;
    if (next.isEmpty && index == -1) {
      return;
    }
    final existing = index == -1 ? null : runtime.messages[index];
    final streamMeta = _streamMeta(
      runtime,
      parentTaskId: parentTaskId,
      entryId: messageId,
      kind: 'text_snapshot',
      isFinal: isFinal,
      existingMessage: existing,
    );
    final content = <String, dynamic>{'text': next, 'id': messageId};
    if (index == -1) {
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: messageId,
          type: 1,
          user: 2,
          content: content,
          streamMeta: streamMeta,
          createAt: DateTime.fromMillisecondsSinceEpoch(
            _startTimeForEntry(runtime, messageId, existingMessage: existing),
          ),
        ),
      );
      return;
    }
    runtime.messages[index] = runtime.messages[index].copyWith(
      content: content,
      isLoading: false,
      isError: false,
      streamMeta: streamMeta,
    );
  }

  void _appendThinking(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String cardId,
    required String delta,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existingContent = index == -1
        ? ''
        : (runtime.messages[index].cardData?['thinkingContent'] ?? '')
              .toString();
    final cachedThinking = runtime.currentThinkingMessages[parentTaskId];
    final baseContent = cachedThinking ?? existingContent;
    final effectiveDelta = _deduplicateReplayDelta(
      runtime,
      entryId: cardId,
      existingText: baseContent,
      delta: delta,
      hasLiveCache: cachedThinking != null,
    );
    if (effectiveDelta == null) {
      return;
    }
    _touchActiveTurn(runtime, parentTaskId);
    runtime.isDeepThinking = true;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    final nextContent = baseContent + effectiveDelta;
    runtime.codexReplayDeltaOffsets.remove(cardId);
    runtime.currentThinkingMessages[parentTaskId] = nextContent;
    runtime.deepThinkingContent = nextContent;
    _upsertThinkingCard(
      runtime,
      taskId: parentTaskId,
      cardId: cardId,
      thinkingContent: nextContent,
      isLoading: true,
      stage: ThinkingStage.thinking.value,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: cardId,
        kind: 'thinking_snapshot',
        existingMessage: index == -1 ? null : runtime.messages[index],
      ),
    );
  }

  void _upsertThinkingCard(
    ChatConversationRuntimeState runtime, {
    required String taskId,
    required String cardId,
    required String thinkingContent,
    required bool isLoading,
    required int stage,
    required Map<String, dynamic> streamMeta,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existing = index == -1 ? null : runtime.messages[index];
    final existingCardData = existing?.cardData ?? const <String, dynamic>{};
    final startTime =
        _asInt(existingCardData['startTime']) ??
        _startTimeForEntry(runtime, cardId, existingMessage: existing);
    final endTime = isLoading
        ? existingCardData['endTime']
        : (existingCardData['endTime'] ??
              DateTime.now().millisecondsSinceEpoch);
    final cardData = <String, dynamic>{
      'type': 'deep_thinking',
      'isLoading': isLoading,
      'thinkingContent': thinkingContent.isNotEmpty
          ? thinkingContent
          : (existingCardData['thinkingContent'] ?? '').toString(),
      'stage': stage,
      'taskID': taskId,
      'cardId': cardId,
      'startTime': startTime,
      'endTime': endTime,
      'isCollapsible': !isLoading,
    };
    final message = ChatMessageModel(
      id: cardId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: streamMeta,
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: streamMeta,
      );
    }
  }

  void _appendToolOutput(
    ChatConversationRuntimeState runtime, {
    required String cardId,
    required String taskId,
    required String toolType,
    required String title,
    required String outputDelta,
    required Map<String, dynamic> raw,
    required Map<String, dynamic> streamMeta,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existingCardData = index == -1
        ? const <String, dynamic>{}
        : runtime.messages[index].cardData ?? const <String, dynamic>{};
    final existingOutput = (existingCardData['terminalOutput'] ?? '')
        .toString();
    final output = _trimTerminalOutput(existingOutput + outputDelta);
    _upsertToolCard(
      runtime,
      cardId: cardId,
      taskId: taskId,
      toolType: toolType,
      title: title,
      status: 'running',
      summary: outputDelta.isNotEmpty ? outputDelta.trim() : title,
      progress: outputDelta,
      terminalOutput: output,
      raw: raw,
      streamMeta: streamMeta,
    );
  }

  void _upsertToolCard(
    ChatConversationRuntimeState runtime, {
    required String cardId,
    required String taskId,
    required String toolType,
    required String title,
    required String status,
    required String summary,
    required String progress,
    required Map<String, dynamic> raw,
    required Map<String, dynamic> streamMeta,
    bool touchTurn = true,
    String terminalOutput = '',
  }) {
    if (touchTurn) {
      _touchActiveTurn(runtime, taskId);
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existing = index == -1 ? null : runtime.messages[index];
    final existingCardData = existing?.cardData ?? const <String, dynamic>{};
    final cardData = <String, dynamic>{
      'type': 'agent_tool_summary',
      'taskId': taskId,
      'toolName': 'codex.$toolType',
      'displayName': title,
      'toolTitle': title,
      'cardId': cardId,
      'toolType': toolType,
      'status': status,
      'summary': summary.isNotEmpty
          ? summary
          : (existingCardData['summary'] ?? '').toString(),
      'progress': progress.isNotEmpty
          ? progress
          : (existingCardData['progress'] ?? '').toString(),
      'argsJson': _safeJson(raw),
      'resultPreviewJson': '',
      'rawResultJson': _safeJson(raw),
      'terminalOutput': terminalOutput.isNotEmpty
          ? terminalOutput
          : (existingCardData['terminalOutput'] ?? '').toString(),
      'terminalOutputDelta': progress,
      'showTerminalOutput': terminalOutput.isNotEmpty || toolType == 'terminal',
      'showRawResult': true,
    };
    final startTime = _startTimeForEntry(
      runtime,
      cardId,
      existingMessage: existing,
    );
    final message = ChatMessageModel(
      id: cardId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: streamMeta,
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: streamMeta,
      );
    }
    runtime.lastAgentToolType = toolType;
  }

  void _upsertCodexRequestCard(
    ChatConversationRuntimeState runtime, {
    required String cardId,
    required String taskId,
    required Object? requestId,
    required String requestKind,
    required String title,
    required String detail,
    required Map<String, dynamic> params,
    required Map<String, dynamic> streamMeta,
    String? questionId,
  }) {
    _touchActiveTurn(runtime, taskId);
    final cardData = <String, dynamic>{
      'type': 'codex_request',
      'taskId': taskId,
      'requestId': requestId,
      'requestKind': requestKind,
      'title': title,
      'detail': detail,
      'questionId': questionId,
      'rawParamsJson': _safeJson(params),
      'status': 'pending',
    };
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existing = index == -1 ? null : runtime.messages[index];
    final startTime = _startTimeForEntry(
      runtime,
      cardId,
      existingMessage: existing,
    );
    final message = ChatMessageModel(
      id: cardId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: streamMeta,
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = runtime.messages[index].copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: streamMeta,
      );
    }
    runtime.isAiResponding = true;
  }

  String? _deduplicateReplayDelta(
    ChatConversationRuntimeState runtime, {
    required String entryId,
    required String existingText,
    required String delta,
    required bool hasLiveCache,
  }) {
    if (delta.isEmpty || hasLiveCache || existingText.isEmpty) {
      runtime.codexReplayDeltaOffsets.remove(entryId);
      return delta;
    }
    final previousOffset = runtime.codexReplayDeltaOffsets[entryId] ?? 0;
    final safeOffset = previousOffset.clamp(0, existingText.length).toInt();
    final remaining = existingText.substring(safeOffset);
    if (!remaining.startsWith(delta)) {
      runtime.codexReplayDeltaOffsets[entryId] = existingText.length;
      return delta;
    }
    final nextOffset = safeOffset + delta.length;
    if (nextOffset >= existingText.length) {
      runtime.codexReplayDeltaOffsets.remove(entryId);
    } else {
      runtime.codexReplayDeltaOffsets[entryId] = nextOffset;
    }
    return null;
  }

  void _completeItem(
    ChatConversationRuntimeState runtime,
    String taskId,
    String? itemId,
    Map<String, dynamic> params,
  ) {
    final item = _asStringMap(params['item']) ?? params;
    final itemType = _string(item['type']) ?? '';
    final text =
        _extractText(item['text']) ??
        _extractText(item['message']) ??
        _extractText(item['content']) ??
        '';
    if (itemType == 'agentMessage') {
      final messageId = '${itemId ?? taskId}-codex-agent';
      final existingText = _assistantTextForEntry(runtime, messageId);
      if (text.isNotEmpty && existingText.isEmpty) {
        _appendAssistantText(
          runtime,
          parentTaskId: taskId,
          entryId: messageId,
          delta: text,
          isFinal: true,
        );
      } else if (text.isNotEmpty && text != existingText) {
        if (text.startsWith(existingText)) {
          _appendAssistantText(
            runtime,
            parentTaskId: taskId,
            entryId: messageId,
            delta: text.substring(existingText.length),
            isFinal: true,
          );
        } else {
          _appendAssistantText(
            runtime,
            parentTaskId: taskId,
            entryId: messageId,
            delta: text,
            isFinal: true,
            replace: true,
          );
        }
      }
      _markAssistantEntryFinal(runtime, taskId, messageId);
      runtime.currentAiMessages.remove('${itemId ?? taskId}-codex-agent');
      runtime.codexReplayDeltaOffsets.remove(messageId);
    }
    if (itemType == 'reasoning') {
      // Keep the thinking card streaming until the entire turn ends.
      // _completeTurn() will call _finalizeThinkingCardsForTask() once
      // turn/completed (or thread/closed/inactive) arrives.
      runtime.codexReplayDeltaOffsets.remove(
        '${itemId ?? taskId}-codex-thinking',
      );
    }
    final completedItemId = itemId ?? taskId;
    for (final suffix in const ['command', 'file', 'plan', 'tool']) {
      _markToolCardComplete(runtime, '$completedItemId-codex-$suffix');
    }
  }

  void _completeTurn(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool appendCancelIfEmpty = true,
  }) {
    final isManualCancel =
        appendCancelIfEmpty &&
        taskId == runtime.currentDispatchTaskId &&
        !_hasVisibleAssistantTextForTask(runtime, taskId);
    if (isManualCancel) {
      _appendAssistantText(
        runtime,
        parentTaskId: taskId,
        entryId: '$taskId-cancelled',
        delta: '任务已取消',
        isFinal: true,
        replace: true,
      );
    }
    runtime.isAiResponding = false;
    runtime.isExecutingTask = false;
    runtime.isCheckingExecutableTask = false;
    runtime.currentDispatchTaskId = null;
    runtime.currentAiMessages.clear();
    runtime.currentThinkingMessages.remove(taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.currentThinkingStage = ThinkingStage.complete.value;
    _markAssistantMessagesFinalForTask(runtime, taskId);
    _finalizeThinkingCardsForTask(runtime, taskId);
    _markToolCardsCompleteForTask(runtime, taskId);
  }

  bool _hasVisibleAssistantTextForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    for (final message in runtime.messages) {
      if (message.type != 1 || message.user != 2) {
        continue;
      }
      if ((message.streamMeta?['parentTaskId'] ?? '').toString() != taskId) {
        continue;
      }
      if (message.streamMeta?['isFinal'] == true) {
        return true;
      }
      if ((message.text ?? '').trim().isNotEmpty) {
        return true;
      }
    }
    return false;
  }

  void _markToolCardComplete(
    ChatConversationRuntimeState runtime,
    String cardId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    final cardData = Map<String, dynamic>.from(existing.cardData ?? const {});
    cardData['status'] = 'success';
    final parentTaskId =
        _string(cardData['taskId']) ??
        _string(existing.streamMeta?['parentTaskId']);
    runtime.messages[index] = existing.copyWith(
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: parentTaskId == null
          ? existing.streamMeta
          : _streamMeta(
              runtime,
              parentTaskId: parentTaskId,
              entryId: cardId,
              kind: 'tool_completed',
              existingMessage: existing,
            ),
    );
  }

  String _assistantTextForEntry(
    ChatConversationRuntimeState runtime,
    String messageId,
  ) {
    final runtimeText = runtime.currentAiMessages[messageId];
    if (runtimeText != null) {
      return runtimeText;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    return index == -1 ? '' : runtime.messages[index].text ?? '';
  }

  void _markAssistantEntryFinal(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
    String messageId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    runtime.messages[index] = existing.copyWith(
      isLoading: false,
      isError: false,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: messageId,
        kind: 'text_snapshot',
        isFinal: true,
        existingMessage: existing,
      ),
    );
  }

  void _markAssistantMessagesFinalForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    for (var index = 0; index < runtime.messages.length; index += 1) {
      final message = runtime.messages[index];
      if (message.type != 1 || message.user != 2) {
        continue;
      }
      if (_string(message.streamMeta?['parentTaskId']) != parentTaskId) {
        continue;
      }
      runtime.messages[index] = message.copyWith(
        isLoading: false,
        isError: false,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: message.id,
          kind: 'text_snapshot',
          isFinal: true,
          existingMessage: message,
        ),
      );
    }
  }

  void _finalizeThinkingCard(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
    String cardId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    final existingCardData = existing.cardData;
    if (existingCardData?['type'] != 'deep_thinking') return;
    final cardData = Map<String, dynamic>.from(existingCardData!);
    final startTime =
        _asInt(cardData['startTime']) ??
        _startTimeForEntry(runtime, cardId, existingMessage: existing);
    cardData['isLoading'] = false;
    cardData['stage'] = ThinkingStage.complete.value;
    cardData['taskID'] = parentTaskId;
    cardData['cardId'] = cardId;
    cardData['startTime'] = startTime;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    cardData['isCollapsible'] = true;
    cardData['thinkingContent'] = (cardData['thinkingContent'] ?? '')
        .toString();
    runtime.messages[index] = existing.copyWith(
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: cardId,
        kind: 'thinking_snapshot',
        isFinal: true,
        existingMessage: existing,
      ),
    );
  }

  void _finalizeThinkingCardsForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    final cardIds = runtime.messages
        .where((message) {
          final cardData = message.cardData;
          if (cardData?['type'] != 'deep_thinking') {
            return false;
          }
          final cardTaskId =
              _string(cardData?['taskID']) ??
              _string(message.streamMeta?['parentTaskId']);
          return cardTaskId == parentTaskId;
        })
        .map((message) => message.id)
        .toList(growable: false);
    for (final cardId in cardIds) {
      _finalizeThinkingCard(runtime, parentTaskId, cardId);
    }
  }

  void _markToolCardsCompleteForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    final cardIds = runtime.messages
        .where((message) {
          final cardData = message.cardData;
          if (cardData?['type'] != 'agent_tool_summary') {
            return false;
          }
          final cardTaskId =
              _string(cardData?['taskId']) ??
              _string(message.streamMeta?['parentTaskId']);
          if (cardTaskId != parentTaskId) {
            return false;
          }
          final status = _string(cardData?['status'])?.toLowerCase();
          return status == null ||
              status == 'running' ||
              status == 'pending' ||
              status == 'progress';
        })
        .map((message) => message.id)
        .toList(growable: false);
    for (final cardId in cardIds) {
      _markToolCardComplete(runtime, cardId);
    }
  }

  Map<String, dynamic> _streamMeta(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required String kind,
    bool isFinal = false,
    ChatMessageModel? existingMessage,
  }) {
    final seq = _sequenceForEntry(
      runtime,
      entryId,
      existingMessage: existingMessage,
    );
    return ensureAgentStreamMessageMeta(
          existingMessage?.streamMeta,
          seq: seq,
          roundIndex: seq,
          kind: kind,
          parentTaskId: parentTaskId,
          entryId: entryId,
          isFinal: isFinal,
        ) ??
        <String, dynamic>{};
  }

  int _sequenceForEntry(
    ChatConversationRuntimeState runtime,
    String entryId, {
    ChatMessageModel? existingMessage,
  }) {
    final key = entryId.trim();
    final cached = runtime.codexEntrySequences[key];
    if (cached != null) {
      return cached;
    }
    final existingSeq = _asInt(existingMessage?.streamMeta?['seq']);
    if (existingSeq != null && existingSeq > 0) {
      runtime.codexEntrySequences[key] = existingSeq;
      if (runtime.codexNextEntrySequence < existingSeq) {
        runtime.codexNextEntrySequence = existingSeq;
      }
      return existingSeq;
    }
    runtime.codexNextEntrySequence += 1;
    runtime.codexEntrySequences[key] = runtime.codexNextEntrySequence;
    return runtime.codexNextEntrySequence;
  }

  int _startTimeForEntry(
    ChatConversationRuntimeState runtime,
    String entryId, {
    ChatMessageModel? existingMessage,
  }) {
    final key = entryId.trim();
    final cached = runtime.codexEntryStartTimes[key];
    if (cached != null) {
      return cached;
    }
    final existingStart =
        _asInt(existingMessage?.cardData?['startTime']) ??
        existingMessage?.createAt.millisecondsSinceEpoch;
    final startTime = existingStart ?? DateTime.now().millisecondsSinceEpoch;
    runtime.codexEntryStartTimes[key] = startTime;
    return startTime;
  }

  bool _removeCodexDebugStatusCards(ChatConversationRuntimeState runtime) {
    final before = runtime.messages.length;
    runtime.messages.removeWhere((message) {
      final cardData = message.cardData;
      if (cardData == null) return false;
      final toolName = _string(cardData['toolName']);
      final title =
          _string(cardData['toolTitle']) ?? _string(cardData['displayName']);
      return toolName == 'codex.status' &&
          (title == 'codex/stderr' || title == 'codex/parseError');
    });
    return runtime.messages.length != before;
  }

  String _approvalTitle(String method, Map<String, dynamic> params) {
    if (method.contains('commandExecution')) {
      return _commandTitle(params);
    }
    if (method.contains('fileChange')) {
      return _fileChangeTitle(params, fallback: 'Codex file approval');
    }
    return 'Codex approval';
  }

  String _approvalDetail(Map<String, dynamic> params) {
    return _extractText(params['reason']) ??
        _extractText(params['description']) ??
        _extractText(params['command']) ??
        _safeJson(params);
  }

  String _commandTitle(Map<String, dynamic> params) {
    final command =
        _extractText(params['command']) ??
        _extractText(_toolArguments(params)['command']) ??
        _extractText(_asStringMap(params['item'])?['command']) ??
        _extractText(params['cmd']);
    if (command == null || command.trim().isEmpty) {
      return 'Codex command';
    }
    return _compactTitle(command, maxLength: 48);
  }

  String _fileChangeTitle(
    Map<String, dynamic> params, {
    String fallback = 'Codex file change',
  }) {
    final args = _toolArguments(params);
    final path = _firstString([
      params['path'],
      params['filePath'],
      params['file_path'],
      params['filename'],
      params['fileName'],
      args['path'],
      args['filePath'],
      args['file_path'],
      args['filename'],
      args['fileName'],
      _firstPathFromList(params['files']),
      _firstPathFromList(params['changes']),
      _firstPathFromList(args['files']),
      _firstPathFromList(args['changes']),
      _asStringMap(params['item'])?['path'],
      _asStringMap(params['item'])?['filePath'],
      _asStringMap(params['item'])?['file_path'],
    ]);
    if (path == null) {
      return fallback;
    }
    final name = _lastPathSegment(path) ?? path;
    return _compactTitle('Edit $name', maxLength: 42);
  }

  String _genericToolTitle(Map<String, dynamic> params) {
    final args = _toolArguments(params);
    final toolName = _firstString([
      params['toolName'],
      params['tool_name'],
      params['name'],
      params['functionName'],
      params['function_name'],
      _asStringMap(params['function'])?['name'],
      _asStringMap(params['tool'])?['name'],
    ]);
    final explicit = _firstString([
      params['toolTitle'],
      params['tool_title'],
      params['displayName'],
      params['display_name'],
      args['toolTitle'],
      args['tool_title'],
      args['displayName'],
      args['display_name'],
    ]);
    if (explicit != null) {
      return _compactTitle(explicit, maxLength: 48);
    }
    final command = _firstString([args['command'], args['cmd'], params['cmd']]);
    if (command != null) {
      return _commandTitle({'command': command});
    }
    final detail = _firstString([
      args['query'],
      args['q'],
      args['url'],
      args['uri'],
      args['path'],
      args['filePath'],
      args['file_path'],
      params['query'],
      params['url'],
      params['path'],
    ]);
    final shortToolName = toolName == null ? null : _shortToolName(toolName);
    if (detail != null) {
      final detailTitle = _looksLikePath(detail)
          ? (_lastPathSegment(detail) ?? detail)
          : detail;
      if (shortToolName != null && shortToolName.isNotEmpty) {
        return _compactTitle('$shortToolName: $detailTitle', maxLength: 48);
      }
      return _compactTitle(detailTitle, maxLength: 48);
    }
    if (shortToolName != null && shortToolName.isNotEmpty) {
      return _compactTitle(shortToolName, maxLength: 48);
    }
    return 'Codex tool';
  }

  Map<String, dynamic> _toolArguments(Map<String, dynamic> params) {
    for (final key in const <String>['arguments', 'args', 'input']) {
      final map = _asStringMap(params[key]);
      if (map != null) {
        return map;
      }
      final text = _string(params[key]);
      if (text == null || text.isEmpty) {
        continue;
      }
      try {
        final decoded = jsonDecode(text);
        if (decoded is Map) {
          return decoded.map((key, value) => MapEntry(key.toString(), value));
        }
      } catch (_) {
        continue;
      }
    }
    final item = _asStringMap(params['item']);
    if (item != null && item != params) {
      return _toolArguments(item);
    }
    return const <String, dynamic>{};
  }

  String? _firstPathFromList(dynamic value) {
    if (value is! List) {
      return null;
    }
    for (final item in value) {
      if (item is String && item.trim().isNotEmpty) {
        return item.trim();
      }
      final map = _asStringMap(item);
      final path = _firstString([
        map?['path'],
        map?['filePath'],
        map?['file_path'],
        map?['filename'],
        map?['fileName'],
      ]);
      if (path != null) {
        return path;
      }
    }
    return null;
  }

  String? _lastPathSegment(String path) {
    final normalized = path.trim().replaceAll(RegExp(r'[/\\]+$'), '');
    if (normalized.isEmpty) {
      return null;
    }
    final parts = normalized
        .split(RegExp(r'[/\\]+'))
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? normalized : parts.last;
  }

  bool _looksLikePath(String value) {
    return value.contains('/') || value.contains('\\');
  }

  String _shortToolName(String value) {
    final normalized = value.trim();
    if (normalized.isEmpty) {
      return '';
    }
    final withoutNamespace = normalized.split(RegExp(r'[./:]')).last;
    final parts = withoutNamespace
        .split('__')
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? withoutNamespace : parts.last;
  }

  String _compactTitle(String value, {required int maxLength}) {
    final normalized = value
        .trim()
        .split('\n')
        .first
        .trim()
        .replaceAll(RegExp(r'\s+'), ' ');
    if (normalized.length <= maxLength) {
      return normalized;
    }
    return '${normalized.substring(0, maxLength)}...';
  }

  _CodexQuestion _firstQuestion(Map<String, dynamic> params) {
    final questions = params['questions'];
    if (questions is List && questions.isNotEmpty) {
      final first = _asStringMap(questions.first);
      if (first != null) {
        final id =
            _string(first['id']) ?? _string(first['questionId']) ?? 'answer';
        final title =
            _string(first['label']) ??
            _string(first['title']) ??
            _string(first['question']) ??
            'Codex needs input';
        final detail =
            _string(first['description']) ??
            _string(first['placeholder']) ??
            title;
        return _CodexQuestion(id: id, title: title, detail: detail);
      }
    }
    final id =
        _string(params['questionId']) ?? _string(params['id']) ?? 'answer';
    final title =
        _string(params['question']) ??
        _string(params['title']) ??
        'Codex needs input';
    final detail = _string(params['description']) ?? title;
    return _CodexQuestion(id: id, title: title, detail: detail);
  }
}

class _CodexQuestion {
  const _CodexQuestion({
    required this.id,
    required this.title,
    required this.detail,
  });

  final String id;
  final String title;
  final String detail;
}

bool _isReasoningMethod(String method) {
  return method == 'item/reasoning/summaryPartAdded' ||
      method == 'item/reasoning/summaryTextDelta' ||
      method == 'item/reasoning/textDelta';
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is! Map) return null;
  return value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue));
}

String? _extractText(dynamic value) {
  if (value == null) return null;
  if (value is String) return value;
  if (value is num || value is bool) return value.toString();
  final map = _asStringMap(value);
  if (map != null) {
    return _firstString([
      map['text'],
      map['content'],
      map['message'],
      map['value'],
      map['delta'],
      map['summary'],
    ]);
  }
  if (value is List) {
    return value.map(_extractText).whereType<String>().join();
  }
  return value.toString();
}

String? _string(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

String? _firstString(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _extractText(value)?.trim();
    if (text != null && text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

String? _statusType(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _statusText(value);
    if (text != null && text.isNotEmpty) {
      return _normalizeStatus(text);
    }
  }
  return null;
}

String? _statusText(dynamic value) {
  if (value == null) return null;
  if (value is String || value is num || value is bool) {
    return value.toString();
  }
  final map = _asStringMap(value);
  if (map != null) {
    return _firstString([
      map['type'],
      map['status'],
      map['state'],
      map['value'],
      map['name'],
    ]);
  }
  return null;
}

String _normalizeStatus(String status) =>
    status.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '');

bool _statusIsActive(String? status) {
  return status == 'running' ||
      status == 'active' ||
      status == 'busy' ||
      status == 'inprogress' ||
      status == 'inflight' ||
      status == 'executing';
}

bool _statusIsInactive(String? status) {
  return status == 'idle' ||
      status == 'closed' ||
      status == 'completed' ||
      status == 'complete' ||
      status == 'notloaded' ||
      status == 'systemerror' ||
      status == 'failed' ||
      status == 'cancelled' ||
      status == 'canceled' ||
      status == 'interrupted';
}

bool _statusIsCancelled(String? status) {
  return status == 'cancelled' ||
      status == 'canceled' ||
      status == 'interrupted';
}

int? _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String _safeJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value?.toString() ?? '';
  }
}

String _accountSummary(Map<String, dynamic> params) {
  final account = _asStringMap(params['account']) ?? params;
  final email = _string(account['email']);
  final plan = _string(account['planType']) ?? _string(account['plan_type']);
  final type = _string(account['type']);
  final parts = <String>[
    if (email != null) email,
    if (plan != null) plan,
    if (type != null && type != 'chatgpt') type,
  ];
  return parts.isEmpty ? _safeJson(params) : parts.join(' / ');
}

String _trimTerminalOutput(String value) {
  const maxChars = 64 * 1024;
  const maxLines = 600;
  var text = value;
  if (text.length > maxChars) {
    text = text.substring(text.length - maxChars);
  }
  final lines = text.split('\n');
  if (lines.length > maxLines) {
    text = lines.sublist(lines.length - maxLines).join('\n');
  }
  return text;
}
